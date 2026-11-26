package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The summary reads of qits-observability's buffer: {@code qits observe sources|errors|slow-spans|
 * metrics}, each one GET under {@code /observability/api/telemetry}. They answer what the buffer
 * holds when called and never wait, so unlike {@code qits observe} they are plain commands.
 * <p>
 * A bucket is named by {@code --source} (a key from {@code sources}), or by a repository and a
 * workspace id; the service ignores the pair when a key is given. An unknown bucket answers empty,
 * not 404, so {@code sources} is how a caller tells "nothing happened" from "wrong bucket".
 * Everything in a record is written by the process that sent it, so text goes through {@link
 * SafeText}.
 * <p>
 * {@link TelemetryRead} also carries the error-issue doors {@link ObserveIssuesCommand} calls
 * (epic qits-2538), so every call to qits-observability's REST side goes through one class.
 */
final class ObserveTelemetryCommands {

    private ObserveTelemetryCommands() {
    }

    static final String TELEMETRY = "/observability/api/telemetry";
    static final String ERROR_ISSUES = "/observability/api/error-issues";

    /** What every read takes: where the service is and how to answer. */
    abstract static class TelemetryRead extends PlatformCommand {

        @CommandLine.Option(names = "--observability-url", paramLabel = "<url>",
                description = "The observability service's base URL, without /observability. Default: "
                        + "QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for "
                        + "`observability`.")
        String observabilityUrl;

        @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<text|json>", defaultValue = "text",
                description = "text: aligned columns (the default). json: the service's answer.")
        String output;

        JsonNode get(CliContext context, String path, List<String> query) throws CliFailure, InterruptedException {
            return new PlatformClient(context.credential()).get(uri(context, TELEMETRY + path, query));
        }

        /** A read of the stored error issues (epic qits-2538), under {@code /observability/api/error-issues}. */
        JsonNode issues(CliContext context, String path, List<String> query) throws CliFailure, InterruptedException {
            return new PlatformClient(context.credential()).get(uri(context, ERROR_ISSUES + path, query));
        }

        /** Dismisses one error issue: a person's decision, so the agent credential gets 403. */
        JsonNode dismiss(CliContext context, String fingerprint) throws CliFailure, InterruptedException {
            return new PlatformClient(context.credential())
                    .post(uri(context, ERROR_ISSUES + "/" + fingerprint + "/dismiss", List.of()));
        }

        private URI uri(CliContext context, String path, List<String> query)
                throws CliFailure, InterruptedException {
            String base = PlatformUrls.observability(observabilityUrl, context.env(), context.idpUrl());
            try {
                URI uri = URI.create(base + path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
                if (uri.getHost() == null) {
                    throw new IllegalArgumentException("no host");
                }
                return uri;
            } catch (IllegalArgumentException e) {
                throw new CliFailure("'" + base + "' is not a usable observability address. Give an http or https "
                        + "base URL.", CliFailure.USAGE);
            }
        }

        boolean json() throws CliFailure {
            return switch (output == null ? "text" : output) {
                case "text" -> false;
                case "json" -> true;
                default -> throw new CliFailure("--output must be text or json, not '" + output + "'.",
                        CliFailure.USAGE);
            };
        }
    }

    /** A read of one bucket, narrowed by service and time. */
    abstract static class BucketRead extends TelemetryRead {

        @CommandLine.Option(names = "--source", paramLabel = "<key>",
                description = "The bucket: a key as `qits observe sources` lists it, such as _service/qits-ci. "
                        + "Wins over --repository-id and --workspace-id.")
        String source;

        @CommandLine.Option(names = "--repository-id", paramLabel = "<id>",
                description = "With --workspace-id, the bucket of one workspace: its repository's id.")
        String repositoryId;

        @CommandLine.Option(names = "--workspace-id", paramLabel = "<id>",
                description = "With --repository-id, the bucket of one workspace: its id.")
        String workspaceId;

        @CommandLine.Option(names = "--service", paramLabel = "<name>",
                description = "Only records of this service.name.")
        String service;

