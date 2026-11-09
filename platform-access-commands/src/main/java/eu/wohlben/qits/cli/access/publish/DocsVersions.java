package eu.wohlben.qits.cli.access.publish;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A docs site's newest version and the content hash recorded on it, read for {@code docs submit
 * --if-changed} (qits-1948).
 *
 * <pre>
 * GET /artifacts/docs/docs/&lt;site&gt;   {name, versions: [{version, metadata?: {...}}, ...]}, newest first
 * </pre>
 *
 * <p>The same listing {@code publish changelog} reads ({@code ChangelogCommand#unpublished}), but read
 * the way {@link ContentHashes} reads its answers rather than the way the changelog does: 404 is "no
 * such site" and anything else that is not a readable 200 — a 5xx, a 401, a body that is not the
 * listing — is the store failing to answer, exit 2, never an answer. The changelog can afford a
 * 4xx as exit 1 because it decides nothing on the listing but how far back to look; here the
 * listing decides whether a release publishes at all, and an error read as "absent" would publish
 * over an outage while one read as "unchanged" would skip a release in silence.
 */
final class DocsVersions {

    /** The newest version, and its {@code content.hash} metadata ({@code null} when none was recorded). */
    record Newest(String version, String contentHash) {
    }

    private final Http http;
    private final Store store;

    DocsVersions(Http http, Store store) {
        this.http = http;
        this.store = store;
    }

    /** The site's newest version, or empty when the site does not exist or lists no version. */
    Optional<Newest> newest(String site) {
        String url = store.docsSite(site);
        Http.Response response = http.get(url);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() != 200) {
            throw failed(url, "answered HTTP " + response.status()
                    + (response.excerpt().isEmpty() ? "" : ": " + response.excerpt()));
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(response.body(), "the body");
        } catch (CliException unreadable) {
            throw failed(url, "answered 200 with a body that is not the listing (" + unreadable.getMessage() + ")");
        }
        if (!(body.get("versions") instanceof List<?> versions)) {
            throw failed(url, "answered 200 with no versions list");
        }
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        if (!(versions.get(0) instanceof Map<?, ?> first)) {
            throw failed(url, "answered a version that is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> newest = (Map<String, Object>) first;
        String version = Json.string(newest, "version");
        if (version == null || version.isEmpty()) {
            throw failed(url, "answered a newest version with no version");
        }
        Object metadata = newest.get("metadata");
        if (metadata == null) {
            return Optional.of(new Newest(version, null));
        }
        if (!(metadata instanceof Map<?, ?> meta)) {
            throw failed(url, "answered metadata that is not an object");
        }
        Object hash = meta.get(DocsContent.HASH_KEY);
        if (hash != null && !(hash instanceof String)) {
            throw failed(url, "answered a " + DocsContent.HASH_KEY + " that is not a string");
        }
        return Optional.of(new Newest(version, (String) hash));
    }

    private static CliException failed(String url, String what) {
        return CliException.transport("the store failed to answer: the docs listing GET " + url + " " + what
                + " — not deciding on an error");
    }
}
