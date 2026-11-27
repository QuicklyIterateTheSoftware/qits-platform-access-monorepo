package eu.wohlben.qits.cli.access.networkaccess;

import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits session-daemon}'s look at the companion (qits-2743): the git host's proxy line follows
 * {@code access.json} at the start, on a change and on a reconnect, and nothing is done in between.
 */
class CompanionGitWatchTest {

    private static final String ORIGIN = "https://githost.qits.example.test";
    private static final String PROXY = "http://127.0.0.1:47813";
    private static final Session SESSION = new Session("https://idp.qits.example.test/idp", "qits-cli", "a",
            Instant.EPOCH, "r", Instant.EPOCH);

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();
    private Path config;
    private boolean healthy = true;
    private int healthAsked;
    private CompanionGitWatch watch;

    @BeforeEach
    void start() throws Exception {
        config = home.resolve("gitconfig");
        env.put("GIT_CONFIG_GLOBAL", config.toString());
        env.put("QITS_GIT_HOST_URL", ORIGIN + "/git/qits");
        watch = new CompanionGitWatch(home, env, proxy -> {
            healthAsked++;
            return healthy;
        });
    }

    @AfterEach
    void stop() {
        GitSetup.GIT = "git";
    }

    private void gitAsksQits() throws Exception {
        GitSetup.configure(GitSetup.commands(ORIGIN, "qits"), env, new PrintStream(OutputStream.nullOutputStream()));
    }

    private void accessJson(String proxy) throws Exception {
        Files.writeString(home.resolve(AccessState.FILE_NAME), proxy == null
                ? "{\"peerId\":\"p-1\",\"image\":\"i\",\"version\":\"1\"}"
                : "{\"peerId\":\"p-1\",\"proxy\":\"" + proxy + "\",\"image\":\"i\",\"version\":\"1\"}");
    }

    private List<String> line() throws Exception {
        Process git = new ProcessBuilder("git", "config", "--file", config.toString(), "--get-all",
                GitSetup.proxyKey(ORIGIN)).start();
        List<String> values = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        git.waitFor();
        return values;
    }

    @Test
    void atTheStartAnUpCompanionGetsItsLine() throws Exception {
        gitAsksQits();
        accessJson(PROXY);

        assertThat(watch.tick(SESSION)).containsExactly("git: " + ORIGIN + " goes through the companion at " + PROXY);

        assertThat(line()).containsExactly(PROXY);
    }

    @Test
    void betweenChangesALookWritesNothingAndSaysNothing() throws Exception {
        gitAsksQits();
        accessJson(PROXY);
        watch.tick(SESSION);
        String before = Files.readString(config);

        for (int i = 0; i < 3; i++) {
            assertThat(watch.tick(SESSION)).isEmpty();
        }

        assertThat(Files.readString(config)).isEqualTo(before);
    }

    @Test
    void aReconnectPutsBackALineThatWasRemoved() throws Exception {
        gitAsksQits();
        accessJson(PROXY);
        watch.tick(SESSION);
        GitSetup.configure(List.of(GitSetup.unproxyCommand(ORIGIN)), env, new PrintStream(OutputStream.nullOutputStream()));

        healthy = false;
        assertThat(watch.tick(SESSION)).isEmpty();
        assertThat(line()).isEmpty();

        healthy = true;
        assertThat(watch.tick(SESSION)).containsExactly("git: " + ORIGIN + " goes through the companion at " + PROXY);
        assertThat(line()).containsExactly(PROXY);
    }

    @Test
    void aCompanionThatIsOnlyDownKeepsItsLine() throws Exception {
        gitAsksQits();
        accessJson(PROXY);
        watch.tick(SESSION);

        healthy = false;
        watch.tick(SESSION);

        assertThat(line()).containsExactly(PROXY);
    }

    @Test
    void downAndRevokeTakeTheLineAway() throws Exception {
        gitAsksQits();
        accessJson(PROXY);
        watch.tick(SESSION);

        accessJson(null);
        assertThat(watch.tick(SESSION)).containsExactly("git: " + ORIGIN + " goes direct again");
        assertThat(line()).isEmpty();

        accessJson(PROXY);
        watch.tick(SESSION);
        Files.delete(home.resolve(AccessState.FILE_NAME));
        assertThat(watch.tick(SESSION)).containsExactly("git: " + ORIGIN + " goes direct again");
        assertThat(line()).isEmpty();
    }

    @Test
    void withNoCompanionSetUpTheHealthIsNeverAsked() throws Exception {
        gitAsksQits();

        watch.tick(SESSION);
        watch.tick(SESSION);

        assertThat(healthAsked).isZero();
        assertThat(config).exists();
        assertThat(line()).isEmpty();
    }

    @Test
    void aGitThatDoesNotAskQitsIsNeverTouched() throws Exception {
        accessJson(PROXY);

        assertThat(watch.tick(SESSION)).isEmpty();

        assertThat(config).doesNotExist();
    }

    @Test
    void noGitIsSaidOnceNotEveryLook() throws Exception {
        accessJson(PROXY);
        GitSetup.GIT = home.resolve("no-such-git").toString();

        assertThat(watch.tick(SESSION)).singleElement().asString().startsWith("warning: ");
        accessJson("http://127.0.0.1:50000");
        assertThat(watch.tick(SESSION)).isEmpty();
    }
}
