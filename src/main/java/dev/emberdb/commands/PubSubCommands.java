package dev.emberdb.commands;

import java.util.ArrayList;
import java.util.List;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.engine.PubSub;
import dev.emberdb.engine.Session;
import dev.emberdb.protocol.Reply;

import static dev.emberdb.engine.Command.Flag.NO_LOCK;
import static dev.emberdb.engine.Command.Flag.NO_MULTI;
import static dev.emberdb.engine.Command.Flag.PUBSUB_CONTEXT;

/**
 * SUBSCRIBE, PSUBSCRIBE, their UNSUBSCRIBE counterparts, PUBLISH and PUBSUB. A subscribe command sends one
 * confirmation per channel, so these handlers send their own replies and return null.
 */
final class PubSubCommands {

    private PubSubCommands() {}

    static void register(CommandTable t) {
        t.add("subscribe", -2, c -> subscribe(c, false), NO_LOCK, PUBSUB_CONTEXT, NO_MULTI);
        t.add("psubscribe", -2, c -> subscribe(c, true), NO_LOCK, PUBSUB_CONTEXT, NO_MULTI);
        t.add("unsubscribe", -1, c -> unsubscribe(c, false), NO_LOCK, PUBSUB_CONTEXT, NO_MULTI);
        t.add("punsubscribe", -1, c -> unsubscribe(c, true), NO_LOCK, PUBSUB_CONTEXT, NO_MULTI);
        t.add("publish", 3, c -> Reply.integer(c.engine().pubsub().publish(c.arg(1), c.arg(2))), NO_LOCK);
        t.add("pubsub", -2, PubSubCommands::pubsub, NO_LOCK);
    }

    private static Reply subscribe(CommandContext c, boolean pattern) {
        PubSub pubsub = c.engine().pubsub();
        Session session = c.session();
        for (String name : c.args(1)) {
            int count = pattern ? pubsub.psubscribe(session, name) : pubsub.subscribe(session, name);
            session.send(confirmation(pattern ? "psubscribe" : "subscribe", name, count));
        }
        return null;
    }

    private static Reply unsubscribe(CommandContext c, boolean pattern) {
        PubSub pubsub = c.engine().pubsub();
        Session session = c.session();
        String kind = pattern ? "punsubscribe" : "unsubscribe";
        List<String> names = c.argc() > 1 ? c.args(1)
                : pattern ? pubsub.patternsOf(session) : pubsub.channelsOf(session);
        if (names.isEmpty()) {
            session.send(confirmation(kind, null, session.subscriptionCount()));
        }
        for (String name : names) {
            int count = pattern ? pubsub.punsubscribe(session, name) : pubsub.unsubscribe(session, name);
            session.send(confirmation(kind, name, count));
        }
        return null;
    }

    private static Reply confirmation(String kind, String name, int count) {
        return Reply.push(Reply.bulk(kind), Reply.bulk(name), Reply.integer(count));
    }

    /** PUBSUB CHANNELS [pattern] | NUMSUB [channel ...] | NUMPAT */
    private static Reply pubsub(CommandContext c) {
        PubSub pubsub = c.engine().pubsub();
        switch (c.option(1)) {
            case "CHANNELS" -> {
                return Reply.bulks(pubsub.activeChannels(c.argc() > 2 ? c.arg(2) : null));
            }
            case "NUMSUB" -> {
                List<Reply> out = new ArrayList<>();
                for (String channel : c.args(2)) {
                    out.add(Reply.bulk(channel));
                    out.add(Reply.integer(pubsub.subscriberCount(channel)));
                }
                return Reply.array(out);
            }
            case "NUMPAT" -> {
                return Reply.integer(pubsub.patternCount());
            }
            default -> throw new CommandException("ERR unknown subcommand '" + c.arg(1) + "'. Try PUBSUB HELP.");
        }
    }
}
