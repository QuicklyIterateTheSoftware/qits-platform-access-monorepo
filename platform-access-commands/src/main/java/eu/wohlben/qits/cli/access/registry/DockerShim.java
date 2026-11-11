package eu.wohlben.qits.cli.access.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/**
 * {@code docker-credential-qits}, the program Docker runs for a {@code credHelpers} entry of
 * {@code qits}. Docker looks it up on PATH by that name, so it sits beside the {@code qits} binary
 * (which is on PATH) and does nothing but run {@code qits docker-credential}. install.sh writes the
 * same file.
 */
final class DockerShim {

    static final String NAME = "docker-credential-" + DockerConfig.HELPER;

    private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

    private DockerShim() {
    }

    /** The script for {@code binary}, an absolute path, quoted for sh. */
    static String content(Path binary) {
        String quoted = "'" + binary.toAbsolutePath().toString().replace("'", "'\\''") + "'";
        return "#!/bin/sh\nexec " + quoted + " docker-credential \"$@\"\n";
    }

    record Plan(Path path, String before, String after) {

        boolean changed() {
            return !before.equals(after);
        }
    }

    /** The shim beside {@code binary}, and what is there now. */
    static Plan plan(Path binary) throws IOException {
        Path path = binary.toAbsolutePath().getParent().resolve(NAME);
        String before;
        try {
            before = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            before = "";
        }
        return new Plan(path, before, content(binary));
    }

    /** The diff {@code --dry-run} prints: every line is the CLI's own, and none is a secret. */
    static String diff(Plan plan) {
        int lines = (int) plan.after().lines().count();
        return UnifiedDiff.of(plan.path().toString(), plan.before(), plan.after(), plan.before(), plan.after(),
                IntStream.range(0, lines).boxed().collect(Collectors.toSet()));
    }

    /** Writes it atomically with mode 0755, when it differs or is not executable. */
    static boolean apply(Plan plan) throws IOException {
        boolean executable = Files.isRegularFile(plan.path())
                && Files.getPosixFilePermissions(plan.path()).containsAll(EXECUTABLE);
        if (!plan.changed() && executable) {
            return false;
        }
        Path directory = plan.path().getParent();
        Path temporary = Files.createTempFile(directory, "." + NAME + ".", ".tmp");
        try {
            Files.writeString(temporary, plan.after(), StandardCharsets.UTF_8);
            // Set after the write: a mode given at creation is narrowed by the umask.
            Files.setPosixFilePermissions(temporary, EXECUTABLE);
            try {
                Files.move(temporary, plan.path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("cannot replace " + plan.path() + " atomically", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }
}
