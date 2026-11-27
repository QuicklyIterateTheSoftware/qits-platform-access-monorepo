package eu.wohlben.qits.cli.access.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The git host's companion proxy line (qits-2743) against a real git and a scratch {@code
 * GIT_CONFIG_GLOBAL}: set, kept, removed, never written when already right, and never touched where
 * Git does not ask qits for the git host.
 */
class GitCompanionLineTest {

    private static final String ORIGIN = "https://githost.qits.example.test";
    private static final String PROXY = "http://127.0.0.1:47813";

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();
    private Path config;

    @BeforeEach
    void start() {
        config = home.resolve("gitconfig");
        env.put("GIT_CONFIG_GLOBAL", config.toString());
    }

    private List<String> values(String key) throws Exception {
        Process git = new ProcessBuilder("git", "config", "--file", config.toString(), "--get-all", key).start();
        List<String> values = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        git.waitFor();
        return values;
    }

    private void gitAsksQits() throws Exception {
        GitSetup.configure(GitSetup.commands(ORIGIN, "qits"), env, new PrintStream(OutputStream.nullOutputStream()));
    }

    @Test
    void aGitThatDoesNotAskQitsIsLeftAlone() throws Exception {
        GitCompanionLine.Result result = GitCompanionLine.sync(ORIGIN, PROXY, env);

        assertThat(result.change()).isEqualTo(GitCompanionLine.Change.NOT_SET_UP);
        assertThat(result.line(PROXY)).isEmpty();
        assertThat(config).doesNotExist();
    }

    @Test
    void aSetUpGitGetsTheLineForTheGitHostOnly() throws Exception {
        gitAsksQits();

        GitCompanionLine.Result result = GitCompanionLine.sync(ORIGIN, PROXY, env);

        assertThat(result.change()).isEqualTo(GitCompanionLine.Change.SET);
        assertThat(result.line(PROXY)).contains("git: " + ORIGIN + " goes through the companion at " + PROXY);
        assertThat(values(GitSetup.proxyKey(ORIGIN))).containsExactly(PROXY);
        assertThat(values("http.proxy")).isEmpty();
        assertThat(values("credential." + ORIGIN + ".helper")).containsExactly("", "!qits git-credential");
    }

    @Test
    void anAlreadyRightLineIsNotWrittenAgainAndSaysNothing() throws Exception {
        gitAsksQits();
        GitCompanionLine.sync(ORIGIN, PROXY, env);
        FileTime old = FileTime.fromMillis(0);
        Files.setLastModifiedTime(config, old);
        String before = Files.readString(config);

        GitCompanionLine.Result again = GitCompanionLine.sync(ORIGIN, PROXY, env);

        assertThat(again.change()).isEqualTo(GitCompanionLine.Change.UNCHANGED);
        assertThat(again.line(PROXY)).isEmpty();
        assertThat(Files.readString(config)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(config)).isEqualTo(old);
    }

    @Test
    void aMovedCompanionReplacesTheLine() throws Exception {
        gitAsksQits();
        GitCompanionLine.sync(ORIGIN, PROXY, env);

        assertThat(GitCompanionLine.sync(ORIGIN, "http://127.0.0.1:50000", env).change())
                .isEqualTo(GitCompanionLine.Change.SET);

        assertThat(values(GitSetup.proxyKey(ORIGIN))).containsExactly("http://127.0.0.1:50000");
    }

    @Test
    void noCompanionRemovesTheLineOnceAndThenSaysNothing() throws Exception {
        gitAsksQits();
        GitCompanionLine.sync(ORIGIN, PROXY, env);

        GitCompanionLine.Result removed = GitCompanionLine.sync(ORIGIN, null, env);
        GitCompanionLine.Result again = GitCompanionLine.sync(ORIGIN, " ", env);

        assertThat(removed.change()).isEqualTo(GitCompanionLine.Change.REMOVED);
        assertThat(removed.line(null)).contains("git: " + ORIGIN + " goes direct again");
        assertThat(again.change()).isEqualTo(GitCompanionLine.Change.UNCHANGED);
        assertThat(values(GitSetup.proxyKey(ORIGIN))).isEmpty();
        assertThat(values("credential." + ORIGIN + ".helper")).containsExactly("", "!qits git-credential");
    }

    /** No git on the machine is a warning, never an exception, and nothing is written. */
    @Test
    void noGitIsAWarningNotAFailure() throws Exception {
        GitSetup.GIT = home.resolve("no-such-git").toString();
        try {
            GitCompanionLine.Result result = GitCompanionLine.sync(ORIGIN, PROXY, env);

            assertThat(result.change()).isEqualTo(GitCompanionLine.Change.NO_GIT);
            assertThat(result.line(PROXY)).hasValueSatisfying(line -> assertThat(line).startsWith("warning: "));
            assertThat(config).doesNotExist();
        } finally {
            GitSetup.GIT = "git";
        }
    }
}
