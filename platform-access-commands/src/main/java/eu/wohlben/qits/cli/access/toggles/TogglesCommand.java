package eu.wohlben.qits.cli.access.toggles;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.access.projects.WorkPayload;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits toggles} (qits-2704, epic qits-2542): qits-toggles' declared feature toggles and their
 * ordered rules, on the three doors the landing app's Toggles section calls, so qits-ci's pact parity
 * between the two holds. There is no create and no delete, as there is none on the service: a toggle
 * exists only by its declaration in qits-toggles-service's code, and goes when that is removed.
 */
@CommandLine.Command(name = "toggles", mixinStandardHelpOptions = true,
        subcommands = {TogglesCommand.ListCommand.class, TogglesCommand.ShowCommand.class,
                TogglesCommand.SetRulesCommand.class},
        description = {"The platform's feature toggles in qits-toggles: list them, show one with its ordered rules, "
                + "and replace a toggle's rules. A toggle is declared in qits-toggles-service's code and nowhere "
                + "else, so there is no create and no delete here."},
        footerHeading = "%nNotes:%n",
        footer = {"- Rules are tried in order and the first that matches decides: USER (the caller, or the user a "
                + "service acts for, is one of userSubjects), TIME (now is after afterInstant), and STATIC, which "
                + "always matches and is always the last.",
                "- Reading takes any signed-in caller. Changing the rules takes qits:admin or qits:desk-admin.",
                "- --output and --toggles-url may come before or after the command."})
public class TogglesCommand implements Runnable {

