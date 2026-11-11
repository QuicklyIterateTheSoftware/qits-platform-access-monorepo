package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an agent is told when a door says no — worded once, not printed raw. */
class InPlatformRefusalsTest {

    private FakePlatform platform;

    @BeforeEach
    void start() throws Exception {
        platform = new FakePlatform();
    }

    @AfterEach
    void stop() {
        platform.close();
    }

    /** An agent's token, as the MCP service hands it on: {@code qits:agent}, for {@code qits-platform}. */
    private Credential agent() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"workspace-7f3a\",\"aud\":[\"qits-platform\"],\"groups\":[\"qits:agent\"]}";
        return new RequestCredential(base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature");
    }

    @Test
    void aWriteDoorSaysWhatTheCredentialIsBeforeItSaysWhatTheServiceSaid() {
        platform.answer("POST", "/projects/api/write", 403, "{\"error\":\"forbidden\"}");
        PlatformClient client = new PlatformClient(agent());
        assertThatThrownBy(() -> client.post(URI.create(platform.url() + "/projects/api/write")))
                .isInstanceOf(CliFailure.class)
                .hasMessageStartingWith("403 - this credential is qits:agent, which reads but does not write")
                .hasMessageContaining("HTTP 403");
    }

    /**
     * Nothing judges a token's {@code aud} claim before dialling: every qits service also accepts
     * {@code qits-platform} — the one audience this token carries — so only the service may say no.
     * {@code localhost} stands in for a wire alias here on purpose: a single label, not among the
     * token's audiences.
     */
    @Test
    void aServiceOutsideTheTokensAudiencesIsDialledRatherThanRefused() throws Exception {
        platform.answer("GET", "/observability/api/telemetry/sources", "{\"sources\":[]}");
        assertThat(new PlatformClient(agent()).get(byName("/observability/api/telemetry/sources")))
                .isNotNull();
        assertThat(platform.requests("GET", "/observability/api/telemetry/sources"))
                .as("the call went out; the service decides, not the claim").hasSize(1);
    }

    @Test
    void aServiceThatRefusesTheTokenSaysSoAboveWhatTheServiceSaid() {
        platform.answer("GET", "/observability/api/telemetry/sources", 401, "{\"error\":\"unauthorized\"}");
        PlatformClient client = new PlatformClient(agent());
        assertThatThrownBy(() -> client.get(byName("/observability/api/telemetry/sources")))
                .isInstanceOf(CliFailure.class)
                .hasMessageStartingWith("401 - this service did not accept the workspace credential,"
                        + " which asked the idp for the audience qits-platform")
                .hasMessageContaining("HTTP 401");
    }

    /** The fake binds to the loopback address; this dials the same port by a name with no dot in it. */
    private URI byName(String path) {
        return URI.create(platform.url().replace("127.0.0.1", "localhost") + path);
    }

    @Test
    void aWorkstationSessionAddsNothingOfItsOwn() {
        assertThat(agent().explain(404)).isNull();
        assertThat(((Credential) () -> "token").explain(403)).isNull();
        assertThat(((Credential) () -> "token").explain(401)).isNull();
    }

    @Test
    void aBrowserCommandIsRefusedInsideAndUntouchedOutside() {
        assertThat(refuse(Mode.IN_PLATFORM, "login")).isEqualTo(BrowserGuard.NO_BROWSER);
        assertThat(refuse(Mode.IN_PLATFORM, "git-login")).isEqualTo(BrowserGuard.NO_BROWSER);
        assertThat(refuse(Mode.WORKSTATION, "login")).isNull();
        assertThat(refuse(Mode.IN_PLATFORM, "ci")).isNull();
    }

    /** A command that only means something on the caller's machine still runs there, inside or out. */
    @Test
    void aLocalCommandIsNotTheGuardsBusiness() {
        assertThat(refuse(Mode.IN_PLATFORM, "git-credential", "get")).isNull();
        assertThat(refuse(Mode.IN_PLATFORM, "help", "skill")).isNull();
        assertThat(refuse(Mode.WORKSTATION, "git-credential", "get")).isNull();
        assertThat(refuse(Mode.IN_PLATFORM, "docker-credential", "get")).isNull();
    }

    @Test
    void helpOnABrowserCommandStillAnswersInside() {
        assertThat(refuse(Mode.IN_PLATFORM, "login", "--help")).isNull();
    }

    /** The guard's answer: the refusal it printed, or null when it let the command through. */
    private String refuse(Mode mode, String... args) {
        CommandLine cli = new CommandLine(new AccessCli());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        boolean[] ranThrough = {false};
        cli.setExecutionStrategy(new BrowserGuard(parsed -> {
            ranThrough[0] = true;
            return 0;
        }, mode));
        cli.execute(args);
        return ranThrough[0] ? null : err.toString(StandardCharsets.UTF_8).strip();
    }

    @Test
    void theCliDeclaresWhichCommandsOpenABrowser() {
        CommandNode root = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        assertThat(root.child("login").interaction()).isEqualTo(Interaction.BROWSER);
        assertThat(root.child("git-login").interaction()).isEqualTo(Interaction.BROWSER);
    }
}
