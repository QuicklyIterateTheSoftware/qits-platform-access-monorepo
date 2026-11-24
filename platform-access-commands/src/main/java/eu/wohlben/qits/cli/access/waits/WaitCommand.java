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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * <p>
 * A timer ({@code --after}, {@code --until}; qits-2309) is a wait with a timeout and no selection: it
 * only ever times out. It is what to declare instead of a selection made never to match.
 */
@CommandLine.Command(name = "wait", mixinStandardHelpOptions = true,
        subcommands = {BranchWait.class, WaitCommand.ReleaseRequestWait.class, WaitCommand.WorkWait.class,
                WaitCommand.CiRunWait.class, WaitCommand.ListCommand.class, WaitCommand.CancelCommand.class},
        description = {"Declare what this holder waits on: a domain event that fits a selection. The platform "
                        + "gives the holder a turn when one arrives, or when the timeout passes. The command "
                        + "returns at once; it never blocks.",
                "Bare, it takes the event's name and its conditions. branch, release-request, work and ci-run are "
                        + "presets for the usual waits; list shows the holder's waits and cancel ends one.",
                "A timer is the bare form with --after <duration> or --until <instant> instead of an event: the "
                        + "holder gets a turn when that time comes, and no event wakes it. To look at something "
                        + "again later, declare a timer, never a selection made so that it cannot match. A timer "
                        + "takes --label, and neither --event, --when nor --timeout.",
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
                        + "a project's desk, for its own project's only.",
                "With none of those, the holder is the project's long-running agent the caller's token belongs "
                        + "to (epic qits-2520), and every wait it declares is held for one work item: --work <item>, "
                        + "else the item `wait work` names, else the <id> of a `wait branch` on ticket/<id> or "
                        + "epic/<id>. Its list and cancel go to its own door, list narrowed to --work when given."},
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
                "  qits wait --after 2h --label 'sweep cancelled runs'",
                "  qits wait --until 2026-11-18T03:05:00Z --label '03:00 GC run'",
                "  qits wait list --state OPEN",
                "  qits wait cancel 7c1e2d3f-0000-4000-8000-000000000001",
                "  qits wait release-request qits-ci-service-rr-12 --desk qits-ci",
                "  qits wait ci-run 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a --work qits-123",
                "  qits wait list --work qits-123"},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:The wait is declared; it is printed. A wait already MATCHED or otherwise closed when "
                        + "declared is 0 too: there is nothing left to wait for.",
                "1:The platform refused (the message names the status), cannot be reached, or the wait came back "
                        + "BROKEN already (the platform decided it can never fire; the message says why).",
                "2:Used wrongly (no holder and no --work, both --agent and --desk, no --event and no timer, a --when, "
                        + "--timeout, --after or --until that cannot be read or is over a limit, a timer given with "
                        + "a selection or --timeout, an --until in the past), not signed in, or the session ended."})
public class WaitCommand extends PlatformCommand {

    static final String AGENT_VARIABLE = "QITS_AGENT_ID";
    static final String DESK_VARIABLE = "QITS_DESK_PROJECT";
    static final Set<String> STATES = Set.of("OPEN", "MATCHED", "TIMED_OUT", "BROKEN", "CANCELLED");
    /** The most any wait may run, a timer included; the platform refuses a longer one. */
    static final Duration MOST = Duration.ofHours(24);
    /** What the event column shows for a wait with no selection. */
    static final String TIMER = "timer";
    private static final Pattern EVENT = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern WHOLE_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Required for the bare form, but not at picocli's level, which would ask it of every subcommand too. */
    @CommandLine.Option(names = "--event", paramLabel = "<Name>",
            description = "Required, unless a preset is named or the wait is a timer (--after, --until). The "
                    + "event's name, as the bus carries it: BuildFailed, EntityTransitioned.")
    String event;

    @CommandLine.Option(names = "--when", paramLabel = "<conditions>",
            description = "One group of conditions, for example 'runId=<id> status=SUCCESS'. Give it again for "
                    + "another group, up to 8: that is OR, not AND, so an event fitting either fires the wait. "
                    + "Conditions that must all hold go in the one quoted --when. Without one, every event of the "
                    + "name fits.")
    List<String> when;

