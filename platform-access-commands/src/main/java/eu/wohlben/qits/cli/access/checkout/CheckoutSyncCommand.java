package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.nio.file.Path;

/**
 * {@code qits checkout sync}: {@link Sync} on one checkout. A {@link PlatformCommand} only for its
 * context and its one-line failures: it never asks the context for a credential, so it runs with no
 * session, no {@code QITS_TOKEN} and no platform to reach.
 * <p>
 * {@code LOCAL}: it moves repositories on the caller's machine, so the MCP service does not serve it.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "sync", mixinStandardHelpOptions = true,
        description = {
                "Bring a workstation clone of a wrapper up to date: the root and every submodule, nested ones "
                        + "(a service's webui) included, fast-forwarded on its own main to origin/main, and every "
                        + "submodule .gitmodules newly declares initialised and put on main, tracking origin/main.",
                "Pure git: no sign-in, no platform. Each repository is fetched from its own origin with whatever "
                        + "credential helper git has, and moved only by `git merge --ff-only`. A repository that "
                        + "is detached, on another branch, has tracked changes, has commits origin/main lacks, or "
                        + "whose fast-forward git refuses is left exactly as it was and named with the reason. "
                        + "Untracked files never block.",
                "stdout has one line per repository that moved (`<path>  +<n> commits`), then the new ones "
                        + "(`<path>  new`), then the skipped ones (`<path>  skipped: <reason>`), then a summary "
                        + "line. A repository already at origin/main prints nothing of its own."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits checkout sync",
                "  qits checkout sync --path ~/src/qits-qits",
                "",
                "- It never resets, stashes, switches over local work, stages a path or recurses through git "
                        + "(--recurse-submodules, submodule.recurse): a moved gitlink in a parent's index is the "
                        + "expected drift `ignore = all` hides, and the bumps own it.",
                "- A submodule whose .git file points at a gitdir that is not there (left by a move of the tree) "
                        + "is skipped and not descended into; the line says how to repair it.",
                "- It refuses a project agent container's checkout, a root detached at a release tag or one a "
                        + "`qits checkout-daemon` holds: that checkout follows releases, and the daemon keeps it."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Every repository is at origin/main, moved there, or new.",
                "1:A repository was skipped or a fetch or fast-forward failed; every other one was still synced.",
                "2:Used wrongly: the path is not in a Git repository, or it is a checkout that follows releases "
                        + "(a project agent container's)."})
public class CheckoutSyncCommand extends PlatformCommand {

    @CommandLine.Option(names = "--path", paramLabel = "<dir>", defaultValue = ".",
            description = "The clone to sync. Default: the directory the command runs in.")
    String path;

    /** Where the process table is read; a test hands its own. */
    Path proc = Path.of("/proc");

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        Path dir = Path.of(path == null || path.isBlank() ? "." : path.strip()).toAbsolutePath();
        return Sync.of(dir, context.env(), proc).run(context.out());
    }
}
