package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
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
 */
public final class WaitsContract {

    /** The client the rows call through, for the contract that holds them. */
    public static final Class<?> CLIENT = WaitsApi.class;

    static final String OPEN_WAIT = "an agent with an open wait";
    /** The release-request preset's declaration. */
    static final String RELEASE_REQUEST_WAIT = "an agent declaring a wait on a release request";
    /** The CI-run preset's declaration. */
    static final String CI_RUN_WAIT = "an agent declaring a wait on a CI run";

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

    @FunctionalInterface
    interface Call {
        void run(WaitsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new WaitsApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
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
        rows.add(row("qits wait list", OPEN_WAIT, "listAgentWaits", "list", LIST)
                .consumes("waits[].id", "waits[].state", "waits[].selection.event", "waits[].label",
                        "waits[].createdAt", "waits[].expiresAt"));
        rows.add(row("qits wait cancel", OPEN_WAIT, "cancelAgentWait", "cancel", CANCEL)
                .consumes(MASTERS.held(OPEN_WAIT, "cancelAgentWait", WAIT)));
        return List.copyOf(rows);
    }

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "WaitsApi#" + method, api(call));
    }

    private WaitsContract() {
    }
}
