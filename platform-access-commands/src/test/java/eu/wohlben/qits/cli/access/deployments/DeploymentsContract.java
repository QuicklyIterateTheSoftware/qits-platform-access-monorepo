package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits deployments settings} and {@code qits deployments database} ask
 * qits-deployments, and why</b> (epic qits-765, qits-1390; qits-2175): settings list, set, delete and
 * preview, and database move, moves and move-status, through {@link DeploymentsApi}, written to
 * {@code pacts/qits-platform-access-monorepo_qits-deployments-service.json} once the provider
 * records them. {@code abort} (qits-2243) is excluded for now: no golden-masters pin records its
 * ABORTED state yet, so it has no row here until one does.
 * <p>
 * Every row is pending until a pinned {@code qits-deployments-golden-masters} records its state.
 * The set row sends the recorded body ({@code {"value", "secret"}}) through the client, so the pact
 * holds the command's own body shape; the move row does the same with {@code {"application",
 * "resource", "server"}}, the members the command sends when they are given.
 */
public final class DeploymentsContract {

    /** An application with a plain and a secret setting in one environment. */
    static final String WITH_SETTINGS = "an application with settings in an environment";
    /** An application deployed in an environment whose settings changed since that deployment. */
    static final String SETTINGS_CHANGED = "an application whose settings changed since its deployment";

    /** An application with one database on the public server, which a move asks onto the private one. */
    static final String HOLDING_A_DATABASE = "an application holding a database";
    /** A deployment's move that finished: both sides counted, the source kept until its drop-after. */
    static final String MOVE_FINISHED = "a database move that finished";

    private static final CliPact BASE = CliPact.of("qits-deployments-service", "qits-deployments");

    /** What {@code move}'s line reads of the queued move. */
    static final List<String> MOVE_LINE = List.of("id", "applicationName", "resourceName", "sourceServer",
            "sourceDatabase", "targetServer", "targetDatabase", "state");

    /** What the moves table reads of each move. */
    static final List<String> MOVE = java.util.stream.Stream.concat(MOVE_LINE.stream(),
            java.util.stream.Stream.of("startedAt", "finishedAt", "dropAfter")).toList();

    /** What move-status reads besides {@link #MOVE}: the counts, the kept copy and who asked. */
    static final List<String> MOVE_IN_FULL = List.of("environmentName", "specServer", "specDatabase",
            "keptDatabase", "origin", "requestedBy", "detail", "createdAt", "droppedAt", "sourceRows", "targetRows",
            "sourceCounts", "targetCounts");

    /** The settings table, and the mask that needs `secret`. */
    static final List<String> SETTING = List.of("application", "environment", "key", "value", "secret", "updatedBy");

    @FunctionalInterface
    interface Call {
        void run(DeploymentsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new DeploymentsApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static final Call LIST = (api, params, recorded) -> {
        JsonNode answer = api.settings(params.get("application"), params.get("environment"));
        List<JsonNode> settings = new ArrayList<>();
        answer.path("settings").forEach(settings::add);
        assertThat(settings).isNotEmpty().allSatisfy(s -> assertThat(s.path("key").asText()).isNotBlank());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SettingsCommand.printSettings(new PrintStream(bytes, true, StandardCharsets.UTF_8), settings);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("KEY");
    };

    private static final Call SET = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        JsonNode stored = api.putSetting(params.get("application"), params.get("environment"), params.get("key"),
                sent.path("value").asText(), sent.path("secret").asBoolean());
        assertThat(stored.path("key").asText()).isEqualTo(params.get("key"));
    };

    private static final Call DELETE = (api, params, recorded) ->
            api.deleteSetting(params.get("application"), params.get("environment"), params.get("key"));

    private static final Call PREVIEW = (api, params, recorded) -> {
        JsonNode preview = api.previewSettings(params.get("application"), params.get("environment"), false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SettingsCommand.printPreview(new PrintStream(bytes, true, StandardCharsets.UTF_8), preview);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("unchanged.");
    };

    private static final Call MOVE_DATABASE = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        JsonNode answer = api.moveDatabase(sent.path("application").asText(), sent.path("resource").asText(null),
                sent.path("server").asText(null), sent.path("database").asText(null));
        assertThat(answer.path("move").path("id").asText()).isNotBlank();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DatabaseCommand.printQueued(new PrintStream(bytes, true, StandardCharsets.UTF_8), answer);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("move-status --id");
    };

