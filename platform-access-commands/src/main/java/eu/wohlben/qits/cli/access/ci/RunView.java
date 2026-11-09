package eu.wohlben.qits.cli.access.ci;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestRefs;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * How runs look in a terminal. A step's output is written by the code of the repository it builds,
 * so, like streamed telemetry, it is untrusted text: every value goes through {@link SafeText}
 * before a person sees it, and the JSON form writes control characters as escapes.
 */
final class RunView {

    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final int TAIL_LINES = 20;

    private RunView() {
    }

    static void printRuns(PrintStream out, List<JsonNode> runs, Instant now) {
        Table.print(out, "", List.of("ID", "STATUS", "BRANCH", "COMMIT", "REQUEST", "CREATED", "TOOK"),
                runs.stream().map(r -> List.of(
                        cell(shortId(text(r, "id")), 8),
                        cell(text(r, "status"), 12),
                        cell(text(r, "branch"), 60),
                        cell(shortId(text(r, "commitSha")), 8),
                        cell(ReleaseRequestRefs.label(text(r, "releaseRequestQualifiedId"), text(r, "releaseRequestId")), 40),
                        Table.time(text(r, "createdAt")),
                        took(r, now))).toList());
    }

    /** The run's release request: its logical id with the uuid beside it, or the uuid alone from an older qits-ci. */
    private static String releaseRequest(JsonNode run) {
        String qualifiedId = text(run, "releaseRequestQualifiedId");
        String id = text(run, "releaseRequestId");
        return qualifiedId.isEmpty() || id.isEmpty() ? qualifiedId + id : qualifiedId + " (" + id + ")";
    }

    static void printRun(PrintStream out, JsonNode run, Instant now) {
        out.println("Run " + SafeText.line(text(run, "id")));
        String repository = text(run, "repoName").isEmpty() ? text(run, "repoId") : text(run, "repoName");
        String reason = text(run, "cancellationReason");
        Table.print(out, "  ", null, List.of(
                row("repository", repository),
                row("status", text(run, "status") + (reason.isEmpty() ? "" : " (" + reason + ")")),
                row("gating", run.path("gating").asBoolean() ? "yes" : "no"),
                row("branch", text(run, "branch")),
                row("commit", text(run, "commitSha")),
                row("release request", releaseRequest(run)),
                row("retry of", text(run, "retryOfRunId")),
                row("superseded by", text(run, "supersededByRunId")),
                row("trigger", text(run, "triggerType") + " " + text(run, "triggerEventName")),
                row("config", text(run, "configPath")),
                List.of("created", Table.time(text(run, "createdAt"))),
                List.of("started", Table.time(text(run, "startedAt"))),
                List.of("finished", Table.time(text(run, "finishedAt"))),
                List.of("took", took(run, now))));

        List<List<String>> rows = new ArrayList<>();
        for (JsonNode step : steps(run)) {
            JsonNode exit = step.path("exitCode");
            rows.add(List.of(step.path("stepIndex").asText(), cell(text(step, "image"), 80), cell(text(step, "status"), 12),
                    exit.isNumber() ? exit.asText() : "-", took(step, now)));
        }
        JsonNode live = run.path("live");
        if (live.isObject()) {
            rows.add(List.of(live.path("stepIndex").asText(), "-", RUNNING, "-", liveTook(live, now)));
        }
        if (rows.isEmpty()) {
            out.println("QUEUED".equals(text(run, "status")) ? "No steps yet: the run is queued." : "No steps.");
            return;
        }
        out.println("Steps:");
        Table.print(out, "  ", List.of("STEP", "IMAGE", "STATUS", "EXIT", "TOOK"), rows);
    }

    /**
     * Without {@code --logs}, the one thing worth seeing without asking for the whole log: the end
     * of a failed step's output. A step that finished SUCCESS, or has no output at all (still
     * running, or the service kept none), prints nothing. A run with no failed step, or none with
     * output, prints nothing here either, so its output is unchanged from before this existed.
     */
    static void printFailedTails(PrintStream out, JsonNode run) {
        boolean any = false;
        for (JsonNode step : steps(run)) {
            String status = text(step, "status");
            String output = text(step, "output");
            if (SUCCESS.equals(status) || output.isEmpty()) {
                continue;
            }
            List<String> tail = tail(output, TAIL_LINES);
            if (tail.isEmpty()) {
                continue;
            }
            any = true;
            JsonNode exit = step.path("exitCode");
            String label = text(step, "name").isEmpty() ? text(step, "image") : text(step, "name");
            out.println();
            out.println("--- step " + step.path("stepIndex").asText() + "  " + SafeText.line(label) + "  "
                    + SafeText.line(status) + (exit.isNumber() ? ", exit " + exit.asText() : "")
                    + ": its last " + tail.size() + (tail.size() == 1 ? " line" : " lines") + " ---");
            tail.forEach(line -> out.println(SafeText.line(line)));
        }
        if (any) {
            out.println("All of it: qits ci run " + SafeText.line(text(run, "id")) + " --logs");
        }
    }

