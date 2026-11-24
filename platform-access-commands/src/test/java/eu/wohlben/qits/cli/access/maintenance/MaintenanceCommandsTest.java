package eu.wohlben.qits.cli.access.maintenance;

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

/** `qits maintenance …` against a fake edge (projects and maintenance) and a fake idp. */
class MaintenanceCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String LANDING = "0a0b0c0d-0000-4000-8000-00000000000a";
    private static final String REQUEST = "4f2a91c0-2222-3333-4444-555555555555";
    private static final String OTHER = "9e9e9e9e-2222-3333-4444-555555555555";
    private static final String JOB = "6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";
    private static final String FOLD = "abc123def4567890abc123def4567890abc123d";
    private static final String RUN_1 = "11111111-2222-3333-4444-555555555555";
    private static final String RUN_2 = "22222222-3333-4444-5555-666666666666";
    private static final String AUTOMATIONS_DOOR = "/maintenance/api/release-requests/" + REQUEST + "/automations";
    private static final String RUN_DOOR = AUTOMATIONS_DOOR + "/screenshot-baselines/runs";
    private static final String VERSIONS_DOOR = "/maintenance/api/artifacts/versions";
    private static final String INGEST_DOOR = "/maintenance/api/artifacts/ingest";
    private static final String SBOM_JOB = "7f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e60";

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
        env.put("QITS_PROJECTS_URL", platform.url());
        env.put("QITS_MAINTENANCE_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}}]}
                """.formatted(QITS));
        platform.answer("GET", "/projects/api/projects/" + QITS + "/repositories", """
                {"entries":[{"repository":{"id":"%s","name":"qits-landing-app","archetype":"APP"},"declared":true}]}
                """.formatted(LANDING));
        platform.answer("GET", "/projects/api/repositories/" + LANDING + "/release-requests", """
                {"requests":[{"id":"%s","qualifiedId":"qits-landing-app-rr-7","state":"REJECTED"},
                             {"id":"%s","qualifiedId":"qits-landing-app-rr-6","state":"RELEASED"}]}
                """.formatted(REQUEST, OTHER));
        platform.answer("GET", AUTOMATIONS_DOOR, """
                {"requestId":"%s","foldSha":"%s","automations":[{"kind":"screenshot-baselines",
                 "label":"Screenshot baselines","state":"RUNNING",
                 "detail":"Waiting for automations at %s: Screenshot baselines running","bumpId":"%s",
                 "runIds":["%s","%s"],"branch":"maintenance/automations/screenshot-baselines/%s","resultSha":null,
                 "updatedAt":"2026-10-02T10:05:00Z"}]}
                """.formatted(REQUEST, FOLD, FOLD, JOB, RUN_1, RUN_2, REQUEST));
        platform.answer("POST", RUN_DOOR, 202, "{\"id\":\"" + JOB + "\"}");
        platform.answer("GET", "/maintenance/api/bumps/" + JOB, """
                {"id":"%s","mode":"AUTOMATION","repository":"qits-landing-app",
                 "branch":"maintenance/automations/screenshot-baselines/%s","status":"SUCCEEDED",
                 "message":"new baselines on maintenance/automations/screenshot-baselines/%s at abc, joined to release request %s",
                 "resultSha":"abc","releaseRequestId":"%s"}
                """.formatted(JOB, REQUEST, REQUEST, REQUEST, REQUEST));
        platform.answer("GET", VERSIONS_DOOR, """
                [{"version":"2026.1104.1","repository":"qits-eventstream-javalib","occurredAt":"2026-10-01T10:00:00Z",
                  "sbomStatus":"PENDING","sbomError":null,"ingestedAt":null},
                 {"version":"2026.1020.1","repository":"qits-eventstream-javalib","occurredAt":"2026-09-20T10:00:00Z",
                  "sbomStatus":"FAILED","sbomError":"the sbom answer is not a CycloneDX document","ingestedAt":null}]
                """);
        platform.answer("POST", INGEST_DOOR, 202, "{\"id\":\"" + SBOM_JOB + "\"}");
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
    void automationsListsEachKindWithItsNewestRun() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automations", "--request", "4f2a");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("KIND").contains("STATE").contains("FOLD").contains("RUN").contains("DETAIL")
                .contains("screenshot-baselines").contains("RUNNING").contains(FOLD.substring(0, 8))
                .contains(RUN_2.substring(0, 8)).doesNotContain(RUN_1.substring(0, 8))
                .contains("Waiting for automations");
        assertThat(platform.requests("GET", AUTOMATIONS_DOOR)).hasSize(1);
        assertThat(platform.requests("GET", AUTOMATIONS_DOOR).getFirst().query()).isNull();
    }

    @Test
    void automationsWithAFoldSendsItAsAQueryParameter() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automations", "--request", REQUEST, "--fold", FOLD);

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("GET", AUTOMATIONS_DOOR).getFirst().query()).isEqualTo("foldSha=" + FOLD);
    }

    @Test
    void automationsJsonPrintsTheAnswerAsIs() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automations", "--request", REQUEST, "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"requestId\"").contains("\"foldSha\"").contains("\"runIds\"");
    }

    @Test
    void anEmptyListSaysNoAutomationApplies() {
        platform.answer("GET", AUTOMATIONS_DOOR, "{\"requestId\":\"" + REQUEST + "\",\"foldSha\":\"" + FOLD
                + "\",\"automations\":[]}");
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automations", "--request", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("No release-request automation applies to qits-landing-app");
    }

    @Test
    void anUnknownRequestIsAUsageError() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automations", "--request", "ffff");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-landing-app has no release request 'ffff'.");
        assertThat(platform.requests("GET", AUTOMATIONS_DOOR)).isEmpty();
    }

    @Test
    void automationRunRequestsAJobAndPrintsItsId() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automation", "run", "--request", "4f2a", "--kind", "screenshot-baselines", "--work-item", "qits-112");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("job " + JOB).contains("qits maintenance bump " + JOB);
        assertThat(platform.requests("POST", RUN_DOOR)).hasSize(1);
        assertThat(platform.requests("POST", RUN_DOOR).getFirst().body()).contains("\"workItem\":\"qits-112\"");
        assertThat(platform.requests("POST", RUN_DOOR).getFirst().authorization())
                .isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
    }

    @Test
    void theLogicalIdIsLookedUpAndTheUuidSent() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automation", "run", "--request", "QITS-LANDING-APP-RR-7", "--kind", "screenshot-baselines");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", RUN_DOOR)).hasSize(1);
        assertThat(r.out()).contains("for release request qits-landing-app-rr-7: job " + JOB);
        assertThat(run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automations", "--request", "qits-landing-app-rr-7").exit()).isZero();
        assertThat(platform.requests("GET", AUTOMATIONS_DOOR)).hasSize(1);
    }

    @Test
    void automationRunWithoutAWorkItemSendsNone() {
        Result r = run("maintenance", "automation", "run", "--project", "qits", "--repository", "qits-landing-app",
                "--request", REQUEST, "--kind", "screenshot-baselines");

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("POST", RUN_DOOR).getFirst().body()).isEqualTo("{}");
    }

    @Test
    void automationRunOfAnUnknownKindIsRefused() {
        platform.answer("POST", RUN_DOOR, 404, "{\"message\":\"no automation 'screenshot-baselines' on this "
                + "repository\"}");
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automation", "run", "--request", REQUEST, "--kind", "screenshot-baselines");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("no automation 'screenshot-baselines'");
    }

    @Test
    void automationRunRefusalIsPassedOn() {
        platform.answer("POST", RUN_DOOR, 409, "{\"message\":\"one is already running for this request and kind\"}");
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "automation", "run", "--request", REQUEST, "--kind", "screenshot-baselines");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("one is already running");
    }

    @Test
    void bumpShowsTheJob() {
        Result r = run("maintenance", "bump", JOB);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("AUTOMATION").contains("SUCCEEDED").contains("joined to release request " + REQUEST);
    }

    @Test
    void theHelpNamesBothCommandsAndNotScreenshotBaselines() {
        Result r = run("maintenance", "--help");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("automations").contains("automation").contains("sbom")
                .doesNotContain("screenshot-baselines");
    }

    @Test
    void sbomVersionsListsEachVersionWithItsStatus() {
        Result r = run("maintenance", "sbom", "versions", "--ecosystem", "maven", "--name",
                "eu.wohlben.qits:qits-eventstream-javalib");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("VERSION").contains("STATUS").contains("REPOSITORY").contains("RELEASED")
                .contains("ERROR").contains("2026.1104.1").contains("PENDING").contains("2026.1020.1")
                .contains("FAILED").contains("the sbom answer is not a CycloneDX document")
                .contains("qits-eventstream-javalib");
        FakePlatform.Request sent = platform.requests("GET", VERSIONS_DOOR).getFirst();
        assertThat(sent.query()).isEqualTo("ecosystem=maven&name=eu.wohlben.qits%3Aqits-eventstream-javalib");
    }

    @Test
    void sbomVersionsJsonPrintsTheAnswerAsIs() {
        Result r = run("maintenance", "sbom", "versions", "--ecosystem", "maven", "--name", "x", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"version\"").contains("\"sbomStatus\"");
    }

    @Test
    void anEmptyVersionsListSaysNoReleaseIsKnown() {
        platform.answer("GET", VERSIONS_DOOR, "[]");
        Result r = run("maintenance", "sbom", "versions", "--ecosystem", "maven", "--name", "eu.wohlben.qits:none");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("No released version of eu.wohlben.qits:none (maven) is known.");
    }

    @Test
    void sbomVersionsOfABadEcosystemIsRefused() {
        platform.answer("GET", VERSIONS_DOOR, 400, "{\"message\":\"ecosystem is maven, npm, docker or daemon\"}");
        Result r = run("maintenance", "sbom", "versions", "--ecosystem", "bogus", "--name", "x");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("ecosystem is maven, npm, docker or daemon");
    }

    @Test
    void sbomRereadQueuesAJobAndPrintsItsId() {
        Result r = run("maintenance", "sbom", "reread", "--ecosystem", "maven", "--name",
                "eu.wohlben.qits:qits-eventstream-javalib", "--version", "2026.1020.1");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Queued an SBOM read of eu.wohlben.qits:qits-eventstream-javalib 2026.1020.1 "
                + "(maven): job " + SBOM_JOB).contains("read asynchronously");
        assertThat(platform.requests("POST", INGEST_DOOR)).hasSize(1);
        FakePlatform.Request sent = platform.requests("POST", INGEST_DOOR).getFirst();
        assertThat(sent.body()).contains("\"ecosystem\":\"maven\"")
                .contains("\"name\":\"eu.wohlben.qits:qits-eventstream-javalib\"")
                .contains("\"version\":\"2026.1020.1\"");
    }

    @Test
    void sbomRereadRefusalIsPassedOn() {
        platform.answer("POST", INGEST_DOOR, 403, "{\"message\":\"this credential is qits:agent, which reads but "
                + "does not write\"}");
        Result r = run("maintenance", "sbom", "reread", "--ecosystem", "maven", "--name",
                "eu.wohlben.qits:qits-eventstream-javalib", "--version", "2026.1020.1");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("this credential is qits:agent, which reads but does not write");
    }

    @Test
    void sbomRereadHelpWorksDespiteItsOwnVersionFlag() {
        // Regression: --version collides with the mixin's -V/--version, so this command cannot use
        // mixinStandardHelpOptions. See AGENTS.md.
        Result r = run("maintenance", "sbom", "reread", "--help");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Usage:");
        assertThat(r.err()).isEmpty();
    }
}
