package eu.wohlben.qits.cli.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import eu.wohlben.qits.pact.consumer.GoldenMasters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * The fake platform the VHS tapes play against (epic qits-881): {@link FakePlatform} and {@link
 * FakeSocketServer} on fixed loopback ports, answering every integration a tape shows at once, for
 * as long as the process lives. {@code tapes/prepare.sh} starts it from the test class path the
 * Dockerfile's {@code tapes-kit} target exports, and points every {@code QITS_URL_<APP>} at it.
 * <p>
 * <b>Every answer is a recorded one where a recording exists</b>: the providers' golden masters,
 * the same jars the consumer pacts read, so a tape shows what the service really answers. What no
 * provider records — a stream's records, the release the checkout tape moves to, the bytes {@code
 * qits update} downloads — is a fixture under
 * {@code src/test/resources/tapes/}. Nothing here reads the clock, and the ports are fixed: a tape
 * shows neither a time nor a random port, so its text golden is the same on every play.
 * <p>
 * <b>A write answers 403</b>, as the platform answers a {@code qits:agent} token on an operator
 * door: the tapes show the refusal, never a change, and the fake keeps no state, so the order the
 * tapes play in does not matter.
 * <p>
 * One method per integration, so a new tape's answers have one obvious place.
 */
public final class FakePlatformMain {

    /** The HTTP edge: every service but observability's stream. {@code tapes/prepare.sh} names it too. */
    public static final int HTTP_PORT = 47080;
    /** qits-observability: its WebSocket stream and its records search, on one address. */
    public static final int SOCKET_PORT = 47081;

    static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    static final String PROJECT_SLUG = "contract-00000001";
    /** {@code contract-00000001-contract-00000001}, {@code contract-service}: the golden masters' repositories. */
    static final String WRAPPER_REPOSITORY = "00000000-0000-4000-8000-000000000002";
    static final String SERVICE_REPOSITORY = "00000000-0000-4000-8000-000000000005";
    /** {@code contract-service-rr-1} in the repository's recorded release requests. */
    static final String RELEASE_REQUEST = "00000000-0000-4000-8000-000000000004";
    /** {@code Faster builds}, the first idea of {@code a project with ideas}. */
    static final String IDEA = "00000000-0000-4000-8000-000000000003";
    static final String RUN = "00000000-0000-4000-8000-000000000004";
    /** The agent {@code qits wait} acts for: {@code QITS_AGENT_ID} in {@code tapes/prepare.sh}. */
    static final String AGENT = "00000000-0000-4000-8000-0000000000a1";
    /** The version the artifacts store's golden master names as the newest {@code qits}. */
    static final String RELEASED_QITS = "2100.1.0";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakePlatform platform;
    private final FakeSocketServer sockets;

    FakePlatformMain(int httpPort, int socketPort) throws IOException {
        this.platform = new FakePlatform(httpPort);
        this.sockets = new FakeSocketServer(socketPort);
    }

