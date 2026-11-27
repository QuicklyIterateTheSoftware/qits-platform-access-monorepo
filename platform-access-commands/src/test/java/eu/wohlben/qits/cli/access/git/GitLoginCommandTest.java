package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The command as a person runs it; the browser is the test, reading the printed address. */
class GitLoginCommandTest {

    private static final String ORIGIN = GitLoginFlowTest.ORIGIN;

    @TempDir
    Path home;

    private FakeIdp idp;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        env.put("XDG_CONFIG_HOME", home.toString());
        // `git config --global` writes here, never to the person's ~/.gitconfig.
        env.put("GIT_CONFIG_GLOBAL", home.resolve("gitconfig").toString());
    }

    @AfterEach
    void stop() {
        idp.close();
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The sign-in address the last run printed. */
    private String address;

    /** Runs the command, and plays the browser once the address is on stdout. */
    private int gitLogin(String... extra) throws Exception {
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new FakeTime(Instant.parse("2026-09-12T10:00:00Z")), new FakeTime(Instant.EPOCH), TokenClient::new, stop -> { });
        List<String> args = new java.util.ArrayList<>(List.of("git-login", "--idp-url", idp.url(), "--git-host",
                ORIGIN + "/git/qits", "--no-browser", "--timeout", "20"));
        args.addAll(List.of(extra));
        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(() -> TestCli.execute(context, args.toArray(String[]::new)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        address = null;
        while (address == null && System.nanoTime() < deadline && !run.isDone()) {
            address = out().lines().map(String::strip).filter(l -> l.contains("/authorize?")).findFirst().orElse(null);
            Thread.sleep(20);
        }
        assertThat(address).as("the printed sign-in address").isNotNull();
        GitLoginFlowTest.signIn(idp, "code-1", null).accept(address);
        return run.get(20, TimeUnit.SECONDS);
    }

    private List<String> helpers() throws Exception {
        Process git = new ProcessBuilder("git", "config", "--file", home.resolve("gitconfig").toString(), "--get-all",
                "credential." + ORIGIN + ".helper").start();
        List<String> values = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        git.waitFor();
        return values;
    }

    @Test
    void itPrintsTheGitSetupForThisHostOnly() throws Exception {
        assertThat(gitLogin()).isZero();

        assertThat(out()).contains("Signed in for Git pushes to " + ORIGIN)
                .contains("  git config --global --replace-all credential." + ORIGIN + ".helper ''")
                .contains("  git config --global --add credential." + ORIGIN + ".helper '!qits git-credential'");
        assertThat(new GitCredentialFile(home.resolve("qits")).find(ORIGIN)).isPresent();
        assertThat(home.resolve("gitconfig")).doesNotExist();
    }

    @Test
    void itAsksForTheOneAudienceEveryPlatformServiceAccepts() throws Exception {
        assertThat(gitLogin()).isZero();

        assertThat(GitLoginFlowTest.query(address)).containsEntry("audience", "qits-platform");
    }

    @Test
    void thereIsNoAudienceOption() throws Exception {
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new FakeTime(Instant.parse("2026-09-12T10:00:00Z")), new FakeTime(Instant.EPOCH), TokenClient::new, stop -> { });

        assertThat(TestCli.execute(context, "git-login", "--idp-url", idp.url(), "--audience", "dev-qits-githost"))
                .isEqualTo(2);
        assertThat(new GitCredentialFile(home.resolve("qits")).find(ORIGIN)).isEmpty();
    }

    @Test
    void configureRunsItAndRunningItAgainChangesNothing() throws Exception {
        assertThat(gitLogin("--configure")).isZero();

        assertThat(out()).contains("Git is set up");
        assertThat(helpers()).containsExactly("", "!qits git-credential");

        GitSetup.configure(GitSetup.commands(ORIGIN, "qits"), env, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(helpers()).containsExactly("", "!qits git-credential");
    }

    private List<String> proxies() throws Exception {
        Process git = new ProcessBuilder("git", "config", "--file", home.resolve("gitconfig").toString(), "--get-all",
                GitSetup.proxyKey(ORIGIN)).start();
        List<String> values = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        git.waitFor();
        return values;
    }

    private void companionRunsAt(String proxy) throws Exception {
        java.nio.file.Files.createDirectories(home.resolve("qits"));
        java.nio.file.Files.writeString(home.resolve("qits/access.json"),
                "{\"peerId\":\"p-1\",\"proxy\":\"" + proxy + "\",\"image\":\"i\",\"version\":\"1\"}");
    }

    /** qits-2743: the git host is private, so while the companion runs Git goes through it for that host. */
    @Test
    void configureSendsTheGitHostThroughARunningCompanion() throws Exception {
        companionRunsAt("http://127.0.0.1:47813");

        assertThat(gitLogin("--configure")).isZero();

        assertThat(helpers()).containsExactly("", "!qits git-credential");
        assertThat(proxies()).containsExactly("http://127.0.0.1:47813");
        assertThat(out()).contains("through the network-access companion at http://127.0.0.1:47813");
    }

    @Test
    void withACompanionThePrintedSetupNamesTheProxyLineToo() throws Exception {
        companionRunsAt("http://127.0.0.1:47813");

        assertThat(gitLogin()).isZero();

        assertThat(out()).contains("  git config --global --replace-all http." + ORIGIN + ".proxy http://127.0.0.1:47813");
        assertThat(home.resolve("gitconfig")).doesNotExist();
    }

    @Test
    void withoutACompanionNoProxyIsSet() throws Exception {
        assertThat(gitLogin("--configure")).isZero();

        assertThat(proxies()).isEmpty();
        assertThat(out()).doesNotContain(".proxy");
    }

    @Test
    void theProxyIsForTheGitHostAloneAndUnsettingItTwiceIsDone() throws Exception {
        assertThat(GitSetup.proxyCommand(ORIGIN, "http://127.0.0.1:47813")).containsExactly("git", "config",
                "--global", "--replace-all", "http." + ORIGIN + ".proxy", "http://127.0.0.1:47813");
        PrintStream quiet = new PrintStream(err, true, StandardCharsets.UTF_8);
        GitSetup.configure(List.of(GitSetup.proxyCommand(ORIGIN, "http://127.0.0.1:47813")), env, quiet);
        assertThat(proxies()).containsExactly("http://127.0.0.1:47813");

        GitSetup.configure(List.of(GitSetup.unproxyCommand(ORIGIN)), env, quiet);
        GitSetup.configure(List.of(GitSetup.unproxyCommand(ORIGIN)), env, quiet);
        assertThat(proxies()).isEmpty();
    }

    @Test
    void helpedIsTrueOnlyOnceTheCredentialHelperIsSet() throws Exception {
        assertThat(GitSetup.helped(ORIGIN, env)).isFalse();
        GitSetup.configure(GitSetup.commands(ORIGIN, "/opt/my tools/qits"), env,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(GitSetup.helped(ORIGIN, env)).isTrue();
        assertThat(GitSetup.helped("https://github.com", env)).isFalse();
    }

    @Test
    void aPathWithASpaceIsQuotedForGitAndTheShell() {
        List<List<String>> commands = GitSetup.commands(ORIGIN, "/home/a b/qits");
        assertThat(commands.get(1).getLast()).isEqualTo("!\"/home/a b/qits\" git-credential");
        assertThat(GitSetup.shellLine(commands.get(1))).endsWith("'!\"/home/a b/qits\" git-credential'");
    }

    private CliContext quiet(Map<String, String> variables) {
        return new CliContext(Map.copyOf(variables), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new FakeTime(Instant.parse("2026-09-12T10:00:00Z")), new FakeTime(Instant.EPOCH), TokenClient::new, stop -> { });
    }

    @Test
    void withNoFlagNoVariableAndNoSessionTheIdpIsAUsageFailureNamingQitsDomain() {
        CliContext context = quiet(env);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GitLoginCommand.idpUrl(null, env, context))
                .isInstanceOfSatisfying(eu.wohlben.qits.cli.access.platform.CliFailure.class,
                        failure -> assertThat(failure.exitCode())
                                .isEqualTo(eu.wohlben.qits.cli.access.platform.CliFailure.USAGE))
                .hasMessageContaining("QITS_DOMAIN")
                .hasMessageNotContaining("wohlben");
    }

    @Test
    void theStoredSessionNamesTheIdpWhenNothingElseDoes() throws Exception {
        new eu.wohlben.qits.cli.access.session.SessionFile(home.resolve("qits")).write(
                new eu.wohlben.qits.cli.access.session.Session("https://idp.qits.example.com/idp", "qits-cli",
                        "access", Instant.parse("2026-09-12T10:10:00Z"), "refresh",
                        Instant.parse("2026-10-12T10:00:00Z")));

        assertThat(GitLoginCommand.idpUrl(null, env, quiet(env))).isEqualTo("https://idp.qits.example.com/idp");
    }

    @Test
    void withNoSessionTheDomainNamesTheIdp() throws Exception {
        env.put("QITS_DOMAIN", "example.org");

        assertThat(GitLoginCommand.idpUrl(null, env, quiet(env))).isEqualTo("https://idp.qits.example.org/idp");
    }
}
