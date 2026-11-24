package eu.wohlben.qits.cli.access.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
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
 * {@code qits orchestrator} (qits-2490): qits-orchestrator's technical processes (garbage
 * collection, the manifest scan, the SBOM check) and their runs, on the five doors the landing
 * app's Orchestrator pages call. The descriptions name no other command group, and none of the
 * four verbs followed by a space, because the TUI's filter matches descriptions too.
 */
@CommandLine.Command(name = "orchestrator", mixinStandardHelpOptions = true,
        subcommands = {OrchestratorCommand.ProcessesCommand.class, OrchestratorCommand.RunsCommand.class,
                OrchestratorCommand.StartCommand.class, OrchestratorCommand.RunCommand.class},
        description = {"The technical processes of qits-orchestrator, such as garbage collection, the manifest "
                + "scan and the SBOM check: list them with their steps, list one kind's past and current "
                + "executions, start one (or a dry rehearsal where the kind offers it), and show one with its steps "
                + "and what each step asked and was answered."},
        footerHeading = "%nNotes:%n",
        footer = {"- Reading takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent. "
                + "Starting takes the same roles, but a commissioned agent (qits:agent alone) may start only the "
                + "kinds open to one.",
                "- --output and --orchestrator-url may come before or after the command."})
public class OrchestratorCommand implements Runnable {

