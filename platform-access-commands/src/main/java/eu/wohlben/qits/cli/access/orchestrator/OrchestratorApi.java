package eu.wohlben.qits.cli.access.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The qits-orchestrator API ({@code /orchestrator/api}, qits-2490): its technical processes and
 * their runs, read as JSON trees like {@code MaintenanceApi}. The same five doors the landing app's
 * Orchestrator pages call, so the two consumers' pacts stay in parity.
 */
public final class OrchestratorApi {

    private final PlatformClient client;
    private final String base;

    public OrchestratorApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** {@code [{kind, name, description, dryRunnable, steps:[{id, name, target, dependsOn}]}]}: every process. */
    public JsonNode processes() throws CliFailure, InterruptedException {
        return client.get(uri("/orchestrator/api/processes"));
    }

    /**
     * {@code [{id, kind, trigger, dryRun, status, startedAt, finishedAt, summary}]}: one process's
     * runs, newest first. Without {@code limit} the service's default (20) applies. HTTP 404 for an
     * unknown kind.
     */
    public JsonNode runs(String kind, Integer limit) throws CliFailure, InterruptedException {
        String query = limit == null ? "" : "?limit=" + limit;
        return client.get(uri("/orchestrator/api/processes/" + segment(kind) + "/runs" + query));
    }

    /**
     * {@code {"id": …}}: a run of the process, started. HTTP 400 for a dry run of a process that has
     * none, 403 for a caller the process is not open to, 404 for an unknown kind, 409 while a run of
     * that kind is active.
     */
    public JsonNode start(String kind, boolean dryRun) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("dryRun", dryRun);
        return client.post(uri("/orchestrator/api/processes/" + segment(kind) + "/runs"), body);
    }

    /**
     * One run with its steps. {@code bodies} false leaves out every step's request body and response
     * ({@code responseBytes} stays); true sends no parameter, and the service's default is the
     * bodies.
     */
    public JsonNode run(String id, boolean bodies) throws CliFailure, InterruptedException {
        return client.get(uri("/orchestrator/api/runs/" + segment(id) + (bodies ? "" : "?bodies=false")));
    }

    /** One step of a run, with its request body and response. HTTP 404 for no such run or step. */
    public JsonNode step(String id, String stepId) throws CliFailure, InterruptedException {
        return client.get(uri("/orchestrator/api/runs/" + segment(id) + "/steps/" + segment(stepId)));
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable orchestrator address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
