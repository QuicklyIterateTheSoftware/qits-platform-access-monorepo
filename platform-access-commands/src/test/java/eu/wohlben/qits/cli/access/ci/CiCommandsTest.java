package eu.wohlben.qits.cli.access.ci;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits ci …` as a person types it, in this process, against a fake edge (projects and ci) and a fake idp. */
class CiCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String CI = "0a0b0c0d-0000-4000-8000-00000000000a";
    private static final String REQUEST = "11111111-2222-3333-4444-555555555555";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String RUNNING = "aaaa1111-0000-4000-8000-000000000001";
    private static final String FAILED = "bbbb2222-0000-4000-8000-000000000002";
    private static final String SUCCESS = "cccc3333-0000-4000-8000-000000000003";
    private static final String QUEUED = "dddd4444-0000-4000-8000-000000000004";
    private static final String NEXT = "eeee5555-0000-4000-8000-000000000005";
    private static final String RUNS = "/ci/api/runs";
    private static final char ESC = 0x1B;
    private static final char BEL = 0x07;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        env.put("QITS_CI_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}}]}
                """.formatted(QITS));
        platform.answer("GET", "/projects/api/projects/" + QITS + "/repositories", """
                {"entries":[{"repository":{"id":"%s","name":"qits-ci-service","archetype":"QUARKUS_SERVICE"},"declared":true}]}
                """.formatted(CI));
        platform.answer("GET", RUNS, """
                {"runs":[
                  {"id":"%1$s","repoId":"%5$s","repoName":"qits-ci-service","branch":"release/%6$s","commitSha":"%7$s",
                   "gating":true,"status":"RUNNING","createdAt":"2026-09-12T09:58:00Z","startedAt":"2026-09-12T09:58:30Z",
                   "finishedAt":null,"releaseRequestId":"%6$s","steps":null,"live":null},
                  {"id":"%2$s","repoId":"%5$s","repoName":"qits-ci-service","branch":"release/%6$s","commitSha":"%7$s",
                   "gating":true,"status":"FAILED","createdAt":"2026-09-12T09:00:00Z","startedAt":"2026-09-12T09:00:10Z",
                   "finishedAt":"2026-09-12T09:03:15Z","releaseRequestId":"%6$s"},
                  {"id":"%3$s","repoId":"%5$s","repoName":"qits-ci-service","branch":"main","commitSha":"fedcba9876543210",
                   "gating":true,"status":"SUCCESS","createdAt":"2026-09-12T08:00:00Z","startedAt":"2026-09-12T08:00:05Z",
                   "finishedAt":"2026-09-12T09:02:05Z","releaseRequestId":null},
                  {"id":"%4$s","repoId":"%5$s","repoName":"qits-ci-service","branch":"2026.911.1","commitSha":"aaaabbbbcccc",
                   "gating":false,"status":"QUEUED","createdAt":"2026-09-12T09:59:00Z","startedAt":null,
                   "finishedAt":null,"releaseRequestId":null}]}
                """.formatted(RUNNING, FAILED, SUCCESS, QUEUED, CI, REQUEST, COMMIT));
        // The steps out of order, and their output as a repository's code may write it: colours, a
        // title-setting sequence, a carriage return before the line break.
        platform.answer("GET", RUNS + "/" + FAILED, """
                {"id":"%1$s","repoId":"%2$s","projectId":"qits","repoName":"qits-ci-service","branch":"release/%3$s",
                 "commitSha":"%4$s","gating":true,"status":"FAILED","createdAt":"2026-09-12T09:00:00Z",
                 "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:03:15Z","cancellationReason":null,
                 "supersededByRunId":null,"daemonVersion":"2026.906.161239","triggerType":"EVENT",
                 "triggerEventId":"e1","triggerEventName":"ReleaseRequestChanged","releaseRequestId":"%3$s",
                 "retryOfRunId":null,"configPath":".config/qits/ci-event-release-request.yml","priority":"HIGH",
                 "expectedStepDurationsMillis":null,
                 "steps":[
                   {"stepIndex":1,"image":"qits/build-images/ci-base:latest","status":"FAILED","exitCode":1,
                    "startedAt":"2026-09-12T09:02:00Z","finishedAt":"2026-09-12T09:03:15Z",
                    "output":"building\\nboom\\u001b]0;evil title\\u0007 done\\r\\n"},
                   {"stepIndex":0,"image":"qits/build-images/maven-base:latest","status":"SUCCESS","exitCode":0,
                    "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:02:00Z",
                    "output":"compiling\\n\\u001b[31mred\\u001b[0m text\\n"}],
                 "live":null}
                """.formatted(FAILED, CI, REQUEST, COMMIT));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        allOut.writeBytes(out.toByteArray());
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Result runs(String... more) {
        String[] args = new String[4 + more.length];
        System.arraycopy(new String[] {"ci", "runs", "--project", "qits"}, 0, args, 0, 4);
        System.arraycopy(more, 0, args, 4, more.length);
        return run(withRepository(args));
    }

    private static String[] withRepository(String[] args) {
        String[] all = new String[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--repository";
        all[args.length + 1] = "qits-ci-service";
        return all;
    }

    private String line(String out, String start) {
        return out.lines().filter(l -> l.startsWith(start)).findFirst().orElse("");
    }

    // --- runs ---

    @Test
    void runsIsATableOfTheNewestRuns() {
        Result r = runs();

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", RUNS).getFirst().query()).isEqualTo("repositoryId=" + CI + "&limit=20");
        assertThat(platform.requests("GET", RUNS).getFirst().authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        assertThat(r.out()).startsWith("ID        STATUS   BRANCH");
        assertThat(line(r.out(), "aaaa1111")).startsWith("aaaa1111  RUNNING  release/" + REQUEST)
                .contains("01234567  11111111").endsWith("1m30s so far");
        assertThat(line(r.out(), "bbbb2222")).contains("FAILED").endsWith("3m05s");
        assertThat(line(r.out(), "cccc3333")).contains("SUCCESS  main").contains("fedcba98  -").endsWith("1h02m");
        assertThat(line(r.out(), "dddd4444")).contains("QUEUED").endsWith("  -");
    }

    @Test
    void theLimitGoesToTheServiceAndIsKeptHereToo() {
        Result r = runs("--limit", "2");

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("GET", RUNS).getFirst().query()).isEqualTo("repositoryId=" + CI + "&limit=2");
        assertThat(r.out().lines().toList()).hasSize(3);
        assertThat(r.out()).contains("aaaa1111").contains("bbbb2222").doesNotContain("cccc3333");
        assertThat(runs("--limit", "0").err()).contains("--limit must be 1 or more.");
    }

    @Test
    void aFilterReadsEveryRunAndIsAppliedHere() {
        Result r = runs("--status", "failed", "--limit", "1");

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("GET", RUNS).getFirst().query()).isEqualTo("repositoryId=" + CI);
        assertThat(r.out()).contains("bbbb2222").doesNotContain("aaaa1111").doesNotContain("cccc3333");
        assertThat(runs("--branch", "main").out()).contains("cccc3333").doesNotContain("bbbb2222");
        assertThat(runs("--status", "CANCELLED").out()).isEqualTo("No runs of qits-ci-service match.\n");
    }

    @Test
    void theRunsOfAReleaseRequestAreFoundByTheStartOfItsId() throws Exception {
        assertThat(runs("--release-request", "1111").out()).contains("aaaa1111").contains("bbbb2222")
                .doesNotContain("cccc3333").doesNotContain("dddd4444");

        Result r = runs("--release-request", REQUEST.toUpperCase(), "-o", "json");

        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("runs")).extracting(n -> n.path("id").asText()).containsExactly(RUNNING, FAILED);
        assertThat(printed.path("runs").get(1).path("finishedAt").asText()).isEqualTo("2026-09-12T09:03:15Z");
    }

    @Test
    void aRunOfARequestWithALogicalIdShowsItAndIsFoundByIt() {
        platform.answer("GET", RUNS, """
                {"runs":[
                  {"id":"%1$s","repoId":"%3$s","repoName":"qits-ci-service","branch":"release/qits-ci-service-rr-12",
                   "commitSha":"%5$s","gating":true,"status":"FAILED","createdAt":"2026-09-12T09:00:00Z",
                   "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:03:15Z","releaseRequestId":"%4$s",
                   "releaseRequestQualifiedId":"qits-ci-service-rr-12"},
                  {"id":"%2$s","repoId":"%3$s","repoName":"qits-ci-service","branch":"main","commitSha":"fedcba9876543210",
                   "gating":true,"status":"SUCCESS","createdAt":"2026-09-12T08:00:00Z","startedAt":"2026-09-12T08:00:05Z",
                   "finishedAt":"2026-09-12T09:02:05Z","releaseRequestId":null,"releaseRequestQualifiedId":null}]}
                """.formatted(FAILED, SUCCESS, CI, REQUEST, COMMIT));
        platform.answer("GET", RUNS + "/" + FAILED, """
                {"id":"%1$s","repoId":"%2$s","repoName":"qits-ci-service","branch":"release/qits-ci-service-rr-12",
                 "commitSha":"%4$s","gating":true,"status":"FAILED","createdAt":"2026-09-12T09:00:00Z",
                 "releaseRequestId":"%3$s","releaseRequestQualifiedId":"qits-ci-service-rr-12","steps":[]}
                """.formatted(FAILED, CI, REQUEST, COMMIT));

        Result r = runs("--release-request", "QITS-CI-SERVICE-RR-12");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(line(r.out(), "bbbb2222")).contains("01234567  qits-ci-service-rr-12");
        assertThat(r.out()).doesNotContain("cccc3333");
        assertThat(runs("--release-request", "1111").out()).contains("bbbb2222");
        assertThat(runs("--release-request", "qits-ci-service-rr-1").out()).isEqualTo("No runs of qits-ci-service match.\n");
        assertThat(run("ci", "run", FAILED).out()).contains("  release request  qits-ci-service-rr-12 (" + REQUEST + ")");
    }

    @Test
    void theProjectAndRepositoryAreRequiredAndMayComeFirst() {
        assertThat(run("ci", "runs").err()).contains("Name the project: --project <id, slug or name>.");
        assertThat(run("ci", "runs", "--project", "qits").err()).contains("Name the repository: --repository <id or name>.");
        assertThat(platform.requests).isEmpty();

        Result r = run("ci", "--project", "qits", "--repository", CI, "runs");
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("aaaa1111");
    }

    // --- run ---

    @Test
    void runShowsTheRunAndItsStepsInOrder() {
        Result r = run("ci", "run", FAILED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("Run " + FAILED + "\n")
                .contains("  repository       qits-ci-service")
                .contains("  status           FAILED")
                .contains("  commit           " + COMMIT)
                .contains("  release request  " + REQUEST)
                .contains("  trigger          EVENT ReleaseRequestChanged")
                .contains("  took             3m05s")
                .doesNotContain("compiling");
        List<String> lines = r.out().lines().toList();
        int header = lines.indexOf("  STEP  IMAGE                                STATUS   EXIT  TOOK");
        assertThat(header).isPositive();
        assertThat(lines.get(header + 1)).isEqualTo("  0     qits/build-images/maven-base:latest  SUCCESS  0     1m50s");
        assertThat(lines.get(header + 2)).isEqualTo("  1     qits/build-images/ci-base:latest     FAILED   1     1m15s");
    }

    @Test
    void aFailedStepsTailIsShownWithoutLogs() {
        Result r = run("ci", "run", FAILED);

        assertThat(r.exit()).as(r.err()).isZero();
        List<String> lines = r.out().lines().toList();
        int at = lines.indexOf("--- step 1  qits/build-images/ci-base:latest  FAILED, exit 1: its last 2 lines ---");
        assertThat(at).isPositive();
        assertThat(lines.get(at - 1)).isEmpty();
        assertThat(lines.subList(at, at + 4)).containsExactly(
                "--- step 1  qits/build-images/ci-base:latest  FAILED, exit 1: its last 2 lines ---",
                "building",
                "boom done",
                "All of it: qits ci run " + FAILED + " --logs");
        assertThat(r.out()).doesNotContain("compiling").doesNotContain("--- step 0")
                .doesNotContain(String.valueOf(ESC)).doesNotContain(String.valueOf(BEL)).doesNotContain("\r");
    }

    /** qits-2011: the step's output is scanned backwards; only the last 20 lines show, trailing blanks ignored. */
    @Test
    void aFailedStepWithManyLinesShowsOnlyTheLastTwenty() throws Exception {
        StringBuilder output = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            output.append("line").append(i).append('\n');
        }
        output.append("\n\n");
        ObjectNode run = (ObjectNode) JSON.readTree("""
                {"id":"%1$s","repoId":"%2$s","repoName":"qits-ci-service","branch":"release/%3$s",
                 "commitSha":"%4$s","gating":true,"status":"FAILED","createdAt":"2026-09-12T09:00:00Z",
                 "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:03:15Z","releaseRequestId":"%3$s",
                 "steps":[{"stepIndex":0,"image":"big-step","status":"FAILED","exitCode":1,
                           "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:03:15Z","output":""}],
                 "live":null}
                """.formatted(FAILED, CI, REQUEST, COMMIT));
        ((ObjectNode) run.path("steps").get(0)).put("output", output.toString());
        platform.answer("GET", RUNS + "/" + FAILED, run.toString());

        Result r = run("ci", "run", FAILED);

        assertThat(r.exit()).as(r.err()).isZero();
        List<String> lines = r.out().lines().toList();
        int at = lines.indexOf("--- step 0  big-step  FAILED, exit 1: its last 20 lines ---");
        assertThat(at).isPositive();
        List<String> expectedTail = new ArrayList<>();
        for (int i = 6; i <= 25; i++) {
            expectedTail.add("line" + i);
        }
        assertThat(lines.subList(at + 1, at + 21)).isEqualTo(expectedTail);
        assertThat(lines.get(at + 21)).isEqualTo("All of it: qits ci run " + FAILED + " --logs");
        assertThat(r.out()).doesNotContain("line1\n").doesNotContain("line5\n");
    }

    /** A SUCCESS run, and a failed step with no output, print no tail block and no hint. */
    @Test
    void aSuccessfulRunOrAFailedStepWithNoOutputShowsNoTail() throws Exception {
        platform.answer("GET", RUNS + "/" + SUCCESS, """
                {"id":"%1$s","repoId":"%2$s","repoName":"qits-ci-service","branch":"main",
                 "commitSha":"fedcba9876543210","gating":true,"status":"SUCCESS","createdAt":"2026-09-12T08:00:00Z",
                 "startedAt":"2026-09-12T08:00:05Z","finishedAt":"2026-09-12T09:02:05Z","releaseRequestId":null,
                 "steps":[{"stepIndex":0,"image":"maven-base","status":"SUCCESS","exitCode":0,
                           "startedAt":"2026-09-12T08:00:05Z","finishedAt":"2026-09-12T09:02:05Z",
                           "output":"all good\\n"}],
                 "live":null}
                """.formatted(SUCCESS, CI));
        Result success = run("ci", "run", SUCCESS);
        assertThat(success.exit()).as(success.err()).isZero();
        assertThat(success.out()).doesNotContain("--- step").doesNotContain("All of it:");

        ObjectNode run = (ObjectNode) JSON.readTree("""
                {"id":"%1$s","repoId":"%2$s","repoName":"qits-ci-service","branch":"release/%3$s",
                 "commitSha":"%4$s","gating":true,"status":"FAILED","createdAt":"2026-09-12T09:00:00Z",
                 "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:03:15Z","releaseRequestId":"%3$s",
                 "steps":[{"stepIndex":0,"image":"empty-step","status":"FAILED","exitCode":1,
                           "startedAt":"2026-09-12T09:00:10Z","finishedAt":"2026-09-12T09:03:15Z","output":""}],
                 "live":null}
                """.formatted(FAILED, CI, REQUEST, COMMIT));
        platform.answer("GET", RUNS + "/" + FAILED, run.toString());

        Result r = run("ci", "run", FAILED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).doesNotContain("--- step").doesNotContain("All of it:");
    }

    @Test
    void theLogsComeWithoutTerminalControlCharacters() {
        Result r = run("ci", "run", FAILED, "--logs");

        assertThat(r.exit()).isZero();
        String logs = r.out().substring(r.out().indexOf("--- step"));
        assertThat(logs.lines().toList()).containsExactly(
                "--- step 0  qits/build-images/maven-base:latest  SUCCESS, exit 0 ---",
                "compiling",
                "red text",
                "--- step 1  qits/build-images/ci-base:latest  FAILED, exit 1 ---",
                "building",
                "boom done");
        assertThat(r.out()).doesNotContain(String.valueOf(ESC)).doesNotContain(String.valueOf(BEL))
                .doesNotContain("\r").doesNotContain("evil title");
    }

    @Test
    void theJsonFormWritesControlCharactersAsEscapes() throws Exception {
        Result r = run("ci", "run", FAILED, "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\\u001B[31m").doesNotContain(String.valueOf(ESC)).doesNotContain(String.valueOf(BEL));
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("status").asText()).isEqualTo("FAILED");
        assertThat(printed.path("steps").get(1).path("output").asText()).startsWith("compiling\n" + ESC + "[31m");
    }

    @Test
    void aRunningRunShowsItsLiveStep() {
        platform.answer("GET", RUNS + "/" + RUNNING, """
                {"id":"%s","repoName":"qits-ci-service","status":"RUNNING","createdAt":"2026-09-12T09:58:00Z",
                 "startedAt":"2026-09-12T09:58:30Z","finishedAt":null,
                 "steps":[{"stepIndex":0,"image":"maven-base","status":"SUCCESS","exitCode":0,
                           "startedAt":"2026-09-12T09:58:30Z","finishedAt":"2026-09-12T09:59:00Z","output":""}],
                 "live":{"stepIndex":1,"startedAt":"2026-09-12T09:59:00Z","output":"still going\\n"}}
                """.formatted(RUNNING));

        Result r = run("ci", "run", RUNNING, "--logs");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("  took             1m30s so far")
                .contains("  1     -           RUNNING  -     1m00s so far")
                .contains("--- step 0  maven-base  SUCCESS, exit 0 ---\n(no output)\n")
                .contains("--- step 1  RUNNING, the output so far ---\nstill going\n");
    }

    @Test
    void aShortIdIsFoundAmongTheRepositorysRuns() {
        Result r = run("ci", "run", "BBBB", "--project", "qits", "--repository", "qits-ci-service");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", RUNS).getFirst().query()).isEqualTo("repositoryId=" + CI);
        assertThat(platform.requests("GET", RUNS + "/" + FAILED)).hasSize(1);
        assertThat(r.out()).startsWith("Run " + FAILED);
    }

    @Test
    void aShortIdThatFitsNoneOrTwoRunsIsAUsageError() {
        Result none = run("ci", "run", "9999", "--project", "qits", "--repository", "qits-ci-service");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Repository qits-ci-service has no run whose id starts with '9999'. "
                + "`qits ci runs --project qits --repository qits-ci-service` shows them.");

        platform.answer("GET", RUNS, """
                {"runs":[{"id":"abcd0001-0000-4000-8000-000000000000"},{"id":"abcd0002-0000-4000-8000-000000000000"}]}
                """);
        Result two = run("ci", "retry", "abcd", "--project", "qits", "--repository", "qits-ci-service");
        assertThat(two.exit()).isEqualTo(2);
        assertThat(two.err()).contains("'abcd' is the start of more than one run of qits-ci-service: "
                + "abcd0001-0000-4000-8000-000000000000, abcd0002-0000-4000-8000-000000000000. Give more of the id.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));

        assertThat(run("ci", "run", "bbbb", "--project", "qits").err())
                .contains("Give both --project and --repository, or neither.");
    }

    @Test
    void anUnknownRunIsA404ThatSaysHowToFindAShortId() {
        Result r = run("ci", "run", "bbbb");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such run: bbbb (HTTP 404). A short id is found only with --project and --repository.");
        assertThat(run("ci", "run", NEXT).err()).contains("No such run: " + NEXT + " (HTTP 404).")
                .doesNotContain("short id");
    }

    // --- retry ---

    @Test
    void retryPostsNothingAndPrintsTheNewRunAndHowToFollowIt() {
        platform.answer("POST", RUNS + "/" + FAILED + "/retry", 202, "{\"runId\":\"" + NEXT + "\"}");

        Result r = run("ci", "retry", "bbbb2222", "--project", "qits", "--repository", "qits-ci-service");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request post = platform.requests("POST", RUNS + "/" + FAILED + "/retry").getFirst();
        assertThat(post.body()).isEmpty();
        assertThat(post.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        assertThat(r.out().lines().toList()).containsExactly(
                "Run " + FAILED + " runs again as run " + NEXT + ", at the same commit. It is queued.",
                "Follow it:  qits ci run " + NEXT,
                "Its verdict also comes as an event:  qits events --filter=BuildSuccessful,BuildFailed");
    }

    /** qits-1825: the service's own sentence is passed on, not reworded into a made-up one. */
    @Test
    void retryingARunThatHasNotFinishedSaysSo() {
        platform.answer("POST", RUNS + "/" + RUNNING + "/retry", 409,
                "{\"message\":\"CI run " + RUNNING + " has not finished (RUNNING) — nothing to retry yet\"}");

        Result r = run("ci", "retry", RUNNING);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(
                "CI run " + RUNNING + " has not finished (RUNNING) — nothing to retry yet (HTTP 409).");
        assertThat(r.err()).doesNotContain("answered HTTP");
        assertThat(r.out()).isEmpty();
    }

    /** qits-1816: an automation's run is refused with the service's sentence, which names regenerate. */
    @Test
    void retryingAnAutomationRunNamesRegenerate() {
        platform.answer("POST", RUNS + "/" + FAILED + "/retry", 409,
                "{\"message\":\"CI run " + FAILED + " is the dependency-bump automation's run for a release request"
                        + " — a retry would not count for the request; run it again with: qits release-request"
                        + " regenerate --kind dependency-bump\"}");

        Result r = run("ci", "retry", FAILED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("qits release-request regenerate --kind dependency-bump (HTTP 409).");
        assertThat(r.err()).doesNotContain("has not finished yet");
        assertThat(r.err()).doesNotContain("answered HTTP");
        assertThat(r.out()).isEmpty();
    }

    /** qits-1825: qits-ci's own sentences are passed on too, not just the automation's. */
    @Test
    void retryingARetiredRunnersHealthCheckSaysSo() {
        platform.answer("POST", RUNS + "/" + FAILED + "/retry", 409,
                "{\"message\":\"CI run " + FAILED + " is a retired CI runner's health check — there is nothing to"
                        + " retry\"}");

        Result r = run("ci", "retry", FAILED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(
                "CI run " + FAILED + " is a retired CI runner's health check — there is nothing to retry (HTTP 409).");
        assertThat(r.err()).doesNotContain("has not finished yet");
        assertThat(r.out()).isEmpty();
    }

    /** qits-1825: a 409 with no service message at all gets a plain refusal, never a guessed one. */
    @Test
    void retryingWithNo409ServiceMessageIsAPlainRefusal() {
        platform.answer("POST", RUNS + "/" + FAILED + "/retry", 409, "");

        Result r = run("ci", "retry", FAILED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The platform refused to retry run " + FAILED + " (HTTP 409).");
        assertThat(r.err()).doesNotContain("has not finished");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void retryingAnUnknownRunOrWithoutTheRoleIsRefused() {
        Result unknown = run("ci", "retry", NEXT);
        assertThat(unknown.exit()).isEqualTo(1);
        assertThat(unknown.err()).contains("No such run: " + NEXT + " (HTTP 404).");

        platform.answer("POST", RUNS + "/" + FAILED + "/retry", 403, "");
        Result forbidden = run("ci", "-o", "json", "retry", FAILED);
        assertThat(forbidden.exit()).isEqualTo(1);
        assertThat(forbidden.err()).contains("Your roles do not allow this (HTTP 403)");
    }

    // --- cancel (qits-1644) ---

    @Test
    void cancelPostsTheReasonThenShowsTheRun() throws Exception {
        platform.answer("POST", RUNS + "/" + QUEUED + "/cancel", 202, "");
        platform.answer("GET", RUNS + "/" + QUEUED, """
                {"id":"%s","repoName":"qits-ci-service","status":"CANCELLED","cancellationReason":"superseded",
                 "createdAt":"2026-09-12T09:59:00Z","startedAt":null,"finishedAt":"2026-09-12T10:00:00Z",
                 "steps":[],"live":null}
                """.formatted(QUEUED));

        Result r = run("ci", "cancel", "dddd", "--project", "qits", "--repository", "qits-ci-service",
                "--reason", "superseded");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request post = platform.requests("POST", RUNS + "/" + QUEUED + "/cancel").getFirst();
        assertThat(JSON.readTree(post.body())).isEqualTo(JSON.readTree("{\"reason\":\"superseded\"}"));
        assertThat(post.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        assertThat(platform.requests("GET", RUNS + "/" + QUEUED)).hasSize(1);
        assertThat(r.out()).startsWith("Run " + QUEUED).contains("CANCELLED").contains("superseded");
    }

    @Test
    void cancelWithoutAReasonSendsAnEmptyObjectAndPrintsJson() throws Exception {
        platform.answer("POST", RUNS + "/" + FAILED + "/cancel", 202, "");

        Result r = run("ci", "-o", "json", "cancel", FAILED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", RUNS + "/" + FAILED + "/cancel").getFirst().body()).isEqualTo("{}");
        assertThat(JSON.readTree(r.out()).path("id").asText()).isEqualTo(FAILED);
    }

    @Test
    void cancellingAFinishedOrUnknownRunIsRefused() {
        platform.answer("POST", RUNS + "/" + SUCCESS + "/cancel", 409, "{\"message\":\"CI run has already finished\"}");
        Result finished = run("ci", "cancel", SUCCESS);
        assertThat(finished.exit()).isEqualTo(1);
        assertThat(finished.err()).contains("Run " + SUCCESS + " has already finished, so there is nothing to cancel "
                + "(HTTP 409).");
        assertThat(platform.requests("GET", RUNS + "/" + SUCCESS)).isEmpty();

        Result unknown = run("ci", "cancel", NEXT);
        assertThat(unknown.exit()).isEqualTo(1);
        assertThat(unknown.err()).contains("No such run: " + NEXT + " (HTTP 404).");
    }

    @Test
    void theHelpNamesTheRolesTheServerAdmits() {
        // picocli may break a line after "qits:", so the words are joined again before they are read.
        String ci = run("ci", "--help").out().replaceAll("\\s+", " ").replace("qits: ", "qits:");
        assertThat(ci).contains("retry takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent")
                .contains("cancel takes the same, qits:desk and qits:agent bound to the run's own project")
                .doesNotContain("retry needs qits:admin.");
        assertThat(run("ci", "cancel", "--help").out().replaceAll("\\s+", " ").replace("qits: ", "qits:"))
                .contains("Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent; a desk or "
                        + "an agent only for a run in its own project, and another project's run answers HTTP 403.");
    }

    // --- the address ---

    @Test
    void theCiAddressComesFromTheIdpWhenNothingNamesIt() {
        env.remove("QITS_CI_URL");

        Result r = run("ci", "run", FAILED);

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Pass --ci-url or set QITS_CI_URL.");
        assertThat(run("ci", "run", FAILED, "--ci-url", platform.url()).exit()).isZero();
    }
}
