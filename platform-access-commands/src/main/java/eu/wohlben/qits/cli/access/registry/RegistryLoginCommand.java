package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.daemon.DaemonProbe;
import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.access.session.SessionRefresh;
import eu.wohlben.qits.cli.access.session.Times;
import eu.wohlben.qits.cli.access.update.AutoUpdate;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "registry-login", mixinStandardHelpOptions = true,
        description = {
                "Give npm, Maven and Docker on this workstation a login for the platform's registries: the access "
                        + "token of `qits login`, in a block of ~/.npmrc and two of ~/.m2/settings.xml that the CLI "
                        + "owns, and for Docker a credential helper that hands it the same token.",
                "--configure sets it up: it asks the platform which npm registries there are, writes the blocks, "
                        + "and records the setup in $XDG_CONFIG_HOME/qits/registries.json. From then on "
                        + "`qits session-daemon` writes each new token into the blocks, and refreshes at least "
                        + "5 minutes before expiry. Without --configure it writes the current token once.",
                "Only the blocks change; the rest of each file stays as it is. Each file is replaced atomically "
                        + "with mode 0600. The Maven blocks are the mirror `qits-maven-host` for the repository "
                        + "`qits-maven`, and servers for both ids with an `Authorization: Bearer` header.",
                "For Docker, --configure sets credHelpers[\"registry.qits.<domain>\"] to \"qits\" in "
                        + "~/.docker/config.json (or $DOCKER_CONFIG/config.json), keeping every other entry, and "
                        + "writes docker-credential-qits beside the qits binary: a two-line script that runs "
                        + "`qits docker-credential`. Docker runs it for each pull and push, so no token is stored "
                        + "for Docker and nothing needs renewing.",
                "--install-daemon also installs `qits session-daemon` as a systemd user service, so the npm "
                        + "and Maven token keeps being renewed. Without a running daemon the command warns when "
                        + "that token stops working, and says how to start one."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits registry-login --configure --remove-stale --dry-run",
                "  qits registry-login --configure --remove-stale",
                "  qits registry-login",
                "  qits registry-login --configure --install-daemon --dry-run",
                "",
                "- --dry-run writes nothing. It prints a unified diff per file, or \"No changes\". Every secret "
                        + "shows as <token>, and lines outside the blocks show only when they change.",
                "- --remove-stale also drops lines for the old *.dev.localhost registries, and entries outside the "
                        + "blocks that use the same registries or ids. In the Docker config, read only with "
                        + "--configure, it drops the auths logins for those registries and for the platform's. "
                        + "Without it they stay, with a warning.",
                "- --install-daemon writes $XDG_CONFIG_HOME/systemd/user/qits-session-daemon.service (else under "
                        + "~/.config) with the qits binary's path, then runs `systemctl --user daemon-reload` and "
                        + "`systemctl --user enable --now qits-session-daemon`. An existing unit file is left as it "
                        + "is. Without a systemd user manager it writes nothing and says how to start the daemon "
                        + "by hand.",
                "- A workstation command: inside the platform, or with QITS_TOKEN, the container has its own "
                        + "registry setup.",
                "- To stop: delete $XDG_CONFIG_HOME/qits/registries.json and the blocks, and the qits entry of the "
                        + "Docker config's credHelpers."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done, or nothing to change.",
                "1:A file cannot be read or written, the session cannot be refreshed, or systemctl failed.",
                "2:Used wrongly, not signed in, not set up, or not on a workstation."})
public class RegistryLoginCommand extends PlatformCommand {

    @CommandLine.Option(names = "--configure",
            description = "Set up the blocks (again), with the registries the platform lists now.")
    boolean configure;

    @CommandLine.Option(names = "--remove-stale",
            description = "Also drop *.dev.localhost registries and duplicates of the blocks' entries, and with "
                    + "--configure the Docker config's auths logins for those and the platform's registry.")
    boolean removeStale;

    @CommandLine.Option(names = "--dry-run",
            description = "Write nothing; print the changes as a redacted unified diff.")
    boolean dryRun;

    @CommandLine.Option(names = "--npmrc", paramLabel = "<file>",
            description = "With --configure: the npm user config. Default: NPM_CONFIG_USERCONFIG, else ~/.npmrc.")
    Path npmrc;

    @CommandLine.Option(names = "--maven-settings", paramLabel = "<file>",
            description = "With --configure: the Maven user settings. Default: ~/.m2/settings.xml.")
    Path mavenSettings;

    @CommandLine.Option(names = "--docker-config", paramLabel = "<file>",
            description = "With --configure: the Docker client config. Default: $DOCKER_CONFIG/config.json, "
                    + "else ~/.docker/config.json.")
    Path dockerConfig;

    @CommandLine.Option(names = "--artifacts-url", paramLabel = "<url>",
            description = "With --configure: qits-artifacts. Default: QITS_ARTIFACTS_URL, else "
                    + "https://registry.qits.<domain of the session's idp>.")
    String artifactsUrl;

