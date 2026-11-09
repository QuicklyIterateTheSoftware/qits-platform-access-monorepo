package eu.wohlben.qits.cli.access.ci;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.ReleaseRequestSource;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestRefs;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.access.complete.RunSource;
import eu.wohlben.qits.cli.access.report.ReportCommand;
import eu.wohlben.qits.cli.tui.api.Completes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "ci", mixinStandardHelpOptions = true, modelTransformer = CiCommand.StepCommands.class,
        subcommands = {CiCommand.RunsCommand.class, CiCommand.RunCommand.class, CiCommand.RetryCommand.class,
                CiCommand.CancelCommand.class, ReportCommand.class},
        description = {"The builds of qits-ci: runs lists a repository's runs, run shows one run with its steps and "
                        + "their logs, retry runs a finished run again, cancel stops a queued or running one, and "
                        + "report shows a run's release reports (report submit is the CI step's side, which collects "
                        + "and uploads them).",
                "A release request's gating runs build its backing branch release/<request id> and carry the "
                        + "request's id. Statuses: QUEUED and RUNNING (not finished), SUCCESS, FAILED (the code's "
                        + "verdict), and CANCELLED, TIMED_OUT, CONFIG_ERROR (the run's end, not a verdict on the code)."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- Reading runs takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk, qits:system or "
                        + "qits:agent. retry takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or "
                        + "qits:agent. cancel takes the same, qits:desk and qits:agent bound to the run's own "
                        + "project.",
                "- To follow a run, run `qits ci run <run id>` again. There is no live log stream. A build's verdict "
                        + "also comes as an event: `qits events --filter=BuildSuccessful,BuildFailed`.",
                "- --project, --repository, --output and the two -url options may come before or after the command.",
                "- A release request's QA run carries release reports: which tests failed, with their class, name "
                        + "and message, and the line coverage of the tree and of the change, with more kinds to "
                        + "come. `qits ci report show <run id>` lists them."})
public class CiCommand implements Runnable {

    /** A run id as qits-ci makes it: a UUID. A shorter id is the start of one. */
    static final int WHOLE_ID = 36;

    static final String NAME_THE_PROJECT = "Name the project: --project <id, slug or name>.";
    static final String NAME_THE_REPOSITORY = "Name the repository: --repository <id or name>.";

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @Completes(RepositorySource.class)
    @CommandLine.Option(names = "--repository", paramLabel = "<repository>", scope = CommandLine.ScopeType.INHERIT,
            description = "The repository: its id or name.")
    String repository;

