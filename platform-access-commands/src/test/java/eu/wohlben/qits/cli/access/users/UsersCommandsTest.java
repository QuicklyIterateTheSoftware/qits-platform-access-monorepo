package eu.wohlben.qits.cli.access.users;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `qits users …` against a fake idp's user doors. The session's idp address is the fake's, so the
 * default address is the one under test; its bearer starts with {@link FakeIdp#SECRET}.
 */
class UsersCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
    private static final String BEARER = "Bearer " + FakeIdp.SECRET + "access-0";
    private static final String TOKENS = "/idp/api/register-tokens";
    private static final String REGISTER = "/idp/api/auth/register";
    private static final String USERS = "/idp/api/users";
    private static final String SUBJECTS = "/idp/api/user-subjects";
    private static final String REGISTER_TOKEN = "rt-SECRET-4b1d";
    private static final String PASSWORD = "pw-SECRET-q7Zx9";

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
        idp.answer("POST", TOKENS, 201, """
                {"id":"7d0c6a1e-1111-4222-8333-444455556666","token":"%s","createdAt":"2026-10-15T10:00:00Z"}
                """.formatted(REGISTER_TOKEN));
        idp.answer("POST", REGISTER, 201, """
                {"username":"jan","password":"%s","sessionId":"s-1","expiresAt":"2026-10-16T10:00:00Z"}
                """.formatted(PASSWORD), "Set-Cookie", "qits_session=SECRET-cookie; HttpOnly");
        idp.answer("GET", USERS, """
                [{"username":"admin","createdAt":"2026-09-01T08:00:00Z","roles":["qits:admin","qits:system"]},
                 {"username":"jan","createdAt":"2026-10-15T10:00:00Z","roles":[]}]
                """);
        idp.answer("DELETE", USERS + "/jan", 204, "");
        idp.answer("GET", SUBJECTS, """
                [{"subject":"0b5c8a52-6f8e-4a77-9d1e-3c2b1a0f9e8d","username":"admin"},
                 {"subject":"7e4d2c19-1a3b-4c5d-8e6f-901234567890","username":"jan"}]
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
    void inviteMintsWithTheSessionsBearerAndPrintsTheTokenAlone() {
        Result r = run("users", "invite");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo(REGISTER_TOKEN + System.lineSeparator());
        assertThat(r.err()).contains("7d0c6a1e").contains("qits users register").doesNotContain(REGISTER_TOKEN);
        assertThat(idp.requests("POST", TOKENS)).singleElement().satisfies(request -> {
            assertThat(request.authorization()).isEqualTo(BEARER);
            assertThat(request.body()).isEmpty();
        });
    }

    @Test
    void inviteJsonPrintsTheIdpsAnswer() {
        Result r = run("users", "invite", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"id\"").contains("\"token\" : \"" + REGISTER_TOKEN + "\"").contains("\"createdAt\"");
    }

    @Test
    void inviteRefusedForAnythingButAnAdminSaysWhichRole() {
        idp.answer("POST", TOKENS, 403, "{\"error\":\"access_denied\",\"error_description\":\"not an admin\"}");
        Result r = run("users", "invite");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Managing users needs qits:admin").contains("HTTP 403")
                .contains("access_denied: not an admin");
    }

    @Test
    void inviteWithARefusedTokenSaysToSignIn() {
        idp.answer("POST", TOKENS, 401, "{\"error\":\"invalid_token\"}");
        Result r = run("users", "invite");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 401").contains("qits login");
    }

    @Test
    void registerSendsTheTokenWithoutABearerAndPrintsThePasswordOnceOnStdout() {
        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo(PASSWORD + System.lineSeparator());
        assertThat(r.err()).contains("Registered jan").contains("only this once").doesNotContain(PASSWORD);
        assertThat(idp.requests("POST", REGISTER)).singleElement().satisfies(request -> {
            assertThat(request.authorization()).isNull();
            assertThat(request.contentType()).startsWith("application/json");
            assertThat(request.body()).contains("\"username\":\"jan\"").contains("\"token\":\"" + REGISTER_TOKEN + "\"")
                    .doesNotContain("password");
        });
    }

    @Test
    void registerNeedsNoSessionAndThenFindsTheIdpLikeLogin() throws Exception {
        Files.delete(home.resolve("qits").resolve("t.json"));
        env.put("QITS_IDP_URL", idp.url() + "/idp/");

        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains(PASSWORD);
        assertThat(idp.requests("POST", REGISTER)).hasSize(1);
    }

    @Test
    void registerWithNoSessionAndNoPlatformNamedIsAUsageErrorNamingQitsDomain() throws Exception {
        Files.delete(home.resolve("qits").resolve("t.json"));

        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("QITS_DOMAIN").contains("QITS_IDP_URL").doesNotContain("wohlben")
                .doesNotContain(REGISTER_TOKEN);
        assertThat(idp.requests("POST", REGISTER)).isEmpty();
    }

    @Test
    void registerJsonPrintsTheUsernameAndPasswordAndNothingOfTheSession() {
        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"username\" : \"jan\"").contains("\"password\" : \"" + PASSWORD + "\"")
                .doesNotContain("sessionId").doesNotContain("SECRET-cookie");
        assertThat(r.err()).isEmpty();
    }

    @Test
    void aSpentRegisterTokenIsRefusedWithoutEchoingIt() {
        idp.answer("POST", REGISTER, 401, "{\"error\":\"invalid_credentials\",\"error_description\":\"not authenticated\"}");
        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("refused the register token (HTTP 401").contains("qits users invite")
                .doesNotContain(REGISTER_TOKEN);
    }

    @Test
    void aTakenUsernameNamesTheIdpsReasonAndKeepsTheToken() {
        idp.answer("POST", REGISTER, 400, "{\"error\":\"invalid_request\",\"error_description\":\"username jan is taken\"}");
        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 400").contains("username jan is taken").contains("not spent")
                .doesNotContain(REGISTER_TOKEN);
    }

    @Test
    void anAnswerWithoutAPasswordIsAFailureThatQuotesNothing() {
        idp.answer("POST", REGISTER, 201, "{\"username\":\"jan\",\"pass\":\"" + PASSWORD + "\"}");
        Result r = run("users", "register", "--token", REGISTER_TOKEN, "jan");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("no password").doesNotContain(PASSWORD);
    }

    @Test
    void registerWithoutATokenIsAUsageError() {
        Result r = run("users", "register", "jan");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void neitherSecretReachesAFile() throws Exception {
        run("users", "invite");
        run("users", "register", "--token", REGISTER_TOKEN, "jan");

        try (Stream<Path> files = Files.walk(home)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain(PASSWORD).doesNotContain(REGISTER_TOKEN);
            }
        }
    }

    @Test
    void listPrintsAnAlignedTable() {
        Result r = run("users", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        String[] lines = r.out().split("\\R");
        assertThat(lines[0]).startsWith("USERNAME").contains("CREATED").contains("ROLES");
        assertThat(lines[1]).startsWith("admin").contains("qits:admin, qits:system");
        assertThat(lines[2]).startsWith("jan").endsWith("-");
        assertThat(lines[1].indexOf("qits:admin")).isEqualTo(lines[0].indexOf("ROLES"));
        assertThat(idp.requests("GET", USERS).getFirst().authorization()).isEqualTo(BEARER);
    }

    @Test
    void listJsonIsTheRawAnswer() {
        Result r = run("users", "list", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"username\" : \"admin\"").contains("\"roles\"");
    }

    @Test
    void listForbiddenSaysWhichRole() {
        idp.answer("GET", USERS, 403, "{\"message\":\"forbidden\"}");
        Result r = run("users", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Managing users needs qits:admin").contains("HTTP 403");
    }

    @Test
    void subjectsPrintsEachUsersNameAndSubject() {
        Result r = run("users", "subjects");

        assertThat(r.exit()).as(r.err()).isZero();
        String[] lines = r.out().split("\\R");
        assertThat(lines[0]).startsWith("USERNAME").contains("SUBJECT");
        assertThat(lines[1]).startsWith("admin").endsWith("0b5c8a52-6f8e-4a77-9d1e-3c2b1a0f9e8d");
        assertThat(lines[2]).startsWith("jan").endsWith("7e4d2c19-1a3b-4c5d-8e6f-901234567890");
        assertThat(lines[1].indexOf("0b5c8a52")).isEqualTo(lines[0].indexOf("SUBJECT"));
        assertThat(idp.requests("GET", SUBJECTS).getFirst().authorization()).isEqualTo(BEARER);
    }

    @Test
    void subjectsJsonIsTheRawAnswer() {
        Result r = run("users", "subjects", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"subject\" : \"7e4d2c19-1a3b-4c5d-8e6f-901234567890\"")
                .contains("\"username\" : \"jan\"");
    }

    @Test
    void subjectsForbiddenNamesBothCallers() {
        idp.answer("GET", SUBJECTS, 403, "{\"error\":\"access_denied\"}");
        Result r = run("users", "subjects");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Listing user subjects needs qits:admin or the delivery desk")
                .contains("HTTP 403");
    }

    @Test
    void deletePrintsAConfirmation() {
        Result r = run("users", "delete", "jan");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("Deleted user jan." + System.lineSeparator());
        assertThat(idp.requests("DELETE", USERS + "/jan").getFirst().authorization()).isEqualTo(BEARER);
    }

    @Test
    void deletingAnUnknownUserSaysSo() {
        Result r = run("users", "delete", "nobody");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("There is no user 'nobody' (HTTP 404)");
    }

    @Test
    void deletingYourselfIsRefused() {
        idp.answer("DELETE", USERS + "/admin", 409, "{\"error\":\"invalid_request\",\"error_description\":\"self\"}");
        Result r = run("users", "delete", "admin");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("You cannot delete yourself (HTTP 409)");
    }

    @Test
    void theIdpUrlFlagWins() throws Exception {
        try (FakePlatform other = new FakePlatform()) {
            other.answer("GET", USERS, "[]");
            Result r = run("users", "list", "--idp-url", other.url() + "/idp");

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("No users.");
            assertThat(other.requests("GET", USERS)).hasSize(1);
            assertThat(idp.requests("GET", USERS)).isEmpty();
        }
    }

    @Test
    void theTokenHomeSendsItsTokenToTheIdpTheEndpointsName() {
        env.put("QITS_TOKEN", "SECRET-workspace-token");
        env.put("QITS_URL_IDP", idp.url());

        Result r = run("users", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(idp.requests("GET", USERS).getFirst().authorization()).isEqualTo("Bearer SECRET-workspace-token");
    }
}
