package io.aiexchange.simulation;

import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeCommand;
import io.aiexchange.core.ExchangeCommand.CancelOrder;
import io.aiexchange.core.ExchangeCommand.PlaceOrder;
import io.aiexchange.core.ExchangeCommand.Side;
import io.aiexchange.core.ExchangeCommand.TimeInForce;
import io.aiexchange.core.ExchangeEvent.OrderRested;
import io.aiexchange.core.ExchangeEvent.TradeExecuted;
import io.aiexchange.core.ExchangeSnapshot;
import io.aiexchange.journal.JournaledExchange;
import io.aiexchange.journal.RunManifest;
import java.io.IOException;
import java.nio.file.Path;

/** Single-thread-confined, autonomous AIX run. Each advance stops at a message boundary. */
public final class SimulationRun implements AutoCloseable {
    public enum Status { READY, EVENT_LIMIT, FAILED, CLOSED }

    /** Reference price begins at the scenario price and follows committed trade prints. */
    public record Progress(Status status, long simTimeNanos, long processedEvents,
                           int pendingMessages, long tradeCount, long referencePrice) {}

    private sealed interface Message permits Wake, Arrival {}
    private record Wake(Trader trader) implements Message {}
    private record Arrival(Trader trader, Trader.Intent intent) implements Message {}

    private final Scenario scenario;
    private final VirtualScheduler<Message> scheduler;
    private final JournaledExchange exchange;
    private Status status = Status.READY;
    private long processed;
    private long trades;
    private long reference;
    private long previous;
    private boolean closed;

    public static SimulationRun create(Path journal, String runId, Scenario scenario, long seed) throws IOException {
        var scheduler = new VirtualScheduler<Message>(scenario.maxScheduled());
        for (long id = 1; id <= scenario.agentCount(); id++) {
            var trader = new Trader(id, seed);
            scheduler.schedule(trader.firstWake(scenario), new Wake(trader));
        }
        var metadata = new RunManifest.Simulation(Trader.STRATEGY_VERSION, AgentRandom.VERSION, scenario.parameters());
        var manifest = new RunManifest(runId, seed, scenario.endowments(), scenario.maxOpenOrders(), metadata);
        return new SimulationRun(scenario, scheduler, JournaledExchange.create(journal, manifest, scenario.journalMaxBytes()));
    }

    private SimulationRun(Scenario scenario, VirtualScheduler<Message> scheduler, JournaledExchange exchange) {
        this.scenario = scenario;
        this.scheduler = scheduler;
        this.exchange = exchange;
        reference = previous = scenario.initialPrice();
    }

    public Progress advance(long events) throws IOException {
        if (events < 1) throw new IllegalArgumentException("Advance requires a positive event count");
        if (closed || status == Status.FAILED) throw new IllegalStateException("Simulation is closed or failed");
        long stop = processed + Math.min(events, scenario.maxEvents() - processed);
        try {
            while (processed < stop) {
                switch (scheduler.poll().message()) {
                    case Wake wake -> wake(wake.trader());
                    case Arrival arrival -> arrive(arrival.trader(), arrival.intent());
                }
                processed++;
            }
            if (processed == scenario.maxEvents()) status = Status.EVENT_LIMIT;
            return progress();
        } catch (IOException | RuntimeException failure) {
            status = Status.FAILED;
            exchange.abort();
            throw failure;
        }
    }

    public Progress progress() {
        return new Progress(status, scheduler.now(), processed, scheduler.size(), trades, reference);
    }

    public ExchangeSnapshot snapshot() { return exchange.snapshot(); }

    private void wake(Trader trader) {
        var intent = trader.intent(scenario, reference, previous);
        if (intent == null) {
            scheduleWake(trader);
        } else {
            scheduler.schedule(scheduler.now() + scenario.latencyNanos(), new Arrival(trader, intent));
        }
    }

    private void arrive(Trader trader, Trader.Intent intent) throws IOException {
        switch (intent) {
            case Trader.Take take -> execute(order(trader, take.side(), take.limitPrice(), TimeInForce.IOC));
            case Trader.Quote quote -> refreshQuotes(trader, quote);
        }
        scheduleWake(trader);
    }

    private void scheduleWake(Trader trader) {
        scheduler.schedule(scheduler.now() + trader.wakeDelay(scenario), new Wake(trader));
    }

    private void refreshQuotes(Trader trader, Trader.Quote quote) throws IOException {
        trader.bid = retainOrCancel(trader, trader.bid, quote.bidPrice());
        trader.ask = retainOrCancel(trader, trader.ask, quote.askPrice());
        if (trader.bid == null) trader.bid = placeQuote(trader, Side.BUY, quote.bidPrice());
        if (trader.ask == null) trader.ask = placeQuote(trader, Side.SELL, quote.askPrice());
    }

    private Trader.RestingQuote retainOrCancel(Trader trader, Trader.RestingQuote resting, long price) throws IOException {
        if (resting == null || !exchange.isOrderOpen(resting.orderId())) return null;
        if (resting.price() == price) return resting;
        execute(new CancelOrder(trader.id, Exchange.SYMBOL, resting.orderId()));
        return null;
    }

    private Trader.RestingQuote placeQuote(Trader trader, Side side, long price) throws IOException {
        var order = order(trader, side, price, TimeInForce.GTC);
        return execute(order) ? new Trader.RestingQuote(order.orderId(), price) : null;
    }

    private PlaceOrder order(Trader trader, Side side, long price, TimeInForce timeInForce) {
        return new PlaceOrder(exchange.nextOrderId(), trader.id, Exchange.SYMBOL, side, timeInForce, price,
                scenario.orderQuantity());
    }

    private boolean execute(ExchangeCommand command) throws IOException {
        boolean rested = false;
        for (var event : exchange.execute(command, scheduler.now())) {
            switch (event.payload()) {
                case TradeExecuted trade -> {
                    previous = reference;
                    reference = trade.price();
                    trades++;
                }
                case OrderRested ignored -> rested = true;
                default -> {}
            }
        }
        return rested;
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            exchange.close();
            if (status != Status.FAILED) status = Status.CLOSED;
        } catch (IOException failure) {
            status = Status.FAILED;
            throw failure;
        }
    }
}
