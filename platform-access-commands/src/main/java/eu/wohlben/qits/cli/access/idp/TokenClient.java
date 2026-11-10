package eu.wohlben.qits.cli.access.idp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.platform.CompanionProxy;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The idp's {@code /token} endpoint, for the public client {@code qits-cli}.
 * <p>
 * The client sends its id in the form and nothing else: no secret and no Authorization header.
 * The idp refuses a public client that sends either.
 * <p>
 * <b>No message here holds a token, a code or a verifier.</b> Errors name the HTTP status and the
 * idp's {@code error} and {@code error_description}, which hold none.
 */
public final class TokenClient {

    public static final String CLIENT_ID = "qits-cli";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String idpUrl;
    private final String clientId;

    public TokenClient(String idpUrl) {
        this(idpUrl, CLIENT_ID);
    }

    /** For another public client of the same idp, such as {@code qits-git-workstation}. */
    public TokenClient(String idpUrl, String clientId) {
        this.idpUrl = IdpUrl.trim(idpUrl);
        this.clientId = clientId;
    }

    public String idpUrl() {
        return idpUrl;
    }

    /** {@code qits-cli}'s redirect target: the idp's page that shows the person the code to paste. */
    public String redirectUri() {
        return idpUrl + "/connect/cli";
    }

    public String authorizeUrl(String codeChallenge) {
        return authorizeUrl(codeChallenge, redirectUri(), Map.of());
    }

    /** {@code extra} goes after the standard parameters, in its own order. */
    public String authorizeUrl(String codeChallenge, String redirectUri, Map<String, String> extra) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        query.putAll(extra);
        return idpUrl + "/authorize?" + form(query);
    }

    public TokenResponse exchange(String code, String verifier) throws IdpException {
        return exchange(code, verifier, redirectUri());
    }

    public TokenResponse exchange(String code, String verifier, String redirectUri) throws IdpException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("client_id", clientId);
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("code_verifier", verifier);
        return post(body);
    }

    public TokenResponse refresh(String refreshToken) throws IdpException {
        return refresh(refreshToken, null);
    }

    /** @param audience the audience the new access token is for, or null for the client's default */
    public TokenResponse refresh(String refreshToken, String audience) throws IdpException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("client_id", clientId);
        body.put("refresh_token", refreshToken);
        if (audience != null && !audience.isBlank()) {
            body.put("audience", audience);
        }
        return post(body);
    }

    private TokenResponse post(Map<String, String> values) throws IdpException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(idpUrl + "/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(values)))
                .build();
        HttpResponse<String> response;
        // A fresh client per request. The daemon sends one every quarter of an hour, and a pooled
        // connection that went stale across a suspend would only fail the next try.
        try (HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(CompanionProxy.selector())
                .build()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IdpException.Unreachable("cannot reach " + idpUrl + "/token: " + describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdpException.Unreachable("interrupted while calling " + idpUrl + "/token");
        }
        int status = response.statusCode();
        if (status >= 500) {
            throw new IdpException.Unreachable(idpUrl + "/token answered HTTP " + status);
        }
        if (status < 200 || status >= 300) {
            String error = field(response.body(), "error");
            String description = field(response.body(), "error_description");
            String message = "the idp refused the request (HTTP " + status
                    + (error.isEmpty() ? "" : ", " + error) + (description.isEmpty() ? "" : ": " + description) + ")";
            throw "invalid_grant".equals(error)
                    ? new IdpException.InvalidGrant(message)
                    : new IdpException.Refused(message);
        }
        TokenResponse token;
        try {
            token = JSON.readValue(response.body(), TokenResponse.class);
        } catch (IOException e) {
            // Jackson's message may quote the body, and the body holds tokens.
            throw new IdpException.Refused("the idp's token answer is not valid JSON");
        }
        if (token == null || blank(token.accessToken()) || blank(token.refreshToken())
                || token.expiresIn() == null || token.refreshExpiresIn() == null) {
            throw new IdpException.Refused("the idp's token answer is missing a field");
        }
        return token;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + message + ")");
    }

    private static String field(String body, String name) {
        try {
            JsonNode node = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode value = node.get(name);
            return value == null || value.isNull() ? "" : value.asText();
        } catch (IOException e) {
            return "";
        }
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** The token answer. Fields the idp adds later are ignored. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_in") Long refreshExpiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[expiresIn=" + expiresIn + ", refreshExpiresIn=" + refreshExpiresIn + "]";
        }
    }
}
