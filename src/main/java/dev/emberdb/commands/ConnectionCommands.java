package dev.emberdb.commands;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.engine.Engine;
import dev.emberdb.engine.Session;
import dev.emberdb.protocol.Reply;
import dev.emberdb.util.Numbers;

import static dev.emberdb.engine.Command.Flag.NO_AUTH;
import static dev.emberdb.engine.Command.Flag.NO_LOCK;
import static dev.emberdb.engine.Command.Flag.PUBSUB_CONTEXT;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/** PING, ECHO, AUTH, HELLO, QUIT, SELECT, CLIENT. */
final class ConnectionCommands {

    private ConnectionCommands() {}

    static void register(CommandTable t) {
        t.add("ping", -1, ConnectionCommands::ping, NO_LOCK, PUBSUB_CONTEXT);
        t.add("echo", 2, c -> Reply.bulk(c.arg(1)), NO_LOCK);
        t.add("auth", -2, ConnectionCommands::auth, NO_LOCK, NO_AUTH);
        t.add("hello", -1, ConnectionCommands::hello, NO_LOCK, NO_AUTH);
        t.add("quit", -1, ConnectionCommands::quit, NO_LOCK, NO_AUTH, PUBSUB_CONTEXT);
        t.add("select", 2, ConnectionCommands::select, NO_LOCK);
        t.add("client", -2, ConnectionCommands::client, NO_LOCK);
    }

    private static Reply ping(CommandContext c) {
        if (c.argc() > 2) {
            throw new CommandException("ERR wrong number of arguments for 'ping' command");
        }
        if (c.session().isSubscribed() && c.session().protocol() == 2) {
            return Reply.bulks("pong", c.argc() == 2 ? c.arg(1) : "");
        }
        return c.argc() == 2 ? Reply.bulk(c.arg(1)) : Reply.PONG;
    }

    private static Reply auth(CommandContext c) {
        if (c.argc() > 3) {
            throw CommandException.syntax();
        }
        String user = c.argc() == 3 ? c.arg(1) : "default";
        authenticate(c, user, c.arg(c.argc() - 1));
        return Reply.OK;
    }

    private static void authenticate(CommandContext c, String user, String password) {
        String required = c.engine().config().requirePass();
        if (required == null) {
            throw new CommandException("ERR AUTH <password> called without any password configured for the default"
                    + " user. Are you sure your configuration is correct?");
        }
        // Constant-time comparison, so response timing does not reveal how much of a guess was right.
        boolean ok = user.equals("default")
                && MessageDigest.isEqual(password.getBytes(ISO_8859_1), required.getBytes(UTF_8));
        if (!ok) {
            throw new CommandException("WRONGPASS invalid username-password pair or user is disabled.");
        }
        c.session().setAuthenticated(true);
    }

    /** HELLO [protover [AUTH user pass] [SETNAME name]]: switches the connection to RESP2 or RESP3. */
    private static Reply hello(CommandContext c) {
        int i = 1;
        int protocol = c.session().protocol();
        if (c.argc() >= 2) {
            Long version = Numbers.parseLong(c.arg(1));
            if (version == null) {
                throw new CommandException("ERR Protocol version is not an integer or out of range");
            }
            if (version != 2 && version != 3) {
                throw new CommandException("NOPROTO unsupported protocol version");
            }
            protocol = version.intValue();
            i = 2;
        }
        String name = null;
        for (; i < c.argc(); i++) {
            String option = c.option(i);
            if (option.equals("AUTH") && i + 2 < c.argc()) {
                authenticate(c, c.arg(i + 1), c.arg(i + 2));
                i += 2;
            } else if (option.equals("SETNAME") && i + 1 < c.argc()) {
                name = c.arg(++i);
            } else {
                throw CommandException.syntax();
            }
        }
        if (!c.session().isAuthenticated()) {
            throw new CommandException("NOAUTH HELLO must be called with the client already authenticated, otherwise"
                    + " the HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and"
                    + " select the RESP protocol version at the same time");
        }
        if (name != null) {
            c.session().setName(checkedName(name));
        }
        c.session().setProtocol(protocol);
        return Reply.dict(List.of(
                Reply.bulk("server"), Reply.bulk("emberdb"),
                Reply.bulk("version"), Reply.bulk(Engine.REDIS_COMPAT_VERSION),
                Reply.bulk("proto"), Reply.integer(protocol),
                Reply.bulk("id"), Reply.integer(c.session().id()),
                Reply.bulk("mode"), Reply.bulk("standalone"),
                Reply.bulk("role"), Reply.bulk("master"),
                Reply.bulk("modules"), Reply.EMPTY_ARRAY));
    }

    private static Reply quit(CommandContext c) {
        c.session().requestClose();
        return Reply.OK;
    }

    /** There is a single database, so only SELECT 0 is accepted. */
    private static Reply select(CommandContext c) {
        if (c.longArg(1) != 0) {
            throw new CommandException("ERR DB index is out of range");
        }
        return Reply.OK;
    }

    private static Reply client(CommandContext c) {
        Session session = c.session();
        String sub = c.option(1);
        switch (sub) {
            case "SETNAME" -> {
                requireArgs(c, 3, sub);
                session.setName(checkedName(c.arg(2)));
                return Reply.OK;
            }
            case "GETNAME" -> {
                return session.name().isEmpty() ? Reply.NIL : Reply.bulk(session.name());
            }
            case "ID" -> {
                return Reply.integer(session.id());
            }
            case "SETINFO" -> {
                requireArgs(c, 4, sub);
                return Reply.OK;
            }
            case "INFO" -> {
                return Reply.verbatim(describe(c, session) + "\n");
            }
            case "LIST" -> {
                List<Session> sessions = new ArrayList<>(c.engine().sessions());
                sessions.sort((a, b) -> Long.compare(a.id(), b.id()));
                StringBuilder out = new StringBuilder();
                for (Session s : sessions) {
                    out.append(describe(c, s)).append('\n');
                }
                return Reply.verbatim(out.toString());
            }
            default -> throw new CommandException("ERR unknown subcommand '" + c.arg(1) + "'. Try CLIENT HELP.");
        }
    }

    private static String describe(CommandContext c, Session s) {
        long age = (c.now() - s.createdAt()) / 1000;
        return "id=" + s.id() + " addr=" + s.address() + " name=" + s.name() + " age=" + age
                + " sub=" + c.engine().pubsub().channelsOf(s).size() + " psub=" + c.engine().pubsub().patternsOf(s).size()
                + " multi=" + (s.inMulti() ? 1 : -1) + " cmd=" + s.lastCommand();
    }

    private static String checkedName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch <= ' ' || ch > '~') {
                throw new CommandException("ERR Client names cannot contain spaces, newlines or special characters.");
            }
        }
        return name;
    }

    private static void requireArgs(CommandContext c, int argc, String sub) {
        if (c.argc() != argc) {
            throw new CommandException(
                    "ERR wrong number of arguments for 'client|" + sub.toLowerCase(Locale.ROOT) + "' command");
        }
    }
}
