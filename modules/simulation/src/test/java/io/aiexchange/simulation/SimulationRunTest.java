package io.aiexchange.simulation;

import io.aiexchange.core.ExchangeSnapshot;
import io.aiexchange.journal.JournalReplay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SimulationRunTest {
    @TempDir Path directory;

    @Test void firstWakePlacesNoOrderBeforeLatencyAndThenBootstrapsWithRealQuotes() throws IOException {
        var path = directory.resolve("first.ndjson");
        var scenario = load(ScenarioTest.JSON.replace("\"agentCount\": 8", "\"agentCount\": 2"));
        try (var run = SimulationRun.create(path, "first", scenario, 42)) {
            assertEquals(10_000, run.progress().referencePrice());
            assertEquals(0, run.advance(1).simTimeNanos());
            assertEquals(0, run.snapshot().commandSequence());
            assertEquals(2, run.advance(1).simTimeNanos());
            assertEquals(2, run.snapshot().commandSequence());
            assertEquals(9_995, run.snapshot().bids().getFirst().price());
            assertEquals(10_005, run.snapshot().asks().getFirst().price());
            assertEquals(0, run.progress().tradeCount());
        }
        assertTrue(JournalReplay.read(path).sealed());
    }

    @Test void differentAdvancePartitionsAndRunIdsProduceIdenticalEconomicResults() throws IOException {
        var scenario = load(ScenarioTest.JSON);
        var first = directory.resolve("first.ndjson");
        var second = directory.resolve("second.ndjson");
        ExchangeSnapshot live;
        try (var run = SimulationRun.create(first, "first", scenario, 42)) {
            assertThrows(IllegalArgumentException.class, () -> run.advance(0));
            var completed = run.advance(Long.MAX_VALUE);
            assertEquals(SimulationRun.Status.EVENT_LIMIT, completed.status());
            assertEquals(1_000, completed.processedEvents());
            assertTrue(completed.tradeCount() > 0);
            assertEquals(completed, run.advance(1));
            live = run.snapshot();
        }
        try (var run = SimulationRun.create(second, "second", scenario, 42)) {
            while (run.progress().status() == SimulationRun.Status.READY) run.advance(17);
            assertEquals(live, run.snapshot());
        }
        var a = JournalReplay.read(first);
        var b = JournalReplay.read(second);
        assertTrue(a.sealed() && b.sealed());
        assertEquals(live, a.snapshot());
        assertEquals(a.snapshot(), b.snapshot());
        assertEquals(a.stateHash(), b.stateHash());
        assertEquals(a.eventDigest(), b.eventDigest());
        assertEquals(scenario.parameters(), a.manifest().simulation().parameters());
        assertThrows(IOException.class, () -> SimulationRun.create(first, "existing", scenario, 42));
    }

    @Test void hittingJournalCapStopsAdmissionAndLeavesAnUnsealedReplayablePrefix() throws IOException {
        var path = directory.resolve("limited.ndjson");
        var scenario = load(ScenarioTest.JSON.replace("4194304", "8192"));
        ExchangeSnapshot live;
        try (var run = SimulationRun.create(path, "limited", scenario, 42)) {
            assertThrows(IOException.class, () -> run.advance(1_000));
            assertEquals(SimulationRun.Status.FAILED, run.progress().status());
            assertThrows(IllegalStateException.class, () -> run.advance(1));
            live = run.snapshot();
            assertTrue(live.commandSequence() > 0);
        }
        var replay = JournalReplay.read(path);
        assertFalse(replay.sealed());
        assertEquals(live, replay.snapshot());
        assertTrue(Files.size(path) <= 8192);
    }

    private Scenario load(String json) throws IOException {
        return Scenario.load(Files.writeString(directory.resolve("scenario.json"), json));
    }
}
