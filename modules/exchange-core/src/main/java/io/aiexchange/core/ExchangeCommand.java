package io.aiexchange.core;

public sealed interface ExchangeCommand {
    enum Side { BUY, SELL }
    enum TimeInForce { GTC, IOC }

    record PlaceOrder(long orderId, long traderId, String symbol, Side side,
                      TimeInForce timeInForce, long price, long quantity) implements ExchangeCommand {}
    record CancelOrder(long traderId, String symbol, long orderId) implements ExchangeCommand {}
}
