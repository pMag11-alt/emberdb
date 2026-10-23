package dev.emberdb;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Minimal Redis-style log lines: {@code 21:04:05.123 * message}. */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    public static void info(String format, Object... args) {
        print('*', format, args);
    }

    public static void warn(String format, Object... args) {
        print('#', format, args);
    }

    private static void print(char level, String format, Object... args) {
        System.out.printf("%s %c %s%n", LocalTime.now().format(TIME), level, args.length == 0 ? format : String.format(format, args));
    }
}
