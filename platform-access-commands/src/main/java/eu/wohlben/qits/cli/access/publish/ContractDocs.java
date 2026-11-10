package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.database.MermaidEntityDiagramFormat;
import eu.wohlben.qits.cli.access.database.TableModel;
import eu.wohlben.qits.cli.access.database.UnitModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code qits artifacts publish contract-docs}: the {@code @contracts/<application>} docs bundle,
 * published whenever at least one golden-masters package is at the release version.
 *
 * <p>The bundle is the golden-masters tree under {@code golden-masters/} (what qits-docs reads
 * today) plus {@code contracts.json}, which lists the packages: ecosystem, coordinate, the newest
 * stored version (the version a consumer pins for this tree) and whether that is this release.
 * {@code --entities} adds {@code entities.json} and {@code --database} adds the entity diagrams under
 * {@code database/}; either forces a publish. Otherwise, when no package moved, nothing is submitted
 * and the docs store keeps the previous bundle.
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
        return publish(application, from, version, packages, meta, entities, null);
    }

    /**
     * {@code published <v>} or {@code unchanged}. {@code entities} as above. {@code database}, when
     * given, must be a directory: every generated entity diagram directly in it ({@code *.md} that
     * {@link MermaidEntityDiagramFormat#isGenerated} accepts) is added as {@code database/<file>},
     * with {@code database/index.json} naming each file's unit and the entities it draws. Like
     * {@code entities}, its presence alone forces a publish: a diagram changes with the schema, not
     * with the golden masters.
     */
    String publish(String application, Path from, String version, List<PackageRef> packages, List<String> meta,
            Path entities, Path database) {
        if (packages.isEmpty()) {
            throw CliException.policy("--package is required, once per golden-masters package");
        }
        if (entities != null && !Files.isRegularFile(entities)) {
            throw CliException.policy("no such file: " + entities);
        }
        if (database != null && !Files.isDirectory(database)) {
            throw CliException.policy("no such directory: " + database);
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
        if (!anyPackageMoved && entities == null && database == null) {
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
        if (database != null) {
            files.addAll(diagrams(database));
        }
        Path archive = temporary(Archives.writeTarGz(files));
        try {
            new Publisher(http, store, console).docsSubmit("@contracts/" + application, version, archive, meta);
        } finally {
            delete(archive);
        }
        return "published " + version;
    }

    /**
     * The generated diagrams directly in {@code dir}, as {@code database/<file>} with their bytes
     * unchanged, then {@code database/index.json}. Units are sorted by file and entities by class, so
     * the same diagrams give the same bytes; a table no class maps (a join table) names no entity.
     */
    static List<Archives.Entry> diagrams(Path dir) {
        List<Path> candidates;
        try (Stream<Path> listing = Files.list(dir)) {
            candidates = listing.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw CliException.transport("cannot list " + dir + ": " + e.getMessage(), e);
        }
        MermaidEntityDiagramFormat format = new MermaidEntityDiagramFormat("jpa");
        List<Archives.Entry> entries = new ArrayList<>();
        ObjectNode index = JSON.createObjectNode();
        index.put("formatVersion", 1);
        ArrayNode units = index.putArray("units");
        for (Path file : candidates) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw CliException.transport("cannot read " + file + ": " + e.getMessage(), e);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!MermaidEntityDiagramFormat.isGenerated(text)) {
                continue;
            }
            String name = file.getFileName().toString();
            entries.add(new Archives.Entry("database/" + name, bytes));
            UnitModel unit = format.parse(text);
            ObjectNode entry = units.addObject();
            entry.put("unit", unit.name());
            entry.put("file", name);
            ArrayNode entities = entry.putArray("entities");
            unit.tables().stream().filter(t -> t.javaClass() != null)
                    .sorted(Comparator.comparing(TableModel::javaClass).thenComparing(TableModel::name))
                    .forEach(t -> entities.addObject().put("entity", t.javaClass()).put("table", t.name()));
        }
        try {
            entries.add(new Archives.Entry("database/index.json",
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index)));
        } catch (IOException e) {
            throw new IllegalStateException("writing database/index.json failed", e);
        }
        return entries;
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
