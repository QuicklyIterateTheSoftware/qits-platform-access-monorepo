package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.Json;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.cell;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.entries;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.slot;

/**
 * What {@code qits agents} and {@code qits workspaces show} share (epic qits-1667): the agents
 * table, the draft, and how a chat, a worktree and a detection read for a person.
 */
final class AgentViews {

    static final Set<String> STATES = Set.of("ACTIVE", "REMOVED");

    private AgentViews() {
    }

    static String required(String given, String what) throws CliFailure {
        String value = given == null ? "" : given.strip();
        if (value.isEmpty()) {
            throw new CliFailure(what, CliFailure.USAGE);
        }
        return value;
    }

    // --- the list ---

    /** {@code list}: the filters resolved and sent, the answer as a table or as it came. */
    static int list(CliContext context, WorkspacesAccess access, String project, String work, Long workspace,
                    String state) throws CliFailure, InterruptedException {
        boolean json = access.json();
        String wanted = state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
        if (wanted != null && !STATES.contains(wanted)) {
            throw new CliFailure("--state must be ACTIVE or REMOVED, not '" + state.strip() + "'.", CliFailure.USAGE);
        }
        Long slot = workspace == null ? null : slot(workspace);
        String workId = work == null || work.isBlank() ? null : access.workId(context, work);
        String projectId = project == null || project.isBlank() ? null : access.projectId(context, project);
        JsonNode answer = access.api(context).agents(workId, slot, wanted, projectId);
        if (json) {
            Json.print(context.out(), answer);
            return 0;
        }
        printAgents(context.out(), entries(answer, "agents", null), "No agents.");
        return 0;
    }

    /** The agents table, ATTENTION column included; {@code none} when there are none. */
    static void printAgents(PrintStream out, List<JsonNode> list, String none) {
        if (list.isEmpty()) {
            out.println(none);
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode agent : list) {
            rows.add(List.of(cell(agent, "agentId", 40), cell(agent, "workspaceRowId", 8),
                    cell(agent, "entityId", 30), cell(agent, "state", 10), Table.cell(run(agent), 12),
                    cell(agent, "harness", 8), Table.cell(waitingOn(agent), 40), cell(agent, "entityTitle", 50),
                    Table.cell(attentionReason(agent), 80)));
        }
        Table.print(out, "", List.of("AGENT", "SLOT", "ITEM", "STATE", "RUN", "HARNESS", "WAITING ON", "TITLE",
                "ATTENTION"), rows);
    }

    /** One agent, field by field: {@code qits agents show}. */
    static void printAgent(PrintStream out, JsonNode agent) {
        out.println("Agent " + SafeText.line(text(agent, "agentId")));
        List<List<String>> rows = new ArrayList<>();
        String status = text(agent, "entityStatus");
        rows.add(List.of("item", cell(agent, "entityId", 40) + (status.isEmpty() ? "" : " (" + SafeText.line(status)
                + ")")));
        rows.add(List.of("title", cell(agent, "entityTitle", 120)));
        rows.add(List.of("state", cell(agent, "state", 20)));
        rows.add(List.of("run", Table.cell(run(agent), 20)));
        String since = text(agent, "phaseSince");
        rows.add(List.of("phase", text(agent, "phase").isEmpty() ? "-"
                : cell(agent, "phase", 20) + (since.isEmpty() ? "" : " since " + Table.time(since))));
        rows.add(List.of("waiting for", cell(agent, "phaseDetail", 160)));
        rows.add(List.of("harness", cell(agent, "harness", 20)));
        rows.add(List.of("slot", cell(agent, "workspaceRowId", 12)));
        rows.add(List.of("branch", cell(agent, "wrapperBranch", 120)));
        rows.add(List.of("session", cell(agent, "sessionId", 60)));
        rows.add(List.of("waiting on", Table.cell(waitingOn(agent), 160)));
        rows.add(List.of("attention", Table.cell(attentionReason(agent), 160)));
        if (!text(agent, "startRefusal").isEmpty()) {
            rows.add(List.of("start refused", cell(agent, "startRefusal", 160)));
        }
        rows.add(List.of("last activity", Table.time(text(agent, "lastActivityAt"))));
        rows.add(List.of("created", Table.time(text(agent, "createdAt"))));
        Table.print(out, "  ", null, rows);
    }

    /**
     * The RUN column (qits-2515): {@code runState} reads RUNNING as soon as the agent holds a slot,
     * lease, container and session or not, so until its {@code phase} is READY the phase stands in
     * for it (QUEUED, PROVISIONING, STARTING). No phase at all (an older service): the runState.
     */
    static String run(JsonNode agent) {
        String phase = text(agent, "phase");
        return phase.isEmpty() || "READY".equals(phase) ? text(agent, "runState") : phase;
    }

