package io.aiexchange.core;

import io.aiexchange.core.ExchangeCommand.PlaceOrder;

public record ExchangeEvent(long sequence, long commandSequence, Payload payload) {
    public sealed interface Payload permits OrderAccepted, OrderRejected, TradeExecuted, OrderRested,
            OrderCancelled, IncomingRemainderCancelled, CancelRejected {}

    public enum RejectionReason {
        INVALID_ORDER_ID, DUPLICATE_ORDER_ID, OUT_OF_SEQUENCE_ORDER_ID, ORDER_IDS_EXHAUSTED,
        UNKNOWN_TRADER, UNKNOWN_SYMBOL, INVALID_ORDER, NOTIONAL_OVERFLOW,
        INSUFFICIENT_CASH, INSUFFICIENT_SHARES, OPEN_ORDER_LIMIT, ORDER_NOT_OPEN, NOT_OWNER
    }
    public enum CancellationReason { USER_CANCELLED, IOC_REMAINDER, SELF_TRADE_PREVENTED }

    public record OrderAccepted(PlaceOrder order) implements Payload {}
    public record OrderRejected(long orderId, RejectionReason reason, boolean consumesOrderId) implements Payload {}
    public record TradeExecuted(long makerOrderId, long takerOrderId, long price, long quantity) implements Payload {}
    public record OrderRested(long orderId) implements Payload {}
    public record OrderCancelled(long orderId, CancellationReason reason) implements Payload {}
    public record IncomingRemainderCancelled(long orderId, CancellationReason reason) implements Payload {}
    public record CancelRejected(long orderId, RejectionReason reason) implements Payload {}
}
