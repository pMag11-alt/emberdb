package dev.emberdb.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/** Number parsing and formatting with Redis's rules, which are stricter than Java's. */
public final class Numbers {

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private Numbers() {}

    /**
     * Parses a signed 64-bit integer, or returns null. Like Redis's {@code string2ll}, this rejects a leading
     * {@code +}, leading zeros, {@code -0} and surrounding spaces, all of which {@link Long#parseLong} accepts.
     */
    public static Long parseLong(String s) {
        int n = s.length();
        if (n == 0 || n > 20) {
            return null;
        }
        int i = s.charAt(0) == '-' ? 1 : 0;
        if (i == n || (s.charAt(i) == '0' && n > 1)) {
            return null;
        }
        for (int j = i; j < n; j++) {
            char c = s.charAt(j);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * Parses a double, or returns null. Accepts {@code inf}, {@code +inf} and {@code -inf} in any case; rejects NaN,
     * hex floats, spaces and Java-only suffixes such as {@code 1d}.
     */
    public static Double parseDouble(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "inf", "+inf", "infinity", "+infinity" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-inf", "-infinity" -> {
                return Double.NEGATIVE_INFINITY;
            }
            default -> {}
        }
        if (!DECIMAL.matcher(s).matches()) {
            return null;
        }
        return Double.parseDouble(s);
    }

    /** Formats a double the way Redis replies with one: {@code 3}, {@code 1.5}, {@code inf}. */
    public static String formatDouble(double d) {
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e17) {
            return Long.toString((long) d);
        }
        double abs = Math.abs(d);
        if (abs >= 1e-6 && abs < 1e21) {
            return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
        }
        return Double.toString(d).replace("E", "e");
    }
}
