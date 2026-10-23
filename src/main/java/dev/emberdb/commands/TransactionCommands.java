package dev.emberdb.commands;

import dev.emberdb.engine.CommandTable;

import static dev.emberdb.engine.Command.Flag.NO_LOCK;
import static dev.emberdb.engine.Command.Flag.TRANSACTION;

/** MULTI, EXEC, DISCARD, WATCH, UNWATCH. The logic lives in the engine, which owns the WATCH table. */
final class TransactionCommands {

    private TransactionCommands() {}

    static void register(CommandTable t) {
        t.add("multi", 1, c -> c.engine().multi(c.session()), TRANSACTION, NO_LOCK);
        t.add("exec", 1, c -> c.engine().exec(c.session()), TRANSACTION);
        t.add("discard", 1, c -> c.engine().discard(c.session()), TRANSACTION);
        t.add("watch", -2, c -> c.engine().watch(c.session(), c.args(1)), TRANSACTION);
        t.add("unwatch", 1, c -> c.engine().unwatch(c.session()));
    }
}
