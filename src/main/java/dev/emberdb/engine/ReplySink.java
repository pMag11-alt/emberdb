package dev.emberdb.engine;

import java.io.IOException;

import dev.emberdb.protocol.Reply;

/** Where a session's replies go: a socket in production, a list in tests. */
public interface ReplySink {

    /** Queues a reply, encoded for the given protocol version (2 or 3). It may sit in a buffer until {@link #flush()}. */
    void write(Reply reply, int protocol) throws IOException;

    void flush() throws IOException;
}
