package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Git side of {@code qits checkout sync}: a workstation clone of a wrapper, every repository in
 * it — the root, each submodule, each submodule of a submodule — fast-forwarded on its own
 * {@code main} to {@code origin/main}, and every newly declared submodule initialised and put on
 * {@code main}. The opposite of {@link Checkout}, which detaches everything at a release.
 * <p>
 * <b>It only ever fast-forwards.</b> A repository that is not on {@code main}, has tracked changes,
 * has commits {@code origin/main} does not, or whose fast-forward git refuses, is left exactly as it
 * was and named with the reason. Nothing here resets, stashes, switches over local work, adds a path
 * to an index, or recurses into submodules through git ({@code --recurse-submodules},
 * {@code submodule.recurse}): every repository is visited by this class, one at a time, so each one
 * is asked before it is moved. A gitlink moved in a parent's index is the drift {@code ignore = all}
 * hides; the bumps own it, and it is never staged here.
 * <p>
 * Git's own output is foreign text, so every piece of it that reaches a person goes through
 * {@link SafeText#line}.
 */
final class Sync {

    static final String MAIN = "main";
    static final String UPSTREAM = "refs/remotes/origin/main";
    static final String ROOT_LABEL = ".";

    /** How many of a repository's changed paths its skip line names before it counts the rest. */
    private static final int NAMED_CHANGES = 3;

    /** No git process may recurse into submodules on its own, whatever the person's config says. */
    private static final String[] NO_RECURSION = {"-c", "submodule.recurse=false"};

    private final Path root;
    private final Map<String, String> env;
    private final List<String> moved = new ArrayList<>();
    private final List<String> fresh = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private int upToDate;

    private Sync(Path root, Map<String, String> env) {
        this.root = root;
        this.env = env;
    }

    /**
     * The repository {@code dir} is in, at its top level, ready to sync, or a refusal with exit code
     * 2: not a Git repository, or the checkout of a project agent container (see
     * {@link #refuseProjectAgentContainer}).
     *
     * @param proc where the process table is read from: {@code /proc}, or a test's own
     */
    static Sync of(Path dir, Map<String, String> env, Path proc) throws CliFailure, InterruptedException {
        Map<String, String> environment = new HashMap<>(env);
        // A fetch that wants a password fails and is named, rather than waiting on a prompt
        // between two repositories of thirty.
        environment.putIfAbsent("GIT_TERMINAL_PROMPT", "0");
        String top = new Checkout.Git(dir, environment).ask("rev-parse", "--show-toplevel");
        if (top == null || top.isBlank()) {
            throw new CliFailure(SafeText.line(dir.toString()) + " is not in a Git repository.", CliFailure.USAGE);
        }
        Path root = Path.of(top);
        refuseProjectAgentContainer(root, environment, proc);
        return new Sync(root, environment);
    }

    /**
     * Refuses, touching nothing, a checkout that follows releases rather than {@code main}: a
     * project agent container's {@code /workspace}. Two signs, either is enough: the root is
     * detached at a commit a tag names (a release), or a {@code qits checkout-daemon} holds this
     * directory. A root detached at no tag is not refused here; it is skipped as {@code detached},
     * like any other repository off {@code main}.
     */
    static void refuseProjectAgentContainer(Path root, Map<String, String> env, Path proc)
            throws CliFailure, InterruptedException {
        Checkout.Git git = new Checkout.Git(root, env);
        if (git.ask("symbolic-ref", "--quiet", "HEAD") == null) {
            String tags = git.ask("tag", "--points-at", "HEAD");
            if (tags != null && !tags.isBlank()) {
                throw refusal(root, "its root is detached at the tag "
                        + SafeText.line(tags.lines().findFirst().orElse("").strip()));
            }
        }
        Optional<Long> daemon = daemonHolding(root, proc);
        if (daemon.isPresent()) {
            throw refusal(root, "`qits checkout-daemon` (process " + daemon.get() + ") holds it");
        }
    }

    private static CliFailure refusal(Path root, String why) {
        return new CliFailure(SafeText.line(root.toString()) + " is a checkout that follows releases, not main: " + why
                + ". That is a project agent container's checkout, and `qits checkout-daemon` keeps it current; "
                + "nothing was touched.", CliFailure.USAGE);
    }

    /**
     * The process, if any, whose command line is {@code qits checkout-daemon} with a {@code --path}
     * that is {@code root} (or no {@code --path}, run in {@code root}). The argv is compared word by
     * word, never searched as a string: a {@code pgrep -f} style substring match also finds the
     * shell that asked. Where {@code /proc} cannot be read, nothing is found.
     */
    static Optional<Long> daemonHolding(Path root, Path proc) {
        Path held;
        try {
            held = root.toRealPath();
        } catch (IOException gone) {
            return Optional.empty();
        }
        long self = ProcessHandle.current().pid();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(proc, entry ->
                entry.getFileName().toString().chars().allMatch(Character::isDigit))) {
            for (Path entry : entries) {
                long pid = Long.parseLong(entry.getFileName().toString());
                if (pid == self) {
                    continue;
                }
                Optional<String> path = heldPath(argv(entry.resolve("cmdline")));
                if (path.isEmpty()) {
                    continue;
                }
                try {
                    Path target = Path.of(path.get());
                    if (!target.isAbsolute()) {
                        target = entry.resolve("cwd").toRealPath().resolve(target);
                    }
                    if (target.toRealPath().equals(held)) {
                        return Optional.of(pid);
                    }
                } catch (IOException | RuntimeException unreadable) {
                    // Another user's process, or one that has ended: it cannot be told apart, so skip it.
                }
            }
        } catch (IOException | RuntimeException noProc) {
            // No /proc (not Linux), or not readable: the tag check above is the only sign.
        }
        return Optional.empty();
    }

    /** {@code /proc/<pid>/cmdline}: the argv, NUL-separated. Empty when it cannot be read. */
    private static List<String> argv(Path cmdline) {
        try {
            String raw = new String(Files.readAllBytes(cmdline), StandardCharsets.UTF_8);
            return raw.isEmpty() ? List.of() : Arrays.asList(raw.split("\0"));
        } catch (IOException | RuntimeException gone) {
            return List.of();
        }
    }

    /**
     * The {@code --path} a {@code qits checkout-daemon} argv names ({@code .} when it names none),
     * or empty when the argv is not that command: the program is {@code qits} (by its file name)
     * and its first argument is {@code checkout-daemon}.
     */
    static Optional<String> heldPath(List<String> argv) {
        if (argv.size() < 2) {
            return Optional.empty();
        }
        String program = argv.get(0);
        program = program.substring(program.lastIndexOf('/') + 1);
        if (!program.equals("qits") || !argv.get(1).equals("checkout-daemon")) {
            return Optional.empty();
        }
        String path = ".";
        for (int i = 2; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (arg.equals("--path") && i + 1 < argv.size()) {
                path = argv.get(++i);
            } else if (arg.startsWith("--path=")) {
                path = arg.substring("--path=".length());
            }
        }
        return Optional.of(path);
    }

    /**
     * Brings every repository of the checkout forward, then prints what moved, what is new and what
     * was skipped, and a summary line. Answers the exit code: 0 when nothing was skipped, 1 when
     * anything was (a git command that failed included). The root goes first, so the submodule
     * steps read the {@code .gitmodules} it now has.
     */
    int run(PrintStream out) throws InterruptedException {
        fastForward(root, ROOT_LABEL);
        submodulesOf(root, "", 1);
        moved.forEach(out::println);
        fresh.forEach(out::println);
        skipped.forEach(out::println);
        out.println(moved.size() + " moved, " + fresh.size() + " new, " + skipped.size() + " skipped, "
                + upToDate + " up to date");
        out.flush();
        return skipped.isEmpty() ? 0 : CliFailure.FAILED;
    }

    /**
     * Every submodule {@code repo} declares, at {@code depth} below the root: a new one initialised,
     * one with a broken {@code gitdir:} pointer named and not descended into, every other one
     * fast-forwarded. Then each one's own submodules, down to {@link Checkout#MAX_SUBMODULE_DEPTH}.
     */
    private void submodulesOf(Path repo, String prefix, int depth) throws InterruptedException {
        if (depth > Checkout.MAX_SUBMODULE_DEPTH) {
            return;
        }
        Map<String, String> declared;
        try {
            declared = Submodules.declared(repo, env);
        } catch (CliFailure unreadable) {
            skip(prefix.isEmpty() ? ROOT_LABEL : prefix.substring(0, prefix.length() - 1),
                    "cannot read its .gitmodules: " + unreadable.getMessage());
            return;
        }
        for (String sub : declared.values()) {
            Path dir = repo.resolve(sub);
            String label = prefix + SafeText.line(sub);
            if (!Submodules.initialised(repo, sub)) {
                if (initialise(repo, sub, label)) {
                    submodulesOf(dir, label + "/", depth + 1);
                }
                continue;
            }
            String broken = brokenGitdir(dir);
            if (broken != null) {
                skip(label, "broken gitdir pointer -> " + broken + "; repair it with `git -C "
                        + (prefix.isEmpty() ? ROOT_LABEL : prefix.substring(0, prefix.length() - 1))
                        + " submodule absorbgitdirs`, or remove it and initialise it again");
                continue;
            }
            fastForward(dir, label);
            submodulesOf(dir, label + "/", depth + 1);
        }
    }

    /**
     * One repository, fetched and then fast-forwarded to {@code origin/main} when it is on
     * {@code main}, has no tracked changes and nothing {@code origin/main} lacks. Anything else is
     * a skip with its reason, and the repository is left as it was. Untracked files never block;
     * when the incoming tree would overwrite one, git refuses the fast-forward itself and that is
     * the reason.
     */
    private void fastForward(Path dir, String label) throws InterruptedException {
        Checkout.Git git = new Checkout.Git(dir, env);
        try {
            Checkout.Git.Result fetch = git.attempt(with("fetch", "--quiet", "--no-recurse-submodules", "origin"));
            if (fetch.exit() != 0) {
                skip(label, "fetch failed: " + said(fetch));
                return;
            }
            String branch = git.ask("symbolic-ref", "--quiet", "--short", "HEAD");
            if (branch == null) {
                skip(label, "detached");
                return;
            }
            if (!branch.equals(MAIN)) {
                skip(label, "on " + SafeText.line(branch));
                return;
            }
            Set<String> changes = Submodules.trackedChanges(git);
            if (!changes.isEmpty()) {
                skip(label, "tracked changes (" + named(changes) + ")");
                return;
            }
            if (git.ask("rev-parse", "--verify", "--quiet", UPSTREAM + "^{commit}") == null) {
                skip(label, "no origin/main");
                return;
            }
            int ahead = count(git, UPSTREAM + "..HEAD");
            int behind = count(git, "HEAD.." + UPSTREAM);
            if (ahead > 0) {
                skip(label, commits(ahead, "local commit", "local commits") + " ahead"
                        + (behind > 0 ? ", " + behind + " behind" : ""));
                return;
            }
            if (behind == 0) {
                upToDate++;
                return;
            }
            String before = git.run("rev-parse", "HEAD");
            Checkout.Git.Result merge = git.attempt(with("merge", "--ff-only", "--quiet", UPSTREAM));
            if (merge.exit() != 0) {
                skip(label, "fast-forward refused: " + said(merge));
                return;
            }
            moved.add(label + "  +" + commits(count(git, before + "..HEAD"), "commit", "commits"));
        } catch (CliFailure failed) {
            skip(label, failed.getMessage());
        }
    }

    /**
     * A submodule {@code .gitmodules} declares and nobody has initialised: {@code submodule update
     * --init} for it alone, then onto {@code main}, a local one tracking {@code origin/main} made
     * when there is none. A fresh clone has no local work, so this switch cannot carry any over.
     * Answers whether it is now initialised.
     */
    private boolean initialise(Path parent, String sub, String label) throws InterruptedException {
        try {
            Checkout.Git git = new Checkout.Git(parent, env);
            if (Submodules.gitlink(parent, sub, env) == null) {
                skip(label, "declared in .gitmodules, but there is no gitlink at that path");
                return false;
            }
            Checkout.Git.Result init = git.attempt(with("submodule", "update", "--init", "--checkout", "--", sub));
            if (init.exit() != 0) {
                skip(label, "initialising failed: " + said(init));
                return false;
            }
            Checkout.Git inside = new Checkout.Git(parent.resolve(sub), env);
            boolean hasMain = inside.ask("rev-parse", "--verify", "--quiet", "refs/heads/" + MAIN) != null;
            Checkout.Git.Result onMain = hasMain
                    ? inside.attempt(with("switch", "--quiet", MAIN))
                    : inside.attempt(with("switch", "--quiet", "--create", MAIN, "--track", "origin/" + MAIN));
            if (onMain.exit() != 0) {
                skip(label, "initialised, but not on main: " + said(onMain));
                return true;
            }
            fresh.add(label + "  new");
            return true;
        } catch (CliFailure failed) {
            skip(label, failed.getMessage());
            return false;
        }
    }

    /**
     * The {@code gitdir:} a submodule's {@code .git} file names, as written, when that directory is
     * not there — a pointer left behind by a move of the tree (the role directories to
     * {@code components/}). Null when {@code .git} is a directory or points somewhere real.
     */
    private static String brokenGitdir(Path dir) {
        Path dotGit = dir.resolve(".git");
        if (!Files.isRegularFile(dotGit)) {
            return null;
        }
        String pointer;
        try {
            pointer = Files.readAllLines(dotGit, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("gitdir:"))
                    .map(line -> line.substring("gitdir:".length()).strip())
                    .findFirst().orElse("");
        } catch (IOException | RuntimeException unreadable) {
            return "(unreadable .git file)";
        }
        if (pointer.isEmpty()) {
            return "(no gitdir in the .git file)";
        }
        try {
            return Files.isDirectory(dir.resolve(pointer)) ? null : SafeText.line(pointer);
        } catch (RuntimeException invalid) {
            return SafeText.line(pointer);
        }
    }

    private void skip(String label, String reason) {
        skipped.add(label + "  skipped: " + reason);
    }

    private static String[] with(String... args) {
        String[] argv = Arrays.copyOf(NO_RECURSION, NO_RECURSION.length + args.length);
        System.arraycopy(args, 0, argv, NO_RECURSION.length, args.length);
        return argv;
    }

    private static int count(Checkout.Git git, String range) throws CliFailure, InterruptedException {
        return Integer.parseInt(git.run("rev-list", "--count", range));
    }

    private static String commits(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** The first changed paths, and how many more. */
    private static String named(Set<String> changes) {
        List<String> names = changes.stream().limit(NAMED_CHANGES).map(SafeText::line).toList();
        String listed = String.join(", ", names);
        return changes.size() > NAMED_CHANGES ? listed + " and " + (changes.size() - NAMED_CHANGES) + " more" : listed;
    }

    /** Git's first line of explanation: stderr's, else stdout's. */
    private static String said(Checkout.Git.Result result) {
        String first = firstLine(result.err());
        if (first.isEmpty()) {
            first = firstLine(result.out());
        }
        return first.isEmpty() ? "git exited with " + result.exit() : first;
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().map(SafeText::line).map(String::strip)
                .filter(line -> !line.isEmpty()).findFirst().orElse("");
    }
}
