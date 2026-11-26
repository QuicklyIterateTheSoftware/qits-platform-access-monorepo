package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.Table;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * What the {@code qits work} commands share: the full state a transition starts from, RFC 7396's
 * merge, what a target schema has no slot for, and the tables. Every value a person sees goes
 * through {@link SafeText}.
 */
final class WorkEntities {

    /**
     * The properties of a row that a transition carries, in the names the transition door reads.
     * {@code membership} is built from {@code parent} and {@code position}. These are the door's
     * field names, not a schema: which of them a target archetype takes is the served schema's call,
     * and whatever it does not list is dropped.
     * <p>
     * {@code tags} (qits-634) is deliberately not here: it is not a registry property an archetype's
     * schema carries a slot for (the transition schema lists none), so it would be dropped on every
     * transition anyway, with a stderr warning for a property this door never meant to move. The
     * service keeps a work item's tags as they stood through a transition on its own, outside the
     * full-state merge.
     */
    static final List<String> CARRIED = List.of("title", "description", "status", "outcome", "ticketType", "impetus",
            "assignee", "supersededBy", "repositoryId", "implementingAt", "implementedAt", "acceptanceCriteria");

    /**
     * Why a DROPPED item was dropped (qits-248), in the service's order. The first three name the
     * other item in {@code supersededBy}; the last three take none.
     */
    static final List<String> OUTCOMES = List.of("DUPLICATE", "RESOLVED_ELSEWHERE", "SUPERSEDED", "OBSOLETE",
            "INVALID", "WONT_DO");

    /** The outcomes that name another item in {@code supersededBy}. */
    static final List<String> REFERENCING = OUTCOMES.subList(0, 3);

    private WorkEntities() {
    }

    /**
     * The rows whose {@code outcome} is {@code outcome}. Applied after the service's own filter too,
     * because a service older than qits-248 ignores {@code ?outcome=} and answers every row.
     */
    static List<JsonNode> withOutcome(List<JsonNode> rows, String outcome) {
        return rows.stream().filter(e -> outcome.equals(text(e, "outcome"))).toList();
    }

    /**
     * The rows that name {@code entity} as their {@code supersededBy}: what it closes. The service
     * stores a UUID, so the id is what matches; the qualified id is tried as well. Applied after the
     * service's own filter too, because a service older than qits-248 ignores {@code ?supersededBy=}
     * and answers the whole project.
     */
    static List<JsonNode> superseding(List<JsonNode> rows, JsonNode entity) {
        String id = text(entity, "id");
        String qualified = text(entity, "qualifiedId");
        return rows.stream().filter(e -> {
            String by = text(e, "supersededBy");
            return !by.isEmpty() && (by.equals(id) || by.equals(qualified));
        }).toList();
    }

