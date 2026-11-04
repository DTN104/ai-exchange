package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.aiexchange.core.ExchangeCommand.*;

class JournalCodecTest {
    static RunManifest manifest(String id) {
        return new RunManifest(id, 42, Map.of(1L, new Exchange.Endowment(1_000_000, 1_000),
                2L, new Exchange.Endowment(1_000_000, 1_000)), Exchange.MAX_OPEN_ORDERS);
    }
    static byte[] raw(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void manifestCanonicalizationAndScenarioHashIgnoreMapOrderAndRunId() throws Exception {
        var one = manifest("first");
        var reversed = new LinkedHashMap<Long, Exchange.Endowment>();
        reversed.put(2L, one.traders().get(2L)); reversed.put(1L, one.traders().get(1L));
        var two = new RunManifest("second", 42, reversed, Exchange.MAX_OPEN_ORDERS);
        assertEquals(JournalCodec.scenarioHash(one), JournalCodec.scenarioHash(two));
        var encoded = JournalCodec.initialization(one);
        assertEquals('\n', encoded[encoded.length - 1]);
        assertEquals(one, JournalCodec.readManifest(JournalCodec.data(encoded)));
        assertArrayEquals(encoded, JournalCodec.initialization(manifest("first")));
        assertThrows(UnsupportedOperationException.class, () -> one.traders().clear());
    }

    @Test void allRecordedCommandShapesRoundTripWithStringLongs() throws Exception {
        var genesis = manifest("roundtrip");
        var core = new Exchange(genesis.traders());
        var inputs = List.of(
                new PlaceOrder(1, 1, "AIX", Side.SELL, TimeInForce.GTC, 100, 10),
                new PlaceOrder(2, 2, "AIX", Side.BUY, TimeInForce.IOC, 105, 6),
                new PlaceOrder(3, 1, "AIX", Side.BUY, TimeInForce.GTC, 100, 5),
                new PlaceOrder(4, 1, null, null, null, Long.MIN_VALUE, Long.MAX_VALUE),
                new CancelOrder(2, "AIX", 1), new CancelOrder(1, "AIX", 1));
        long time = 0;
        for (ExchangeCommand input : inputs) {
            var events = core.execute(input).events();
            var encoded = JournalCodec.command(input, time, events);
            var decoded = JournalCodec.readCommand(JournalCodec.data(encoded));
            assertEquals(input, decoded.command());
            assertEquals(events, decoded.events());
            assertEquals(time++, decoded.simTimeNanos());
        }
        String line = new String(JournalCodec.initialization(genesis), StandardCharsets.UTF_8);
        assertTrue(line.contains("\"seed\":\"42\""));
        assertTrue(line.contains("\"cash\":\"1000000\""));
    }

    @Test void checksumStrictFieldsVersionsDuplicatesAndTrailingTokensAreRejected() throws Exception {
        var encoded = JournalCodec.initialization(manifest("strict"));
        var text = new String(encoded, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> JournalCodec.data(raw(text.replace("1000000", "1000001"))));
        assertThrows(IOException.class, () -> JournalCodec.data(raw(text.trim() + "{}")));
        assertThrows(IOException.class, () -> JournalCodec.data(raw("{\"checksum\":\"duplicate\"," + text.substring(1))));
        var data = JournalCodec.data(encoded);
        data.put("extra", true);
        var extra = JournalCodec.wrap(data);
        assertThrows(IOException.class, () -> JournalCodec.readManifest(JournalCodec.data(extra)));
        data.remove("extra"); data.put("engineVersion", "future-engine");
        assertThrows(IOException.class, () -> JournalCodec.readManifest(JournalCodec.data(JournalCodec.wrap(data))));
        data.put("engineVersion", Exchange.ENGINE_VERSION); data.put("schemaVersion", 2);
        assertThrows(IOException.class, () -> JournalCodec.readManifest(JournalCodec.data(JournalCodec.wrap(data))));
    }

    @Test void stateHashesAreStableAndRecordLengthIsBounded() throws Exception {
        var state = new Exchange(manifest("a").traders()).snapshot();
        assertEquals(JournalCodec.stateHash(state), JournalCodec.stateHash(new Exchange(manifest("b").traders()).snapshot()));
        assertEquals(64, JournalCodec.stateHash(state).length());
        var data = JournalCodec.data(JournalCodec.initialization(manifest("large")));
        data.put("runId", "x".repeat(JournalCodec.MAX_RECORD_BYTES));
        assertThrows(IOException.class, () -> JournalCodec.wrap(data));
    }
}
