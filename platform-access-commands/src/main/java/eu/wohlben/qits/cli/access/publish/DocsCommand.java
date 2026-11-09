package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code qits artifacts publish docs} — a documentation bundle. */
@CommandLine.Command(name = "docs", mixinStandardHelpOptions = true,
        subcommands = DocsCommand.SubmitCommand.class,
        description = "A documentation bundle, at (site, version).")
public class DocsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "submit",
            // Not mixinStandardHelpOptions: qits-publish's own --version flag names the artifact
            // version, and the mixin's -V/--version (print the qits binary's own version) would
            // collide with it by name, and picocli then recognises neither. -h/--help alone is safe.
            description = {"Publish a documentation bundle at (site, version).",
                    "--openapi publishes one OpenAPI document instead of an archive: it is packed as the "
                            + "bundle's only entry, openapi.yml (or openapi.json for a .json file). That is the "
                            + "platform's @apidocs publish.",
                    "--dir packs a directory instead: every regular file under it, named relative to it, so its "
                            + "own files sit at the bundle root. Symbolic links are skipped, never followed, as the "
                            + "store skips them in an archive. The pack is deterministic: sorted entries, fixed "
                            + "modes, owners and mtimes.",
                    "With --if-changed the bundle is published only when its content hash differs from the one "
                            + "recorded on the site's newest version, and the hash is recorded on the new version "
                            + "as content.hash. The hash covers each file's path and bytes, never its order or "
                            + "mtime. No such site, or a newest version with no hash, counts as changed. It prints "
                            + "one line on stdout: `published <version>` or `unchanged since <version>`.",
                    "The store explodes the archive into per-file blobs and keeps no archive digest, so an "
                            + "occupied version cannot be verified: it is skipped, with a WARN naming the "
                            + "degradation, rather than reported as a plain success."},
            footerHeading = "%nExamples:%n",
            footer = {"  qits artifacts publish docs submit --site @apidocs/qits-ci --version 2026.906.1 "
                    + "--archive apidocs.tgz --meta git.commit.hash=deadbeef",
                    "  qits artifacts publish docs submit --site @apidocs/qits-projects --version 2026.1002.1 "
                            + "--openapi docs/openapi.yml",
                    "  qits artifacts publish docs submit --site @routes/qits-landing --version 2026.1109.1 "
                            + "--dir src/testing/browser/freezes --if-changed"},
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Published, already published (see above: not verified in that case), or unchanged.",
                    "1:Refused: bad arguments, a missing or empty --dir, or a 4xx that is not the store's "
                            + "\"already there\".",
                    "2:Could not ask: the store unreachable, an I/O failure, a 5xx, or (with --if-changed) a "
                            + "listing that is not an answer; never read as unchanged."})
    public static class SubmitCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @CommandLine.Option(names = "--site", paramLabel = "<name>", description = "The docs site's name.")
        List<String> site = new ArrayList<>();

        @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The version.")
        List<String> version = new ArrayList<>();

        @CommandLine.Option(names = "--archive", paramLabel = "<tgz>",
                description = "The gzipped tar archive to publish. Exclusive with --openapi and --dir.")
        List<String> archive = new ArrayList<>();

        @CommandLine.Option(names = "--openapi", paramLabel = "<file>",
                description = "An OpenAPI document (.yml, .yaml or .json) to publish as the whole bundle. "
                        + "Exclusive with --archive and --dir.")
        List<String> openapi = new ArrayList<>();

        @CommandLine.Option(names = "--dir", paramLabel = "<directory>",
                description = "A directory to pack as the bundle, its files at the bundle root. "
                        + "Exclusive with --archive and --openapi.")
        List<String> dir = new ArrayList<>();

        @CommandLine.Option(names = "--if-changed",
                description = "Publish only when the content differs from the site's newest version, and "
                        + "record its hash as content.hash.")
        boolean ifChanged;

        @CommandLine.Option(names = "--meta", paramLabel = "<key=value>",
                description = "A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable.")
        List<String> meta = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String site = PublishArgs.requiredOnce(this.site, "--site");
            String version = PublishArgs.requiredOnce(this.version, "--version");
            String archive = PublishArgs.optionalOnce(this.archive, "--archive", null);
            String openapi = PublishArgs.optionalOnce(this.openapi, "--openapi", null);
            String dir = PublishArgs.optionalOnce(this.dir, "--dir", null);
            int sources = (archive == null ? 0 : 1) + (openapi == null ? 0 : 1) + (dir == null ? 0 : 1);
            if (sources > 1) {
                throw CliException.policy("--archive, --openapi and --dir are exclusive: give the bundle, the one "
                        + "document or the directory to make it from");
            }
            if (sources == 0) {
                throw CliException.policy("--archive, --openapi or --dir is required");
            }
            if (ifChanged && meta.stream().anyMatch(DocsCommand.SubmitCommand::namesTheHash)) {
                throw CliException.policy("--meta " + DocsContent.HASH_KEY + " cannot be given with --if-changed, "
                        + "which records the hash it computes: one version, one hash");
            }
            Publisher publisher = new Publisher(http(), store(env), console);
            if (archive != null) {
                // An archive is published as it stands; it is read for its files only to hash them.
                Path bundle = Path.of(archive);
                return ifChanged
                        ? publisher.docsSubmitIfChanged(site, version, bundle, meta,
                                DocsContent.hash(DocsContent.ofArchive(bundle)), new DocsVersions(http(), store(env)))
                        : publisher.docsSubmit(site, version, bundle, meta);
            }
            List<Archives.Entry> files = dir != null
                    ? DocsContent.pack(Path.of(dir), skipped -> console.info("--dir " + dir + ": skipped " + skipped))
                    : List.of(openApiEntry(Path.of(openapi)));
            Path bundle = ContractDocs.temporary(Archives.writeTarGz(files));
            try {
                return ifChanged
                        ? publisher.docsSubmitIfChanged(site, version, bundle, meta, DocsContent.hash(files),
                                new DocsVersions(http(), store(env)))
                        : publisher.docsSubmit(site, version, bundle, meta);
            } finally {
                ContractDocs.delete(bundle);
            }
        }

        /** Whether a {@code --meta} pair sets {@code content.hash}, in any case (a header name has none). */
        private static boolean namesTheHash(String pair) {
            int eq = pair.indexOf('=');
            return eq > 0 && pair.substring(0, eq).equalsIgnoreCase(DocsContent.HASH_KEY);
        }

        /** The document as the bundle's one entry, named for its format. */
        static Archives.Entry openApiEntry(Path file) {
            String lower = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String entry;
            if (lower.endsWith(".json")) {
                entry = "openapi.json";
            } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                entry = "openapi.yml";
            } else {
                throw CliException.policy("--openapi " + file + " is not an OpenAPI document (.yml, .yaml or .json)");
            }
            byte[] bytes;
            try {
                bytes = java.nio.file.Files.readAllBytes(file);
            } catch (java.nio.file.NoSuchFileException e) {
                throw CliException.policy("no such file: " + file);
            } catch (java.io.IOException e) {
                throw CliException.transport("cannot read " + file + ": " + e.getMessage(), e);
            }
            if (bytes.length == 0) {
                throw CliException.policy("--openapi " + file + " is empty");
            }
            return new Archives.Entry(entry, bytes);
        }
    }
}
