package eu.wohlben.qits.cli.access.egress;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.deployments.DeploymentsApi;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits egress grants}: the outbound routes a repository has declared it needs, in its own
 * {@code .config/qits/egress.yml}, read off the version qits-deployments has deployed (qits-2611,
 * qits-2616). This command only reads what a repository declared; it changes nothing.
 */
@CommandLine.Command(name = "grants", mixinStandardHelpOptions = true,
        subcommands = {GrantsCommand.ListCommand.class},
        description = {"The outbound routes a repository has declared it needs, in its own "
                + ".config/qits/egress.yml, read off the version qits-deployments has deployed. "
                + "This only reads what was declared; it never grants or changes one."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --deployments-url may come before or after the command."})
public class GrantsCommand implements Runnable {

    @CommandLine.Option(names = "--deployments-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, "
                    + "else the session's idp address with `idp` swapped for `deployments`.")
    String deploymentsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    DeploymentsApi deployments(CliContext context) throws CliFailure, InterruptedException {
        return new DeploymentsApi(new PlatformClient(context.credential()),
                PlatformUrls.deployments(deploymentsUrl, context.env(), context.idpUrl()));
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

    /** The routes table: PRINCIPAL, ROUTE, TARGET, SECRET, INJECT, METHODS, VERSION. */
    static void printRoutes(PrintStream out, List<JsonNode> routes) {
        Table.print(out, "", List.of("PRINCIPAL", "ROUTE", "TARGET", "SECRET", "INJECT", "METHODS", "VERSION"),
                routes.stream().map(r -> List.of(
                        Table.cell(principal(r), 48),
                        Table.cell(text(r, "name"), 24),
                        Table.cell(text(r, "target"), 60),
                        Table.cell(text(r, "secret"), 32),
                        Table.cell(text(r, "inject"), 16),
                        Table.cell(methods(r), 40),
                        Table.cell(text(r, "deployedVersion"), 24))).toList());
    }

    /** The refused section: PRINCIPAL, VERSION, REASON. Printed only when there is at least one. */
    static void printRefused(PrintStream out, List<JsonNode> refused) {
        out.println();
        out.println("Refused:");
        Table.print(out, "", List.of("PRINCIPAL", "VERSION", "REASON"),
                refused.stream().map(r -> List.of(
                        Table.cell(principal(r), 48),
                        Table.cell(text(r, "deployedVersion"), 24),
                        Table.cell(text(r, "reason"), 80))).toList());
    }

    /** The route's or refused entry's own {@code principal}, falling back to {@code application:<application>}. */
    static String principal(JsonNode entry) {
        String principal = text(entry, "principal");
        return principal.isBlank() ? "application:" + text(entry, "application") : principal;
    }

    private static String methods(JsonNode route) {
        List<String> methods = new ArrayList<>();
        route.path("methods").forEach(m -> methods.add(m.asText()));
        return String.join(",", methods);
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List every repository's declared egress grants: each route's principal, name, "
                    + "target, the secret it is injected with, how it is injected, its methods and the version "
                    + "it was declared at.",
                    "A repository the deployer could not read the declaration of (a bad egress.yml, a version "
                            + "it has not deployed) shows under Refused instead, with the reason."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits egress grants list",
                    "  qits egress grants list --principal application:qits-edge",
                    "  qits egress grants list --principal project:00000000-0000-4000-8000-000000000001",
                    "  qits egress grants list --application qits-edge",
                    "  qits egress grants list -o json",
                    "",
                    "- --principal filters the table on an exact match; --application is shorthand for "
                            + "--principal application:<application>. The service always answers every repository."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        GrantsCommand parent;

        @CommandLine.Option(names = "--principal", paramLabel = "<principal>",
                description = "Only this principal's routes, an exact match: application:qits-edge, "
                        + "or project:<project id>.")
        String principal;

        @CommandLine.Option(names = "--application", paramLabel = "<application>",
                description = "Shorthand for --principal application:<application>.")
        String application;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wanted = wantedPrincipal();
            JsonNode answer = parent.deployments(context).egressGrants();
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> routes = new ArrayList<>();
            answer.path("routes").forEach(routes::add);
            if (wanted != null) {
                routes = routes.stream().filter(r -> principal(r).equals(wanted)).toList();
            }
            List<JsonNode> refused = new ArrayList<>();
            answer.path("refused").forEach(refused::add);
            if (routes.isEmpty()) {
                context.out().println("No egress grants" + (wanted == null ? "" : " for " + SafeText.line(wanted)) + ".");
            } else {
                printRoutes(context.out(), routes);
            }
            if (!refused.isEmpty()) {
                printRefused(context.out(), refused);
            }
            return 0;
        }

        /** {@code --principal}, or {@code --application} as its shorthand; null for neither. */
        private String wantedPrincipal() throws CliFailure {
            String p = principal == null || principal.isBlank() ? null : principal.strip();
            String a = application == null || application.isBlank() ? null : application.strip();
            if (p != null && a != null) {
                throw new CliFailure("--principal and --application cannot both be given.", CliFailure.USAGE);
            }
            return a == null ? p : "application:" + a;
        }
    }
}
