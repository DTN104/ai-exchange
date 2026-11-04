package io.aiexchange.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.aiexchange.core.ExchangeCommand.*;
import static io.aiexchange.core.ExchangeEvent.*;
import static io.aiexchange.core.ExchangeTest.*;

class ExchangeInvariantTest {
    private static BigInteger big(long value) { return BigInteger.valueOf(value); }

    static void assertInvariants(ExchangeSnapshot state, BigInteger totalCash, BigInteger totalShares) {
        BigInteger cash = BigInteger.ZERO;
        BigInteger shares = BigInteger.ZERO;
        var reservedCash = new HashMap<Long, BigInteger>();
        var reservedShares = new HashMap<Long, BigInteger>();
        var ids = new HashSet<Long>();
        for (var active : state.orders()) {
            var order = active.order();
            assertTrue(ids.add(order.orderId()), "Duplicate active ID");
            assertTrue(order.orderId() > 0 && order.orderId() < state.nextOrderId());
            assertTrue(active.remaining() > 0 && active.remaining() <= order.quantity());
            assertTrue(active.priority() > 0 && active.priority() <= state.commandSequence());
            assertEquals(TimeInForce.GTC, order.timeInForce(), "IOC rested on book");
            assertEquals("AIX", order.symbol());
            assertTrue(order.price() > 0);
            if (order.side() == Side.BUY) reservedCash.merge(order.traderId(), big(order.price()).multiply(big(active.remaining())), BigInteger::add);
            else reservedShares.merge(order.traderId(), big(active.remaining()), BigInteger::add);
        }
        for (var account : state.accounts()) {
            assertTrue(account.availableCash() >= 0 && account.reservedCash() >= 0);
            assertTrue(account.availableShares() >= 0 && account.reservedShares() >= 0);
            cash = cash.add(big(account.availableCash())).add(big(account.reservedCash()));
            shares = shares.add(big(account.availableShares())).add(big(account.reservedShares()));
            assertEquals(reservedCash.getOrDefault(account.traderId(), BigInteger.ZERO), big(account.reservedCash()), "Cash reservation mismatch");
            assertEquals(reservedShares.getOrDefault(account.traderId(), BigInteger.ZERO), big(account.reservedShares()), "Share reservation mismatch");
        }
        assertEquals(totalCash, cash, "Cash conservation");
        assertEquals(totalShares, shares, "Share conservation");
        var bookOrders = new ArrayList<ExchangeSnapshot.OrderView>();
        for (var side : Side.values()) {
            var levels = side == Side.BUY ? state.bids() : state.asks();
            Long previousPrice = null;
            for (var level : levels) {
                if (previousPrice != null) assertTrue(side == Side.BUY ? previousPrice > level.price() : previousPrice < level.price());
                previousPrice = level.price();
                assertFalse(level.orders().isEmpty());
                BigInteger quantity = BigInteger.ZERO;
                long priority = 0;
                for (var order : level.orders()) {
                    assertEquals(side, order.order().side());
                    assertEquals(level.price(), order.order().price());
                    assertTrue(order.priority() > priority, "FIFO priority changed");
                    priority = order.priority();
                    quantity = quantity.add(big(order.remaining()));
                    bookOrders.add(order);
                }
                assertEquals(quantity, big(level.quantity()));
            }
        }
        assertEquals(state.orders().size(), bookOrders.size(), "Lost or duplicate book entry");
        assertEquals(new HashSet<>(state.orders()), new HashSet<>(bookOrders));
        if (!state.bids().isEmpty() && !state.asks().isEmpty()) assertTrue(state.bids().getFirst().price() < state.asks().getFirst().price(), "Crossed committed book");
    }

