package eu.wohlben.qits.cli.access.egress;

import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.session.Times;
import eu.wohlben.qits.cli.session.Credential;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code qits egress forward}: a local TCP listener whose every connection is carried, byte for
 * byte, over a WebSocket of its own to qits-egress's forward door (epic qits-2525). What a tool
 * writes to the listener is the HTTP proxy protocol, and qits-egress hands it to its proxy as it
 * is; nothing here reads it.
 * <p>
 * <b>Never through a proxy.</b> The client is built with {@link HttpClient.Builder#NO_PROXY}, not
 * with {@code CompanionProxy} and not from {@code HTTPS_PROXY}: inside a container that variable
 * names this very listener, and dialling through it would loop.
 * <p>
 * <b>Back-pressure both ways.</b> The next chunk is read from the TCP client only once the last
 * one is sent, and the next frame is asked for ({@link WebSocket#request(long)}) only once the
 * last one is written to the TCP client.
 * <p>
 * <b>Either end closes both.</b> EOF from the TCP client sends a close frame; a close frame, an
 * error or a drop from the socket closes the TCP connection.
 * <p>
 * <b>A refused upgrade ends one connection.</b> A 401 or 403 (or anything else that is not an
 * upgrade) closes that TCP client and says so in one stderr line with the status; the listener
 * goes on. Nothing printed ever carries the bearer.
 */
public final class EgressForward {

    static final int CHUNK = 64 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /** How long a close frame this side sent waits for the other side's before the socket is dropped. */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(10);

    private final Credential credential;
    private final URI uri;
    private final InetSocketAddress listen;
    private final PrintStream log;
    private final Clock clock;
    private final HttpClient http;
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "qits-egress-forward");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Pipe> open = ConcurrentHashMap.newKeySet();

    private volatile ServerSocket server;
    private volatile boolean stopRequested;

    public EgressForward(Credential credential, URI uri, InetSocketAddress listen, PrintStream log, Clock clock) {
        this.credential = credential;
        this.uri = uri;
        this.listen = listen;
        this.log = log;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY)
                .executor(threads)
                .build();
    }

    /** Takes the listening address; a second call does nothing. */
    public void bind() throws CliFailure {
        if (server != null) {
            return;
        }
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(listen, 128);
            server = socket;
        } catch (IOException e) {
            throw new CliFailure("Cannot listen on " + address(listen) + ": " + describe(e), CliFailure.FAILED);
        }
    }

    /** The port listened on, once bound: what {@code --listen 127.0.0.1:0} was given. */
    public int port() {
        return server.getLocalPort();
    }

    /** Accepts connections until {@link #stop()}, then answers 0. */
    public int run() throws CliFailure {
        bind();
        ServerSocket listening = server;
        log("listening on " + address(new InetSocketAddress(listening.getInetAddress(), listening.getLocalPort()))
                + ", forwarding to " + uri);
        while (!stopRequested) {
            Socket client;
            try {
                client = listening.accept();
            } catch (IOException e) {
                if (stopRequested || listening.isClosed()) {
                    break;
                }
                // Out of descriptors, say: this connection is lost, the listener is not.
                log("cannot accept a connection: " + describe(e));
                pause();
                continue;
            }
            try {
                threads.execute(() -> carry(client));
            } catch (RuntimeException rejected) {
                close(client);
            }
        }
        log("stopped");
        return 0;
    }

    /** Ends the listener and every connection, from any thread. */
    public void stop() {
        stopRequested = true;
        ServerSocket listening = server;
        if (listening != null) {
            try {
                listening.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        open.forEach(Pipe::abort);
        http.shutdownNow();
        threads.shutdownNow();
    }

    /** One TCP connection, from the upgrade to the close of both ends. Runs on its own thread. */
    private void carry(Socket client) {
        String from = address(client.getRemoteSocketAddress());
        Pipe pipe;
        try {
            client.setTcpNoDelay(true);
            pipe = new Pipe(client);
        } catch (IOException e) {
            close(client);
            return;
        }
        open.add(pipe);
        try {
            String bearer;
            try {
                bearer = credential.bearer();
            } catch (CliFailure failure) {
                log("closed the connection from " + from + ": " + SafeText.line(failure.getMessage()));
                return;
            }
            WebSocket socket;
            try {
                socket = http.newWebSocketBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer)
                        .buildAsync(uri, pipe)
                        .get();
            } catch (ExecutionException failed) {
                log(refusal(failed.getCause()) + "; closed the connection from " + from);
                return;
            } catch (IllegalArgumentException notUsable) {
                log("'" + uri + "' is not a usable WebSocket address; closed the connection from " + from);
                return;
            }
            pipe.pumpToSocket(socket);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } finally {
            pipe.abort();
            open.remove(pipe);
        }
    }

    private String refusal(Throwable cause) {
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof WebSocketHandshakeException handshake && handshake.getResponse() != null) {
            return "the forward door refused the upgrade with " + handshake.getResponse().statusCode();
        }
        return "cannot reach " + uri + ": " + describe(cause);
    }

    /**
     * The two directions of one connection. The TCP client's bytes go out on the calling thread
     * ({@link #pumpToSocket}); the socket's frames come in through the listener methods and are
     * written on the client's executor, one at a time.
     */
    private final class Pipe implements WebSocket.Listener {
        private final Socket client;
        private final InputStream in;
        private final OutputStream out;
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private volatile WebSocket socket;

        Pipe(Socket client) throws IOException {
            this.client = client;
            this.in = client.getInputStream();
            this.out = client.getOutputStream();
        }

        /** TCP to socket, until EOF or either end goes; then closes both. */
        void pumpToSocket(WebSocket socket) throws InterruptedException {
            this.socket = socket;
            byte[] buffer = new byte[CHUNK];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n > 0) {
                        // Waiting for the send keeps the buffer ours and is the back-pressure.
                        socket.sendBinary(ByteBuffer.wrap(buffer, 0, n), true).get();
                    }
                }
            } catch (IOException | ExecutionException ended) {
                // The TCP client reset, or the socket closed or failed: both ends close below.
            }
            if (!closed.isDone()) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                    closed.get(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException | IllegalStateException | CompletionException gone) {
                    // The other side never answered the close; abort ends it.
                }
            }
        }

        void abort() {
            WebSocket current = socket;
            if (current != null) {
                current.abort();
            }
            closed.complete(null);
            close(client);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            CompletableFuture<Void> written = new CompletableFuture<>();
            try {
                threads.execute(() -> {
                    try {
                        if (data.hasArray()) {
                            out.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
                        } else {
                            byte[] copy = new byte[data.remaining()];
                            data.get(copy);
                            out.write(copy);
                        }
                        out.flush();
                        written.complete(null);
                        webSocket.request(1);
                    } catch (IOException e) {
                        written.completeExceptionally(e);
                        // The TCP client is gone: closing it ends the read in pumpToSocket, which closes the socket.
                        close(client);
                    }
                });
            } catch (RuntimeException rejected) {
                written.completeExceptionally(rejected);
                close(client);
            }
            return written;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // The door speaks binary only; text would be bytes in an unknown encoding.
            close(client);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            close(client);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(null);
            close(client);
        }
    }

    private static void close(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }

    private void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopRequested = true;
        }
    }

    static String address(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            String host = inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "no detail";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + SafeText.line(message) + ")");
    }

    private void log(String message) {
        log.println(Times.stamp(clock.instant()) + " qits egress forward: " + message);
        log.flush();
    }
}
