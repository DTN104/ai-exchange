package io.aiexchange.simulation;

import io.aiexchange.core.ExchangeCommand.Side;

final class Trader {
    static final String STRATEGY_VERSION = "aix-rules-v1";
    private enum Strategy { MARKET_MAKER, NOISE, MOMENTUM, VALUE }
    private static final Strategy[] ROTATION = Strategy.values();

    sealed interface Intent permits Quote, Take {}
    record Quote(long bidPrice, long askPrice) implements Intent {}
    record Take(Side side, long limitPrice) implements Intent {}
    record RestingQuote(long orderId, long price) {}

    final long id;
    private final Strategy strategy;
    private final AgentRandom random;
    RestingQuote bid;
    RestingQuote ask;

    Trader(long id, long seed) {
        this.id = id;
        strategy = ROTATION[(int) ((id - 1) % ROTATION.length)];
        random = new AgentRandom(seed, id);
    }

    long firstWake(Scenario scenario) {
        return strategy == Strategy.MARKET_MAKER ? 0 : wakeDelay(scenario);
    }

    long wakeDelay(Scenario scenario) {
        long interval = scenario.wakeIntervalNanos();
        return interval + random.nextLong(interval);
    }

    Intent intent(Scenario scenario, long reference, long previous) {
        long spread = scenario.quoteHalfSpread();
        long aggression = spread * 3;
        return switch (strategy) {
            case MARKET_MAKER -> new Quote(price(reference - spread), price(reference + spread));
            case NOISE -> take(random.nextLong(2) == 0 ? Side.BUY : Side.SELL, reference, aggression);
            case MOMENTUM -> {
                long move = reference - previous;
                yield Math.abs(move) < scenario.momentumThreshold() ? null
                        : take(move > 0 ? Side.BUY : Side.SELL, reference, aggression);
            }
            case VALUE -> {
                long fair = scenario.initialPrice();
                long difference = fair - reference;
                if (Math.abs(difference) < scenario.valueThreshold()) yield null;
                yield difference > 0 ? new Take(Side.BUY, Math.min(fair, reference + aggression))
                        : new Take(Side.SELL, Math.max(fair, reference - aggression));
            }
        };
    }

    private static Take take(Side side, long reference, long aggression) {
        return new Take(side, price(side == Side.BUY ? reference + aggression : reference - aggression));
    }

    private static long price(long ticks) {
        return Math.clamp(ticks, 1, Scenario.MAX_PRICE);
    }
}
