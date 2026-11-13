package eu.wohlben.qits.cli.access.uibuilder;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.publish.Store;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * qits-artifacts' docs store, repository {@code docs}, read with the command's own credential: the
 * four reads the landing app's UI builder page makes (qits-2156), at the same addresses.
 *
 * <pre>
 * GET /artifacts/docs/docs                              listDocsSites     every site, with its latestVersion
 * GET /artifacts/docs/docs/{site}                       listDocsVersions  a site's versions and their metadata
 * GET /artifacts/docs/docs/{site}/-/{version}/{path}    getDocsFile       one published file
 * </pre>
 *
 * <p>A site such as {@code @routes/qits-landing} goes on the wire with its {@code /} and {@code @}
 * literal: the store answers 404 to {@code %40routes%2Fqits-landing}. Inside a file path {@code /}
 * stays literal too, and everything else is percent-encoded ({@code [slug]} is a 400 and {@code
 * %5Bslug%5D} a 200). That is the landing app's {@code literalDocsUrl}: each value encoded whole,
 * then {@code %2F} and {@code %40} turned back.
 */
public final class DocsStore {

    private final PlatformClient client;
    private final String origin;

    /** @param origin qits-artifacts' base URL, without {@code /artifacts} */
    public DocsStore(PlatformClient client, String origin) {
        this.client = client;
        this.origin = origin;
    }

    /** {@code listDocsSites}: {@code {sites: [{name, latestVersion, ...}]}}. */
    public JsonNode sites() throws CliFailure, InterruptedException {
        return client.get(address(""));
    }

    /** {@code listDocsVersions}: {@code {name, versions: [{version, metadata, ...}]}}, newest first. */
    public JsonNode versions(String site) throws CliFailure, InterruptedException {
        return client.get(address("/" + literal(site)));
    }

    /** {@code getDocsFile} of a JSON file: a manifest, an index, a recorded response, an entity map. */
    public JsonNode file(String site, String version, String path) throws CliFailure, InterruptedException {
        return client.get(fileAddress(site, version, path));
    }

    /** {@code getDocsFile} of a text file, as it is: a freeze's HTML. */
    public String text(String site, String version, String path) throws CliFailure, InterruptedException {
        return client.getText(fileAddress(site, version, path)).body();
    }

    URI fileAddress(String site, String version, String path) throws CliFailure {
        return address("/" + literal(site) + "/-/" + literal(version) + "/" + literal(path));
    }

    private URI address(String rest) throws CliFailure {
        try {
            URI uri = URI.create(origin + Store.DOCS_PATH + rest);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + origin + "' is not a usable qits-artifacts address.", CliFailure.USAGE);
        }
    }

    /**
     * {@code value} percent-encoded as {@code encodeURIComponent} does, with {@code /} and {@code @}
     * left literal. {@link URLEncoder} is form encoding: a space becomes {@code +}, and {@code *},
     * {@code ~} and the rest differ, so those are put right after it.
     */
    static String literal(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")")
                .replace("%7E", "~")
                .replace("%2F", "/").replace("%40", "@");
    }
}
