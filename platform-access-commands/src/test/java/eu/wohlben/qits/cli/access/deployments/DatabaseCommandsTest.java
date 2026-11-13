package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits deployments database move|moves|move-status` against a fake edge and a fake idp (qits-2175). */
class DatabaseCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    private static final String MOVES = "/deployments/api/database-moves";
    private static final String ID = "6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String QUEUED = """
            {"id":"%s","batchId":"%s","applicationName":"qits-ci","environmentName":"dev","resourceName":"db",
             "sourceServer":"public","sourceDatabase":"qits_ci","targetServer":"private","targetDatabase":"qits_ci",
             "specServer":"public","specDatabase":"qits_ci","keptDatabase":null,"origin":"OPERATOR",
             "requestedBy":"an-operator","state":"QUEUED","sourceCounts":null,"targetCounts":null,
             "sourceRows":null,"targetRows":null,"detail":null,"createdAt":"2026-11-13T10:00:00Z",
             "startedAt":null,"finishedAt":null,"dropAfter":null,"droppedAt":null}
            """.formatted(ID, ID);

    private static final String MOVED = """
            {"id":"%s","batchId":"%s","applicationName":"qits-ci","environmentName":"dev","resourceName":"db",
             "sourceServer":"public","sourceDatabase":"qits_ci","targetServer":"private","targetDatabase":"qits_ci",
             "specServer":null,"specDatabase":null,"keptDatabase":"qits_ci_moved_202611131000","origin":"DEPLOYMENT",
             "requestedBy":null,"state":"MOVED",
             "sourceCounts":{"public.flyway_schema_history":4,"public.items":12},
             "targetCounts":{"public.flyway_schema_history":4,"public.items":12},
             "sourceRows":16,"targetRows":16,"detail":null,"createdAt":"2026-11-13T10:00:00Z",
             "startedAt":"2026-11-13T10:00:05Z","finishedAt":"2026-11-13T10:01:00Z",
             "dropAfter":"2026-11-20T10:01:00Z","droppedAt":null}
            """.formatted(ID, ID);

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_DEPLOYMENTS_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("POST", MOVES, 202, "{\"move\":" + QUEUED + ",\"note\":\"this overrides the spec: the next "
                + "deployment of qits-ci moves it back to public/qits_ci, which its spec resolves to\"}");
        platform.answer("GET", MOVES, "{\"moves\":[" + MOVED + "]}");
        platform.answer("GET", MOVES + "/" + ID, "{\"move\":" + MOVED + ",\"note\":null}");
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void movePostsOnlyWhatWasGivenAndPrintsTheMoveAndItsNote() throws Exception {
        Result r = run("deployments", "database", "move", "--application", "qits-ci", "--resource", "db",
                "--server", "PRIVATE");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request sent = platform.requests("POST", MOVES).getFirst();
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(
                "{\"application\":\"qits-ci\",\"resource\":\"db\",\"server\":\"private\"}"));
        assertThat(sent.contentType()).startsWith("application/json");
        assertThat(r.out().lines().toList()).containsExactly(
                "move " + ID + ": QUEUED",
                "qits-ci db: public/qits_ci -> private/qits_ci",
                "note: this overrides the spec: the next deployment of qits-ci moves it back to public/qits_ci, "
                        + "which its spec resolves to",
                "Follow it: qits deployments database move-status --id " + ID);
    }

    @Test
    void moveWithoutANoteSaysNothingOfOneAndSendsTheDatabase() throws Exception {
        platform.answer("POST", MOVES, 202, "{\"move\":" + QUEUED + ",\"note\":null}");

        Result r = run("deployments", "database", "move", "--application", "qits-ci", "--database", "qits_ci_new");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", MOVES).getFirst().body())).isEqualTo(JSON.readTree(
                "{\"application\":\"qits-ci\",\"database\":\"qits_ci_new\"}"));
        assertThat(r.out()).doesNotContain("note:");
    }

    @Test
    void moveRefusesAnUnknownServerBeforeSendingAnything() {
        Result r = run("deployments", "database", "move", "--application", "qits-ci", "--server", "elsewhere");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--server is public or private");
        assertThat(platform.requests("POST", MOVES)).isEmpty();
    }

    @Test
    void aRefusedMoveIsExitOneWithTheServicesWords() {
        platform.answer("POST", MOVES, 409, "{\"message\":\"qits-ci's db is already on private/qits_ci\"}");

        Result r = run("deployments", "database", "move", "--application", "qits-ci", "--server", "private");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("409").contains("already on private/qits_ci");
        assertThat(r.err()).doesNotContain("reads but does not write");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void moveJsonIsTheServicesAnswer() {
        Result r = run("deployments", "database", "move", "--application", "qits-ci", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("\"state\" : \"QUEUED\"").contains("\"note\" : \"this overrides the spec");
    }

    @Test
    void movesIsATableOfEveryMove() {
        Result r = run("deployments", "database", "moves");

        assertThat(r.exit()).as(r.err()).isZero();
        String header = r.out().lines().findFirst().orElseThrow();
        assertThat(header).containsSubsequence("ID", "APPLICATION", "RESOURCE", "SOURCE", "TARGET", "STATE", "STARTED",
                "FINISHED", "DROP AFTER");
        assertThat(r.out()).contains(ID).contains("qits-ci").contains("public/qits_ci").contains("private/qits_ci")
                .contains("MOVED");
        assertThat(platform.requests("GET", MOVES).getFirst().query()).isNull();
    }

    @Test
    void movesOfOneApplicationAsksForIt() {
        platform.answer("GET", MOVES, "{\"moves\":[]}");

        Result r = run("deployments", "database", "moves", "--application", "qits-ci");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().strip()).isEqualTo("No database moves of qits-ci.");
        assertThat(platform.requests("GET", MOVES).getFirst().query()).isEqualTo("application=qits-ci");
    }

    @Test
    void moveStatusShowsTheMoveAndItsPerTableCounts() {
        Result r = run("deployments", "database", "move-status", "--id", ID);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains(ID).contains("MOVED").contains("public/qits_ci").contains("private/qits_ci")
                .contains("qits_ci_moved_202611131000").contains("DEPLOYMENT").contains("16 -> 16")
                .doesNotContain("spec resolves");
        assertThat(r.out().lines().filter(l -> l.startsWith("public.items")).findFirst().orElseThrow())
                .matches("public\\.items\\s+12\\s+12");
        assertThat(r.out()).contains("TABLE").doesNotContain("!");
    }

    @Test
    void moveStatusMarksATableWhoseSidesDiffer() {
        platform.answer("GET", MOVES + "/" + ID, "{\"move\":" + MOVED.replace(
                "\"targetCounts\":{\"public.flyway_schema_history\":4,\"public.items\":12}",
                "\"targetCounts\":{\"public.flyway_schema_history\":4,\"public.items\":11}") + ",\"note\":null}");

        Result r = run("deployments", "database", "move-status", "--id", ID);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().filter(l -> l.startsWith("public.items")).findFirst().orElseThrow())
                .matches("public\\.items\\s+12\\s+11\\s+!");
    }

    @Test
    void aQueuedMoveHasNoCountsYet() {
        platform.answer("GET", MOVES + "/" + ID, "{\"move\":" + QUEUED + ",\"note\":null}");

        Result r = run("deployments", "database", "move-status", "--id", ID);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("QUEUED").contains("spec resolves").contains("- -> -").doesNotContain("TABLE");
    }

    @Test
    void anUnknownMoveIsExitOne() {
        platform.answer("GET", MOVES + "/" + ID, 404, "{\"message\":\"no database move " + ID + "\"}");

        Result r = run("deployments", "database", "move-status", "--id", ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("404");
    }
}