    /** The item's tags, as the service names them (its enum names); empty when it carries none. */
    private static List<String> tags(JsonNode e) {
        JsonNode array = e.path("tags");
        List<String> all = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(t -> {
                String name = t.asText();
                if (!name.isEmpty()) {
                    all.add(name);
                }
            });
        }
        return all;
    }

    /** A tag's display text: lower case, its underscores as spaces ({@code RESERVED_FOR_HUMAN} to "reserved for human"). */
    private static String tagDisplay(String tag) {
        return tag.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** The item's tags, display text, comma-joined; empty when it carries none. */
    private static String tagsText(JsonNode e) {
        List<String> words = new ArrayList<>();
        tags(e).forEach(tag -> words.add(tagDisplay(tag)));
        return String.join(", ", words);
    }

    /** The status, with the outcome beside it when the service sent one: {@code DROPPED (DUPLICATE)}. */
    static String status(JsonNode e) {
        String outcome = text(e, "outcome");
        return outcome.isEmpty() ? text(e, "status") : text(e, "status") + " (" + outcome + ")";
    }

    /** How details labels {@code supersededBy}: by the outcome that names it, when there is one. */
    private static String supersededByLabel(JsonNode e) {
        return switch (text(e, "outcome")) {
            case "DUPLICATE" -> "duplicate of";
            case "RESOLVED_ELSEWHERE" -> "resolved by";
            default -> "superseded by";
        };
    }

    /** The row as the full state the transition door takes: what it carries now, nothing else. */
    static ObjectNode currentState(JsonNode row) {
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        for (String field : CARRIED) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull()) {
                state.set(field, value.deepCopy());
            }
        }
        JsonNode parent = row.get("parent");
        if (parent != null && !parent.isNull()) {
            ObjectNode membership = state.putObject("membership");
            membership.set("parent", parent.deepCopy());
            JsonNode position = row.get("position");
            if (position != null && !position.isNull()) {
                membership.set("position", position.deepCopy());
            }
        }
        return state;
    }

    /** RFC 7396: the patch applied to a copy of the target. Null removes, an object merges, anything else replaces. */
    static JsonNode mergePatch(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode result = target != null && target.isObject() ? ((ObjectNode) target).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : patch.properties()) {
            if (field.getValue().isNull()) {
                result.remove(field.getKey());
            } else {
                result.set(field.getKey(), mergePatch(result.get(field.getKey()), field.getValue()));
            }
        }
        return result;
    }

    /** The properties of {@code state} the schema lists no slot for, in the state's order. */
    static List<String> unslotted(JsonNode schema, JsonNode state) throws CliFailure {
        JsonNode properties = properties(schema);
        List<String> none = new ArrayList<>();
        state.fieldNames().forEachRemaining(name -> {
            if (!properties.has(name)) {
                none.add(name);
            }
        });
        return none;
    }

    /**
     * The schema's required properties the state does not carry. An object property the state has
     * is looked into one level ({@code membership.parent}); one it lacks is named with what it
     * requires, so the answer says what to write.
     */
    static List<String> missing(JsonNode schema, JsonNode state) throws CliFailure {
        JsonNode properties = properties(schema);
        List<String> missing = new ArrayList<>();
        for (JsonNode required : schema.path("required")) {
            String name = required.asText();
            JsonNode inner = properties.path(name).path("required");
            JsonNode value = state.get(name);
            if (value == null || value.isNull()) {
                if (inner.isArray() && !inner.isEmpty()) {
                    inner.forEach(r -> missing.add(SafeText.line(name + "." + r.asText())));
                } else {
                    missing.add(SafeText.line(name));
                }
            } else if (inner.isArray() && value.isObject()) {
                inner.forEach(r -> {
                    if (!value.hasNonNull(r.asText())) {
                        missing.add(SafeText.line(name + "." + r.asText()));
                    }
                });
            }
        }
        return missing;
    }

    private static JsonNode properties(JsonNode schema) throws CliFailure {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            throw new CliFailure("Nothing was sent. The service's schema lists no properties, so what the target "
                    + "archetype takes cannot be told.", CliFailure.FAILED);
        }
        return properties;
    }

    /** The registry's entry for one archetype; null when it has none. */
    static JsonNode declared(JsonNode registry, String archetype) {
        for (JsonNode entry : registry.path("archetypes")) {
            if (archetype.equals(text(entry, "archetype"))) {
                return entry;
            }
        }
        return null;
    }

    static List<String> texts(JsonNode array) {
        List<String> all = new ArrayList<>();
        array.forEach(v -> all.add(SafeText.line(v.asText())));
        return all;
    }

    /** The entities of a list answer, {@code {"entities":[…]}}. */
    static List<JsonNode> list(JsonNode answer) {
        return objects(answer.path("entities"));
    }

    /** The children of a children answer, {@code {"children":[…]}}. */
    static List<JsonNode> children(JsonNode answer) {
        return objects(answer.path("children"));
    }

    private static List<JsonNode> objects(JsonNode array) {
        List<JsonNode> all = new ArrayList<>();
        array.forEach(e -> {
            if (e.isObject()) {
                all.add(e);
            }
        });
        return all;
    }

    /** A write's answer: as JSON, or the one row the item has in a list. */
    static void printAnswer(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        printRows(out, "", List.of(answer));
    }

    /**
     * ID (qualified), ARCHETYPE, STATUS, BLOCKED when one is, NEEDS when one needs another item that
     * is not at its status yet (epic qits-1899), TAGS when one carries any, ASSIGNEE when one has one
     * (epic qits-2520), TITLE, UPDATED.
     */
    static void printRows(PrintStream out, String indent, List<JsonNode> entities) {
        boolean anyBlocked = entities.stream().anyMatch(WorkEntities::blocked);
        boolean anyNeeds = entities.stream().anyMatch(WorkEntities::needsSomething);
        boolean anyTagged = entities.stream().anyMatch(e -> !tags(e).isEmpty());
        boolean anyAssigned = entities.stream().anyMatch(e -> !text(e, "assignee").isBlank());
        List<String> headers = new ArrayList<>(List.of("ID", "ARCHETYPE", "STATUS"));
        if (anyBlocked) {
            headers.add("BLOCKED");
        }
        if (anyNeeds) {
            headers.add("NEEDS");
        }
        if (anyTagged) {
            headers.add("TAGS");
        }
        if (anyAssigned) {
            headers.add("ASSIGNEE");
        }
        headers.add("TITLE");
        headers.add("UPDATED");
        Table.print(out, indent, headers, entities.stream().map(e -> {
            List<String> row = new ArrayList<>(List.of(cell(label(e), 40), cell(text(e, "archetype"), 9),
                    // 30, so that DROPPED (RESOLVED_ELSEWHERE), the longest status with its outcome, is not cut
                    cell(status(e), 30)));
            if (anyBlocked) {
                row.add(blockedCell(e));
            }
            if (anyNeeds) {
                row.add(needsCell(e));
            }
            if (anyTagged) {
                String joined = tagsText(e);
                row.add(cell(joined.isEmpty() ? "-" : joined, 40));
            }
            if (anyAssigned) {
                String held = text(e, "assignee");
                row.add(cell(held.isBlank() ? "-" : held, 40));
            }
            row.add(cell(text(e, "title"), 70));
            row.add(Table.time(text(e, "updatedAt")));
            return row;
        }).toList());
    }

    /**
     * One item in full: its fields, its description, its thread, its children and what it closes
     * (the items naming it as their {@code supersededBy}; null when that could not be read).
     */
    static void printDetails(PrintStream out, JsonNode e, List<JsonNode> comments, List<JsonNode> children,
            List<JsonNode> closes) {
        out.println(SafeText.line(text(e, "archetype")) + " " + SafeText.line(label(e)) + "  ("
                + SafeText.line(text(e, "id")) + ")");
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("title", text(e, "title")));
        rows.add(row("status", status(e)));
        if (e.has("blocked") && !e.get("blocked").isNull()) {
            rows.add(row("blocked", blockedDetail(e)));
        }
        List<JsonNode> unmet = needs(e, false);
        if (!unmet.isEmpty()) {
            needRows(rows, "needs", unmet);
        } else if (needsSomething(e)) {
            rows.add(row("needs", needsCell(e)));
        }
        needRows(rows, "needs met", needs(e, true));
        String tagsText = tagsText(e);
        if (!tagsText.isEmpty()) {
            rows.add(row("tags", tagsText));
        }
        optional(rows, e, "ticket type", "ticketType");
        optional(rows, e, "impetus", "impetus");
        optional(rows, e, "assignee", "assignee");
        optional(rows, e, "parent", "parent");
        optional(rows, e, "position", "position");
        optional(rows, e, "repository", "repositoryId");
        optional(rows, e, "inspired by", "inspiredBy");
        optional(rows, e, "error issue", "errorIssue");
        optional(rows, e, supersededByLabel(e), "supersededBy");
        if (!text(e, "implementedAt").isEmpty()) {
            rows.add(List.of("implemented", Table.time(text(e, "implementedAt"))));
        }
        rows.add(row("created by", text(e, "createdBy")));
        rows.add(List.of("created", Table.time(text(e, "createdAt"))));
        rows.add(List.of("updated", Table.time(text(e, "updatedAt"))));
        Table.print(out, "  ", null, rows);
        out.println("Description:");
        String description = text(e, "description");
        if (description.isBlank()) {
            out.println("  (none)");
        } else {
            lines(out, "  ", description);
        }
        acceptanceCriteria(out, e);
        if (comments.isEmpty()) {
            out.println("Comments: none.");
        } else {
            out.println("Comments (" + comments.size() + "):");
            for (JsonNode comment : comments) {
                out.println("  " + Table.time(text(comment, "createdAt")) + "  "
                        + cell(WorkCommentCommand.author(comment), 80));
                lines(out, "    ", text(comment, "body"));
            }
        }
        if (children.isEmpty()) {
            out.println("Children: none.");
        } else {
            out.println("Children (" + children.size() + "):");
            printRows(out, "  ", children);
        }
        if (closes == null) {
            out.println("Closes: could not be read.");
        } else if (closes.isEmpty()) {
            out.println("Closes: none.");
        } else {
            out.println("Closes (" + closes.size() + "), the items naming this one as their supersededBy:");
            printRows(out, "  ", closes);
        }
    }

    /** Acceptance criteria, one numbered Markdown line each; nothing printed when the archetype carries none. */
    private static void acceptanceCriteria(PrintStream out, JsonNode e) {
        JsonNode criteria = e.path("acceptanceCriteria");
        if (!criteria.isArray() || criteria.isEmpty()) {
            return;
        }
        out.println("Acceptance criteria:");
        int n = 1;
        for (JsonNode item : criteria) {
            out.println("  " + n + ". " + SafeText.line(item.asText()));
            n++;
        }
    }

    private static void optional(List<List<String>> rows, JsonNode e, String name, String field) {
        String value = text(e, field);
        if (!value.isEmpty()) {
            rows.add(row(name, value));
        }
    }

    /** The qualified id, which is what a person types; the id when the service sent none. */
    private static String label(JsonNode e) {
        String qualified = text(e, "qualifiedId");
        return qualified.isEmpty() ? text(e, "id") : qualified;
    }

    /**
     * Blocked means the phase the item's status belongs to cannot proceed. Only tickets carry it.
     * The service computes it as the effective block: an explicit block, or an agent session
     * waiting idle, or both.
     */
    private static boolean blocked(JsonNode e) {
        return e.path("blocked").asBoolean(false);
    }

    /** EXPLICIT, AGENT_WAITING or BOTH; empty when the item is not blocked or an older service sends none. */
    private static String blockSource(JsonNode e) {
        return text(e, "blockSource");
    }

    /** The BLOCKED column of a list: yes, waiting for an agent-only block, or - when it is not blocked. */
    private static String blockedCell(JsonNode e) {
        if (!blocked(e)) {
            return "-";
        }
        return "AGENT_WAITING".equals(blockSource(e)) ? "waiting" : "yes";
    }

    /**
     * The blocked row of details: no when it is not; yes (agent waiting) for an agent-only block;
     * yes[: reason][ (by who)] for an explicit block, its absent parts left out; the explicit form
     * with ", agent waiting" appended when both hold; plain yes when an older service sends no
     * blockSource.
     */
    private static String blockedDetail(JsonNode e) {
        if (!blocked(e)) {
            return "no";
        }
        String source = blockSource(e);
        if (source.isEmpty()) {
            return "yes";
        }
        if ("AGENT_WAITING".equals(source)) {
            return "yes (agent waiting)";
        }
        String explicit = explicitBlock(e);
        return "BOTH".equals(source) ? explicit + ", agent waiting" : explicit;
    }

    /** yes, with the reason and who after it, whichever the service sent. */
    private static String explicitBlock(JsonNode e) {
        String reason = text(e, "blockReason");
        String by = text(e, "blockedBy");
        StringBuilder explicit = new StringBuilder("yes");
        if (!reason.isEmpty()) {
            explicit.append(": ").append(reason);
        }
        if (!by.isEmpty()) {
            explicit.append(" (by ").append(by).append(")");
        }
        return explicit.toString();
    }

    /**
     * The item's needs (epic qits-1899) that are met ({@code met}) or not: each names another item,
     * the status it must reach, and where it stands now. Empty when the service sends none.
     */
    static List<JsonNode> needs(JsonNode e, boolean met) {
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode need : e.path("needs")) {
            if (need.isObject() && need.path("met").asBoolean(false) == met) {
                all.add(need);
            }
        }
        return all;
    }

    /** Whether the item needs another item that is not at its status yet. */
    static boolean needsSomething(JsonNode e) {
        return !needs(e, false).isEmpty() || e.path("unmetNeeds").asInt(0) > 0;
    }

    /**
     * The NEEDS column: each unmet need as {@code <qualifiedId>@<status>}, comma-joined, or - when it
     * needs nothing. An answer that carries only the count ({@code unmetNeeds}) gets the count.
     */
    static String needsCell(JsonNode e) {
        List<String> unmet = new ArrayList<>();
        needs(e, false).forEach(n -> unmet.add(text(n, "qualifiedId") + "@" + text(n, "status")));
        if (!unmet.isEmpty()) {
            return cell(String.join(", ", unmet), 60);
        }
        int count = e.path("unmetNeeds").asInt(0);
        return count > 0 ? count + " unmet" : "-";
    }

    /** One need as details prints it: {@code qits-1897 at VERIFIED (now REFINED)}. */
    static String needLine(JsonNode need) {
        String now = text(need, "currentStatus");
        return text(need, "qualifiedId") + " at " + text(need, "status") + (now.isEmpty() ? "" : " (now " + now + ")");
    }

    /** A details row per need, the label on the first one only. */
    private static void needRows(List<List<String>> rows, String label, List<JsonNode> needs) {
        for (int i = 0; i < needs.size(); i++) {
            rows.add(row(i == 0 ? label : "", needLine(needs.get(i))));
        }
    }

    /** The text a line at a time, each line cleaned on its own and indented. */
    static void lines(PrintStream out, String indent, String text) {
        for (String line : text.split("\\R", -1)) {
            out.println((indent + SafeText.line(line)).stripTrailing());
        }
    }

    private static List<String> row(String name, String value) {
        return List.of(name, cell(value, 600));
    }

    static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }
}
