package eu.wohlben.qits.cli.access.uibuilder;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits ui-builder} asks qits-artifacts</b> (qits-2156), rows of {@code
 * publish/ArtifactsContract}: the docs store reads of the landing app's UI builder page, made by
 * {@link DocsStore}. Every command lists the sites and reads a site's versions to learn which
 * repository it belongs to, then reads one file of the version it found: a routes bundle's {@code
 * manifest.json} and a freeze's HTML, a contracts bundle's {@code golden-masters/index.json} and its
 * {@code entities.json}.
 * <p>
 * A recorded response under {@code golden-masters/} ({@code golden-masters --state --operation}) is
 * the same door, {@code getDocsFile}, at another path; no qits-artifacts state records that path yet,
 * so it has no row of its own. A freeze's HTML ({@code freeze --html}) is bound to the manifest's
 * path: see {@link #TEXT}.
 */
public final class UiBuilderRows {

    static final String ROUTES = "a published routes bundle";
    static final String KINDS = "a published routes bundle with kinds and viewports";
    static final String GOLDEN_INDEX = "a published contracts bundle with a golden-master index";
    static final String ENTITY_MAP = "a published contracts bundle with an entity map";

    public static final Class<?> CLIENT = DocsStore.class;

    public static final String[] DOORS = {"sites", "versions", "file", "text"};

    private static final String GROUP = "qits ui-builder ";

    private static DocsStore store(String url) {
        return new DocsStore(new PlatformClient(Reached.CREDENTIAL), url);
    }

    /** Every site, of which the command keeps the {@code @routes/} or {@code @contracts/} ones. */
    private static CliRow.Call sites(String prefix) {
        return (url, recorded) -> {
            JsonNode sites = store(url).sites();
            assertThat(sites.path("sites").findValuesAsText("name")).anyMatch(n -> n.startsWith(prefix));
        };
    }

    /** A site's versions, and the repository the newest one was released from. */
    private static final CliRow.Call VERSIONS = (url, recorded) -> {
        JsonNode versions = store(url).versions(recorded.params().get("site"));
        JsonNode newest = versions.path("versions").path(0);
        assertThat(newest.path("version").asText()).isNotBlank();
        assertThat(newest.path("metadata").path(Bundles.REPOSITORY_NAME).asText()).isNotBlank();
    };

    /** One JSON file of the version, which the command prints as it was read. */
    private static CliRow.Call file(String field) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            JsonNode file = store(url).file(p.get("site"), p.get("version"), p.get("path"));
            assertThat(file.has(field)).as(p.get("path") + " has " + field).isTrue();
        };
    }

    /**
     * {@code freeze --html}: a file of the version read as text, printed as it is. Bound to the
     * manifest's GET, not to {@code a published routes bundle freeze}: that state's file path holds
     * {@code [slug]}, which the recorded request names literally while the store needs it encoded
     * ({@code %5Bslug%5D}; literal is a 400), and this harness binds a path exactly. The request is
     * the same door, {@code getDocsFile}, with the same address rules.
     */
    private static final CliRow.Call TEXT = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        assertThat(store(url).text(p.get("site"), p.get("version"), p.get("path"))).contains("freezes");
    };

    public static final List<CliRow> ROWS = rows();

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        // routes and freeze: find the @routes/ site of the repository, read its manifest
        for (String command : List.of("routes", "freeze")) {
            for (String state : List.of(ROUTES, KINDS)) {
                rows.add(CliRow.of(GROUP + command, state, "listDocsSites", "DocsStore#sites", sites(Bundles.ROUTES))
                        .consumes("sites[].name", "sites[].latestVersion")
                        .request("GET /artifacts/docs/docs"));
                rows.add(CliRow.of(GROUP + command, state, "listDocsVersions", "DocsStore#versions", VERSIONS)
                        .consumes("versions[].version", "versions[].metadata")
                        .request("GET /artifacts/docs/docs/{site}"));
                if (command.equals("freeze") && state.equals(ROUTES)) {
                    // Its manifest GET is the --html row's below: one interaction per request.
                    continue;
                }
                rows.add(CliRow.of(GROUP + command, state, "getDocsFile", "DocsStore#file", file("freezes"))
                        .consumes("freezes[].file")
                        .request("GET /artifacts/docs/docs/{site}/-/{version}/{path}"));
            }
        }
        rows.add(CliRow.of(GROUP + "freeze", ROUTES, "getDocsFile", "DocsStore#text", TEXT)
                .consumes("freezes[].file")
                .request("GET /artifacts/docs/docs/{site}/-/{version}/{path}"));
        // golden-masters and entities: find the @contracts/ site of the provider, read one file
        rows.add(CliRow.of(GROUP + "golden-masters", GOLDEN_INDEX, "listDocsSites", "DocsStore#sites",
                        sites(Bundles.CONTRACTS))
                .consumes("sites[].name", "sites[].latestVersion")
                .request("GET /artifacts/docs/docs"));
        rows.add(CliRow.of(GROUP + "golden-masters", GOLDEN_INDEX, "listDocsVersions", "DocsStore#versions", VERSIONS)
                .consumes("versions[].version", "versions[].metadata")
                .request("GET /artifacts/docs/docs/{site}"));
        rows.add(CliRow.of(GROUP + "golden-masters", GOLDEN_INDEX, "getDocsFile", "DocsStore#file", file("states"))
                .consumes("states[].slug", "states[].name", "states[].operations[].operationId",
                        "states[].operations[].file")
                .request("GET /artifacts/docs/docs/{site}/-/{version}/{path}"));
        rows.add(CliRow.of(GROUP + "entities", ENTITY_MAP, "listDocsSites", "DocsStore#sites",
                        sites(Bundles.CONTRACTS))
                .consumes("sites[].name", "sites[].latestVersion")
                .request("GET /artifacts/docs/docs"));
        rows.add(CliRow.of(GROUP + "entities", ENTITY_MAP, "listDocsVersions", "DocsStore#versions", VERSIONS)
                .consumes("versions[].version", "versions[].metadata")
                .request("GET /artifacts/docs/docs/{site}"));
        rows.add(CliRow.of(GROUP + "entities", ENTITY_MAP, "getDocsFile", "DocsStore#file", file("entities"))
                .consumes("entities[].entity")
                .request("GET /artifacts/docs/docs/{site}/-/{version}/{path}"));
        return List.copyOf(rows);
    }

    private UiBuilderRows() {
    }
}
