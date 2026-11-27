package eu.wohlben.qits.cli.access.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `qits events query` against a fake events log that pages the way qits-events-service's
 * {@code EventController.list} does: {@code since} inclusive, the composite cursor exclusive, both
 * truncated to microseconds, newest first, {@code limit + 1} rows read to know whether there are more.
 */
class EventsQueryCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-01T18:00:00Z");
    private static final String PATH = "/events/api/events";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private final List<Row> log = new ArrayList<>();
    /** Fields set over a row's defaults, by event id: a new key is appended after onBehalfOf. */
    private final Map<String, Map<String, String>> extra = new HashMap<>();
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    record Row(String id, String name, Instant occurredAt) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        SessionFile store = new SessionFile(home.resolve("qits"));
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1", NOW.plusSeconds(900),
                idp.issueRefreshToken(), NOW.plus(Duration.ofDays(30))));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_EVENTS_URL", platform.url());
        platform.route("GET", PATH, request -> new FakePlatform.Answer(200, page(query(request.query())), Map.of()));
        // A live stream that never ends: a query that touched it would hang, and the test would show it.
        platform.streams.add(platform.stream(true, ": keepalive"));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(out()).doesNotContain(FakeIdp.SECRET);
        assertThat(err()).doesNotContain(FakeIdp.SECRET);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private int run(String... args) {
        FakeTime time = new FakeTime(NOW);
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, r -> { });
        return TestCli.execute(context, args);
    }

    private void event(String id, String name, Instant at) {
        log.add(new Row(id, name, at));
    }

    /** {@code n} events one minute apart, the newest a minute before now, ids e01 .. eNN oldest first. */
    private void minutely(int n) {
        for (int i = 1; i <= n; i++) {
            event("e%02d".formatted(i), i % 2 == 0 ? "BuildFailed" : "BuildSuccessful", NOW.minusSeconds(60L * (n - i + 1)));
        }
    }

    // --- the fake log ---

    private static Map<String, String> query(String raw) {
        Map<String, String> params = new HashMap<>();
        for (String pair : (raw == null ? "" : raw).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private String page(Map<String, String> params) {
        List<String> names = params.containsKey("name") ? List.of(params.get("name").split(",")) : List.of();
        Instant since = params.containsKey("since") ? Instant.parse(params.get("since")).truncatedTo(ChronoUnit.MICROS) : null;
        String cursor = params.get("cursor");
        Instant at = cursor == null ? null : Instant.parse(cursor.substring(0, cursor.indexOf(','))).truncatedTo(ChronoUnit.MICROS);
        String atId = cursor == null ? null : cursor.substring(cursor.indexOf(',') + 1);
        int limit = Math.min(Integer.parseInt(params.getOrDefault("limit", "200")), 1000);
        assertThat(params.get("order")).isEqualTo("desc");
        List<Row> rows = log.stream()
                .filter(r -> names.isEmpty() || names.contains(r.name()))
                .filter(r -> since == null || !r.occurredAt().isBefore(since))
                .filter(r -> at == null || r.occurredAt().isBefore(at) || (r.occurredAt().equals(at) && r.id().compareTo(atId) < 0))
                .sorted(Comparator.comparing(Row::occurredAt).thenComparing(Row::id).reversed())
                .limit(limit + 1L)
                .toList();
        boolean more = rows.size() > limit;
        List<Row> kept = more ? rows.subList(0, limit) : rows;
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode events = answer.putArray("events");
        for (Row row : kept) {
            // In EventDto's order: the row's own times sit between environment and publisher.
            ObjectNode written = events.addObject().put("id", row.id()).put("name", row.name())
                    .put("occurredAt", row.occurredAt().toString())
                    .put("payload", "{\"repository\":\"qits-ci-service\"}").putNull("description").putNull("parentId")
                    .put("environment", "dev").put("createdAt", NOW.toString()).put("updatedAt", NOW.toString())
                    .putNull("publisher").putNull("originTraceId").putNull("onBehalfOf");
            extra.getOrDefault(row.id(), Map.of()).forEach(written::put);
        }
        if (more) {
            Row last = kept.getLast();
            answer.put("nextCursor", last.occurredAt() + "," + last.id());
        } else {
            answer.putNull("nextCursor");
        }
        return answer.toString();
    }

    private List<Map<String, String>> asked() {
        return platform.requests("GET", PATH).stream().map(r -> query(r.query())).toList();
    }

    private JsonNode json() throws Exception {
        return JSON.readTree(out());
    }

    // --- the tests ---

    @Test
    void theDefaultWindowIsTheLastHourAndTheLinesAreTheLiveOnes() throws Exception {
        event("old", "BuildSuccessful", NOW.minusSeconds(3601));
        event("a", "BuildSuccessful", NOW.minusSeconds(3600));
        event("b", "BuildFailed", NOW.minusSeconds(10));
        extra.put("b", Map.of("originTraceId", "0af7651916cd43dd8448eb211c80319c", "onBehalfOf", "user:owner"));

        assertThat(run("events", "query")).isZero();

        assertThat(out()).isEqualTo("""
                window: 2026-10-01T17:00:00Z .. 2026-10-01T18:00:00Z
                {"id":"a","name":"BuildSuccessful","occurredAt":"2026-10-01T17:00:00Z","payload":{"repository":"qits-ci-service"},"description":null,"parentId":null,"environment":"dev","publisher":null,"originTraceId":null,"onBehalfOf":null}
                {"id":"b","name":"BuildFailed","occurredAt":"2026-10-01T17:59:50Z","payload":{"repository":"qits-ci-service"},"description":null,"parentId":null,"environment":"dev","publisher":null,"originTraceId":"0af7651916cd43dd8448eb211c80319c","onBehalfOf":"user:owner"}
                """);
        Map<String, String> first = asked().getFirst();
        assertThat(first).doesNotContainKey("name")
                .containsEntry("since", "2026-10-01T17:00:00Z")
                .containsEntry("order", "desc")
                .containsEntry("limit", "100")
                .containsEntry("cursor", "2026-10-01T18:00:00.000001Z,0");
        assertThat(platform.requests("GET", "/events/api/stream")).as("a query never opens the stream").isEmpty();
        assertThat(err()).isEmpty();
    }

    @Test
    void theFirstCursorIsOneMicrosecondPastUntilTruncated() {
        assertThat(EventsQueryCommand.firstCursor(Instant.parse("2026-10-01T18:00:00Z")))
                .isEqualTo("2026-10-01T18:00:00.000001Z,0");
        assertThat(EventsQueryCommand.firstCursor(Instant.parse("2026-10-01T18:00:00.000000999Z")))
                .isEqualTo("2026-10-01T18:00:00.000001Z,0");
        assertThat(EventsQueryCommand.firstCursor(Instant.parse("2026-10-01T18:00:00.999999Z")))
                .isEqualTo("2026-10-01T18:00:01Z,0");
    }

    @Test
    void untilIsInclusiveToTheMicrosecondAndNothingAfterItComes() throws Exception {
        Instant until = Instant.parse("2026-10-01T17:30:00Z");
        event("tie-1", "BuildFailed", until);
        event("tie-2", "BuildFailed", until);
        event("after", "BuildFailed", until.plus(1, ChronoUnit.MICROS));
        event("before", "BuildFailed", until.minus(1, ChronoUnit.MICROS));

        assertThat(run("events", "query", "--since", "1d", "--until", "2026-10-01T17:30:00.000000400Z", "-o", "json"))
                .isZero();

        assertThat(json().path("events").findValuesAsText("id")).containsExactly("before", "tie-1", "tie-2");
        assertThat(json().path("truncated").asBoolean()).isFalse();
        assertThat(asked().getFirst()).containsEntry("cursor", "2026-10-01T17:30:00.000001Z,0");
    }

    @Test
    void itPagesUntilItHasTheLimitAndKeepsTheNewest() throws Exception {
        minutely(30);
        // A tie at a page boundary, which only the composite cursor pages through without loss.
        event("e20b", "BuildFailed", NOW.minusSeconds(60L * 11));

        assertThat(run("events", "query", "--limit", "25", "-o", "json")).isZero();

        List<String> ids = json().path("events").findValuesAsText("id");
        assertThat(ids).hasSize(25).first().isEqualTo("e07");
        assertThat(ids).containsSubsequence("e19", "e20", "e20b", "e21").endsWith("e30").doesNotHaveDuplicates();
        assertThat(json().path("truncated").asBoolean()).isTrue();
        assertThat(asked()).hasSize(1).first().satisfies(q -> assertThat(q).containsEntry("limit", "25"));
    }

    @Test
    void theLargestLimitIsOnePageOfTheLog() throws Exception {
        for (int i = 0; i < 1200; i++) {
            event("x%04d".formatted(i), "BuildFailed", NOW.minusSeconds(3000).plusMillis(i));
        }

        assertThat(run("events", "query", "--limit", "1000", "--filter", "BuildFailed", "-o", "json")).isZero();

        JsonNode events = json().path("events");
        assertThat(events).hasSize(1000);
        assertThat(events.get(0).path("id").asText()).isEqualTo("x0200");
        assertThat(events.get(999).path("id").asText()).isEqualTo("x1199");
        assertThat(json().path("truncated").asBoolean()).isTrue();
        assertThat(asked()).hasSize(1).first().satisfies(q -> assertThat(q).containsEntry("limit", "1000")
                .containsEntry("name", "BuildFailed"));
    }

    @Test
    void itFollowsNextCursorWhenThePageIsShortOfTheLimit() throws Exception {
        minutely(12);
        // A tie on the first page's boundary, which only the composite cursor pages through without loss.
        event("e08b", "BuildFailed", NOW.minusSeconds(60L * 5));
        // A log that pages smaller than it is asked to: the cursor is followed until the limit is in hand.
        platform.route("GET", PATH, request -> {
            Map<String, String> params = new HashMap<>(query(request.query()));
            params.put("limit", "5");
            return new FakePlatform.Answer(200, page(params), Map.of());
        });

        assertThat(run("events", "query", "--limit", "13", "-o", "json")).isZero();

        assertThat(json().path("events").findValuesAsText("id")).containsExactly(
                "e01", "e02", "e03", "e04", "e05", "e06", "e07", "e08", "e08b", "e09", "e10", "e11", "e12");
        assertThat(json().path("truncated").asBoolean()).isFalse();
        List<Map<String, String>> asked = asked();
        assertThat(asked).hasSize(3);
        assertThat(asked.get(0).get("limit")).isEqualTo("13");
        assertThat(asked.get(1).get("limit")).isEqualTo("8");
        assertThat(asked.get(1).get("cursor")).isEqualTo(NOW.minusSeconds(60L * 5) + ",e08b");
        assertThat(asked.get(2).get("limit")).isEqualTo("3");
        assertThat(asked).allSatisfy(q -> assertThat(q).containsEntry("since", "2026-10-01T17:00:00Z"));
    }

    @Test
    void aCutAnswerSaysSoOnItsLastLine() throws Exception {
        minutely(5);

        assertThat(run("events", "query", "--limit", "2", "--since", "10m")).isZero();

        assertThat(out().lines().toList()).hasSize(4)
                .first().isEqualTo("window: 2026-10-01T17:50:00Z .. 2026-10-01T18:00:00Z");
        assertThat(out().lines().toList().get(1)).contains("\"id\":\"e04\"");
        assertThat(out().lines().toList().get(2)).contains("\"id\":\"e05\"");
        assertThat(out().lines().toList().getLast()).isEqualTo("… truncated: 2 shown, more in the window");
    }

    @Test
    void anExactFitIsNotCut() throws Exception {
        minutely(3);
        assertThat(run("events", "query", "--limit", "3")).isZero();
        assertThat(out()).doesNotContain("truncated");
    }

    @Test
    void theJsonFormIsOneObjectWithTheWindow() throws Exception {
        minutely(1);

        assertThat(run("events", "query", "--since", "2026-10-01T17:00:00Z", "--until", "2026-10-01T19:00:00Z",
                "-o", "json", "--filter", "BuildSuccessful,BuildFailed")).isZero();

        assertThat(out().lines().count()).isEqualTo(1);
        assertThat(json()).isEqualTo(JSON.readTree("""
                {"events":[{"id":"e01","name":"BuildSuccessful","occurredAt":"2026-10-01T17:59:00Z",
                            "payload":{"repository":"qits-ci-service"},"description":null,"parentId":null,
                            "environment":"dev","publisher":null,"originTraceId":null,"onBehalfOf":null}],
                 "truncated":false,
                 "window":{"since":"2026-10-01T17:00:00Z","until":"2026-10-01T18:00:00Z"}}
                """));
        assertThat(asked().getFirst()).containsEntry("name", "BuildSuccessful,BuildFailed");
    }

    /** The line drops only the row's own times; a field the log adds later is printed as it comes. */
    @Test
    void aFieldTheRowAppendsReachesTheLine() throws Exception {
        event("a", "BuildFailed", NOW.minusSeconds(10));
        extra.put("a", Map.of("later", "x"));

        assertThat(run("events", "query", "-o", "json")).isZero();

        JsonNode line = json().path("events").get(0);
        assertThat(line.path("later").asText()).isEqualTo("x");
        assertThat(line.has("createdAt")).isFalse();
        assertThat(line.has("updatedAt")).isFalse();
    }

    @Test
    void anEmptyWindowPrintsOnlyTheWindow() throws Exception {
        assertThat(run("events", "query", "--since", "5m")).isZero();
        assertThat(out()).isEqualTo("window: 2026-10-01T17:55:00Z .. 2026-10-01T18:00:00Z\n");
    }

    @Test
    void usageErrorsNameTheOptionAndSendNothing() {
        assertThat(run("events", "query", "--since", "yesterday")).isEqualTo(2);
        assertThat(err()).contains("--since: 'yesterday' is neither an ISO-8601 instant");
        assertThat(run("events", "query", "--since", "5m", "--until", "1h")).isEqualTo(2);
        assertThat(err()).contains("is after --until");
        assertThat(run("events", "query", "--limit", "1001")).isEqualTo(2);
        assertThat(err()).contains("--limit must be between 1 and 1000, not 1001.");
        assertThat(run("events", "query", "--filter", "Build*")).isEqualTo(2);
        assertThat(err()).contains("--filter takes exact event names");
        assertThat(run("events", "query", "-o", "yaml")).isEqualTo(2);
        assertThat(err()).contains("--output must be text or json, not 'yaml'.");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aRefusalIsExitOne() {
        platform.route("GET", PATH, request -> new FakePlatform.Answer(400, "{\"message\":\"since must be an ISO-8601 instant\"}", Map.of()));
        assertThat(run("events", "query")).isEqualTo(1);
        assertThat(err()).contains("answered HTTP 400: since must be an ISO-8601 instant");
    }

    @Test
    void bareEventsStillStreams() throws Exception {
        platform.streams.clear();
        platform.streams.add(platform.stream(true, FakePlatform.event("1", "{\"id\":\"s1\",\"name\":\"BuildFailed\"}")));
        AtomicStop stop = new AtomicStop();
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> {
            FakeTime time = new FakeTime(NOW);
            CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                    time, time, TokenClient::new, stop::set);
            return TestCli.execute(context, "events");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!out().contains("s1") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        stop.run();
        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(out()).isEqualTo("{\"id\":\"s1\",\"name\":\"BuildFailed\"}\n");
        assertThat(platform.requests("GET", PATH)).isEmpty();
    }

    /** The stop the command installs, called once it is there. */
    private static final class AtomicStop {
        private volatile Runnable stop;

        void set(Runnable stop) {
            this.stop = stop;
        }

        void run() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (stop == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            stop.run();
        }
    }
}
