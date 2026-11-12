package io.aiexchange.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioTest {
    @TempDir Path directory;

    static final String JSON = """
            {
              "schemaVersion": 1, "symbol": "AIX", "agentCount": 8, "initialPrice": 10000,
              "cashPerAgent": 1000000, "sharesPerAgent": 100, "orderQuantity": 2,
              "quoteHalfSpread": 5, "momentumThreshold": 2, "valueThreshold": 8,
              "wakeIntervalNanos": 100, "latencyNanos": 2, "maxEvents": 1000,
              "maxScheduled": 100, "maxOpenOrders": 20, "journalMaxBytes": 4194304
            }
            """;

    @Test void resolvesAixAndRoundTripsAllParameters() throws IOException {
        var scenario = load(JSON);
        assertEquals(8, scenario.agentCount());
        assertEquals(10000, scenario.initialPrice());
        assertEquals(2, scenario.latencyNanos());
        assertEquals(4194304, scenario.journalMaxBytes());
        assertEquals(8, scenario.endowments().size());
        assertEquals(100, scenario.endowments().get(8L).shares());
        assertEquals(scenario, Scenario.fromParameters(scenario.parameters()));
        assertThrows(UnsupportedOperationException.class, () -> scenario.parameters().put("initialPrice", 1L));
    }

    @Test void rejectsDuplicateMissingUnknownFractionalAndTrailingFields() {
        assertThrows(IOException.class, () -> load(JSON.replace("\"agentCount\": 8", "\"agentCount\": 4, \"agentCount\": 8")));
        assertThrows(IOException.class, () -> load(JSON.replace("\"agentCount\": 8, ", "")));
        assertThrows(IOException.class, () -> load(JSON.replace("\"agentCount\": 8", "\"agentCount\": 8, \"extra\": 0")));
        assertThrows(IOException.class, () -> load(JSON.replace("\"agentCount\": 8", "\"agentCount\": 8.0")));
        assertThrows(IOException.class, () -> load(JSON + "{}"));
        assertThrows(IOException.class, () -> load(JSON.replace("AIX", "REAL")));
        assertThrows(IOException.class, () -> load(JSON + " ".repeat(16384)));
    }

    @Test void rejectsOutOfRangeValuesBeforeOpeningAJournal() throws IOException {
        var params = new HashMap<>(load(JSON).parameters());
        params.put("maxScheduled", 7L);
        assertThrows(IllegalArgumentException.class, () -> Scenario.fromParameters(params));
        params.put("maxScheduled", 100L);
        params.put("latencyNanos", 0L);
        assertThrows(IllegalArgumentException.class, () -> Scenario.fromParameters(params));
        params.put("latencyNanos", 2L);
        params.put("cashPerAgent", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> Scenario.fromParameters(params));
        params.put("cashPerAgent", 1_000_000L);
        params.put("agentCount", 501L);
        assertThrows(IllegalArgumentException.class, () -> Scenario.fromParameters(params));
        params.put("agentCount", 8L);
        params.put("maxEvents", 1_000_001L);
        assertThrows(IllegalArgumentException.class, () -> Scenario.fromParameters(params));
    }

    private Scenario load(String json) throws IOException {
        return Scenario.load(Files.writeString(directory.resolve("scenario.json"), json));
    }
}
