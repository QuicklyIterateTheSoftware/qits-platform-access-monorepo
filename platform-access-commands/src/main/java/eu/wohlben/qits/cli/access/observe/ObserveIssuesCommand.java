package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static eu.wohlben.qits.cli.access.observe.ObserveTelemetryCommands.add;
import static eu.wohlben.qits.cli.access.observe.ObserveTelemetryCommands.cell;
import static eu.wohlben.qits.cli.access.observe.ObserveTelemetryCommands.printJson;
import static eu.wohlben.qits.cli.access.observe.ObserveTelemetryCommands.text;
import static eu.wohlben.qits.cli.access.observe.ObserveTelemetryCommands.truncation;

/**
 * {@code qits observe issues list|show|dismiss} (epic qits-2538): qits-observability's stored error
 * issues, each one fingerprint of ERROR logs folded into a count, hourly counts, versions and a
 * sample. Unlike the buffer reads they survive a restart and are never evicted.
 * <p>
 * Every option is sent only when it is given, so the service's own defaults apply (state OPEN,
 * limit 200, hours 168). An issue's message and stack trace come from the process that logged it,
 * and a ticket's title from whoever wrote it, so every value goes through {@link SafeText}.
 */
@TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
@CommandLine.Command(name = "issues", mixinStandardHelpOptions = true,
        subcommands = {ObserveIssuesCommand.ListCommand.class, ObserveIssuesCommand.ShowCommand.class,
                ObserveIssuesCommand.DismissCommand.class},
        description = {"Stored error issues: every ERROR log of a platform service, folded by its fingerprint "
                + "into one issue with a count, hourly counts, versions and a sample stack trace. An issue stays "
                + "OPEN until a person dismisses it, and opens again when it recurs.",
                "list reads the issues, show one issue in full and the tickets made from it, dismiss closes one."})
public class ObserveIssuesCommand implements Runnable {

