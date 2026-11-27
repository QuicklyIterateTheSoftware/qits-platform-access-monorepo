package eu.wohlben.qits.cli.access.networkaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CompanionProxy;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits network-access up|down|status} as a person types them (epic qits-1977), against a
 * fake qits-network-access and a fake {@code docker} first on PATH that records every argv. The
 * exact sequence of docker calls is the behaviour, and the publish argument is a security
 * requirement: the companion's proxy is unauthenticated, so it must reach host loopback only.
 */
class NetworkAccessCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
    private static final String KEY = "AAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc=";
    private static final String PEER_ID = "00000000-0000-4000-8000-000000000001";
    private static final String REF = "registry.qits.wohlben.eu/qits/qits-network-access:2026.1110.191501";
    private static final List<String> AS_COMPANION = List.of("--user", "65532:65532", "--entrypoint",
            "/work/companion/qits-access-companion", "-v", "qits-network-access-companion:/state", REF);
    private static final List<String> LINK_MTU_ENV = List.of("-e", "QITS_COMPANION_LINK_MTU=1380");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GIT_ORIGIN = "https://githost.qits.example.test";

    private static final String COMPANION = """
            {"image":"registry.qits.wohlben.eu/qits/qits-network-access","version":"2026.1110.191501",
             "entrypoint":"/work/companion/qits-access-companion","user":"65532:65532"}""";
    private static final String PEER = """
            {"id":"%s","kind":"DEVICE","name":"laptop","publicKey":"%s","tunnelIp":"100.96.0.10",
             "status":"ACTIVE"}""".formatted(PEER_ID, KEY);
    private static final String TUNNEL = """
            {"peerId":"%s","tunnelIp":"100.96.0.10","edge":{"publicKey":"AAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc=",
             "publicAddress":"46.224.171.33","listenPort":47812,"dnsAddress":"100.96.0.1",
             "tunnelCidr":"100.96.0.0/24","mtu":1380,"domain":"wohlben.eu"}}""".formatted(PEER_ID);

    /** Records argv (tab-separated) and answers each docker call the commands make. */
    private static final String FAKE_DOCKER = """
            #!/bin/sh
            line="$1"; shift
            for a in "$@"; do line="$line$(printf '\\t')$a"; done
            printf '%s\\n' "$line" >> "$FAKE_DOCKER_LOG"
            set -- $line
            last=""; for a in "$@"; do last="$a"; done
            # exec, so the deadline's kill lands on the sleep itself and leaves nothing behind.
            if [ -n "$FAKE_DOCKER_HANG" ] && [ "$1" = "$FAKE_DOCKER_HANG" ]; then exec sleep 30; fi
            case "$1" in
              version) exit "${FAKE_DOCKER_VERSION_EXIT:-0}" ;;
              pull) if [ "$FAKE_DOCKER_PULL_DENIED" = "realm" ]; then
                      echo "Error response from daemon: error from registry: client credentials required" >&2; exit 1
                    elif [ -n "$FAKE_DOCKER_PULL_DENIED" ]; then
                      echo "Error response from daemon: unauthorized: authentication required" >&2; exit 1; fi ;;
              run) case "$last" in
                     keygen) echo "created /state/private.key" >&2; echo "$FAKE_DOCKER_KEY" ;;
                     configure) cat > "$FAKE_DOCKER_STDIN" ;;
                     run) echo 0123456789ab ;;
                   esac ;;
              inspect) echo running ;;
              rm) if [ -n "$FAKE_DOCKER_NO_CONTAINER" ]; then
                    echo "Error response from daemon: No such container: x" >&2; exit 1; fi ;;
              volume) echo qits-network-access-companion ;;
            esac
            exit 0
            """;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private Path log;
    private Path stdin;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, FAKE_DOCKER);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        log = home.resolve("docker.log");
        stdin = home.resolve("configure.stdin");
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("FAKE_DOCKER_LOG", log.toString());
        env.put("FAKE_DOCKER_STDIN", stdin.toString());
        env.put("FAKE_DOCKER_KEY", KEY);
        env.put("QITS_NETWORK_ACCESS_URL", platform.url());
        // Git's --global config is a scratch file, never the person's (or the build container's).
        env.put("GIT_CONFIG_GLOBAL", home.resolve("gitconfig").toString());
        env.put("QITS_GIT_HOST_URL", GIT_ORIGIN + "/git/qits");
        // The healthz probe goes where this names the companion: the fake, here.
        env.put(CompanionProxy.VARIABLE, platform.url());
        new SessionFile(home.resolve(".config/qits")).write(new Session(idp.url(), "qits-cli",
                FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(),
                T0.plus(Duration.ofDays(30))));
        platform.answer("GET", "/network-access/api/companion", COMPANION);
        platform.answer("POST", "/network-access/api/peers", 201, PEER);
        platform.answer("GET", "/network-access/api/peers/" + PEER_ID + "/tunnel", TUNNEL);
        platform.answer("POST", "/network-access/api/peers/" + PEER_ID + "/revoke", PEER.replace("ACTIVE", "REVOKED"));
        platform.answer("GET", "/healthz", "{\"status\":\"ok\",\"peerId\":\"" + PEER_ID + "\"}");
        // A canned answer, not a real probe: a test must not depend on what network this machine
        // happens to run on. Tests that care about failure replace this with their own.
        NetworkAccessCommand.UpCommand.LINK_MTU = (host, port) -> new LinkMtu.Measurement.Measured("eth0", 1380);
    }

    @AfterEach
    void stop() {
        NetworkAccessCommand.DOCKER_WITHIN = Duration.ofSeconds(60);
        NetworkAccessCommand.UpCommand.LINK_MTU = LinkMtu.DEFAULT;
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, s -> { });
        int exit = TestCli.execute(context, args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private List<List<String>> dockerCalls() throws Exception {
        if (!Files.exists(log)) {
            return List.of();
        }
        return Files.readAllLines(log).stream().map(l -> Arrays.asList(l.split("\t"))).toList();
    }

    private static List<String> args(Object... parts) {
        List<String> all = new java.util.ArrayList<>();
        for (Object part : parts) {
            if (part instanceof List<?> list) {
                list.forEach(p -> all.add((String) p));
            } else {
                all.add((String) part);
            }
        }
        return all;
    }

    private JsonNode accessJson() throws Exception {
        return JSON.readTree(home.resolve(".config/qits/access.json").toFile());
    }

    @Test
    void upPullsRegistersConfiguresAndStartsTheCompanion() throws Exception {
        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(dockerCalls()).containsExactly(
                List.of("version"),
                List.of("pull", REF),
                List.of("volume", "create", "qits-network-access-companion"),
                args("run", "--rm", AS_COMPANION, "keygen"),
                args("run", "--rm", "-i", AS_COMPANION, "configure"),
                List.of("rm", "-f", "qits-network-access-companion"),
                args("run", "-d", "--name", "qits-network-access-companion", "--restart", "unless-stopped",
                        "-p", "127.0.0.1:47813:47813", LINK_MTU_ENV, AS_COMPANION, "run"));
        // The tunnel answer reaches the companion's configure whole.
        assertThat(JSON.readTree(stdin.toFile())).isEqualTo(JSON.readTree(TUNNEL));
        FakePlatform.Request created = platform.requests("POST", "/network-access/api/peers").getFirst();
        assertThat(JSON.readTree(created.body())).isEqualTo(JSON.readTree(
                "{\"name\":\"laptop\",\"publicKey\":\"" + KEY + "\"}"));
        assertThat(created.authorization()).startsWith("Bearer " + FakeIdp.SECRET);
        assertThat(accessJson()).isEqualTo(JSON.readTree("""
                {"peerId":"%s","proxy":"%s","image":"registry.qits.wohlben.eu/qits/qits-network-access",
                 "version":"2026.1110.191501"}""".formatted(PEER_ID, platform.url())));
        assertThat(Files.getPosixFilePermissions(home.resolve(".config/qits/access.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(result.out()).contains("peer " + PEER_ID + " (laptop), tunnel IP 100.96.0.10")
                .contains("platform calls now go through the companion")
                .contains("browsers: set the proxy auto-config URL to " + platform.url() + "/proxy.pac")
                .contains("companion mtu: host link eth0 1380");
    }

    /**
     * qits-2373: the companion computes {@code min(edge MTU, link MTU - 80)} itself; this command
     * hands it only the raw host link MTU, measured on the host because inside any container every
     * interface reads the runtime's default regardless of what actually carries its packets.
     */
    @Test
    void upMeasuresTheHostLinkTowardTheEdgeAndPassesItToTheRunContainerOnly() throws Exception {
        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).as(result.err()).isZero();
        List<List<String>> calls = dockerCalls();
        assertThat(calls).filteredOn(argv -> "run".equals(argv.getFirst()) && "keygen".equals(argv.getLast()))
                .allSatisfy(argv -> assertThat(argv).doesNotContain("QITS_COMPANION_LINK_MTU"));
        assertThat(calls).filteredOn(argv -> "run".equals(argv.getFirst()) && "configure".equals(argv.getLast()))
                .allSatisfy(argv -> assertThat(argv).doesNotContain("QITS_COMPANION_LINK_MTU"));
        assertThat(calls.getLast()).isEqualTo(args("run", "-d", "--name", "qits-network-access-companion",
                "--restart", "unless-stopped", "-p", "127.0.0.1:47813:47813", LINK_MTU_ENV, AS_COMPANION, "run"));
        assertThat(result.out()).contains("companion mtu: host link eth0 1380");
    }

    /** Nothing can be measured: `up` warns and still starts the companion, without the variable. */
    @Test
    void aLinkMtuMeasurementFailureStartsWithoutTheVariableAndWarns() throws Exception {
        NetworkAccessCommand.UpCommand.LINK_MTU =
                (host, port) -> new LinkMtu.Measurement.Unmeasurable("no route to host");

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(dockerCalls().getLast()).isEqualTo(args("run", "-d", "--name",
                "qits-network-access-companion", "--restart", "unless-stopped", "-p", "127.0.0.1:47813:47813",
                AS_COMPANION, "run"));
        assertThat(dockerCalls()).noneMatch(argv -> argv.stream().anyMatch(a -> a.contains("QITS_COMPANION_LINK_MTU")));
        assertThat(result.err()).contains("warning: could not measure the host link toward 46.224.171.33: "
                + "no route to host; the companion uses the edge's MTU");
        assertThat(result.out()).doesNotContain("companion mtu:");
    }

    /**
     * THE SECURITY REQUIREMENT (desk, 2026-11-10): the companion's CONNECT proxy listens
     * unauthenticated on 0.0.0.0:47813 inside its container, so the one docker run that publishes
     * a port publishes it on host loopback, exactly, and nothing else ever binds it.
     */
    @Test
    void theCompanionIsPublishedOnHostLoopbackOnly() throws Exception {
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();

        List<List<String>> publishing = dockerCalls().stream().filter(argv -> argv.contains("-p")
                || argv.stream().anyMatch(a -> a.startsWith("--publish") || a.startsWith("-p") && a.length() > 2
                        || a.contains("47813"))).toList();
        assertThat(publishing).hasSize(1);
        List<String> run = publishing.getFirst();
        int flag = run.indexOf("-p");
        assertThat(flag).isPositive();
        assertThat(run.get(flag + 1)).isEqualTo("127.0.0.1:47813:47813");
        assertThat(run).filteredOn(a -> a.contains("47813")).containsExactly("127.0.0.1:47813:47813");
        assertThat(run).noneMatch(a -> a.contains("0.0.0.0")).noneMatch(a -> a.equals("47813:47813"))
                .noneMatch(a -> a.equals("-P") || a.equals("--publish-all") || a.equals("--network=host")
                        || a.equals("host"));
        assertThat(NetworkAccessCommand.PUBLISH).isEqualTo("127.0.0.1:47813:47813");
    }

    @Test
    void aSecondUpKeepsTheActivePeerAndRegistersNothing() throws Exception {
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        platform.answer("GET", "/network-access/api/peers/" + PEER_ID, PEER);

        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();

        assertThat(platform.requests("POST", "/network-access/api/peers")).hasSize(1);
        assertThat(platform.requests("GET", "/network-access/api/peers/" + PEER_ID)).hasSize(1);
        assertThat(accessJson().path("peerId").asText()).isEqualTo(PEER_ID);
    }

    @Test
    void aRevokedOrUnknownPeerIsRegisteredAgain() throws Exception {
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        platform.answer("GET", "/network-access/api/peers/" + PEER_ID, PEER.replace("ACTIVE", "REVOKED"));
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();

        platform.answer("GET", "/network-access/api/peers/" + PEER_ID, 404, "{\"message\":\"no such peer\"}");
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();

        assertThat(platform.requests("POST", "/network-access/api/peers")).hasSize(3);
    }

    /** A device already active names the page that revokes it: the service's own host (qits-2361). */
    @Test
    void aDeviceAlreadyActivePointsAtTheNetworkAccessPage() throws Exception {
        platform.answer("POST", "/network-access/api/peers", 409, "{\"message\":\"laptop is already an active device\"}");

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).isNotZero();
        assertThat(result.err()).contains("Revoke that device at " + platform.url() + "/, or pass another --name.");
    }

    /** Every docker call runs under a deadline (qits-2213): one that hangs fails `up` instead of hanging it. */
    @Test
    void aDockerCallThatHangsPastItsDeadlineFailsUp() throws Exception {
        NetworkAccessCommand.DOCKER_WITHIN = Duration.ofSeconds(1);
        env.put("FAKE_DOCKER_HANG", "volume");

        long started = System.nanoTime();
        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("`docker volume create qits-network-access-companion` did not finish "
                + "within 1 s");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        assertThat(dockerCalls()).noneMatch(argv -> argv.getFirst().equals("run"));
        assertThat(platform.requests("POST", "/network-access/api/peers")).isEmpty();
    }

    /** The driver's belts refuse a served entrypoint docker would read as a flag, before anything runs. */
    @Test
    void aServedEntrypointThatIsAFlagIsRefusedBeforeAnyRun() throws Exception {
        platform.answer("GET", "/network-access/api/companion",
                COMPANION.replace("/work/companion/qits-access-companion", "--privileged"));

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("Invalid entrypoint").contains("--privileged");
        assertThat(dockerCalls()).noneMatch(argv -> argv.getFirst().equals("run"));
        assertThat(platform.requests("POST", "/network-access/api/peers")).isEmpty();
    }

    @Test
    void theDefaultNameIsTheShortHostNameAsALabel() {
        assertThat(NetworkAccessCommand.label("Jans-Laptop.fritz.box")).isEqualTo("jans-laptop");
        assertThat(NetworkAccessCommand.label("DESKTOP_42")).isEqualTo("desktop-42");
        assertThat(NetworkAccessCommand.label("--")).isEqualTo("workstation");
        assertThat(NetworkAccessCommand.label("a".repeat(80))).hasSize(63);
        assertThat(NetworkAccessCommand.defaultName()).matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?");
    }

    @Test
    void aNameThatIsNoLabelIsRefused() throws Exception {
        Result result = run("network-access", "up", "--name", "Not A Label");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(dockerCalls()).isEmpty();
    }

    @Test
    void withoutDockerUpSaysToInstallIt() throws Exception {
        env.put("PATH", home.resolve("nothing-here").toString());
        Result missing = run("network-access", "up", "--name", "laptop");
        assertThat(missing.exit()).isEqualTo(3);
        assertThat(missing.err()).contains("install or start Docker");

        env.put("PATH", home.resolve("bin") + ":/usr/bin:/bin");
        env.put("FAKE_DOCKER_VERSION_EXIT", "1");
        Result stopped = run("network-access", "up", "--name", "laptop");
        assertThat(stopped.exit()).isEqualTo(3);
        assertThat(stopped.err()).contains("install or start Docker");
        assertThat(platform.requests("GET", "/network-access/api/companion")).isEmpty();
    }

    @Test
    void aDeniedPullSaysToLogIn() throws Exception {
        env.put("FAKE_DOCKER_PULL_DENIED", "yes");

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.err()).contains("The registry refused to pull " + REF
                + ": run `qits registry-login --configure` so Docker gets its login from your qits session, "
                + "then run `qits network-access up` again.");
        assertThat(platform.requests("POST", "/network-access/api/peers")).isEmpty();
    }

    /** The edge's token realm says {@code client credentials required} when Docker sent no login. */
    @Test
    void thePlatformRealmsMissingCredentialsSaysToConfigure() throws Exception {
        env.put("FAKE_DOCKER_PULL_DENIED", "realm");

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.err()).contains("run `qits registry-login --configure` so Docker gets its login");
        assertThat(platform.requests("POST", "/network-access/api/peers")).isEmpty();
    }

    @Test
    void aCompanionThatNeverAnswersHealthyLeavesCallsDirect() throws Exception {
        platform.answer("GET", "/healthz", 503, "{\"status\":\"no handshake\"}");

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("did not answer healthy within 20 s");
        assertThat(accessJson().has("proxy")).isFalse();
        assertThat(accessJson().path("peerId").asText()).isEqualTo(PEER_ID);
        assertThat(time.instant()).isAfterOrEqualTo(T0.plusSeconds(20));
    }

    @Test
    void theTokenHomeAndInPlatformRefuseWithExit2() throws Exception {
        for (String[] other : List.of(new String[]{"QITS_TOKEN", "a-token"}, new String[]{"QITS_PLATFORM", "true"})) {
            Map<String, String> saved = Map.copyOf(env);
            env.put(other[0], other[1]);
            env.put("QITS_DOMAIN", "wohlben.eu");
            for (String[] command : List.of(new String[]{"up"}, new String[]{"down", "--revoke"}, new String[]{"status"})) {
                String[] argv = new String[command.length + 1];
                argv[0] = "network-access";
                System.arraycopy(command, 0, argv, 1, command.length);
                Result result = run(argv);
                assertThat(result.exit()).as(String.join(" ", argv)).isEqualTo(2);
                assertThat(result.err()).contains("host containers reach the private edge over the overlay");
            }
            env.clear();
            env.putAll(saved);
        }
        assertThat(dockerCalls()).isEmpty();
        assertThat(platform.requests).isEmpty();
    }

    /** The docker commands a dry run prints, as a person would paste them: what `up` runs, in order. */
    private static final List<String> UP_LINES = List.of(
            "docker pull " + REF,
            "docker volume create qits-network-access-companion",
            "docker run --rm " + String.join(" ", AS_COMPANION) + " keygen",
            "docker run --rm -i " + String.join(" ", AS_COMPANION) + " configure",
            "docker rm -f qits-network-access-companion",
            "docker run -d --name qits-network-access-companion --restart unless-stopped -p 127.0.0.1:47813:47813 "
                    + String.join(" ", AS_COMPANION) + " run",
            "up also passes -e QITS_COMPANION_LINK_MTU=<MTU of the host interface toward the edge> to the "
                    + "run container");

    /** The token home a workspace is: QITS_TOKEN, and no session file the command may read. */
    private void inTheTokenHome() throws Exception {
        Files.delete(home.resolve(".config/qits/t.json"));
        env.put("QITS_TOKEN", "a-token");
        env.put("QITS_DOMAIN", "wohlben.eu");
    }

    /** qits-2295: how a workspace live-checks `up`. It reads GET /companion and does nothing else. */
    @Test
    void upDryRunInTheTokenHomePrintsWhatUpWouldRunAndRunsNothing() throws Exception {
        inTheTokenHome();

        Result result = run("network-access", "up", "--name", "laptop", "--dry-run");

        assertThat(result.exit()).as(result.err()).isZero();
        List<String> lines = result.out().lines().toList();
        assertThat(lines.getFirst()).startsWith("dry run: nothing ran;").contains("laptop");
        assertThat(lines.subList(1, lines.size())).containsExactlyElementsOf(UP_LINES);
        assertThat(dockerCalls()).isEmpty();
        assertThat(platform.requests("GET", "/network-access/api/companion")).hasSize(1)
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer a-token"));
        assertThat(platform.requests).noneMatch(r -> r.path().contains("/peers"));
        assertThat(home.resolve(".config/qits/access.json")).doesNotExist();
    }

    @Test
    void upDryRunNeedsNoDocker() throws Exception {
        env.put("PATH", home.resolve("nothing-here").toString());

        Result result = run("network-access", "up", "--name", "laptop", "--dry-run");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out().lines().skip(1).toList()).containsExactlyElementsOf(UP_LINES);
        assertThat(platform.requests).noneMatch(r -> r.path().contains("/peers"));
        assertThat(home.resolve(".config/qits/access.json")).doesNotExist();
    }

    @Test
    void upDryRunRefusesAServedEntrypointThatIsAFlag() throws Exception {
        inTheTokenHome();
        platform.answer("GET", "/network-access/api/companion",
                COMPANION.replace("/work/companion/qits-access-companion", "--privileged"));

        Result result = run("network-access", "up", "--name", "laptop", "--dry-run");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("Invalid entrypoint").contains("--privileged");
        assertThat(result.out()).doesNotContain("docker ");
    }

    @Test
    void upDryRunStillChecksTheName() throws Exception {
        inTheTokenHome();

        assertThat(run("network-access", "up", "--name", "Not A Label", "--dry-run").exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void downDryRunInTheTokenHomePrintsWhatDownWouldRunAndRevokesNothing() throws Exception {
        inTheTokenHome();

        Result revoke = run("network-access", "down", "--revoke", "--dry-run");

        assertThat(revoke.exit()).as(revoke.err()).isZero();
        assertThat(revoke.out().lines().toList()).hasSize(3).endsWith(
                "docker rm -f qits-network-access-companion",
                "docker volume rm qits-network-access-companion");
        assertThat(revoke.out()).startsWith("dry run: nothing ran;");

        Result plain = run("network-access", "down", "--dry-run");
        assertThat(plain.exit()).as(plain.err()).isZero();
        assertThat(plain.out().lines().toList()).hasSize(2).endsWith("docker rm -f qits-network-access-companion");

        assertThat(dockerCalls()).isEmpty();
        assertThat(platform.requests).isEmpty();
        assertThat(home.resolve(".config/qits/access.json")).doesNotExist();
    }

    /** A workstation's dry run leaves its access.json as it was: down --revoke would delete it. */
    @Test
    void downDryRunOnAWorkstationKeepsTheState() throws Exception {
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        Files.delete(log);
        String before = Files.readString(home.resolve(".config/qits/access.json"));

        assertThat(run("network-access", "down", "--revoke", "--dry-run").exit()).isZero();

        assertThat(dockerCalls()).isEmpty();
        assertThat(platform.requests("POST", "/network-access/api/peers/" + PEER_ID + "/revoke")).isEmpty();
        assertThat(Files.readString(home.resolve(".config/qits/access.json"))).isEqualTo(before);
    }

    @Test
    void aDryRunInsideThePlatformStillRefusesWithExit2() throws Exception {
        env.put("QITS_PLATFORM", "true");
        env.put("QITS_DOMAIN", "wohlben.eu");

        for (String command : List.of("up", "down")) {
            Result result = run("network-access", command, "--dry-run");
            assertThat(result.exit()).as(command).isEqualTo(2);
            assertThat(result.err()).contains("host containers reach the private edge over the overlay");
        }
        assertThat(dockerCalls()).isEmpty();
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void downStopsTheCompanionAndKeepsThePeerForTheNextUp() throws Exception {
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        Files.delete(log);

        Result result = run("network-access", "down");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(dockerCalls()).containsExactly(List.of("rm", "-f", "qits-network-access-companion"));
        assertThat(accessJson().has("proxy")).isFalse();
        assertThat(accessJson().path("peerId").asText()).isEqualTo(PEER_ID);
        assertThat(platform.requests("POST", "/network-access/api/peers/" + PEER_ID + "/revoke")).isEmpty();
        assertThat(result.out()).contains("platform calls go direct");
    }

    @Test
    void downRevokeRevokesTheDeviceAndDeletesTheKeyAndTheState() throws Exception {
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        Files.delete(log);

        Result result = run("network-access", "down", "--revoke");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(platform.requests("POST", "/network-access/api/peers/" + PEER_ID + "/revoke")).hasSize(1)
                .allSatisfy(r -> assertThat(r.authorization()).startsWith("Bearer " + FakeIdp.SECRET));
        assertThat(dockerCalls()).containsExactly(
                List.of("rm", "-f", "qits-network-access-companion"),
                List.of("volume", "rm", "qits-network-access-companion"));
        assertThat(home.resolve(".config/qits/access.json")).doesNotExist();
        assertThat(result.out()).contains("peer " + PEER_ID + " revoked");
    }

    private List<String> git(String... args) throws Exception {
        List<String> argv = new java.util.ArrayList<>(List.of("git", "config", "--file",
                home.resolve("gitconfig").toString()));
        argv.addAll(List.of(args));
        Process git = new ProcessBuilder(argv).start();
        List<String> values = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        git.waitFor();
        return values;
    }

    private List<String> gitProxy() throws Exception {
        return git("--get-all", "http." + GIT_ORIGIN + ".proxy");
    }

    /** What `qits git-login --configure` leaves: Git asks qits for the git host. */
    private void gitAsksQits() throws Exception {
        git("--add", "credential." + GIT_ORIGIN + ".helper", "");
        git("--add", "credential." + GIT_ORIGIN + ".helper", "!qits git-credential");
    }

    /** qits-2743: the git host is private, so a set-up Git follows the companion for that host alone. */
    @Test
    void upSendsASetUpGitThroughTheCompanionForTheGitHostOnly() throws Exception {
        gitAsksQits();

        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(gitProxy()).containsExactly(platform.url());
        assertThat(git("--get-regexp", "proxy")).hasSize(1);
        assertThat(result.out()).contains("git: " + GIT_ORIGIN + " goes through the companion");
    }

    @Test
    void aSecondUpFindsTheLineRightAndSaysNothingAboutGit() throws Exception {
        gitAsksQits();
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        String before = Files.readString(home.resolve("gitconfig"));

        Result again = run("network-access", "up", "--name", "laptop");

        assertThat(again.exit()).as(again.err()).isZero();
        assertThat(again.out()).doesNotContain("git:");
        assertThat(Files.readString(home.resolve("gitconfig"))).isEqualTo(before);
    }

    @Test
    void upLeavesAGitThatNeverAsksQitsAlone() throws Exception {
        Result result = run("network-access", "up", "--name", "laptop");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(home.resolve("gitconfig")).doesNotExist();
        assertThat(result.out()).doesNotContain("git:");
    }

    @Test
    void downTakesGitOffTheCompanionAgain() throws Exception {
        gitAsksQits();
        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();

        Result result = run("network-access", "down");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(gitProxy()).isEmpty();
        assertThat(git("--get-all", "credential." + GIT_ORIGIN + ".helper")).containsExactly("", "!qits git-credential");
        assertThat(result.out()).contains("git: " + GIT_ORIGIN + " goes direct again");
    }

    @Test
    void aCompanionThatNeverAnswersHealthyLeavesGitAlone() throws Exception {
        gitAsksQits();
        platform.answer("GET", "/healthz", 503, "{\"status\":\"starting\"}");

        assertThat(run("network-access", "up", "--name", "laptop").exit()).isEqualTo(1);

        assertThat(gitProxy()).isEmpty();
    }

    @Test
    void downWithNoContainerIsStillDone() throws Exception {
        env.put("FAKE_DOCKER_NO_CONTAINER", "yes");

        assertThat(run("network-access", "down").exit()).isZero();
    }

    @Test
    void statusIsHealthyOnlyWithAProxyThatAnswers200() throws Exception {
        Result before = run("network-access", "status");
        assertThat(before.exit()).isEqualTo(1);
        assertThat(before.out()).contains("not set up");

        assertThat(run("network-access", "up", "--name", "laptop").exit()).isZero();
        Files.delete(log);
        Result up = run("network-access", "status");
        assertThat(up.exit()).as(up.out() + up.err()).isZero();
        assertThat(up.out()).contains("peer       " + PEER_ID).contains("container  running")
                .contains("health     HTTP 200")
                .contains("browsers: set the proxy auto-config URL to " + platform.url() + "/proxy.pac");
        assertThat(dockerCalls()).containsExactly(
                List.of("inspect", "-f", "{{.State.Status}}", "qits-network-access-companion"));

        platform.answer("GET", "/healthz", 503, "{}");
        assertThat(run("network-access", "status").exit()).isEqualTo(1);

        assertThat(run("network-access", "down").exit()).isZero();
        Result down = run("network-access", "status");
        assertThat(down.exit()).isEqualTo(1);
        assertThat(down.out()).contains("platform calls go direct")
                .contains("browsers: set the proxy auto-config URL to " + platform.url() + "/proxy.pac");
    }
}
