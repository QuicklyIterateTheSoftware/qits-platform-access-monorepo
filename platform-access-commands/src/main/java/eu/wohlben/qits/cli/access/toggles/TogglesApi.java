package eu.wohlben.qits.cli.access.toggles;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The qits-toggles configuration doors ({@code /toggles/api/toggles}, qits-2704, epic qits-2542),
 * read as JSON trees like the other services' APIs. The same three the landing app's Toggles section
 * calls, so the two consumers' pacts stay in parity. There is no create and no delete door: a toggle
 * exists only by its declaration in qits-toggles-service's code.
 */
public final class TogglesApi {

    static final String TOGGLES = "/toggles/api/toggles";

    private final PlatformClient client;
    private final String base;

    public TogglesApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * {@code [{slug, type, description, declaredDefault, firstDeclaredAt, version, rules, lastUsedAt,
     * removalTicket}]}: every declared toggle. Open to every authenticated caller.
     */
    public JsonNode list() throws CliFailure, InterruptedException {
        return client.get(uri(TOGGLES));
    }

    /** One toggle, the shape of a {@link #list} entry. HTTP 404 for a slug no declaration names. */
    public JsonNode get(String slug) throws CliFailure, InterruptedException {
        return client.get(uri(TOGGLES + "/" + segment(slug)));
    }

    /**
     * Replaces a toggle's ordered rules: {@code body} is {@code {version, rules}} and goes as it is.
     * The answer is the toggle with its new version. HTTP 400 for rules the service does not take
     * (the STATIC rule not last, say), 403 for a caller without qits:admin or qits:desk-admin, 404 for
     * an unknown slug, 409 when the rules changed since {@code version} was read.
     */
    public JsonNode putRules(String slug, JsonNode body) throws CliFailure, InterruptedException {
        return client.put(uri(TOGGLES + "/" + segment(slug) + "/rules"), body);
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable toggles address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
