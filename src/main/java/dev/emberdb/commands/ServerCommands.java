package dev.emberdb.commands;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.emberdb.Config;
import dev.emberdb.engine.Command;
import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.engine.Engine;
import dev.emberdb.persistence.AppendOnlyFile;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.Database;
import dev.emberdb.util.Glob;

import static dev.emberdb.engine.Command.Flag.NO_LOCK;
import static dev.emberdb.engine.Command.Flag.NO_MULTI;
import static dev.emberdb.engine.Command.Flag.WRITE;

/** DBSIZE, FLUSHALL, INFO, TIME, CONFIG, COMMAND, BGREWRITEAOF, SHUTDOWN. */
final class ServerCommands {

    private ServerCommands() {}

    static void register(CommandTable t) {
        t.add("dbsize", 1, c -> Reply.integer(c.db().size()));
        t.add("flushall", -1, ServerCommands::flush, WRITE);
        t.add("flushdb", -1, ServerCommands::flush, WRITE);
        t.add("info", -1, ServerCommands::info);
        t.add("time", 1, ServerCommands::time, NO_LOCK);
        t.add("config", -2, ServerCommands::config);
        t.add("command", -1, ServerCommands::command, NO_LOCK);
        t.add("bgrewriteaof", 1, ServerCommands::rewriteAof, NO_MULTI);
        t.add("shutdown", -1, ServerCommands::shutdown, NO_MULTI);
    }

    private static Reply flush(CommandContext c) {
        if (c.argc() > 2 || (c.argc() == 2 && !Set.of("ASYNC", "SYNC").contains(c.option(1)))) {
            throw CommandException.syntax();
        }
        c.db().flush();
        return Reply.OK;
    }

    private static Reply time(CommandContext c) {
        long now = c.now();
        return Reply.bulks(Long.toString(now / 1000), Long.toString(now % 1000 * 1000));
    }

    private static Reply rewriteAof(CommandContext c) {
        try {
            c.engine().rewriteAof();
        } catch (IOException e) {
            throw new CommandException("ERR rewriting the append only file failed: " + e.getMessage());
        }
        return Reply.simple("Append only file rewritten");
    }

    /** SHUTDOWN: the AOF is flushed and fsynced on the way down. No reply; the connection just closes. */
    private static Reply shutdown(CommandContext c) {
        if (c.argc() > 2) {
            throw CommandException.syntax();
        }
        c.session().requestClose();
        c.engine().requestShutdown();
        return null;
    }

    // ---- CONFIG ------------------------------------------------------------------------------------------------

    private static Reply config(CommandContext c) {
        switch (c.option(1)) {
            case "GET" -> {
                if (c.argc() < 3) {
                    throw new CommandException("ERR wrong number of arguments for 'config|get' command");
                }
                Map<String, String> values = configValues(c.engine().config());
                List<Reply> out = new ArrayList<>();
                for (Map.Entry<String, String> e : values.entrySet()) {
                    for (String pattern : c.args(2)) {
                        if (Glob.matches(pattern.toLowerCase(Locale.ROOT), e.getKey())) {
                            out.add(Reply.bulk(e.getKey()));
                            out.add(Reply.bulk(e.getValue()));
                            break;
                        }
                    }
                }
                return Reply.dict(out);
            }
            case "SET" -> throw new CommandException("ERR CONFIG SET is not supported; restart with the new option");
            case "RESETSTAT" -> {
                return Reply.OK;
            }
            default -> throw new CommandException("ERR unknown subcommand '" + c.arg(1) + "'. Try CONFIG HELP.");
        }
    }

