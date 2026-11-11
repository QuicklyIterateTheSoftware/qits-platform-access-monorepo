package eu.wohlben.qits.cli.access.targets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits targets}: execution targets and their leases, qits-system-service's {@code
 * /system/api} (epic qits-1481, dossier "Targets in qits-system: data, doors, allocation"). One
 * command per door; this replaces the old per-slot-type runner groups ({@code qits workspaces
 * runners}, retired by qits-1534).
 * <p>
 * {@code create} and {@code rotate-token} answer a one-time registration token inside an install
 * line (qits-1574). They print that line alone on stdout, once, and never on stderr, in a log or in
 * an error, the way {@code users invite} prints its register token. Both are {@link
 * Interaction#LOCAL}, so the MCP service does not serve them and the line never lands in an agent's
 * transcript.
 */
@CommandLine.Command(name = "targets", mixinStandardHelpOptions = true,
        subcommands = {TargetsCommand.ListCommand.class, TargetsCommand.ShowCommand.class,
                TargetsCommand.CreateCommand.class, TargetsCommand.RotateTokenCommand.class,
                TargetsCommand.UpdateCommand.class, TargetsCommand.GreenlightCommand.class,
                TargetsCommand.HealthcheckCommand.class, TargetsCommand.LoginCheckCommand.class,
                TargetsCommand.DeleteCommand.class, TargetsCommand.LeasesCommand.class,
                TargetsCommand.SignInCommand.class, TargetDockerCommand.class},
        description = {"The execution targets qits-system-runner runs on, and their leases: list them with their "
                + "connection, slots, login and quarantine; read one in full; declare one, or rotate its registration "
                + "token, and print the line that installs its runner; change its slot reservations, slot maximums "
                + "or description; "
                + "lift a quarantine; ask for a health check or a login check; delete one; list the leases a "
                + "target or a slot type holds, show one or end one; sign a target in; and read or handle a "
                + "target's own docker containers and volumes without a shell on it.",
                "A target is named by its name or its id (`qits targets list` shows both)."},
        footerHeading = "%nNotes:%n",
        footer = {"- Reading, and asking for a health check, are open to qits:agent. Declaring, rotating a "
                + "registration token, updating, deleting and asking for a login check need qits:admin, "
                + "qits:admin-agent, qits:desk-admin or (for all but login-check) qits:system; qits:agent alone is "
                + "refused (HTTP 403) by design. Each command's help names its door.",
                "- Ending a lease takes qits:admin, qits:admin-agent or qits:desk-admin, which end any lease and are "
                        + "audited, or qits:system, which ends its own.",
                "- `qits targets docker` needs qits:admin, qits:admin-agent or qits:desk-admin; qits:agent and "
                        + "qits:system are refused (HTTP 403). Every call is audited; see its own help.",
                "- create and rotate-token print an install line that carries a one-time token: alone on stdout, "
                        + "shown once, and not served by the qits MCP server.",
                "- --system-url and --output may come before or after the command."})
public class TargetsCommand implements Runnable {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @CommandLine.Option(names = "--system-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived "
                    + "from the idp address, like the other services.")
    String systemUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): for a person. json: the service's answer, pretty-printed, with "
                    + "control characters written as escapes.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    TargetsApi api(CliContext context) throws CliFailure, InterruptedException {
        return new TargetsApi(new PlatformClient(context.credential()),
                PlatformUrls.system(systemUrl, context.env(), context.idpUrl()));
    }

    boolean json() throws CliFailure {
        return Json.wanted(output);
    }

    static boolean isUuid(String value) {
        return value != null && UUID.matcher(value.strip()).matches();
    }

    /** {@code --target}: a uuid as it is, else a name looked up in {@code listTargets}. */
    String targetId(CliContext context, String given) throws CliFailure, InterruptedException {
        String wanted = given == null ? "" : given.strip();
        if (wanted.isEmpty()) {
            throw new CliFailure("Name the target: its name or id (`qits targets list` shows it).", CliFailure.USAGE);
        }
        if (isUuid(wanted)) {
            return wanted;
        }
        for (JsonNode target : api(context).targets()) {
            if (wanted.equals(text(target, "name"))) {
                return text(target, "id");
            }
        }
        throw new CliFailure("No target named '" + wanted + "'.", CliFailure.USAGE);
    }

    /** {@code claude} or {@code kimi}, case-insensitive; default {@code claude}. */
    static String harness(String given) throws CliFailure {
        String value = (given == null || given.isBlank() ? "claude" : given.strip()).toLowerCase(Locale.ROOT);
        if (!value.equals("claude") && !value.equals("kimi")) {
            throw new CliFailure("--harness must be claude or kimi, not '" + given.strip() + "'.", CliFailure.USAGE);
        }
        return value;
    }

    /** {@code -o}: the shared reading of table or json, and the one JSON printer. */
    static final class Json {

        private static final ObjectMapper PLAIN = new ObjectMapper();

        private Json() {
        }

        static boolean wanted(String output) throws CliFailure {
            if (output == null || output.equals("table")) {
                return false;
            }
            if (output.equals("json")) {
                return true;
            }
            throw new CliFailure("--output must be table or json, not '" + output + "'.", CliFailure.USAGE);
        }

        static void print(PrintStream out, JsonNode node) throws CliFailure {
            try {
                out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            } catch (JsonProcessingException impossible) {
                throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
            }
        }

        static JsonNode parse(String text) {
            try {
                JsonNode node = PLAIN.readTree(text == null ? "" : text);
                return node == null || node.isMissingNode() ? null : node;
            } catch (IOException notJson) {
                return null;
            }
        }
    }

    /** A cell from a field: one line, at most {@code max} characters, a dash for nothing. */
    static String cell(JsonNode node, String field, int max) {
        return Table.cell(text(node, field), max);
    }

    static String yesNo(JsonNode node, String field) {
        return node.path(field).asBoolean(false) ? "yes" : "no";
    }

    /** What {@link #slots} shows: per type used over reserved (with a maximum where set), then used over the total. */
    static final String SLOTS_LEGEND = "used/reserved[≤max], total used/total";

    /**
     * {@code ci a/r, ws b/r, desk c/r, total u/slots}, as the list column, with {@code ≤<max>}
     * after a type's reserved count where the target carries one. A reservation holds slots for one
     * type; it is a floor, not a limit, so {@code ws 5/1} is valid while shared slots are free. A
     * maximum is a limit: a type never runs more leases than its maximum on that target. No suffix
     * when the maximum is null or the field is absent (an older server). The total is the target's
     * real slot count.
     */
    static String slots(JsonNode target) {
        JsonNode reserved = reservations(target);
        JsonNode maximums = maximums(target);
        JsonNode used = target.path("used");
        int ci = used.path("ci").asInt(0);
        int ws = used.path("workspace").asInt(0);
        int desk = used.path("desk").asInt(0);
        return "ci " + ci + "/" + reserved.path("ci").asInt(0) + maxSuffix(maximums, "ci")
                + ", ws " + ws + "/" + reserved.path("workspace").asInt(0) + maxSuffix(maximums, "workspace")
                + ", desk " + desk + "/" + reserved.path("desk").asInt(0) + maxSuffix(maximums, "desk")
                + ", total " + (ci + ws + desk) + "/" + target.path("slotsTotal").asInt(0);
    }

    /** {@code ≤<n>} for a type with a maximum set; empty when the field is absent or explicitly null. */
    private static String maxSuffix(JsonNode maximums, String field) {
        JsonNode value = maximums.path(field);
        return value.isNull() || value.isMissingNode() ? "" : "≤" + value.asInt();
    }

    /** A target's per-type reservations. */
    static JsonNode reservations(JsonNode target) {
        return target.path("reservations");
    }

    /** A target's per-type maximums; an older server's answer carries no field, read as absent. */
    static JsonNode maximums(JsonNode target) {
        return target.path("maximums");
    }

    /** The per-type reservations a create or update sends. */
    static void putReservations(ObjectNode body, Integer ci, Integer workspace, Integer desk) {
        ObjectNode reserved = JsonNodeFactory.instance.objectNode();
        if (ci != null) {
            reserved.put("ci", ci);
        }
        if (workspace != null) {
            reserved.put("workspace", workspace);
        }
        if (desk != null) {
            reserved.put("desk", desk);
        }
        if (!reserved.isEmpty()) {
            body.set("reservations", reserved);
        }
    }

    /**
     * The per-type maximums an update sends: {@code none} (case-insensitive) sends an explicit JSON
     * {@code null}, which clears the maximum; a whole number >= 0 sends it; anything else is a usage
     * error naming the option. An option left out (its value null) is omitted, keeping its value.
     */
    static void putMaximums(ObjectNode body, String maxCi, String maxWorkspace, String maxDesk) throws CliFailure {
        ObjectNode max = JsonNodeFactory.instance.objectNode();
        putMaximum(max, "ci", "--max-ci", maxCi);
        putMaximum(max, "workspace", "--max-workspace", maxWorkspace);
        putMaximum(max, "desk", "--max-desk", maxDesk);
        if (!max.isEmpty()) {
            body.set("maximums", max);
        }
    }

    private static void putMaximum(ObjectNode max, String field, String option, String given) throws CliFailure {
        if (given == null) {
            return;
        }
        String value = given.strip();
        if (value.equalsIgnoreCase("none")) {
            max.putNull(field);
            return;
        }
        try {
            int n = Integer.parseInt(value);
            if (n < 0) {
                throw new NumberFormatException(value);
            }
            max.put(field, n);
        } catch (NumberFormatException notANumber) {
            throw new CliFailure(option + " must be a whole number >= 0, or `none`, not '" + value + "'.",
                    CliFailure.USAGE);
        }
    }

    /** {@code claude:<state> kimi:<state>}, as the list column. */
    static String login(JsonNode target) {
        return "claude:" + loginValue(target, "claude") + " kimi:" + loginValue(target, "kimi");
    }

    /** One harness's login value off a target DTO: {@code PRESENT}, {@code ABSENT}, {@code UNKNOWN} or {@code -}. */
    static String loginValue(JsonNode target, String harness) {
        JsonNode value = target.path("login").path(harness);
        return value.isTextual() ? value.asText() : "-";
    }

    /** A target's fields, for a person; the strip `show` prints. */
    static void printTarget(PrintStream out, JsonNode target) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("id", cell(target, "id", 60)));
        rows.add(List.of("name", cell(target, "name", 60)));
        rows.add(List.of("description", cell(target, "description", 200)));
        rows.add(List.of("connected", yesNo(target, "connected")
                + (text(target, "connectedSince").isEmpty() ? "" : " since " + Table.time(text(target, "connectedSince")))));
        rows.add(List.of("runner version", cell(target, "runnerVersion", 60)));
        rows.add(List.of("slots (" + SLOTS_LEGEND + ")", slots(target)));
        rows.add(List.of("credential volume", cell(target, "credentialVolume", 120)));
        rows.add(List.of("login claude", loginValue(target, "claude")));
        rows.add(List.of("login kimi", loginValue(target, "kimi")));
        if (!text(target, "loginCheckedAt").isEmpty()) {
            rows.add(List.of("login checked", Table.time(text(target.path("login"), "checkedAt"))));
        }
        rows.add(List.of("login command", cell(target, "loginCommand", 200)));
        rows.add(List.of("kimi login command", cell(target, "kimiLoginCommand", 200)));
        rows.add(List.of("quarantined", yesNo(target, "quarantined")
                + (text(target, "quarantineReason").isEmpty() ? "" : ": " + cell(target, "quarantineReason", 160))));
        rows.add(List.of("registered", yesNo(target, "registered")
                + (text(target, "registeredAt").isEmpty() ? "" : " at " + Table.time(text(target, "registeredAt")))));
        rows.add(List.of("last seen", Table.time(text(target, "lastSeenAt"))));
        rows.add(List.of("last health check", text(target, "lastHealthCheckAt").isEmpty() ? "never"
                : Table.time(text(target, "lastHealthCheckAt")) + " ("
                + (target.path("lastHealthCheckOk").asBoolean(false) ? "ok" : "failed") + ")"));
        rows.add(List.of("oom kills", oomKills(target)));
        String memory = memoryRow(target);
        if (memory != null) {
            rows.add(List.of("memory", memory));
        }
        rows.add(List.of("created", Table.time(text(target, "createdAt"))));
        Table.print(out, "  ", null, rows);
    }

    /**
     * A target's node/kernel OOM kill counters (qits-1818), `memory.nodeOomKills`/`kernelOomKills`:
     * {@code node <n>, kernel <n>}, a part left out when its value is null; {@code -} when both are
     * (an older service, with no {@code memory} field at all, reads the same as both null).
     */
    static String oomKills(JsonNode target) {
        JsonNode memory = target.path("memory");
        List<String> parts = new ArrayList<>();
        String node = text(memory, "nodeOomKills");
        if (!node.isEmpty()) {
            parts.add("node " + node);
        }
        String kernel = text(memory, "kernelOomKills");
        if (!kernel.isEmpty()) {
            parts.add("kernel " + kernel);
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    /**
     * The {@code memory} row's value: {@code limit <bytes>, reported <time>}, {@code limit none}
     * when {@code memoryMaxBytes} is null; null (the row is left out) when {@code memory} is absent
     * or its {@code reportedAt} is null, an older service or one that has not reported yet.
     */
    static String memoryRow(JsonNode target) {
        JsonNode memory = target.path("memory");
        String reportedAt = text(memory, "reportedAt");
        if (reportedAt.isEmpty()) {
            return null;
        }
        String maxBytes = text(memory, "memoryMaxBytes");
        String limit = maxBytes.isEmpty() ? "none" : Table.bytes(Long.parseLong(maxBytes));
        return "limit " + limit + ", reported " + Table.time(reportedAt);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"Every target, by name: connected or not, version, slots, login and quarantine. Slots show "
                    + "ci, workspace and desk as used over reserved (with a maximum where set), then all used over "
                    + "the target's slot total. A reservation is a floor, not a limit: a type may use more while "
                    + "shared slots are free. A maximum is a limit: a type never runs more leases than its maximum.",
                    "GET /system/api/targets. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets list", "  qits targets list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            JsonNode answer = targets.api(context).targets();
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode target : answer) {
                rows.add(List.of(cell(target, "name", 30), yesNo(target, "connected"), cell(target, "runnerVersion", 20),
                        slots(target), login(target), yesNo(target, "quarantined")));
            }
            if (rows.isEmpty()) {
                context.out().println("No targets.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "CONNECTED", "VERSION", "SLOTS (" + SLOTS_LEGEND + ")", "LOGIN",
                    "QUARANTINED"),
                    rows);
            return 0;
        }
    }

    // --- show ---

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"One target in full: its slots, reservations and maximums, its login state for claude "
                    + "and kimi, its login command for each (the docker run that signs its credential volume in), "
                    + "its quarantine, its newest connection, and its node and kernel OOM kill counters with its "
                    + "last reported memory limit.",
                    "GET /system/api/targets/{id}. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets show --target localhost", "  qits targets show --target localhost -o json",
                    "", "- loginCommand and kimiLoginCommand are null until a login state has arrived on the "
                            + "target's current connection: `qits targets sign-in` names that."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            JsonNode answer = targets.api(context).target(id);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            printTarget(context.out(), answer);
            return 0;
        }
    }

    // --- create and rotate-token ---

    /**
     * The answer of createTarget or rotateTargetRegistrationToken: the install line alone on stdout,
     * and on stderr which target it is for, by name and id. The line carries the registration
     * token, so it is printed nowhere else, and no message here quotes the answer.
     */
    static void printInstallLine(CliContext context, boolean json, JsonNode answer, String what, String given)
            throws CliFailure {
        JsonNode target = answer == null ? JsonNodeFactory.instance.objectNode() : answer.path("target");
        String name = text(target, "name").isEmpty() ? given : text(target, "name");
        String id = text(target, "id");
        String named = SafeText.line(name) + (id.isEmpty() ? "" : " (" + SafeText.line(id) + ")");
        String installLine = answer == null ? "" : text(answer, "installLine");
        if (installLine.isBlank()) {
            throw new CliFailure("The service " + what + " target " + named + ", but its answer carries no install "
                    + "line.", CliFailure.FAILED);
        }
        if (json) {
            Json.print(context.out(), answer);
            return;
        }
        context.out().println(SafeText.line(installLine));
        context.err().println("Install line for target " + named + ": shown only this once. Run it on the "
                + "target's own machine; it installs the runner, which registers with it once. Lost, `qits targets "
                + "rotate-token --target " + SafeText.line(name) + "` makes a new one.");
    }

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Declare a target and print the line that installs its runner, with a one-time "
                    + "registration token in it.",
                    "POST /system/api/targets with {name, description?, reservations?: {ci, workspace, desk}, slots?} "
                            + "(HTTP 201 {target, registrationToken, installLine}). " + Doors.WRITE,
                    "THE INSTALL LINE IS SHOWN ONLY THIS ONCE: it is printed alone on stdout and nowhere else. Run "
                            + "it on the target's own machine. Lost, `qits targets rotate-token` makes a new one."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets create --name buildbox",
                    "  qits targets create --name buildbox --description 'The build machine under the desk' "
                            + "--reserved-ci 2 --reserved-workspace 2 --reserved-desk 1",
                    "",
                    "- A reservation left out is not sent. HTTP 400: --slots is below the reservations' sum, or outside "
                            + "0..1024. HTTP 409 TARGET_NAME_TAKEN: the name is in use. HTTP 503: commissioning is "
                            + "off, or the runner plane is not configured or pins no runner version.",
                    "- -o json prints the service's answer: the target, the registration token and the install line.",
                    "- Not served by the qits MCP server: the token would land in a transcript."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(interaction = Interaction.LOCAL)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--name", paramLabel = "<name>", required = true,
                description = "The new target's name.")
        String name;

        @CommandLine.Option(names = "--description", paramLabel = "<text>", description = "Free text.")
        String description;

        @CommandLine.Option(names = "--reserved-ci", paramLabel = "<n>",
                description = "Slots reserved for CI.")
        Integer reservedCi;

        @CommandLine.Option(names = "--reserved-workspace", paramLabel = "<n>",
                description = "Slots reserved for workspaces.")
        Integer reservedWorkspace;

        @CommandLine.Option(names = "--reserved-desk", paramLabel = "<n>",
                description = "Slots reserved for the desk.")
        Integer reservedDesk;

        @CommandLine.Option(names = "--slots", paramLabel = "<n>",
                description = "The slots total the install line asks the runner for; default: the reported total, "
                        + "or the reservations' sum.")
        Integer slots;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String wanted = name == null ? "" : name.strip();
            if (wanted.isEmpty()) {
                throw new CliFailure("Name the target: --name <name>.", CliFailure.USAGE);
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("name", wanted);
            if (description != null) {
                body.put("description", description);
            }
            putReservations(body, reservedCi, reservedWorkspace, reservedDesk);
            if (slots != null) {
                body.put("slots", slots);
            }
            JsonNode answer = targets.api(context).createTarget(body);
            printInstallLine(context, json, answer, "declared", wanted);
            return 0;
        }
    }

    @CommandLine.Command(name = "rotate-token", mixinStandardHelpOptions = true,
            description = {"Mint a new registration token and print the line that installs its runner with it. On "
                    + "a target whose runner has not registered yet, this is the first token's replacement; on a "
                    + "REGISTERED target, running the new line reinstalls its runner under the same target (its id "
                    + "is kept) and the old runner's client is decommissioned once the new one registers.",
                    "POST /system/api/targets/{id}/registration-token with an optional {slots?} (HTTP 200 {target, "
                            + "registrationToken, installLine}). " + Doors.WRITE,
                    "THE INSTALL LINE IS SHOWN ONLY THIS ONCE: it is printed alone on stdout and nowhere else. Run "
                            + "it on the target's own machine."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets rotate-token --target buildbox",
                    "  qits targets rotate-token --target buildbox -o json",
                    "  qits targets rotate-token --target buildbox --slots 8",
                    "",
                    "- With no --slots, the request carries no body and the service picks max(reported total, the "
                            + "reservations' sum). HTTP 400: --slots is below the reservations' sum, or outside 0..1024. "
                            + "HTTP 503: commissioning is off, or the runner plane is not configured or pins no "
                            + "runner version.",
                    "- -o json prints the service's answer: the target, the registration token and the install line.",
                    "- Not served by the qits MCP server: the token would land in a transcript."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(interaction = Interaction.LOCAL)
    public static class RotateTokenCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Option(names = "--slots", paramLabel = "<n>",
                description = "The slots total the install line asks the runner for; default: the reported total, "
                        + "or the reservations' sum.")
        Integer slots;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            JsonNode answer = targets.api(context).rotateTargetRegistrationToken(id, slots);
            printInstallLine(context, json, answer, "rotated the registration token of", target.strip());
            return 0;
        }
    }

    // --- update ---

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Change a target's description, slot reservations or slot maximums. Only what is given "
                    + "is sent; a reservation or maximum left out keeps its value. A reservation is a floor, not a "
                    + "limit: a type may use more while shared slots are free. A maximum is the limit: a type "
                    + "never runs more leases than its maximum on that target. `none` clears a maximum.",
                    "PATCH /system/api/targets/{id} with {description?, reservations?: {ci?, workspace?, desk?}, "
                            + "maximums?: {ci?, workspace?, desk?}}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets update --target localhost --reserved-ci 2 --reserved-workspace 2 --reserved-desk 1",
                    "  qits targets update --target localhost --description 'The build machine under the desk'",
                    "  qits targets update --target ws-proof --max-ci 2",
                    "",
                    "- A blank --description clears it. HTTP 409 RESERVATIONS_EXCEED_TOTAL: their sum is above the "
                            + "target's slots total. HTTP 409 MAXIMUM_BELOW_RESERVATION: a maximum would be below "
                            + "that type's reservation."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Option(names = "--reserved-ci", paramLabel = "<n>",
                description = "Slots reserved for CI.")
        Integer reservedCi;

        @CommandLine.Option(names = "--reserved-workspace", paramLabel = "<n>",
                description = "Slots reserved for workspaces.")
        Integer reservedWorkspace;

        @CommandLine.Option(names = "--reserved-desk", paramLabel = "<n>",
                description = "Slots reserved for the desk.")
        Integer reservedDesk;

        @CommandLine.Option(names = "--max-ci", paramLabel = "<n>|none",
                description = "Maximum slots this target ever runs for CI leases; `none` clears it, leaving no "
                        + "ceiling.")
        String maxCi;

        @CommandLine.Option(names = "--max-workspace", paramLabel = "<n>|none",
                description = "Maximum slots this target ever runs for workspace leases; `none` clears it, leaving "
                        + "no ceiling.")
        String maxWorkspace;

        @CommandLine.Option(names = "--max-desk", paramLabel = "<n>|none",
                description = "Maximum slots this target ever runs for desk leases; `none` clears it, leaving no "
                        + "ceiling.")
        String maxDesk;

        @CommandLine.Option(names = "--description", paramLabel = "<text>", description = "Free text.")
        String description;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            ObjectNode change = JsonNodeFactory.instance.objectNode();
            if (description != null) {
                change.put("description", description);
            }
            putReservations(change, reservedCi, reservedWorkspace, reservedDesk);
            putMaximums(change, maxCi, maxWorkspace, maxDesk);
            if (change.isEmpty()) {
                throw new CliFailure("Nothing to change: give --reserved-ci, --reserved-workspace, --reserved-desk, "
                        + "--max-ci, --max-workspace, --max-desk or --description.", CliFailure.USAGE);
            }
            JsonNode answer = targets.api(context).patchTarget(id, change);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Target " + SafeText.line(text(answer, "name")) + ": " + slots(answer)
                    + " (" + SLOTS_LEGEND + "), "
                    + cell(answer, "description", 100) + ".");
            return 0;
        }
    }

    /** greenlight, healthcheck and login-check: one POST each, no body, as a person's buttons send it. */
    abstract static class TargetVerb extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        abstract JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException;

        abstract String done(String name, JsonNode answer);

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            JsonNode answer = call(targets.api(context), id);
            if (json) {
                if (answer != null && !answer.isNull()) {
                    Json.print(context.out(), answer);
                }
                return 0;
            }
            context.out().println(done(SafeText.line(targetName(target)), answer));
            return 0;
        }

        /** The name as given, for the message; `--target` by id still reads back as the id it was given. */
        private static String targetName(String given) {
            return given == null ? "" : given.strip();
        }
    }

    @CommandLine.Command(name = "greenlight", mixinStandardHelpOptions = true,
            description = {"Lift a target's quarantine. The service quarantines a target itself, after a failed "
                    + "health check, or when it registers.",
                    "POST /system/api/targets/{id}/greenlight, no body. " + Doors.ADMIN_ONLY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets greenlight --target localhost"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class GreenlightCommand extends TargetVerb {
        @Override
        JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException {
            return api.greenlightTarget(id);
        }

        @Override
        String done(String name, JsonNode answer) {
            return "Target " + name + (answer != null && answer.path("quarantined").asBoolean(false)
                    ? " is still quarantined." : " is out of quarantine.");
        }
    }

    @CommandLine.Command(name = "healthcheck", mixinStandardHelpOptions = true,
            description = {"Ask a connected target for a health check now. The result arrives with a later "
                    + "`qits targets show`.",
                    "POST /system/api/targets/{id}/healthcheck, no body (HTTP 202 {requestId}). " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets healthcheck --target localhost",
                    "", "- A disconnected target is refused: HTTP 409 TARGET_UNAVAILABLE."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class HealthcheckCommand extends TargetVerb {
        @Override
        JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException {
            return api.requestTargetHealthCheck(id);
        }

        @Override
        String done(String name, JsonNode answer) {
            return "Queued a health check on target " + name + ".";
        }
    }

    @CommandLine.Command(name = "login-check", mixinStandardHelpOptions = true,
            description = {"Ask a connected target to probe its credential volume's sign-in again. The result "
                    + "shows in a later `qits targets show` or `qits targets list`.",
                    "POST /system/api/targets/{id}/login-check, no body (HTTP 202). " + Doors.ADMIN_ONLY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets login-check --target localhost",
                    "", "- A disconnected target is refused: HTTP 409 TARGET_UNAVAILABLE."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class LoginCheckCommand extends TargetVerb {
        @Override
        JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException {
            return api.requestTargetLoginCheck(id);
        }

        @Override
        String done(String name, JsonNode answer) {
            return "Asked target " + name + " to check its credential volume's sign-in.";
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Decommission a target: retire its runner and give its credentials back.",
                    "DELETE /system/api/targets/{id}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets delete --target localhost",
                    "", "- A target that still holds a lease that has not ended is refused: HTTP 409 TARGET_BUSY."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            targets.api(context).deleteTarget(id);
            if (!json) {
                context.out().println("Deleted target " + SafeText.line(target.strip()) + ".");
            }
            return 0;
        }
    }

    // --- leases ---

    @CommandLine.Command(name = "leases", mixinStandardHelpOptions = true,
            subcommands = {TargetsCommand.LeasesCommand.ShowLeaseCommand.class,
                    TargetsCommand.LeasesCommand.EndLeaseCommand.class},
            description = {"Leases, oldest first: filter by target, slot type (ci, workspace or desk) or state. "
                    + "show reads one lease; end ends one.",
                    "GET /system/api/leases?targetId=&slotType=&state=. " + Doors.LEASES},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets leases", "  qits targets leases --target localhost",
                    "  qits targets leases --type workspace --state RUNNING",
                    "  qits targets leases show 3f2a9c1e-0000-4000-8000-000000000001",
                    "  qits targets leases end 3f2a9c1e-0000-4000-8000-000000000001 --reason 'stuck in ENDING'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class LeasesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", description = "Only this target's leases.")
        String target;

        @CommandLine.Option(names = "--type", paramLabel = "ci|workspace|desk", description = "Only this slot type.")
        String type;

        @CommandLine.Option(names = "--state", paramLabel = "<state>",
                description = "Only this state: QUEUED, GRANTED, RUNNING, EXITED, FAILED, ENDING or ENDED.")
        String state;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String targetId = target == null || target.isBlank() ? null : targets.targetId(context, target);
            JsonNode answer = targets.api(context).leases(targetId, type, state);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode lease : answer) {
                rows.add(List.of(cell(lease, "id", 36), cell(lease, "slotType", 10),
                        cell(lease, "ownerApp", 16) + "/" + cell(lease, "ownerRef", 30), cell(lease, "state", 10),
                        cell(lease, "detail", 60), resolversCell(lease), Table.time(text(lease, "requestedAt"))));
            }
            if (rows.isEmpty()) {
                context.out().println("No leases.");
                return 0;
            }
            Table.print(context.out(), "",
                    List.of("ID", "TYPE", "OWNER", "STATE", "DETAIL", "RESOLVERS", "REQUESTED"), rows);
            return 0;
        }

        /** A lease's fields, for a person; what {@code show} and {@code end} print. */
        static void printLease(PrintStream out, JsonNode lease) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("id", cell(lease, "id", 60)));
            rows.add(List.of("slot type", cell(lease, "slotType", 20)));
            rows.add(List.of("owner", cell(lease, "ownerApp", 60) + "/" + cell(lease, "ownerRef", 120)));
            rows.add(List.of("state", cell(lease, "state", 20)));
            rows.add(List.of("detail", cell(lease, "detail", 400)));
            String resolvers = resolversLine(lease);
            if (resolvers != null) {
                rows.add(List.of("resolvers", resolvers));
            }
            if (lease.path("resolversStale").asBoolean(false)) {
                rows.add(List.of("stale", "yes — the node has a resolver this container lacks"));
            }
            rows.add(List.of("target", cell(lease, "targetId", 60)));
            rows.add(List.of("project", cell(lease, "projectId", 60)));
            rows.add(List.of("work", cell(lease, "workKey", 120)));
            rows.add(List.of("priority", cell(lease, "priority", 10)));
            rows.add(List.of("exit code", cell(lease, "exitCode", 10)));
            rows.add(List.of("oom kills", oomKills(lease)));
            rows.add(List.of("memory peak", memoryPeak(lease)));
            rows.add(List.of("requested", Table.time(text(lease, "requestedAt"))));
            rows.add(List.of("granted", Table.time(text(lease, "grantedAt"))));
            rows.add(List.of("started", Table.time(text(lease, "startedAt"))));
            rows.add(List.of("ended", Table.time(text(lease, "endedAt"))));
            Table.print(out, "  ", null, rows);
        }

        /**
         * A lease's OOM kills (qits-1818): {@code <n> (<scope>)}, with {@code , container OOM-killed}
         * appended when the container's own {@code oomKilled} is true; {@code -} when {@code oomKills}
         * is null (an older service, or a lease no OOM killer ever touched).
         */
        static String oomKills(JsonNode lease) {
            String kills = text(lease, "oomKills");
            if (kills.isEmpty()) {
                return "-";
            }
            String scope = text(lease, "oomScope");
            boolean killed = lease.path("oomKilled").asBoolean(false);
            StringBuilder detail = new StringBuilder(scope);
            if (killed) {
                if (!detail.isEmpty()) {
                    detail.append(", ");
                }
                detail.append("container OOM-killed");
            }
            return detail.isEmpty() ? kills : kills + " (" + detail + ")";
        }

        /** A lease's peak memory (qits-1818), {@code memoryPeakBytes}; {@code -} when it is null. */
        static String memoryPeak(JsonNode lease) {
            String bytes = text(lease, "memoryPeakBytes");
            return bytes.isEmpty() ? "-" : Table.bytes(Long.parseLong(bytes));
        }

        /**
         * A lease's resolvers (qits-2091), for {@code show}: the node tunnel or private edge
         * resolvers the lease's container may use, comma-separated; {@code node default} when the
         * list is empty, meaning the node's own default resolvers. {@code null} when {@code
         * resolvers} is absent (an older service), so the caller leaves the row out rather than
         * printing it empty.
         */
        static String resolversLine(JsonNode lease) {
            JsonNode resolvers = lease.path("resolvers");
            if (!resolvers.isArray()) {
                return null;
            }
            if (resolvers.isEmpty()) {
                return "node default";
            }
            List<String> values = new ArrayList<>();
            resolvers.forEach(value -> values.add(SafeText.line(value.asText())));
            return String.join(", ", values);
        }

        /**
         * A lease's resolvers, for the {@code leases} list: its first resolver, or {@code default}
         * when the list is empty; {@code -} when {@code resolvers} is absent (an older service).
         * Suffixed {@code " !"} when {@code resolversStale} is true: the node now has a resolver
         * (a node tunnel or private edge) this lease's container lacks.
         */
        static String resolversCell(JsonNode lease) {
            JsonNode resolvers = lease.path("resolvers");
            if (!resolvers.isArray()) {
                return "-";
            }
            String first = resolvers.isEmpty() ? "default" : SafeText.line(resolvers.get(0).asText());
            return lease.path("resolversStale").asBoolean(false) ? first + " !" : first;
        }

        static String leaseId(String given) throws CliFailure {
            String wanted = given == null ? "" : given.strip();
            if (!isUuid(wanted)) {
                throw new CliFailure("Name the lease by its id, a UUID (`qits targets leases` shows it).",
                        CliFailure.USAGE);
            }
            return wanted;
        }

        @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
                description = {"Show one lease: its slot type, owner, state and detail, its resolvers and whether "
                        + "they are stale, its target, its OOM kills and peak memory, and when it was requested, "
                        + "granted, started and ended. A lease the ENDING deadline or an operator ended says so in "
                        + "its detail.",
                        "GET /system/api/leases/{id}. " + Doors.LEASE},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits targets leases show 3f2a9c1e-0000-4000-8000-000000000001",
                        "  qits targets leases show 3f2a9c1e-0000-4000-8000-000000000001 -o json"},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
        public static class ShowLeaseCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            LeasesCommand leases;

            @CommandLine.Parameters(index = "0", paramLabel = "<lease id>", description = "The lease's id.")
            String lease;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = leases.targets.json();
                JsonNode answer = leases.targets.api(context).getLease(leaseId(lease));
                if (json) {
                    Json.print(context.out(), answer);
                    return 0;
                }
                printLease(context.out(), answer);
                return 0;
            }
        }

        @CommandLine.Command(name = "end", mixinStandardHelpOptions = true,
                description = {"End a lease, then show it. A queued lease, or one with no target, is ENDED at once. "
                        + "A granted or running one goes ENDING: its runner removes the container and reports it "
                        + "gone, and then it is ENDED. A lease already ENDING that an operator ends goes ENDED at "
                        + "once: that is the door for a lease stuck in ENDING because its runner never reported.",
                        "The owning service (qits-ci, qits-workspaces) hears of it from the lease's change event and "
                                + "lets go of its run or slot.",
                        "DELETE /system/api/leases/{id}?reason=. " + Doors.END_LEASE},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits targets leases end 3f2a9c1e-0000-4000-8000-000000000001",
                        "  qits targets leases end 3f2a9c1e-0000-4000-8000-000000000001 --reason 'runner gone'",
                        "",
                        "- --reason is recorded in the audit row, and in the detail of an ENDING lease it ends.",
                        "- Ending a lease stops what runs in it: a CI run's step, or an agent's workspace container."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:The lease is ENDING or ENDED; it is printed.", HelpText.REFUSED, HelpText.USAGE})
        public static class EndLeaseCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            LeasesCommand leases;

            @CommandLine.Parameters(index = "0", paramLabel = "<lease id>", description = "The lease's id.")
            String lease;

            @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                    description = "Why it is ended: recorded in the audit row, and in the detail of an ENDING "
                            + "lease.")
            String reason;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = leases.targets.json();
                String id = leaseId(lease);
                JsonNode answer = leases.targets.api(context).endLease(id,
                        reason == null || reason.isBlank() ? null : reason.strip());
                if (json) {
                    Json.print(context.out(), answer);
                    return 0;
                }
                context.out().println("Lease " + id + " is " + SafeText.line(text(answer, "state")) + ".");
                printLease(context.out(), answer);
                return 0;
            }
        }
    }

    // --- sign-in ---

    @CommandLine.Command(name = "sign-in", mixinStandardHelpOptions = true,
            description = {"Sign a target's credential volume in for a harness: starts a sign-in on the target (or "
                    + "resumes one with --sign-in), prints its URL and code as the runner reports them, pastes a "
                    + "claude code typed on stdin back to it, and waits for the harness to report signed in.",
                    "GET /system/api/targets/{id}; POST .../sign-ins {harness} (or POST .../sign-ins/{id}/code "
                            + "{code} to resume with --sign-in and --code); then GET .../sign-ins/{id} every 2s "
                            + "until the sign-in ends, then GET /system/api/targets/{id} every 2s until login."
                            + "<harness> is PRESENT, or --timeout passes. " + Doors.ADMIN_ONLY,
                    "Already PRESENT: prints that and exits at once, nothing is started."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets sign-in --target localhost",
                    "  qits targets sign-in --target localhost --harness kimi --timeout 2h",
                    "  qits targets sign-in --target localhost --sign-in <id> --code 123456",
                    "",
                    "- A claude sign-in pauses at AWAITING_CODE and asks for the code on stdin. With none given "
                            + "(stdin closed or empty), it prints the command that resumes the same sign-in with "
                            + "--sign-in and --code, and exits 1: the sign-in itself keeps running on the target "
                            + "until it expires.",
                    "- --sign-in resumes a sign-in already running, whose URL or code this command already showed; "
                            + "--code needs --sign-in and is a usage error alone.",
                    "- FAILED, EXPIRED or CANCELLED fails naming the state and its detail. --timeout reached "
                            + "deletes the sign-in (best effort) and fails naming the last state.",
                    "- --output has no effect here: this command prints notices, never the service's answer."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The harness reports PRESENT (already, or once the sign-in succeeds).",
                    "1:The platform refused or cannot be reached, the sign-in failed, expired or was cancelled, no "
                            + "code was given at AWAITING_CODE, or --timeout passed (the message names the last "
                            + "state, or the command that resumes).",
                    HelpText.USAGE})
    public static class SignInCommand extends PlatformCommand {

        static final Duration POLL = Duration.ofSeconds(2);
        private static final List<String> TERMINAL = List.of("FAILED", "EXPIRED", "CANCELLED");

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Option(names = "--harness", paramLabel = "claude|kimi", defaultValue = "claude",
                description = "The harness to sign in. Default: claude.")
        String harness;

        @CommandLine.Option(names = "--timeout", paramLabel = "<duration>", defaultValue = "10m",
                description = "How long to wait: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 10m.")
        String timeout;

        @CommandLine.Option(names = "--sign-in", paramLabel = "<id>",
                description = "Resume this sign-in instead of starting a new one.")
        String signIn;

        @CommandLine.Option(names = "--code", paramLabel = "<code>",
                description = "The code the browser showed, posted to --sign-in instead of reading one from stdin.")
        String code;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            if (code != null && signIn == null) {
                throw new CliFailure("--code needs --sign-in: it posts the code to a sign-in already running.",
                        CliFailure.USAGE);
            }
            String chosen = harness(harness);
            Duration budget = SignInTimeout.parse(timeout);
            String id = targets.targetId(context, target);
            TargetsApi api = targets.api(context);
            JsonNode currentTarget = api.target(id);
            String name = text(currentTarget, "name");
            if ("PRESENT".equals(loginValue(currentTarget, chosen))) {
                context.out().println(SafeText.line(name) + " is already signed in.");
                return 0;
            }
            Instant deadline = context.clock().instant().plus(budget);

            JsonNode signInView = signIn == null ? api.createSignIn(id, chosen)
                    : code != null ? api.postSignInCode(id, signIn, code.strip()) : api.getSignIn(id, signIn);

            BufferedReader stdin = new BufferedReader(new InputStreamReader(context.in(), StandardCharsets.UTF_8));
            Set<String> printed = new HashSet<>();
            // A posted code leaves the view at AWAITING_CODE until the runner has passed it on: asking
            // again then would paste a second code into a login that is already checking the first.
            boolean codePosted = code != null;
            while (true) {
                String signInId = text(signInView, "signInId");
                String state = text(signInView, "state");
                printNotices(context, signInView, printed);

                if ("SUCCEEDED".equals(state)) {
                    return waitForLogin(context, api, id, chosen, name, deadline);
                }
                if (TERMINAL.contains(state)) {
                    String detail = text(signInView, "detail");
                    throw new CliFailure("Sign-in for " + SafeText.line(name) + " " + state
                            + (detail.isEmpty() ? "" : ": " + SafeText.line(detail)) + ".", CliFailure.FAILED);
                }
                if ("AWAITING_CODE".equals(state) && "claude".equals(chosen) && !codePosted) {
                    String pasted = readCode(context, stdin);
                    if (pasted == null) {
                        throw new CliFailure("No code given. Resume with `qits targets sign-in --target "
                                + SafeText.line(target.strip()) + " --sign-in " + SafeText.line(signInId)
                                + " --code <code>`.", CliFailure.FAILED);
                    }
                    signInView = api.postSignInCode(id, signInId, pasted);
                    codePosted = true;
                    continue;
                }
                if (!context.clock().instant().isBefore(deadline)) {
                    try {
                        api.deleteSignIn(id, signInId);
                    } catch (CliFailure ignored) {
                        // Best effort: the sign-in expires on its own if this does not reach the service.
                    }
                    throw new CliFailure("Sign-in for " + SafeText.line(name) + " is still " + state + " after "
                            + timeout.strip() + ".", CliFailure.FAILED);
                }
                context.sleeper().sleep(POLL);
                signInView = api.getSignIn(id, signInId);
            }
        }

        /** After SUCCEEDED, the runner's own login probe: poll the target until the harness is PRESENT. */
        private int waitForLogin(CliContext context, TargetsApi api, String id, String harness, String name,
                Instant deadline) throws CliFailure, InterruptedException {
            while (true) {
                JsonNode current = api.target(id);
                String state = loginValue(current, harness);
                if ("PRESENT".equals(state)) {
                    context.out().println(SafeText.line(name) + " is signed in.");
                    return 0;
                }
                if (!context.clock().instant().isBefore(deadline)) {
                    throw new CliFailure("Target " + SafeText.line(name) + " is still " + state + " after "
                            + timeout.strip() + ".", CliFailure.FAILED);
                }
                context.sleeper().sleep(POLL);
            }
        }

        /** The URL and, for kimi, the user code: each printed once, the first time it shows up. */
        private static void printNotices(CliContext context, JsonNode signInView, Set<String> printed) {
            String url = text(signInView, "url");
            if (!url.isEmpty() && printed.add("url")) {
                context.out().println("Open this URL in a browser and sign in:");
                context.out().println("  " + SafeText.line(url));
            }
            String userCode = text(signInView, "userCode");
            if (!userCode.isEmpty() && printed.add("userCode")) {
                context.out().println("Enter the code " + SafeText.line(userCode) + " there.");
            }
        }

        /** One trimmed line pasted on stdin; null for an empty line or a closed stdin. */
        private static String readCode(CliContext context, BufferedReader stdin) throws CliFailure {
            context.out().println("Paste the code the browser shows:");
            context.out().flush();
            String line;
            try {
                line = stdin.readLine();
            } catch (IOException e) {
                throw new CliFailure("Cannot read the code from stdin: " + e.getMessage(), CliFailure.USAGE);
            }
            if (line == null || line.strip().isEmpty()) {
                return null;
            }
            return line.strip();
        }
    }
}