    @Test void fiveThousandSeededCommandsConserveAssetsAndIgnoreGenesisInsertionOrder() {
        var genesis = new LinkedHashMap<Long, Exchange.Endowment>();
        var reversed = new LinkedHashMap<Long, Exchange.Endowment>();
        for (long id = 1; id <= 8; id++) genesis.put(id, new Exchange.Endowment(10_000_000, 10_000));
        for (long id = 8; id >= 1; id--) reversed.put(id, genesis.get(id));
        var first = new Exchange(genesis);
        var second = new Exchange(reversed);
        var random = new Random(0xA1E2026L);
        long eventSequence = 0;
        long tradeCount = 0;
        for (int step = 0; step < 5_000; step++) {
            ExchangeCommand command;
            var state = first.snapshot();
            long trader = 1 + random.nextInt(8);
            if (random.nextInt(3) == 0 && !state.orders().isEmpty()) {
                var resting = state.orders().get(random.nextInt(state.orders().size())).order();
                command = new CancelOrder(random.nextInt(5) == 0 ? trader : resting.traderId(), "AIX", resting.orderId());
            } else {
                var side = random.nextBoolean() ? Side.BUY : Side.SELL;
                var tif = random.nextInt(3) == 0 ? TimeInForce.IOC : TimeInForce.GTC;
                command = new PlaceOrder(state.nextOrderId(), step % 211 == 0 ? 99 : trader, "AIX", side, tif,
                        step % 137 == 0 ? Long.MAX_VALUE : 95 + random.nextInt(11), 1 + random.nextInt(25));
            }
            var pure = first.plan(command);
            assertEquals(state, first.snapshot(), "Planner mutated state at step " + step);
            first.apply(pure);
            var other = second.execute(command);
            assertEquals(pure.events(), other.events(), "Different event stream at step " + step);
            assertEquals(first.snapshot(), second.snapshot(), "Different state at step " + step);
            for (var event : pure.events()) {
                assertEquals(++eventSequence, event.sequence());
                if (event.payload() instanceof TradeExecuted) tradeCount++;
            }
            assertEquals(step + 1, first.snapshot().commandSequence());
            assertInvariants(first.snapshot(), big(80_000_000), big(80_000));
        }
        assertTrue(tradeCount > 100, "Workload did not exercise matching");
    }

    @Test void nearLongLimitBuyMakerCanTransferAllRemainingCash() {
        var e = new Exchange(Map.of(1L, new Exchange.Endowment(Long.MAX_VALUE - 100, 0),
                2L, new Exchange.Endowment(100, 1)));
        place(e, 1, Side.BUY, Long.MAX_VALUE - 100, 1);
        place(e, 2, Side.SELL, 1, 1);
        assertEquals(Long.MAX_VALUE, account(e, 2).availableCash());
        assertEquals(0, account(e, 1).availableCash());
        assertInvariants(e.snapshot(), big(Long.MAX_VALUE), big(1));
    }

    @Test void nearLongLimitPriceImprovementAndShareTransferRemainExact() {
        var e = new Exchange(Map.of(1L, new Exchange.Endowment(Long.MAX_VALUE, 0),
                2L, new Exchange.Endowment(0, Long.MAX_VALUE)));
        place(e, 2, Side.SELL, 1, 1);
        place(e, 1, Side.BUY, Long.MAX_VALUE, 1);
        assertEquals(Long.MAX_VALUE - 1, account(e, 1).availableCash());
        assertEquals(1, account(e, 2).availableCash());
        assertInvariants(e.snapshot(), big(Long.MAX_VALUE), big(Long.MAX_VALUE));
        e.execute(order(e, 2, Side.SELL, TimeInForce.IOC, 1, Long.MAX_VALUE - 1));
        assertInvariants(e.snapshot(), big(Long.MAX_VALUE), big(Long.MAX_VALUE));
    }

    @Test void selfTradeSellTakerAndCompletedOrderCancelPreserveAssets() {
        var e = exchange();
        place(e, 3, Side.BUY, 102, 2);
        place(e, 1, Side.BUY, 101, 2);
        place(e, 3, Side.BUY, 100, 2);
        var result = e.execute(order(e, 1, Side.SELL, TimeInForce.IOC, 100, 5));
        assertEquals(List.of(new TradeExecuted(1, 4, 102, 2)), trades(result));
        assertEquals(new IncomingRemainderCancelled(4, CancellationReason.SELF_TRADE_PREVENTED), result.events().getLast().payload());
        var before = e.snapshot();
        assertEquals(RejectionReason.ORDER_NOT_OPEN, rejection(e.execute(new CancelOrder(3, "AIX", 1))));
        assertSameAssetsAndBook(before, e.snapshot());
        assertInvariants(e.snapshot(), big(3_000_000), big(3_000));
    }

    @Test void genesisInputCannotMutateLiveBalancesAndTraderCountIsBounded() {
        var initial = new HashMap<Long, Exchange.Endowment>();
        initial.put(1L, new Exchange.Endowment(1, 1));
        var e = new Exchange(initial);
        initial.put(1L, new Exchange.Endowment(999, 999));
        assertEquals(1, account(e, 1).availableCash());
        assertEquals(1, account(e, 1).availableShares());
        for (long id = 2; id <= 501; id++) initial.put(id, new Exchange.Endowment(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(initial));
    }
}
