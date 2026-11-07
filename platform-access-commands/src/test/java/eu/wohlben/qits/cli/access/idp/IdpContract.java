package eu.wohlben.qits.cli.access.idp;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.remembered.IdpRememberedDevicesContract;
import eu.wohlben.qits.cli.access.users.IdpUsersContract;
import eu.wohlben.qits.pact.consumer.Trigger;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the commands ask the idp, and why</b> (ticket qits-1149): the token endpoint, for each of
 * the CLI's two sessions, the user doors of {@code qits users}
 * ({@code users/IdpUsersContract}) and the remembered-devices doors of {@code qits remembered-devices}
 * ({@code remembered/IdpRememberedDevicesContract}, qits-1944).
 * <ul>
 *   <li><b>A person's session</b> ({@link TokenClient}, client {@code qits-cli}): {@code qits login}
 *       exchanges the pasted code, and {@code qits session-daemon} and every platform command on a
 *       workstation ({@code AccessTokens}, here {@code qits work list}) refresh it, through the one
 *       {@code SessionRefresh}.</li>
 *   <li><b>Git's session</b> ({@link TokenClient}, client {@code qits-git-workstation}): {@code qits
 *       git-login} exchanges the loopback callback's code, {@code qits git-credential} refreshes it
 *       for {@code qits-platform}.</li>
 * </ul>
 * Every request goes as a form, compared field by field with the recording, so each row's call
 * sends the state's own values. The one exception is {@code qits login}'s exchange: its {@code
 * redirect_uri} is the idp's own page, {@code <idp>/connect/cli}, whose host is wherever the CLI
 * reaches the idp, so that field is held to {@link #CONNECT_PAGE}, and is generated from the state's
 * {@code redirectUri} when the provider verifies it. {@code GET /idp/authorize}, which
 * the provider records too, is the browser's request, never the CLI's: it has no row.
 */
public final class IdpContract {

    /** The code the idp's connect page shows, issued for {@code redirect_uri=<idp>/connect/cli}. */
    static final String CONNECT_PAGE_CODE = "an authorization code issued to the CLI for the connect page";
    /**
     * {@code qits login}'s {@code redirect_uri}, {@link TokenClient#redirectUri}: the connect page of
     * whatever idp the CLI reaches, so its host is the CLI's idp address, never one fixed value. The
     * provider records its own ({@code http://dev-qits-idp:8080/idp/connect/cli}), the mock serves
     * another; the path is what both hold.
     */
    static final String CONNECT_PAGE = "https?://[^/?#]+/idp/connect/cli";
    static final String GIT_CODE = "an authorization code issued to the git client";
    static final String SESSION = "a session to refresh";
    static final String GIT_SESSION = "a git session to refresh";
    static final String SPENT_SESSION = "no session with the given refresh token";
    /** The idp state that records the discovery document and the JWKS, its key named {@code kid}. */
    static final String SIGNING_KEY = "a published signing key";

    /** The MCP service's quarkus-oidc tenant, which reads the idp when the service starts. */
    public static final Trigger MCP_TENANT = Trigger.event("StartupEvent").from("qits-platform-access-mcp-service");

    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    interface Call {
        void run(String idp, java.util.Map<String, String> params) throws Exception;
    }

    /** The idp is served under {@code /idp}, as the CLI addresses it. */
    private static CliRow.Call idp(Call call) {
        return (url, recorded) -> call.run(url + "/idp", recorded.params());
    }

    /** What every grant's answer is read for ({@code TokenClient.TokenResponse}). */
    static final List<String> SESSION_TOKENS = List.of("access_token", "expires_in", "refresh_token",
            "refresh_expires_in");

    private static void session(TokenClient.TokenResponse token) {
        assertThat(token.accessToken()).isNotBlank();
        assertThat(token.refreshToken()).isNotBlank();
        assertThat(token.expiresIn()).isPositive();
        assertThat(token.refreshExpiresIn()).isPositive();
    }

    /** {@code qits login}: the code the person pasted, for the connect page it was shown on. */
    private static final Call LOGIN = (idp, params) -> session(new TokenClient(idp).exchange(params.get("code"),
            params.get("codeVerifier")));

    private static final Call GIT_LOGIN = (idp, params) -> session(new TokenClient(idp, params.get("clientId"))
            .exchange(params.get("code"), params.get("codeVerifier"), params.get("redirectUri")));

    private static final Call REFRESH = (idp, params) -> session(new TokenClient(idp).refresh(params.get("refreshToken")));

    private static final Call GIT_REFRESH = (idp, params) -> session(new TokenClient(idp, params.get("clientId"))
            .refresh(params.get("refreshToken"), params.get("audience")));

    /** A spent refresh token is {@code invalid_grant}: the session is over, and the person signs in again. */
    private static final Call SPENT = (idp, params) ->
            assertThatThrownBy(() -> new TokenClient(idp).refresh(params.get("refreshToken")))
                    .isInstanceOf(IdpException.InvalidGrant.class).hasMessageContaining("invalid_grant");

    /**
     * The discovery document, as the tenant reads it: the issuer, compared with the recorded one by
     * value, and the two addresses it takes from it.
     */
    private static final CliRow.Call DISCOVERY = (url, recorded) -> {
        JsonNode document = JSON.readTree(get(url + recorded.path()));
        assertThat(document.path("issuer").asText()).as("the issuer every bearer's iss is checked against")
                .isEqualTo(IdpContract.CONTRACT.masters().json(SIGNING_KEY, "getOpenIdConfiguration").path("issuer").asText());
        assertThat(URI.create(document.path("jwks_uri").asText()).isAbsolute()).isTrue();
        assertThat(URI.create(document.path("token_endpoint").asText()).isAbsolute()).isTrue();
    };

    /** The JWKS: the state's key, an RSA signing key for RS256 that a public key can be built from. */
    private static final CliRow.Call JWKS = (url, recorded) -> {
        JsonNode keys = JSON.readTree(get(url + recorded.path())).path("keys");
        JsonNode key = null;
        for (JsonNode k : keys) {
            if (recorded.params().get("kid").equals(k.path("kid").asText())) {
                key = k;
            }
        }
        assertThat(key).as("the key named by the state's kid in %s", keys).isNotNull();
        assertThat(key.path("kty").asText()).isEqualTo("RSA");
        assertThat(key.path("use").asText()).isEqualTo("sig");
        assertThat(key.path("alg").asText()).isEqualTo("RS256");
        Base64.Decoder b64 = Base64.getUrlDecoder();
        KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, b64.decode(key.path("n").asText())),
                new BigInteger(1, b64.decode(key.path("e").asText()))));
    };

    /** A plain GET, as quarkus-oidc's own reads are: 200 and the body. */
    private static String get(String url) throws Exception {
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        return answer.body();
    }

    static final List<CliRow> ROWS = rows();

    public static final CliPact CONTRACT = CliPact.of("qits-idp-service", "qits-idp")
            .client(TokenClient.class)
            .client(IdpUsersContract.CLIENT)
            .client(IdpRememberedDevicesContract.CLIENT)
            .excluded("TokenClient#authorizeUrl", "the address the browser is sent to (GET /idp/authorize); the"
                    + " browser makes that request, never the CLI")
            .excluded("TokenClient#redirectUri", "the connect page's address, sent inside the exchange's form")
            .excluded("TokenClient#idpUrl", "an accessor, no request")
            .rows(ROWS);

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        rows.add(CliRow.of("qits login", CONNECT_PAGE_CODE, "issueToken", "TokenClient#exchange", idp(LOGIN))
                .consumes(SESSION_TOKENS).request("POST /idp/token")
                .fieldMatches("redirect_uri", CONNECT_PAGE));
        rows.add(CliRow.of("qits git-login", GIT_CODE, "issueToken", "TokenClient#exchange", idp(GIT_LOGIN))
                .consumes(SESSION_TOKENS));
        rows.add(CliRow.of("qits session-daemon", SESSION, "issueToken", "TokenClient#refresh", idp(REFRESH))
                .consumes(SESSION_TOKENS));
        rows.add(CliRow.of("qits session-daemon", SPENT_SESSION, "issueToken", "TokenClient#refresh", idp(SPENT))
                .consumes("error", "error_description"));
        rows.add(CliRow.of("qits work list", SESSION, "issueToken", "TokenClient#refresh", idp(REFRESH))
                .consumes(SESSION_TOKENS));
        rows.add(CliRow.of("qits git-credential", GIT_SESSION, "issueToken", "TokenClient#refresh", idp(GIT_REFRESH))
                .consumes(SESSION_TOKENS));
        rows.addAll(IdpUsersContract.ROWS);
        rows.addAll(IdpRememberedDevicesContract.ROWS);
        // The MCP service's quarkus-oidc tenant (qits-708): the discovery document read from
        // auth-server-url at startup, then the JWKS at the jwks_uri it names.
        rows.add(CliRow.framework(MCP_TENANT, SIGNING_KEY, "getOpenIdConfiguration", DISCOVERY)
                .consumes("issuer", "jwks_uri", "token_endpoint").exact("issuer"));
        rows.add(CliRow.framework(MCP_TENANT, SIGNING_KEY, "getJwks", JWKS)
                .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use"));
        return List.copyOf(rows);
    }

    private IdpContract() {
    }
}
