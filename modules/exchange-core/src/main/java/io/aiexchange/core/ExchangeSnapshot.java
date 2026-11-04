package io.aiexchange.core;

import java.util.List;
import io.aiexchange.core.ExchangeCommand.PlaceOrder;

public record ExchangeSnapshot(long commandSequence, long eventSequence, long nextOrderId,
                               List<AccountView> accounts, List<OrderView> orders,
                               List<PriceLevel> bids, List<PriceLevel> asks) {
    public ExchangeSnapshot {
        accounts = List.copyOf(accounts);
        orders = List.copyOf(orders);
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    public record AccountView(long traderId, long availableCash, long reservedCash,
                              long availableShares, long reservedShares) {}
    public record OrderView(PlaceOrder order, long remaining, long priority) {}
    public record PriceLevel(long price, long quantity, List<OrderView> orders) {
        public PriceLevel { orders = List.copyOf(orders); }
    }
}
