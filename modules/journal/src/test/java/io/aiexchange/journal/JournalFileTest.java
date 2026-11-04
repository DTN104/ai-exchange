package io.aiexchange.journal;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JournalFileTest {
    @TempDir Path directory;

    @Test void writesCompleteBytesAndLocksReadersUntilClosed() throws Exception {
        var path = directory.resolve("run.ndjson");
        var bytes = JournalCodec.initialization(JournalCodecTest.manifest("locked"));
        try (var writer = JournalFile.create(path)) {
            writer.append(bytes);
            assertArrayEquals(bytes, Files.readAllBytes(path));
            try (var reader = FileChannel.open(path, StandardOpenOption.READ)) {
                assertThrows(OverlappingFileLockException.class, () -> reader.tryLock(0, Long.MAX_VALUE, true));
            }
        }
        try (var reader = FileChannel.open(path, StandardOpenOption.READ); var lock = reader.tryLock(0, Long.MAX_VALUE, true)) {
            assertNotNull(lock);
        }
    }

    @Test void neverOverwritesExistingFile() throws Exception {
        var path = directory.resolve("existing.ndjson");
        Files.writeString(path, "keep me");
        assertThrows(FileAlreadyExistsException.class, () -> JournalFile.create(path));
        assertEquals("keep me", Files.readString(path));
    }
}
