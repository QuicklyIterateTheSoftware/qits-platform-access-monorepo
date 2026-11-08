package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits wait}: an agent, or a project's desk, declares what it waits on, and the platform wakes
 * it when that happens (qits-1153, qits-1618). The command only declares; it never blocks. The wait
 * lives on the holder's row in qits-workspaces, which matches it against the domain events and gives
 * the holder a turn when one fits, or when the timeout passes.
 * <p>
 * The presets are the generic form with the event and the groups filled in (W8). The wire shape is
 * W8's, and nothing here knows more of an event than the path a preset names.
 */
@CommandLine.Command(name = "wait", mixinStandardHelpOptions = true,
        subcommands = {BranchWait.class, WaitCommand.ReleaseRequestWait.class, WaitCommand.WorkWait.class,
                WaitCommand.CiRunWait.class, WaitCommand.ListCommand.class, WaitCommand.CancelCommand.class},
        description = {"Declare what this holder waits on: a domain event that fits a selection. The platform "
                        + "gives the holder a turn when one arrives, or when the timeout passes. The command "
                        + "returns at once; it never blocks.",
                "Bare, it takes the event's name and its conditions. branch, release-request, work and ci-run are "
                        + "presets for the usual waits; list shows the holder's waits and cancel ends one.",
                "Prefer `wait branch` for your own work: a request id goes stale when it refolds or is superseded.",
                "Each --when is one group of conditions, separated by spaces, that must all hold. An event that fits "
                        + "any group matches; with no --when, every event of the name does. Conditions: path=value "
                        + "equal, path^=value starts with, path? present, !path absent. A path is the payload's fields "
                        + "joined by dots; [] after a field means some element of that array, and the conditions of "
                        + "one group that go through the same name[] must hold on the same element "
                        + "(entities[].entityId=<id> entities[].status=VERIFIED). Quote a value that holds spaces or "
                        + "is empty: label=\"two words\", note=\"\". At most 8 groups of 8 conditions.",
                "Two --when are OR, not AND: --when 'repoName=a' --when 'status=b' fires on either; --when "
                        + "'repoName=a status=b' needs both.",
                "The holder is --agent or --desk (give one, not both), else QITS_AGENT_ID, else QITS_DESK_PROJECT: "
                        + "the harness sets QITS_AGENT_ID in an agent's environment and QITS_DESK_PROJECT in a "
                        + "project's kept front-desk session, and the MCP server sets QITS_AGENT_ID from the "
                        + "caller's token's agent claim, or QITS_DESK_PROJECT from its project claim when the "
                        + "token names no agent and is a desk's (the role qits:desk-admin, or a context_kind of "
                        + "desk-session or desk-delivery). An agent may declare and cancel waits for itself only; "
                        + "a project's desk, for its own project's only."},
        footerHeading = "%nWaiting on the platform:%n",
        footer = {
                "- Declare the wait, then end your turn. You get a new turn that names what fired: the event, or "
                        + "the timeout.",
                "- Never poll in a background loop (sleep, watch, `qits events`) for something a wait can say.",
                "- While a wait is open, the platform shows the holder as waiting on its label, not as blocked.",
                "- A wait closes when it fires, times out, is cancelled, or breaks: the platform decided it can "
                        + "never fire (BROKEN, with a reason). Declare a new one to wait again.",
                "- Waits take qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent. Every "
                        + "agent, admin-agent included, declares, lists and cancels its own waits only; "
                        + "qits:desk-admin acts on the agents of its own project and names one with --agent; "
                        + "qits:admin and qits:system act on any agent.",
                "",
                "  qits wait branch ticket/qits-123 --until released",
                "  qits wait release-request qits-ci-service-rr-12",
                "  qits wait work qits-123 --status VERIFIED --timeout 2h",
                "  qits wait ci-run 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a",
                "  qits wait --event BuildFailed --when 'repoName=qits-ci-service branch=main' --label 'main to break'",
                "  qits wait --event EntityTransitioned --when 'entities[].status=VERIFIED' --timeout 30m",
                "  qits wait list --state OPEN",
                "  qits wait cancel 7c1e2d3f-0000-4000-8000-000000000001",
                "  qits wait release-request qits-ci-service-rr-12 --desk qits-ci"},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:The wait is declared; it is printed.",
                "1:The platform refused (the message names the status), or cannot be reached.",
                "2:Used wrongly (no holder, both --agent and --desk, no --event, a --when or --timeout that "
                        + "cannot be read or is over a limit), not signed in, or the session ended."})
