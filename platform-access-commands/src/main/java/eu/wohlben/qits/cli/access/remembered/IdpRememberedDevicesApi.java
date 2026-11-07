package eu.wohlben.qits.cli.access.remembered;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The idp's remembered-devices doors (qits-1944), read as JSON trees like the other services' APIs:
 * the signed-in person's own browsers that sign back in without a password. Both take the caller's
 * bearer through {@link PlatformClient}; the idp admits a person's CLI bearer (what {@code qits
 * login} stores) and refuses every other one with 403.
 */
public final class IdpRememberedDevicesApi {

    static final String DEVICES = "/api/remembered-devices";

    private final PlatformClient client;
    /** The idp's base URL with its path prefix: {@code https://idp.qits.<QITS_DOMAIN>/idp}. */
    private final String idp;

    IdpRememberedDevicesApi(PlatformClient client, String idp) {
        this.client = client;
        this.idp = idp;
    }

    /**
     * {@code [{id, label, createdAt, lastUsedAt, expiresAt, absoluteExpiresAt, current}]}, most
     * recently used first. Its 403 is left as the idp worded it: this is a read an agent's
     * credential is refused as well, so the "reads but does not write" line would say the opposite
     * of what happened.
     */
    JsonNode list() throws CliFailure, InterruptedException {
        return client.get(uri(DEVICES), false);
    }

    /** Forgets one device and ends every session it opened: 204, or 404 for an unknown or foreign id. */
    void revoke(String id) throws CliFailure, InterruptedException {
        client.delete(uri(DEVICES + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")));
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(idp + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + idp + "' is not a usable idp address.", CliFailure.USAGE);
        }
    }
}
