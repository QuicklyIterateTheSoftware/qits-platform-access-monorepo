package eu.wohlben.qits.cli.access.maintenance;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.ReleaseRequestSource;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestRefs;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "maintenance", mixinStandardHelpOptions = true,
        subcommands = {MaintenanceCommand.AutomationsCommand.class, AutomationCommand.class,
                MaintenanceCommand.BumpCommand.class, SbomCommand.class},
        description = {"Jobs of qits-maintenance: automations lists a release request's release-request "
                        + "automations and their state, automation run re-runs one kind, bump shows how one "
                        + "job went, and sbom lists an artifact's released versions and their SBOM ingestion."},
        footerHeading = "%nNotes:%n",
        footer = {"- --project, --repository, --output and the two -url options may come before or after the command."})
public class MaintenanceCommand implements Runnable {

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @Completes(RepositorySource.class)
    @CommandLine.Option(names = "--repository", paramLabel = "<repository>", scope = CommandLine.ScopeType.INHERIT,
            description = "The repository: its id or name.")
    String repository;

    @CommandLine.Option(names = "--maintenance-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, "
                    + "else the session's idp address with `idp` swapped for `maintenance`.")
    String maintenanceUrl;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The projects service's base URL, without /projects, where --project, --repository and "
                    + "--request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url.")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): one line per field. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    MaintenanceApi maintenance(CliContext context) throws CliFailure, InterruptedException {
        PlatformClient client = new PlatformClient(context.credential());
        return new MaintenanceApi(client, PlatformUrls.maintenance(maintenanceUrl, context.env(), context.idpUrl()));
    }

    boolean json() throws CliFailure {
        if (output == null || output.isBlank() || output.equalsIgnoreCase("table")) {
            return false;
        }
        if (output.equalsIgnoreCase("json")) {
            return true;
        }
        throw new CliFailure("--output is table or json, not '" + output + "'.", CliFailure.USAGE);
    }

    static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    /** The bump, one field per line. */
    static void printBump(PrintStream out, JsonNode bump) {
        for (String field : List.of("id", "mode", "repository", "branch", "status", "message", "resultSha",
                "releaseRequestId", "ciRunId", "startedAt", "finishedAt")) {
            String value = text(bump, field);
            if (!value.isEmpty()) {
                out.printf("%-17s %s%n", field, SafeText.line(value));
            }
        }
    }

    /**
     * The repository's request that {@code wanted} names, of any state: its id ({@code
     * <repository>-rr-<n>}), its uuid or the start of the uuid. qits-maintenance takes only the uuid,
     * so the commands send the uuid this finds.
     */
    static JsonNode request(ProjectsApi projects, String repoId, String repoName, String wanted)
            throws CliFailure, InterruptedException {
        return ReleaseRequestRefs.find(projects.releaseRequests(repoId, "all").path("requests"), wanted, repoName, "");
    }

    /** The id or sha, shortened to the first 8 characters a table has room for. */
    static String shortId(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    @CommandLine.Command(name = "automations", mixinStandardHelpOptions = true,
            description = {"List a release request's release-request automations and their state: the "
                    + "regenerations that must be fresh before the request can proceed, each one a kind the "
                    + "platform decided applies to this repository.",
                    "The request holds until every automation that applies to the repository is fresh for its "
                            + "merged commit. --fold reads an older fold; without it, the request's newest one."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits maintenance --project qits --repository qits-landing-app automations "
                            + "--request qits-landing-app-rr-7",
                    "",
                    "- RUN is the newest run of the kind's current attempt. `qits maintenance automation run` "
                            + "re-runs one; `qits ci run <id> --logs` shows a run."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class AutomationsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MaintenanceCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The release request: its id as `qits release-request list` shows it "
                        + "(qits-landing-app-rr-7), its uuid, or the start of the uuid.")
        String request;

        @CommandLine.Option(names = "--fold", paramLabel = "<sha>",
                description = "The fold to read; without it, the request's newest.")
        String fold;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wantedProject = required(parent.project, "Name the project: --project <id, slug or name>.");
            String wantedRepository = required(parent.repository, "Name the repository: --repository <id or name>.");
            String wantedRequest = required(request, "Name the release request: --request <id>.");
            ProjectsApi projects = ProjectsApi.connect(context, parent.projectsUrl);
            JsonNode foundProject = projects.project(wantedProject);
            JsonNode repo = projects.repository(foundProject, wantedRepository);
            String requestId = text(request(projects, text(repo, "id"), text(repo, "name"), wantedRequest), "id");

            JsonNode answer = parent.maintenance(context).automations(requestId, fold);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> automations = new ArrayList<>();
            answer.path("automations").forEach(automations::add);
            if (automations.isEmpty()) {
                context.out().println("No release-request automation applies to " + text(repo, "name") + ".");
                return 0;
            }
            String foldSha = text(answer, "foldSha");
            Table.print(context.out(), "", List.of("KIND", "STATE", "FOLD", "RUN", "DETAIL"),
                    automations.stream().map(a -> List.of(
                            cell(text(a, "kind"), 24),
                            cell(text(a, "state"), 12),
                            cell(shortId(foldSha), 8),
                            cell(shortId(newestRun(a)), 8),
                            // The last column, so a long sentence costs no alignment: a DEFERRED kind's
                            // detail says how to regenerate it, and 60 cut that off (qits-1920).
                            cell(text(a, "detail"), DETAIL_WIDTH))).toList());
            return 0;
        }

        /** How much of an automation's sentence the table shows (qits-1920). */
        static final int DETAIL_WIDTH = 200;

        /** The newest of a kind's runs: the one whose outcome the state reflects. */
        private static String newestRun(JsonNode automation) {
            JsonNode runIds = automation.path("runIds");
            return runIds.isArray() && !runIds.isEmpty() ? runIds.get(runIds.size() - 1).asText("") : "";
        }

        private static String cell(String value, int max) {
            return Table.cell(SafeText.line(value), max);
        }
    }

    @CommandLine.Command(name = "bump", mixinStandardHelpOptions = true,
            description = {"Show one qits-maintenance job: its mode, status, the sentence about how it went, and the "
                    + "commit it left."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits maintenance bump 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    "",
                    "- Run it again to follow a job: REQUESTED and RUNNING are not finished."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class BumpCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MaintenanceCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<id>", description = "The job's id, as the request printed it.")
        String id;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode bump = parent.maintenance(context).bump(required(id, "Name the job: qits maintenance bump <id>."));
            if (json) {
                ProjectsApi.printJson(context.out(), bump);
                return 0;
            }
            printBump(context.out(), bump);
            return 0;
        }
    }
}
