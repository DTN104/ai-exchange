package io.aiexchange.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class JournalFile implements AppendSink {
    private final FileChannel channel;
    private final FileLock lock;

    private JournalFile(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    static JournalFile create(Path path) throws IOException {
        var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try { return new JournalFile(channel, lock(channel, false)); }
        catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
    }

    static FileLock lock(FileChannel channel, boolean shared) throws IOException {
        try {
            var lock = channel.tryLock(0, Long.MAX_VALUE, shared);
            if (lock == null) throw new IOException("Journal is locked by another process");
            return lock;
        } catch (OverlappingFileLockException busy) { throw new IOException("Journal is already open", busy); }
    }

    @Override public void append(byte[] record) throws IOException {
        var buffer = ByteBuffer.wrap(record);
        while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw new IOException("Journal write made no progress");
        channel.force(true);
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
