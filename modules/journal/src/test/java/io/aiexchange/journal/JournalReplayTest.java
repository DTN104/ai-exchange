package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeCommand;
import io.aiexchange.core.ExchangeSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static io.aiexchange.core.ExchangeCommand.*;
import static io.aiexchange.journal.JournalCodecTest.*;

class JournalReplayTest {
    @TempDir Path directory;
    static final PlaceOrder ASK = new PlaceOrder(1, 1, "AIX", Side.SELL, TimeInForce.GTC, 100, 10);
    static final PlaceOrder BUY = new PlaceOrder(2, 2, "AIX", Side.BUY, TimeInForce.GTC, 105, 6);

    ExchangeSnapshot sample(Path path) throws Exception {
        try (var run = JournaledExchange.create(path, manifest("sample"))) {
            run.execute(ASK, 1); run.execute(BUY, 2);
            run.execute(new CancelOrder(2, "AIX", 1), 3);
            run.execute(new CancelOrder(1, "AIX", 1), 4);
            return run.snapshot();
        }
    }

    @Test void sealedFullAndPrefixReplayMatchLiveSnapshotsAndHashes() throws Exception {
        var path = directory.resolve("sealed.ndjson");
        var expected = sample(path);
        var report = JournalReplay.read(path);
        assertTrue(report.sealed());
        assertEquals(expected, report.snapshot());
        assertEquals(JournalReplay.stateHash(expected), report.stateHash());
        assertEquals(4, report.journalCommands());
        var prefix = JournalReplay.read(path, 2);
        assertEquals(2, prefix.snapshot().commandSequence());
        assertEquals(4, prefix.snapshot().asks().getFirst().quantity());
        assertEquals(4, prefix.journalCommands());
        assertTrue(prefix.sealed());
        assertEquals(0, JournalReplay.read(path, 0).snapshot().commandSequence());
        assertThrows(IOException.class, () -> JournalReplay.read(path, 5));
    }

    @Test void appendHappensBeforeApplyAndFailureClosesAdmission() throws Exception {
        var holder = new JournaledExchange[1];
        var records = new ArrayList<byte[]>();
        AppendSink sink = new AppendSink() {
            public void append(byte[] bytes) throws IOException {
                if (!records.isEmpty()) {
                    assertEquals(0, holder[0].snapshot().commandSequence());
                    throw new IOException("injected force failure");
                }
                records.add(bytes.clone());
            }
            public void close() {}
        };
        var run = new JournaledExchange(manifest("failed"), JournalCodec.MAX_JOURNAL_BYTES, sink);
        holder[0] = run;
        var before = run.snapshot();
        assertThrows(IOException.class, () -> run.execute(ASK, 1));
        assertEquals(before, run.snapshot());
        assertTrue(run.isFailed());
        assertThrows(IllegalStateException.class, () -> run.execute(ASK, 1));
        run.close();
        assertEquals(1, records.size(), "Failed run must not claim a completion seal");
    }

    @Test void durableButUnappliedCommandIsRecoveredFromItsEvents() throws Exception {
        var path = directory.resolve("uncertain.ndjson");
        var actual = JournalFile.create(path);
        AppendSink uncertain = new AppendSink() {
            int writes;
            public void append(byte[] bytes) throws IOException {
                actual.append(bytes);
                if (++writes == 2) throw new IOException("Failure after durable write, before live apply");
            }
            public void close() throws IOException { actual.close(); }
        };
        var run = new JournaledExchange(manifest("uncertain"), JournalCodec.MAX_JOURNAL_BYTES, uncertain);
        var before = run.snapshot();
        assertThrows(IOException.class, () -> run.execute(ASK, 1));
        assertEquals(before, run.snapshot());
        run.close();
        var expected = new Exchange(manifest("reference").traders()); expected.execute(ASK);
        var report = JournalReplay.read(path);
        assertFalse(report.sealed());
        assertEquals(expected.snapshot(), report.snapshot());
    }

