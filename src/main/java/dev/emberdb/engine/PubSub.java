package dev.emberdb.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.emberdb.protocol.Reply;
import dev.emberdb.util.Glob;

/**
 * Channel and pattern subscriptions. This works without the engine's command lock, so a PUBLISH that has to write
 * to many sockets never holds up commands on other connections. Each map update is a single atomic
 * {@code compute}, so a subscribe racing with the last unsubscribe from the same channel cannot be lost.
 */
public final class PubSub {

    private final ConcurrentHashMap<String, Set<Session>> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Session>> patterns = new ConcurrentHashMap<>();

    /** Returns the session's total number of subscriptions afterwards. */
    public int subscribe(Session session, String channel) {
        if (session.channels.add(channel)) {
            add(channels, channel, session);
        }
        return session.subscriptionCount();
    }

    public int unsubscribe(Session session, String channel) {
        if (session.channels.remove(channel)) {
            remove(channels, channel, session);
        }
        return session.subscriptionCount();
    }

    public int psubscribe(Session session, String pattern) {
        if (session.patterns.add(pattern)) {
            add(patterns, pattern, session);
        }
        return session.subscriptionCount();
    }

    public int punsubscribe(Session session, String pattern) {
        if (session.patterns.remove(pattern)) {
            remove(patterns, pattern, session);
        }
        return session.subscriptionCount();
    }

    public void unsubscribeAll(Session session) {
        for (String channel : List.copyOf(session.channels)) {
            unsubscribe(session, channel);
        }
        for (String pattern : List.copyOf(session.patterns)) {
            punsubscribe(session, pattern);
        }
    }

    /** Delivers a message and returns how many subscriptions received it. */
    public int publish(String channel, String message) {
        int receivers = 0;
        Set<Session> subscribers = channels.get(channel);
        if (subscribers != null) {
            Reply reply = Reply.push(Reply.bulk("message"), Reply.bulk(channel), Reply.bulk(message));
            for (Session session : subscribers) {
                session.push(reply);
                receivers++;
            }
        }
        for (Map.Entry<String, Set<Session>> entry : patterns.entrySet()) {
            if (Glob.matches(entry.getKey(), channel)) {
                Reply reply = Reply.push(Reply.bulk("pmessage"), Reply.bulk(entry.getKey()), Reply.bulk(channel),
                        Reply.bulk(message));
                for (Session session : entry.getValue()) {
                    session.push(reply);
                    receivers++;
                }
            }
        }
        return receivers;
    }

    /** Channels with at least one subscriber, optionally filtered by a glob pattern. */
    public List<String> activeChannels(String pattern) {
        List<String> result = new ArrayList<>();
        for (String channel : channels.keySet()) {
            if (pattern == null || Glob.matches(pattern, channel)) {
                result.add(channel);
            }
        }
        return result;
    }

    public int subscriberCount(String channel) {
        Set<Session> subscribers = channels.get(channel);
        return subscribers == null ? 0 : subscribers.size();
    }

    public int patternCount() {
        return patterns.size();
    }

    public List<String> channelsOf(Session session) {
        return List.copyOf(session.channels);
    }

    public List<String> patternsOf(Session session) {
        return List.copyOf(session.patterns);
    }

    private static void add(ConcurrentHashMap<String, Set<Session>> map, String key, Session session) {
        map.compute(key, (k, set) -> {
            Set<Session> s = set != null ? set : ConcurrentHashMap.newKeySet();
            s.add(session);
            return s;
        });
    }

    private static void remove(ConcurrentHashMap<String, Set<Session>> map, String key, Session session) {
        map.computeIfPresent(key, (k, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }
}
