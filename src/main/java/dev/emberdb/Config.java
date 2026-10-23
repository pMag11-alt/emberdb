package dev.emberdb;

import java.nio.file.Path;
import java.util.Locale;

import dev.emberdb.persistence.AppendOnlyFile.Fsync;

/**
 * Server settings. Options use the same names as redis.conf, passed as command-line flags:
 * {@code --port 6380 --appendonly yes}.
 */
public record Config(
        String bind,
        int port,
        String requirePass,
        int maxKeys,
        boolean appendOnly,
        Path dir,
        String appendFilename,
        Fsync appendFsync) {

    public static final String USAGE = """
            Usage: java -jar emberdb.jar [options]

              --port <n>              TCP port (default 6379, 0 picks a free port)
              --bind <address>        interface to listen on (default 127.0.0.1)
              --requirepass <pass>    require AUTH <pass> before other commands
              --maxkeys <n>           evict least-recently-used keys beyond n keys (default 0 = unlimited)
              --appendonly yes|no     log writes to an append-only file and replay it on start (default no)
              --dir <path>            directory for the append-only file (default .)
              --appendfilename <name> append-only file name (default appendonly.aof)
              --appendfsync <policy>  always | everysec | no (default everysec)
              --help, --version
            """;

    public static Config defaults() {
        return new Config("127.0.0.1", 6379, null, 0, false, Path.of("."), "appendonly.aof", Fsync.EVERYSEC);
    }

    public Path aofPath() {
        return dir.resolve(appendFilename);
    }

    public Config withPort(int port) {
        return new Config(bind, port, requirePass, maxKeys, appendOnly, dir, appendFilename, appendFsync);
    }

    public Config withRequirePass(String password) {
        return new Config(bind, port, password, maxKeys, appendOnly, dir, appendFilename, appendFsync);
    }

    public Config withMaxKeys(int limit) {
        return new Config(bind, port, requirePass, limit, appendOnly, dir, appendFilename, appendFsync);
    }

    public Config withAppendOnly(Path directory, Fsync policy) {
        return new Config(bind, port, requirePass, maxKeys, true, directory, appendFilename, policy);
    }

    /** Parses command-line flags. Throws {@link IllegalArgumentException} with a readable message on bad input. */
    public static Config parse(String... args) {
        Config c = defaults();
        String bind = c.bind;
        int port = c.port;
        String requirePass = c.requirePass;
        int maxKeys = c.maxKeys;
        boolean appendOnly = c.appendOnly;
        Path dir = c.dir;
        String appendFilename = c.appendFilename;
        Fsync appendFsync = c.appendFsync;

        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!option.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + option + "'");
            }
            option = option.substring(2).toLowerCase(Locale.ROOT);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--" + option + " needs a value");
            }
            String value = args[++i];
            switch (option) {
                case "port" -> port = intOption(option, value, 0, 65535);
                case "bind" -> bind = value;
                case "requirepass" -> requirePass = value.isEmpty() ? null : value;
                case "maxkeys" -> maxKeys = intOption(option, value, 0, Integer.MAX_VALUE);
                case "appendonly" -> appendOnly = yesNo(option, value);
                case "dir" -> dir = Path.of(value);
                case "appendfilename" -> appendFilename = value;
                case "appendfsync" -> appendFsync = switch (value.toLowerCase(Locale.ROOT)) {
                    case "always" -> Fsync.ALWAYS;
                    case "everysec" -> Fsync.EVERYSEC;
                    case "no" -> Fsync.NO;
                    default -> throw new IllegalArgumentException("--appendfsync must be always, everysec or no");
                };
                default -> throw new IllegalArgumentException("unknown option --" + option);
            }
        }
        return new Config(bind, port, requirePass, maxKeys, appendOnly, dir, appendFilename, appendFsync);
    }

    private static int intOption(String option, String value, int min, int max) {
        try {
            int n = Integer.parseInt(value);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the error below
        }
        throw new IllegalArgumentException("--" + option + " must be a number from " + min + " to " + max);
    }

    private static boolean yesNo(String option, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "yes" -> true;
            case "no" -> false;
            default -> throw new IllegalArgumentException("--" + option + " must be yes or no");
        };
    }
}
