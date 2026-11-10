package eu.wohlben.qits.cli.access.changelog;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.CompanionProxy;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.publish.PublishCredential;
import eu.wohlben.qits.cli.access.publish.Store;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@code qits changelog bump-message}: the commit message of one dependency-bump step, with the
 * changelogs of what it upgraded to under it and the tickets they resolved in its scope.
 * <p>
 * The bump's own body stays as it was; below it come the changelogs {@code qits artifacts publish
 * changelog} published for every version the upgraded dependencies moved through, read from the docs
 * store, and their {@code associated tickets:} lines become the subject's scope. So the dependant's
 * own release names the tickets it brings in; its own changelog does not list them again, because
 * {@code publish changelog} leaves a bump commit's subject out of the tickets it names (qits-1866).
 * <p>
 * Which versions: the step's {@code QITS_EVENT_PAYLOAD} lists the changes the bump was asked for,
 * each with an optional {@code changelog: {repository, versions}}; only the changes the step
 * actually applied ({@code --applied}) count, because a change that was dropped brings nothing in.
 */
@TuiCommand(interaction = Interaction.CI_ONLY, effect = Effect.READ)
@CommandLine.Command(name = "bump-message", mixinStandardHelpOptions = true,
        description = {"Print the commit message of a dependency-bump step: `bump(<group>): <N> dependencies`, the "
                        + "step's body, and the changelogs of every version the applied changes upgrade to, with the "
                        + "tickets those changelogs name in the subject's scope.",
                "N is the number of lines in --applied. The changelogs come from the docs store "
                        + "(@changelog/<repository> at each version, CHANGELOG.md), for the changes in "
                        + "QITS_EVENT_PAYLOAD's changes[] that carry `changelog: {repository, versions}` and are "
                        + "listed in --applied; repositories by name, versions in version order. With no "
                        + "changelog to read the message is the subject and the body alone."},
        footerHeading = "%nExamples:%n",
        footer = {"  qits changelog bump-message --group targeted --applied applied.tsv --body body.txt > message.txt",
                "",
                "- --applied holds one `<ecosystem><TAB><name>` line per change the step applied; --body the "
                        + "step's own lines (`- maven g:a 1 -> 2 (pom.xml)`), printed as they are.",
                "- With tickets the subject is `chore(qits-9, qits-10): bump(<group>): <N> dependencies`, the "
                        + "ids sorted by project and number.",
                "- The store is https://registry.qits.$QITS_DOMAIN; the bearer comes from "
                        + "QITS_PUBLISH_TOKEN_COMMAND or QITS_PUBLISH_TOKEN, as for `qits artifacts publish`."},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:The message is on stdout.",
                "1:Refused: an option or QITS_EVENT_PAYLOAD missing or unreadable, no credential in the "
                        + "environment, a changelog that is not published (404) or whose last line is not "
                        + "`associated tickets: ...`, or another 4xx.",
                "2:Could not ask: the store unreachable, a 5xx, or a token command that failed. A step "
                        + "may retry a 2."})
public class BumpMessageCommand extends PlatformCommand {

    static final String PAYLOAD = "QITS_EVENT_PAYLOAD";
    static final String FILE = "CHANGELOG.md";

    /** Refused; asking again will not change it. */
    static final int REFUSED = 1;
    /** Could not ask, or could not be answered. */
    static final int COULD_NOT_ASK = 2;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** What a repository or a version must be to go into the store's path: one coordinate segment. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~+:@-]+");

    @CommandLine.Option(names = "--group", paramLabel = "<group>", description = "The bump's group, as in `bump(<group>)`.")
    String group;

    @CommandLine.Option(names = "--applied", paramLabel = "<file>",
            description = "The changes this step applied, one `<ecosystem><TAB><name>` per line.")
    Path applied;

    @CommandLine.Option(names = "--body", paramLabel = "<file>", description = "The step's body, printed as it is.")
    Path body;

