package eu.wohlben.qits.cli.access.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a docs bundle <em>is</em>, apart from the tarball that carries it: its files, by bundle-relative
 * path, and the one hash over them that {@code docs submit --if-changed} decides on (qits-1948).
 *
 * <h2>The content hash</h2>
 *
 * <pre>
 * sha256( for each file, sorted by the UTF-8 bytes of its path:  &lt;path&gt; NUL &lt;sha256 hex of its bytes&gt; LF )
 * </pre>
 *
 * <p>spelled {@code sha256:<64 lowercase hex>}, and stored on the version as the metadata key {@code
 * content.hash}. It depends on paths and bytes only, never on tar order, mtimes, modes or owners, so
 * the same tree hashes the same whether it arrives as {@code --dir}, as an {@code --archive} somebody
 * else packed, or (one file) as {@code --openapi}. The docs store keeps no bundle digest of its own
 * (see {@link Publisher#docsSubmit}), which is why this hash travels as metadata rather than being
 * compared against anything the store computes: the store records it, and the next run reads it back.
 *
 * <p>A path is the store's spelling of it: {@code /}-separated, with an archive's leading {@code ./}
 * stripped the way qits-artifacts' {@code DocsBundle.relativise} strips it, so {@code tar -C dir .}
 * and {@code --dir dir} name {@code manifest.json} alike.
 */
final class DocsContent {

    /** The metadata key the hash is stored under, sent as {@code X-Artifacts-Meta-content.hash}. */
    static final String HASH_KEY = "content.hash";

    private DocsContent() {
    }

    /**
     * Every regular file under {@code directory}, named relative to it, for {@link
     * Archives#writeTarGz} (which sorts them and fixes every mode, owner and mtime, so two packs of
     * one tree are byte-identical).
     *
     * <p><b>Regular files only; a symbolic link is skipped, never followed</b> — the same rule the
     * store applies to a symlink entry in an uploaded archive, so a link is absent from the bundle
     * whichever way it was packed. Following one would publish whatever it happens to point at on the
     * build machine, which may lie outside the directory altogether. Directories are implied by their
     * files and carry no entry of their own. {@code onSkipped} hears every path left out.
     *
     * @throws CliException 1 for a missing path, one that is not a directory, or a directory with no
     *     regular file in it: a bundle of nothing would publish an empty site, and under {@code
     *     --if-changed} would hash the same as every other empty run
     */
    static List<Archives.Entry> pack(Path directory, java.util.function.Consumer<String> onSkipped) {
        Path root;
        try {
            // The root itself may be a link to the tree (a build output symlinked into place); only
            // what lies beneath it is held to the regular-files rule.
            root = directory.toRealPath();
        } catch (NoSuchFileException e) {
            throw CliException.policy("--dir " + directory + ": no such directory");
        } catch (IOException e) {
            throw CliException.transport("cannot read --dir " + directory + ": " + e.getMessage(), e);
        }
        if (!Files.isDirectory(root)) {
            throw CliException.policy("--dir " + directory + " is not a directory");
        }
        List<Archives.Entry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted()::iterator) {
                if (path.equals(root)) {
                    continue;
                }
                String name = relative(root, path);
                if (Files.isSymbolicLink(path)) {
                    onSkipped.accept(name + " (a symbolic link)");
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new Archives.Entry(name, Files.readAllBytes(path)));
                } else {
                    onSkipped.accept(name + " (not a regular file)");
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw CliException.transport("cannot read --dir " + directory + ": " + e.getMessage(), e);
        }
        if (entries.isEmpty()) {
            throw CliException.policy("--dir " + directory + " holds no regular file: there is nothing to publish");
        }
        return entries;
    }

    /**
     * The regular files of an archive somebody else packed, as the store will name them: {@code ./}
     * stripped, the bare root entry dropped. A path present twice is refused here, as the store would
     * refuse the PUT, rather than hashed as whichever copy came last.
     */
    static List<Archives.Entry> ofArchive(Path archive) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(archive);
        } catch (NoSuchFileException e) {
            throw CliException.policy("no such file: " + archive);
        } catch (IOException e) {
            throw CliException.transport("cannot read " + archive + ": " + e.getMessage(), e);
        }
        List<Archives.Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Archives.Entry entry : Archives.readTarGz(bytes, "--archive " + archive)) {
            String name = entry.name().replace('\\', '/');
            while (name.startsWith("./")) {
                name = name.substring(2);
            }
            if (name.isEmpty() || name.equals(".")) {
                continue;
            }
            if (!seen.add(name)) {
                throw CliException.policy("--archive " + archive + " carries '" + name + "' more than once");
            }
            entries.add(new Archives.Entry(name, entry.bytes()));
        }
        if (entries.isEmpty()) {
            throw CliException.policy("--archive " + archive + " holds no regular file: there is nothing to publish");
        }
        return entries;
    }

    /**
     * The content hash of these files, {@code sha256:<hex>}: see the class javadoc. A path carrying
     * a NUL or a line break is refused rather than escaped, since either would let two different
     * trees spell the same hashed text.
     */
    static String hash(List<Archives.Entry> files) {
        List<Archives.Entry> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> HashManifest.UTF8_ORDER.compare(a.name(), b.name()));
        MessageDigest digest = sha256();
        for (Archives.Entry file : sorted) {
            if (file.name().indexOf('\0') >= 0 || file.name().indexOf('\n') >= 0) {
                throw CliException.policy("the bundle path '" + file.name().replace("\n", "\\n").replace("\0", "\\0")
                        + "' carries a NUL or a line break, which the content hash refuses rather than escapes");
            }
            digest.update((file.name() + "\0" + HashManifest.hex(HashManifest.sha256(file.bytes())) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return "sha256:" + HashManifest.hex(digest.digest());
    }

    private static String relative(Path root, Path path) {
        StringBuilder name = new StringBuilder();
        for (Path segment : root.relativize(path)) {
            if (!name.isEmpty()) {
                name.append('/');
            }
            name.append(segment);
        }
        return name.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256", e);
        }
    }
}
