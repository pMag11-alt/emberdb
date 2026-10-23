package dev.emberdb.util;

/**
 * Redis-style glob matching, used by KEYS and PSUBSCRIBE.
 * <ul>
 *   <li>{@code *} matches any sequence, {@code ?} matches one character</li>
 *   <li>{@code [abc]}, {@code [a-z]} and {@code [^a]} match character classes</li>
 *   <li>{@code \x} matches {@code x} literally</li>
 * </ul>
 * Matching is greedy with a single backtrack point (the most recent {@code *}), so it runs in O(pattern × text)
 * time. Naive recursion is exponential on patterns like {@code a*a*a*a*b}, which once let a single KEYS call
 * stall a Redis server.
 */
public final class Glob {

    private Glob() {}

    public static boolean matches(String pattern, String text) {
        int p = 0;
        int t = 0;
        int starP = -1;
        int starT = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                starP = p++;
                starT = t;
                continue;
            }
            int next = p < pattern.length() ? matchOne(pattern, p, text.charAt(t)) : -1;
            if (next >= 0) {
                p = next;
                t++;
            } else if (starP >= 0) {
                // Let the last star swallow one more character and retry from just after it.
                p = starP + 1;
                t = ++starT;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    /** True if the pattern has no special characters, so it can only match itself. */
    public static boolean isLiteral(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            switch (pattern.charAt(i)) {
                case '*', '?', '[', '\\' -> {
                    return false;
                }
                default -> {}
            }
        }
        return true;
    }

    /** If the pattern token starting at {@code p} matches {@code c}, returns the index after it, otherwise -1. */
    private static int matchOne(String pattern, int p, char c) {
        char token = pattern.charAt(p);
        if (token == '?') {
            return p + 1;
        }
        if (token == '\\' && p + 1 < pattern.length()) {
            return pattern.charAt(p + 1) == c ? p + 2 : -1;
        }
        if (token == '[') {
            return matchClass(pattern, p, c);
        }
        return token == c ? p + 1 : -1;
    }

    private static int matchClass(String pattern, int p, char c) {
        int i = p + 1;
        boolean negate = i < pattern.length() && pattern.charAt(i) == '^';
        if (negate) {
            i++;
        }
        boolean matched = false;
        while (i < pattern.length() && pattern.charAt(i) != ']') {
            char lo = pattern.charAt(i);
            if (lo == '\\' && i + 1 < pattern.length()) {
                lo = pattern.charAt(++i);
                if (c == lo) {
                    matched = true;
                }
                i++;
            } else if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-' && pattern.charAt(i + 2) != ']') {
                char hi = pattern.charAt(i + 2);
                if (lo > hi) {
                    char swap = lo;
                    lo = hi;
                    hi = swap;
                }
                if (c >= lo && c <= hi) {
                    matched = true;
                }
                i += 3;
            } else {
                if (c == lo) {
                    matched = true;
                }
                i++;
            }
        }
        // An unterminated class runs to the end of the pattern, as in Redis.
        int next = i < pattern.length() ? i + 1 : i;
        return matched != negate ? next : -1;
    }
}
