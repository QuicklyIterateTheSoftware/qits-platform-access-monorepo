package eu.wohlben.qits.cli.access.networkaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.CompanionProxy;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.HostRunArgv;
import eu.wohlben.qits.containers.driver.spec.HostRunSpec;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code qits network-access}: this workstation onto the platform's private edge (epic qits-1977).
 * {@code up} runs the companion — a userspace WireGuard tunnel with a CONNECT proxy in front, from
 * the image GET /companion names — registers it as a device peer of the signed-in person, and
 * records it in {@code access.json}, after which every platform call goes through it ({@link
 * CompanionProxy}). Local commands: they run docker on the caller's machine, so the MCP service
 * does not serve them, and only a workstation has a use for them.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "network-access", mixinStandardHelpOptions = true,
        subcommands = {NetworkAccessCommand.UpCommand.class, NetworkAccessCommand.DownCommand.class,
                NetworkAccessCommand.StatusCommand.class},
        description = {"Reach the platform's private apps from this workstation: `up` runs the network-access "
                + "companion in Docker, a WireGuard tunnel to the private edge with a proxy on 127.0.0.1:47813, "
                + "and from then on every qits command, and a browser too, dials the platform's hosts through it.",
                "The companion's state is the Docker volume qits-network-access-companion (its private key "
                        + "never leaves it) and $XDG_CONFIG_HOME/qits/access.json (the peer and the proxy).",
                "A browser reaches the same tunnel through the companion's proxy auto-config file: set its "
                        + "proxy auto-config URL to http://127.0.0.1:47813/proxy.pac."},
        footerHeading = "%nNotes:%n",
        footer = {"- A workstation command, signed in as a person: with QITS_TOKEN or inside the platform it "
                + "refuses, because host containers reach the private edge over the overlay. The exception is "
                + "`up --dry-run` and `down --dry-run`, which also run with QITS_TOKEN: they run nothing and only "
                + "print the docker commands, which is how a workspace live-checks this command.",
                "- Registering a device takes qits:admin.",
                "- QITS_NETWORK_ACCESS_PROXY=<url> names the companion's address instead of access.json's, and "
                        + "QITS_NETWORK_ACCESS_PROXY=off sends every call direct.",
                "- Only https and wss calls to hosts under the platform's domain go through the companion; "
                        + "loopback and every QITS_URL_* address go direct. When the companion does not answer, "
                        + "calls go direct with one warning."})
public class NetworkAccessCommand implements Runnable {

    /** The container, and the volume that keeps its key across recreations. */
    static final String CONTAINER = "qits-network-access-companion";
    static final String VOLUME = "qits-network-access-companion";

    /**
     * The companion's proxy listens unauthenticated on every address of its container, so it is
     * published on host loopback only: never a bare {@code 47813:47813}, never {@code 0.0.0.0}.
     */
    static final String PUBLISH = "127.0.0.1:47813:47813";

    static final Duration HEALTHY_WITHIN = Duration.ofSeconds(20);
    static final Duration HEALTH_POLL = Duration.ofMillis(500);

    /**
     * How long one docker call may take before it is killed and the command fails: the pull, which
     * may move a whole image, and every other call. Not final so a test can shorten them.
     */
    static Duration PULL_WITHIN = Duration.ofMinutes(10);
    static Duration DOCKER_WITHIN = Duration.ofSeconds(60);

    static final String NOT_HERE = "qits network-access is for a workstation: host containers reach the "
            + "private edge over the overlay.";

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern PUBLIC_KEY = Pattern.compile("[A-Za-z0-9+/]{43}=");
    private static final Pattern REGISTRY_REFUSED =
            Pattern.compile("(?i)unauthorized|denied|authentication required|credentials required|\\b401\\b"
                    + "|no basic auth credentials");
    private static final ObjectMapper JSON = new ObjectMapper();

