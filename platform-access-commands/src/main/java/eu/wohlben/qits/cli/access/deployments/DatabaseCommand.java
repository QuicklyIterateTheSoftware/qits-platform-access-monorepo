package eu.wohlben.qits.cli.access.deployments;

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
import java.util.Locale;
import java.util.TreeSet;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits deployments database}: an application's database moved to the other postgres server or
 * to another name, and the record of every move, a deployment's and an operator's alike (qits-2175).
 * A move is a redeploy of the live version whose provisioning copies, verifies and switches the
 * database, so the service is stopped while it runs, and the answer is only that it was queued.
 * {@code abort} stops one before its switch (qits-2243).
 */
@CommandLine.Command(name = "database", mixinStandardHelpOptions = true,
        subcommands = {DatabaseCommand.MoveCommand.class, DatabaseCommand.MovesCommand.class,
                DatabaseCommand.MoveStatusCommand.class, DatabaseCommand.AbortCommand.class},
        description = {"An application's database, moved by qits-deployments to the other postgres server (public "
                + "or private) or to another name, and the record of every move: a deployment's and an operator's.",
                "A move stops the application's service for its whole duration: it is a redeploy of the live "
                        + "version that copies the database, compares both sides' per-table row counts, and only "
                        + "then switches. The source is kept, closed, until its drop-after time."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --deployments-url may come before or after the command.",
                "- Every door but `abort` takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or "
                        + "qits:agent; qits:system is refused, move included. `abort` refuses qits:agent too "
                        + "(HTTP 403)."})
public class DatabaseCommand implements Runnable {

    /** The servers a database can be held on, as the service names them. */
    static final List<String> SERVERS = List.of("public", "private");

    @CommandLine.Option(names = "--deployments-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, "
                    + "else the session's idp address with `idp` swapped for `deployments`.")
    String deploymentsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    DeploymentsApi deployments(CliContext context) throws CliFailure, InterruptedException {
        return new DeploymentsApi(new PlatformClient(context.credential()),
                PlatformUrls.deployments(deploymentsUrl, context.env(), context.idpUrl()));
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

    /** {@code server/database}, or a dash when the move names neither. */
    static String place(JsonNode move, String server, String database) {
        String s = text(move, server);
        String d = text(move, database);
        if (s.isEmpty() && d.isEmpty()) {
            return "-";
        }
        return (s.isEmpty() ? "?" : s) + "/" + (d.isEmpty() ? "?" : d);
    }

    // --- move ---

    @CommandLine.Command(name = "move", mixinStandardHelpOptions = true,
            description = {"Move one database of an application to the other postgres server or to another name. "
                    + "It prints the queued move: its id, its state, source -> target, and the service's note when "
                    + "the target overrides the application's spec.",
                    "POST /deployments/api/database-moves with {application, resource?, server?, database?} (HTTP "
                            + "202 {move, note}). A member left out defaults to what the live version's spec "
                            + "resolves.",
                    "THE APPLICATION'S SERVICE IS STOPPED FOR THE WHOLE MOVE: the move is a redeploy of its live "
                            + "version, whose provisioning copies the database, compares both sides' per-table "
                            + "counts and only then switches. Anything short of a match is undone, the source "
                            + "untouched. `move-status` follows it.",
                    "A target that differs from the spec is moved back by the next deployment of the application, "
                            + "which provisions what its spec resolves to: change the spec to keep it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments database move --application qits-ci --server private",
                    "  qits deployments database move --application qits-ci --resource db --database qits_ci_new",
                    "",
                    "- --resource is required when the application holds more than one database.",
                    "- HTTP 400: a malformed name or an unknown server, or no --resource where several databases are "
                            + "held. HTTP 404: no such application, or it holds no such database. HTTP 409: refused "
                            + "(already there, the deployer's own database, a target another application holds, "
                            + "nothing serving to redeploy) or deferred (the target server is not deployed in the "
                            + "environment). Each is exit 1, and nothing was stopped.",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class MoveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DatabaseCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>", required = true,
                description = "The application, as qits-deployments names it: qits-ci.")
        String application;

        @CommandLine.Option(names = "--resource", paramLabel = "<name>",
                description = "Which of its databases, by the resource name its spec gives it: db. Required when "
                        + "it holds more than one.")
        String resource;

        @CommandLine.Option(names = "--server", paramLabel = "public|private",
                description = "The server to move it to. Default: the one the spec resolves.")
        String server;

        @CommandLine.Option(names = "--database", paramLabel = "<name>",
                description = "The database name to move it to. Default: the one the spec resolves.")
        String database;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String app = SettingsCommand.required(application, "Name the application: --application <application>.");
            String to = null;
            if (server != null && !server.isBlank()) {
                to = server.strip().toLowerCase(Locale.ROOT);
                if (!SERVERS.contains(to)) {
                    throw new CliFailure("--server is public or private, not '" + SafeText.line(server) + "'.",
                            CliFailure.USAGE);
                }
            }
            JsonNode answer = parent.deployments(context).moveDatabase(app, resource, to, database);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printQueued(context.out(), answer);
            return 0;
        }
    }

