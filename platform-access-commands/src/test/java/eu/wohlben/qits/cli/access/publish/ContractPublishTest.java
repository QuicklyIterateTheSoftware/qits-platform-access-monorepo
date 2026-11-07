package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code publish contract} and {@code publish contract-docs}: the platform's deterministic contract
 * packagers, and the {@code @contracts/<application>} bundle that follows them.
 */
class ContractPublishTest {

  private static final String V = "2026.1002.1";
  private static final String GM = "eu.wohlben.qits:qits-projects-golden-masters";
  private static final String GM_JAR = "/artifacts/maven/maven/eu/wohlben/qits/qits-projects-golden-masters/" + V
      + "/qits-projects-golden-masters-" + V + ".jar";
  private static final String GM_POM = GM_JAR.replace(".jar", ".pom");
  private static final String NPM = "@qits/projects-golden-masters";
  private static final String DOCS = "/artifacts/docs/docs/@contracts/qits-projects/-/" + V;

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path tree;

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    tree = work.resolve("golden-masters");
    Files.createDirectories(tree.resolve("states"));
    Files.writeString(tree.resolve("index.json"), "{\"formatVersion\":1}\n");
    Files.writeString(tree.resolve("states/a.json"), "[]\n");
    store.on("PUT", GM_JAR, StubStore.Reply.of(201, ""));
    store.on("PUT", GM_POM, StubStore.Reply.of(201, ""));
    store.on("PUT", "/artifacts/npm/npm/" + NPM, StubStore.Reply.of(201, "{}"));
    store.on("PUT", DOCS, StubStore.Reply.of(201, "{\"fileCount\":3,\"totalBytes\":100}"));
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private StubStore.Request put(String path) {
    return store.requests().stream().filter(r -> r.method().equals("PUT") && r.path().equals(path))
        .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no PUT " + path + " in " + store.paths()));
  }

  // --- the packagers -----------------------------------------------------------------------------

