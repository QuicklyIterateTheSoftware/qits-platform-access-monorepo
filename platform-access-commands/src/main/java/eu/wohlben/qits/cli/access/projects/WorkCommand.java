package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits work}: any work entity — ticket, epic, feature, task or issue — named once with
 * {@code --entity}, whatever its kind. One group for every archetype: the service serves the
 * archetype-agnostic doors and, per archetype and door, the payload's schema, so nothing here knows a
 * route per kind or a field of any kind.
 * <p>
 * Every write reads its payload, a JSON object, on stdin ({@link WorkPayload}); with none, it prints
 * its usage and the schema the service serves, and sends nothing.
 * <p>
 * A title, a description and a comment are written by people and agents, so every value a table
 * shows goes through {@link SafeText}, and the JSON form through {@link SafeJson}.
 */
@CommandLine.Command(name = "work", mixinStandardHelpOptions = true,
        subcommands = {WorkCommand.ListCommand.class, WorkCommand.DetailsCommand.class, WorkCommand.CreateCommand.class,
                WorkCommand.UpdateCommand.class, WorkCommand.TransitionCommand.class, WorkCommand.StatusCommand.class,
                WorkCommand.BlockCommand.class, WorkCommand.UnblockCommand.class, WorkCommand.DeleteCommand.class,
                WorkCommand.DispatchCommand.class, WorkCommentCommand.class, WorkDossierCommand.class,
                WorkRefinementCommand.class, WorkDesignCommand.class},
        description = {"Work items of every archetype - epics, tickets, features, tasks and issues - and their "
                        + "comment threads. list shows a project's items, details shows one with its comments and "
                        + "children, create files one, update edits its fields, transition reshapes it into "
                        + "another archetype, status moves it along its lifecycle, block and unblock flag that its "
                        + "phase cannot go on, delete removes it, dispatch puts an agent on it, and comment writes to its "
                        + "thread.",
                "An epic's or a ticket's dossier (dossier), its refinement room itself (refinement: open, show, "
                        + "discard) and the HTML designs of a refinement (design) are here too.",
                "An item can need other items: \"needs\" names each one and the status it must reach, such as "
                        + "qits-1897 at VERIFIED. An item whose needs are not all met is not dispatched from REFINED "
                        + "or READY_FOR_DEV. create and update write the whole list, like tags.",
                "A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or "
                        + "empty) it sends nothing and prints its usage and the payload's JSON schema instead, "
                        + "served by the service for that archetype.",
                "Every archetype has a lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, "
                        + "VERIFYING, VERIFIED, DONE, and DROPPED for work a decision was taken not to do, with an "
                        + "outcome saying why (status names them). "
                        + "Features and tasks walk the same nine words as epics and tickets. A SKIP transition lets "
                        + "READY_FOR_DEV move straight to IMPLEMENTED, bypassing IMPLEMENTING, and IMPLEMENTED "
                        + "move straight to VERIFIED, bypassing VERIFYING. IMPLEMENTING only moves forward or "
                        + "drops; it never moves back. REFINED to READY_FOR_DEV needs a person - a person's own "
                        + "`qits` CLI sign-in, or the browser; an agent credential is refused. `status` names "
                        + "the moves open from where an item stands."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity, --output and --projects-url may come before or after the command.",
                "- --entity is the item's qualified id (qits-100) or its id, sent to the service as it is; the "
                        + "service resolves either. So is every id inside a payload (parent, membership.parent, "
                        + "supersededBy, and needs[].item, as in {\"needs\":[{\"item\":\"qits-90\",\"status\":"
                        + "\"VERIFIED\"}]}).",
                "- Reading takes qits:admin or qits:agent; so does writing, except an epic's status move, which "
                        + "takes qits:admin. An agent writes only in its own project.",
                "- REFINED to READY_FOR_DEV takes a person signed in with their own `qits` CLI or the browser; "
                        + "an agent credential is refused (HTTP 409, or HTTP 403 for an epic).",
                "- An issue is one thing the delivery desk cannot resolve. Only the desk creates one; a person "
                        + "resolves it (see create and status). Open ones: qits work list --project <project> "
                        + "--archetype ISSUE --status OPEN. Comment with qits work comment create."})
public class WorkCommand implements Runnable {

