package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.platform.PlatformClient;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>No call to a qits service without its row</b> (ticket qits-1149), derived from the code rather
 * than from a list of what is covered.
 * <ol>
 *   <li>Every class of the commands that makes a request, found in its own bytecode (a call of
 *       {@code PlatformClient}'s or the publish client's request methods, or of the JDK's {@code
 *       HttpClient} and WebSocket builder), is a client of some provider's contract, or a transport
 *       or stream named in {@link #NOT_A_CLIENT} with the reason.</li>
 *   <li>Every door of every client (each non-private instance method of an {@code *Api} class, or
 *       the methods a command class names) is reached by at least one row, or named as an exclusion
 *       of its contract with the reason: a WebSocket, a stream, an address built for a browser.</li>
 *   <li>Every row reaches a door of a client of its own contract, and a row the provider has not
 *       recorded names its door ({@code METHOD /path}).</li>
 *   <li>Every contract with a recorded row has its committed {@code pacts/} file and its line in
 *       {@code .config/qits/release.yml}'s {@code contracts.pacts}, and nothing else is there.</li>
 * </ol>
 * The calls a framework makes rather than a class of the commands (the MCP service's quarkus-oidc
 * tenant reading the idp, qits-708) are {@linkplain CliRow#framework framework rows}: they reach no
 * door, and are held instead to the configuration that switches them on ({@link
 * #theMcpTenantsReadsArePacted}).
 * <p>
 * A new door on a client, or a new class that sends, fails here until it has its row or its
 * reason. The row's own test ({@link CliPactTest}) proves the row goes through the door it names.
 */
class ClientCallsGuardTest {

    /** The request methods of the two transports, as the bytecode of a caller names them. */
    private static final Map<String, Set<String>> SENDS = Map.of(
            "eu/wohlben/qits/cli/access/platform/PlatformClient",
            Set.of("get", "post", "put", "patch", "delete", "head", "download", "openStream", "openSocket"),
            "eu/wohlben/qits/cli/access/publish/Http", Set.of("get", "head", "putFile", "putText", "putBytes"),
            "java/net/http/HttpClient", Set.of("send", "sendAsync"),
            "java/net/http/WebSocket$Builder", Set.of("buildAsync"));

    /** Classes that send but are no provider's client, each with the reason. */
    static final Map<String, String> NOT_A_CLIENT = Map.of(
            "PlatformClient", "the transport every client calls through, with the caller's bearer",
            "Http", "the publish commands' transport, with the publish credential",
            "EventStream", "qits events: the live stream GET /events/api/stream, Server-Sent Events",
            "ObserveStream", "qits observe: the live telemetry stream, a WebSocket",
            "TerminalAttach", "a terminal attached over a WebSocket (workspaces attach, repositories remote-login)",
            "WorkspacesCommand$LogCommand", "qits workspaces log: a technical process's log, Server-Sent Events",
            "TargetsApi", "qits targets (qits-1530): qits-system-service has not published golden masters yet "
                    + "(epic qits-1481's targets module is not released); add its CliPact once it has",
            "RegistryDiscovery", "qits registry-login --configure (qits-1675): two list reads, qits-artifacts' and "
                    + "qits-mirror's GET .../api/repositories; any failure falls back to the default registries. "
                    + "qits-mirror has no contract with the CLI yet; add rows once it has",
            "CompanionHealth", "qits network-access up and status (epic qits-1977): GET /healthz of the "
                    + "network-access companion on this machine's loopback, never a platform service");

    @Test
    void everyClassThatSendsIsAClientOrNamed() throws Exception {
        Set<String> clients = new TreeSet<>();
        Contracts.ALL.forEach(c -> c.clients().keySet().forEach(type -> clients.add(simpleName(type))));
        Set<String> unnamed = new TreeSet<>();
        for (String sender : senders()) {
            if (!clients.contains(sender) && !NOT_A_CLIENT.containsKey(sender)) {
                unnamed.add(sender);
            }
        }
        assertThat(unnamed).as("classes that send a request, with no contract that names them a client and no"
                + " reason in NOT_A_CLIENT").isEmpty();
        assertThat(NOT_A_CLIENT.keySet()).as("NOT_A_CLIENT names only classes that send").isSubsetOf(senders());
    }

    @Test
    void everyDoorIsReachedByARowOrNamed() {
        Set<String> reached = new HashSet<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        Contracts.ALL.forEach(c -> {
            c.rows().stream().filter(r -> !r.isFramework()).forEach(r -> reached.add(r.reaches()));
            excluded.putAll(c.excluded());
        });
        Set<String> missing = new TreeSet<>();
        for (CliPact contract : Contracts.ALL) {
            for (String door : doors(contract)) {
                if (!reached.contains(door) && !excluded.containsKey(door)) {
                    missing.add(door + " (" + contract.provider() + ")");
                }
            }
        }
        assertThat(missing).as("client doors no row reaches and no contract names as an exclusion").isEmpty();
        Set<String> allDoors = new HashSet<>();
        Contracts.ALL.forEach(c -> allDoors.addAll(doors(c)));
        assertThat(excluded.keySet()).as("exclusions name doors").isSubsetOf(allDoors);
        assertThat(excluded.keySet()).as("an excluded door has no row").doesNotContainAnyElementsOf(reached);
    }

    @Test
    void everyRowReachesADoorOfItsOwnClients() {
        List<String> wrong = new ArrayList<>();
        for (CliPact contract : Contracts.ALL) {
            Set<String> doors = doors(contract);
            for (CliRow row : contract.rows()) {
                if (row.isFramework()) {
                    continue;
                }
                if (!doors.contains(row.reaches())) {
                    wrong.add(row.description() + " [" + row.state() + "] reaches " + row.reaches()
                            + ", no door of " + contract.provider() + "'s clients");
                }
                if (!contract.recorded(row) && row.request() == null) {
                    wrong.add(row.description() + " [" + row.state() + "] is not recorded and names no door: "
                            + contract.needs(row));
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    /**
     * The MCP service's quarkus-oidc tenant reads qits-idp at {@code auth-server-url}, and its reads
     * are framework rows of the idp's contract held to the shipped configuration (qits-708): with
     * discovery on, the default and what ships, that is the discovery document and the JWKS it
     * names; with discovery off it would be the JWKS alone. So turning discovery off again, or
     * pinning an issuer in configuration beside it, is a red build here rather than an unpacted read
     * or an issuer nobody's discovery publishes.
     */
    @Test
    void theMcpTenantsReadsArePacted() throws IOException {
        Properties shipped = new Properties();
        try (InputStream in = Files.newInputStream(
                root().resolve("platform-access-mcp-service/src/main/resources/application.properties"))) {
            shipped.load(in);
        }
        String server = shipped.getProperty("quarkus.oidc.auth-server-url", "");
        assertThat(server).as("the tenant reads qits-idp at its public name, through the edge (qits-428)")
                .isEqualTo("https://idp.qits.${QITS_DOMAIN}/idp");
        assertThat(shipped.stringPropertyNames().stream().filter(k -> k.startsWith("quarkus.oidc.")
                && k.contains("issuer"))).as("the issuer is never configuration: discovery publishes it (qits-730)")
                .isEmpty();
        boolean discovery = Boolean.parseBoolean(shipped.getProperty("quarkus.oidc.discovery-enabled", "true"));
        Set<String> framework = new TreeSet<>();
        for (CliPact contract : Contracts.ALL) {
            for (CliRow row : contract.rows()) {
                if (row.isFramework()) {
                    assertThat(contract.provider()).as(row.description()).isEqualTo("qits-idp-service");
                    assertThat(contract.recorded(row)).as("%s is recorded by the pinned idp", row.description())
                            .isTrue();
                    framework.add(row.operationId());
                }
            }
        }
        assertThat(framework).as("the MCP service's oidc tenant's reads of " + server)
                .isEqualTo(discovery ? Set.of("getJwks", "getOpenIdConfiguration") : Set.of("getJwks"));
    }

    /** The reactor's root: the outermost directory above this module that holds a pom. */
    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root.getParent() != null && Files.isRegularFile(root.getParent().resolve("pom.xml"))) {
            root = root.getParent();
        }
        return root;
    }

    @Test
    void everyLivePactIsCommittedAndPublished() throws IOException {
        Path root = root();
        Set<String> live = new TreeSet<>();
        Contracts.ALL.stream().filter(c -> !c.live().isEmpty()).forEach(c -> live.add(c.provider()));
        Set<String> committed = new TreeSet<>();
        try (Stream<Path> files = Files.list(root.resolve("pacts"))) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(CliPact.CONSUMER + "_") && n.endsWith(".json"))
                    .forEach(n -> committed.add(n.substring(CliPact.CONSUMER.length() + 1, n.length() - 5)));
        }
        assertThat(committed).as("pacts/ holds a file for each contract with a recorded row").isEqualTo(live);
        assertThat(publishedPacts(Files.readString(root.resolve(".config/qits/release.yml"))))
                .as("release.yml's contracts.pacts names each of them").isEqualTo(live);
    }

    // --- the doors ------------------------------------------------------------------------------

    /** {@code Class#method} of every door of the contract's clients. */
    static Set<String> doors(CliPact contract) {
        Set<String> doors = new TreeSet<>();
        contract.clients().forEach((type, named) -> {
            if (!named.isEmpty()) {
                named.forEach(n -> doors.add(simpleName(type) + "#" + n));
                return;
            }
            for (Method m : type.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isStatic(mod) && !Modifier.isPrivate(mod) && !m.isSynthetic() && !m.isBridge()) {
                    doors.add(simpleName(type) + "#" + m.getName());
                }
            }
        });
        return doors;
    }

    private static String simpleName(Class<?> type) {
        return type.getName().substring(type.getName().lastIndexOf('.') + 1);
    }

    /** The {@code pacts:} keys under {@code contracts:} of release.yml. */
    static Set<String> publishedPacts(String yaml) {
        Set<String> names = new TreeSet<>();
        boolean contracts = false;
        boolean pacts = false;
        Pattern key = Pattern.compile("^ {4}([A-Za-z0-9_.-]+):");
        for (String line : yaml.split("\n")) {
            if (line.startsWith("contracts:")) {
                contracts = true;
                continue;
            }
            if (contracts && !line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                contracts = false;
            }
            if (contracts && line.startsWith("  pacts:")) {
                pacts = true;
                continue;
            }
            if (pacts && (line.startsWith("  ") && !line.startsWith("    ") && !line.isBlank() || !contracts)) {
                pacts = false;
            }
            Matcher m = key.matcher(line);
            if (pacts && m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    // --- the senders ----------------------------------------------------------------------------

    /** Every class of the commands whose bytecode calls a request method of a transport. */
    static Set<String> senders() throws IOException, URISyntaxException {
        Path classes = Path.of(PlatformClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> senders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    ClassRefs refs = ClassRefs.read(in);
                    boolean sends = refs.methods().stream().anyMatch(ref -> {
                        int at = ref.indexOf('#');
                        Set<String> methods = SENDS.get(ref.substring(0, at));
                        return methods != null && methods.contains(ref.substring(at + 1));
                    });
                    if (sends) {
                        senders.add(refs.name().substring(refs.name().lastIndexOf('/') + 1));
                    }
                }
            }
        }
        return senders;
    }

    /** A class file's own name and the {@code owner#method} of each method it refers to. */
    record ClassRefs(String name, Set<String> methods) {

        static ClassRefs read(InputStream stream) throws IOException {
            DataInputStream in = new DataInputStream(stream);
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Map<Integer, String> utf8 = new TreeMap<>();
            Map<Integer, Integer> classes = new TreeMap<>();
            Map<Integer, int[]> refs = new TreeMap<>();
            Map<Integer, int[]> nameAndTypes = new TreeMap<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8.put(i, in.readUTF());
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7 -> classes.put(i, in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 17, 18 -> in.readInt();
                    case 10, 11 -> refs.put(i, new int[] {in.readUnsignedShort(), in.readUnsignedShort()});
                    case 12 -> nameAndTypes.put(i, new int[] {in.readUnsignedShort(), in.readUnsignedShort()});
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("constant pool tag " + tag);
                }
            }
            in.readUnsignedShort();
            String self = utf8.get(classes.get(in.readUnsignedShort()));
            Set<String> methods = new TreeSet<>();
            refs.values().forEach(ref -> methods.add(utf8.get(classes.get(ref[0])) + "#"
                    + utf8.get(nameAndTypes.get(ref[1])[0])));
            return new ClassRefs(self, methods);
        }
    }
}
