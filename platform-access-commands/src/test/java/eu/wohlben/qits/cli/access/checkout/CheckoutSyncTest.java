package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code qits checkout sync} against real Git repositories in a temporary directory: a wrapper with
 * two submodules, one of which declares a nested webui, the way the estate is built ({@code ignore =
 * all}, {@code update = merge}, {@code branch = main}, relative URLs), and a workstation clone of it
 * with every repository on its own {@code main}. No platform, no session and no token: the command
 * needs none.
 */
class CheckoutSyncTest {

    private static final String SVC = "qits-demo-service";
    private static final String LIB = "qits-demo-javalib";
    private static final String WEBUI = "qits-demo-webui";
    private static final String SVC_PATH = "components/qits-demo/" + SVC;
    private static final String LIB_PATH = "components/qits-demo/" + LIB;
    private static final String WEBUI_IN_SVC = "service/src/main/webui";
    private static final String WEBUI_PATH = SVC_PATH + "/" + WEBUI_IN_SVC;

    @TempDir
    Path tmp;

    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** The origins: plain repositories side by side, so {@code ../<name>} resolves among them. */
    private Path upstream;
    /** The workstation clone of the wrapper. */
    private Path clone;

    @BeforeEach
    void build() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        // No session file and no token: the command must not need either.
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("GIT_CONFIG_GLOBAL", tmp.resolve("gitconfig").toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "Test");
        env.put("GIT_AUTHOR_EMAIL", "test@example.invalid");
        env.put("GIT_COMMITTER_NAME", "Test");
        env.put("GIT_COMMITTER_EMAIL", "test@example.invalid");
        // A submodule whose url is a local path is refused by default since git 2.38.
        git(tmp, "config", "--file", tmp.resolve("gitconfig").toString(), "protocol.file.allow", "always");

        upstream = Files.createDirectories(tmp.resolve("origins"));
        Path webui = repository(WEBUI);
        commit(webui, "index.html", "one");
        Path svc = repository(SVC);
        commit(svc, "Service.java", "one");
        declare(svc, WEBUI, WEBUI_IN_SVC);
        Path lib = repository(LIB);
        commit(lib, "Lib.java", "one");
        Path wrapper = repository("qits-qits");
        commit(wrapper, "README.md", "one");
        declare(wrapper, SVC, SVC_PATH);
        declare(wrapper, LIB, LIB_PATH);

