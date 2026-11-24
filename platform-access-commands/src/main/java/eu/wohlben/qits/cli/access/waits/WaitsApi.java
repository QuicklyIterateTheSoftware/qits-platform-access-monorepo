package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An agent's waits, a project's desk's, or a project's long-running agent's, in qits-workspaces
 * ({@code /workspaces/api/agents/{agentId}/waits}, {@code /workspaces/api/desks/{projectId}/waits} or
 * {@code /workspaces/api/project-agent/waits}; qits-1153 W8, qits-1618, epic qits-2520), read as JSON
 * trees like the other services' answers. The doors take the same request and answer the same shape;
 * the path differs by who holds the waits, and the project agent's door also names the work item
 * each wait is held for ({@code work}).
 */
final class WaitsApi {

    private final PlatformClient client;
    private final String base;

    WaitsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * Who a wait belongs to: one agent, one project's desk (qits-1618), or the project's long-running
     * agent the bearer is (epic qits-2520). Never two; {@link WaitCommand#holder} is the one place that
     * decides which.
     */
    sealed interface Holder {

        record Agent(String id) implements Holder {
        }

        record Desk(String projectId) implements Holder {
        }

        /**
         * The bearer itself, a project's long-running agent: no path names it. {@code work} is the
         * item a declaration is held for, and narrows a list; null lists every item's waits.
         */
        record ProjectAgent(String work) implements Holder {
        }

        static Holder agent(String id) {
            return new Agent(id);
        }

        static Holder desk(String projectId) {
            return new Desk(projectId);
        }

        static Holder projectAgent(String work) {
            return new ProjectAgent(work);
        }
    }

    /**
     * {@code {label?, selection, timeout?}} in, plus {@code work} for a project agent's; the new wait
     * out (201). A ninth OPEN wait of a project agent on one item is HTTP 409.
     */
    JsonNode create(Holder holder, JsonNode body) throws CliFailure, InterruptedException {
        if (holder instanceof Holder.ProjectAgent(String work) && body.isObject()) {
            ObjectNode withWork = ((ObjectNode) body).deepCopy();
            withWork.put("work", work);
            return client.post(uri(path(holder)), withWork);
        }
        return client.post(uri(path(holder)), body);
    }

    /**
     * The holder's waits, in the state given, or in every state when it is null. The project agent's
     * door reads its own words ({@code open}, the default, or {@code all}), so {@code state} goes to it
     * as given; {@link WaitCommand.ListCommand} picks the word.
     */
    JsonNode list(Holder holder, String state) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        if (holder instanceof Holder.ProjectAgent(String work) && work != null) {
            query.add("work=" + segment(work));
        }
        if (state != null) {
            query.add("state=" + segment(state));
        }
        return client.get(uri(path(holder) + (query.isEmpty() ? "" : "?" + String.join("&", query))));
    }

    /** Cancels one wait: the wait as it now stands, or nothing (204). */
    JsonNode cancel(Holder holder, String waitId) throws CliFailure, InterruptedException {
        return client.delete(uri(path(holder) + "/" + segment(waitId)));
    }

    static String path(Holder holder) {
        return switch (holder) {
            case Holder.Agent agent -> "/workspaces/api/agents/" + segment(agent.id()) + "/waits";
            case Holder.Desk desk -> "/workspaces/api/desks/" + segment(desk.projectId()) + "/waits";
            case Holder.ProjectAgent ignored -> "/workspaces/api/project-agent/waits";
        };
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable workspaces address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
