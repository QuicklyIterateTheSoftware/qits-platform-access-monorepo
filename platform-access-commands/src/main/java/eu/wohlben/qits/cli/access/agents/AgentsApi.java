package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;

import java.net.URI;

/**
 * qits-agents-service ({@code /agents/api}, epic qits-2520): the long-running agents it runs for a
 * project, read as JSON trees like the other services' answers. One door so far, {@link #me()}: who
 * the calling bearer is, when it is such an agent.
 */
public final class AgentsApi {

    private final PlatformClient client;
    private final String base;

    public AgentsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** Takes this home's credential and finds the service: --agents-url, else QITS_AGENTS_URL, else the idp's sibling. */
    public static AgentsApi connect(CliContext context, String agentsUrl) throws CliFailure, InterruptedException {
        return new AgentsApi(new PlatformClient(context.credential()),
                PlatformUrls.agents(agentsUrl, context.env(), context.idpUrl()));
    }

    /**
     * {@code {project, projectId, name, generation}}: the project's long-running agent the bearer
     * belongs to. HTTP 404 for any other bearer: a person's, a workspace agent's, a desk's.
     */
    public JsonNode me() throws CliFailure, InterruptedException {
        // No role hint on a refusal: the door answers every bearer, and 404 is its "not one of mine".
        return client.get(uri("/agents/api/me"), false);
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable agents address.", CliFailure.USAGE);
        }
    }
}
