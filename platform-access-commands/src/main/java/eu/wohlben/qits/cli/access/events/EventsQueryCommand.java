package eu.wohlben.qits.cli.access.events;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.QueryWindow;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code qits events query}: the events log's past in a window, read from {@code GET
 * /events/api/events}, never from the stream.
 * <p>
 * The route has a lower bound ({@code ?since=}, inclusive) and no upper one: its cursor is the upper
 * bound. {@code EventCursor} reads {@code occurred_at < :at or (occurred_at = :at and id < :id)}
 * and truncates its instant to microseconds, so the first page starts at {@code until} truncated to
 * microseconds plus one microsecond, with the id {@code 0}: every row at or before {@code until},
 * and nothing after it. The pages run newest first and follow {@code nextCursor} until {@code limit}
 * rows are in hand or the log has no more; the rows are then printed oldest first.
 */
@TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
@CommandLine.Command(name = "query", mixinStandardHelpOptions = true,
        description = {
                "Print the qits-events domain events that already happened in a window of time, oldest first, "
                        + "from the events log. It answers with what exists when it is called and never waits for "
                        + "a new event; `qits events` is the live form.",
                "The window is --since to --until, both inclusive; --since is an hour ago and --until is now unless "
                        + "given. When more than --limit events fall in it, the newest --limit are kept. The text "
                        + "form starts with a line giving the window as two absolute instants, then prints one "
                        + "JSON object per event, the same line `qits events` prints, and ends with a line saying "
                        + "so when the answer was cut. -o json prints one object: "
                        + "{\"events\": [...], \"truncated\": true|false, \"window\": {\"since\": ..., \"until\": ...}}."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits events query --filter=BuildFailed --since 1d",
                "  qits events query --filter=ReleaseRequestChanged --since 2026-10-01T18:00:00Z --until 2026-10-01T19:00:00Z",
                "  qits events query --since 15m -o json | jq -r '.events[].name'",
                "",
                "--filter takes exact event names, as `qits events` does. A pattern such as Build* is refused."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                "2:Used wrongly (a pattern in --filter, a time that cannot be read, --since after --until, a "
                        + "--limit outside 1..1000), not signed in, or the session ended."})
public class EventsQueryCommand extends PlatformCommand {

    /** The events service's own cap on one page. */
    static final int PAGE_LIMIT = 1000;

    /**
     * The log row's own times, which the live stream's frame ({@code EventCreated}) never carries:
     * they say when the log stored the event, not what happened. Every other field of the row is the
     * frame and passes through, so a field the frame gains later reaches the line without a change
     * here (qits-2729: a list of the fields to keep had hidden {@code originTraceId} and
     * {@code onBehalfOf}).
     */
    private static final List<String> ROW_ONLY_FIELDS = List.of("createdAt", "updatedAt");

    @CommandLine.Option(names = "--filter", paramLabel = "<names>", defaultValue = "*",
            description = "Which events: exact event names, comma-separated (for example BuildSuccessful,BuildFailed), "
                    + "or * for every event. No patterns. Default: ${DEFAULT-VALUE}.")
    String filter;

    @CommandLine.Option(names = "--events-url", paramLabel = "<url>",
            description = "The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the "
                    + "session's idp address with `idp` swapped for `events`.")
    String eventsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<text|json>", defaultValue = "text",
            description = "text: the window, then one line per event (the default). json: one object with the "
                    + "events, whether the answer was cut, and the window.")
    String output;

    @CommandLine.Mixin
    QueryWindow window;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = QueryWindow.json(output);
        String names = EventsCommand.names(filter);
        QueryWindow.Resolved resolved = window.resolve(context.clock());
        PlatformClient client = new PlatformClient(context.credential());
        String base = PlatformUrls.events(eventsUrl, context.env(), context.idpUrl());

        List<ObjectNode> newestFirst = new ArrayList<>();
        boolean truncated = false;
        String cursor = firstCursor(resolved.until());
        while (true) {
            int wanted = Math.min(resolved.limit() - newestFirst.size(), PAGE_LIMIT);
            URI uri = pageUri(base, names, resolved.since(), cursor, wanted);
            JsonNode page = client.get(uri);
            JsonNode events = page.path("events");
            if (!events.isArray()) {
                throw new CliFailure("GET " + uri + " answered without an events list.", CliFailure.FAILED);
            }
            for (JsonNode event : events) {
                if (newestFirst.size() == resolved.limit()) {
                    truncated = true;
                    break;
                }
                newestFirst.add(liveFields(event));
            }
            JsonNode next = page.path("nextCursor");
            if (truncated || !next.isTextual() || next.asText().isBlank()) {
                break;
            }
            if (newestFirst.size() >= resolved.limit()) {
                truncated = true;
                break;
            }
            if (next.asText().equals(cursor)) {
                throw new CliFailure("GET " + uri + " answered with the cursor it was asked for; the log cannot be "
                        + "paged further.", CliFailure.FAILED);
            }
            cursor = next.asText();
        }
        List<ObjectNode> oldestFirst = newestFirst.reversed();
        print(context.out(), json, oldestFirst, truncated, resolved);
        return 0;
    }

    /**
     * The first page's cursor: {@code until}, truncated to the microseconds the log keeps, plus one
     * microsecond, and the id {@code 0}. The cursor is exclusive, so this takes every row at or
     * before {@code until}, ties at {@code until} included, and none after it.
     */
    static String firstCursor(Instant until) {
        return until.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS) + ",0";
    }

    static URI pageUri(String base, String names, Instant since, String cursor, int limit) throws CliFailure {
        StringBuilder query = new StringBuilder();
        if (!names.equals("*")) {
            // The log takes no `*`: no name filter is every event.
            query.append("name=").append(names).append('&');
        }
        query.append("since=").append(encode(since.toString()))
                .append("&order=desc&limit=").append(limit)
                .append("&cursor=").append(encode(cursor));
        try {
            return URI.create(base + "/events/api/events?" + query);
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable events address.", CliFailure.USAGE);
        }
    }

    /** The row as the live stream would have sent it, with its payload read as JSON. */
    static ObjectNode liveFields(JsonNode row) {
        ObjectNode event = row instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        event.remove(ROW_ONLY_FIELDS);
        return EventStream.line(event);
    }

    private static void print(PrintStream out, boolean json, List<ObjectNode> events, boolean truncated,
                              QueryWindow.Resolved window) {
        if (json) {
            ObjectNode answer = JsonNodeFactory.instance.objectNode();
            ArrayNode list = answer.putArray("events");
            events.forEach(list::add);
            answer.put("truncated", truncated);
            answer.putObject("window").put("since", window.since().toString()).put("until", window.until().toString());
            out.println(EventStream.write(answer));
            return;
        }
        out.println(QueryWindow.windowLine(window));
        for (ObjectNode event : events) {
            out.println(EventStream.write(event));
        }
        if (truncated) {
            out.println(QueryWindow.truncatedLine(events.size()));
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