    /**
     * The WAITING ON column: {@code waits[]} joined with {@code "; "}, each in its kind's own words
     * ({@link #waitLabel}); with no {@code waits} array at all (an older service), today's
     * {@code waitingOn} sentence.
     */
    static String waitingOn(JsonNode agent) {
        if (!agent.has("waits")) {
            return text(agent, "waitingOn");
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode wait : agent.path("waits")) {
            labels.add(waitLabel(wait));
        }
        return String.join("; ", labels);
    }

    /**
     * One wait of {@code waits[]}: a release request or work item by its qualified id (its
     * {@code subjectRef} when unresolved) and state, a CI run by the first 8 characters of its id
     * and state, a comments wait as "comments on <item>", else (OTHER, or a kind this CLI does not
     * know yet) the wait's own label. A state the service has not resolved yet prints {@code "?"}.
     */
    static String waitLabel(JsonNode wait) {
        String kind = text(wait, "subjectKind");
        String ref = text(wait, "subjectRef");
        String qualifiedId = text(wait, "subjectQualifiedId");
        String name = qualifiedId.isEmpty() ? ref : qualifiedId;
        String state = text(wait, "subjectState");
        String known = state.isEmpty() ? "?" : state;
        return switch (kind) {
            case "RELEASE_REQUEST", "WORK_ITEM" -> name + " " + known;
            case "CI_RUN" -> "run " + (ref.length() > 8 ? ref.substring(0, 8) : ref) + " " + known;
            case "COMMENTS" -> "comments on " + name;
            default -> text(wait, "label");
        };
    }

    /** The ATTENTION column: {@code attention.reason}, empty (a dash) when the agent needs nobody. */
    static String attentionReason(JsonNode agent) {
        JsonNode attention = agent.path("attention");
        return attention.isObject() ? text(attention, "reason") : "";
    }

    // --- a prompt, a discard ---

    /** What became of a prompt: the delivery answer both prompt doors give, {@code delivered|launched|resumed}. */
    static void printDelivery(PrintStream out, JsonNode answer, String nobody) {
        String agentId = text(answer, "agentId");
        String outcome = agentId.isEmpty() ? nobody
                : answer.path("delivered").asBoolean() ? "Queued for agent " + SafeText.line(agentId)
                + ": said when its current turn ends."
                : answer.path("launched").asBoolean() ? "Agent " + SafeText.line(agentId)
                + " hears it as its first turn, once it starts."
                : answer.path("resumed").asBoolean() ? "Agent " + SafeText.line(agentId) + " resumes to hear it."
                : "Agent " + SafeText.line(agentId) + " was not told.";
        out.println(outcome);
        if (!text(answer, "detail").isEmpty()) {
            out.println(SafeText.line(text(answer, "detail")));
        }
    }

    static int discard(CliContext context, WorkspacesAccess access, String id, boolean force)
            throws CliFailure, InterruptedException {
        boolean json = access.json();
        JsonNode answer = access.api(context).discardAgent(id, force);
        if (json) {
            Json.print(context.out(), answer);
            return 0;
        }
        context.out().println("Agent " + SafeText.line(id) + ": " + cell(answer, "state", 20) + ".");
        return 0;
    }

    // --- the prompt draft ---

    static int draftShow(CliContext context, WorkspacesAccess access, String id) throws CliFailure, InterruptedException {
        boolean json = access.json();
        JsonNode answer;
        try {
            answer = access.api(context).draft(id);
        } catch (CliFailure failure) {
            if (failure.status() != 404) {
                throw failure;
            }
            if (json) {
                Json.print(context.out(), JsonNodeFactory.instance.objectNode().putNull("draft"));
            } else {
                context.out().println("Agent " + SafeText.line(id) + " has no saved draft.");
            }
            return 0;
        }
        if (json) {
            Json.print(context.out(), answer);
            return 0;
        }
        JsonNode saved = answer.path("draft");
        JsonNode content = Json.parse(text(saved, "content"));
        String written = content == null ? "" : text(content, "text");
        context.out().println("Draft of agent " + SafeText.line(id) + ", written "
                + Table.time(text(saved, "updatedAt")) + ":");
        for (String line : written.split("\n", -1)) {
            context.out().println("  " + SafeText.line(line));
        }
        return 0;
    }

    static int draftSave(CliContext context, WorkspacesAccess access, String id, String text)
            throws CliFailure, InterruptedException {
        boolean json = access.json();
        String written = text == null ? "" : text;
        JsonNode answer = access.api(context).saveDraft(id, draftBody(written));
        if (json) {
            Json.print(context.out(), answer);
            return 0;
        }
        context.out().println("Saved the draft of agent " + SafeText.line(id) + ", "
                + Table.time(text(answer.path("draft"), "updatedAt")) + ".");
        return 0;
    }