    /**
     * The store's origin when the suite says so; null, as in every real run, composes it from
     * {@code QITS_DOMAIN}. A field and not a variable, so nothing a step carries can send the bearer
     * elsewhere ({@code AbstractPublishCommand.hosts} is the same seam).
     */
    String registryOrigin;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        String group = required(this.group, "--group");
        if (applied == null) {
            throw new CliFailure("--applied is required.", REFUSED);
        }
        if (body == null) {
            throw new CliFailure("--body is required.", REFUSED);
        }
        Set<String> appliedKeys = new HashSet<>();
        int count = 0;
        for (String line : read(applied, "--applied").split("\\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            count++;
            appliedKeys.add(line.strip());
        }
        String bodyText = trimTrailingNewlines(read(body, "--body"));
        Map<String, Set<String>> versions = versions(context.env().get(PAYLOAD), appliedKeys);

        String origin = registryOrigin != null ? registryOrigin : Store.publicOrigin(Store.REGISTRY_HOST, context.env());
        Credential credential = PublishCredential.forCiStep(context.env(), context.err());
        List<String> sections = new ArrayList<>();
        Set<String> tickets = new TreeSet<>(CommitSubjects.ORDER);
        for (Map.Entry<String, Set<String>> repository : versions.entrySet()) {
            List<String> blocks = new ArrayList<>();
            for (String version : repository.getValue()) {
                String text = trimTrailingNewlines(changelog(origin, credential, repository.getKey(), version));
                try {
                    tickets.addAll(AssociatedTickets.parse(text));
                } catch (AssociatedTickets.Malformed malformed) {
                    throw new CliFailure("The changelog of " + repository.getKey() + " " + version + " is malformed: "
                            + malformed.getMessage() + ".", REFUSED);
                }
                blocks.add("# " + version + "\n" + text);
            }
            sections.add("## " + repository.getKey() + "\n" + String.join("\n\n", blocks));
        }

        String subject = "bump(" + group + "): " + count + " dependencies";
        if (!tickets.isEmpty()) {
            subject = "chore(" + String.join(", ", tickets) + "): " + subject;
        }
        List<String> parts = new ArrayList<>();
        parts.add(subject);
        if (!bodyText.isEmpty()) {
            parts.add(bodyText);
        }
        parts.addAll(sections);
        context.out().print(String.join("\n\n", parts) + "\n");
        return 0;
    }

    /**
     * Repository by repository (by name), the versions the applied changes name (in version order),
     * each once however many coordinates name it.
     */
    static Map<String, Set<String>> versions(String payload, Set<String> applied) throws CliFailure {
        if (payload == null || payload.isBlank()) {
            throw new CliFailure(PAYLOAD + " is not set: qits-maintenance's bump step carries it.", REFUSED);
        }
        JsonNode event;
        try {
            event = JSON.readTree(payload);
        } catch (IOException notJson) {
            throw new CliFailure(PAYLOAD + " is not JSON.", REFUSED);
        }
        Map<String, Set<String>> versions = new TreeMap<>();
        for (JsonNode change : event.path("changes")) {
            String key = change.path("ecosystem").asText("") + "\t" + change.path("name").asText("");
            JsonNode changelog = change.path("changelog");
            if (!applied.contains(key) || !changelog.isObject()) {
                continue;
            }
            String repository = changelog.path("repository").asText("");
            if (!SEGMENT.matcher(repository).matches()) {
                throw new CliFailure(PAYLOAD + " names the changelog repository '" + repository + "', which is not "
                        + "a repository name.", REFUSED);
            }
            for (JsonNode version : changelog.path("versions")) {
                String text = version.asText("");
                if (!SEGMENT.matcher(text).matches()) {
                    throw new CliFailure(PAYLOAD + " names the version '" + text + "' of " + repository
                            + ", which is not a version.", REFUSED);
                }
                versions.computeIfAbsent(repository, r -> new TreeSet<>(VERSION_ORDER)).add(text);
            }
        }
        versions.values().removeIf(Set::isEmpty);
        return versions;
    }

    /** Calendar versions, numerically segment by segment; a segment that is not a number compares as text. */
    static final Comparator<String> VERSION_ORDER = (a, b) -> {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            if (i >= left.length) {
                return -1;
            }
            if (i >= right.length) {
                return 1;
            }
            int compared;
            if (left[i].matches("[0-9]{1,18}") && right[i].matches("[0-9]{1,18}")) {
                compared = Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]));
            } else {
                compared = left[i].compareTo(right[i]);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    };

    /** One published changelog, as text. */
    private static String changelog(String origin, Credential credential, String repository, String version)
            throws CliFailure, InterruptedException {
        String url = origin + Store.DOCS_PATH + "/@changelog/" + repository + "/-/" + version + "/" + FILE;
        String bearer;
        try {
            bearer = credential.bearer();
        } catch (CliFailure failed) {
            throw new CliFailure(failed.getMessage(), failed.retryable() ? COULD_NOT_ASK : REFUSED);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + bearer).GET().build();
        } catch (IllegalArgumentException notAnAddress) {
            throw new CliFailure("'" + url + "' is not a usable address.", REFUSED);
        }
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).proxy(CompanionProxy.selector()).build()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException unreachable) {
            throw new CliFailure("Cannot reach " + url + ": " + unreachable.getMessage(), COULD_NOT_ASK);
        }
        int status = response.statusCode();
        if (status == 200) {
            return response.body();
        }
        if (status == 404) {
            throw new CliFailure("no changelog for " + repository + " " + version, REFUSED);
        }
        String said = "GET " + url + " answered HTTP " + status
                + (status == 401 ? ": the CI step's credential was not accepted" : "");
        throw new CliFailure(said, status >= 500 ? COULD_NOT_ASK : REFUSED);
    }

    private static String read(Path file, String option) throws CliFailure {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            throw new CliFailure(option + " " + file + ": no such file.", REFUSED);
        } catch (IOException unreadable) {
            throw new CliFailure(option + " " + file + " cannot be read: " + unreadable.getMessage(), COULD_NOT_ASK);
        }
    }

    private static String required(String value, String option) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(option + " is required.", REFUSED);
        }
        return value.strip();
    }

    private static String trimTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }
}
