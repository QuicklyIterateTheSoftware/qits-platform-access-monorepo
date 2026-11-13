package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits pitches}: a named agent's mock snapshots of the UI builder's screenshots (qits-2155),
 * kept per agent with every version of their HTML. A pitch names the repository, route and
 * screenshot of the freeze it was drawn over; a person compares it with the original without
 * committing to anything.
 * <p>
 * The writes read their payload on stdin as {@code qits work design put} does ({@link WorkPayload}),
 * and with none print the payload's schema from the service's OpenAPI document. Only a named agent
 * creates a pitch, and only the pitch's agent adds a version: the service words that 403 itself, so
 * those two post with no role hint.
 * <p>
 * Titles, notes and the HTML are written by agents, so every value a table shows goes through
 * {@link SafeText}, and the JSON form through {@link SafeJson}.
 */
@CommandLine.Command(name = "pitches", mixinStandardHelpOptions = true,
        subcommands = {PitchesCommand.ListCommand.class, PitchesCommand.ShowCommand.class,
                PitchesCommand.CreateCommand.class, PitchesCommand.AddVersionCommand.class,
                PitchesCommand.ArchiveCommand.class},
        description = {"A project's pitches: mock HTML snapshots a named agent (such as ui-builder) draws over one "
                + "of the UI builder's screenshots, kept per agent with every version. list shows a project's "
                + "pitches, show one with its versions or one version's HTML, create pitches a new one, add-version "
                + "adds a version to one, and archive hides one from the list.",
                "A pitch names the repository, route and screenshot of the freeze it was drawn over, its kind "
                        + "(DATA, NO_DATA, PENDING or ERROR), viewport (PHONE, TABLET or DESKTOP), color scheme (LIGHT, "
                        + "DARK, or none) and the freeze's manifest version. Versions are never rewritten: each "
                        + "change is a new one.",
                "A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or "
                        + "empty) it sends nothing and prints its usage and the payload's JSON schema instead, from "
                        + "the service's OpenAPI document."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --pitch, --output and --projects-url may come before or after the command.",
                "- --pitch is the pitch's id, as list and create print it.",
                "- Reading takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk, qits:agent or "
                        + "qits:system, in the pitch's project.",
                "- Only a named agent creates a pitch, in its own project, and only the pitch's agent adds a "
                        + "version; everybody else is refused (HTTP 403), an admin included. The pitch's agent, "
                        + "qits:admin or qits:desk-admin may archive it.",
                "- The HTML is not cleaned by the service: show it only in a sandboxed frame with no scripts."})
public class PitchesCommand implements Runnable {

