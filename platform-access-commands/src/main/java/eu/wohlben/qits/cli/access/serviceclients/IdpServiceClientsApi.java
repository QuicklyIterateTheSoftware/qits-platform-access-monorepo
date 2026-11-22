package eu.wohlben.qits.cli.access.serviceclients;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;

/**
 * The idp's service-client read (qits-2531), as a JSON tree like the other services' APIs: every
 * service's {@code idp:client}, never a secret. It takes the caller's bearer through {@link
 * PlatformClient}; the idp admits {@code qits:admin}, {@code qits:agent}, {@code qits:system},
 * {@code qits:admin-agent} and {@code qits:desk-admin}.
 */
public final class IdpServiceClientsApi {

    static final String SERVICE_CLIENTS = "/api/service-clients";

    private final PlatformClient client;
    /** The idp's base URL with its path prefix: {@code https://idp.qits.<QITS_DOMAIN>/idp}. */
    private final String idp;

    IdpServiceClientsApi(PlatformClient client, String idp) {
        this.client = client;
        this.idp = idp;
    }

    /** {@code [{clientId, source, createdAt, rotatedAt}]}. */
    JsonNode list() throws CliFailure, InterruptedException {
        return client.get(uri(SERVICE_CLIENTS));
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
