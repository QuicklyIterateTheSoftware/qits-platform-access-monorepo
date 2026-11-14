package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The qits-deployments doors the CLI calls, read as JSON trees like every platform answer: the
 * settings ({@code /deployments/api/settings}, epic qits-765), the per-application, per-environment
 * values the deployer hands a service, and the database moves ({@code
 * /deployments/api/database-moves}, qits-2175).
 * <p>
 * A secret's {@code value} is null in every answer: the service never sends one back.
 */
public final class DeploymentsApi {

    private final PlatformClient client;
    private final String base;

    public DeploymentsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * {@code {settings:[{application, environment, key, value, secret, updatedAt, updatedBy}]}}: the
     * application's settings, of every environment unless {@code environment} names one.
     */
    public JsonNode settings(String application, String environment) throws CliFailure, InterruptedException {
        String query = "?application=" + query(application)
                + (environment == null || environment.isBlank() ? "" : "&environment=" + query(environment));
        return client.get(uri("/deployments/api/settings" + query));
    }

    /** The stored row, its value null when it is a secret. The body is {@code {"value", "secret"}}. */
    public JsonNode putSetting(String application, String environment, String key, String value, boolean secret)
            throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("value", value);
        body.put("secret", secret);
        return client.put(uri(settingPath(application, environment, key)), body);
    }

    /** Nothing (204); 404 when no such setting is stored. */
    public JsonNode deleteSetting(String application, String environment, String key)
            throws CliFailure, InterruptedException {
        return client.delete(uri(settingPath(application, environment, key)));
    }

    /**
     * {@code {application, environment, added:[{key, value, secret}], removed:[{key, value, secret}],
     * changed:[{key, from, to, secret}], kept:[{key, value, secret}], unchanged}}: what the next
     * deployment of the application in the environment would hand it that the running one does not
     * have. {@code extras} asks for {@code ?extras=include}: the values the running deployment has
     * that no setting holds, listed under {@code kept}.
     */
    public JsonNode previewSettings(String application, String environment, boolean extras)
            throws CliFailure, InterruptedException {
        return client.get(uri("/deployments/api/settings/preview/" + segment(application) + "/" + segment(environment)
                + (extras ? "?extras=include" : "")));
    }

    /**
     * {@code {move: {id, applicationName, resourceName, sourceServer, sourceDatabase, targetServer,
     * targetDatabase, state, ...}, note}}: the move the service queued (202). {@code note} is not null
     * when the target overrides the application's spec. A member left null is not sent, so the service
     * defaults it from the live version's spec.
     */
    public JsonNode moveDatabase(String application, String resource, String server, String database)
            throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("application", application);
        putIfGiven(body, "resource", resource);
        putIfGiven(body, "server", server);
        putIfGiven(body, "database", database);
        // The door admits qits:agent and names its own refusal: the generic "reads but does not write"
        // line would say the opposite of what the door decides.
        return client.post(uri("/deployments/api/database-moves"), body, false);
    }

    /** {@code {moves: [move, ...]}}, newest first, at most 200: of one application, or of every one. */
    public JsonNode databaseMoves(String application) throws CliFailure, InterruptedException {
        return client.get(uri("/deployments/api/database-moves"
                + (application == null || application.isBlank() ? "" : "?application=" + query(application))));
    }

    /** {@code {move, note}}: one move in full, its per-table counts included; 404 when there is no such move. */
    public JsonNode databaseMove(String id) throws CliFailure, InterruptedException {
        return client.get(uri("/deployments/api/database-moves/" + segment(id)));
    }

    /**
     * {@code {move, note}}: the move stopped before its switch, the same shape {@link #databaseMove}
     * reads (202). 404 when there is no such move; 409 when it is QUEUED, already finished, or at
     * SWITCHING or later. Unlike {@link #moveDatabase}, this door refuses {@code qits:agent}, so the
     * generic "reads but does not write" 403 hint is correct here and kept.
     */
    public JsonNode abortDatabaseMove(String id) throws CliFailure, InterruptedException {
        return client.post(uri("/deployments/api/database-moves/" + segment(id) + "/abort"));
    }

    private static void putIfGiven(ObjectNode body, String name, String value) {
        if (value != null && !value.isBlank()) {
            body.put(name, value.strip());
        }
    }

    private static String settingPath(String application, String environment, String key) {
        return "/deployments/api/settings/" + segment(application) + "/" + segment(environment) + "/" + segment(key);
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable deployments address.", CliFailure.USAGE);
        }
    }

    private static String query(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
