package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeCommand;
import io.aiexchange.core.ExchangeEvent;
import io.aiexchange.core.ExchangeSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/** Single-thread-confined, persist-before-apply owner of one new run. */
public final class JournaledExchange implements AutoCloseable {
    public static final long DEFAULT_MAX_BYTES = JournalCodec.MAX_JOURNAL_BYTES;
    private static final int COMPLETION_RESERVE = 2_048;
    private final Exchange exchange;
    private final AppendSink sink;
    private final long maxBytes;
    private final MessageDigest eventDigest = JournalCodec.digest();
    private long writtenBytes;
    private long lastTime;
    private boolean failed;
    private boolean closed;

    public static JournaledExchange create(Path path, RunManifest manifest) throws IOException {
        return create(path, manifest, DEFAULT_MAX_BYTES);
    }
    public static JournaledExchange create(Path path, RunManifest manifest, long maxBytes) throws IOException {
        var initialization = prepare(manifest, maxBytes);
        var file = JournalFile.create(path);
        try { return new JournaledExchange(manifest, maxBytes, file, initialization); }
        catch (IOException | RuntimeException failure) {
            try { file.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    JournaledExchange(RunManifest manifest, long maxBytes, AppendSink sink) throws IOException {
        this(manifest, maxBytes, sink, prepare(manifest, maxBytes));
    }
    private JournaledExchange(RunManifest manifest, long maxBytes, AppendSink sink, byte[] initialization) throws IOException {
        this.exchange = new Exchange(manifest.traders(), manifest.maxOpenOrders());
        this.sink = sink;
        this.maxBytes = maxBytes;
        sink.append(initialization);
        writtenBytes = initialization.length;
    }
    private static byte[] prepare(RunManifest manifest, long maxBytes) throws IOException {
        if (manifest == null || maxBytes <= 0 || maxBytes > DEFAULT_MAX_BYTES)
            throw new IllegalArgumentException("Journal limit must be positive and at most 256 MiB");
        var header = JournalCodec.initialization(manifest);
        if (header.length + COMPLETION_RESERVE > maxBytes) throw new IllegalArgumentException("Limit cannot hold initialization and completion");
        return header;
    }

    public List<ExchangeEvent> execute(ExchangeCommand command, long simTimeNanos) throws IOException {
        if (closed || failed) throw new IllegalStateException("Run is closed or failed");
        if (simTimeNanos < 0 || simTimeNanos < lastTime) throw new IllegalArgumentException("Simulation time must be nonnegative and nondecreasing");
        var plan = exchange.plan(command);
        try {
            var bytes = JournalCodec.command(command, simTimeNanos, plan.events());
            var digestBytes = JournalCodec.eventBytes(simTimeNanos, plan.events());
            if (writtenBytes + bytes.length + COMPLETION_RESERVE > maxBytes) throw new IOException("Journal size limit reached");
            sink.append(bytes);
            writtenBytes += bytes.length;
            exchange.apply(plan);
            eventDigest.update(digestBytes); eventDigest.update((byte) '\n');
            lastTime = simTimeNanos;
            return plan.events();
        } catch (IOException | RuntimeException failure) {
            failed = true;
            throw failure;
        }
    }
    public ExchangeSnapshot snapshot() { return exchange.snapshot(); }
    public long nextOrderId() { return exchange.nextOrderId(); }
    public boolean isOrderOpen(long orderId) { return exchange.isOrderOpen(orderId); }
    public boolean isFailed() { return failed; }

    /** Stop admission and leave the journal unsealed when the owner fails outside execute. */
    public void abort() {
        if (!closed) failed = true;
    }

    @Override public void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try {
            if (!failed) {
                var footer = JournalCodec.completion(exchange.snapshot(), lastTime, JournalCodec.hex(eventDigest.digest()));
                if (footer.length > COMPLETION_RESERVE || writtenBytes + footer.length > maxBytes) throw new IOException("Completion exceeds reserved journal space");
                sink.append(footer);
                writtenBytes += footer.length;
            }
        } catch (IOException | RuntimeException problem) {
            failed = true;
            failure = problem instanceof IOException io ? io : new IOException("Cannot seal journal", problem);
        } finally {
            closed = true;
            try { sink.close(); }
            catch (IOException close) { failed = true; if (failure == null) failure = close; else failure.addSuppressed(close); }
        }
        if (failure != null) throw failure;
    }
}