    /** An issue's id: 32 lowercase hex characters. */
    static final String FINGERPRINT = "[0-9a-f]{32}";

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static String fingerprint(String value) throws CliFailure {
        String fingerprint = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!fingerprint.matches(FINGERPRINT)) {
            throw new CliFailure("A fingerprint is 32 hex characters, as `qits observe issues list` shows it, not '"
                    + SafeText.line(value) + "'.", CliFailure.USAGE);
        }
        return fingerprint;
    }

    /** {@code <exceptionType simple name>: <message>}, or the message alone. */
    static String title(JsonNode issue) {
        String type = text(issue, "exceptionType");
        String message = text(issue, "message");
        if (type.isEmpty()) {
            return message;
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return message.isEmpty() ? simple : simple + ": " + message;
    }

    static void range(String name, Integer value, int min, int max) throws CliFailure {
        if (value != null && (value < min || value > max)) {
            throw new CliFailure(name + " must be " + min + " to " + max + ", not " + value + ".", CliFailure.USAGE);
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The stored error issues, the last seen first: fingerprint, state, service, how often "
                    + "it occurred, when it was last seen, and its title (exception type and message).",
                    "Reads GET /observability/api/error-issues. The fingerprint is what show and dismiss take."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe issues list",
                    "  qits observe issues list --state ALL --service qits-ci",
                    "  qits observe issues list --state DISMISSED -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (the message names the status), or cannot be reached.",
                    "2:Used wrongly (for example a --limit outside 1 to 1000), not signed in, or the session "
                            + "ended."})
    public static class ListCommand extends ObserveTelemetryCommands.TelemetryRead {

        @CommandLine.Option(names = "--state", paramLabel = "<OPEN|DISMISSED|ALL>",
                description = "Only issues in this state, or ALL. Default: OPEN.")
        String state;

        @CommandLine.Option(names = "--service", paramLabel = "<name>",
                description = "Only issues of this service.name.")
        String service;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "At most n issues, 1 to 1000. Default: 200.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            String wanted = state == null ? null : state.strip().toUpperCase(Locale.ROOT);
            if (wanted != null && !List.of("OPEN", "DISMISSED", "ALL").contains(wanted)) {
                throw new CliFailure("--state must be OPEN, DISMISSED or ALL, not '" + SafeText.line(state) + "'.",
                        CliFailure.USAGE);
            }
            range("--limit", limit, 1, 1000);
            List<String> query = new ArrayList<>();
            add(query, "state", wanted);
            add(query, "service", service);
            add(query, "limit", limit);
            JsonNode answer = issues(context, "", query);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode issue : answer.path("issues")) {
                rows.add(List.of(cell(text(issue, "fingerprint"), 32), cell(text(issue, "state"), 9),
                        cell(text(issue, "serviceName"), 40), cell(text(issue, "occurrences"), 12),
                        Table.time(text(issue, "lastSeen")), cell(title(issue), 100)));
            }
            if (rows.isEmpty()) {
                context.out().println("No error issues.");
                return 0;
            }
            Table.print(context.out(), "", List.of("FINGERPRINT", "STATE", "SERVICE", "COUNT", "LAST-SEEN", "TITLE"),
                    rows);
            truncation(context.out(), answer, rows.size());
            return 0;
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"One error issue in full: state, service, first and last version, code location, first "
                    + "and last seen, occurrences, regressions, who dismissed it, the hourly counts, the sample "
                    + "stack trace, and the tickets of --project made from it.",
                    "Reads GET /observability/api/error-issues/{fingerprint}, then the project's tickets that "
                            + "carry the fingerprint as errorIssue. -o json prints {\"issue\": ..., \"tickets\": "
                            + "[...]}."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe issues show 0123456789abcdef0123456789abcdef",
                    "  qits observe issues show 0123456789abcdef0123456789abcdef --hours 24 -o json",
                    "",
                    "- Make a ticket from an issue by naming its fingerprint as errorIssue in the ticket's payload:",
                    "  echo '{\"project\":\"qits\",\"title\":\"...\",\"ticketType\":\"BUG\",\"impetus\":\"...\","
                            + "\"errorIssue\":\"<fingerprint>\"}' | qits work create --archetype ticket",
                    "- One issue may have many tickets, for example a new one after a regression; show lists "
                            + "them all.",
                    "- When the tickets cannot be read, the issue is still shown, with a line saying so."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an unknown fingerprint, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example a fingerprint that is not 32 hex characters), not signed in, or "
                            + "the session ended."})
    public static class ShowCommand extends ObserveTelemetryCommands.TelemetryRead {

        @CommandLine.Parameters(index = "0", paramLabel = "<fingerprint>",
                description = "The issue's fingerprint, 32 hex characters.")
        String fingerprint;

        @CommandLine.Option(names = "--hours", paramLabel = "<n>",
                description = "The hourly counts of the last n hours, 1 to 720. Default: 168.")
        Integer hours;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>", defaultValue = "qits",
                description = "The project whose tickets made from the issue are shown: its id or its slug. "
                        + "Default: ${DEFAULT-VALUE}.")
        String project;

        @CommandLine.Option(names = "--projects-url", paramLabel = "<url>",
                description = "The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, "
                        + "else the session's idp address with `idp` swapped for `projects`.")
        String projectsUrl;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            String wanted = fingerprint(fingerprint);
            range("--hours", hours, 1, 720);
            List<String> query = new ArrayList<>();
            add(query, "hours", hours);
            JsonNode issue = issues(context, "/" + wanted, query);
            List<JsonNode> tickets = null;
            String unread = null;
            try {
                JsonNode answer = ProjectsApi.connect(context, projectsUrl).errorIssueTickets(project, wanted);
                tickets = new ArrayList<>();
                answer.path("entities").forEach(tickets::add);
            } catch (CliFailure failed) {
                unread = failed.getMessage();
                context.err().println("The tickets of project " + SafeText.line(project) + " could not be read: "
                        + SafeText.line(unread));
            }
            if (json) {
                ObjectNode both = JsonNodeFactory.instance.objectNode();
                both.set("issue", issue);
                if (tickets == null) {
                    both.putNull("tickets");
                } else {
                    both.putArray("tickets").addAll(tickets);
                }
                printJson(context.out(), both);
                return 0;
            }
            print(context.out(), issue, tickets, wanted);
            return 0;
        }

        private static void print(PrintStream out, JsonNode issue, List<JsonNode> tickets, String fingerprint) {
            out.println("ERROR ISSUE " + fingerprint + "  " + Table.cell(title(issue), 120));
            List<List<String>> rows = new ArrayList<>();
            field(rows, "state", text(issue, "state"));
            field(rows, "service", text(issue, "serviceName"));
            String first = text(issue, "firstVersion");
            String last = text(issue, "lastVersion");
            field(rows, "versions", first.equals(last) || last.isEmpty() ? first : first + " -> " + last);
            field(rows, "location", text(issue, "location"));
            field(rows, "exception", text(issue, "exceptionType"));
            field(rows, "message", text(issue, "sampleMessage"));
            rows.add(List.of("first seen", Table.time(text(issue, "firstSeen"))));
            rows.add(List.of("last seen", Table.time(text(issue, "lastSeen"))));
            field(rows, "occurrences", text(issue, "occurrences"));
            field(rows, "regressions", text(issue, "regressions"));
            if (!text(issue, "regressedAt").isEmpty()) {
                rows.add(List.of("regressed at", Table.time(text(issue, "regressedAt"))));
            }
            if (!text(issue, "dismissedAt").isEmpty()) {
                rows.add(List.of("dismissed at", Table.time(text(issue, "dismissedAt"))));
                field(rows, "dismissed by", text(issue, "dismissedBy"));
            }
            field(rows, "last trace", text(issue, "sampleTraceId"));
            Table.print(out, "  ", null, rows);

            List<List<String>> hourly = new ArrayList<>();
            for (JsonNode hour : issue.path("hourly")) {
                hourly.add(List.of(Table.time(text(hour, "hour")), cell(text(hour, "count"), 12)));
            }
            if (hourly.isEmpty()) {
                out.println("hourly: none.");
            } else {
                out.println("hourly:");
                Table.print(out, "  ", List.of("HOUR", "COUNT"), hourly);
            }

            String trace = text(issue, "sampleStacktrace");
            if (trace.isBlank()) {
                out.println("stack trace: none.");
            } else {
                out.println("stack trace:");
                for (String line : trace.strip().split("\\R")) {
                    out.println("  " + SafeText.line(line.strip()));
                }
            }

            if (tickets == null) {
                out.println("tickets: could not be read.");
            } else if (tickets.isEmpty()) {
                out.println("tickets: none.");
            } else {
                out.println("tickets:");
                List<List<String>> ticketRows = new ArrayList<>();
                for (JsonNode ticket : tickets) {
                    ticketRows.add(List.of(cell(text(ticket, "qualifiedId"), 40), cell(text(ticket, "status"), 20),
                            cell(text(ticket, "title"), 100)));
                }
                Table.print(out, "  ", null, ticketRows);
            }
            out.println("logs: qits observe query --filter 'kind=log attr.qits.error.fingerprint=" + fingerprint
                    + "' --since 1d");
        }

        private static void field(List<List<String>> rows, String name, String value) {
            if (!value.isEmpty()) {
                rows.add(List.of(name, cell(value, 200)));
            }
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.WRITE)
    @CommandLine.Command(name = "dismiss", mixinStandardHelpOptions = true,
            description = {"Dismiss an error issue: it is closed until its fingerprint occurs again, when it opens "
                    + "again as a regression and keeps its history.",
                    "Calls POST /observability/api/error-issues/{fingerprint}/dismiss. A person decides this: "
                            + "qits:admin, qits:admin-agent or qits:desk-admin may, and the agent credential "
                            + "(qits:agent) is refused with HTTP 403."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe issues dismiss 0123456789abcdef0123456789abcdef"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example the agent credential, HTTP 403, or an unknown "
                            + "fingerprint, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example a fingerprint that is not 32 hex characters), not signed in, or "
                            + "the session ended."})
    public static class DismissCommand extends ObserveTelemetryCommands.TelemetryRead {

        @CommandLine.Parameters(index = "0", paramLabel = "<fingerprint>",
                description = "The issue's fingerprint, 32 hex characters.")
        String fingerprint;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            String wanted = fingerprint(fingerprint);
            JsonNode answer = dismiss(context, wanted);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            context.out().println("dismissed " + wanted);
            return 0;
        }
    }
}