    @CommandLine.Option(names = "--network-access-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The network-access service's base URL, without /network-access. Default: "
                    + "QITS_NETWORK_ACCESS_URL, else the session's idp address with `idp` swapped for "
                    + "`network-access`.")
    String networkAccessUrl;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static void workstationOnly(CliContext context) throws CliFailure {
        workstationOnly(context, false);
    }

    /**
     * A dry run also runs with QITS_TOKEN: it runs no docker and changes nothing, it only reads GET
     * /companion, which an agent's token may, so it is the one way a workspace checks the verb live.
     * Inside the platform it still refuses: there is no caller's machine to print commands for.
     */
    static void workstationOnly(CliContext context, boolean dryRun) throws CliFailure {
        Mode mode = context.mode();
        if (mode != Mode.WORKSTATION && !(dryRun && mode == Mode.EDGE_TOKEN)) {
            throw new CliFailure(NOT_HERE, CliFailure.USAGE);
        }
    }

    /** What a dry run prints: its first line, then every argv as a person would paste it, in run order. */
    static void printDryRun(CliContext context, String heading, List<List<String>> argvs) {
        context.out().println(heading);
        argvs.forEach(argv -> context.out().println(Docker.line(argv)));
    }

    NetworkAccessApi api(CliContext context) throws CliFailure, InterruptedException {
        // Direct, never through the companion this command is setting up or taking down.
        return new NetworkAccessApi(PlatformClient.direct(context.credential()),
                PlatformUrls.networkAccess(networkAccessUrl, context.env(), context.idpUrl()));
    }

    /**
     * The page a person manages their devices on: qits-network-access-service serves
     * qits-network-access-frontend at {@code /} of its own host (qits-2361). Not a door of {@link
     * NetworkAccessApi}, so no contract row has to reach it.
     */
    String page(CliContext context) throws CliFailure, InterruptedException {
        return PlatformUrls.networkAccess(networkAccessUrl, context.env(), context.idpUrl()) + "/";
    }

    static Path directory(CliContext context) {
        return context.sessionFile().directory();
    }

    /** Where the companion answers on this machine: QITS_NETWORK_ACCESS_PROXY when it names one. */
    static String address(CliContext context) {
        String told = context.env().get(CompanionProxy.VARIABLE);
        return told == null || told.isBlank() || told.strip().equalsIgnoreCase("off")
                ? CompanionProxy.DEFAULT_ADDRESS : told.strip();
    }

    /** {@code <address>/proxy.pac}: the companion's GET /proxy.pac, trimmed of a trailing slash first. */
    static String pacUrl(String address) {
        String trimmed = address.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/proxy.pac";
    }

    /** The one sentence every command prints once it knows the companion's address. */
    static String browsersLine(String address) {
        return "browsers: set the proxy auto-config URL to " + pacUrl(address);
    }

    static Optional<AccessState> state(CliContext context) throws CliFailure {
        try {
            return AccessState.read(directory(context));
        } catch (IOException e) {
            throw new CliFailure(e.getMessage(), CliFailure.FAILED);
        }
    }

    /**
     * A companion run as the driver renders it: {@code head}'s mode and options, then what every
     * companion run takes before its image (its user, its entrypoint, its volume), then {@code mode}.
     * The driver's belts refuse a served user, entrypoint or image that could be read as a flag.
     */
    static List<String> companionRun(String bin, HostRunSpec.Builder head, Companion companion, String mode)
            throws CliFailure {
        try {
            return HostRunArgv.render(bin, head.user(companion.user()).entrypoint(companion.entrypoint())
                    .volume(VOLUME + ":/state").args(mode).build());
        } catch (IllegalArgumentException refused) {
            throw new CliFailure("GET /network-access/api/companion answered a companion this command will not "
                    + "run: " + SafeText.line(refused.getMessage()) + ".", CliFailure.FAILED);
        }
    }

    /** What GET /companion answers. */
    record Companion(String image, String version, String entrypoint, String user) {
        String reference() {
            return image + ":" + version;
        }