    private static final Call LIST_MOVES = (api, params, recorded) -> {
        JsonNode answer = api.databaseMoves(params.get("application"));
        List<JsonNode> moves = new ArrayList<>();
        answer.path("moves").forEach(moves::add);
        assertThat(moves).isNotEmpty().allSatisfy(m -> assertThat(m.path("id").asText()).isNotBlank());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DatabaseCommand.printMoves(new PrintStream(bytes, true, StandardCharsets.UTF_8), moves);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("DROP AFTER");
    };

    private static final Call GET_MOVE = (api, params, recorded) -> {
        JsonNode answer = api.databaseMove(params.get("id"));
        assertThat(answer.path("move").path("id").asText()).isEqualTo(params.get("id"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DatabaseCommand.printMove(new PrintStream(bytes, true, StandardCharsets.UTF_8), answer.path("move"));
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("TABLE");
    };

    static final List<CliRow> ROWS = List.of(
            row("qits deployments settings list", WITH_SETTINGS, "listSettings", "settings", LIST)
                    .consumes(BASE.held(WITH_SETTINGS, "listSettings", SETTING.stream().map(p -> "settings[]." + p).toList()))
                    .request("GET /deployments/api/settings?application={application}&environment={environment}"),
            row("qits deployments settings set", WITH_SETTINGS, "putSetting", "putSetting", SET)
                    .consumes(BASE.held(WITH_SETTINGS, "putSetting", SETTING))
                    .request("PUT /deployments/api/settings/{application}/{environment}/{key}"),
            row("qits deployments settings delete", WITH_SETTINGS, "deleteSetting", "deleteSetting", DELETE)
                    .request("DELETE /deployments/api/settings/{application}/{environment}/{key}"),
            row("qits deployments settings preview", SETTINGS_CHANGED, "previewSettings", "previewSettings", PREVIEW)
                    .consumes(BASE.held(SETTINGS_CHANGED, "previewSettings", List.of(
                            "added[].key", "added[].value", "added[].secret",
                            "removed[].key", "removed[].value", "removed[].secret",
                            "changed[].key", "changed[].from", "changed[].to", "changed[].secret", "unchanged")))
                    .request("GET /deployments/api/settings/preview/{application}/{environment}"),
            row("qits deployments database move", HOLDING_A_DATABASE, "moveDatabase", "moveDatabase", MOVE_DATABASE)
                    .consumes(BASE.held(HOLDING_A_DATABASE, "moveDatabase",
                            java.util.stream.Stream.concat(MOVE_LINE.stream().map(p -> "move." + p), java.util.stream.Stream.of("note"))
                                    .toList()))
                    .request("POST /deployments/api/database-moves"),
            row("qits deployments database moves", MOVE_FINISHED, "listDatabaseMoves", "databaseMoves", LIST_MOVES)
                    .consumes(BASE.held(MOVE_FINISHED, "listDatabaseMoves", MOVE.stream().map(p -> "moves[]." + p).toList()))
                    .request("GET /deployments/api/database-moves?application={application}"),
            row("qits deployments database move-status", MOVE_FINISHED, "getDatabaseMove", "databaseMove", GET_MOVE)
                    .consumes(BASE.held(MOVE_FINISHED, "getDatabaseMove",
                            java.util.stream.Stream.concat(MOVE.stream(), MOVE_IN_FULL.stream()).map(p -> "move." + p).toList()))
                    .request("GET /deployments/api/database-moves/{id}"));

    public static final CliPact CONTRACT = BASE.client(DeploymentsApi.class)
            // qits-2243: no golden-masters pin records ABORTED yet. Add the row (POST
            // /deployments/api/database-moves/{id}/abort) once one does; this is not a door that
            // sends no request, only one with no state to bind to yet.
            .excluded("DeploymentsApi#abortDatabaseMove", "qits deployments database abort (qits-2243): pending "
                    + "until a pinned qits-deployments-golden-masters records ABORTED; add its row then")
            .rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "DeploymentsApi#" + method, api(call));
    }

    private DeploymentsContract() {
    }
}
