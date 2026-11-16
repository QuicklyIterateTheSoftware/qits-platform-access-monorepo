package eu.wohlben.qits.cli.access.deployments;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits deployments requests approve` and `decline` against a fake edge and a fake idp (qits-2268, qits-2271). */
class RequestsCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private static final String ID = "6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";
    private static final String DEPLOYMENT = "0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d";
    private static final String APPROVE = "/deployments/api/deployment-requests/" + ID + "/approve";
    private static final String DECLINE = "/deployments/api/deployment-requests/" + ID + "/decline";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String APPROVED = """
            {"deploymentRequest":{"id":"%s","applicationName":"qits-ci","variant":null,"version":"2026.1115.90000",
              "environmentId":"e","repoId":"qits-ci-service","qualityGate":"MET","gateDetail":"approved by dana.weber",
              "deploymentId":"%s","createdAt":"2026-11-15T09:00:00Z","gateSettledAt":"2026-11-15T10:00:00Z",
              "deploymentStatus":"QUEUED","deployHold":true,"releaseRequestQualifiedId":"qits-ci-service-rr-12",
              "approvedBy":"dana.weber","approvedAt":"2026-11-15T10:00:00Z"},
             "deployment":{"id":"%s","applicationName":"qits-ci","version":"2026.1115.90000","status":"QUEUED"}}
            """.formatted(ID, DEPLOYMENT, DEPLOYMENT);

    private static final String DECLINED = """
            {"deploymentRequest":{"id":"%s","applicationName":"qits-ci","variant":null,"version":"2026.1115.90000",
              "environmentId":"e","repoId":"qits-ci-service","qualityGate":"UNMET",
              "gateDetail":"declined by dana.weber: the change is wrong",
              "deploymentId":null,"createdAt":"2026-11-15T09:00:00Z","gateSettledAt":"2026-11-15T10:00:00Z",
              "deploymentStatus":null,"deployHold":true,"releaseRequestQualifiedId":"qits-ci-service-rr-12",
              "approvedBy":null,"approvedAt":null,"declinedBy":"dana.weber","declinedAt":"2026-11-15T10:00:00Z"},
             "deployment":null}
            """.formatted(ID);

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
        env.put("QITS_DEPLOYMENTS_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("POST", APPROVE, APPROVED);
        platform.answer("POST", DECLINE, DECLINED);
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
    void approvePostsNoBodyAndPrintsTheGateAndTheQueuedDeployment() {
        Result r = run("deployments", "requests", "approve", "--request", ID);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", APPROVE).getFirst().body()).isEmpty();
        assertThat(r.out()).startsWith("deployment request " + ID + ": MET\n")
                .contains("  application      qits-ci")
                .contains("  version          2026.1115.90000")
                .contains("  release request  qits-ci-service-rr-12")
                .contains("  gate             MET")
                .contains("  gate detail      approved by dana.weber")
                .contains("  approved by      dana.weber")
                .contains("  approved at      ")
                .endsWith("deployment " + DEPLOYMENT + ": QUEUED\n");
    }

    @Test
    void approveJsonIsTheServicesAnswer() throws Exception {
        Result r = run("deployments", "requests", "approve", "--request", ID, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(APPROVED));
    }

    @Test
    void approveRequiresARequest() {
        Result r = run("deployments", "requests", "approve");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(platform.requests("POST", APPROVE)).isEmpty();
    }

    @Test
    void aRequestNotHeldRefusesWithExitOneAndTheServicesWords() {
        platform.answer("POST", APPROVE, 409, "{\"message\":\"deployment request " + ID + " is not held for approval\"}");

        Result r = run("deployments", "requests", "approve", "--request", ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("409").contains("is not held for approval");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void anUnknownRequestRefusesWithExitOne() {
        platform.answer("POST", APPROVE, 404, "{\"message\":\"no deployment request " + ID + "\"}");

        Result r = run("deployments", "requests", "approve", "--request", ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("404");
    }

    @Test
    void declineWithANotePostsItAndPrintsTheSettledGate() throws Exception {
        Result r = run("deployments", "requests", "decline", "--request", ID, "--note", "the change is wrong");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DECLINE).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"note\":\"the change is wrong\"}"));
        assertThat(r.out()).startsWith("deployment request " + ID + ": UNMET\n")
                .contains("  application      qits-ci")
                .contains("  version          2026.1115.90000")
                .contains("  release request  qits-ci-service-rr-12")
                .contains("  gate             UNMET")
                .contains("  gate detail      declined by dana.weber: the change is wrong")
                .contains("  declined by      dana.weber")
                .contains("  declined at      ");
        assertThat(r.out()).doesNotContain("deployment " + DEPLOYMENT);
    }

    @Test
    void declineWithoutANoteSendsAnEmptyBody() throws Exception {
        Result r = run("deployments", "requests", "decline", "--request", ID);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DECLINE).getFirst().body())).isEqualTo(JSON.readTree("{}"));
    }

    @Test
    void declineJsonIsTheServicesAnswer() throws Exception {
        Result r = run("deployments", "requests", "decline", "--request", ID, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(DECLINED));
    }

    @Test
    void declineRequiresARequest() {
        Result r = run("deployments", "requests", "decline");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(platform.requests("POST", DECLINE)).isEmpty();
    }

    @Test
    void aRequestNotHeldForDeclineRefusesWithExitOneAndTheServicesWords() {
        platform.answer("POST", DECLINE, 409, "{\"message\":\"deployment request " + ID + " is not held for decline\"}");

        Result r = run("deployments", "requests", "decline", "--request", ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("409").contains("is not held for decline");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void anUnknownRequestRefusesDeclineWithExitOne() {
        platform.answer("POST", DECLINE, 404, "{\"message\":\"no deployment request " + ID + "\"}");

        Result r = run("deployments", "requests", "decline", "--request", ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("404");
    }
}
