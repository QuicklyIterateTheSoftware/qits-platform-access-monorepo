package eu.wohlben.qits.cli.access.orchestrator;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits orchestrator …` against a fake edge and a fake idp (qits-2490). */
class OrchestratorCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
    private static final String RUN = "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f";
    private static final String PROCESSES_DOOR = "/orchestrator/api/processes";
    private static final String SCAN_RUNS = "/orchestrator/api/processes/scan/runs";
    private static final String GC_RUNS = "/orchestrator/api/processes/gc/runs";
    private static final String RUN_DOOR = "/orchestrator/api/runs/" + RUN;
    private static final String STEP_DOOR = RUN_DOOR + "/steps/internal.follow";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_ORCHESTRATOR_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("GET", PROCESSES_DOOR, """
                [{"kind":"gc","name":"Garbage collection","description":"Deletes what nothing pins.","dryRunnable":true,
                  "steps":[{"id":"pins.ci","name":"CI daemon pin","target":"ci","dependsOn":[]},
                           {"id":"artifacts.plan","name":"Plan","target":"artifacts","dependsOn":["pins.ci"]}]},
                 {"kind":"scan","name":"Manifest scan","description":"Scans manifests.","dryRunnable":false,
                  "steps":[{"id":"internal.request","name":"Request","target":"maintenance","dependsOn":[]}]}]
                """);
        platform.answer("GET", SCAN_RUNS, """
                [{"id":"%s","kind":"scan","trigger":"scheduled","dryRun":false,"status":"SUCCEEDED",
                  "startedAt":"2026-11-24T02:15:00Z","finishedAt":"2026-11-24T02:16:00Z",
                  "summary":"internal.follow SUCCEEDED (INTERNAL scan SUCCEEDED)"}]
                """.formatted(RUN));
        platform.answer("GET", GC_RUNS, "[]");
        platform.answer("POST", SCAN_RUNS, 202, "{\"id\":\"" + RUN + "\"}");
        platform.answer("GET", RUN_DOOR, """
                {"id":"%s","kind":"scan","trigger":"manual","dryRun":false,"status":"SUCCEEDED",
                 "startedAt":"2026-11-24T02:15:00Z","finishedAt":"2026-11-24T02:16:00Z","summary":"all four steps done",
                 "steps":[{"id":"internal.request","name":"Request the internal scan","target":"maintenance",
                   "dependsOn":[],"status":"SUCCEEDED","httpStatus":202,
                   "request":{"method":"POST","url":"http://dev-qits-maintenance:8080/maintenance/api/scans","body":null},
                   "response":null,"responseBytes":45,"error":null,"summary":"queued scan 1"},
                  {"id":"internal.follow","name":"Follow the internal scan","target":"maintenance",
                   "dependsOn":["internal.request"],"status":"FAILED","httpStatus":500,
                   "request":{"method":"GET","url":"http://dev-qits-maintenance:8080/maintenance/api/scans/1","body":null},
                   "response":null,"responseBytes":12,"error":"maintenance answered 500","summary":null}]}
                """.formatted(RUN));
        platform.answer("GET", STEP_DOOR, """
                {"id":"internal.follow","name":"Follow the internal scan","target":"maintenance",
                 "dependsOn":["internal.request"],"status":"SUCCEEDED","httpStatus":200,
                 "request":{"method":"GET","url":"http://dev-qits-maintenance:8080/maintenance/api/scans/1","body":null},
                 "response":"{\\"status\\":\\"SUCCEEDED\\"}","responseBytes":22,"error":null,
                 "summary":"INTERNAL scan SUCCEEDED"}
                """);
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void processesListsKindNameDryRunAndStepCount() {
        Result r = run("orchestrator", "processes");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("KIND").contains("NAME").contains("DRY RUN").contains("STEPS");
        assertThat(r.out().lines().filter(l -> l.startsWith("gc ")).findFirst().orElseThrow())
                .contains("Garbage collection").contains("yes").endsWith("2");
        assertThat(r.out().lines().filter(l -> l.startsWith("scan ")).findFirst().orElseThrow())
                .contains("Manifest scan").contains("no").endsWith("1");
        assertThat(platform.requests("GET", PROCESSES_DOOR).getFirst().authorization())
                .isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
    }

    @Test
    void processesJsonPrintsTheAnswerWithTheSteps() {
        Result r = run("orchestrator", "processes", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"steps\"").contains("\"artifacts.plan\"").contains("\"dependsOn\"");
    }

    @Test
    void runsListsTheKindsRunsWithTheWholeId() {
        Result r = run("orchestrator", "runs", "--kind", "scan");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("ID").contains("STATUS").contains("TRIGGER").contains("SUMMARY")
                .contains(RUN).contains("SUCCEEDED").contains("scheduled").contains("INTERNAL scan SUCCEEDED");
        assertThat(platform.requests("GET", SCAN_RUNS).getFirst().query()).isNull();
    }

    @Test
    void runsSendsTheLimit() {
        Result r = run("orchestrator", "runs", "--kind", "scan", "--limit", "5");

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("GET", SCAN_RUNS).getFirst().query()).isEqualTo("limit=5");
    }

    @Test
    void aLimitBelowOneIsAUsageError() {
        Result r = run("orchestrator", "runs", "--kind", "scan", "--limit", "0");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(platform.requests("GET", SCAN_RUNS)).isEmpty();
    }

    @Test
    void noRunsYetSaysSo() {
        Result r = run("orchestrator", "runs", "--kind", "gc");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("The gc process has not run yet.");
    }

    @Test
    void startPostsTheDryRunFlagAndPrintsTheRunId() {
        Result r = run("orchestrator", "start", "--kind", "scan");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("Started scan: run " + RUN).contains("qits orchestrator run --id " + RUN);
        assertThat(platform.requests("POST", SCAN_RUNS).getFirst().body()).isEqualTo("{\"dryRun\":false}");
    }

    @Test
    void startWithDryRunSaysSoAndSendsIt() {
        platform.answer("POST", GC_RUNS, 202, "{\"id\":\"" + RUN + "\"}");
        Result r = run("orchestrator", "start", "--kind", "gc", "--dry-run");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Started a dry run of gc: run " + RUN);
        assertThat(platform.requests("POST", GC_RUNS).getFirst().body()).isEqualTo("{\"dryRun\":true}");
    }

    @Test
    void startJsonPrintsTheAnswer() {
        Result r = run("orchestrator", "start", "--kind", "scan", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"id\" : \"" + RUN + "\"");
    }

    @Test
    void aDryRunOfAKindWithoutOneIsExplained() {
        platform.answer("POST", SCAN_RUNS, 400, "{\"message\":\"the scan process has no dry run\"}");
        Result r = run("orchestrator", "start", "--kind", "scan", "--dry-run");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The scan process offers no dry run: start it without --dry-run.")
                .contains("the scan process has no dry run");
    }

    @Test
    void aKindNotOpenToTheCallerIsExplained() {
        platform.answer("POST", SCAN_RUNS, 403, "{\"message\":\"a commissioned agent may not start scan\"}");
        Result r = run("orchestrator", "start", "--kind", "scan");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("This credential may not start a scan run.")
                .contains("a commissioned agent may not start scan");
    }

    @Test
    void anUnknownKindIsExplained() {
        platform.answer("POST", "/orchestrator/api/processes/nope/runs", 404, "{\"message\":\"no process nope\"}");
        Result r = run("orchestrator", "start", "--kind", "nope");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("There is no process of kind 'nope'").contains("no process nope");
    }

    @Test
    void anActiveRunIsExplained() {
        platform.answer("POST", SCAN_RUNS, 409, "{\"message\":\"a scan run is already active: " + RUN + "\"}");
        Result r = run("orchestrator", "start", "--kind", "scan");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("A scan run is still active").contains("already active: " + RUN);
    }

    @Test
    void runShowsTheRunAndItsStepsWithoutBodies() {
        Result r = run("orchestrator", "run", "--id", RUN);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("all four steps done").contains("STEP").contains("TARGET").contains("HTTP")
                .contains("internal.request").contains("queued scan 1")
                .contains("maintenance answered 500");
        assertThat(platform.requests("GET", RUN_DOOR).getFirst().query()).isEqualTo("bodies=false");
    }

    @Test
    void runWithBodiesAsksForThem() {
        Result r = run("orchestrator", "run", "--id", RUN, "--bodies");

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("GET", RUN_DOOR).getFirst().query()).isNull();
    }

    @Test
    void runWithAStepShowsThatStepWithItsResponse() {
        Result r = run("orchestrator", "run", "--id", RUN, "--step", "internal.follow");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("Follow the internal scan").contains("GET http://dev-qits-maintenance:8080")
                .contains("response:").contains("{\"status\":\"SUCCEEDED\"}");
        assertThat(platform.requests("GET", RUN_DOOR)).isEmpty();
    }

    @Test
    void anUnknownOutputIsAUsageError() {
        Result r = run("orchestrator", "processes", "-o", "yaml");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--output is table or json");
    }

    @Test
    void theHelpNamesTheFourCommands() {
        Result r = run("orchestrator", "--help");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("processes").contains("runs").contains("start").contains("run");
    }
}
