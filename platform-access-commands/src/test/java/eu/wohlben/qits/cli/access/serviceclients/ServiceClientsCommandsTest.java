package eu.wohlben.qits.cli.access.serviceclients;

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

/**
 * `qits service-clients list` (qits-2531) against a fake idp's service-client read. The session's
 * idp address is the fake's; its bearer starts with {@link FakeIdp#SECRET}.
 */
class ServiceClientsCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    private static final String BEARER = "Bearer " + FakeIdp.SECRET + "access-0";
    private static final String CLIENTS = "/idp/api/service-clients";

    @TempDir
    Path home;

    private FakePlatform idp;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url() + "/idp", "qits-cli",
                FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)), FakeIdp.SECRET + "refresh-0",
                T0.plus(Duration.ofDays(30))));
        idp.answer("GET", CLIENTS, """
                [{"clientId":"dev-qits-projects","source":"database","createdAt":"2026-09-10T17:05:52Z","rotatedAt":null},
                 {"clientId":"dev-qits-ci","source":"database","createdAt":"2026-09-10T17:05:50Z",
                  "rotatedAt":"2026-10-01T08:00:00Z"}]
                """);
    }

    @AfterEach
    void stop() {
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
    void listPrintsATableSortedByIdWithTheSessionsBearer() {
        Result r = run("service-clients", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        String[] lines = r.out().split("\\R");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("CLIENT ID").contains("CREATED").endsWith("SECRET ROTATED");
        assertThat(lines[1]).startsWith("dev-qits-ci").doesNotEndWith("-");
        assertThat(lines[2]).startsWith("dev-qits-projects").endsWith("-");
        assertThat(r.out().toLowerCase()).doesNotContain("secret\"");
        assertThat(idp.requests("GET", CLIENTS)).singleElement()
                .satisfies(request -> assertThat(request.authorization()).isEqualTo(BEARER));
    }

    @Test
    void listJsonIsTheRawAnswer() {
        Result r = run("service-clients", "list", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"clientId\" : \"dev-qits-projects\"").contains("\"rotatedAt\" : null");
    }

    @Test
    void anEmptyListSaysSo() {
        idp.answer("GET", CLIENTS, "[]");
        Result r = run("service-clients", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo("No service clients." + System.lineSeparator());
    }

    @Test
    void aCallerWithoutAReadingRoleIsToldWhichRolesRead() {
        idp.answer("GET", CLIENTS, 403, "{\"error\":\"access_denied\",\"error_description\":\"lacks a role\"}");
        Result r = run("service-clients", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Reading service clients needs qits:admin").contains("HTTP 403")
                .contains("lacks a role");
    }

    @Test
    void theIdpUrlFlagWins() throws Exception {
        try (FakePlatform other = new FakePlatform()) {
            other.answer("GET", CLIENTS, "[]");
            Result r = run("service-clients", "list", "--idp-url", other.url() + "/idp");

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(other.requests("GET", CLIENTS)).hasSize(1);
            assertThat(idp.requests("GET", CLIENTS)).isEmpty();
        }
    }

    @Test
    void anUnknownOutputIsAUsageError() {
        Result r = run("service-clients", "list", "-o", "yaml");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(idp.requests).isEmpty();
    }
}
