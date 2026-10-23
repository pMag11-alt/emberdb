package dev.emberdb.commands;

import java.util.ArrayList;
import java.util.List;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.ScoreRange;
import dev.emberdb.store.SkipList;
import dev.emberdb.store.ZSetValue;
import dev.emberdb.util.Numbers;

import static dev.emberdb.engine.Command.Flag.WRITE;

/** The sorted-set type: leaderboards, priority queues, time-ordered indexes. */
final class SortedSetCommands {

    private SortedSetCommands() {}

    static void register(CommandTable t) {
        t.add("zadd", -4, SortedSetCommands::zadd, WRITE);
        t.add("zincrby", 4, SortedSetCommands::zincrby, WRITE);
        t.add("zrem", -3, SortedSetCommands::zrem, WRITE);
        t.add("zscore", 3, SortedSetCommands::zscore);
        t.add("zmscore", -3, SortedSetCommands::zmscore);
        t.add("zcard", 2, c -> Reply.integer(sizeOf(c.db().getZSet(c.arg(1)))));
        t.add("zcount", 4, SortedSetCommands::zcount);
        t.add("zrank", -3, c -> zrank(c, false));
        t.add("zrevrank", -3, c -> zrank(c, true));
        t.add("zrange", -4, SortedSetCommands::zrange);
        t.add("zrevrange", -4, SortedSetCommands::zrevrange);
        t.add("zrangebyscore", -4, c -> rangeByScore(c, false));
        t.add("zrevrangebyscore", -4, c -> rangeByScore(c, true));
        t.add("zpopmin", -2, c -> pop(c, false), WRITE);
        t.add("zpopmax", -2, c -> pop(c, true), WRITE);
    }

