package eu.wohlben.qits.cli.access.users;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.CompanionProxy;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The idp's user doors, read as JSON trees like the other services' APIs.
 * <p>
 * Three of them take the caller's bearer through {@link PlatformClient}. Registration does not: the
 * register token in its body is the credential, and the person registering has no session yet, so
 * it is sent with no Authorization header at all.
 * <p>
 * <b>No message here holds the register token or the password.</b> A refusal names the status and
 * the idp's {@code error} and {@code error_description}; an answer that cannot be read is said to be
 * unreadable, never quoted, because it carries the password.
 */
public final class IdpUsersApi {

    static final String REGISTER_TOKENS = "/api/register-tokens";
    static final String REGISTER = "/api/auth/register";
    static final String USERS = "/api/users";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int REFUSAL_BODY_LIMIT = 64 * 1024;

    private final PlatformClient client;
    /** The idp's base URL with its path prefix: {@code https://idp.qits.<QITS_DOMAIN>/idp}. */
    private final String idp;

    IdpUsersApi(PlatformClient client, String idp) {
        this.client = client;
        this.idp = idp;
    }

    /** {@code {id, token, createdAt}}: a fresh register token, in this answer and nowhere else. */
    JsonNode invite() throws CliFailure, InterruptedException {
        return client.post(uri(REGISTER_TOKENS));
    }

    /** {@code [{username, createdAt, roles}]}. */
    JsonNode list() throws CliFailure, InterruptedException {
        return client.get(uri(USERS));
    }

    void delete(String username) throws CliFailure, InterruptedException {
        client.delete(uri(USERS + "/" + segment(username)));
    }

    /**
     * Spends {@code token} on {@code username}. The answer holds the generated password, which the
     * idp gives once; the session it also opens (a cookie) is not kept.
     */
    JsonNode register(String username, String token) throws CliFailure, InterruptedException {
        URI uri = uri(REGISTER);
        ObjectNode body = JSON.createObjectNode();
        body.put("username", username);
        body.put("token", token);
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot write the request body", CliFailure.FAILED);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try (HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(CompanionProxy.selector())
                .build()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw CliFailure.retryable("Cannot reach " + uri + ": " + e.getClass().getSimpleName());
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw registrationRefused(uri, status, response.body());
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(response.body());
        } catch (IOException notJson) {
            answer = null;
        }
        if (answer == null || !answer.isObject() || !answer.path("password").isTextual()
                || answer.path("password").asText().isEmpty()) {
            // Not quoted: the body is where the password would be.
            throw new CliFailure("The idp registered the user, but its answer carries no password this command can "
                    + "read (POST " + uri + ", HTTP " + status + ").", CliFailure.FAILED);
        }
        return answer;
    }

    private static CliFailure registrationRefused(URI uri, int status, byte[] bytes) {
        String said = idpError(bytes);
        String detail = said.isEmpty() ? "" : ": " + said;
        if (status >= 500) {
            return CliFailure.retryable("POST " + uri + " answered HTTP " + status + detail);
        }
        if (status == 401 || status == 403) {
            return CliFailure.refused("The idp refused the register token (HTTP " + status + detail + "). It is "
                    + "unknown or already spent; ask an admin for a new one (`qits users invite`).", status);
        }
        if (status == 400 || status == 409) {
            return CliFailure.refused("The idp refused the registration (HTTP " + status + detail + "). The token "
                    + "was not spent; fix the username and run it again.", status);
        }
        return CliFailure.refused("POST " + uri + " answered HTTP " + status + detail, status);
    }

    /** {@code error: error_description}, or {@code message}; never anything else of the body. */
    private static String idpError(byte[] bytes) {
        JsonNode node;
        try {
            byte[] head = bytes == null ? new byte[0]
                    : bytes.length <= REFUSAL_BODY_LIMIT ? bytes : java.util.Arrays.copyOf(bytes, REFUSAL_BODY_LIMIT);
            node = head.length == 0 ? null : JSON.readTree(head);
        } catch (IOException notJson) {
            return "";
        }
        if (node == null || !node.isObject()) {
            return "";
        }
        String error = node.path("error").asText("");
        String description = node.path("error_description").asText("");
        if (error.isEmpty() && description.isEmpty()) {
            return node.path("message").asText("");
        }
        return error.isEmpty() ? description : description.isEmpty() ? error : error + ", " + description;
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

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