        static Companion of(JsonNode answer) throws CliFailure {
            List<String> values = new ArrayList<>();
            for (String field : List.of("image", "version", "entrypoint", "user")) {
                String value = answer.path(field).asText("").strip();
                if (value.isEmpty() || !value.matches("[A-Za-z0-9._:/@+-]+")) {
                    throw new CliFailure("GET /network-access/api/companion answered no usable " + field + ".",
                            CliFailure.FAILED);
                }
                values.add(value);
            }
            return new Companion(values.get(0), values.get(1), values.get(2), values.get(3));
        }
    }

    /** {@code hostname -s}, lowercased and made a DNS label. */
    static String defaultName() {
        String host = "";
        try {
            host = Files.readString(Path.of("/proc/sys/kernel/hostname"), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException noProc) {
            // Not Linux, or no /proc: ask the JDK.
        }
        if (host.isEmpty()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (IOException | RuntimeException unknown) {
                host = "";
            }
        }
        return label(host);
    }

    /** The short host name as a DNS label: lower case, [a-z0-9-], no outer dash, at most 63; else workstation. */
    static String label(String host) {
        String shortName = host == null ? "" : host.strip();
        int dot = shortName.indexOf('.');
        if (dot >= 0) {
            shortName = shortName.substring(0, dot);
        }
        String label = shortName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        if (label.length() > 63) {
            label = label.substring(0, 63);
        }
        label = label.replaceAll("^-+", "").replaceAll("-+$", "");
        return label.isEmpty() ? "workstation" : label;
    }

    @CommandLine.Command(name = "up", mixinStandardHelpOptions = true,
            description = {"Run the network-access companion and send this workstation's platform calls, and a "
                    + "browser's, through it. Pulls the image the platform names, makes the companion's key, "
                    + "registers it as a device of yours (once: a later `up` reuses it), hands it its tunnel and "
                    + "starts it, published on 127.0.0.1:47813 only.",
                    "Running it again is how the companion is upgraded to the image the platform names now.",
                    "qits commands dial the companion's CONNECT proxy directly; a browser instead needs its "
                            + "proxy auto-config URL set to http://127.0.0.1:47813/proxy.pac, which this command "
                            + "prints once the companion is up."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits network-access up",
                    "  qits network-access up --name laptop",
                    "  qits network-access up --dry-run",
                    "",
                    "- --dry-run runs no docker, registers no device and writes no file: it reads the companion "
                            + "the platform names and prints, in order, the docker commands `up` would run. It "
                            + "needs no Docker, and runs with QITS_TOKEN too.",
                    "- Needs Docker. Docker gets its login for the platform's registry from `qits registry-login --configure`, "
                            + "which points it at `qits docker-credential`.",
                    "- Registering the device takes qits:admin."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The companion is up and healthy.",
                    "1:The platform refused, docker failed, or the companion did not become healthy in 20 s.",
                    "2:Used wrongly, not signed in, or not on a workstation (with --dry-run: inside the platform).",
                    "3:Docker is missing or not running, or the registry refused the pull: run `qits registry-login --configure`."})
    public static class UpCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        NetworkAccessCommand parent;

        @CommandLine.Option(names = "--name", paramLabel = "<label>",
                description = "The device's name, a DNS label. Default: this machine's short host name.")
        String name;

        @CommandLine.Option(names = "--dry-run",
                description = "Run nothing and register nothing; print the docker commands `up` would run.")
        boolean dryRun;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            workstationOnly(context, dryRun);
            String device = name == null ? defaultName() : name.strip();
            if (!LABEL.matcher(device).matches()) {
                throw new CliFailure("--name must be a DNS label: lower-case letters, digits and inner dashes, "
                        + "at most 63.", CliFailure.USAGE);
            }
            Docker docker = null;
            if (!dryRun) {
                docker = Docker.of(context.env());
                Docker.Result version = docker.run(DockerArgv.version(docker.bin(), null), null, DOCKER_WITHIN);
                if (!version.ok()) {
                    throw new CliFailure("Docker does not answer (`docker version`: " + version.said()
                            + "): install or start Docker.", Docker.NOT_READY);
                }
            }
            String bin = docker == null ? Docker.binOrName(context.env()) : docker.bin();

            NetworkAccessApi api = parent.api(context);
            Companion companion = Companion.of(api.companion());
            // Rendered before anything runs, so a companion the belts refuse is refused before a pull,
            // and a dry run refuses it exactly as `up` would.
            String ref = companion.reference();
            List<String> keygen = companionRun(bin, HostRunSpec.removed(ref), companion, "keygen");
            List<String> configure = companionRun(bin, HostRunSpec.removed(ref).interactive(), companion,
                    "configure");
            List<String> start = companionRun(bin, HostRunSpec.detached(CONTAINER, ref)
                    .restart("unless-stopped").publish(PUBLISH), companion, "run");
            List<String> pullArgv = DockerArgv.pull(bin, ref);
            List<String> volumeCreate = DockerArgv.volumeCreate(bin, VOLUME);
            List<String> rm = DockerArgv.rm(bin, CONTAINER);
            if (dryRun) {
                printDryRun(context, "dry run: nothing ran; up would run these docker commands, in order, and "
                                + "register the device " + device + " between keygen and configure:",
                        List.of(pullArgv, volumeCreate, keygen, configure, rm, start));
                return 0;
            }

            Docker.Result pull = docker.run(pullArgv, null, PULL_WITHIN);
            if (!pull.ok()) {
                if (REGISTRY_REFUSED.matcher(pull.said()).find()) {
                    throw new CliFailure("The registry refused to pull " + companion.reference()
                            + ": run `qits registry-login --configure` so Docker gets its login from your qits "
                            + "session, then run `qits network-access up` again.", Docker.NOT_READY);
                }
                throw failed(pullArgv, pull);
            }
            must(docker, volumeCreate);
            Docker.Result key = must(docker, keygen);
            String publicKey = lastLine(key.out());
            if (!PUBLIC_KEY.matcher(publicKey).matches()) {
                throw new CliFailure("`" + Docker.line(keygen) + "` printed no public key.", CliFailure.FAILED);
            }

            Path directory = directory(context);
            Optional<AccessState> saved = state(context);
            JsonNode peer = saved.map(AccessState::peerId).isPresent()
                    ? kept(api, saved.get().peerId(), publicKey) : null;
            if (peer == null) {
                try {
                    peer = api.createPeer(device, publicKey);
                } catch (CliFailure refused) {
                    if (refused.status() == 409) {
                        throw new CliFailure(refused.getMessage() + System.lineSeparator()
                                + "This companion's key, or the name " + device + ", is already an active device. "
                                + "Revoke that device at " + parent.page(context) + ", or pass another --name.",
                                CliFailure.FAILED);
                    }
                    throw refused;
                }
            }
            String peerId = peer.path("id").asText("");
            if (peerId.isBlank()) {
                throw new CliFailure("The network-access service answered a peer with no id.", CliFailure.FAILED);
            }
            JsonNode tunnel = api.peerTunnel(peerId);
            String tunnelJson;
            try {
                tunnelJson = JSON.writeValueAsString(tunnel);
            } catch (IOException impossible) {
                throw new CliFailure("cannot write the tunnel answer", CliFailure.FAILED);
            }
            Docker.Result configured = docker.run(configure, tunnelJson, DOCKER_WITHIN);
            if (!configured.ok()) {
                throw failed(configure, configured);
            }

            // Absent is fine: the first `up` has nothing to remove.
            docker.run(rm, null, DOCKER_WITHIN);
            must(docker, start);

            String address = address(context);
            boolean healthy = awaitHealthy(context, address);
            try {
                new AccessState(peerId, healthy ? address : null, companion.image(), companion.version())
                        .write(directory);
            } catch (IOException e) {
                throw new CliFailure("Cannot write " + directory.resolve(AccessState.FILE_NAME) + ": "
                        + e.getMessage(), CliFailure.FAILED);
            }
            String tunnelIp = SafeText.line(tunnel.path("tunnelIp").asText(peer.path("tunnelIp").asText("")));
            context.out().println("peer " + SafeText.line(peerId) + " (" + SafeText.line(peer.path("name").asText(device))
                    + "), tunnel IP " + tunnelIp);
            context.out().println("companion " + companion.reference() + " on " + address);
            context.out().println(browsersLine(address));
            if (!healthy) {
                throw new CliFailure("The companion did not answer healthy within " + HEALTHY_WITHIN.toSeconds()
                        + " s; platform calls stay direct. `docker logs " + CONTAINER + "` says why.",
                        CliFailure.FAILED);
            }
            context.out().println("platform calls now go through the companion");
            return 0;
        }

