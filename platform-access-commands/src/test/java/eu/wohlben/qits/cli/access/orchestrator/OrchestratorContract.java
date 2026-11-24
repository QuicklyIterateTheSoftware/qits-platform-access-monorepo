package eu.wohlben.qits.cli.access.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What {@code qits orchestrator} asks qits-orchestrator, and why</b> (qits-2490), through {@link
 * OrchestratorApi}, written to {@code pacts/qits-platform-access-monorepo_qits-orchestrator-service.json}.
 * <p>
 * Exactly the five operations the landing app's Orchestrator pages call (listProcesses,
 * listProcessRuns, startProcessRun, getProcessRun, getProcessRunStep): qits-ci's pact parity check
 * holds both consumers of the provider to the same set, so an operation here that landing does not
 * make, or the reverse, needs an exclusion with its reason in {@code .config/qits/release.yml}.
 * <p>
 * A run is read with {@code ?bodies=false} unless {@code --bodies} is given, because that is the
 * read the provider records; the runs list is read without {@code limit} for the same reason.
 */
public final class OrchestratorContract {

    static final String SCAN_PROCESS = "the scan process";
    static final String SBOM_CHECK_PROCESS = "the sbom-check process";
    static final String NO_ACTIVE_SCAN = "no active scan run";
    static final String NO_ACTIVE_GC = "no active gc run";
    static final String RUNNING_GC = "a running gc run";
    static final String FINISHED_SCAN = "a finished scan run";
    static final String FINISHED_GC_DRY_RUN = "a finished gc dry run";

    private static final CliPact BASE = CliPact.of("qits-orchestrator-service", "qits-orchestrator");

    /** The processes table: kind, name, dry run, and the steps (counted; -o json prints them). */
    static final List<String> PROCESSES = List.of("[].kind", "[].name", "[].dryRunnable", "[].steps");

    /** The runs table. */
    static final List<String> RUNS = List.of("[].id", "[].status", "[].trigger", "[].dryRun", "[].startedAt",
            "[].finishedAt", "[].summary");

    /** One run as {@code OrchestratorCommand.printRun} prints it. */
    static final List<String> RUN = List.of("id", "kind", "status", "trigger", "dryRun", "startedAt", "finishedAt",
            "summary", "steps[].id", "steps[].status", "steps[].target", "steps[].httpStatus", "steps[].error",
            "steps[].summary");

    /** One step as {@code OrchestratorCommand.printStep} prints it. */
    static final List<String> STEP = List.of("id", "name", "target", "status", "startedAt", "finishedAt",
            "httpStatus", "request.method", "request.url", "response", "responseBytes", "error", "summary");

    @FunctionalInterface
    interface Call {
        void run(OrchestratorApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new OrchestratorApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static String printed(Consumer<PrintStream> print) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        print.accept(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static final Call PROCESSES_READ = (api, params, recorded) -> {
        JsonNode processes = api.processes();
        assertThat(processes).isNotEmpty().allSatisfy(p -> {
            assertThat(p.path("kind").asText()).isNotBlank();
            assertThat(p.path("dryRunnable").isBoolean()).isTrue();
        });
        assertThat(printed(out -> OrchestratorCommand.printProcesses(out, processes))).contains("DRY RUN");
    };

    private static final Call RUNS_READ = (api, params, recorded) -> {
        JsonNode runs = api.runs(params.get("kind"), null);
        assertThat(runs).isNotEmpty().allSatisfy(r -> {
            assertThat(r.path("id").asText()).isNotBlank();
            assertThat(r.path("status").asText()).isNotBlank();
        });
        assertThat(printed(out -> OrchestratorCommand.printRuns(out, runs))).contains(params.get("runId"));
    };

    private static Call start(boolean dryRun) {
        return (api, params, recorded) ->
                assertThat(api.start(params.get("kind"), dryRun).path("id").asText()).isNotBlank();
    }

    private static Call refusedStart(boolean dryRun, int status) {
        return (api, params, recorded) -> assertThatThrownBy(() -> api.start(params.get("kind"), dryRun))
                .isInstanceOfSatisfying(CliFailure.class, f -> {
                    assertThat(f.status()).isEqualTo(status);
                    assertThat(OrchestratorCommand.StartCommand.explained(params.get("kind"), dryRun, f).getMessage())
                            .contains(params.get("kind"));
                });
    }

    private static final Call RUN_READ = (api, params, recorded) -> {
        JsonNode run = api.run(params.get("runId"), false);
        assertThat(run.path("id").asText()).isEqualTo(params.get("runId"));
        assertThat(run.path("steps")).isNotEmpty();
        assertThat(printed(out -> OrchestratorCommand.printRun(out, run))).contains("STEP").contains(params.get("stepId"));
    };

    private static final Call STEP_READ = (api, params, recorded) -> {
        JsonNode step = api.step(params.get("runId"), params.get("stepId"));
        assertThat(step.path("id").asText()).isEqualTo(params.get("stepId"));
        assertThat(printed(out -> OrchestratorCommand.printStep(out, step))).contains(params.get("stepId"));
    };

    static final List<CliRow> ROWS = List.of(
            row("qits orchestrator processes", SCAN_PROCESS, "listProcesses", "processes", PROCESSES_READ)
                    .consumes(PROCESSES)
                    // A process may be added, or gain a step; the table only lists and counts them.
                    .anyLength("$", "[].steps", "[].steps[].dependsOn"),
            row("qits orchestrator runs", FINISHED_SCAN, "listProcessRuns", "runs", RUNS_READ)
                    .consumes(RUNS),
            row("qits orchestrator start", NO_ACTIVE_SCAN, "startProcessRun", "start", start(false))
                    .consumes("id"),
            row("qits orchestrator start", NO_ACTIVE_GC, "startProcessRun", "start", start(true))
                    .consumes("id"),
            row("qits orchestrator start", SCAN_PROCESS, "startProcessRun", "start", refusedStart(true, 400))
                    .consumes("message"),
            row("qits orchestrator start", RUNNING_GC, "startProcessRun", "start", refusedStart(true, 409))
                    .consumes("message"),
            row("qits orchestrator run", FINISHED_SCAN, "getProcessRun", "run", RUN_READ)
                    .consumes(BASE.held(FINISHED_SCAN, "getProcessRun", RUN))
                    .anyLength("steps"),
            row("qits orchestrator run", FINISHED_SCAN, "getProcessRunStep", "step", STEP_READ)
                    .consumes(BASE.held(FINISHED_SCAN, "getProcessRunStep", STEP)));

    public static final CliPact CONTRACT = BASE.client(OrchestratorApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "OrchestratorApi#" + method, api(call));
    }

    private OrchestratorContract() {
    }
}
