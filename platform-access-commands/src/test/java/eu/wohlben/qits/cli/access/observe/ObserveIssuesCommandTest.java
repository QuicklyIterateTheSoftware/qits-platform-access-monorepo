package eu.wohlben.qits.cli.access.observe;

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

/** {@code qits observe issues list|show|dismiss} against the error-issue doors on the fake platform (epic qits-2538). */
class ObserveIssuesCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-10T11:00:00Z");
    private static final String ISSUES = "/observability/api/error-issues";
    private static final String FP = "0123456789abcdef0123456789abcdef";
    private static final char ESC = 0x1B;

    private static final String ISSUE = """
            {"fingerprint":"0123456789abcdef0123456789abcdef","state":"OPEN","serviceName":"qits-example",
             "exceptionType":"java.lang.IllegalStateException","location":"eu.example.InvoiceResource.list",
             "message":"invoice store unreachable","sampleMessage":"invoice store unreachable\\u001b[2J",
             "sampleTraceId":"6a1f0c2b9d8e47f3a5b4c3d2e1f00a01","firstSeen":"2026-01-01T00:00:00Z",
             "lastSeen":"2026-01-01T02:00:00Z","firstVersion":"2026.1230.090000","lastVersion":"2026.1231.120000",
             "occurrences":3,"regressions":1,"regressedAt":"2026-01-01T02:00:00Z","dismissedAt":null,
             "dismissedBy":null,
             "sampleStacktrace":"java.lang.IllegalStateException: invoice store unreachable\\n\\tat eu.example.InvoiceResource.list(InvoiceResource.java:42)\\n",
             "hourly":[{"hour":"2026-01-01T00:00:00Z","count":2},{"hour":"2026-01-01T02:00:00Z","count":1}]}
            """;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        SessionFile store = new SessionFile(home.resolve("qits"));
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1", NOW.plusSeconds(900),
                idp.issueRefreshToken(), NOW.plus(Duration.ofDays(30))));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_OBSERVABILITY_URL", platform.url());
        env.put("QITS_PROJECTS_URL", platform.url());
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(out()).doesNotContain(FakeIdp.SECRET);
        assertThat(err()).doesNotContain(FakeIdp.SECRET);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private int run(String... args) {
        FakeTime time = new FakeTime(NOW);
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, r -> { });
        return TestCli.execute(context, args);
    }

    @Test
    void listSendsNoQueryByDefaultAndPrintsATitlePerIssue() {
        platform.answer("GET", ISSUES, """
                {"issues":[
                  {"fingerprint":"fedcba9876543210fedcba9876543210","state":"OPEN","serviceName":"qits-example",
                   "exceptionType":null,"message":"nightly export finished with <n> failures","occurrences":1,
                   "lastSeen":"2026-01-01T00:00:00Z"},
                  {"fingerprint":"0123456789abcdef0123456789abcdef","state":"OPEN","serviceName":"qits-example",
                   "exceptionType":"java.lang.IllegalStateException","message":"invoice store unreachable",
                   "occurrences":3,"lastSeen":"2026-01-01T00:00:00Z"}],
                 "total":5,"truncated":true}
                """);

        assertThat(run("observe", "issues", "list")).as(err()).isZero();
        assertThat(platform.requests("GET", ISSUES).getFirst().query()).isNull();
        assertThat(out()).contains("FINGERPRINT").contains("LAST-SEEN").contains("TITLE")
                .contains("nightly export finished with <n> failures")
                .contains("IllegalStateException: invoice store unreachable")
                .doesNotContain("java.lang.IllegalStateException")
                .contains("Showing 2 of 5");
    }

    @Test
    void listSendsTheFiltersGiven() {
        platform.answer("GET", ISSUES, "{\"issues\":[],\"total\":0,\"truncated\":false}");

        assertThat(run("observe", "issues", "list", "--state", "all", "--service", "qits-ci", "--limit", "5"))
                .as(err()).isZero();
        assertThat(platform.requests("GET", ISSUES).getFirst().query()).isEqualTo("state=ALL&service=qits-ci&limit=5");
        assertThat(out()).contains("No error issues.");
    }

    @Test
    void listRefusesAnUnknownStateOrLimitWithoutAsking() {
        assertThat(run("observe", "issues", "list", "--state", "CLOSED")).isEqualTo(2);
        assertThat(err()).contains("--state must be OPEN, DISMISSED or ALL");
        assertThat(run("observe", "issues", "list", "--limit", "0")).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void showPrintsTheIssueItsHoursItsTraceAndItsTickets() {
        platform.answer("GET", ISSUES + "/" + FP, ISSUE);
        platform.answer("GET", "/projects/api/projects/qits/work", """
                {"entities":[{"qualifiedId":"qits-12","status":"REPORTED","title":"Invoices cannot be listed"}]}
                """);

        assertThat(run("observe", "issues", "show", FP)).as(err()).isZero();
        assertThat(platform.requests("GET", ISSUES + "/" + FP).getFirst().query()).isNull();
        assertThat(platform.requests("GET", "/projects/api/projects/qits/work").getFirst().query())
                .isEqualTo("errorIssue=" + FP);
        assertThat(out()).contains("ERROR ISSUE " + FP)
                .contains("2026.1230.090000 -> 2026.1231.120000")
                .contains("eu.example.InvoiceResource.list")
                .contains("occurrences").contains("regressions")
                .contains("HOUR").contains("COUNT")
                .contains("at eu.example.InvoiceResource.list(InvoiceResource.java:42)")
                .contains("qits-12").contains("Invoices cannot be listed")
                .contains("logs: qits observe query --filter 'kind=log attr.qits.error.fingerprint=" + FP
                        + "' --since 1d")
                .doesNotContain("dismissed at")
                .doesNotContain(String.valueOf(ESC));
    }

    @Test
    void showSendsHoursAndProjectAndPrintsBothAsJson() {
        platform.answer("GET", ISSUES + "/" + FP, ISSUE);
        platform.answer("GET", "/projects/api/projects/contract/work", "{\"entities\":[]}");

        assertThat(run("observe", "issues", "show", FP.toUpperCase(), "--hours", "24", "--project", "contract",
                "-o", "json")).as(err()).isZero();
        assertThat(platform.requests("GET", ISSUES + "/" + FP).getFirst().query()).isEqualTo("hours=24");
        assertThat(Frames.tree(out()).path("issue").path("occurrences").asInt()).isEqualTo(3);
        assertThat(Frames.tree(out()).path("tickets").isArray()).isTrue();
    }

    @Test
    void showStillPrintsTheIssueWhenTheTicketsCannotBeRead() {
        platform.answer("GET", ISSUES + "/" + FP, ISSUE);
        platform.answer("GET", "/projects/api/projects/qits/work", 500, "{\"message\":\"down\"}");

        assertThat(run("observe", "issues", "show", FP)).as(err()).isZero();
        assertThat(out()).contains("ERROR ISSUE " + FP).contains("tickets: could not be read.");
        assertThat(err()).contains("could not be read");
    }

    @Test
    void aFingerprintThatIsNot32HexIsRefusedWithoutAsking() {
        assertThat(run("observe", "issues", "show", "c9db9ec1")).isEqualTo(2);
        assertThat(err()).contains("32 hex characters");
        assertThat(run("observe", "issues", "dismiss", "../../telemetry")).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void dismissPostsAndSaysSo() {
        platform.answer("POST", ISSUES + "/" + FP + "/dismiss", "{\"fingerprint\":\"" + FP + "\",\"state\":\"DISMISSED\"}");

        assertThat(run("observe", "issues", "dismiss", FP)).as(err()).isZero();
        assertThat(out()).isEqualTo("dismissed " + FP + System.lineSeparator());
    }

    @Test
    void dismissAsTheAgentPrintsTheRefusal() {
        platform.answer("POST", ISSUES + "/" + FP + "/dismiss", 403,
                "{\"message\":\"a person dismisses an error issue\"}");

        assertThat(run("observe", "issues", "dismiss", FP)).isEqualTo(1);
        assertThat(err()).contains("HTTP 403").contains("a person dismisses an error issue");
    }
}
