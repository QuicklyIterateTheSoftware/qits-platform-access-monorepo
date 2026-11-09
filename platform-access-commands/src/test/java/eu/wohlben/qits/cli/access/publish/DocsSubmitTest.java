package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The docs wire, which is the policy's fourth case and the only surface in it: the store explodes a
 * bundle into per-file blobs and keeps no archive digest, so an occupied version cannot be verified
 * at all and the WARN is what stops that reading as a verification.
 *
 * <p>Note the two path facts the fleet's curl invocations carry and a client must reproduce: the docs
 * wire has a repository segment ({@code docs}) that the sbom and daemon wires do not, and the site is
 * a two-segment name like {@code @apidocs/qits-ci} sent with a real slash.
 */
class DocsSubmitTest {

  private static final String PATH = "/artifacts/docs/docs/@apidocs/qits-ci/-/2026.906.1";
  /** The site's listing, which --if-changed reads before it decides. */
  private static final String LISTING = "/artifacts/docs/docs/@apidocs/qits-ci";

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path archive;

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    archive = work.resolve("apidocs.tgz");
    Files.write(archive, new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0});
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void anAbsentVersionIsPublishedWithItsMetadataHeaders() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":120}"));

    Harness.Run run =
        cli.run(
            "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "2026.906.1", "--archive",
            archive.toString(), "--meta", "git.commit.hash=deadbeef", "--meta",
            "git.repository.name=qits-ci");

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("published the docs bundle @apidocs/qits-ci@2026.906.1"), run.err());
    assertTrue(run.errContains("1 files, 120 bytes"), run.err());
    StubStore.Request request = store.lastRequest();
    assertEquals("deadbeef", request.header("X-Artifacts-Meta-git.commit.hash"));
    assertEquals("qits-ci", request.header("X-Artifacts-Meta-git.repository.name"));
    // A gzipped tar, said as such: the media type qits-artifacts records for putDocs.
    assertEquals("application/gzip", request.header("Content-Type"));
  }

  @Test
  void anOccupiedVersionSkipsAndSaysOutLoudThatNothingWasCompared() {
    store.on("PUT", PATH, StubStore.Reply.of(409, "docs versions are immutable"));
    store.on(
        "GET",
        PATH,
        StubStore.Reply.of(
            200, "{\"fileCount\":3,\"totalBytes\":900,\"publishedAt\":\"2026-09-06T10:00:00Z\"}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("WARN:"), run.err());
    assertTrue(run.errContains("keeps no archive digest"), run.err());
    assertTrue(run.errContains("3 files, 900 bytes"), run.err());
    assertEquals(List.of("PUT " + PATH, "GET " + PATH), store.paths());
  }

  @Test
  void anOccupiedVersionWhoseProbeAlsoFailsIsStillSuccessBecauseTheConflictProvedItIsThere() {
    store.on("PUT", PATH, StubStore.Reply.of(409, "docs versions are immutable"));
    store.on("GET", PATH, StubStore.Reply.of(500, "no"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("not even its shape could be read"), run.err());
  }

  @Test
  void aStoreThatFailsExitsTwo() {
    store.on("PUT", PATH, StubStore.Reply.of(503, "away"));
    assertEquals(ExitCode.TRANSPORT, submit().code());
  }

  @Test
  void aMetadataValueCarryingANewlineIsRefusedRatherThanSentAsTwoHeaders() {
    Harness.Run run =
        cli.run(
            "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "1", "--archive",
            archive.toString(), "--meta", "k=a\r\nX-Evil: 1");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("control character"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  // --- --dir (qits-1948) ------------------------------------------------------------------------

  @Test
  void aDirectoryIsPackedWithItsFilesAtTheBundleRoot() throws IOException {
    Path freezes = tree(work.resolve("freezes"), "manifest.json", "{}", "pages/index.html", "<p>");
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":2,\"totalBytes\":5}"));

    Harness.Run run = submitWith("--dir", freezes.toString());

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals(List.of("PUT " + PATH), store.paths());
    List<Archives.Entry> entries = Archives.readTarGz(store.lastRequest().body(), "the PUT body");
    assertEquals(List.of("manifest.json", "pages/index.html"), entries.stream().map(Archives.Entry::name).toList());
    assertEquals("<p>", new String(entries.get(1).bytes(), StandardCharsets.UTF_8));
    assertEquals("application/gzip", store.lastRequest().header("Content-Type"));
    // Without --if-changed nothing is hashed and the stdout answer stays empty, as it always was.
    assertNull(store.lastRequest().header("X-Artifacts-Meta-content.hash"));
    assertEquals("", run.out());
  }

  @Test
  void twoPacksOfOneTreeAreByteIdenticalWhateverTheCreationOrderAndMtimes() throws IOException {
    Path first = tree(work.resolve("one"), "b/z.txt", "z", "a.txt", "a", "manifest.json", "{}");
    Path second = tree(work.resolve("two"), "manifest.json", "{}", "a.txt", "a", "b/z.txt", "z");
    Files.setLastModifiedTime(second.resolve("a.txt"), FileTime.from(Instant.parse("2001-02-03T04:05:06Z")));
    Files.setLastModifiedTime(first.resolve("b/z.txt"), FileTime.from(Instant.parse("2031-02-03T04:05:06Z")));

    List<Archives.Entry> one = DocsContent.pack(first, skipped -> {});
    List<Archives.Entry> two = DocsContent.pack(second, skipped -> {});

    assertArrayEquals(Archives.writeTarGz(one), Archives.writeTarGz(two));
    assertEquals(DocsContent.hash(one), DocsContent.hash(two));
  }

  @Test
  void theHashIsTheSha256OfEachPathNulItsDigestLfSortedByPath() throws Exception {
    List<Archives.Entry> files = List.of(
        new Archives.Entry("pages/index.html", "<p>".getBytes(StandardCharsets.UTF_8)),
        new Archives.Entry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8)));

    String expected = "manifest.json\0" + sha256Hex("{}".getBytes(StandardCharsets.UTF_8)) + "\n"
        + "pages/index.html\0" + sha256Hex("<p>".getBytes(StandardCharsets.UTF_8)) + "\n";

    assertEquals("sha256:" + sha256Hex(expected.getBytes(StandardCharsets.UTF_8)), DocsContent.hash(files));
    assertEquals(DocsContent.hash(files), DocsContent.hash(List.of(files.get(1), files.get(0))));
  }

  @Test
  void anArchiveHashesAsTheDirectoryItWasPackedFromLeadingDotSlashAndAll() throws IOException {
    Path freezes = tree(work.resolve("freezes"), "manifest.json", "{}", "pages/index.html", "<p>");
    Path packed = work.resolve("freezes.tgz");
    Files.write(packed, Archives.writeTarGz(List.of(
        new Archives.Entry("./manifest.json", "{}".getBytes(StandardCharsets.UTF_8)),
        new Archives.Entry("./pages/index.html", "<p>".getBytes(StandardCharsets.UTF_8)))));

    assertEquals(DocsContent.hash(DocsContent.pack(freezes, skipped -> {})),
        DocsContent.hash(DocsContent.ofArchive(packed)));
  }

  @Test
  void aSymbolicLinkUnderTheDirectoryIsSkippedNeverFollowed() throws IOException {
    Path freezes = tree(work.resolve("freezes"), "manifest.json", "{}");
    Path outside = tree(work.resolve("outside"), "secret.txt", "s");
    Files.createSymbolicLink(freezes.resolve("leak.txt"), outside.resolve("secret.txt"));
    Files.createSymbolicLink(freezes.resolve("leaks"), outside);
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":2}"));

    Harness.Run run = submitWith("--dir", freezes.toString());

    assertEquals(ExitCode.OK, run.code(), run.err());
    List<Archives.Entry> entries = Archives.readTarGz(store.lastRequest().body(), "the PUT body");
    assertEquals(List.of("manifest.json"), entries.stream().map(Archives.Entry::name).toList());
    assertTrue(run.errContains("skipped leak.txt (a symbolic link)"), run.err());
    assertTrue(run.errContains("skipped leaks (a symbolic link)"), run.err());
  }

  @Test
  void anEmptyDirectoryIsRefusedNamingIt() throws IOException {
    Path empty = Files.createDirectories(work.resolve("freezes/only-a-folder")).getParent();

    Harness.Run run = submitWith("--dir", empty.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains(empty.toString()), run.err());
    assertTrue(run.errContains("holds no regular file"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  @Test
  void aMissingDirectoryAndAFileAreRefusedNamingThePath() throws IOException {
    Path missing = work.resolve("nowhere");
    Harness.Run absent = submitWith("--dir", missing.toString());
    assertEquals(ExitCode.POLICY, absent.code());
    assertTrue(absent.errContains(missing + ": no such directory"), absent.err());

    Harness.Run file = submitWith("--dir", archive.toString());
    assertEquals(ExitCode.POLICY, file.code());
    assertTrue(file.errContains(archive + " is not a directory"), file.err());
    assertTrue(store.requests().isEmpty());
  }

  @Test
  void exactlyOneSourceIsRequired() throws IOException {
    Path freezes = tree(work.resolve("freezes"), "manifest.json", "{}");

    Harness.Run two = submitWith("--archive", archive.toString(), "--dir", freezes.toString());
    assertEquals(ExitCode.POLICY, two.code());
    assertTrue(two.errContains("--archive, --openapi and --dir are exclusive"), two.err());

    Harness.Run none = submitWith();
    assertEquals(ExitCode.POLICY, none.code());
    assertTrue(none.errContains("--archive, --openapi or --dir is required"), none.err());
    assertTrue(store.requests().isEmpty());
  }

  // --- --if-changed (qits-1948) ------------------------------------------------------------------

  @Test
  void theSameHashOnTheNewestVersionIsUnchangedAndNothingIsPut() throws IOException {
    Path freezes = tree(work.resolve("freezes"), "manifest.json", "{}");
    String hash = DocsContent.hash(DocsContent.pack(freezes, skipped -> {}));
    store.on("GET", LISTING, StubStore.Reply.of(200, listing("2026.905.3", hash)));

    Harness.Run run = submitWith("--dir", freezes.toString(), "--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("unchanged since 2026.905.3", run.out().strip());
    assertEquals(List.of("GET " + LISTING), store.paths());
  }

  @Test
  void aDifferentHashPublishesAndRecordsTheNewOne() throws IOException {
    Path freezes = tree(work.resolve("freezes"), "manifest.json", "{}");
    String hash = DocsContent.hash(DocsContent.pack(freezes, skipped -> {}));
    store.on("GET", LISTING, StubStore.Reply.of(200, listing("2026.905.3", "sha256:" + "d".repeat(64))));
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":2}"));

    Harness.Run run = submitWith("--dir", freezes.toString(), "--if-changed", "--meta", "git.commit.hash=deadbeef");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published 2026.906.1", run.out().strip());
    assertTrue(run.errContains("the content changed since 2026.905.3"), run.err());
    assertTrue(run.errContains("published the docs bundle @apidocs/qits-ci@2026.906.1"), run.err());
    assertEquals(List.of("GET " + LISTING, "PUT " + PATH), store.paths());
    assertEquals(hash, store.lastRequest().header("X-Artifacts-Meta-content.hash"));
    assertEquals("deadbeef", store.lastRequest().header("X-Artifacts-Meta-git.commit.hash"));
  }

  @Test
  void aNewestVersionWithNoHashPublishes() throws IOException {
    store.on("GET", LISTING, StubStore.Reply.of(200,
        "{\"name\":\"@apidocs/qits-ci\",\"versions\":[{\"version\":\"2026.905.3\",\"metadata\":"
            + "{\"git.branch.name\":\"main\"}},{\"version\":\"2026.905.2\"}]}"));
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":8}"));

    Harness.Run run = submitWith("--archive", tarball().toString(), "--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published 2026.906.1", run.out().strip());
    assertTrue(run.errContains("records no content.hash"), run.err());
  }

  @Test
  void anAbsentSitePublishesItsFirstVersion() throws IOException {
    Path spec = work.resolve("openapi.yml");
    Files.writeString(spec, "openapi: 3.0.0\n");
    store.on("GET", LISTING, StubStore.Reply.of(404, "no such docs site"));
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":15}"));

    Harness.Run run = submitWith("--openapi", spec.toString(), "--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published 2026.906.1", run.out().strip());
    assertTrue(run.errContains("first publish"), run.err());
    // --openapi hashes its one entry, as it is packed.
    assertEquals(DocsContent.hash(List.of(DocsCommand.SubmitCommand.openApiEntry(spec))),
        store.lastRequest().header("X-Artifacts-Meta-content.hash"));
  }

  @Test
  void anOccupiedVersionUnderIfChangedKeepsItsWarnAndSaysPublished() throws IOException {
    store.on("GET", LISTING, StubStore.Reply.of(404, "no such docs site"));
    store.on("PUT", PATH, StubStore.Reply.of(409, "docs versions are immutable"));
    store.on("GET", PATH, StubStore.Reply.of(200, "{\"fileCount\":1,\"totalBytes\":8,\"publishedAt\":\"x\"}"));

    Harness.Run run = submitWith("--archive", tarball().toString(), "--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertTrue(run.errContains("WARN:"), run.err());
    assertEquals("published 2026.906.1", run.out().strip());
  }

  @Test
  void aListingThatIsNotAnAnswerExitsTwoAndPutsNothing() throws IOException {
    for (StubStore.Reply reply : List.of(
        StubStore.Reply.of(503, "away"),
        StubStore.Reply.of(401, "who are you"),
        StubStore.Reply.of(200, "<html>a proxy page</html>"),
        StubStore.Reply.of(200, "{\"name\":\"x\"}"))) {
      store.on("GET", LISTING, reply);

      Harness.Run run = submitWith("--archive", tarball().toString(), "--if-changed");

      assertEquals(ExitCode.TRANSPORT, run.code(), reply + ": " + run.err());
      assertTrue(run.errContains("the store failed to answer"), run.err());
      assertEquals("", run.out());
    }
    assertFalse(store.paths().contains("PUT " + PATH), store.paths().toString());
  }

  @Test
  void anExplicitContentHashIsRefusedWithIfChanged() {
    Harness.Run run = submitWith("--archive", archive.toString(), "--if-changed", "--meta",
        "Content.Hash=sha256:" + "d".repeat(64));

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--meta content.hash cannot be given with --if-changed"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  @Test
  void anExplicitContentHashWithoutIfChangedIsJustMetadata() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":8}"));

    Harness.Run run = submitWith("--archive", archive.toString(), "--meta", "content.hash=by-hand");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("by-hand", store.lastRequest().header("X-Artifacts-Meta-content.hash"));
  }

  private Harness.Run submitWith(String... source) {
    List<String> argv = new java.util.ArrayList<>(
        List.of("docs", "submit", "--site", "@apidocs/qits-ci", "--version", "2026.906.1"));
    argv.addAll(List.of(source));
    return cli.run(argv.toArray(String[]::new));
  }

  /** A real one-file bundle, for the --if-changed runs that read an --archive's files to hash them. */
  private Path tarball() throws IOException {
    Path tarball = work.resolve("bundle.tgz");
    Files.write(tarball, Archives.writeTarGz(List.of(
        new Archives.Entry("index.html", "<p>".getBytes(StandardCharsets.UTF_8)))));
    return tarball;
  }

  /** The store's listing, newest first, the newest carrying {@code hash}. */
  private static String listing(String newest, String hash) {
    return "{\"name\":\"@apidocs/qits-ci\",\"versions\":[{\"version\":\"" + newest + "\",\"metadata\":"
        + "{\"content.hash\":\"" + hash + "\",\"git.branch.name\":\"main\"}},"
        + "{\"version\":\"2026.905.2\",\"metadata\":{}}]}";
  }

  /** A directory of {@code path, content} pairs, written in the order given. */
  private static Path tree(Path root, String... pairs) throws IOException {
    Files.createDirectories(root);
    for (int i = 0; i < pairs.length; i += 2) {
      Path file = root.resolve(pairs[i]);
      Files.createDirectories(file.getParent());
      Files.writeString(file, pairs[i + 1]);
    }
    return root;
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private Harness.Run submit() {
    return cli.run(
        "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "2026.906.1", "--archive",
        archive.toString());
  }
}
