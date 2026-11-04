package io.aiexchange.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.aiexchange.core.ExchangeCommand.*;
import static io.aiexchange.core.ExchangeEvent.*;

class ExchangeTest {
    static Exchange exchange() {
        return new Exchange(Map.of(1L, new Exchange.Endowment(1_000_000, 1_000),
                2L, new Exchange.Endowment(1_000_000, 1_000),
                3L, new Exchange.Endowment(1_000_000, 1_000)));
    }

    static PlaceOrder order(Exchange e, long trader, Side side, TimeInForce tif, long price, long quantity) {
        return new PlaceOrder(e.snapshot().nextOrderId(), trader, "AIX", side, tif, price, quantity);
    }

    static Exchange.Plan place(Exchange e, long trader, Side side, long price, long quantity) {
        return e.execute(order(e, trader, side, TimeInForce.GTC, price, quantity));
    }

    static ExchangeSnapshot.AccountView account(Exchange e, long id) {
        return e.snapshot().accounts().stream().filter(a -> a.traderId() == id).findFirst().orElseThrow();
    }

    static List<TradeExecuted> trades(Exchange.Plan plan) {
        return plan.events().stream().map(ExchangeEvent::payload).filter(TradeExecuted.class::isInstance)
                .map(TradeExecuted.class::cast).toList();
    }

    static RejectionReason rejection(Exchange.Plan plan) {
        return switch (plan.events().getFirst().payload()) {
            case OrderRejected r -> r.reason();
            case CancelRejected r -> r.reason();
            default -> null;
        };
    }

    static void assertSameAssetsAndBook(ExchangeSnapshot before, ExchangeSnapshot after) {
        assertEquals(before.accounts(), after.accounts());
        assertEquals(before.orders(), after.orders());
        assertEquals(before.bids(), after.bids());
        assertEquals(before.asks(), after.asks());
    }

    @Test void initializesImmutableSortedAccountsAndEmptyBook() {
        var e = exchange();
        assertEquals(0, e.snapshot().commandSequence());
        assertEquals(0, e.snapshot().eventSequence());
        assertEquals(1, e.snapshot().nextOrderId());
        assertEquals(List.of(1L, 2L, 3L), e.snapshot().accounts().stream().map(ExchangeSnapshot.AccountView::traderId).toList());
        assertTrue(e.snapshot().orders().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> e.snapshot().accounts().clear());
    }

    @Test void matchesTenSellAt100WithSixBuyAt105AtMakerPrice() {
        var e = exchange();
        place(e, 1, Side.SELL, 100, 10);
        var result = place(e, 2, Side.BUY, 105, 6);
        assertNull(rejection(result));
        assertEquals(List.of(new TradeExecuted(1, 2, 100, 6)), trades(result));
        assertEquals(4, e.snapshot().orders().getFirst().remaining());
        assertEquals(4, e.snapshot().asks().getFirst().quantity());
        assertEquals(999_400, account(e, 2).availableCash());
        assertEquals(0, account(e, 2).reservedCash());
        assertEquals(1_006, account(e, 2).availableShares());
        assertEquals(1_000_600, account(e, 1).availableCash());
        assertEquals(990, account(e, 1).availableShares());
        assertEquals(4, account(e, 1).reservedShares());
        e.execute(new CancelOrder(1, "AIX", 1));
        assertEquals(994, account(e, 1).availableShares());
        assertEquals(0, account(e, 1).reservedShares());
    }

    @Test void priceBeatsArrivalTimeAcrossAskLevels() {
        var e = exchange();
        place(e, 1, Side.SELL, 101, 4);
        place(e, 3, Side.SELL, 100, 4);
        assertEquals(List.of(new TradeExecuted(2, 3, 100, 4), new TradeExecuted(1, 3, 101, 2)),
                trades(place(e, 2, Side.BUY, 102, 6)));
        assertEquals(2, e.snapshot().orders().getFirst().remaining());
    }

    @Test void highestBidWinsAndSellTakerUsesBuyMakerPrice() {
        var e = exchange();
        place(e, 1, Side.BUY, 100, 4);
        place(e, 3, Side.BUY, 105, 4);
        assertEquals(List.of(new TradeExecuted(2, 3, 105, 4), new TradeExecuted(1, 3, 100, 2)),
                trades(place(e, 2, Side.SELL, 95, 6)));
        assertEquals(200, account(e, 1).reservedCash());
        assertEquals(0, account(e, 3).reservedCash());
    }

