package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.idp.IdpUrl;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.login.Browser;
import eu.wohlben.qits.cli.access.networkaccess.AccessState;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@TuiCommand(interaction = Interaction.BROWSER)
@CommandLine.Command(name = "git-login", mixinStandardHelpOptions = true,
        description = {
                "Sign this workstation in for Git pushes to the platform's git host, through the browser. Run it "
                        + "once; afterwards Git gets its token from `qits git-credential`.",
                "The sign-in may push branches under refs/heads/external/ and nothing else. It is stored in "
                        + "$XDG_CONFIG_HOME/qits/git.json, apart from the session of `qits login`."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits git-login --configure",
                "  git push <remote> HEAD:refs/heads/external/log-view",
                "",
                "- The git host refuses a push to any other ref, main included. A push releases nothing: ask for a "
                        + "release with `qits release-request`.",
                "- --configure sets the credential helper for the git host only; a global helper (for example for "
                        + "GitHub) stays.",
                "- The git host is a private app. While `qits network-access up` has the companion running, "
                        + "--configure also sets the git host's http.<origin>.proxy to it, so Git reaches the host "
                        + "through the tunnel; `network-access up` and `down` keep that line in step later."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Signed in.",
                "1:The sign-in did not complete (the browser did not come back in time, or the idp refused).",
                "2:Used wrongly, or the git host cannot be worked out."})
public class GitLoginCommand extends PlatformCommand {

    /**
     * The one audience every platform service accepts, the git host included. Sign-in and refresh
     * always ask for it.
     */
    static final String AUDIENCE = "qits-platform";

    @CommandLine.Option(names = "--idp-url", paramLabel = "<url>",
            description = "The idp's public base URL. Default: QITS_IDP_URL, else the idp of the `qits login` "
                    + "session, else https://idp.qits.<QITS_DOMAIN>/idp; with none of them, the command refuses.")
    String idpUrl;

    @CommandLine.Option(names = "--git-host", paramLabel = "<url>",
            description = "The git host's address. Default: QITS_GIT_HOST_URL, else the idp's host with `idp` "
                    + "swapped for `githost` (https://githost.qits.<QITS_DOMAIN>).")
    String gitHost;

    @CommandLine.Option(names = "--timeout", paramLabel = "<seconds>", defaultValue = "300",
            description = "How long to wait for the browser to come back. Default: ${DEFAULT-VALUE}.")
    long timeoutSeconds;

    @CommandLine.Option(names = "--no-browser",
            description = "Only print the sign-in address; do not start a browser.")
    boolean noBrowser;

    @CommandLine.Option(names = "--configure",
            description = "Also run the `git config --global` commands that make Git ask "
                    + "`qits git-credential` for this git host (and no other), and, while the network-access "
                    + "companion runs, send that host through it.")
    boolean configure;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (timeoutSeconds < 1) {
            throw new CliFailure("--timeout must be at least 1 second.", CliFailure.USAGE);
        }
        Map<String, String> env = context.env();
        String idp = idpUrl(idpUrl, env, context);
        String origin;
        try {
            origin = GitOrigin.normalize(PlatformUrls.gitHost(gitHost, env, idp));
        } catch (IllegalArgumentException e) {
            throw new CliFailure("The " + e.getMessage() + ".", CliFailure.USAGE);
        }

        int result;
        try {
            result = new GitLoginFlow(new TokenClient(idp, GitLoginFlow.CLIENT_ID), GitCredentialFile.fromEnvironment(env),
                    origin, context.out(), context.err(), context.clock(),
                    noBrowser ? null : url -> Browser.open(url, env), Duration.ofSeconds(timeoutSeconds)).run();
        } catch (IOException e) {
            throw new CliFailure("Cannot complete the sign-in: " + e.getMessage(), CliFailure.FAILED);
        }
        if (result != 0) {
            return result;
        }

        List<List<String>> commands = new ArrayList<>(GitSetup.commands(origin, executable()));
        // The git host is a private app (qits-2743): through the companion when one runs here.
        Optional<String> proxy = AccessState.readQuietly(context.sessionFile().directory())
                .map(AccessState::proxy).filter(value -> !value.isBlank());
        proxy.ifPresent(value -> commands.add(GitSetup.proxyCommand(origin, value)));
        context.out().println();
        if (configure) {
            GitSetup.configure(commands, env, context.err());
            context.out().println("Git is set up: it asks `qits git-credential` for " + origin + " and for no other host.");
            proxy.ifPresent(value -> context.out().println("It reaches " + origin
                    + " through the network-access companion at " + value + "."));
        } else {
            context.out().println("Set Git up once, for this host only (the empty value clears its helper list first):");
            context.out().println();
            commands.forEach(argv -> context.out().println("  " + GitSetup.shellLine(argv)));
            context.out().println();
            context.out().println("Or run `qits git-login --configure`, which runs them.");
        }
        return 0;
    }

    /** Which idp: the flag or QITS_IDP_URL, else the `qits login` session's, else as `qits login` finds it. */
    static String idpUrl(String flag, Map<String, String> env, CliContext context) throws CliFailure {
        if ((flag != null && !flag.isBlank()) || !blank(env.get("QITS_IDP_URL"))) {
            return IdpUrl.resolve(flag, env);
        }
        try {
            Optional<Session> session = context.sessionFile().read();
            if (session.isPresent()) {
                return session.get().idpUrl();
            }
        } catch (IOException unreadable) {
            // Not needed: the idp can be found without it.
        }
        return IdpUrl.resolve(null, env);
    }

    /**
     * This binary's absolute path, for Git to run. Under the JVM (the tests) there is no such
     * binary, and the name on the PATH stands in.
     */
    static String executable() {
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return ProcessHandle.current().info().command().orElse("qits");
        }
        return "qits";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
