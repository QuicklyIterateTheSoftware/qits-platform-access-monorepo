package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one Git line the network-access companion needs on a workstation (qits-2743): {@code
 * http.<git host origin>.proxy} in the person's {@code --global} Git config, pointing at the
 * companion while one is set up, and absent once it is taken down.
 * <p>
 * <b>Why the CLI writes it, and not the companion.</b> The companion is a container running as
 * {@code 65532:65532} with only its own state volume: it cannot see the person's home, and must not.
 * The qits processes that run as the person keep the line in step instead, at every point of the
 * companion's lifecycle they see: {@code network-access up} and {@code down}, {@code qits
 * session-daemon} while it runs (its start, a change of {@code access.json}, and each time the
 * companion answers healthy again after it did not), {@code git-login --configure}, and {@code
 * git-credential get}.
 * <p>
 * <b>The line follows {@code access.json}'s {@code proxy}</b>, which {@code up} writes once the
 * companion is healthy and {@code down} drops: a companion that is only restarting keeps the line,
 * and one stopped for good loses it. Always {@code --global}: Git's own resolution of that
 * ({@code GIT_CONFIG_GLOBAL}, else {@code ~/.gitconfig}), never a system or repository file.
 * <p>
 * <b>Only where Git already asks {@code qits git-credential} for the git host</b> ({@link
 * GitSetup#helped}): a workstation that never pushes there keeps a Git config nothing here touched.
 * Idempotent: the current value is read first and nothing is written when it is already right.
 */
public final class GitCompanionLine {

    /** What {@link #sync} did. */
    public enum Change {
        /** The line now names the companion; it did not before. */
        SET,
        /** The line is gone; it was there before. */
        REMOVED,
        /** It was already as wanted: nothing written. */
        UNCHANGED,
        /** Git does not ask qits for this host: left alone. */
        NOT_SET_UP,
        /** No git on the PATH, or git failed: nothing could be done. */
        NO_GIT
    }

    /** The outcome, and for {@link Change#NO_GIT} what git said. */
    public record Result(Change change, String origin, String detail) {

        /** The line to say about it, or empty when there is nothing worth saying. */
        public Optional<String> line(String proxy) {
            return switch (change) {
                case SET -> Optional.of("git: " + origin + " goes through the companion at " + proxy);
                case REMOVED -> Optional.of("git: " + origin + " goes direct again");
                case NO_GIT -> Optional.of("warning: could not keep Git's proxy for " + origin
                        + " in step with the companion: " + detail);
                case UNCHANGED, NOT_SET_UP -> Optional.empty();
            };
        }
    }

    private GitCompanionLine() {
    }

    /**
     * Brings {@code http.<origin>.proxy} in step with {@code proxy}: that value when it is set, no
     * line when it is null or blank.
     */
    public static Result sync(String origin, String proxy, Map<String, String> env) throws InterruptedException {
        String wanted = proxy == null || proxy.isBlank() ? null : proxy.strip();
        Optional<Boolean> helped = GitSetup.asksQits(origin, env);
        if (helped.isEmpty()) {
            return new Result(Change.NO_GIT, origin, "git is not on the PATH");
        }
        if (!helped.get()) {
            return new Result(Change.NOT_SET_UP, origin, null);
        }
        List<String> current;
        try {
            current = GitSetup.globalValues(GitSetup.proxyKey(origin), env);
        } catch (IOException noGit) {
            return new Result(Change.NO_GIT, origin, noGit.getMessage());
        }
        if (wanted == null ? current.isEmpty() : current.equals(List.of(wanted))) {
            return new Result(Change.UNCHANGED, origin, null);
        }
        try {
            GitSetup.configure(List.of(wanted == null ? GitSetup.unproxyCommand(origin)
                    : GitSetup.proxyCommand(origin, wanted)), env, new PrintStream(OutputStream.nullOutputStream()));
        } catch (CliFailure failed) {
            return new Result(Change.NO_GIT, origin, failed.getMessage());
        }
        return new Result(wanted == null ? Change.REMOVED : Change.SET, origin, null);
    }
}