        List<String> scope() throws CliFailure {
            boolean pair = present(repositoryId) || present(workspaceId);
            if (!present(source) && !(present(repositoryId) && present(workspaceId))) {
                throw new CliFailure(pair
                        ? "--repository-id and --workspace-id go together: give both, or --source <key>."
                        : "Name the bucket: --source <key> (see `qits observe sources`), or --repository-id <id> "
                                + "with --workspace-id <id>.", CliFailure.USAGE);
            }
            List<String> query = new ArrayList<>();
            add(query, "source", source);
            add(query, "repositoryId", repositoryId);
            add(query, "workspaceId", workspaceId);
            add(query, "service", service);
            return query;
        }
    }

    static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    static void add(List<String> query, String name, Object value) {
        if (value != null && present(value.toString())) {
            query.add(name + "=" + URLEncoder.encode(value.toString().strip(), StandardCharsets.UTF_8));
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }

    static void printJson(PrintStream out, JsonNode answer) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(answer));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    /** {@code total} and {@code truncated}: a line when the list was cut. */
    static void truncation(PrintStream out, JsonNode answer, int shown) {
        if (answer.path("truncated").asBoolean()) {
            out.println("Showing " + shown + " of " + answer.path("total").asInt() + ". Raise --limit for more.");
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
    @CommandLine.Command(name = "sources", mixinStandardHelpOptions = true,
            description = {"List the buckets qits-observability holds telemetry in: each one's key, kind "
                    + "(SERVICE, WORKSPACE or UNSCOPED), label, repository and workspace ids, and how many spans, "
                    + "logs and metric series it holds.",
                    "Reads GET /observability/api/telemetry/sources. The key is what --source takes; pass it back "
                            + "as it is."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe sources", "  qits observe sources -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class SourcesCommand extends TelemetryRead {

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            JsonNode answer = get(context, "/sources", List.of());
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode s : answer.path("sources")) {
                rows.add(List.of(cell(text(s, "key"), 80), cell(text(s, "kind"), 9), cell(text(s, "label"), 50),
                        text(s, "spans"), text(s, "logs"), text(s, "metricSeries"),
                        Table.time(text(s, "newestReceivedAt"))));
            }
            if (rows.isEmpty()) {
                context.out().println("The buffer holds no telemetry.");
                return 0;
            }
            Table.print(context.out(), "", List.of("KEY", "KIND", "LABEL", "SPANS", "LOGS", "METRICS", "NEWEST"),
                    rows);
            return 0;
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
    @CommandLine.Command(name = "errors", mixinStandardHelpOptions = true,
            description = {"Recent errors in one bucket, grouped by trace: spans with an error status, their "
                    + "exception events with stack traces, and ERROR logs. Start here when a failure needs "
                    + "explaining; follow a trace with `qits observe query --filter 'trace=<id>'`.",
                    "Reads GET /observability/api/telemetry/errors. The table shows each trace, its service, how "
                            + "many error spans and logs it has, and the first message; -o json has every span, "
                            + "event and log in full."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe errors --source _service/qits-ci --since-minutes 30",
                    "  qits observe errors --repository-id <repo> --workspace-id <workspace> -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a --limit above 1000, HTTP 400), or cannot be reached.",
                    "2:Used wrongly (for example no bucket named), not signed in, or the session ended."})
    public static class ErrorsCommand extends BucketRead {

        @CommandLine.Option(names = "--since-minutes", paramLabel = "<n>",
                description = "Only errors received in the last n minutes. Default: everything buffered.")
        Integer sinceMinutes;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "At most n traces, 1 to 1000. Default: 200.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            List<String> query = scope();
            add(query, "sinceMinutes", sinceMinutes);
            add(query, "limit", limit);
            JsonNode answer = get(context, "/errors", query);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode g : answer.path("groups")) {
                JsonNode spans = g.path("errorSpans");
                JsonNode logs = g.path("errorLogs");
                String first = spans.isEmpty() ? text(logs.path(0), "body")
                        : text(spans.path(0), "name") + ": " + text(spans.path(0), "statusMessage");
                rows.add(List.of(cell(text(g, "traceId"), 32), cell(text(g, "serviceName"), 40),
                        String.valueOf(spans.size()), String.valueOf(logs.size()), cell(first, 100)));
            }
            if (rows.isEmpty()) {
                context.out().println("No errors.");
                return 0;
            }
            Table.print(context.out(), "", List.of("TRACE", "SERVICE", "SPANS", "LOGS", "FIRST"), rows);
            truncation(context.out(), answer, rows.size());
            return 0;
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
    @CommandLine.Command(name = "slow-spans", mixinStandardHelpOptions = true,
            description = {"Spans in one bucket that took at least --threshold-ms, slowest first: duration, "
                    + "service, span name, status and trace.",
                    "Reads GET /observability/api/telemetry/slow-spans. -o json has each span's attributes and "
                            + "events too."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe slow-spans --source _service/qits-ci --threshold-ms 1000",
                    "  qits observe slow-spans --repository-id <repo> --workspace-id <workspace> --since-minutes 10"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a --limit above 1000, HTTP 400), or cannot be reached.",
                    "2:Used wrongly (for example no bucket named), not signed in, or the session ended."})
    public static class SlowSpansCommand extends BucketRead {

        @CommandLine.Option(names = "--threshold-ms", paramLabel = "<ms>", defaultValue = "500",
                description = "The shortest duration to list, in milliseconds. Default: ${DEFAULT-VALUE}.")
        long thresholdMs;

        @CommandLine.Option(names = "--since-minutes", paramLabel = "<n>",
                description = "Only spans received in the last n minutes. Default: everything buffered.")
        Integer sinceMinutes;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "At most n spans, 1 to 1000. Default: 200.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            List<String> query = scope();
            add(query, "thresholdMs", thresholdMs);
            add(query, "sinceMinutes", sinceMinutes);
            add(query, "limit", limit);
            JsonNode answer = get(context, "/slow-spans", query);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode s : answer.path("spans")) {
                rows.add(List.of(text(s, "durationMs") + " ms", cell(text(s, "serviceName"), 40),
                        cell(text(s, "name"), 80), cell(text(s, "status"), 8), cell(text(s, "traceId"), 32)));
            }
            if (rows.isEmpty()) {
                context.out().println("No span took " + thresholdMs + " ms or more.");
                return 0;
            }
            Table.print(context.out(), "", List.of("DURATION", "SERVICE", "NAME", "STATUS", "TRACE"), rows);
            truncation(context.out(), answer, rows.size());
            return 0;
        }
    }

    @TuiCommand(interaction = Interaction.PLAIN, effect = Effect.READ)
    @CommandLine.Command(name = "metrics", mixinStandardHelpOptions = true,
            description = {"The latest value of every metric series in one bucket: name, value, unit, service "
                    + "and the series' attributes. Gauges and counters alike, with no maths over time.",
                    "Reads GET /observability/api/telemetry/metrics."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits observe metrics --source _service/qits-ci",
                    "  qits observe metrics --source _service/qits-ci --name http.server.active_requests -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example no bucket named), not signed in, or the session ended."})
    public static class MetricsCommand extends BucketRead {

        @CommandLine.Option(names = "--name", paramLabel = "<metric>",
                description = "Only the series of this metric, by its exact name.")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            List<String> query = scope();
            add(query, "name", name);
            JsonNode answer = get(context, "/metrics", query);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode m : answer.path("metrics")) {
                List<String> attributes = new ArrayList<>();
                for (Map.Entry<String, JsonNode> a : m.path("attributes").properties()) {
                    attributes.add(a.getKey() + "=" + a.getValue().asText());
                }
                rows.add(List.of(cell(text(m, "name"), 60), cell(text(m, "value"), 20), cell(text(m, "unit"), 10),
                        cell(text(m, "serviceName"), 40), cell(String.join(" ", attributes), 80)));
            }
            if (rows.isEmpty()) {
                context.out().println("No metrics.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "VALUE", "UNIT", "SERVICE", "ATTRIBUTES"), rows);
            return 0;
        }
    }
}