    @CommandLine.Option(names = "--after", paramLabel = "<duration>",
            description = "A timer: wake the holder after this long, with no event. 30m, 2h, 1h30m, 1d or ISO-8601 "
                    + "(PT2H), as --timeout; the most is 24h, and the platform refuses a longer one. Not with --event, "
                    + "--when, --timeout or --until.")
    String after;

    @CommandLine.Option(names = "--until", paramLabel = "<instant>",
            description = "A timer: wake the holder at this time, with no event. ISO-8601 with a zone: "
                    + "2026-11-18T03:05:00Z or 2026-11-18T04:05:00+01:00. It must be in the future and at most 24h "
                    + "away. Not with --event, --when, --timeout or --after.")
    String until;

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

    @CommandLine.Option(names = "--work", paramLabel = "<item>", scope = CommandLine.ScopeType.INHERIT,
            description = "The work item this wait is for: a project's long-running agent names one on every wait "
                    + "(qits-2520). Its qualified id (qits-123) or its id. Default: the item of `wait work`, or the "
                    + "<id> of `wait branch ticket/<id>` or `epic/<id>`. With list, only that item's waits.")
    String work;

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
        if (!blank(after) || !blank(until)) {
            return timer(context, json);
        }
        if (event == null || event.isBlank()) {
            throw new CliFailure("Name the event: --event <Name> with --when <conditions>; or a timer, --after "
                    + "<duration> or --until <instant>; or a preset: "
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
     * {@code --after} or {@code --until}: a wait with a timeout and no selection (qits-2309). It takes
     * nothing that would make it wait on an event, nor a second timeout.
     */
    private int timer(CliContext context, boolean json) throws CliFailure, InterruptedException {
        if (!blank(after) && !blank(until)) {
            throw new CliFailure("--after and --until are mutually exclusive: a timer ends after a while or at a "
                    + "time, not both.", CliFailure.USAGE);
        }
        String option = blank(after) ? "--until" : "--after";
        String clash = !blank(event) ? "--event" : when != null && !when.isEmpty() ? "--when"
                : !blank(declaration.timeout) ? "--timeout" : null;
        if (clash != null) {
            throw new CliFailure(option + " declares a timer, which takes no " + clash + ": no event wakes it, and "
                    + "its time is its timeout. Wait on an event or declare a timer, not both.", CliFailure.USAGE);
        }
        String timeout = blank(after) ? untilTimeout(until, context.clock()) : WaitTimeout.iso(after, "--after");
        WaitsApi.Holder holder = holder(context, null);
        JsonNode wait = api(context).create(holder, timerBody(declaration.label, timeout));
        return print(context, json, wait, "when the time comes");
    }

    /**
     * {@code --until} as the timeout from now, ISO-8601, rounded up to whole seconds so the timer
     * never fires before the instant. One in the past, or more than {@link #MOST} away, is refused
     * here: the instant is the caller's, and a refusal from the platform would name only a duration.
     */
    static String untilTimeout(String given, Clock clock) throws CliFailure {
        String text = given.strip();
        Instant at;
        try {
            at = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException notOne) {
            throw new CliFailure("--until '" + SafeText.line(text) + "' is not an instant. Write ISO-8601 with a zone: "
                    + "2026-11-18T03:05:00Z or 2026-11-18T04:05:00+01:00.", CliFailure.USAGE);
        }
        Instant now = clock.instant();
        Duration left = Duration.between(now, at);
        if (left.isNegative() || left.isZero()) {
            throw new CliFailure("--until " + SafeText.line(text) + " is not in the future: it is " + now
                    + " now.", CliFailure.USAGE);
        }
        if (left.getNano() > 0) {
            left = left.withNanos(0).plusSeconds(1);
        }
        if (left.compareTo(MOST) > 0) {
            throw new CliFailure("--until " + SafeText.line(text) + " is more than 24h away (it is " + now
                    + " now): the most any wait may run is 24h.", CliFailure.USAGE);
        }
        return left.toString();
    }

    /** The message for a declaration with no holder at all. */
    static final String NO_HOLDER = "No holder: give --agent <id> or --desk <project>, or --work <item> as a "
            + "project's long-running agent. The harness sets " + AGENT_VARIABLE + " in an agent's environment and "
            + DESK_VARIABLE + " in a project's kept front-desk session, and the MCP server sets either from the "
            + "caller's token.";

    /**
     * Who a declaration belongs to: the named holder ({@link #namedHolder}), else the project's
     * long-running agent with the item the wait is held for: --work, else {@code presetWork} (the
     * item a preset names). A usage failure with neither (epic qits-2520).
     */
    WaitsApi.Holder holder(CliContext context, String presetWork) throws CliFailure, InterruptedException {
        WaitsApi.Holder named = namedHolder(context);
        if (named != null) {
            return named;
        }
        String item = !blank(work) ? work.strip() : !blank(presetWork) ? presetWork.strip() : null;
        if (item == null) {
            throw new CliFailure(NO_HOLDER, CliFailure.USAGE);
        }
        return WaitsApi.Holder.projectAgent(item);
    }

    /**
     * Whose waits list and cancel act on: the named holder, else the project's long-running agent's
     * own door, narrowed to --work when it is given. That door answers 403 to anyone else, and the
     * 403 is printed as it comes.
     */
    WaitsApi.Holder listHolder(CliContext context) throws CliFailure, InterruptedException {
        WaitsApi.Holder named = namedHolder(context);
        return named != null ? named : WaitsApi.Holder.projectAgent(blank(work) ? null : work.strip());
    }

    /**
     * The named holder: --agent or --desk, else QITS_AGENT_ID, else QITS_DESK_PROJECT; null with none
     * of the four, and a usage failure with both --agent and --desk. On the MCP server the variable
     * comes from the caller's token, not the container's environment (qits-1213, qits-1618).
     * <p>
     * {@code --desk} may name a project by id, slug or name, resolved against the projects service
     * like the other commands that take a {@code --project} (ProjectsCommand); the environment
     * variable is always a project uuid already, so it is used as given, with no lookup.
     */
    WaitsApi.Holder namedHolder(CliContext context) throws CliFailure, InterruptedException {
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
        return null;
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

    /** {@code {label?, timeout}}: a timer's create, with no {@code selection} member at all (qits-2309). */
    static ObjectNode timerBody(String label, String timeout) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (label != null && !label.isBlank()) {
            body.put("label", label.strip());
        }
        body.put("timeout", timeout);
        return body;
    }

    /**
     * Sends the wait and prints it. Everything is checked before anything goes out: the holder, the
     * timeout and the groups. {@code presetLabel} is used when --label was not given.
     */
    int declare(CliContext context, boolean json, String eventName, List<List<WhenGrammar.Condition>> groups,
                Declaration given, String presetLabel) throws CliFailure, InterruptedException {
        return declare(context, json, eventName, groups, given, presetLabel, null);
    }

    /**
     * {@code declare}, with a hint appended to a BROKEN reason that says the subject does not exist
     * (qits-2470): only {@code wait ci-run} has one, for a run id that was never a CI run id at all.
     */
    int declare(CliContext context, boolean json, String eventName, List<List<WhenGrammar.Condition>> groups,
                Declaration given, String presetLabel, String brokenHint) throws CliFailure, InterruptedException {
        return declare(context, json, eventName, groups, given, presetLabel, brokenHint, null);
    }

    /**
     * {@code declare}, with the work item a preset names ({@code wait work}'s item, {@code wait
     * branch}'s ticket or epic): the one a project agent's wait is held for when --work is not given.
     */
    int declare(CliContext context, boolean json, String eventName, List<List<WhenGrammar.Condition>> groups,
                Declaration given, String presetLabel, String brokenHint, String presetWork)
            throws CliFailure, InterruptedException {
        if (!blank(after) || !blank(until)) {
            // Given before a preset's name, they would be dropped without a word: the preset runs, not the bare form.
            throw new CliFailure("--after and --until declare a timer with the bare `qits wait`; a preset takes "
                    + "neither. Give the preset --timeout, or declare the timer on its own.", CliFailure.USAGE);
        }
        WaitsApi.Holder holder = holder(context, presetWork);
        // A preset's own options win; the ones given before the preset's name count too.
        String timeout = WaitTimeout.iso(given.timeout != null ? given.timeout : declaration.timeout);
        String label = !blank(given.label) ? given.label : !blank(declaration.label) ? declaration.label : presetLabel;
        JsonNode wait = api(context).create(holder, body(eventName, groups, label, timeout));
        return print(context, json, wait, "when it fires or times out", brokenHint);
    }

    private static int print(CliContext context, boolean json, JsonNode wait, String when) throws CliFailure {
        return print(context, json, wait, when, null);
    }

    /**
     * Prints the wait exactly as declared, then says what its state means. The platform looks the
     * subject up synchronously, so a wait can come back already decided (qits-2470): OPEN is the
     * only state that is actually waited on, so only it gets "end your turn now". BROKEN means
     * nothing will ever fire it, so this fails the command instead of telling the caller to wait on
     * nothing; {@code brokenHint}, when given, is appended to a reason that says the subject does
     * not exist. Any other state (MATCHED most often: the selection was already true when declared)
     * closes at once with no new turn promised for it, because there is nothing left to wait for.
     */
    private static int print(CliContext context, boolean json, JsonNode wait, String when, String brokenHint)
            throws CliFailure {
        if (json) {
            printJson(context.out(), wait);
        } else {
            printWait(context.out(), wait);
        }
        String state = text(wait, "state");
        if (state.equals("BROKEN")) {
            String rawReason = text(wait, "brokenReason");
            String hint = brokenHint != null && rawReason.contains("does not exist") ? " " + brokenHint : "";
            throw new CliFailure("The wait was declared but is already BROKEN: " + SafeText.line(rawReason)
                    + "; nothing will fire it, so do not end the turn waiting for it." + hint, CliFailure.FAILED);
        }
        if (json) {
            return 0;
        }
        context.out().println();
        if (state.isEmpty() || state.equals("OPEN")) {
            context.out().println("Declared. End your turn now: you get a new turn " + when + ". Do not poll.");
        } else if (state.equals("MATCHED")) {
            context.out().println("Matched already when declared: carry on, there is nothing to wait for.");
        } else {
            context.out().println("Already " + state + ": carry on, there is nothing to wait for.");
        }
        return 0;
    }

    /** The event a wait matches, or {@link #TIMER} for one with no selection: a timer (qits-2309). */
    static String event(JsonNode wait) {
        JsonNode selection = wait.path("selection");
        return selection.isObject() ? text(selection, "event") : TIMER;
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
        if (!text(wait, "work").isEmpty()) {
            rows.add(List.of("work", Table.cell(SafeText.line(text(wait, "work")), 200)));
        }
        rows.add(List.of("event", Table.cell(SafeText.line(event(wait)), 200)));
        // A timer's selection is null or absent: no groups, so no when rows.
        boolean first = true;
        for (JsonNode group : wait.path("selection").path("when")) {
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
            exitCodeList = {"0:The wait is declared; it is printed. A request already in a final state fires it "
                            + "at once (MATCHED), which is 0 too.",
                    "1:The platform refused (the message names the status), cannot be reached, or the wait came "
                            + "back BROKEN already (the message says why).",
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
            exitCodeList = {"0:The wait is declared; it is printed. An item already at that status fires it at "
                            + "once (MATCHED), which is 0 too.",
                    "1:The platform refused (for example an item it does not know, HTTP 404), cannot be reached, "
                            + "or the wait came back BROKEN already (the message says why).",
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
            parent.holder(context, wanted);
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
                    SafeText.line(qualified) + " to reach " + target, null, qualified);
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
            exitCodeList = {"0:The wait is declared; it is printed. A run already ended fires it at once "
                            + "(MATCHED), which is 0 too.",
                    "1:The platform refused (the message names the status), cannot be reached, or the wait came "
                            + "back BROKEN already (the message says why; a run id that does not exist is "
                            + "commonly an automation job id, not a CI run id).",
                    "2:Used wrongly (no holder, not a whole id), not signed in, or the session ended."})
    public static class CiRunWait extends PlatformCommand {

        static final String EVENT = "BuildStatusChanged";
        static final List<String> FINAL_STATUSES = List.of("SUCCESS", "FAILED", "CANCELLED", "CONFIG_ERROR", "TIMED_OUT");

        /**
         * Appended to a BROKEN reason that says the run does not exist: the usual cause is an
         * automation job id (`qits release-request regenerate`, `qits maintenance automation run`)
         * mistaken for one, before qits-ci has even accepted the bump's run (qits-2470).
         */
        static final String NOT_A_RUN_HINT = "An id printed by `qits release-request regenerate` or `qits "
                + "maintenance automation run` is an automation job id, not a CI run; `qits maintenance bump "
                + "<id>` shows its CI run (ciRunId) once qits-ci has accepted it.";

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
            return parent.declare(context, json, EVENT, groups, declaration, "CI run " + shortId(runId) + " to end",
                    NOT_A_RUN_HINT);
        }
    }

    // --- list and cancel ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The holder's waits: id, state, event (timer, for a timer), label, when it was declared, "
                    + "when it times out and, for a BROKEN wait, why. WORK appears when some wait is held for a work "
                    + "item: a project's long-running agent's.",
                    "With no --agent, --desk, QITS_AGENT_ID or QITS_DESK_PROJECT it lists the project's "
                            + "long-running agent's waits, on every item or only --work's."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait list", "  qits wait list --state OPEN -o json", "  qits wait list --work qits-123"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (a state qits does not know), not signed in, or the session ended."})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WaitCommand parent;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE>",
                description = "Only the waits in this state: OPEN, MATCHED, TIMED_OUT, BROKEN or CANCELLED. "
                        + "Default: all.")
        String state;