    @CommandLine.Option(names = "--ci-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp "
                    + "address with `idp` swapped for `ci` (https://idp.qits.<QITS_DOMAIN>/idp gives "
                    + "https://ci.qits.<QITS_DOMAIN>).")
    String ciUrl;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The projects service's base URL, without /projects, where --project and --repository "
                    + "are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url.")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed, with "
                    + "control characters written as escapes.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /**
     * Takes the inherited options off the commands that run in a CI step ({@code report submit}).
     * They take the run from {@code QITS_CI_*} and the address from {@code QITS_DOMAIN}, so a
     * {@code --project}, a {@code --repository} or an address would only be refused; listed in their
     * help, they would read as if they did something. Inheritance happens as the tree is built, so
     * this runs on {@code ci}, once its commands are in place.
     */
    @RegisterForReflection
    public static final class StepCommands implements CommandLine.IModelTransformer {

        @Override
        public CommandLine.Model.CommandSpec transform(CommandLine.Model.CommandSpec ci) {
            CommandLine report = ci.subcommands().get("report");
            CommandLine submit = report == null ? null : report.getSubcommands().get("submit");
            if (submit != null) {
                CommandLine.Model.CommandSpec spec = submit.getCommandSpec();
                for (CommandLine.Model.OptionSpec option : List.copyOf(spec.options())) {
                    if (option.inherited()) {
                        spec.remove(option);
                    }
                }
            }
            return ci;
        }
    }

    /** The run a command under {@code qits ci report} names, and the ci service that holds it. */
    public record Target(CiApi ci, String runId, boolean lookedUp) {

        /** A 404 on this run: say so, and how a short id is found. */
        public CliFailure noSuchRun() {
            return CiCommand.noSuchRun(runId, lookedUp);
        }
    }

    /**
     * For the commands under {@code qits ci report}, which live in their own package: the run
     * {@code wanted} names, its whole id or, with --project and --repository, its start, as
     * {@code qits ci run} reads it.
     */
    public Target target(CliContext context, String wanted) throws CliFailure, InterruptedException {
        boolean lookedUp = repositoryNamed();
        Apis apis = connect(context);
        return new Target(apis.ci(), runId(context, apis, wanted), lookedUp);
    }

    /** {@code --output}, as given; null when it was not. */
    public String output() {
        return output;
    }

    /**
     * The inherited options that name an address, a project or a repository and were given. A CI
     * step's command takes all of that from its environment instead, and refuses them.
     */
    public List<String> addressingOptionsGiven() {
        List<String> given = new ArrayList<>();
        if (!blank(project)) {
            given.add("--project");
        }
        if (!blank(repository)) {
            given.add("--repository");
        }
        if (!blank(ciUrl)) {
            given.add("--ci-url");
        }
        if (!blank(projectsUrl)) {
            given.add("--projects-url");
        }
        return given;
    }

    /** The session, the client that carries its token, and the ci service. */
    private record Apis(PlatformClient client, String idpUrl, CiApi ci) {
    }

    private record Repository(String projectLabel, String id, String name) {

        String listCommand() {
            return "`qits ci runs --project " + projectLabel + " --repository " + name + "`";
        }
    }

    private Apis connect(CliContext context) throws CliFailure, InterruptedException {
        String idpUrl = context.idpUrl();
        PlatformClient client = new PlatformClient(context.credential());
        return new Apis(client, idpUrl, new CiApi(client, PlatformUrls.ci(ciUrl, context.env(), idpUrl)));
    }

    /** The repository --project and --repository name, found in the projects service. */
    private Repository repository(CliContext context, Apis apis) throws CliFailure, InterruptedException {
        ProjectsApi projects = new ProjectsApi(apis.client(),
                PlatformUrls.projects(projectsUrl, context.env(), apis.idpUrl()));
        JsonNode found = projects.project(project.strip());
        JsonNode repo = projects.repository(found, repository.strip());
        return new Repository(ProjectsApi.projectLabel(found), text(repo, "id"), text(repo, "name"));
    }

    /** Both, or neither: a run is found by its whole id, or by its start among one repository's runs. */
    private boolean repositoryNamed() throws CliFailure {
        boolean projectNamed = !blank(project);
        if (projectNamed != !blank(repository)) {
            throw new CliFailure("Give both --project and --repository, or neither.", CliFailure.USAGE);
        }
        return projectNamed;
    }

    /**
     * The run's whole id. With a repository named, {@code wanted} may be the start of it; the
     * lookup reads that repository's runs. Without one, it is used as it is.
     */
    private String runId(CliContext context, Apis apis, String wanted) throws CliFailure, InterruptedException {
        if (!repositoryNamed()) {
            return wanted;
        }
        Repository repo = repository(context, apis);
        String start = wanted.toLowerCase(Locale.ROOT);
        List<JsonNode> matches = new ArrayList<>();
        apis.ci().runs(repo.id(), null).path("runs").forEach(r -> {
            if (text(r, "id").toLowerCase(Locale.ROOT).startsWith(start)) {
                matches.add(r);
            }
        });
        if (matches.isEmpty()) {
            throw new CliFailure("Repository " + repo.name() + " has no run whose id starts with '" + wanted + "'. "
                    + repo.listCommand() + " shows them.", CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' is the start of more than one run of " + repo.name() + ": "
                    + matches.stream().map(r -> SafeText.line(text(r, "id"))).collect(Collectors.joining(", "))
                    + ". Give more of the id.", CliFailure.USAGE);
        }
        return text(matches.getFirst(), "id");
    }

    /** A 404 on a run: say so, and how a short id is found. */
    private static CliFailure noSuchRun(String id, boolean lookedUp) {
        String hint = !lookedUp && id.length() < WHOLE_ID
                ? " A short id is found only with --project and --repository." : "";
        return new CliFailure("No such run: " + id + " (HTTP 404)." + hint, CliFailure.FAILED);
    }

    static String required(String value, String message) throws CliFailure {
        if (blank(value)) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @CommandLine.Command(name = "runs", mixinStandardHelpOptions = true,
            description = {"List a repository's CI runs, newest first.",
                    "Columns: the run's id (its first 8 characters, enough for `run` and `retry` with --project and "
                            + "--repository), status, branch, commit, the release request, when it was created, and "
                            + "how long it took (so far, while it runs)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ci runs --project qits --repository qits-ci-service",
                    "  qits ci runs --project qits --repository qits-ci-service --status FAILED --limit 5",
                    "  qits ci runs --project qits --repository qits-ci-service --release-request qits-ci-service-rr-12",
                    "  qits ci runs --project qits --repository qits-ci-service --branch main -o json",
                    "",
                    "- A release request's gating runs: --release-request with the request's id as `qits release-request "
                            + "list` shows it (qits-ci-service-rr-12), its uuid or the start of the uuid. They build "
                            + "the request's branch, release/<request id>.",
                    "- The service filters by repository and count only. --branch, --status and --release-request "
                            + "are applied here, over all of the repository's runs, and --limit after them.",
                    "- The listing carries no step output (each step's output is null here): read it with "
                            + "`qits ci run <run id> --logs`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class RunsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CiCommand parent;

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>",
                description = "Only the runs of this branch: main, release/<request id>, or a version for a release run.")
        String branch;

        @CommandLine.Option(names = "--status", paramLabel = "<STATUS>",
                description = "Only the runs in this status: QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, TIMED_OUT "
                        + "or CONFIG_ERROR.")
        String status;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--release-request", paramLabel = "<id>",
                description = "Only the runs of this release request: its id (qits-ci-service-rr-12), its uuid, or the "
                        + "start of the uuid.")
        String releaseRequest;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>", defaultValue = "20",
                description = "At most this many runs, the newest. Default: ${DEFAULT-VALUE}.")
        int limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            required(parent.project, NAME_THE_PROJECT);
            required(parent.repository, NAME_THE_REPOSITORY);
            if (limit < 1) {
                throw new CliFailure("--limit must be 1 or more.", CliFailure.USAGE);
            }
            Apis apis = parent.connect(context);
            Repository repo = parent.repository(context, apis);
            Predicate<JsonNode> filter = filter();
            // The service counts before any filter here, so a filtered list asks for every run.
            JsonNode answer = apis.ci().runs(repo.id(), filter == null ? Integer.valueOf(limit) : null);
            List<JsonNode> runs = new ArrayList<>();
            for (JsonNode run : answer.path("runs")) {
                if (runs.size() < limit && (filter == null || filter.test(run))) {
                    runs.add(run);
                }
            }
            if (json) {
                ObjectNode copy = answer.isObject() ? ((ObjectNode) answer).deepCopy() : JsonNodeFactory.instance.objectNode();
                ArrayNode kept = copy.arrayNode();
                runs.forEach(kept::add);
                copy.set("runs", kept);
                RunView.printJson(context.out(), copy);
                return 0;
            }
            if (runs.isEmpty()) {
                context.out().println(filter == null ? "No runs of " + repo.name() + "."
                        : "No runs of " + repo.name() + " match.");
                return 0;
            }
            RunView.printRuns(context.out(), runs, context.clock().instant());
            return 0;
        }

        /** Null when nothing narrows the list. */
        private Predicate<JsonNode> filter() {
            List<Predicate<JsonNode>> all = new ArrayList<>();
            if (!blank(branch)) {
                String wanted = branch.strip();
                all.add(r -> wanted.equals(text(r, "branch")));
            }
            if (!blank(status)) {
                String wanted = status.strip().toUpperCase(Locale.ROOT);
                all.add(r -> wanted.equals(text(r, "status").toUpperCase(Locale.ROOT)));
            }
            if (!blank(releaseRequest)) {
                String wanted = releaseRequest.strip();
                all.add(r -> ReleaseRequestRefs.matches(text(r, "releaseRequestQualifiedId"),
                        text(r, "releaseRequestId"), wanted));
            }
            return all.stream().reduce(Predicate::and).orElse(null);
        }
    }

    @CommandLine.Command(name = "run", mixinStandardHelpOptions = true,
            description = {"Show one CI run: what it built, its status, and its steps with their exit codes.",
                    "Without --logs, a failed step's last lines are shown too, enough to see why it failed. "
                            + "--logs prints every step's whole output instead, the first step first. The service "
                            + "keeps the end of each step's output. A running step shows what it has printed so "
                            + "far. Terminal control characters are taken out."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ci run 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 --logs",
                    "  qits ci run 5f2c0a9e --project qits --repository qits-ci-service",
                    "  qits ci run 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 -o json | jq -r .status",
                    "",
                    "- <run id> is the run's whole id, or its start when --project and --repository name the "
                            + "repository.",
                    "- The exit code says whether the read worked, not whether the run passed. Read the status.",
                    "- -o json prints the service's answer, the logs included."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:Done, whatever the run's status.",
                    "1:The platform refused (for example no such run, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example an id start that fits no run of the repository, or more than one), "
                            + "not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class RunCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CiCommand parent;

        @Completes(RunSource.class)
        @CommandLine.Parameters(index = "0", paramLabel = "<run id>",
                description = "The run: its id, or its start when --project and --repository name its repository.")
        String runId;

        @CommandLine.Option(names = "--logs",
                description = "Also print each step's output, with terminal control characters taken out.")
        boolean logs;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String wanted = required(runId, "Name the run: qits ci run <run id>.");
            boolean lookedUp = parent.repositoryNamed();
            Apis apis = parent.connect(context);
            String id = parent.runId(context, apis, wanted);
            JsonNode run;
            try {
                run = apis.ci().run(id);
            } catch (CliFailure refused) {
                throw refused.status() == 404 ? noSuchRun(id, lookedUp) : refused;
            }
            PrintStream out = context.out();
            if (json) {
                RunView.printJson(out, run);
                return 0;
            }
            RunView.printRun(out, run, context.clock().instant());
            if (logs) {
                out.println();
                RunView.printLogs(out, run);
            } else {
                RunView.printFailedTails(out, run);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "retry", mixinStandardHelpOptions = true,
            description = {"Run a finished CI run again: the same commit, the same pipeline, the same release request.",
                    "For a red run that was the platform's fault, not the code's: a flaked container, a registry that "
                            + "was down, a step that ran out of time on a busy host. The new run is queued; the "
                            + "command prints its id and how to follow it. Its verdict counts for the release request "
                            + "like the first run's would have. A release-request automation's run (a dependency "
                            + "bump, golden recordings, the entity diagram, screenshot baselines, cli-tapes, estate "
                            + "pins) is refused instead (HTTP 409): re-run it with `qits release-request regenerate "
                            + "--kind <kind>`."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ci retry 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17",
                    "  qits ci retry 5f2c0a9e --project qits --repository qits-ci-service",
                    "",
                    "- Only a finished run can be retried. One that is queued or running answers HTTP 409: wait for it.",
                    "- A retry builds the same commit. To fix the code, push the branch instead: the release request "
                            + "folds again and builds the new commit.",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The new run is queued.",
                    "1:The platform refused: the run has not finished yet (HTTP 409), the run is an automation's "
                            + "run (HTTP 409), there is no such run (HTTP 404), or your roles do not allow it "
                            + "(HTTP 403). Or it cannot be reached.",
                    "2:Used wrongly (for example an id start that fits no run of the repository, or more than one), "
                            + "not signed in, or the session ended."})
    public static class RetryCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CiCommand parent;

        @Completes(RunSource.class)
        @CommandLine.Parameters(index = "0", paramLabel = "<run id>",
                description = "The run to retry: its id, or its start when --project and --repository name its "
                        + "repository.")
        String runId;

        private static final String ANSWERED_409 = "answered HTTP 409: ";

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String wanted = required(runId, "Name the run: qits ci retry <run id>.");
            boolean lookedUp = parent.repositoryNamed();
            Apis apis = parent.connect(context);
            String id = parent.runId(context, apis, wanted);
            JsonNode answer;
            try {
                answer = apis.ci().retry(id);
            } catch (CliFailure refused) {
                if (refused.status() == 409) {
                    // qits-1825: a 409 can mean the run has not finished, is an automation's run (qits-1816),
                    // or something a provider adds later (qits-ci's retired-runner health check); the
                    // service's own sentence is passed on rather than guessed at, and only a 409 with no
                    // service message at all falls back to a plain refusal.
                    String said = refused.getMessage();
                    int at = said.indexOf(ANSWERED_409);
                    throw new CliFailure((at < 0 ? "The platform refused to retry run " + id
                            : said.substring(at + ANSWERED_409.length())) + " (HTTP 409).", CliFailure.FAILED);
                }
                throw refused.status() == 404 ? noSuchRun(id, lookedUp) : refused;
            }
            PrintStream out = context.out();
            if (json) {
                RunView.printJson(out, answer);
                return 0;
            }
            String next = SafeText.line(text(answer, "runId"));
            out.println("Run " + id + " runs again as run " + next + ", at the same commit. It is queued.");
            out.println("Follow it:  qits ci run " + next);
            out.println("Its verdict also comes as an event:  qits events --filter=BuildSuccessful,BuildFailed");
            return 0;
        }
    }

    @CommandLine.Command(name = "cancel", mixinStandardHelpOptions = true,
            description = {"Stop a queued or running CI run, then show it.",
                    "A queued run is cancelled at once and never starts. A running one is asked to stop: its step "
                            + "in flight is stopped and the rest are skipped, which takes a moment, so the run shown "
                            + "may still say RUNNING. Run `qits ci run <run id>` again to see it end. A cancelled run "
                            + "ends CANCELLED, which is not a verdict on the code: it publishes neither BuildSuccessful "
                            + "nor BuildFailed.",
                    "Sends POST /ci/api/runs/{runId}/cancel with {reason} (HTTP 202), then GET /ci/api/runs/{runId}."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ci cancel 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17",
                    "  qits ci cancel 5f2c0a9e --project qits --repository qits-ci-service --reason 'superseded by a fix'",
                    "",
                    "- Only a queued or running run can be cancelled. One that has finished answers HTTP 409.",
                    "- --reason is kept on the run as its cancellation reason, and `qits ci run` shows it.",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent; a desk or an "
                            + "agent only for a run in its own project, and another project's run answers HTTP 403.",
                    "- -o json prints the run as the service answers it after the cancel."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The run is cancelled, or asked to stop; it is printed.",
                    "1:The platform refused: the run has already finished (HTTP 409), there is no such run (HTTP 404), "
                            + "or your roles do not allow it (HTTP 403). Or it cannot be reached.",
                    "2:Used wrongly (for example an id start that fits no run of the repository, or more than one), "
                            + "not signed in, or the session ended."})
    public static class CancelCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CiCommand parent;

        @Completes(RunSource.class)
        @CommandLine.Parameters(index = "0", paramLabel = "<run id>",
                description = "The run to cancel: its id, or its start when --project and --repository name its "
                        + "repository.")
        String runId;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                description = "Why it is cancelled, kept on the run.")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String wanted = required(runId, "Name the run: qits ci cancel <run id>.");
            boolean lookedUp = parent.repositoryNamed();
            Apis apis = parent.connect(context);
            String id = parent.runId(context, apis, wanted);
            try {
                apis.ci().cancel(id, blank(reason) ? null : reason.strip());
            } catch (CliFailure refused) {
                if (refused.status() == 409) {
                    throw new CliFailure("Run " + id + " has already finished, so there is nothing to cancel "
                            + "(HTTP 409).", CliFailure.FAILED);
                }
                throw refused.status() == 404 ? noSuchRun(id, lookedUp) : refused;
            }
            JsonNode run = apis.ci().run(id);
            PrintStream out = context.out();
            if (json) {
                RunView.printJson(out, run);
                return 0;
            }
            RunView.printRun(out, run, context.clock().instant());
            return 0;
        }
    }
}
