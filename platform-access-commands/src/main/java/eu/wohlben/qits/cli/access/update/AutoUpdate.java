package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.agents.Detached;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.Mode;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The automatic update: before a command runs, at most once an hour, start {@code qits update
 * --background} detached, and print the last background failure once.
 * <p>
 * <b>It never delays or breaks the command the person ran.</b> All it does in the foreground is
 * read a small file and, when a check is due, write it and start a process it does not wait for;
 * the network is only ever reached from that process. Anything that goes wrong here is swallowed.
 * <p>
 * Where it does nothing at all: anywhere but a workstation ({@link #refusal}), with
 * {@value #DISABLE} off, without a session file, in a JVM rather than the binary, and for the
 * commands whose stdout or latency another program reads ({@link #PROTOCOL}), or that only print
 * help or the version.
 */
public final class AutoUpdate {

    /** {@code false}, {@code 0}, {@code off} or {@code no} turns the automatic update off. */
    public static final String DISABLE = "QITS_AUTO_UPDATE";

    /** How often the automatic check runs at most. */
    static final Duration INTERVAL = Duration.ofHours(1);

    /**
     * The first word of a command that never starts a check: {@code update} itself (and its
     * background run), the two daemons, the git and Docker credential helpers and {@code agents} (Claude runs
     * the hook and the status line, and reads their stdout), and {@code help} (agents read it).
     */
    static final Set<String> PROTOCOL = Set.of("update", "session-daemon", "checkout-daemon", "git-credential",
            "docker-credential", "agents", "help");

    private static final Set<String> HELP_OR_VERSION = Set.of("-h", "--help", "-V", "--version");
    private static final Set<String> OFF = Set.of("false", "0", "off", "no");

    private final Map<String, String> env;
    private final Clock clock;
    private final Path configDirectory;
    private final Supplier<Optional<Path>> binary;
    private final Consumer<List<String>> starter;

    AutoUpdate(Map<String, String> env, Clock clock, Path configDirectory, Supplier<Optional<Path>> binary,
               Consumer<List<String>> starter) {
        this.env = env;
        this.clock = clock;
        this.configDirectory = configDirectory;
        this.binary = binary;
        this.starter = starter;
    }

    /**
     * What {@code Main} calls before it runs a command. Never throws and never waits for the network.
     *
     * @param args the arguments the command will run with
     */
    public static void beforeCommand(String[] args, Map<String, String> env, PrintStream err) {
        try {
            new AutoUpdate(env, Clock.systemUTC(), SessionFile.fromEnvironment(env).directory(),
                    AutoUpdate::nativeBinary, Detached::start).before(args, err);
        } catch (RuntimeException | LinkageError never) {
            // The command the person ran comes first.
        }
    }

    void before(String[] args, PrintStream err) {
        if (!wanted(args) || refusal(env).isPresent() || disabled(env)) {
            return;
        }
        if (binary.get().isEmpty() || !Files.exists(configDirectory.resolve(SessionFile.FILE_NAME))) {
            return;
        }
        UpdateState state = UpdateState.read(configDirectory);
        UpdateState next = state;
        Optional<String> failure = state.failureLine();
        if (failure.isPresent()) {
            err.println(failure.get());
            err.flush();
            next = next.cleared();
        }
        Instant now = clock.instant();
        boolean due = due(state.checkedAt(), now);
        if (due) {
            next = next.checked(now);
        }
        if (!next.equals(state)) {
            next.writeQuietly(configDirectory);
        }
        if (due) {
            starter.accept(List.of("update", "--background"));
        }
    }

    /** Whether a check is due: never checked, an hour gone, or a time in the future (a clock set back). */
    static boolean due(Instant checkedAt, Instant now) {
        return checkedAt == null || !now.isBefore(checkedAt.plus(INTERVAL)) || checkedAt.isAfter(now);
    }

    /** Whether {@code args} name a command that may start a check. */
    static boolean wanted(String[] args) {
        if (args.length == 0) {
            return false;
        }
        for (String arg : args) {
            if (HELP_OR_VERSION.contains(arg)) {
                return false;
            }
        }
        String first = args[0];
        return !first.startsWith("-") && !PROTOCOL.contains(first);
    }

    /** Whether {@value #DISABLE} turns the automatic update off. */
    static boolean disabled(Map<String, String> env) {
        String value = env.get(DISABLE);
        return value != null && OFF.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Why qits never updates itself here, or empty on a workstation. Inside the platform and in a
     * workspace with its own token, the image decides which qits there is; the MCP service runs the
     * commands for somebody else.
     */
    static Optional<String> refusal(Map<String, String> env) {
        if (env.containsKey(CliContext.MCP_SERVICE) || System.getProperty(CliContext.MCP_SERVICE) != null) {
            return Optional.of("qits does not update itself in the MCP service.");
        }
        return switch (Mode.of(env)) {
            case WORKSTATION -> Optional.empty();
            case IN_PLATFORM -> Optional.of("qits does not update itself inside the platform: the container's "
                    + "image decides which qits it has.");
            case EDGE_TOKEN -> Optional.of("qits does not update itself in a workspace with " + Mode.TOKEN
                    + ": the workspace's image decides which qits it has.");
        };
    }

    /** The running binary, when this process is one named like it; empty in a JVM. */
    public static Optional<Path> nativeBinary() {
        return Detached.self().filter(path -> path.getFileName().toString().startsWith("qits"));
    }
}
