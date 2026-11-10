package eu.wohlben.qits.cli.access.networkaccess;

import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code docker} CLI, as a subprocess: the argv is a list and never goes through a shell, the
 * way {@link GitSetup} runs git. The executable is looked up on the command's own {@code PATH}
 * rather than the JVM's, so a test puts a recording fake first on it.
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

    private final Path executable;
    private final Map<String, String> env;

    private Docker(Path executable, Map<String, String> env) {
        this.executable = executable;
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

    Result run(List<String> args) throws CliFailure, InterruptedException {
        return run(args, null);
    }

    /** {@code docker <args>}, with {@code stdin} written to it when given, and both outputs read whole. */
    Result run(List<String> args, byte[] stdin) throws CliFailure, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().clear();
        builder.environment().putAll(env);
        if (stdin == null) {
            builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CliFailure("Cannot run docker: " + e.getMessage() + ". Install or start Docker.", NOT_READY);
        }
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        if (stdin != null) {
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin);
            } catch (IOException closed) {
                // docker went away before reading it all; its exit code says why.
            }
        }
        try {
            int exit = process.waitFor();
            return new Result(exit, out.join(), err.join());
        } catch (InterruptedException e) {
            process.destroy();
            throw e;
        }
    }

    /** The command as a person would paste it: for a failure message. */
    static String line(List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.addAll(args);
        return GitSetup.shellLine(argv);
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = stream) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                return bytes.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