    /**
     * Walks {@code output} backwards from the end, one line at a time, rather than splitting it
     * whole: a step's output can be long, and the tail is all this needs to read. A blank line
     * right at the end is skipped and not counted; one found once a real line has been seen is kept,
     * the same as {@link #lines}. Empty when nothing but blank lines, or nothing at all, is left.
     */
    private static List<String> tail(String output, int max) {
        List<String> collected = new ArrayList<>();
        boolean skippingTrailingBlanks = true;
        int right = output.length();
        while (right > 0) {
            int boundary = output.charAt(right - 1) == '\n' ? right - 1 : right;
            int previous = output.lastIndexOf('\n', boundary - 1);
            int start = previous + 1;
            int end = boundary > start && output.charAt(boundary - 1) == '\r' ? boundary - 1 : boundary;
            String line = output.substring(start, end);
            right = start;
            if (skippingTrailingBlanks) {
                if (line.isEmpty()) {
                    continue;
                }
                skippingTrailingBlanks = false;
            }
            collected.add(line);
            if (collected.size() == max) {
                break;
            }
        }
        Collections.reverse(collected);
        return collected;
    }

    /**
     * Each step's output under a line that names the step, then the running step's output so far.
     * Written line by line, each line cleaned on its own, so no second copy of a long log is built.
     */
    static void printLogs(PrintStream out, JsonNode run) {
        for (JsonNode step : steps(run)) {
            JsonNode exit = step.path("exitCode");
            out.println("--- step " + step.path("stepIndex").asText() + "  " + SafeText.line(text(step, "image")) + "  "
                    + SafeText.line(text(step, "status")) + (exit.isNumber() ? ", exit " + exit.asText() : "") + " ---");
            lines(out, text(step, "output"));
        }
        JsonNode live = run.path("live");
        if (live.isObject()) {
            out.println("--- step " + live.path("stepIndex").asText() + "  " + RUNNING + ", the output so far ---");
            lines(out, text(live, "output"));
        }
        out.flush();
    }

    /** The output, a line at a time, with terminal control characters taken out of each line. */
    static void lines(PrintStream out, String output) {
        if (output.isEmpty()) {
            out.println("(no output)");
            return;
        }
        int start = 0;
        while (start < output.length()) {
            int end = output.indexOf('\n', start);
            if (end < 0) {
                end = output.length();
            }
            int stop = end > start && output.charAt(end - 1) == '\r' ? end - 1 : end;
            out.println(SafeText.line(output.substring(start, stop)));
            start = end + 1;
        }
    }

    /** The service's answer, indented, with every control character written as an escape. */
    static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    /**
     * How long a run or a step took: from its start (a run's creation when it never started) to
     * its end. A run that is running shows the time so far; one that has not started shows a dash.
     */
    static String took(JsonNode node, Instant now) {
        Instant started = instant(text(node, "startedAt"));
        Instant finished = instant(text(node, "finishedAt"));
        if (finished != null) {
            Instant from = started != null ? started : instant(text(node, "createdAt"));
            return from == null ? "-" : duration(Duration.between(from, finished));
        }
        if (started != null && RUNNING.equals(text(node, "status"))) {
            return duration(Duration.between(started, now)) + " so far";
        }
        return "-";
    }

    /** The step in flight has no start while its container is being set up. */
    private static String liveTook(JsonNode live, Instant now) {
        Instant started = instant(text(live, "startedAt"));
        return started == null ? "starting" : duration(Duration.between(started, now)) + " so far";
    }

    static String duration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh%02dm", seconds / 3600, seconds % 3600 / 60);
    }

    static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static List<JsonNode> steps(JsonNode run) {
        List<JsonNode> steps = new ArrayList<>();
        run.path("steps").forEach(steps::add);
        steps.sort(Comparator.comparingInt(s -> s.path("stepIndex").asInt()));
        return steps;
    }

    private static List<String> row(String name, String value) {
        return List.of(name, cell(value, 200));
    }

    private static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }

    private static Instant instant(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notATime) {
            return null;
        }
    }
}
