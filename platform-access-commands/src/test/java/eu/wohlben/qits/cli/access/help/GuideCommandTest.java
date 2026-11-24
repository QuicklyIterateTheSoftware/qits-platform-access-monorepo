package eu.wohlben.qits.cli.access.help;

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

/** {@code qits help guide} (qits-1679): which variant it asks for, and what it prints. */
class GuideCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");
    private static final String GUIDE = "/projects/api/guide";

    @TempDir
    Path home;

    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        env.put("QITS_URL_PROJECTS", platform.url());
        env.put("QITS_DOMAIN", "wohlben.eu");
        platform.route("GET", GUIDE, request -> {
            String variant = request.query() == null || !request.query().startsWith("for=")
                    ? "agent" : request.query().substring("for=".length());
            return new FakePlatform.Answer(200, """
                    {"variant":"%s","detected":%s,"markdown":"# Development guide: %s\\n\\nText."}
                    """.formatted(variant, request.query() == null, variant), Map.of());
        });
        platform.answer("GET", GUIDE + "/variants", """
                {"variants":[{"name":"workstation","description":"A person."},
                             {"name":"agent","description":"A workspace agent."}]}
                """);
    }

    @AfterEach
    void stop() {
        platform.close();
    }

    private void signedIn() throws Exception {
        new SessionFile(home.resolve("qits")).write(new Session(platform.url() + "/idp", "qits-cli",
                FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)), FakeIdp.SECRET + "refresh-0",
                T0.plus(Duration.ofDays(30))));
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
    void aWorkstationSessionAsksForTheWorkstationGuide() throws Exception {
        signedIn();

        Result r = run("help", "guide");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("# Development guide: workstation\n\nText.\n");
        assertThat(platform.requests("GET", GUIDE)).singleElement()
                .satisfies(request -> assertThat(request.query()).isEqualTo("for=workstation"));
    }

    @Test
    void anAgentTokenLetsThePlatformPickFromTheToken() {
        env.put("QITS_TOKEN", "SECRET-agent-token");

        Result r = run("help", "guide");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("# Development guide: agent");
        assertThat(platform.requests("GET", GUIDE)).singleElement().satisfies(request -> {
            assertThat(request.query()).isNull();
            assertThat(request.authorization()).isEqualTo("Bearer SECRET-agent-token");
        });
    }

    @Test
    void aDeskTokenIsJudgedByTheServiceToo() {
        // The desk's token is opaque like any workspace token: the CLI names no variant, and the
        // service answers the desk's from the token's qits:desk role.
        env.put("QITS_TOKEN", "SECRET-desk-token");
        platform.route("GET", GUIDE, request -> new FakePlatform.Answer(200,
                "{\"variant\":\"desk\",\"detected\":true,\"markdown\":\"# Development guide: delivery desk\"}",
                Map.of()));

        Result r = run("help", "guide");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("# Development guide: delivery desk\n");
        assertThat(platform.requests("GET", GUIDE).getFirst().query()).isNull();
    }

    @Test
    void forOverridesTheContext() throws Exception {
        signedIn();

        Result r = run("help", "guide", "--for", "Desk");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("# Development guide: desk");
        assertThat(platform.requests("GET", GUIDE).getFirst().query()).isEqualTo("for=desk");
    }

    @Test
    void forAsksForTheProjectAgentsVariant() {
        env.put("QITS_TOKEN", "SECRET-agent-token");

        Result r = run("help", "guide", "--for", "project-agent");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", GUIDE).getFirst().query()).isEqualTo("for=project-agent");
    }

    @Test
    void forAlsoOverridesATokenHome() {
        env.put("QITS_TOKEN", "SECRET-agent-token");

        Result r = run("help", "guide", "--for", "workstation");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", GUIDE).getFirst().query()).isEqualTo("for=workstation");
    }

    @Test
    void anUnknownVariantIsAUsageErrorAndCallsNothing() throws Exception {
        signedIn();

        Result r = run("help", "guide", "--for", "kiosk");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--for must be one of workstation, agent, project-agent, desk, refinement");
        assertThat(platform.requests("GET", GUIDE)).isEmpty();
    }

    @Test
    void listPrintsTheVariants() throws Exception {
        signedIn();

        Result r = run("help", "guide", "--list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("VARIANT").contains("workstation").contains("A person.")
                .contains("agent").contains("A workspace agent.");
        assertThat(platform.requests("GET", GUIDE)).isEmpty();
    }

    @Test
    void aHashLeadsTheOutputBeforeTheMarkdown() {
        env.put("QITS_TOKEN", "SECRET-agent-token");
        platform.route("GET", GUIDE, request -> new FakePlatform.Answer(200,
                "{\"variant\":\"agent\",\"detected\":true,\"markdown\":\"# Guide\\n\\nText.\","
                        + "\"hash\":\"abcdef0123456789\"}",
                Map.of()));

        Result r = run("help", "guide");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("guide-hash: abcdef0123456789\n# Guide\n\nText.\n");
    }

    @Test
    void hashOnlyPrintsOnlyTheHashLineAndItMatchesTheNormalOutputsFirstLine() {
        env.put("QITS_TOKEN", "SECRET-agent-token");
        platform.route("GET", GUIDE, request -> new FakePlatform.Answer(200,
                "{\"variant\":\"agent\",\"detected\":true,\"markdown\":\"# Guide\\n\\nText.\","
                        + "\"hash\":\"abcdef0123456789\"}",
                Map.of()));

        Result normal = run("help", "guide");
        Result hashOnly = run("help", "guide", "--hash-only");

        assertThat(hashOnly.exit()).as(hashOnly.err()).isZero();
        assertThat(hashOnly.out()).isEqualTo("guide-hash: abcdef0123456789\n");
        assertThat(normal.out().lines().findFirst()).contains(hashOnly.out().strip());
    }

    @Test
    void withNoHashTheOutputIsMarkdownAlone() {
        env.put("QITS_TOKEN", "SECRET-agent-token");
        platform.route("GET", GUIDE, request -> new FakePlatform.Answer(200,
                "{\"variant\":\"agent\",\"detected\":true,\"markdown\":\"# Guide\\n\\nText.\"}", Map.of()));

        Result r = run("help", "guide");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("# Guide\n\nText.\n");
    }

    @Test
    void hashOnlyWithNoHashRefuses() {
        env.put("QITS_TOKEN", "SECRET-agent-token");
        platform.route("GET", GUIDE, request -> new FakePlatform.Answer(200,
                "{\"variant\":\"agent\",\"detected\":true,\"markdown\":\"# Guide\\n\\nText.\"}", Map.of()));

        Result r = run("help", "guide", "--hash-only");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("no guide-hash");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void hashOnlyWithListIsAUsageError() {
        env.put("QITS_TOKEN", "SECRET-agent-token");

        Result r = run("help", "guide", "--hash-only", "--list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--list or --hash-only");
        assertThat(platform.requests("GET", GUIDE)).isEmpty();
    }
}
