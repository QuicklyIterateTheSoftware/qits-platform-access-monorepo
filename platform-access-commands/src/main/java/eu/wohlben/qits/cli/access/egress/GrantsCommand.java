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

    /** The routes table: APPLICATION, ROUTE, TARGET, SECRET, METHODS, VERSION. */
    static void printRoutes(PrintStream out, List<JsonNode> routes) {
        Table.print(out, "", List.of("APPLICATION", "ROUTE", "TARGET", "SECRET", "METHODS", "VERSION"),
                routes.stream().map(r -> List.of(
                        Table.cell(text(r, "application"), 32),
                        Table.cell(text(r, "name"), 24),
                        Table.cell(text(r, "target"), 60),
                        Table.cell(text(r, "secret"), 32),
                        Table.cell(methods(r), 40),
                        Table.cell(text(r, "deployedVersion"), 24))).toList());
    }

    /** The refused section: APPLICATION, VERSION, REASON. Printed only when there is at least one. */
    static void printRefused(PrintStream out, List<JsonNode> refused) {
        out.println();
        out.println("Refused:");
        Table.print(out, "", List.of("APPLICATION", "VERSION", "REASON"),
                refused.stream().map(r -> List.of(
                        Table.cell(text(r, "application"), 32),
                        Table.cell(text(r, "deployedVersion"), 24),
                        Table.cell(text(r, "reason"), 80))).toList());
    }

    private static String methods(JsonNode route) {
        List<String> methods = new ArrayList<>();
        route.path("methods").forEach(m -> methods.add(m.asText()));
        return String.join(",", methods);
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List every repository's declared egress grants: each route's application, name, "
                    + "target, the secret it is injected with, its methods and the version it was declared at.",
                    "A repository the deployer could not read the declaration of (a bad egress.yml, a version "
                            + "it has not deployed) shows under Refused instead, with the reason."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits egress grants list",
                    "  qits egress grants list --application qits-edge",
                    "  qits egress grants list -o json",
                    "",
                    "- --application filters the table; the service always answers every repository."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        GrantsCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>",
                description = "Only this application's routes: qits-edge.")
        String application;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode answer = parent.deployments(context).egressGrants();
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> routes = new ArrayList<>();
            answer.path("routes").forEach(routes::add);
            String wanted = application == null || application.isBlank() ? null : application.strip();
            if (wanted != null) {
                routes = routes.stream().filter(r -> text(r, "application").equals(wanted)).toList();
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
    }
}