    /** ZADD key [NX | XX] [GT | LT] [CH] [INCR] score member [score member ...] */
    private static Reply zadd(CommandContext c) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        boolean ch = false;
        boolean incr = false;
        int i = 2;
        while (i < c.argc() && isZaddFlag(c.option(i))) {
            switch (c.option(i)) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GT" -> gt = true;
                case "LT" -> lt = true;
                case "CH" -> ch = true;
                default -> incr = true;
            }
            i++;
        }
        int pairs = c.argc() - i;
        if (pairs == 0 || pairs % 2 != 0) {
            throw CommandException.syntax();
        }
        if (nx && xx) {
            throw new CommandException("ERR XX and NX options at the same time are not compatible");
        }
        if ((gt && lt) || (nx && (gt || lt))) {
            throw new CommandException("ERR GT, LT, and/or NX options at the same time are not compatible");
        }
        if (incr && pairs > 2) {
            throw new CommandException("ERR INCR option supports a single increment-element pair");
        }
        // Parse every score before changing anything, so a bad score leaves the set untouched.
        double[] scores = new double[pairs / 2];
        for (int p = 0; p < scores.length; p++) {
            scores[p] = c.doubleArg(i + 2 * p);
        }

        String key = c.arg(1);
        ZSetValue zset = c.db().getZSet(key);
        if (zset == null) {
            if (xx) {
                return incr ? Reply.NIL : Reply.ZERO;
            }
            zset = c.db().getOrCreateZSet(key);
        }
        int added = 0;
        int updated = 0;
        Double incrResult = null;
        for (int p = 0; p < scores.length; p++) {
            String member = c.arg(i + 2 * p + 1);
            Double current = zset.score(member);
            if (current == null) {
                if (xx) {
                    continue;
                }
                zset.put(member, scores[p]);
                incrResult = scores[p];
                added++;
                continue;
            }
            if (nx) {
                continue;
            }
            double score = incr ? current + scores[p] : scores[p];
            if (Double.isNaN(score)) {
                throw new CommandException("ERR resulting score is not a number (NaN)");
            }
            if ((gt && score <= current) || (lt && score >= current)) {
                continue;
            }
            incrResult = score;
            if (score != current) {
                zset.put(member, score);
                updated++;
            }
        }
        if (added + updated > 0) {
            c.db().modified(key);
        }
        if (incr) {
            return incrResult == null ? Reply.NIL : Reply.dbl(incrResult);
        }
        return Reply.integer(ch ? added + updated : added);
    }

    private static boolean isZaddFlag(String option) {
        return switch (option) {
            case "NX", "XX", "GT", "LT", "CH", "INCR" -> true;
            default -> false;
        };
    }

    private static Reply zincrby(CommandContext c) {
        String key = c.arg(1);
        double delta = c.doubleArg(2);
        String member = c.arg(3);
        ZSetValue existing = c.db().getZSet(key);
        Double current = existing == null ? null : existing.score(member);
        double score = current == null ? delta : current + delta;
        if (Double.isNaN(score)) {
            throw new CommandException("ERR resulting score is not a number (NaN)");
        }
        c.db().getOrCreateZSet(key).put(member, score);
        c.db().modified(key);
        return Reply.dbl(score);
    }

    private static Reply zrem(CommandContext c) {
        String key = c.arg(1);
        ZSetValue zset = c.db().getZSet(key);
        if (zset == null) {
            return Reply.ZERO;
        }
        int removed = 0;
        for (String member : c.args(2)) {
            if (zset.remove(member)) {
                removed++;
            }
        }
        if (removed > 0) {
            c.db().modified(key);
        }
        return Reply.integer(removed);
    }

    private static Reply zscore(CommandContext c) {
        ZSetValue zset = c.db().getZSet(c.arg(1));
        Double score = zset == null ? null : zset.score(c.arg(2));
        return score == null ? Reply.NIL : Reply.dbl(score);
    }

    private static Reply zmscore(CommandContext c) {
        ZSetValue zset = c.db().getZSet(c.arg(1));
        List<Reply> scores = new ArrayList<>();
        for (String member : c.args(2)) {
            Double score = zset == null ? null : zset.score(member);
            scores.add(score == null ? Reply.NIL : Reply.dbl(score));
        }
        return Reply.array(scores);
    }

    /** ZCOUNT is O(log n): the difference between the ranks of the first and last node in range. */
    private static Reply zcount(CommandContext c) {
        ScoreRange range = parseRange(c.arg(2), c.arg(3));
        ZSetValue zset = c.db().getZSet(c.arg(1));
        if (zset == null || range.isEmpty()) {
            return Reply.ZERO;
        }
        SkipList list = zset.ordered();
        SkipList.Node first = list.firstInRange(range);
        if (first == null) {
            return Reply.ZERO;
        }
        SkipList.Node last = list.lastInRange(range);
        return Reply.integer(list.rank(last.score(), last.member()) - list.rank(first.score(), first.member()) + 1);
    }

    /** ZRANK key member [WITHSCORE] */
    private static Reply zrank(CommandContext c, boolean reverse) {
        if (c.argc() > 4 || (c.argc() == 4 && !c.option(3).equals("WITHSCORE"))) {
            throw CommandException.syntax();
        }
        ZSetValue zset = c.db().getZSet(c.arg(1));
        int rank = zset == null ? -1 : zset.rank(c.arg(2));
        if (rank < 0) {
            return c.argc() == 4 ? Reply.NIL_ARRAY : Reply.NIL;
        }
        long result = reverse ? zset.size() - 1 - rank : rank;
        if (c.argc() == 4) {
            return Reply.array(Reply.integer(result), Reply.dbl(zset.score(c.arg(2))));
        }
        return Reply.integer(result);
    }

    /** ZRANGE key start stop [REV] [WITHSCORES]: by rank. */
    private static Reply zrange(CommandContext c) {
        boolean reverse = false;
        boolean withScores = false;
        for (int i = 4; i < c.argc(); i++) {
            switch (c.option(i)) {
                case "REV" -> reverse = true;
                case "WITHSCORES" -> withScores = true;
                default -> throw CommandException.syntax();
            }
        }
        return rangeByRank(c, reverse, withScores);
    }

    private static Reply zrevrange(CommandContext c) {
        if (c.argc() > 5 || (c.argc() == 5 && !c.option(4).equals("WITHSCORES"))) {
            throw CommandException.syntax();
        }
        return rangeByRank(c, true, c.argc() == 5);
    }

    private static Reply rangeByRank(CommandContext c, boolean reverse, boolean withScores) {
        long start = c.longArg(2);
        long stop = c.longArg(3);
        ZSetValue zset = c.db().getZSet(c.arg(1));
        IndexRange range = zset == null ? null : IndexRange.of(start, stop, zset.size());
        if (range == null) {
            return Reply.EMPTY_ARRAY;
        }
        // One O(log n) descent to the first node, then follow the level-0 links.
        SkipList.Node node = zset.ordered().byRank(reverse ? zset.size() - 1 - range.from() : range.from());
        List<Reply> out = new ArrayList<>(range.length() * (withScores ? 2 : 1));
        for (int n = 0; n < range.length(); n++) {
            add(out, node, withScores);
            node = reverse ? node.prev() : node.next();
        }
        return withScores ? Reply.pairs(out) : Reply.array(out);
    }

    /** ZRANGEBYSCORE key min max [WITHSCORES] [LIMIT offset count]; ZREVRANGEBYSCORE takes max before min. */
    private static Reply rangeByScore(CommandContext c, boolean reverse) {
        ScoreRange range = reverse ? parseRange(c.arg(3), c.arg(2)) : parseRange(c.arg(2), c.arg(3));
        boolean withScores = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < c.argc(); i++) {
            String option = c.option(i);
            if (option.equals("WITHSCORES")) {
                withScores = true;
            } else if (option.equals("LIMIT") && i + 2 < c.argc()) {
                offset = c.longArg(i + 1);
                count = c.longArg(i + 2);
                i += 2;
            } else {
                throw CommandException.syntax();
            }
        }
        ZSetValue zset = c.db().getZSet(c.arg(1));
        if (zset == null || range.isEmpty() || offset < 0) {
            return Reply.EMPTY_ARRAY;
        }
        SkipList.Node node = reverse ? zset.ordered().lastInRange(range) : zset.ordered().firstInRange(range);
        List<Reply> out = new ArrayList<>();
        while (node != null && (reverse ? range.aboveMin(node.score()) : range.belowMax(node.score()))) {
            if (offset > 0) {
                offset--;
            } else if (count == 0) {
                break;
            } else {
                add(out, node, withScores);
                count--;
            }
            node = reverse ? node.prev() : node.next();
        }
        return withScores ? Reply.pairs(out) : Reply.array(out);
    }

    /** ZPOPMIN / ZPOPMAX key [count]. With a count, RESP3 clients get [member, score] pairs. */
    private static Reply pop(CommandContext c, boolean max) {
        if (c.argc() > 3) {
            throw CommandException.syntax();
        }
        long count = c.argc() == 3 ? c.longArg(2) : 1;
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        String key = c.arg(1);
        ZSetValue zset = c.db().getZSet(key);
        List<Reply> out = new ArrayList<>();
        if (zset == null) {
            return Reply.EMPTY_ARRAY;
        }
        for (long n = 0; n < count && zset.size() > 0; n++) {
            SkipList.Node node = max ? zset.ordered().last() : zset.ordered().first();
            add(out, node, true);
            zset.remove(node.member());
        }
        if (!out.isEmpty()) {
            c.db().modified(key);
        }
        return c.argc() == 3 ? Reply.pairs(out) : Reply.array(out);
    }

    private static void add(List<Reply> out, SkipList.Node node, boolean withScores) {
        out.add(Reply.bulk(node.member()));
        if (withScores) {
            out.add(Reply.dbl(node.score()));
        }
    }

    /** Parses min/max bounds: {@code 1.5}, {@code (1.5} for exclusive, {@code -inf}, {@code +inf}. */
    static ScoreRange parseRange(String min, String max) {
        boolean minExclusive = min.startsWith("(");
        boolean maxExclusive = max.startsWith("(");
        Double lo = Numbers.parseDouble(minExclusive ? min.substring(1) : min);
        Double hi = Numbers.parseDouble(maxExclusive ? max.substring(1) : max);
        if (lo == null || hi == null) {
            throw new CommandException("ERR min or max is not a float");
        }
        return new ScoreRange(lo, minExclusive, hi, maxExclusive);
    }

    private static int sizeOf(ZSetValue zset) {
        return zset == null ? 0 : zset.size();
    }
}
