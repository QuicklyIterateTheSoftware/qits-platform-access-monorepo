package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits wait branch …` (epic qits-1676, task qits-1723) against a fake edge: the look, then the wait. */
class BranchWaitTest {

    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "a9e1c2d3-0000-4000-8000-0000000000a1";
    private static final String WAIT = "7c1e2d3f-0000-4000-8000-000000000001";
    private static final String PROJECT = "c9e1c2d3-0000-4000-8000-0000000000c3";
    private static final String WAITS = "/workspaces/api/agents/" + AGENT + "/waits";
    private static final String DELIVERIES = "/projects/api/branch-deliveries";
    private static final String BRANCH = "ticket/qits-123";
    private static final String UNKNOWN = """
            {"branch":"ticket/qits-123","everywhere":null,"deliveries":[]}""";

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
        env.put("QITS_WORKSPACES_URL", platform.url());
        env.put("QITS_PROJECTS_URL", platform.url());
        env.put("QITS_AGENT_ID", AGENT);
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.route("POST", WAITS, request -> {
            try {
                JsonNode sent = JSON.readTree(request.body());
                var wait = JSON.createObjectNode();
                wait.put("id", WAIT);
                wait.put("agentId", AGENT);
                wait.set("label", sent.get("label"));
                wait.set("selection", sent.get("selection"));
                wait.put("state", "OPEN");
                wait.put("createdAt", "2026-10-28T10:00:00Z");
                return new FakePlatform.Answer(201, wait.toString(), Map.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}}]}
                """.formatted(PROJECT));
        platform.answer("GET", DELIVERIES, UNKNOWN);
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
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private JsonNode sent() throws Exception {
        List<FakePlatform.Request> posts = platform.requests("POST", WAITS);
        assertThat(posts).hasSize(1);
        return JSON.readTree(posts.getFirst().body());
    }

    /** Each group of the sent selection as its conditions, written {@code path=value}. */
    private static List<List<String>> groups(JsonNode sent) {
        List<List<String>> groups = new ArrayList<>();
        for (JsonNode group : sent.path("selection").path("when")) {
            List<String> conditions = new ArrayList<>();
            for (JsonNode condition : group) {
                assertThat(condition.path("op").asText()).isEqualTo("exact");
                conditions.add(condition.path("path").asText() + "=" + condition.path("value").asText());
            }
            groups.add(conditions);
        }
        return groups;
    }

    /** The groups the table of epic qits-1676 asks: the common conditions, then one value per group. */
    private static List<List<String>> expected(List<String> common, String targetPath, String targets, String early) {
        List<List<String>> groups = new ArrayList<>();
        for (String step : targets.split(" ")) {
            List<String> group = new ArrayList<>(common);
            group.add(targetPath + "=" + step);
            groups.add(group);
        }
        for (String step : early.isEmpty() ? new String[0] : early.split(" ")) {
            List<String> group = new ArrayList<>(common);
            group.add("step=" + step);
            groups.add(group);
        }
        return groups;
    }

    private Map<String, String> lookQuery() {
        List<FakePlatform.Request> looks = platform.requests("GET", DELIVERIES);
        assertThat(looks).hasSize(1);
        Map<String, String> query = new TreeMap<>();
        Arrays.stream(looks.getFirst().query().split("&")).map(p -> p.split("=", 2))
                .forEach(p -> query.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        return query;
    }

    private static String delivery(String repository, String step) {
        return """
                {"projectId":"%s","repoId":"00000000-0000-4000-8000-000000000002","repository":"%s","step":"%s",
                 "requestId":"00000000-0000-4000-8000-000000000003","qualifiedId":"%s-rr-3","foldSha":"abc",
                 "runId":null,"version":null,"changedAt":"2026-10-28T09:00:00Z"}""".formatted(PROJECT, repository, step,
                repository);
    }

    private void deliveries(String everywhere, String... deliveries) {
        platform.answer("GET", DELIVERIES, """
                {"branch":"%s","everywhere":%s,"deliveries":[%s]}""".formatted(BRANCH,
                everywhere == null ? "null" : "\"" + everywhere + "\"", String.join(",", deliveries)));
    }

    // --- the groups ---

    @ParameterizedTest
    @CsvSource({
            "joined,   JOINED MOVED,   FAILED CONFLICTED WITHDRAWN",
            "released, RELEASED LIVE,  FAILED CONFLICTED WITHDRAWN",
            "live,     LIVE,           FAILED CONFLICTED WITHDRAWN",
            "failed,   FAILED CONFLICTED, ''"})
    void withARepositoryEveryGroupIsThatRepositorysStep(String until, String targets, String early) throws Exception {
        Result result = run("wait", "branch", BRANCH, "--until", until, "--repository", "qits-ci-service");

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode sent = sent();
        assertThat(sent.path("selection").path("event").asText()).isEqualTo("BranchDeliveryChanged");
        assertThat(groups(sent)).isEqualTo(expected(List.of("branch=" + BRANCH, "repository=qits-ci-service"), "step",
                targets, early));
        assertThat(sent.path("label").asText()).isEqualTo("branch " + BRANCH + " " + until + " in qits-ci-service");
        assertThat(lookQuery()).isEqualTo(Map.of("branch", BRANCH, "repository", "qits-ci-service"));
    }

    @ParameterizedTest
    @CsvSource({
            "joined,   everywhere, JOINED RELEASED LIVE, FAILED CONFLICTED WITHDRAWN",
            "released, everywhere, RELEASED LIVE,        FAILED CONFLICTED WITHDRAWN",
            "live,     everywhere, LIVE,                 FAILED CONFLICTED WITHDRAWN",
            "failed,   step,       FAILED CONFLICTED,    ''"})
    void withoutARepositoryTheTargetIsEverywhereButFailedIsAStep(String until, String path, String targets,
                                                                  String early) throws Exception {
        Result result = run("wait", "branch", BRANCH, "--until", until);

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode sent = sent();
        assertThat(groups(sent)).isEqualTo(expected(List.of("branch=" + BRANCH), path, targets, early));
        assertThat(groups(sent)).hasSizeLessThanOrEqualTo(WhenGrammar.MAX_GROUPS);
        assertThat(sent.path("label").asText()).isEqualTo("branch " + BRANCH + " " + until + " everywhere");
        assertThat(lookQuery()).isEqualTo(Map.of("branch", BRANCH));
        assertThat(result.out()).contains("Declared.");
    }

    @Test
    void aProjectBySlugAddsItsIdToEveryGroupAndToTheLook() throws Exception {
        Result result = run("wait", "branch", BRANCH, "--until", "LIVE", "--project", "qits", "--repository",
                "qits-ci-service");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(groups(sent())).isEqualTo(expected(List.of("branch=" + BRANCH, "repository=qits-ci-service",
                "projectId=" + PROJECT), "step", "LIVE", "FAILED CONFLICTED WITHDRAWN"));
        assertThat(lookQuery()).isEqualTo(Map.of("branch", BRANCH, "repository", "qits-ci-service",
                "project", PROJECT));
    }

    @Test
    void aProjectWithoutARepositoryToo() throws Exception {
        Result result = run("wait", "branch", BRANCH, "--until", "released", "--project", PROJECT);

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(groups(sent())).isEqualTo(expected(List.of("branch=" + BRANCH, "projectId=" + PROJECT),
                "everywhere", "RELEASED LIVE", "FAILED CONFLICTED WITHDRAWN"));
    }

    @Test
    void aLabelAndATimeoutReplaceThePresets() throws Exception {
        Result result = run("wait", "branch", BRANCH, "--until", "joined", "--label", "my branch in", "--timeout",
                "2h");

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode sent = sent();
        assertThat(sent.path("label").asText()).isEqualTo("my branch in");
        assertThat(sent.path("timeout").asText()).isEqualTo("PT2H");
    }

    // --- the look first ---

    @Test
    void anUnknownBranchWarnsAndWaitsAnyway() throws Exception {
        Result result = run("wait", "branch", BRANCH, "--until", "released");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.err()).contains("no repository knows " + BRANCH + " yet; waiting anyway");
        sent();
    }

    @Test
    void aFailedReadStillDeclares() throws Exception {
        platform.answer("GET", DELIVERIES, 400, "{\"message\":\"branch in several projects; name --project\"}");

        Result result = run("wait", "branch", BRANCH, "--until", "released");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.err()).contains("waiting anyway");
        sent();
    }

    @Test
    void aTargetAlreadyReachedEverywhereDeclaresNothing() {
        deliveries("RELEASED", delivery("qits-ci-service", "RELEASED"), delivery("qits-ci-frontend", "LIVE"));

        Result result = run("wait", "branch", BRANCH, "--until", "released");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out().strip()).isEqualTo("branch " + BRANCH + " already RELEASED everywhere");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void notEverywhereYetIsWaitedOn() throws Exception {
        deliveries("JOINED", delivery("qits-ci-service", "LIVE"), delivery("qits-ci-frontend", "JOINED"));

        Result result = run("wait", "branch", BRANCH, "--until", "live");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.err()).doesNotContain("no repository knows");
        sent();
    }

    @Test
    void aTargetAlreadyReachedInTheRepositoryDeclaresNothing() {
        deliveries("JOINED", delivery("qits-ci-service", "MOVED"));

        Result result = run("wait", "branch", BRANCH, "--until", "joined", "--repository", "qits-ci-service");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out().strip())
                .isEqualTo("branch " + BRANCH + " already MOVED in qits-ci-service (qits-ci-service-rr-3)");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void anEarlyStepAlreadyHeldEverywhereNamesTheRepository() {
        deliveries("JOINED", delivery("qits-ci-service", "JOINED"), delivery("qits-ci-frontend", "CONFLICTED"));

        Result result = run("wait", "branch", BRANCH, "--until", "released");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("branch " + BRANCH + " already CONFLICTED in qits-ci-frontend (qits-ci-frontend-rr-3)")
                .contains("nothing was declared");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void anEarlyStepAlreadyHeldInTheRepositoryDeclaresNothing() {
        deliveries("JOINED", delivery("qits-ci-service", "FAILED"));

        Result result = run("wait", "branch", BRANCH, "--until", "live", "--repository", "qits-ci-service");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .contains("branch " + BRANCH + " already FAILED in qits-ci-service (qits-ci-service-rr-3)")
                .contains("nothing was declared");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void anEarlyStepAlreadyHeldWithJsonPrintsTheReadAndRefuses() throws Exception {
        deliveries("JOINED", delivery("qits-ci-service", "FAILED"));

        Result result = run("wait", "-o", "json", "branch", BRANCH, "--until", "live", "--repository",
                "qits-ci-service");

        assertThat(result.exit()).isEqualTo(1);
        JsonNode printed = JSON.readTree(result.out());
        assertThat(printed.path("declared").asBoolean()).isFalse();
        assertThat(printed.path("already").asText())
                .isEqualTo("branch " + BRANCH + " already FAILED in qits-ci-service (qits-ci-service-rr-3)");
        assertThat(printed.path("deliveries").get(0).path("repository").asText()).isEqualTo("qits-ci-service");
        assertThat(result.err())
                .contains("branch " + BRANCH + " already FAILED in qits-ci-service (qits-ci-service-rr-3)")
                .contains("nothing was declared");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void anEarlyStepInAnotherRepositoryDoesNotCountForOne() throws Exception {
        deliveries("JOINED", delivery("qits-ci-service", "JOINED"), delivery("qits-ci-frontend", "FAILED"));

        Result result = run("wait", "branch", BRANCH, "--until", "released", "--repository", "qits-ci-service");

        assertThat(result.exit()).as(result.err()).isZero();
        sent();
    }

    @Test
    void failedAlreadyHeldIsSaidAndWithdrawnIsNotFailed() throws Exception {
        deliveries("JOINED", delivery("qits-ci-service", "FAILED"));
        Result failed = run("wait", "branch", BRANCH, "--until", "failed");
        assertThat(failed.exit()).as(failed.err()).isZero();
        assertThat(failed.out().strip())
                .isEqualTo("branch " + BRANCH + " already FAILED in qits-ci-service (qits-ci-service-rr-3)");
        assertThat(platform.requests("POST", WAITS)).isEmpty();

        deliveries("PUSHED", delivery("qits-ci-service", "WITHDRAWN"));
        Result withdrawn = run("wait", "branch", BRANCH, "--until", "failed");
        assertThat(withdrawn.exit()).as(withdrawn.err()).isZero();
        sent();
    }

    @Test
    void alreadyWithJsonPrintsTheRead() throws Exception {
        deliveries("LIVE", delivery("qits-ci-service", "LIVE"));

        Result result = run("wait", "-o", "json", "branch", BRANCH, "--until", "live");

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode printed = JSON.readTree(result.out());
        assertThat(printed.path("everywhere").asText()).isEqualTo("LIVE");
        assertThat(printed.path("deliveries").get(0).path("repository").asText()).isEqualTo("qits-ci-service");
        assertThat(printed.path("declared").asBoolean()).isFalse();
        assertThat(printed.path("already").asText()).isEqualTo("branch " + BRANCH + " already LIVE everywhere");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    // --- usage errors: exit 2, and nothing sent ---

    @Test
    void usageErrorsSendNothing() {
        assertThat(run("wait", "branch", BRANCH).exit()).as("no --until").isEqualTo(2);
        Result wrong = run("wait", "branch", BRANCH, "--until", "merged");
        assertThat(wrong.exit()).isEqualTo(2);
        assertThat(wrong.err()).contains("--until must be joined, released, live or failed");
        Result blank = run("wait", "branch", " ", "--until", "live");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("Name the branch");
        assertThat(run("wait", "branch", "--until", "live").exit()).as("no branch").isEqualTo(2);
        assertThat(run("wait", "branch", BRANCH, "--until", "live", "--timeout", "soon").exit()).isEqualTo(2);
        assertThat(run("wait", "branch", BRANCH, "--until", "live", "--project", "nope").exit()).isEqualTo(2);
        env.remove("QITS_AGENT_ID");
        Result noHolder = run("wait", "branch", "feature/x", "--until", "live");
        assertThat(noHolder.exit()).as("no holder").isEqualTo(2);
        assertThat(noHolder.err()).contains("No holder: give --agent <id> or --desk <project>, or --work <item> as a "
                + "project's long-running agent.");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    // --- a project's long-running agent (epic qits-2520) ---

    private static final String PROJECT_AGENT_WAITS = "/workspaces/api/project-agent/waits";

    @Test
    void aTicketBranchHoldsAProjectAgentsWaitForItsItem() throws Exception {
        env.remove("QITS_AGENT_ID");
        platform.answer("POST", PROJECT_AGENT_WAITS, 201, """
                {"id":"%s","state":"OPEN","label":"l","work":"qits-123","selection":{"event":"BranchDeliveryChanged"}}
                """.formatted(WAIT));

        Result result = run("wait", "branch", BRANCH, "--until", "released");

        assertThat(result.exit()).as(result.err()).isZero();
        List<FakePlatform.Request> posts = platform.requests("POST", PROJECT_AGENT_WAITS);
        assertThat(posts).hasSize(1);
        JsonNode sent = JSON.readTree(posts.getFirst().body());
        assertThat(sent.path("work").asText()).isEqualTo("qits-123");
        assertThat(sent.path("selection").path("event").asText()).isEqualTo("BranchDeliveryChanged");
        assertThat(result.out()).contains("work").contains("qits-123");
    }

    @Test
    void anEpicBranchNamesTheEpicAndWorkOverridesEither() throws Exception {
        env.remove("QITS_AGENT_ID");
        platform.answer("POST", PROJECT_AGENT_WAITS, 201, "{\"id\":\"" + WAIT + "\",\"state\":\"OPEN\"}");

        assertThat(run("wait", "branch", "epic/qits-2520", "--until", "live").exit()).isZero();
        assertThat(run("wait", "--work", "qits-9", "branch", BRANCH, "--until", "live").exit()).isZero();

        List<FakePlatform.Request> posts = platform.requests("POST", PROJECT_AGENT_WAITS);
        assertThat(JSON.readTree(posts.get(0).body()).path("work").asText()).isEqualTo("qits-2520");
        assertThat(JSON.readTree(posts.get(1).body()).path("work").asText()).isEqualTo("qits-9");
    }

    @Test
    void workOfReadsTicketAndEpicBranchesOnly() {
        assertThat(BranchWait.workOf("ticket/qits-123")).isEqualTo("qits-123");
        assertThat(BranchWait.workOf("epic/qits-2520")).isEqualTo("qits-2520");
        assertThat(BranchWait.workOf("ticket/")).isNull();
        assertThat(BranchWait.workOf("ticket/qits-1/more")).isNull();
        assertThat(BranchWait.workOf("main")).isNull();
        assertThat(BranchWait.workOf("maintenance/automations/x")).isNull();
    }
}