    @Test void failedCompletionCannotReturnSuccessOrAcceptMoreCommands() throws Exception {
        var path = directory.resolve("seal-failure.ndjson");
        var file = JournalFile.create(path);
        AppendSink sink = new AppendSink() {
            public void append(byte[] bytes) throws IOException {
                if (JournalCodec.text(JournalCodec.data(bytes), "kind").equals("RunCompleted")) throw new IOException("Injected seal failure");
                file.append(bytes);
            }
            public void close() throws IOException { file.close(); }
        };
        var run = new JournaledExchange(manifest("seal-failure"), JournalCodec.MAX_JOURNAL_BYTES, sink);
        run.execute(ASK, 1);
        assertThrows(IOException.class, run::close);
        assertTrue(run.isFailed());
        assertThrows(IllegalStateException.class, () -> run.execute(BUY, 2));
        run.close();
        var report = JournalReplay.read(path);
        assertFalse(report.sealed());
        assertEquals(run.snapshot(), report.snapshot());
    }

    @Test void fileLimitStopsBeforeChangingStateAndReservesSpaceForSeal() throws Exception {
        var path = directory.resolve("limit.ndjson");
        var run = JournaledExchange.create(path, manifest("limit"), 8_192);
        boolean stopped = false;
        for (int i = 0; i < 100; i++) {
            var before = run.snapshot(); long size = Files.size(path);
            var order = new PlaceOrder(before.nextOrderId(), 1, "AIX", Side.BUY, TimeInForce.GTC, 1, 1);
            try { run.execute(order, i); }
            catch (IOException full) {
                assertEquals(before, run.snapshot()); assertEquals(size, Files.size(path)); stopped = true; break;
            }
        }
        assertTrue(stopped); assertTrue(run.isFailed());
        var committed = run.snapshot(); run.close();
        assertEquals(committed, JournalReplay.read(path).snapshot());
        assertTrue(Files.size(path) <= 8_192);
        var closed = directory.resolve("small-closed.ndjson");
        try (var empty = JournaledExchange.create(closed, manifest("empty"), 4_096)) { assertEquals(0, empty.snapshot().commandSequence()); }
        assertTrue(JournalReplay.read(closed).sealed());
        var invalid = directory.resolve("invalid.ndjson");
        assertThrows(IllegalArgumentException.class, () -> JournaledExchange.create(invalid, manifest("bad"), 0));
        assertFalse(Files.exists(invalid));
    }

    @Test void tornTailIsNeverSilentlyDroppedAndRepairIsExplicit() throws Exception {
        var path = directory.resolve("tail.ndjson"); sample(path);
        var tail = "{\"incomplete\":".getBytes(StandardCharsets.UTF_8);
        Files.write(path, tail, java.nio.file.StandardOpenOption.APPEND);
        long damaged = Files.size(path);
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        assertEquals(damaged, Files.size(path));
        assertEquals(tail.length, JournalReplay.recoverIncompleteTail(path));
        assertTrue(JournalReplay.read(path).sealed());
        assertEquals(0, JournalReplay.recoverIncompleteTail(path));
        var bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        assertTrue(JournalReplay.recoverIncompleteTail(path) > 0);
        assertFalse(JournalReplay.read(path).sealed(), "Truncated completion has no completion proof");
    }

