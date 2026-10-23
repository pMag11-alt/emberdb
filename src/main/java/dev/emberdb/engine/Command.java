package dev.emberdb.engine;

import java.util.Set;

import dev.emberdb.protocol.Reply;

/**
 * A command definition.
 *
 * @param arity Redis convention: N means exactly N arguments including the name, -N means at least N
 */
public record Command(String name, int arity, Set<Flag> flags, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        /** Returns the reply, or null if the handler already sent its replies through the session. */
        Reply handle(CommandContext ctx);
    }

    public enum Flag {
        /** May modify the keyspace; logged to the append-only file when it does. */
        WRITE,
        /** Runs without the command lock (pub/sub has its own concurrent state). */
        NO_LOCK,
        /** MULTI, EXEC, DISCARD and WATCH: run immediately even inside MULTI. */
        TRANSACTION,
        /** Allowed before AUTH when a password is set. */
        NO_AUTH,
        /** Allowed while the connection is subscribed to channels. */
        PUBSUB_CONTEXT,
        /** Rejected inside MULTI. */
        NO_MULTI
    }

    public boolean has(Flag flag) {
        return flags.contains(flag);
    }

    public boolean acceptsArgCount(int argc) {
        return arity >= 0 ? argc == arity : argc >= -arity;
    }
}
