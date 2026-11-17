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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits wait …` as an agent types it, in this process, against a fake edge (workspaces and projects). */
class WaitCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "a9e1c2d3-0000-4000-8000-0000000000a1";
    private static final String OTHER_AGENT = "b9e1c2d3-0000-4000-8000-0000000000b2";
    private static final String WAIT = "7c1e2d3f-0000-4000-8000-000000000001";
    private static final String REQUEST = "4f2a91c0-1d2e-4c3b-9a8f-0123456789ab";
    private static final String RUN = "0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a";
    private static final String ENTITY = "45a14f8e-f550-45bb-a117-6b34d8c472e3";
    private static final String PROJECT = "c9e1c2d3-0000-4000-8000-0000000000c3";
    private static final String WAITS = "/workspaces/api/agents/" + AGENT + "/waits";
    private static final String DESK_WAITS = "/workspaces/api/desks/" + PROJECT + "/waits";
    private static final char ESC = 0x1B;

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
        // The service answers with the wait it stored: the selection as sent, and its own fields.
        platform.route("POST", WAITS, request -> {
            try {
                JsonNode sent = JSON.readTree(request.body());
                var wait = JSON.createObjectNode();
                wait.put("id", WAIT);
                wait.put("agentId", AGENT);
                wait.set("label", sent.path("label").isMissingNode() ? null : sent.get("label"));
                wait.set("selection", sent.get("selection"));
                wait.put("state", "OPEN");
                wait.put("createdAt", "2026-10-10T10:00:00Z");
                if (sent.has("timeout")) {
                    wait.put("expiresAt", "2026-10-10T12:00:00Z");
                }
                return new FakePlatform.Answer(201, wait.toString(), Map.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        platform.answer("GET", "/projects/api/work/qits-123", """
                {"id":"%s","archetype":"TICKET","qualifiedId":"qits-123","status":"IMPLEMENTING","title":"t"}
                """.formatted(ENTITY));
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}}]}
                """.formatted(PROJECT));
        // The service answers the same shape on the desk door as it does on the agent one.
        platform.route("POST", DESK_WAITS, request -> {
            try {
                JsonNode sent = JSON.readTree(request.body());
                var wait = JSON.createObjectNode();
                wait.put("id", WAIT);
                wait.put("projectId", PROJECT);
                wait.set("label", sent.path("label").isMissingNode() ? null : sent.get("label"));
                wait.set("selection", sent.get("selection"));
                wait.put("state", "OPEN");
                wait.put("createdAt", "2026-10-10T10:00:00Z");
                return new FakePlatform.Answer(201, wait.toString(), Map.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
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
        assertThat(posts.getFirst().contentType()).startsWith("application/json");
        assertThat(posts.getFirst().authorization()).startsWith("Bearer ");
        return JSON.readTree(posts.getFirst().body());
    }

    // --- the generic form ---

    @Test
    void theGenericFormSendsTheSelectionTheTimeoutAndTheLabel() throws Exception {
        Result result = run("wait", "--event", "BuildFailed", "--when", "repoName=qits-ci-service branch^=release/",
                "--when", "triggerEventId? !phase", "--timeout", "2h", "--label", "main to break");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("""
                {"label":"main to break",
                 "selection":{"event":"BuildFailed","when":[
                   [{"path":"repoName","op":"exact","value":"qits-ci-service"},
                    {"path":"branch","op":"prefix","value":"release/"}],
                   [{"path":"triggerEventId","op":"exists","value":true},{"path":"phase","op":"exists","value":false}]]},
                 "timeout":"PT2H"}
                """));
        assertThat(result.out()).contains("Wait " + WAIT + ": OPEN")
                .contains("event    BuildFailed")
                .contains("when     repoName=qits-ci-service branch^=release/")
                .contains("or       triggerEventId? !phase")
                .contains("expires")
                .contains("End your turn now");
    }

    @Test
    void withoutATimeoutOrALabelNeitherIsSent() throws Exception {
        Result result = run("wait", "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode body = sent();
        assertThat(body.has("timeout")).isFalse();
        assertThat(body.has("label")).isFalse();
    }

    @Test
    void withoutAWhenEveryEventOfTheNameFits() throws Exception {
        Result result = run("wait", "--event", "SCMRelease");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("{\"selection\":{\"event\":\"SCMRelease\",\"when\":[]}}"));
    }

    @Test
    void agentOverridesTheEnvironment() {
        platform.answer("POST", "/workspaces/api/agents/" + OTHER_AGENT + "/waits", 201, "{\"id\":\"w\",\"state\":\"OPEN\"}");

        Result result = run("wait", "--agent", OTHER_AGENT, "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("POST", "/workspaces/api/agents/" + OTHER_AGENT + "/waits")).hasSize(1);
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    // --- the desk holder (qits-1618) ---

    @Test
    void deskByIdSendsToTheDeskDoor() {
        Result result = run("wait", "--desk", PROJECT, "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("POST", DESK_WAITS)).hasSize(1);
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void deskAcceptsASlugOrName() {
        assertThat(run("wait", "--desk", "qits", "--event", "BuildFailed", "--when", "repoName=x").exit()).isZero();
        assertThat(platform.requests("POST", DESK_WAITS)).hasSize(1);

        assertThat(run("wait", "--desk", "qits platform", "--event", "BuildFailed", "--when", "repoName=x").exit())
                .isZero();
        assertThat(platform.requests("POST", DESK_WAITS)).hasSize(2);
    }

    @Test
    void deskOverridesTheAgentEnvironment() {
        Result result = run("wait", "--desk", PROJECT, "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void theDeskEnvironmentIsUsedAsGivenWithNoLookup() {
        env.remove("QITS_AGENT_ID");
        env.put("QITS_DESK_PROJECT", PROJECT);

        Result result = run("wait", "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("POST", DESK_WAITS)).hasSize(1);
        assertThat(platform.requests("GET", "/projects/api/projects")).isEmpty();
    }

    @Test
    void agentAndDeskAreMutuallyExclusive() {
        Result result = run("wait", "--agent", AGENT, "--desk", PROJECT, "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("--agent and --desk are mutually exclusive");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void withNoHolderAtAllItNamesAllFour() {
        env.remove("QITS_AGENT_ID");

        Result result = run("wait", "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("--agent").contains("--desk").contains("QITS_AGENT_ID")
                .contains("QITS_DESK_PROJECT");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void listAndCancelUseTheDeskHolderToo() {
        platform.answer("GET", DESK_WAITS, "[]");
        assertThat(run("wait", "--desk", PROJECT, "list").out()).contains("No waits.");

        platform.answer("DELETE", DESK_WAITS + "/" + WAIT, 204, "");
        Result cancel = run("wait", "--desk", PROJECT, "cancel", WAIT);
        assertThat(cancel.exit()).as(cancel.err()).isZero();
        assertThat(platform.requests("DELETE", DESK_WAITS + "/" + WAIT)).hasSize(1);
    }

    @Test
    void jsonPrintsTheServicesAnswer() throws Exception {
        Result result = run("wait", "--event", "BuildFailed", "--when", "repoName=x", "-o", "json");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(JSON.readTree(result.out()).path("id").asText()).isEqualTo(WAIT);
        assertThat(result.out()).doesNotContain("End your turn");
    }

    // --- usage errors: exit 2, and nothing sent ---

    @Test
    void withoutAnAgentItRefusesWithExitTwo() {
        env.remove("QITS_AGENT_ID");

        Result result = run("wait", "--event", "BuildFailed", "--when", "repoName=x");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("QITS_AGENT_ID").contains("--agent");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void usageErrorsSendNothing() {
        assertThat(run("wait").err()).contains("--event").contains("--after").contains("--until");
        assertThat(run("wait", "--event", "BuildFailed", "--when", "a^=\"\"").err()).contains("empty prefix");
        assertThat(run("wait", "--event", "E".repeat(129)).err()).contains("not an event name");
        String[] nine = new String[] {"wait", "--event", "X", "--when", "a=1", "--when", "a=2", "--when", "a=3",
                "--when", "a=4", "--when", "a=5", "--when", "a=6", "--when", "a=7", "--when", "a=8", "--when", "a=9"};
        assertThat(run(nine).err()).contains("the most is 8");
        assertThat(run("wait", "--event", "Build Failed", "--when", "a=b").err()).contains("not an event name");
        assertThat(run("wait", "--event", "BuildFailed", "--when", "a==b").err()).contains("--when: 'a==b'");
        assertThat(run("wait", "--event", "BuildFailed", "--when", "a=b", "--timeout", "soon").err())
                .contains("--timeout");
        assertThat(run("wait", "--event", "BuildFailed", "--when", "a=b", "-o", "yaml").err()).contains("--output");
        for (String[] args : List.of(new String[] {"wait"}, new String[] {"wait", "--event", "BuildFailed", "--when", "1a=b"},
                new String[] {"wait", "--event", "X", "--when", "a==b"},
                new String[] {"wait", "release-request", "4f2a91c0"},
                new String[] {"wait", "ci-run", "not-a-run"},
                new String[] {"wait", "work", "qits-123"},
                new String[] {"wait", "list", "--state", "DONE"})) {
            assertThat(run(args).exit()).as(String.join(" ", args)).isEqualTo(2);
        }
        assertThat(platform.requests).isEmpty();
    }

    // --- timers (qits-2309) ---

    @Test
    void afterSendsATimerWithNoSelection() throws Exception {
        Result result = run("wait", "--after", "2h", "--label", "sweep cancelled runs");

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode body = sent();
        assertThat(body).isEqualTo(JSON.readTree("{\"label\":\"sweep cancelled runs\",\"timeout\":\"PT2H\"}"));
        assertThat(body.has("selection")).isFalse();
        assertThat(result.out()).contains("Wait " + WAIT + ": OPEN").contains("event    timer").contains("expires")
                .doesNotContain("  when ").contains("End your turn now: you get a new turn when the time comes.");
    }

    @Test
    void afterIsHeldToTheCeilingByThePlatformAsTimeoutIs() throws Exception {
        assertThat(run("wait", "--after", "25h").exit()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("{\"timeout\":\"PT25H\"}"));
    }

    @Test
    void untilSendsTheTimeLeftFromNow() throws Exception {
        Result result = run("wait", "--until", "2026-10-10T13:05:00Z", "--label", "03:00 GC run");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("{\"label\":\"03:00 GC run\",\"timeout\":\"PT3H5M\"}"));
    }

    @Test
    void untilTakesAnOffsetAndRoundsUpToWholeSeconds() throws Exception {
        assertThat(WaitCommand.untilTimeout("2026-10-10T14:05:00+01:00", time)).isEqualTo("PT3H5M");
        assertThat(WaitCommand.untilTimeout("2026-10-10T10:00:00.001Z", time)).isEqualTo("PT1S");
        assertThat(WaitCommand.untilTimeout("2026-10-10T10:00:01.5Z", time)).isEqualTo("PT2S");
        assertThat(WaitCommand.untilTimeout(" 2026-10-11T10:00:00Z ", time)).as("exactly 24h").isEqualTo("PT24H");
    }

    @Test
    void untilInThePastOrOverADayAwayIsRefused() {
        Result past = run("wait", "--until", "2026-10-10T09:59:59Z");
        assertThat(past.exit()).isEqualTo(2);
        assertThat(past.err()).contains("--until 2026-10-10T09:59:59Z is not in the future");
        assertThat(run("wait", "--until", "2026-10-10T10:00:00Z").exit()).as("now").isEqualTo(2);
        Result far = run("wait", "--until", "2026-10-11T10:00:01Z");
        assertThat(far.exit()).isEqualTo(2);
        assertThat(far.err()).contains("is more than 24h away");
        Result local = run("wait", "--until", "2026-10-10T12:00:00");
        assertThat(local.exit()).isEqualTo(2);
        assertThat(local.err()).contains("is not an instant").contains("with a zone");
        assertThat(run("wait", "--until", "tomorrow").exit()).isEqualTo(2);
        Result unreadable = run("wait", "--after", "soon");
        assertThat(unreadable.exit()).isEqualTo(2);
        assertThat(unreadable.err()).contains("--after 'soon' is not a duration");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aTimerTakesNoSelectionNoTimeoutAndNotBoth() {
        Result both = run("wait", "--after", "2h", "--until", "2026-10-10T12:00:00Z");
        assertThat(both.exit()).isEqualTo(2);
        assertThat(both.err()).contains("--after and --until are mutually exclusive");
        Result event = run("wait", "--after", "2h", "--event", "BuildFailed");
        assertThat(event.exit()).isEqualTo(2);
        assertThat(event.err()).contains("--after declares a timer, which takes no --event");
        Result when = run("wait", "--until", "2026-10-10T12:00:00Z", "--when", "a=b");
        assertThat(when.exit()).isEqualTo(2);
        assertThat(when.err()).contains("--until declares a timer, which takes no --when");
        Result timeout = run("wait", "--after", "2h", "--timeout", "3h");
        assertThat(timeout.exit()).isEqualTo(2);
        assertThat(timeout.err()).contains("--after declares a timer, which takes no --timeout");
        for (String[] args : List.of(new String[] {"wait", "--until", "2026-10-10T12:00:00Z", "--event", "X"},
                new String[] {"wait", "--after", "2h", "--when", "a=b"},
                new String[] {"wait", "--until", "2026-10-10T12:00:00Z", "--timeout", "1h"})) {
            assertThat(run(args).exit()).as(String.join(" ", args)).isEqualTo(2);
        }
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aTimerBeforeAPresetsNameIsRefusedRatherThanDropped() {
        Result result = run("wait", "--after", "2h", "ci-run", RUN);

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("a preset takes neither");
        assertThat(platform.requests).isEmpty();
    }

    // --- presets ---

    @Test
    void releaseRequestWaitsForEveryFinalState() throws Exception {
        Result result = run("wait", "release-request", REQUEST.toUpperCase());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("""
                {"label":"release request 4f2a91c0 to end",
                 "selection":{"event":"ReleaseRequestStateChanged","when":[
                   [{"path":"releaseRequestId","op":"exact","value":"%1$s"},{"path":"state","op":"exact","value":"FINALIZED"}],
                   [{"path":"releaseRequestId","op":"exact","value":"%1$s"},{"path":"state","op":"exact","value":"REJECTED"}],
                   [{"path":"releaseRequestId","op":"exact","value":"%1$s"},{"path":"state","op":"exact","value":"FAILED"}],
                   [{"path":"releaseRequestId","op":"exact","value":"%1$s"},{"path":"state","op":"exact","value":"WITHDRAWN"}],
                   [{"path":"releaseRequestId","op":"exact","value":"%1$s"},{"path":"state","op":"exact","value":"OBSOLETE"}]]}}
                """.formatted(REQUEST)));
    }

    @Test
    void releaseRequestRefusesTheStartOfAnId() {
        Result result = run("wait", "release-request", "4f2a91c0");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("'4f2a91c0' is neither a release request's id (qits-ci-service-rr-12) nor a "
                + "whole uuid").contains("`qits release-request list` shows the ids.");
        assertThat(run("wait", "release-request", "qits-ci-service-rr-").exit()).isEqualTo(2);
        assertThat(run("wait", "release-request", "qits-ci-service").exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void releaseRequestByItsLogicalIdMatchesTheQualifiedIdTheEventCarries() throws Exception {
        Result result = run("wait", "release-request", " qits-ci-service-rr-12 ");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("""
                {"label":"release request qits-ci-service-rr-12 to end",
                 "selection":{"event":"ReleaseRequestStateChanged","when":[
                   [{"path":"releaseRequestQualifiedId","op":"exact","value":"qits-ci-service-rr-12"},{"path":"state","op":"exact","value":"FINALIZED"}],
                   [{"path":"releaseRequestQualifiedId","op":"exact","value":"qits-ci-service-rr-12"},{"path":"state","op":"exact","value":"REJECTED"}],
                   [{"path":"releaseRequestQualifiedId","op":"exact","value":"qits-ci-service-rr-12"},{"path":"state","op":"exact","value":"FAILED"}],
                   [{"path":"releaseRequestQualifiedId","op":"exact","value":"qits-ci-service-rr-12"},{"path":"state","op":"exact","value":"WITHDRAWN"}],
                   [{"path":"releaseRequestQualifiedId","op":"exact","value":"qits-ci-service-rr-12"},{"path":"state","op":"exact","value":"OBSOLETE"}]]}}
                """));
    }

    @Test
    void ciRunWaitsForEveryTerminalStatus() throws Exception {
        Result result = run("wait", "ci-run", RUN, "--timeout", "PT45M");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent()).isEqualTo(JSON.readTree("""
                {"label":"CI run 0d9e8f7a to end",
                 "selection":{"event":"BuildStatusChanged","when":[
                   [{"path":"runId","op":"exact","value":"%1$s"},{"path":"status","op":"exact","value":"SUCCESS"}],
                   [{"path":"runId","op":"exact","value":"%1$s"},{"path":"status","op":"exact","value":"FAILED"}],
                   [{"path":"runId","op":"exact","value":"%1$s"},{"path":"status","op":"exact","value":"CANCELLED"}],
                   [{"path":"runId","op":"exact","value":"%1$s"},{"path":"status","op":"exact","value":"CONFIG_ERROR"}],
                   [{"path":"runId","op":"exact","value":"%1$s"},{"path":"status","op":"exact","value":"TIMED_OUT"}]]},
                 "timeout":"PT45M"}
                """.formatted(RUN)));
    }

    @Test
    void workResolvesTheQualifiedIdAndWaitsForTheStatus() throws Exception {
        Result result = run("wait", "work", "qits-123", "--status", "verified", "--label", "my ticket");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("GET", "/projects/api/work/qits-123")).hasSize(1);
        assertThat(sent()).isEqualTo(JSON.readTree("""
                {"label":"my ticket",
                 "selection":{"event":"EntityTransitioned","when":[
                   [{"path":"entities[].entityId","op":"exact","value":"%s"},
                    {"path":"entities[].status","op":"exact","value":"VERIFIED"}]]}}
                """.formatted(ENTITY)));
        assertThat(result.out()).contains("entities[].entityId=" + ENTITY + " entities[].status=VERIFIED");
    }

    @Test
    void workGivesALabelOfItsOwn() throws Exception {
        assertThat(run("wait", "work", "qits-123", "--status", "DONE").exit()).isZero();
        assertThat(sent().path("label").asText()).isEqualTo("qits-123 to reach DONE");
    }

    @Test
    void workOnAnItemTheServiceDoesNotKnowIsExitOneAndSendsNoWait() {
        Result result = run("wait", "work", "qits-999", "--status", "DONE");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("404");
        assertThat(platform.requests("POST", WAITS)).isEmpty();
    }

    @Test
    void workWithoutAnAgentAsksTheProjectsServiceNothing() {
        env.remove("QITS_AGENT_ID");

        assertThat(run("wait", "work", "qits-123", "--status", "DONE").exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aTimeoutBeforeThePresetsNameCountsToo() throws Exception {
        assertThat(run("wait", "--timeout", "30m", "ci-run", RUN).exit()).isZero();
        assertThat(sent().path("timeout").asText()).isEqualTo("PT30M");
    }

    // --- list and cancel ---

    @Test
    void listShowsTheWaitsAndPassesTheState() {
        platform.answer("GET", WAITS, """
                [{"id":"%s","agentId":"%s","label":"release \\u001b[31mred\\u001b[0m","state":"OPEN",
                  "selection":{"event":"ReleaseRequestStateChanged","when":[]},
                  "createdAt":"2026-10-10T10:00:00Z","expiresAt":null}]
                """.formatted(WAIT, AGENT));

        Result result = run("wait", "list", "--state", "open");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("GET", WAITS).getFirst().query()).isEqualTo("state=OPEN");
        assertThat(result.out()).contains("ID").contains("STATE").contains(WAIT).contains("ReleaseRequestStateChanged")
                .contains("release red").doesNotContain(String.valueOf(ESC));
    }

    @Test
    void listShowsATimerInTheEventColumn() {
        platform.answer("GET", WAITS, """
                [{"id":"%s","agentId":"%s","label":"sweep cancelled runs","state":"OPEN","selection":null,
                  "subjectKind":"TIMER","createdAt":"2026-10-10T10:00:00Z","expiresAt":"2026-10-10T12:00:00Z"}]
                """.formatted(WAIT, AGENT));

        Result result = run("wait", "list");

        assertThat(result.exit()).as(result.err()).isZero();
        String row = result.out().lines().filter(line -> line.startsWith(WAIT)).findFirst().orElseThrow();
        assertThat(row).contains("OPEN").contains("timer").contains("sweep cancelled runs");
    }

    @Test
    void listReadsAnEnvelopeToo() {
        platform.answer("GET", WAITS, "{\"waits\":[{\"id\":\"" + WAIT + "\",\"state\":\"MATCHED\"}]}");

        Result result = run("wait", "list");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("GET", WAITS).getFirst().query()).isNull();
        assertThat(result.out()).contains(WAIT).contains("MATCHED");
    }

    @Test
    void anEmptyListSaysSo() {
        platform.answer("GET", WAITS, "[]");

        assertThat(run("wait", "list", "--state", "OPEN").out()).contains("No OPEN waits.");
    }

    @Test
    void cancelDeletesTheWait() {
        platform.answer("DELETE", WAITS + "/" + WAIT, 204, "");

        Result result = run("wait", "cancel", WAIT);

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("DELETE", WAITS + "/" + WAIT)).hasSize(1);
        assertThat(result.out()).contains("Cancelled wait " + WAIT + ".");
    }

    @Test
    void cancelPrintsTheWaitWhenTheServiceAnswersIt() {
        platform.answer("DELETE", WAITS + "/" + WAIT, 200, """
                {"id":"%s","state":"CANCELLED","selection":{"event":"X","when":[[{"path":"a","op":"exact","value":"b"}]]},
                 "closedAt":"2026-10-10T10:05:00Z"}
                """.formatted(WAIT));

        Result result = run("wait", "cancel", WAIT);

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).contains("Wait " + WAIT + ": CANCELLED").contains("when     a=b").contains("closed");
    }

    @Test
    void aRefusalIsExitOne() {
        platform.answer("DELETE", WAITS + "/" + WAIT, 403, "{\"message\":\"an agent cancels only its own waits\"}");

        Result result = run("wait", "cancel", WAIT);

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("403").contains("an agent cancels only its own waits");
    }
}