    @CommandLine.Option(names = "--orchestrator-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The orchestrator service's base URL, without /orchestrator. Default: QITS_ORCHESTRATOR_URL, "
                    + "else the session's idp address with `idp` swapped for `orchestrator`.")
    String orchestratorUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    OrchestratorApi api(CliContext context) throws CliFailure, InterruptedException {
        PlatformClient client = new PlatformClient(context.credential());
        return new OrchestratorApi(client, PlatformUrls.orchestrator(orchestratorUrl, context.env(), context.idpUrl()));
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

    private static String cell(String value, int max) {
        return Table.cell(value, max);
    }

    private static String yesNo(JsonNode node, String field) {
        return node.path(field).asBoolean(false) ? "yes" : "no";
    }

    /** The processes: kind, name, whether a dry run is offered, and how many steps each has. */
    static void printProcesses(PrintStream out, JsonNode processes) {
        List<List<String>> rows = new ArrayList<>();
        processes.forEach(p -> rows.add(List.of(cell(text(p, "kind"), 32), cell(text(p, "name"), 48),
                yesNo(p, "dryRunnable"), String.valueOf(p.path("steps").size()))));
        Table.print(out, "", List.of("KIND", "NAME", "DRY RUN", "STEPS"), rows);
    }

    /** One process's runs, newest first; the id whole, since the run's view is asked by it. */
    static void printRuns(PrintStream out, JsonNode runs) {
        List<List<String>> rows = new ArrayList<>();
        runs.forEach(r -> rows.add(List.of(cell(text(r, "id"), 36), cell(text(r, "status"), 12),
                cell(text(r, "trigger"), 12), yesNo(r, "dryRun"), Table.time(text(r, "startedAt")),
                Table.time(text(r, "finishedAt")), cell(text(r, "summary"), 120))));
        Table.print(out, "", List.of("ID", "STATUS", "TRIGGER", "DRY RUN", "STARTED", "FINISHED", "SUMMARY"), rows);
    }

    /** One run: its fields, one per line, then its steps as a table, then each step's bodies where they came. */
    static void printRun(PrintStream out, JsonNode run) {
        field(out, "id", text(run, "id"));
        field(out, "kind", text(run, "kind"));
        field(out, "status", text(run, "status"));
        field(out, "trigger", text(run, "trigger"));
        field(out, "dryRun", yesNo(run, "dryRun"));
        field(out, "startedAt", Table.time(text(run, "startedAt")));
        field(out, "finishedAt", Table.time(text(run, "finishedAt")));
        field(out, "summary", text(run, "summary"));
        JsonNode steps = run.path("steps");
        if (steps.isEmpty()) {
            return;
        }
        out.println();
        List<List<String>> rows = new ArrayList<>();
        steps.forEach(s -> rows.add(List.of(cell(text(s, "id"), 40), cell(text(s, "status"), 12),
                cell(text(s, "target"), 16), cell(text(s, "httpStatus"), 4),
                cell(text(s, "error").isEmpty() ? text(s, "summary") : text(s, "error"), 120))));
        Table.print(out, "", List.of("STEP", "STATUS", "TARGET", "HTTP", "SUMMARY"), rows);
        steps.forEach(s -> {
            if (s.hasNonNull("response") || s.path("request").hasNonNull("body")) {
                out.println();
                out.println("--- step " + SafeText.line(text(s, "id")));
                printBodies(out, s);
            }
        });
    }

    /** One step: its fields, one per line, then what it sent and what it was answered. */
    static void printStep(PrintStream out, JsonNode step) {
        field(out, "id", text(step, "id"));
        field(out, "name", text(step, "name"));
        field(out, "target", text(step, "target"));
        field(out, "status", text(step, "status"));
        field(out, "startedAt", Table.time(text(step, "startedAt")));
        field(out, "finishedAt", Table.time(text(step, "finishedAt")));
        field(out, "httpStatus", text(step, "httpStatus"));
        field(out, "responseBytes", text(step, "responseBytes"));
        field(out, "error", text(step, "error"));
        field(out, "summary", text(step, "summary"));
        printBodies(out, step);
    }

    private static void printBodies(PrintStream out, JsonNode step) {
        JsonNode request = step.path("request");
        if (!request.isMissingNode() && !request.isNull()) {
            field(out, "request", (text(request, "method") + " " + text(request, "url")).strip());
            if (request.hasNonNull("body")) {
                out.println("request body:");
                out.println(SafeText.line(text(request, "body")));
            }
        }
        if (step.hasNonNull("response")) {
            out.println("response:");
            out.println(SafeText.line(text(step, "response")));
        }
    }

    private static void field(PrintStream out, String name, String value) {
        if (!value.isEmpty() && !value.equals("-")) {
            out.printf("%-14s %s%n", name, SafeText.line(value));
        }
    }

    @CommandLine.Command(name = "processes", mixinStandardHelpOptions = true,
            description = {"List every technical process the orchestrator knows: its kind, its name, whether it "
                    + "offers a dry rehearsal, and how many steps it has. -o json adds each one's description and "
                    + "its steps with their targets and dependencies."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits orchestrator processes",
                    "  qits orchestrator processes -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ProcessesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        OrchestratorCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode processes = parent.api(context).processes();
            if (json) {
                ProjectsApi.printJson(context.out(), processes);
                return 0;
            }
            if (processes.isEmpty()) {
                context.out().println("The orchestrator knows no process.");
                return 0;
            }
            printProcesses(context.out(), processes);
            return 0;
        }
    }

    @CommandLine.Command(name = "runs", mixinStandardHelpOptions = true,
            description = {"List one kind's executions, newest first: id, status, what started it (a person or the "
                    + "schedule), whether it was a dry rehearsal, when it started and ended, and its summary."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits orchestrator runs --kind scan",
                    "  qits orchestrator runs --kind gc --limit 5 -o json",
                    "",
                    "- Without --limit the service answers its default, 20. An unknown kind is HTTP 404."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class RunsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        OrchestratorCommand parent;

        @CommandLine.Option(names = "--kind", paramLabel = "<kind>", required = true,
                description = "The process's kind, as the processes listing names it: gc, scan, sbom-check.")
        String kind;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "At most this many, newest first. Default: the service's, 20.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wantedKind = required(kind, "Name the process: --kind <kind>.");
            if (limit != null && limit < 1) {
                throw new CliFailure("--limit is a positive number, not " + limit + ".", CliFailure.USAGE);
            }
            JsonNode runs = parent.api(context).runs(wantedKind, limit);
            if (json) {
                ProjectsApi.printJson(context.out(), runs);
                return 0;
            }
            if (runs.isEmpty()) {
                context.out().println("The " + wantedKind + " process has not run yet.");
                return 0;
            }
            printRuns(context.out(), runs);
            return 0;
        }
    }

    @CommandLine.Command(name = "start", mixinStandardHelpOptions = true,
            description = {"Start an execution of one process now, beside its schedule, and print its id. With "
                    + "--dry-run it rehearses: every step asks its target what it would do and changes nothing, "
                    + "for the kinds that offer that."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits orchestrator start --kind scan",
                    "  qits orchestrator start --kind gc --dry-run",
                    "",
                    "- Refusals: 400 a dry rehearsal of a kind without one, 403 a kind not open to the caller "
                            + "(a commissioned agent may not start scan or sbom-check), 404 an unknown kind, 409 one "
                            + "of that kind is still active.",
                    "- Follow it with `qits orchestrator run --id <id>` until its status is no longer RUNNING."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class StartCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        OrchestratorCommand parent;

        @CommandLine.Option(names = "--kind", paramLabel = "<kind>", required = true,
                description = "The process's kind, as the processes listing names it: gc, scan, sbom-check.")
        String kind;

        @CommandLine.Option(names = "--dry-run",
                description = "Rehearse: plan every step and change nothing. Only for a kind that offers it.")
        boolean dryRun;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wantedKind = required(kind, "Name the process: --kind <kind>.");
            JsonNode answer;
            try {
                answer = parent.api(context).start(wantedKind, dryRun);
            } catch (CliFailure refused) {
                throw explained(wantedKind, dryRun, refused);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            String id = text(answer, "id");
            context.out().println("Started " + (dryRun ? "a dry run of " : "") + wantedKind + ": run " + id);
            context.out().println("Follow it with `qits orchestrator run --id " + id + "`.");
            return 0;
        }

        /** The refusals the door names, each with the sentence that says what to do; the service's own words below it. */
        static CliFailure explained(String kind, boolean dryRun, CliFailure refused) {
            String said = switch (refused.status()) {
                case 400 -> dryRun ? "The " + kind + " process offers no dry run: start it without --dry-run." : null;
                case 403 -> "This credential may not start a " + kind + " run.";
                case 404 -> "There is no process of kind '" + kind + "': `qits orchestrator processes` lists them.";
                case 409 -> "A " + kind + " run is still active: wait for it to finish.";
                default -> null;
            };
            return said == null ? refused
                    : CliFailure.refused(said + System.lineSeparator() + refused.getMessage(), refused.status());
        }
    }

    @CommandLine.Command(name = "run", mixinStandardHelpOptions = true,
            description = {"Show one execution: its status, trigger and summary, and each of its steps with its "
                    + "status, target, HTTP status and summary. With --step, that one step whole, with the request "
                    + "it sent and the response it got."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits orchestrator run --id 00000000-0000-4000-8000-000000000001",
                    "  qits orchestrator run --id 00000000-0000-4000-8000-000000000001 --bodies",
                    "  qits orchestrator run --id 00000000-0000-4000-8000-000000000001 --step internal.follow",
                    "",
                    "- Without --bodies the steps' request bodies and responses are left out (their size stays, as "
                            + "responseBytes); a gc execution's answers can be large. --step always has them."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class RunCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        OrchestratorCommand parent;

        @CommandLine.Option(names = "--id", paramLabel = "<run id>", required = true,
                description = "The execution's id, as starting it printed it or the runs listing shows it.")
        String id;

        @CommandLine.Option(names = "--step", paramLabel = "<step id>",
                description = "One step of it, by its id (internal.follow, artifacts.plan).")
        String step;

        @CommandLine.Option(names = "--bodies",
                description = "Include every step's request body and response.")
        boolean bodies;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wantedId = required(id, "Name the execution: --id <run id>.");
            OrchestratorApi api = parent.api(context);
            if (step != null) {
                String wantedStep = required(step, "Name the step: --step <step id>.");
                JsonNode answer = api.step(wantedId, wantedStep);
                if (json) {
                    ProjectsApi.printJson(context.out(), answer);
                } else {
                    printStep(context.out(), answer);
                }
                return 0;
            }
            JsonNode answer = api.run(wantedId, bodies);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
            } else {
                printRun(context.out(), answer);
            }
            return 0;
        }
    }
}