        /**
         * What a project agent's door is asked for {@code wanted}: it knows only {@code open}, its
         * default, and {@code all}. OPEN sends nothing; any other state asks for all and is picked
         * out here ({@link #only}).
         */
        static String projectAgentState(String wanted) {
            return "OPEN".equals(wanted) ? null : "all";
        }

        /** The answer with only the waits in {@code wanted}, in the envelope the project agent's door uses. */
        static JsonNode only(JsonNode answer, String wanted) {
            ObjectNode narrowed = JsonNodeFactory.instance.objectNode();
            var kept = narrowed.putArray("waits");
            waits(answer).stream().filter(w -> wanted.equals(text(w, "state"))).forEach(kept::add);
            return narrowed;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.output);
            String wanted = state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
            if (wanted != null && !STATES.contains(wanted)) {
                throw new CliFailure("--state must be OPEN, MATCHED, TIMED_OUT, BROKEN or CANCELLED, not '"
                        + state.strip() + "'.", CliFailure.USAGE);
            }
            WaitsApi.Holder holder = parent.listHolder(context);
            JsonNode answer;
            if (holder instanceof WaitsApi.Holder.ProjectAgent) {
                answer = parent.api(context).list(holder, projectAgentState(wanted));
                if (wanted != null && !wanted.equals("OPEN")) {
                    answer = only(answer, wanted);
                }
            } else {
                answer = parent.api(context).list(holder, wanted);
            }
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> waits = waits(answer);
            if (waits.isEmpty()) {
                context.out().println(wanted == null ? "No waits." : "No " + wanted + " waits.");
                return 0;
            }
            // WORK only where some wait is held for an item: a project agent's (epic qits-2520).
            boolean anyWork = waits.stream().anyMatch(w -> !text(w, "work").isEmpty());
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode wait : waits) {
                List<String> row = new ArrayList<>(List.of(SafeText.line(text(wait, "id")),
                        SafeText.line(text(wait, "state"))));
                if (anyWork) {
                    row.add(Table.cell(SafeText.line(text(wait, "work")), 40));
                }
                row.addAll(List.of(Table.cell(SafeText.line(event(wait)), 40),
                        Table.cell(SafeText.line(text(wait, "label")), 50),
                        Table.time(text(wait, "createdAt")), Table.time(text(wait, "expiresAt")),
                        Table.cell(SafeText.line(text(wait, "brokenReason")), 50)));
                rows.add(row);
            }
            List<String> headers = new ArrayList<>(List.of("ID", "STATE"));
            if (anyWork) {
                headers.add("WORK");
            }
            headers.addAll(List.of("EVENT", "LABEL", "CREATED", "EXPIRES", "REASON"));
            Table.print(context.out(), "", headers, rows);
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
            description = {"End one of the holder's open waits. An agent holder gets no turn for it. With no "
                    + "--agent, --desk, QITS_AGENT_ID or QITS_DESK_PROJECT it ends one of the project's long-running "
                    + "agent's waits."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits wait cancel 7c1e2d3f-0000-4000-8000-000000000001"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a wait it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (no wait id), not signed in, or the session ended."})
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
            WaitsApi.Holder holder = parent.listHolder(context);
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
