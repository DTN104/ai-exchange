package io.aiexchange.journal;

import java.io.IOException;

// The narrow IO boundary permits deterministic write/force failure tests.
interface AppendSink extends AutoCloseable {
    void append(byte[] record) throws IOException;
    @Override void close() throws IOException;
}
