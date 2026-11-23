package eu.wohlben.qits.cli.access.workspaces;

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

/**
 * `qits agents …` (epic qits-1667) as a person types it, against a fake edge: the door each verb
 * calls, what it prints, how --work finds the agent, and what is refused before anything goes out.
 * The rows of {@link WorkspacesContract} hold the same doors against the provider's recordings.
 */
class AgentCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "00000000-0000-4000-8000-0000000000a1";
    private static final String OTHER = "00000000-0000-4000-8000-0000000000a4";
    private static final String TICKET = "00000000-0000-4000-8000-000000000001";
    private static final String SESSION = "00000000-0000-4000-8000-0000000000d1";
    private static final String API = "/workspaces/api/agents/" + AGENT;
    private static final String PROXY = "/workspaces/agent/" + AGENT;

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
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
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

    private static String agent(String id, String runState) {
        return """
                {"agentId":"%s","workspaceRowId":1,"entityId":"qits-123","entityStatus":"IMPLEMENTING",
                 "state":"ACTIVE","runState":"%s","harness":"CLAUDE","entityTitle":"Invoice totals",
                 "wrapperBranch":"ticket/qits-123","sessionId":null,"waits":[],"attention":null,
                 "lastActivityAt":"2026-10-29T09:00:00Z","createdAt":"2026-10-28T09:00:00Z"}""".formatted(id, runState);
    }

    /** {@link #agent} with its start-up phase fields (qits-2515), {@code fields} as JSON members. */
    private static String phased(String id, String runState, String fields) {
        return "{" + fields + "," + agent(id, runState).substring(1);
    }

    /** qits-123 in the projects service, and the ACTIVE agents qits-workspaces answers for it. */
    private void onTheTicket(String... agents) {
        platform.answer("GET", "/projects/api/work/qits-123",
                "{\"id\":\"" + TICKET + "\",\"qualifiedId\":\"qits-123\",\"archetype\":\"TICKET\"}");
        platform.answer("GET", "/workspaces/api/agents", "{\"agents\":[" + String.join(",", agents) + "]}");
    }

    // --- --work: AgentRef ---

    @Test
    void workNamesTheOneActiveAgentOnTheItem() {
        onTheTicket(agent(AGENT, "RUNNING"));
        platform.answer("POST", API + "/stop", agent(AGENT, "STOPPED"));

        Result stopped = run("agents", "stop", "--work", "qits-123");

        assertThat(stopped.exit()).as(stopped.err()).isZero();
        assertThat(stopped.out()).isEqualTo("Agent " + AGENT + ": STOPPED." + System.lineSeparator());
        assertThat(platform.requests("GET", "/workspaces/api/agents").getFirst().query())
                .isEqualTo("workId=" + TICKET + "&state=ACTIVE");
        assertThat(platform.requests("POST", API + "/stop")).hasSize(1);
    }

    @Test
    void workWithNoActiveAgentIsRefusedWithExitOne() {
        onTheTicket();

        Result none = run("agents", "show", "--work", "qits-123");

        assertThat(none.exit()).isEqualTo(1);
        assertThat(none.err()).contains("No active agent works on qits-123");
        assertThat(platform.requests("GET", API)).isEmpty();
    }

    @Test
    void workWithSeveralActiveAgentsListsThemAndPicksNone() {
        onTheTicket(agent(AGENT, "RUNNING"), agent(OTHER, "YIELDED"));

        Result several = run("agents", "interrupt", "--work", "qits-123");

        assertThat(several.exit()).isEqualTo(1);
        assertThat(several.err()).contains("2 active agents work on qits-123")
                .contains(AGENT + " (slot 1, RUNNING)").contains(OTHER + " (slot 1, YIELDED)");
        assertThat(platform.requests("POST", API + "/interrupt")).isEmpty();
    }

    @Test
    void anAgentAndWorkTogetherOrNeitherIsAUsageError() {
        Result both = run("agents", "show", AGENT, "--work", "qits-123");
        Result neither = run("agents", "show");

        assertThat(both.exit()).isEqualTo(2);
        assertThat(both.err()).contains("not both");
        assertThat(neither.exit()).isEqualTo(2);
        assertThat(neither.err()).contains("Name the agent").contains("--work");
        assertThat(platform.requests).isEmpty();
    }

    // --- the agent ---

    @Test
    void listIsTheAgentsTableWithAttention() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"qits-123","state":"ACTIVE",
                 "runState":"STOPPED","harness":"CLAUDE","entityTitle":"Invoice totals","waits":[],
                 "attention":{"reason":"Blocked 50 min, no @desk comment since"}}]}""".formatted(AGENT));

        Result table = run("agents", "list", "--workspace", "1", "--state", "active");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("ATTENTION").contains("STOPPED").contains("Blocked 50 min");
        assertThat(table.err()).isEmpty();
        assertThat(platform.requests("GET", "/workspaces/api/agents").getFirst().query())
                .isEqualTo("workspaceId=1&state=ACTIVE");
    }

    @Test
    void aReleaseRequestWaitShowsItsQualifiedIdAndState() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"contract-1","state":"ACTIVE",
                 "runState":"RUNNING","harness":"CLAUDE","entityTitle":"Invoice totals",
                 "waits":[{"subjectKind":"RELEASE_REQUEST","subjectRef":"00000000-0000-4000-8000-0000000000c1",
                  "subjectQualifiedId":"qits-projects-service-rr-368","subjectState":"FINALIZED"}],
                 "attention":null}]}
                """.formatted(AGENT));

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("WAITING ON").contains("ATTENTION")
                .contains("qits-projects-service-rr-368 FINALIZED");
    }

    @Test
    void aCiRunWaitShowsTheRunsFirstEightCharactersAndState() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"contract-1","state":"ACTIVE",
                 "runState":"RUNNING","harness":"CLAUDE","entityTitle":"Download button",
                 "waits":[{"subjectKind":"CI_RUN","subjectRef":"9677c59a-0000-4000-8000-0000000000c2",
                  "subjectQualifiedId":null,"subjectState":"RUNNING"}],
                 "attention":null}]}
                """.formatted(AGENT));

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("run 9677c59a RUNNING");
    }

    @Test
    void aWorkItemWaitShowsItsQualifiedIdAndStateOrAQuestionMarkWhenItIsUnknown() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"contract-1","state":"ACTIVE",
                 "runState":"RUNNING","harness":"CLAUDE","entityTitle":"Remember the format",
                 "waits":[{"subjectKind":"WORK_ITEM","subjectRef":"00000000-0000-4000-8000-000000000011",
                  "subjectQualifiedId":"contract-00000001-5","subjectState":null}],
                 "attention":null}]}
                """.formatted(AGENT));

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("contract-00000001-5 ?");
    }

    @Test
    void aCommentsWaitShowsCommentsOnTheItem() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"contract-1","state":"ACTIVE",
                 "runState":"RUNNING","harness":"CLAUDE","entityTitle":"Remember the format",
                 "waits":[{"subjectKind":"COMMENTS","subjectRef":"00000000-0000-4000-8000-000000000011",
                  "subjectQualifiedId":"qits-5","subjectState":null},
                 {"subjectKind":"WORK_ITEM","subjectRef":"00000000-0000-4000-8000-000000000011",
                  "subjectQualifiedId":"qits-5","subjectState":"IMPLEMENTING"}],
                 "attention":null}]}
                """.formatted(AGENT));

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("comments on qits-5; qits-5 IMPLEMENTING");
    }

    @Test
    void anOtherOrUnrecognisedWaitKindShowsItsLabel() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"contract-1","state":"ACTIVE",
                 "runState":"RUNNING","harness":"CLAUDE","entityTitle":"Remember the format",
                 "waits":[{"subjectKind":"OTHER","label":"something else","subjectRef":null,
                  "subjectQualifiedId":null,"subjectState":null},
                 {"subjectKind":"A_FUTURE_KIND","label":"a future kind","subjectRef":null,
                  "subjectQualifiedId":null,"subjectState":null}],
                 "attention":null}]}
                """.formatted(AGENT));

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("something else; a future kind");
    }

    @Test
    void waitsAbsentFallsBackToTodaysWaitingOn() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[{"agentId":"%s","workspaceRowId":1,"entityId":"contract-1","state":"ACTIVE",
                 "runState":"RUNNING","harness":"CLAUDE","entityTitle":"PDF export",
                 "waitingOn":"CI run 00000000 to end"}]}
                """.formatted(AGENT));

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("CI run 00000000 to end");
    }

    @Test
    void theAttentionColumnShowsTheReasonOrADash() {
        platform.answer("GET", "/workspaces/api/agents", """
                {"agents":[
                 {"agentId":"00000000-0000-4000-8000-0000000000a6","workspaceRowId":1,"entityId":"contract-1",
                  "state":"ACTIVE","runState":"RUNNING","harness":"CLAUDE","entityTitle":"Export invoices",
                  "waits":[],"attention":{"code":"BLOCKED_NO_DESK",
                   "reason":"Blocked 50 min by contract-person, no @desk comment since","since":null,"waitId":null}},
                 {"agentId":"00000000-0000-4000-8000-0000000000a7","workspaceRowId":1,"entityId":"contract-2",
                  "state":"ACTIVE","runState":"RUNNING","harness":"CLAUDE","entityTitle":"PDF export",
                  "waits":[],"attention":null}]}
                """);

        Result table = run("agents", "list");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("Blocked 50 min by contract-person, no @desk comment since");
        List<String> lines = table.out().lines().toList();
        String noAttention = lines.stream().filter(l -> l.contains("0000000000a7")).findFirst().orElseThrow();
        assertThat(noAttention).endsWith("-");
    }

    @Test
    void listSendsTheProjectsUuidWhenGivenAndResolvesASlugFirst() throws Exception {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"11111111-1111-4111-8111-111111111111","slug":"qits","name":"qits"}}]}
                """);
        platform.answer("GET", "/workspaces/api/agents", "{\"agents\":[]}");

        Result named = run("agents", "list", "--project", "qits", "--state", "ACTIVE");

        assertThat(named.exit()).as(named.err()).isZero();
        assertThat(platform.requests("GET", "/workspaces/api/agents").getFirst().query())
                .isEqualTo("state=ACTIVE&projectId=11111111-1111-4111-8111-111111111111");

        Result byUuid = run("agents", "list", "--project", "11111111-1111-4111-8111-111111111111");
        assertThat(byUuid.exit()).as(byUuid.err()).isZero();
        assertThat(platform.requests("GET", "/workspaces/api/agents").getLast().query())
                .isEqualTo("projectId=11111111-1111-4111-8111-111111111111");
    }

    @Test
    void showPrintsTheAgentAndJsonIsTheAnswer() throws Exception {
        platform.answer("GET", API, agent(AGENT, "RUNNING"));

        Result shown = run("agents", "show", AGENT);
        Result json = run("agents", "show", AGENT, "-o", "json");

        assertThat(shown.exit()).as(shown.err()).isZero();
        assertThat(shown.out()).contains("Agent " + AGENT).contains("qits-123 (IMPLEMENTING)")
                .contains("ticket/qits-123").containsPattern("run\\s+RUNNING");
        assertThat(JSON.readTree(json.out()).path("runState").asText()).isEqualTo("RUNNING");
    }

    @Test
    void anAgentNotReadyYetShowsItsPhaseNotRunning() {
        String queued = phased(AGENT, "RUNNING", """
                "phase":"QUEUED","phaseSince":"2026-10-29T09:30:00Z",
                "phaseDetail":"no target has memory for a 6g WORKSPACE lease\"""");
        platform.answer("GET", "/workspaces/api/agents", "{\"agents\":[" + queued + "]}");
        platform.answer("GET", API, queued);

        Result table = run("agents", "list");
        Result shown = run("agents", "show", AGENT);

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).containsPattern("ACTIVE\\s+QUEUED\\s+CLAUDE").doesNotContain("RUNNING");
        assertThat(shown.exit()).as(shown.err()).isZero();
        assertThat(shown.out()).containsPattern("run\\s+QUEUED").doesNotContain("RUNNING")
                .containsPattern("phase\\s+QUEUED since \\S+")
                .containsPattern("waiting for\\s+no target has memory for a 6g WORKSPACE lease");
        assertThat(shown.out().indexOf("phase")).isBetween(shown.out().indexOf("run "),
                shown.out().indexOf("harness"));
    }

    @Test
    void aReadyAgentOrAnOlderServicesAnswerShowsTheRunState() {
        platform.answer("GET", "/workspaces/api/agents", "{\"agents\":["
                + phased(AGENT, "RUNNING", "\"phase\":\"READY\",\"phaseSince\":null,\"phaseDetail\":null") + ","
                + agent(OTHER, "YIELDED") + "]}");
        platform.answer("GET", API, agent(AGENT, "STOPPED"));

        Result table = run("agents", "list");
        Result shown = run("agents", "show", AGENT);

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).containsPattern("ACTIVE\\s+RUNNING\\s+CLAUDE")
                .containsPattern("ACTIVE\\s+YIELDED\\s+CLAUDE");
        assertThat(shown.out()).containsPattern("run\\s+STOPPED").containsPattern("phase\\s+-")
                .containsPattern("waiting for\\s+-");
    }

    @Test
    void aProvisioningAgentFitsTheRunColumn() {
        platform.answer("GET", "/workspaces/api/agents", "{\"agents\":["
                + phased(AGENT, "RUNNING", "\"phase\":\"PROVISIONING\"") + "]}");

        Result table = run("agents", "list");

        assertThat(table.out()).containsPattern("ACTIVE\\s+PROVISIONING\\s+CLAUDE");
    }

    @Test
    void anUnknownAgentIsTheServicesNotFound() {
        platform.answer("GET", API, 404, "{\"message\":\"Agent not found: " + AGENT + "\"}");

        Result missing = run("agents", "show", AGENT);

        assertThat(missing.exit()).isEqualTo(1);
        assertThat(missing.err()).contains("Agent not found");
    }

    @Test
    void promptSendsTheTextToTheAgentsOwnDoor() throws Exception {
        platform.answer("POST", API + "/prompt", """
                {"agentId":"%s","delivered":true,"launched":false,"resumed":false,"detail":"Queued."}"""
                .formatted(AGENT));

        Result prompted = run("agents", "prompt", AGENT, "Check the rounding first.");

        assertThat(prompted.exit()).as(prompted.err()).isZero();
        assertThat(prompted.out()).contains("Queued for agent " + AGENT);
        JsonNode sent = JSON.readTree(platform.requests("POST", API + "/prompt").getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"text\":\"Check the rounding first.\",\"compactFirst\":false}"));
    }

    @Test
    void promptByWorkTakesTheOnePositionalAsTheTextAndCompactFirst() throws Exception {
        onTheTicket(agent(AGENT, "STOPPED"));
        platform.answer("POST", API + "/prompt", """
                {"agentId":"%s","delivered":false,"launched":false,"resumed":true,"detail":"Starts again."}"""
                .formatted(AGENT));

        Result prompted = run("agents", "prompt", "--work", "qits-123", "--compact-first", "Go on.");

        assertThat(prompted.exit()).as(prompted.err()).isZero();
        assertThat(prompted.out()).contains("Agent " + AGENT + " resumes to hear it.").contains("Starts again.");
        JsonNode sent = JSON.readTree(platform.requests("POST", API + "/prompt").getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"text\":\"Go on.\",\"compactFirst\":true}"));
    }

    @Test
    void anEmptyPromptIsRefusedBeforeAnythingGoesOut() {
        Result empty = run("agents", "prompt", AGENT, "  ");
        Result missing = run("agents", "prompt", AGENT);

        assertThat(empty.exit()).isEqualTo(2);
        assertThat(missing.exit()).isEqualTo(2);
        assertThat(empty.err()).contains("The prompt is empty");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void interruptSaysWhatTheDaemonDid() {
        platform.answer("POST", API + "/interrupt",
                "{\"agentId\":\"" + AGENT + "\",\"interrupted\":true,\"detail\":\"ESC was sent.\"}");

        Result interrupted = run("agents", "interrupt", AGENT);

        assertThat(interrupted.exit()).as(interrupted.err()).isZero();
        assertThat(interrupted.out()).contains("Agent " + AGENT + ": interrupted.").contains("ESC was sent.");
        assertThat(platform.requests("POST", API + "/interrupt").getFirst().body()).isEmpty();
    }

    @Test
    void stopRestartAndYieldPostNoBodyAndPrintTheRunState() {
        platform.answer("POST", API + "/stop", agent(AGENT, "STOPPED"));
        platform.answer("POST", API + "/restart", agent(AGENT, "YIELDED"));
        platform.answer("POST", API + "/yield", agent(AGENT, "YIELDED"));

        for (String[] verb : new String[][] {{"stop", "STOPPED"}, {"restart", "YIELDED"}, {"yield", "YIELDED"}}) {
            Result done = run("agents", verb[0], AGENT);
            assertThat(done.exit()).as(done.err()).isZero();
            assertThat(done.out()).contains("Agent " + AGENT + ": " + verb[1] + ".");
            assertThat(platform.requests("POST", API + "/" + verb[0])).singleElement()
                    .satisfies(request -> assertThat(request.body()).isEmpty());
        }
    }

    @Test
    void aControlTheCredentialMayNotUseSaysSoInTheServicesWords() {
        platform.answer("POST", API + "/stop", 403, "{\"message\":\"a desk of another project\"}");

        Result refused = run("agents", "stop", AGENT);

        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("HTTP 403").contains("a desk of another project");
    }

    @Test
    void sessionsAreATableAndATranscriptReadsTurnByTurn() {
        platform.answer("GET", API + "/sessions", """
                {"sessions":[{"sessionId":"%s","startedAt":"2026-10-28T09:00:00Z","endedAt":"2026-10-28T10:00:00Z",
                 "messageCount":2,"subagents":[{"agentId":"x"}]}]}""".formatted(SESSION));
        platform.answer("GET", API + "/sessions/" + SESSION + "/transcript", """
                {"lines":["{\\"type\\":\\"user\\",\\"message\\":{\\"content\\":\\"Fix the rounding.\\"}}",
                 "{\\"type\\":\\"assistant\\",\\"message\\":{\\"content\\":\\"Done.\\"}}"]}""");

        Result sessions = run("agents", "sessions", AGENT);
        Result transcript = run("agents", "transcript", AGENT, SESSION);

        assertThat(sessions.exit()).as(sessions.err()).isZero();
        assertThat(sessions.out()).contains("SESSION").contains("MESSAGES").containsPattern(SESSION + ".*\\s2\\s+1");
        assertThat(transcript.exit()).as(transcript.err()).isZero();
        assertThat(transcript.out()).isEqualTo(String.join(System.lineSeparator(),
                "user: Fix the rounding.", "assistant: Done.", ""));
    }

    @Test
    void aTranscriptByWorkTakesTheOnePositionalAsTheSession() {
        onTheTicket(agent(AGENT, "RUNNING"));
        platform.answer("GET", API + "/sessions/" + SESSION + "/transcript", "{\"lines\":[]}");

        Result transcript = run("agents", "transcript", "--work", "qits-123", SESSION);

        assertThat(transcript.exit()).as(transcript.err()).isZero();
        assertThat(transcript.out()).contains("The conversation is empty.");
    }

    @Test
    void discardAndTheDraftAreTheAgentsDoors() throws Exception {
        platform.answer("POST", API + "/discard", "{\"agentId\":\"" + AGENT + "\",\"state\":\"REMOVED\"}");
        platform.answer("PUT", API + "/prompt-draft", "{\"draft\":{\"updatedAt\":\"2026-10-29T09:00:00Z\"}}");
        platform.answer("DELETE", API + "/prompt-draft", 204, "");

        Result discarded = run("agents", "discard", AGENT, "--force");
        Result saved = run("agents", "draft", "save", AGENT, "Check the rounding.");
        Result dropped = run("agents", "draft", "discard", AGENT);

        assertThat(discarded.out()).contains("Agent " + AGENT + ": REMOVED.");
        assertThat(JSON.readTree(platform.requests("POST", API + "/discard").getFirst().body()))
                .isEqualTo(JSON.readTree("{\"force\":true}"));
        assertThat(saved.exit()).as(saved.err()).isZero();
        assertThat(JSON.readTree(platform.requests("PUT", API + "/prompt-draft").getFirst().body()).path("serializedPrompt")
                .asText()).isEqualTo("Check the rounding.");
        assertThat(dropped.out()).contains("Discarded the draft of agent " + AGENT);
    }

    @Test
    void anAgentWithNoDraftSaysSo() {
        platform.answer("GET", API + "/prompt-draft", 404, "");

        Result none = run("agents", "draft", "show", AGENT);

        assertThat(none.exit()).as(none.err()).isZero();
        assertThat(none.out()).contains("has no saved draft");
    }

    // --- its daemon, through the agent proxy ---

    @Test
    void chatReadsTheTranscriptThroughTheAgentProxy() {
        platform.answer("GET", PROXY + "/log", """
                {"lines":[
                 {"sequence":2,"content":"{\\"type\\":\\"assistant\\",\\"message\\":{\\"content\\":[{\\"type\\":\\"text\\",\\"text\\":\\"On it.\\"}]}}"},
                 {"sequence":1,"content":"{\\"type\\":\\"user\\",\\"message\\":{\\"content\\":\\"Fix the login\\"}}"}]}
                """);

        Result chat = run("agents", "chat", AGENT);

        assertThat(chat.exit()).as(chat.err()).isZero();
        assertThat(chat.out()).isEqualTo(String.join(System.lineSeparator(),
                "user: Fix the login", "assistant: On it.", ""));
        assertThat(platform.requests("GET", PROXY + "/log").getFirst().query()).isEqualTo("channel=TRANSCRIPT");
        assertThat(platform.requests.stream().map(FakePlatform.Request::path))
                .noneMatch(path -> path.startsWith("/workspaces/container/"));
    }

    @Test
    void filesGoThroughTheAgentProxyWithoutASlot() {
        platform.answer("GET", PROXY + "/files", "{\"paths\":[\"README.md\"],\"lazyDirs\":[{\"path\":\"node_modules\","
                + "\"childCount\":3}]}");
        platform.answer("GET", PROXY + "/files/content", "{\"binary\":false,\"content\":\"# Title\\nline\"}");
        platform.answer("GET", PROXY + "/detection", "{\"frameworks\":[{\"frameworkId\":\"maven\",\"root\":\".\","
                + "\"label\":\"Maven\",\"memberPaths\":[\"pom.xml\"]}],\"links\":[]}");

        Result list = run("agents", "files", "list", AGENT, "--path", "src");
        Result read = run("agents", "files", "read", AGENT, "README.md");
        Result detect = run("agents", "files", "detect", AGENT);

        assertThat(list.out()).contains("README.md").contains("node_modules/  (3 entries");
        assertThat(platform.requests("GET", PROXY + "/files").getFirst().query()).isEqualTo("path=src");
        assertThat(read.out()).isEqualTo(String.join(System.lineSeparator(), "# Title", "line", ""));
        assertThat(platform.requests("GET", PROXY + "/files/content").getFirst().query()).isEqualTo("path=README.md");
        assertThat(detect.out()).contains("FRAMEWORK").contains("maven");
    }

    @Test
    void filesReadByWorkTakesTheOnePositionalAsThePath() {
        onTheTicket(agent(AGENT, "RUNNING"));
        platform.answer("GET", PROXY + "/files/content", "{\"binary\":true,\"content\":\"\"}");

        Result read = run("agents", "files", "read", "--work", "qits-123", "logo.png");

        assertThat(read.exit()).as(read.err()).isZero();
        assertThat(read.out()).contains("logo.png is binary");
    }

    // --- qits workspaces show ---

    @Test
    void workspacesShowListsTheSlotsAgents() {
        platform.answer("GET", "/workspaces/api/workspaces/1", "{\"workspace\":{\"id\":1,\"workspaceId\":\"ws-1\","
                + "\"runtimeStatus\":\"RUNNING\",\"placement\":\"TARGET\",\"admin\":false}}");
        platform.answer("GET", "/workspaces/api/workspaces/1/agents", "{\"agents\":[" + agent(AGENT, "RUNNING") + "]}");

        Result shown = run("workspaces", "show", "1");

        assertThat(shown.exit()).as(shown.err()).isZero();
        assertThat(shown.out()).contains("Workspace slot 1").contains("RUNNING").contains("AGENT")
                .contains(AGENT).contains("Invoice totals");
    }

    @Test
    void workspacesShowLeavesTheAgentsOutWhereTheServiceHasNoDoorForThem() throws Exception {
        platform.answer("GET", "/workspaces/api/workspaces/1", "{\"workspace\":{\"id\":1,\"runtimeStatus\":\"RUNNING\"}}");

        Result shown = run("workspaces", "show", "1");
        Result json = run("workspaces", "show", "1", "-o", "json");

        assertThat(shown.exit()).as(shown.err()).isZero();
        assertThat(shown.out()).contains("Workspace slot 1").doesNotContain("AGENT");
        assertThat(JSON.readTree(json.out()).path("workspace").path("id").asInt()).isEqualTo(1);
        assertThat(platform.requests("GET", "/workspaces/api/workspaces/1/agents")).as("json is the slot alone")
                .hasSize(1);
    }

    @Test
    void theNewVerbsSayNothingOnStderr() {
        platform.answer("GET", "/workspaces/api/agents", "{\"agents\":[]}");

        Result list = run("agents", "list");

        assertThat(list.exit()).as(list.err()).isZero();
        assertThat(list.out()).contains("No agents.");
        assertThat(list.err()).isEmpty();
    }

    @Test
    void theClaudeCommandsAreStillThere() {
        assertThat(run("agents").exit()).isEqualTo(2);
        assertThat(run("agents", "claude").exit()).isEqualTo(2);
        assertThat(run("agents", "files").exit()).isEqualTo(2);
        assertThat(run("agents", "draft").exit()).isEqualTo(2);
    }
}
