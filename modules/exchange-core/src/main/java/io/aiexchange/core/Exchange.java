package io.aiexchange.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import static java.lang.Math.*;
import static io.aiexchange.core.ExchangeCommand.*;
import static io.aiexchange.core.ExchangeEvent.*;
import static io.aiexchange.core.ExchangeSnapshot.*;

/** Single-thread-confined AIX matching and accounting state. No persistence is performed here. */
public final class Exchange {
    public static final String SYMBOL = "AIX";
    public static final String ENGINE_VERSION = "matching-v1";
    public static final int MAX_OPEN_ORDERS = 20_000;
    public record Endowment(long cash, long shares) {}

    /** Only this engine can issue plans; callers cannot inject arbitrary event batches. */
    public static final class Plan {
        private final Exchange owner;
        private final long baseSequence;
        private final long commandSequence;
        private final List<ExchangeEvent> events;

        private Plan(Exchange owner, long baseSequence, long commandSequence, List<ExchangeEvent> events) {
            this.owner = owner;
            this.baseSequence = baseSequence;
            this.commandSequence = commandSequence;
            this.events = List.copyOf(events);
        }
        public long baseSequence() { return baseSequence; }
        public long commandSequence() { return commandSequence; }
        public List<ExchangeEvent> events() { return events; }
    }

    private static final class Account {
        long availableCash;
        long reservedCash;
        long availableShares;
        long reservedShares;
        Account(Endowment initial) { availableCash = initial.cash(); availableShares = initial.shares(); }
    }
    private record LiveOrder(PlaceOrder order, long remaining, long priority) {
        OrderView view() { return new OrderView(order, remaining, priority); }
    }

    private final NavigableMap<Long, Account> accounts = new TreeMap<>();
    private final NavigableMap<Long, LiveOrder> orders = new TreeMap<>();
    private final NavigableMap<Long, LinkedHashSet<Long>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, LinkedHashSet<Long>> asks = new TreeMap<>();
    private final int maxOpenOrders;
    private long commandSequence;
    private long eventSequence;
    private long nextOrderId = 1;
    private boolean failed;

    public Exchange(Map<Long, Endowment> traders) { this(traders, MAX_OPEN_ORDERS); }

    public Exchange(Map<Long, Endowment> traders, int maxOpenOrders) {
        if (traders == null || traders.isEmpty() || traders.size() > 500)
            throw new IllegalArgumentException("Genesis requires 1 to 500 traders");
        if (maxOpenOrders < 1 || maxOpenOrders > MAX_OPEN_ORDERS)
            throw new IllegalArgumentException("Open-order limit must be between 1 and 20000");
        this.maxOpenOrders = maxOpenOrders;
        long totalCash = 0;
        long totalShares = 0;
        for (var entry : traders.entrySet()) {
            var id = entry.getKey();
            var initial = entry.getValue();
            if (id == null || id <= 0 || initial == null || initial.cash() < 0 || initial.shares() < 0)
                throw new IllegalArgumentException("Trader IDs must be positive and endowments nonnegative");
            try {
                totalCash = addExact(totalCash, initial.cash());
                totalShares = addExact(totalShares, initial.shares());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Aggregate endowments exceed long range", overflow);
            }
            accounts.put(id, new Account(initial));
        }
    }

    public Plan plan(ExchangeCommand command) {
        ensureHealthy();
        if (command == null) throw new IllegalArgumentException("Command is required");
        long sequence = incrementExact(commandSequence);
        var payloads = switch (command) {
            case PlaceOrder order -> planPlace(order);
            case CancelOrder cancel -> planCancel(cancel);
        };
        var events = new ArrayList<ExchangeEvent>(payloads.size());
        long event = eventSequence;
        for (var payload : payloads) events.add(new ExchangeEvent(event = incrementExact(event), sequence, payload));
        return new Plan(this, commandSequence, sequence, events);
    }

    public void apply(Plan plan) {
        ensureHealthy();
        if (plan == null || plan.owner != this) throw new IllegalArgumentException("Plan belongs to another engine");
        if (plan.baseSequence != commandSequence) throw new IllegalStateException("Plan is stale or already applied");
        try {
            for (var event : plan.events) {
                applyPayload(event.payload());
                eventSequence = event.sequence();
            }
            commandSequence = plan.commandSequence;
        } catch (RuntimeException failure) {
            failed = true;
            throw new IllegalStateException("Reducer failed; this engine cannot accept further commands", failure);
        }
    }

    public Plan execute(ExchangeCommand command) {
        var plan = plan(command);
        apply(plan);
        return plan;
    }

