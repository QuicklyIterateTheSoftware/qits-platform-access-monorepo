package eu.wohlben.qits.cli.access.uibuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qits ui-builder routes|freeze|golden-masters|entities (qits-2156), as the ui-builder agent types
 * them, in this process, against a fake docs store and a fake idp.
 */
class UiBuilderCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DOCS = "/artifacts/docs/docs";
    private static final String ROUTES = "@routes/qits-landing";
    private static final String PROJECTS = "@contracts/qits-projects";
    private static final String GITHOST = "@contracts/qits-githost";
    private static final String FREEZE = "src/app/routes/projects/[slug]/work/work.page.browser.spec.ts/board-desktop.html";

    private static final String SITES = """
            {"sites":[
             {"name":"@contracts/qits-githost","versionCount":1,"latestVersion":"2100.1.0"},
             {"name":"@contracts/qits-projects","versionCount":2,"latestVersion":"2100.2.0"},
             {"name":"@routes/qits-landing","versionCount":2,"latestVersion":"2026.1113.2"},
             {"name":"some-docs-site","versionCount":1,"latestVersion":"1.0.0"}]}
            """;

    private static String versions(String site, String repository, String... versions) {
        StringBuilder list = new StringBuilder();
        for (String version : versions) {
            list.append(list.isEmpty() ? "" : ",").append("""
                    {"name":"%s","version":"%s","metadata":{"git.repository.name":"%s"}}""".formatted(site, version,
                    repository));
        }
        return "{\"name\":\"" + site + "\",\"versions\":[" + list + "]}";
    }

    private static final String MANIFEST = """
            {"routes":[{"path":"projects/:slug/work","fullPath":"projects/:slug/work"}],
             "freezes":[
              {"file":"%s","name":"board","route":"projects/:slug/work",
               "records":[{"provider":"qits-projects-service","state":"a project exists","operationId":"listProjects"}],
               "triggers":[],"kind":"DATA","viewport":"DESKTOP","colorScheme":"DARK","dataMarks":[],
               "connectivity":"online"},
              {"file":"src/app/layout/slots.html","name":"slots","route":null,"records":[]}],
             "byRoute":{"projects/:slug/work":["%s"]},"colorSchemes":["LIGHT","DARK"],"outcomes":{}}
            """.formatted(FREEZE, FREEZE);

    private static final String HTML = "<!doctype html>\n<html><body><main>Board</main></body></html>\n";

    private static final String INDEX = """
            {"formatVersion":1,"provider":"qits-projects","states":[
             {"name":"a project exists","slug":"a-project-exists","params":{"project":"qits"},"operations":[
              {"operationId":"listProjects","method":"GET","path":"/projects/api/projects","status":200,
               "file":"a-project-exists/listProjects.json"},
              {"operationId":"deleteProject","method":"DELETE","path":"/projects/api/projects/{id}","status":204}]}]}
            """;

    private static final String RECORDED = "{\"projects\":[{\"slug\":\"qits\"}]}";

    private static final String ENTITIES = """
            {"formatVersion":1,"application":"qits-projects","entities":[
             {"entity":"eu.wohlben.qits.entities.domain.Project","table":"project","marking":"domain",
              "dtos":[{"type":"ProjectDto"}],"operations":[{"operationId":"listProjects"}]}]}
            """;

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
        SessionFile store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_ARTIFACTS_URL", platform.url());
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        platform.answer("GET", DOCS, SITES);
        platform.answer("GET", DOCS + "/" + ROUTES,
                versions(ROUTES, "qits-landing-app", "2026.1113.2", "2026.1113.1"));
        platform.answer("GET", DOCS + "/" + PROJECTS,
                versions(PROJECTS, "qits-projects-service", "2100.2.0", "2100.1.0"));
        platform.answer("GET", DOCS + "/" + GITHOST, versions(GITHOST, "qits-githost-service", "2100.1.0"));
        platform.answer("GET", DOCS + "/" + ROUTES + "/-/2026.1113.2/manifest.json", MANIFEST);
        platform.answer("GET", DOCS + "/" + ROUTES + "/-/2026.1113.1/manifest.json",
                MANIFEST.replace("\"DARK\",\"dataMarks\"", "\"LIGHT\",\"dataMarks\""));
        platform.answer("GET", DOCS + "/" + ROUTES + "/-/2026.1113.2/" + FREEZE, 200, HTML,
                "Content-Type", "text/html; charset=utf-8");
        platform.answer("GET", DOCS + "/" + PROJECTS + "/-/2100.2.0/golden-masters/index.json", INDEX);
        platform.answer("GET", DOCS + "/" + PROJECTS + "/-/2100.2.0/golden-masters/a-project-exists/listProjects.json",
                RECORDED);
        platform.answer("GET", DOCS + "/" + PROJECTS + "/-/2100.2.0/entities.json", ENTITIES);
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
        String o = out.toString(StandardCharsets.UTF_8);
        String e = err.toString(StandardCharsets.UTF_8);
        assertThat(o).doesNotContain(FakeIdp.SECRET);
        assertThat(e).doesNotContain(FakeIdp.SECRET);
        return new Result(exit, o, e);
    }

    private boolean anyWrite() {
        synchronized (platform.requests) {
            return platform.requests.stream().anyMatch(r -> !r.method().equals("GET"));
        }
    }

    // --- the address ---

    @Test
    void aSiteKeepsItsSlashAndAtLiteralAndTheRestOfAFilePathIsEncoded() throws Exception {
        DocsStore store = new DocsStore(new PlatformClient(null), "https://registry.qits.example");
        assertThat(store.fileAddress(ROUTES, "2026.1113.2", FREEZE).getRawPath()).isEqualTo(
                "/artifacts/docs/docs/@routes/qits-landing/-/2026.1113.2/"
                        + "src/app/routes/projects/%5Bslug%5D/work/work.page.browser.spec.ts/board-desktop.html");
        assertThat(DocsStore.literal("a b+c*~!'()")).isEqualTo("a%20b%2Bc*~!'()");
    }

    // --- routes ---

    @Test
    void routesFindsTheSiteByTheRepositoryItsNewestVersionNamesAndPrintsTheFreezes() {
        Result r = run("ui-builder", "routes", "--repository", "qits-landing-app");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains(ROUTES).contains("2026.1113.2").contains("SCREENSHOT").contains(FREEZE)
                .contains("projects/:slug/work").contains("DESKTOP").contains("DARK");
        assertThat(platform.requests("GET", DOCS + "/" + ROUTES + "/-/2026.1113.2/manifest.json")).hasSize(1);
        // Only the @routes/ sites' versions are read to find it.
        assertThat(platform.requests("GET", DOCS + "/" + PROJECTS)).isEmpty();
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void routesAsJsonIsTheSiteTheVersionAndTheManifestAsRead() throws Exception {
        Result r = run("ui-builder", "routes", "--repository", "qits-landing-app", "--bundle-version", "2026.1113.1",
                "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode answer = JSON.readTree(r.out());
        assertThat(answer.path("site").asText()).isEqualTo(ROUTES);
        assertThat(answer.path("version").asText()).isEqualTo("2026.1113.1");
        assertThat(answer.path("manifest").path("freezes").path(0).path("colorScheme").asText()).isEqualTo("LIGHT");
        assertThat(answer.path("manifest").has("colorSchemes")).isTrue();
    }

    @Test
    void anUnknownRepositoryIsOneLineNamingIt() {
        Result r = run("ui-builder", "routes", "--repository", "qits-ci-frontend");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err().strip().lines().count()).isEqualTo(1);
        assertThat(r.err()).contains("qits-ci-frontend");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void routesWithoutARepositoryIsAUsageErrorWithNoRequest() {
        Result r = run("ui-builder", "routes");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--repository");
        assertThat(platform.requests).isEmpty();
    }

    // --- freeze ---

    @Test
    void freezePrintsTheScreenshotsEntry() throws Exception {
        Result table = run("ui-builder", "freeze", "--repository", "qits-landing-app", "--screenshot", FREEZE);

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("kind").contains("DATA").contains("connectivity").contains("online")
                .contains("PROVIDER").contains("qits-projects-service").contains("a project exists")
                .contains("listProjects");

        Result json = run("ui-builder", "freeze", "--repository", "qits-landing-app", "--screenshot", FREEZE,
                "-o", "json");
        assertThat(json.exit()).as(json.err()).isZero();
        JsonNode entry = JSON.readTree(json.out());
        assertThat(entry.path("file").asText()).isEqualTo(FREEZE);
        assertThat(entry.path("records").path(0).path("operationId").asText()).isEqualTo("listProjects");
    }

    @Test
    void freezeWithHtmlPrintsTheFrozenHtmlAsItIs() {
        Result r = run("ui-builder", "freeze", "--repository", "qits-landing-app", "--screenshot", FREEZE, "--html");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo(HTML);
    }

    @Test
    void anUnknownScreenshotIsOneLineNamingIt() {
        Result r = run("ui-builder", "freeze", "--repository", "qits-landing-app", "--screenshot", "nope.html",
                "--html");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err().strip().lines().count()).isEqualTo(1);
        assertThat(r.err()).contains("nope.html");
        assertThat(platform.requests("GET", DOCS + "/" + ROUTES + "/-/2026.1113.2/nope.html")).isEmpty();
    }

    // --- golden-masters ---

    @Test
    void goldenMastersPrintsTheIndexOfTheProvidersSite() throws Exception {
        Result table = run("ui-builder", "golden-masters", "--provider", "qits-projects");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains(PROJECTS).contains("2100.2.0").contains("a-project-exists")
                .contains("a project exists");
        // The site is named by the application, so no site's versions are read.
        assertThat(platform.requests("GET", DOCS + "/" + PROJECTS)).isEmpty();

        Result json = run("ui-builder", "golden-masters", "--provider", "qits-projects", "-o", "json");
        assertThat(JSON.readTree(json.out())).isEqualTo(JSON.readTree(INDEX));
    }

    @Test
    void goldenMastersFindsTheProviderByItsRepositoryAndFiltersToTheState() throws Exception {
        Result r = run("ui-builder", "golden-masters", "--provider", "qits-projects-service", "--state",
                "a-project-exists", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode state = JSON.readTree(r.out());
        assertThat(state.path("slug").asText()).isEqualTo("a-project-exists");
        assertThat(state.path("operations")).hasSize(2);

        Result table = run("ui-builder", "golden-masters", "--provider", "qits-projects-service", "--state",
                "a project exists");
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("OPERATION").contains("listProjects").contains("deleteProject")
                .contains("a-project-exists/listProjects.json");
    }

    @Test
    void goldenMastersWithAStateAndAnOperationPrintsTheRecordedResponse() throws Exception {
        Result r = run("ui-builder", "golden-masters", "--provider", "qits-projects", "--state", "a-project-exists",
                "--operation", "listProjects");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(RECORDED));
    }

    @Test
    void anUnknownProviderStateOrOperationIsOneLineNamingIt() {
        Result provider = run("ui-builder", "golden-masters", "--provider", "qits-nothing");
        assertThat(provider.exit()).isEqualTo(1);
        assertThat(provider.err().strip().lines().count()).isEqualTo(1);
        assertThat(provider.err()).contains("qits-nothing");

        Result state = run("ui-builder", "golden-masters", "--provider", "qits-projects", "--state", "no-such-state");
        assertThat(state.exit()).isEqualTo(1);
        assertThat(state.err().strip().lines().count()).isEqualTo(1);
        assertThat(state.err()).contains("no-such-state");

        Result operation = run("ui-builder", "golden-masters", "--provider", "qits-projects", "--state",
                "a-project-exists", "--operation", "getNothing");
        assertThat(operation.exit()).isEqualTo(1);
        assertThat(operation.err().strip().lines().count()).isEqualTo(1);
        assertThat(operation.err()).contains("getNothing");

        Result noFile = run("ui-builder", "golden-masters", "--provider", "qits-projects", "--state",
                "a-project-exists", "--operation", "deleteProject");
        assertThat(noFile.exit()).isEqualTo(1);
        assertThat(noFile.err()).contains("deleteProject").contains("no response file");
    }

    @Test
    void anOperationWithoutAStateIsAUsageError() {
        Result r = run("ui-builder", "golden-masters", "--provider", "qits-projects", "--operation", "listProjects");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--state");
        assertThat(platform.requests).isEmpty();
    }

    // --- entities ---

    @Test
    void entitiesPrintsTheProvidersEntityMap() throws Exception {
        Result table = run("ui-builder", "entities", "--provider", "qits-projects-service");

        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains(PROJECTS).contains("ENTITY").contains("eu.wohlben.qits.entities.domain.Project")
                .contains("project").contains("domain");

        Result json = run("ui-builder", "entities", "--provider", "qits-projects", "-o", "json");
        assertThat(json.exit()).as(json.err()).isZero();
        assertThat(JSON.readTree(json.out())).isEqualTo(JSON.readTree(ENTITIES));
    }

    @Test
    void aVersionWithNoEntityMapIsOneLineNamingIt() {
        Result r = run("ui-builder", "entities", "--provider", "qits-githost");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err().strip().lines().count()).isEqualTo(1);
        assertThat(r.err()).contains(GITHOST).contains("entities.json").contains("404");
    }

    @Test
    void anUnknownProviderOfEntitiesIsOneLineNamingIt() {
        Result r = run("ui-builder", "entities", "--provider", "qits-nothing-service");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err().strip().lines().count()).isEqualTo(1);
        assertThat(r.err()).contains("qits-nothing-service");
    }
}