public class WaitCommand extends PlatformCommand {

    static final String AGENT_VARIABLE = "QITS_AGENT_ID";
    static final String DESK_VARIABLE = "QITS_DESK_PROJECT";
    static final Set<String> STATES = Set.of("OPEN", "MATCHED", "TIMED_OUT", "BROKEN", "CANCELLED");
    private static final Pattern EVENT = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern WHOLE_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Required for the bare form, but not at picocli's level, which would ask it of every subcommand too. */
    @CommandLine.Option(names = "--event", paramLabel = "<Name>",
            description = "Required, unless a preset is named. The event's name, as the bus carries it: "
                    + "BuildFailed, EntityTransitioned.")
    String event;

    @CommandLine.Option(names = "--when", paramLabel = "<conditions>",
            description = "One group of conditions, for example 'runId=<id> status=SUCCESS'. Give it again for "
                    + "another group, up to 8: that is OR, not AND, so an event fitting either fires the wait. "
                    + "Conditions that must all hold go in the one quoted --when. Without one, every event of the "
                    + "name fits.")
    List<String> when;

    @CommandLine.Mixin
    Declaration declaration;

    @CommandLine.Option(names = "--agent", paramLabel = "<id>", scope = CommandLine.ScopeType.INHERIT,
            description = "The agent whose waits these are. Mutually exclusive with --desk. Default: "
                    + "QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim).")
    String agent;

    @CommandLine.Option(names = "--desk", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project whose desk's waits these are: its id, slug or name. Mutually exclusive "
                    + "with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's "
                    + "project claim, when the token names no agent and is a desk's).")
    String desk;