    @CommandLine.Option(names = "--mirror-url", paramLabel = "<url>",
            description = "With --configure: qits-mirror. Default: QITS_MIRROR_URL, else "
                    + "https://mirror.qits.<domain of the session's idp>.")
    String mirrorUrl;

    @CommandLine.Option(names = "--install-daemon",
            description = "With --configure: install and start `qits session-daemon` as a systemd user service, "
                    + "unless its unit file exists.")
    boolean installDaemon;

    /** The running qits binary, beside which the Docker helper goes; a test hands its own. */
    Supplier<Optional<Path>> binary = AutoUpdate::nativeBinary;

    /** Whether a qits session-daemon runs for this session; a test says yes or no. */
    Predicate<SessionFile> daemonRunning = DaemonProbe::running;

    /** Runs systemctl; a test hands its own. */
    SessionDaemonUnit.Runner systemctl = SessionDaemonUnit.REAL;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (context.mode() != Mode.WORKSTATION) {
            throw new CliFailure("qits registry-login is for a workstation. Inside the platform the container "
                    + "has its own registry setup.", CliFailure.USAGE);
        }
        if (!configure && (npmrc != null || mavenSettings != null || dockerConfig != null || artifactsUrl != null
                || mirrorUrl != null)) {
            throw new CliFailure("--npmrc, --maven-settings, --docker-config, --artifacts-url and --mirror-url "
                    + "need --configure.", CliFailure.USAGE);
        }
        if (!configure && installDaemon) {
            throw new CliFailure("--install-daemon needs --configure.", CliFailure.USAGE);
        }
        RegistryLogins logins = new RegistryLogins(context.sessionFile().directory());

        Optional<RegistryState> saved;
        try {
            saved = configure ? Optional.empty() : logins.state();
        } catch (IOException e) {
            throw new CliFailure(e.getMessage(), CliFailure.FAILED);
        }
        if (!configure && saved.isEmpty()) {
            throw new CliFailure("Registry logins are not set up — run `qits registry-login --configure`.",
                    CliFailure.USAGE);
        }

        // A one-off write should leave npm and Maven a token with time to run, as the daemon does.
        SessionRefresh.refreshIfDue(context.sessionFile(), RegistryLogins.MARGIN, context.clock(), context.idpFor());
        Session session = context.tokens().session();

