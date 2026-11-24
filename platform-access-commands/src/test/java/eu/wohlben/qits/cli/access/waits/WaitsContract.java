package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits wait} asks qits-workspaces, and why</b> (ticket qits-1149): an agent's waits,
 * declared, listed and cancelled. The rows belong to qits-workspaces' contract ({@code
 * workspaces/WorkspacesContract}); they are written here because {@link WaitsApi} is this package's.
 * <p>
 * Each declaration sends the body {@link WaitCommand#body} builds, from the selection a preset
 * makes, so the row proves the command's own body is the recorded one. qits-workspaces records the
 * declaration of {@code qits wait work} only; the release-request and CI-run presets send other
 * selections, so their rows wait for a recording of their own.
 * <p>
 * A timer ({@code qits wait --after}, {@code --until}; qits-2309) sends no selection at all, only
 * the label and the timeout, so its row sends the body {@link WaitCommand#timerBody} builds. It is
 * pending until a pinned qits-workspaces-golden-masters records {@value #TIMER}.
 */
public final class WaitsContract {

    /** The client the rows call through, for the contract that holds them. */
    public static final Class<?> CLIENT = WaitsApi.class;

    static final String OPEN_WAIT = "an agent with an open wait";
    /** The release-request preset's declaration. */
    static final String RELEASE_REQUEST_WAIT = "an agent declaring a wait on a release request";
    /** The CI-run preset's declaration. */
    static final String CI_RUN_WAIT = "an agent declaring a wait on a CI run";
    /** The bare form's timer, {@code --after} or {@code --until} (qits-2309). */
    static final String TIMER = "an agent declaring a timer";
    /** A project's long-running agent's declaration, {@code --work} (epic qits-2520). */
    static final String PROJECT_AGENT_DECLARING = "a project agent declaring a wait for a work item";
    /** A project's long-running agent's list and cancel (epic qits-2520). */
    static final String PROJECT_AGENT_OPEN_WAIT = "a project agent with an open wait";

    private static final CliPact MASTERS = CliPact.of("qits-workspaces-service", "qits-workspaces");

    /**
     * One wait as {@code printWait} prints it, but for {@code selection.when}: its groups are arrays
     * of arrays, which qits-pact-consumer cannot express as a matcher yet, so the pact leaves them
     * unbound and the row does not read them. {@code brokenReason} (qits-1961) is left out for the
     * same reason, a different way: every state the golden masters record it in holds it {@code
     * null}, so consuming it would bind the field to exactly {@code null} rather than a string
     * (qits-pact-consumer only widens a leaf to "type or null" when it merges an array whose
     * elements disagree on nullness; a single object has no such merge). Nothing here enforces that
     * a printed field be consumed, so this is a choice, not a workaround: add it once a BROKEN wait
     * is recorded somewhere.
     */
    static final List<String> WAIT = List.of("id", "state", "label", "selection.event", "createdAt", "expiresAt",
            "matchedEventId", "closedAt");

    /**
     * One timer as {@code printWait} prints it. Its {@code selection} is null, and the command reads
     * only that it is not an object, to show {@code timer}; consuming it would bind it to exactly
     * null, which is what a timer is, but no row needs that, so the pact leaves it unbound.
     */
    static final List<String> TIMER_WAIT = WAIT.stream().filter(path -> !path.startsWith("selection")).toList();

    @FunctionalInterface
    interface Call {
        void run(WaitsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new WaitsApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    /**
     * The same call with the bearer the state names ({@code authorization}): the project agent's door
     * knows its holder only by the bearer, and the provider records its calls with that one.
     */
    private static CliRow.Call asTheBearer(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new WaitsApi(new PlatformClient(credential), url), recorded.params(), recorded);
        };
    }

    private static void wait(JsonNode wait) {
        assertThat(wait.path("id").asText()).isNotBlank();
        assertThat(wait.path("state").asText()).isNotBlank();
        assertThat(wait.path("selection").path("event").asText()).isNotBlank();
    }

    /** {@code qits wait work <item> --status VERIFIED --label … --timeout 2h}: the recorded declaration. */
    private static final Call DECLARE_WORK = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        JsonNode when = sent.path("selection").path("when").get(0);
        List<List<WhenGrammar.Condition>> groups = List.of(List.of(
                new WhenGrammar.Condition("entities[].entityId", WhenGrammar.Op.EXACT, when.get(0).path("value").asText()),
                new WhenGrammar.Condition("entities[].status", WhenGrammar.Op.EXACT, when.get(1).path("value").asText())));
        wait(api.create(WaitsApi.Holder.agent(params.get("agentId")), WaitCommand.body(WaitCommand.WorkWait.EVENT, groups,
                sent.path("label").asText(), sent.path("timeout").asText())));
    };

    /** {@code qits wait release-request <uuid>}: one group per final state, the preset's label. */
    private static final Call DECLARE_RELEASE_REQUEST = (api, params, recorded) -> {
        WhenGrammar.Condition which = new WhenGrammar.Condition("releaseRequestId", WhenGrammar.Op.EXACT,
                params.get("releaseRequestId"));
        wait(api.create(WaitsApi.Holder.agent(params.get("agentId")), WaitCommand.body(WaitCommand.ReleaseRequestWait.EVENT,
                WaitCommand.onePerValue(which, "state", WaitCommand.ReleaseRequestWait.FINAL_STATES),
                "release request " + WaitCommand.shortId(params.get("releaseRequestId")) + " to end", null)));
    };

    /** {@code qits wait ci-run <uuid>}: one group per final status, the preset's label. */
    private static final Call DECLARE_CI_RUN = (api, params, recorded) -> {
        WhenGrammar.Condition which = new WhenGrammar.Condition("runId", WhenGrammar.Op.EXACT, params.get("runId"));
        wait(api.create(WaitsApi.Holder.agent(params.get("agentId")), WaitCommand.body(WaitCommand.CiRunWait.EVENT,
                WaitCommand.onePerValue(which, "status", WaitCommand.CiRunWait.FINAL_STATUSES),
                "CI run " + WaitCommand.shortId(params.get("runId")) + " to end", null)));
    };

    /** {@code qits wait --after 30m --label …}: the recorded timer, the timeout read as --after reads it. */
    private static final Call DECLARE_TIMER = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        JsonNode timer = api.create(WaitsApi.Holder.agent(params.get("agentId")), WaitCommand.timerBody(
                sent.path("label").asText(), WaitTimeout.iso(sent.path("timeout").asText(), "--after")));
        assertThat(timer.path("id").asText()).isNotBlank();
        assertThat(timer.path("state").asText()).isNotBlank();
        assertThat(WaitCommand.event(timer)).isEqualTo(WaitCommand.TIMER);
    };

    /**
     * {@code qits wait --event ReleaseRequestStateChanged --when 'releaseRequestId=<id> state=FINALIZED'
     * --label … --timeout 2h --work qits-123}, as a project's long-running agent: the bare form's body,
     * with the item the wait is held for added by the client (epic qits-2520).
     */
    private static final Call DECLARE_FOR_WORK = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        List<List<WhenGrammar.Condition>> groups = new ArrayList<>();
        for (JsonNode group : sent.path("selection").path("when")) {
            groups.add(WhenGrammar.parse(WhenGrammar.written(group)));
        }
        JsonNode wait = api.create(WaitsApi.Holder.projectAgent(sent.path("work").asText()), WaitCommand.body(
                sent.path("selection").path("event").asText(), groups, sent.path("label").asText(),
                WaitTimeout.iso(sent.path("timeout").asText())));
        wait(wait);
        assertThat(wait.path("work").asText()).isEqualTo(params.get("work"));
    };

    /** {@code qits wait list --work qits-123 --state OPEN}: the door's default, so no state is sent. */
    private static final Call LIST_FOR_WORK = (api, params, recorded) -> {
        List<JsonNode> waits = WaitCommand.ListCommand.waits(api.list(WaitsApi.Holder.projectAgent(params.get("work")),
                WaitCommand.ListCommand.projectAgentState("OPEN")));
        assertThat(waits).isNotEmpty().allSatisfy(WaitsContract::wait);
        assertThat(waits).allSatisfy(w -> assertThat(w.path("work").asText()).isEqualTo(params.get("work")));
    };

    /** {@code qits wait cancel <id>} as a project's long-running agent: 204, nothing to read. */
    private static final Call CANCEL_FOR_WORK = (api, params, recorded) ->
            api.cancel(WaitsApi.Holder.projectAgent(null), params.get("waitId"));

    /** {@code qits wait list --state open}: id, state, event, label, created and expires of each. */
    private static final Call LIST = (api, params, recorded) -> {
        List<JsonNode> waits = WaitCommand.ListCommand.waits(api.list(WaitsApi.Holder.agent(params.get("agentId")),
                recorded.example(recorded.query().get("state").getFirst())));
        assertThat(waits).isNotEmpty().allSatisfy(WaitsContract::wait);
    };

    private static final Call CANCEL = (api, params, recorded) ->
            wait(api.cancel(WaitsApi.Holder.agent(params.get("agentId")), params.get("waitId")));

    /** The rows, for qits-workspaces' contract. */
    public static final List<CliRow> ROWS = rows();

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        rows.add(row("qits wait work", OPEN_WAIT, "createAgentWait", "create", DECLARE_WORK)
                .consumes(MASTERS.held(OPEN_WAIT, "createAgentWait", WAIT)));
        rows.add(row("qits wait release-request", RELEASE_REQUEST_WAIT, "createAgentWait", "create",
                DECLARE_RELEASE_REQUEST).consumes(WAIT).request("POST /workspaces/api/agents/{agentId}/waits"));
        rows.add(row("qits wait ci-run", CI_RUN_WAIT, "createAgentWait", "create", DECLARE_CI_RUN)
                .consumes(WAIT).request("POST /workspaces/api/agents/{agentId}/waits"));
        rows.add(row("qits wait", TIMER, "createAgentWait", "create", DECLARE_TIMER)
                .consumes(MASTERS.held(TIMER, "createAgentWait", TIMER_WAIT))
                .request("POST /workspaces/api/agents/{agentId}/waits"));
        rows.add(row("qits wait list", OPEN_WAIT, "listAgentWaits", "list", LIST)
                .consumes("waits[].id", "waits[].state", "waits[].selection.event", "waits[].label",
                        "waits[].createdAt", "waits[].expiresAt"));
        rows.add(row("qits wait cancel", OPEN_WAIT, "cancelAgentWait", "cancel", CANCEL)
                .consumes(MASTERS.held(OPEN_WAIT, "cancelAgentWait", WAIT)));
        // epic qits-2520: a project's long-running agent, the bearer itself, on its own door.
        rows.add(rowAsTheBearer("qits wait", PROJECT_AGENT_DECLARING, "createProjectAgentWait", "create", DECLARE_FOR_WORK)
                .consumes(MASTERS.held(PROJECT_AGENT_DECLARING, "createProjectAgentWait",
                        java.util.stream.Stream.concat(WAIT.stream(), java.util.stream.Stream.of("work")).toList()))
                .header("Authorization", "{authorization}")
                .request("POST /workspaces/api/project-agent/waits"));
        rows.add(rowAsTheBearer("qits wait list", PROJECT_AGENT_OPEN_WAIT, "listProjectAgentWaits", "list", LIST_FOR_WORK)
                .consumes("waits[].id", "waits[].state", "waits[].selection.event", "waits[].label",
                        "waits[].createdAt", "waits[].expiresAt", "waits[].work")
                .header("Authorization", "{authorization}")
                .request("GET /workspaces/api/project-agent/waits?work={work}"));
        rows.add(rowAsTheBearer("qits wait cancel", PROJECT_AGENT_OPEN_WAIT, "cancelProjectAgentWait", "cancel", CANCEL_FOR_WORK)
                .header("Authorization", "{authorization}")
                .request("DELETE /workspaces/api/project-agent/waits/{waitId}"));
        return List.copyOf(rows);
    }

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "WaitsApi#" + method, api(call));
    }

    private static CliRow rowAsTheBearer(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "WaitsApi#" + method, asTheBearer(call));
    }

    private WaitsContract() {
    }
}
