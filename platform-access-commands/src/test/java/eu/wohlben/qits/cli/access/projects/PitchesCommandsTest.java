package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qits pitches list|show|create|add-version|archive (qits-2155), as an agent types them, in this
 * process, against a fake platform and a fake idp.
 */
class PitchesCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char ESC = 0x1B;

    private static final String PITCH = "22222222-0000-4000-8000-000000000002";
    private static final String REPOSITORY = "44444444-0000-4000-8000-000000000004";
    private static final String PITCHES = "/projects/api/pitches";
    private static final String OPENAPI = "/projects/q/openapi";

    /** A pitch as the list answers it. Its title carries an escape sequence, as any agent can write one. */
    private static final String SUMMARY = """
            {"id":"%s","agent":"ui-builder","repositoryId":"%s","repositoryName":"qits-landing-app",
             "route":"/work","screenshot":"work/board-desktop-light.html","kind":"DATA","viewport":"DESKTOP",
             "colorScheme":"LIGHT","manifestVersion":"2026.1113.1","title":"A calmer \\u001b[2Jboard header",
             "note":"The banner folds into the toolbar.","latestVersion":2,"archivedAt":null,
             "createdAt":"2026-11-13T08:00:00Z","updatedAt":"2026-11-13T09:00:00Z"}
            """.formatted(PITCH, REPOSITORY);

    private static final String DETAIL = SUMMARY.strip().replaceFirst("}$", """
            ,"versions":[{"number":1,"htmlBytes":27,"note":"First cut","createdAt":"2026-11-13T08:00:00Z"},
             {"number":2,"htmlBytes":45,"note":"Filters beside the title","createdAt":"2026-11-13T09:00:00Z"}]}
            """);

    private static final String VERSION = """
            {"number":2,"html":"<main><h1>Board</h1>\\n<nav>Filters</nav></main>","htmlBytes":45,
             "note":"Filters beside the title","createdAt":"2026-11-13T09:00:00Z"}
            """;

    private static final String ADDED = """
            {"number":3,"htmlBytes":57,"note":"Saved views","createdAt":"2026-11-13T10:00:00Z"}
            """;

    private static final String DOCUMENT = """
            {"openapi":"3.1.0","paths":{
              "/projects/api/pitches":{"post":{"requestBody":{"content":{"application/json":{
                "schema":{"$ref":"#/components/schemas/NewPitch"}}}}}},
              "/projects/api/pitches/{id}/versions":{"post":{"requestBody":{"content":{"application/json":{
                "schema":{"$ref":"#/components/schemas/NewPitchVersion"}}}}}}},
             "components":{"schemas":{
              "NewPitch":{"type":"object","required":["project","html"],"properties":{"project":{"type":"string"},
                "html":{"type":"string"},"manifestVersion":{"type":"string"}}},
              "NewPitchVersion":{"type":"object","required":["html"],"properties":{"html":{"type":"string"},
                "note":{"type":"string","description":"What changed."}}}}}}
            """;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        SessionFile store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        platform.answer("GET", PITCHES, "{\"pitches\":[" + SUMMARY + "]}");
        platform.answer("GET", PITCHES + "/" + PITCH, DETAIL);
        platform.answer("GET", PITCHES + "/" + PITCH + "/versions/latest", VERSION);
        platform.answer("GET", PITCHES + "/" + PITCH + "/versions/2", VERSION);
        platform.answer("POST", PITCHES, 201, DETAIL);
        platform.answer("POST", PITCHES + "/" + PITCH + "/versions", 201, ADDED);
        platform.answer("POST", PITCHES + "/" + PITCH + "/archive",
                DETAIL.replace("\"archivedAt\":null", "\"archivedAt\":\"2026-11-13T10:00:00Z\""));
        platform.answer("GET", OPENAPI, DOCUMENT);
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result run(String... args) {
        return runWithInput(InputStream.nullInputStream(), args);
    }

    private Result runWithInput(String stdin, String... args) {
        return runWithInput(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), args);
    }

    private Result runWithInput(InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), in,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), time, time, TokenClient::new, stop -> { });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        allOut.writeBytes(out.toByteArray());
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private boolean anyWrite() {
        synchronized (platform.requests) {
            return platform.requests.stream().anyMatch(r -> !r.method().equals("GET"));
        }
    }

    private JsonNode sent(String method, String path) throws Exception {
        List<FakePlatform.Request> all = platform.requests(method, path);
        assertThat(all).as(method + " " + path).hasSize(1);
        return JSON.readTree(all.getFirst().body());
    }

    // --- list ---

    @Test
    void listPrintsATableAndSendsTheProjectAlone() {
        Result r = run("pitches", "list", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PITCHES).getFirst().query()).isEqualTo("project=qits");
        assertThat(r.out().lines().toList().getFirst()).startsWith("ID").contains("AGENT").contains("SCREENSHOT")
                .contains("VERSION").contains("ARCHIVED").contains("TITLE");
        assertThat(r.out()).contains(PITCH).contains("ui-builder").contains("qits-landing-app")
                .contains("work/board-desktop-light.html").contains("calmer").doesNotContain(String.valueOf(ESC));
    }

    @Test
    void listSendsEveryFilterAndTheArchivedOnesOnlyWhenAsked() {
        Result r = run("pitches", "list", "--project", "qits", "--agent", "ui-builder", "--repository",
                "qits-landing-app", "--route", "/work", "--screenshot", "work/board-desktop-light.html", "--archived");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PITCHES).getFirst().query()).isEqualTo(
                "project=qits&agent=ui-builder&repository=qits-landing-app&route=%2Fwork"
                        + "&screenshot=work%2Fboard-desktop-light.html&archived=true");
    }

    @Test
    void listWithoutAProjectIsAUsageErrorWithNoRequest() {
        Result r = run("pitches", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--project");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void anEmptyListSaysSo() {
        platform.answer("GET", PITCHES, "{\"pitches\":[]}");

        Result r = run("pitches", "list", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("No pitches match." + System.lineSeparator());
    }

    // --- show ---

    @Test
    void showPrintsThePitchAndItsVersionsWithoutHtml() {
        Result r = run("pitches", "show", "--pitch", PITCH);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("PITCH " + PITCH).contains("ui-builder")
                .contains("qits-landing-app (" + REPOSITORY + ")").contains("DATA, DESKTOP, LIGHT, manifest 2026.1113.1")
                .contains("The banner folds into the toolbar.").contains("Versions (2):")
                .contains("First cut").contains("Filters beside the title")
                .doesNotContain(String.valueOf(ESC)).doesNotContain("<main>");
    }

    @Test
    void showWithANumberPrintsThatVersionsHtmlAlone() {
        Result r = run("pitches", "--pitch", PITCH, "show", "--number", "LATEST");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PITCHES + "/" + PITCH + "/versions/latest")).hasSize(1);
        assertThat(r.out()).isEqualTo("<main><h1>Board</h1>" + System.lineSeparator() + "<nav>Filters</nav></main>"
                + System.lineSeparator());
    }

    @Test
    void showWithANumberAsJsonIsTheServicesAnswer() throws Exception {
        Result r = run("pitches", "show", "--pitch", PITCH, "--number", "2", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(VERSION));
    }

    @Test
    void showRefusesANumberThatIsNeitherANumberNorLatest() {
        for (String bad : List.of("0", "-1", "two", "1.5", "../archive")) {
            Result r = run("pitches", "show", "--pitch", PITCH, "--number", bad);

            assertThat(r.exit()).as(bad).isEqualTo(2);
            assertThat(r.err()).as(bad).contains("--number is a number from 1, or latest");
        }
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void showWithoutAPitchIsAUsageErrorWithNoRequest() {
        Result r = run("pitches", "show");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--pitch");
        assertThat(platform.requests).isEmpty();
    }

    // --- create and add-version ---

    @Test
    void createSendsThePayloadUnchangedAndPrintsThePitch() throws Exception {
        String payload = "{\"project\":\"qits\",\"repository\":\"qits-landing-app\",\"route\":\"/work\","
                + "\"html\":\"<main></main>\",\"extra\":[1]}";
        Result r = runWithInput(payload, "pitches", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", PITCHES)).isEqualTo(JSON.readTree(payload));
        assertThat(platform.requests("GET", OPENAPI)).isEmpty();
        assertThat(r.out()).contains("PITCH " + PITCH).contains("Versions (2):");
    }

    @Test
    void createWithNothingOnStdinPrintsTheSchemaAndSendsNothing() {
        Result r = run("pitches", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(r.out()).contains("Nothing on stdin, so nothing was sent.").contains("POST /projects/api/pitches")
                .contains("\"manifestVersion\"");
    }

    @Test
    void createRefusedPrintsTheServicesSentence() {
        platform.answer("POST", PITCHES, 403, "{\"message\":\"Only a named agent pitches\"}");

        Result r = runWithInput("{\"project\":\"qits\"}", "pitches", "create");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403").contains("Only a named agent pitches");
    }

    @Test
    void addVersionSendsThePayloadUnchangedAndPrintsTheVersion() throws Exception {
        String payload = "{\"html\":\"<main><p>Saved</p></main>\",\"note\":\"Saved views\"}";
        Result r = runWithInput(payload, "pitches", "add-version", "--pitch", PITCH);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", PITCHES + "/" + PITCH + "/versions")).isEqualTo(JSON.readTree(payload));
        assertThat(r.out()).contains("VERSION 3").contains("57").contains("Saved views");
    }

    @Test
    void addVersionWithNothingOnStdinPrintsTheSchemaAndSendsNothing() {
        Result r = run("pitches", "add-version");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(r.out()).contains("POST /projects/api/pitches/{id}/versions").contains("What changed.");
    }

    @Test
    void addVersionWithoutAPitchIsAUsageErrorWithNoRequest() {
        Result r = runWithInput("{\"html\":\"x\"}", "pitches", "add-version");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--pitch");
        assertThat(platform.requests).isEmpty();
    }

    // --- archive ---

    @Test
    void archiveSendsNoBodyAndPrintsThePitch() {
        Result r = run("pitches", "archive", "--pitch", PITCH);

        assertThat(r.exit()).as(r.err()).isZero();
        List<FakePlatform.Request> sent = platform.requests("POST", PITCHES + "/" + PITCH + "/archive");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().body()).isEmpty();
        assertThat(r.out()).contains("PITCH " + PITCH).contains("archived").contains("yes (");
    }

    @Test
    void archiveAsJsonIsTheServicesAnswer() throws Exception {
        Result r = run("pitches", "--pitch", PITCH, "archive", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out()).path("archivedAt").asText()).isEqualTo("2026-11-13T10:00:00Z");
        assertThat(r.out()).doesNotContain(String.valueOf(ESC));
    }
}