    @CommandLine.Option(names = "--toggles-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The toggles service's base URL, without /toggles. Default: QITS_TOGGLES_URL, else the "
                    + "session's idp address with `idp` swapped for `toggles`.")
    String togglesUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): for people. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    TogglesApi api(CliContext context) throws CliFailure, InterruptedException {
        return new TogglesApi(new PlatformClient(context.credential()),
                PlatformUrls.toggles(togglesUrl, context.env(), context.idpUrl()));
    }

    boolean json() throws CliFailure {
        if (output == null || output.isBlank() || output.equalsIgnoreCase("table")) {
            return false;
        }
        if (output.equalsIgnoreCase("json")) {
            return true;
        }
        throw new CliFailure("--output is table or json, not '" + output + "'.", CliFailure.USAGE);
    }

    static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    /** One rule in a few words: {@code user in [a, b] -> true}, {@code after <time> -> true}, {@code otherwise false}. */
    static String rule(JsonNode rule) {
        String value = rule.path("value").asBoolean(false) ? "true" : "false";
        return switch (text(rule, "kind")) {
            case "USER" -> {
                List<String> subjects = new ArrayList<>();
                rule.path("userSubjects").forEach(s -> subjects.add(s.asText()));
                yield "user in [" + String.join(", ", subjects) + "] -> " + value;
            }
            case "TIME" -> "after " + Table.time(text(rule, "afterInstant")) + " -> " + value;
            case "STATIC" -> "otherwise " + value;
            default -> text(rule, "kind") + " -> " + value;
        };
    }

    /** The toggles: slug, default, version, how many rules, when a pact last used it, and its removal ticket. */
    static void printToggles(PrintStream out, JsonNode toggles) {
        List<List<String>> rows = new ArrayList<>();
        toggles.forEach(t -> rows.add(List.of(
                Table.cell(text(t, "slug"), 48),
                t.path("declaredDefault").asBoolean(false) ? "true" : "false",
                text(t, "version"),
                String.valueOf(t.path("rules").size()),
                dash(Table.time(text(t, "lastUsedAt"))),
                dash(Table.cell(text(t, "removalTicket"), 24)))));
        Table.print(out, "", List.of("SLUG", "DEFAULT", "VERSION", "RULES", "LAST USED", "REMOVAL TICKET"), rows);
    }

    /** One toggle: its fields, one per line, then its rules in the order they are tried. */
    static void printToggle(PrintStream out, JsonNode toggle) {
        field(out, "slug", text(toggle, "slug"));
        field(out, "type", text(toggle, "type"));
        field(out, "description", text(toggle, "description"));
        field(out, "default", toggle.path("declaredDefault").asBoolean(false) ? "true" : "false");
        field(out, "declared", Table.time(text(toggle, "firstDeclaredAt")));
        field(out, "version", text(toggle, "version"));
        field(out, "lastUsedAt", dash(Table.time(text(toggle, "lastUsedAt"))));
        field(out, "removalTicket", dash(text(toggle, "removalTicket")));
        JsonNode rules = toggle.path("rules");
        if (rules.isEmpty()) {
            return;
        }
        out.println();
        List<List<String>> rows = new ArrayList<>();
        int position = 1;
        for (JsonNode rule : rules) {
            rows.add(List.of(String.valueOf(position++), text(rule, "kind"),
                    rule.path("value").asBoolean(false) ? "true" : "false", Table.cell(matches(rule), 120)));
        }
        Table.print(out, "", List.of("#", "KIND", "VALUE", "MATCHES"), rows);
    }

    private static String matches(JsonNode rule) {
        return switch (text(rule, "kind")) {
            case "USER" -> {
                List<String> subjects = new ArrayList<>();
                rule.path("userSubjects").forEach(s -> subjects.add(s.asText()));
                yield String.join(", ", subjects);
            }
            case "TIME" -> "after " + Table.time(text(rule, "afterInstant"));
            case "STATIC" -> "always";
            default -> "";
        };
    }

    private static String dash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static void field(PrintStream out, String name, String value) {
        if (!value.isEmpty()) {
            out.printf("%-14s %s%n", name, SafeText.line(value));
        }
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List every declared toggle, sorted as the service answers: its slug, its declared "
                    + "default, its rules' version and how many rules it has, when a deployed consumer's pact "
                    + "last used it, and the removal ticket filed once none has for three days."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits toggles list",
                    "  qits toggles list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TogglesCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode toggles = parent.api(context).list();
            if (json) {
                ProjectsApi.printJson(context.out(), toggles);
                return 0;
            }
            if (!toggles.isArray() || toggles.isEmpty()) {
                context.out().println("No toggle is declared. Toggles are declared in qits-toggles-service's code.");
                return 0;
            }
            printToggles(context.out(), toggles);
            return 0;
        }
    }

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"Show one toggle: its description, declared default, version and usage, and its rules in "
                    + "the order they are tried."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits toggles show toggles-smoke",
                    "  qits toggles show toggles-smoke -o json",
                    "",
                    "- A slug no declaration names is HTTP 404."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TogglesCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<slug>", description = "The toggle's slug, as the list names it.")
        String slug;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wanted = required(slug, "Name the toggle: qits toggles show <slug>.");
            JsonNode toggle = parent.api(context).get(wanted);
            if (json) {
                ProjectsApi.printJson(context.out(), toggle);
            } else {
                printToggle(context.out(), toggle);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "set-rules", mixinStandardHelpOptions = true,
            description = {"Replace one toggle's ordered rules.",
                    "Reads {\"version\":<n>,\"rules\":[...]} on stdin and sends it unchanged to PUT "
                            + "/toggles/api/toggles/{slug}/rules. version is the toggle's version as it was read; "
                            + "a rule is {\"kind\":\"USER\"|\"TIME\"|\"STATIC\",\"value\":true|false,"
                            + "\"userSubjects\":[...],\"afterInstant\":\"<instant>\"}, and exactly one STATIC rule "
                            + "comes last. Prints the toggle as it is now, with its new version.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the body it wants, and "
                            + "exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits toggles set-rules toggles-smoke </dev/null",
                    "  qits toggles show toggles-smoke -o json | jq '{version, rules}' > rules.json",
                    "  qits toggles set-rules toggles-smoke < rules.json",
                    "  echo '{\"version\":3,\"rules\":[{\"kind\":\"STATIC\",\"value\":true}]}' "
                            + "| qits toggles set-rules toggles-smoke",
                    "",
                    "- The second and third lines edit the rules as they are: show prints version and rules in "
                            + "the shape set-rules takes.",
                    "- A USER rule's userSubjects are bearer subjects: a person's is their subject id, which "
                            + "`qits users subjects` lists; an agent's is its tok-... id.",
                    "- Refusals: 400 rules the service does not take (it names the rule's position), 403 a caller "
                            + "without qits:admin or qits:desk-admin, 404 an unknown slug, 409 the rules changed "
                            + "since that version was read: show the toggle again and resend with its version."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The rules are replaced, or nothing was put in and the body is described.",
                    "1:The platform refused (HTTP 400, 403, 404 or 409, with the service's own sentence), or cannot "
                            + "be reached.",
                    "2:Used wrongly (for example a body that is not a JSON object), not signed in, or the session "
                            + "ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class SetRulesCommand extends PlatformCommand {

        static final String BODY = """
                Nothing was sent. set-rules reads the toggle's new rules on stdin, as one JSON object:

                  {"version": <the version you read>,
                   "rules": [{"kind": "USER", "value": true, "userSubjects": ["<sub>"]},
                             {"kind": "TIME", "value": true, "afterInstant": "2026-12-01T00:00:00Z"},
                             {"kind": "STATIC", "value": false}]}

                Rules are tried in order; exactly one STATIC rule, and it is the last.
                A person's <sub> is their subject id: `qits users subjects` lists them.
                `qits toggles show <slug> -o json | jq '{version, rules}'` prints the current ones in this shape.""";

        @CommandLine.ParentCommand
        TogglesCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<slug>", description = "The toggle's slug, as the list names it.")
        String slug;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wanted = required(slug, "Name the toggle: qits toggles set-rules <slug>.");
            JsonNode body = WorkPayload.read(context);
            if (body == null) {
                context.out().println(BODY);
                return 0;
            }
            JsonNode toggle;
            try {
                toggle = parent.api(context).putRules(wanted, body);
            } catch (CliFailure refused) {
                throw explained(wanted, refused);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), toggle);
            } else {
                context.out().println("The rules of " + SafeText.line(wanted) + " are replaced: version "
                        + text(toggle, "version") + ".");
                context.out().println();
                printToggle(context.out(), toggle);
            }
            return 0;
        }

        /** The 409 and 404 with the sentence that says what to do; the service's own words below it. */
        static CliFailure explained(String slug, CliFailure refused) {
            String said = switch (refused.status()) {
                case 404 -> "No toggle is declared as '" + slug + "': `qits toggles list` lists them.";
                case 409 -> "The rules of " + slug + " changed since that version was read: "
                        + "`qits toggles show " + slug + "` has the new version.";
                default -> null;
            };
            return said == null ? refused
                    : CliFailure.refused(said + System.lineSeparator() + refused.getMessage(), refused.status());
        }
    }
}