    @CommandLine.Option(names = "--workspaces-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, "
                    + "else derived from the idp address, like the other services.")
    String workspacesUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): the wait for a person. json: the service's answer, pretty-printed, "
                    + "with control characters written as escapes.")
    String output;

    /** What a declared wait is called and how long it may stay open: the bare form and every preset take both. */
    public static class Declaration {

        @CommandLine.Option(names = "--timeout", paramLabel = "<duration>",
                description = "Close the wait and wake the agent after this long, if nothing fitted: 30m, 2h, 1h30m, "
                        + "1d or ISO-8601 (PT2H). Default: 8h, the platform's ceiling for an unstated timeout; the "
                        + "most any wait may run is 24h, and the platform refuses a longer one.")
        String timeout;

        @CommandLine.Option(names = "--label", paramLabel = "<text>",
                description = "What the agent waits on, in a few words. The platform shows the agent as `waiting on "
                        + "<label>`. A preset gives one of its own.")
        String label;
    }

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = json(output);
        if (event == null || event.isBlank()) {
            throw new CliFailure("Name the event: --event <Name> with --when <conditions>, or a preset: "
                    + "`qits wait branch <branch> --until released`, `qits wait release-request <id>`, "
                    + "`qits wait work <qits-123> --status <STATUS>`, `qits wait ci-run <id>`.", CliFailure.USAGE);
        }
        String name = event.strip();
        if (name.length() > WhenGrammar.MAX_EVENT_LENGTH || !EVENT.matcher(name).matches()) {
            throw new CliFailure("--event '" + name + "' is not an event name, like BuildFailed: letters, digits, "
                    + "'_', '-' or '.', at most " + WhenGrammar.MAX_EVENT_LENGTH + ".", CliFailure.USAGE);
        }
        List<String> given = when == null ? List.of() : when;
        if (given.size() > WhenGrammar.MAX_GROUPS) {
            throw new CliFailure("There are " + given.size() + " --when groups; the most is " + WhenGrammar.MAX_GROUPS
                    + ".", CliFailure.USAGE);
        }
        List<List<WhenGrammar.Condition>> groups = new ArrayList<>();
        for (String group : given) {
            groups.add(WhenGrammar.parse(group));
        }
        return declare(context, json, name, groups, declaration, null);
    }

    /**
     * Who the waits belong to: --agent or --desk, else QITS_AGENT_ID, else QITS_DESK_PROJECT; a usage
     * failure with both --agent and --desk, or with none of the four. On the MCP server the variable
     * comes from the caller's token, not the container's environment (qits-1213, qits-1618).
     * <p>
     * {@code --desk} may name a project by id, slug or name, resolved against the projects service
     * like the other commands that take a {@code --project} (ProjectsCommand); the environment
     * variable is always a project uuid already, so it is used as given, with no lookup.
     */
    WaitsApi.Holder holder(CliContext context) throws CliFailure, InterruptedException {
        boolean hasAgent = agent != null && !agent.isBlank();
        boolean hasDesk = desk != null && !desk.isBlank();
        if (hasAgent && hasDesk) {
            throw new CliFailure("--agent and --desk are mutually exclusive: a wait belongs to one agent or one "
                    + "project's desk, never both.", CliFailure.USAGE);
        }
        if (hasAgent) {
            return WaitsApi.Holder.agent(agent.strip());
        }
        if (hasDesk) {
            return WaitsApi.Holder.desk(resolveDesk(context, desk.strip()));
        }
        String envAgent = context.env().get(AGENT_VARIABLE);
        if (envAgent != null && !envAgent.isBlank()) {
            return WaitsApi.Holder.agent(envAgent.strip());
        }
        String envDesk = context.env().get(DESK_VARIABLE);
        if (envDesk != null && !envDesk.isBlank()) {
            return WaitsApi.Holder.desk(envDesk.strip());
        }
        throw new CliFailure("No holder: give --agent <id> or --desk <project>, or set " + AGENT_VARIABLE + " or "
                + DESK_VARIABLE + ". A wait belongs to one agent or one project's desk; the harness sets "
                + AGENT_VARIABLE + " in an agent's environment and " + DESK_VARIABLE + " in a project's kept "
                + "front-desk session, and the MCP server sets either from the caller's token.",
                CliFailure.USAGE);
    }

    /** {@code --desk}'s value resolved to the project's id, the way {@code qits projects} commands do. */
    private String resolveDesk(CliContext context, String wanted) throws CliFailure, InterruptedException {
        return text(ProjectsApi.connect(context, null).project(wanted), "id");
    }

    WaitsApi api(CliContext context) throws CliFailure, InterruptedException {
        return new WaitsApi(new PlatformClient(context.credential()),
                PlatformUrls.workspaces(workspacesUrl, context.env(), context.idpUrl()));
    }

    /** {@code {label?, selection: {event, when}, timeout?}}: the body of W8's create. */
    static ObjectNode body(String event, List<List<WhenGrammar.Condition>> groups, String label, String timeout) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (label != null && !label.isBlank()) {
            body.put("label", label.strip());
        }
        ObjectNode selection = body.putObject("selection");
        selection.put("event", event);
        selection.set("when", WhenGrammar.json(groups));
        if (timeout != null) {
            body.put("timeout", timeout);
        }
        return body;
    }

    /**
     * Sends the wait and prints it. Everything is checked before anything goes out: the holder, the
     * timeout and the groups. {@code presetLabel} is used when --label was not given.
     */
    int declare(CliContext context, boolean json, String eventName, List<List<WhenGrammar.Condition>> groups,
                Declaration given, String presetLabel) throws CliFailure, InterruptedException {
        WaitsApi.Holder holder = holder(context);
        // A preset's own options win; the ones given before the preset's name count too.
        String timeout = WaitTimeout.iso(given.timeout != null ? given.timeout : declaration.timeout);
        String label = !blank(given.label) ? given.label : !blank(declaration.label) ? declaration.label : presetLabel;
        JsonNode wait = api(context).create(holder, body(eventName, groups, label, timeout));
        if (json) {
            printJson(context.out(), wait);
            return 0;
        }
        printWait(context.out(), wait);
        context.out().println();
        context.out().println("Declared. End your turn now: you get a new turn when it fires or times out. Do not poll.");
        return 0;
    }

    /** One condition list per group, each with the same leading condition: what a preset asks. */
    static List<List<WhenGrammar.Condition>> onePerValue(WhenGrammar.Condition common, String path, List<String> values) {
        List<List<WhenGrammar.Condition>> groups = new ArrayList<>();
        for (String value : values) {
            groups.add(List.of(common, new WhenGrammar.Condition(path, WhenGrammar.Op.EXACT, value)));
        }
        return groups;
    }

    /** A wait matches an id exactly, so the start of one would make a wait that never fires. */
    static String wholeId(String given, String what, String whereShown) throws CliFailure {
        String id = given == null ? "" : given.strip();
        if (!WHOLE_ID.matcher(id).matches()) {
            throw new CliFailure("'" + id + "' is not a whole " + what + " id. A wait matches the id exactly, so give "
                    + "all 36 characters; " + whereShown + " shows them.", CliFailure.USAGE);
        }
        return id.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    /** One wait, for a person. Every value goes through {@link SafeText}: an agent wrote the label. */
    static void printWait(PrintStream out, JsonNode wait) {
        out.println("Wait " + SafeText.line(text(wait, "id")) + ": " + SafeText.line(text(wait, "state")));
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("label", Table.cell(SafeText.line(text(wait, "label")), 200)));
        JsonNode selection = wait.path("selection");
        rows.add(List.of("event", Table.cell(SafeText.line(text(selection, "event")), 200)));
        boolean first = true;
        for (JsonNode group : selection.path("when")) {
            String written = WhenGrammar.written(group);
            rows.add(List.of(first ? "when" : "or", SafeText.line(written == null ? group.toString() : written)));
            first = false;
        }
        rows.add(List.of("created", Table.time(text(wait, "createdAt"))));
        if (!text(wait, "expiresAt").isEmpty()) {
            rows.add(List.of("expires", Table.time(text(wait, "expiresAt"))));
        }
        if (!text(wait, "matchedEventId").isEmpty()) {
            rows.add(List.of("matched", SafeText.line(text(wait, "matchedEventId"))));
        }
        if (!text(wait, "brokenReason").isEmpty()) {
            rows.add(List.of("broken", Table.cell(SafeText.line(text(wait, "brokenReason")), 200)));
        }
        if (!text(wait, "closedAt").isEmpty()) {
            rows.add(List.of("closed", Table.time(text(wait, "closedAt"))));
        }
        Table.print(out, "  ", null, rows);
    }

    // --- presets ---

    @CommandLine.Command(name = "release-request", mixinStandardHelpOptions = true,
            description = {"Wait until a release request ends: FINALIZED, REJECTED, FAILED, WITHDRAWN or OBSOLETE.",
                    "A request already in one of these states fires the wait at once; after a rejection, wait on "
                            + "--event ReleaseRequestStateChanged --when 'releaseRequestQualifiedId=<id> "
                            + "state=PENDING', or on your branch, to see it re-armed.",
                    "The same as --event ReleaseRequestStateChanged with one group per final state: "
                            + "releaseRequestQualifiedId=<id> state=<STATE>, or releaseRequestId=<uuid> "
                            + "state=<STATE> for a uuid.",
                    "Prefer `wait branch` for your own work: a request id goes stale when it refolds or is "
                            + "superseded."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait release-request qits-ci-service-rr-12",
                    "  qits wait release-request qits-ci-service-rr-12 --timeout 3h",
                    "  qits wait release-request 4f2a91c0-1d2e-4c3b-9a8f-0123456789ab",
                    "",
                    "- Give the whole id, as `qits release-request list` shows it, or the whole uuid. The wait "
                            + "matches it exactly, so the start of a uuid is refused, and the id must be in the "
                            + "case the list shows."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The wait is declared; it is printed.", HelpText.REFUSED,
                    "2:Used wrongly (no holder, not a whole id or uuid), not signed in, or the session ended."})
    public static class ReleaseRequestWait extends PlatformCommand {

        static final String EVENT = "ReleaseRequestStateChanged";
        static final List<String> FINAL_STATES = List.of("FINALIZED", "REJECTED", "FAILED", "WITHDRAWN", "OBSOLETE");

        @CommandLine.ParentCommand
        WaitCommand parent;

        @CommandLine.Parameters(paramLabel = "<id>",
                description = "The release request: its id (qits-ci-service-rr-12) or its whole uuid.")
        String id;

        @CommandLine.Mixin
        Declaration declaration;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String given = id == null ? "" : id.strip();
            // The event carries both ids; a logical id is matched as given, a uuid in lower case.
            WhenGrammar.Condition which;
            String label;
            if (ReleaseRequestRefs.isQualified(given)) {
                which = new WhenGrammar.Condition("releaseRequestQualifiedId", WhenGrammar.Op.EXACT, given);
                label = given;
            } else {
                if (!WHOLE_ID.matcher(given).matches()) {
                    throw new CliFailure("'" + given + "' is neither a release request's id (qits-ci-service-rr-12) "
                            + "nor a whole uuid. A wait matches it exactly, so the start of a uuid is not enough; "
                            + "`qits release-request list` shows the ids.", CliFailure.USAGE);
                }
                String requestId = given.toLowerCase(Locale.ROOT);
                which = new WhenGrammar.Condition("releaseRequestId", WhenGrammar.Op.EXACT, requestId);
                label = shortId(requestId);
            }
            List<List<WhenGrammar.Condition>> groups = onePerValue(which, "state", FINAL_STATES);
            return parent.declare(context, json, EVENT, groups, declaration, "release request " + label + " to end");
        }
    }

    @CommandLine.Command(name = "work", mixinStandardHelpOptions = true,
            description = {"Wait until a work item reaches a status.",
                    "The item is looked up in the projects service first, so its qualified id (qits-123) works. The "
                            + "same as --event EntityTransitioned --when 'entities[].entityId=<its id> "
                            + "entities[].status=<STATUS>'."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait work qits-123 --status VERIFIED",
                    "  qits wait work qits-123 --status DONE --timeout 1d --label 'qits-123 to be done'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The wait is declared; it is printed.",
                    "1:The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (no holder, no --status), not signed in, or the session ended."})
    public static class WorkWait extends PlatformCommand {

        static final String EVENT = "EntityTransitioned";

        @CommandLine.ParentCommand
        WaitCommand parent;

        @CommandLine.Parameters(paramLabel = "<entity>", description = "The work item: its qualified id (qits-123) or its id.")
        String entity;

        @CommandLine.Option(names = "--status", paramLabel = "<STATUS>", required = true,
                description = "The status to wait for: REFINED, READY_FOR_DEV, IMPLEMENTED, VERIFIED, DONE, DROPPED "
                        + "or another word of the item's lifecycle.")
        String status;

        @CommandLine.Option(names = "--projects-url", paramLabel = "<url>",
                description = "The projects service's base URL, without /projects, where the item is looked up. "
                        + "Default: QITS_PROJECTS_URL, else derived from the idp address.")
        String projectsUrl;

        @CommandLine.Mixin
        Declaration declaration;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String wanted = entity == null ? "" : entity.strip();
            if (wanted.isEmpty()) {
                throw new CliFailure("Name the work item: its qualified id, like qits-123.", CliFailure.USAGE);
            }
            String target = status.strip().toUpperCase(Locale.ROOT);
            if (target.isEmpty()) {
                throw new CliFailure("--status is empty. Name a status, like VERIFIED.", CliFailure.USAGE);
            }
            // Refuses without a holder before the item lookup goes out.
            parent.holder(context);
            WaitTimeout.iso(declaration.timeout != null ? declaration.timeout : parent.declaration.timeout);
            JsonNode item = ProjectsApi.connect(context, projectsUrl).work(wanted);
            String entityId = text(item, "id");
            if (entityId.isEmpty()) {
                throw new CliFailure("The projects service answered " + SafeText.line(wanted) + " without its id.",
                        CliFailure.FAILED);
            }
            String qualified = text(item, "qualifiedId").isEmpty() ? wanted : text(item, "qualifiedId");
            List<List<WhenGrammar.Condition>> groups = List.of(List.of(
                    new WhenGrammar.Condition("entities[].entityId", WhenGrammar.Op.EXACT, entityId),
                    new WhenGrammar.Condition("entities[].status", WhenGrammar.Op.EXACT, target)));
            return parent.declare(context, json, EVENT, groups, declaration,
                    SafeText.line(qualified) + " to reach " + target);
        }
    }

    @CommandLine.Command(name = "ci-run", mixinStandardHelpOptions = true,
            description = {"Wait until a CI run ends: SUCCESS, FAILED, CANCELLED, CONFIG_ERROR or TIMED_OUT.",
                    "The same as --event BuildStatusChanged with one group per final status: runId=<id> "
                            + "status=<STATUS>."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait ci-run 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a",
                    "",
                    "- Give the whole id: `qits ci runs ... -o json` shows it."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The wait is declared; it is printed.", HelpText.REFUSED,
                    "2:Used wrongly (no holder, not a whole id), not signed in, or the session ended."})
    public static class CiRunWait extends PlatformCommand {

        static final String EVENT = "BuildStatusChanged";
        static final List<String> FINAL_STATUSES = List.of("SUCCESS", "FAILED", "CANCELLED", "CONFIG_ERROR", "TIMED_OUT");

        @CommandLine.ParentCommand
        WaitCommand parent;

        @CommandLine.Parameters(paramLabel = "<run id>", description = "The run's whole id.")
        String id;

        @CommandLine.Mixin
        Declaration declaration;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String runId = wholeId(id, "run", "`qits ci runs --project <p> --repository <r> -o json`");
            List<List<WhenGrammar.Condition>> groups = onePerValue(
                    new WhenGrammar.Condition("runId", WhenGrammar.Op.EXACT, runId), "status", FINAL_STATUSES);
            return parent.declare(context, json, EVENT, groups, declaration, "CI run " + shortId(runId) + " to end");
        }
    }

    // --- list and cancel ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The holder's waits: id, state, event, label, when it was declared, when it times out "
                    + "and, for a BROKEN wait, why."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait list", "  qits wait list --state OPEN -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (no holder, a state qits does not know), not signed in, or the session ended."})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WaitCommand parent;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE>",
                description = "Only the waits in this state: OPEN, MATCHED, TIMED_OUT, BROKEN or CANCELLED. "
                        + "Default: all.")
        String state;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String wanted = state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
            if (wanted != null && !STATES.contains(wanted)) {
                throw new CliFailure("--state must be OPEN, MATCHED, TIMED_OUT, BROKEN or CANCELLED, not '"
                        + state.strip() + "'.", CliFailure.USAGE);
            }
            WaitsApi.Holder holder = parent.holder(context);
            JsonNode answer = parent.api(context).list(holder, wanted);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> waits = waits(answer);
            if (waits.isEmpty()) {
                context.out().println(wanted == null ? "No waits." : "No " + wanted + " waits.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode wait : waits) {
                rows.add(List.of(SafeText.line(text(wait, "id")), SafeText.line(text(wait, "state")),
                        Table.cell(SafeText.line(text(wait.path("selection"), "event")), 40),
                        Table.cell(SafeText.line(text(wait, "label")), 50),
                        Table.time(text(wait, "createdAt")), Table.time(text(wait, "expiresAt")),
                        Table.cell(SafeText.line(text(wait, "brokenReason")), 50)));
            }
            Table.print(context.out(), "", List.of("ID", "STATE", "EVENT", "LABEL", "CREATED", "EXPIRES", "REASON"),
                    rows);
            return 0;
        }

        /** A bare array, or the array under {@code waits} or {@code entries}: W8 does not fix the list's envelope. */
        static List<JsonNode> waits(JsonNode answer) {
            JsonNode array = answer.isArray() ? answer
                    : answer.path("waits").isArray() ? answer.path("waits") : answer.path("entries");
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode node : array) {
                result.add(node.has("wait") && node.get("wait").isObject() ? node.get("wait") : node);
            }
            return result;
        }
    }

    @CommandLine.Command(name = "cancel", mixinStandardHelpOptions = true,
            description = {"End one of the holder's open waits. An agent holder gets no turn for it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait cancel 7c1e2d3f-0000-4000-8000-000000000001"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a wait it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (no holder), not signed in, or the session ended."})
    public static class CancelCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WaitCommand parent;

        @CommandLine.Parameters(paramLabel = "<wait id>", description = "The wait's id, as `qits wait list` shows it.")
        String waitId;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String id = waitId == null ? "" : waitId.strip();
            if (id.isEmpty()) {
                throw new CliFailure("Name the wait: its id, as `qits wait list` shows it.", CliFailure.USAGE);
            }
            WaitsApi.Holder holder = parent.holder(context);
            JsonNode answer = parent.api(context).cancel(holder, id);
            boolean isWait = answer != null && answer.isObject();
            if (json) {
                if (isWait) {
                    printJson(context.out(), answer);
                }
                return 0;
            }
            if (isWait) {
                printWait(context.out(), answer);
            } else {
                context.out().println("Cancelled wait " + SafeText.line(id) + ".");
            }
            return 0;
        }
    }
}
