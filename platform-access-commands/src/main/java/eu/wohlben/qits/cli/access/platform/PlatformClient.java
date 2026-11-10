package eu.wohlben.qits.cli.access.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import eu.wohlben.qits.cli.session.Credential;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Calls to platform services through the edge, with the session's access token as a bearer.
 * <p>
 * <b>No message here holds a token.</b> A refusal names the method, the address, the status and
 * the service's own error message; the request's headers never appear.
 */
public final class PlatformClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** A refusal's message is at the start of its body; an HTML page from a proxy is not read whole. */
    static final int REFUSAL_BODY_LIMIT = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern AUTH_PARAM = Pattern.compile("(error|error_description)=\"([^\"]*)\"");

    private final Credential credential;
    /** Never through the network-access companion: see {@link #direct}. */
    private final boolean bypassCompanion;

    public PlatformClient(Credential credential) {
        this(credential, false);
    }

    private PlatformClient(Credential credential, boolean bypassCompanion) {
        this.credential = credential;
        this.bypassCompanion = bypassCompanion;
    }

    /**
     * A client that never dials through the network-access companion ({@link CompanionProxy}): what
     * {@code qits network-access} sets the companion up with, which must not depend on the companion
     * it is about to replace. The network-access service is a public app.
     */
    public static PlatformClient direct(Credential credential) {
        return new PlatformClient(credential, true);
    }

    /**
     * The bearer for this call. Nothing is refused here on the strength of the address: whether a
     * service accepts this token is that service's answer to give, and a 401 comes back with the
     * credential's own sentence on it ({@link #explained}).
     */
    private String bearer() throws CliFailure, InterruptedException {
        return "Bearer " + credential.bearer();
    }

    public JsonNode get(URI uri) throws CliFailure, InterruptedException {
        return send("GET", uri, HttpRequest.newBuilder(uri).GET());
    }

    /**
     * A GET whose 403, when {@code roleHintOn403} is false, carries no "reads but does not write"
     * line: for a read door that an agent's credential is refused as well, where that line would
     * say the opposite of what happened ({@code qits projects desk answer}).
     */
    public JsonNode get(URI uri, boolean roleHintOn403) throws CliFailure, InterruptedException {
        return send("GET", uri, HttpRequest.newBuilder(uri).GET(), roleHintOn403);
    }

    public JsonNode post(URI uri, JsonNode body) throws CliFailure, InterruptedException {
        return post(uri, body, true);
    }

    /**
     * A POST whose 403, when {@code roleHintOn403} is false, is left exactly as the door worded it:
     * no generic "this credential reads but does not write" line pasted above it. That line is
     * right for most doors, which admit a person and nothing else, but wrong for one that decides
     * eligibility itself and already named every admitted role in its own sentence — approve and
     * decline (epic qits-1038), and the backup twin reset (qits-1885), the only callers that pass
     * false. Every other write keeps the hint.
     */
    public JsonNode post(URI uri, JsonNode body, boolean roleHintOn403) throws CliFailure, InterruptedException {
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (IOException impossible) {
            throw new CliFailure("cannot write the request body", CliFailure.FAILED);
        }
        return send("POST", uri, HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)), roleHintOn403);
    }

    /** A POST whose address says it all (a retry), with no body. */
    public JsonNode post(URI uri) throws CliFailure, InterruptedException {
        return send("POST", uri, HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** A DELETE. A 204 gives a {@link NullNode}; a 200 gives what the service answered. */
    public JsonNode delete(URI uri) throws CliFailure, InterruptedException {
        return send("DELETE", uri, HttpRequest.newBuilder(uri).DELETE());
    }

    /** A PUT whose address says it all, with no body. */
    public JsonNode put(URI uri) throws CliFailure, InterruptedException {
        return send("PUT", uri, HttpRequest.newBuilder(uri).PUT(HttpRequest.BodyPublishers.noBody()));
    }

    public JsonNode put(URI uri, JsonNode body) throws CliFailure, InterruptedException {
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (IOException impossible) {
            throw new CliFailure("cannot write the request body", CliFailure.FAILED);
        }
        return send("PUT", uri, HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    /**
     * A PATCH with a body of the given media type: a merge patch is {@code application/merge-patch+json},
     * and a service that reads both takes it by that name.
     */
    public JsonNode patch(URI uri, JsonNode body, String contentType) throws CliFailure, InterruptedException {
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (IOException impossible) {
            throw new CliFailure("cannot write the request body", CliFailure.FAILED);
        }
        return send("PATCH", uri, HttpRequest.newBuilder(uri)
                .header("Content-Type", contentType)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    /**
     * The body is parsed as it arrives, not first held as one string: a CI run's answer carries
     * the output of its steps. A refusal's body is read only as far as its message needs.
     */
    private JsonNode send(String method, URI uri, HttpRequest.Builder builder) throws CliFailure, InterruptedException {
        return send(method, uri, builder, true);
    }

    private JsonNode send(String method, URI uri, HttpRequest.Builder builder, boolean roleHintOn403)
            throws CliFailure, InterruptedException {
        HttpRequest request = builder
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", bearer())
                .build();
        try (HttpClient http = newClient(bypassCompanion)) {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (PushbackInputStream body = new PushbackInputStream(response.body())) {
                if (status == 204) {
                    // A write that answers nothing (a stored report) has nothing to parse.
                    return NullNode.getInstance();
                }
                if (status < 200 || status >= 300) {
                    String text = new String(body.readNBytes(REFUSAL_BODY_LIMIT), StandardCharsets.UTF_8);
                    CliFailure refusal = refusal(method, uri, status, response.headers(), text);
                    throw status == 403 && !roleHintOn403 ? refusal : explained(status, refusal);
                }
                int first = body.read();
                if (first < 0) {
                    // A 202 that only says "queued", or a 200 with no body, answers nothing too.
                    return NullNode.getInstance();
                }
                body.unread(first);
                JsonNode node;
                try {
                    node = JSON.readTree(body);
                } catch (JsonProcessingException notJson) {
                    node = null;
                }
                if (node == null || node.isMissingNode()) {
                    throw new CliFailure(method + " " + uri + " answered with a body that is not JSON.", CliFailure.FAILED);
                }
                return node;
            }
        } catch (IOException e) {
            throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(e));
        }
    }

    /** A GET whose answer is {@code text/plain}, with a header read off it: {@code docker logs}. */
    public record TextAnswer(String body, boolean truncated) {
    }

    /**
     * A GET read as raw text rather than JSON, with {@code X-Qits-Truncated} read off the answer: a
     * target's own {@code docker logs} and a lease's (epic qits-1621). A refusal reads as one of
     * {@link #get}'s.
     */
    public TextAnswer getText(URI uri) throws CliFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/plain")
                .header("Authorization", bearer())
                .GET()
                .build();
        try (HttpClient http = newClient(bypassCompanion)) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw explained(status, refusal("GET", uri, status, response.headers(), response.body()));
            }
            boolean truncated = response.headers().firstValue("X-Qits-Truncated")
                    .map(Boolean::parseBoolean).orElse(false);
            return new TextAnswer(response.body(), truncated);
        } catch (IOException e) {
            throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(e));
        }
    }

    /**
     * A HEAD: what the service says about a file without sending it ({@code Content-Length}, a
     * digest). A refusal reads as one of {@link #get}'s.
     */
    public HttpHeaders head(URI uri) throws CliFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", bearer())
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient http = newClient(bypassCompanion)) {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw explained(status, refusal("HEAD", uri, status, response.headers(), ""));
            }
            return response.headers();
        } catch (IOException e) {
            throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(e));
        }
    }

    /**
     * A GET whose body is a file, written to {@code target} as it arrives rather than held in
     * memory: the {@code qits} binary is tens of megabytes. The caller decides what a partial file
     * is worth; this only says that it failed.
     *
     * @return the number of bytes written
     */
    public long download(URI uri, Path target) throws CliFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("Authorization", bearer())
                .GET()
                .build();
        try (HttpClient http = newClient(bypassCompanion)) {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status < 200 || status >= 300) {
                    String text = new String(body.readNBytes(REFUSAL_BODY_LIMIT), StandardCharsets.UTF_8);
                    throw explained(status, refusal("GET", uri, status, response.headers(), text));
                }
                try (OutputStream file = Files.newOutputStream(target, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    return body.transferTo(file);
                }
            }
        } catch (IOException e) {
            throw CliFailure.retryable("Cannot download " + uri + ": " + describe(e));
        }
    }

    /**
     * Opens a Server-Sent Events stream. A status other than 200 is a {@link CliFailure}: retryable
     * for a 5xx, final for a 4xx. The caller reads the lines and closes the connection.
     */
    public Connection openStream(URI uri) throws CliFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .header("Authorization", bearer())
                .GET()
                .build();
        // A client per connection, so that stopping can abort exactly this one. No request
        // timeout: the body never ends, and a silent stream is the caller's watchdog's business.
        HttpClient http = newClient(bypassCompanion);
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            http.shutdownNow();
            throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(e));
        } catch (InterruptedException e) {
            http.shutdownNow();
            throw e;
        }
        int status = response.statusCode();
        if (status != 200) {
            String body;
            try (Stream<String> lines = response.body()) {
                body = lines.limit(200).collect(Collectors.joining("\n"));
            } catch (RuntimeException unreadable) {
                body = "";
            }
            http.shutdownNow();
            CliFailure refusal = explained(status, refusal("GET", uri, status, response.headers(), body));
            throw status >= 500 ? CliFailure.retryable(refusal.getMessage()) : refusal;
        }
        return new Connection(http, response.body());
    }

    /** An open stream. {@link #abort()} may be called from any thread, and ends a blocked read. */
    public static final class Connection implements AutoCloseable {
        private final HttpClient http;
        private final Stream<String> lines;

        Connection(HttpClient http, Stream<String> lines) {
            this.http = http;
            this.lines = lines;
        }

        public Stream<String> lines() {
            return lines;
        }

        public void abort() {
            http.shutdownNow();
        }

        @Override
        public void close() {
            abort();
            try {
                lines.close();
            } catch (RuntimeException ignored) {
                // The client is already shut down; nothing is left to release.
            }
        }
    }

    /**
     * Starts a WebSocket's opening handshake, with the access token as a bearer, and returns at
     * once, so the caller can {@link Socket#abort()} a handshake that hangs. {@link Socket#await()}
     * waits for the answer. Reading the token may refresh the session first.
     */
    public Socket openSocket(URI uri, WebSocket.Listener listener) throws CliFailure, InterruptedException {
        String bearer = bearer();
        // A client per connection, like openStream, so that stopping can abort exactly this one.
        HttpClient http = newClient(bypassCompanion);
        CompletableFuture<WebSocket> opening;
        try {
            opening = http.newWebSocketBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .header("Authorization", bearer)
                    .buildAsync(uri, listener);
        } catch (IllegalArgumentException notUsable) {
            http.shutdownNow();
            throw new CliFailure("'" + uri + "' is not a usable WebSocket address.", CliFailure.USAGE);
        }
        return new Socket(uri, http, opening, credential);
    }

    /** A WebSocket being opened, then open. {@link #abort()} may be called from any thread. */
    public static final class Socket {
        private final URI uri;
        private final HttpClient http;
        private final CompletableFuture<WebSocket> opening;
        /** Kept so a refused handshake reads the same as a refused request: see {@link #await()}. */
        private final Credential credential;

        Socket(URI uri, HttpClient http, CompletableFuture<WebSocket> opening, Credential credential) {
            this.uri = uri;
            this.http = http;
            this.opening = opening;
            this.credential = credential;
        }

        /**
         * The open socket, once the platform accepted the upgrade. A refusal is a {@link
         * CliFailure}: retryable for a 5xx or a network error, final for any other status.
         * <p>
         * A 401 or a 403 gets the credential's own sentence above it, the same as a refused
         * request does. This is the path {@code qits observe} takes, and it is the path that used
         * to be cut off before it was ever walked: the audience check refused the command outright
         * rather than letting qits-observability answer. It answers, so the socket is opened and
         * only a real refusal is worded.
         */
        public WebSocket await() throws CliFailure, InterruptedException {
            try {
                return opening.get();
            } catch (CancellationException aborted) {
                throw CliFailure.retryable("the connection to " + uri + " was aborted");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof WebSocketHandshakeException handshake && handshake.getResponse() != null) {
                    HttpResponse<?> response = handshake.getResponse();
                    int status = response.statusCode();
                    String body = response.body() instanceof String text ? text : "";
                    CliFailure refusal = refusal("GET", uri, status, response.headers(), body);
                    if (status >= 500) {
                        throw CliFailure.retryable(refusal.getMessage());
                    }
                    if (status < 300) {
                        throw new CliFailure(refusal.getMessage() + ", not a WebSocket upgrade", CliFailure.FAILED);
                    }
                    String said = credential.explain(status);
                    throw said == null ? refusal
                            : CliFailure.refused(said + System.lineSeparator() + refusal.getMessage(), status);
                }
                throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(cause == null ? failed : cause));
            }
        }

        public void abort() {
            opening.cancel(false);
            try {
                WebSocket open = opening.getNow(null);
                if (open != null) {
                    open.abort();
                }
            } catch (CancellationException | CompletionException notOpen) {
                // Nothing was open; shutting the client down below ends the handshake.
            }
            http.shutdownNow();
        }
    }

    /**
     * A refusal with the credential's own sentence above it, when it has one. The body stays: it is
     * what the service said, and the line above is only what the caller is holding.
     */
    private CliFailure explained(int status, CliFailure refusal) {
        String said = credential.explain(status);
        return said == null ? refusal
                : CliFailure.refused(said + System.lineSeparator() + refusal.getMessage(), status);
    }

    static CliFailure refusal(String method, URI uri, int status, HttpHeaders headers, String body) {
        if (status == 401) {
            String detail = headers.allValues("WWW-Authenticate").stream()
                    .map(PlatformClient::authenticateError)
                    .filter(s -> !s.isEmpty())
                    .findFirst()
                    .orElse("");
            return CliFailure.refused("The platform refused the token (HTTP 401" + (detail.isEmpty() ? "" : ": " + detail)
                    + "). If this goes on, run `qits login`.", status);
        }
        if (status == 403) {
            // The service's own sentence says which rule refused ("this credential is qits:agent,
            // which reads but does not write"); without it a person sees only that something did.
            String serviceMessage = serviceMessage(body);
            return CliFailure.refused("Your roles do not allow this (HTTP 403): " + method + " " + uri
                    + (serviceMessage.isEmpty() ? "" : ": " + serviceMessage), status);
        }
        StringBuilder message = new StringBuilder(method + " " + uri + " answered HTTP " + status);
        String serviceMessage = serviceMessage(body);
        if (!serviceMessage.isEmpty()) {
            message.append(": ").append(serviceMessage);
        }
        if (status >= 300 && status < 400) {
            headers.firstValue("Location").ifPresent(l -> message.append(" (a redirect to ").append(l).append(")"));
        }
        return CliFailure.refused(message.toString(), status);
    }

    /** {@code error} and {@code error_description} of a bearer challenge; never anything else. */
    private static String authenticateError(String challenge) {
        Matcher m = AUTH_PARAM.matcher(challenge);
        String error = "";
        String description = "";
        while (m.find()) {
            if (m.group(1).equals("error")) {
                error = m.group(2);
            } else {
                description = m.group(2);
            }
        }
        return error.isEmpty() ? description : description.isEmpty() ? error : error + ", " + description;
    }

    /**
     * The service's own words: {@code message} (what qits services answer), {@code errors[].message},
     * a validation answer's {@code violations[].message}, the idp's {@code error} and
     * {@code error_description}, or {@code reason}. A body that is not JSON (an HTML page
     * from a proxy) gives nothing; it is not quoted.
     */
    static String serviceMessage(String body) {
        JsonNode node;
        try {
            node = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException notJson) {
            return "";
        }
        if (node == null || !node.isObject()) {
            return "";
        }
        List<String> messages = new ArrayList<>();
        if (node.path("message").isTextual()) {
            messages.add(node.get("message").asText());
        }
        for (String list : List.of("errors", "violations")) {
            for (JsonNode entry : node.path(list)) {
                String text = entry.path("message").asText("");
                String field = entry.path("field").asText("");
                if (!text.isEmpty()) {
                    messages.add(field.isEmpty() ? text : field + ": " + text);
                }
            }
        }
        if (messages.isEmpty() && (node.path("error").isTextual() || node.path("error_description").isTextual())) {
            // The idp's shape: {"error":"invalid_request","error_description":"…"}. Neither holds a
            // credential; the idp writes the description for a person.
            String error = node.path("error").asText("");
            String description = node.path("error_description").asText("");
            messages.add(error.isEmpty() ? description : description.isEmpty() ? error : error + ": " + description);
        }
        if (messages.isEmpty() && node.path("reason").isTextual()) {
            // qits-projects' front desk doors answer {"reason":"…"} (qits-1572).
            messages.add(node.get("reason").asText());
        }
        if (messages.isEmpty() && node.path("title").isTextual()) {
            messages.add(node.get("title").asText());
        }
        return String.join("; ", messages);
    }

    /** The platform's hosts go through the companion when a workstation runs one ({@link CompanionProxy}). */
    private static HttpClient newClient(boolean bypassCompanion) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(bypassCompanion ? CompanionProxy.NONE : CompanionProxy.selector())
                .build();
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + message + ")");
    }
}
