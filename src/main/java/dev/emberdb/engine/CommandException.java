package dev.emberdb.engine;

import java.io.Serial;

/** Turns into an error reply. The message starts with the error code, e.g. {@code "ERR syntax error"}. */
public final class CommandException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CommandException(String message) {
        super(message, null, false, false);
    }

    public static CommandException syntax() {
        return new CommandException("ERR syntax error");
    }

    public static CommandException notInteger() {
        return new CommandException("ERR value is not an integer or out of range");
    }

    public static CommandException notFloat() {
        return new CommandException("ERR value is not a valid float");
    }
}
