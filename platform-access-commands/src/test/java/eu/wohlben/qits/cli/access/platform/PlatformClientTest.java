package eu.wohlben.qits.cli.access.platform;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformClientTest {

    private static final URI URL = URI.create("https://projects.qits.wohlben.eu/projects/api/projects");

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (k, v) -> true);
    }

    @Test
    void theServiceMessageIsReadFromEachKnownShape() {
        assertThat(PlatformClient.serviceMessage("{\"message\":\"repository not found\"}")).isEqualTo("repository not found");
        assertThat(PlatformClient.serviceMessage("{\"errors\":[{\"message\":\"a\"},{\"message\":\"b\"}]}")).isEqualTo("a; b");
        assertThat(PlatformClient.serviceMessage(
                "{\"title\":\"Constraint Violation\",\"violations\":[{\"field\":\"create.body.branch\",\"message\":\"must not be blank\"}]}"))
                .isEqualTo("create.body.branch: must not be blank");
        assertThat(PlatformClient.serviceMessage("{\"title\":\"Bad Request\"}")).isEqualTo("Bad Request");
        assertThat(PlatformClient.serviceMessage("{\"reason\":\"the front desk is not running\"}"))
                .isEqualTo("the front desk is not running");
        assertThat(PlatformClient.serviceMessage("<html>gateway</html>")).isEmpty();
        assertThat(PlatformClient.serviceMessage("")).isEmpty();
    }

    @Test
    void a401NamesTheChallengeError() {
        CliFailure failure = PlatformClient.refusal("GET", URL, 401, headers(Map.of("WWW-Authenticate",
                List.of("Bearer realm=\"qits\", error=\"invalid_token\", error_description=\"the token has expired\""))), "");
        assertThat(failure.getMessage())
                .startsWith("The platform refused the token (HTTP 401: invalid_token, the token has expired)");
        assertThat(failure.exitCode()).isEqualTo(CliFailure.FAILED);
        assertThat(PlatformClient.refusal("GET", URL, 401, headers(Map.of()), "").getMessage())
                .startsWith("The platform refused the token (HTTP 401).");
    }

    @Test
    void a403AndOtherStatusesSayWhatHappened() {
        assertThat(PlatformClient.refusal("GET", URL, 403, headers(Map.of()), "{\"message\":\"x\"}").getMessage())
                .isEqualTo("Your roles do not allow this (HTTP 403): GET " + URL + ": x");
        assertThat(PlatformClient.refusal("GET", URL, 403, headers(Map.of()), "").getMessage())
                .isEqualTo("Your roles do not allow this (HTTP 403): GET " + URL);
        assertThat(PlatformClient.refusal("POST", URL, 409, headers(Map.of()), "{\"message\":\"stale\"}").getMessage())
                .isEqualTo("POST " + URL + " answered HTTP 409: stale");
        assertThat(PlatformClient.refusal("POST", URL, 409, headers(Map.of()), "").status()).isEqualTo(409);
        assertThat(PlatformClient.refusal("GET", URL, 403, headers(Map.of()), "").status()).isEqualTo(403);
        assertThat(PlatformClient.refusal("GET", URL, 302, headers(Map.of("Location", List.of("https://idp/login"))), "").getMessage())
                .contains("HTTP 302").contains("a redirect to https://idp/login");
    }

    /** A platform host, which nothing resolves: only the companion can have answered for it. */
    private static final URI PRIVATE = URI.create("https://projects.qits.example.test/projects/api/projects");

    private static void companionAt(ServerSocket server) {
        CompanionProxy.replaceForTests(new CompanionProxy(
                InetSocketAddress.createUnresolved("127.0.0.1", server.getLocalPort()), ".qits.example.test",
                Set.of(), new PrintStream(PrintStream.nullOutputStream()), companion -> true));
    }

    /**
     * The tiny CONNECT server: reads one request head and refuses the tunnel, which is enough to see
     * what the client asked for without a TLS server behind it.
     */
    private static CompletableFuture<List<String>> oneConnect(ServerSocket server) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                List<String> head = new ArrayList<>();
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    head.add(line);
                }
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return head;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aPlatformCallGoesThroughTheCompanionAsAConnect() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<List<String>> head = oneConnect(server);
            companionAt(server);
            try {
                assertThatThrownBy(() -> new PlatformClient(() -> "the-bearer").get(PRIVATE))
                        .isInstanceOf(CliFailure.class)
                        .hasMessageContaining("Cannot reach " + PRIVATE);
            } finally {
                CompanionProxy.replaceForTests(null);
            }
            List<String> request = head.get(10, TimeUnit.SECONDS);
            assertThat(request.getFirst()).isEqualTo("CONNECT projects.qits.example.test:443 HTTP/1.1");
            // The bearer goes inside the tunnel, never to the companion.
            assertThat(String.join("\n", request)).doesNotContain("the-bearer");
        }
    }

    @Test
    void theNetworkAccessCommandsClientNeverUsesTheCompanion() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            companionAt(server);
            try {
                assertThatThrownBy(() -> PlatformClient.direct(() -> "the-bearer").get(PRIVATE))
                        .isInstanceOf(CliFailure.class);
            } finally {
                CompanionProxy.replaceForTests(null);
            }
            server.setSoTimeout(300);
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }
}