        /** The saved peer when it is still ACTIVE with this key; null when a new one is needed. */
        private static JsonNode kept(NetworkAccessApi api, String peerId, String publicKey)
                throws CliFailure, InterruptedException {
            try {
                JsonNode peer = api.peer(peerId);
                return "ACTIVE".equals(peer.path("status").asText()) && publicKey.equals(peer.path("publicKey").asText())
                        ? peer : null;
            } catch (CliFailure refused) {
                if (refused.status() == 404) {
                    return null;
                }
                throw refused;
            }
        }

        private static boolean awaitHealthy(CliContext context, String address) throws InterruptedException {
            Instant deadline = context.clock().instant().plus(HEALTHY_WITHIN);
            while (true) {
                Optional<CompanionHealth.Answer> answer = CompanionHealth.ask(address);
                if (answer.isPresent() && answer.get().healthy()) {
                    return true;
                }
                if (!context.clock().instant().isBefore(deadline)) {
                    return false;
                }
                context.sleeper().sleep(HEALTH_POLL);
            }
        }

        private static String lastLine(String out) {
            String[] lines = out.strip().split("\\R");
            return lines.length == 0 ? "" : lines[lines.length - 1].strip();
        }
    }

    @CommandLine.Command(name = "down", mixinStandardHelpOptions = true,
            description = {"Stop the network-access companion: platform calls go direct again. The device and the "
                    + "companion's key stay, so the next `up` reuses them.",
                    "--revoke also revokes the device at the platform and deletes the key's volume and access.json."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits network-access down",
                    "  qits network-access down --revoke",
                    "  qits network-access down --revoke --dry-run",
                    "",
                    "- --dry-run revokes nothing, runs no docker and deletes no file: it prints the docker commands "
                            + "`down` would run. It needs no Docker, and runs with QITS_TOKEN too.",
                    "- Revoking takes qits:admin."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused the revoke, or docker or a file failed.",
                    "2:Used wrongly, not signed in, or not on a workstation (with --dry-run: inside the platform).",
                    "3:Docker is missing or not running."})
    public static class DownCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        NetworkAccessCommand parent;

        @CommandLine.Option(names = "--revoke", description = "Also revoke the device and delete its key.")
        boolean revoke;

        @CommandLine.Option(names = "--dry-run",
                description = "Revoke nothing and run nothing; print the docker commands `down` would run.")
        boolean dryRun;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            workstationOnly(context, dryRun);
            if (dryRun) {
                // Never access.json: with QITS_TOKEN there is no session directory to read it from.
                String bin = Docker.binOrName(context.env());
                printDryRun(context, revoke
                                ? "dry run: nothing ran; down --revoke would revoke the device access.json names, "
                                        + "run these docker commands, in order, and delete access.json:"
                                : "dry run: nothing ran; down would run this docker command:",
                        revoke ? List.of(DockerArgv.rm(bin, CONTAINER), DockerArgv.volumeRm(bin, VOLUME))
                                : List.of(DockerArgv.rm(bin, CONTAINER)));
                return 0;
            }
            Optional<AccessState> saved = state(context);
            Docker docker = Docker.of(context.env());
            String peerId = saved.map(AccessState::peerId).orElse(null);
            if (revoke && peerId != null) {
                // Before the container goes: the call never depends on the companion anyway.
                try {
                    parent.api(context).revokePeer(peerId);
                    context.out().println("peer " + SafeText.line(peerId) + " revoked");
                } catch (CliFailure refused) {
                    if (refused.status() != 404) {
                        throw refused;
                    }
                    context.out().println("peer " + SafeText.line(peerId) + " is gone already");
                }
            }
            List<String> rm = DockerArgv.rm(docker.bin(), CONTAINER);
            Docker.Result removed = docker.run(rm, null, DOCKER_WITHIN);
            if (!removed.ok() && !removed.said().toLowerCase(Locale.ROOT).contains("no such container")) {
                throw failed(rm, removed);
            }
            Path directory = directory(context);
            try {
                if (revoke) {
                    List<String> volumeRm = DockerArgv.volumeRm(docker.bin(), VOLUME);
                    Docker.Result volume = docker.run(volumeRm, null, DOCKER_WITHIN);
                    if (!volume.ok() && !volume.said().toLowerCase(Locale.ROOT).contains("no such volume")) {
                        throw failed(volumeRm, volume);
                    }
                    AccessState.delete(directory);
                } else if (saved.isPresent()) {
                    AccessState s = saved.get();
                    new AccessState(s.peerId(), null, s.image(), s.version()).write(directory);
                }
            } catch (IOException e) {
                throw new CliFailure("Cannot update " + directory.resolve(AccessState.FILE_NAME) + ": "
                        + e.getMessage(), CliFailure.FAILED);
            }
            context.out().println("the companion is stopped; platform calls go direct");
            return 0;
        }
    }

    @CommandLine.Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show the device from access.json, the companion container's state and its health check.",
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits network-access status"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The companion is up and healthy.",
                    "1:It is down, not set up, or not healthy.",
                    "2:Used wrongly, or not on a workstation."})
    public static class StatusCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        NetworkAccessCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            workstationOnly(context);
            Optional<AccessState> saved = state(context);
            if (saved.isEmpty()) {
                context.out().println("not set up: run `qits network-access up`");
                return CliFailure.FAILED;
            }
            AccessState state = saved.get();
            context.out().println("peer       " + SafeText.line(String.valueOf(state.peerId())));
            context.out().println("companion  " + SafeText.line(state.image() + ":" + state.version()));
            String container = "unknown (no docker on PATH)";
            if (Docker.find(context.env()).isPresent()) {
                Docker docker = Docker.of(context.env());
                Docker.Result inspect = docker.run(DockerArgv.inspect(docker.bin(), CONTAINER, "{{.State.Status}}"),
                        null, DOCKER_WITHIN);
                container = inspect.ok() ? SafeText.line(inspect.out().strip()) : "absent";
            }
            context.out().println("container  " + container);
            if (state.proxy() == null) {
                context.out().println("proxy      none: platform calls go direct (run `qits network-access up`)");
                context.out().println(browsersLine(address(context)));
                return CliFailure.FAILED;
            }
            context.out().println("proxy      " + SafeText.line(state.proxy()));
            context.out().println(browsersLine(SafeText.line(state.proxy())));
            Optional<CompanionHealth.Answer> health = CompanionHealth.ask(state.proxy());
            context.out().println("health     " + health.map(a -> "HTTP " + a.status() + " "
                    + SafeText.line(a.body().strip())).orElse("no answer"));
            return health.isPresent() && health.get().healthy() ? 0 : CliFailure.FAILED;
        }
    }

    /** The whole {@code argv}, under {@link #DOCKER_WITHIN}; a non-zero exit fails the command. */
    private static Docker.Result must(Docker docker, List<String> argv) throws CliFailure, InterruptedException {
        Docker.Result result = docker.run(argv, null, DOCKER_WITHIN);
        if (!result.ok()) {
            throw failed(argv, result);
        }
        return result;
    }

    private static CliFailure failed(List<String> argv, Docker.Result result) {
        String said = result.said();
        return new CliFailure("`" + Docker.line(argv) + "` failed (exit code " + result.exit() + ")"
                + (said.isEmpty() ? "" : ": " + SafeText.line(said)), CliFailure.FAILED);
    }
}
