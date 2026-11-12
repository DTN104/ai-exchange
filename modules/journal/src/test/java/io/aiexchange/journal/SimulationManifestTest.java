package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SimulationManifestTest {
    private static final Map<Long, Exchange.Endowment> GENESIS = Map.of(1L, new Exchange.Endowment(400, 7));
    @TempDir Path directory;

    @Test void versionsOnlyGenesisAndPersistsImmutableResolvedConfiguration() throws IOException {
        var parameters = new HashMap<>(Map.of("maxEvents", 400L, "latencyNanos", 20L));
        var simulation = new RunManifest.Simulation("rules-v1", "splitmix64-v1", parameters);
        var manifest = new RunManifest("new", Long.MIN_VALUE, GENESIS, 12, simulation);
        parameters.put("maxEvents", 0L);
        var path = directory.resolve("new.ndjson");
        try (var writer = JournaledExchange.create(path, manifest)) {
            assertEquals(0, writer.snapshot().commandSequence());
        }
        var report = JournalReplay.read(path);
        assertTrue(report.sealed());
        assertEquals(manifest, report.manifest());
        assertEquals(400L, report.manifest().simulation().parameters().get("maxEvents"));
        assertEquals(2, decode(JournalCodec.initialization(manifest)).get("schemaVersion").asInt());
    }

    @Test void legacyHeaderRemainsSchemaOneAndRoundTripsWithoutSimulation() throws IOException {
        var legacy = new RunManifest("legacy", 0, GENESIS, 12);
        var decoded = decode(JournalCodec.initialization(legacy));
        assertEquals(1, decoded.get("schemaVersion").asInt());
        assertFalse(decoded.has("parameters"));
        assertEquals(legacy, JournalCodec.readManifest(decoded));
        assertNull(legacy.simulation());
    }

    @Test void scenarioHashCoversSimulationParametersButNotRunIdOrSeed() throws IOException {
        var first = new RunManifest("a", 1, GENESIS, 12,
                new RunManifest.Simulation("rules-v1", "splitmix64-v1", Map.of("latencyNanos", 20L)));
        var another = new RunManifest("b", 2, GENESIS, 12, first.simulation());
        var changed = new RunManifest("a", 1, GENESIS, 12,
                new RunManifest.Simulation("rules-v1", "splitmix64-v1", Map.of("latencyNanos", 21L)));
        assertEquals(JournalCodec.scenarioHash(first), JournalCodec.scenarioHash(another));
        assertNotEquals(JournalCodec.scenarioHash(first), JournalCodec.scenarioHash(changed));
        var record = decode(JournalCodec.initialization(first));
        ((tools.jackson.databind.node.ObjectNode) record.get("parameters")).put("latencyNanos", "21");
        assertThrows(IOException.class, () -> JournalCodec.readManifest(decode(JournalCodec.wrap(record))));
    }

    private static tools.jackson.databind.node.ObjectNode decode(byte[] line) throws IOException {
        assertEquals((byte) '\n', line[line.length - 1], new String(line, StandardCharsets.UTF_8));
        return JournalCodec.data(Arrays.copyOf(line, line.length - 1));
    }
}
