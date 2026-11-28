package eu.wohlben.qits.cli.access.certificate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;

/**
 * qits-certificates' workload certificate door, {@code POST /certificates/api/workload-certificates}
 * (epic qits-2626, qits-2756), read as a JSON tree like the other services' APIs. Only the CSR goes
 * out; the door decides the principal from the caller's bearer and ignores the CSR's names.
 */
public final class CertificatesApi {

    static final String WORKLOAD_CERTIFICATES = "/certificates/api/workload-certificates";

    private final PlatformClient client;
    private final String base;

    public CertificatesApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * {@code {certificate, chain, serial, notAfter, anchorId, principal}} for the CSR's key. HTTP 400
     * for a CSR the CA does not take, 403 for a token that may not have a workload certificate (a
     * person's, an exchanged one), 503 while the CA has no ACTIVE anchor.
     */
    public JsonNode issue(String csrPem) throws CliFailure, InterruptedException {
        return client.post(uri(), JsonNodeFactory.instance.objectNode().put("csr", csrPem));
    }

    private URI uri() throws CliFailure {
        try {
            URI uri = URI.create(base + WORKLOAD_CERTIFICATES);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable certificates address.", CliFailure.USAGE);
        }
    }
}
