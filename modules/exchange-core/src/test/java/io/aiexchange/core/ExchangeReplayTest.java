package io.aiexchange.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.aiexchange.core.ExchangeCommand.*;
import static io.aiexchange.core.ExchangeEvent.*;
import static io.aiexchange.core.ExchangeTest.*;

class ExchangeReplayTest {
    static Map<Long, Exchange.Endowment> genesis() {
        return Map.of(1L, new Exchange.Endowment(1_000_000, 1_000), 2L, new Exchange.Endowment(1_000_000, 1_000),
                3L, new Exchange.Endowment(1_000_000, 1_000));
    }
    static Exchange.Replayer replayer() { return Exchange.replayer(genesis(), Exchange.MAX_OPEN_ORDERS); }

    @Test void reducesHandwrittenEventsWithoutGeneratingMatchingDecisions() {
        var replay = replayer();
        var sell = new PlaceOrder(1, 1, "AIX", Side.SELL, TimeInForce.GTC, 100, 10);
        replay.accept(sell, List.of(new ExchangeEvent(1, 1, new OrderAccepted(sell)),
                new ExchangeEvent(2, 1, new OrderRested(1))));
        var buy = new PlaceOrder(2, 2, "AIX", Side.BUY, TimeInForce.GTC, 105, 6);
        replay.accept(buy, List.of(new ExchangeEvent(3, 2, new OrderAccepted(buy)),
                new ExchangeEvent(4, 2, new TradeExecuted(1, 2, 100, 6))));
        assertEquals(4, replay.snapshot().asks().getFirst().quantity());
        assertEquals(999_400, replay.snapshot().accounts().get(1).availableCash());
        replay.accept(new CancelOrder(1, "AIX", 1), List.of(new ExchangeEvent(5, 3,
                new OrderCancelled(1, CancellationReason.USER_CANCELLED))));
        assertTrue(replay.snapshot().orders().isEmpty());
        assertEquals(994, replay.snapshot().accounts().getFirst().availableShares());
    }

    @Test void recordedMixedBatchesRebuildExactlyTheLiveState() {
        var live = new Exchange(genesis());
        var replay = replayer();
        var random = new Random(37);
        for (int i = 0; i < 1_000; i++) {
            ExchangeCommand command;
            var state = live.snapshot();
            if (i % 4 == 0 && !state.orders().isEmpty()) {
                var old = state.orders().get(random.nextInt(state.orders().size())).order();
                command = new CancelOrder(i % 8 == 0 ? 99 : old.traderId(), "AIX", old.orderId());
            } else command = new PlaceOrder(state.nextOrderId(), 1 + random.nextInt(3), "AIX",
                    random.nextBoolean() ? Side.BUY : Side.SELL, i % 3 == 0 ? TimeInForce.IOC : TimeInForce.GTC,
                    i % 101 == 0 ? Long.MAX_VALUE : 95 + random.nextInt(11), 1 + random.nextInt(12));
            replay.accept(command, live.execute(command).events());
            assertEquals(live.snapshot(), replay.snapshot(), "Step " + i);
        }
    }

    @Test void rejectsGapsWrongCommandsAndMalformedTerminalEventsAndPoisonsOnlyReplay() {
        var order = new PlaceOrder(1, 1, "AIX", Side.BUY, TimeInForce.GTC, 100, 2);
        var valid = new Exchange(genesis()).execute(order).events();
        var cases = List.of(
                List.of(new ExchangeEvent(2, 1, new OrderAccepted(order)), new ExchangeEvent(3, 1, new OrderRested(1))),
                List.of(new ExchangeEvent(1, 2, new OrderAccepted(order)), new ExchangeEvent(2, 2, new OrderRested(1))),
                List.of(new ExchangeEvent(1, 1, new OrderAccepted(order))),
                List.of(new ExchangeEvent(1, 1, new OrderAccepted(order)), new ExchangeEvent(2, 1, new OrderRested(99))),
                List.of(new ExchangeEvent(1, 1, new OrderAccepted(order)), new ExchangeEvent(2, 1, new IncomingRemainderCancelled(1, CancellationReason.IOC_REMAINDER))),
                List.of(new ExchangeEvent(1, 1, new OrderRejected(1, RejectionReason.INSUFFICIENT_CASH, true))));
        for (var events : cases) {
            var replay = replayer();
            assertThrows(IllegalArgumentException.class, () -> replay.accept(order, events));
            assertThrows(IllegalStateException.class, replay::snapshot);
            assertThrows(IllegalStateException.class, () -> replay.accept(order, valid));
        }
        var different = new PlaceOrder(1, 1, "AIX", Side.BUY, TimeInForce.GTC, 101, 2);
        assertThrows(IllegalArgumentException.class, () -> replayer().accept(different, valid));
    }

    @Test void rejectsWrongPriceQuantityMakerAndFalseCancellationOwnership() {
        var live = new Exchange(genesis());
        var sell1 = order(live, 1, Side.SELL, TimeInForce.GTC, 100, 2);
        var first = live.execute(sell1).events();
        var sell2 = order(live, 3, Side.SELL, TimeInForce.GTC, 101, 2);
        var second = live.execute(sell2).events();
        var buy = order(live, 2, Side.BUY, TimeInForce.GTC, 105, 2);
        var valid = live.execute(buy).events();
        for (var bad : List.of(new TradeExecuted(1, 3, 105, 2), new TradeExecuted(1, 3, 100, -1),
                new TradeExecuted(1, 3, 100, 1), new TradeExecuted(2, 3, 101, 2), new TradeExecuted(1, 99, 100, 2))) {
            var replay = replayer(); replay.accept(sell1, first); replay.accept(sell2, second);
            var changed = new ArrayList<>(valid);
            changed.set(1, new ExchangeEvent(valid.get(1).sequence(), valid.get(1).commandSequence(), bad));
            assertThrows(IllegalArgumentException.class, () -> replay.accept(buy, changed));
        }
        var replay = replayer(); replay.accept(sell1, first);
        assertThrows(IllegalArgumentException.class, () -> replay.accept(new CancelOrder(2, "AIX", 1),
                List.of(new ExchangeEvent(3, 2, new OrderCancelled(1, CancellationReason.USER_CANCELLED)))));
    }

    @Test void validatesRejectedIdConsumptionAndCapacityRejections() {
        var live = new Exchange(genesis(), 1);
        var replay = Exchange.replayer(genesis(), 1);
        var first = order(live, 1, Side.BUY, TimeInForce.GTC, 100, 2);
        replay.accept(first, live.execute(first).events());
        var rejected = order(live, 2, Side.BUY, TimeInForce.GTC, 99, 2);
        replay.accept(rejected, live.execute(rejected).events());
        assertEquals(live.snapshot(), replay.snapshot());
        var current = order(live, 99, Side.BUY, TimeInForce.GTC, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> replay.accept(current,
                List.of(new ExchangeEvent(4, 3, new OrderRejected(3, RejectionReason.UNKNOWN_TRADER, false)))));
    }
}
