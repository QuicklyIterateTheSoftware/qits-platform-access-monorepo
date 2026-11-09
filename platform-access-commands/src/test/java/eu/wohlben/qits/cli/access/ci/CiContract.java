package eu.wohlben.qits.cli.access.ci;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the commands ask qits-ci, and why</b> (ticket qits-1149): {@code qits ci runs|run|retry|cancel},
 * {@code qits ci report show|submit}, the changelog's gate highlights and the tui's run dropdown,
 * each row through {@link CiApi} against a pact mock server, written to {@code
 * pacts/qits-platform-access-monorepo_qits-ci-service.json}.
 * <p>
 * Each row binds what the table form reads ({@code RunView}, the report summaries); {@code -o json}
 * prints the answer as it came. Besides the default {@code runs}, qits-ci records the run list
 * without a count (the lookup of a run by the start of its id, and a filtered {@code runs}), the
 * tui's {@code limit=30}, a retry of an unfinished run (409) and a run that does not exist (404).
 * {@code report submit}'s PUT carries the run's own {@code ci-run} token ({@link #asTheRun}).
 */
public final class CiContract {

    static final String RETRIES_ANOTHER = "a failed run that retries another";
    static final String GREEN_RELEASE_RUN = "a green release run";
    static final String RUNNING = "a running run";
    static final String WITH_REPORTS = "a run with reports: failing tests and coverage";
    static final String GATE_REPORTS = "a release run whose gate run has reports";
    static final String NO_RUN = "no run with the given id";
    /** listRuns without a limit. */
    static final String RUNS_BY_ID_START = "a repository with runs to find by the start of an id";
    /** listRuns with limit=30. */
    static final String RECENT_RUNS = "a repository with its 30 most recent runs";

    private static final CliPact BASE = CliPact.of("qits-ci-service", "qits-ci");

    /** A run as {@code RunView.printRuns} shows it in a list. */
    static final List<String> RUN_ROW = List.of("id", "status", "branch", "commitSha", "releaseRequestQualifiedId",
            "releaseRequestId", "createdAt", "startedAt", "finishedAt");

    /** One run as {@code RunView.printRun} shows it, with its steps and, while it runs, the live step. */
    static final List<String> RUN = List.of("id", "repoName", "repoId", "status", "cancellationReason", "phase",
            "branch", "commitSha", "releaseRequestQualifiedId", "releaseRequestId", "retryOfRunId",
            "supersededByRunId", "triggerType", "triggerEventName", "configPath", "createdAt", "startedAt",
            "finishedAt", "steps[].stepIndex", "steps[].image", "steps[].status", "steps[].exitCode",
            "steps[].startedAt", "steps[].finishedAt", "steps[].output", "live");

    @FunctionalInterface
    interface Call {
        void run(CiApi ci, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new CiApi(new PlatformClient(Reached.CREDENTIAL), url), recorded.params(),
                recorded);
    }

    /**
     * The call as {@code qits ci report submit} makes it from a CI step: with the run's own {@code
     * ci-run} token, which qits-ci's door checks. The state hands that token out as its {@code
     * authorization} param, so the row sends that header and the call's credential answers the
     * state's frozen token, which the verifier replaces with the one it seeds.
     */
    private static CliRow.Call asTheRun(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new CiApi(new PlatformClient(credential), url), recorded.params(), recorded);
        };
    }

    private static String printed(java.util.function.Consumer<PrintStream> print) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        print.accept(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static Call runs(Integer limit) {
        return (ci, params, recorded) -> {
            Integer sent = limit;
            if (recorded.query().containsKey("limit")) {
                sent = Integer.valueOf(recorded.query().get("limit").getFirst());
            }
            List<JsonNode> runs = new ArrayList<>();
            ci.runs(params.get("repositoryId"), sent).path("runs").forEach(runs::add);
            assertThat(runs).isNotEmpty().allSatisfy(r -> {
                assertThat(r.path("id").asText()).isNotBlank();
                assertThat(r.path("status").asText()).isNotBlank();
            });
            assertThat(printed(out -> RunView.printRuns(out, runs, Instant.now()))).contains("STATUS");
        };
    }

    /** A lookup of a run by the start of its id reads each run's id. */
    private static final Call FIND_RUN = (ci, params, recorded) ->
            assertThat(ci.runs(params.get("repositoryId"), null).path("runs")).isNotEmpty()
                    .allSatisfy(r -> assertThat(r.path("id").asText()).isNotBlank());

    private static final Call RUN_DETAIL = (ci, params, recorded) -> {
        JsonNode run = ci.run(params.get("runId"));
        assertThat(run.path("id").asText()).isEqualTo(params.get("runId"));
        assertThat(run.path("steps")).isNotEmpty();
        assertThat(printed(out -> RunView.printRun(out, run, Instant.now()))).contains("Steps:");
        assertThat(printed(out -> RunView.printLogs(out, run))).contains("--- step");
    };

    private static final Call NO_SUCH_RUN = (ci, params, recorded) ->
            assertThatThrownBy(() -> ci.run(params.get("runId")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> assertThat(f.status()).isEqualTo(404));

    private static final Call RETRY = (ci, params, recorded) ->
            assertThat(ci.retry(params.get("runId")).path("runId").asText()).isNotBlank();

    private static final Call RETRY_UNFINISHED = (ci, params, recorded) ->
            assertThatThrownBy(() -> ci.retry(params.get("runId")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> assertThat(f.status()).isEqualTo(409));

    /**
     * {@code qits ci cancel}: the 202, which answers nothing, sent with the recorded body's reason
     * (the command then reads the run through {@code getRun}, a row of its own). Pending until a
     * qits-ci golden-masters pin records {@code cancelRun} in {@value #RUNNING}.
     */
    private static final Call CANCEL = (ci, params, recorded) -> {
        JsonNode body = CliRow.exampleBody(recorded);
        JsonNode reason = body == null ? null : body.get("reason");
        ci.cancel(params.get("runId"), reason == null || reason.isNull() ? null : reason.asText());
    };

    /** {@code report show}: the run's commit, request, baseline and each report's kind, version, step and highlights. */
    private static final Call REPORTS = (ci, params, recorded) -> {
        JsonNode answer = ci.reports(params.get("runId"));
        assertThat(answer.path("baseline").path("version").asText()).isNotBlank();
        assertThat(answer.path("reports")).isNotEmpty().allSatisfy(r -> {
            assertThat(r.path("id").asText()).isNotBlank();
            assertThat(r.path("kind").asText()).isNotBlank();
            assertThat(r.path("highlights")).allSatisfy(h -> assertThat(h.path("text").asText()).isNotBlank());
        });
    };

    /** {@code report show --kind}: the whole report, its payload printed as JSON. */
    private static final Call REPORT = (ci, params, recorded) -> {
        JsonNode report = ci.report(params.get("runId"), params.get("reportId"));
        assertThat(report.path("kind").asText()).isNotBlank();
        assertThat(report.path("payload").isObject()).isTrue();
    };

    /** {@code report submit} compares against the baseline the run names: its version, run and tag. */
    private static final Call BASELINE = (ci, params, recorded) -> {
        JsonNode baseline = ci.baseline(params.get("runId")).path("baseline");
        assertThat(baseline.path("version").asText()).isNotBlank();
        assertThat(baseline.path("runId").asText()).isNotBlank();
        assertThat(baseline.path("tagSha").asText()).isNotBlank();
    };

    /** The baseline's reports of one kind, the kind's payload read back. */
    private static final Call BASELINE_REPORTS = (ci, params, recorded) -> {
        String kind = recorded.examplePath().substring(recorded.examplePath().lastIndexOf('/') + 1);
        JsonNode reports = ci.baselineReports(params.get("runId"), kind);
        assertThat(reports.isArray()).isTrue();
        assertThat(reports).isNotEmpty().allSatisfy(r -> {
            assertThat(r.path("kindVersion").isNumber()).isTrue();
            assertThat(r.path("payload").isObject()).isTrue();
        });
    };

    /** The step's report, stored (204): sent as the recording holds it, the step and kind from its path. */
    private static final Call PUT_REPORT = (ci, params, recorded) -> {
        String path = recorded.examplePath();
        String kind = path.substring(path.lastIndexOf('/') + 1);
        int step = Integer.parseInt(path.replaceAll(".*/steps/(\\d+)/reports/.*", "$1"));
        ci.putReport(params.get("runId"), step, kind, CliRow.exampleBody(recorded));
    };

    /** The changelog's gate highlights: each report's step, kind and highlights. */
    private static final Call GATE = (ci, params, recorded) ->
            assertThat(ci.gateReports(params.get("runId")).path("reports")).isNotEmpty().allSatisfy(r -> {
                assertThat(r.path("kind").asText()).isNotBlank();
                assertThat(r.path("highlights")).allSatisfy(h -> assertThat(h.path("severity").asText()).isNotBlank());
            });

    static final List<CliRow> ROWS = List.of(
            row("qits ci runs", GREEN_RELEASE_RUN, "listRuns", "runs", runs(20))
                    .consumes(held(GREEN_RELEASE_RUN, "listRuns", prefixed("runs[].", RUN_ROW))),
            row("qits ci runs", RUNS_BY_ID_START, "listRuns", "runs", FIND_RUN)
                    .consumes(prefixed("runs[].", RUN_ROW)).request("GET /ci/api/runs?repositoryId={repositoryId}"),
            row("qits ci run", RUNS_BY_ID_START, "listRuns", "runs", FIND_RUN)
                    .consumes("runs[].id").request("GET /ci/api/runs?repositoryId={repositoryId}"),
            row("qits ci retry", RUNS_BY_ID_START, "listRuns", "runs", FIND_RUN)
                    .consumes("runs[].id").request("GET /ci/api/runs?repositoryId={repositoryId}"),
            row("qits ci report show", RUNS_BY_ID_START, "listRuns", "runs", FIND_RUN)
                    .consumes("runs[].id").request("GET /ci/api/runs?repositoryId={repositoryId}"),
            row("qits tui", RECENT_RUNS, "listRuns", "runs", runs(30))
                    .consumes("runs[].id", "runs[].branch", "runs[].status", "runs[].createdAt")
                    .request("GET /ci/api/runs?repositoryId={repositoryId}&limit=30"),
            row("qits ci run", RETRIES_ANOTHER, "getRun", "run", RUN_DETAIL)
                    .consumes(held(RETRIES_ANOTHER, "getRun", RUN)),
            row("qits ci run", NO_RUN, "getRun", "run", NO_SUCH_RUN).request("GET /ci/api/runs/{runId}"),
            row("qits ci retry", RETRIES_ANOTHER, "retryRun", "retry", RETRY).consumes("runId"),
            row("qits ci retry", RUNNING, "retryRun", "retry", RETRY_UNFINISHED)
                    .request("POST /ci/api/runs/{runId}/retry"),
            row("qits ci cancel", RUNNING, "cancelRun", "cancel", CANCEL)
                    .request("POST /ci/api/runs/{runId}/cancel"),
            row("qits ci report show", WITH_REPORTS, "listRunReports", "reports", REPORTS)
                    .consumes(held(WITH_REPORTS, "listRunReports", List.of("commitSha", "releaseRequestQualifiedId",
                            "releaseRequestId", "baseline.version", "baseline.runId", "reports[].id", "reports[].kind",
                            "reports[].kindVersion", "reports[].stepIndex", "reports[].highlights[].severity",
                            "reports[].highlights[].text"))),
            row("qits ci report show", WITH_REPORTS, "getRunReport", "report", REPORT)
                    .consumes("kind", "kindVersion", "stepIndex", "payload"),
            row("qits ci report submit", WITH_REPORTS, "getRunBaseline", "baseline", BASELINE)
                    .consumes("baseline.version", "baseline.runId", "baseline.releaseRequestId",
                            "baseline.releaseRequestQualifiedId", "baseline.tagSha"),
            row("qits ci report submit", WITH_REPORTS, "listRunBaselineReports", "baselineReports", BASELINE_REPORTS)
                    .consumes("[].kindVersion", "[].stepIndex", "[].payload"),
            CliRow.of("qits ci report submit", RUNNING, "putRunStepReport", "CiApi#putReport", asTheRun(PUT_REPORT))
                    .header("Authorization", "{authorization}"),
            row("qits artifacts publish changelog", GATE_REPORTS, "listRunGateReports", "gateReports", GATE)
                    .consumes("reports[].stepIndex", "reports[].kind", "reports[].highlights[].severity",
                            "reports[].highlights[].text"));

    public static final CliPact CONTRACT = BASE.client(CiApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "CiApi#" + method, api(call));
    }

    private static List<String> prefixed(String prefix, List<String> paths) {
        return paths.stream().map(p -> prefix + p).toList();
    }

    private static List<String> held(String state, String operationId, List<String> paths) {
        return BASE.held(state, operationId, paths);
    }

    private CiContract() {
    }
}
