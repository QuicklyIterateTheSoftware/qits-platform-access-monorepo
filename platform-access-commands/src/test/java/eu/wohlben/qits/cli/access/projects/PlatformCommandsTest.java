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
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.RequestCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The commands as a person types them, in this process, against a fake platform and a fake idp. */
class PlatformCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String DEMO = "8f1c2d3e-0000-4000-8000-000000000002";
    private static final String CI = "0a0b0c0d-0000-4000-8000-00000000000a";
    private static final String CI_FRONTEND = "0a0b0c0d-0000-4000-8000-00000000000b";
    private static final String DOCS_APP = "0a0b0c0d-0000-4000-8000-00000000000c";
    private static final String REQUESTS = "/projects/api/repositories/" + CI + "/release-requests";
    private static final String REPOSITORIES = "/projects/api/projects/" + QITS + "/repositories";
    private static final String PROJECT_REQUESTS = "/projects/api/projects/" + QITS + "/release-requests";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private SessionFile store;
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
        store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        seed(Duration.ofMinutes(10));

        platform.answer("GET", "/projects/api/projects", """
                {"entries":[
                  {"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}},
                  {"project":{"id":"%s","name":"Demo","slug":"demo","description":null,"dns":null}}]}
                """.formatted(QITS, DEMO));
        platform.answer("GET", "/projects/api/projects/" + QITS + "/repositories", """
                {"entries":[
                  {"repository":{"id":"%s","name":"qits-ci-service","archetype":"QUARKUS_SERVICE","component":"qits-ci","projectId":"%s"},"declared":true},
                  {"repository":{"id":"%s","name":"qits-ci-frontend","archetype":"ANGULAR_FRONTEND","component":"qits-ci","projectId":"%s"},"declared":true}],
                 "wrapper":{"repositoryId":"w"}}
                """.formatted(CI, QITS, CI_FRONTEND, QITS));
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"11111111-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"PENDING","priority":"HIGH",
                   "items":[{"source":"feature/x","label":"Ship the\\nnew log view","workItem":null}],"version":null,"updatedAt":"2026-09-12T09:00:00Z"},
                  {"id":"22222222-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"RELEASED","priority":"MEDIUM",
                   "items":[{"source":"feature/x","label":"Cut, still gating","workItem":null}],"version":"2026.911.1","updatedAt":"2026-09-11T09:00:00Z"},
                  {"id":"33333333-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"REJECTED","priority":"MEDIUM",
                   "items":[{"source":"feature/x","label":"Red build","workItem":null}],"version":null,"updatedAt":"2026-09-12T08:00:00Z"},
                  {"id":"77777777-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"FINALIZED","priority":"MEDIUM",
                   "items":[{"source":"feature/x","label":"Old release","workItem":null}],"version":"2026.910.1","updatedAt":"2026-09-10T09:00:00Z"}]}
                """);
        platform.answer("GET", PROJECT_REQUESTS, """
                {"requests":[
                  {"id":"55555555-2222-3333-4444-555555555555","repoName":"qits-ci-frontend","state":"PENDING",
                   "approvalState":"WAITING","priority":"BLOCKING","items":[{"source":"feature/x","label":"Dark theme","workItem":null}],"version":null,
                   "updatedAt":"2026-09-12T09:30:00Z"},
                  {"id":"11111111-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"PENDING",
                   "approvalState":"NOT_REQUIRED","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"version":null,
                   "updatedAt":"2026-09-12T09:00:00Z"},
                  {"id":"77777777-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"FINALIZED",
                   "approvalState":"NOT_REQUIRED","priority":"MEDIUM","items":[{"source":"feature/x","label":"Old release","workItem":null}],"version":"2026.910.1",
                   "updatedAt":"2026-09-10T09:00:00Z"}]}
                """);
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"feature/logs","ref":"refs/heads/feature/logs","implicit":false,"priority":"HIGH","addedBy":"wohlben"}]}}
                """.formatted(CI));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Session seed(Duration accessLeft) throws Exception {
        Session session = new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(accessLeft),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        store.write(session);
        return session;
    }

    private Result run(String... args) {
        return run(null, args);
    }

    /** As {@link #run}, but calling the platform with {@code credential} rather than the session. */
    private Result run(Credential credential, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext built = new CliContext(Map.copyOf(env), java.io.InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), time, time, TokenClient::new, stop -> { });
        CliContext context = credential == null ? built : built.withCredential(credential);
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
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        allOut.writeBytes(out.toByteArray());
        allErr.writeBytes(err.toByteArray());
        return result;
    }

    // --- projects ---

    @Test
    void projectsListIsATable() {
        Result r = run("projects", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "SLUG  NAME           ID",
                "qits  qits platform  " + QITS,
                "demo  Demo           " + DEMO);
        FakePlatform.Request request = platform.requests.getFirst();
        assertThat(request.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        assertThat(request.accept()).isEqualTo("application/json");
    }

    @Test
    void jsonOutputIsTheServicesAnswer() throws Exception {
        Result r = run("projects", "list", "--output", "json");

        assertThat(r.exit()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("entries").get(1).path("project").path("slug").asText()).isEqualTo("demo");
        assertThat(run("projects", "-o", "json", "list").out()).isEqualTo(r.out());
        assertThat(run("projects", "list", "-o", "yaml").exit()).isEqualTo(2);
    }

    // --- repositories and the project's name ---

    @Test
    void theProjectIsFoundByIdSlugOrName() {
        for (String project : List.of(QITS, "qits", "qits platform")) {
            Result r = run("repositories", "--project", project, "list");
            assertThat(r.exit()).as(project).isZero();
            assertThat(r.out().lines().toList()).containsExactly(
                    "NAME              ARCHETYPE         COMPONENT  ID",
                    "qits-ci-service   QUARKUS_SERVICE   qits-ci    " + CI,
                    "qits-ci-frontend  ANGULAR_FRONTEND  qits-ci    " + CI_FRONTEND);
        }
        assertThat(platform.requests("GET", "/projects/api/projects/" + QITS + "/repositories")).hasSize(3);
    }

    @Test
    void theProjectMayComeAfterTheSubcommand() {
        assertThat(run("repositories", "list", "--project", "qits").out()).contains("qits-ci-service");
        assertThat(run("release-request", "--project", "qits", "list", "--repository", "qits-ci-service").out())
                .contains("PENDING");
        assertThat(run("release-request", "list", "--project", "qits", "--repository", CI).out()).contains("PENDING");
    }

    @Test
    void anUnknownProjectNamesTheOnesThereAre() {
        Result r = run("repositories", "--project", "nope", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("No project has the id, slug or name 'nope'. Projects: qits, demo.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aNameThatFitsTwoProjectsAsksForTheId() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}},
                            {"project":{"id":"%s","name":"qits","slug":"legacy"}}]}
                """.formatted(QITS, DEMO));

        Result r = run("repositories", "--project", "qits", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'qits' names more than one project: qits (id " + QITS + "), legacy (id " + DEMO
                + "). Name it by id.");
    }

    @Test
    void theProjectIsRequired() {
        Result r = run("repositories", "list");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Name the project: --project <id, slug or name>.");
        assertThat(platform.requests).isEmpty();
    }

    // --- creating a repository ---

    /** The door's answer: the row it wrote, and where the wrapper now mounts it. */
    private void answerCreate() {
        platform.answer("POST", REPOSITORIES, """
                {"repository":{"id":"%s","name":"qits-docs-app","backupUrl":null,"mainBranch":"main",
                  "archetype":"APP","component":"qits-docs","projectId":"%s","lastBackup":null},
                 "projectId":"%s","wrapperPath":"components/qits-docs/qits-docs-app"}
                """.formatted(DOCS_APP, QITS, QITS));
    }

    @Test
    void createSendsTheNameAndComponentAndNoArchetypeFieldWithoutTheFlag() throws Exception {
        answerCreate();

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app", "--component", "qits-docs");

        assertThat(r.exit()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", REPOSITORIES).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"name\":\"qits-docs-app\",\"component\":\"qits-docs\"}"));
        assertThat(sent.has("archetype")).as("no --archetype was given, so none is sent").isFalse();
    }

    @Test
    void createWithArchetypeSendsItNormalisedToUpperCase() throws Exception {
        answerCreate();

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app", "--component", "qits-docs",
                "--archetype", "daemon");

        assertThat(r.exit()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", REPOSITORIES).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree(
                "{\"name\":\"qits-docs-app\",\"component\":\"qits-docs\",\"archetype\":\"DAEMON\"}"));
    }

    @Test
    void createPrintsTheArchetypeTheDoorAnsweredAndTheWrapperPath() {
        answerCreate();

        Result r = run("repositories", "create", "qits-docs-app", "--project", "qits", "--component", "qits-docs");

        assertThat(r.exit()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "Repository qits-docs-app",
                "  archetype      APP",
                "  component      qits-docs",
                "  id             " + DOCS_APP,
                "  wrapper entry  components/qits-docs/qits-docs-app");
    }

    @Test
    void createWithoutAComponentSendsNoneAndLetsTheWrapperDecide() throws Exception {
        platform.answer("POST", REPOSITORIES, """
                {"repository":{"id":"%s","name":"qits-docs-app","archetype":"APP","component":null,"projectId":"%s"},
                 "projectId":"%s","wrapperPath":null}
                """.formatted(DOCS_APP, QITS, QITS));

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app");

        assertThat(r.exit()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", REPOSITORIES).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"name\":\"qits-docs-app\"}"));
        assertThat(r.out().lines().toList()).containsExactly(
                "Repository qits-docs-app",
                "  archetype  APP",
                "  component  -",
                "  id         " + DOCS_APP);
    }

    @Test
    void createInJsonPrintsTheAnswerAsItCame() throws Exception {
        answerCreate();

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app", "--component", "qits-docs",
                "-o", "json");

        assertThat(r.exit()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("repository").path("archetype").asText()).isEqualTo("APP");
        assertThat(printed.path("wrapperPath").asText()).isEqualTo("components/qits-docs/qits-docs-app");
    }

    @Test
    void aRefusedCredentialSaysWhichRolesTheDoorTakes() {
        platform.answer("POST", REPOSITORIES, 403, "{\"message\":\"forbidden\"}");

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403")
                .contains("Creating a repository needs qits:admin or qits:agent")
                .contains("has not been released with that role on this door yet");
    }

    @Test
    void anotherRefusalIsLeftAsTheServiceWordedIt() {
        platform.answer("POST", REPOSITORIES, 400, "{\"message\":\"A repository named qits-docs-app exists.\"}");

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("A repository named qits-docs-app exists.");
    }

    @Test
    void creatingNeedsTheProjectAndTheName() {
        Result noProject = run("repositories", "create", "qits-docs-app");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");

        Result noName = run("repositories", "--project", "qits", "create");
        assertThat(noName.exit()).isEqualTo(2);
        assertThat(platform.requests("POST", REPOSITORIES)).isEmpty();
    }

    // --- archiving a repository ---

    @Test
    void archiveSendsNoBodyAndPrintsTheRequestedOutcome() {
        String path = "/projects/api/repositories/" + CI + "/archive";
        platform.answer("POST", path, 202, """
                {"outcome":"requested","archived":true,"branch":"archive/qits-ci-service","commit":"abc123",
                 "releaseRequestId":"66666666-2222-3333-4444-555555555555","qualifiedId":"qits-ci-service-rr-9",
                 "detail":"The edit was committed and a release request was asked for."}
                """);

        Result r = run("repositories", "--project", "qits", "archive", "--repository", "qits-ci-service");

        assertThat(r.exit()).isZero();
        List<FakePlatform.Request> sent = platform.requests("POST", path);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().body()).isEmpty();
        assertThat(r.out().lines().toList()).containsExactly(
                "  outcome          requested",
                "  archived         yes",
                "  branch           archive/qits-ci-service",
                "  release request  qits-ci-service-rr-9",
                "  detail           The edit was committed and a release request was asked for.");
    }

    @Test
    void archiveAlreadyArchivedAnswersUnchangedAndOpensNothing() {
        String path = "/projects/api/repositories/" + CI + "/archive";
        platform.answer("POST", path, 200, """
                {"outcome":"unchanged","archived":true,"detail":"main already says repository.archived: true"}
                """);

        Result r = run("repositories", "--project", "qits", "archive", "--repository", "qits-ci-service");

        assertThat(r.exit()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "  outcome   unchanged",
                "  archived  yes",
                "  detail    main already says repository.archived: true");
    }

    @Test
    void unarchiveSendsToItsOwnDoorAndPrintsJsonAsItCame() throws Exception {
        String path = "/projects/api/repositories/" + CI + "/unarchive";
        platform.answer("POST", path, 202, """
                {"outcome":"requested","archived":false,"branch":"unarchive/qits-ci-service","commit":"def456",
                 "releaseRequestId":"66666666-2222-3333-4444-555555555555","qualifiedId":"qits-ci-service-rr-10",
                 "detail":"d"}
                """);

        Result r = run("repositories", "--project", "qits", "unarchive", "--repository", "qits-ci-service",
                "-o", "json");

        assertThat(r.exit()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("branch").asText()).isEqualTo("unarchive/qits-ci-service");
        assertThat(printed.path("qualifiedId").asText()).isEqualTo("qits-ci-service-rr-10");
        assertThat(platform.requests("POST", path)).hasSize(1);
        assertThat(platform.requests("POST", "/projects/api/repositories/" + CI + "/archive")).isEmpty();
    }

    @Test
    void anArchiveRefusalPrintsTheServiceMessageAndExitsNonZero() {
        String path = "/projects/api/repositories/" + CI + "/archive";
        platform.answer("POST", path, 422, "{\"message\":\"the file cannot be edited safely\"}");

        Result r = run("repositories", "--project", "qits", "archive", "--repository", "qits-ci-service");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 422").contains("the file cannot be edited safely");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void archivingNeedsTheProjectAndTheRepository() {
        Result noProject = run("repositories", "archive", "--repository", "qits-ci-service");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");

        Result noRepository = run("repositories", "--project", "qits", "archive");
        assertThat(noRepository.exit()).isEqualTo(2);
        assertThat(noRepository.err()).contains("Name the repository: --repository <id or name>.");
    }

    // --- resetting a backup twin ---

    private static final String BACKUP_RESET = "/projects/api/repositories/" + CI + "/backup-reset";

    @Test
    void backupResetSendsTheReasonAndPrintsTheDecision() throws Exception {
        platform.answer("POST", BACKUP_RESET, 200, """
                {"repositoryId":"%s","kind":"UNRELATED_RESET",
                 "twinShaBefore":"4cafe88754afd99a5b8c174d94b7bca435ec1f3d",
                 "pushedSha":"2ea701248909a576a8800b07134c97e195e6a33b","decidedBy":"dev",
                 "decidedAt":"2026-01-01T00:00:00Z","reason":"The forge seeded the twin."}
                """.formatted(CI));

        Result r = run("repositories", "--project", "qits", "backup", "reset", "--repository", "qits-ci-service",
                "--reason", "  The forge seeded the twin.  ");

        assertThat(r.exit()).isZero();
        List<FakePlatform.Request> sent = platform.requests("POST", BACKUP_RESET);
        assertThat(sent).hasSize(1);
        assertThat(JSON.readTree(sent.getFirst().body()))
                .isEqualTo(JSON.readTree("{\"reason\":\"The forge seeded the twin.\"}"));
        assertThat(r.out().lines().toList()).containsExactly(
                "  repository  qits-ci-service",
                "  kind        UNRELATED_RESET",
                "  twin main   4cafe88754afd99a5b8c174d94b7bca435ec1f3d -> 2ea701248909a576a8800b07134c97e195e6a33b",
                "  decided by  dev",
                "  reason      The forge seeded the twin.");
    }

    @Test
    void backupResetOfATwinWithNoMainSaysSoAndJsonIsTheAnswerAsItCame() throws Exception {
        platform.answer("POST", BACKUP_RESET, 200, """
                {"repositoryId":"%s","kind":"FAST_FORWARD","twinShaBefore":null,
                 "pushedSha":"2ea701248909a576a8800b07134c97e195e6a33b","decidedBy":"dev",
                 "decidedAt":"2026-01-01T00:00:00Z","reason":"r"}
                """.formatted(CI));

        Result table = run("repositories", "backup", "reset", "--project", "qits", "--repository", CI,
                "--reason", "r");
        assertThat(table.exit()).isZero();
        assertThat(table.out()).contains("  kind        FAST_FORWARD")
                .contains("  twin main   (none) -> 2ea701248909a576a8800b07134c97e195e6a33b");

        Result json = run("repositories", "--project", "qits", "backup", "reset", "--repository", "qits-ci-service",
                "--reason", "r", "-o", "json");
        assertThat(json.exit()).isZero();
        JsonNode printed = JSON.readTree(json.out());
        assertThat(printed.path("kind").asText()).isEqualTo("FAST_FORWARD");
        assertThat(printed.path("twinShaBefore").isNull()).isTrue();
    }

    @Test
    void aBackupResetRefusalPrintsTheServicesSentenceAsItCame() {
        String refusal = "The backup twin's main 1111111111 is ahead of ours (2222222222): it holds work the "
                + "platform does not have, so nothing was pushed.";
        platform.answer("POST", BACKUP_RESET, 409, "{\"message\":\"" + refusal + "\"}");

        Result r = run("repositories", "--project", "qits", "backup", "reset", "--repository", "qits-ci-service",
                "--reason", "r");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 409").contains(refusal);
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a403ToTheBackupResetReachesThePersonInTheServicesWordsAlone() {
        String refusal = "Only a person or this project's delivery desk may reset a backup twin.";
        platform.answer("POST", BACKUP_RESET, 403, "{\"message\":\"" + refusal + "\"}");

        Result r = run("repositories", "--project", "qits", "backup", "reset", "--repository", "qits-ci-service",
                "--reason", "r");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(refusal).doesNotContain("reads but does not write");
    }

    @Test
    void backupResetNeedsTheProjectTheRepositoryAndAReason() {
        Result noProject = run("repositories", "backup", "reset", "--repository", "qits-ci-service", "--reason", "r");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");

        Result noRepository = run("repositories", "--project", "qits", "backup", "reset", "--reason", "r");
        assertThat(noRepository.exit()).isEqualTo(2);
        assertThat(noRepository.err()).contains("Name the repository: --repository <id or name>.");

        Result noReason = run("repositories", "--project", "qits", "backup", "reset", "--repository",
                "qits-ci-service", "--reason", "  ");
        assertThat(noReason.exit()).isEqualTo(2);
        assertThat(noReason.err()).contains("Say why the twin's main may be replaced: --reason <text>.");
        assertThat(platform.requests("POST", BACKUP_RESET)).isEmpty();
    }

    // --- release requests ---

    @Test
    void theListShowsOpenRequestsOnly() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("ID        STATE     FREEZE  PRIORITY  ITEMS")
                .contains("11111111  PENDING   -       HIGH      Ship the new log view")
                .contains("22222222  RELEASED").contains("Cut, still gating")
                .contains("33333333  REJECTED").doesNotContain("FINALIZED").doesNotContain("Old release");
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isNull();
    }

    @Test
    void jsonOutputOfTheListLeavesTheFinalizedOutToo() throws Exception {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list", "-o", "json");

        assertThat(JSON.readTree(r.out()).path("requests")).extracting(n -> n.path("state").asText())
                .containsExactly("PENDING", "RELEASED", "REJECTED");
    }

    @Test
    void aStateIsPassedThroughAndNothingIsDropped() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list", "--state", "all");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("FINALIZED").contains("2026.910.1");
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
    }

    @Test
    void anUnknownRepositorySaysWhereToLook() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-cd", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Project qits has no repository with the id or name 'qits-cd'.")
                .contains("`qits repositories --project qits list`");
        assertThat(run("release-request", "--project", "qits", "create", "--branch", "b").err())
                .contains("Name the repository: --repository <id or name>.");
    }

    @Test
    void withoutARepositoryTheListShowsTheWholeProjectAsTheServiceAnswers() {
        Result r = run("release-request", "--project", "qits", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out())
                .contains("ID        REPOSITORY        STATE      FREEZE  APPROVAL      PRIORITY  ITEMS")
                .contains("55555555  qits-ci-frontend  PENDING    -       WAITING       BLOCKING  Dark theme")
                .contains("11111111  qits-ci-service   PENDING")
                // The landing app's default: the last finalized stay, nothing is dropped.
                .contains("77777777  qits-ci-service   FINALIZED").contains("2026.910.1");
        assertThat(platform.requests("GET", PROJECT_REQUESTS).getFirst().query()).isNull();
        assertThat(platform.requests("GET", REQUESTS)).isEmpty();
        assertThat(platform.requests("GET", REPOSITORIES)).isEmpty();
    }

    @Test
    void theProjectListPassesTheStateAndCutsAtTheLimitInBothForms() throws Exception {
        Result r = run("release-request", "list", "--project", "qits", "--state", "all", "--limit", "2");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("55555555").contains("11111111").doesNotContain("77777777");
        assertThat(platform.requests("GET", PROJECT_REQUESTS).getFirst().query()).isEqualTo("state=all");

        Result json = run("release-request", "list", "--project", "qits", "--limit", "1", "-o", "json");
        assertThat(JSON.readTree(json.out()).path("requests")).extracting(n -> n.path("repoName").asText())
                .containsExactly("qits-ci-frontend");
    }

    @Test
    void theRepositoryListCutsAtTheLimitToo() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list", "--limit", "1");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("11111111").doesNotContain("22222222");
    }

    @Test
    void anEmptyProjectListNamesTheProjectAndALimitBelowOneIsRefused() {
        platform.answer("GET", PROJECT_REQUESTS, "{\"requests\":[]}");

        assertThat(run("release-request", "--project", "qits", "list").out())
                .contains("No open release requests for project qits.");
        Result refused = run("release-request", "--project", "qits", "list", "--limit", "0");
        assertThat(refused.exit()).isEqualTo(2);
        assertThat(refused.err()).contains("--limit must be 1 or more.");
        assertThat(run("release-request", "list").err()).contains("--project");
    }

    @Test
    void createSendsTheBranchAndPriorityOnlyAndPrintsTheAnswer() throws Exception {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "--priority", "high");

        assertThat(r.exit()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", REQUESTS).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"branch\":\"feature/logs\",\"priority\":\"HIGH\"}"));
        assertThat(r.out()).startsWith("Release request 44444444-2222-3333-4444-555555555555\n")
                .contains("  state          PENDING")
                .contains("  participants   wohlben")
                .contains("  approval       NOT_REQUIRED")
                .contains("  version        -")
                .contains("Items:\n  Ship the log view\n")
                .contains("Sources:")
                .contains("BRANCH  feature/logs  refs/heads/feature/logs  named  HIGH      wohlben")
                .doesNotContain("summary").doesNotContain("requester");
    }

    @Test
    void createRefusesASummary() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "--summary", "Ship the log view");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--summary");
        assertThat(platform.requests("POST", REQUESTS)).isEmpty();
    }

    @Test
    void showNamesEachItemsWorkItemAndEveryParticipant() {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","qualifiedId":"qits-ci-service-rr-4",
                  "repoName":"qits-ci-service","state":"PENDING","priority":"HIGH",
                  "summary":"ignored","requester":"ignored-too",
                  "participants":["agent-7","wohlben"],
                  "items":[{"source":"ticket/qits-9","label":"qits-9 Search the log","workItem":{"qualifiedId":"qits-9","title":"Search the log"}},
                           {"source":"external/fix","label":"external/fix","workItem":null}],
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"ticket/qits-9","ref":"refs/heads/ticket/qits-9","implicit":false,"priority":"HIGH","addedBy":"wohlben"},
                             {"kind":"BRANCH","name":"external/fix","ref":"refs/heads/external/fix","implicit":false,"priority":"HIGH","addedBy":"agent-7"}]}}
                """);

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "external/fix");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("  participants   agent-7, wohlben")
                .contains("Items:\n  qits-9 Search the log\n  external/fix\n")
                .doesNotContain("ignored");
    }

    @Test
    void anOlderPlatformsRequestShowsItsBranchesForItems() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"11111111-2222-3333-4444-555555555555","state":"PENDING","priority":"HIGH",
                   "summary":"never read","version":null,"updatedAt":"2026-09-12T09:00:00Z",
                   "sources":[{"kind":"BRANCH","name":"main"},{"kind":"BRANCH","name":"feature/a"},{"kind":"BRANCH","name":"feature/b"}]},
                  {"id":"22222222-2222-3333-4444-555555555555","state":"PENDING","priority":"HIGH",
                   "items":[],"version":null,"updatedAt":"2026-09-12T09:00:00Z",
                   "sources":[{"kind":"BRANCH","name":"main"}]},
                  {"id":"33333333-2222-3333-4444-555555555555","state":"PENDING","priority":"HIGH",
                   "items":[{"source":"ticket/qits-9","label":"qits-9 Search the log"},
                            {"source":"ticket/qits-10","label":"qits-10 Keep the log view open across every reload"}],
                   "version":null,"updatedAt":"2026-09-12T09:00:00Z","sources":[{"kind":"BRANCH","name":"main"}]}]}
                """);
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"PENDING",
                  "summary":"never read","requester":"never read",
                  "sources":[{"kind":"BRANCH","name":"feature/a","ref":"refs/heads/feature/a","implicit":false}]}}
                """);

        Result list = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list");
        assertThat(list.out()).contains("11111111  PENDING  -       HIGH      feature/a; feature/b ")
                .contains("22222222  PENDING  -       HIGH      main ")
                .contains("33333333  PENDING  -       HIGH      qits-9 Search the log; qits-10 Keep the log view open acros…")
                .doesNotContain("never read");

        Result shown = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/a");
        assertThat(shown.out()).contains("  participants   -").doesNotContain("Items:").doesNotContain("never read");
    }

    @Test
    void printsTheGatesAndWhatSupersededTheRequestWhenTheServiceSendsThem() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"OBSOLETE","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":"99999999-2222-3333-4444-555555555555",
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "gates":[{"kind":"PUBLISH","state":"FAILED"},
                           {"kind":"APPROVAL","state":"PASSED","detail":"configured by manual-review"}],
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("  state          OBSOLETE")
                .contains("  superseded by  99999999")
                .contains("Gates:")
                .contains("KIND      STATE   REASON")
                .contains("PUBLISH   FAILED  -")
                .contains("APPROVAL  PASSED  configured by manual-review");
    }

    @Test
    void leavesTheGatesReasonBlankWhenAnOlderServiceOmitsIt() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "gates":[{"kind":"APPROVAL","state":"PASSED"}],
                  "sources":[]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Gates:")
                .contains("KIND      STATE   REASON")
                .contains("APPROVAL  PASSED  -");
    }

    @Test
    void printsTheQualityGatesWithAnIndentedCheckPerThingTheGateIsWaitingFor() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "qualityGates":[{"kind":"upstream-in-flight","label":"Upstream releases","position":"pre-run-qa",
                    "state":"PENDING","detail":"Waiting for upstream releases: qits-ci-frontend-rr-12",
                    "checks":[{"name":"qits-ci-frontend-rr-12","state":"PENDING","detail":"QUEUED",
                      "runId":"5eed5eed-0000-4000-8000-00000000000a"}],"runId":null,"link":null}],
                  "sources":[]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Quality gates:")
                .contains("KIND")
                .contains("POSITION")
                .contains("STATE")
                .contains("DETAIL")
                .contains("upstream-in-flight")
                .contains("pre-run-qa")
                .contains("Waiting for upstream releases: qits-ci-frontend-rr-12")
                .contains("qits-ci-frontend-rr-12")
                .doesNotContain("Gates:")
                .containsSubsequence("upstream-in-flight", "pre-run-qa",
                        "Waiting for upstream releases: qits-ci-frontend-rr-12", "qits-ci-frontend-rr-12");
    }

    @Test
    void printsEachGatesChecksDirectlyUnderThatGateRatherThanAfterEveryGate() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "qualityGates":[{"kind":"automations","label":"Automations","position":"pre-run-qa",
                    "state":"PENDING","detail":"Waiting for automations",
                    "checks":[{"name":"Dependency bump","state":"PENDING","detail":"QUEUED","runId":null},
                      {"name":"Golden recordings (maven)","state":"PENDING","detail":"QUEUED","runId":null}],
                    "runId":null,"link":null},
                   {"kind":"manual-review","label":"Deployment","position":"post-release",
                    "state":"PASSED","detail":null,"checks":[],"runId":null,"link":null}],
                  "sources":[]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs");

        assertThat(r.exit()).isZero();
        assertThat(r.out())
                .containsSubsequence("automations", "Dependency bump", "Golden recordings (maven)",
                        "manual-review")
                .doesNotContain("Gates:");
    }

    @Test
    void fallsBackToTheDeprecatedGatesTableWhenTheAnswerHasNoQualityGates() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "gates":[{"kind":"PUBLISH","state":"FAILED","detail":"the tag's release pipeline"}],
                  "sources":[]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Gates:")
                .contains("KIND     STATE   REASON")
                .contains("PUBLISH  FAILED  the tag's release pipeline")
                .doesNotContain("Quality gates:");
    }

    @Test
    void createWithoutAPriorityLeavesItToThePlatform() throws Exception {
        Result r = run("release-request", "create", "--project", "qits", "--repository", CI,
                "--branch", "main", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", REQUESTS).getFirst().body()).has("priority")).isFalse();
        assertThat(JSON.readTree(r.out()).path("request").path("id").asText()).startsWith("44444444");
        assertThat(run("release-request", "create", "--project", "qits", "--repository", CI).exit()).isEqualTo(2);
    }

    // --- the soft feature freeze (epic qits-1580) ---

    private static final String QUEUED_ID = "66666666-2222-3333-4444-555555555555";

    @Test
    void createPrintsWhereTheBranchWentFirstThenTheQueuedRequestBehindTheFrozenOne() {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"%s","qualifiedId":"qits-ci-service-rr-14","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"MEDIUM","items":[{"source":"feature/x","label":"Ship the log search","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,
                  "detail":"Queued behind qits-ci-service-rr-13: it folds once that request has released",
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "queuedBehind":"%s","queuedBehindQualifiedId":"qits-ci-service-rr-13",
                  "queuedNext":null,"queuedNextQualifiedId":null,
                  "featureFreeze":{"features":1,"threshold":3,"frozen":false,"source":"PROJECT"},
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM",
                              "addedBy":null,"feature":false,"explicitJoin":false},
                             {"kind":"BRANCH","name":"ticket/qits-9","ref":"refs/heads/ticket/qits-9","implicit":false,
                              "priority":"MEDIUM","addedBy":"wohlben","feature":true,"explicitJoin":false}]},
                 "placement":{"outcome":"CREATED","queued":true,
                  "detail":"ticket/qits-9 is queued in qits-ci-service-rr-14, behind the frozen qits-ci-service-rr-13"}}
                """.formatted(QUEUED_ID, CI, PENDING_ID));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "ticket/qits-9");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("ticket/qits-9 is queued in qits-ci-service-rr-14, behind the frozen "
                + "qits-ci-service-rr-13\nRelease request qits-ci-service-rr-14\n");
        assertThat(r.out().lines().toList()).contains(
                "  freeze         1 of 3 counted branches (project)",
                "  queued         behind qits-ci-service-rr-13",
                "  KIND    NAME           REF                       HOW    FREEZE   PRIORITY  ADDED BY",
                "  BRANCH  main           refs/heads/main           named  -        MEDIUM    -",
                "  BRANCH  ticket/qits-9  refs/heads/ticket/qits-9  named  counted  MEDIUM    wohlben");
        assertThat(r.out()).doesNotContain("  next ");
        assertThat(r.out()).containsSubsequence("  state ", "  freeze ", "  queued ", "  superseded by ");
    }

    @Test
    void joinPrintsWhereTheBranchWentAndMarksTheExplicitJoinOnTheFrozenRequest() {
        platform.answer("POST", sourcesOf(PENDING_ID), """
                {"request":{"id":"%s","qualifiedId":"qits-ci-service-rr-13","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "queuedBehind":null,"queuedBehindQualifiedId":null,
                  "queuedNext":"%s","queuedNextQualifiedId":"qits-ci-service-rr-14",
                  "featureFreeze":{"features":3,"threshold":3,"frozen":true,"source":"PLATFORM"},
                  "sources":[{"kind":"BRANCH","name":"epic/qits-7","ref":"refs/heads/epic/qits-7","implicit":false,
                              "priority":"HIGH","addedBy":"wohlben","feature":true,"explicitJoin":false},
                             {"kind":"BRANCH","name":"ticket/qits-10","ref":"refs/heads/ticket/qits-10","implicit":false,
                              "priority":"MEDIUM","addedBy":"wohlben","feature":true,"explicitJoin":true}]},
                 "placement":{"outcome":"JOINED","queued":false,
                  "detail":"ticket/qits-10 joined qits-ci-service-rr-13 as asked, past its freeze (3 of 3 counted branches)"}}
                """.formatted(PENDING_ID, CI, QUEUED_ID));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "1111", "--branch", "ticket/qits-10");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("ticket/qits-10 joined qits-ci-service-rr-13 as asked, past its freeze "
                + "(3 of 3 counted branches)\nRelease request qits-ci-service-rr-13\n");
        assertThat(r.out().lines().toList()).contains(
                "  freeze         3 of 3 counted branches, frozen (platform default)",
                "  next           qits-ci-service-rr-14",
                "  BRANCH  ticket/qits-10  refs/heads/ticket/qits-10  named  counted, explicit join  MEDIUM    wohlben");
        assertThat(r.out()).doesNotContain("  queued ");
    }

    @Test
    void aFreezeTurnedOffSaysWhichLevelTurnedItOffAndJsonIsTheServicesAnswer() throws Exception {
        String answer = """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "featureFreeze":{"features":5,"threshold":0,"frozen":false,"source":"REPOSITORY"},
                  "sources":[]},
                 "placement":{"outcome":"PRESENT","queued":false,"detail":"feature/logs is already on 44444444"}}
                """.formatted(CI);
        platform.answer("POST", REQUESTS, answer);

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs");
        assertThat(r.out()).startsWith("feature/logs is already on 44444444\n")
                .contains("  freeze         off (repository)\n");

        Result json = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "-o", "json");
        assertThat(JSON.readTree(json.out())).isEqualTo(JSON.readTree(answer));
    }

    @Test
    void theListsFreezeColumnShowsFrozenCountedOffAndQueued() {
        String requests = """
                {"requests":[
                  {"id":"%1$s","qualifiedId":"qits-ci-service-rr-13","repoName":"qits-ci-service","state":"PENDING",
                   "approvalState":"NOT_REQUIRED","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],
                   "updatedAt":"2026-09-12T10:00:00Z","queuedNextQualifiedId":"qits-ci-service-rr-14",
                   "featureFreeze":{"features":3,"threshold":3,"frozen":true,"source":"PLATFORM"}},
                  {"id":"%2$s","qualifiedId":"qits-ci-service-rr-14","repoName":"qits-ci-service","state":"PENDING",
                   "approvalState":"NOT_REQUIRED","priority":"MEDIUM","items":[{"source":"feature/x","label":"Ship the log search","workItem":null}],
                   "updatedAt":"2026-09-12T10:00:00Z","queuedBehind":"%1$s","queuedBehindQualifiedId":"qits-ci-service-rr-13",
                   "featureFreeze":{"features":1,"threshold":3,"frozen":false,"source":"PLATFORM"}},
                  {"id":"%3$s","qualifiedId":"qits-ci-frontend-rr-2","repoName":"qits-ci-frontend","state":"READY",
                   "approvalState":"NOT_REQUIRED","priority":"MEDIUM","items":[{"source":"feature/x","label":"Dark theme","workItem":null}],
                   "updatedAt":"2026-09-12T10:00:00Z",
                   "featureFreeze":{"features":1,"threshold":3,"frozen":false,"source":"PROJECT"}},
                  {"id":"%4$s","qualifiedId":"qits-ci-frontend-rr-3","repoName":"qits-ci-frontend","state":"PENDING",
                   "approvalState":"NOT_REQUIRED","priority":"MEDIUM","items":[{"source":"feature/x","label":"Light theme","workItem":null}],
                   "updatedAt":"2026-09-12T10:00:00Z",
                   "featureFreeze":{"features":4,"threshold":0,"frozen":false,"source":"REPOSITORY"}}]}
                """.formatted(PENDING_ID, QUEUED_ID, RELEASED_ID, "77777777-2222-3333-4444-555555555555");
        platform.answer("GET", PROJECT_REQUESTS, requests);
        platform.answer("GET", REQUESTS, requests);

        Result project = run("release-request", "--project", "qits", "list");
        assertThat(project.exit()).as(project.err()).isZero();
        assertThat(project.out().lines().toList()).anySatisfy(l -> assertThat(l)
                        .startsWith("ID                     REPOSITORY        STATE    FREEZE      APPROVAL"))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("qits-ci-service-rr-13  qits-ci-service   PENDING  3/3 frozen  NOT_REQUIRED"))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("qits-ci-service-rr-14  qits-ci-service   PENDING  queued      NOT_REQUIRED"))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("qits-ci-frontend-rr-2  qits-ci-frontend  READY    1/3         NOT_REQUIRED"))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("qits-ci-frontend-rr-3  qits-ci-frontend  PENDING  off         NOT_REQUIRED"));

        Result repository = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list");
        assertThat(repository.exit()).as(repository.err()).isZero();
        assertThat(repository.out().lines().toList()).anySatisfy(l -> assertThat(l)
                        .startsWith("ID                     STATE    FREEZE      PRIORITY  ITEMS"))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("qits-ci-service-rr-13  PENDING  3/3 frozen  HIGH      Ship the log view"))
                .anySatisfy(l -> assertThat(l)
                        .startsWith("qits-ci-service-rr-14  PENDING  queued      MEDIUM    Ship the log search"));
    }

    // --- joining a branch to a request ---

    private static final String PENDING_ID = "11111111-2222-3333-4444-555555555555";
    private static final String RELEASED_ID = "22222222-2222-3333-4444-555555555555";

    private static String sourcesOf(String id) {
        return REQUESTS + "/" + id + "/sources";
    }

    /** The pending request, answered with a second branch on it. */
    private void answerJoin() {
        platform.answer("POST", sourcesOf(PENDING_ID), """
                {"request":{"id":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGHER","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"feature/search","ref":"refs/heads/feature/search","implicit":false,"priority":"HIGHER","addedBy":"wohlben"}]}}
                """.formatted(PENDING_ID, CI));
    }

    @Test
    void joinFindsTheRequestByTheStartOfItsIdAndSendsTheBranchAndPriority() throws Exception {
        answerJoin();

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "1111", "--branch", "feature/search", "--priority", "higher");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        JsonNode sent = JSON.readTree(platform.requests("POST", sourcesOf(PENDING_ID)).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"branch\":\"feature/search\",\"priority\":\"HIGHER\"}"));
        assertThat(r.out()).startsWith("Release request " + PENDING_ID + "\n")
                .contains("  state          PENDING")
                .contains("  priority       HIGHER")
                .contains("  merged sha     abc123")
                .contains("Sources:");
        assertThat(r.out().lines().toList()).contains(
                "  KIND    NAME            REF                        HOW    PRIORITY  ADDED BY",
                "  BRANCH  main            refs/heads/main            named  MEDIUM    -",
                "  BRANCH  feature/search  refs/heads/feature/search  named  HIGHER    wohlben");
    }

    @Test
    void joinWithoutAPriorityLeavesTheStoredOneAloneAndTakesItsOptionsAfterTheSubcommand() throws Exception {
        answerJoin();

        Result r = run("release-request", "join", "--request", PENDING_ID, "--branch", "feature/search",
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", sourcesOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"branch\":\"feature/search\"}"));
        assertThat(JSON.readTree(r.out()).path("request").path("sources")).extracting(s -> s.path("name").asText())
                .containsExactly("main", "feature/search");
        assertThat(run("release-request", "join", "--project", "qits", "--repository", CI, "--branch", "x").exit())
                .isEqualTo(2);
        assertThat(run("release-request", "join", "--project", "qits", "--repository", CI, "--request", "1111").exit())
                .isEqualTo(2);
    }

    @Test
    void aRequestStartThatFitsNoneSaysWhereToLook() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "9999", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no release request '9999'. Give its id "
                        + "(qits-ci-service-rr-<n>), its uuid or the start of the uuid.")
                .contains("`qits release-request --project qits --repository qits-ci-service list --state all` shows them.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aRequestStartThatFitsTwoAsksForMore() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"abcd0001-0000-4000-8000-000000000000","state":"PENDING"},
                  {"id":"abcd0002-0000-4000-8000-000000000000","state":"READY"},
                  {"id":"ef000003-0000-4000-8000-000000000000","state":"PENDING"}]}
                """);

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "ABCD", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'ABCD' is the start of more than one release request of qits-ci-service: "
                + "abcd0001-0000-4000-8000-000000000000 (PENDING), abcd0002-0000-4000-8000-000000000000 (READY). "
                + "Give its id or more of the uuid.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aReleasedRequestTakesNoMoreBranches() {
        platform.answer("POST", sourcesOf(RELEASED_ID), 409,
                "{\"message\":\"Release request " + RELEASED_ID + " is already RELEASED; join qits-ci-service-rr-13\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "2222", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request " + RELEASED_ID + " is RELEASED and takes no more branches (HTTP 409). "
                + "Join the next request the platform names, or use `qits release-request create`. ")
                .contains("join qits-ci-service-rr-13");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a404SaysThereIsNoSuchRequestOrBranch() {
        platform.answer("POST", sourcesOf(PENDING_ID), 404,
                "{\"message\":\"Release request not found: " + PENDING_ID + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "11111111", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such request or branch. POST " + platform.url() + sourcesOf(PENDING_ID)
                + " answered HTTP 404: Release request not found: " + PENDING_ID);
    }

    // --- logical ids (qits-1158) ---

    /** The repository's requests as a qits-projects with logical ids answers them. */
    private void answerQualified() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"%s","qualifiedId":"qits-ci-service-rr-12","repoName":"qits-ci-service","state":"PENDING",
                   "priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"version":null,"updatedAt":"2026-09-12T09:00:00Z"},
                  {"id":"%s","qualifiedId":"qits-ci-service-rr-1","repoName":"qits-ci-service","state":"RELEASED",
                   "priority":"MEDIUM","items":[{"source":"feature/x","label":"Cut, still gating","workItem":null}],"version":"2026.911.1","updatedAt":"2026-09-11T09:00:00Z"}]}
                """.formatted(PENDING_ID, RELEASED_ID));
    }

    @Test
    void theListShowsTheLogicalId() {
        answerQualified();

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).startsWith("ID                     STATE     FREEZE  PRIORITY  ITEMS              VERSION     UPDATED\n"
                + "qits-ci-service-rr-12  PENDING   -       HIGH      Ship the log view  -           2026-09-12 ");
        assertThat(r.out()).doesNotContain("11111111");
    }

    @Test
    void joinTakesTheLogicalIdInAnyCaseAndSendsTheUuid() throws Exception {
        answerQualified();
        platform.answer("POST", sourcesOf(PENDING_ID), """
                {"request":{"id":"%s","qualifiedId":"qits-ci-service-rr-12","repoId":"%s","repoName":"qits-ci-service",
                  "state":"OBSOLETE","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":"99999999-2222-3333-4444-555555555555","supersededByQualifiedId":"qits-ci-service-rr-13",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z","sources":[]}}
                """.formatted(PENDING_ID, CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "QITS-CI-SERVICE-RR-12", "--branch", "feature/search");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", sourcesOf(PENDING_ID))).hasSize(1);
        assertThat(r.out()).startsWith("Release request qits-ci-service-rr-12\n")
                .contains("  uuid           " + PENDING_ID)
                .contains("  superseded by  qits-ci-service-rr-13");
    }

    @Test
    void aLogicalIdWinsOverAUuidStartAndOneThatFitsNoneIsRefused() {
        answerQualified();
        platform.answer("POST", withdrawOf(RELEASED_ID), 409, "{\"message\":\"already RELEASED\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "qits-ci-service-rr-1");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request qits-ci-service-rr-1 is RELEASED already and cannot be withdrawn");
        assertThat(run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "qits-ci-service-rr-2").err())
                .contains("Repository qits-ci-service has no release request 'qits-ci-service-rr-2'.");
        assertThat(platform.requests("POST", withdrawOf(RELEASED_ID))).hasSize(1);
    }

    // --- withdrawing a request ---

    private static String withdrawOf(String id) {
        return REQUESTS + "/" + id + "/withdraw";
    }

    /** The pending request, answered as withdrawn with the given detail. */
    private void answerWithdraw(String detail) {
        platform.answer("POST", withdrawOf(PENDING_ID), """
                {"request":{"id":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"WITHDRAWN","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,"detail":"%s",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(PENDING_ID, CI, detail));
    }

    @Test
    void withdrawFindsTheRequestByTheStartOfItsIdAndSendsTheReason() throws Exception {
        answerWithdraw("The log view moves to qits-observability");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "1111", "--reason", "  The log view moves to qits-observability ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(JSON.readTree(platform.requests("POST", withdrawOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"reason\":\"The log view moves to qits-observability\"}"));
        assertThat(r.out()).startsWith("Release request " + PENDING_ID + "\n")
                .contains("  state          WITHDRAWN")
                .contains("  detail         The log view moves to qits-observability")
                .contains("Sources:");
    }

    @Test
    void withdrawWithoutAReasonSendsAnEmptyBodyAndTakesItsOptionsAfterTheSubcommand() throws Exception {
        answerWithdraw("Withdrawn by wohlben");

        Result r = run("release-request", "withdraw", "--request", PENDING_ID,
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", withdrawOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{}"));
        assertThat(JSON.readTree(r.out()).path("request").path("state").asText()).isEqualTo("WITHDRAWN");
        assertThat(JSON.readTree(r.out()).path("request").path("detail").asText()).isEqualTo("Withdrawn by wohlben");
        assertThat(run("release-request", "withdraw", "--project", "qits", "--repository", CI).exit()).isEqualTo(2);
    }

    @Test
    void withdrawOfAStartThatFitsNoneSendsNothing() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "9999");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no release request '9999'.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aReleasedRequestCannotBeWithdrawn() {
        platform.answer("POST", withdrawOf(RELEASED_ID), 409,
                "{\"message\":\"Release request " + RELEASED_ID + " is already RELEASED\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "2222");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request " + RELEASED_ID + " is RELEASED already and cannot be "
                + "withdrawn (HTTP 409).");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a404OnWithdrawSaysThereIsNoSuchRequest() {
        platform.answer("POST", withdrawOf(PENDING_ID), 404,
                "{\"message\":\"Release request not found: " + PENDING_ID + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "11111111");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such request. POST " + platform.url() + withdrawOf(PENDING_ID)
                + " answered HTTP 404: Release request not found: " + PENDING_ID);
    }

    // --- moving one source to the queued request (qits-1665) ---

    private static final String MOVED_TO_ID = "33333333-2222-3333-4444-555555555555";

    private static String moveOf(String id) {
        return REQUESTS + "/" + id + "/sources/move";
    }

    /** The pending request gives up feature/search to the request queued after it. */
    private void answerMove() {
        String move = """
                {"branch":"feature/search","fromQualifiedId":"qits-ci-service-rr-12",
                 "toQualifiedId":"qits-ci-service-rr-13","movedBy":"wohlben",
                 "reason":"It conflicts with feature/x.","movedAt":"2026-09-12T10:00:00Z"}""";
        platform.answer("POST", moveOf(PENDING_ID), """
                {"from":{"id":"%s","qualifiedId":"qits-ci-service-rr-12","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","queuedNext":"%s","queuedNextQualifiedId":"qits-ci-service-rr-13",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"feature/x","ref":"refs/heads/feature/x","implicit":false,"priority":"HIGH","addedBy":"wohlben"}],
                  "sourceMoves":[%s]},
                 "to":{"id":"%s","qualifiedId":"qits-ci-service-rr-13","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGHER","queuedBehind":"%s","queuedBehindQualifiedId":"qits-ci-service-rr-12",
                  "detail":"Queued behind qits-ci-service-rr-12: it folds once that request has released",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"feature/search","ref":"refs/heads/feature/search","implicit":false,"priority":"HIGHER","addedBy":"wohlben"}],
                  "sourceMoves":[%s]}}
                """.formatted(PENDING_ID, CI, MOVED_TO_ID, move, MOVED_TO_ID, CI, PENDING_ID, move));
    }

    @Test
    void moveSendsTheBranchAndReasonAndPrintsWhereItWentAndTheRequestThatHoldsIt() throws Exception {
        answerMove();

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "move",
                "--request", "1111", "--branch", " feature/search ", "--reason", "It conflicts with feature/x.");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(JSON.readTree(platform.requests("POST", moveOf(PENDING_ID)).getFirst().body())).isEqualTo(
                JSON.readTree("{\"branch\":\"feature/search\",\"reason\":\"It conflicts with feature/x.\"}"));
        assertThat(r.out()).startsWith("feature/search moved from qits-ci-service-rr-12 to qits-ci-service-rr-13\n"
                        + "Release request qits-ci-service-rr-13\n")
                .contains("  queued         behind qits-ci-service-rr-12")
                .contains("Moves:");
        assertThat(r.out().lines().toList()).contains(
                "  BRANCH  feature/search  refs/heads/feature/search  named  HIGHER    wohlben")
                .anySatisfy(l -> assertThat(l).startsWith(
                        "  BRANCH          FROM                   TO                     BY       AT ").endsWith("REASON"))
                .anySatisfy(l -> assertThat(l).startsWith(
                        "  feature/search  qits-ci-service-rr-12  qits-ci-service-rr-13  wohlben  2026-09-12 ")
                        .endsWith("It conflicts with feature/x."));
    }

    @Test
    void moveWithJsonPrintsBothRequestsAndNeedsABranchAndAReason() throws Exception {
        answerMove();

        Result r = run("release-request", "move", "--request", PENDING_ID, "--branch", "feature/search",
                "--reason", "Not ready.", "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode out = JSON.readTree(r.out());
        assertThat(out.path("from").path("qualifiedId").asText()).isEqualTo("qits-ci-service-rr-12");
        assertThat(out.path("to").path("qualifiedId").asText()).isEqualTo("qits-ci-service-rr-13");
        assertThat(run("release-request", "move", "--project", "qits", "--repository", CI, "--request", "1111",
                "--branch", "feature/search").exit()).isEqualTo(2);
        assertThat(run("release-request", "move", "--project", "qits", "--repository", CI, "--request", "1111",
                "--reason", "Not ready.").exit()).isEqualTo(2);
        Result blank = run("release-request", "move", "--project", "qits", "--repository", CI, "--request", "1111",
                "--branch", "feature/search", "--reason", "  ");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("--request, --branch and --reason must not be empty.");
        assertThat(platform.requests("POST", moveOf(PENDING_ID))).hasSize(1);
    }

    @Test
    void aRefusedMoveSaysWhy() {
        platform.answer("POST", moveOf(PENDING_ID), 409, "{\"message\":\"Branch feature/search is the only branch "
                + "of release request qits-ci-service-rr-12 besides main: moving it would leave nothing to release. "
                + "Withdraw the request instead (qits release-request withdraw)\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "move",
                "--request", "1111", "--branch", "feature/search", "--reason", "Not ready.");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request " + PENDING_ID + " cannot give up feature/search (HTTP 409). ")
                .contains("Withdraw the request instead");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aForeignBranchIsAnotherAgentsToMove() {
        platform.answer("POST", moveOf(PENDING_ID), 403,
                "{\"message\":\"An agent may move only a branch in its git_refs, its own work's\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "move",
                "--request", "1111", "--branch", "feature/search", "--reason", "Not ready.");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("You may not move feature/search (HTTP 403): an agent moves only its own branch. "
                + "Ask the branch's own agent or the delivery desk.");
    }

    @Test
    void aMoveOfABranchNotOnTheRequestIsA404AndABlankReasonA400() {
        platform.answer("POST", moveOf(PENDING_ID), 404,
                "{\"message\":\"Release request " + PENDING_ID + " has no named source feature/nope\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "move",
                "--request", "1111", "--branch", "feature/nope", "--reason", "Not ready.");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such request, or feature/nope is not a source of " + PENDING_ID
                + " (HTTP 404). POST " + platform.url() + moveOf(PENDING_ID) + " answered HTTP 404");

        platform.answer("POST", moveOf(PENDING_ID), 400, "{\"message\":\"A move says why\"}");
        Result blank = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "move",
                "--request", "1111", "--branch", "feature/search", "--reason", "x");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("A move says why: give --reason a sentence (HTTP 400).");
    }

    // --- overriding an upstream gate (epic qits-897, qits-916) ---

    private static final String UPSTREAM_ID = "88888888-2222-3333-4444-555555555555";

    private static String overrideOf(String id) {
        return REQUESTS + "/" + id + "/upstream-overrides";
    }

    /** The pending request, answered with the upstream-in-flight gate overridden. */
    private void answerOverride() {
        platform.answer("POST", overrideOf(PENDING_ID), """
                {"request":{"id":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,
                  "detail":"Overridden: qits-ci-service-rr-7 (wohlben)",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(PENDING_ID, CI));
    }

    @Test
    void overrideFindsTheRequestByTheStartOfItsIdAndSendsTheUpstreamAndNote() throws Exception {
        answerOverride();

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "override",
                "--request", "1111", "--upstream", "  qits-ci-service-rr-7  ", "--note", "  Safe to go ahead. ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(JSON.readTree(platform.requests("POST", overrideOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"upstreamRequestId\":\"qits-ci-service-rr-7\",\"note\":\"Safe to go ahead.\"}"));
        assertThat(r.out()).startsWith("Release request " + PENDING_ID + "\n")
                .contains("  state          PENDING")
                .contains("  detail         Overridden: qits-ci-service-rr-7 (wohlben)")
                .contains("Sources:");
    }

    @Test
    void overrideWithoutANoteSendsNoNoteAndTakesItsOptionsAfterTheSubcommand() throws Exception {
        answerOverride();

        Result r = run("release-request", "override", "--request", PENDING_ID, "--upstream", UPSTREAM_ID,
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", overrideOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"upstreamRequestId\":\"" + UPSTREAM_ID + "\"}"));
        assertThat(JSON.readTree(r.out()).path("request").path("state").asText()).isEqualTo("PENDING");
        assertThat(run("release-request", "override", "--project", "qits", "--repository", CI,
                "--upstream", UPSTREAM_ID).exit()).isEqualTo(2);
        assertThat(run("release-request", "override", "--project", "qits", "--repository", CI,
                "--request", PENDING_ID).exit()).isEqualTo(2);
    }

    @Test
    void overrideOfAStartThatFitsNoneSendsNothing() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "override",
                "--request", "9999", "--upstream", UPSTREAM_ID);

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no release request '9999'.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aRequestThatIsNotPendingAVerdictCannotBeOverridden() {
        String sentence = "Release request " + PENDING_ID + " is not pending a verdict of its own, so there is "
                + "nothing to override.";
        platform.answer("POST", overrideOf(PENDING_ID), 409, "{\"message\":\"" + sentence + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "override",
                "--request", "1111", "--upstream", UPSTREAM_ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(sentence);
        assertThat(r.out()).isEmpty();
    }

    @Test
    void anOverrideThatIsMootIsTheServices409() {
        String moot = "The override is moot: qits-ci-service-rr-7 is not building.";
        platform.answer("POST", overrideOf(PENDING_ID), 409, "{\"message\":\"" + moot + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "override",
                "--request", "1111", "--upstream", "qits-ci-service-rr-7");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(moot);
    }

    @Test
    void a404OnOverrideReachesThePersonInTheServicesWords() {
        platform.answer("POST", overrideOf(PENDING_ID), 404,
                "{\"message\":\"No such upstream request: " + UPSTREAM_ID + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "override",
                "--request", "11111111", "--upstream", UPSTREAM_ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("answered HTTP 404: No such upstream request: " + UPSTREAM_ID);
    }

    /**
     * Only a person's own CLI sign-in counts for this decision (qits-897): an agent gets 403 in
     * the door's own words, exactly as it reaches a person.
     */
    @Test
    void a403ToAnAgentOrAServiceClientReachesThePersonInTheServicesWords() {
        String personOnly = "Only a person's own CLI sign-in counts for this decision. Asserted identity headers, "
                + "service clients, agents and workspace credentials are refused.";
        platform.answer("POST", overrideOf(PENDING_ID), 403, "{\"message\":\"" + personOnly + "\"}");

        Result r = run(new RequestCredential(agentToken()), "release-request", "--project", "qits",
                "--repository", "qits-ci-service", "override", "--request", "1111", "--upstream", UPSTREAM_ID);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(personOnly);
        assertThat(r.out()).isEmpty();
    }

    // --- approving and declining a fold ---

    private static final String DECIDED_ID = "66666666-2222-3333-4444-555555555555";
    private static final String DECIDED = "qits-ci-service-rr-221";
    private static final String FOLD = "aaa1111111111111111111111111111111111111";

    private static String decisionOf(String verb) {
        return REQUESTS + "/" + DECIDED_ID + "/" + verb;
    }

    /** The project's door answers the request with its repository and the fold it is on (or none). */
    private void answerProjectRequest(String ref, String mergedSha) {
        platform.answer("GET", PROJECT_REQUESTS + "/" + ref, """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","approvalState":"WAITING","mergedSha":%s,"items":[{"source":"feature/x","label":"Bump the estate","workItem":null}]}}
                """.formatted(DECIDED_ID, DECIDED, CI, mergedSha == null ? "null" : "\"" + mergedSha + "\""));
    }

    /** The decision door's answer: the request as the decision left it. */
    private static String decided(String state, String approval, String sha) {
        return """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"%s","priority":"MEDIUM","items":[{"source":"feature/x","label":"Bump the estate","workItem":null}],"participants":["maintenance"],
                  "approvalState":"%s","mergedSha":"%s","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z","sources":[]}}
                """.formatted(DECIDED_ID, DECIDED, CI, state, approval, sha);
    }

    @Test
    void approveWithoutAShaFindsTheRequestInTheProjectAndApprovesTheFoldItRead() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        platform.answer("POST", decisionOf("approve"), decided("PENDING", "APPROVED", FOLD));

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PROJECT_REQUESTS + "/" + DECIDED)).hasSize(1);
        assertThat(platform.requests("GET", REPOSITORIES)).as("no repository is looked up").isEmpty();
        assertThat(JSON.readTree(platform.requests("POST", decisionOf("approve")).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD + "\"}"));
        assertThat(r.out()).startsWith("Approved " + DECIDED + " at " + FOLD + ".\nRelease request " + DECIDED + "\n")
                .contains("  approval       APPROVED")
                .contains("  merged sha     " + FOLD);
    }

    @Test
    void approveWithAShaSendsThatShaAsGiven() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        String judged = "bbb2222222222222222222222222222222222222";
        platform.answer("POST", decisionOf("approve"), decided("PENDING", "APPROVED", judged));

        Result r = run("release-request", "approve", "--project", "qits", "--request", DECIDED, "--sha", " " + judged + " ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", decisionOf("approve")).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mergedSha\":\"" + judged + "\"}"));
        assertThat(r.out()).startsWith("Approved " + DECIDED + " at " + judged + ".\n");
    }

    @Test
    void aStaleFoldReachesThePersonInTheServicesWords() {
        answerProjectRequest(DECIDED, FOLD);
        String stale = "Release request x-rr-1 is on bbb now, not aaa; re-read it and approve the fold you are looking at.";
        platform.answer("POST", decisionOf("approve"), 409, "{\"message\":\"" + stale + "\"}");

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED, "--sha", "aaa");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("POST " + platform.url() + decisionOf("approve") + " answered HTTP 409: " + stale);
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aRefusalOfWhoMayDecideReachesThePersonInTheServicesWords() {
        answerProjectRequest(DECIDED, FOLD);
        String personOnly = "Only a person may make this decision, verified here: a browser session or a person's "
                + "qits CLI, holding qits:admin. Asserted identity headers, service clients, agents, CI and "
                + "workstation tokens do not count.";
        platform.answer("POST", decisionOf("decline"), 403, "{\"message\":\"" + personOnly + "\"}");

        Result r = run("release-request", "--project", "qits", "decline", "--request", DECIDED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(personOnly);
        assertThat(r.out()).isEmpty();
    }

    @Test
    void declineSendsItsNoteAndABlankOrMissingNoteIsLeftOut() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        platform.answer("POST", decisionOf("decline"), decided("REJECTED", "DECLINED", FOLD));

        Result noted = run("release-request", "--project", "qits", "decline", "--request", DECIDED,
                "--note", "  The README promises an approval policy nobody has signed off yet. ");
        Result blank = run("release-request", "--project", "qits", "decline", "--request", DECIDED, "--note", "  ");
        Result none = run("release-request", "--project", "qits", "decline", "--request", DECIDED);

        assertThat(List.of(noted, blank, none)).allSatisfy(r -> assertThat(r.exit()).as(r.err()).isZero());
        List<FakePlatform.Request> sent = platform.requests("POST", decisionOf("decline"));
        assertThat(sent).hasSize(3);
        assertThat(JSON.readTree(sent.get(0).body())).isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD
                + "\",\"note\":\"The README promises an approval policy nobody has signed off yet.\"}"));
        assertThat(JSON.readTree(sent.get(1).body())).isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD + "\"}"));
        assertThat(JSON.readTree(sent.get(2).body())).isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD + "\"}"));
        assertThat(none.out()).startsWith("Declined " + DECIDED + " at " + FOLD + ".\n")
                .contains("  state          REJECTED");
    }

    @Test
    void aRequestWithNoFoldIsRefusedHereAndNothingIsSent() {
        answerProjectRequest(DECIDED, null);

        Result approve = run("release-request", "--project", "qits", "approve", "--request", DECIDED);
        Result decline = run("release-request", "--project", "qits", "decline", "--request", DECIDED);

        assertThat(approve.exit()).isNotZero();
        assertThat(approve.err()).contains("Release request " + DECIDED
                + " has not been folded yet, so there is no fold to approve.");
        assertThat(decline.exit()).isNotZero();
        assertThat(decline.err()).contains("there is no fold to decline.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void jsonPrintsTheServicesAnswerUnchanged() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        String answer = decided("PENDING", "APPROVED", FOLD);
        platform.answer("POST", decisionOf("approve"), answer);

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(answer));
        assertThat(r.out()).doesNotContain("Approved ");
    }

    // --- approval requirements (epic qits-1038) ---

    /** The project's door, with one open {@code approvalRequirements} entry on the request it reads. */
    private void answerProjectRequestAwaitingApproval() {
        platform.answer("GET", PROJECT_REQUESTS + "/" + DECIDED, """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","approvalState":"WAITING","mergedSha":"%s","items":[{"source":"feature/x","label":"Bump the estate","workItem":null}],
                  "approvalRequirements":[
                    {"id":"manual-review","reason":"configured by manual-review","admits":["person"],
                     "excludesAuthors":false,"detail":null,"satisfied":false,"satisfiedBy":null}]}}
                """.formatted(DECIDED_ID, DECIDED, CI, FOLD));
    }

    /** The decision door's answer, with that same requirement now satisfied. */
    private static String approvedWithRequirement() {
        return """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"MEDIUM","items":[{"source":"feature/x","label":"Bump the estate","workItem":null}],"participants":["maintenance"],
                  "approvalState":"APPROVED","mergedSha":"%s","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z","sources":[],
                  "approvalRequirements":[
                    {"id":"manual-review","reason":"configured by manual-review","admits":["person"],
                     "excludesAuthors":false,"detail":null,"satisfied":true,
                     "satisfiedBy":{"approver":"dev","roles":["person"],"at":"2026-09-12T10:00:00Z"}}]}}
                """.formatted(DECIDED_ID, DECIDED, CI, FOLD);
    }

    @Test
    void approveWithRequirementsPrintsThemBeforeAndAfterTheDecision() throws Exception {
        answerProjectRequestAwaitingApproval();
        platform.answer("POST", decisionOf("approve"), approvedWithRequirement());

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).containsSubsequence("Requirements before approve:",
                "manual-review", "configured by manual-review", "Approved " + DECIDED,
                "Requirements after approve:", "manual-review", "configured by manual-review");
        // Before the decision nothing has satisfied it yet.
        assertThat(r.out()).containsPattern(
                "manual-review\\s+configured by manual-review\\s+person\\s+no\\s+no\\s+-");
        // After the decision the answered request reports it satisfied, and by whom.
        assertThat(r.out()).containsPattern(
                "manual-review\\s+configured by manual-review\\s+person\\s+no\\s+yes\\s+dev \\(person\\) at");
    }

    @Test
    void jsonWithRequirementsPrintsTheServicesAnswerUnchanged() throws Exception {
        answerProjectRequestAwaitingApproval();
        String answer = approvedWithRequirement();
        platform.answer("POST", decisionOf("approve"), answer);

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(answer));
        assertThat(r.out()).doesNotContain("Requirements before").doesNotContain("Requirements after");
    }

    @Test
    void anOlderServiceWithNoApprovalRequirementsFieldPrintsNothingExtra() throws Exception {
        // answerProjectRequest/decided (above) carry no approvalRequirements at all: an older
        // provider's shape. Table output stays exactly what it was before this epic.
        answerProjectRequest(DECIDED, FOLD);
        platform.answer("POST", decisionOf("approve"), decided("PENDING", "APPROVED", FOLD));

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).doesNotContain("Requirements");
    }

    @Test
    void a403NamingTheOpenRequirementsReachesThePersonVerbatim() {
        answerProjectRequest(DECIDED, FOLD);
        String refusal = "You may not approve release request " + DECIDED + " at " + FOLD.substring(0, 10)
                + ": its open approval requirements are manual-review (configured by manual-review) admits person."
                + " You hold no approval role.";
        platform.answer("POST", decisionOf("approve"), 403, "{\"message\":\"" + refusal + "\"}");

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(refusal);
        assertThat(r.out()).isEmpty();
    }

    /** An unsigned JWT naming {@code qits:agent}: enough for {@link RequestCredential} to read it. */
    private static String agentToken() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"workspace-7f3a\",\"aud\":\"qits-platform\",\"groups\":[\"qits:agent\"]}";
        return base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    /**
     * The defect this replaced (qits-1038, verified live on qits-1044): an agent approving a
     * request whose open requirement admits only {@code person} used to see "this credential is
     * qits:agent, which reads but does not write" above the door's own sentence — wrong, because
     * qits-projects admits {@code qits:agent} at this door and decides eligibility itself; a 403
     * here means "you hold no admitted role", not "agents only read". The door's sentence now
     * reaches the agent exactly as it reached the person in the test above, with no hint pasted
     * over it.
     */
    @Test
    void a403ToAnAgentsTokenPrintsTheDoorsSentenceWithoutTheReadOnlyHint() {
        answerProjectRequest(DECIDED, FOLD);
        String refusal = "You may not approve release request " + DECIDED + " at " + FOLD.substring(0, 10)
                + ": its open approval requirements are manual-review (configured by manual-review) admits person."
                + " You hold agent.";
        platform.answer("POST", decisionOf("approve"), 403, "{\"message\":\"" + refusal + "\"}");

        Result r = run(new RequestCredential(agentToken()), "release-request", "--project", "qits", "approve",
                "--request", DECIDED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(refusal)
                .doesNotContain("reads but does not write");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aRequestTheProjectDoesNotHoldIsTheServices404() {
        platform.answer("GET", PROJECT_REQUESTS + "/qits-ci-service-rr-9", 404,
                "{\"message\":\"Project " + QITS + " has no release request qits-ci-service-rr-9\"}");

        Result r = run("release-request", "--project", "qits", "approve", "--request", "qits-ci-service-rr-9");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("answered HTTP 404: Project " + QITS + " has no release request qits-ci-service-rr-9");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    // --- regenerating a release output ---

    private static final String REJECTED_ID = "33333333-2222-3333-4444-555555555555";

    private static String runsOf(String id, String kind) {
        return REQUESTS + "/" + id + "/automations/" + kind + "/runs";
    }

    /** The repository with one open request, the REJECTED one; the finalized one does not count. */
    private void answerOneOpenRequest() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"%s","repoName":"qits-ci-service","state":"REJECTED","priority":"MEDIUM",
                   "items":[{"source":"feature/x","label":"Red build","workItem":null}],"version":null,"updatedAt":"2026-09-12T08:00:00Z"},
                  {"id":"77777777-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"FINALIZED","priority":"MEDIUM",
                   "items":[{"source":"feature/x","label":"Old release","workItem":null}],"version":"2026.910.1","updatedAt":"2026-09-10T09:00:00Z"}]}
                """.formatted(REJECTED_ID));
    }

    @Test
    void regenerateWithoutARequestTakesTheOneOpenRequestEvenWhenItIsRejected() {
        answerOneOpenRequest();
        platform.answer("POST", runsOf(REJECTED_ID, "entity-diagram"), 202, "{\"id\":\"bump-1\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "regenerate",
                "--kind", "entity-diagram");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isNull();
        assertThat(platform.requests("POST", runsOf(REJECTED_ID, "entity-diagram")).getFirst().body()).isEmpty();
        assertThat(r.out().lines().toList()).containsExactly(
                "Asked for entity-diagram on release request " + REJECTED_ID + ": run bump-1",
                "The request folds again when the output is committed to maintenance/automations/entity-diagram/"
                        + REJECTED_ID + "; QA then runs again.",
                "Follow the run with `qits maintenance bump bump-1`.");
    }

    @Test
    void regenerateFindsANamedRequestByTheStartOfItsIdAndPrintsJson() throws Exception {
        platform.answer("POST", runsOf(PENDING_ID, "screenshot-baselines"), 202, "{\"id\":\"bump-2\"}");

        Result r = run("release-request", "regenerate", "--request", "1111", "--kind", "Screenshot-Baselines",
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree("{\"id\":\"bump-2\"}"));
    }

    @Test
    void regenerateWithoutARequestRefusesWhenMoreThanOneIsOpen() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "regenerate",
                "--kind", "estate-pins");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has more than one open release request: "
                + "11111111 (PENDING), 33333333 (REJECTED). Name one with --request.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
        assertThat(run("release-request", "regenerate", "--project", "qits", "--repository", CI).exit()).isEqualTo(2);
    }

    @Test
    void regenerateWithoutARequestRefusesWhenNoneIsOpen() {
        platform.answer("GET", REQUESTS, "{\"requests\":[]}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "regenerate",
                "--kind", "estate-pins");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no open release request. Name one with --request.");
    }

    @Test
    void regenerateTurnsTheDoorsRefusalsIntoSentences() {
        answerOneOpenRequest();
        platform.answer("POST", runsOf(REJECTED_ID, "estate-pins"), 409,
                "{\"message\":\"An estate-pins run is already active for this request\"}");
        platform.answer("POST", runsOf(REJECTED_ID, "nonsense"), 404, "{\"message\":\"Unknown kind: nonsense\"}");
        platform.answer("POST", runsOf(REJECTED_ID, "entity-diagram"), 503,
                "{\"message\":\"qits-maintenance is not configured\"}");
        platform.answer("POST", runsOf(REJECTED_ID, "dependency-bump"), 504,
                "{\"message\":\"qits-maintenance did not answer in time\"}");

        Result busy = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "estate-pins");
        Result unknown = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "nonsense");
        Result down = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "entity-diagram");
        Result timedOut = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "dependency-bump");

        assertThat(busy.exit()).isEqualTo(1);
        assertThat(busy.err()).contains("The platform will not run estate-pins on release request " + REJECTED_ID
                        + " now (HTTP 409)")
                .contains("`qits maintenance automations --request 33333333`")
                .contains("An estate-pins run is already active for this request");
        assertThat(unknown.exit()).isEqualTo(1);
        assertThat(unknown.err()).contains("The platform knows no kind 'nonsense' or no release request "
                        + REJECTED_ID + " (HTTP 404). The kinds are entity-diagram, entity-exposure, screenshot-baselines, "
                        + "cli-tapes, dependency-bump, estate-pins, golden-recordings-maven and golden-recordings-npm.")
                .contains("Unknown kind: nonsense");
        assertThat(down.exit()).isEqualTo(1);
        assertThat(down.err()).contains("qits-maintenance cannot be reached or is not set up, so nothing ran "
                + "(HTTP 503). Try again later.");
        assertThat(timedOut.exit()).isEqualTo(1);
        assertThat(timedOut.err()).contains("is unknown (HTTP 504)")
                .contains("`qits maintenance automations --request 33333333`")
                .doesNotContain("nothing ran");
        assertThat(busy.out() + unknown.out() + down.out() + timedOut.out()).isEmpty();
    }

    // --- rerunning a pipeline phase ---

    private static String rerunOf(String id, String phase) {
        return REQUESTS + "/" + id + "/pipeline/" + phase + "/rerun";
    }

    /** The pending request, answered unchanged: the rerun is asynchronous, so nothing here reflects it yet. */
    private void answerRerun(String phase) {
        platform.answer("POST", rerunOf(PENDING_ID, phase), """
                {"request":{"id":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"REJECTED","priority":"HIGH","items":[{"source":"feature/x","label":"Ship the log view","workItem":null}],"participants":["wohlben"],
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(PENDING_ID, CI));
    }

    @Test
    void rerunFindsTheRequestByTheStartOfItsIdAndSendsNoBody() throws Exception {
        answerRerun("QA");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "rerun",
                "--request", "1111", "--phase", "QA");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(platform.requests("POST", rerunOf(PENDING_ID, "QA")).getFirst().body()).isEmpty();
        assertThat(r.out()).startsWith("Release request " + PENDING_ID + "\n")
                .contains("  state          REJECTED")
                .contains("Sources:");
    }

    @Test
    void rerunUppercasesThePhaseAndTakesItsOptionsAfterTheSubcommand() throws Exception {
        answerRerun("DEPLOY");

        Result r = run("release-request", "rerun", "--request", PENDING_ID, "--phase", "deploy",
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", rerunOf(PENDING_ID, "DEPLOY"))).hasSize(1);
        assertThat(JSON.readTree(r.out()).path("request").path("id").asText()).isEqualTo(PENDING_ID);
        assertThat(run("release-request", "rerun", "--project", "qits", "--repository", CI, "--phase", "QA").exit())
                .isEqualTo(2);
        assertThat(run("release-request", "rerun", "--project", "qits", "--repository", CI, "--request", PENDING_ID)
                .exit()).isEqualTo(2);
    }

    @Test
    void rerunOfAStartThatFitsNoneSendsNothing() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "rerun",
                "--request", "9999", "--phase", "QA");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no release request '9999'.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void a400OnRerunSaysThePhaseMustBeOneOfTheThree() {
        platform.answer("POST", rerunOf(PENDING_ID, "NONSENSE"), 400, "{\"message\":\"Unknown phase: NONSENSE\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "rerun",
                "--request", "1111", "--phase", "nonsense");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The phase must be QA, PUBLISH or DEPLOY (HTTP 400).")
                .contains("Unknown phase: NONSENSE");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a404OnRerunSaysThereIsNoSuchRequest() {
        platform.answer("POST", rerunOf(PENDING_ID, "QA"), 404,
                "{\"message\":\"Release request not found: " + PENDING_ID + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "rerun",
                "--request", "1111", "--phase", "QA");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such release request " + PENDING_ID + " (HTTP 404). ")
                .contains("Release request not found: " + PENDING_ID);
    }

    /**
     * 409 is qits-ci's or the service's own sentence (the ticket's own words): the command prints it
     * verbatim, with no sentence of its own layered on top, the same way it prints a 503.
     */
    @Test
    void a409OnRerunPrintsTheDoorsSentenceVerbatim() {
        platform.answer("POST", rerunOf(PENDING_ID, "QA"), 409,
                "{\"message\":\"The QA run for this request is still active\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "rerun",
                "--request", "1111", "--phase", "QA");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The QA run for this request is still active");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a503OnRerunSaysQitsCiCannotBeReached() {
        platform.answer("POST", rerunOf(PENDING_ID, "PUBLISH"), 503, "{\"message\":\"qits-ci is not configured\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "rerun",
                "--request", "1111", "--phase", "PUBLISH");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("qits-ci is not configured");
        assertThat(r.out()).isEmpty();
    }

    // --- dispatching work, and the delivery desk ---

    private static final String DISPATCH = "/projects/api/work/qits-100/dispatch";

    private void answerDispatch() {
        platform.answer("POST", DISPATCH, """
                {"dispatch":{"entityId":"e1","archetype":"TICKET","phase":"implement","mode":"FLOW","agentId":"agent-41",
                  "workspaceRowId":41,"repositoryId":"r1","branch":"ticket/qits-100","fresh":true,
                  "agentLaunch":"SCHEDULED","assignee":"ws-agent-41"}}
                """);
    }

    @Test
    void dispatchSendsTheModeAndTheRuleAndPrintsWhatItStarted() throws Exception {
        answerDispatch();

        Result r = run("work", "--entity", "qits-100", "dispatch", "--mode", "flow", "--rule", "backlog-order");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DISPATCH).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mode\":\"FLOW\",\"rule\":\"backlog-order\"}"));
        assertThat(r.out().lines().toList()).containsExactly(
                "ENTITY    MODE  PHASE      BRANCH           ASSIGNEE",
                "qits-100  FLOW  implement  ticket/qits-100  ws-agent-41");
    }

    @Test
    void dispatchWithoutARuleLeavesItOutAndJsonIsTheAnswerUnchanged() throws Exception {
        answerDispatch();

        Result r = run("work", "dispatch", "--entity", "qits-100", "--mode", "PHASE", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DISPATCH).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mode\":\"PHASE\"}"));
        assertThat(JSON.readTree(r.out()).path("dispatch").path("assignee").asText()).isEqualTo("ws-agent-41");
    }

    @Test
    void dispatchNeedsAModeItKnowsAndSendsNothingWithout() {
        Result none = run("work", "--entity", "qits-100", "dispatch");
        Result wrong = run("work", "--entity", "qits-100", "dispatch", "--mode", "all");

        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Name the mode: --mode <FLOW or PHASE>.");
        assertThat(wrong.exit()).isEqualTo(2);
        assertThat(wrong.err()).contains("--mode is FLOW or PHASE, not all.");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aDispatchRefusalReachesStderrInTheServicesWords() {
        String noPhase = "qits-100 is DONE, and DONE starts no phase.";
        platform.answer("POST", DISPATCH, 409, "{\"message\":\"" + noPhase + "\"}");
        Result conflict = run("work", "--entity", "qits-100", "dispatch", "--mode", "FLOW");
        String otherProject = "The delivery desk of demo may not dispatch qits-100 of another project.";
        platform.answer("POST", DISPATCH, 403, "{\"message\":\"" + otherProject + "\"}");
        Result forbidden = run("work", "--entity", "qits-100", "dispatch", "--mode", "FLOW", "--rule", "start-refined");

        assertThat(conflict.exit()).isEqualTo(1);
        assertThat(conflict.err()).contains("POST " + platform.url() + DISPATCH + " answered HTTP 409: " + noPhase);
        assertThat(forbidden.exit()).isEqualTo(1);
        assertThat(forbidden.err()).contains(otherProject);
        assertThat(conflict.out() + forbidden.out()).isEmpty();
    }

    @Test
    void deliveryPrintsTheProjectsSettings() throws Exception {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","delivery":{"enabled":true,
                  "maxRunning":4,"startRefined":true,"approves":["estate-pins","screenshots"]}}}]}
                """.formatted(QITS));

        Result r = run("projects", "delivery", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "Project qits: delivery desk",
                "SETTING        VALUE",
                "enabled        true",
                "max_running    4",
                "start_refined  true",
                "approves       estate-pins, screenshots");
        assertThat(JSON.readTree(run("projects", "-o", "json", "delivery", "--project", QITS).out()))
                .isEqualTo(JSON.readTree("{\"enabled\":true,\"maxRunning\":4,\"startRefined\":true,"
                        + "\"approves\":[\"estate-pins\",\"screenshots\"]}"));
    }

    @Test
    void deliveryPrintsTheDefaultsForWhatTheServiceDoesNotSend() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","delivery":{"enabled":true,
                  "approves":[]}}},
                  {"project":{"id":"%s","name":"Demo","slug":"demo"}}]}
                """.formatted(QITS, DEMO));

        Result partly = run("projects", "delivery", "--project", "qits");
        Result older = run("projects", "delivery", "--project", "Demo");

        assertThat(partly.out().lines().toList()).containsExactly(
                "Project qits: delivery desk",
                "SETTING        VALUE",
                "enabled        true",
                "max_running    2 (default)",
                "start_refined  false (default)",
                "approves       none");
        assertThat(older.exit()).isZero();
        assertThat(older.out().lines().toList()).containsExactly(
                "Project demo: delivery desk",
                "SETTING        VALUE",
                "enabled        false (default)",
                "max_running    2 (default)",
                "start_refined  false (default)",
                "approves       none (default)");
        assertThat(run("projects", "delivery").err()).contains("Name the project: --project <id, slug or name>.");
    }

    // --- the front desk (qits-1572) ---

    private static final String DESK_PROMPT = "/projects/api/projects/" + QITS + "/front-desk/prompt";
    private static final String DESK_ANSWER = "/projects/api/projects/" + QITS + "/front-desk/last-answer";

    private void answerProjects() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}}]}
                """.formatted(QITS));
    }

    @Test
    void deskPromptJoinsTheWordsAndSaysWhetherItWasDeliveredOrQueued() throws Exception {
        answerProjects();
        platform.answer("POST", DESK_PROMPT, """
                {"commandId":"c-7","state":"IDLE","delivered":true,"queued":false,"position":null}
                """);

        Result delivered = run("projects", "desk", "prompt", "--project", "qits", "What", "is", "in", "flight?");

        assertThat(delivered.exit()).as(delivered.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DESK_PROMPT).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"text\":\"What is in flight?\"}"));
        assertThat(delivered.out().lines().toList())
                .containsExactly("Project qits: delivered to the front desk (command c-7).");

        platform.answer("POST", DESK_PROMPT, """
                {"commandId":"c-8","state":"BUSY","delivered":false,"queued":true,"position":2}
                """);
        Result queued = run("projects", "desk", "prompt", "--project", "qits", "tick now");

        assertThat(queued.exit()).as(queued.err()).isZero();
        assertThat(queued.out().lines().toList()).containsExactly("Project qits: queued at position 2 (command c-8). "
                + "The desk is BUSY; the turn is typed once it is idle.");
        assertThat(JSON.readTree(run("projects", "-o", "json", "desk", "prompt", "--project", "qits", "x").out()))
                .isEqualTo(JSON.readTree("{\"commandId\":\"c-8\",\"state\":\"BUSY\",\"delivered\":false,"
                        + "\"queued\":true,\"position\":2}"));
    }

    @Test
    void deskPromptNeedsAProjectAndTextAndSurfacesTheServicesReason() {
        assertThat(run("projects", "desk", "prompt", "hello").err())
                .contains("Name the project: --project <id, slug or name>.");
        Result noText = run("projects", "desk", "prompt", "--project", "qits");
        assertThat(noText.exit()).isEqualTo(2);
        assertThat(noText.err()).contains("Name the turn's text");
        assertThat(platform.requests).isEmpty();

        answerProjects();
        platform.answer("POST", DESK_PROMPT, 429, "{\"reason\":\"the front desk queue is full\"}");
        Result full = run("projects", "desk", "prompt", "--project", "qits", "one", "more");

        assertThat(full.exit()).isEqualTo(1);
        assertThat(full.err()).contains("POST " + platform.url() + DESK_PROMPT
                + " answered HTTP 429: the front desk queue is full");
        assertThat(full.out()).isEmpty();
    }

    @Test
    void deskAnswerPrintsTheLastAnswerCleanedAndSaysWhenThereIsNone() throws Exception {
        answerProjects();
        platform.answer("GET", DESK_ANSWER, """
                {"commandId":"c-7","sessionId":"s-1","state":"IDLE","text":"Two items run.\\n\\u001b[2Jqits-100 waits.",
                 "at":"2026-10-24T08:00:00Z"}
                """);

        Result r = run("projects", "desk", "answer", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "Project qits: the front desk's last answer",
                "FIELD      VALUE",
                "at         2026-10-24T08:00:00Z",
                "state      IDLE",
                "commandId  c-7",
                "sessionId  s-1",
                "",
                "Two items run.",
                "qits-100 waits.");
        String json = run("projects", "-o", "json", "desk", "answer", "--project", "qits").out();
        assertThat(json).doesNotContain("\u001b").contains("\\u001B");
        assertThat(JSON.readTree(json).path("commandId").asText()).isEqualTo("c-7");

        platform.answer("GET", DESK_ANSWER, """
                {"commandId":null,"sessionId":null,"state":"IDLE","text":null,"at":null}
                """);
        Result none = run("projects", "desk", "answer", "--project", "qits");
        assertThat(none.exit()).isZero();
        assertThat(none.out().lines().toList()).containsExactly("Project qits: the front desk has not answered yet.");

        platform.answer("GET", DESK_ANSWER, 503, "{\"reason\":\"no projects daemon is connected\"}");
        Result down = run("projects", "desk", "answer", "--project", "qits");
        assertThat(down.exit()).isEqualTo(1);
        assertThat(down.err()).contains("answered HTTP 503: no projects daemon is connected");
    }

    // --- the desk triggers (qits-1613) ---

    private static final String DESK_TRIGGERS = "/projects/api/projects/" + QITS + "/desk-triggers";

    @Test
    void deskTriggersPrintsOneRowPerMessageNewestFirst() throws Exception {
        answerProjects();
        platform.answer("GET", DESK_TRIGGERS, """
                {"messages":[
                  {"id":"m-2","state":"DELIVERED","openedAt":"2026-10-25T08:00:00Z","closesAt":"2026-10-25T08:05:00Z",
                   "closedAt":"2026-10-25T08:05:00Z","acceptedAt":"2026-10-25T08:05:10Z","attempts":1,
                   "lastStatus":200,"lastDetail":null,"commandId":"c-9","queuePosition":null,
                   "message":"Two items moved.",
                   "events":[{"source":"work-verified","itemRef":"qits-100","itemId":"i-1","label":"Thing",
                              "cause":"tok-x","eventId":"e-1","occurredAt":"2026-10-25T08:00:01Z"},
                             {"source":"work-verified","itemRef":"qits-101","itemId":"i-3","label":"Other",
                              "cause":"tok-x","eventId":"e-3","occurredAt":"2026-10-25T08:00:01Z"},
                             {"source":"approval-needed","itemRef":"qits-service-rr-1","itemId":null,"label":null,
                              "cause":"fold abc","eventId":"e-2","occurredAt":"2026-10-25T08:00:02Z"}]},
                  {"id":"m-1","state":"OPEN","openedAt":"2026-10-25T07:00:00Z","closesAt":"2026-10-25T07:05:00Z",
                   "closedAt":null,"acceptedAt":null,"attempts":0,"lastStatus":null,"lastDetail":null,
                   "commandId":null,"queuePosition":null,"message":null,"events":[]}
                ]}
                """);

        Result r = run("projects", "desk", "triggers", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", DESK_TRIGGERS)).hasSize(1);
        assertThat(platform.requests("GET", DESK_TRIGGERS).getFirst().query()).isNullOrEmpty();
        assertThat(r.out().lines().toList()).contains("Project qits: desk trigger messages")
                .anySatisfy(line -> assertThat(line).contains("2026-10-25T08:00:00Z").contains("DELIVERED")
                        .contains("work-verified, approval-needed").contains("2026-10-25T08:05:10Z"))
                .anySatisfy(line -> assertThat(line).contains("2026-10-25T07:00:00Z").contains("OPEN"));

        String json = run("projects", "-o", "json", "desk", "triggers", "--project", "qits").out();
        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.path("messages")).hasSize(2);
        assertThat(parsed.path("messages").get(0).path("id").asText()).isEqualTo("m-2");
        assertThat(parsed.path("messages").get(0).path("events")).hasSize(3);
    }

    @Test
    void deskTriggersSendsLimitOnlyWhenGivenAndSaysWhenThereAreNone() {
        answerProjects();
        platform.answer("GET", DESK_TRIGGERS, "{\"messages\":[]}");

        Result none = run("projects", "desk", "triggers", "--project", "qits");
        assertThat(none.exit()).as(none.err()).isZero();
        assertThat(none.out().lines().toList()).containsExactly("Project qits: no desk trigger messages.");
        assertThat(platform.requests("GET", DESK_TRIGGERS).getFirst().query()).isNullOrEmpty();

        Result limited = run("projects", "desk", "triggers", "--project", "qits", "--limit", "5");
        assertThat(limited.exit()).as(limited.err()).isZero();
        assertThat(platform.requests("GET", DESK_TRIGGERS).getLast().query()).isEqualTo("limit=5");

        platform.answer("GET", DESK_TRIGGERS, 400, "{\"message\":\"limit must be between 1 and 100, was 500\"}");
        Result tooMany = run("projects", "desk", "triggers", "--project", "qits", "--limit", "500");
        assertThat(tooMany.exit()).isEqualTo(1);
        assertThat(tooMany.err()).contains("answered HTTP 400: limit must be between 1 and 100, was 500");

        Result noProject = run("projects", "desk", "triggers");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");
    }

    // --- refusals ---

    @Test
    void a401NamesTheChallenge() {
        platform.answer("GET", "/projects/api/projects", 401, "",
                "WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"the token has expired\"");

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The platform refused the token (HTTP 401: invalid_token, the token has expired).");
    }

    @Test
    void a403SaysTheRolesDoNotAllowIt() {
        platform.answer("GET", "/projects/api/projects", 403, "");

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403)");
    }

    @Test
    void anotherStatusCarriesTheServicesMessage() {
        platform.answer("POST", REQUESTS, 409, "{\"message\":\"the request is already released\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "x");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("POST " + platform.url() + REQUESTS + " answered HTTP 409: the request is already released");
    }

    // --- the session ---

    @Test
    void withoutASessionItSaysToSignIn() throws Exception {
        java.nio.file.Files.delete(store.path());

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Not signed in — run `qits login`.");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void anExpiringTokenIsRefreshedFirst() throws Exception {
        seed(Duration.ofSeconds(10));

        Result r = run("projects", "list");

        assertThat(r.exit()).isZero();
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        String fresh = store.read().orElseThrow().accessToken();
        assertThat(fresh).isNotEqualTo(FakeIdp.SECRET + "access-0");
        assertThat(platform.requests).allMatch(req -> req.authorization().equals("Bearer " + fresh));
    }

    @Test
    void theProjectsAddressComesFromTheIdpWhenNothingNamesIt() {
        env.remove("QITS_PROJECTS_URL");

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Pass --projects-url or set QITS_PROJECTS_URL.");
        assertThat(run("projects", "list", "--projects-url", platform.url()).exit()).isZero();
    }

    // --- events, as far as a command goes ---

    @Test
    void eventsSendsTheFilterAsNames() {
        env.put("QITS_EVENTS_URL", platform.url());
        platform.streams.add(platform.refuse(403, ""));

        Result r = run("events", "--filter", " BuildSuccessful, BuildFailed ,");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(platform.requests("GET", "/events/api/stream").getFirst().query()).isEqualTo("names=BuildSuccessful,BuildFailed");
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403)");
    }

    @Test
    void eventsRefusesAPattern() {
        Result r = run("events", "--filter", "Build*");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'Build*' is neither");
        assertThat(run("events", "--filter", " , ").err()).contains("--filter names no event");
    }

    // --- repository git reads ---

    private static final String REPO = "/projects/api/repositories/" + CI;

    @Test
    void branchesAndCommitsResolveTheRepositoryByNameAndPassTheBranch() {
        platform.answer("GET", REPO + "/branches", """
                {"branches":[{"name":"ticket/x","parent":"main","ahead":2,"behind":0,"canCleanup":false}]}
                """);
        platform.answer("GET", REPO + "/commits", """
                {"branch":"ticket/x","parent":"main","commits":[{"hash":"abc","shortHash":"abc1234",
                  "author":"wohlben","date":"2026-10-10","message":"feat(qits-1): one\\n\\nbody","files":["a","b"]}]}
                """);

        Result branches = run("repositories", "--project", "qits", "branches", "--repository", "qits-ci-service");
        Result commits = run("repositories", "commits", "--project", "qits", "--repository", "qits-ci-service",
                "--branch", "ticket/x");

        assertThat(branches.exit()).as(branches.err()).isZero();
        assertThat(branches.out()).contains("ticket/x").contains("main");
        assertThat(commits.exit()).as(commits.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commits").getFirst().query()).isEqualTo("branch=ticket%2Fx");
        assertThat(commits.out()).contains("abc1234").contains("feat(qits-1): one").doesNotContain("body");
    }

    @Test
    void commitChangesDiffAndSubjectsSendTheirQueries() {
        platform.answer("GET", REPO + "/commits/abc/changes", """
                {"commit":"abc","files":[{"path":"README.md","changeType":"MODIFY"}]}
                """);
        platform.answer("GET", REPO + "/commits/abc/diff", """
                {"path":"README.md","changeType":"MODIFY","diff":"@@ -1 +1 @@\\n-a\\n+b"}
                """);
        platform.answer("GET", REPO + "/commit-subjects", """
                {"branch":"main","counts":{"total":3,"merge":0,"bump":1,"release":0,"platform":0,"subject":1,
                 "nonComplying":1},
                 "nonComplying":[{"shortHash":"def5678","authorName":"x","subject":"fix stuff"}]}
                """);

        Result changes = run("repositories", "--project", "qits", "commit-changes", "--repository", CI,
                "--commit", "abc", "--parent", "p0");
        Result diff = run("repositories", "--project", "qits", "commit-diff", "--repository", CI, "--commit", "abc",
                "--path", "README.md");
        Result subjects = run("repositories", "--project", "qits", "commit-subjects", "--repository", CI,
                "--limit", "20");

        assertThat(changes.exit()).as(changes.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commits/abc/changes").getFirst().query()).isEqualTo("parent=p0");
        assertThat(changes.out()).contains("MODIFY").contains("README.md");
        assertThat(diff.exit()).as(diff.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commits/abc/diff").getFirst().query()).isEqualTo("path=README.md");
        assertThat(diff.out()).contains("+b");
        assertThat(subjects.exit()).as(subjects.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commit-subjects").getFirst().query()).isEqualTo("limit=20");
        assertThat(subjects.out()).contains("1 non-complying of 3: 1 bump, 1 subject, 0 merge, 0 platform, 0 release")
                .contains("fix stuff");
    }

    @Test
    void aGitReadNeedsTheRepository() {
        Result r = run("repositories", "--project", "qits", "branches");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--repository");
    }
}
