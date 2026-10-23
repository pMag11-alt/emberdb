package dev.emberdb.commands;

import dev.emberdb.engine.CommandTable;

/** Builds the table of every supported command. */
public final class Commands {

    private Commands() {}

    public static CommandTable table() {
        CommandTable table = new CommandTable();
        ConnectionCommands.register(table);
        ServerCommands.register(table);
        KeyCommands.register(table);
        StringCommands.register(table);
        ListCommands.register(table);
        HashCommands.register(table);
        SetCommands.register(table);
        SortedSetCommands.register(table);
        TransactionCommands.register(table);
        PubSubCommands.register(table);
        return table;
    }
}
