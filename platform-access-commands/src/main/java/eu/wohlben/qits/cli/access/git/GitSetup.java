package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Git setup for ONE origin. A person's global helper (on this workstation, Windows Git
 * Credential Manager for GitHub) must stay, so nothing is set for every host. The empty value
 * first clears the helper list for this origin; {@code --replace-all} makes that work however many
 * values are there, so running the setup again changes nothing.
 */
public final class GitSetup {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=@%+,-]+");

    private GitSetup() {
    }

    /** The two {@code git config} commands, as argument lists. */
    public static List<List<String>> commands(String gitOrigin, String executable) {
        String key = "credential." + gitOrigin + ".helper";
        String program = PLAIN.matcher(executable).matches() ? executable : "\"" + executable.replace("\"", "\\\"") + "\"";
        return List.of(
                List.of("git", "config", "--global", "--replace-all", key, ""),
                List.of("git", "config", "--global", "--add", key, "!" + program + " git-credential"));
    }

    /** One command as a person pastes it into a shell. */
    public static String shellLine(List<String> argv) {
        return argv.stream()
                .map(a -> PLAIN.matcher(a).matches() ? a : "'" + a.replace("'", "'\\''") + "'")
                .collect(Collectors.joining(" "));
    }

    /**
     * git's proxy key for this one origin, {@code http.<origin>.proxy} (qits-2743). git matches it
     * against the remote's URL, so it sends this host, and no other, through the proxy.
     */
    public static String proxyKey(String gitOrigin) {
        return "http." + gitOrigin + ".proxy";
    }

    /**
     * Send Git's calls to {@code gitOrigin} through the network-access companion at {@code proxy}
     * (qits-2743). The git host is a private app: on a workstation it answers only through the
     * companion's CONNECT proxy, which the qits commands use on their own and Git does not.
     */
    public static List<String> proxyCommand(String gitOrigin, String proxy) {
        return List.of("git", "config", "--global", "--replace-all", proxyKey(gitOrigin), proxy);
    }

    /** The reverse of {@link #proxyCommand}: Git dials the git host direct again. */
    public static List<String> unproxyCommand(String gitOrigin) {
        return List.of("git", "config", "--global", "--unset-all", proxyKey(gitOrigin));
    }

    /**
     * Whether Git asks {@code qits git-credential} for {@code gitOrigin}: what {@link #commands} set
     * up. {@code qits network-access} follows the companion for Git only then, so a workstation that
     * never pushes to the git host keeps a Git config the companion never touched. No git on the
     * PATH reads as not set up.
     */
    public static boolean helped(String gitOrigin, Map<String, String> env) throws InterruptedException {
        return asksQits(gitOrigin, env).orElse(false);
    }

    /** {@link #helped}, with no git on the PATH as empty rather than false. */
    static Optional<Boolean> asksQits(String gitOrigin, Map<String, String> env) throws InterruptedException {
        try {
            return Optional.of(globalValues("credential." + gitOrigin + ".helper", env).stream()
                    .anyMatch(line -> line.strip().endsWith(" git-credential")));
        } catch (IOException noGit) {
            return Optional.empty();
        }
    }

    /**
     * Every value of {@code key} in the {@code --global} config, in order; none when it is unset
     * (git's exit 1). Throws when git cannot be run.
     */
    static List<String> globalValues(String key, Map<String, String> env) throws IOException, InterruptedException {
        Ran ran = run(List.of("git", "config", "--global", "--get-all", key), env);
        if (ran.exit() == 1) {
            return List.of();
        }
        if (ran.exit() != 0) {
            throw new IOException("`git config --global --get-all " + key + "` failed (exit code " + ran.exit() + ")"
                    + (ran.output().isEmpty() ? "" : ": " + ran.output()));
        }
        return ran.output().lines().toList();
    }

    /**
     * Runs the commands. {@code env} is laid over this process's environment, so a test can point
     * {@code GIT_CONFIG_GLOBAL} at a scratch file.
     */
    public static void configure(List<List<String>> commands, Map<String, String> env, PrintStream err)
            throws CliFailure, InterruptedException {
        for (List<String> argv : commands) {
            Ran ran;
            try {
                ran = run(argv, env);
            } catch (IOException e) {
                throw new CliFailure("Cannot run git: " + e.getMessage(), CliFailure.FAILED);
            }
            // `--unset-all` of a key that is not there exits 5: nothing to undo is done.
            boolean nothingToUnset = ran.exit() == 5 && argv.contains("--unset-all");
            if (ran.exit() != 0 && !nothingToUnset) {
                throw new CliFailure("`" + shellLine(argv) + "` failed (exit code " + ran.exit() + ")"
                        + (ran.output().isEmpty() ? "" : ": " + ran.output()), CliFailure.FAILED);
            }
        }
    }

    private record Ran(int exit, String output) {
    }

    /**
     * The program every {@code git} argv runs as. Not final so a test can name one that is not
     * there, which is how a missing git is tested.
     */
    public static String GIT = "git";

    private static Ran run(List<String> argv, Map<String, String> env) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(argv);
        if ("git".equals(command.getFirst())) {
            command.set(0, GIT);
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new Ran(process.waitFor(), output);
    }
}
