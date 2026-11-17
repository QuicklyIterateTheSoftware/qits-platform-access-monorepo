package eu.wohlben.qits.cli.access.networkaccess;

import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code docker} CLI, run through qits-containers-driver (qits-2213): the argv comes whole
 * from its {@code DockerArgv}/{@code HostRunArgv}, never through a shell, and {@link
 * ContainerProcess} runs it under a deadline. The executable is looked up on the command's own
 * {@code PATH} rather than the JVM's, so a test puts a recording fake first on it.
 */
final class Docker {

    /** What a person must fix before anything here can work: Docker itself, or its registry login. */
    static final int NOT_READY = 3;

    record Result(int exit, String out, String err) {
        boolean ok() {
            return exit == 0;
        }

        /** What docker said, stderr first, on one line, for a failure message. */
        String said() {
            String both = (err.strip() + " " + out.strip()).strip();
            return both.replaceAll("\\s+", " ");
        }
    }

    private final String bin;
    private final Map<String, String> env;

    private Docker(Path executable, Map<String, String> env) {
        this.bin = executable.toString();
        this.env = env;
    }

    /** {@code docker} on {@code env}'s PATH; exit 3 when there is none. */
    static Docker of(Map<String, String> env) throws CliFailure {
        return find(env).map(path -> new Docker(path, env)).orElseThrow(() -> new CliFailure(
                "There is no docker on PATH: install or start Docker.", NOT_READY));
    }

    static Optional<Path> find(Map<String, String> env) {
        String path = env.get("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String directory : path.split(":")) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve("docker");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The binary a dry run renders its argv with: {@code docker} on {@code env}'s PATH, else the bare
     * word, because a dry run prints the commands and needs no docker to do it.
     */
    static String binOrName(Map<String, String> env) {
        return find(env).map(Path::toString).orElse("docker");
    }

    /** The binary every argv starts with: element 0 of what the driver renders. */
    String bin() {
        return bin;
    }

    /**
     * The whole {@code argv}, with the command's env added to the child's, {@code stdin} written to it
     * when given (closed at once when null), and the run killed past {@code deadline}.
     */
    Result run(List<String> argv, String stdin, Duration deadline) throws CliFailure, InterruptedException {
        ContainerProcess.Separated run = ContainerProcess.runSeparate(argv, env, stdin, deadline,
                ContainerProcess.STDOUT_CAP);
        if (Thread.interrupted()) {
            throw new InterruptedException("interrupted while `" + line(argv) + "` ran");
        }
        if (run.exitCode() == 127 && run.stderrTail().startsWith("cannot start ")) {
            // "cannot start <argv0>: <the IOException's message>": the reason is the message.
            String said = run.stderrTail();
            String prefix = "cannot start " + argv.getFirst() + ": ";
            String reason = said.startsWith(prefix) ? said.substring(prefix.length()) : said;
            throw new CliFailure("Cannot run docker: " + reason + ". Install or start Docker.", NOT_READY);
        }
        if (run.timedOut()) {
            throw new CliFailure("`" + line(argv) + "` did not finish within " + deadline.toSeconds()
                    + " s and was stopped.", CliFailure.FAILED);
        }
        return new Result(run.exitCode(), run.stdout(), run.stderrTail());
    }

    /** The command as a person would paste it, {@code docker} for the binary's path: for a failure message. */
    static String line(List<String> argv) {
        List<String> shown = new ArrayList<>(argv);
        shown.set(0, "docker");
        return GitSetup.shellLine(shown);
    }
}
