package dev.emberdb.protocol;

import java.io.IOException;
import java.io.Serial;

/** The client sent bytes that are not valid RESP. The connection is closed after reporting it. */
public final class ProtocolException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