    @Test void equalPriceIsFifoAndPartialMakerKeepsPriority() {
        var e = exchange();
        place(e, 1, Side.SELL, 100, 3);
        place(e, 3, Side.SELL, 100, 4);
        var result = place(e, 2, Side.BUY, 100, 5);
        assertEquals(List.of(new TradeExecuted(1, 3, 100, 3), new TradeExecuted(2, 3, 100, 2)), trades(result));
        place(e, 1, Side.SELL, 100, 2);
        assertEquals(List.of(2L, 4L), e.snapshot().asks().getFirst().orders().stream().map(o -> o.order().orderId()).toList());
        assertEquals(List.of(new TradeExecuted(2, 5, 100, 2)), trades(place(e, 2, Side.BUY, 100, 2)));
    }

    @Test void gtcReservesAndCancelReturnsExactCash() {
        var e = exchange();
        place(e, 1, Side.BUY, 97, 10);
        assertEquals(999_030, account(e, 1).availableCash());
        assertEquals(970, account(e, 1).reservedCash());
        assertNull(rejection(e.execute(new CancelOrder(1, "AIX", 1))));
        assertEquals(1_000_000, account(e, 1).availableCash());
        assertEquals(0, account(e, 1).reservedCash());
        assertTrue(e.snapshot().orders().isEmpty());
        assertTrue(e.snapshot().bids().isEmpty());
    }

    @Test void partialBuyMakerCancelOnlyRefundsRemainingReserve() {
        var e = exchange();
        place(e, 1, Side.BUY, 105, 10);
        place(e, 2, Side.SELL, 100, 6);
        assertEquals(420, account(e, 1).reservedCash());
        e.execute(new CancelOrder(1, "AIX", 1));
        assertEquals(999_370, account(e, 1).availableCash());
        assertEquals(0, account(e, 1).reservedCash());
        assertEquals(1_006, account(e, 1).availableShares());
    }

    @Test void iocTradesAvailableLiquidityAndReleasesRemainder() {
        var e = exchange();
        place(e, 1, Side.SELL, 100, 3);
        var result = e.execute(order(e, 2, Side.BUY, TimeInForce.IOC, 105, 5));
        assertEquals(List.of(new TradeExecuted(1, 2, 100, 3)), trades(result));
        assertEquals(new IncomingRemainderCancelled(2, CancellationReason.IOC_REMAINDER), result.events().getLast().payload());
        assertEquals(999_700, account(e, 2).availableCash());
        assertEquals(0, account(e, 2).reservedCash());
        assertTrue(e.snapshot().orders().isEmpty());
    }

    @Test void emptyIocAndNonCrossingOrdersDoNotCreateTrades() {
        var e = exchange();
        var before = e.snapshot();
        assertTrue(trades(e.execute(order(e, 1, Side.SELL, TimeInForce.IOC, 100, 5))).isEmpty());
        assertSameAssetsAndBook(before, e.snapshot());
        place(e, 1, Side.BUY, 99, 5);
        place(e, 2, Side.SELL, 100, 5);
        assertEquals(2, e.snapshot().orders().size());
    }

    @Test void rejectsInsufficientCashAndSharesWithoutMutation() {
        var e = exchange();
        var before = e.snapshot();
        assertEquals(RejectionReason.INSUFFICIENT_CASH, rejection(place(e, 1, Side.BUY, 1_000_001, 1)));
        assertSameAssetsAndBook(before, e.snapshot());
        assertEquals(RejectionReason.INSUFFICIENT_SHARES, rejection(place(e, 1, Side.SELL, 100, 1_001)));
        assertSameAssetsAndBook(before, e.snapshot());
        place(e, 1, Side.BUY, 1_000, 1_000);
        before = e.snapshot();
        assertEquals(RejectionReason.INSUFFICIENT_CASH, rejection(place(e, 1, Side.BUY, 1, 1)));
        assertSameAssetsAndBook(before, e.snapshot());
    }

