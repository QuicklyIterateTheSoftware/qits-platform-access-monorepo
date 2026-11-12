package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The systemd user unit that keeps {@code qits session-daemon} running, the README's sample with
 * the binary's own path. {@code qits registry-login --configure --install-daemon} writes it and
 * enables it; an existing unit file is the person's and is never replaced.
 */
final class SessionDaemonUnit {

    static final String NAME = "qits-session-daemon";

    /** Exit 0 means a user manager answers; nothing else is read from it. */
    static final List<String> PROBE = List.of("systemctl", "--user", "show-environment");
    static final List<String> RELOAD = List.of("systemctl", "--user", "daemon-reload");
    static final List<String> ENABLE = List.of("systemctl", "--user", "enable", "--now", NAME);
    /** With a daemon already running (started by hand), a second one would only exit 1 and restart every 30 s. */
    static final List<String> ENABLE_LATER = List.of("systemctl", "--user", "enable", NAME);

    /** What install runs after writing the unit: start it now unless a daemon already runs. */
    static List<List<String>> commands(boolean running) {
        return List.of(RELOAD, running ? ENABLE_LATER : ENABLE);
    }

    /** A finished command: its exit code, and stdout and stderr together. */
    record Ran(int exit, String output) {
    }

    /** Runs one command. A test hands its own, so no test reaches the real systemctl. */
    @FunctionalInterface
    interface Runner {
        Ran run(List<String> argv) throws IOException, InterruptedException;
    }

    static final Runner REAL = argv -> {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new Ran(process.waitFor(), output);
    };

    private SessionDaemonUnit() {
    }

    /** {@code $XDG_CONFIG_HOME/systemd/user/qits-session-daemon.service}, else under ~/.config. */
    static Path path(Map<String, String> env) {
        return configHome(env).resolve("systemd").resolve("user").resolve(NAME + ".service");
    }

    /** Something is there, a dangling link included. */
    static boolean exists(Path path) {
        return Files.exists(path) || Files.isSymbolicLink(path);
    }

    /** The unit for {@code binary}, an absolute path. */
    static String content(Path binary, Map<String, String> env) {
        return text(execPath(binary), env);
    }

    /** The unit for someone who writes it by hand: install.sh's path, which they may have to change. */
    static String sample(Map<String, String> env) {
        return text("%h/.local/bin/qits", env);
    }

    private static String text(String exec, Map<String, String> env) {
        StringBuilder text = new StringBuilder("""
                [Unit]
                Description=Keep the qits session fresh

                [Service]
                ExecStart=""").append(exec).append(" session-daemon\n");
        // The daemon reads XDG_CONFIG_HOME to find t.json, and a user manager does not have the shell's.
        Path config = configHome(env);
        if (!config.equals(RegistryLoginCommand.home(env).resolve(".config").toAbsolutePath().normalize())) {
            text.append("Environment=").append(quoted("XDG_CONFIG_HOME=" + config.toString().replace("%", "%%")))
                    .append('\n');
        }
        return text.append("""
                Restart=on-failure
                RestartSec=30

                [Install]
                WantedBy=default.target
                """).toString();
    }

    /** True when a systemd user manager answers. No systemctl, or one that fails, is no. */
    static boolean systemd(Runner runner) throws InterruptedException {
        try {
            return runner.run(PROBE).exit() == 0;
        } catch (IOException absent) {
            return false;
        }
    }

    /** The diff {@code --dry-run} prints for a new unit file: every line is the CLI's own. */
    static String diff(Path path, String content) {
        int lines = (int) content.lines().count();
        return UnifiedDiff.of(path.toString(), "", content, "", content,
                IntStream.range(0, lines).boxed().collect(Collectors.toSet()));
    }

    /**
     * Writes the unit, mode 0644, then reloads systemd and enables it for every login, starting it
     * now unless {@code running}.
     */
    static void install(Path path, String content, boolean running, Runner runner)
            throws CliFailure, InterruptedException {
        try {
            Files.createDirectories(path.getParent());
            // CREATE_NEW: a unit written since the check is still never replaced.
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (IOException e) {
            throw new CliFailure("Cannot write " + path + ": " + e.getMessage(), CliFailure.FAILED);
        }
        for (List<String> argv : commands(running)) {
            Ran ran;
            try {
                ran = runner.run(argv);
            } catch (IOException e) {
                throw new CliFailure("Cannot run `" + GitSetup.shellLine(argv) + "`: " + e.getMessage(),
                        CliFailure.FAILED);
            }
            if (ran.exit() != 0) {
                throw new CliFailure("`" + GitSetup.shellLine(argv) + "` failed (exit code " + ran.exit() + ")"
                        + (ran.output().isBlank() ? "" : ": " + ran.output().strip()), CliFailure.FAILED);
            }
        }
    }

    /**
     * What gets a daemon running, by what this workstation has: a unit file to start, systemd to
     * install one with, or neither.
     */
    static String fix(boolean unitExists, boolean systemd) {
        if (unitExists) {
            return "Start it: `" + GitSetup.shellLine(ENABLE) + "`.";
        }
        if (systemd) {
            return "Install it as a systemd user service: `qits registry-login --configure --install-daemon`.";
        }
        return "There is no systemd user manager here, so start it by hand: `qits session-daemon &` (or "
                + "`nohup qits session-daemon >> ~/.local/state/qits-session-daemon.log 2>&1 &`), and again "
                + "after every reboot.";
    }

    /** XDG_CONFIG_HOME when it is set and absolute, as the XDG specification says; else ~/.config. */
    private static Path configHome(Map<String, String> env) {
        String xdg = env.get("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg)
                : RegistryLoginCommand.home(env).resolve(".config");
        return base.toAbsolutePath().normalize();
    }

    /** The binary as one ExecStart word: systemd expands % and $, and splits at blanks and quotes. */
    private static String execPath(Path binary) {
        return quoted(binary.toAbsolutePath().toString().replace("%", "%%").replace("$", "$$"));
    }

    /** In systemd's double quotes when it has a blank, a quote or a backslash; as it is otherwise. */
    private static String quoted(String word) {
        boolean plain = word.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'' || c == '\\');
        return plain ? word : "\"" + word.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