  @Test
  void theContractJarIsByteIdenticalAcrossBuildsAndCarriesItsDirectories() throws IOException {
    byte[] first = ContractPackager.jar(tree, ContractPackager.Kind.GOLDEN_MASTERS, null);
    byte[] second = ContractPackager.jar(tree, ContractPackager.Kind.GOLDEN_MASTERS, null);
    assertArrayEquals(first, second);

    List<String> names = new java.util.ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(first))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        names.add(entry.getName());
        assertEquals(ZipEntry.STORED, entry.getMethod(), entry.getName());
      }
    }
    assertEquals(List.of("META-INF/", "META-INF/MANIFEST.MF", "golden-masters/", "golden-masters/index.json",
        "golden-masters/states/", "golden-masters/states/a.json"), names);
  }

  @Test
  void aPactsJarHoldsOnlyItsProvidersPactsFromAFlatDirectory() throws IOException {
    Path pacts = Files.createDirectories(work.resolve("consumer-pacts"));
    Files.writeString(pacts.resolve("qits-landing-app_qits-projects-service.json"), "{}\n");
    Files.writeString(pacts.resolve("qits-landing-app_qits-githost-service.json"), "{}\n");
    Files.writeString(pacts.resolve("README.md"), "not a pact\n");

    List<String> names = Archives.readJar(
            ContractPackager.jar(pacts, ContractPackager.Kind.PACTS, "qits-projects-service"), "jar").stream()
        .map(Archives.Entry::name).toList();

    assertEquals(List.of("META-INF/", "META-INF/MANIFEST.MF", "pacts/",
        "pacts/qits-landing-app_qits-projects-service.json"), names);
  }

  @Test
  void aPactsPackageWithNoFileForItsProviderIsRefused() throws IOException {
    Path pacts = Files.createDirectories(work.resolve("consumer-pacts"));
    Files.writeString(pacts.resolve("qits-landing-app_qits-githost-service.json"), "{}\n");

    Harness.Run run = cli.run("contract", "--kind", "pacts", "--ecosystem", "maven", "--name",
        "eu.wohlben.qits:qits-landing-app-pacts-qits-projects-service", "--application", "qits-landing",
        "--provider", "qits-projects-service", "--from", pacts.toString(), "--version", V);

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("holds no *_qits-projects-service.json"), run.err());
  }

  @Test
  void anEmptyTreeIsRefused() throws IOException {
    Path empty = Files.createDirectories(work.resolve("empty/sub"));

    Harness.Run run = cli.run("contract", "--kind", "golden-masters", "--ecosystem", "maven", "--name", GM,
        "--application", "qits-projects", "--from", empty.getParent().toString(), "--version", V);

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("holds no file"), run.err());
  }

  // --- contract ----------------------------------------------------------------------------------

  @Test
  void aMavenContractIsPublishedWithTheF1Hash() throws Exception {
    Harness.Run run = cli.run("contract", "--kind", "golden-masters", "--ecosystem", "maven", "--name", GM,
        "--application", "qits-projects", "--from", tree.toString(), "--version", V);

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    // The same tree as fixture F1, so the same value: the packager adds nothing the hash counts.
    assertEquals("v1:sha256:f8e47f30eefda8d066196888451be0ec1d08d9e53afed8b30cf71ba36ddb77c3",
        put(GM_POM).header("X-Artifacts-Content-Hash"));
    String pom = put(GM_POM).bodyText();
    assertTrue(pom.contains("<description>qits-projects golden masters (contracts)</description>"), pom);
    assertFalse(pom.contains("<parent>"), pom);
    assertFalse(pom.contains("<dependencies>"), pom);
  }

  @Test
  void aContractIsAlwaysIfChanged() {
    store.on("GET", "/artifacts/content-hashes/maven/" + GM + "/-/newest", StubStore.Reply.of(200,
        "{\"version\":\"2026.930.1\",\"contentHash\":\"v1:sha256:f8e47f30eefda8d066196888451be0ec1d08d9e53afed8b30cf71ba36ddb77c3\"}"));

    Harness.Run run = cli.run("contract", "--kind", "golden-masters", "--ecosystem", "maven", "--name", GM,
        "--application", "qits-projects", "--from", tree.toString(), "--version", V);

    assertEquals("unchanged since 2026.930.1\n", run.out());
    assertFalse(store.paths().stream().anyMatch(p -> p.startsWith("PUT")));
  }

  @Test
  void anNpmContractCarriesAGeneratedManifestAndTheTree() throws Exception {
    Harness.Run run = cli.run("contract", "--kind", "golden-masters", "--ecosystem", "npm", "--name", NPM,
        "--application", "qits-projects", "--from", tree.toString(), "--version", V);

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    JsonNode document = new ObjectMapper().readTree(put("/artifacts/npm/npm/" + NPM).body());
    byte[] tarball = Base64.getDecoder().decode(
        document.path("_attachments").path(NPM + "-" + V + ".tgz").path("data").asText());
    List<Archives.Entry> entries = Archives.readTarGz(tarball, "t");
    assertEquals(List.of("package/golden-masters/index.json", "package/golden-masters/states/a.json",
        "package/package.json"), entries.stream().map(Archives.Entry::name).toList());
    JsonNode manifest = new ObjectMapper().readTree(entries.get(2).bytes());
    assertEquals(NPM, manifest.path("name").asText());
    assertEquals(V, manifest.path("version").asText());
    assertEquals("golden-masters/", manifest.path("files").get(0).asText());
    assertFalse(store.paths().stream().anyMatch(p -> p.contains("dist-tags")), "a contract moves no main tag");
  }

  @Test
  void aPactNeedsItsProvider() {
    Harness.Run run = cli.run("contract", "--kind", "pacts", "--ecosystem", "maven", "--name",
        "eu.wohlben.qits:qits-workspaces-pacts-qits-projects", "--application", "qits-workspaces", "--from",
        tree.toString(), "--version", V);

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--provider is required"), run.err());
  }

  // --- contract-docs -----------------------------------------------------------------------------

  private void newest(String type, String name, String version) {
    store.on("GET", "/artifacts/content-hashes/" + type + "/" + name + "/-/newest",
        StubStore.Reply.of(200, "{\"version\":\"" + version + "\",\"contentHash\":null}"));
  }

  private Harness.Run contractDocs() {
    return cli.run("contract-docs", "--application", "qits-projects", "--from", tree.toString(), "--package",
        "maven=" + GM, "--package", "npm=" + NPM, "--version", V, "--meta", "git.commit.hash=deadbeef");
  }

  @Test
  void contractDocsPublishWhenOnePackageIsAtTheReleaseVersion() throws Exception {
    newest("maven", GM, "2026.930.1");
    newest("npm", NPM, V);

    Harness.Run run = contractDocs();

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    StubStore.Request docs = put(DOCS);
    assertEquals("deadbeef", docs.header("X-Artifacts-Meta-git.commit.hash"));
    List<Archives.Entry> entries = Archives.readTarGz(docs.body(), "bundle");
    assertEquals(List.of("contracts.json", "golden-masters/index.json", "golden-masters/states/a.json"),
        entries.stream().map(Archives.Entry::name).toList());
    JsonNode contracts = new ObjectMapper().readTree(entries.get(0).bytes());
    assertEquals(1, contracts.path("formatVersion").asInt());
    assertEquals("qits-projects", contracts.path("application").asText());
    assertEquals(V, contracts.path("version").asText());
    JsonNode maven = contracts.path("packages").get(0);
    assertEquals("maven", maven.path("ecosystem").asText());
    assertEquals(GM, maven.path("coordinate").asText());
    assertEquals("2026.930.1", maven.path("version").asText());
    assertFalse(maven.path("published").asBoolean());
    assertTrue(contracts.path("packages").get(1).path("published").asBoolean());
  }

  @Test
  void contractDocsSubmitNothingWhenNoPackageMoved() {
    newest("maven", GM, "2026.930.1");
    newest("npm", NPM, "2026.930.1");

    Harness.Run run = contractDocs();

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("unchanged\n", run.out());
    assertFalse(store.paths().contains("PUT " + DOCS));
  }

  @Test
  void contractDocsRefuseAPackageWithNoVersionAtAll() {
    newest("maven", GM, V);

    Harness.Run run = contractDocs();

    assertEquals(ExitCode.POLICY, run.code());
    assertFalse(store.paths().contains("PUT " + DOCS));
  }

  @Test
  void contractDocsStopOnA5xx() {
    newest("maven", GM, V);
    store.on("GET", "/artifacts/content-hashes/npm/" + NPM + "/-/newest", StubStore.Reply.of(503, "down"));

    Harness.Run run = contractDocs();

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertFalse(store.paths().contains("PUT " + DOCS));
  }

  @Test
  void contractDocsWithEntitiesPublishesEvenWhenNoPackageMoved() throws Exception {
    newest("maven", GM, "2026.930.1");
    newest("npm", NPM, "2026.930.1");
    Path entities = work.resolve("entities.json");
    Files.writeString(entities, "{\"qits-projects.Foo\":{\"technical\":true}}\n");

    Harness.Run run = cli.run("contract-docs", "--application", "qits-projects", "--from", tree.toString(),
        "--package", "maven=" + GM, "--package", "npm=" + NPM, "--version", V, "--meta",
        "git.commit.hash=deadbeef", "--entities", entities.toString());

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    StubStore.Request docs = put(DOCS);
    List<Archives.Entry> entries = Archives.readTarGz(docs.body(), "bundle");
    assertEquals(List.of("contracts.json", "entities.json", "golden-masters/index.json",
        "golden-masters/states/a.json"), entries.stream().map(Archives.Entry::name).toList());
    Archives.Entry entitiesEntry = entries.stream().filter(e -> e.name().equals("entities.json")).findFirst()
        .orElseThrow();
    assertArrayEquals(Files.readAllBytes(entities), entitiesEntry.bytes());
    JsonNode contracts = new ObjectMapper().readTree(
        entries.stream().filter(e -> e.name().equals("contracts.json")).findFirst().orElseThrow().bytes());
    assertFalse(contracts.path("packages").get(0).path("published").asBoolean());
    assertFalse(contracts.path("packages").get(1).path("published").asBoolean());
  }

  @Test
  void contractDocsWithAMissingEntitiesFileIsRefused() {
    newest("maven", GM, "2026.930.1");
    newest("npm", NPM, "2026.930.1");
    Path missing = work.resolve("no-such-entities.json");

    Harness.Run run = cli.run("contract-docs", "--application", "qits-projects", "--from", tree.toString(),
        "--package", "maven=" + GM, "--package", "npm=" + NPM, "--version", V, "--entities", missing.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains(missing.toString()), run.err());
    assertFalse(store.paths().contains("PUT " + DOCS));
  }

  // --- docs submit --openapi ---------------------------------------------------------------------

  @Test
  void docsSubmitOpenapiPublishesAOneEntryBundle() throws IOException {
    Path openapi = work.resolve("openapi.yaml");
    Files.writeString(openapi, "openapi: 3.0.3\n");
    String path = "/artifacts/docs/docs/@apidocs/qits-projects/-/" + V;
    store.on("PUT", path, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":15}"));

    Harness.Run run = cli.run("docs", "submit", "--site", "@apidocs/qits-projects", "--version", V, "--openapi",
        openapi.toString());

    assertEquals(ExitCode.OK, run.code(), run.err());
    List<Archives.Entry> entries = Archives.readTarGz(put(path).body(), "bundle");
    assertEquals(1, entries.size());
    assertEquals("openapi.yml", entries.get(0).name());
    assertEquals("openapi: 3.0.3\n", new String(entries.get(0).bytes(), StandardCharsets.UTF_8));
  }

  @Test
  void docsSubmitOpenapiAndArchiveAreExclusive() throws IOException {
    Path openapi = Files.writeString(work.resolve("openapi.json"), "{}");

    Harness.Run run = cli.run("docs", "submit", "--site", "@apidocs/qits-projects", "--version", V, "--openapi",
        openapi.toString(), "--archive", openapi.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("exclusive"), run.err());
  }

  @Test
  void docsSubmitOpenapiRefusesAnEmptyFile() throws IOException {
    Path openapi = Files.writeString(work.resolve("openapi.json"), "");

    Harness.Run run = cli.run("docs", "submit", "--site", "@apidocs/qits-projects", "--version", V, "--openapi",
        openapi.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("is empty"), run.err());
  }
}
