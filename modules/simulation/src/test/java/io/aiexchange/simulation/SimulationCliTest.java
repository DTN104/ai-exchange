package io.aiexchange.simulation;

import io.aiexchange.journal.JournaledExchange;
import io.aiexchange.journal.JournalReplay;
import io.aiexchange.journal.RunManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SimulationCliTest {
    @TempDir Path directory;

    @Test void runsFromJsonAndVerifiesFromTheJournalAlone() throws IOException {
        var config = scenarioFile();
        var first = directory.resolve("first run.ndjson");
        var second = directory.resolve("rerun.ndjson");
        var third = directory.resolve("other-seed.ndjson");
        var initial = execute("run", config.toString(), "-42", first.toString());
        assertEquals(0, initial.status(), initial.output());
        assertTrue(initial.output().contains("stateReplay=verified"));
        var other = execute("run", config.toString(), "43", third.toString());
        assertEquals(0, other.status(), other.output());
        Files.delete(config);

        var verified = execute("verify", first.toString(), second.toString());
        assertEquals(0, verified.status(), verified.output());
        assertTrue(verified.output().contains("rerun=verified"));
        var a = JournalReplay.read(first);
        var b = JournalReplay.read(second);
        assertTrue(a.sealed() && b.sealed());
        assertEquals(-42, b.manifest().seed());
        assertNotEquals(a.manifest().runId(), b.manifest().runId());
        assertEquals(a.eventDigest(), b.eventDigest());
        assertEquals(a.snapshot(), b.snapshot());
        assertNotEquals(a.eventDigest(), JournalReplay.read(third).eventDigest());
        assertEquals(1, execute("verify", first.toString(), first.toString()).status());
        assertEquals(a, JournalReplay.read(first));
    }

    @Test void rejectsMalformedSeedBeforeCreatingAJournal() throws IOException {
        var path = directory.resolve("bad-seed.ndjson");
        var result = execute("run", scenarioFile().toString(), "9223372036854775808", path.toString());
        assertNotEquals(0, result.status());
        assertTrue(result.output().contains("signed 64-bit"));
        assertFalse(Files.exists(path));
    }

    @Test void reportsMismatchWhenAHealthyPrefixWasClosedEarly() throws IOException {
        var path = directory.resolve("prefix.ndjson");
        var rerun = directory.resolve("different.ndjson");
        try (var run = SimulationRun.create(path, "prefix", Scenario.load(scenarioFile()), 42)) {
            run.advance(4);
        }
        assertTrue(JournalReplay.read(path).sealed());
        var result = execute("verify", path.toString(), rerun.toString());
        assertEquals(1, result.status());
        assertTrue(result.output().contains("differs"));
        assertTrue(JournalReplay.read(rerun).sealed());
    }

    @Test void cannotRerunWithAnUnsupportedStrategyVersion() throws IOException {
        var path = directory.resolve("unsupported.ndjson");
        var rerun = directory.resolve("must-not-exist.ndjson");
        var scenario = Scenario.load(scenarioFile());
        var manifest = new RunManifest("unsupported", 3, scenario.endowments(), scenario.maxOpenOrders(),
                new RunManifest.Simulation("aix-rules-v2", "splitmix64-v1", scenario.parameters()));
        try (var ignored = JournaledExchange.create(path, manifest)) {}
        var result = execute("verify", path.toString(), rerun.toString());
        assertEquals(1, result.status());
        assertTrue(result.output().contains("version"));
        assertFalse(Files.exists(rerun));
    }

    private Path scenarioFile() throws IOException {
        return Files.writeString(directory.resolve("scenario.json"), ScenarioTest.JSON.replace("\"maxEvents\": 1000", "\"maxEvents\": 120"));
    }

    private static Result execute(String... args) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            return new Result(SimulationCli.execute(args, out), bytes.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int status, String output) {}
}