    /** Isolated event reducer: recorded input cannot modify a live Exchange. */
    public static final class Replayer {
        private final Exchange state;
        private Replayer(Map<Long, Endowment> traders, int limit) { state = new Exchange(traders, limit); }
        public void accept(ExchangeCommand command, List<ExchangeEvent> events) { state.replayBatch(command, events); }
        public ExchangeSnapshot snapshot() { return state.snapshot(); }
    }

    public static Replayer replayer(Map<Long, Endowment> traders, int maxOpenOrders) {
        return new Replayer(traders, maxOpenOrders);
    }

    private void replayBatch(ExchangeCommand command, List<ExchangeEvent> recorded) {
        ensureHealthy();
        try {
            requireReplay(command != null && recorded != null, "Missing replay input");
            var events = List.copyOf(recorded);
            requireReplay(!events.isEmpty() && events.size() <= MAX_OPEN_ORDERS + 2, "Invalid batch length");
            long nextCommand = incrementExact(commandSequence);
            long nextEvent = eventSequence;
            for (var event : events) {
                requireReplay(event.payload() != null && event.commandSequence() == nextCommand
                        && event.sequence() == (nextEvent = incrementExact(nextEvent)), "Replay sequence gap");
            }
            switch (command) {
                case PlaceOrder place -> replayPlace(place, events);
                case CancelOrder cancel -> {
                    requireReplay(events.size() == 1 && events.getFirst().payload().equals(planCancel(cancel).getFirst()),
                            "Recorded cancellation disagrees with state/input");
                    applyPayload(events.getFirst().payload());
                }
            }
            commandSequence = nextCommand;
            eventSequence = nextEvent;
        } catch (RuntimeException invalid) {
            failed = true;
            throw new IllegalArgumentException("Invalid recorded event batch", invalid);
        }
    }

    private void replayPlace(PlaceOrder input, List<ExchangeEvent> events) {
        var first = events.getFirst().payload();
        if (first instanceof OrderRejected rejected) {
            var reason = validate(input);
            if (reason == null && rejected.reason() == RejectionReason.OPEN_ORDER_LIMIT
                    && input.timeInForce() == TimeInForce.GTC && orders.size() >= maxOpenOrders && !crossesBest(input))
                reason = RejectionReason.OPEN_ORDER_LIMIT;
            requireReplay(reason != null && events.size() == 1 && rejected(input, reason).getFirst().equals(rejected),
                    "Invalid recorded order rejection");
            applyPayload(first);
            return;
        }
        requireReplay(validate(input) == null && first.equals(new OrderAccepted(input)), "Invalid recorded order acceptance");
        applyPayload(first);
        boolean terminal = false;
        for (int i = 1; i < events.size(); i++) {
            var payload = events.get(i).payload();
            requireReplay(!terminal && orders.containsKey(input.orderId()), "Events after incoming order completed");
            switch (payload) {
                case TradeExecuted trade -> {
                    var maker = bestContra(input);
                    var incoming = orders.get(input.orderId());
                    requireReplay(maker != null && crossesBest(input) && maker.order().traderId() != input.traderId()
                            && trade.makerOrderId() == maker.order().orderId() && trade.takerOrderId() == input.orderId()
                            && trade.price() == maker.order().price()
                            && trade.quantity() == min(maker.remaining(), incoming.remaining()), "Invalid recorded trade");
                    applyPayload(trade);
                }
                case OrderRested rested -> {
                    requireReplay(i == events.size() - 1 && rested.orderId() == input.orderId()
                            && input.timeInForce() == TimeInForce.GTC && !crossesBest(input)
                            && orders.size() <= maxOpenOrders, "Invalid resting remainder");
                    applyPayload(rested);
                    terminal = true;
                }
                case IncomingRemainderCancelled cancelled -> {
                    var maker = bestContra(input);
                    boolean selfTrade = crossesBest(input) && maker.order().traderId() == input.traderId();
                    boolean validReason = cancelled.reason() == CancellationReason.SELF_TRADE_PREVENTED ? selfTrade
                            : cancelled.reason() == CancellationReason.IOC_REMAINDER && input.timeInForce() == TimeInForce.IOC && !crossesBest(input);
                    requireReplay(i == events.size() - 1 && cancelled.orderId() == input.orderId() && validReason,
                            "Invalid incoming cancellation");
                    applyPayload(cancelled);
                    terminal = true;
                }
                default -> throw new IllegalArgumentException("Unexpected placement event");
            }
        }
        requireReplay(!orders.containsKey(input.orderId()) || terminal, "Incoming order has no terminal event");
    }

