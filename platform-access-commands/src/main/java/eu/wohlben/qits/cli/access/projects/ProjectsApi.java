package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The qits-projects REST API ({@code /projects/api}), read as JSON trees. A tree and not records:
 * the services add fields and grow their word lists, and a tree needs no reflection in the native
 * binary.
 */
public final class ProjectsApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where the service's OpenAPI document is. */
    public static final String OPENAPI = "/projects/q/openapi";

    /** RFC 7396's media type, which the service's PATCH doors consume. */
    public static final String MERGE_PATCH = "application/merge-patch+json";

    private final PlatformClient client;
    private final String base;

    public ProjectsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** Takes this home's credential (refreshing a session if it is due) and finds the service. */
    public static ProjectsApi connect(CliContext context, String projectsUrl) throws CliFailure, InterruptedException {
        return new ProjectsApi(new PlatformClient(context.credential()),
                PlatformUrls.projects(projectsUrl, context.env(), context.idpUrl()));
    }

    /** {@code {"entries":[{"project":{…}}]}}. */
    public JsonNode projects() throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects"));
    }

    /** {@code {"entries":[{"repository":{…},"declared":…}],"wrapper":{…}}}. */
    public JsonNode repositories(String projectId) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects/" + segment(projectId) + "/repositories"));
    }

    /**
     * {@code {"repository":{…},"projectId":…,"wrapperPath":…}}: a blank repository on the platform's
     * git host, and the wrapper entry that names it, which is the same statement made twice.
     * <p>
     * The name's role suffix (owner ruling 2026-11-03) decides nothing any more: it is a convention
     * that tells a reader what a repository is, not an input the service reads a kind from. A kind
     * is stated by {@code archetype}, or by the repository's own {@code .config/qits/repository.yml}
     * once the wrapper reconcile runs; with neither, the service stores no archetype at all. A null
     * {@code archetype} here omits the field, so a caller who names none leaves the row exactly as
     * undeclared as that.
     * <p>
     * A null component lets the wrapper's own layout decide where the entry is mounted.
     */
    public JsonNode createRepository(String projectId, String name, String component, String archetype)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        if (component != null && !component.isBlank()) {
            body.put("component", component.strip());
        }
        if (archetype != null && !archetype.isBlank()) {
            body.put("archetype", archetype.strip().toUpperCase(Locale.ROOT));
        }
        return client.post(uri("/projects/api/projects/" + segment(projectId) + "/repositories"), body);
    }

    /** {@code {"requests":[…]}}. A null state asks for the service's default. */
    public JsonNode releaseRequests(String repoId, String state) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests" + stateQuery(state)));
    }

    /**
     * {@code {"requests":[…]}}: the release requests of every repository of the project, most
     * recently moved first, each with its {@code repoName}. Without a state the service answers the
     * open ones plus the last 10 finalized, which is what the landing app shows; {@code all} is every
     * state, and a state's name narrows to it.
     */
    public JsonNode projectReleaseRequests(String projectId, String state) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects/" + segment(projectId) + "/release-requests" + stateQuery(state)));
    }

    /**
     * {@code {"request":{…}}}: one release request of the project, named by {@code ref}, its logical
     * id ({@code <repository>-rr-<n>}, any case) or its whole uuid. The service resolves either
     * within the project and answers 404 for a request of another project, so the caller needs no
     * repository to find one. A uuid's start is not a ref here.
     */
    public JsonNode projectReleaseRequest(String projectId, String ref) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects/" + segment(projectId) + "/release-requests/" + segment(ref)));
    }

    private static String stateQuery(String state) {
        return state == null || state.isBlank() ? "" : "?state=" + URLEncoder.encode(state.strip(), StandardCharsets.UTF_8);
    }

    /**
     * {@code {"mergedSha", "commits":[…], "detail", "foldParents":[…], "sources":[…]}}: what a release
     * request carries, newest commit first. Each commit is {@code {hash, shortHash, author, email,
     * date, message, files, parents, fold}}; {@code fold} marks the request's own merge commits.
     */
    public JsonNode releaseRequestCommits(String repoId, String requestId) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/commits"));
    }

    /**
     * {@code {"request":{…}}}: new, or the open request that already holds or joined the branch. No
     * summary and no requester: the service derives the one from the request's items and takes the
     * other from the token (qits-1673).
     */
    public JsonNode createReleaseRequest(String repoId, String branch, String priority)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("branch", branch);
        putPriority(body, priority);
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests"), body);
    }

    /**
     * {@code {"request":{…}}}: the request with the branch on it. A branch already on the request
     * adds nothing; a priority with it states that priority again, and none leaves it as it is.
     * No requester: the service takes the token's.
     */
    public JsonNode joinReleaseRequest(String repoId, String requestId, String branch, String priority)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("branch", branch);
        putPriority(body, priority);
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/sources"), body);
    }

    /**
     * {@code {"request":{…}}}: the request, now WITHDRAWN. A reason only when one is given; without
     * one the service writes its own, which names the caller.
     */
    public JsonNode withdrawReleaseRequest(String repoId, String requestId, String reason)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason.strip());
        }
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/withdraw"), body);
    }

    /**
     * {@code {"from":{…},"to":{…}}} (qits-1665): one source taken off an open request and put on the
     * request queued after it, minted when there is none. {@code from} is the request it left,
     * folding again without it; {@code to} the one that now holds it. Both the branch and the reason
     * are required; the branch goes in the body, as on the priority door, because it has slashes.
     */
    public JsonNode moveReleaseRequestSource(String repoId, String requestId, String branch, String reason)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("branch", branch);
        body.put("reason", reason);
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/sources/move"), body);
    }

    /**
     * {@code {"request":{…}}}: the request, with the upstream-in-flight gate lifted for one
     * upstream request (epic qits-897, qits-916). {@code upstreamRequestId} is the upstream
     * request's uuid or qualified id, sent as given; a note only when one is given.
     */
    public JsonNode overrideReleaseRequestUpstream(String repoId, String requestId, String upstreamRequestId, String note)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("upstreamRequestId", upstreamRequestId);
        if (note != null && !note.isBlank()) {
            body.put("note", note.strip());
        }
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/upstream-overrides"), body);
    }

    /**
     * {@code {"request":{…}}}: the request, approved at {@code mergedSha}. The sha is the fold the
     * caller judged, and the service refuses it (409) when the request has moved to another one
     * since, so an approval never lands on a fold nobody looked at. A note only when one is given.
     */
    public JsonNode approveReleaseRequest(String repoId, String requestId, String mergedSha, String note)
            throws CliFailure, InterruptedException {
        return decideReleaseRequest(repoId, requestId, "approve", mergedSha, note);
    }

    /**
     * {@code {"request":{…}}}: the request, declined at {@code mergedSha}, which sends it to
     * REJECTED until a new push folds it again. The same body, the same stale-fold refusal, as
     * {@link #approveReleaseRequest}.
     */
    public JsonNode declineReleaseRequest(String repoId, String requestId, String mergedSha, String note)
            throws CliFailure, InterruptedException {
        return decideReleaseRequest(repoId, requestId, "decline", mergedSha, note);
    }

    /**
     * Both decisions are one body, {@code {mergedSha, note?}}, at the door named by {@code verb}.
     * The door decides who may act in the domain and words its own 403 in full, naming each open
     * requirement and the roles it admits (epic qits-1038), so this call skips the client's generic
     * "reads but does not write" hint: that hint is right for a door only a person may write to,
     * and this one is not.
     */
    private JsonNode decideReleaseRequest(String repoId, String requestId, String verb, String mergedSha, String note)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("mergedSha", mergedSha);
        if (note != null && !note.isBlank()) {
            body.put("note", note.strip());
        }
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/" + verb), body, false);
    }

    /**
     * {@code {"id"}}: the run qits-maintenance accepted for one release-request automation on the
     * request's current fold (202). No body: the address names the request and the kind.
     */
    public JsonNode rerunReleaseRequestAutomation(String repoId, String requestId, String kind)
            throws CliFailure, InterruptedException {
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/automations/" + segment(kind) + "/runs"));
    }

    /**
     * {@code {"request":{…}}}: the request, unchanged. {@code phase} is QA, PUBLISH or DEPLOY, sent
     * as given; the service answers 400 for anything else. No body: the address names the request
     * and the phase. The rerun itself is asynchronous (qits-ci for QA and PUBLISH, the deployer for
     * DEPLOY): nothing in this answer reflects it yet, which is why the request comes back as it was.
     */
    public JsonNode rerunReleasePipelinePhase(String repoId, String requestId, String phase)
            throws CliFailure, InterruptedException {
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/pipeline/" + segment(phase) + "/rerun"));
    }

    /**
     * Where one work item is addressed: {@code /projects/api/work/{entity}}. {@code entity} is its
     * qualified id ({@code qits-100}) or its id, and goes as it was given: the service resolves
     * either, so the CLI never looks an item up to find its UUID.
     */
    static String workPath(String entity) {
        return WORK + "/" + segment(entity);
    }

    /** The work family's root: every item of every archetype, by qualified id. */
    public static final String WORK = "/projects/api/work";

    /** {@code {"entries":[{"comment":{…}}]}}, oldest first: the thread of a work item of any archetype. */
    public JsonNode workComments(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity) + "/comments"));
    }

    /**
     * {@code {"comment":{…}}}: the new comment. The payload goes as the caller wrote it; the service
     * is the one that says what it may hold, and takes the author from the caller's identity.
     */
    public JsonNode addWorkComment(String entity, JsonNode payload) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/comments"), payload);
    }

    /**
     * {@code {"comment":{…}}}: the comment with the merge patch applied, sent as it was written. The
     * path names the item and the comment together, so a comment on another item's thread is the
     * service's 404, the same answer as one that does not exist.
     */
    public JsonNode editWorkComment(String entity, String commentId, JsonNode mergePatch)
            throws CliFailure, InterruptedException {
        return client.patch(uri(workPath(entity) + "/comments/" + segment(commentId)), mergePatch, MERGE_PATCH);
    }

    /** Where one archetype's payload schema for one door is served; {@code door} is create, update or transition. */
    public static String schemaPath(String archetype, String door) {
        return ARCHETYPES + "/" + segment(archetype) + "/schemas/" + segment(door);
    }

    /** Where the archetype registry is served: lifecycles and the legal moves of every status. */
    public static final String ARCHETYPES = WORK + "/archetypes";

    /** A work item of any archetype, flat: {@code {id, archetype, qualifiedId, status, parent, …}}. */
    public JsonNode work(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity)));
    }

    /** {@code {"children":[…]}}: an epic's features or a feature's tasks; empty for any other kind. */
    public JsonNode workChildren(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity) + "/children"));
    }

    /**
     * {@code {"entities":[…]}}: a project's work items, every archetype, in the service's order.
     * {@code project} is its id or its slug, {@code parent} a qualified id or an id. Each filter is
     * left out when null.
     */
    public JsonNode projectWork(String project, String archetype, String status, String parent)
            throws CliFailure, InterruptedException {
        return projectWork(project, archetype, status, parent, null, null, null);
    }

    /**
     * The same list with the filters qits-248 and qits-634 add: {@code outcome} (why a DROPPED item
     * was dropped), {@code supersededBy} (the items naming that one, a qualified id or an id, as
     * theirs) and {@code tag} (only items carrying it; the service reads its enum name or its kebab
     * form, either case, and answers HTTP 400 for anything else). A service older than qits-248
     * ignores the first two and answers the unfiltered list, so a caller that relies on either
     * filters the answer again itself.
     */
    public JsonNode projectWork(String project, String archetype, String status, String parent, String outcome,
            String supersededBy, String tag) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "archetype", archetype);
        addQuery(query, "status", status);
        addQuery(query, "parent", parent);
        addQuery(query, "outcome", outcome);
        addQuery(query, "supersededBy", supersededBy);
        addQuery(query, "tag", tag);
        return client.get(uri("/projects/api/projects/" + segment(project) + "/work"
                + (query.isEmpty() ? "" : "?" + String.join("&", query))));
    }

    /** The new item, flat. The body is the caller's payload with {@code archetype} set. */
    public JsonNode createWork(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(WORK), body);
    }

    /** The item with the merge patch applied, sent as it was written. */
    public JsonNode patchWork(String entity, JsonNode mergePatch) throws CliFailure, InterruptedException {
        return client.patch(uri(workPath(entity)), mergePatch, MERGE_PATCH);
    }

    /**
     * {@code {<key>: item}}, keyed exactly as the request was. The body is {@code {<key>: full
     * state}}, each key a qualified id or an id: the door is full-state, so a property the state
     * leaves out is cleared. Every id inside a state ({@code membership.parent}, {@code
     * supersededBy}) may be a qualified id too.
     */
    public JsonNode transitionWork(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(WORK + "/transition"), body);
    }

    /** The item in its new status, {@code statusBefore} filled. The body is {@code {"target": …}}. */
    public JsonNode setWorkStatus(String entity, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/status"), body);
    }

    /** {@code {"block":{…}}}: the flag as the write left it. The body is {@code {"blocked", "reason"}}. */
    public JsonNode setWorkBlocked(String entity, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/blocked"), body);
    }

    /** {@code {"success":true}}: the item and its subtree are gone. */
    public JsonNode deleteWork(String entity) throws CliFailure, InterruptedException {
        return client.delete(uri(workPath(entity)));
    }

    /**
     * {@code {"dispatch":{…}}}: the agent a press put on an epic or a ticket. The body is {@code {mode[, rule]}}:
     * a rule only when one is given, because only the delivery desk names one and the service
     * ignores it for anyone else.
     */
    public JsonNode dispatchWork(String entity, String mode, String rule) throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("mode", mode);
        if (rule != null && !rule.isBlank()) {
            body.put("rule", rule.strip());
        }
        return client.post(uri(workPath(entity) + "/dispatch"), body);
    }

    // --- ideas (epic qits-888) ---

    /** The ideas' root: a project's short notes that epics and tickets name as {@code inspiredBy}. */
    public static final String IDEAS = "/projects/api/ideas";

    static String ideaPath(String idea) {
        return IDEAS + "/" + segment(idea);
    }

    /**
     * {@code {"ideas":[…]}}, in rank order: priority highest first, the unranked after, then oldest
     * first (qits-1791). {@code project} is its id or its slug. {@code archived}
     * adds the archived ideas beside the others (it does not narrow to them), so the query says it
     * only when it is true; {@code status} (PLANNED, QUEUED or DONE) narrows, left out when null.
     */
    public JsonNode ideas(String project, boolean archived, String status) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "project", project);
        addQuery(query, "archived", archived ? "true" : null);
        addQuery(query, "status", status);
        return client.get(uri(IDEAS + query(query)));
    }

    /**
     * One idea with {@code inspired} (the work naming it as inspiredBy), {@code enables} (the ideas
     * naming it as enabledBy) and {@code enabledByIdea} (the idea enabling it, in brief, or null).
     */
    public JsonNode idea(String idea) throws CliFailure, InterruptedException {
        return client.get(uri(ideaPath(idea)));
    }

    /** The new idea. The body is the caller's payload, sent as it was written. */
    public JsonNode createIdea(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(IDEAS), body);
    }

    /** The idea with the merge patch applied, sent as it was written. */
    public JsonNode patchIdea(String idea, JsonNode mergePatch) throws CliFailure, InterruptedException {
        return client.patch(uri(ideaPath(idea)), mergePatch, MERGE_PATCH);
    }

    /** The idea, archived. The body is {@code {"reason"}}; the service refuses none (400). */
    public JsonNode archiveIdea(String idea, String reason) throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("reason", reason);
        return client.post(uri(ideaPath(idea) + "/archive"), body);
    }

    /** The idea, out of the archive. No body. */
    public JsonNode unarchiveIdea(String idea) throws CliFailure, InterruptedException {
        return client.post(uri(ideaPath(idea) + "/unarchive"));
    }

    /**
     * {@code {"items":[{"id","priority"},…]}}: the ideas a drop renumbered, highest first
     * (qits-1791). {@code where} is {@code above} or {@code below}, and {@code anchor} the idea the
     * dropped one now sits directly above or below; the body is {@code {"above":"<id>"}} or {@code
     * {"below":"<id>"}}, exactly one of them.
     */
    public JsonNode reprioritizeIdea(String idea, String where, String anchor)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put(where, anchor);
        return client.put(uri(ideaPath(idea) + "/priority"), body);
    }

    // --- dossier ---

    /** {@code {"pages":[…]}}, in reading order, bodies included. */
    public JsonNode workDossier(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity) + "/dossier"));
    }

    /** One page, by its slug or its id: {@code {id, slug, title, position, body, version, …}}. */
    public JsonNode workDossierPage(String entity, String page) throws CliFailure, InterruptedException {
        return client.get(uri(dossierPagePath(entity, page)));
    }

    /** The new page. The body is {@code {"title", "body"}}. */
    public JsonNode createWorkDossierPage(String entity, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/dossier"), body);
    }

    /** The page as written. The body is {@code {"title"?, "body"?, "version"}}. */
    public JsonNode putWorkDossierPage(String entity, String page, JsonNode body)
            throws CliFailure, InterruptedException {
        return client.put(uri(dossierPagePath(entity, page)), body);
    }

    /** The page in its new place. The body is {@code {"position"}}. */
    public JsonNode moveWorkDossierPage(String entity, String page, JsonNode body)
            throws CliFailure, InterruptedException {
        return client.post(uri(dossierPagePath(entity, page) + "/move"), body);
    }

    /** {@code {"success":true}}. */
    public JsonNode deleteWorkDossierPage(String entity, String page) throws CliFailure, InterruptedException {
        return client.delete(uri(dossierPagePath(entity, page)));
    }

    /** {@code {id, kind, mimeType, label, url, markdown}}. The body is {@code {"sourceId", "kind"}}. */
    public JsonNode inlineWorkDossierAsset(String entity, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/dossier-assets"), body);
    }

    static String dossierPagePath(String entity, String page) {
        return workPath(entity) + "/dossier/" + segment(page);
    }

    // --- the refinement room, and its designs ---

    /** {@code {"refinement":{id, …}}}, or {@code {"refinement":null}} when the item has no room. */
    public JsonNode workRefinement(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity) + "/refinement"));
    }

    /**
     * {@code {"refinement":{id, …}}}: the item's room, opened, or the one it already has (find-or-create).
     * Opening starts nothing; {@link #ensureRefinement} brings the room's container up.
     */
    public JsonNode openWorkRefinement(String entity) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/refinement"));
    }

    /** {@code {"refinement":{id, …}, "technicalProcessId"}}: the room, with the process bringing its container up. */
    public JsonNode ensureRefinement(String refinementId) throws CliFailure, InterruptedException {
        return client.post(uri(refinementPath(refinementId) + "/ensure-container"));
    }

    /** {@code {"success":true}}: the room is gone, with its container, its branch and its designs. */
    public JsonNode discardRefinement(String refinementId) throws CliFailure, InterruptedException {
        return client.post(uri(refinementPath(refinementId) + "/discard"));
    }

    private static String refinementPath(String refinementId) {
        return "/projects/api/refinements/" + segment(refinementId);
    }

    /** {@code {"designs":[…]}}, oldest first, without their documents. */
    public JsonNode refinementDesigns(String refinementId) throws CliFailure, InterruptedException {
        return client.get(uri(designsPath(refinementId)));
    }

    /** One design with its whole document. */
    public JsonNode refinementDesign(String refinementId, String designId) throws CliFailure, InterruptedException {
        return client.get(uri(designsPath(refinementId) + "/" + segment(designId)));
    }

    /** The new design, without its document. The body is {@code {"title", "html"}}. */
    public JsonNode addRefinementDesign(String refinementId, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(designsPath(refinementId)), body);
    }

    /** The design as written. The body is {@code {"title", "html"?, "version"}}. */
    public JsonNode updateRefinementDesign(String refinementId, String designId, JsonNode body)
            throws CliFailure, InterruptedException {
        return client.put(uri(designsPath(refinementId) + "/" + segment(designId)), body);
    }

    private static String designsPath(String refinementId) {
        return refinementPath(refinementId) + "/designs";
    }

    // --- repository reads ---

    /** {@code {"branches":[{name, parent, ahead, behind, canCleanup}]}}. */
    public JsonNode branches(String repoId) throws CliFailure, InterruptedException {
        return client.get(uri(repositoryPath(repoId) + "/branches"));
    }

    /** {@code {branch, parent, commits:[…]}}: the commits on the branch that are not on its parent. */
    public JsonNode commits(String repoId, String branch) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "branch", branch);
        return client.get(uri(repositoryPath(repoId) + "/commits" + query(query)));
    }

    /** {@code {commit, parent, files:[{path, oldPath, changeType}]}}. A null parent is the first parent. */
    public JsonNode commitChanges(String repoId, String hash, String parent) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "parent", parent);
        return client.get(uri(repositoryPath(repoId) + "/commits/" + segment(hash) + "/changes" + query(query)));
    }

    /** {@code {path, changeType, diff}}: one file's unified diff. */
    public JsonNode commitDiff(String repoId, String hash, String path, String parent)
            throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "path", path);
        addQuery(query, "parent", parent);
        return client.get(uri(repositoryPath(repoId) + "/commits/" + segment(hash) + "/diff" + query(query)));
    }

    /** {@code {branch, limit, counts, nonComplying:[…], qualifiedIds}}. */
    public JsonNode commitSubjects(String repoId, String branch, Integer limit) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "branch", branch);
        addQuery(query, "limit", limit == null ? null : limit.toString());
        return client.get(uri(repositoryPath(repoId) + "/commit-subjects" + query(query)));
    }

    // --- archiving a repository (qits-1784) ---

    /**
     * {@code {outcome, archived, branch?, commit?, releaseRequestId?, qualifiedId?, detail}}: asks
     * for {@code repository.archived: true} in the repository's own {@code
     * .config/qits/repository.yml}, as a commit on {@code archive/<name>} released through the
     * repository's release request — never a push to main. 202 {@code outcome: "requested"} when
     * the edit was committed and the branch asked for; 200 {@code outcome: "unchanged"} when main
     * already says so and nothing was opened.
     */
    public JsonNode archiveRepository(String repoId) throws CliFailure, InterruptedException {
        return client.post(uri(repositoryPath(repoId) + "/archive"));
    }

    /**
     * {@link #archiveRepository}'s mirror: asks for {@code repository.archived} to be removed from
     * the file, on {@code unarchive/<name>}, the same two outcomes.
     */
    public JsonNode unarchiveRepository(String repoId) throws CliFailure, InterruptedException {
        return client.post(uri(repositoryPath(repoId) + "/unarchive"));
    }

    // --- the front desk ---

    /**
     * {@code {"commandId", "state", "delivered", "queued", "position"}}: one turn handed to the
     * project's kept front desk session (qits-1572). {@code delivered} when it was typed at once,
     * {@code queued} with its {@code position} when it waits for the session to be idle. The door
     * takes {@code qits:admin}, {@code qits:admin-agent} or {@code qits:desk-admin}; it answers {@code {"reason"}} on a
     * refusal (409 no desk running, 429 a full queue, 503 no daemon).
     */
    public JsonNode deskPrompt(String projectId, String text) throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("text", text);
        return client.post(uri(deskPath(projectId) + "/prompt"), body);
    }

    /**
     * {@code {"commandId", "sessionId", "state", "text", "at"}}: the last answer of the project's
     * front desk session; {@code text} and {@code at} are null while it has not answered. A read,
     * but one {@code qits:agent} is refused as well, so its 403 carries no "reads but does not
     * write" line.
     */
    public JsonNode deskAnswer(String projectId) throws CliFailure, InterruptedException {
        return client.get(uri(deskPath(projectId) + "/last-answer"), false);
    }

    private static String deskPath(String projectId) {
        return "/projects/api/projects/" + segment(projectId) + "/front-desk";
    }

    /**
     * {@code {"messages":[{id, state, openedAt, closesAt, closedAt, acceptedAt, attempts, lastStatus,
     * lastDetail, commandId, queuePosition, message, events}]}}: the project's desk trigger
     * windows, newest first by {@code openedAt} (epic qits-1604, task qits-1613). Every state (OPEN,
     * OWED, DELIVERED, QUEUED, FAILED), with the lines each window collected. A null {@code limit}
     * leaves the query empty, which the service answers as its own default (20); the service 400s a
     * limit outside 1..100 and 404s an unknown project. Unlike the front desk's own doors, this one
     * reads but does not write, so {@code qits:agent} alone is enough.
     */
    public JsonNode deskTriggers(String projectId, Integer limit) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "limit", limit == null ? null : limit.toString());
        return client.get(uri(deskTriggersPath(projectId) + query(query)));
    }

    // --- named agents (epic qits-2154) ---

    /**
     * {@code {"agents":[{"name","surface","state","sessionId","commandId"}]}}: every named agent
     * kept in the project's desk container, generalising the front desk (epic qits-2154); the desk
     * is listed first. {@code state} is one of STOPPED, STARTING, IDLE, BUSY or FAILED. The door
     * takes the same roles as {@link #agentPrompt} and {@link #agentAnswer}, so an agent's own
     * {@code qits:agent} credential is refused here too (403) despite this being a plain read.
     */
    public JsonNode agents(String projectId) throws CliFailure, InterruptedException {
        return client.get(uri(agentsPath(projectId)), false);
    }

    /**
     * {@code {"commandId", "state", "delivered", "queued", "position"}}: one turn handed to a
     * named agent's kept session (epic qits-2154), the same shape {@link #deskPrompt} answers.
     * {@code delivered} when it was typed at once, {@code queued} with its {@code position} when
     * it waits for the session to be idle. With the desk container stopped (or not yet connected),
     * the service starts it and answers 202 {@code {"commandId":null,"state":"STARTING",
     * "delivered":false,"queued":true,"position":null}}: the turn waits in the agent's own inbox
     * and is delivered once that agent's session first reports running. The door takes {@code
     * qits:admin}, {@code qits:admin-agent} or {@code qits:desk-admin}; it answers {@code
     * {"reason"}} on a refusal (404 an agent the project does not name, 409 a prompt already
     * waits or the agent is not running, 429 a full inbox, 503 no projects daemon to reach the
     * desk container).
     */
    public JsonNode agentPrompt(String projectId, String name, String text) throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("text", text);
        return client.post(uri(agentPath(projectId, name) + "/prompt"), body);
    }

    /**
     * {@code {"commandId", "sessionId", "state", "text", "at"}}: the named agent's last answer;
     * {@code text} and {@code at} are null while it has not answered. A read, but one {@code
     * qits:agent} is refused as well, so its 403 carries no "reads but does not write" line, the
     * same as {@link #deskAnswer}.
     */
    public JsonNode agentAnswer(String projectId, String name) throws CliFailure, InterruptedException {
        return client.get(uri(agentPath(projectId, name) + "/last-answer"), false);
    }

    private static String agentsPath(String projectId) {
        return "/projects/api/projects/" + segment(projectId) + "/agents";
    }

    private static String agentPath(String projectId, String name) {
        return agentsPath(projectId) + "/" + segment(name);
    }

    // --- branch deliveries ---

    /**
     * {@code {"branch", "everywhere", "deliveries":[{projectId, repoId, repository, step, requestId,
     * qualifiedId, foldSha, runId, version, changedAt}]}}: how far a branch has come in each
     * repository that knows it (epic qits-1676). {@code everywhere} is the least advanced step across
     * the branch's repositories in the project, even when {@code repository} narrows the list; an
     * unknown branch answers no deliveries and a null {@code everywhere}. {@code repository} is a
     * name or an id, {@code project} an id or a slug; each is left out when null. The service 400s a
     * branch that several projects know when no project is named.
     */
    public JsonNode branchDeliveries(String branch, String repository, String project)
            throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "branch", branch);
        addQuery(query, "repository", repository);
        addQuery(query, "project", project);
        return client.get(uri("/projects/api/branch-deliveries" + query(query)));
    }

    private static String deskTriggersPath(String projectId) {
        return "/projects/api/projects/" + segment(projectId) + "/desk-triggers";
    }

    // --- backups ---

    /**
     * {@code {"projectId", "scheduled"}}: every repository of the project that has a backup remote,
     * queued for a push to it (202). The pushes run after the answer, so it says how many, never how
     * they went; each outcome lands on its repository's {@code lastBackup}.
     */
    public JsonNode backupSyncProject(String projectId) throws CliFailure, InterruptedException {
        return client.post(uri("/projects/api/projects/" + segment(projectId) + "/repositories/backup-sync"));
    }

    /**
     * {@code {repositoryId, kind, twinShaBefore, pushedSha, decidedBy, decidedAt, reason}}: the
     * repository's backup twin, whose main holds history ours does not share (a forge's seed
     * commit), made ours (qits-1885). A twin with no main, at ours or behind it is fast-forwarded
     * ({@code FAST_FORWARD}); one with unrelated history has its main replaced ({@code
     * UNRELATED_RESET}); one ahead or diverged is refused (409, naming both shas) and nothing is
     * pushed. {@code twinShaBefore} is null when the twin had no main.
     * <p>
     * The door decides who may act itself (a verified person, or the project's own delivery desk)
     * and words its own 403, so the client's generic "reads but does not write" hint stays off, as
     * for {@link #approveReleaseRequest}.
     */
    public JsonNode backupReset(String repoId, String reason) throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("reason", reason);
        return client.post(uri(repositoryPath(repoId) + "/backup-reset"), body, false);
    }

    /**
     * The repository's backup-remote sign-in terminal, {@code WS
     * /projects/api/repositories/{id}/remote-login}: {@code ws} for an http base and {@code wss} for
     * https, as the workspaces terminal socket is derived.
     */
    public URI remoteLoginSocket(String repoId) throws CliFailure {
        URI http = uri(repositoryPath(repoId) + "/remote-login");
        String scheme = switch (http.getScheme().toLowerCase(Locale.ROOT)) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new CliFailure("'" + base + "' is not a usable projects address. Give an http or "
                    + "https base URL.", CliFailure.USAGE);
        };
        return URI.create(scheme + http.toString().substring(http.getScheme().length()));
    }

    /**
     * {@code {"variant":…,"detected":…,"markdown":…}}: the development guide (qits-1679). A null
     * variant asks the service to pick the caller's own from its credential.
     */
    public JsonNode developmentGuide(String variant) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/guide"
                + (variant == null ? "" : "?for=" + segment(variant))));
    }

    /** {@code {"variants":[{"name":…,"description":…}]}}: the development guide's variants. */
    public JsonNode developmentGuideVariants() throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/guide/variants"));
    }

    /** The client the doors are called with; a socket opened beside them carries the same credential. */
    public PlatformClient client() {
        return client;
    }

    private static String repositoryPath(String repoId) {
        return "/projects/api/repositories/" + segment(repoId);
    }

    private static String query(List<String> query) {
        return query.isEmpty() ? "" : "?" + String.join("&", query);
    }

    /** A JSON Schema: what the door takes for this archetype, as the service validates it. */
    public JsonNode archetypeSchema(String archetype, String door) throws CliFailure, InterruptedException {
        return client.get(uri(schemaPath(archetype, door)));
    }

    /** {@code {"archetypes":[{archetype, lifecycle, transitions, …}]}}. */
    public JsonNode archetypes() throws CliFailure, InterruptedException {
        return client.get(uri(ARCHETYPES));
    }

    private static void addQuery(List<String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name + "=" + URLEncoder.encode(value.strip(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The service's OpenAPI document, as JSON. It is served as YAML unless asked otherwise, and
     * {@code format=json} is how SmallRye OpenAPI is asked. It lives beside the API, under {@code
     * /projects/q}, not under {@code /projects/api}.
     */
    public JsonNode openApi() throws CliFailure, InterruptedException {
        return client.get(uri(OPENAPI + "?format=json"));
    }

    /** A priority only when one is given, so the service's rule for none applies. */
    private static void putPriority(ObjectNode body, String priority) {
        if (priority != null && !priority.isBlank()) {
            body.put("priority", priority.strip().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * The project whose id, slug or name is {@code wanted}. The repository path takes the id only,
     * so a slug or a name is looked up here.
     */
    public JsonNode project(String wanted) throws CliFailure, InterruptedException {
        List<JsonNode> all = entries(projects(), "project");
        List<JsonNode> matches = all.stream()
                .filter(p -> wanted.equals(text(p, "id")) || wanted.equals(text(p, "slug")) || wanted.equals(text(p, "name")))
                .toList();
        if (matches.isEmpty()) {
            String known = all.stream().map(ProjectsApi::projectLabel).collect(Collectors.joining(", "));
            throw new CliFailure("No project has the id, slug or name '" + wanted + "'."
                    + (known.isEmpty() ? " There are no projects." : " Projects: " + known + "."), CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' names more than one project: "
                    + matches.stream().map(p -> projectLabel(p) + " (id " + text(p, "id") + ")").collect(Collectors.joining(", "))
                    + ". Name it by id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    /** The repository of {@code project} whose id or name is {@code wanted}. */
    public JsonNode repository(JsonNode project, String wanted) throws CliFailure, InterruptedException {
        List<JsonNode> all = entries(repositories(text(project, "id")), "repository");
        List<JsonNode> matches = all.stream()
                .filter(r -> wanted.equals(text(r, "id")) || wanted.equals(text(r, "name")))
                .toList();
        String label = projectLabel(project);
        if (matches.isEmpty()) {
            throw new CliFailure("Project " + label + " has no repository with the id or name '" + wanted
                    + "'. `qits repositories --project " + label + " list` shows them.", CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' names more than one repository of project " + label + ": "
                    + matches.stream().map(r -> text(r, "name") + " (id " + text(r, "id") + ")").collect(Collectors.joining(", "))
                    + ". Name it by id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    /** The {@code field} object of every entry in {@code answer.entries}. */
    public static List<JsonNode> entries(JsonNode answer, String field) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode entry : answer.path("entries")) {
            JsonNode value = entry.get(field);
            if (value != null && value.isObject()) {
                result.add(value);
            }
        }
        return result;
    }

    /** The field as text; empty when absent or null. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText();
    }

    public static String projectLabel(JsonNode project) {
        String slug = text(project, "slug");
        return slug.isEmpty() ? text(project, "name") : slug;
    }

    /** The answer as the service sent it, indented. */
    public static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (IOException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable projects address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
