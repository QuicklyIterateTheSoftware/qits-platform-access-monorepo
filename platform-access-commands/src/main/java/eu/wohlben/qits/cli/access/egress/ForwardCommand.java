package eu.wohlben.qits.cli.access.egress;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;

/**
 * {@code qits egress forward}: a local HTTP proxy address whose connections are carried over the
 * edge to qits-egress, one WebSocket each (epic qits-2525, qits-2655). The work is {@link
 * EgressForward}'s. It runs until it is stopped, so the MCP service does not serve it.
 */
@TuiCommand(interaction = Interaction.STREAMING)
@CommandLine.Command(name = "forward", mixinStandardHelpOptions = true,
        description = {"Listen on a local address and carry every connection to qits-egress's proxy, through the "
                + "edge, over a WebSocket of its own. Point a tool's HTTPS_PROXY and HTTP_PROXY at the listener: "
                + "the proxy decides what may go out, and names this credential as the caller.",
                "Each connection's upgrade carries this home's bearer (QITS_TOKEN in a workspace, else the session). "
                        + "A refused upgrade (401, 403) closes that connection and prints one line on stderr; the "
                        + "listener goes on. It never dials through a proxy itself. Notes go to stderr. Runs until "
                        + "SIGINT or SIGTERM."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits egress forward",
                "  qits egress forward --listen 127.0.0.1:3128",
                "  curl -x http://127.0.0.1:3128 https://example.com",
                "",
                "- Any qits bearer may open a forward; what goes through it is decided per request by the proxy."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Stopped by SIGINT or SIGTERM.",
                "1:The listening address cannot be taken.",
                "2:--listen or --url cannot be read, not signed in, or the session ended."})
public class ForwardCommand extends PlatformCommand {

    static final String DOOR = "/egress/api/forward";

    @CommandLine.Option(names = "--listen", paramLabel = "<host:port>", defaultValue = "127.0.0.1:3128",
            description = "The local address to listen on. Default: 127.0.0.1:3128. Port 0 takes a free one, "
                    + "named on stderr.")
    String listen;

    @CommandLine.Option(names = "--url", paramLabel = "<wss url>",
            description = "The forward door. Default: wss://egress.qits.<QITS_DOMAIN>" + DOOR + ", from "
                    + "QITS_EGRESS_URL or the session's idp address with `idp` swapped for `egress`.")
    String url;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        InetSocketAddress address = listenAddress(listen);
        URI door = url == null || url.isBlank()
                ? socketUri(PlatformUrls.egress(null, context.env(), context.idpUrl()) + DOOR)
                : socketUri(url.strip());
        // Fails at once without a credential, rather than on the first connection.
        Credential credential = context.credential();
        credential.bearer();
        EgressForward forward = new EgressForward(credential, door, address, context.err(), context.clock());
        forward.bind();
        context.onStop().accept(forward::stop);
        return forward.run();
    }

    /** {@code host:port}; an IPv6 host in brackets. */
    static InetSocketAddress listenAddress(String value) throws CliFailure {
        String refused = "--listen takes host:port, such as 127.0.0.1:3128, not '" + value + "'.";
        String text = value == null ? "" : value.strip();
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
        String host = text.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        } else if (host.contains(":")) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1));
        } catch (NumberFormatException notANumber) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
        if (host.isEmpty() || port < 0 || port > 65535) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new CliFailure("--listen names a host that does not resolve: '" + host + "'.", CliFailure.USAGE);
        }
        return address;
    }

    /** A WebSocket address: ws and wss as they are, http and https turned into them. */
    static URI socketUri(String value) throws CliFailure {
        String refused = "'" + value + "' is not a usable forward address. Give a ws, wss, http or https URL.";
        int colon = value.indexOf("://");
        String scheme = colon < 0 ? "" : value.substring(0, colon).toLowerCase(Locale.ROOT);
        String socketScheme = switch (scheme) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new CliFailure(refused, CliFailure.USAGE);
        };
        try {
            URI uri = URI.create(socketScheme + value.substring(colon));
            if (uri.getHost() == null) {
                throw new CliFailure(refused, CliFailure.USAGE);
            }
            return uri;
        } catch (IllegalArgumentException notAUri) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
    }
}
