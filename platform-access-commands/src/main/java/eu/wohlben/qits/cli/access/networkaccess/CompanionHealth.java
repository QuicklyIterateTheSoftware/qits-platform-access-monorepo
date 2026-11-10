package eu.wohlben.qits.cli.access.networkaccess;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code GET <companion>/healthz}: the companion's own answer, 200 while its tunnel handshook in the
 * last three minutes. It dials the companion on this machine's loopback, never the platform, so it
 * carries no credential, belongs to no provider's contract, and never goes through a proxy.
 */
final class CompanionHealth {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int BODY_LIMIT = 2000;

    /** The status and the first part of the body. */
    record Answer(int status, String body) {
        boolean healthy() {
            return status == 200;
        }
    }

    private CompanionHealth() {
    }

    /** The answer, or empty when nothing answers at {@code proxy}. */
    static Optional<Answer> ask(String proxy) throws InterruptedException {
        URI uri;
        try {
            uri = URI.create(trim(proxy) + "/healthz");
        } catch (IllegalArgumentException notAnAddress) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        try (HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build()) {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String body = new String(response.body(), StandardCharsets.UTF_8);
            return Optional.of(new Answer(response.statusCode(),
                    body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) : body));
        } catch (IOException | IllegalArgumentException unreachable) {
            return Optional.empty();
        }
    }

    private static String trim(String url) {
        String stripped = url.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
