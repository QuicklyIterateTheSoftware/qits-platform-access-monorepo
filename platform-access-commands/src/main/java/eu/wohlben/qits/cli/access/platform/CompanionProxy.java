package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.networkaccess.AccessState;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.Mode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>The platform's hosts through the network-access companion</b> (epic qits-1977): the {@link
 * ProxySelector} every {@code HttpClient} of the commands is built with. On a workstation where
 * {@code qits network-access up} runs the companion, a call to {@code https://<app>.qits.<domain>}
 * is a CONNECT through it, so a private app answers as a public one does; {@code java.net.http}
 * sends CONNECT for https and wss alike, so the event stream and the WebSockets follow.
 * <p>
 * <b>Only a workstation, only the platform's hosts.</b> It is active when {@code access.json} names
 * a proxy, or when {@code QITS_NETWORK_ACCESS_PROXY} names one; {@code off} there switches it off.
 * The token home and the in-platform home never use it: a host container reaches the private edge
 * over the overlay. A host goes through it only when it ends in the session's platform suffix
 * ({@code .qits.<domain>}, the vhost base {@code PlatformEndpoints} builds every address from), and
 * the scheme is https or wss: the companion serves CONNECT and nothing else. Loopback and the hosts
 * of every {@code QITS_URL_*} override go direct, which keeps the tapes' fake platform and the pact
 * mocks out of it.
 * <p>
 * <b>A companion that does not answer is not an outage.</b> Before the first proxied call (and again
 * at most every {@link #RECHECK_NANOS}) the selector opens a TCP connection to the companion; when it
 * is refused, the call goes direct, and the process says so on stderr once. The public apps keep
 * working, and the private ones fail as they would without a companion at all. That is checked
 * before the call rather than retried after it so all five clients, the streams and the sockets get
 * it with no retry of their own.
 * <p>
 * Built at run time, on the first call, and never in a static initialiser: the native image would
 * otherwise freeze the build machine's environment into the binary ({@code --initialize-at-run-time},
 * as {@code Pkce} is).
 */
public final class CompanionProxy extends ProxySelector {

    /** The companion's address, which wins over access.json's; {@code off} switches the proxy off. */
    public static final String VARIABLE = "QITS_NETWORK_ACCESS_PROXY";

    /** Where {@code qits network-access up} publishes the companion: host loopback only. */
    public static final String DEFAULT_ADDRESS = "http://127.0.0.1:47813";

    static final String WARNING = "the network-access companion does not answer; run `qits network-access up`";

    /** How long a verdict on the companion holds before the next proxied call asks again. */
    static final long RECHECK_NANOS = 30_000_000_000L;

    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

    /** Never proxies: the selector of every home but a workstation with a companion. */
    static final ProxySelector NONE = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return DIRECT;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // Nothing was proxied.
        }
    };

    private static final Object LOCK = new Object();
    private static volatile ProxySelector process;
    private static volatile ProxySelector replaced;

    /** Whether the companion answers a TCP connect. A seam, so a test decides. */
    @FunctionalInterface
    interface Probe {
        boolean answers(InetSocketAddress companion);
    }

    private final InetSocketAddress companion;
    private final String suffix;
    private final Set<String> direct;
    private final PrintStream err;
    private final Probe probe;
    private final AtomicBoolean warned = new AtomicBoolean();
    private boolean answering;
    private long checkedAt;
    private boolean checked;

    CompanionProxy(InetSocketAddress companion, String suffix, Set<String> direct, PrintStream err, Probe probe) {
        this.companion = companion;
        this.suffix = suffix.toLowerCase(Locale.ROOT);
        this.direct = Set.copyOf(direct);
        this.err = err;
        this.probe = probe;
    }

    /**
     * This process's selector: built from its environment and its config directory on the first
     * call, then kept. The environment does not change while a process runs, and a person who runs
     * {@code up} in another terminal starts the next command after it.
     */
    public static ProxySelector selector() {
        ProxySelector test = replaced;
        if (test != null) {
            return test;
        }
        ProxySelector built = process;
        if (built == null) {
            synchronized (LOCK) {
                built = process;
                if (built == null) {
                    built = CliContext.inMcpService() ? NONE : fromEnvironment(System.getenv(), System.err);
                    process = built;
                }
            }
        }
        return built;
    }

    /** A test's own selector for this process, or null for the real one again. */
    static void replaceForTests(ProxySelector selector) {
        replaced = selector;
    }

    /**
     * The selector this environment asks for: {@link #NONE} outside a workstation, with {@code
     * QITS_NETWORK_ACCESS_PROXY=off}, with no companion named, or with no session to take the
     * platform's suffix from.
     */
    public static ProxySelector fromEnvironment(Map<String, String> env, PrintStream err) {
        if (Mode.of(env) != Mode.WORKSTATION) {
            return NONE;
        }
        String told = env.get(VARIABLE);
        if (told != null && told.strip().equalsIgnoreCase("off")) {
            return NONE;
        }
        SessionFile session = SessionFile.fromEnvironment(env);
        Optional<String> address = told != null && !told.isBlank() ? Optional.of(told.strip())
                : AccessState.readQuietly(session.directory()).map(AccessState::proxy);
        if (address.isEmpty() || address.get().isBlank()) {
            return NONE;
        }
        InetSocketAddress companion = address(address.get());
        String suffix = suffix(session);
        if (companion == null || suffix == null) {
            return NONE;
        }
        return new CompanionProxy(companion, suffix, overrideHosts(env), err, CompanionProxy::connects);
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("no address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = host(uri);
        if (!(scheme.equals("https") || scheme.equals("wss")) || host == null || loopback(host)
                || direct.contains(host) || !host.endsWith(suffix)) {
            return DIRECT;
        }
        if (!answering()) {
            return DIRECT;
        }
        return List.of(new Proxy(Proxy.Type.HTTP, companion));
    }

    /** The JDK's report that a proxied connect failed: the next call asks the companion again. */
    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        synchronized (this) {
            checked = false;
        }
    }

    private boolean answering() {
        synchronized (this) {
            long now = System.nanoTime();
            if (!checked || now - checkedAt > RECHECK_NANOS) {
                answering = probe.answers(companion);
                checkedAt = now;
                checked = true;
            }
            if (!answering && warned.compareAndSet(false, true)) {
                err.println(WARNING);
                err.flush();
            }
            return answering;
        }
    }

    /** {@code .qits.<domain>}, from the session's idp ({@code https://idp.qits.<domain>/idp}); null without one. */
    static String suffix(SessionFile session) {
        try {
            Optional<Session> read = session.read();
            if (read.isEmpty()) {
                return null;
            }
            String host = URI.create(read.get().idpUrl().strip()).getHost();
            if (host == null || !host.toLowerCase(Locale.ROOT).startsWith("idp.")) {
                return null;
            }
            String rest = host.substring("idp".length()).toLowerCase(Locale.ROOT);
            return rest.indexOf('.', 1) < 0 ? null : rest;
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** The host and port of {@code http://127.0.0.1:47813}; null when it names neither. */
    static InetSocketAddress address(String value) {
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort() == -1 ? 47813 : uri.getPort();
            return InetSocketAddress.createUnresolved(uri.getHost(), port);
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
    }

    private static Set<String> overrideHosts(Map<String, String> env) {
        Set<String> hosts = new HashSet<>();
        env.forEach((name, value) -> {
            if (name.startsWith("QITS_URL_") && value != null && !value.isBlank()) {
                try {
                    String host = URI.create(value.strip()).getHost();
                    if (host != null) {
                        hosts.add(normal(host));
                    }
                } catch (IllegalArgumentException notAnAddress) {
                    // Not an address; PlatformEndpoints says so when a command uses it.
                }
            }
        });
        return hosts;
    }

    private static String host(URI uri) {
        String host = uri.getHost();
        return host == null ? null : normal(host);
    }

    private static String normal(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    static boolean loopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("::1")
                || h.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean connects(InetSocketAddress companion) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(companion.getHostString(), companion.getPort()), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException refused) {
            return false;
        }
    }
}
