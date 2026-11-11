package eu.wohlben.qits.cli.access.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code qits docker-credential} as Docker runs it, through docker-credential-qits. */
class DockerCredentialCommandTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
    private static final String REGISTRY = "registry.qits.example.test";
    private static final String ACCESS = FakeIdp.SECRET + "access-0";
    private static final String NOT_FOUND = "credentials not found in native keychain\n";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private SessionFile sessionFile;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("QITS_ARTIFACTS_URL", "https://" + REGISTRY);
        sessionFile = new SessionFile(home.resolve(".config/qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
        // stdout carries a token by design, for Docker; stderr never does.
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private void signIn(Duration accessLeft) throws Exception {
        sessionFile.write(new Session(idp.url(), "qits-cli", ACCESS, T0.plus(accessLeft), idp.issueRefreshToken(),
                T0.plus(Duration.ofDays(30))));
    }

    private Result run(String action, String stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, "docker-credential", action);
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void getAnswersTheRegistryWithTheSessionsToken() throws Exception {
        signIn(Duration.ofMinutes(10));

        Result result = run("get", REGISTRY + "\n");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEmpty();
        JsonNode answer = json(result.out());
        assertThat(answer.path("ServerURL").asText()).isEqualTo(REGISTRY);
        assertThat(answer.path("Username").asText()).isEqualTo("oauth2");
        assertThat(answer.path("Secret").asText()).isEqualTo(ACCESS);
        assertThat(idp.grants("refresh_token")).isZero();
    }

    @Test
    void getTakesTheAddressWithASchemeAndHandsItBackAsGiven() throws Exception {
        signIn(Duration.ofMinutes(10));

        Result result = run("get", "https://Registry.qits.example.test");

        assertThat(result.exit()).isZero();
        assertThat(json(result.out()).path("ServerURL").asText()).isEqualTo("https://Registry.qits.example.test");
        assertThat(json(result.out()).path("Secret").asText()).isEqualTo(ACCESS);
    }

    @Test
    void getRefreshesATokenWithLessThanFiveMinutesLeft() throws Exception {
        signIn(Duration.ofMinutes(3));

        Result result = run("get", REGISTRY);

        assertThat(result.exit()).isZero();
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        String secret = json(result.out()).path("Secret").asText();
        assertThat(secret).startsWith(FakeIdp.SECRET + "access-").isNotEqualTo(ACCESS);
        assertThat(sessionFile.read().orElseThrow().accessToken()).isEqualTo(secret);
    }

    @Test
    void aRefreshThatFailsSaysWhyAndAnswersNotFound() throws Exception {
        signIn(Duration.ofMinutes(3));
        idp.failNext(503, 503, 503, 503);

        Result result = run("get", REGISTRY);

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(NOT_FOUND);
        assertThat(result.err()).isNotBlank();
    }

    @Test
    void anotherRegistryIsNotAnsweredAndTheSessionIsNotTouched() throws Exception {
        signIn(Duration.ofMinutes(3));

        for (String other : new String[] {"https://index.docker.io/v1/", "ghcr.io", "registry.qits.example.test:5000"}) {
            Result result = run("get", other);

            assertThat(result).as(other).isEqualTo(new Result(1, NOT_FOUND, ""));
        }
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void withoutASessionGetAnswersNotFound() {
        Result result = run("get", REGISTRY);

        assertThat(result).isEqualTo(new Result(1, NOT_FOUND, ""));
        assertThat(home.resolve(".config/qits")).doesNotExist();
    }

    @Test
    void theTokenHomeAndInPlatformAnswerNotFound() throws Exception {
        signIn(Duration.ofMinutes(10));

        env.put("QITS_TOKEN", FakeIdp.SECRET + "workspace");
        assertThat(run("get", REGISTRY)).isEqualTo(new Result(1, NOT_FOUND, ""));

        env.remove("QITS_TOKEN");
        env.put(Mode.OVERRIDE, "true");
        assertThat(run("get", REGISTRY)).isEqualTo(new Result(1, NOT_FOUND, ""));
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void storeEraseAndListChangeNothing() throws Exception {
        signIn(Duration.ofMinutes(10));
        String stored = "{\"ServerURL\":\"" + REGISTRY + "\",\"Username\":\"oauth2\",\"Secret\":\"" + FakeIdp.SECRET
                + "x\"}";
        byte[] before = java.nio.file.Files.readAllBytes(sessionFile.path());

        assertThat(run("store", stored)).isEqualTo(new Result(0, "", ""));
        assertThat(run("erase", REGISTRY)).isEqualTo(new Result(0, "", ""));
        assertThat(run("list", "")).isEqualTo(new Result(0, "{}\n", ""));
        assertThat(java.nio.file.Files.readAllBytes(sessionFile.path())).isEqualTo(before);
    }

    @Test
    void anUnknownActionIsAUsageError() {
        assertThat(run("forget", "").exit()).isEqualTo(2);
    }
}