        RegistryState state;
        String dockerHost = null;
        Optional<Path> self = Optional.empty();
        boolean running = daemonRunning.test(context.sessionFile());
        Path unit = SessionDaemonUnit.path(context.env());
        boolean unitExists = SessionDaemonUnit.exists(unit);
        // Asked only without a unit file, where the answer decides what to install or what to advise.
        boolean systemd = !unitExists && (installDaemon || !running) && SessionDaemonUnit.systemd(systemctl);
        try {
            DockerConfig.Plan docker = null;
            Optional<DockerShim.Plan> shim = Optional.empty();
            if (configure) {
                Map<String, String> env = context.env();
                String idp = session.idpUrl();
                String artifacts = PlatformUrls.artifacts(artifactsUrl, env, idp);
                RegistryDiscovery.Found found = RegistryDiscovery.find(new PlatformClient(context.credential()),
                        artifacts, PlatformUrls.mirror(mirrorUrl, env, idp), context.err());
                Path dockerFile = path(dockerConfig, DockerConfig.defaultPath(env));
                state = new RegistryState(path(npmrc, defaultNpmrc(env)).toString(),
                        path(mavenSettings, home(env).resolve(".m2").resolve("settings.xml")).toString(),
                        found.npm(), found.mavenUrl(), dockerFile.toString());
                // Read before any file is written, so a Docker config that cannot be read changes nothing.
                dockerHost = DockerConfig.host(artifacts);
                docker = DockerConfig.plan(dockerFile, dockerHost, removeStale);
                self = binary.get();
                if (self.isPresent()) {
                    shim = Optional.of(DockerShim.plan(self.get()));
                } else {
                    context.err().println("Warning: this is not the qits binary, so " + DockerShim.NAME
                            + " was not written. Docker needs it on PATH: run `qits registry-login --configure` "
                            + "with the installed qits binary, or run install.sh again.");
                }
            } else {
                state = saved.orElseThrow();
            }

            List<RegistryLogins.FileChange> changes = logins.apply(state, session.accessToken(), removeStale, dryRun);
            changes.stream().flatMap(c -> c.warnings().stream()).distinct().forEach(context.err()::println);
            if (docker != null) {
                docker.warnings().forEach(context.err()::println);
            }
            if (dryRun) {
                StringBuilder diffs = new StringBuilder(String.join("",
                        changes.stream().map(RegistryLogins.FileChange::diff).toList()));
                if (docker != null) {
                    diffs.append(DockerConfig.diff(docker));
                }
                shim.ifPresent(plan -> diffs.append(DockerShim.diff(plan)));
                if (installDaemon) {
                    Optional<String> content = unitToInstall(context, unit, unitExists, systemd, self);
                    if (content.isPresent()) {
                        diffs.append(SessionDaemonUnit.diff(unit, content.get()));
                        for (List<String> argv : SessionDaemonUnit.commands(running)) {
                            diffs.append("Would run: ").append(GitSetup.shellLine(argv)).append('\n');
                        }
                    }
                }
                context.out().print(diffs.isEmpty() ? "No changes\n" : diffs.toString());
                warnWithoutDaemon(context, running, state, session, unitExists, systemd);
                return 0;
            }
            if (docker != null) {
                DockerConfig.apply(docker);
            }
            boolean shimWritten = false;
            if (shim.isPresent()) {
                shimWritten = DockerShim.apply(shim.get());
            }
            if (configure) {
                logins.saveState(state);
            }
            for (RegistryLogins.FileChange change : changes) {
                context.out().println((change.changed() ? "Updated " : "Up to date: ") + change.path());
            }
            if (docker != null) {
                context.out().println((docker.changed() ? "Updated " : "Up to date: ") + docker.path());
                for (String key : docker.removed()) {
                    context.out().println("Removed the stored login for " + key + " from " + docker.path() + ".");
                }
            }
            if (shim.isPresent()) {
                context.out().println((shimWritten ? "Updated " : "Up to date: ") + shim.get().path());
            }
            // After the registry files: a unit that fails to start leaves them written all the same.
            if (installDaemon) {
                Optional<String> content = unitToInstall(context, unit, unitExists, systemd, self);
                if (content.isPresent()) {
                    SessionDaemonUnit.install(unit, content.get(), running, systemctl);
                    context.out().println("Installed " + unit);
                    context.out().println("Enabled " + SessionDaemonUnit.NAME + (running
                            ? "; it starts at the next login, since a qits session-daemon is running now" : ""));
                    running = true;
                }
            }
        } catch (IllegalArgumentException noHost) {
            throw new CliFailure("Cannot tell the registry's host: " + noHost.getMessage(), CliFailure.USAGE);
        } catch (IOException e) {
            throw new CliFailure("Cannot update the registry logins: " + e.getMessage(), CliFailure.FAILED);
        }
        context.out().println("npm registries: " + String.join(", ", state.npmRegistries()));
        context.out().println("Maven: " + state.mavenUrl());
        if (dockerHost != null) {
            context.out().println("Docker: " + dockerHost + " via " + DockerShim.NAME);
        }
        warnWithoutDaemon(context, running, state, session, unitExists, systemd);
        context.out().println("The token is good until " + Times.local(session.accessExpiresAt()) + "."
                + (running ? " `qits session-daemon` is running and renews it." : ""));
        return 0;
    }

    /**
     * The unit --install-daemon would write, or empty with the reason printed: a unit file that is
     * there already, no systemd user manager, or no binary to name in ExecStart.
     */
    private static Optional<String> unitToInstall(CliContext context, Path unit, boolean unitExists, boolean systemd,
                                                  Optional<Path> self) {
        if (unitExists) {
            context.out().println(unit + " exists; left as it is");
            return Optional.empty();
        }
        if (!systemd) {
            context.err().println("Warning: no systemd user manager answers `"
                    + GitSetup.shellLine(SessionDaemonUnit.PROBE) + "`, so " + unit + " was not written.");
            return Optional.empty();
        }
        if (self.isEmpty()) {
            context.err().print("Warning: this is not the qits binary, so " + unit + " was not written. To "
                    + "install it yourself, write this there, with ExecStart naming the installed qits binary, "
                    + "then run `" + GitSetup.shellLine(SessionDaemonUnit.RELOAD) + "` and `"
                    + GitSetup.shellLine(SessionDaemonUnit.ENABLE) + "`:\n\n"
                    + SessionDaemonUnit.sample(context.env()) + "\n");
            return Optional.empty();
        }
        return Optional.of(SessionDaemonUnit.content(self.get(), context.env()));
    }

    /** Only the daemon renews the token in the npm and Maven files; without one they stop working. */
    private static void warnWithoutDaemon(CliContext context, boolean running, RegistryState state, Session session,
                                          boolean unitExists, boolean systemd) {
        if (running) {
            return;
        }
        context.err().println("Warning: no qits session-daemon is running, so the npm and Maven token in "
                + state.npmrc() + " and " + state.mavenSettings() + " stops working at "
                + Times.local(session.accessExpiresAt()) + ". " + SessionDaemonUnit.fix(unitExists, systemd));
    }

    private static Path path(Path flag, Path fallback) {
        return (flag != null ? flag : fallback).toAbsolutePath().normalize();
    }

    static Path defaultNpmrc(Map<String, String> env) {
        for (String name : List.of("NPM_CONFIG_USERCONFIG", "npm_config_userconfig")) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return Path.of(value.strip());
            }
        }
        return home(env).resolve(".npmrc");
    }

    static Path home(Map<String, String> env) {
        String home = env.get("HOME");
        return Path.of(home != null && !home.isBlank() ? home : System.getProperty("user.home"));
    }
}