    static final String NAME_THE_PITCH = "Name the pitch: --pitch <id>.";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--pitch", paramLabel = "<pitch>", scope = CommandLine.ScopeType.INHERIT,
            description = "The pitch: its id, passed to the service as it is.")
    String pitch;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    String pitch() throws CliFailure {
        return RepositoriesCommand.required(pitch, NAME_THE_PITCH);
    }

    ProjectsApi api(CliContext context) throws CliFailure, InterruptedException {
        return ProjectsApi.connect(context, options.projectsUrl);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"A project's pitches, the most recently changed first, without their HTML. Each row is "
                    + "the id, the agent, the repository, the route, the screenshot, the latest version, whether "
                    + "archived, the title and when it last changed. The archived ones are left out unless "
                    + "--archived is given, which adds them beside the rest.",
                    "Reads GET /projects/api/pitches?project=<project>, with each other option as a filter of the "
                            + "same name."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits pitches list --project qits",
                    "  qits pitches list --project qits --agent ui-builder --repository qits-landing-app "
                            + "--route /work",
                    "  qits pitches list --project qits --screenshot work/board-desktop-dark.html --archived -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a project or repository it does not know, HTTP 404), or "
                            + "cannot be reached.",
                    "2:Used wrongly (for example no --project), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        PitchesCommand pitches;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id or its slug.")
        String project;

        @CommandLine.Option(names = "--agent", paramLabel = "<agent>",
                description = "Only this named agent's pitches, such as ui-builder.")
        String agent;

        @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
                description = "Only the pitches over this repository's screenshots: its id or its name.")
        String repository;

        @CommandLine.Option(names = "--route", paramLabel = "<route>",
                description = "Only the pitches over this route, as the freeze names it (for example /work).")
        String route;

        @CommandLine.Option(names = "--screenshot", paramLabel = "<file>",
                description = "Only the pitches over this screenshot: its file in the freeze's manifest.")
        String screenshot;

        @CommandLine.Option(names = "--archived", description = "Show the archived pitches too, beside the others.")
        boolean archived;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(pitches.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            JsonNode answer = pitches.api(context).pitches(wanted, agent, repository, route, screenshot, archived);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> all = objects(answer.path("pitches"));
            if (all.isEmpty()) {
                context.out().println("No pitches match.");
                return 0;
            }
            printRows(context.out(), all);
            return 0;
        }
    }

    // --- show ---

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"One pitch. Without --number: its fields and its versions (number, size, note, when), "
                    + "without their HTML, from GET /projects/api/pitches/{pitch}. With --number: that version's "
                    + "HTML alone, from GET /projects/api/pitches/{pitch}/versions/{n}, so it can go straight to a "
                    + "file; -o json then prints the version's number, html, htmlBytes, note and createdAt."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits pitches show --pitch 6f1c0e2a-...",
                    "  qits pitches show --pitch 6f1c0e2a-... --number latest > pitch.html",
                    "  qits pitches --pitch 6f1c0e2a-... show --number 1 -o json | jq -r .note",
                    "",
                    "- --number is a version's number, from 1, or latest."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a pitch or version it does not know, HTTP 404), or cannot "
                            + "be reached.",
                    "2:Used wrongly (for example no --pitch, or a --number that is neither a number nor latest), "
                            + "not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        PitchesCommand pitches;

        @CommandLine.Option(names = "--number", paramLabel = "<n|latest>",
                description = "Print the HTML of the version with this number instead of the pitch: a number from 1, "
                        + "or latest.")
        String number;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(pitches.options.output);
            String id = pitches.pitch();
            if (number == null) {
                JsonNode detail = pitches.api(context).pitch(id);
                if (json) {
                    SafeJson.print(context.out(), detail);
                    return 0;
                }
                printDetail(context.out(), detail);
                return 0;
            }
            JsonNode answer = pitches.api(context).pitchVersion(id, number(number));
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            WorkEntities.lines(context.out(), "", text(answer, "html"));
            return 0;
        }

        /** {@code latest}, or a whole number from 1; anything else is a usage error before any request. */
        static String number(String given) throws CliFailure {
            String value = given.strip();
            if (value.equalsIgnoreCase("latest")) {
                return "latest";
            }
            if (value.matches("[1-9][0-9]{0,8}")) {
                return value;
            }
            throw new CliFailure("--number is a number from 1, or latest, not " + SafeText.line(value)
                    + ". Nothing was sent.", CliFailure.USAGE);
        }
    }

    // --- create ---

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Pitch a new mock of one of the UI builder's screenshots, as its first version.",
                    "Reads the payload, a JSON object, on stdin and sends it unchanged to POST /projects/api/pitches: "
                            + "{\"project\", \"repository\", \"route\", \"screenshot\", \"kind\", \"viewport\", "
                            + "\"colorScheme\"?, \"manifestVersion\", \"title\", \"note\"?, \"html\", \"versionNote\"?}. "
                            + "\"project\" is the project's id or slug and \"repository\" the repository's id or name; "
                            + "\"route\", \"screenshot\" (the file in the freeze's manifest), \"kind\", \"viewport\", "
                            + "\"colorScheme\" and \"manifestVersion\" are the freeze's. \"html\" is a complete HTML "
                            + "document with inline styles and no scripts; \"versionNote\" says what version 1 is. The "
                            + "command prints the pitch, its id first.",
                    "Only a named agent may create a pitch, in its own project; it becomes the pitch's owner.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's JSON schema from the service's OpenAPI document (/projects/q/openapi), and "
                            + "exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits pitches create </dev/null",
                    "  jq -n --rawfile h board.html '{project:\"qits\", repository:\"qits-landing-app\", route:\"/work\", "
                            + "screenshot:\"work/board-desktop-dark.html\", kind:\"DATA\", viewport:\"DESKTOP\", "
                            + "colorScheme:\"DARK\", manifestVersion:\"2026.1113.1\", title:\"A darker board header\", "
                            + "html:$h}' | qits pitches create",
                    "",
                    "- The first one prints the schema: nothing is sent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The pitch is written, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a field it does not take or an empty html, HTTP 400, a "
                            + "caller that is not a named agent of the project, HTTP 403, a project or repository it "
                            + "does not know, HTTP 404, or html too large, HTTP 413), or cannot be reached.",
                    "2:Used wrongly (for example a payload that is not a JSON object), not signed in, or the session "
                            + "ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        PitchesCommand pitches;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(pitches.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = pitches.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "POST", ProjectsApi.PITCHES, List.of("application/json"));
                return 0;
            }
            JsonNode detail = api.createPitch(payload);
            if (json) {
                SafeJson.print(context.out(), detail);
                return 0;
            }
            printDetail(context.out(), detail);
            return 0;
        }
    }

    // --- add-version ---

    @CommandLine.Command(name = "add-version", mixinStandardHelpOptions = true,
            description = {"Add a version to a pitch: the next number after its latest. Earlier versions stay as "
                    + "they are.",
                    "Reads the payload, {\"html\":\"...\",\"note\"?:\"...\"}, on stdin and sends it unchanged to POST "
                            + "/projects/api/pitches/{pitch}/versions. \"html\" is a complete HTML document with "
                            + "inline styles and no scripts; \"note\" says what changed. The command prints the new "
                            + "version without its HTML.",
                    "Only the pitch's own agent may add a version, and an archived pitch takes none.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's JSON schema from the service's OpenAPI document (/projects/q/openapi), and "
                            + "exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits pitches add-version --pitch 6f1c0e2a-... </dev/null",
                    "  jq -n --rawfile h board.html '{html:$h, note:\"Saved views under the filters\"}' "
                            + "| qits pitches add-version --pitch 6f1c0e2a-..."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The version is written, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example an empty html, HTTP 400, a caller that is not the pitch's "
                            + "agent, HTTP 403, a pitch it does not know, HTTP 404, an archived pitch, HTTP 409, or "
                            + "html too large, HTTP 413), or cannot be reached.",
                    "2:Used wrongly (for example no --pitch, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class AddVersionCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        PitchesCommand pitches;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(pitches.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = pitches.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "POST", ProjectsApi.PITCHES + "/{id}/versions",
                        List.of("application/json"));
                return 0;
            }
            JsonNode added = api.addPitchVersion(pitches.pitch(), payload);
            if (json) {
                SafeJson.print(context.out(), added);
                return 0;
            }
            printVersion(context.out(), added);
            return 0;
        }
    }

    // --- archive ---

    @CommandLine.Command(name = "archive", mixinStandardHelpOptions = true,
            description = {"Archive a pitch: hide it from the list. Nothing is deleted, and archiving an archived "
                    + "pitch changes nothing.",
                    "Sends POST /projects/api/pitches/{pitch}/archive and prints the pitch. The pitch's agent, "
                            + "qits:admin or qits:desk-admin may archive it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits pitches archive --pitch 6f1c0e2a-..."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The pitch is archived.",
                    "1:The platform refused (for example a caller that is neither the pitch's agent nor an admin, "
                            + "HTTP 403, or a pitch it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --pitch), not signed in, or the session ended."})
    public static class ArchiveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        PitchesCommand pitches;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(pitches.options.output);
            JsonNode detail = pitches.api(context).archivePitch(pitches.pitch());
            if (json) {
                SafeJson.print(context.out(), detail);
                return 0;
            }
            printDetail(context.out(), detail);
            return 0;
        }
    }

    // --- the tables ---

    /** ID, AGENT, REPOSITORY, ROUTE, SCREENSHOT, VERSION, ARCHIVED, TITLE, UPDATED. */
    static void printRows(PrintStream out, List<JsonNode> pitches) {
        Table.print(out, "", List.of("ID", "AGENT", "REPOSITORY", "ROUTE", "SCREENSHOT", "VERSION", "ARCHIVED", "TITLE",
                "UPDATED"), pitches.stream()
                .map(p -> List.of(WorkEntities.cell(text(p, "id"), 40), WorkEntities.cell(text(p, "agent"), 20),
                        WorkEntities.cell(text(p, "repositoryName"), 40), WorkEntities.cell(text(p, "route"), 40),
                        WorkEntities.cell(text(p, "screenshot"), 60), p.path("latestVersion").asText(),
                        text(p, "archivedAt").isEmpty() ? "-" : "yes", WorkEntities.cell(text(p, "title"), 60),
                        Table.time(text(p, "updatedAt"))))
                .toList());
    }

    /** One pitch: its fields, its note and its versions, never HTML. */
    static void printDetail(PrintStream out, JsonNode p) {
        out.println("PITCH " + SafeText.line(text(p, "id")));
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("title", text(p, "title")));
        rows.add(row("agent", text(p, "agent")));
        String repositoryName = text(p, "repositoryName");
        String repositoryId = text(p, "repositoryId");
        rows.add(row("repository", repositoryName.isEmpty() ? repositoryId : repositoryName + " (" + repositoryId + ")"));
        rows.add(row("route", text(p, "route")));
        rows.add(row("screenshot", text(p, "screenshot")));
        String scheme = text(p, "colorScheme");
        rows.add(row("freeze", text(p, "kind") + ", " + text(p, "viewport") + (scheme.isEmpty() ? "" : ", " + scheme)
                + ", manifest " + text(p, "manifestVersion")));
        rows.add(row("latest version", p.path("latestVersion").asText()));
        String archivedAt = text(p, "archivedAt");
        rows.add(List.of("archived", archivedAt.isEmpty() ? "no" : "yes (" + Table.time(archivedAt) + ")"));
        rows.add(List.of("created", Table.time(text(p, "createdAt"))));
        rows.add(List.of("updated", Table.time(text(p, "updatedAt"))));
        Table.print(out, "  ", null, rows);
        out.println("Note:");
        String note = text(p, "note");
        if (note.isBlank()) {
            out.println("  (none)");
        } else {
            WorkEntities.lines(out, "  ", note);
        }
        List<JsonNode> versions = objects(p.path("versions"));
        if (versions.isEmpty()) {
            return;
        }
        out.println("Versions (" + versions.size() + "):");
        Table.print(out, "  ", List.of("VERSION", "BYTES", "NOTE", "CREATED"), versions.stream()
                .map(v -> List.of(v.path("number").asText(), v.path("htmlBytes").asText(),
                        WorkEntities.cell(text(v, "note"), 80), Table.time(text(v, "createdAt"))))
                .toList());
    }

    /** One version without its HTML, as add-version answers it. */
    static void printVersion(PrintStream out, JsonNode v) {
        out.println("VERSION " + SafeText.line(v.path("number").asText()));
        Table.print(out, "  ", null, List.of(
                List.of("bytes", v.path("htmlBytes").asText()),
                row("note", text(v, "note")),
                List.of("created", Table.time(text(v, "createdAt")))));
    }

    private static List<String> row(String name, String value) {
        return List.of(name, WorkEntities.cell(value, 600));
    }

    private static List<JsonNode> objects(JsonNode array) {
        List<JsonNode> all = new ArrayList<>();
        array.forEach(e -> {
            if (e.isObject()) {
                all.add(e);
            }
        });
        return all;
    }
}
