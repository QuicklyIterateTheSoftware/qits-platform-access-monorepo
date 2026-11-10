package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Which calls go through the network-access companion, and what happens when it does not answer (epic qits-1977). */
class CompanionProxyTest {

    private static final InetSocketAddress COMPANION = InetSocketAddress.createUnresolved("127.0.0.1", 47813);

    @TempDir
    Path home;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private CompanionProxy proxy(boolean answers, AtomicInteger probes) {
        return new CompanionProxy(COMPANION, ".qits.wohlben.eu", Set.of("projects.qits.override.example"),
                new PrintStream(err, true, StandardCharsets.UTF_8), companion -> {
                    probes.incrementAndGet();
                    return answers;
                });
    }

    private static boolean proxied(ProxySelector selector, String uri) {
        List<Proxy> chosen = selector.select(URI.create(uri));
        assertThat(chosen).hasSize(1);
        return chosen.getFirst().type() == Proxy.Type.HTTP;
    }

    @Test
    void onlyThePlatformsHostsOverHttpsAndWssGoThroughIt() {
        CompanionProxy selector = proxy(true, new AtomicInteger());

        assertThat(proxied(selector, "https://projects.qits.wohlben.eu/projects/api/projects")).isTrue();
        assertThat(proxied(selector, "wss://observability.qits.wohlben.eu/observability/api/live")).isTrue();
        assertThat(proxied(selector, "https://PROJECTS.QITS.WOHLBEN.EU./x")).isTrue();
        assertThat(selector.select(URI.create("https://ci.qits.wohlben.eu/x")).getFirst().address())
                .isEqualTo(COMPANION);

        assertThat(proxied(selector, "https://github.com/x")).isFalse();
        assertThat(proxied(selector, "https://qits.wohlben.eu.evil.example/x")).isFalse();
        assertThat(proxied(selector, "https://evilqits.wohlben.eu/x")).isFalse();
        // The companion serves CONNECT only, and a plain http call would be a forward-proxy request.
        assertThat(proxied(selector, "http://projects.qits.wohlben.eu/x")).isFalse();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void loopbackAndTheOverridesGoDirect() {
        CompanionProxy selector = proxy(true, new AtomicInteger());

        assertThat(proxied(selector, "https://127.0.0.1:47080/x")).isFalse();
        assertThat(proxied(selector, "https://localhost:8443/x")).isFalse();
        assertThat(proxied(selector, "https://[::1]:8443/x")).isFalse();
        assertThat(proxied(selector, "https://projects.qits.localhost/x")).isFalse();
        assertThat(proxied(selector, "https://projects.qits.override.example/x")).isFalse();
    }

    @Test
    void aCompanionThatDoesNotAnswerSendsCallsDirectWithOneWarning() {
        AtomicInteger probes = new AtomicInteger();
        CompanionProxy selector = proxy(false, probes);

        assertThat(proxied(selector, "https://projects.qits.wohlben.eu/x")).isFalse();
        assertThat(proxied(selector, "https://ci.qits.wohlben.eu/x")).isFalse();
        assertThat(proxied(selector, "https://idp.qits.wohlben.eu/x")).isFalse();

        assertThat(err.toString(StandardCharsets.UTF_8))
                .isEqualTo(CompanionProxy.WARNING + System.lineSeparator());
        // One probe holds for a while: not one connect per call.
        assertThat(probes).hasValue(1);
        // A host that never goes through it never asks.
        proxy(false, probes).select(URI.create("https://github.com/x"));
        assertThat(probes).hasValue(1);
    }

    @Test
    void theRealProbeFindsAClosedPortAndFallsBack() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        Map<String, String> env = workstation();
        env.put(CompanionProxy.VARIABLE, "http://127.0.0.1:" + port);
        ProxySelector selector = CompanionProxy.fromEnvironment(env, new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(selector).isInstanceOf(CompanionProxy.class);
        assertThat(proxied(selector, "https://projects.qits.wohlben.eu/x")).isFalse();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("run `qits network-access up`");
    }

    @Test
    void theRealProbeKeepsALiveCompanion() throws Exception {
        try (ServerSocket open = new ServerSocket(0)) {
            Map<String, String> env = workstation();
            env.put(CompanionProxy.VARIABLE, "http://127.0.0.1:" + open.getLocalPort());
            ProxySelector selector = CompanionProxy.fromEnvironment(env, new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(proxied(selector, "https://projects.qits.wohlben.eu/x")).isTrue();
        }
    }

    @Test
    void accessJsonSwitchesItOnAndOffSwitchesItOff() throws Exception {
        Map<String, String> env = workstation();
        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isSameAs(CompanionProxy.NONE);

        Files.writeString(home.resolve("qits/access.json"),
                "{\"peerId\":\"p\",\"proxy\":\"http://127.0.0.1:47813\",\"image\":\"i\",\"version\":\"v\"}");
        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isInstanceOf(CompanionProxy.class);

        env.put(CompanionProxy.VARIABLE, "off");
        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isSameAs(CompanionProxy.NONE);
        env.put(CompanionProxy.VARIABLE, "OFF");
        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isSameAs(CompanionProxy.NONE);
    }

    @Test
    void downLeavesAPeerAndNoProxy() throws Exception {
        Map<String, String> env = workstation();
        Files.writeString(home.resolve("qits/access.json"), "{\"peerId\":\"p\",\"image\":\"i\",\"version\":\"v\"}");

        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isSameAs(CompanionProxy.NONE);
    }

    @Test
    void theTokenHomeAndInPlatformNeverUseIt() throws Exception {
        Map<String, String> env = workstation();
        env.put(CompanionProxy.VARIABLE, "http://127.0.0.1:47813");
        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isInstanceOf(CompanionProxy.class);

        Map<String, String> token = new HashMap<>(env);
        token.put("QITS_TOKEN", "a-token");
        token.put("QITS_DOMAIN", "wohlben.eu");
        assertThat(CompanionProxy.fromEnvironment(token, System.err)).isSameAs(CompanionProxy.NONE);

        Map<String, String> inside = new HashMap<>(env);
        inside.put("QITS_PLATFORM", "true");
        assertThat(CompanionProxy.fromEnvironment(inside, System.err)).isSameAs(CompanionProxy.NONE);
    }

    @Test
    void withoutASessionThereIsNoSuffixToMatch() {
        Map<String, String> env = new HashMap<>();
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put(CompanionProxy.VARIABLE, "http://127.0.0.1:47813");

        assertThat(CompanionProxy.fromEnvironment(env, System.err)).isSameAs(CompanionProxy.NONE);
    }

    @Test
    void theOverridesHostsComeFromQitsUrlVariables() throws Exception {
        Map<String, String> env = workstation();
        env.put(CompanionProxy.VARIABLE, "http://127.0.0.1:47813");
        env.put("QITS_URL_CI", "https://ci.qits.wohlben.eu");
        ProxySelector selector = CompanionProxy.fromEnvironment(env, System.err);

        assertThat(proxied(selector, "https://ci.qits.wohlben.eu/x")).isFalse();
    }

    @Test
    void theSuffixIsTheSessionsIdpHostWithoutIdp() throws Exception {
        workstation();
        assertThat(CompanionProxy.suffix(new SessionFile(home.resolve("qits")))).isEqualTo(".qits.wohlben.eu");
        assertThat(CompanionProxy.address("http://127.0.0.1:47813")).isEqualTo(COMPANION);
        assertThat(CompanionProxy.address("127.0.0.1:9")).isEqualTo(InetSocketAddress.createUnresolved("127.0.0.1", 9));
    }

    /** A workstation signed in at idp.qits.wohlben.eu, its config directory under the test's own. */
    private Map<String, String> workstation() throws Exception {
        new SessionFile(home.resolve("qits")).write(new Session("https://idp.qits.wohlben.eu/idp", "qits-cli",
                "access", Instant.parse("2100-01-01T00:00:00Z"), "refresh", Instant.parse("2100-01-01T00:00:00Z")));
        Map<String, String> env = new HashMap<>();
        env.put("XDG_CONFIG_HOME", home.toString());
        return env;
    }
}