    /**
     * {@code move <id>: <STATE>}, then {@code <application> <resource>: source -> target}, then the
     * note when there is one, and how to follow the move.
     */
    static void printQueued(PrintStream out, JsonNode answer) {
        JsonNode move = answer == null ? null : answer.path("move");
        if (move == null || !move.isObject()) {
            out.println("queued");
            return;
        }
        String id = text(move, "id");
        out.println("move " + SafeText.line(id) + ": " + SafeText.line(text(move, "state")));
        out.println(SafeText.line(text(move, "applicationName"))
                + (text(move, "resourceName").isEmpty() ? "" : " " + SafeText.line(text(move, "resourceName")))
                + ": " + SafeText.line(place(move, "sourceServer", "sourceDatabase"))
                + " -> " + SafeText.line(place(move, "targetServer", "targetDatabase")));
        String note = text(answer, "note");
        if (!note.isEmpty()) {
            out.println("note: " + SafeText.line(note));
        }
        if (!id.isEmpty()) {
            out.println("Follow it: qits deployments database move-status --id " + SafeText.line(id));
        }
    }

    // --- moves ---

    @CommandLine.Command(name = "moves", mixinStandardHelpOptions = true,
            description = {"List the database moves, newest first (at most 200): of one application, or of every "
                    + "one. A deployment's own moves are listed with an operator's.",
                    "GET /deployments/api/database-moves[?application=]. Columns: ID, APPLICATION, RESOURCE, SOURCE, "
                            + "TARGET (server/database), STATE, STARTED, FINISHED, DROP AFTER (when the kept source "
                            + "copy is dropped)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments database moves",
                    "  qits deployments database moves --application qits-ci -o json",
                    "",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class MovesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DatabaseCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>",
                description = "Only this application's moves: qits-ci.")
        String application;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode answer = parent.deployments(context).databaseMoves(application);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> moves = new ArrayList<>();
            answer.path("moves").forEach(moves::add);
            if (moves.isEmpty()) {
                context.out().println("No database moves"
                        + (application == null || application.isBlank() ? "" : " of " + SafeText.line(application.strip()))
                        + ".");
                return 0;
            }
            printMoves(context.out(), moves);
            return 0;
        }
    }

    /** The moves table: ID, APPLICATION, RESOURCE, SOURCE, TARGET, STATE, STARTED, FINISHED, DROP AFTER. */
    static void printMoves(PrintStream out, List<JsonNode> moves) {
        Table.print(out, "", List.of("ID", "APPLICATION", "RESOURCE", "SOURCE", "TARGET", "STATE", "STARTED",
                        "FINISHED", "DROP AFTER"),
                moves.stream().map(m -> List.of(
                        Table.cell(text(m, "id"), 36),
                        Table.cell(text(m, "applicationName"), 32),
                        Table.cell(text(m, "resourceName"), 24),
                        Table.cell(place(m, "sourceServer", "sourceDatabase"), 60),
                        Table.cell(place(m, "targetServer", "targetDatabase"), 60),
                        Table.cell(text(m, "state"), 12),
                        Table.time(text(m, "startedAt")),
                        Table.time(text(m, "finishedAt")),
                        Table.time(text(m, "dropAfter")))).toList());
    }

    // --- move-status ---

    @CommandLine.Command(name = "move-status", mixinStandardHelpOptions = true,
            description = {"One database move in full: where from and where to, its state, who asked, what the spec "
                    + "resolves to, the kept source copy and when it is dropped, the reason it stopped when it did, "
                    + "and both sides' per-table row counts once they were read.",
                    "GET /deployments/api/database-moves/{id}. States: QUEUED, STOPPING, EXTRACTING, INGESTING, "
                            + "VERIFIED, SWITCHING, then MOVED, ABORTED or REFUSED (detail says why)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments database move-status --id 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    "  qits deployments database move-status --id 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f -o json",
                    "",
                    "- In the counts, a table both sides counted differently is marked with !; a dash is a side not read yet.",
                    "- HTTP 404 (exit 1): no move of that id.",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class MoveStatusCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DatabaseCommand parent;

        @CommandLine.Option(names = "--id", paramLabel = "<id>", required = true,
                description = "The move's id, as `move` and `moves` print it.")
        String id;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String move = SettingsCommand.required(id, "Name the move: --id <id>.");
            JsonNode answer = parent.deployments(context).databaseMove(move);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printMove(context.out(), answer.path("move"));
            return 0;
        }
    }

    /** One move as name/value lines, then the per-table counts when either side was read. */
    static void printMove(PrintStream out, JsonNode move) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("id", Table.cell(text(move, "id"), 36)));
        rows.add(List.of("application", Table.cell(text(move, "applicationName"), 64)));
        rows.add(List.of("environment", Table.cell(text(move, "environmentName"), 64)));
        rows.add(List.of("resource", Table.cell(text(move, "resourceName"), 64)));
        rows.add(List.of("state", Table.cell(text(move, "state"), 32)));
        rows.add(List.of("source", Table.cell(place(move, "sourceServer", "sourceDatabase"), 120)));
        rows.add(List.of("target", Table.cell(place(move, "targetServer", "targetDatabase"), 120)));
        if (!text(move, "specServer").isEmpty() || !text(move, "specDatabase").isEmpty()) {
            rows.add(List.of("spec resolves", Table.cell(place(move, "specServer", "specDatabase"), 120)));
        }
        rows.add(List.of("origin", Table.cell(text(move, "origin"), 32)));
        rows.add(List.of("requested by", Table.cell(text(move, "requestedBy"), 64)));
        rows.add(List.of("kept copy", Table.cell(text(move, "keptDatabase"), 120)));
        rows.add(List.of("detail", Table.cell(text(move, "detail"), 400)));
        rows.add(List.of("created", Table.time(text(move, "createdAt"))));
        rows.add(List.of("started", Table.time(text(move, "startedAt"))));
        rows.add(List.of("finished", Table.time(text(move, "finishedAt"))));
        rows.add(List.of("drop after", Table.time(text(move, "dropAfter"))));
        rows.add(List.of("dropped", Table.time(text(move, "droppedAt"))));
        rows.add(List.of("rows", count(move.get("sourceRows")) + " -> " + count(move.get("targetRows"))));
        bytesRow(rows, "source bytes", move.get("sourceBytes"));
        bytesRow(rows, "target free bytes", move.get("targetFreeBytes"));
        bytesRow(rows, "volume free bytes", move.get("volumeFreeBytes"));
        Table.print(out, "", null, rows);
        JsonNode source = move.path("sourceCounts");
        JsonNode target = move.path("targetCounts");
        if (!source.isObject() && !target.isObject()) {
            return;
        }
        TreeSet<String> tables = new TreeSet<>();
        source.fieldNames().forEachRemaining(tables::add);
        target.fieldNames().forEachRemaining(tables::add);
        out.println();
        Table.print(out, "", List.of("TABLE", "SOURCE", "TARGET", ""), tables.stream().map(t -> {
            String from = count(source.get(t));
            String to = count(target.get(t));
            boolean differ = !from.equals("-") && !to.equals("-") && !from.equals(to);
            return List.of(Table.cell(t, 80), from, to, differ ? "!" : "");
        }).toList());
    }

    /** A count as a number, or a dash when that side was not read. */
    private static String count(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? "-" : Long.toString(value.asLong());
    }

    /** A byte count row, only when the service sent one. */
    private static void bytesRow(List<List<String>> rows, String label, JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            rows.add(List.of(label, Long.toString(value.asLong())));
        }
    }

    // --- abort ---

    @CommandLine.Command(name = "abort", mixinStandardHelpOptions = true,
            description = {"Stop a running database move before its switch: the extract or ingest container is "
                    + "stopped, the move ends ABORTED, and the application's service is scaled back up on the "
                    + "source. Prints the move as it stood when the abort was asked, the same way `move-status` "
                    + "does: the service answers before undoing the running step, so the state printed here is "
                    + "still the one the move was in (EXTRACTING or INGESTING); run `move-status` once it is "
                    + "done to see it ABORTED.",
                    "POST /deployments/api/database-moves/{id}/abort with no body (HTTP 202, the same move shape "
                            + "`move-status` reads)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments database abort --id 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    "",
                    "- HTTP 404: no move of that id. HTTP 409: the move is QUEUED, already finished, or at "
                            + "SWITCHING or later; there is nothing left to stop.",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin or qits:desk; qits:agent is refused "
                            + "(HTTP 403)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class AbortCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DatabaseCommand parent;

        @CommandLine.Option(names = "--id", paramLabel = "<id>", required = true,
                description = "The move's id, as `move` and `moves` print it.")
        String id;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String move = SettingsCommand.required(id, "Name the move: --id <id>.");
            JsonNode answer = parent.deployments(context).abortDatabaseMove(move);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printMove(context.out(), answer.path("move"));
            return 0;
        }
    }
}
