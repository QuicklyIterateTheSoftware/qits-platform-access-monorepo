package eu.wohlben.qits.cli.access.uibuilder;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which docs site and version is a repository's routes bundle or a provider's contracts bundle,
 * found the way the landing app's UI builder finds them (qits-2156): from the metadata a release
 * writes on each version ({@code git.repository.name}), never from the site's name alone.
 * <p>
 * A site's newest version is the one the site list calls {@code latestVersion}, else the first of
 * its versions (they are listed newest first): the landing app's {@code newestVersion}.
 */
final class Bundles {

    static final String ROUTES = "@routes/";
    static final String CONTRACTS = "@contracts/";
    static final String MANIFEST = "manifest.json";
    static final String GOLDEN_INDEX = "golden-masters/index.json";
    static final String ENTITIES = "entities.json";
    static final String REPOSITORY_NAME = "git.repository.name";

    /** A recorded file as an index names it: plain relative segments, as the landing app's {@code safeRecordedFile}. */
    private static final Pattern RECORDED_FILE = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

    /** One site at one version. */
    record Bundle(String site, String version) {
    }

    private Bundles() {
    }

    /**
     * The {@code @routes/} site whose newest version names {@code repository}, at {@code version}
     * when one is given, else at that newest version. None is exit 1, naming the repository.
     */
    static Bundle routes(DocsStore store, String repository, String version) throws CliFailure, InterruptedException {
        JsonNode sites = store.sites();
        for (JsonNode site : prefixed(sites, ROUTES)) {
            String name = site.path("name").asText();
            JsonNode newest = newest(site, store.versions(name));
            if (newest != null && repository.equals(newest.path("metadata").path(REPOSITORY_NAME).asText(null))) {
                return new Bundle(name, version != null ? version : newest.path("version").asText());
            }
        }
        throw new CliFailure("No routes bundle names the repository " + SafeText.line(repository)
                + ": no @routes/ site's newest version was released from it.", CliFailure.FAILED);
    }

    /**
     * The contracts bundle of {@code provider}: the site {@code @contracts/<provider>} when the store
     * lists it (the provider's application name, {@code qits-projects}), else the {@code @contracts/}
     * site whose newest version names it as its repository ({@code qits-projects-service}, as a
     * freeze's records name a provider). The landing app's {@code providerSite}, in that order. None
     * is exit 1, naming the provider.
     */
    static Bundle contracts(DocsStore store, String provider, String version) throws CliFailure, InterruptedException {
        List<JsonNode> sites = prefixed(store.sites(), CONTRACTS);
        for (JsonNode site : sites) {
            if (site.path("name").asText().equals(CONTRACTS + provider)) {
                String newest = site.path("latestVersion").asText("");
                if (version == null && newest.isEmpty()) {
                    JsonNode first = newest(site, store.versions(CONTRACTS + provider));
                    newest = first == null ? "" : first.path("version").asText("");
                }
                if (version == null && newest.isEmpty()) {
                    break;
                }
                return new Bundle(CONTRACTS + provider, version != null ? version : newest);
            }
        }
        for (JsonNode site : sites) {
            String name = site.path("name").asText();
            JsonNode newest = newest(site, store.versions(name));
            if (newest != null && provider.equals(newest.path("metadata").path(REPOSITORY_NAME).asText(null))) {
                return new Bundle(name, version != null ? version : newest.path("version").asText());
            }
        }
        throw new CliFailure("No contracts bundle for the provider " + SafeText.line(provider)
                + ": no @contracts/" + SafeText.line(provider) + " site, and no @contracts/ site's newest version "
                + "was released from a repository of that name.", CliFailure.FAILED);
    }

    /** The sites whose name starts with {@code prefix}, in the order the store lists them. */
    private static List<JsonNode> prefixed(JsonNode sites, String prefix) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode site : sites.path("sites")) {
            if (site.path("name").asText("").startsWith(prefix)) {
                found.add(site);
            }
        }
        return found;
    }

    /** The site's newest version entry: the one matching {@code latestVersion}, else the first; null when none. */
    private static JsonNode newest(JsonNode site, JsonNode versions) {
        String latest = site.path("latestVersion").asText(null);
        JsonNode first = null;
        for (JsonNode version : versions.path("versions")) {
            if (first == null) {
                first = version;
            }
            if (latest != null && latest.equals(version.path("version").asText(null))) {
                return version;
            }
        }
        return first;
    }

    /** Whether a recorded file named by an index stays inside the index's own tree. */
    static boolean safeRecordedFile(String file) {
        return file != null && RECORDED_FILE.matcher(file).matches()
                && !file.matches("(^|.*/)\\.\\.?(/.*|$)");
    }

    /** Whether a freeze's file names a path inside the bundle: relative, no {@code .} or {@code ..} segment. */
    static boolean insideBundle(String file) {
        if (file == null || file.isEmpty() || file.startsWith("/") || file.contains("\\")) {
            return false;
        }
        for (String segment : file.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