    @Test void rejectsNotionalOverflowForEitherSideBeforeReservingAnything() {
        var e = exchange();
        for (var side : Side.values()) {
            var before = e.snapshot();
            assertEquals(RejectionReason.NOTIONAL_OVERFLOW, rejection(place(e, 1, side, Long.MAX_VALUE, 2)));
            assertSameAssetsAndBook(before, e.snapshot());
        }
    }

    @Test void malformedOrdersAndUnknownReferencesAreBusinessRejections() {
        var e = exchange();
        for (var command : List.of(
                new PlaceOrder(1, 1, "AIX", Side.BUY, TimeInForce.GTC, 0, 1),
                new PlaceOrder(2, 1, "AIX", Side.BUY, TimeInForce.GTC, 1, -1),
                new PlaceOrder(3, 1, "AIX", null, TimeInForce.GTC, 1, 1),
                new PlaceOrder(4, 1, "AIX", Side.BUY, null, 1, 1))) {
            var before = e.snapshot();
            assertEquals(RejectionReason.INVALID_ORDER, rejection(e.execute(command)));
            assertSameAssetsAndBook(before, e.snapshot());
        }
        assertEquals(RejectionReason.UNKNOWN_TRADER, rejection(place(e, 99, Side.BUY, 1, 1)));
        assertEquals(RejectionReason.UNKNOWN_SYMBOL, rejection(e.execute(new PlaceOrder(6, 1, null, Side.BUY, TimeInForce.GTC, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> e.plan(null));
    }

    @Test void duplicateAndOutOfSequenceIdsCannotReplaceRestingOrders() {
        var e = exchange();
        var first = order(e, 1, Side.BUY, TimeInForce.GTC, 100, 2);
        e.execute(first);
        var before = e.snapshot();
        assertEquals(RejectionReason.DUPLICATE_ORDER_ID, rejection(e.execute(first)));
        assertEquals(RejectionReason.OUT_OF_SEQUENCE_ORDER_ID, rejection(e.execute(new PlaceOrder(5, 1, "AIX", Side.BUY, TimeInForce.GTC, 1, 1))));
        assertEquals(RejectionReason.INVALID_ORDER_ID, rejection(e.execute(new PlaceOrder(0, 1, "AIX", Side.BUY, TimeInForce.GTC, 1, 1))));
        assertSameAssetsAndBook(before, e.snapshot());
        assertEquals(2, e.snapshot().nextOrderId());
        e.execute(new CancelOrder(1, "AIX", 1));
        assertEquals(RejectionReason.DUPLICATE_ORDER_ID, rejection(e.execute(first)));
    }

    @Test void cancelChecksTraderSymbolOwnershipAndOpenState() {
        var e = exchange();
        place(e, 1, Side.SELL, 100, 10);
        var before = e.snapshot();
        assertEquals(RejectionReason.NOT_OWNER, rejection(e.execute(new CancelOrder(2, "AIX", 1))));
        assertEquals(RejectionReason.UNKNOWN_SYMBOL, rejection(e.execute(new CancelOrder(1, "BAD", 1))));
        assertEquals(RejectionReason.UNKNOWN_TRADER, rejection(e.execute(new CancelOrder(99, "AIX", 1))));
        assertEquals(RejectionReason.ORDER_NOT_OPEN, rejection(e.execute(new CancelOrder(1, "AIX", 99))));
        assertSameAssetsAndBook(before, e.snapshot());
        e.execute(new CancelOrder(1, "AIX", 1));
        before = e.snapshot();
        assertEquals(RejectionReason.ORDER_NOT_OPEN, rejection(e.execute(new CancelOrder(1, "AIX", 1))));
        assertSameAssetsAndBook(before, e.snapshot());
    }

    @Test void selfTradeCancelsIncomingWithoutSkippingOwnMaker() {
        var e = exchange();
        place(e, 1, Side.SELL, 100, 10);
        place(e, 3, Side.SELL, 101, 10);
        var result = place(e, 1, Side.BUY, 105, 5);
        assertTrue(trades(result).isEmpty());
        assertEquals(new IncomingRemainderCancelled(3, CancellationReason.SELF_TRADE_PREVENTED), result.events().getLast().payload());
        assertEquals(2, e.snapshot().orders().size());
        assertEquals(1_000_000, account(e, 1).availableCash());
        assertEquals(0, account(e, 1).reservedCash());
    }

    @Test void selfTradeAfterThirdPartyFillKeepsPriorFillsOnly() {
        var e = exchange();
        place(e, 3, Side.SELL, 99, 2);
        place(e, 1, Side.SELL, 100, 2);
        place(e, 3, Side.SELL, 101, 2);
        var result = place(e, 1, Side.BUY, 101, 5);
        assertEquals(List.of(new TradeExecuted(1, 4, 99, 2)), trades(result));
        assertEquals(new IncomingRemainderCancelled(4, CancellationReason.SELF_TRADE_PREVENTED), result.events().getLast().payload());
        assertEquals(999_802, account(e, 1).availableCash());
        assertEquals(0, account(e, 1).reservedCash());
        assertEquals(2, e.snapshot().orders().size());
    }

    @Test void fullBookRejectsNewRestingOrderButPermitsFillsAndIoc() {
        var e = new Exchange(Map.of(1L, new Exchange.Endowment(1_000_000, 1_000),
                2L, new Exchange.Endowment(1_000_000, 1_000)), 1);
        place(e, 1, Side.SELL, 100, 10);
        var before = e.snapshot();
        assertEquals(RejectionReason.OPEN_ORDER_LIMIT, rejection(place(e, 2, Side.BUY, 90, 5)));
        assertSameAssetsAndBook(before, e.snapshot());
        var ioc = e.execute(order(e, 2, Side.BUY, TimeInForce.IOC, 90, 5));
        assertNull(rejection(ioc));
        assertSameAssetsAndBook(before, e.snapshot());
        assertEquals(1, trades(place(e, 2, Side.BUY, 100, 5)).size());
        assertEquals(5, e.snapshot().orders().getFirst().remaining());
        place(e, 2, Side.BUY, 100, 7);
        assertEquals(1, e.snapshot().orders().size());
        assertEquals(2, e.snapshot().bids().getFirst().quantity());
    }

    @Test void planningIsPureAndOnlyCurrentEngineIssuedBatchCanBeApplied() {
        var e = exchange();
        var before = e.snapshot();
        var plan = e.plan(order(e, 1, Side.BUY, TimeInForce.GTC, 100, 2));
        var competing = e.plan(order(e, 2, Side.SELL, TimeInForce.GTC, 101, 2));
        assertEquals(before, e.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> plan.events().clear());
        assertThrows(IllegalArgumentException.class, () -> exchange().apply(plan));
        e.apply(plan);
        var committed = e.snapshot();
        assertThrows(IllegalStateException.class, () -> e.apply(plan));
        assertThrows(IllegalStateException.class, () -> e.apply(competing));
        assertEquals(committed, e.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> e.snapshot().bids().getFirst().orders().clear());
        assertThrows(UnsupportedOperationException.class, () -> e.snapshot().orders().clear());
    }

    @Test void eventSequencesAreContiguousIncludingRejectionsAndCancels() {
        var e = exchange();
        long sequence = 0;
        for (var command : List.of(order(e, 1, Side.BUY, TimeInForce.GTC, 100, 2),
                new CancelOrder(2, "AIX", 1), new CancelOrder(1, "AIX", 1))) {
            var result = e.execute(command);
            for (var event : result.events()) {
                assertEquals(++sequence, event.sequence());
                assertEquals(e.snapshot().commandSequence(), event.commandSequence());
            }
        }
        assertEquals(sequence, e.snapshot().eventSequence());
    }

    @Test void rejectsInvalidGenesisAndGlobalArithmeticOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(null));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(0L, new Exchange.Endowment(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(1L, new Exchange.Endowment(-1, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(1L, new Exchange.Endowment(0, -1))));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(1L, new Exchange.Endowment(Long.MAX_VALUE, 0), 2L, new Exchange.Endowment(1, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(1L, new Exchange.Endowment(0, Long.MAX_VALUE), 2L, new Exchange.Endowment(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(1L, new Exchange.Endowment(1, 1)), 0));
        assertThrows(IllegalArgumentException.class, () -> new Exchange(Map.of(1L, new Exchange.Endowment(1, 1)), 20_001));
        var nullEntry = new HashMap<Long, Exchange.Endowment>();
        nullEntry.put(1L, null);
        assertThrows(IllegalArgumentException.class, () -> new Exchange(nullEntry));
    }
}
