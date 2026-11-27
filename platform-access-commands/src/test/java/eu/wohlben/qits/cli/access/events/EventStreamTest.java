package eu.wohlben.qits.cli.access.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.AccessTokens;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EventStreamTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GAP = "events in the gap are missed (the stream has no replay)";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private SessionFile store;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        store = new SessionFile(home.resolve("qits"));
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1", T0.plusSeconds(900),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(out()).doesNotContain(FakeIdp.SECRET);
        assertThat(err()).doesNotContain(FakeIdp.SECRET);
        assertThat(err().lines()).allMatch(l -> l.matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d.* qits events: .*"));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private EventStream stream(Duration idleLimit) {
        return new EventStream(new PlatformClient(new AccessTokens(store, time, TokenClient::new)),
                URI.create(platform.url() + "/events/api/stream?names=*"),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, idleLimit);
    }

    private static CompletableFuture<Integer> runInBackground(EventStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return stream.run();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the condition did not hold within 10 s");
            }
            Thread.sleep(20);
        }
    }

    private static String envelope(String id, String name, String payloadString) throws Exception {
        return JSON.writeValueAsString(JSON.createObjectNode().put("id", id).put("name", name)
                .put("occurredAt", "2026-09-12T10:00:01Z").put("payload", payloadString)
                .putNull("description").putNull("parentId").put("environment", "dev")
                .put("publisher", "eu.wohlben.qits:qits-ci-service")
                .put("originTraceId", "0af7651916cd43dd8448eb211c80319c").put("onBehalfOf", "user:owner"));
    }

    private static String[] concat(String[]... parts) {
        return Stream.of(parts).flatMap(Stream::of).toArray(String[]::new);
    }

    private List<JsonNode> printed() throws Exception {
        List<JsonNode> lines = new java.util.ArrayList<>();
        for (String line : out().lines().toList()) {
            lines.add(JSON.readTree(line));
        }
        return lines;
    }

    @Test
    void eachEventIsOneJsonLineWithItsPayloadRead() throws Exception {
        platform.streams.add(platform.stream(true, concat(
                new String[] {": open", ": keepalive"},
                FakePlatform.event("e-1", envelope("e-1", "BuildSuccessful", "{\"repoName\":\"qits-ci\",\"number\":7}")),
                new String[] {": keepalive"},
                FakePlatform.event("e-2", envelope("e-2", "SCMDeleteBranch", "not json at all")))));
        EventStream stream = stream(EventStream.IDLE_LIMIT);
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 2);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        List<JsonNode> lines = printed();
        assertThat(lines.get(0).path("name").asText()).isEqualTo("BuildSuccessful");
        assertThat(lines.get(0).path("payload").path("repoName").asText()).isEqualTo("qits-ci");
        assertThat(lines.get(0).path("payload").path("number").asInt()).isEqualTo(7);
        assertThat(lines.get(0).path("environment").asText()).isEqualTo("dev");
        // The stream filters no field of the frame: the chain an event was published in reaches the line.
        assertThat(lines.get(0).path("originTraceId").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(lines.get(0).path("onBehalfOf").asText()).isEqualTo("user:owner");
        assertThat(lines.get(1).path("payload").isTextual()).isTrue();
        assertThat(lines.get(1).path("payload").asText()).isEqualTo("not json at all");
        assertThat(out().lines()).allMatch(l -> !l.contains("\n"));
        assertThat(err()).contains("connected to " + platform.url()).contains("stopped");

        FakePlatform.Request request = platform.requests("GET", "/events/api/stream").getFirst();
        assertThat(request.accept()).isEqualTo("text/event-stream");
        assertThat(request.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-1");
        assertThat(request.query()).isEqualTo("names=*");
    }

    /** The stop comes while the reader is blocked on a silent, open stream. */
    @Test
    void aStopEndsABlockedRead() throws Exception {
        platform.streams.add(platform.stream(true, ": open"));
        EventStream stream = stream(EventStream.IDLE_LIMIT);
        CompletableFuture<Integer> run = runInBackground(stream);
        await(() -> err().contains("connected to"));

        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("stopped").doesNotContain("reconnecting");
    }

    @Test
    void aDroppedStreamIsOpenedAgainAndSaysTheGapIsLost() throws Exception {
        platform.streams.add(platform.stream(false, concat(new String[] {": open"},
                FakePlatform.event("e-1", envelope("e-1", "BuildSuccessful", "{}")))));
        platform.streams.add(platform.stream(true, concat(new String[] {": open"},
                FakePlatform.event("e-2", envelope("e-2", "BuildFailed", "{}")))));
        EventStream stream = stream(EventStream.IDLE_LIMIT);
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 2);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(printed()).extracting(n -> n.path("id").asText()).containsExactly("e-1", "e-2");
        assertThat(err()).contains("the stream ended; reconnecting in 1 s — " + GAP).contains("connected again to");
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void a5xxIsTriedAgainWithAGrowingWait() throws Exception {
        for (int i = 0; i < 7; i++) {
            platform.streams.add(platform.refuse(503, "{\"message\":\"starting\"}"));
        }
        platform.streams.add(platform.refuse(403, "{\"message\":\"forbidden\"}"));

        int exit = stream(EventStream.IDLE_LIMIT).run();

        assertThat(exit).isEqualTo(1);
        assertThat(time.sleeps).extracting(Duration::toSeconds).containsExactly(1L, 2L, 4L, 8L, 16L, 30L, 30L);
        assertThat(err()).contains("answered HTTP 503: starting; reconnecting in 1 s")
                .doesNotContain(GAP)
                .contains("Your roles do not allow this (HTTP 403)");
    }

    @Test
    void a401StopsWithTheChallenge() throws Exception {
        platform.streams.add(platform.refuse(401, "", "WWW-Authenticate",
                "Bearer error=\"invalid_token\", error_description=\"the token has expired\""));

        assertThat(stream(EventStream.IDLE_LIMIT).run()).isEqualTo(1);

        assertThat(err()).contains("The platform refused the token (HTTP 401: invalid_token, the token has expired)");
        assertThat(time.sleeps).isEmpty();
    }

    /** A connection that goes quiet (a suspend can leave one half open) is ended and opened again. */
    @Test
    void aSilentStreamIsOpenedAgain() throws Exception {
        platform.streams.add(platform.stream(true, ": open"));
        platform.streams.add(platform.stream(true, concat(new String[] {": open"},
                FakePlatform.event("e-9", envelope("e-9", "BuildSuccessful", "{}")))));
        EventStream stream = stream(Duration.ofMillis(300));
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 1);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("the stream sent nothing, not even a keepalive, for 300 ms").contains(GAP);
    }

    @Test
    void anAccessTokenAboutToExpireIsRefreshedBeforeConnecting() throws Exception {
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-old", T0.plusSeconds(10),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.streams.add(platform.refuse(403, ""));

        stream(EventStream.IDLE_LIMIT).run();

        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(platform.requests("GET", "/events/api/stream").getFirst().authorization())
                .isNotEqualTo("Bearer " + FakeIdp.SECRET + "access-old")
                .isEqualTo("Bearer " + store.read().orElseThrow().accessToken());
    }
}
