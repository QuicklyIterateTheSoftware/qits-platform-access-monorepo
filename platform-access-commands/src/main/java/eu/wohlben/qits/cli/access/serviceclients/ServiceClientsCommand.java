package eu.wohlben.qits.cli.access.serviceclients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.idp.IdpUrl;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code qits service-clients}: the idp's service clients, one per service that declares {@code
 * idp:client} (qits-2531). {@code list} shows them; the idp frontend's Clients page shows the same
 * list. Creating, rotating and deleting stay with the platform's services, over their own Basic pair.
 */
@CommandLine.Command(name = "service-clients", mixinStandardHelpOptions = true,
        subcommands = {ServiceClientsCommand.ListCommand.class},
        description = {"The idp's service clients: one per platform service that declares idp:client. list shows "
                + "each one's id, when it was created and when its secret was last rotated. Never a secret.",
                "Reading needs qits:admin, qits:agent, qits:system, qits:admin-agent or qits:desk-admin."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --idp-url may come before or after the command."})
public class ServiceClientsCommand implements Runnable {

    static final String READER = "Reading service clients needs qits:admin, qits:agent, qits:system, "
            + "qits:admin-agent or qits:desk-admin";

    @CommandLine.Option(names = "--idp-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The idp's base URL, with /idp. Default: the session's idp; inside the platform "
                    + "https://idp.qits.<QITS_DOMAIN>/idp.")
    String idpUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): for people. json: the answer as JSON, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
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

    /** The idp's API, called with this home's credential. */
    IdpServiceClientsApi api(CliContext context) throws CliFailure, InterruptedException {
        return new IdpServiceClientsApi(new PlatformClient(context.credential()), idp(context));
    }

    /**
     * The idp with its {@code /idp} prefix, found as {@code qits users} finds it: the flag first; on
     * a workstation the session's idp; anywhere else {@code PlatformEndpoints}.
     */
    String idp(CliContext context) throws CliFailure, InterruptedException {
        if (idpUrl != null && !idpUrl.isBlank()) {
            return IdpUrl.resolve(idpUrl, context.env());
        }
        if (context.mode() == Mode.WORKSTATION) {
            return IdpUrl.resolve(context.idpUrl(), context.env());
        }
        return context.endpoints(null).base("idp") + "/idp";
    }

    /** A 403 from the idp names the roles the read wants, above the idp's own sentence. */
    static CliFailure forbidden(CliFailure refused) {
        return refused.status() == 403
                ? CliFailure.refused(READER + ". " + refused.getMessage(), 403)
                : refused;
    }

    static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : "";
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the idp's service clients, sorted by id: each one's id, when it was created, and "
                    + "when its secret was last rotated (- for never)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits service-clients list",
                    "  qits service-clients list -o json",
                    "",
                    "- -o json prints the idp's answer, in the idp's order."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused (HTTP 403: the caller holds none of the reading roles), or cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ServiceClientsCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode clients;
            try {
                clients = parent.api(context).list();
            } catch (CliFailure refused) {
                throw forbidden(refused);
            }
            if (json) {
                printJson(context.out(), clients);
                return 0;
            }
            if (!clients.isArray() || clients.isEmpty()) {
                context.out().println("No service clients.");
                return 0;
            }
            List<JsonNode> sorted = new ArrayList<>();
            clients.forEach(sorted::add);
            sorted.sort(Comparator.comparing(client -> text(client, "clientId")));
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode client : sorted) {
                String rotated = text(client, "rotatedAt");
                rows.add(List.of(
                        Table.cell(text(client, "clientId"), 48),
                        Table.time(text(client, "createdAt")),
                        rotated.isEmpty() ? "-" : Table.time(rotated)));
            }
            Table.print(context.out(), "", List.of("CLIENT ID", "CREATED", "SECRET ROTATED"), rows);
            return 0;
        }
    }
}
