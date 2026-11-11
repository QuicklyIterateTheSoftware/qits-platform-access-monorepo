package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.agentmcp.AgentMcpCommand;
import eu.wohlben.qits.cli.access.agents.AgentsCommand;
import eu.wohlben.qits.cli.access.artifacts.ArtifactsCommand;
import eu.wohlben.qits.cli.access.changelog.ChangelogGroup;
import eu.wohlben.qits.cli.access.checkout.CheckoutCommand;
import eu.wohlben.qits.cli.access.checkout.CheckoutDaemonCommand;
import eu.wohlben.qits.cli.access.ci.CiCommand;
import eu.wohlben.qits.cli.access.contracts.ContractsCommand;
import eu.wohlben.qits.cli.access.maintenance.MaintenanceCommand;
import eu.wohlben.qits.cli.access.networkaccess.NetworkAccessCommand;
import eu.wohlben.qits.cli.access.daemon.SessionDaemonCommand;
import eu.wohlben.qits.cli.access.database.DatabaseCommand;
import eu.wohlben.qits.cli.access.deployments.DeploymentsCommand;
import eu.wohlben.qits.cli.access.events.EventsCommand;
import eu.wohlben.qits.cli.access.git.GitCredentialCommand;
import eu.wohlben.qits.cli.access.git.GitLoginCommand;
import eu.wohlben.qits.cli.access.registry.DockerCredentialCommand;
import eu.wohlben.qits.cli.access.registry.RegistryLoginCommand;
import eu.wohlben.qits.cli.access.help.HelpCommand;
import eu.wohlben.qits.cli.access.login.LoginCommand;
import eu.wohlben.qits.cli.access.observe.ObserveCommand;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.projects.IdeasCommand;
import eu.wohlben.qits.cli.access.projects.ProjectsCommand;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestCommand;
import eu.wohlben.qits.cli.access.projects.RepositoriesCommand;
import eu.wohlben.qits.cli.access.projects.WorkCommand;
import eu.wohlben.qits.cli.access.remembered.RememberedDevicesCommand;
import eu.wohlben.qits.cli.access.targets.TargetsCommand;
import eu.wohlben.qits.cli.access.update.QitsVersion;
import eu.wohlben.qits.cli.access.update.UpdateCommand;
import eu.wohlben.qits.cli.access.users.UsersCommand;
import eu.wohlben.qits.cli.access.waits.WaitCommand;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand;
import picocli.CommandLine;

/**
 * The {@code qits} command. With no command named, it shows what there is.
 * <p>
 * The help texts of all commands are the one source of SKILL.md ({@code qits help skill}). The
 * first description line here is its trigger, and the footer is its platform rules.
 * <p>
 * The tree here is every command but {@code qits tui}, which needs a terminal and lives with the
 * binary. The binary names this class as its top command ({@code quarkus.picocli.top-command}) and
 * puts {@code tui} back in its place, before {@code help} ({@code QitsCommandLine}), so the binary's
 * tree and its help are what they were when this was one module.
 */
@CommandLine.Command(
        name = "qits",
        mixinStandardHelpOptions = true,
        versionProvider = QitsVersion.Provider.class,
        subcommands = {LoginCommand.class, SessionDaemonCommand.class, CheckoutDaemonCommand.class, CheckoutCommand.class,
                ProjectsCommand.class,
                RepositoriesCommand.class, WorkCommand.class, IdeasCommand.class,
                ReleaseRequestCommand.class, CiCommand.class, DatabaseCommand.class, ContractsCommand.class, DeploymentsCommand.class, MaintenanceCommand.class, UsersCommand.class,
                RememberedDevicesCommand.class,
                EventsCommand.class,
                ObserveCommand.class, WaitCommand.class, WorkspacesCommand.class, TargetsCommand.class, GitLoginCommand.class, GitCredentialCommand.class, RegistryLoginCommand.class,
                DockerCredentialCommand.class,
                NetworkAccessCommand.class,
                ArtifactsCommand.class, ChangelogGroup.class, AgentsCommand.class, AgentMcpCommand.class, UpdateCommand.class,
                HelpCommand.class},
        description = {
                "Use for any work on the qits platform from a terminal: signing in, projects and repositories, "
                        + "work items of every archetype (epics, tickets, features, tasks, issues) and their comment "
                        + "threads, ideas, release requests, CI runs and their logs, "
                        + "domain events, live telemetry, waiting on the platform, workspaces and their agents, "
                        + "execution targets and their leases, Git pushes to the platform's git host, and "
                        + "publishing release artifacts from a CI step.",
                "Each command calls the platform through its edge, with the session of `qits login`, except "
                        + "`qits artifacts publish`, which runs in a CI step container with no person and never touches that "
                        + "session. `qits <command> --help` shows a command's options, examples and exit codes.",
                "A workspace is the token home: on a runner node, or an admin or editor workspace placed "
                        + "directly on qits-containers, with QITS_TOKEN set every command sends that token as it "
                        + "is, to the public vhosts https://<app>.qits.<QITS_DOMAIN>. It wins over the session, "
                        + "nothing is minted or written to disk, and a 401 means the token "
                        + "was deleted with the workspace's container. QITS_URL_<APP> still overrides an address."},
        footerHeading = "%nPlatform rules:%n",
        footer = {
                "- Sign in once with `qits login`, and keep `qits session-daemon` running so the session stays fresh.",
                "- Release only through a release request: `qits release-request create`, or `join` to add a "
                        + "branch to an open one. A push releases nothing.",
                "- Push only branches under refs/heads/external/, after `qits git-login`. Git gets the token from "
                        + "`qits git-credential`.",
                "- On a workstation, npm, Maven and Docker get the platform's registries from `qits registry-login "
                        + "--configure`: npm and Maven a token kept fresh by `qits session-daemon`, Docker its "
                        + "helper `qits docker-credential`. Do not write registry tokens by hand.",
                "- qits never prints a token. The two exceptions are `qits git-credential get`, which Git runs, "
                        + "and `qits docker-credential get`, which Docker runs. "
                        + "Two commands print a secret meant for one person: `qits users invite` (a register token) and `qits users register` (the new "
                        + "password, once).",
                "- To wait for something on the platform, declare it with `qits wait` and end your turn: you get a "
                        + "new turn when it happens. Never poll for it in a background loop.",
                "- A command that says `Not signed in` or `Session ended` exits with 2: run `qits login`.",
                "- For how to develop on the platform, run `qits help guide`: it prints the platform's current "
                        + "guide for where it runs (a workstation, or an agent, desk or refinement room inside). It "
                        + "leads with `guide-hash: <hash>` when the service sends one; `--hash-only` prints just "
                        + "that line, to check cheaply whether the guide changed."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
public class AccessCli implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
