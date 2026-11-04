package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

public final class JournalReplay {
    private JournalReplay() {}
    public record Report(RunManifest manifest, ExchangeSnapshot snapshot, String stateHash, String eventDigest,
                         long journalCommands, long journalBytes, boolean sealed) {}
    private record Scan(Report report, long completeBytes, long tailBytes) {}

    public static Report read(Path path) throws IOException { return read(path, Long.MAX_VALUE); }

    /** A prefix is captured at a committed command boundary; the entire journal is still validated. */
    public static Report read(Path path, long throughCommandSequence) throws IOException {
        if (throughCommandSequence < 0) throw new IllegalArgumentException("Replay boundary must be nonnegative");
        try (var file = FileChannel.open(path, StandardOpenOption.READ); var lock = JournalFile.lock(file, true)) {
            return scan(file, throughCommandSequence, false).report();
        }
    }
    public static String stateHash(ExchangeSnapshot state) throws IOException { return JournalCodec.stateHash(state); }

    /** Explicitly discards only an unterminated tail after validating every complete prefix record. */
    public static long recoverIncompleteTail(Path path) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = JournalFile.lock(file, false)) {
            var scan = scan(file, Long.MAX_VALUE, true);
            if (scan.tailBytes() > 0) { file.truncate(scan.completeBytes()); file.force(true); }
            return scan.tailBytes();
        }
    }

    private static Scan scan(FileChannel file, long through, boolean allowTail) throws IOException {
        long size = file.size();
        if (size > JournalCodec.MAX_JOURNAL_BYTES) throw new IOException("Journal exceeds 256 MiB");
        var buffer = ByteBuffer.allocate(8_192);
        var line = new ByteArrayOutputStream();
        var state = new ReadState(through);
        long position = 0;
        long complete = 0;
        file.position(0);
        int read;
        while ((read = file.read(buffer)) != -1) {
            if (read == 0) throw new IOException("Journal read made no progress");
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte value = buffer.get(); position++;
                if (value == '\n') {
                    if (line.size() == 0) throw new IOException("Empty journal record");
                    try { state.accept(JournalCodec.data(line.toByteArray())); }
                    catch (RuntimeException invalid) { throw new IOException("Invalid recorded event state", invalid); }
                    line.reset(); complete = position;
                } else {
                    if (line.size() >= JournalCodec.MAX_RECORD_BYTES - 1) throw new IOException("Journal record exceeds 8 MiB");
                    line.write(value);
                }
            }
            buffer.clear();
        }
        if (file.size() != size) throw new IOException("Journal changed during read");
        if (line.size() > 0 && !allowTail) throw new IOException("Torn journal tail: missing final LF; explicit recovery required");
        return new Scan(state.report(size), complete, position - complete);
    }

    private static final class ReadState {
        final long through;
        final MessageDigest allEvents = JournalCodec.digest();
        final MessageDigest selectedEvents = JournalCodec.digest();
        RunManifest manifest;
        Exchange.Replayer replay;
        ExchangeSnapshot selected;
        long commands;
        long lastTime;
        boolean sealed;

        ReadState(long through) { this.through = through; }

        void accept(tools.jackson.databind.JsonNode data) throws IOException {
            if (manifest == null) {
                manifest = JournalCodec.readManifest(data);
                replay = Exchange.replayer(manifest.traders(), manifest.maxOpenOrders());
                if (through == 0) selected = replay.snapshot();
                return;
            }
            if (sealed) throw new IOException("Record after run completion");
            switch (JournalCodec.text(data, "kind")) {
                case "Command" -> {
                    var entry = JournalCodec.readCommand(data);
                    if (entry.commandSequence() != commands + 1 || entry.simTimeNanos() < lastTime) throw new IOException("Command sequence/time regression");
                    replay.accept(entry.command(), entry.events());
                    var bytes = JournalCodec.eventBytes(entry.simTimeNanos(), entry.events());
                    allEvents.update(bytes); allEvents.update((byte) '\n');
                    if (entry.commandSequence() <= through) { selectedEvents.update(bytes); selectedEvents.update((byte) '\n'); }
                    commands = entry.commandSequence(); lastTime = entry.simTimeNanos();
                    if (commands == through) selected = replay.snapshot();
                }
                case "RunCompleted" -> {
                    var completed = JournalCodec.readCompletion(data);
                    var state = replay.snapshot();
                    if (completed.commandSequence() != commands || completed.eventSequence() != state.eventSequence()
                            || completed.simTimeNanos() != lastTime
                            || !completed.stateHash().equals(JournalCodec.stateHash(state))
                            || !completed.eventDigest().equals(JournalCodec.hex(allEvents.digest())))
                        throw new IOException("Completion hash/counter mismatch");
                    sealed = true;
                }
                default -> throw new IOException("Unknown journal record type");
            }
        }

        Report report(long bytes) throws IOException {
            if (manifest == null) throw new IOException("Missing run initialization");
            if (through == Long.MAX_VALUE) selected = replay.snapshot();
            if (selected == null) throw new IOException("Requested command boundary is beyond journal end");
            return new Report(manifest, selected, JournalCodec.stateHash(selected), JournalCodec.hex(selectedEvents.digest()), commands, bytes, sealed);
        }
    }
}
