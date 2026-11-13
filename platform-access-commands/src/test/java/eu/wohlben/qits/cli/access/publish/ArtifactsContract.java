package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.artifacts.FetchRows;
import eu.wohlben.qits.cli.access.changelog.BumpMessageRows;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.uibuilder.UiBuilderRows;
import eu.wohlben.qits.cli.access.update.UpdateRows;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the commands ask qits-artifacts, and why</b> (ticket qits-1149): the publish commands'
 * store doors (sboms, daemon binaries, docs bundles, content hashes, the hosted Maven repository
 * and npm registry), {@code qits update}'s daemon reads ({@link UpdateRows}), {@code qits
 * changelog bump-message}'s changelog read ({@link BumpMessageRows}) and {@code qits artifacts fetch
 * deployed-pacts}' pact jar ({@link FetchRows}) and {@code qits ui-builder}'s docs store reads ({@link
 * UiBuilderRows}), written to {@code
 * pacts/qits-platform-access-monorepo_qits-artifacts-service.json}.
 * <p>
 * <b>One row per (command, request).</b> A row runs the client method the command calls, over the
 * publish {@link Http} with a fixed token, against a mock answering that one request. Where a
 * command makes a second request on one answer (the HEAD after a daemon PUT's 409, the GET after a
 * docs PUT's 409, the sbom read back when the digests differ, the packument before an npm publish),
 * each request has its own row, which sends exactly what the command sends through the same {@link
 * Store} address and {@link Http} method, since a mock serving one interaction cannot answer two.
 * The publish client sends no {@code PlatformClient} bearer, so these rows are held to the request
 * the pact names rather than to the stack ({@code Reached}).
 * <p>
 * Every row is recorded: the hosted Maven repository and npm registry doors, the 409s, the 404s of a
 * coordinate nothing published, and {@code qits update}'s reads, under the state names the provider
 * records them by. Not in this pact: {@code
 * MavenReactor}'s fall-through to Maven Central, which is qits-mirror's ({@code
 * /mirror/maven/central}); the rows here give the resolver the hosted root only.
 */
public final class ArtifactsContract {

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- the states -----------------------------------------------------------------------------

    static final String NO_SBOM = "no sbom published for the coordinate";
    static final String AN_SBOM = "an sbom published for the coordinate";
    static final String NO_DAEMON = "a daemon nothing has published";
    static final String TWO_DAEMONS = "a daemon with two versions";
    static final String NO_DOCS = "no docs bundle published for the coordinate";
    static final String A_DOCS_SITE = "a published docs site";
    /** A site whose newest version records {@code content.hash}: what {@code docs submit --if-changed} reads. */
    static final String A_HASHED_DOCS_SITE = "a docs site published with a content hash";
    static final String A_HASH = "a content hash recorded for the coordinate";
    static final String CHANGELOGS = "a repository with published changelogs";
    static final String NO_CHANGELOG = "a repository with no changelog";
    static final String A_MAVEN_ARTIFACT = "a maven artifact published at a version";
    static final String NO_MAVEN_ARTIFACT = "no maven artifact published at the version";
    static final String AN_NPM_PACKAGE = "an npm package published at a version";
    static final String NO_NPM_PACKAGE = "no npm package with the given name";
    /** The jar the maven publish PUTs before its pom. */
    static final String NO_MAVEN_JAR = "no maven jar published at the version";
    /** The content hashes of an npm package (the other recorded ones are a maven artifact's). */
    static final String AN_NPM_HASH = "a content hash recorded for an npm package";

    private static final CliPact BASE = CliPact.of("qits-artifacts-service", "qits-artifacts");

    // --- the clients ----------------------------------------------------------------------------

    private static Console console() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new Console(sink, sink);
    }

    static Http http() {
        return new Http(new PublishCredential(new Env(Map.of(PublishCredential.TOKEN, "pact-consumer-test")),
                console()));
    }

    static Store store(String url) {
        return new Store(new Store.Hosts(url, url));
    }

    private static Publisher publisher(String url) {
        return new Publisher(http(), store(url), console());
    }

    private static ContentHashes hashes(String url) {
        return new ContentHashes(http(), store(url));
    }

    private static Path file(byte[] bytes) throws Exception {
        Path file = Files.createTempFile("pact-", ".bin");
        Files.write(file, bytes);
        file.toFile().deleteOnExit();
        return file;
    }

    /** The recorded request body, exactly: a text as it stands, a JSON body compact, the way it was stored. */
    private static byte[] body(Operation recorded) throws Exception {
        if (recorded.body().isTextual()) {
            return recorded.example(recorded.body().asText()).getBytes(StandardCharsets.UTF_8);
        }
        return JSON.writeValueAsBytes(CliRow.exampleBody(recorded));
    }

    /**
     * The recorded docs bundle, byte for byte: the gzipped tar the provider recorded as {@code
     * bodyBase64}, which qits-pact-consumer sends as the request body and pact matches exactly.
     */
    private static byte[] bundle(Operation recorded) {
        if (recorded.binaryBody() == null) {
            throw new IllegalStateException(recorded.state() + "/" + recorded.operationId()
                    + ": no recorded bundle (bodyBase64) to send");
        }
        return recorded.binaryBody();
    }

    private static String[] coordinate(Map<String, String> params) {
        return params.get("name").split(":", 2);
    }

    // --- the calls ------------------------------------------------------------------------------

    private static final CliRow.Call SBOM = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        assertThat(publisher(url).sbomSubmit(p.get("packageType"), p.get("packageName"), p.get("version"),
                file(body(recorded)))).isEqualTo(ExitCode.OK);
    };

    /** The stored sbom read back, compared whole once serialNumber and the timestamp are set aside. */
    private static final CliRow.Call SBOM_READ_BACK = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        Http.Response stored = http().get(store(url).sbomDocument(p.get("packageType"), p.get("packageName"),
                p.get("version")));
        assertThat(stored.status()).isEqualTo(200);
        assertThat(Json.parseObject(stored.body(), "the stored sbom")).containsKey("bomFormat");
    };

    private static final CliRow.Call DAEMON = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        assertThat(publisher(url).daemonSubmit(p.get("daemon"), p.get("version"), file(body(recorded))))
                .isEqualTo(ExitCode.OK);
    };

    /** The PUT that answers 409: the command reads the stored digest from a HEAD next. */
    private static final CliRow.Call DAEMON_TAKEN = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        assertThat(http().putFile(store(url).daemonBinary(p.get("daemon"), p.get("version")), file(body(recorded)),
                "application/octet-stream", Map.of()).status()).isEqualTo(409);
    };

    /** After a 409, the stored digest: Docker-Content-Digest, else the ETag. */
    private static final CliRow.Call DAEMON_DIGEST = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        Http.Response head = http().head(store(url).daemonBinary(p.get("daemon"), p.get("version")));
        assertThat(head.status()).isEqualTo(200);
        assertThat(Sha256.normalize(head.header("Docker-Content-Digest"))).isNotBlank();
    };

    private static CliRow.Call docs(String site) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            assertThat(publisher(url).docsSubmit(site == null ? p.get("site") : site, p.get("version"),
                    file(bundle(recorded)), List.of())).isEqualTo(ExitCode.OK);
        };
    }

    /** The PUT that answers 409: the command probes the stored version next. */
    private static final CliRow.Call DOCS_TAKEN = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        assertThat(http().putFile(store(url).docsBundle(p.get("site"), p.get("version")),
                file(bundle(recorded)), Publisher.DOCS_BUNDLE_TYPE, Map.of()).status()).isEqualTo(409);
    };

    /** After a 409, the stored version's shape: file count, bytes and when it was published. */
    private static final CliRow.Call DOCS_PROBE = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        Http.Response probe = http().get(store(url).docsBundle(p.get("site"), p.get("version")));
        assertThat(probe.status()).isEqualTo(200);
        Map<String, Object> body = Json.parseObject(probe.body(), "the docs bundle description");
        assertThat(Json.number(body, "fileCount")).isNotBlank();
        assertThat(Json.string(body, "publishedAt")).isNotBlank();
    };

    /**
     * {@code docs submit --if-changed}: the site's newest version and the {@code content.hash} it
     * records, which is the state's {@code hash}; a site nothing published is absent, not an error.
     */
    private static CliRow.Call newestDocs(boolean present) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            var newest = new DocsVersions(http(), store(url)).newest(p.get("site"));
            assertThat(newest.isPresent()).isEqualTo(present);
            newest.ifPresent(n -> {
                assertThat(n.version()).isNotBlank();
                assertThat(n.contentHash()).isEqualTo(p.get("hash"));
            });
        };
    }

    private static CliRow.Call exists(String type, String nameParam, int exit) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            assertThat(publisher(url).exists(type, p.get(nameParam), p.get("version"))).isEqualTo(exit);
        };
    }

    private static CliRow.Call version(boolean present) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            var stored = hashes(url).version(p.get("type"), p.get("name"), p.get("version"));
            assertThat(stored.isPresent()).isEqualTo(present);
            stored.ifPresent(s -> assertThat(s.contentHash()).startsWith("v1:"));
        };
    }

    private static CliRow.Call newest(boolean present) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            var stored = hashes(url).newest(p.get("type"), p.get("name"));
            assertThat(stored.isPresent()).isEqualTo(present);
            stored.ifPresent(s -> assertThat(s.version()).isNotBlank());
        };
    }

    /** A pom outside the reactor, read from the hosted repository (the mirror is never reached here). */
    private static CliRow.Call resolve(boolean found) {
        return (url, recorded) -> {
            String[] ga = coordinate(recorded.params());
            Class<?> type = resolver();
            Constructor<?> make = type.getDeclaredConstructor(Http.class, List.class, Map.class);
            make.setAccessible(true);
            Object resolver = make.newInstance(http(), List.of(store(url).mavenRegistry()), Map.of());
            Method resolveModel = type.getMethod("resolveModel", String.class, String.class, String.class);
            if (found) {
                assertThat(resolveModel.invoke(resolver, ga[0], ga[1], recorded.params().get("version"))).isNotNull();
            } else {
                assertThatThrownBy(() -> {
                    try {
                        resolveModel.invoke(resolver, ga[0], ga[1], recorded.params().get("version"));
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }).isInstanceOf(CliException.class).hasMessageContaining("answered 404");
            }
        };
    }

    /** The upload's PUT of one maven file, as {@code MavenPublisher.put} sends it. */
    private static CliRow.Call mavenPut(String extension, String contentType, boolean hashHeader) {
        return (url, recorded) -> {
            Map<String, String> p = recorded.params();
            String[] ga = coordinate(p);
            String version = p.get("version");
            Http.Response response = http().putBytes(
                    store(url).mavenFile(ga[0], ga[1], version, ga[1] + "-" + version + extension), body(recorded),
                    contentType, hashHeader ? Map.of("X-Artifacts-Content-Hash", "v1:sha256:" + "c".repeat(64))
                            : Map.of());
            assertThat(response.status()).isIn(200, 201);
        };
    }

    private static CliRow.Call plan(String answer) {
        return (url, recorded) -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
            Map<String, String> p = recorded.params();
            assertThat(new Npm(http(), store(url), new Console(print, print)).plan(p.get("package"),
                    p.get("version"))).isEqualTo(ExitCode.OK);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains(answer);
        };
    }

    private static final CliRow.Call DIST_TAG = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        assertThat(new Npm(http(), store(url), console()).distTag(p.get("package"), p.get("version"), p.get("tag")))
                .isEqualTo(ExitCode.OK);
    };

    /** Before the publish, the packument: a version below its latest is a replay. */
    private static final CliRow.Call REPLAY = (url, recorded) -> {
        Http.Response response = http().get(store(url).npmPackument(recorded.params().get("package")));
        assertThat(response.status()).isEqualTo(200);
        assertThat(Json.string(Json.object(Json.parseObject(response.body(), "the npm packument"), "dist-tags"),
                "latest")).isNotBlank();
    };

    /** The npm publish document, as {@code NpmPublisher.upload} PUTs it. */
    private static final CliRow.Call NPM_PUT = (url, recorded) -> {
        Http.Response response = http().putBytes(store(url).npmPackument(recorded.params().get("package")),
                body(recorded), "application/json", Map.of("X-Artifacts-Content-Hash", "v1:sha256:" + "c".repeat(64)));
        assertThat(response.status()).isIn(200, 201);
    };

    /** {@code publish changelog}: the newest changelog below this release in the store's listing. */
    private static final CliRow.Call CHANGELOG_LISTING = (url, recorded) -> {
        Map<String, String> p = recorded.params();
        ChangelogCommand.Release release = new ChangelogCommand.Release("00000000-0000-4000-8000-000000000001",
                p.get("repository"), "00000000-0000-4000-8000-000000000002", null, Instant.EPOCH);
        // The release itself is the newest listed: nothing below it is unpublished, so the projects
        // service is not asked (its own pact has that row).
        assertThat(ChangelogCommand.unpublished(http(), store(url), null, release, p.get("version"))).isEmpty();
    };

    // --- the table ------------------------------------------------------------------------------

    private static final String PUBLISH = "qits artifacts publish ";

    static final List<CliRow> ROWS = rows();

    public static final CliPact CONTRACT = BASE
            .client(Publisher.class, "sbomSubmit", "daemonSubmit", "docsSubmit", "exists")
            .client(ContentHashes.class, "version", "newest")
            .client(DocsVersions.class, "newest")
            .client(ChangelogCommand.class, "unpublished")
            .client(MavenPublisher.class, "publish", "publishContract")
            .client(resolver(), "resolveModel")
            .client(Npm.class, "plan", "distTag")
            .client(NpmPublisher.class, "publish")
            .client(UpdateRows.CLIENT, UpdateRows.DOORS)
            .client(BumpMessageRows.CLIENT, BumpMessageRows.DOORS)
            .client(FetchRows.CLIENT, FetchRows.DOORS)
            .client(UiBuilderRows.CLIENT, UiBuilderRows.DOORS)
            .rows(ROWS)
            .rows(UpdateRows.ROWS)
            .rows(BumpMessageRows.ROWS)
            .rows(FetchRows.ROWS)
            .rows(UiBuilderRows.ROWS);

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        // sbom submit
        rows.add(row("sbom submit", NO_SBOM, "putSbom", "Publisher#sbomSubmit", SBOM).consumes("sizeBytes", "digest"));
        rows.add(row("sbom submit", AN_SBOM, "putSbom", "Publisher#sbomSubmit", SBOM).consumes("digest"));
        rows.add(row("sbom submit", AN_SBOM, "getSbom", "Publisher#sbomSubmit", SBOM_READ_BACK).consumes("$"));
        // daemon submit
        rows.add(row("daemon submit", NO_DAEMON, "putDaemon", "Publisher#daemonSubmit", DAEMON)
                .consumes("sizeBytes", "digest"));
        rows.add(row("daemon submit", TWO_DAEMONS, "putDaemon", "Publisher#daemonSubmit", DAEMON_TAKEN)
                .request("PUT /artifacts/daemons/{daemon}/{version}"));
        rows.add(row("daemon submit", TWO_DAEMONS, "headDaemon", "Publisher#daemonSubmit", DAEMON_DIGEST)
                .readsHeader("Docker-Content-Digest", "ETag"));
        // docs submit, and the two commands that submit a bundle of their own
        for (String command : List.of("docs submit", "changelog", "contract-docs")) {
            rows.add(row(command, NO_DOCS, "putDocs", "Publisher#docsSubmit", docs(null))
                    .consumes("fileCount", "totalBytes"));
            rows.add(row(command, A_DOCS_SITE, "putDocs", "Publisher#docsSubmit", DOCS_TAKEN)
                    .request("PUT /artifacts/docs/docs/{site}/-/{version}"));
            rows.add(row(command, A_DOCS_SITE, "getDocsVersion", "Publisher#docsSubmit", DOCS_PROBE)
                    .consumes("fileCount", "totalBytes", "publishedAt"));
        }
        // docs submit --if-changed: the newest version's content hash, before the PUT above (qits-1948).
        // `metadata` is read whole: its one key the command reads, content.hash, has a dot in it,
        // which a consumed path cannot spell.
        rows.add(row("docs submit", NO_DOCS, "listDocsVersions", "DocsVersions#newest", newestDocs(false)));
        rows.add(row("docs submit", A_HASHED_DOCS_SITE, "listDocsVersions", "DocsVersions#newest", newestDocs(true))
                .consumes("versions[].version", "versions[].metadata")
                .request("GET /artifacts/docs/docs/{site}"));
        rows.add(row("changelog", CHANGELOGS, "listDocsVersions", "ChangelogCommand#unpublished", CHANGELOG_LISTING)
                .consumes("versions[].version"));
        rows.add(row("changelog", NO_CHANGELOG, "listDocsVersions", "ChangelogCommand#unpublished",
                CHANGELOG_LISTING));
        // exists
        rows.add(row("exists", TWO_DAEMONS, "headDaemon", "Publisher#exists", exists("daemon", "daemon", ExitCode.OK)));
        rows.add(row("exists", NO_DAEMON, "headDaemon", "Publisher#exists",
                exists("daemon", "daemon", ExitCode.POLICY)));
        rows.add(row("exists", A_DOCS_SITE, "getDocsVersion", "Publisher#exists", exists("docs", "site", ExitCode.OK)));
        rows.add(row("exists", NO_DOCS, "getDocsVersion", "Publisher#exists", exists("docs", "site", ExitCode.POLICY)));
        rows.add(row("exists", AN_SBOM, "headSbom", "Publisher#exists", exists("sbom", "coordinate", ExitCode.OK)));
        rows.add(row("exists", NO_SBOM, "headSbom", "Publisher#exists", exists("sbom", "coordinate", ExitCode.POLICY)));
        rows.add(row("exists", AN_NPM_PACKAGE, "getNpmPackument", "Publisher#exists",
                exists("npm", "package", ExitCode.OK)).consumes("versions")
                .request("GET /artifacts/npm/npm/{package}"));
        rows.add(row("exists", NO_NPM_PACKAGE, "getNpmPackument", "Publisher#exists",
                exists("npm", "package", ExitCode.POLICY)).request("GET /artifacts/npm/npm/{package}"));
        // the if-changed decision of every package publish: the version's content hash, then the newest's
        for (String command : List.of("maven", "contract")) {
            rows.add(row(command, A_HASH, "getContentHash", "ContentHashes#version", version(true))
                    .consumes("version", "contentHash"));
            rows.add(row(command, A_HASH, "getNewestContentHash", "ContentHashes#newest", newest(true))
                    .consumes("version", "contentHash"));
            rows.add(row(command, NO_MAVEN_ARTIFACT, "getContentHash", "ContentHashes#version", version(false))
                    .request("GET /artifacts/content-hashes/{type}/{name}/-/{version}"));
            rows.add(row(command, NO_MAVEN_ARTIFACT, "getNewestContentHash", "ContentHashes#newest", newest(false))
                    .request("GET /artifacts/content-hashes/{type}/{name}/-/newest"));
        }
        rows.add(row("contract-docs", A_HASH, "getNewestContentHash", "ContentHashes#newest", newest(true))
                .consumes("version", "contentHash"));
        rows.add(row("npm", AN_NPM_HASH, "getContentHash", "ContentHashes#version", version(true))
                .consumes("version", "contentHash").request("GET /artifacts/content-hashes/{type}/{name}/-/{version}"));
        rows.add(row("npm", AN_NPM_HASH, "getNewestContentHash", "ContentHashes#newest", newest(true))
                .consumes("version", "contentHash").request("GET /artifacts/content-hashes/{type}/{name}/-/newest"));
        // qits artifacts publish maven: the poms outside the reactor, then the upload
        rows.add(row("maven", A_MAVEN_ARTIFACT, "getMavenFile", "MavenReactor$HttpResolver#resolveModel", resolve(true))
                .request("GET /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.pom"));
        rows.add(row("maven", NO_MAVEN_ARTIFACT, "getMavenFile", "MavenReactor$HttpResolver#resolveModel", resolve(false))
                .request("GET /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.pom"));
        rows.add(row("maven", NO_MAVEN_JAR, "putMavenFile", "MavenPublisher#publish",
                mavenPut(".jar", "application/java-archive", false))
                .request("PUT /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.jar"));
        rows.add(row("maven", NO_MAVEN_ARTIFACT, "putMavenFile", "MavenPublisher#publish",
                mavenPut(".pom", "application/xml", true))
                .request("PUT /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.pom"));
        rows.add(row("contract", NO_MAVEN_JAR, "putMavenFile", "MavenPublisher#publishContract",
                mavenPut(".jar", "application/java-archive", false))
                .request("PUT /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.jar"));
        rows.add(row("contract", NO_MAVEN_ARTIFACT, "putMavenFile", "MavenPublisher#publishContract",
                mavenPut(".pom", "application/xml", true))
                .request("PUT /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.pom"));
        // npm: plan, dist-tag, and the publish (contract's npm packages publish the same way)
        rows.add(row("npm plan", AN_NPM_PACKAGE, "getNpmPackument", "Npm#plan", plan("skip"))
                .consumes("versions", "dist-tags.latest").request("GET /artifacts/npm/npm/{package}"));
        rows.add(row("npm plan", NO_NPM_PACKAGE, "getNpmPackument", "Npm#plan", plan("publish"))
                .request("GET /artifacts/npm/npm/{package}"));
        rows.add(row("npm dist-tag", AN_NPM_PACKAGE, "putNpmDistTag", "Npm#distTag", DIST_TAG)
                .request("PUT /artifacts/npm/npm/-/package/{package}/dist-tags/{tag}"));
        for (String command : List.of("npm", "contract")) {
            rows.add(row(command, AN_NPM_PACKAGE, "getNpmPackument", "NpmPublisher#publish", REPLAY)
                    .consumes("dist-tags.latest").request("GET /artifacts/npm/npm/{package}"));
            rows.add(row(command, NO_NPM_PACKAGE, "putNpmPackage", "NpmPublisher#publish", NPM_PUT)
                    .request("PUT /artifacts/npm/npm/{package}"));
            rows.add(row(command, AN_NPM_PACKAGE, "putNpmDistTag", "Npm#distTag", DIST_TAG)
                    .request("PUT /artifacts/npm/npm/-/package/{package}/dist-tags/{tag}"));
        }
        return List.copyOf(rows);
    }

    /** {@code MavenReactor$HttpResolver}, a private class: the client {@code publish maven} reads poms through. */
    private static Class<?> resolver() {
        try {
            return Class.forName(MavenReactor.class.getName() + "$HttpResolver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CliRow row(String command, String state, String operationId, String reaches, CliRow.Call call) {
        return CliRow.of(PUBLISH + command, state, operationId, reaches, call);
    }

    private ArtifactsContract() {
    }
}
