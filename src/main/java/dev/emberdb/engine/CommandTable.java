package dev.emberdb.engine;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.emberdb.engine.Command.Flag;

/** Command name (lower case) → definition. */
public final class CommandTable {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void add(String name, int arity, Command.Handler handler, Flag... flags) {
        EnumSet<Flag> set = flags.length == 0 ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(Arrays.asList(flags));
        if (commands.putIfAbsent(name, new Command(name, arity, Collections.unmodifiableSet(set), handler)) != null) {
            throw new IllegalStateException("duplicate command " + name);
        }
    }

    public Command get(String name) {
        return commands.get(name);
    }

    public Collection<Command> all() {
        return Collections.unmodifiableCollection(commands.values());
    }

    public int size() {
        return commands.size();
    }
}