    static final String NAME_THE_ENTITY = "Name the entity: --entity <id or qualified id, like qits-100>.";
    static final String NAME_THE_ARCHETYPE = "Name the archetype: --archetype <EPIC, TICKET, FEATURE, TASK or ISSUE>.";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--entity", paramLabel = "<entity>", scope = CommandLine.ScopeType.INHERIT,
            description = "The work entity: its id or its qualified id (qits-100), passed to the service as it is.")
    String entity;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** The entity as given, without surrounding blanks; a usage error without one. */
    String entity() throws CliFailure {
        return RepositoriesCommand.required(entity, NAME_THE_ENTITY);
    }

    ProjectsApi api(CliContext context) throws CliFailure, InterruptedException {
        return ProjectsApi.connect(context, options.projectsUrl);
    }

    /** The service reads archetype and status names in capitals. */
    static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    static String archetype(String given) throws CliFailure {
        return upper(RepositoriesCommand.required(given, NAME_THE_ARCHETYPE));
    }

    /**
     * The usage of the command, then the schema: what a person running a write with nothing piped in
     * needs. The JSON form is the bare schema, for a program.
     */
    static void usageAndSchema(CliContext context, CommandLine.Model.CommandSpec spec, boolean json, JsonNode schema,
            String from, List<String> notes) throws CliFailure {
        PrintStream out = context.out();
        if (!json) {
            spec.commandLine().usage(out, CommandLine.Help.Ansi.OFF);
            out.println();
        }
        WorkPayload.printSchema(out, json, schema, from, notes);
    }

    /** The schema one archetype's door takes, from the service. */
    static JsonNode schema(ProjectsApi api, String archetype, String door) throws CliFailure, InterruptedException {
        return WorkPayload.served(ProjectsApi.schemaPath(archetype, door), () -> api.archetypeSchema(archetype, door));
    }

    static String from(String archetype, String door) {
        return "From the service: GET " + ProjectsApi.schemaPath(archetype, door);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"A project's work items, every archetype, in the service's order: id (qualified), "
                    + "archetype, status, title and when it last changed. BLOCKED appears when a ticket is: "
                    + "yes for an explicit block, waiting when the only reason is an agent session sitting "
                    + "idle, and - when it is not blocked. NEEDS appears when some item needs another item that is "
                    + "not at its status yet, naming each as <id>@<status> (qits-1897@VERIFIED), and - for an item "
                    + "whose needs are all met. TAGS appears "
                    + "when some item carries one, as its "
                    + "display text (RESERVED_FOR_HUMAN shows as reserved for human), and - for an item with "
                    + "none. A DROPPED item shows its outcome beside the status: DROPPED (DUPLICATE).",
                    "Each filter narrows the list; without one it shows everything."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work list --project qits",
                    "  qits work list --project qits --archetype epic --status REFINED",
                    "  qits work list --project qits --parent qits-120 -o json",
                    "  qits work list --project qits --status DROPPED --outcome duplicate",
                    "  qits work list --project qits --tag reserved-for-human",
                    "",
                    "- --archetype, --status and --outcome are case-insensitive. --parent lists the children of "
                            + "one item. --tag takes the tag's display form (reserved-for-human) or its enum name "
                            + "(RESERVED_FOR_HUMAN), either case."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an unknown archetype or status, HTTP 400, or a project it "
                            + "does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --project), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id or its slug.")
        String project;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "Only items of this archetype: EPIC, TICKET, FEATURE, TASK or ISSUE.")
        String archetype;

        @CommandLine.Option(names = "--status", paramLabel = "<status>",
                description = "Only items in this status, such as REFINED or OPEN.")
        String status;

        @CommandLine.Option(names = "--parent", paramLabel = "<entity>",
                description = "Only the children of this item: its id or its qualified id.")
        String parent;

        @CommandLine.Option(names = "--outcome", paramLabel = "<outcome>",
                description = "Only DROPPED items dropped with this outcome: DUPLICATE, RESOLVED_ELSEWHERE, "
                        + "SUPERSEDED, OBSOLETE, INVALID or WONT_DO.")
        String outcome;

        @CommandLine.Option(names = "--tag", paramLabel = "<tag>",
                description = "Only items carrying this tag: reserved-for-human (RESERVED_FOR_HUMAN).")
        String tag;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            String why = upper(outcome);
            if (why != null && !WorkEntities.OUTCOMES.contains(why)) {
                throw new CliFailure("--outcome is one of " + String.join(", ", WorkEntities.OUTCOMES) + ", not "
                        + SafeText.line(outcome.strip()) + ".", CliFailure.USAGE);
            }
            ProjectsApi api = work.api(context);
            String parentEntity = parent == null || parent.isBlank() ? null : parent.strip();
            // The service itself reads a tag's enum name or its kebab form, either case, so the CLI
            // sends --tag as it was given, trimmed, and leaves the 400 for an unknown one to it.
            String wantedTag = tag == null || tag.isBlank() ? null : tag.strip();
            JsonNode answer = api.projectWork(wanted, upper(archetype), upper(status), parentEntity, why, null,
                    wantedTag);
            List<JsonNode> entities = WorkEntities.list(answer);
            if (why != null) {
                // Again here: a service older than qits-248 ignores ?outcome= and answers every row.
                entities = WorkEntities.withOutcome(entities, why);
                if (answer.isObject()) {
                    ObjectNode narrowed = ((ObjectNode) answer).deepCopy();
                    ArrayNode kept = narrowed.putArray("entities");
                    entities.forEach(kept::add);
                    answer = narrowed;
                }
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            if (entities.isEmpty()) {
                context.out().println("No work items match.");
                return 0;
            }
            WorkEntities.printRows(context.out(), "", entities);
            return 0;
        }
    }

    // --- details ---

    @CommandLine.Command(name = "details", mixinStandardHelpOptions = true,
            description = {"One work item: its fields, its description, its comment thread, its children, and what "
                    + "it closes: the items naming it as their supersededBy (dropped as its duplicate, resolved by "
                    + "it, or superseded by it). A DROPPED item shows its outcome beside the status, and its "
                    + "supersededBy as duplicate of, resolved by or superseded by. needs names, one a line, each "
                    + "item it needs that is not at its status yet, with where that item stands now (qits-1897 at "
                    + "VERIFIED (now REFINED)), and needs met the ones that are."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 details",
                    "  qits work details --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 -o json",
                    "",
                    "- -o json prints one object: the item as the service answers it, its comments, its "
                            + "children and supersedes, the items it closes. Control characters are written as "
                            + "escapes."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class DetailsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = work.entity();
            ProjectsApi api = work.api(context);
            JsonNode entity = api.work(wanted);
            List<JsonNode> comments = ProjectsApi.entries(api.workComments(wanted), "comment");
            List<JsonNode> children = WorkEntities.children(api.workChildren(wanted));
            List<JsonNode> closes = closes(context, api, entity);
            if (json) {
                ObjectNode all = JsonNodeFactory.instance.objectNode();
                all.set("entity", entity);
                ArrayNode thread = all.putArray("comments");
                comments.forEach(thread::add);
                ArrayNode kids = all.putArray("children");
                children.forEach(kids::add);
                if (closes != null) {
                    ArrayNode supersedes = all.putArray("supersedes");
                    closes.forEach(supersedes::add);
                }
                SafeJson.print(context.out(), all);
                return 0;
            }
            WorkEntities.printDetails(context.out(), entity, comments, children, closes);
            return 0;
        }

        /**
         * The items naming this one as their supersededBy, from its project's list filtered by
         * {@code ?supersededBy=} and filtered again here, because a service older than qits-248
         * ignores the parameter and answers the whole project. The filter names the item by its
         * qualified id where it has one, as every other door the CLI calls does (epic qits-965), and
         * by its id otherwise: the service takes either, and the qualified id is the form its
         * recording pins. A secondary read: when it is refused, stderr says so and the rest of the
         * item is still printed; null then.
         */
        private static List<JsonNode> closes(CliContext context, ProjectsApi api, JsonNode entity)
                throws CliFailure, InterruptedException {
            String project = text(entity, "projectId");
            String id = text(entity, "id");
            if (project.isEmpty() || id.isEmpty()) {
                return null;
            }
            String qualified = text(entity, "qualifiedId");
            try {
                return WorkEntities.superseding(WorkEntities.list(api.projectWork(project, null, null, null, null,
                        qualified.isEmpty() ? id : qualified, null)), entity);
            } catch (CliFailure refused) {
                if (refused.exitCode() == CliFailure.USAGE) {
                    throw refused;
                }
                context.err().println("The items this one closes could not be read: " + refused.getMessage());
                return null;
            }
        }
    }

    // --- create ---

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"File a new work item of an archetype.",
                    "Reads the payload, a JSON object, on stdin, sets its \"archetype\" from --archetype, and sends "
                            + "it to POST /projects/api/work. A root item names its \"project\" (id or slug), a "
                            + "child its \"parent\" (qualified id or id). The item starts REPORTED if its archetype "
                            + "has a lifecycle, and an issue OPEN. The command prints the new item, its qualified id first.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's JSON schema, served by the service at "
                            + "GET /projects/api/work/archetypes/{archetype}/schemas/create, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work create --archetype ticket </dev/null",
                    "  echo '{\"project\":\"qits\",\"title\":\"The log view stops at 64 KiB\",\"ticketType\":\"BUG\","
                            + "\"impetus\":\"A long run's log is cut.\"}' | qits work create --archetype ticket",
                    "  jq -n --rawfile d plan.md '{parent:\"qits-120\",title:\"Retry\",description:$d}' "
                            + "| qits work create --archetype feature -o json",
                    "",
                    "  echo '{\"project\":\"qits\",\"title\":\"CI retry refused\",\"description\":\"qits-ci refuses "
                            + "the retry. A person must lift the lease.\"}' | qits work create --archetype issue",
                    "",
                    "- The first one prints the schema: nothing is sent.",
                    "- An issue takes qits:desk or qits:desk-admin; everyone else, qits:admin included, is answered "
                            + "HTTP 403. Its description holds at most 280 characters (HTTP 400). It may name one "
                            + "related item in \"relatedTo\" (qualified id or id)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is filed, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a payload it does not take, HTTP 400, your roles or "
                            + "another project, HTTP 403, or an archetype it does not know, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --archetype, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "The new item's archetype (required): EPIC, TICKET, FEATURE, TASK or ISSUE. "
                        + "Case-insensitive.")
        String archetype;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            String kind = archetype(archetype);
            ProjectsApi api = work.api(context);
            if (payload == null) {
                usageAndSchema(context, spec, json, schema(api, kind, "create"), from(kind, "create"), List.of());
                return 0;
            }
            JsonNode stated = payload.get("archetype");
            if (stated != null && !stated.isNull() && !kind.equals(upper(stated.asText()))) {
                throw new CliFailure("The payload says \"archetype\": " + SafeText.line(stated.toString())
                        + ", and --archetype says " + kind + ". Nothing was sent.", CliFailure.USAGE);
            }
            ObjectNode body = payload.deepCopy();
            body.put("archetype", kind);
            JsonNode answer = api.createWork(body);
            WorkEntities.printAnswer(context.out(), json, answer);
            return 0;
        }
    }

    // --- update ---

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Edit a work item's fields.",
                    "Reads a JSON merge patch on stdin and sends it unchanged to PATCH /projects/api/work/{entity} "
                            + "as application/merge-patch+json: a property left out stays as it is, null clears it. "
                            + "Status and archetype are not edited here; see status and transition.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, looks the item up for its "
                            + "archetype, prints this usage and the patch's JSON schema for that archetype, served by "
                            + "the service at GET /projects/api/work/archetypes/{archetype}/schemas/update, and exits "
                            + "with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 update </dev/null",
                    "  echo '{\"title\":\"The log view stops at 64 KiB\"}' | qits work --entity qits-100 update",
                    "  echo '{\"assignee\":null}' | qits work update --entity qits-100 -o json",
                    "  echo '{\"needs\":[{\"item\":\"qits-90\",\"status\":\"VERIFIED\"}]}' | qits work --entity qits-100 update",
                    "",
                    "- needs is the whole list: it replaces what the item needed, and [] or null clears it."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is edited, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a patch it does not take, HTTP 400, your roles or another "
                            + "project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a patch that is not a JSON object), not signed in, "
                            + "or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode patch = WorkPayload.read(context);
            String wanted = work.entity();
            ProjectsApi api = work.api(context);
            if (patch == null) {
                String kind = text(api.work(wanted), "archetype");
                usageAndSchema(context, spec, json, schema(api, kind, "update"), from(kind, "update"), List.of());
                return 0;
            }
            JsonNode answer = api.patchWork(wanted, patch);
            WorkEntities.printAnswer(context.out(), json, answer);
            return 0;
        }
    }

    // --- transition ---

    @CommandLine.Command(name = "transition", mixinStandardHelpOptions = true,
            description = {"Reshape a work item into another archetype (a ticket into an epic, a feature into a "
                    + "task, ...), keeping its id, its number and its thread.",
                    "The door is full-state: what the request leaves out is cleared. So the command starts from the "
                            + "item as it stands (title, description, status, outcome, ticketType, impetus, assignee, "
                            + "supersededBy, repositoryId, implementingAt, implementedAt, and membership {parent, "
                            + "position} if it has a parent), merges the JSON object on stdin over it as a merge "
                            + "patch (null clears), sets \"archetype\", and drops every property the target "
                            + "archetype's schema has no slot for, naming them on stderr. It sends the result to "
                            + "POST /projects/api/work/transition, keyed by --entity as it was given.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the target's "
                            + "JSON schema, served at GET /projects/api/work/archetypes/{archetype}/schemas/"
                            + "transition, names the required properties the item does not carry yet and the ones "
                            + "that would be dropped, and exits with 0.",
                    "With --map it restates several items at once: the payload is the whole request, "
                            + "{\"<entity>\": <full state>, ...}, sent unchanged. Each key is a qualified id or an "
                            + "id, and each state names its \"archetype\" and every property the item is to keep, "
                            + "because a property left out is cleared. The service judges the map as one post-state "
                            + "and writes all of it or nothing, so a move with no legal order (a feature becomes an "
                            + "epic while its tasks become features under it) is one call. Nothing is created or "
                            + "deleted: every id must already exist. A status change to or from READY_FOR_DEV, or "
                            + "into started work or REFINED, is refused here; use status. --map takes no --entity "
                            + "and no --archetype."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 transition --archetype epic </dev/null",
                    "  echo '{}' | qits work --entity qits-100 transition --archetype epic",
                    "  echo '{\"membership\":{\"parent\":\"qits-120\"}}' "
                            + "| qits work --entity qits-100 transition --archetype feature",
                    "  jq -n '{\"qits-101\":{archetype:\"EPIC\",title:\"Export\",status:\"REPORTED\"},"
                            + "\"qits-102\":{archetype:\"FEATURE\",title:\"CSV\",status:\"REPORTED\","
                            + "membership:{parent:\"qits-101\"}}}' | qits work transition --map",
                    "",
                    "- {} carries the item over as it stands. membership.parent and supersededBy take a "
                            + "qualified id or an id. needs is no part of this door, as tags are not: write it with "
                            + "update, as in echo '{\"needs\":[{\"item\":\"qits-90\",\"status\":\"VERIFIED\"}]}' "
                            + "| qits work --entity qits-100 update.",
                    "- --map with nothing on stdin prints the request's schema from the service's OpenAPI document."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The items are reshaped, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a state it does not take, HTTP 400, your roles or another "
                            + "project, HTTP 403, an item or archetype it does not know, HTTP 404, or a conflict, "
                            + "HTTP 409), or cannot be reached.",
                    "2:Used wrongly (for example no --entity or --archetype, --map with either, or a payload that "
                            + "is not a JSON object), not signed in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class TransitionCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "The archetype to turn the item into (required without --map): EPIC, TICKET, "
                        + "FEATURE or TASK. Case-insensitive.")
        String archetype;

        @CommandLine.Option(names = "--map",
                description = "Send the payload as it is: a map of entity to full state, for several items at once.")
        boolean map;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            if (map) {
                return restate(context, json, payload);
            }
            String wanted = work.entity();
            String target = archetype(archetype);
            ProjectsApi api = work.api(context);
            JsonNode entity = api.work(wanted);
            JsonNode schema = schema(api, target, "transition");
            ObjectNode current = WorkEntities.currentState(entity);
            if (payload == null) {
                List<String> notes = new ArrayList<>();
                List<String> missing = WorkEntities.missing(schema, current);
                notes.add("Required and missing (the item does not carry them yet): "
                        + (missing.isEmpty() ? "nothing" : String.join(", ", missing)) + ".");
                List<String> dropped = WorkEntities.unslotted(schema, current);
                if (!dropped.isEmpty()) {
                    notes.add("Not carried: " + String.join(", ", dropped) + " "
                            + (dropped.size() == 1 ? "has" : "have") + " no slot on " + target + ".");
                }
                usageAndSchema(context, spec, json, schema, from(target, "transition"), notes);
                return 0;
            }
            ObjectNode state = (ObjectNode) WorkEntities.mergePatch(current, payload);
            state.remove("archetype");
            List<String> dropped = WorkEntities.unslotted(schema, state);
            if (!dropped.isEmpty()) {
                context.err().println(String.join(", ", dropped) + " " + (dropped.size() == 1 ? "has" : "have")
                        + " no slot on " + target + " and " + (dropped.size() == 1 ? "is" : "are") + " not carried.");
                dropped.forEach(state::remove);
            }
            state.put("archetype", target);
            // Keyed by the entity as it was given: the service answers keyed exactly as it was asked.
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.set(wanted, state);
            JsonNode answer = api.transitionWork(body);
            WorkEntities.printAnswer(context.out(), json, json ? answer : answer.path(wanted));
            return 0;
        }

        /** {@code --map}: the payload is the request, sent unchanged; each answer is a row. */
        private int restate(CliContext context, boolean json, ObjectNode payload)
                throws CliFailure, InterruptedException {
            if (work.entity != null && !work.entity.isBlank() || archetype != null && !archetype.isBlank()) {
                throw new CliFailure("--map takes no --entity and no --archetype: the payload's keys name the "
                        + "items and each state names its archetype. Nothing was sent.", CliFailure.USAGE);
            }
            ProjectsApi api = work.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "POST", ProjectsApi.WORK + "/transition",
                        List.of("application/json"));
                return 0;
            }
            JsonNode answer = api.transitionWork(payload);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> rows = new ArrayList<>();
            answer.forEach(rows::add);
            WorkEntities.printRows(context.out(), "", rows);
            return 0;
        }
    }

    // --- status ---

    @CommandLine.Command(name = "status", mixinStandardHelpOptions = true,
            description = {"Move a work item along its lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, "
                    + "IMPLEMENTED, VERIFYING, VERIFIED, DONE, or DROPPED. Epics, tickets, features and tasks walk "
                    + "it by hand. A SKIP transition lets READY_FOR_DEV move straight to IMPLEMENTED, bypassing "
                    + "IMPLEMENTING, and IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING. IMPLEMENTING "
                    + "only moves forward or drops; it never moves back. REFINED to READY_FOR_DEV needs a person "
                    + "- a person's own `qits` CLI sign-in, or the browser; an agent credential is refused.",
                    "Reads {\"target\":\"<STATUS>\"} on stdin and sends it unchanged to POST "
                            + "/projects/api/work/{entity}/status. The service refuses a move its lifecycle does not "
                            + "allow (HTTP 409).",
                    "A move into DROPPED says why in \"outcome\": DUPLICATE (another item asks for the same "
                            + "thing), RESOLVED_ELSEWHERE (another item's change already resolved it), SUPERSEDED "
                            + "(a successor replaced it), OBSOLETE (the subject went away by itself), INVALID (it "
                            + "never was a problem) or WONT_DO (a decision was taken not to do it; scratch and probe "
                            + "items too). The first three name that other item in \"supersededBy\" (qualified id "
                            + "or id); the last three take none; any other move takes neither. Each breach is HTTP "
                            + "400. Do not drop an item merely because it is hard or stale: block it instead. "
                            + "DROPPED moves back only to REPORTED, which clears both.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's schema, whose target enum is the moves open from the item's current status "
                            + "as the service's archetype registry (GET /projects/api/work/archetypes) states "
                            + "them, and exits with 0.",
                    "An issue has its own lifecycle: OPEN, then RESOLVED or OBSOLETE, and both are final. A person "
                            + "(qits:admin, signed in) moves it to RESOLVED with the answer in an optional "
                            + "\"reason\"; the delivery desk does too, with a one-line \"reason\" saying why: "
                            + "answered, or made redundant by other work (HTTP 400 without one). Anyone else gets "
                            + "HTTP 403. The delivery desk or a person moves it to OBSOLETE, with a one-line "
                            + "\"reason\" (HTTP 400 without one). The reason lands on its thread. An issue is never "
                            + "dispatched and is not reshaped by transition.",
                    "An epic or a feature whose children are all DONE or DROPPED can be closed straight to DONE by "
                            + "a person or the delivery desk (a CLOSE move)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 status </dev/null",
                    "  echo '{\"target\":\"REFINED\"}' | qits work --entity qits-100 status",
                    "  echo '{\"target\":\"DROPPED\",\"outcome\":\"DUPLICATE\",\"supersededBy\":\"qits-90\"}' "
                            + "| qits work --entity qits-100 status",
                    "  echo '{\"target\":\"DROPPED\",\"outcome\":\"OBSOLETE\"}' | qits work --entity qits-100 status",
                    "  echo '{\"target\":\"RESOLVED\",\"reason\":\"Lease lifted.\"}' | qits work --entity qits-100 status",
                    "  echo '{\"target\":\"OBSOLETE\",\"reason\":\"The lease ended.\"}' | qits work --entity qits-100 status",
                    "",
                    "- An epic's status move takes qits:admin; an agent is answered HTTP 403.",
                    "- REFINED to READY_FOR_DEV takes a person signed in with their own `qits` CLI or the browser; "
                            + "an agent credential is refused (HTTP 409, or HTTP 403 for an epic)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item moved, or nothing was put in and the legal targets are printed.",
                    "1:The platform refused (for example a drop without its outcome or reference, HTTP 400, a "
                            + "move the lifecycle does not allow, HTTP 409, your roles "
                            + "or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --entity, an item on a service whose registry has not yet given "
                            + "its archetype a lifecycle, or a payload that is not a JSON object), not signed in, "
                            + "or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class StatusCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            String wanted = work.entity();
            ProjectsApi api = work.api(context);
            JsonNode entity = api.work(wanted);
            String kind = text(entity, "archetype");
            JsonNode declared = WorkEntities.declared(api.archetypes(), kind);
            if (declared == null || !declared.path("lifecycle").isArray() || declared.path("lifecycle").isEmpty()) {
                throw new CliFailure(SafeText.line(text(entity, "qualifiedId").isEmpty() ? wanted
                        : text(entity, "qualifiedId")) + " is a " + SafeText.line(kind)
                        + ", and the service's archetype registry gives it no lifecycle, so no status. Nothing "
                        + "was sent.", CliFailure.USAGE);
            }
            if (payload == null) {
                String current = text(entity, "status");
                ArrayNode targets = JsonNodeFactory.instance.arrayNode();
                for (JsonNode move : declared.path("transitions").path(current)) {
                    targets.add(move.isTextual() ? move.asText() : text(move, "to"));
                }
                ObjectNode schema = statusSchema(kind, current, targets);
                List<String> notes = new ArrayList<>();
                notes.add("Current status: " + SafeText.line(current) + ". Open moves: "
                        + (targets.isEmpty() ? "none" : String.join(", ", WorkEntities.texts(targets))) + ".");
                if (schema.path("properties").has("outcome")) {
                    notes.add(DROP_NOTE);
                }
                usageAndSchema(context, spec, json, schema,
                        "From the service: GET " + ProjectsApi.ARCHETYPES + ", the moves of " + SafeText.line(kind)
                                + " from " + SafeText.line(current) + ".", notes);
                return 0;
            }
            JsonNode answer = api.setWorkStatus(wanted, payload);
            WorkEntities.printAnswer(context.out(), json, answer);
            return 0;
        }

        static final String DROP_NOTE = "DROPPED takes \"outcome\"; DUPLICATE, RESOLVED_ELSEWHERE and SUPERSEDED "
                + "also take \"supersededBy\", the other item. Do not drop work merely because it is hard or stale: "
                + "block it instead.";

        /**
         * The move's payload: {@code target}, one of the open moves. When DROPPED is one of them, also
         * {@code outcome} and {@code supersededBy}, with the service's rules as conditionals: DROPPED
         * requires an outcome and nothing else takes one; the three referencing outcomes require
         * supersededBy and nothing else takes it. {@code additionalProperties} stays false either way.
         */
        static ObjectNode statusSchema(String kind, String current, ArrayNode targets) {
            JsonNodeFactory f = JsonNodeFactory.instance;
            ObjectNode schema = f.objectNode();
            schema.put("title", kind + " status move from " + current);
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("target").set("enum", targets);
            schema.putArray("required").add("target");
            schema.put("additionalProperties", false);
            boolean drops = false;
            for (JsonNode target : targets) {
                drops |= "DROPPED".equals(target.asText());
            }
            if (!drops) {
                return schema;
            }
            ObjectNode outcome = properties.putObject("outcome");
            outcome.put("type", "string");
            outcome.put("description", "Why the item is dropped; only with target DROPPED, and required there. "
                    + "DUPLICATE: another item asks for the same thing. RESOLVED_ELSEWHERE: another item's change "
                    + "already resolved it. SUPERSEDED: a successor replaced it. OBSOLETE: the subject went away by "
                    + "itself. INVALID: it never was a problem. WONT_DO: a decision was taken not to do it (scratch "
                    + "and probe items too). Not because it is hard or stale: block it instead.");
            ArrayNode outcomes = outcome.putArray("enum");
            WorkEntities.OUTCOMES.forEach(outcomes::add);
            ObjectNode supersededBy = properties.putObject("supersededBy");
            supersededBy.put("type", "string");
            supersededBy.put("description", "The other item, a qualified id or an id: required with DUPLICATE, "
                    + "RESOLVED_ELSEWHERE and SUPERSEDED, refused with any other outcome or move.");
            ArrayNode rules = schema.putArray("allOf");
            // DROPPED requires an outcome; any other target takes neither field.
            ObjectNode drop = rules.addObject();
            drop.putObject("if").putObject("properties").putObject("target").put("const", "DROPPED");
            drop.putObject("then").putArray("required").add("outcome");
            ArrayNode neither = drop.putObject("else").putObject("not").putArray("anyOf");
            neither.addObject().putArray("required").add("outcome");
            neither.addObject().putArray("required").add("supersededBy");
            // A referencing outcome requires supersededBy; any other outcome refuses it.
            ObjectNode reference = rules.addObject();
            ObjectNode referencing = reference.putObject("if");
            ArrayNode named = referencing.putObject("properties").putObject("outcome").putArray("enum");
            WorkEntities.REFERENCING.forEach(named::add);
            referencing.putArray("required").add("outcome");
            reference.putObject("then").putArray("required").add("supersededBy");
            reference.putObject("else").putObject("not").putArray("required").add("supersededBy");
            return schema;
        }
    }

    // --- block and unblock ---

    @CommandLine.Command(name = "block", mixinStandardHelpOptions = true,
            description = {"Block a ticket or an epic: say that the phase its status started cannot "
                    + "go on, and why. Use it when you stop and cannot take the work further.",
                    "Sends {\"blocked\":true,\"reason\":\"...\"} to POST /projects/api/work/{entity}/blocked. "
                            + "The status does not move: it is the phase to resume, and the next status move "
                            + "clears the block. The reason lands on the item's comment thread, so write what is "
                            + "in the way concretely enough for somebody else to act on it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 block --reason 'The database team must confirm the new limit.'",
                    "",
                    "- A feature or a task runs no phase of its own: block its epic. An item that is VERIFIED, "
                            + "DONE or DROPPED cannot be blocked (HTTP 409)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is blocked.",
                    "1:The platform refused (for example a feature, a task or a status that starts no phase, HTTP "
                            + "409, your roles or another project, HTTP 403, or an item it does not know, HTTP "
                            + "404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --reason), not signed in, or the session ended."})
    public static class BlockCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                description = "What is in the way (required). It lands on the item's thread.")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = work.entity();
            String why = RepositoriesCommand.required(reason, "Say what is in the way: --reason <text>.");
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("blocked", true);
            body.put("reason", why);
            printBlock(context.out(), json, work.api(context).setWorkBlocked(wanted, body));
            return 0;
        }
    }

    @CommandLine.Command(name = "unblock", mixinStandardHelpOptions = true,
            description = {"Unblock a ticket or an epic: say that what was in the way is gone and its "
                    + "phase can go on. Do it as soon as you know, whoever blocked it.",
                    "Sends {\"blocked\":false} to POST /projects/api/work/{entity}/blocked, with the reason when "
                            + "one is given. No status moves."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 unblock",
                    "  qits work --entity qits-100 unblock --reason 'The limit is confirmed.' -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is unblocked.",
                    "1:The platform refused (for example your roles or another project, HTTP 403, or an item it "
                            + "does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    public static class UnblockCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                description = "Why it can go on again. Optional.")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = work.entity();
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("blocked", false);
            if (reason != null && !reason.isBlank()) {
                body.put("reason", reason.strip());
            }
            printBlock(context.out(), json, work.api(context).setWorkBlocked(wanted, body));
            return 0;
        }
    }

    /** The flag as the write left it: blocked or not, and by what. */
    private static void printBlock(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        JsonNode b = answer.path("block");
        Table.print(out, "", List.of("ARCHETYPE", "STATUS", "BLOCKED", "SOURCE", "REASON"),
                List.of(List.of(WorkEntities.cell(text(b, "archetype"), 9), WorkEntities.cell(text(b, "status"), 11),
                        b.path("blocked").asBoolean() ? "yes" : "no", WorkEntities.cell(text(b, "blockSource"), 14),
                        WorkEntities.cell(text(b, "blockReason"), 80))));
    }

    // --- delete ---

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Delete a work item, its children and their comment threads. The audit log keeps them.",
                    "Sends DELETE /projects/api/work/{entity}. An agent may delete a feature or a task of its own "
                            + "project while the epic is REPORTED; deleting an epic or a ticket takes qits:admin."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-104 delete"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is gone.",
                    "1:The platform refused (for example an epic or a ticket for an agent, or another project, HTTP "
                            + "403, an item it does not know, HTTP 404, or an epic past REPORTED, HTTP 409), or "
                            + "cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = work.entity();
            JsonNode answer = work.api(context).deleteWork(wanted);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println("Deleted " + SafeText.line(wanted) + ".");
            return 0;
        }
    }

    // --- dispatch ---

    @CommandLine.Command(name = "dispatch", mixinStandardHelpOptions = true,
            description = {"Put an agent on an epic or a ticket: the Dispatch and Run the next phase buttons of the "
                    + "item's page.",
                    "--mode FLOW runs the phase the item's status starts and every phase after it, until a status "
                            + "starts none. --mode PHASE runs that one phase, then stops. A machine at REFINED waits "
                            + "for a person to move it to READY_FOR_DEV, unless the project's delivery desk may "
                            + "start refined items (qits projects delivery shows whether it may).",
                    "Sends {\"mode\":\"FLOW\"} or {\"mode\":\"PHASE\"}, with \"rule\" when --rule is given, to "
                            + "POST /projects/api/work/{entity}/dispatch, and prints the item, the mode, the phase "
                            + "started, the agent's branch and the assignee."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 dispatch --mode flow",
                    "  qits work dispatch --entity qits-100 --mode PHASE -o json",
                    "  qits work --entity qits-100 dispatch --mode FLOW --rule backlog-order",
                    "",
                    "- Pressing takes qits:admin, or the project's delivery desk, which names the rule it presses "
                            + "under; a plain agent is answered HTTP 403.",
                    "- The service refuses a status that starts no phase, a feature or a task, a blocked item, an "
                            + "item at REFINED or READY_FOR_DEV whose needs are not all met, or a project with no "
                            + "wrapper (HTTP 409), and the command prints its sentence as it came."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The agent is dispatched.",
                    "1:The platform refused (for example a mode it does not know, HTTP 400, your roles, HTTP 403, an "
                            + "item it does not know, HTTP 404, or an item no phase can start from, HTTP 409), or "
                            + "cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a --mode that is neither FLOW nor PHASE), not signed "
                            + "in, or the session ended."})
    public static class DispatchCommand extends PlatformCommand {

        static final List<String> MODES = List.of("FLOW", "PHASE");

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Option(names = "--mode", paramLabel = "<mode>",
                description = "FLOW (every phase from here on) or PHASE (the next one only); required. "
                        + "Case-insensitive.")
        String mode;

        @CommandLine.Option(names = "--rule", paramLabel = "<rule>",
                description = "The delivery desk's rule the press is made under (backlog-order, "
                        + "start-refined). Optional; the service ignores it for anyone but the desk.")
        String rule;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = work.entity();
            String how = upper(RepositoriesCommand.required(mode, "Name the mode: --mode <FLOW or PHASE>."));
            if (!MODES.contains(how)) {
                throw new CliFailure("--mode is FLOW or PHASE, not " + SafeText.line(mode.strip()) + ".",
                        CliFailure.USAGE);
            }
            JsonNode answer = work.api(context).dispatchWork(wanted, how, rule);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            JsonNode d = answer.path("dispatch");
            Table.print(context.out(), "", List.of("ENTITY", "MODE", "PHASE", "BRANCH", "ASSIGNEE"),
                    List.of(List.of(WorkEntities.cell(wanted, 40), WorkEntities.cell(text(d, "mode"), 5),
                            WorkEntities.cell(text(d, "phase"), 12), WorkEntities.cell(text(d, "branch"), 60),
                            WorkEntities.cell(text(d, "assignee"), 40))));
            return 0;
        }
    }
}
