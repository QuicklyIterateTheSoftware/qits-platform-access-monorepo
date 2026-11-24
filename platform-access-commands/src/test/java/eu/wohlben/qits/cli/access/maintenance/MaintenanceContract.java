package eu.wohlben.qits.cli.access.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What the commands ask qits-maintenance, and why</b> (ticket qits-1149): {@code qits maintenance
 * automations}, {@code automation run} and {@code bump}, and {@code qits artifacts fetch
 * deployed-pacts}'s one read (qits-1765), through {@link MaintenanceApi}, written to
 * {@code pacts/qits-platform-access-monorepo_qits-maintenance-service.json}.
 * <p>
 * qits-maintenance records the automations of a request at a named fold, and a re-run with no body
 * but {@code {}}: those are what {@code --fold} and a run without {@code --work-item} send. The list
 * at the request's newest fold (no {@code ?foldSha=}) and a run naming a work item ({@code
 * {"workItem": …}}, where the first re-run's recording sends {@code {"workItem": null, "repository":
 * …}}) each have a state of their own.
 * <p>
 * {@code qits maintenance sbom versions} and {@code sbom reread} (ticket qits-2490, {@link
 * SbomCommand}) are {@code listArtifactVersions} and {@code ingestArtifactSbom}: an artifact with
 * released versions, one with none, and a reread queued on a repository's manifests.
 */
public final class MaintenanceContract {

    static final String WITH_AUTOMATIONS = "a release request with automations";
    static final String TO_RE_RUN = "a release request with an automation to re-run";
    static final String PENDING_BUMPS = "pending bumps";
    /** listReleaseRequestAutomations without foldSha. */
    static final String AT_NEWEST_FOLD = "a release request with automations at its newest fold";
    /** runReleaseRequestAutomation with {"workItem": "<id>"}. */
    static final String FOR_A_WORK_ITEM = "a release request with an automation to re-run for a work item";
    /** listDeployedPacts for {@code qits artifacts fetch deployed-pacts} (qits-1765). */
    static final String DEPLOYED_PACTS = "a provider whose deployed consumers publish pacts";
    /** listArtifactVersions with a state param (ecosystem, name): {@code qits maintenance sbom versions}. */
    static final String AN_ARTIFACT_WITH_VERSIONS = "an artifact released in several versions";
    /** listArtifactVersions with no param: the ecosystem and name are the recorded request's own literals. */
    static final String NO_RELEASED_ARTIFACTS = "no released artifacts";
    /** ingestArtifactSbom: {@code qits maintenance sbom reread}. */
    static final String MANIFESTS_PIN_ARTIFACTS = "manifests that pin artifacts";

    private static final CliPact BASE = CliPact.of("qits-maintenance-service", "qits-maintenance");

    /** The automations table: kind, state, fold, newest run and detail. */
    static final List<String> AUTOMATIONS = List.of("foldSha", "automations[].kind", "automations[].state",
            "automations[].runIds", "automations[].detail");

    /** One bump as {@code MaintenanceCommand.printBump} prints it, each field only where the service sends it. */
    static final List<String> BUMP = List.of("id", "mode", "repository", "branch", "status", "message", "resultSha",
            "releaseRequestId", "ciRunId", "startedAt", "finishedAt");