    /** The landing app's draft: its own JSON, {@code {text, picked}}, and the prompt it serializes to. */
    static ObjectNode draftBody(String text) {
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("text", text);
        content.putArray("picked");
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("content", content.toString());
        body.put("serializedPrompt", text.strip());
        return body;
    }

    static int draftDiscard(CliContext context, WorkspacesAccess access, String id)
            throws CliFailure, InterruptedException {
        boolean json = access.json();
        access.api(context).deleteDraft(id);
        if (!json) {
            context.out().println("Discarded the draft of agent " + SafeText.line(id) + ".");
        }
        return 0;
    }

    // --- the chat ---

    /** A harness log's TRANSCRIPT channel, {@code {lines: [{sequence, content}]}}, in order, for a person. */
    static void printChat(PrintStream out, JsonNode answer) {
        List<JsonNode> lines = new ArrayList<>(entries(answer, "lines", null));
        lines.sort(Comparator.comparingLong(line -> line.path("sequence").asLong()));
        List<String> contents = new ArrayList<>();
        lines.forEach(line -> contents.add(text(line, "content")));
        printTurns(out, contents);
    }

    /** The harness's JSON lines, each a turn for a person; nothing to say is said as such. */
    static void printTurns(PrintStream out, List<String> contents) {
        if (contents.isEmpty()) {
            out.println("The conversation is empty.");
            return;
        }
        for (String content : contents) {
            String said = turn(content);
            if (!said.isEmpty()) {
                out.println(said);
            }
        }
    }

    /**
     * One transcript line for a person: who said it and its text, the tools it used named. A line
     * that is not the harness's JSON is shown as it is; a line with nothing to say gives nothing.
     */
    static String turn(String content) {
        JsonNode line = Json.parse(content);
        if (line == null || !line.isObject()) {
            return SafeText.line(content);
        }
        String type = text(line, "type");
        if (!type.equals("user") && !type.equals("assistant")) {
            return type.equals("result") ? "result: " + SafeText.line(text(line, "result")) : "";
        }
        JsonNode said = line.path("message").path("content");
        List<String> parts = new ArrayList<>();
        if (said.isTextual()) {
            parts.add(said.asText());
        }
        for (JsonNode part : said.isArray() ? said : JsonNodeFactory.instance.arrayNode()) {
            switch (text(part, "type")) {
                case "text" -> parts.add(text(part, "text"));
                case "tool_use" -> parts.add("[tool " + text(part, "name") + "]");
                case "tool_result" -> parts.add("[tool result]");
                default -> {
                    // Thinking and the kinds a newer harness invents stay out of the summary.
                }
            }
        }
        String text = SafeText.line(String.join(" ", parts)).strip();
        return text.isEmpty() ? "" : type + ": " + text;
    }

    // --- the worktree ---

    /** The daemon's {@code listFiles}: each path, then each gitignored directory as a stub. */
    static void printFiles(PrintStream out, JsonNode answer) {
        for (JsonNode file : answer.path("paths")) {
            out.println(SafeText.line(file.asText()));
        }
        for (JsonNode lazy : answer.path("lazyDirs")) {
            out.println(SafeText.line(text(lazy, "path")) + "/  (" + lazy.path("childCount").asInt()
                    + " entries, not listed: --path " + SafeText.line(text(lazy, "path")) + ")");
        }
    }

    /** The daemon's {@code readFile}: the text, line by line, or why it is not shown. */
    static void printFile(PrintStream out, String file, JsonNode answer) {
        if (answer.path("binary").asBoolean(false)) {
            out.println(SafeText.line(file) + " is binary, or over the daemon's 2 MB cap: not shown.");
            return;
        }
        for (String line : text(answer, "content").split("\n", -1)) {
            out.println(SafeText.line(line));
        }
    }

    /** The daemon's {@code detect}: a table of the frameworks, then each source's tests. */
    static void printDetection(PrintStream out, JsonNode answer) {
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode framework : answer.path("frameworks")) {
            rows.add(List.of(cell(framework, "frameworkId", 30), cell(framework, "root", 60),
                    cell(framework, "label", 40), String.valueOf(framework.path("memberPaths").size())));
        }
        if (rows.isEmpty()) {
            out.println("No frameworks.");
        } else {
            Table.print(out, "", List.of("FRAMEWORK", "ROOT", "LABEL", "FILES"), rows);
        }
        for (JsonNode link : answer.path("links")) {
            List<String> tests = new ArrayList<>();
            for (JsonNode test : link.path("tests")) {
                tests.add(SafeText.line(text(test, "path")));
            }
            out.println(SafeText.line(text(link, "path")) + "  <-  " + String.join(", ", tests));
        }
    }
}
