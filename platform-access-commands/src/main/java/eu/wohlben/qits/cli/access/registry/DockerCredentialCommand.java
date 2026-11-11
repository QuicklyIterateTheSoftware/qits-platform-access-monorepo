package eu.wohlben.qits.cli.access.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionRefresh;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Docker's credential helper protocol (docker-credential-helpers): Docker runs
 * {@code docker-credential-qits <action>}, which {@code qits registry-login --configure} writes and
 * which runs this command, writes the registry's address to stdin and reads a JSON answer from
 * stdout.
 * <p>
 * <b>{@code get} prints the session's access token</b>, because the protocol is exactly that, and
 * only for the platform's registry. stderr never carries one. The edge's registry realm takes it
 * as the Basic password of {@code oauth2}.
 * <p>
 * "No credential" is the line {@value #NOT_FOUND} and exit 1, the words Docker's own helpers use:
 * Docker then pulls anonymously. That is the answer for any other registry, without a session, and
 * anywhere but a workstation, so a helper set up on a laptop never hands out a token it should not.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "docker-credential", mixinStandardHelpOptions = true,
        description = {
                "Docker's credential helper for the platform's registry. Docker runs it, through the "
                        + "docker-credential-qits script that `qits registry-login --configure` writes; a person "
                        + "does not.",
                "get reads the registry's address on stdin and, for https://registry.qits.<domain of the "
                        + "session's idp> (or QITS_ARTIFACTS_URL), prints the access token of `qits login` as "
                        + "the password of `oauth2`, refreshing the session first when it has less than 5 minutes "
                        + "left. store and erase do nothing: the token comes from the session. list prints {}.",
                "For any other registry, without a session, and with QITS_TOKEN or QITS_PLATFORM, get prints "
                        + "`" + DockerCredentialCommand.NOT_FOUND + "` and exits 1, which Docker reads as no "
                        + "login, so it pulls without one."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits registry-login --configure",
                "  printf 'registry.qits.<domain>' | qits docker-credential get",
                "",
                "Do not run `get` yourself: it prints a token on stdout, because that is Docker's helper protocol."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done. get printed the login; store, erase and list always end here.",
                "1:get has no login for that registry. A session that cannot be refreshed says why on stderr.",
                "2:No action named, or one Docker does not have."})
public class DockerCredentialCommand extends PlatformCommand {

    /** What Docker's helpers print when they have nothing; Docker matches these words. */
    static final String NOT_FOUND = "credentials not found in native keychain";

    static final String USERNAME = "oauth2";

    private static final ObjectMapper JSON = new ObjectMapper();

    @CommandLine.Parameters(index = "0", paramLabel = "<get|store|erase|list>", description = "What Docker asks for.")
    String action;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        String input;
        try {
            input = new String(context.in().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            input = "";
        }
        return switch (action) {
            case "get" -> get(context, input.strip());
            case "list" -> {
                context.out().println("{}");
                context.out().flush();
                yield 0;
            }
            // Only `qits login` makes the credential, and only the session holds it.
            case "store", "erase" -> 0;
            default -> throw new CliFailure("Unknown action " + action + ": Docker asks for get, store, erase "
                    + "or list.", CliFailure.USAGE);
        };
    }

    private int get(CliContext context, String serverUrl) throws InterruptedException {
        if (context.mode() != Mode.WORKSTATION || serverUrl.isEmpty()) {
            return notFound(context);
        }
        Optional<Session> stored;
        try {
            stored = context.sessionFile().read();
        } catch (IOException unreadable) {
            context.err().println(unreadable.getMessage());
            return notFound(context);
        }
        if (stored.isEmpty()) {
            return notFound(context);
        }
        // The host is checked before the token is touched: this helper may be asked about any registry.
        String registry;
        try {
            registry = DockerConfig.host(PlatformUrls.artifacts(null, context.env(), stored.get().idpUrl()));
        } catch (CliFailure | IllegalArgumentException noRegistry) {
            return notFound(context);
        }
        String asked;
        try {
            asked = DockerConfig.host(serverUrl);
        } catch (IllegalArgumentException notAnAddress) {
            return notFound(context);
        }
        if (!registry.equals(asked)) {
            return notFound(context);
        }

        // Docker may push for a while on one login, so hand it a token with time to run.
        SessionRefresh.Outcome outcome = SessionRefresh.refreshIfDue(context.sessionFile(), RegistryLogins.MARGIN,
                context.clock(), context.idpFor());
        String reason = switch (outcome) {
            case SessionRefresh.Outcome.Retry retry -> "Cannot refresh the qits session: " + retry.detail();
            case SessionRefresh.Outcome.Ended ended -> "The qits session has ended (" + ended.detail()
                    + "): run `qits login`.";
            case SessionRefresh.Outcome.Unreadable unreadable -> unreadable.detail();
            default -> null;
        };
        if (reason != null) {
            context.err().println(reason);
            return notFound(context);
        }
        Session session;
        try {
            session = context.tokens().session();
        } catch (CliFailure noSession) {
            context.err().println(noSession.getMessage());
            return notFound(context);
        }
        ObjectNode answer = JSON.createObjectNode()
                .put("ServerURL", serverUrl)
                .put("Username", USERNAME)
                .put("Secret", session.accessToken());
        context.out().println(answer.toString());
        context.out().flush();
        return 0;
    }

    private static int notFound(CliContext context) {
        context.err().flush();
        context.out().println(NOT_FOUND);
        context.out().flush();
        return 1;
    }
}