    private static Map<String, String> configValues(Config config) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bind", config.bind());
        values.put("port", Integer.toString(config.port()));
        values.put("requirepass", config.requirePass() == null ? "" : config.requirePass());
        values.put("maxkeys", Integer.toString(config.maxKeys()));
        values.put("appendonly", config.appendOnly() ? "yes" : "no");
        values.put("appendfsync", config.appendFsync().name().toLowerCase(Locale.ROOT));
        values.put("appendfilename", config.appendFilename());
        values.put("dir", config.dir().toAbsolutePath().toString());
        values.put("databases", "1");
        values.put("save", "");
        return values;
    }

    // ---- COMMAND -----------------------------------------------------------------------------------------------

    private static Reply command(CommandContext c) {
        CommandTable table = c.engine().commands();
        if (c.argc() == 1) {
            List<Reply> all = new ArrayList<>();
            for (Command command : table.all()) {
                all.add(describe(command));
            }
            return Reply.array(all);
        }
        switch (c.option(1)) {
            case "COUNT" -> {
                return Reply.integer(table.size());
            }
            case "LIST" -> {
                List<String> names = new ArrayList<>();
                for (Command command : table.all()) {
                    names.add(command.name());
                }
                return Reply.bulks(names);
            }
            case "INFO" -> {
                List<Reply> out = new ArrayList<>();
                for (String name : c.args(2)) {
                    Command command = table.get(name.toLowerCase(Locale.ROOT));
                    out.add(command == null ? Reply.NIL_ARRAY : describe(command));
                }
                return Reply.array(out);
            }
            case "DOCS" -> {
                // redis-cli asks for docs to show argument hints. Sending none is allowed.
                return Reply.dict(List.of());
            }
            default -> throw new CommandException("ERR unknown subcommand '" + c.arg(1) + "'. Try COMMAND HELP.");
        }
    }

    private static Reply describe(Command command) {
        List<Reply> flags = new ArrayList<>();
        flags.add(Reply.simple(command.has(Command.Flag.WRITE) ? "write" : "readonly"));
        if (command.has(Command.Flag.NO_LOCK)) {
            flags.add(Reply.simple("pubsub-safe"));
        }
        return Reply.array(Reply.bulk(command.name()), Reply.integer(command.arity()), Reply.array(flags),
                Reply.ZERO, Reply.ZERO, Reply.ZERO);
    }

    // ---- INFO --------------------------------------------------------------------------------------------------

    private static Reply info(CommandContext c) {
        Engine engine = c.engine();
        Database db = c.db();
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long uptime = (c.now() - engine.startedAt()) / 1000;
        AppendOnlyFile aof = engine.aof();

        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("server", List.of(
                "redis_version:" + Engine.REDIS_COMPAT_VERSION,
                "ember_version:" + Engine.VERSION,
                "redis_mode:standalone",
                "os:" + System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "java_version:" + Runtime.version(),
                "process_id:" + ProcessHandle.current().pid(),
                "tcp_port:" + engine.config().port(),
                "uptime_in_seconds:" + uptime,
                "uptime_in_days:" + uptime / 86400));
        sections.put("clients", List.of(
                "connected_clients:" + engine.sessions().size()));
        sections.put("memory", List.of(
                "used_memory:" + usedMemory,
                "used_memory_human:" + human(usedMemory),
                "jvm_heap_max:" + runtime.maxMemory(),
                "jvm_threads:" + ManagementFactory.getThreadMXBean().getThreadCount(),
                "maxkeys:" + engine.config().maxKeys(),
                "eviction_policy:" + (engine.config().maxKeys() > 0 ? "allkeys-lru" : "noeviction")));
        sections.put("persistence", List.of(
                "aof_enabled:" + (aof != null ? 1 : 0),
                "aof_fsync:" + engine.config().appendFsync().name().toLowerCase(Locale.ROOT),
                "aof_current_size:" + (aof != null ? aof.size() : 0),
                "aof_last_write_status:" + (aof != null && aof.lastWriteFailed() ? "err" : "ok")));
        sections.put("stats", List.of(
                "total_connections_received:" + engine.connectionsReceived(),
                "total_commands_processed:" + engine.commandsProcessed(),
                "expired_keys:" + db.expiredKeys(),
                "evicted_keys:" + db.evictedKeys(),
                "keyspace_hits:" + db.hits(),
                "keyspace_misses:" + db.misses(),
                "pubsub_channels:" + engine.pubsub().activeChannels(null).size(),
                "pubsub_patterns:" + engine.pubsub().patternCount()));
        sections.put("keyspace", db.size() == 0 ? List.of()
                : List.of("db0:keys=" + db.size() + ",expires=" + db.expiresCount() + ",avg_ttl=0"));

        String wanted = c.argc() > 1 ? c.arg(1).toLowerCase(Locale.ROOT) : "default";
        boolean everything = wanted.equals("default") || wanted.equals("all") || wanted.equals("everything");
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            if (!everything && !section.getKey().equals(wanted)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("\r\n");
            }
            String title = section.getKey();
            out.append("# ").append(Character.toUpperCase(title.charAt(0))).append(title.substring(1)).append("\r\n");
            for (String line : section.getValue()) {
                out.append(line).append("\r\n");
            }
        }
        return Reply.verbatim(out.toString());
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2fK", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2fM", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2fG", bytes / (1024.0 * 1024 * 1024));
    }
}
