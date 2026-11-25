package eu.wohlben.qits.cli.access.egress;

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

/** `qits egress grants list` against a fake deployments door (qits-2611, qits-2616). */
class EgressCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
    private static final String GRANTS = "/deployments/api/egress-grants";

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
        platform.answer("GET", GRANTS, """
                {"version":1764061200000,
                 "routes":[{"application":"qits-edge","environment":"dev","deployedVersion":"2026.1201.101010",
                            "name":"hetzner","target":"https://api.hetzner.cloud","secret":"HETZNER_DNS",
                            "inject":"bearer","methods":["DELETE","GET","POST","PUT"]},
                           {"application":"qits-idp","environment":"dev","deployedVersion":"2026.1130.1",
                            "name":"idp-events","target":"https://events.internal","secret":null,
                            "inject":null,"methods":["GET"]}],
                 "refused":[{"application":"qits-broken","deployedVersion":"2026.1129.1","reason":"egress.yml is not valid YAML"}]}
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
    void listPrintsTheRoutesAndTheRefusedSection() {
        Result r = run("egress", "grants", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("APPLICATION").contains("ROUTE").contains("TARGET").contains("SECRET")
                .contains("METHODS").contains("VERSION")
                .contains("qits-edge").contains("hetzner").contains("https://api.hetzner.cloud")
                .contains("HETZNER_DNS").contains("DELETE,GET,POST,PUT").contains("2026.1201.101010")
                .contains("qits-idp").contains("idp-events").contains("2026.1130.1")
                .contains("Refused:").contains("REASON").contains("qits-broken").contains("egress.yml is not valid YAML");
        // A route with no secret shows a dash, never a blank cell or the literal "null".
        String idpLine = r.out().lines().filter(l -> l.contains("idp-events")).findFirst().orElseThrow();
        assertThat(idpLine).doesNotContain("null");
        assertThat(idpLine.trim().split("\\s+")).contains("-");
    }

    @Test
    void listJsonIsTheDoorsAnswer() {
        Result r = run("egress", "grants", "list", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("\"version\" : 1764061200000").contains("\"name\" : \"hetzner\"")
                .contains("\"secret\" : null");
    }

    @Test
    void applicationFiltersTheTableClientSide() {
        Result r = run("egress", "grants", "list", "--application", "qits-edge");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("qits-edge").doesNotContain("qits-idp")
                // the refused section is unaffected by the filter: it is not an application's own routes
                .contains("Refused:").contains("qits-broken");
        assertThat(platform.requests("GET", GRANTS)).hasSize(1);
    }

    @Test
    void anUnknownApplicationSaysSoAndStillShowsRefused() {
        Result r = run("egress", "grants", "list", "--application", "no-such-app");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("No egress grants for no-such-app.").contains("Refused:");
    }

    @Test
    void anEmptyAnswerSaysSoWithNoRefusedSection() {
        platform.answer("GET", GRANTS, "{\"version\":1,\"routes\":[],\"refused\":[]}");
        Result r = run("egress", "grants", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("No egress grants." + System.lineSeparator());
    }

    @Test
    void anUnknownOutputIsAUsageError() {
        Result r = run("egress", "grants", "list", "-o", "yaml");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }
}
