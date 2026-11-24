package eu.wohlben.qits.cli.access.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits maintenance sbom}: one artifact's released versions and whether qits-maintenance has
 * read each one's SBOM. {@code versions} lists them; {@code reread} queues a fresh read of one.
 */
@CommandLine.Command(name = "sbom", mixinStandardHelpOptions = true,
        subcommands = {SbomCommand.VersionsCommand.class, SbomCommand.RereadCommand.class},
        description = "One artifact's released versions and whether qits-maintenance has read each one's SBOM. "
                + "`versions` lists them; `reread` queues a fresh read of one.")
public class SbomCommand implements Runnable {

    @CommandLine.ParentCommand
    MaintenanceCommand parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** The versions table: version, SBOM status, repository, release time and error. */
    static void printVersions(PrintStream out, List<JsonNode> versions) {
        Table.print(out, "", List.of("VERSION", "STATUS", "REPOSITORY", "RELEASED", "ERROR"),
                versions.stream().map(v -> List.of(
                        cell(text(v, "version"), 24),
                        cell(text(v, "sbomStatus"), 10),
                        cell(text(v, "repository"), 28),
                        Table.time(text(v, "occurredAt")),
                        cell(text(v, "sbomError"), 60))).toList());
    }

    private static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }

    @CommandLine.Command(name = "versions", mixinStandardHelpOptions = true,
            description = {"List one artifact's released versions, newest first, and whether "
                    + "qits-maintenance has read each one's SBOM."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits maintenance sbom versions --ecosystem maven --name eu.wohlben.qits:qits-eventstream",
                    "",
                    "- Empty means the platform has never seen a released version of this artifact.",
                    "- STATUS is PENDING, INGESTED, MISSING or FAILED; `sbom reread` queues a fresh read of a "
                            + "FAILED or MISSING one."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class VersionsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        SbomCommand parent;

        @CommandLine.Option(names = "--ecosystem", paramLabel = "<maven|npm|docker|daemon>", required = true,
                description = "The package ecosystem.")
        String ecosystem;

        @CommandLine.Option(names = "--name", paramLabel = "<name>", required = true,
                description = "The artifact's name, as the ecosystem spells it "
                        + "(eu.wohlben.qits:qits-eventstream).")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            MaintenanceCommand maintenance = parent.parent;
            boolean json = maintenance.json();
            String wantedEcosystem = MaintenanceCommand.required(ecosystem, "Name the ecosystem: --ecosystem <ecosystem>.");
            String wantedName = MaintenanceCommand.required(name, "Name the artifact: --name <name>.");
            JsonNode answer = maintenance.maintenance(context).listArtifactVersions(wantedEcosystem, wantedName);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> versions = new ArrayList<>();
            answer.forEach(versions::add);
            if (versions.isEmpty()) {
                context.out().println("No released version of " + wantedName + " (" + wantedEcosystem + ") is known.");
                return 0;
            }
            printVersions(context.out(), versions);
            return 0;
        }
    }

    @CommandLine.Command(name = "reread",
            // Not mixinStandardHelpOptions: this command's own --version (the artifact's released
            // version) collides by name with the mixin's -V/--version (the qits binary's own
            // version), and picocli then recognises neither. -h/--help alone is safe. See AGENTS.md.
            description = {"Queue a fresh read of one released version's SBOM.",
                    "The read happens asynchronously: this only queues it and prints the job's id. Run `qits "
                            + "maintenance sbom versions` again, once it has had time to run, to see the result."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits maintenance sbom reread --ecosystem maven --name "
                            + "eu.wohlben.qits:qits-eventstream --version 2026.811.1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class RereadCommand extends PlatformCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @CommandLine.ParentCommand
        SbomCommand parent;

        @CommandLine.Option(names = "--ecosystem", paramLabel = "<maven|npm|docker|daemon>", required = true,
                description = "The package ecosystem.")
        String ecosystem;

        @CommandLine.Option(names = "--name", paramLabel = "<name>", required = true,
                description = "The artifact's name, as the ecosystem spells it "
                        + "(eu.wohlben.qits:qits-eventstream).")
        String name;

        @CommandLine.Option(names = "--version", paramLabel = "<version>", required = true,
                description = "The released version to read again.")
        String version;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            MaintenanceCommand maintenance = parent.parent;
            boolean json = maintenance.json();
            String wantedEcosystem = MaintenanceCommand.required(ecosystem, "Name the ecosystem: --ecosystem <ecosystem>.");
            String wantedName = MaintenanceCommand.required(name, "Name the artifact: --name <name>.");
            String wantedVersion = MaintenanceCommand.required(version, "Name the version: --version <version>.");
            JsonNode answer = maintenance.maintenance(context)
                    .ingestArtifactSbom(wantedEcosystem, wantedName, wantedVersion);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            String id = text(answer, "id");
            context.out().println("Queued an SBOM read of " + wantedName + " " + wantedVersion + " (" + wantedEcosystem
                    + "): job " + id);
            context.out().println("It is read asynchronously; run `qits maintenance sbom versions` again to see "
                    + "the result.");
            return 0;
        }
    }
}
