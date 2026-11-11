package eu.wohlben.qits.cli.access.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Docker client's {@code config.json}: one {@code credHelpers} entry sending the platform's
 * registry to {@code docker-credential-qits}. Everything else in the file is kept, in its order.
 * <p>
 * The entry holds no secret: Docker runs the helper for each pull and the helper answers with the
 * session's current token. So the file is written by {@code --configure} only, never on a refresh.
 * It is written the way {@code docker} writes it (tab indent), atomically, with mode 0600, because
 * the {@code auths} it keeps can hold credentials of other registries.
 */
final class DockerConfig {

    /** The helper's name in {@code credHelpers}; Docker runs {@code docker-credential-<name>}. */
    static final String HELPER = "qits";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A string value under a key that may name a secret, on one line of the file as written. */
    private static final Pattern SECRET = Pattern.compile(
            "(\"[^\"]*(?i:auth|token|password|secret)[^\"]*\"\\s*:\\s*\")((?:[^\"\\\\]|\\\\.)*)(\")");

    private DockerConfig() {
    }

    /** {@code $DOCKER_CONFIG/config.json}, else {@code ~/.docker/config.json}: where docker reads it. */
    static Path defaultPath(Map<String, String> env) {
        String directory = env.get("DOCKER_CONFIG");
        if (directory != null && !directory.isBlank()) {
            return Path.of(directory.strip()).resolve("config.json");
        }
        return RegistryLoginCommand.home(env).resolve(".docker").resolve("config.json");
    }

    /**
     * The key Docker looks a registry up by: the host, with a port only when it is not the scheme's
     * default. {@code https://} is assumed when the text has no scheme, as Docker does.
     */
    static String host(String url) {
        String text = url.strip();
        URI uri = URI.create(text.contains("://") ? text : "https://" + text);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("no host in " + text);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
        return defaultPort ? host : host + ":" + port;
    }

    /** What the file becomes; {@code after} equals {@code before} when the entry is already there. */
    record Plan(Path path, String before, String after, List<String> warnings) {

        boolean changed() {
            return !before.equals(after);
        }
    }

    static Plan plan(Path path, String registryHost) throws IOException {
        String before;
        try {
            before = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            before = "";
        }
        ObjectNode config;
        if (before.isBlank()) {
            config = JSON.createObjectNode();
        } else {
            JsonNode read;
            try {
                read = JSON.readTree(before);
            } catch (JsonProcessingException notJson) {
                // Jackson's message can quote the file, and the file can hold credentials.
                throw new IOException(path + " is not valid JSON");
            }
            if (!(read instanceof ObjectNode object)) {
                throw new IOException(path + " is not a Docker config file: it is not a JSON object");
            }
            config = object;
        }

        List<String> warnings = new ArrayList<>();
        JsonNode auths = config.get("auths");
        if (auths != null && auths.isObject()) {
            for (Iterator<String> names = auths.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (sameHost(name, registryHost)) {
                    warnings.add("Warning: " + path + " also has a stored login for " + name + " under auths. "
                            + "credHelpers takes precedence, so it is no longer used; `docker logout "
                            + registryHost + "` removes it.");
                }
            }
        }

        JsonNode helpers = config.get("credHelpers");
        if (helpers != null && !helpers.isObject()) {
            throw new IOException(path + " is not a Docker config file: credHelpers is not a JSON object");
        }
        if (helpers != null && HELPER.equals(helpers.path(registryHost).textValue())) {
            return new Plan(path, before, before, warnings);
        }
        ObjectNode entries = helpers != null ? (ObjectNode) helpers : config.putObject("credHelpers");
        entries.put(registryHost, HELPER);
        return new Plan(path, before, write(config) + "\n", warnings);
    }

    private static boolean sameHost(String name, String registryHost) {
        try {
            return host(name).equals(registryHost);
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
    }

    /** The redacted unified diff {@code --dry-run} prints; empty when nothing changes. */
    static String diff(Plan plan) {
        return UnifiedDiff.of(plan.path().toString(), plan.before(), plan.after(), mask(plan.before()),
                mask(plan.after()), ownLines(plan.after()));
    }

    static void apply(Plan plan) throws IOException {
        if (plan.changed()) {
            RegistryLogins.write(plan.path(), plan.after());
        }
    }

    /** Every string value under a key that names auth, a token, a password or a secret shows as {@code <token>}. */
    static String mask(String text) {
        Matcher matcher = SECRET.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + "<token>" + matcher.group(3)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The lines that are this CLI's: the {@code credHelpers} key and its {@code qits} entries. */
    private static Set<Integer> ownLines(String text) {
        Set<Integer> own = new HashSet<>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("\"credHelpers\"") || line.endsWith(": \"" + HELPER + "\"")
                    || line.endsWith(": \"" + HELPER + "\",")) {
                own.add(i);
            }
        }
        return own;
    }

    // ---------------------------------------------------------------- docker's layout

    /** What Go's {@code json.MarshalIndent(v, "", "\t")} writes, which is what docker writes. */
    static String write(JsonNode node) throws JsonProcessingException {
        StringBuilder out = new StringBuilder();
        write(node, out, "");
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out, String indent) throws JsonProcessingException {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.append(indent).append('\t').append(JSON.writeValueAsString(field.getKey())).append(": ");
                write(field.getValue(), out, indent + "\t");
                out.append(fields.hasNext() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                out.append(indent).append('\t');
                write(node.get(i), out, indent + "\t");
                out.append(i + 1 < node.size() ? ",\n" : "\n");
            }
            out.append(indent).append(']');
        } else {
            out.append(JSON.writeValueAsString(node));
        }
    }
}
