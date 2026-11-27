package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitCredentialCommandTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final String ORIGIN = "https://githost.qits.wohlben.eu";
    private static final String REQUEST = "protocol=https\nhost=githost.qits.wohlben.eu\npath=git/qits/qits-ci.git\n\n";
    private static final String OLD_ACCESS = FakeIdp.SECRET + "access-git";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private GitCredentialFile store;
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new GitCredentialFile(home.resolve("qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
        // stdout carries a token by design, for Git; stderr never does.
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result credential(String action, String stdin) {
        return credential(Map.of("XDG_CONFIG_HOME", home.toString()), action, stdin);
    }

    /** {@code QITS_PLATFORM} set by hand, no {@code QITS_TOKEN}: the in-platform home. */
    private Result platformSet(String action, String stdin) {
        return credential(Map.of("XDG_CONFIG_HOME", home.toString(), Mode.OVERRIDE, "true"), action, stdin);
    }

    private Result credential(Map<String, String> env, String action, String stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(env,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, "git-credential", action);
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private GitCredential seed(Duration accessLeft, String refreshToken, Duration signInLeft) throws Exception {
        GitCredential credential = new GitCredential(idp.url(), GitLoginFlow.CLIENT_ID, ORIGIN,
                OLD_ACCESS, T0.plus(accessLeft), refreshToken, T0.plus(signInLeft));
        try (ExclusiveLock ignored = store.lock()) {
            store.put(credential);
        }
        return credential;
    }

    private static String answer(String token) {
        return "username=oauth2\npassword=" + token + "\n\n";
    }

    /**
     * In-platform mode has no credential for Git: nothing that sets {@code QITS_PLATFORM} runs this
     * command. A sign-in file that happens to sit there is a workstation's, never read here — a
     * fresh one would be printed and a stale one refreshed if it were.
     */
    @Test
    void inThePlatformGetAnswersNothingAndNeverReadsTheSignInFile() throws Exception {
        seed(Duration.ofSeconds(10), idp.issueRefreshToken(), Duration.ofDays(30));
        byte[] before = Files.readAllBytes(store.path());

        assertThat(platformSet("get", REQUEST)).isEqualTo(new Result(0, "", ""));

        assertThat(idp.requests).isEmpty();
        assertThat(Files.readAllBytes(store.path())).isEqualTo(before);
    }

    @Test
    void inThePlatformNoActionWritesAFile() {
        String withPassword = REQUEST.replace("\n\n", "\nusername=oauth2\npassword=something\n\n");
        for (String action : new String[] {"get", "store", "erase"}) {
            assertThat(platformSet(action, withPassword)).as(action).isEqualTo(new Result(0, "", ""));
        }

        assertThat(store.path()).doesNotExist();
        assertThat(store.lockPath()).doesNotExist();
    }

    @Test
    void getHandsOverAFreshTokenAsItIs() throws Exception {
        seed(Duration.ofMinutes(5), idp.issueRefreshToken(), Duration.ofDays(30));

        Result r = credential("get", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo(answer(OLD_ACCESS));
        assertThat(r.err()).isEmpty();
        assertThat(idp.requests).isEmpty();
    }

    /** qits-2743: a fetch with the companion set up repairs the git host's proxy line, quietly. */
    @Test
    void getRepairsTheCompanionLineAndSaysNothingAboutIt() throws Exception {
        seed(Duration.ofMinutes(5), idp.issueRefreshToken(), Duration.ofDays(30));
        Path gitconfig = home.resolve("gitconfig");
        Map<String, String> env = Map.of("XDG_CONFIG_HOME", home.toString(), "GIT_CONFIG_GLOBAL", gitconfig.toString());
        GitSetup.configure(GitSetup.commands(ORIGIN, "qits"), env, new PrintStream(new ByteArrayOutputStream()));
        Files.writeString(home.resolve("qits/access.json"),
                "{\"peerId\":\"p-1\",\"proxy\":\"http://127.0.0.1:47813\",\"image\":\"i\",\"version\":\"1\"}");

        Result r = credential(env, "get", REQUEST);

        assertThat(r.out()).isEqualTo(answer(OLD_ACCESS));
        assertThat(r.err()).isEmpty();
        Process git = new ProcessBuilder("git", "config", "--file", gitconfig.toString(), "--get",
                GitSetup.proxyKey(ORIGIN)).start();
        assertThat(new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip())
                .isEqualTo("http://127.0.0.1:47813");
        git.waitFor();
    }

    @Test
    void withoutACompanionGetTouchesNoGitConfig() throws Exception {
        seed(Duration.ofMinutes(5), idp.issueRefreshToken(), Duration.ofDays(30));
        Path gitconfig = home.resolve("gitconfig");

        Result r = credential(Map.of("XDG_CONFIG_HOME", home.toString(), "GIT_CONFIG_GLOBAL", gitconfig.toString()),
                "get", REQUEST);

        assertThat(r.out()).isEqualTo(answer(OLD_ACCESS));
        assertThat(gitconfig).doesNotExist();
    }

    @Test
    void getRefreshesATokenWithAMinuteLeftAndWritesBeforePrinting() throws Exception {
        String old = seed(Duration.ofSeconds(59), idp.issueRefreshToken(), Duration.ofDays(30)).refreshToken();

        Result r = credential("get", REQUEST);

        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(idp.requests.getFirst()).containsEntry("client_id", "qits-git-workstation")
                .containsEntry("refresh_token", old).containsEntry("audience", "qits-platform");
        GitCredential now = store.find(ORIGIN).orElseThrow();
        assertThat(now.refreshToken()).isNotEqualTo(old);
        assertThat(now.accessExpiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(r.out()).isEqualTo(answer(now.accessToken()));
    }

    /**
     * A {@code git.json} written when a sign-in still kept its own audience: it loads, the refresh
     * asks for {@code qits-platform} whatever it names, and the rewritten entry drops the field.
     */
    @Test
    void anOldSignInWithItsOwnAudienceRefreshesForQitsPlatform() throws Exception {
        String old = idp.issueRefreshToken();
        Files.createDirectories(store.path().getParent());
        Files.writeString(store.path(), """
                {"credentials": {"%s": {
                  "idpUrl": "%s", "clientId": "qits-git-workstation", "audience": "dev-qits-githost",
                  "gitOrigin": "%s", "accessToken": "%s", "accessExpiresAt": "%s",
                  "refreshToken": "%s", "refreshExpiresAt": "%s"}}}
                """.formatted(ORIGIN, idp.url(), ORIGIN, OLD_ACCESS, T0.plusSeconds(10), old,
                T0.plus(Duration.ofDays(30))));

        Result r = credential("get", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(idp.requests.getFirst()).containsEntry("client_id", "qits-git-workstation")
                .containsEntry("refresh_token", old).containsEntry("audience", "qits-platform");
        GitCredential now = store.find(ORIGIN).orElseThrow();
        assertThat(r.out()).isEqualTo(answer(now.accessToken()));
        assertThat(Files.readString(store.path())).doesNotContain("audience");
    }

    /** Two Git processes at once: the second waits for the lock and uses the first one's refresh. */
    @Test
    void aRefreshMadeWhileWaitingForTheLockIsUsed() throws Exception {
        seed(Duration.ofSeconds(10), idp.issueRefreshToken(), Duration.ofDays(30));
        CountDownLatch readTheStaleFile = new CountDownLatch(1);
        Clock signalling = new Clock() {
            @Override
            public Instant instant() {
                readTheStaleFile.countDown();
                return time.instant();
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        GitCredential refreshedElsewhere = new GitCredential(idp.url(), GitLoginFlow.CLIENT_ID,
                ORIGIN, FakeIdp.SECRET + "access-elsewhere", T0.plusSeconds(900), FakeIdp.SECRET + "refresh-elsewhere",
                T0.plus(Duration.ofDays(30)));
        PrintStream err = new PrintStream(allErr, true, StandardCharsets.UTF_8);

        CompletableFuture<Optional<String>> got;
        try (ExclusiveLock held = store.lock()) {
            got = CompletableFuture.supplyAsync(() -> {
                try {
                    return new GitAccess(store, signalling).accessToken(ORIGIN, err);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(readTheStaleFile.await(10, TimeUnit.SECONDS)).isTrue();
            store.put(refreshedElsewhere);
        }

        assertThat(got.get(10, TimeUnit.SECONDS)).contains(FakeIdp.SECRET + "access-elsewhere");
        assertThat(idp.grants("refresh_token")).isZero();
    }

    @Test
    void eraseDropsTheAccessTokenAndKeepsTheSignIn() throws Exception {
        GitCredential seeded = seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));

        Result r = credential("erase", REQUEST.replace("\n\n", "\nusername=oauth2\npassword=" + OLD_ACCESS + "\n\n"));

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEmpty();
        GitCredential now = store.find(ORIGIN).orElseThrow();
        assertThat(now.accessToken()).isNull();
        assertThat(now.refreshToken()).isEqualTo(seeded.refreshToken());
        assertThat(idp.requests).isEmpty();

        // The next get refreshes instead of asking for a new sign-in.
        Result next = credential("get", REQUEST);
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(next.out()).isEqualTo(answer(store.find(ORIGIN).orElseThrow().accessToken()));
    }

    @Test
    void eraseOfAnotherTokenLeavesTheStoredOne() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));

        credential("erase", REQUEST.replace("\n\n", "\npassword=an-older-token\n\n"));

        assertThat(store.find(ORIGIN).orElseThrow().accessToken()).isEqualTo(OLD_ACCESS);
    }

    @Test
    void anOriginWithoutASignInGetsNoAnswer() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));

        Result other = credential("get", "protocol=https\nhost=github.com\n\n");
        Result noHost = credential("get", "protocol=https\n\n");

        assertThat(other).isEqualTo(new Result(0, "", ""));
        assertThat(noHost).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", REQUEST.replace("githost.qits", "githost.other"))).isEqualTo(new Result(0, "", ""));
    }

    @Test
    void noFileAtAllGetsNoAnswer() {
        assertThat(credential("get", REQUEST)).isEqualTo(new Result(0, "", ""));
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void aRefusedRefreshSaysToSignInAgainAndPrintsNothing() throws Exception {
        seed(Duration.ofSeconds(-5), FakeIdp.SECRET + "revoked", Duration.ofDays(30));

        Result r = credential("get", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Git sign-in ended — run `qits git-login`.").contains("invalid_grant");
    }

    @Test
    void anEndedSignInIsNotRefreshed() throws Exception {
        seed(Duration.ofSeconds(-5), idp.issueRefreshToken(), Duration.ofSeconds(-1));

        Result r = credential("get", REQUEST);

        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Git sign-in ended");
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void storeIsIgnored() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));
        String before = Files.readString(store.path());

        Result r = credential("store", REQUEST.replace("\n\n", "\nusername=someone\npassword=something\n\n"));

        assertThat(r).isEqualTo(new Result(0, "", ""));
        assertThat(Files.readString(store.path())).isEqualTo(before);
    }
}
