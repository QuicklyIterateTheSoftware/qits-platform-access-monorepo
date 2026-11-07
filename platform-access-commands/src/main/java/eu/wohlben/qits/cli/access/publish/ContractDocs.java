package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code qits artifacts publish contract-docs}: the {@code @contracts/<application>} docs bundle,
 * published whenever at least one golden-masters package is at the release version.
 *
 * <p>The bundle is the golden-masters tree under {@code golden-masters/} (what qits-docs reads
 * today) plus {@code contracts.json}, which lists the packages: ecosystem, coordinate, the newest
 * stored version (the version a consumer pins for this tree) and whether that is this release.
 * When no package moved, nothing is submitted and the docs store keeps the previous bundle.
 */
final class ContractDocs {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One {@code --package ecosystem=coordinate}. */
    record PackageRef(String ecosystem, String coordinate) {

        static PackageRef parse(String value) {
            int eq = value.indexOf('=');
            if (eq <= 0 || eq == value.length() - 1) {
                throw CliException.policy("--package is ecosystem=coordinate, not '" + value + "'");
            }
            String ecosystem = value.substring(0, eq);
            if (!ecosystem.equals("maven") && !ecosystem.equals("npm")) {
                throw CliException.policy("--package " + value + " names the ecosystem '" + ecosystem
                        + "'; contracts are packaged for maven and npm");
            }
            return new PackageRef(ecosystem, value.substring(eq + 1));
        }
    }

    private final Http http;
    private final Store store;
    private final Console console;

    ContractDocs(Http http, Store store, Console console) {
        this.http = http;
        this.store = store;
        this.console = console;
    }

    /** {@code published <v>} or {@code unchanged}. */
    String publish(String application, Path from, String version, List<PackageRef> packages, List<String> meta) {
        return publish(application, from, version, packages, meta, null);
    }

    /**
     * {@code published <v>} or {@code unchanged}. {@code entities}, when given, must be a readable
     * regular file: its bytes are added to the bundle as {@code entities.json}, and their presence
     * alone forces a publish, because the exposure map can change with no golden master moving.
     */
    String publish(String application, Path from, String version, List<PackageRef> packages, List<String> meta,
            Path entities) {
        if (packages.isEmpty()) {
            throw CliException.policy("--package is required, once per golden-masters package");
        }
        if (entities != null && !Files.isRegularFile(entities)) {
            throw CliException.policy("no such file: " + entities);
        }
        ContentHashes hashes = new ContentHashes(http, store);
        ObjectNode index = JSON.createObjectNode();
        index.put("formatVersion", 1);
        index.put("application", application);
        index.put("version", version);
        ArrayNode list = index.putArray("packages");
        boolean anyPackageMoved = false;
        for (PackageRef ref : packages) {
            ContentHashes.Stored newest = hashes.newest(ref.ecosystem(), ref.coordinate()).orElseThrow(() ->
                    CliException.policy(ref.ecosystem() + " " + ref.coordinate() + " has no published version; "
                            + "the contract packages are published earlier in this step, so this is a bug, not "
                            + "a state"));
            boolean published = newest.version().equals(version);
            anyPackageMoved |= published;
            ObjectNode entry = list.addObject();
            entry.put("ecosystem", ref.ecosystem());
            entry.put("coordinate", ref.coordinate());
            entry.put("version", newest.version());
            entry.put("published", published);
            console.info(ref.ecosystem() + " " + ref.coordinate() + " is at " + newest.version()
                    + (published ? " (this release)" : ""));
        }
        if (!anyPackageMoved && entities == null) {
            console.info("no golden-masters package was published at " + version
                    + "; the docs store keeps the previous @contracts/" + application + " bundle");
            return "unchanged";
        }

        List<Archives.Entry> files = new ArrayList<>(ContractPackager.tree(from, ContractPackager.Kind.GOLDEN_MASTERS.root)
                .stream().filter(e -> !e.directory()).toList());
        try {
            byte[] contracts = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
            files.add(new Archives.Entry("contracts.json", contracts));
        } catch (IOException e) {
            throw new IllegalStateException("writing contracts.json failed", e);
        }
        if (entities != null) {
            try {
                files.add(new Archives.Entry("entities.json", Files.readAllBytes(entities)));
            } catch (IOException e) {
                throw CliException.transport("cannot read " + entities + ": " + e.getMessage(), e);
            }
        }
        Path archive = temporary(Archives.writeTarGz(files));
        try {
            new Publisher(http, store, console).docsSubmit("@contracts/" + application, version, archive, meta);
        } finally {
            delete(archive);
        }
        return "published " + version;
    }

    static Path temporary(byte[] bytes) {
        try {
            Path file = Files.createTempFile("qits-publish-", ".tgz");
            Files.write(file, bytes);
            return file;
        } catch (IOException e) {
            throw CliException.transport("cannot write a temporary bundle: " + e.getMessage(), e);
        }
    }

    static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A temporary file in /tmp of a step container that is about to go away.
        }
    }
}