    public static void main(String[] args) throws Exception {
        int httpPort = args.length > 0 ? Integer.parseInt(args[0]) : HTTP_PORT;
        int socketPort = args.length > 1 ? Integer.parseInt(args[1]) : SOCKET_PORT;
        FakePlatformMain fake = new FakePlatformMain(httpPort, socketPort);
        try {
            fake.script();
        } catch (RuntimeException missing) {
            // A golden master the fake names is gone (a newer pin renamed its state, say): say so and
            // end, so prepare.sh fails rather than the tapes playing against half a platform.
            missing.printStackTrace();
            System.exit(1);
        }
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            fake.platform.close();
            fake.sockets.close();
            stopped.countDown();
        }));
        System.out.println("fake platform on " + fake.platform.url() + ", observability on " + fake.sockets.url());
        stopped.await();
    }

    void script() {
        projects();
        repositories();
        work();
        ideas();
        agentMcp();
        releaseRequests();
        ci();
        deployments();
        maintenance();
        users();
        rememberedDevices();
        workspaces();
        waits();
        events();
        checkoutDaemon();
        observe();
        targets();
        update();
        // What prepare.sh polls before it hands the shell to the tapes: last, so it answers only
        // once every other answer is in place.
        platform.answer("GET", "/tapes/ready", "{\"ready\":true}");
    }

    // --- qits-projects -----------------------------------------------------------------------------

    private void projects() {
        platform.answer("GET", "/projects/api/projects", projects("a project exists", "listProjects"));
        refuse("POST", "/projects/api/projects/" + PROJECT + "/front-desk/prompt");
    }

    private void repositories() {
        platform.answer("GET", "/projects/api/projects/" + PROJECT + "/repositories",
                projects("a project with 3 repositories", "listProjectRepositories"));
        refuse("POST", "/projects/api/projects/" + PROJECT + "/repositories");
    }

    private void work() {
        String work = projects("a project with work in every status", "listProjectWork");
        // `--project` goes into the path as it is given: the slug the screen's dropdown fills in, or the uuid.
        platform.answer("GET", "/projects/api/projects/" + PROJECT + "/work", work);
        platform.answer("GET", "/projects/api/projects/" + PROJECT_SLUG + "/work", work);
        refuse("POST", "/projects/api/work/" + PROJECT_SLUG + "-1/blocked");
    }

    private void ideas() {
        platform.answer("GET", "/projects/api/ideas", projects("a project with ideas", "listIdeas"));
        refuse("POST", "/projects/api/ideas/" + IDEA + "/archive");
    }

    /** No recording yet (AgentMcpContract's rows are pending): a fixture. */
    private void agentMcp() {
        String credentials = "/projects/api/agent-mcp-catalog/credentials";
        platform.answer("GET", credentials, fixture("agent-mcp-credentials.json"));
        refuse("DELETE", credentials + "/env.GITHUB_TOKEN");
    }

    private void releaseRequests() {
        platform.answer("GET", "/projects/api/projects/" + PROJECT + "/release-requests",
                projects("a project with release requests in every state", "listProjectReleaseRequests"));
        String requests = projects("a frozen release request with a queued next request", "listRepositoryReleaseRequests");
        for (String repository : List.of(WRAPPER_REPOSITORY, SERVICE_REPOSITORY)) {
            platform.answer("GET", "/projects/api/repositories/" + repository + "/release-requests", requests);
            refuse("POST", "/projects/api/repositories/" + repository + "/release-requests");
        }
    }

    // --- qits-ci, qits-maintenance, the idp ---------------------------------------------------------

    private void ci() {
        platform.answer("GET", "/ci/api/runs", golden("qits-ci-service", "qits-ci",
                "a repository with its 30 most recent runs", "listRuns"));
        refuse("POST", "/ci/api/runs/" + RUN + "/retry");
    }

    /** No recording of the settings doors yet (DeploymentsContract's rows are pending): a fixture. */
    private void deployments() {
        platform.answer("GET", "/deployments/api/settings", fixture("deployment-settings.json"));
        refuse("DELETE", "/deployments/api/settings/qits-ci/dev/WEBHOOK_URL");
    }

    private void maintenance() {
        String automations = "/maintenance/api/release-requests/" + RELEASE_REQUEST + "/automations";
        platform.answer("GET", automations, golden("qits-maintenance-service", "qits-maintenance",
                "a release request with automations", "listReleaseRequestAutomations"));
        refuse("POST", automations + "/cli-tapes/runs");
    }

    private void users() {
        platform.answer("GET", "/idp/api/users", golden("qits-idp-service", "qits-idp",
                "an admin signed in to the CLI", "listUsers"));
        refuse("DELETE", "/idp/api/users/jan");
    }

    /**
     * No recording at the pinned idp golden masters yet (IdpRememberedDevicesContract's rows are
     * pending): a fixture. The real idp refuses the tapes' token on both doors; the fake shows the
     * listing and refuses only the revoke.
     */
    private void rememberedDevices() {
        platform.answer("GET", "/idp/api/remembered-devices", fixture("remembered-devices.json"));
        refuse("DELETE", "/idp/api/remembered-devices/00000000-0000-4000-8000-000000000001");
    }

    // --- qits-workspaces ----------------------------------------------------------------------------

    private void workspaces() {
        platform.answer("GET", "/workspaces/api/workspaces", golden("qits-workspaces-service", "qits-workspaces",
                "a project with workspace slots hosting agents", "listWorkspaces"));
        refuse("POST", "/workspaces/api/workspaces/1/stop-container");
    }

    private void waits() {
        String waits = "/workspaces/api/agents/" + AGENT + "/waits";
        platform.answer("GET", waits, golden("qits-workspaces-service", "qits-workspaces",
                "an agent with an open wait", "listAgentWaits"));
        refuse("POST", waits);
    }

    // --- qits-events: the log, the stream, and the checkout's release --------------------------------

    private void events() {
        String recent = golden("qits-events-service", "qits-events", "a few recent events", "listEvents");
        String released = fixture("checkout-released.json");
        // One door, two readers: `qits events query`, and `qits checkout-daemon`'s lookup of the newest
        // release, which names the event it wants. The service filters by name; so does the fake.
        platform.route("GET", "/events/api/events", request -> new FakePlatform.Answer(200,
                names(request.query()).contains("ReleaseRequestStateChanged") ? released : recent, java.util.Map.of()));
        List<JsonNode> stream = new ArrayList<>();
        fixtureTree("events-stream.json").forEach(stream::add);
        platform.everyStream("/events/api/stream", exchange -> {
            Set<String> wanted = names(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write(": open\n\n".getBytes(StandardCharsets.UTF_8));
            for (JsonNode event : stream) {
                if (wanted.contains("*") || wanted.contains(event.path("name").asText())) {
                    body.write(("id:" + event.path("id").asText() + "\ndata:" + event + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            body.flush();
            holdOpen(exchange);
        });
    }

    /** Nothing beyond {@link #events}: the daemon reads the same log and stream, filtered by name. */
    private void checkoutDaemon() {
    }

    // --- qits-observability: the stream and the search, on the socket server --------------------------

    private void observe() {
        JsonNode recorded = goldenTree("qits-observability-service", "qits-observability",
                "telemetry records of every kind", "searchTelemetryRecords");
        List<String> frames = new ArrayList<>();
        recorded.path("records").forEach(frame -> frames.add(frame.toString()));
        fixtureTree("observe-stream.json").forEach(frame -> frames.add(frame.toString()));
        String search = recorded.toString();
        sockets.everyConnection = connection -> {
            if (connection.path.startsWith("/observability/stream")) {
                connection.accept();
                connection.awaitText();
                for (String frame : frames) {
                    connection.send(frame);
                }
                connection.hold();
            } else if (connection.path.startsWith("/observability/api/telemetry/records/search")) {
                connection.answer(search);
            } else {
                connection.refuse(404, "{\"message\":\"the fake has no answer for " + connection.path + "\"}");
            }
        };
    }

    // --- qits-system --------------------------------------------------------------------------------

    private void targets() {
        JsonNode targets = goldenTree("qits-system-service", "qits-system", "a connected target with leases",
                "listTargets");
        platform.answer("GET", "/system/api/targets", targets.toString());
        for (JsonNode target : targets) {
            String path = "/system/api/targets/" + target.path("id").asText();
            platform.answer("GET", path, target.toString());
            refuse("POST", path + "/greenlight");
        }
    }

    // --- qits-artifacts: the newest qits, for `qits update` -------------------------------------------

    private void update() {
        platform.answer("GET", "/artifacts/api/repositories/daemons/daemons", golden("qits-artifacts-service",
                "qits-artifacts", "the qits CLI published as a daemon", "listDaemons"));
        // The released "binary" is a two-line script that answers `-V` with the version the store
        // names: what `qits update` checks before it replaces anything.
        String released = fixture("released-qits.sh");
        String path = "/artifacts/daemons/qits-platform-access-cli/" + RELEASED_QITS;
        String length = String.valueOf(released.getBytes(StandardCharsets.UTF_8).length);
        String digest = "sha256:" + sha256(released);
        platform.answer("HEAD", path, 200, "", "Content-Length", length, "Docker-Content-Digest", digest);
        platform.answer("GET", path, 200, released, "Docker-Content-Digest", digest);
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** The platform's answer to a write a {@code qits:agent} token may not make. */
    private void refuse(String method, String path) {
        platform.answer(method, path, 403, "");
    }

    private static String projects(String state, String operation) {
        return golden("qits-projects-service", "qits-projects", state, operation);
    }

    private static String golden(String repository, String application, String state, String operation) {
        return GoldenMasters.of(repository, application).body(state, operation);
    }

    private static JsonNode goldenTree(String repository, String application, String state, String operation) {
        return tree(golden(repository, application, state, operation));
    }

    private static String fixture(String name) {
        try (InputStream in = FakePlatformMain.class.getResourceAsStream("/tapes/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no fixture tapes/" + name + " on the class path");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode fixtureTree(String name) {
        return tree(fixture(name));
    }

    private static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code names=A,B} or {@code name=A}, as the stream and the log take them; empty when absent. */
    static Set<String> names(String query) {
        Set<String> names = new java.util.HashSet<>();
        if (query == null) {
            return names;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals("names") || key.equals("name")) {
                for (String name : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8).split(",")) {
                    names.add(name.strip());
                }
            }
        }
        return names;
    }

    /**
     * The connection stays open, as a quiet stream does, until the client goes. A comment line every
     * 15 s is what finds out that it went (the write fails), and the CLI ignores comments, so nothing
     * of it shows; its own idle watchdog wants a line within 60 s.
     */
    private static void holdOpen(HttpExchange exchange) throws IOException, InterruptedException {
        OutputStream body = exchange.getResponseBody();
        while (true) {
            Thread.sleep(15_000);
            body.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
