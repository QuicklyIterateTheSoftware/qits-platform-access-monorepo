package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.networkaccess.AccessState;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.session.PlatformEndpoints;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Git's credential helper protocol: Git writes {@code key=value} lines and an empty line to stdin,
 * and reads the answer from stdout.
 * <p>
 * <b>{@code get} is the one place {@code qits} prints a token</b>, because the protocol is exactly
 * that. stderr never carries one.
 * <p>
 * On a workstation the sign-in of {@code qits git-login} answers, from {@code git.json}. In the
 * token home — every agent container, a runner node, or an admin or editor workspace placed
 * directly on qits-containers — the workspace token answers, for the public git host
 * {@code githost.qits.<QITS_DOMAIN>} and no other.
 * <p>
 * In-platform mode ({@code QITS_PLATFORM} without {@code QITS_TOKEN}) answers nothing: no process
 * that sets it runs this command, so there is no credential here worth handing to Git, and a
 * workstation's sign-in file is not this home's to read. Every action prints nothing and exits 0,
 * so Git asks its next helper.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "git-credential", mixinStandardHelpOptions = true,
        description = {
                "Git's credential helper for the platform's git host. Git runs it; a person does not.",
                "On a workstation `qits git-login` sets it up: get prints that sign-in's access token, "
                        + "refreshing it first when needed. store is ignored. erase drops the cached access token "
                        + "and keeps the sign-in.",
                "With a workspace token (QITS_TOKEN) get answers https://githost.qits.<QITS_DOMAIN> with that "
                        + "token, and answers no other host; store and erase do nothing and no file is written.",
                "With QITS_PLATFORM set and no QITS_TOKEN it answers nothing: every action prints nothing, "
                        + "writes no file and exits 0, so Git asks its other helpers."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  git config --global --get-all credential.https://githost.qits.<QITS_DOMAIN>.helper",
                "",
                "The example shows the workstation setup. Do not run `get` yourself: it prints a token on stdout, "
                        + "because that is Git's helper protocol."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done. For a host without a sign-in, and with a workspace token for any host but the "
                + "public git host, it prints nothing and Git asks its other helpers. A credential that cannot be "
                + "had says why on stderr and still exits 0, so Git carries on.",
                "1:The sign-in file cannot be read or written.",
                "2:No action named."})
public class GitCredentialCommand extends PlatformCommand {

    @CommandLine.Parameters(index = "0", paramLabel = "<get|store|erase>", description = "What Git asks for.")
    String action;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        Map<String, String> request;
        try {
            request = readRequest(context.in());
        } catch (IOException e) {
            return 0;
        }
        String protocol = request.get("protocol");
        String origin;
        try {
            origin = GitOrigin.fromGitRequest(protocol, request.get("host"));
        } catch (IllegalArgumentException notAnHttpHost) {
            return 0;
        }
        if (context.mode().edgeToken()) {
            return withToken(context, origin);
        }
        if (context.mode() == Mode.IN_PLATFORM) {
            // Nothing here is Git's to have, and git.json is a workstation's file, not this home's.
            return 0;
        }
        GitAccess access = new GitAccess(GitCredentialFile.fromEnvironment(context.env()), context.clock());
        try {
            switch (action) {
                case "get" -> {
                    Optional<String> token = access.accessToken(origin, context.err());
                    if (token.isPresent()) {
                        context.out().print("username=oauth2\npassword=" + token.get() + "\n\n");
                        context.out().flush();
                        repairCompanionLine(context, origin);
                    }
                }
                case "erase" -> access.erase(origin, request.get("password"));
                default -> {
                    // `store` and any action Git adds later: only `qits git-login` stores a sign-in.
                }
            }
        } catch (IOException e) {
            throw new CliFailure(e.getMessage(), CliFailure.FAILED);
        }
        return 0;
    }

    /**
     * Puts the git host's companion proxy line back in step after answering (qits-2743), on a
     * workstation that set up the network-access companion ({@code access.json} exists): Git runs
     * this helper on every fetch and push to the git host, so a line that drifted is repaired by the
     * next one. Quiet: stdout is Git's protocol and already answered, and a repair is not news.
     * Never fails the answer.
     */
    private static void repairCompanionLine(CliContext context, String origin) {
        Path directory = context.sessionFile().directory();
        if (!Files.exists(directory.resolve(AccessState.FILE_NAME))) {
            return;
        }
        String proxy = AccessState.readQuietly(directory).map(AccessState::proxy).orElse(null);
        try {
            GitCompanionLine.sync(origin, proxy, context.env());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // The token is answered; the next fetch tries the repair again.
        }
    }

    /**
     * The token home's answer: the workspace token, for the public git host of {@code QITS_DOMAIN}
     * only. The same global helper runs for every remote here too, so a domain that cannot be read
     * answers nothing rather than any host, and the host is checked before the token is touched.
     */
    private int withToken(CliContext context, String origin) throws InterruptedException {
        String githost;
        try {
            githost = GitOrigin.normalize(PlatformEndpoints.edge("githost", context.env()));
        } catch (CliFailure | IllegalArgumentException noDomain) {
            return 0;
        }
        if (!"get".equals(action) || !githost.equals(origin)) {
            return 0;
        }
        try {
            context.out().print("username=oauth2\npassword=" + context.credential().bearer() + "\n\n");
            context.out().flush();
        } catch (CliFailure noToken) {
            context.err().println(noToken.getMessage());
            context.err().flush();
        }
        return 0;
    }

    static Map<String, String> readRequest(InputStream in) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        BufferedReader input = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null && !line.isEmpty(); ) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                result.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return result;
    }
}
