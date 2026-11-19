package eu.wohlben.qits.cli.access.networkaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The qits-network-access doors {@code qits network-access} uses ({@code /network-access/api},
 * epic qits-1977), read as JSON trees like every platform answer. The writes take a person: the
 * service admits {@code qits:admin} and the desk's and system's roles, never an agent.
 */
public final class NetworkAccessApi {

    private final PlatformClient client;
    private final String base;

    public NetworkAccessApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** {@code {image, version, entrypoint, user}}: what the companion runs from, and as whom. */
    public JsonNode companion() throws CliFailure, InterruptedException {
        return client.get(uri("/network-access/api/companion"));
    }

    /** {@code {id, kind, name, publicKey, tunnelIp, status, ...}}; 404 when there is no such peer. */
    public JsonNode peer(String peerId) throws CliFailure, InterruptedException {
        return client.get(uri("/network-access/api/peers/" + segment(peerId)));
    }

    /** The new DEVICE peer, owned by the caller (201). 409 for a key or a name already ACTIVE. */
    public JsonNode createPeer(String name, String publicKey) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("name", name);
        body.put("publicKey", publicKey);
        return client.post(uri("/network-access/api/peers"), body);
    }

    /** {@code {peerId, tunnelIp, edge:{...}}}: the companion's {@code configure} input, as it is. */
    public JsonNode peerTunnel(String peerId) throws CliFailure, InterruptedException {
        return client.get(uri("/network-access/api/peers/" + segment(peerId) + "/tunnel"));
    }

    /** The peer, REVOKED; idempotent on a revoked one. */
    public JsonNode revokePeer(String peerId) throws CliFailure, InterruptedException {
        return client.post(uri("/network-access/api/peers/" + segment(peerId) + "/revoke"));
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable network-access address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
