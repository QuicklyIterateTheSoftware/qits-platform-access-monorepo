package eu.wohlben.qits.cli.access.egress;

import eu.wohlben.qits.cli.access.FakeSocketServer;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits egress forward} (qits-2655) against {@link FakeSocketServer} standing in for
 * qits-egress's forward door: bytes both ways, the bearer, a refused upgrade, both ends closing.
 */
@Timeout(60)
class EgressForwardTest {

    private static final String TOKEN = "forward-token-3f9a";
    private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

    private FakeSocketServer door;
    private EgressForward forward;
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private CompletableFuture<Integer> running;

    @BeforeEach
    void start() throws Exception {
        door = new FakeSocketServer();
        forward = new EgressForward(() -> TOKEN, URI.create("ws://127.0.0.1:" + door.port() + ForwardCommand.DOOR),
                new InetSocketAddress("127.0.0.1", 0), new PrintStream(log, true, StandardCharsets.UTF_8),
                new FakeTime(T0));
        forward.bind();
        running = onOwnThread(forward::run);
    }

    /**
     * Runs on a thread of its own, never on the common pool. The JDK completes every WebSocket
     * handshake on the common ForkJoinPool (HttpClientImpl.sendAsync's whenCompleteAsync on
     * ASYNC_POOL), so a listener or sender parked there, beside other classes' blocked tasks in the
     * gate's full run, left no worker for the upgrade: every connection hung (qits-2655).
     */
    private static <T> CompletableFuture<T> onOwnThread(java.util.concurrent.Callable<T> work) {
        CompletableFuture<T> done = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("egress-forward-test").start(() -> {
            try {
                done.complete(work.call());
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    @AfterEach
    void stop() throws Exception {
        forward.stop();
        assertThat(running.get(10, TimeUnit.SECONDS)).isZero();
        door.close();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", forward.port());
        socket.setSoTimeout(20_000);
        return socket;
    }

    private String log() {
        return log.toString(StandardCharsets.UTF_8);
    }

    @Test
    void bytesGoBothWaysAndTheUpgradeCarriesTheBearer() throws Exception {
        CompletableFuture<byte[]> heard = new CompletableFuture<>();
        door.scripts.add(c -> {
            c.accept();
            heard.complete(c.awaitBinary());
            c.sendBinary("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            c.hold();
        });

        try (Socket client = connect()) {
            client.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] answer = client.getInputStream().readNBytes("HTTP/1.1 200 OK\r\n\r\n".length());

            assertThat(new String(heard.get(10, TimeUnit.SECONDS), StandardCharsets.US_ASCII))
                    .isEqualTo("CONNECT example.com:443 HTTP/1.1\r\n\r\n");
            assertThat(new String(answer, StandardCharsets.US_ASCII)).isEqualTo("HTTP/1.1 200 OK\r\n\r\n");
        }
        assertThat(door.upgrades).singleElement().satisfies(u -> {
            assertThat(u.path()).isEqualTo("/egress/api/forward");
            assertThat(u.authorization()).isEqualTo("Bearer " + TOKEN);
        });
        assertThat(log()).doesNotContain(TOKEN);
    }

    @Test
    void aMebibyteRoundTripsWhole() throws Exception {
        door.scripts.add(c -> {
            c.accept();
            c.echoBinary();
        });
        byte[] payload = new byte[1024 * 1024];
        new Random(2655).nextBytes(payload);

        try (Socket client = connect()) {
            CompletableFuture<Void> sent = onOwnThread(() -> {
                OutputStream out = client.getOutputStream();
                // Several writes, so the forwarder sends many frames and the door echoes each.
                for (int i = 0; i < payload.length; i += 100_000) {
                    out.write(payload, i, Math.min(100_000, payload.length - i));
                }
                out.flush();
                return null;
            });
            byte[] back = client.getInputStream().readNBytes(payload.length);
            sent.get(10, TimeUnit.SECONDS);

            assertThat(back.length).isEqualTo(payload.length);
            assertThat(Arrays.equals(back, payload)).as("the echoed bytes are the bytes sent").isTrue();
        }
    }

    @Test
    void aMebibyteFromTheDoorInOneMessageArrivesWhole() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        new Random(25).nextBytes(payload);
        door.scripts.add(c -> {
            c.accept();
            c.sendBinary(payload);
            c.hold();
        });

        try (Socket client = connect()) {
            byte[] back = client.getInputStream().readNBytes(payload.length);
            assertThat(Arrays.equals(back, payload)).isTrue();
        }
    }

    @Test
    void theDoorClosingClosesTheTcpClient() throws Exception {
        door.scripts.add(c -> {
            c.accept();
            c.sendBinary("bye".getBytes(StandardCharsets.US_ASCII));
            c.close(1000, "");
        });

        try (Socket client = connect()) {
            InputStream in = client.getInputStream();
            assertThat(new String(in.readNBytes(3), StandardCharsets.US_ASCII)).isEqualTo("bye");
            assertThat(in.read()).as("EOF once the door closed").isEqualTo(-1);
        }
    }

    @Test
    void theTcpClientsEofClosesTheSocket() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        door.scripts.add(c -> {
            c.accept();
            if (c.awaitBinary() != null && c.awaitBinary() == null) {
                closed.countDown();
            }
        });

        try (Socket client = connect()) {
            client.getOutputStream().write("x".getBytes(StandardCharsets.US_ASCII));
            client.shutdownOutput();
            assertThat(closed.await(10, TimeUnit.SECONDS)).as("the door got a close frame").isTrue();
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test
    void aRefusedUpgradeClosesOnlyThatClientAndTheListenerGoesOn() throws Exception {
        door.scripts.add(c -> c.refuse(401, "{\"message\":\"no bearer\"}"));
        door.scripts.add(c -> {
            c.accept();
            c.echoBinary();
        });

        try (Socket refused = connect()) {
            refused.getOutputStream().write("CONNECT a:443 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertThat(refused.getInputStream().read()).as("the refused client is closed").isEqualTo(-1);
        }
        assertThat(log().lines().filter(l -> l.contains("refused the upgrade")).toList()).singleElement()
                .satisfies(line -> assertThat(line).contains("401").contains("qits egress forward:"));

        try (Socket next = connect()) {
            next.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(next.getInputStream().readNBytes(4), StandardCharsets.US_ASCII)).isEqualTo("ping");
        }
        assertThat(running).isNotDone();
        assertThat(log()).doesNotContain(TOKEN);
    }

    @Test
    void twoClientsAtOnceGetASocketEach() throws Exception {
        door.everyConnection = c -> {
            c.accept();
            c.echoBinary();
        };
        try (Socket a = connect(); Socket b = connect()) {
            a.getOutputStream().write("aaaa".getBytes(StandardCharsets.US_ASCII));
            b.getOutputStream().write("bbbb".getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(b.getInputStream().readNBytes(4), StandardCharsets.US_ASCII)).isEqualTo("bbbb");
            assertThat(new String(a.getInputStream().readNBytes(4), StandardCharsets.US_ASCII)).isEqualTo("aaaa");
        }
        assertThat(door.upgrades).hasSize(2);
    }

    // The command's own reading of its flags; the listener is the class above.

    record Result(int exit, String out, String err) {
    }

    private static Result command(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        FakeTime time = new FakeTime(T0);
        CliContext context = new CliContext(env, InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, s -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anUnreadableListenIsAUsageError() {
        Result r = command(Map.of("QITS_TOKEN", TOKEN, "QITS_DOMAIN", "example.test"),
                "egress", "forward", "--listen", "3128");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--listen takes host:port");
    }

    @Test
    void withNoBearerItFailsAtOnceWithOneLineAndNeverListens(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) {
        // No QITS_TOKEN and no session file: nothing to send, so it refuses before it binds.
        Result r = command(Map.of("XDG_CONFIG_HOME", home.toString()),
                "egress", "forward", "--listen", "127.0.0.1:0", "--url", "ws://127.0.0.1:1" + ForwardCommand.DOOR);

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err().lines().toList()).singleElement().satisfies(line -> assertThat(line).contains("qits login"));
        assertThat(r.err()).doesNotContain("listening");
    }

    @Test
    void theDefaultDoorIsTheEgressVhostAndUrlsAreTurnedIntoSocketAddresses() throws Exception {
        assertThat(ForwardCommand.socketUri("https://egress.qits.example.test/egress/api/forward"))
                .hasToString("wss://egress.qits.example.test/egress/api/forward");
        assertThat(ForwardCommand.socketUri("ws://127.0.0.1:1/x")).hasToString("ws://127.0.0.1:1/x");
        assertThat(ForwardCommand.listenAddress("127.0.0.1:3128").getPort()).isEqualTo(3128);
        assertThat(ForwardCommand.listenAddress("[::1]:0").getAddress().getHostAddress()).contains(":");
        assertThat(eu.wohlben.qits.cli.access.platform.PlatformUrls.egress(null,
                Map.of("QITS_TOKEN", TOKEN, "QITS_DOMAIN", "example.test"), null))
                .isEqualTo("https://egress.qits.example.test");
    }
}
