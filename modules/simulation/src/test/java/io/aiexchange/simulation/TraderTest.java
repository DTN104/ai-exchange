package io.aiexchange.simulation;

import io.aiexchange.core.ExchangeCommand.Side;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TraderTest {
    @TempDir Path directory;
    Scenario scenario;

    @BeforeEach void loadScenario() throws IOException {
        scenario = Scenario.load(Files.writeString(directory.resolve("scenario.json"), ScenarioTest.JSON));
    }

    @Test void makerQuotesBothSidesAndKeepsPricesPositive() {
        var maker = new Trader(1, 42);
        assertEquals(new Trader.Quote(9_995, 10_005), maker.intent(scenario, 10_000, 10_000));
        assertEquals(new Trader.Quote(1, 6), maker.intent(scenario, 1, 1));
    }

    @Test void noiseUsesItsOwnSeedForEitherSide() {
        var noise = new Trader(2, 42);
        assertEquals(new Trader.Take(Side.SELL, 9_985), noise.intent(scenario, 10_000, 10_000));
        assertEquals(new Trader.Take(Side.BUY, 10_015), noise.intent(scenario, 10_000, 10_000));
    }

    @Test void momentumFollowsTheLastPriceMoveAtTheThreshold() {
        var momentum = new Trader(3, 42);
        assertNull(momentum.intent(scenario, 10_001, 10_000));
        assertEquals(new Trader.Take(Side.BUY, 10_017), momentum.intent(scenario, 10_002, 10_000));
        assertEquals(new Trader.Take(Side.SELL, 9_983), momentum.intent(scenario, 9_998, 10_000));
    }

    @Test void valueTradesTowardsFundamentalAndCapsItsLimitAtFairValue() {
        var value = new Trader(4, 42);
        assertNull(value.intent(scenario, 10_007, 10_000));
        assertEquals(new Trader.Take(Side.SELL, 10_000), value.intent(scenario, 10_008, 10_001));
        assertEquals(new Trader.Take(Side.BUY, 10_000), value.intent(scenario, 9_992, 9_990));
    }
}