    @FunctionalInterface
    interface Call {
        void run(MaintenanceApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new MaintenanceApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static Call automations(boolean atFold) {
        return (api, params, recorded) -> {
            JsonNode answer = api.automations(params.get("requestId"), atFold ? params.get("foldSha") : null);
            assertThat(answer.path("foldSha").asText()).isNotBlank();
            assertThat(answer.path("automations")).isNotEmpty().allSatisfy(a -> {
                assertThat(a.path("kind").asText()).isNotBlank();
                assertThat(a.path("state").asText()).isNotBlank();
                assertThat(a.path("runIds").isArray()).isTrue();
            });
        };
    }

    private static Call run(boolean workItem) {
        return (api, params, recorded) -> assertThat(api.runAutomation(params.get("requestId"), params.get("kind"),
                workItem ? params.get("workItem") : null).path("id").asText()).isNotBlank();
    }

    private static final Call BUMP_READ = (api, params, recorded) -> {
        String id = recorded.examplePath().substring(recorded.examplePath().lastIndexOf('/') + 1);
        JsonNode bump = api.bump(id);
        assertThat(bump.path("id").asText()).isEqualTo(id);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MaintenanceCommand.printBump(new PrintStream(bytes, true, StandardCharsets.UTF_8), bump);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("status");
    };

    /** What {@code qits artifacts fetch deployed-pacts} reads of each pact: the coordinate, where it goes, the line. */
    static final List<String> DEPLOYED = List.of("pacts[].consumerRepository", "pacts[].groupId",
            "pacts[].artifactId", "pacts[].pactVersion", "pacts[].deployedVersions");

    private static final Call DEPLOYED_PACTS_READ = (api, params, recorded) -> {
        JsonNode answer = api.deployedPacts(params.get("provider"));
        assertThat(answer.path("pacts")).isNotEmpty().allSatisfy(p -> {
            assertThat(p.path("consumerRepository").asText()).isNotBlank();
            assertThat(p.path("groupId").asText()).isNotBlank();
            assertThat(p.path("artifactId").asText()).isNotBlank();
            assertThat(p.path("pactVersion").asText()).isNotBlank();
            assertThat(p.path("deployedVersions").isArray()).isTrue();
        });
    };

    /** The versions table {@code SbomCommand.printVersions} prints: version, status, repository, released, error. */
    static final List<String> VERSIONS = List.of("[].version", "[].sbomStatus", "[].repository", "[].occurredAt",
            "[].sbomError");

    /** {@code qits maintenance sbom versions}, with a state naming the ecosystem and name as params. */
    private static final Call ARTIFACT_VERSIONS = (api, params, recorded) -> {
        JsonNode answer = api.listArtifactVersions(params.get("ecosystem"), params.get("name"));
        List<JsonNode> versions = new ArrayList<>();
        answer.forEach(versions::add);
        assertThat(versions).isNotEmpty().allSatisfy(v -> {
            assertThat(v.path("version").asText()).isNotBlank();
            assertThat(v.path("sbomStatus").asText()).isNotBlank();
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SbomCommand.printVersions(new PrintStream(bytes, true, StandardCharsets.UTF_8), versions);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("STATUS");
    };

    /**
     * {@code no released artifacts}: an empty array, for an artifact the platform has never seen
     * released. The state names no param, so the ecosystem and name are the recorded request's own
     * literals, read off its query rather than a state param.
     */
    private static final Call NO_VERSIONS = (api, params, recorded) -> {
        JsonNode answer = api.listArtifactVersions(recorded.query().get("ecosystem").getFirst(),
                recorded.query().get("name").getFirst());
        assertThat(answer.isArray()).isTrue();
        assertThat(answer).isEmpty();
    };

    /**
     * {@code qits maintenance sbom reread}: the ecosystem, name and version are the recorded
     * request's own literals (the state names a repository, not these), so the call reads them off
     * the recorded body rather than a state param.
     */
    private static final Call REREAD_SBOM = (api, params, recorded) -> {
        JsonNode body = CliRow.exampleBody(recorded);
        assertThat(api.ingestArtifactSbom(body.path("ecosystem").asText(), body.path("name").asText(),
                body.path("version").asText()).path("id").asText()).isNotBlank();
    };

    static final List<CliRow> ROWS = List.of(
            row("qits maintenance automations", WITH_AUTOMATIONS, "listReleaseRequestAutomations", "automations",
                    automations(true)).consumes(AUTOMATIONS),
            row("qits maintenance automations", AT_NEWEST_FOLD, "listReleaseRequestAutomations", "automations",
                    automations(false)).consumes(AUTOMATIONS)
                    .request("GET /maintenance/api/release-requests/{requestId}/automations"),
            row("qits maintenance automation run", TO_RE_RUN, "runReleaseRequestAutomation", "runAutomation",
                    run(false)).consumes("id"),
            row("qits maintenance automation run", FOR_A_WORK_ITEM, "runReleaseRequestAutomation", "runAutomation",
                    run(true)).consumes("id")
                    .request("POST /maintenance/api/release-requests/{requestId}/automations/{kind}/runs"),
            row("qits maintenance bump", PENDING_BUMPS, "getBump", "bump", BUMP_READ)
                    .consumes(BASE.held(PENDING_BUMPS, "getBump", BUMP)),
            CliRow.of("qits artifacts fetch deployed-pacts", DEPLOYED_PACTS, "listDeployedPacts",
                            "MaintenanceApi#deployedPacts", api(DEPLOYED_PACTS_READ)).consumes(DEPLOYED)
                    .request("GET /maintenance/api/deployed-pacts"),
            row("qits maintenance sbom versions", AN_ARTIFACT_WITH_VERSIONS, "listArtifactVersions",
                    "listArtifactVersions", ARTIFACT_VERSIONS).consumes(VERSIONS)
                    .request("GET /maintenance/api/artifacts/versions"),
            row("qits maintenance sbom versions", NO_RELEASED_ARTIFACTS, "listArtifactVersions",
                    "listArtifactVersions", NO_VERSIONS).consumes("$")
                    .request("GET /maintenance/api/artifacts/versions"),
            row("qits maintenance sbom reread", MANIFESTS_PIN_ARTIFACTS, "ingestArtifactSbom", "ingestArtifactSbom",
                    REREAD_SBOM).consumes("id")
                    .request("POST /maintenance/api/artifacts/ingest"));

    public static final CliPact CONTRACT = BASE.client(MaintenanceApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "MaintenanceApi#" + method, api(call));
    }

    private MaintenanceContract() {
    }
}