        // The fresh clone CLAUDE.md describes: init, and every submodule on its own main.
        clone = tmp.resolve("clone");
        git(tmp, "clone", "-q", wrapper.toString(), clone.toString());
        git(clone, "submodule", "update", "-q", "--init", "--recursive");
        git(clone, "submodule", "foreach", "-q", "--recursive", "git switch -q main");
        assertThat(branch(clone.resolve(WEBUI_PATH))).isEqualTo("main");
    }

    // ---------- the fixture ----------

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(String.join(" ", argv) + "\n" + output).isZero();
        return output.strip();
    }

    private Path repository(String name) throws Exception {
        Path repo = Files.createDirectories(upstream.resolve(name));
        git(repo, "init", "-q", "--initial-branch=main");
        return repo;
    }

    private String commit(Path repo, String file, String text) throws Exception {
        Files.writeString(repo.resolve(file), text);
        git(repo, "add", file);
        git(repo, "commit", "-q", "-m", "change " + file + " to " + text);
        return git(repo, "rev-parse", "HEAD");
    }

    /** A submodule entry as the wrapper writes one, with a relative URL among the origins. */
    private void declare(Path parent, String name, String path) throws Exception {
        git(parent, "submodule", "add", "-q", "--name", name, "../" + name, path);
        git(parent, "config", "-f", ".gitmodules", "submodule." + name + ".ignore", "all");
        git(parent, "config", "-f", ".gitmodules", "submodule." + name + ".update", "merge");
        git(parent, "config", "-f", ".gitmodules", "submodule." + name + ".branch", "main");
        git(parent, "add", ".gitmodules", path);
        git(parent, "commit", "-q", "-m", "declare " + name);
    }

    private String head(Path repo) throws Exception {
        return git(repo, "rev-parse", "HEAD");
    }

    private String branch(Path repo) throws Exception {
        return git(repo, "rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * The repository's own status, untracked files included. Its submodules are left out: one that
     * moved on its own main is the drift {@code ignore = all} hides, not a change of this one.
     */
    private String status(Path repo) throws Exception {
        return git(repo, "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=all");
    }

    private Path origin(String name) {
        return upstream.resolve(name);
    }

    private Path in(String path) {
        return clone.resolve(path);
    }

    private CliContext context() {
        FakeTime time = new FakeTime(Instant.parse("2026-11-11T10:00:00Z"));
        return new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
    }

    private int sync() {
        return TestCli.execute(context(), "checkout", "sync", "--path", clone.toString());
    }

    /** Every origin one commit (the webui two) ahead of the clone. */
    private void advanceEveryOrigin() throws Exception {
        commit(origin("qits-qits"), "README.md", "two");
        commit(origin(SVC), "Service.java", "two");
        commit(origin(WEBUI), "index.html", "two");
        commit(origin(WEBUI), "index.html", "three");
        commit(origin(LIB), "Lib.java", "two");
    }

    // ---------- fast-forwarding ----------

    @Test
    void everyRepositoryNestedOnesIncludedIsFastForwardedAndCounted() throws Exception {
        advanceEveryOrigin();

        assertThat(sync()).as(err()).isZero();

        assertThat(out().lines().toList()).containsExactly(
                ".  +1 commit",
                SVC_PATH + "  +1 commit",
                WEBUI_PATH + "  +2 commits",
                LIB_PATH + "  +1 commit",
                "4 moved, 0 new, 0 skipped, 0 up to date");
        assertThat(head(clone)).isEqualTo(head(origin("qits-qits")));
        assertThat(head(in(SVC_PATH))).isEqualTo(head(origin(SVC)));
        assertThat(head(in(WEBUI_PATH))).isEqualTo(head(origin(WEBUI)));
        assertThat(head(in(LIB_PATH))).isEqualTo(head(origin(LIB)));
        for (String path : List.of(".", SVC_PATH, WEBUI_PATH, LIB_PATH)) {
            assertThat(branch(in(path))).as(path).isEqualTo("main");
        }
    }

    @Test
    void anUpToDateCheckoutPrintsOnlyTheSummary() throws Exception {
        assertThat(sync()).as(err()).isZero();

        assertThat(out().lines().toList()).containsExactly("0 moved, 0 new, 0 skipped, 4 up to date");
    }

    /** The submodules follow main, so a parent's gitlinks fall behind them; that is never staged. */
    @Test
    void noIndexEverGainsAStagedGitlink() throws Exception {
        advanceEveryOrigin();

        assertThat(sync()).as(err()).isZero();

        assertThat(git(clone, "diff", "--cached", "--name-only", "--ignore-submodules=none")).isEmpty();
        assertThat(git(in(SVC_PATH), "diff", "--cached", "--name-only", "--ignore-submodules=none")).isEmpty();
        assertThat(git(clone, "ls-files", "--stage", "--", SVC_PATH)).as("the gitlink the wrapper's main records")
                .isEqualTo(git(origin("qits-qits"), "ls-files", "--stage", "--", SVC_PATH));
    }

    @Test
    void anUntrackedFileDoesNotBlockTheFastForward() throws Exception {
        advanceEveryOrigin();
        Files.writeString(in(LIB_PATH).resolve("notes.txt"), "mine");

        assertThat(sync()).as(err()).isZero();

        assertThat(out()).contains(LIB_PATH + "  +1 commit");
        assertThat(head(in(LIB_PATH))).isEqualTo(head(origin(LIB)));
        assertThat(in(LIB_PATH).resolve("notes.txt")).hasContent("mine");
    }

    @Test
    void anUntrackedFileTheIncomingTreeWouldOverwriteIsGitsRefusalAndNamed() throws Exception {
        commit(origin(LIB), "New.java", "theirs");
        Files.writeString(in(LIB_PATH).resolve("New.java"), "mine");
        String before = head(in(LIB_PATH));

        assertThat(sync()).isEqualTo(1);

        assertThat(out()).contains(LIB_PATH + "  skipped: fast-forward refused: error: The following untracked "
                + "working tree files would be overwritten by merge:");
        assertThat(head(in(LIB_PATH))).isEqualTo(before);
        assertThat(in(LIB_PATH).resolve("New.java")).hasContent("mine");
    }

    // ---------- a newly declared submodule ----------

    @Test
    void aNewlyDeclaredSubmoduleIsInitialisedAndEndsOnMain() throws Exception {
        Path extra = repository("qits-demo-extra");
        commit(extra, "Extra.java", "one");
        declare(origin("qits-qits"), "qits-demo-extra", "components/qits-demo-extra/qits-demo-extra");
        String extraPath = "components/qits-demo-extra/qits-demo-extra";
        // Ahead of the gitlink the wrapper records: it ends at main's tip, not the gitlink.
        String tip = commit(extra, "Extra.java", "two");

        assertThat(sync()).as(err()).isZero();

        assertThat(out().lines().toList()).containsExactly(
                ".  +1 commit",
                extraPath + "  new",
                "1 moved, 1 new, 0 skipped, 3 up to date");
        assertThat(branch(in(extraPath))).isEqualTo("main");
        assertThat(git(in(extraPath), "rev-parse", "--abbrev-ref", "main@{upstream}")).isEqualTo("origin/main");
        assertThat(head(in(extraPath))).isEqualTo(tip);
        assertThat(git(clone, "diff", "--cached", "--name-only", "--ignore-submodules=none")).isEmpty();
    }

    // ---------- skipped, named and unchanged ----------

    @Test
    void aTrackedChangeAnAheadMainAndAnotherBranchAreSkippedNamedAndUnchanged() throws Exception {
        advanceEveryOrigin();
        Files.writeString(in(LIB_PATH).resolve("Lib.java"), "edited");
        commit(in(SVC_PATH), "Local.java", "mine");
        git(in(WEBUI_PATH), "switch", "-q", "-c", "feature");
        Map<String, List<String>> before = snapshot(SVC_PATH, WEBUI_PATH, LIB_PATH);

        assertThat(sync()).isEqualTo(1);

        assertThat(out().lines().toList()).containsExactly(
                ".  +1 commit",
                SVC_PATH + "  skipped: 1 local commit ahead, 1 behind",
                WEBUI_PATH + "  skipped: on feature",
                LIB_PATH + "  skipped: tracked changes (Lib.java)",
                "1 moved, 0 new, 3 skipped, 0 up to date");
        assertThat(snapshot(SVC_PATH, WEBUI_PATH, LIB_PATH)).isEqualTo(before);
    }

    @Test
    void anAheadMainThatIsNotBehindReadsAheadAlone() throws Exception {
        commit(in(LIB_PATH), "Local.java", "mine");
        commit(in(LIB_PATH), "Local.java", "mine again");

        assertThat(sync()).isEqualTo(1);

        assertThat(out()).contains(LIB_PATH + "  skipped: 2 local commits ahead\n");
    }

    @Test
    void aStagedChangeIsATrackedChange() throws Exception {
        advanceEveryOrigin();
        Files.writeString(in(LIB_PATH).resolve("Staged.java"), "staged");
        git(in(LIB_PATH), "add", "Staged.java");
        String before = head(in(LIB_PATH));

        assertThat(sync()).isEqualTo(1);

        assertThat(out()).contains(LIB_PATH + "  skipped: tracked changes (Staged.java)");
        assertThat(head(in(LIB_PATH))).isEqualTo(before);
        assertThat(git(in(LIB_PATH), "diff", "--cached", "--name-only")).isEqualTo("Staged.java");
    }

    /** A root detached at no tag is skipped like any other repository; its submodules still sync. */
    @Test
    void aDetachedRepositoryIsSkippedAndTheRestStillSyncs() throws Exception {
        advanceEveryOrigin();
        git(clone, "switch", "-q", "--detach");
        git(in(LIB_PATH), "switch", "-q", "--detach");
        Map<String, List<String>> before = snapshot(".", LIB_PATH);

        assertThat(sync()).isEqualTo(1);

        assertThat(out().lines().toList()).containsExactly(
                SVC_PATH + "  +1 commit",
                WEBUI_PATH + "  +2 commits",
                ".  skipped: detached",
                LIB_PATH + "  skipped: detached",
                "2 moved, 0 new, 2 skipped, 0 up to date");
        assertThat(snapshot(".", LIB_PATH)).isEqualTo(before);
    }

    @Test
    void aBrokenGitdirPointerIsSkippedNamedAndNotDescendedInto() throws Exception {
        advanceEveryOrigin();
        String pointer = "../../../.git/modules/qits-docs/qits-docs";
        Files.writeString(in(SVC_PATH).resolve(".git"), "gitdir: " + pointer + "\n");

        assertThat(sync()).isEqualTo(1);

        assertThat(out().lines().toList()).containsExactly(
                ".  +1 commit",
                LIB_PATH + "  +1 commit",
                SVC_PATH + "  skipped: broken gitdir pointer -> " + pointer + "; repair it with `git -C . "
                        + "submodule absorbgitdirs`, or remove it and initialise it again",
                "2 moved, 0 new, 1 skipped, 0 up to date");
        assertThat(Files.readString(in(SVC_PATH).resolve(".git")).strip()).isEqualTo("gitdir: " + pointer);
    }

    @Test
    void aFailedFetchIsNamedAndEveryOtherRepositoryStillSyncs() throws Exception {
        advanceEveryOrigin();
        git(in(LIB_PATH), "remote", "set-url", "origin", tmp.resolve("nowhere").toString());

        assertThat(sync()).isEqualTo(1);

        assertThat(out()).contains(".  +1 commit", SVC_PATH + "  +1 commit", WEBUI_PATH + "  +2 commits",
                LIB_PATH + "  skipped: fetch failed: ", "3 moved, 0 new, 1 skipped, 0 up to date");
    }

    // ---------- refusals ----------

    @Test
    void aRootDetachedAtATagIsAProjectAgentContainerAndNothingIsTouched() throws Exception {
        git(clone, "tag", "2026.1111.100000");
        git(clone, "switch", "-q", "--detach", "2026.1111.100000");
        advanceEveryOrigin();
        Map<String, List<String>> before = snapshot(".", SVC_PATH, WEBUI_PATH, LIB_PATH);
        String remote = git(clone, "rev-parse", "refs/remotes/origin/main");

        assertThat(sync()).isEqualTo(2);

        assertThat(out()).isEmpty();
        assertThat(err()).contains("detached at the tag 2026.1111.100000", "qits checkout-daemon",
                "nothing was touched");
        assertThat(snapshot(".", SVC_PATH, WEBUI_PATH, LIB_PATH)).isEqualTo(before);
        assertThat(git(clone, "rev-parse", "refs/remotes/origin/main")).as("not even fetched").isEqualTo(remote);
    }

    @Test
    void aCheckoutADaemonHoldsIsAProjectAgentContainer() throws Exception {
        Path proc = Files.createDirectories(tmp.resolve("proc"));
        process(proc, 4242, clone, "/usr/local/bin/qits", "checkout-daemon", "--path", clone.toString());

        assertThatThrownBy(() -> Sync.of(clone, env, proc))
                .isInstanceOf(CliFailure.class)
                .hasMessageContaining("`qits checkout-daemon` (process 4242) holds it")
                .satisfies(failure -> assertThat(((CliFailure) failure).exitCode()).isEqualTo(CliFailure.USAGE));
    }

    @Test
    void theDaemonIsFoundByItsArgvNeverByASubstring() throws Exception {
        Path proc = Files.createDirectories(tmp.resolve("proc"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        // The shell that asked, and a shell running it, carry the words but are not the daemon.
        process(proc, 10, clone, "pgrep", "-af", "qits checkout-daemon --path " + clone);
        process(proc, 11, clone, "bash", "-c", "qits checkout-daemon --path " + clone);
        process(proc, 12, clone, "qits", "checkout-daemon", "--path", elsewhere.toString());
        process(proc, 13, clone, "qits", "checkout", "sync");
        Files.createDirectories(proc.resolve("self"));

        assertThat(Sync.daemonHolding(clone, proc)).isEmpty();

        process(proc, 20, elsewhere, "qits", "checkout-daemon", "--path=../clone");
        assertThat(Sync.daemonHolding(clone, proc)).isEqualTo(Optional.of(20L));
    }

    @Test
    void aDaemonWithNoPathHoldsTheDirectoryItRunsIn() throws Exception {
        Path proc = Files.createDirectories(tmp.resolve("proc"));
        process(proc, 30, clone, "qits", "checkout-daemon", "--once");

        assertThat(Sync.daemonHolding(clone, proc)).isEqualTo(Optional.of(30L));
        assertThat(Sync.daemonHolding(clone, tmp.resolve("no-proc-here"))).isEmpty();
    }

    @Test
    void notAGitRepositoryIsUsage() throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("plain"));

        assertThat(TestCli.execute(context(), "checkout", "sync", "--path", plain.toString())).isEqualTo(2);

        assertThat(err()).contains("is not in a Git repository");
        assertThat(out()).isEmpty();
    }

    /** In-platform mode, which refuses every platform call without a handed credential, changes nothing here. */
    @Test
    void itNeedsNoCredentialInAnyMode() throws Exception {
        advanceEveryOrigin();
        env.put("QITS_PLATFORM", "true");

        assertThat(sync()).as(err()).isZero();

        assertThat(out()).contains("4 moved, 0 new, 0 skipped, 0 up to date");
    }

    /** {@code /proc/<pid>}: its NUL-separated argv, and its working directory as a link. */
    private static void process(Path proc, long pid, Path cwd, String... argv) throws Exception {
        Path dir = Files.createDirectories(proc.resolve(Long.toString(pid)));
        Files.writeString(dir.resolve("cmdline"), String.join("\0", argv) + "\0");
        Files.createSymbolicLink(dir.resolve("cwd"), cwd);
    }

    /** HEAD and the full status of each repository, to prove a skipped one was left as it was. */
    private Map<String, List<String>> snapshot(String... paths) throws Exception {
        Map<String, List<String>> taken = new HashMap<>();
        for (String path : paths) {
            taken.put(path, List.of(head(in(path)), git(in(path), "rev-parse", "--abbrev-ref", "HEAD"),
                    status(in(path))));
        }
        return taken;
    }
}