    private LiveOrder bestContra(PlaceOrder input) {
        var contra = input.side() == Side.BUY ? asks : bids;
        return contra.isEmpty() ? null : orders.get(contra.firstEntry().getValue().getFirst());
    }

    private boolean crossesBest(PlaceOrder input) {
        var maker = bestContra(input);
        return maker != null && (input.side() == Side.BUY ? input.price() >= maker.order().price() : input.price() <= maker.order().price());
    }

    private static void requireReplay(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private List<Payload> planPlace(PlaceOrder order) {
        var invalid = validate(order);
        if (invalid != null) return rejected(order, invalid);
        var events = new ArrayList<Payload>();
        events.add(new OrderAccepted(order));
        long remaining = order.quantity();
        int filledMakers = 0;
        boolean selfTrade = false;
        var contra = order.side() == Side.BUY ? asks : bids;
        matching:
        for (var level : contra.entrySet()) {
            boolean crosses = order.side() == Side.BUY ? order.price() >= level.getKey() : order.price() <= level.getKey();
            if (!crosses) break;
            for (var id : level.getValue()) {
                var maker = orders.get(id);
                if (maker.order().traderId() == order.traderId()) { selfTrade = true; break matching; }
                long quantity = min(remaining, maker.remaining());
                events.add(new TradeExecuted(id, order.orderId(), maker.order().price(), quantity));
                remaining = subtractExact(remaining, quantity);
                if (quantity == maker.remaining()) filledMakers++;
                if (remaining == 0) break matching;
            }
        }
        if (remaining > 0) {
            if (selfTrade) {
                events.add(new IncomingRemainderCancelled(order.orderId(), CancellationReason.SELF_TRADE_PREVENTED));
            } else if (order.timeInForce() == TimeInForce.IOC) {
                events.add(new IncomingRemainderCancelled(order.orderId(), CancellationReason.IOC_REMAINDER));
            } else {
                if (orders.size() - filledMakers >= maxOpenOrders) return rejected(order, RejectionReason.OPEN_ORDER_LIMIT);
                events.add(new OrderRested(order.orderId()));
            }
        }
        return events;
    }

    private RejectionReason validate(PlaceOrder order) {
        if (order.orderId() <= 0) return RejectionReason.INVALID_ORDER_ID;
        if (order.orderId() < nextOrderId) return RejectionReason.DUPLICATE_ORDER_ID;
        if (order.orderId() > nextOrderId) return RejectionReason.OUT_OF_SEQUENCE_ORDER_ID;
        if (nextOrderId == Long.MAX_VALUE) return RejectionReason.ORDER_IDS_EXHAUSTED;
        if (!accounts.containsKey(order.traderId())) return RejectionReason.UNKNOWN_TRADER;
        if (!SYMBOL.equals(order.symbol())) return RejectionReason.UNKNOWN_SYMBOL;
        if (order.side() == null || order.timeInForce() == null || order.price() <= 0 || order.quantity() <= 0)
            return RejectionReason.INVALID_ORDER;
        long notional;
        try { notional = multiplyExact(order.price(), order.quantity()); }
        catch (ArithmeticException overflow) { return RejectionReason.NOTIONAL_OVERFLOW; }
        var account = accounts.get(order.traderId());
        if (order.side() == Side.BUY && account.availableCash < notional) return RejectionReason.INSUFFICIENT_CASH;
        if (order.side() == Side.SELL && account.availableShares < order.quantity()) return RejectionReason.INSUFFICIENT_SHARES;
        return null;
    }

    private List<Payload> rejected(PlaceOrder order, RejectionReason reason) {
        return List.of(new OrderRejected(order.orderId(), reason,
                order.orderId() == nextOrderId && nextOrderId != Long.MAX_VALUE));
    }

    private List<Payload> planCancel(CancelOrder cancel) {
        RejectionReason reason = null;
        if (!accounts.containsKey(cancel.traderId())) reason = RejectionReason.UNKNOWN_TRADER;
        else if (!SYMBOL.equals(cancel.symbol())) reason = RejectionReason.UNKNOWN_SYMBOL;
        else {
            var order = orders.get(cancel.orderId());
            if (order == null) reason = RejectionReason.ORDER_NOT_OPEN;
            else if (order.order().traderId() != cancel.traderId()) reason = RejectionReason.NOT_OWNER;
        }
        return reason == null ? List.of(new OrderCancelled(cancel.orderId(), CancellationReason.USER_CANCELLED))
                : List.of(new CancelRejected(cancel.orderId(), reason));
    }

    private void applyPayload(Payload payload) {
        switch (payload) {
            case OrderAccepted accepted -> {
                var order = accepted.order();
                var account = accounts.get(order.traderId());
                if (order.side() == Side.BUY) {
                    long reserved = multiplyExact(order.price(), order.quantity());
                    account.availableCash = subtractExact(account.availableCash, reserved);
                    account.reservedCash = addExact(account.reservedCash, reserved);
                } else {
                    account.availableShares = subtractExact(account.availableShares, order.quantity());
                    account.reservedShares = addExact(account.reservedShares, order.quantity());
                }
                orders.put(order.orderId(), new LiveOrder(order, order.quantity(), incrementExact(commandSequence)));
                nextOrderId = incrementExact(nextOrderId);
            }
            case OrderRejected rejected -> {
                if (rejected.consumesOrderId()) nextOrderId = incrementExact(nextOrderId);
            }
            case TradeExecuted trade -> settle(trade);
            case OrderRested rested -> {
                var order = orders.get(rested.orderId()).order();
                book(order.side()).computeIfAbsent(order.price(), ignored -> new LinkedHashSet<>()).add(order.orderId());
            }
            case OrderCancelled cancelled -> release(cancelled.orderId());
            case IncomingRemainderCancelled cancelled -> release(cancelled.orderId());
            case CancelRejected ignored -> { }
        }
    }

    private void settle(TradeExecuted trade) {
        var maker = orders.get(trade.makerOrderId());
        var taker = orders.get(trade.takerOrderId());
        var buy = maker.order().side() == Side.BUY ? maker : taker;
        var sell = maker.order().side() == Side.SELL ? maker : taker;
        var buyer = accounts.get(buy.order().traderId());
        var seller = accounts.get(sell.order().traderId());
        long quantity = trade.quantity();
        buyer.reservedCash = subtractExact(buyer.reservedCash, multiplyExact(buy.order().price(), quantity));
        buyer.availableCash = addExact(buyer.availableCash, multiplyExact(subtractExact(buy.order().price(), trade.price()), quantity));
        buyer.availableShares = addExact(buyer.availableShares, quantity);
        seller.reservedShares = subtractExact(seller.reservedShares, quantity);
        seller.availableCash = addExact(seller.availableCash, multiplyExact(trade.price(), quantity));
        decrease(maker, quantity);
        decrease(taker, quantity);
    }

    private void decrease(LiveOrder order, long quantity) {
        long remaining = subtractExact(order.remaining(), quantity);
        if (remaining == 0) remove(order);
        else orders.put(order.order().orderId(), new LiveOrder(order.order(), remaining, order.priority()));
    }

    private void release(long id) {
        var order = orders.get(id);
        var account = accounts.get(order.order().traderId());
        if (order.order().side() == Side.BUY) {
            long reserved = multiplyExact(order.order().price(), order.remaining());
            account.reservedCash = subtractExact(account.reservedCash, reserved);
            account.availableCash = addExact(account.availableCash, reserved);
        } else {
            account.reservedShares = subtractExact(account.reservedShares, order.remaining());
            account.availableShares = addExact(account.availableShares, order.remaining());
        }
        remove(order);
    }

    private void remove(LiveOrder order) {
        orders.remove(order.order().orderId());
        var book = book(order.order().side());
        var level = book.get(order.order().price());
        if (level != null) {
            level.remove(order.order().orderId());
            if (level.isEmpty()) book.remove(order.order().price());
        }
    }

    private NavigableMap<Long, LinkedHashSet<Long>> book(Side side) { return side == Side.BUY ? bids : asks; }

    public long nextOrderId() { return nextOrderId; }

    public boolean isOrderOpen(long orderId) { return orders.containsKey(orderId); }

    public ExchangeSnapshot snapshot() {
        ensureHealthy();
        var accountViews = accounts.entrySet().stream().map(e -> new AccountView(e.getKey(),
                e.getValue().availableCash, e.getValue().reservedCash,
                e.getValue().availableShares, e.getValue().reservedShares)).toList();
        return new ExchangeSnapshot(commandSequence, eventSequence, nextOrderId, accountViews,
                orders.values().stream().map(LiveOrder::view).toList(), levels(bids), levels(asks));
    }

    private List<PriceLevel> levels(NavigableMap<Long, LinkedHashSet<Long>> book) {
        var result = new ArrayList<PriceLevel>(book.size());
        for (var entry : book.entrySet()) {
            var resting = entry.getValue().stream().map(id -> orders.get(id).view()).toList();
            long quantity = 0;
            for (var order : resting) quantity = addExact(quantity, order.remaining());
            result.add(new PriceLevel(entry.getKey(), quantity, resting));
        }
        return result;
    }

    private void ensureHealthy() {
        if (failed) throw new IllegalStateException("Engine failed; discard this state and recover from trusted inputs");
    }
}
