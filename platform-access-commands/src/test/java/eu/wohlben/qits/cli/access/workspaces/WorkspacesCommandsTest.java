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
 * `qits workspaces …` as a person types it, against a fake edge. Which request each command makes
 * is checked against the landing app's calls by qits-ci's {@code contracts.mirror} (qits-2465),
 * not here; this is what the commands print, and what they refuse before anything goes out.
 */
class WorkspacesCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROCESS = "00000000-0000-4000-8000-000000000100";
    private static final String REPOSITORY = "0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a";

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

    @Test
    void theSlotsAreATableForAPersonAndTheServicesAnswerInJson() throws Exception {
        platform.answer("GET", "/workspaces/api/workspaces", """
                {"entries":[{"workspace":{"id":1,"workspaceId":"ws-contract","runtimeStatus":"RUNNING",
                 "placement":"TARGET","admin":false,"queuedAt":null}}]}
                """);

        Result table = run("workspaces", "list", "--repository", REPOSITORY);
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("SLOT").contains("CONTAINER")
                .containsPattern("1\\s+ws-contract\\s+RUNNING\\s+TARGET\\s+false");
        assertThat(platform.requests("GET", "/workspaces/api/workspaces").getFirst().query())
                .isEqualTo("repositoryId=" + REPOSITORY);

        Result json = run("workspaces", "list", "--repository", REPOSITORY, "-o", "json");
        assertThat(JSON.readTree(json.out()).path("entries").get(0).path("workspace").path("workspaceId").asText())
                .isEqualTo("ws-contract");
    }

    @Test
    void listResolvesARepositoryNameWithProjectAndSendsItsUuid() throws Exception {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"11111111-1111-4111-8111-111111111111","slug":"qits","name":"qits"}}]}
                """);
        platform.answer("GET", "/projects/api/projects/11111111-1111-4111-8111-111111111111/repositories", """
                {"entries":[{"repository":{"id":"%s","name":"qits-qits"}}]}
                """.formatted(REPOSITORY));
        platform.answer("GET", "/workspaces/api/workspaces", "{\"entries\":[]}");

        Result named = run("workspaces", "list", "--project", "qits", "--repository", "qits-qits");

        assertThat(named.exit()).as(named.err()).isZero();
        assertThat(platform.requests("GET", "/workspaces/api/workspaces").getFirst().query())
                .isEqualTo("repositoryId=" + REPOSITORY);
    }

    @Test
    void listRefusesAMissingRepository() {
        Result missing = run("workspaces", "list");

        assertThat(missing.exit()).isEqualTo(2);
        assertThat(missing.err()).contains("--repository");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void listRefusesANamedRepositoryWithoutAProject() {
        Result named = run("workspaces", "list", "--repository", "qits-qits");

        assertThat(named.exit()).isEqualTo(2);
        assertThat(named.err()).contains("--project");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aStartNamesTheProcessAndTheLogFollowsItUntilItEnds() {
        platform.answer("POST", "/workspaces/api/workspaces/1/ensure-container",
                "{\"technicalProcessId\":\"" + PROCESS + "\"}");
        Result started = run("workspaces", "start", "1");
        assertThat(started.out()).contains("started process " + PROCESS)
                .contains("qits workspaces log " + PROCESS);

        String events = "/workspaces/api/technical-processes/" + PROCESS + "/events";
        platform.streamAt(events);
        platform.streams.add(platform.stream(false,
                "data:{\"segment\":\"pull\",\"kind\":\"segment-open\",\"seq\":0}", "",
                "data:{\"segment\":\"pull\",\"kind\":\"line\",\"seq\":1,\"line\":\"pulling \\u001b[31mimage\"}", "",
                "data:{\"kind\":\"ping\",\"seq\":2}", "",
                "data:{\"segment\":\"pull\",\"kind\":\"segment-settled\",\"seq\":3,\"status\":\"ok\"}", "",
                "data:{\"kind\":\"done\",\"seq\":4,\"status\":\"ok\"}", ""));

        Result log = run("workspaces", "log", PROCESS);

        assertThat(log.exit()).as(log.err()).isZero();
        assertThat(log.out()).isEqualTo(String.join(System.lineSeparator(),
                "== pull", "pull  pulling image", "== pull: ok", "== done: ok", ""));
        assertThat(platform.requests("GET", events).getFirst().accept()).isEqualTo("text/event-stream");
    }

    @Test
    void theLogInJsonIsOneFramePerLineWithoutThePings() throws Exception {
        String events = "/workspaces/api/technical-processes/" + PROCESS + "/events";
        platform.streamAt(events);
        platform.streams.add(platform.stream(false,
                "data:{\"kind\":\"ping\",\"seq\":0}", "",
                "data:{\"segment\":\"pull\",\"kind\":\"line\",\"seq\":1,\"line\":\"x\"}", ""));

        Result log = run("workspaces", "log", PROCESS, "-o", "json");

        assertThat(log.exit()).as(log.err()).isZero();
        List<String> lines = log.out().lines().toList();
        assertThat(lines).hasSize(1);
        assertThat(JSON.readTree(lines.getFirst()).path("line").asText()).isEqualTo("x");
    }

    @Test
    void whatCannotBeSentIsRefusedBeforeAnythingGoesOut() {
        assertThat(run("workspaces", "agents", "sign-in", "open", "--workspace", "1", "--harness", "GPT").exit())
                .isEqualTo(2);
        assertThat(run("workspaces", "show", "0").exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aGroupAloneAsksForACommand() {
        assertThat(run("workspaces").exit()).isEqualTo(2);
        assertThat(run("workspaces", "agents").exit()).isEqualTo(2);
    }

    @Test
    void thereIsNoRunnersCommandAnymore() {
        Result result = run("workspaces", "runners", "list");
        assertThat(result.exit()).isEqualTo(2);
    }
}
