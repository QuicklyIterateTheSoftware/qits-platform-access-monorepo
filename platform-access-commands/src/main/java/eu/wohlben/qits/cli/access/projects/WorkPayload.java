package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The payload of a {@code qits work} write: a JSON document on stdin, sent as it was written. The
 * service is the one that says what a payload may hold, so nothing here knows a field. When nothing
 * is put in, the command shows the payload's schema instead, and that too is the service's: the
 * entity doors read the schema the service serves per archetype and door
 * ({@code /projects/api/work/archetypes/{A}/schemas/{door}}), the comment doors the OpenAPI
 * document the running service serves. Never a copy kept here, so it cannot describe a payload the
 * service no longer takes.
 */
public final class WorkPayload {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private WorkPayload() {
    }

    /**
     * The JSON object on stdin, or null when nothing was put in: stdin is a terminal, or it holds
     * nothing but blanks (a closed stdin, {@code </dev/null}, or the TUI, which closes it). Anything
     * else must be one JSON object, or it is a usage error and nothing is sent.
     */
    public static ObjectNode read(CliContext context) throws CliFailure {
        if (context.stdinIsTerminal()) {
            return null;
        }
        String text;
        try {
            text = new String(context.in().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliFailure("Cannot read the payload from stdin: " + e.getMessage(), CliFailure.USAGE);
        }
        if (text.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new CliFailure("The payload on stdin is not JSON (" + SafeText.line(e.getOriginalMessage())
                    + ", line " + e.getLocation().getLineNr() + "). Nothing was sent.", CliFailure.USAGE);
        }
        if (node == null || !node.isObject()) {
            throw new CliFailure("The payload on stdin must be a JSON object, not "
                    + (node == null ? "nothing" : article(node.getNodeType().name().toLowerCase(Locale.ROOT)))
                    + ". Nothing was sent.", CliFailure.USAGE);
        }
        return (ObjectNode) node;
    }

    private static String article(String kind) {
        return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind;
    }

    /**
     * Prints the request body schema of {@code method path} from the service's OpenAPI document,
     * every {@code $ref} resolved. {@code path} is the service's path template; the names of its
     * parameters do not have to match ({@code {id}} fits {@code {entityId}}). {@code mediaTypes} is
     * the order of preference when the operation takes more than one.
     */
    static void printSchema(CliContext context, ProjectsApi api, boolean json, String method, String path,
            List<String> mediaTypes) throws CliFailure, InterruptedException {
        JsonNode document;
        try {
            document = api.openApi();
        } catch (CliFailure refused) {
            throw new CliFailure("Nothing was sent. The payload's schema comes from the service's OpenAPI document ("
                    + ProjectsApi.OPENAPI + "), which could not be read: " + refused.getMessage(), refused.exitCode());
        }
        Found found = requestSchema(document, method, path, mediaTypes);
        if (found == null) {
            throw new CliFailure("Nothing was sent. The projects service's OpenAPI document (" + ProjectsApi.OPENAPI
                    + ") describes no request body for " + method + " " + path
                    + ": the service that serves this door may not be deployed yet.", CliFailure.FAILED);
        }
        printSchema(context.out(), json, found.schema(), "From the service's OpenAPI document: " + method + " " + path
                + ", " + SafeText.line(found.mediaType()) + ".", List.of());
    }

    /**
     * The schema the service serves at {@code GET path}: one archetype's payload for one door, built
     * by the service from the same table its validators read. A refusal says that nothing was sent.
     */
    static JsonNode served(String path, Fetch fetch) throws CliFailure, InterruptedException {
        try {
            return fetch.get();
        } catch (CliFailure refused) {
            throw new CliFailure("Nothing was sent. The payload's schema comes from the service (GET " + path
                    + "), which could not be read: " + refused.getMessage(), refused.exitCode());
        }
    }

    /** A read that may be refused. */
    @FunctionalInterface
    interface Fetch {
        JsonNode get() throws CliFailure, InterruptedException;
    }

    /**
     * Prints a schema, wherever it came from. The JSON form is the schema alone; the table form
     * first says nothing was sent, names the required properties, adds {@code notes} (a line each)
     * and says where the schema came from in {@code from}.
     */
    static void printSchema(PrintStream out, boolean json, JsonNode schema, String from, List<String> notes)
            throws CliFailure {
        if (!json) {
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(r -> required.add(SafeText.line(r.asText())));
            out.println("Nothing on stdin, so nothing was sent. Pipe in a JSON document of this schema to send it.");
            out.println("Required: " + (required.isEmpty() ? "nothing" : String.join(", ", required)) + ".");
            notes.forEach(out::println);
            out.println(from);
            out.println();
        }
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the schema as JSON", CliFailure.FAILED);
        }
    }

    record Found(String mediaType, JsonNode schema) {
    }

    /** The operation's request body schema with its references resolved; null when it is not there. */
    static Found requestSchema(JsonNode document, String method, String path, List<String> mediaTypes) {
        JsonNode pathItem = null;
        String wanted = template(path);
        for (Map.Entry<String, JsonNode> entry : document.path("paths").properties()) {
            if (template(entry.getKey()).equals(wanted)) {
                pathItem = entry.getValue();
                break;
            }
        }
        if (pathItem == null) {
            return null;
        }
        JsonNode content = pathItem.path(method.toLowerCase(Locale.ROOT)).path("requestBody").path("content");
        if (!content.isObject() || content.isEmpty()) {
            return null;
        }
        String mediaType = mediaTypes.stream().filter(content::has).findFirst()
                .orElseGet(() -> content.fieldNames().next());
        JsonNode schema = content.path(mediaType).path("schema");
        if (schema.isMissingNode()) {
            return null;
        }
        return new Found(mediaType, resolve(schema, document, new HashSet<>()));
    }

    /** A path template with its parameter names dropped, so {@code {id}} and {@code {entityId}} match. */
    private static String template(String path) {
        return path.replaceAll("\\{[^}/]*}", "{}");
    }

    /**
     * A copy with every local {@code $ref} replaced by what it points at. A reference inside its own
     * definition is left as it is, so a recursive schema ends; one that points nowhere stays too.
     */
    static JsonNode resolve(JsonNode node, JsonNode document, Set<String> open) {
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> copy.add(resolve(item, document, open)));
            return copy;
        }
        if (!node.isObject()) {
            return node;
        }
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual() && ref.asText().startsWith("#/") && !open.contains(ref.asText())) {
            JsonNode target = document.at(ref.asText().substring(1));
            if (!target.isMissingNode()) {
                open.add(ref.asText());
                JsonNode resolved = resolve(target, document, open);
                open.remove(ref.asText());
                if (!resolved.isObject()) {
                    return resolved;
                }
                ObjectNode merged = ((ObjectNode) resolved).deepCopy();
                // OpenAPI 3.1 lets a reference carry siblings (a description, say); they say more.
                for (Map.Entry<String, JsonNode> sibling : node.properties()) {
                    if (!sibling.getKey().equals("$ref")) {
                        merged.set(sibling.getKey(), resolve(sibling.getValue(), document, open));
                    }
                }
                return merged;
            }
        }
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            copy.set(field.getKey(), resolve(field.getValue(), document, open));
        }
        return copy;
    }
}
