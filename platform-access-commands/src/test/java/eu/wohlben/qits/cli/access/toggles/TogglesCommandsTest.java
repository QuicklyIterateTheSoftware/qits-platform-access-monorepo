package eu.wohlben.qits.cli.access.toggles;

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

import java.io.ByteArrayInputStream;
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

/**
 * {@code qits toggles list|show|set-rules} as a person types them, against a fake qits-toggles
 * (qits-2704, epic qits-2542): the landing app's Toggles section, on the command line.
 */
class TogglesCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOGGLES = "/toggles/api/toggles";
    private static final String SMOKE = TOGGLES + "/toggles-smoke";
    private static final String RULES = SMOKE + "/rules";

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
        env.put("QITS_TOGGLES_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        return run(InputStream.nullInputStream(), args);
    }

    private Result run(InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), in,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private static InputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String toggle(long version, String rules, String removalTicket) {
        return """
                {"slug":"toggles-smoke","type":"BOOLEAN","description":"Smoke toggle of qits-toggles itself.",
                 "declaredDefault":false,"firstDeclaredAt":"2026-11-20T00:00:00Z","version":%d,"rules":%s,
                 "lastUsedAt":"2026-11-25T08:00:00Z","removalTicket":%s}
                """.formatted(version, rules, removalTicket == null ? "null" : "\"" + removalTicket + "\"");
    }

    private static final String STATIC_ONLY = """
            [{"kind":"STATIC","value":false,"userSubjects":null,"afterInstant":null}]""";

    private static final String THREE_RULES = """
            [{"kind":"USER","value":true,"userSubjects":["tok-project-agent-qits-developer","jan"],"afterInstant":null},
             {"kind":"TIME","value":true,"userSubjects":null,"afterInstant":"2026-12-01T00:00:00Z"},
             {"kind":"STATIC","value":false,"userSubjects":null,"afterInstant":null}]""";

    private static final String NEW_RULES = """
            {"version":1,"rules":[{"kind":"USER","value":true,"userSubjects":["jan"]},
             {"kind":"STATIC","value":false}]}""";

    @Test
    void theTogglesAreATableForAPersonAndTheServiceAnswersInJson() throws Exception {
        platform.answer("GET", TOGGLES, "[" + toggle(3, THREE_RULES, "qits-2800") + "]");

        Result table = run("toggles", "list");
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("SLUG").contains("DEFAULT").contains("REMOVAL TICKET")
                .containsPattern("toggles-smoke\\s+false\\s+3\\s+3\\s+2026-11-25 08:00:00 UTC\\s+qits-2800");
        assertThat(platform.requests("GET", TOGGLES)).singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0"));

        Result json = run("toggles", "list", "-o", "json");
        assertThat(JSON.readTree(json.out()).get(0).path("slug").asText()).isEqualTo("toggles-smoke");
    }

    @Test
    void noDeclaredToggleSaysWhereTogglesComeFrom() {
        platform.answer("GET", TOGGLES, "[]");

        Result r = run("toggles", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("No toggle is declared").contains("qits-toggles-service's code");
    }

    @Test
    void showPrintsTheRulesInTheOrderTheyAreTried() {
        platform.answer("GET", SMOKE, toggle(3, THREE_RULES, null));

        Result r = run("toggles", "show", "toggles-smoke");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("slug           toggles-smoke").contains("version        3")
                .contains("removalTicket  -");
        String rules = r.out().substring(r.out().indexOf("#  KIND"));
        assertThat(rules.lines().skip(1).toList()).satisfiesExactly(
                line -> assertThat(line).startsWith("1").contains("USER").contains("true")
                        .contains("tok-project-agent-qits-developer, jan"),
                line -> assertThat(line).startsWith("2").contains("TIME").contains("after 2026-12-01 00:00:00 UTC"),
                line -> assertThat(line).startsWith("3").contains("STATIC").contains("false").contains("always"));
    }

    @Test
    void anUnknownSlugIsTheServicesSentenceAndExitOne() {
        platform.answer("GET", TOGGLES + "/no-such-toggle", 404,
                "{\"error\":\"no toggle is declared as 'no-such-toggle'\",\"position\":null}");

        Result r = run("toggles", "show", "no-such-toggle");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("HTTP 404").contains("no toggle is declared as 'no-such-toggle'");
    }

    @Test
    void setRulesSendsStdinUnchangedAndPrintsTheNewVersion() throws Exception {
        platform.answer("PUT", RULES, toggle(2, THREE_RULES, null));

        Result r = run(stdin(NEW_RULES), "toggles", "set-rules", "toggles-smoke");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("The rules of toggles-smoke are replaced: version 2.").contains("#  KIND");
        assertThat(platform.requests("PUT", RULES)).singleElement().satisfies(request ->
                assertThat(JSON.readTree(request.body())).isEqualTo(JSON.readTree(NEW_RULES)));
    }

    @Test
    void setRulesWithNothingOnStdinSendsNothingAndDescribesTheBody() {
        Result r = run("toggles", "set-rules", "toggles-smoke");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).startsWith("Nothing was sent.").contains("\"version\"").contains("STATIC");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void setRulesRefusesABodyThatIsNotAJsonObject() {
        Result notJson = run(stdin("{\"version\":"), "toggles", "set-rules", "toggles-smoke");
        Result array = run(stdin("[]"), "toggles", "set-rules", "toggles-smoke");

        assertThat(notJson.exit()).isEqualTo(2);
        assertThat(notJson.err()).contains("not JSON").contains("Nothing was sent.");
        assertThat(array.exit()).isEqualTo(2);
        assertThat(array.err()).contains("must be a JSON object");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void malformedRulesAreTheServicesSentenceAndExitOne() {
        platform.answer("PUT", RULES, 400, "{\"error\":\"the STATIC rule must be the last one\",\"position\":0}");

        Result r = run(stdin(NEW_RULES), "toggles", "set-rules", "toggles-smoke");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("HTTP 400").contains("the STATIC rule must be the last one");
    }

    @Test
    void aCallerWhoMayNotConfigureIsRefusedWithExitOne() {
        platform.answer("PUT", RULES, 403, "");

        Result r = run(stdin(NEW_RULES), "toggles", "set-rules", "toggles-smoke");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403");
    }

    @Test
    void aStaleVersionSaysToReadTheToggleAgain() {
        platform.answer("PUT", RULES, 409, "{\"error\":\"the rules of 'toggles-smoke' changed since version 1 was "
                + "read; reload and try again\",\"position\":null}");

        Result r = run(stdin(NEW_RULES), "toggles", "set-rules", "toggles-smoke");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("`qits toggles show toggles-smoke` has the new version").contains("HTTP 409")
                .contains("changed since version 1 was read");
    }

    @Test
    void aSlugIsRequiredAndAnUnknownOutputIsAUsageError() {
        Result noSlug = run("toggles", "show");
        Result yaml = run("toggles", "list", "-o", "yaml");

        assertThat(noSlug.exit()).isEqualTo(2);
        assertThat(yaml.exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void theTogglesUrlFlagWins() throws Exception {
        try (FakePlatform other = new FakePlatform()) {
            other.answer("GET", TOGGLES, "[]");
            Result r = run("toggles", "list", "--toggles-url", other.url());

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(other.requests("GET", TOGGLES)).hasSize(1);
            assertThat(platform.requests).isEmpty();
        }
    }

    @Test
    void theAnswerIsReadAsTheServiceSentIt() throws Exception {
        platform.answer("GET", SMOKE, toggle(0, STATIC_ONLY, null));

        Result r = run("toggles", "show", "toggles-smoke", "-o", "json");

        JsonNode answer = JSON.readTree(r.out());
        assertThat(answer.path("rules")).hasSize(1);
        assertThat(answer.path("removalTicket").isNull()).isTrue();
    }
}