    @Test void corruptCompletePrefixCannotBeHiddenByTailRepair() throws Exception {
        var path = directory.resolve("corrupt.ndjson"); sample(path);
        var lines = Files.readAllLines(path);
        lines.set(1, lines.get(1).replace("\"price\":\"100\"", "\"price\":\"101\""));
        Files.writeString(path, String.join("\n", lines) + "\n{");
        var before = Files.readAllBytes(path);
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        assertThrows(IOException.class, () -> JournalReplay.recoverIncompleteTail(path));
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void validChecksumsDoNotPermitSequenceSemanticVersionOrCompletionTampering() throws Exception {
        var source = directory.resolve("source.ndjson"); sample(source);
        for (int kind = 0; kind < 9; kind++) {
            var lines = Files.readAllLines(source);
            int index = kind == 0 || kind == 5 || kind == 7 ? 0 : kind == 4 || kind == 6 ? lines.size() - 1 : 2;
            var data = JournalCodec.data(raw(lines.get(index)));
            switch (kind) {
                case 0 -> data.put("engineVersion", "future");
                case 1 -> ((ObjectNode) data.get("events").get(0)).put("sequence", "99");
                case 2 -> ((ObjectNode) data.get("events").get(1).get("payload")).put("price", "105");
                case 3 -> data.put("simTimeNanos", "0");
                case 4 -> data.put("stateHash", "0".repeat(64));
                case 5 -> data.put("seed", 42);
                case 6 -> data.put("eventDigest", "0".repeat(64));
                case 7 -> data.put("scenarioHash", "0".repeat(64));
                case 8 -> data.put("outcome", "REJECTED");
            }
            lines.set(index, new String(JournalCodec.wrap(data), StandardCharsets.UTF_8).stripTrailing());
            var path = directory.resolve("tampered-" + kind + ".ndjson");
            Files.writeString(path, String.join("\n", lines) + "\n");
            assertThrows(IOException.class, () -> JournalReplay.read(path), "Case " + kind);
            assertThrows(IOException.class, () -> JournalReplay.read(path, 0), "Prefix must still validate the whole file");
        }
    }

    @Test void rejectsBadUtf8DuplicateKeysEmptyFilesAndOversizedLines() throws Exception {
        var path = directory.resolve("bytes.ndjson");
        Files.write(path, new byte[]{(byte) 0xff, '\n'});
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        Files.write(path, new byte[0]);
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        var header = new String(JournalCodec.initialization(manifest("duplicate")), StandardCharsets.UTF_8);
        Files.writeString(path, "{\"checksum\":\"duplicate\"," + header.substring(1));
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        Files.writeString(path, "x".repeat(JournalCodec.MAX_RECORD_BYTES) + "\n");
        assertThrows(IOException.class, () -> JournalReplay.read(path));
    }

    @Test void readerAndRecoveryRefuseActiveWriterAndLifecycleIsExplicit() throws Exception {
        var path = directory.resolve("active.ndjson");
        var run = JournaledExchange.create(path, manifest("active"));
        assertThrows(IOException.class, () -> JournalReplay.read(path));
        assertThrows(IOException.class, () -> JournalReplay.recoverIncompleteTail(path));
        run.execute(ASK, 10);
        var before = run.snapshot();
        assertThrows(IllegalArgumentException.class, () -> run.execute(BUY, 9));
        assertThrows(IllegalArgumentException.class, () -> run.execute(BUY, -1));
        assertEquals(before, run.snapshot()); assertFalse(run.isFailed());
        run.execute(BUY, 10);
        run.close(); run.close();
        assertThrows(IllegalStateException.class, () -> run.execute(BUY, 11));
        var bytes = Files.readAllBytes(path);
        assertThrows(IOException.class, () -> JournaledExchange.create(path, manifest("again")));
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertTrue(JournalReplay.read(path).sealed());
    }

    @Test void multiBufferSweepAndMixedJournalRunsHaveDeterministicHashes() throws Exception {
        var traders = new TreeMap<Long, Exchange.Endowment>();
        for (long id = 1; id <= 51; id++) traders.put(id, new Exchange.Endowment(1_000_000, 100));
        var path = directory.resolve("sweep.ndjson");
        ExchangeSnapshot expected;
        try (var run = JournaledExchange.create(path, new RunManifest("sweep", 7, traders, 20_000))) {
            for (long id = 1; id <= 50; id++) run.execute(new PlaceOrder(id, id, "AIX", Side.SELL, TimeInForce.GTC, 100 + id, 1), id);
            run.execute(new PlaceOrder(51, 51, "AIX", Side.BUY, TimeInForce.IOC, 200, 50), 51);
            expected = run.snapshot();
        }
        assertTrue(Files.readAllLines(path).get(51).length() > 8_192);
        assertEquals(expected, JournalReplay.read(path).snapshot());
        var left = directory.resolve("left.ndjson"); var right = directory.resolve("right.ndjson");
        try (var a = JournaledExchange.create(left, manifest("left")); var b = JournaledExchange.create(right, manifest("right"))) {
            var random = new Random(17);
            for (int i = 0; i < 250; i++) {
                var command = new PlaceOrder(a.snapshot().nextOrderId(), 1 + random.nextInt(2), "AIX",
                        random.nextBoolean() ? Side.BUY : Side.SELL, i % 3 == 0 ? TimeInForce.IOC : TimeInForce.GTC,
                        95 + random.nextInt(11), 1 + random.nextInt(8));
                assertEquals(a.execute(command, i), b.execute(command, i));
            }
        }
        var a = JournalReplay.read(left); var b = JournalReplay.read(right);
        assertEquals(a.snapshot(), b.snapshot()); assertEquals(a.stateHash(), b.stateHash()); assertEquals(a.eventDigest(), b.eventDigest());
    }
}
