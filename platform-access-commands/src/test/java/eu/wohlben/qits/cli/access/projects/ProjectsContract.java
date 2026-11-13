package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.access.agentmcp.AgentMcpContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the commands ask qits-projects, and why</b> (epic qits-965, ticket qits-1149): the one
 * table {@code ProjectsConsumerPactTest} runs row by row against a pact mock server and writes to
 * {@code pacts/qits-platform-access-monorepo_qits-projects-service.json}, so the file and the verified
 * behaviour cannot drift apart.
 * <p>
 * <b>One row per (command, call).</b> Each row runs the {@link ProjectsApi} method the command
 * calls, with the state's frozen example as its argument and the recorded request as its body (pact
 * matches a body exactly, and the PATCH doors' {@code application/merge-patch+json}, which the
 * recordings leave unnamed, goes as a header of the row), and asserts what the command then reads. {@code consumes} lists exactly
 * those reads, as the table form prints them, and the pact binds nothing else: the {@code -o json}
 * form prints the answer as it came and reads no field of it. Every {@code /work} call goes by
 * qualified id (epic qits-965): the CLI never resolves one to a UUID.
 * <p>
 * <b>Every command that names a project or a repository looks it up first</b>, by id, slug or
 * name, so each of those commands has a row for the project list and one for the repository list
 * ({@link #PROJECT_SCOPED}, {@link #REPOSITORY_SCOPED}). The repository lookup is asserted on the
 * entries rather than through {@link ProjectsApi#repository}: the mock answers every entry in the
 * first one's shape, so a lookup by name would find the one name twice.
 * <p>
 * <b>Each state is one the provider records</b>, picked for the call. A command that reads an item
 * before it writes it appears once per call. {@code qits work details}' read of the items an item
 * closes ({@code ?supersededBy=}) binds only what its recording holds: none of those items is blocked.
 * {@code openApi}, the served OpenAPI document the comment and transition payload commands read
 * their schema from, is no operation of the provider: it is named as an exclusion.
 */
public final class ProjectsContract {

    // --- the states -----------------------------------------------------------------------------

    static final String WORK_IN_EVERY_STATUS = "a project with work in every status";
    static final String EPIC_IN_DETAIL = "an epic in detail";
    static final String NO_WORK = "a project with no work";
    static final String REGISTRY = "the archetype registry";
    static final String UPDATE_SCHEMA = "the archetype registry's update schema";
    static final String TRANSITION_SCHEMA = "the archetype registry's transition schema";
    static final String REPORTED_TICKET = "a reported ticket";
    static final String TICKET_WITH_A_COMMENT = "a ticket with a comment";
    static final String IMPLEMENTED_TICKET = "an implemented ticket";
    static final String RELEASED_REQUEST = "a released release request";
    static final String REQUESTS_OF_EVERY_STATE = "a repository with a release not merged to main";
    static final String RELEASED_REQUESTS = "a repository with released release requests";
    static final String BUG_TICKET_IN_DETAIL = "a bug ticket in detail";
    static final String EPIC_WITH_A_SKETCH = "an epic with a sketch to inline";
    static final String AWAITING_REQUEST = "a release request awaiting approval";
    static final String PENDING_REQUESTS = "a project with pending release requests";
    static final String HELD_BY_A_FAILED_AUTOMATION = "a release request held by a failed automation";
    static final String TICKET_WITH_A_DESIGN = "a ticket with a design";
    static final String REFINED_TICKET = "a refined ticket";
    static final String READY_FOR_DEV_TICKET = "a ready for dev ticket";
    static final String A_PROJECT = "a project exists";
    static final String ONE_REPOSITORY = "a project with one repository";
    static final String REPOSITORIES_IN_COMPONENTS = "a project with repositories in components";
    static final String THREE_REPOSITORIES = "a project with 3 repositories";
    static final String TO_CREATE_A_REPOSITORY_IN = "a project to create a repository in";
    static final String A_BRANCH_TO_RELEASE = "a repository with a branch to release";
    static final String OPEN_TO_ANOTHER_BRANCH = "a release request open to another branch";
    static final String PROJECT_REQUESTS_OF_EVERY_STATE = "a project with release requests in every state";
    static final String AWAITING_WHILE_ITS_BUILD_RUNS = "a release request awaiting approval while its build runs";
    static final String HELD_BY_AN_UPSTREAM_RELEASE = "a release request held by an upstream release";
    /**
     * {@code qits release-request rerun --phase QA}: the provider's recording for a request whose
     * CI run failed, the usual reason to rerun it.
     */
    static final String REJECTED_BY_ITS_BUILD = "a release request rejected by its build";
    /** {@code qits release-request rerun --phase PUBLISH}: the provider's recording for that phase's rerun. */
    static final String PUBLISH_FAILED = "a release request whose publish failed";
    /**
     * The soft feature freeze (epic qits-1580): the active request holds its threshold of counted
     * branches, and a second request waits behind it. The one state that records {@code
     * queuedBehind} set, so the only one whose rows bind it: everywhere else the field is null, and
     * a null the pact demanded would break the provider the moment a recording queues a request.
     */
    static final String FROZEN_WITH_A_QUEUED_NEXT = "a frozen release request with a queued next request";
    /**
     * The front desk doors of {@code qits projects desk} (qits-1572). Pending on the provider: the
     * golden masters pinned in the root pom record no such state yet, so both rows are skipped with
     * this state and their door, and stay out of the pact file. They go live with no change here
     * once qits-maintenance bumps {@code qits.qits-projects-golden-masters.version} past the release
     * that records this state under the operation ids {@link #DESK_PROMPT_OPERATION} and {@link
     * #DESK_ANSWER_OPERATION}: write the pact again with {@code -Dgolden.update=true} on that bump. If
     * the provider records them under other operation ids, the ids here follow.
     */
    static final String DESK_RUNNING = "a project whose front desk is running";
    static final String DESK_PROMPT_OPERATION = "promptFrontDesk";
    static final String DESK_ANSWER_OPERATION = "getFrontDeskLastAnswer";
    /**
     * The desk trigger read door of {@code qits projects desk triggers} (epic qits-1604, task
     * qits-1613): pending on the provider the same way the two rows above are. The golden masters
     * pinned in the root pom record no such state yet, so the row is skipped with this state and
     * its door, and stays out of the pact file. It goes live with no change here once
     * qits-maintenance bumps {@code qits.qits-projects-golden-masters.version} past the release
     * that records this state under the operation id {@link #DESK_TRIGGERS_OPERATION}: write the
     * pact again with {@code -Dgolden.update=true} on that bump. If the provider records it under
     * another operation id, the id here follows.
     */
    static final String DESK_RECEIVED_A_TRIGGER = "a project whose desk received a trigger message";
    static final String DESK_TRIGGERS_OPERATION = "listDeskTriggerMessages";
    /**
     * The named agents' doors of {@code qits projects agents} (qits-2154), generalising the
     * front desk into named agents with {@code ui-builder} as the pilot. Pending on the provider
     * the same way {@link #DESK_RUNNING} is: the golden masters pinned in the root pom record no
     * such state yet, so all three rows are skipped with this state and their door, and stay out
     * of the pact file. They go live with no change here once qits-maintenance bumps {@code
     * qits.projects-golden-masters.version} past the qits-projects-service release that records
     * this state under the operation ids {@link #LIST_AGENTS_OPERATION}, {@link
     * #PROMPT_AGENT_OPERATION} and {@link #AGENT_ANSWER_OPERATION}: write the pact again with
     * {@code -Dgolden.update=true} on that bump. If the provider records them under other
     * operation ids, the ids here follow.
     */
    static final String AGENT_RUNNING = "a project whose named agent is running";
    static final String LIST_AGENTS_OPERATION = "listAgents";
    static final String PROMPT_AGENT_OPERATION = "promptAgent";
    static final String AGENT_ANSWER_OPERATION = "getAgentLastAnswer";
    /**
     * The project list filtered by {@code ?supersededBy=}, the item named by its qualified id; its
     * items are not blocked, so no blockSource.
     */
    static final String SUPERSEDED_ITEMS = "an item that other items name as their supersededBy";

    /**
     * {@code qits repositories archive} and {@code unarchive} (qits-1784): the doors are live on the
     * provider ({@code archiveRepository}, {@code unarchiveRepository}), but the golden masters
     * pinned in the root pom record no state for them yet, so both rows are skipped with this state
     * and their door, and stay out of the pact file. They go live with no change here once
     * qits-maintenance bumps {@code qits.qits-projects-golden-masters.version} past the release that
     * records this state under the operation ids {@link #ARCHIVE_OPERATION} and {@link
     * #UNARCHIVE_OPERATION}: write the pact again with {@code -Dgolden.update=true} on that bump. If
     * the provider records either under another state or operation id, the names here follow.
     */
    static final String ARCHIVABLE_REPOSITORY = "a repository to archive";
    static final String ARCHIVE_OPERATION = "archiveRepository";
    static final String UNARCHIVE_OPERATION = "unarchiveRepository";

    /**
     * {@code qits repositories backup reset} (qits-1885): the provider records the door as the
     * person the state names, with that person's CLI bearer ({@code authorization}), since only a
     * person or the project's delivery desk may reset a twin. Pending until qits-maintenance bumps
     * {@code qits.projects-golden-masters.version} past the qits-projects-service release that
     * records this state under {@link #BACKUP_RESET_OPERATION}; on that bump write the pact again
     * with {@code -Dgolden.update=true}.
     */
    static final String TWIN_WITH_UNRELATED_HISTORY = "a repository whose backup twin holds unrelated history";
    static final String BACKUP_RESET_OPERATION = "backupResetRepository";

    /** What {@code backup reset} prints: the kind, the twin's main before and after, who decided and why. */
    static final List<String> BACKUP_RESET_ANSWER = List.of("kind", "twinShaBefore", "pushedSha", "decidedBy",
            "reason");

    /** What {@code archive} and {@code unarchive} print: the table shows exactly these, nothing else. */
    static final List<String> ARCHIVE_ANSWER = List.of("outcome", "archived", "branch", "qualifiedId", "detail");

    /**
     * {@code qits ideas} (epic qits-888): the provider records the idea doors in this state, and
     * {@code createIdea} in {@link #NO_WORK} too. Pending until qits-maintenance bumps {@code
     * qits.projects-golden-masters.version} to a release that records it (qits-projects-service
     * 2026.1030.185452 does); on that bump write the pact again with {@code -Dgolden.update=true}.
     */
    static final String IDEAS = "a project with ideas";

    /**
     * {@code qits ideas rank} and the priority half of {@code qits ideas update} (qits-1791): the
     * provider records the drop door, {@code reprioritizeIdea}, as a {@code below} drop, and {@code
     * patchIdea} with {@code {"priority":5}}, in this state (qits-projects-service
     * 2026.1031.205956). Pending until qits-maintenance bumps {@code
     * qits.projects-golden-masters.version} to that release or a later one; on that bump write the
     * pact again with {@code -Dgolden.update=true}.
     */
    static final String IDEAS_ONE_RANKED = "a project with three ideas, one with a priority";

    /**
     * One idea as a row of {@code qits ideas list} and of every write's answer. Neither {@code
     * enabledBy}, {@code archiveReason} nor {@code archivedAt}: null in most recordings, and a pact
     * must not demand a null the field exists to replace; the table does not print them.
     */
    static final List<String> IDEA_ROW = List.of("id", "title", "status", "archived", "updatedAt");

    /**
     * What {@code qits ideas details} reads where the recording holds a value: the idea, the work it
     * inspired and the ideas it enables. {@code enabledBy}, {@code enabledByIdea}, {@code
     * archiveReason} and {@code archivedAt} are read too, but recorded null here, so not bound.
     */
    static final List<String> IDEA_DETAILS = List.of("id", "projectId", "title", "description", "status", "archived",
            "createdAt", "updatedAt", "inspired[].qualifiedId", "inspired[].archetype", "inspired[].status",
            "inspired[].title", "enables[].id", "enables[].status", "enables[].archived", "enables[].title");

    /**
     * The needs of {@code qits work list} and {@code details} (epic qits-1899, task qits-1905): the
     * provider records an item that needs another item at a status, not met yet, in this state
     * (qits-1903, qits-projects-service 2026.1106.140436), which the golden masters pinned in the
     * root pom carry.
     * <p>
     * Every {@code needs} array is matched with no length: an item gains and loses needs at every
     * status, and a pinned length would break the provider the day its recording changes one.
     */
    static final String NEEDS_ANOTHER = "a ticket that needs another";

    /** One need as the NEEDS column and the details' needs rows print it. */
    static final List<String> NEED = List.of("qualifiedId", "status", "currentStatus", "met");

    /**
     * The tags a work schema offers: its length is left open, so the provider may add a tag
     * (qits-1908 adds CONDITION) without breaking the pact.
     */
    static final String TAGS_ENUM = "properties.tags.items.enum";

    private static final CliPact BASE = CliPact.of("qits-projects-service", "qits-projects");

    // --- what the commands read -----------------------------------------------------------------

    /** Every lookup of a project by id, slug or name. */
    static final List<String> PROJECT = List.of("entries[].project.id", "entries[].project.slug",
            "entries[].project.name");

    /**
     * What {@code qits projects delivery} reads of the project it looked up (epic qits-1440). Pending
     * on the provider: qits-projects records {@code delivery} on its project reads from qits-1490 on,
     * and the golden masters pinned in the root pom (2026.1019.190811) predate it, so {@link #held}
     * binds none of these paths yet and the row binds the lookup alone. Once qits-maintenance bumps
     * the pin past qits-1490's release, the same row binds all four: write the pact again with
     * {@code -Dgolden.update=true} on that bump.
     */
    static final List<String> DELIVERY = List.of("entries[].project.delivery.enabled",
            "entries[].project.delivery.maxRunning", "entries[].project.delivery.startRefined",
            "entries[].project.delivery.approves");

    /** Every lookup of a repository by id or name. */
    static final List<String> REPOSITORY = List.of("entries[].repository.id", "entries[].repository.name");

    /**
     * What {@code qits projects desk triggers} reads of each message: what the table prints,
     * {@code openedAt}, {@code state}, each event's {@code source} (the EVENTS count and the
     * SOURCES column), {@code attempts} and {@code acceptedAt}. {@code -o json} prints the answer
     * as it came and reads no field of it.
     */
    static final List<String> DESK_TRIGGERS_FIELDS = List.of("messages[].openedAt", "messages[].state",
            "messages[].events[].source", "messages[].attempts", "messages[].acceptedAt");

    /**
     * What {@code qits projects agents list} reads of each agent: the table's five columns (name,
     * surface, state, session, command).
     */
    static final List<String> AGENT_ROWS_FIELDS = List.of("agents[].name", "agents[].surface", "agents[].state",
            "agents[].sessionId", "agents[].commandId");

    /**
     * One work item as a row of a list ({@code WorkEntities.printRows}): each only where the service
     * sends it. {@code tags} (qits-634) is bound here too: {@code held} drops it from a row generated
     * against a pinned golden-masters jar that predates the field, so this is safe before the
     * provider's release reaches the pin as well as after.
     */
    static final List<String> ROW = List.of("qualifiedId", "id", "archetype", "status", "outcome", "blocked",
            "blockSource", "tags", "title", "updatedAt");

    /** One work item in full ({@code WorkEntities.printDetails}), and the project its closes are read in. */
    static final List<String> DETAILS = Stream.concat(ROW.stream(), Stream.of("blockReason", "blockedBy",
            "ticketType", "impetus", "assignee", "parent", "position", "repositoryId", "supersededBy",
            "implementedAt", "createdBy", "createdAt", "description", "acceptanceCriteria", "projectId", "inspiredBy"))
            .toList();

    /**
     * What {@code qits work transition} carries over from the item ({@code WorkEntities.currentState}).
     * No {@code tags}: {@code WorkEntities.CARRIED} leaves it out (qits-634; see its javadoc), and the
     * transition schema golden masters bind here ({@link #TRANSITION_SCHEMA}) list no slot for it
     * either, so there is nothing a row could bind.
     */
    static final List<String> CARRIED = Stream.concat(WorkEntities.CARRIED.stream(), Stream.of("parent", "position"))
            .toList();

    /**
     * One release request as {@code ReleaseRequestCommand.printRequest} prints it. {@code
     * qualityGates[].checks[].runId} is not here: the recorder froze it under both {@code ids} and
     * {@code strings} (a whole uuid in some elements only), so qits-pact-consumer would match it as
     * a uuid; no row consumes it. Nor are {@code queuedBehind}, {@code queuedNext} and their qualified
     * ids, which the table prints too: they are null in every state a write door is recorded in, and
     * a pact must not demand a null the field exists to replace. {@link #FROZEN_WITH_A_QUEUED_NEXT}'s
     * list rows bind {@code queuedBehind}; no recorded answer the CLI reads sets {@code queuedNext}.
     * <p>
     * Nor are {@code summary} and {@code requester} (qits-1673): the service has deprecated both and
     * will drop them, so the CLI reads {@code participants} and {@code items} instead, and the pact
     * must not demand either. Those two are bound only where the pinned recording holds them, with no
     * length ({@link #REQUEST_OPEN_ENDED}). An item's {@code workItem} is not bound: it is null for a
     * branch that names no work item, which every recording holds today, and a pact must not demand
     * that null, nor an object shape no recording has shown; the CLI prints the label without it.
     */
    static final List<String> REQUEST = List.of("id", "qualifiedId", "repoName", "state", "featureFreeze.features",
            "featureFreeze.threshold", "featureFreeze.frozen", "featureFreeze.source", "supersededBy",
            "supersededByQualifiedId", "priority", "participants", "items[].label", "approvalState", "mergedSha",
            "version",
            "detail", "createdAt", "updatedAt", "qualityGates[].kind", "qualityGates[].position",
            "qualityGates[].state", "qualityGates[].detail", "qualityGates[].checks[].name",
            "qualityGates[].checks[].state", "qualityGates[].checks[].detail", "sources[].kind",
            "sources[].name", "sources[].ref", "sources[].implicit", "sources[].feature", "sources[].explicitJoin",
            "sources[].priority", "sources[].addedBy", "sourceMoves[].branch", "sourceMoves[].fromQualifiedId",
            "sourceMoves[].toQualifiedId", "sourceMoves[].movedBy", "sourceMoves[].reason", "sourceMoves[].movedAt");

    /** Where create and join put the branch: the first line of the table form (epic qits-1580). */
    static final List<String> PLACEMENT = List.of("placement.outcome", "placement.queued", "placement.detail");

    /** The list's FREEZE column, of each request. */
    static final List<String> FREEZE = List.of("requests[].featureFreeze.features",
            "requests[].featureFreeze.threshold", "requests[].featureFreeze.frozen",
            "requests[].featureFreeze.source");

    /** The list's FREEZE column says queued for a request waiting behind a frozen one. */
    static final List<String> QUEUED = List.of("requests[].queuedBehind", "requests[].queuedBehindQualifiedId");

    /**
     * {@code qualityGates} itself, and each gate's {@code checks}, must not pin a length (ticket
     * qits-904's follow-up): qits-projects-service is about to grow an {@code upstream-in-flight}
     * gate (qits-913), which would change {@code qualityGates}' length on every state recorded here,
     * and provider verification of a pact pinned at today's length would then fail and deadlock that
     * service's release. {@link CliRow#anyLength} matches these by type with no length instead,
     * templated from every element the recording holds (see {@code GoldenInteraction#anyLength}'s
     * javadoc for the path spelling: the array's own path, with no trailing {@code []}).
     */
    static final List<String> REQUEST_OPEN_ENDED = List.of("qualityGates", "qualityGates[].checks", "participants",
            "items");

    /**
     * {@code qits release-request move} (qits-1665): the provider records it in {@link
     * #FROZEN_WITH_A_QUEUED_NEXT}. Pending on the provider until qits-maintenance bumps {@code
     * qits.projects-golden-masters.version} past the qits-projects-service release that records it;
     * the row is skipped until then and stays out of the pact file. Write the pact again with {@code
     * -Dgolden.update=true} on that bump.
     * <p>
     * {@code sourceMoves} is in {@link #REQUEST}: {@code printRequest} lists the moves. Every other
     * recording holds it empty, which binds nothing; here both requests hold the one move, and its
     * length is left open on both sides, as a request gathers moves.
     */
    static final String MOVE_OPERATION = "moveReleaseRequestSource";

    /** The move's two requests, with no length pinned on what grows. */
    private static String[] openEndedMove() {
        return Stream.of("from.", "to.")
                .flatMap(side -> Stream.concat(REQUEST_OPEN_ENDED.stream(), Stream.of("sourceMoves"))
                        .map(p -> side + p))
                .toArray(String[]::new);
    }

    /**
     * The list's ITEMS column (qits-1673), of each request: the items' labels, else the branches
     * ({@code ReleaseRequestRefs.items}). Neither array may pin a length: {@link #LIST_OPEN_ENDED}.
     */
    static final List<String> ITEMS = List.of("requests[].items[].label", "requests[].sources[].name");

    /** {@link #ITEMS}' arrays, matched with no length: a request grows items and sources as branches join. */
    static final String[] LIST_OPEN_ENDED = {"requests[].items", "requests[].sources"};

    /** Every command that takes a release request finds it by its logical id or its uuid's start. */
    static final List<String> FIND_REQUEST = List.of("requests[].id", "requests[].qualifiedId", "requests[].state");

    // --- the calls ------------------------------------------------------------------------------

    /** A call through {@link ProjectsApi}, over the real {@link PlatformClient}. */
    @FunctionalInterface
    interface Call {
        void run(ProjectsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new ProjectsApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    /**
     * The same call as the person the state names: approve, decline and the backup twin reset are a
     * person's decisions,
     * and the provider records them with that person's CLI bearer ({@code authorization}), which
     * the row sends as its {@code Authorization} header.
     */
    private static CliRow.Call asThePerson(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new ProjectsApi(new PlatformClient(credential), url), recorded.params(), recorded);
        };
    }

    private static JsonNode body(Operation recorded) {
        return CliRow.exampleBody(recorded);
    }

    private static int recorded(Operation op, String array) {
        return BASE.masters().json(op.state(), op.operationId()).path(array).size();
    }

    /** The literal segment after {@code marker} in the recorded path: a page slug or a commit hash. */
    private static String segmentAfter(Operation recorded, String marker) {
        String path = recorded.examplePath();
        String rest = path.substring(path.indexOf(marker) + marker.length());
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static String query(Operation recorded, String name) {
        return recorded.example(recorded.query().get(name).getFirst());
    }

    /** {@code qits projects list} and the agents' work-links cache: slug, name and id of each project. */
    private static final Call PROJECTS = (api, params, recorded) -> {
        List<JsonNode> projects = ProjectsApi.entries(api.projects(), "project");
        assertThat(projects).hasSize(recorded(recorded, "entries"));
        assertThat(projects).allSatisfy(p -> {
            assertThat(ProjectsApi.text(p, "id")).isNotBlank();
            assertThat(ProjectsApi.text(p, "slug")).isNotBlank();
            assertThat(ProjectsApi.text(p, "name")).isNotBlank();
        });
    };

    /** The lookup every project-scoped command makes first: by id, as a person may type it. */
    private static final Call FIND_PROJECT = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.project(params.get("projectId")), "id")).isEqualTo(params.get("projectId"));

    private static final Call FIND_REPOSITORY = (api, params, recorded) -> {
        List<JsonNode> repositories = ProjectsApi.entries(api.repositories(params.get("projectId")), "repository");
        assertThat(repositories).hasSize(recorded(recorded, "entries")).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "name")).isNotBlank();
        });
    };

    /** {@code qits repositories list}: name, archetype, component and id. */
    private static final Call REPOSITORIES = (api, params, recorded) -> {
        List<JsonNode> repositories = ProjectsApi.entries(api.repositories(params.get("projectId")), "repository");
        assertThat(repositories).hasSize(recorded(recorded, "entries")).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "name")).isNotBlank();
            assertThat(ProjectsApi.text(r, "archetype")).isNotBlank();
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
        });
    };

    /** {@code qits tui}'s repository dropdown: each repository's name and archetype. */
    private static final Call REPOSITORY_CHOICES = (api, params, recorded) ->
            assertThat(ProjectsApi.entries(api.repositories(params.get("projectId")), "repository")).isNotEmpty()
                    .allSatisfy(r -> assertThat(ProjectsApi.text(r, "archetype")).isNotBlank());

    /** {@code qits repositories remote-login} without --repository signs in through the wrapper's repository. */
    private static final Call WRAPPER_REPOSITORY = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.repositories(params.get("projectId")).path("wrapper"), "repositoryId"))
                    .isNotBlank();

    /** After the sign-in, the backups as they now stand: each repository with a backup remote and its last backup. */
    private static final Call BACKUPS = (api, params, recorded) -> {
        List<JsonNode> repositories = ProjectsApi.entries(api.repositories(params.get("projectId")), "repository");
        assertThat(repositories).anySatisfy(r -> {
            assertThat(ProjectsApi.text(r, "backupUrl")).isNotBlank();
            assertThat(ProjectsApi.text(r.path("lastBackup"), "outcome")).isNotBlank();
        });
    };

    private static final Call BACKUP_SYNC = (api, params, recorded) ->
            assertThat(api.backupSyncProject(params.get("projectId")).path("scheduled").isNumber()).isTrue();

    private static final Call CREATE_REPOSITORY = (api, params, recorded) -> {
        JsonNode created = api.createRepository(params.get("projectId"), body(recorded).path("name").asText(),
                body(recorded).path("component").asText(),
                // The recording's own archetype, when it states one (qits-1834): the service stores no
                // kind it was not told, so a stated one is what the answer's archetype comes from.
                body(recorded).path("archetype").asText(null));
        assertThat(ProjectsApi.text(created.path("repository"), "name")).isEqualTo(body(recorded).path("name").asText());
        assertThat(ProjectsApi.text(created.path("repository"), "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(created, "wrapperPath")).isNotBlank();
    };

    /** The request as {@code printRequest} prints it: its name, state and sources. */
    private static void request(JsonNode request) {
        assertThat(ReleaseRequestRefs.name(request)).isNotBlank();
        assertThat(ProjectsApi.text(request, "state")).isNotBlank();
        assertThat(request.path("sources")).isNotEmpty();
    }

    /** {@code qits release-request create}: the branch and the priority, never the summary the provider still records. */
    private static final Call CREATE_REQUEST = (api, params, recorded) -> request(api.createReleaseRequest(
            params.get("repositoryId"), body(recorded).path("branch").asText(),
            body(recorded).path("priority").asText()).path("request"));

    private static final Call JOIN_REQUEST = (api, params, recorded) -> request(api.joinReleaseRequest(
            params.get("repositoryId"), params.get("requestId"), body(recorded).path("branch").asText(),
            body(recorded).path("priority").asText()).path("request"));

    private static final Call WITHDRAW = (api, params, recorded) -> request(api.withdrawReleaseRequest(
            params.get("repositoryId"), params.get("requestId"), body(recorded).path("reason").asText())
            .path("request"));

    /** {@code qits release-request move}: the branch and the reason; the first line names both requests. */
    private static final Call MOVE = (api, params, recorded) -> {
        JsonNode moved = api.moveReleaseRequestSource(params.get("repositoryId"), params.get("requestId"),
                body(recorded).path("branch").asText(), body(recorded).path("reason").asText());
        request(moved.path("from"));
        request(moved.path("to"));
        assertThat(moved.path("to").path("sourceMoves")).isNotEmpty();
    };

    /** {@code qits work list}: the table reads every entity's qualified id, archetype and status. */
    private static final Call LIST = (api, params, recorded) -> {
        List<JsonNode> listed = WorkEntities.list(api.projectWork(params.get("projectId"), null, null, null));
        assertThat(listed).hasSize(recorded(recorded, "entities"));
        assertThat(listed).allSatisfy(e -> {
            assertThat(ProjectsApi.text(e, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(e, "archetype")).isNotBlank();
            assertThat(ProjectsApi.text(e, "status")).isNotBlank();
        });
    };

    /**
     * {@code qits work details}: the items naming this one as their supersededBy, from its project's
     * list filtered by the item's qualified id (the state's {@code entityId}), as the command sends it.
     * The command filters the answer again by the item's id or qualified id (for services older than
     * qits-248); the state names the item by its qualified id alone, and the answer names it by its
     * UUID, so the item's id is the one the answer's rows carry.
     */
    private static final Call CLOSES = (api, params, recorded) -> {
        String id = BASE.masters().json(recorded.state(), recorded.operationId()).path("entities").path(0)
                .path("supersededBy").asText();
        JsonNode entity = JsonNodeFactory.instance.objectNode().put("id", id)
                .put("qualifiedId", params.get("entityId")).put("projectId", params.get("projectId"));
        List<JsonNode> closes = WorkEntities.superseding(WorkEntities.list(api.projectWork(params.get("projectId"),
                null, null, null, null, params.get("entityId"), null)), entity);
        assertThat(closes).isNotEmpty().allSatisfy(e -> {
            assertThat(ProjectsApi.text(e, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(e, "supersededBy")).isNotBlank();
        });
    };

    /** The item read by its qualified id: what details prints, update and status take its archetype from. */
    private static final Call GET = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(item, "status")).isNotBlank();
    };

    /** {@code qits work update} with nothing piped in reads the item's archetype for its schema. */
    private static final Call GET_ARCHETYPE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.work(params.get("qualifiedId")), "archetype")).isNotBlank();

    /** A work item named by its qualified id, as {@code qits workspaces} and {@code qits wait} key it: its id. */
    private static final Call WORK_ID = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "id")).isNotBlank();
        assertThat(ProjectsApi.text(item, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
    };

    /** The status line's state of an item: its status, and whether and why it is blocked. */
    private static final Call WORK_STATE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.work(params.get("qualifiedId")), "status")).isNotBlank();

    /** {@code qits work transition} starts from the item as it stands. */
    private static final Call GET_FOR_TRANSITION = (api, params, recorded) -> {
        JsonNode state = WorkEntities.currentState(api.work(params.get("qualifiedId")));
        assertThat(ProjectsApi.text(state, "title")).isNotBlank();
        assertThat(ProjectsApi.text(state, "status")).isNotBlank();
    };

    private static final Call COMMENTS = (api, params, recorded) -> {
        List<JsonNode> thread = ProjectsApi.entries(api.workComments(params.get("qualifiedId")), "comment");
        assertThat(thread).hasSize(recorded(recorded, "entries"));
        assertThat(thread).allSatisfy(c -> assertThat(ProjectsApi.text(c, "author")).isNotBlank());
    };

    private static final Call CHILDREN = (api, params, recorded) -> {
        List<JsonNode> children = WorkEntities.children(api.workChildren(params.get("qualifiedId")));
        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(c -> assertThat(ProjectsApi.text(c, "qualifiedId")).isNotBlank());
    };

    private static final Call CREATE = (api, params, recorded) -> {
        JsonNode created = api.createWork(body(recorded));
        assertThat(ProjectsApi.text(created, "qualifiedId")).isNotBlank();
        assertThat(ProjectsApi.text(created, "archetype")).isEqualTo(body(recorded).path("archetype").asText());
    };

    /** The schema printed when nothing is put in: WorkPayload reads its properties and required. */
    private static Call schema(String door) {
        return (api, params, recorded) -> {
            JsonNode schema = api.archetypeSchema("TICKET", door);
            assertThat(schema.path("properties").isObject()).isTrue();
            assertThat(schema.path("required").isArray()).isTrue();
        };
    }

    private static final Call PATCH = (api, params, recorded) -> {
        JsonNode patched = api.patchWork(params.get("qualifiedId"), body(recorded));
        assertThat(ProjectsApi.text(patched, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(patched, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    /** Keyed by the qualified id as sent, and answered keyed the same: the table prints that entry. */
    private static final Call TRANSITION = (api, params, recorded) -> {
        String key = params.get("qualifiedId");
        assertThat(body(recorded).has(key)).as("the recorded request is keyed by the qualified id").isTrue();
        JsonNode answer = api.transitionWork(body(recorded));
        assertThat(ProjectsApi.text(answer.path(key), "qualifiedId")).isEqualTo(key);
        assertThat(ProjectsApi.text(answer.path(key), "archetype"))
                .isEqualTo(body(recorded).path(key).path("archetype").asText());
    };

    /**
     * {@code qits work status} finds the item's archetype in the registry and reads its lifecycle.
     * The mock answers the open-ended list with one entry, so the row finds that one by its name.
     */
    private static final Call REGISTRY_READ = (api, params, recorded) -> {
        JsonNode registry = api.archetypes();
        assertThat(registry.path("archetypes")).isNotEmpty();
        String archetype = ProjectsApi.text(registry.path("archetypes").get(0), "archetype");
        assertThat(archetype).isNotBlank();
        JsonNode declared = WorkEntities.declared(registry, archetype);
        assertThat(declared).isNotNull();
        assertThat(declared.path("lifecycle").isArray()).isTrue();
        assertThat(declared.path("lifecycle")).isNotEmpty();
    };

    private static final Call STATUS = (api, params, recorded) -> {
        JsonNode moved = api.setWorkStatus(params.get("qualifiedId"), body(recorded));
        assertThat(ProjectsApi.text(moved, "status")).isEqualTo(body(recorded).path("target").asText());
    };

    private static final Call COMMENT = (api, params, recorded) -> {
        JsonNode comment = api.addWorkComment(params.get("qualifiedId"), body(recorded)).path("comment");
        assertThat(ProjectsApi.text(comment, "id")).isNotBlank();
        assertThat(ProjectsApi.text(comment, "author")).isNotBlank();
        assertThat(ProjectsApi.text(comment, "createdAt")).isNotBlank();
    };

    private static final Call EDIT_COMMENT = (api, params, recorded) -> {
        JsonNode comment = api.editWorkComment(params.get("qualifiedId"), params.get("commentId"), body(recorded))
                .path("comment");
        assertThat(ProjectsApi.text(comment, "id")).isEqualTo(params.get("commentId"));
        assertThat(ProjectsApi.text(comment, "updatedAt")).isNotBlank();
    };

    /** The changelog titles every ticket a subject names, and reads nothing else of it. */
    private static final Call TITLE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.work(params.get("qualifiedId")), "title")).isNotBlank();

    /** The changelog lists the commits that are not the request's own folds, by short hash, subject and author. */
    private static final Call RELEASE_COMMITS = (api, params, recorded) -> {
        JsonNode commits = api.releaseRequestCommits(params.get("repositoryId"), params.get("requestId")).path("commits");
        assertThat(commits.size()).isEqualTo(recorded(recorded, "commits"));
        assertThat(commits).allSatisfy(c -> {
            assertThat(c.path("fold").isBoolean()).isTrue();
            assertThat(ProjectsApi.text(c, "shortHash")).isNotBlank();
            assertThat(ProjectsApi.text(c, "message")).isNotBlank();
            assertThat(ProjectsApi.text(c, "author")).isNotBlank();
        });
        // Not "some are not folds": the mock answers every element in the first one's shape, and
        // the recording's first commit is the fold.
    };

    /**
     * Every command that takes a release request finds it among the repository's requests of every
     * state, by its logical id (qits-1158) or its uuid's start, and sends the uuid it found.
     */
    private static final Call FIND = (api, params, recorded) -> {
        JsonNode requests = api.releaseRequests(params.get("repositoryId"), "all").path("requests");
        assertThat(requests).isNotEmpty().allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
        });
        JsonNode first = requests.get(0);
        JsonNode found = ReleaseRequestRefs.find(requests, ProjectsApi.text(first, "qualifiedId").toUpperCase(),
                ProjectsApi.text(first, "repoName"), "");
        assertThat(ProjectsApi.text(found, "id")).isEqualTo(ProjectsApi.text(first, "id"));
    };

    /**
     * {@code qits artifacts publish changelog} finds the earlier releases that published no changelog
     * among the repository's requests of every state (qits-1193): it reads each one's id, logical id
     * and the version it cut, and skips a request that cut none.
     */
    private static final Call EARLIER_RELEASES = (api, params, recorded) -> {
        JsonNode requests = api.releaseRequests(params.get("repositoryId"), "all").path("requests");
        assertThat(requests).isNotEmpty().allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "qualifiedId")).isNotBlank();
            assertThat(r.path("version").isTextual() || r.path("version").isNull()).isTrue();
        });
        assertThat(requests).anySatisfy(r -> assertThat(ProjectsApi.text(r, "version")).isNotBlank());
    };

    /** The repository's open requests, its default list: what {@code list --repository} and the tui show. */
    private static final Call OPEN_REQUESTS = (api, params, recorded) -> {
        JsonNode requests = api.releaseRequests(params.get("repositoryId"), null).path("requests");
        assertThat(requests.size()).isEqualTo(recorded(recorded, "requests"));
        assertThat(requests).allSatisfy(r -> {
            assertThat(ReleaseRequestRefs.label(r)).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
        });
    };

    /**
     * The list's FREEZE column: the active request frozen, the next one queued behind it. The mock
     * answers every request in the first one's shape, the queued one, so the row asserts the cell
     * the queued request gets; the frozen one's cell is the same freeze the other list rows bind.
     */
    private static final Call FROZEN_AND_QUEUED = (api, params, recorded) -> {
        JsonNode requests = recorded.operationId().equals("listProjectReleaseRequests")
                ? api.projectReleaseRequests(params.get("projectId"), null).path("requests")
                : api.releaseRequests(params.get("repositoryId"), null).path("requests");
        assertThat(requests.size()).isEqualTo(recorded(recorded, "requests"));
        assertThat(requests).extracting(ReleaseRequestCommand::freezeCell).contains("queued");
        assertThat(requests).anySatisfy(r -> assertThat(ProjectsApi.text(r, "queuedBehindQualifiedId")).isNotBlank());
    };

    /** {@code qits tui}'s version dropdown: the released requests' versions. */
    private static final Call RELEASED_VERSIONS = (api, params, recorded) ->
            assertThat(api.releaseRequests(params.get("repositoryId"), "RELEASED").path("requests")).isNotEmpty()
                    .allSatisfy(r -> assertThat(ProjectsApi.text(r, "version")).isNotBlank());

    private static final Call BLOCK = (api, params, recorded) -> {
        JsonNode block = api.setWorkBlocked(params.get("qualifiedId"), body(recorded)).path("block");
        assertThat(block.path("blocked").asBoolean()).isTrue();
        assertThat(ProjectsApi.text(block, "blockSource")).isNotBlank();
        assertThat(ProjectsApi.text(block, "blockReason")).isEqualTo(body(recorded).path("reason").asText());
    };

    private static final Call DELETE = (api, params, recorded) ->
            assertThat(api.deleteWork(params.get("qualifiedId")).path("success").asBoolean()).isTrue();

    /** A page as `dossier get`, `put` and `move` print it. */
    private static void page(JsonNode page) {
        assertThat(ProjectsApi.text(page, "slug")).isNotBlank();
        assertThat(ProjectsApi.text(page, "title")).isNotBlank();
        assertThat(page.path("version").isNumber()).isTrue();
        assertThat(page.path("position").isNumber()).isTrue();
    }

    private static final Call DOSSIER = (api, params, recorded) -> {
        JsonNode pages = api.workDossier(params.get("qualifiedId")).path("pages");
        assertThat(pages.size()).isEqualTo(recorded(recorded, "pages"));
        pages.forEach(ProjectsContract::page);
    };

    private static final Call DOSSIER_PAGE = (api, params, recorded) -> {
        JsonNode page = api.workDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"));
        page(page);
        assertThat(ProjectsApi.text(page, "body")).isNotBlank();
    };

    private static final Call DOSSIER_CREATE = (api, params, recorded) ->
            page(api.createWorkDossierPage(params.get("qualifiedId"), body(recorded)));

    private static final Call DOSSIER_PUT = (api, params, recorded) ->
            page(api.putWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"), body(recorded)));

    private static final Call DOSSIER_MOVE = (api, params, recorded) ->
            page(api.moveWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"), body(recorded)));

    private static final Call DOSSIER_DELETE = (api, params, recorded) ->
            assertThat(api.deleteWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"))
                    .path("success").asBoolean()).isTrue();

    private static final Call INLINE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.inlineWorkDossierAsset(params.get("qualifiedId"), body(recorded)),
                    "markdown")).isNotBlank();

    /** {@code qits work list}'s NEEDS: some item names another it needs, at a status not met yet. */
    private static final Call LIST_NEEDING = (api, params, recorded) -> {
        List<JsonNode> listed = WorkEntities.list(api.projectWork(params.get("projectId"), null, null, null));
        assertThat(listed).isNotEmpty().allSatisfy(e -> assertThat(ProjectsApi.text(e, "qualifiedId")).isNotBlank());
        assertThat(listed).anySatisfy(e -> {
            assertThat(WorkEntities.needsSomething(e)).isTrue();
            assertThat(WorkEntities.needsCell(e)).contains("@");
        });
    };

    /** {@code qits work details}' needs: each item it needs, the status, and where that item stands now. */
    private static final Call GET_NEEDING = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(WorkEntities.needs(item, false)).isNotEmpty().allSatisfy(n ->
                assertThat(WorkEntities.needLine(n)).contains(" at ").contains("(now "));
        assertThat(item.path("unmetNeeds").asInt()).isPositive();
    };

    /**
     * The branch deliveries read of {@code qits wait branch} (epic qits-1676, task qits-1723): the
     * look before a wait is declared. Pending until qits-maintenance bumps {@code
     * qits.projects-golden-masters.version} to a release that records this state (qits-projects-service
     * 2026.1028.230057 does); on that bump write the pact again with {@code -Dgolden.update=true}.
     */
    static final String BRANCH_RELEASED = "branch ticket/qits-9001 is RELEASED in qits-demo-service";
    static final String BRANCH_DELIVERIES_OPERATION = "getBranchDeliveries";
    /**
     * What the look reads: everywhere, and each delivery's repository, step and request. The
     * deliveries are open-ended: a branch is in as many repositories as it was pushed to, and a
     * pinned length would break the provider the day its recording grows one.
     */
    static final List<String> BRANCH_DELIVERIES_FIELDS = List.of("everywhere", "deliveries[].repository",
            "deliveries[].repoId", "deliveries[].step", "deliveries[].qualifiedId");

    /** {@code qits wait branch <branch> --until released}: the recorded branch, no repository, no project. */
    private static final Call BRANCH_DELIVERIES = (api, params, recorded) -> {
        JsonNode answer = api.branchDeliveries(query(recorded, "branch"), null, null);
        assertThat(ProjectsApi.text(answer, "everywhere")).isNotBlank();
        assertThat(answer.path("deliveries")).isNotEmpty().allSatisfy(d -> {
            assertThat(ProjectsApi.text(d, "repository")).isNotBlank();
            assertThat(ProjectsApi.text(d, "step")).isNotBlank();
            assertThat(ProjectsApi.text(d, "qualifiedId")).isNotBlank();
        });
    };

    /**
     * {@code qits projects delivery}: the project by id, and its delivery settings where the
     * recording carries them. A recording without them is a service older than the loop, whose
     * settings the command prints as the defaults; see {@link #DELIVERY}.
     */
    /** The provider state the guide is recorded in. */
    static final String GUIDE_SHIPS = "the development guide ships";

    /** {@code qits help guide --for workstation}: the variant and its markdown. */
    private static final Call GUIDE = (api, params, recorded) -> {
        JsonNode guide = api.developmentGuide("workstation");
        assertThat(ProjectsApi.text(guide, "variant")).isEqualTo("workstation");
        assertThat(ProjectsApi.text(guide, "markdown")).isNotBlank();
    };

    /** {@code qits help guide --list}: each variant's name and description. */
    private static final Call GUIDE_VARIANTS = (api, params, recorded) -> {
        JsonNode variants = api.developmentGuideVariants().path("variants");
        assertThat(variants.isArray()).isTrue();
        assertThat(ProjectsApi.text(variants.get(0), "name")).isNotBlank();
    };

    private static final Call DELIVERY_READ = (api, params, recorded) -> {
        JsonNode project = api.project(params.get("projectId"));
        assertThat(ProjectsApi.text(project, "id")).isEqualTo(params.get("projectId"));
        JsonNode delivery = project.path("delivery");
        if (BASE.held(recorded.state(), recorded.operationId(), DELIVERY).isEmpty()) {
            return;
        }
        assertThat(delivery.path("enabled").isBoolean()).isTrue();
        assertThat(delivery.path("maxRunning").isInt()).isTrue();
        assertThat(delivery.path("startRefined").isBoolean()).isTrue();
        assertThat(delivery.path("approves").isArray()).isTrue();
    };

    /** {@code qits projects desk prompt}: the recorded text; the table prints delivered or queued, the position and the command. */
    private static final Call DESK_PROMPT = (api, params, recorded) -> {
        JsonNode answer = api.deskPrompt(params.get("projectId"), body(recorded).path("text").asText());
        assertThat(ProjectsApi.text(answer, "commandId")).isNotBlank();
        assertThat(answer.path("delivered").isBoolean()).isTrue();
        assertThat(answer.path("queued").isBoolean()).isTrue();
    };

    /** {@code qits projects desk answer}: the last answer, whose text is null until the desk has answered. */
    private static final Call DESK_ANSWER = (api, params, recorded) -> {
        JsonNode answer = api.deskAnswer(params.get("projectId"));
        assertThat(answer.has("text")).isTrue();
        assertThat(answer.path("text").isTextual() || answer.path("text").isNull()).isTrue();
    };

    /**
     * {@code qits projects desk triggers}: the recorded messages, sent with no {@code limit} query
     * (the command's own default, read as the service's); the table prints one row per message.
     */
    private static final Call DESK_TRIGGERS = (api, params, recorded) -> {
        JsonNode answer = api.deskTriggers(params.get("projectId"), null);
        assertThat(answer.path("messages")).isNotEmpty();
        answer.path("messages").forEach(m -> {
            assertThat(ProjectsApi.text(m, "openedAt")).isNotBlank();
            assertThat(ProjectsApi.text(m, "state")).isNotBlank();
            assertThat(m.path("attempts").isInt()).isTrue();
        });
    };

    /** {@code qits projects agents list}: every named agent, the desk first. */
    private static final Call LIST_AGENTS = (api, params, recorded) -> {
        JsonNode answer = api.agents(params.get("projectId"));
        assertThat(answer.path("agents")).isNotEmpty();
        answer.path("agents").forEach(a -> {
            assertThat(ProjectsApi.text(a, "name")).isNotBlank();
            assertThat(ProjectsApi.text(a, "surface")).isNotBlank();
            assertThat(ProjectsApi.text(a, "state")).isNotBlank();
        });
    };

    /**
     * {@code qits projects agents prompt}: the recorded text; the table prints delivered, queued or
     * (with the desk container starting) waiting.
     */
    private static final Call PROMPT_AGENT = (api, params, recorded) -> {
        JsonNode answer = api.agentPrompt(params.get("projectId"), params.get("name"),
                body(recorded).path("text").asText());
        assertThat(answer.path("delivered").isBoolean()).isTrue();
        assertThat(answer.path("queued").isBoolean()).isTrue();
    };

    /** {@code qits projects agents answer}: the last answer, whose text is null until the agent has answered. */
    private static final Call AGENT_ANSWER = (api, params, recorded) -> {
        JsonNode answer = api.agentAnswer(params.get("projectId"), params.get("name"));
        assertThat(answer.has("text")).isTrue();
        assertThat(answer.path("text").isTextual() || answer.path("text").isNull()).isTrue();
    };

    /** {@code qits ideas list}: the project's ideas, without the archived ones. */
    private static final Call LIST_IDEAS = (api, params, recorded) -> {
        // No length: the list grows as a project writes ideas, so the mock answers its shortest.
        JsonNode ideas = api.ideas(params.get("projectId"), false, null).path("ideas");
        assertThat(ideas).isNotEmpty().allSatisfy(ProjectsContract::idea);
    };

    /** An idea as a row prints it. */
    private static void idea(JsonNode idea) {
        assertThat(ProjectsApi.text(idea, "id")).isNotBlank();
        assertThat(ProjectsApi.text(idea, "title")).isNotBlank();
        assertThat(ProjectsApi.text(idea, "status")).isNotBlank();
        assertThat(idea.path("archived").isBoolean()).isTrue();
    }

    /** {@code qits ideas details}: the idea, the work it inspired and the ideas it enables. */
    private static final Call GET_IDEA = (api, params, recorded) -> {
        JsonNode idea = api.idea(params.get("ideaId"));
        idea(idea);
        assertThat(ProjectsApi.text(idea, "id")).isEqualTo(params.get("ideaId"));
        assertThat(idea.path("inspired")).isNotEmpty().allSatisfy(w -> {
            assertThat(ProjectsApi.text(w, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(w, "status")).isNotBlank();
        });
        assertThat(idea.path("enables")).isNotEmpty().allSatisfy(ProjectsContract::ideaRef);
    };

    /** An idea in brief, as details lists the ones it enables. */
    private static void ideaRef(JsonNode idea) {
        assertThat(ProjectsApi.text(idea, "id")).isNotBlank();
        assertThat(idea.path("archived").isBoolean()).isTrue();
    }

    /** {@code qits ideas spawn}: the pre-fill the idea gives the new item. */
    private static final Call SPAWN_FROM = (api, params, recorded) -> {
        JsonNode prefill = IdeasCommand.SpawnCommand.prefill(api.idea(params.get("ideaId")));
        assertThat(ProjectsApi.text(prefill, "project")).isNotBlank();
        assertThat(ProjectsApi.text(prefill, "title")).isNotBlank();
        assertThat(ProjectsApi.text(prefill, "inspiredBy")).isEqualTo(params.get("ideaId"));
    };

    private static final Call CREATE_IDEA = (api, params, recorded) -> {
        JsonNode created = api.createIdea(body(recorded));
        idea(created);
        assertThat(ProjectsApi.text(created, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    /** A title the service refuses: the client fails with its status and the service's sentence. */
    private static final Call CREATE_IDEA_REFUSED = (api, params, recorded) ->
            assertThatThrownBy(() -> api.createIdea(body(recorded)))
                    .isInstanceOfSatisfying(CliFailure.class, f -> {
                        assertThat(f.status()).isEqualTo(400);
                        assertThat(f.getMessage()).contains(ProjectsApi.text(
                                BASE.masters().json(recorded.state(), recorded.operationId()), "message"));
                    });

    private static final Call PATCH_IDEA = (api, params, recorded) -> {
        JsonNode patched = api.patchIdea(params.get("enabledIdeaId"), body(recorded));
        idea(patched);
        assertThat(ProjectsApi.text(patched, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    private static final Call ARCHIVE_IDEA = (api, params, recorded) -> {
        JsonNode archived = api.archiveIdea(params.get("enabledIdeaId"), body(recorded).path("reason").asText());
        idea(archived);
        assertThat(archived.path("archived").asBoolean()).isTrue();
        assertThat(ProjectsApi.text(archived, "archiveReason")).isEqualTo(body(recorded).path("reason").asText());
    };

    /** {@code qits ideas update} with {@code {"priority":n}}: the idea, with the priority its PRIORITY column prints. */
    private static final Call PRIORITIZE_IDEA = (api, params, recorded) -> {
        JsonNode patched = api.patchIdea(params.get("ideaId"), body(recorded));
        idea(patched);
        assertThat(patched.path("priority").asInt()).isEqualTo(body(recorded).path("priority").asInt());
    };

    /** {@code qits ideas rank}: a drop below the anchor; the table prints each written row's id and priority. */
    private static final Call RANK_IDEA = (api, params, recorded) -> {
        JsonNode body = body(recorded);
        String where = body.has("above") ? "above" : "below";
        JsonNode items = api.reprioritizeIdea(params.get("ideaId"), where, body.path(where).asText()).path("items");
        assertThat(items).isNotEmpty().allSatisfy(i -> {
            assertThat(ProjectsApi.text(i, "id")).isNotBlank();
            assertThat(i.path("priority").isIntegralNumber()).isTrue();
        });
    };

    private static final Call UNARCHIVE_IDEA = (api, params, recorded) -> {
        JsonNode unarchived = api.unarchiveIdea(params.get("archivedIdeaId"));
        idea(unarchived);
        assertThat(unarchived.path("archived").asBoolean()).isFalse();
    };

    /** {@code qits work dispatch}: the recorded mode, no rule; the table prints the mode, phase, branch and assignee. */
    private static final Call DISPATCH = (api, params, recorded) -> {
        JsonNode dispatch = api.dispatchWork(params.get("qualifiedId"), body(recorded).path("mode").asText(),
                null).path("dispatch");
        assertThat(ProjectsApi.text(dispatch, "mode")).isEqualTo(body(recorded).path("mode").asText());
        assertThat(ProjectsApi.text(dispatch, "phase")).isNotBlank();
        assertThat(ProjectsApi.text(dispatch, "branch")).isNotBlank();
        assertThat(ProjectsApi.text(dispatch, "assignee")).isNotBlank();
    };

    /** {@code qits work unblock}: the flag as the write left it. */
    private static final Call UNBLOCK = (api, params, recorded) -> {
        JsonNode block = api.setWorkBlocked(params.get("qualifiedId"), body(recorded)).path("block");
        assertThat(block.path("blocked").isBoolean()).isTrue();
        assertThat(block.path("blocked").asBoolean()).isFalse();
        assertThat(ProjectsApi.text(block, "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(block, "status")).isNotBlank();
    };

    /** Every `design` command finds the item's open refinement by its id first. */
    private static final Call OPEN_REFINEMENT = (api, params, recorded) ->
            assertThat(WorkDesignCommand.refinement(api, params.get("qualifiedId"))).isNotBlank();

    /** A design as `design list` prints it: no document. */
    private static void design(JsonNode d) {
        assertThat(ProjectsApi.text(d, "id")).isNotBlank();
        assertThat(ProjectsApi.text(d, "title")).isNotBlank();
        assertThat(d.path("version").isNumber()).isTrue();
        assertThat(d.path("htmlBytes").isNumber()).isTrue();
        assertThat(d.path("inUse").isBoolean()).isTrue();
        assertThat(ProjectsApi.text(d, "updatedAt")).isNotBlank();
    }

    private static final Call DESIGNS = (api, params, recorded) -> {
        JsonNode designs = api.refinementDesigns(params.get("refinementId")).path("designs");
        assertThat(designs.size()).isEqualTo(recorded(recorded, "designs"));
        designs.forEach(ProjectsContract::design);
    };

    /** `design get` prints the whole document, which a rewrite sends back with this version. */
    private static final Call DESIGN_GET = (api, params, recorded) -> {
        JsonNode d = api.refinementDesign(params.get("refinementId"), params.get("designId"));
        design(d);
        assertThat(ProjectsApi.text(d, "html")).isNotBlank();
    };

    private static final Call DESIGN_ADD = (api, params, recorded) -> {
        JsonNode d = api.addRefinementDesign(params.get("refinementId"), body(recorded));
        design(d);
        assertThat(ProjectsApi.text(d, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    private static final Call DESIGN_UPDATE = (api, params, recorded) -> {
        JsonNode d = api.updateRefinementDesign(params.get("refinementId"), params.get("designId"), body(recorded));
        design(d);
        assertThat(ProjectsApi.text(d, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    /** A refinement room as `refinement open` and `show` print it: its id, its item, its label and its status. */
    private static void room(JsonNode room) {
        assertThat(ProjectsApi.text(room, "id")).isNotBlank();
        assertThat(ProjectsApi.text(room, "entityId")).isNotBlank();
        assertThat(ProjectsApi.text(room, "label")).isNotBlank();
        assertThat(ProjectsApi.text(room, "runtimeStatus")).isNotBlank();
    }

    /** `refinement open` opens the room first, and brings up the room whose id that answers. */
    private static final Call OPEN_ROOM = (api, params, recorded) -> {
        JsonNode room = api.openWorkRefinement(params.get("qualifiedId")).path("refinement");
        assertThat(ProjectsApi.text(room, "id")).isNotBlank();
    };

    /** An item that cannot be refined now: the client fails with the status, and nothing is brought up. */
    private static final Call OPEN_REFUSED = (api, params, recorded) ->
            assertThatThrownBy(() -> api.openWorkRefinement(params.get("qualifiedId")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> {
                        assertThat(f.status()).isEqualTo(409);
                        assertThat(f.getMessage()).contains(ProjectsApi.text(
                                BASE.masters().json(recorded.state(), recorded.operationId()), "message"));
                    });

    private static final Call ENSURE_ROOM = (api, params, recorded) -> {
        JsonNode answer = api.ensureRefinement(params.get("refinementId"));
        room(answer.path("refinement"));
        assertThat(ProjectsApi.text(answer, "technicalProcessId")).isNotBlank();
    };

    private static final Call SHOW_ROOM = (api, params, recorded) ->
            room(api.workRefinement(params.get("qualifiedId")).path("refinement"));

    /** `refinement discard` finds the room as `design` does, with its own sentence for an item with none. */
    private static final Call DISCARD_FINDS_THE_ROOM = (api, params, recorded) ->
            assertThat(WorkDesignCommand.refinement(api, params.get("qualifiedId"), "")).isNotBlank();

    /** The table prints a confirmation and reads nothing of the answer: the status is all, so the pact binds no body. */
    private static final Call DISCARD_ROOM = (api, params, recorded) -> api.discardRefinement(params.get("refinementId"));

    /** {@code qits repositories branches}: the table reads each branch's name, parent, ahead, behind and cleanup. */
    private static final Call BRANCHES = (api, params, recorded) -> {
        JsonNode branches = api.branches(params.get("repositoryId")).path("branches");
        assertThat(branches.size()).isEqualTo(recorded(recorded, "branches"));
        assertThat(branches).isNotEmpty().allSatisfy(b -> {
            assertThat(ProjectsApi.text(b, "name")).isNotBlank();
            assertThat(b.path("canCleanup").isBoolean()).isTrue();
        });
    };

    /** {@code qits repositories commits}: hash, date, author, files and subject of each commit. */
    private static final Call COMMITS = (api, params, recorded) -> {
        String branch = query(recorded, "branch");
        JsonNode answer = api.commits(params.get("repositoryId"), branch);
        assertThat(ProjectsApi.text(answer, "branch")).isEqualTo(branch);
        assertThat(answer.path("commits")).isNotEmpty().allSatisfy(c -> {
            assertThat(ProjectsApi.text(c, "shortHash")).isNotBlank();
            assertThat(ProjectsApi.text(c, "date")).isNotBlank();
            assertThat(ProjectsApi.text(c, "author")).isNotBlank();
            assertThat(ProjectsApi.text(c, "message")).isNotBlank();
            assertThat(c.path("files").isArray()).isTrue();
        });
    };

    /** {@code qits repositories commit-subjects}: the branch, the counts and the commits that do not comply. */
    private static final Call COMMIT_SUBJECTS = (api, params, recorded) -> {
        JsonNode answer = api.commitSubjects(params.get("repositoryId"), null, null);
        assertThat(ProjectsApi.text(answer, "branch")).isNotBlank();
        for (String count : List.of("total", "nonComplying")) {
            assertThat(answer.path("counts").path(count).isNumber()).as(count).isTrue();
        }
        assertThat(answer.path("nonComplying")).isNotEmpty().allSatisfy(c -> {
            assertThat(ProjectsApi.text(c, "shortHash")).isNotBlank();
            assertThat(ProjectsApi.text(c, "authorName")).isNotBlank();
            assertThat(ProjectsApi.text(c, "subject")).isNotBlank();
        });
    };

    /** {@code qits repositories archive}: outcome, whether it is now archived, branch, request and detail. */
    private static final Call ARCHIVE_REPOSITORY = (api, params, recorded) -> {
        JsonNode answer = api.archiveRepository(params.get("repositoryId"));
        assertThat(ProjectsApi.text(answer, "outcome")).isNotBlank();
        assertThat(answer.path("archived").isBoolean()).isTrue();
        assertThat(ProjectsApi.text(answer, "detail")).isNotBlank();
    };

    /** {@link #ARCHIVE_REPOSITORY}'s mirror. */
    private static final Call UNARCHIVE_REPOSITORY = (api, params, recorded) -> {
        JsonNode answer = api.unarchiveRepository(params.get("repositoryId"));
        assertThat(ProjectsApi.text(answer, "outcome")).isNotBlank();
        assertThat(answer.path("archived").isBoolean()).isTrue();
        assertThat(ProjectsApi.text(answer, "detail")).isNotBlank();
    };

    /** {@code qits repositories backup reset}: the recorded reason as sent; the table prints the decision. */
    private static final Call BACKUP_RESET = (api, params, recorded) -> {
        JsonNode answer = api.backupReset(params.get("repositoryId"), body(recorded).path("reason").asText());
        for (String field : List.of("kind", "pushedSha", "decidedBy", "reason")) {
            assertThat(ProjectsApi.text(answer, field)).as(field).isNotBlank();
        }
    };

    /** `design` asks the item for its refinement first; an item with none answers null. */
    private static final Call NO_REFINEMENT = (api, params, recorded) ->
            assertThat(api.workRefinement(params.get("qualifiedId")).path("refinement").isNull()).isTrue();

    private static final Call COMMIT_CHANGES = (api, params, recorded) -> {
        JsonNode files = api.commitChanges(params.get("repositoryId"), segmentAfter(recorded, "/commits/"), null)
                .path("files");
        assertThat(files).isNotEmpty().allSatisfy(f -> {
            assertThat(ProjectsApi.text(f, "path")).isNotBlank();
            assertThat(ProjectsApi.text(f, "changeType")).isNotBlank();
        });
    };

    private static final Call COMMIT_DIFF = (api, params, recorded) -> {
        String path = query(recorded, "path");
        JsonNode diff = api.commitDiff(params.get("repositoryId"), segmentAfter(recorded, "/commits/"), path, null);
        assertThat(ProjectsApi.text(diff, "path")).isEqualTo(path);
        assertThat(ProjectsApi.text(diff, "diff")).isNotBlank();
    };

    /** {@code qits release-request list} without a repository: the table reads these of each request. */
    private static final Call PROJECT_RELEASE_REQUESTS = (api, params, recorded) -> {
        JsonNode requests = api.projectReleaseRequests(params.get("projectId"), null).path("requests");
        assertThat(requests.size()).isEqualTo(recorded(recorded, "requests"));
        assertThat(requests).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "repoName")).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
            // ITEMS: the labels, else the branches; a recording older than items may hold neither.
            assertThat(ReleaseRequestRefs.items(r)).isNotNull();
            assertThat(ProjectsApi.text(r, "updatedAt")).isNotBlank();
        });
    };

    /**
     * {@code qits release-request approve} and {@code decline} find the request in the project by its
     * logical id, with no repository named: the answer carries the repository and the uuid the
     * decision door takes, and the fold judged when no --sha is given (qits-1287).
     */
    private static final Call FIND_IN_PROJECT = (api, params, recorded) -> {
        JsonNode request = api.projectReleaseRequest(params.get("projectId"), params.get("requestQualifiedId"))
                .path("request");
        assertThat(ProjectsApi.text(request, "qualifiedId")).isEqualTo(params.get("requestQualifiedId"));
        assertThat(ProjectsApi.text(request, "id")).isNotBlank();
        assertThat(ProjectsApi.text(request, "repoId")).isNotBlank();
        assertThat(request.path("mergedSha").isTextual() || request.path("mergedSha").isNull()).isTrue();
    };

    /** The decision as sent: the recorded fold and note. The table prints the request that comes back. */
    private static final Call APPROVE = (api, params, recorded) -> {
        JsonNode request = api.approveReleaseRequest(params.get("repositoryId"), params.get("requestId"),
                body(recorded).path("mergedSha").asText(), body(recorded).path("note").asText()).path("request");
        request(request);
        assertThat(ProjectsApi.text(request, "approvalState")).isEqualTo("APPROVED");
        assertThat(ProjectsApi.text(request, "mergedSha")).isEqualTo(body(recorded).path("mergedSha").asText());
    };

    private static final Call DECLINE = (api, params, recorded) -> {
        JsonNode request = api.declineReleaseRequest(params.get("repositoryId"), params.get("requestId"),
                body(recorded).path("mergedSha").asText(), body(recorded).path("note").asText()).path("request");
        request(request);
        assertThat(ProjectsApi.text(request, "approvalState")).isEqualTo("DECLINED");
        assertThat(ProjectsApi.text(request, "state")).isEqualTo("REJECTED");
        assertThat(ProjectsApi.text(request, "mergedSha")).isEqualTo(body(recorded).path("mergedSha").asText());
    };

    /** The override as sent: the recorded upstream request and note. The table prints the request that comes back. */
    private static final Call OVERRIDE_UPSTREAM = (api, params, recorded) -> request(api.overrideReleaseRequestUpstream(
            params.get("repositoryId"), params.get("requestId"), body(recorded).path("upstreamRequestId").asText(),
            body(recorded).hasNonNull("note") ? body(recorded).path("note").asText() : null)
            .path("request"));

    /** {@code qits release-request regenerate}: the kind is the recording's path segment; it prints the run's id. */
    private static final Call REGENERATE = (api, params, recorded) -> {
        JsonNode run = api.rerunReleaseRequestAutomation(params.get("repositoryId"), params.get("requestId"),
                segmentAfter(recorded, "/automations/"));
        assertThat(ProjectsApi.text(run, "id")).isNotBlank();
    };

    /** {@code qits release-request rerun}: the phase is the recording's path segment; it prints the request. */
    private static final Call RERUN_PHASE = (api, params, recorded) -> request(api.rerunReleasePipelinePhase(
            params.get("repositoryId"), params.get("requestId"), segmentAfter(recorded, "/pipeline/"))
            .path("request"));

    // --- the table ------------------------------------------------------------------------------

    /** Every command that looks a project up by its id, slug or name before it calls anything else. */
    static final List<String> PROJECT_SCOPED = List.of("qits repositories list", "qits repositories create",
            "qits repositories remote-login", "qits repositories branches", "qits repositories commits",
            "qits repositories commit-changes", "qits repositories commit-diff", "qits repositories commit-subjects",
            "qits repositories archive", "qits repositories unarchive", "qits repositories backup reset",
            "qits release-request list", "qits release-request create", "qits release-request join",
            "qits release-request move", "qits release-request withdraw", "qits release-request regenerate", "qits release-request override",
            "qits release-request rerun",
            "qits release-request approve", "qits release-request decline", "qits ci runs", "qits ci run",
            "qits ci retry", "qits ci report show", "qits maintenance automations", "qits maintenance automation run",
            "qits workspaces list", "qits tui", "qits wait branch");

    /** Of those, every command that then looks a repository up by its id or name. */
    static final List<String> REPOSITORY_SCOPED = PROJECT_SCOPED.stream()
            .filter(c -> !c.equals("qits repositories list") && !c.equals("qits repositories create")
                    && !c.equals("qits release-request approve") && !c.equals("qits release-request decline")
                    && !c.equals("qits wait branch"))
            .toList();

    /** Every command that takes a release request and finds it among the repository's requests of every state. */
    static final List<String> REQUEST_SCOPED = List.of("qits release-request join", "qits release-request move",
            "qits release-request withdraw",
            "qits release-request regenerate", "qits release-request override", "qits release-request rerun",
            "qits maintenance automations", "qits maintenance automation run");

    /** Every command outside {@code qits work} that names a work item by its qualified id and sends its id. */
    static final List<String> WORK_ITEM_SCOPED = List.of("qits agents list", "qits agents prompt", "qits wait work");

    static final List<CliRow> ROWS = rows();

    public static final CliPact CONTRACT = BASE
            .client(ProjectsApi.class)
            .client(AgentMcpContract.CLIENT)
            .excluded("ProjectsApi#openApi", "the served OpenAPI document (GET /projects/q/openapi), which the payload"
                    + " commands read a request body's schema from: no operation of the provider, so no recording")
            .excluded("ProjectsApi#remoteLoginSocket", "the backup-remote sign-in terminal, a WebSocket")
            .excluded("ProjectsApi#client", "the client the doors are called with, handed to the sign-in socket")
            .excluded("ProjectsApi#repository", "a lookup over repositories(), whose rows assert the entries: the mock"
                    + " answers every entry alike, so a lookup by name would find it twice")
            .rows(ROWS)
            .rows(AgentMcpContract.ROWS);

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        // qits projects, and every lookup by project and repository
        rows.add(row("qits projects list", A_PROJECT, "listProjects", "projects", PROJECTS).consumes(PROJECT));
        rows.add(row("qits agents claude hook work-links", A_PROJECT, "listProjects", "projects", PROJECTS)
                .consumes("entries[].project.slug", "entries[].project.id", "entries[].project.name"));
        rows.add(row("qits projects delivery", A_PROJECT, "listProjects", "project", DELIVERY_READ)
                .consumes(Stream.concat(PROJECT.stream(), held(A_PROJECT, "listProjects", DELIVERY).stream()).toList()));
        PROJECT_SCOPED.forEach(c -> rows.add(row(c, A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT)));
        rows.add(row("qits projects desk prompt", A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT));
        rows.add(row("qits projects desk prompt", DESK_RUNNING, DESK_PROMPT_OPERATION, "deskPrompt", DESK_PROMPT)
                .consumes(held(DESK_RUNNING, DESK_PROMPT_OPERATION,
                        List.of("commandId", "state", "delivered", "queued", "position")))
                .request("POST /projects/api/projects/{projectId}/front-desk/prompt"));
        rows.add(row("qits projects desk answer", A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT));
        rows.add(row("qits projects desk answer", DESK_RUNNING, DESK_ANSWER_OPERATION, "deskAnswer", DESK_ANSWER)
                .consumes(held(DESK_RUNNING, DESK_ANSWER_OPERATION,
                        List.of("commandId", "sessionId", "state", "text", "at")))
                .request("GET /projects/api/projects/{projectId}/front-desk/last-answer"));
        rows.add(row("qits projects desk triggers", A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT));
        rows.add(row("qits projects desk triggers", DESK_RECEIVED_A_TRIGGER, DESK_TRIGGERS_OPERATION, "deskTriggers",
                DESK_TRIGGERS)
                .consumes(held(DESK_RECEIVED_A_TRIGGER, DESK_TRIGGERS_OPERATION, DESK_TRIGGERS_FIELDS))
                .request("GET /projects/api/projects/{projectId}/desk-triggers"));

        // qits projects agents (qits-2154): pending, the same way the front desk's rows above are.
        rows.add(row("qits projects agents list", A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT));
        rows.add(row("qits projects agents list", AGENT_RUNNING, LIST_AGENTS_OPERATION, "agents", LIST_AGENTS)
                .consumes(held(AGENT_RUNNING, LIST_AGENTS_OPERATION, AGENT_ROWS_FIELDS))
                .request("GET /projects/api/projects/{projectId}/agents"));
        rows.add(row("qits projects agents prompt", A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT));
        rows.add(row("qits projects agents prompt", AGENT_RUNNING, PROMPT_AGENT_OPERATION, "agentPrompt",
                PROMPT_AGENT)
                .consumes(held(AGENT_RUNNING, PROMPT_AGENT_OPERATION,
                        List.of("commandId", "state", "delivered", "queued", "position")))
                .request("POST /projects/api/projects/{projectId}/agents/{name}/prompt"));
        rows.add(row("qits projects agents answer", A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT));
        rows.add(row("qits projects agents answer", AGENT_RUNNING, AGENT_ANSWER_OPERATION, "agentAnswer",
                AGENT_ANSWER)
                .consumes(held(AGENT_RUNNING, AGENT_ANSWER_OPERATION,
                        List.of("commandId", "sessionId", "state", "text", "at")))
                .request("GET /projects/api/projects/{projectId}/agents/{name}/last-answer"));

        REPOSITORY_SCOPED.forEach(c -> rows.add(row(c, ONE_REPOSITORY, "listProjectRepositories", "repositories",
                FIND_REPOSITORY).consumes(REPOSITORY)));

        // qits wait branch (qits-1723): pending until the pinned golden masters carry the deliveries.
        rows.add(row("qits wait branch", BRANCH_RELEASED, BRANCH_DELIVERIES_OPERATION, "branchDeliveries",
                BRANCH_DELIVERIES)
                .consumes(held(BRANCH_RELEASED, BRANCH_DELIVERIES_OPERATION, BRANCH_DELIVERIES_FIELDS))
                .anyLength("deliveries")
                .request("GET /projects/api/branch-deliveries?branch=ticket/qits-9001"));

        // qits help guide (qits-1679): pending until the pinned golden masters carry the guide.
        rows.add(row("qits help guide", GUIDE_SHIPS, "getDevelopmentGuide", "developmentGuide", GUIDE)
                .consumes(held(GUIDE_SHIPS, "getDevelopmentGuide", List.of("variant", "markdown")))
                .request("GET /projects/api/guide?for=workstation"));
        rows.add(row("qits help guide", GUIDE_SHIPS, "listDevelopmentGuideVariants", "developmentGuideVariants",
                GUIDE_VARIANTS)
                .consumes(held(GUIDE_SHIPS, "listDevelopmentGuideVariants",
                        List.of("variants[].name", "variants[].description")))
                .request("GET /projects/api/guide/variants"));

        // qits repositories
        rows.add(row("qits repositories list", REPOSITORIES_IN_COMPONENTS, "listProjectRepositories", "repositories",
                REPOSITORIES).consumes(held(REPOSITORIES_IN_COMPONENTS, "listProjectRepositories",
                List.of("entries[].repository.name", "entries[].repository.archetype", "entries[].repository.component",
                        "entries[].repository.id"))));
        rows.add(row("qits repositories create", TO_CREATE_A_REPOSITORY_IN, "createProjectRepository",
                "createRepository", CREATE_REPOSITORY).consumes("repository.name", "repository.archetype",
                "repository.component", "repository.id", "wrapperPath"));
        rows.add(row("qits repositories remote-login", THREE_REPOSITORIES, "listProjectRepositories",
                "repositories", WRAPPER_REPOSITORY).consumes("wrapper.repositoryId"));
        rows.add(row("qits repositories remote-login", REPOSITORIES_IN_COMPONENTS, "backupSyncProject",
                "backupSyncProject", BACKUP_SYNC).consumes("scheduled"));
        rows.add(row("qits repositories remote-login", REPOSITORIES_IN_COMPONENTS, "listProjectRepositories",
                "repositories", BACKUPS).consumes("entries[].repository.name", "entries[].repository.backupUrl",
                "entries[].repository.lastBackup.outcome", "entries[].repository.lastBackup.at",
                "entries[].repository.lastBackup.detail"));
        rows.add(row("qits repositories branches", AWAITING_REQUEST, "listRepositoryBranches", "branches", BRANCHES)
                .consumes("branches[].name", "branches[].parent", "branches[].ahead", "branches[].behind",
                        "branches[].canCleanup"));
        rows.add(row("qits repositories commits", AWAITING_REQUEST, "listBranchCommits", "commits", COMMITS)
                .consumes("branch", "commits[].shortHash", "commits[].date", "commits[].author", "commits[].message",
                        "commits[].files"));
        rows.add(row("qits repositories commit-changes", AWAITING_REQUEST, "listCommitChanges", "commitChanges",
                COMMIT_CHANGES).consumes(held(AWAITING_REQUEST, "listCommitChanges",
                List.of("files[].path", "files[].oldPath", "files[].changeType"))));
        rows.add(row("qits repositories commit-diff", AWAITING_REQUEST, "getCommitFileDiff", "commitDiff",
                COMMIT_DIFF).consumes("path", "changeType", "diff"));
        rows.add(row("qits repositories commit-subjects", AWAITING_REQUEST, "measureCommitSubjects", "commitSubjects",
                COMMIT_SUBJECTS).consumes("branch", "counts.total", "counts.nonComplying",
                "nonComplying[].shortHash", "nonComplying[].authorName", "nonComplying[].subject"));
        rows.add(row("qits repositories archive", ARCHIVABLE_REPOSITORY, ARCHIVE_OPERATION, "archiveRepository",
                ARCHIVE_REPOSITORY)
                .consumes(held(ARCHIVABLE_REPOSITORY, ARCHIVE_OPERATION, ARCHIVE_ANSWER))
                .request("POST /projects/api/repositories/{repositoryId}/archive"));
        rows.add(row("qits repositories unarchive", ARCHIVABLE_REPOSITORY, UNARCHIVE_OPERATION,
                "unarchiveRepository", UNARCHIVE_REPOSITORY)
                .consumes(held(ARCHIVABLE_REPOSITORY, UNARCHIVE_OPERATION, ARCHIVE_ANSWER))
                .request("POST /projects/api/repositories/{repositoryId}/unarchive"));

        rows.add(CliRow.of("qits repositories backup reset", TWIN_WITH_UNRELATED_HISTORY, BACKUP_RESET_OPERATION,
                "ProjectsApi#backupReset", asThePerson(BACKUP_RESET))
                .consumes(held(TWIN_WITH_UNRELATED_HISTORY, BACKUP_RESET_OPERATION, BACKUP_RESET_ANSWER))
                .header("Authorization", "{authorization}")
                .request("POST /projects/api/repositories/{repositoryId}/backup-reset"));

        // qits release-request
        rows.add(row("qits release-request list", PENDING_REQUESTS, "listProjectReleaseRequests",
                "projectReleaseRequests", PROJECT_RELEASE_REQUESTS).consumes(held(PENDING_REQUESTS,
                "listProjectReleaseRequests", concat(List.of("requests[].id", "requests[].qualifiedId",
                        "requests[].repoName", "requests[].state", "requests[].approvalState", "requests[].priority",
                        "requests[].version", "requests[].updatedAt"), ITEMS, FREEZE)))
                .anyLength(LIST_OPEN_ENDED));
        rows.add(row("qits release-request list", AWAITING_REQUEST, "listRepositoryReleaseRequests", "releaseRequests",
                OPEN_REQUESTS).consumes(held(AWAITING_REQUEST, "listRepositoryReleaseRequests",
                concat(List.of("requests[].id", "requests[].qualifiedId", "requests[].state",
                        "requests[].priority", "requests[].version", "requests[].updatedAt"), ITEMS, FREEZE)))
                .anyLength(LIST_OPEN_ENDED));
        rows.add(row("qits release-request list", FROZEN_WITH_A_QUEUED_NEXT, "listProjectReleaseRequests",
                "projectReleaseRequests", FROZEN_AND_QUEUED).consumes(held(FROZEN_WITH_A_QUEUED_NEXT,
                "listProjectReleaseRequests", concat(List.of("requests[].id", "requests[].qualifiedId",
                        "requests[].state"), FREEZE, QUEUED))));
        rows.add(row("qits release-request list", FROZEN_WITH_A_QUEUED_NEXT, "listRepositoryReleaseRequests",
                "releaseRequests", FROZEN_AND_QUEUED).consumes(held(FROZEN_WITH_A_QUEUED_NEXT,
                "listRepositoryReleaseRequests", concat(List.of("requests[].id", "requests[].qualifiedId",
                        "requests[].state"), FREEZE, QUEUED))));
        REQUEST_SCOPED.forEach(c -> rows.add(row(c, REQUESTS_OF_EVERY_STATE, "listRepositoryReleaseRequests",
                "releaseRequests", FIND).consumes(FIND_REQUEST)));
        rows.add(row("qits release-request create", A_BRANCH_TO_RELEASE, "createReleaseRequest",
                "createReleaseRequest", CREATE_REQUEST)
                .consumes(held(A_BRANCH_TO_RELEASE, "createReleaseRequest", withPlacement()))
                .anyLength(openEndedRequest())
                .sendsWithout("summary"));
        rows.add(row("qits release-request join", OPEN_TO_ANOTHER_BRANCH, "addReleaseRequestSource",
                "joinReleaseRequest", JOIN_REQUEST)
                .consumes(held(OPEN_TO_ANOTHER_BRANCH, "addReleaseRequestSource", withPlacement()))
                .anyLength(openEndedRequest()));
        rows.add(row("qits release-request move", FROZEN_WITH_A_QUEUED_NEXT, MOVE_OPERATION,
                "moveReleaseRequestSource", MOVE)
                .consumes(held(FROZEN_WITH_A_QUEUED_NEXT, MOVE_OPERATION,
                        concat(prefixed("from.", REQUEST), prefixed("to.", REQUEST))))
                .anyLength(openEndedMove())
                .request("POST /projects/api/repositories/{repositoryId}/release-requests/{requestId}/sources/move"));
        rows.add(row("qits release-request withdraw", AWAITING_REQUEST, "withdrawReleaseRequest",
                "withdrawReleaseRequest", WITHDRAW)
                .consumes(held(AWAITING_REQUEST, "withdrawReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));
        for (String command : List.of("qits release-request approve", "qits release-request decline")) {
            rows.add(row(command, PROJECT_REQUESTS_OF_EVERY_STATE, "getProjectReleaseRequest", "projectReleaseRequest",
                    FIND_IN_PROJECT).consumes("request.id", "request.qualifiedId", "request.repoId", "request.mergedSha"));
        }
        rows.add(CliRow.of("qits release-request approve", AWAITING_WHILE_ITS_BUILD_RUNS, "approveReleaseRequest",
                "ProjectsApi#approveReleaseRequest", asThePerson(APPROVE))
                .consumes(held(AWAITING_WHILE_ITS_BUILD_RUNS, "approveReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest())
                .header("Authorization", "{authorization}"));
        rows.add(CliRow.of("qits release-request decline", AWAITING_REQUEST, "declineReleaseRequest",
                "ProjectsApi#declineReleaseRequest", asThePerson(DECLINE))
                .consumes(held(AWAITING_REQUEST, "declineReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest())
                .header("Authorization", "{authorization}"));
        rows.add(row("qits release-request regenerate", HELD_BY_A_FAILED_AUTOMATION, "rerunReleaseRequestAutomation",
                "rerunReleaseRequestAutomation", REGENERATE).consumes("id"));
        rows.add(row("qits release-request regenerate", AWAITING_REQUEST, "listRepositoryReleaseRequests",
                "releaseRequests", OPEN_REQUESTS).consumes(held(AWAITING_REQUEST, "listRepositoryReleaseRequests",
                List.of("requests[].id", "requests[].qualifiedId", "requests[].state"))));
        rows.add(CliRow.of("qits release-request override", HELD_BY_AN_UPSTREAM_RELEASE, "overrideReleaseRequestUpstream",
                "ProjectsApi#overrideReleaseRequestUpstream", asThePerson(OVERRIDE_UPSTREAM))
                .consumes(held(HELD_BY_AN_UPSTREAM_RELEASE, "overrideReleaseRequestUpstream", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest())
                .header("Authorization", "{authorization}"));
        rows.add(row("qits release-request rerun", REJECTED_BY_ITS_BUILD, "rerunReleasePipelinePhase",
                "rerunReleasePipelinePhase", RERUN_PHASE)
                .consumes(held(REJECTED_BY_ITS_BUILD, "rerunReleasePipelinePhase", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));
        rows.add(row("qits release-request rerun", PUBLISH_FAILED, "rerunReleasePipelinePhase",
                "rerunReleasePipelinePhase", RERUN_PHASE)
                .consumes(held(PUBLISH_FAILED, "rerunReleasePipelinePhase", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));
        rows.add(row("qits release-request rerun", RELEASED_REQUEST, "rerunReleasePipelinePhase",
                "rerunReleasePipelinePhase", RERUN_PHASE)
                .consumes(held(RELEASED_REQUEST, "rerunReleasePipelinePhase", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));

        // qits tui: the dropdowns
        rows.add(row("qits tui", REPOSITORIES_IN_COMPONENTS, "listProjectRepositories", "repositories",
                REPOSITORY_CHOICES).consumes("entries[].repository.name", "entries[].repository.archetype"));
        rows.add(row("qits tui", AWAITING_REQUEST, "listRepositoryReleaseRequests", "releaseRequests", OPEN_REQUESTS)
                .consumes(held(AWAITING_REQUEST, "listRepositoryReleaseRequests", concat(List.of("requests[].id",
                        "requests[].qualifiedId", "requests[].state"), ITEMS)))
                .anyLength(LIST_OPEN_ENDED));
        rows.add(row("qits tui", RELEASED_REQUESTS, "listRepositoryReleaseRequests", "releaseRequests",
                RELEASED_VERSIONS).consumes(held(RELEASED_REQUESTS, "listRepositoryReleaseRequests",
                concat(List.of("requests[].version"), ITEMS)))
                .anyLength(LIST_OPEN_ENDED));

        // qits work
        rows.add(row("qits work list", WORK_IN_EVERY_STATUS, "listProjectWork", "projectWork", LIST)
                .consumes(held(WORK_IN_EVERY_STATUS, "listProjectWork", prefixed("entities[].", ROW))));
        rows.add(row("qits work details", EPIC_IN_DETAIL, "getWork", "work", GET)
                .consumes(held(EPIC_IN_DETAIL, "getWork", DETAILS)));
        rows.add(row("qits work details", EPIC_IN_DETAIL, "listWorkComments", "workComments", COMMENTS)
                .consumes("entries[].comment.createdAt", "entries[].comment.author", "entries[].comment.body"));
        // Open-ended: qits-1908 takes the campaign and the dependsOn edges out of the seed these
        // states share, which shortens the lists.
        rows.add(row("qits work details", EPIC_IN_DETAIL, "listWorkChildren", "workChildren", CHILDREN)
                .consumes(held(EPIC_IN_DETAIL, "listWorkChildren", prefixed("children[].", ROW)))
                .anyLength("children"));
        rows.add(row("qits work details", SUPERSEDED_ITEMS, "listProjectWork", "projectWork", CLOSES)
                .consumes(held(SUPERSEDED_ITEMS, "listProjectWork",
                        prefixed("entities[].", Stream.concat(ROW.stream(), Stream.of("supersededBy")).toList())))
                .anyLength("entities")
                .request("GET /projects/api/projects/{projectId}/work?supersededBy={entityId}"));
        rows.add(row("qits work create", NO_WORK, "createWork", "createWork", CREATE)
                .consumes(held(NO_WORK, "createWork", ROW)));
        // The tags enum grows (qits-1908 adds CONDITION), so its length is left open.
        rows.add(row("qits work create", REGISTRY, "getWorkArchetypeSchema", "archetypeSchema", schema("create"))
                .consumes("properties", "required").anyLength(TAGS_ENUM));
        rows.add(row("qits work update", REPORTED_TICKET, "patchWork", "patchWork", PATCH)
                .consumes(held(REPORTED_TICKET, "patchWork", ROW)).header("Content-Type", ProjectsApi.MERGE_PATCH));
        rows.add(row("qits work update", TICKET_WITH_A_COMMENT, "getWork", "work", GET_ARCHETYPE).consumes("archetype"));
        rows.add(row("qits work update", UPDATE_SCHEMA, "getWorkArchetypeSchema", "archetypeSchema", schema("update"))
                .consumes("properties", "required").anyLength(TAGS_ENUM));
        rows.add(row("qits work transition", TICKET_WITH_A_COMMENT, "getWork", "work", GET_FOR_TRANSITION)
                .consumes(held(TICKET_WITH_A_COMMENT, "getWork", CARRIED)));
        rows.add(row("qits work transition", TRANSITION_SCHEMA, "getWorkArchetypeSchema", "archetypeSchema",
                schema("transition")).consumes("properties", "required"));
        rows.add(row("qits work transition", REPORTED_TICKET, "transitionWork", "transitionWork", TRANSITION)
                .consumes(held(REPORTED_TICKET, "transitionWork", prefixed("{qualifiedId}.", ROW))));
        rows.add(row("qits work status", IMPLEMENTED_TICKET, "getWork", "work", GET)
                .consumes("archetype", "qualifiedId", "status"));
        rows.add(row("qits work status", REGISTRY, "listWorkArchetypes", "archetypes", REGISTRY_READ)
                // The list is open-ended, so qits-1908 can delete the CAMPAIGN entry (epic qits-1899).
                // One template stands for every entry then, so the row binds only what every entry
                // carries: its name and its lifecycle. The moves stay unbound, though the command
                // reads the ones open from the item's status: each archetype keys them by its own
                // statuses (an issue by OPEN, RESOLVED and OBSOLETE), and an array whose entries
                // disagree on a key can only be pinned entry by entry, its length and its CAMPAIGN
                // entry with it.
                .consumes("archetypes[].archetype", "archetypes[].lifecycle")
                .anyLength("archetypes"));
        rows.add(row("qits work status", REPORTED_TICKET, "setWorkStatus", "setWorkStatus", STATUS)
                .consumes(held(REPORTED_TICKET, "setWorkStatus", ROW)));
        rows.add(row("qits work comment create", TICKET_WITH_A_COMMENT, "addWorkComment", "addWorkComment", COMMENT)
                .consumes("comment.id", "comment.author", "comment.createdAt"));
        rows.add(row("qits work comment update", TICKET_WITH_A_COMMENT, "editWorkComment", "editWorkComment",
                EDIT_COMMENT).consumes("comment.id", "comment.author", "comment.updatedAt")
                .header("Content-Type", ProjectsApi.MERGE_PATCH));
        rows.add(row("qits work block", REPORTED_TICKET, "setWorkBlocked", "setWorkBlocked", BLOCK)
                .consumes("block.blocked", "block.blockSource", "block.blockReason"));
        rows.add(row("qits work unblock", BUG_TICKET_IN_DETAIL, "setWorkBlocked", "setWorkBlocked", UNBLOCK)
                .consumes("block.blocked", "block.archetype", "block.status"));
        rows.add(row("qits work delete", REPORTED_TICKET, "deleteWork", "deleteWork", DELETE).consumes("success"));
        rows.add(row("qits work dispatch", READY_FOR_DEV_TICKET, "dispatchWork", "dispatchWork", DISPATCH)
                .consumes("dispatch.mode", "dispatch.phase", "dispatch.branch", "dispatch.assignee"));
        rows.add(row("qits work dossier list", BUG_TICKET_IN_DETAIL, "listWorkDossier", "workDossier", DOSSIER)
                .consumes("pages[].slug", "pages[].title", "pages[].version", "pages[].position"));
        rows.add(row("qits work dossier get", EPIC_IN_DETAIL, "getWorkDossierPage", "workDossierPage", DOSSIER_PAGE)
                .consumes("slug", "title", "version", "position", "body"));
        rows.add(row("qits work dossier put", BUG_TICKET_IN_DETAIL, "createWorkDossierPage", "createWorkDossierPage",
                DOSSIER_CREATE).consumes("slug", "title", "version", "position"));
        rows.add(row("qits work dossier put", BUG_TICKET_IN_DETAIL, "putWorkDossierPage", "putWorkDossierPage",
                DOSSIER_PUT).consumes("slug", "title", "version", "position"));
        rows.add(row("qits work dossier move", BUG_TICKET_IN_DETAIL, "moveWorkDossierPage", "moveWorkDossierPage",
                DOSSIER_MOVE).consumes("slug", "title", "version", "position"));
        rows.add(row("qits work dossier delete", BUG_TICKET_IN_DETAIL, "deleteWorkDossierPage",
                "deleteWorkDossierPage", DOSSIER_DELETE).consumes("success"));
        rows.add(row("qits work dossier inline", EPIC_WITH_A_SKETCH, "inlineWorkDossierAsset",
                "inlineWorkDossierAsset", INLINE).consumes("markdown"));
        List<String> design = List.of("id", "title", "version", "htmlBytes", "inUse", "updatedAt");
        rows.add(row("qits work design list", REPORTED_TICKET, "getWorkRefinement", "workRefinement", NO_REFINEMENT)
                .consumes("refinement"));
        for (String command : List.of("qits work design list", "qits work design get", "qits work design put")) {
            rows.add(row(command, TICKET_WITH_A_DESIGN, "getWorkRefinement", "workRefinement", OPEN_REFINEMENT)
                    .consumes("refinement.id"));
        }
        rows.add(row("qits work design list", TICKET_WITH_A_DESIGN, "listRefinementDesigns", "refinementDesigns",
                DESIGNS).consumes(prefixed("designs[].", design)));
        rows.add(row("qits work design get", TICKET_WITH_A_DESIGN, "getRefinementDesign", "refinementDesign",
                DESIGN_GET).consumes(Stream.concat(design.stream(), Stream.of("html")).toList()));
        rows.add(row("qits work design put", TICKET_WITH_A_DESIGN, "addRefinementDesign", "addRefinementDesign",
                DESIGN_ADD).consumes(design));
        rows.add(row("qits work design put", TICKET_WITH_A_DESIGN, "updateRefinementDesign", "updateRefinementDesign",
                DESIGN_UPDATE).consumes(design));

        List<String> room = List.of("refinement.id", "refinement.entityId", "refinement.label",
                "refinement.runtimeStatus");
        rows.add(row("qits work refinement open", TICKET_WITH_A_DESIGN, "startWorkRefinement", "openWorkRefinement",
                OPEN_ROOM).consumes("refinement.id"));
        rows.add(row("qits work refinement open", REFINED_TICKET, "startWorkRefinement", "openWorkRefinement",
                OPEN_REFUSED).consumes("message"));
        rows.add(row("qits work refinement open", TICKET_WITH_A_DESIGN, "ensureRefinementContainer",
                "ensureRefinement", ENSURE_ROOM).consumes(Stream.concat(room.stream(),
                Stream.of("technicalProcessId")).toList()));
        rows.add(row("qits work refinement show", REPORTED_TICKET, "getWorkRefinement", "workRefinement",
                NO_REFINEMENT).consumes("refinement"));
        rows.add(row("qits work refinement show", TICKET_WITH_A_DESIGN, "getWorkRefinement", "workRefinement",
                SHOW_ROOM).consumes(room));
        rows.add(row("qits work refinement discard", TICKET_WITH_A_DESIGN, "getWorkRefinement", "workRefinement",
                DISCARD_FINDS_THE_ROOM).consumes("refinement.id"));
        rows.add(row("qits work refinement discard", TICKET_WITH_A_DESIGN, "discardRefinement", "discardRefinement",
                DISCARD_ROOM));

        // qits ideas (epic qits-888): pending until the pinned golden masters carry the ideas
        rows.add(row("qits ideas list", IDEAS, "listIdeas", "ideas", LIST_IDEAS)
                .consumes(held(IDEAS, "listIdeas", prefixed("ideas[].", IDEA_ROW)))
                .anyLength("ideas")
                .request("GET /projects/api/ideas?project={projectId}"));
        rows.add(row("qits ideas details", IDEAS, "getIdea", "idea", GET_IDEA)
                .consumes(held(IDEAS, "getIdea", IDEA_DETAILS))
                .anyLength("inspired", "enables")
                .request("GET /projects/api/ideas/{ideaId}"));
        rows.add(row("qits ideas create", NO_WORK, "createIdea", "createIdea", CREATE_IDEA)
                .consumes(held(NO_WORK, "createIdea", IDEA_ROW))
                .request("POST /projects/api/ideas"));
        rows.add(row("qits ideas create", IDEAS, "createIdea", "createIdea", CREATE_IDEA_REFUSED)
                .consumes("message")
                .request("POST /projects/api/ideas"));
        rows.add(row("qits ideas update", IDEAS, "patchIdea", "patchIdea", PATCH_IDEA)
                .consumes(held(IDEAS, "patchIdea", IDEA_ROW))
                .header("Content-Type", ProjectsApi.MERGE_PATCH)
                .request("PATCH /projects/api/ideas/{enabledIdeaId}"));
        rows.add(row("qits ideas archive", IDEAS, "archiveIdea", "archiveIdea", ARCHIVE_IDEA)
                .consumes(held(IDEAS, "archiveIdea", concat(IDEA_ROW, List.of("archiveReason"))))
                .request("POST /projects/api/ideas/{enabledIdeaId}/archive"));
        rows.add(row("qits ideas unarchive", IDEAS, "unarchiveIdea", "unarchiveIdea", UNARCHIVE_IDEA)
                .consumes(held(IDEAS, "unarchiveIdea", IDEA_ROW))
                .request("POST /projects/api/ideas/{archivedIdeaId}/unarchive"));
        rows.add(row("qits ideas update", IDEAS_ONE_RANKED, "patchIdea", "patchIdea", PRIORITIZE_IDEA)
                .consumes(held(IDEAS_ONE_RANKED, "patchIdea", concat(IDEA_ROW, List.of("priority"))))
                .header("Content-Type", ProjectsApi.MERGE_PATCH)
                .request("PATCH /projects/api/ideas/{ideaId}"));
        rows.add(row("qits ideas rank", IDEAS_ONE_RANKED, "reprioritizeIdea", "reprioritizeIdea", RANK_IDEA)
                .consumes("items[].id", "items[].priority")
                .anyLength("items")
                .request("PUT /projects/api/ideas/{ideaId}/priority"));
        // spawn reads the idea, then files the item through work create's door: that door's recording
        // carries no inspiredBy yet, so the row binds what spawn reads of its answer, as work create's does.
        rows.add(row("qits ideas spawn", IDEAS, "getIdea", "idea", SPAWN_FROM)
                .consumes(held(IDEAS, "getIdea", List.of("id", "projectId", "title", "description")))
                .request("GET /projects/api/ideas/{ideaId}"));
        rows.add(row("qits ideas spawn", NO_WORK, "createWork", "createWork", CREATE)
                .consumes(held(NO_WORK, "createWork", ROW)));

        // the NEEDS of work list and details (epic qits-1899)
        rows.add(row("qits work list", NEEDS_ANOTHER, "listProjectWork", "projectWork", LIST_NEEDING)
                .consumes(held(NEEDS_ANOTHER, "listProjectWork", prefixed("entities[].", concat(List.of("qualifiedId",
                        "unmetNeeds"), prefixed("needs[].", NEED)))))
                .anyLength("entities", "entities[].needs")
                .request("GET /projects/api/projects/{projectId}/work"));
        rows.add(row("qits work details", NEEDS_ANOTHER, "getWork", "work", GET_NEEDING)
                .consumes(held(NEEDS_ANOTHER, "getWork", concat(List.of("qualifiedId", "unmetNeeds"),
                        prefixed("needs[].", NEED))))
                .anyLength("needs")
                .request("GET /projects/api/work/{qualifiedId}"));

        // a work item named by its qualified id outside qits work
        WORK_ITEM_SCOPED.forEach(c -> rows.add(row(c, TICKET_WITH_A_COMMENT, "getWork", "work", WORK_ID)
                .consumes("id", "qualifiedId", "archetype")));
        rows.add(row("qits agents claude statusline work-items", TICKET_WITH_A_COMMENT, "getWork", "work", WORK_STATE)
                .consumes(held(TICKET_WITH_A_COMMENT, "getWork", List.of("status", "blocked", "blockSource"))));

        // qits artifacts publish changelog, in every release step
        rows.add(row("qits artifacts publish changelog", RELEASED_REQUEST, "listReleaseRequestCommits",
                "releaseRequestCommits", RELEASE_COMMITS)
                .consumes("commits[].fold", "commits[].shortHash", "commits[].message", "commits[].author"));
        rows.add(row("qits artifacts publish changelog", TICKET_WITH_A_COMMENT, "getWork", "work", TITLE)
                .consumes("title"));
        rows.add(row("qits artifacts publish changelog", REQUESTS_OF_EVERY_STATE, "listRepositoryReleaseRequests",
                "releaseRequests", EARLIER_RELEASES)
                .consumes("requests[].id", "requests[].qualifiedId", "requests[].version"));
        return List.copyOf(rows);
    }

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "ProjectsApi#" + method, api(call));
    }

    private static List<String> prefixed(String prefix, List<String> paths) {
        return paths.stream().map(p -> prefix + p).toList();
    }

    /** The paths the recording holds: the command reads the others only where the service sends them. */
    private static List<String> held(String state, String operationId, List<String> paths) {
        return BASE.held(state, operationId, paths);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        return Stream.of(lists).flatMap(List::stream).toList();
    }

    /** What create and join read: the request as printed, and the placement line above it. */
    private static List<String> withPlacement() {
        return concat(prefixed("request.", REQUEST), PLACEMENT);
    }

    /** {@link #REQUEST_OPEN_ENDED}, prefixed for a row's {@code request.*} body, as {@code anyLength} takes it. */
    private static String[] openEndedRequest() {
        return prefixed("request.", REQUEST_OPEN_ENDED).toArray(String[]::new);
    }

    private ProjectsContract() {
    }
}
