package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code qits artifacts publish contract-docs} — the {@code @contracts/<application>} docs bundle. */
@CommandLine.Command(name = "contract-docs",
        // Not mixinStandardHelpOptions: see MavenCommand's --version note.
        description = {"Publish the @contracts/<application> docs bundle when at least one golden-masters "
                + "package is at --version: the tree under golden-masters/, plus contracts.json listing every "
                + "--package with its newest version and whether that is this release. Prints exactly one "
                + "line on stdout: `published <version>` or `unchanged` (no package moved; the docs store "
                + "keeps the previous bundle).",
                "--entities adds the entity exposure map as entities.json, beside contracts.json, and makes "
                        + "the bundle publish on every release even when no golden-masters package moved: the "
                        + "map can change on its own, e.g. marking an entity @Technical.",
                "Run it after the contract packages are published: a package with no version at all is a "
                        + "refusal, not a state."},
        footerHeading = "%nExamples:%n",
        footer = {"  qits artifacts publish contract-docs --application qits-projects --from golden-masters/ "
                + "--package maven=eu.wohlben.qits:qits-projects-golden-masters "
                + "--package npm=@qits/projects-golden-masters --version 2026.1002.1 --meta git.commit.hash=deadbeef"},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, already published, or unchanged.",
                "1:Refused: bad arguments, a package with no published version, or a 4xx.",
                "2:Could not ask: the store unreachable, an I/O failure, or a 5xx."})
public class ContractDocsCommand extends AbstractPublishCommand {

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @CommandLine.Option(names = "--application", paramLabel = "<application>",
            description = "The provider application; the site is @contracts/<application>.")
    List<String> application = new ArrayList<>();

    @CommandLine.Option(names = "--from", paramLabel = "<dir>", description = "The golden-masters tree.")
    List<String> from = new ArrayList<>();

    @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The release version.")
    List<String> version = new ArrayList<>();

    @CommandLine.Option(names = "--package", paramLabel = "<ecosystem=coordinate>",
            description = "A golden-masters package, e.g. npm=@qits/projects-golden-masters. Repeatable.")
    List<String> packages = new ArrayList<>();

    @CommandLine.Option(names = "--meta", paramLabel = "<key=value>",
            description = "A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable.")
    List<String> meta = new ArrayList<>();

    @CommandLine.Option(names = "--entities", paramLabel = "<file>",
            description = "The entity exposure map (docs/entities.json); bundled as entities.json, and the "
                    + "bundle is then published on every release.")
    List<String> entities = new ArrayList<>();

    @CommandLine.Unmatched
    List<String> unmatched = new ArrayList<>();

    @Override
    protected int run(Env env, Console console) {
        PublishArgs.noExtras(unmatched);
        String application = PublishArgs.requiredOnce(this.application, "--application");
        Path from = Path.of(PublishArgs.requiredOnce(this.from, "--from"));
        String version = PublishArgs.requiredOnce(this.version, "--version");
        List<ContractDocs.PackageRef> refs = packages.stream().map(ContractDocs.PackageRef::parse).toList();
        String entities = PublishArgs.optionalOnce(this.entities, "--entities", null);
        Path entitiesFile = entities == null ? null : Path.of(entities);
        String line = new ContractDocs(http(), store(env), console)
                .publish(application, from, version, refs, meta, entitiesFile);
        console.answer(line);
        return ExitCode.OK;
    }
}
