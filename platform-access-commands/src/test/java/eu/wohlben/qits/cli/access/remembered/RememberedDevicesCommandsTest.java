package eu.wohlben.qits.cli.access.remembered;

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
 * `qits remembered-devices …` (qits-1944) against a fake idp's remembered-devices doors. The
 * session's idp address is the fake's, so the default address is the one under test; its bearer
 * starts with {@link FakeIdp#SECRET}.
 */
class RememberedDevicesCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    private static final String BEARER = "Bearer " + FakeIdp.SECRET + "access-0";
    private static final String DEVICES = "/idp/api/remembered-devices";
    private static final String LAPTOP = "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f";
    private static final String PHONE = "0b9a8c7d-1e2f-4a3b-9c4d-5e6f7a8b9c0d";

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
        idp.answer("GET", DEVICES, """
                [{"id":"%s","label":"Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
                  "createdAt":"2026-10-01T08:00:00Z","lastUsedAt":"2026-11-07T09:30:00Z",
                  "expiresAt":"2026-12-07T09:30:00Z","absoluteExpiresAt":"2027-01-01T08:00:00Z","current":true},
                 {"id":"%s","label":"Safari on iPhone",
                  "createdAt":"2026-10-20T08:00:00Z","lastUsedAt":"2026-10-21T08:00:00Z",
                  "expiresAt":"2026-11-20T08:00:00Z","absoluteExpiresAt":"2027-01-20T08:00:00Z","current":false}]
                """.formatted(LAPTOP, PHONE));
        idp.answer("DELETE", DEVICES + "/" + LAPTOP, 204, "");
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
    void listPrintsAnAlignedTableWithTheSessionsBearer() {
        Result r = run("remembered-devices", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        String[] lines = r.out().split("\\R");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("ID").contains("LABEL").contains("LAST USED").contains("EXPIRES")
                .endsWith("CURRENT");
        assertThat(lines[1]).startsWith(LAPTOP).contains("Mozilla/5.0 (X11; Linux x86_64; rv:140.…")
                .doesNotContain("Firefox").endsWith("yes");
        assertThat(lines[2]).startsWith(PHONE).contains("Safari on iPhone").endsWith("-");
        assertThat(lines[1].indexOf("Mozilla")).isEqualTo(lines[0].indexOf("LABEL"));
        assertThat(lines[2].lastIndexOf('-')).isEqualTo(lines[0].indexOf("CURRENT"));
        assertThat(idp.requests("GET", DEVICES)).singleElement()
                .satisfies(request -> assertThat(request.authorization()).isEqualTo(BEARER));
    }

    @Test
    void listJsonIsTheRawAnswer() {
        Result r = run("remembered-devices", "list", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("\"id\" : \"" + LAPTOP + "\"").contains("\"absoluteExpiresAt\"")
                .contains("\"current\" : true");
    }

    @Test
    void anEmptyListSaysSo() {
        idp.answer("GET", DEVICES, "[]");
        Result r = run("remembered-devices", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo("No remembered devices." + System.lineSeparator());
    }

    @Test
    void anotherCredentialIsRefusedWithTheIdpsSentence() {
        idp.answer("GET", DEVICES, 403, "{\"error\":\"access_denied\",\"error_description\":"
                + "\"only a person's CLI bearer may reach remembered devices\"}");
        Result r = run("remembered-devices", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Remembered devices are a person's own").contains("HTTP 403")
                .contains("only a person's CLI bearer may reach remembered devices");
    }

    @Test
    void anAgentsTokenIsRefusedWithoutTheReadsButDoesNotWriteLine() {
        env.put("QITS_TOKEN", "SECRET-workspace-token");
        env.put("QITS_URL_IDP", idp.url());
        idp.answer("GET", DEVICES, 403, "{\"error\":\"access_denied\",\"error_description\":\"not a person\"}");

        Result r = run("remembered-devices", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403").contains("not a person").doesNotContain("reads but does not write")
                .doesNotContain("SECRET-workspace-token");
        assertThat(idp.requests("GET", DEVICES).getFirst().authorization()).isEqualTo("Bearer SECRET-workspace-token");
    }

    @Test
    void revokePrintsAConfirmation() {
        Result r = run("remembered-devices", "revoke", "--device", LAPTOP);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("Revoked remembered device " + LAPTOP + ": it signs in with a password again."
                + System.lineSeparator());
        assertThat(idp.requests("DELETE", DEVICES + "/" + LAPTOP)).singleElement()
                .satisfies(request -> assertThat(request.authorization()).isEqualTo(BEARER));
    }

    @Test
    void revokingAnUnknownOrForeignDeviceSaysSo() {
        idp.answer("DELETE", DEVICES + "/" + PHONE, 404, "{\"error\":\"not_found\","
                + "\"error_description\":\"remembered device not found\"}");
        Result r = run("remembered-devices", "revoke", "--device", PHONE);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("You have no remembered device " + PHONE + " (HTTP 404)");
    }

    @Test
    void revokeRefusedForAnotherCredential() {
        idp.answer("DELETE", DEVICES + "/" + LAPTOP, 403, "{\"error\":\"access_denied\","
                + "\"error_description\":\"only a person's CLI bearer may reach remembered devices\"}");
        Result r = run("remembered-devices", "revoke", "--device", LAPTOP);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Remembered devices are a person's own").contains("HTTP 403")
                .contains("only a person's CLI bearer may reach remembered devices");
    }

    @Test
    void revokeWithoutADeviceIsAUsageError() {
        Result r = run("remembered-devices", "revoke");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--device");
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void revokeWithSomethingThatIsNoIdIsAUsageError() {
        Result r = run("remembered-devices", "revoke", "--device", "../users/jan");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("is not one");
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void theIdpUrlFlagWins() throws Exception {
        try (FakePlatform other = new FakePlatform()) {
            other.answer("GET", DEVICES, "[]");
            Result r = run("remembered-devices", "list", "--idp-url", other.url() + "/idp");

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(other.requests("GET", DEVICES)).hasSize(1);
            assertThat(idp.requests("GET", DEVICES)).isEmpty();
        }
    }

    @Test
    void anUnknownOutputIsAUsageError() {
        Result r = run("remembered-devices", "list", "-o", "yaml");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(idp.requests).isEmpty();
    }
}
