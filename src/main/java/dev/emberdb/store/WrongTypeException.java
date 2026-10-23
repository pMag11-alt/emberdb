package dev.emberdb.store;

import java.io.Serial;

/** A command was used on a key that holds a different type, for example LPUSH on a string. */
public final class WrongTypeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    public WrongTypeException() {
        super(MESSAGE, null, false, false);
    }
}
