package eu.wohlben.qits.cli.access.deployments;

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
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits deployments requests}: the deployment requests qits-deployments holds (qits-2268). A
 * release whose release request held its deploy ({@code qits release-request hold-deploy}) is
 * published as ever, and its deployment request waits at the deployer until somebody approves it;
 * {@code approve} is that approval, and the deployment it queues.
 */
@CommandLine.Command(name = "requests", mixinStandardHelpOptions = true,
        subcommands = {RequestsCommand.ApproveCommand.class},
        description = {"Deployment requests at qits-deployments: what a release asks to deploy, and the gate it "
                + "waits behind. `approve` lets one held for a person's approval deploy."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --deployments-url may come before or after the command."})
public class RequestsCommand implements Runnable {

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

    // --- approve ---

    @CommandLine.Command(name = "approve", mixinStandardHelpOptions = true,
            description = {"Approve a deployment request held for a person's approval, so its deployment is queued "
                    + "and runs. It prints the request's gate (MET once approved, with who approved it and when) and "
                    + "the deployment it queued.",
                    "POST /deployments/api/deployment-requests/{id}/approve with no body (HTTP 200 "
                            + "{deploymentRequest, deployment}). A request is held when its release request held its "
                            + "deploy: `qits release-request hold-deploy`."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments requests approve --request 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    "",
                    "- HTTP 404: no deployment request of that id. HTTP 409: the request is not held, its gate "
                            + "already settled, or its version is no longer the application's newest release "
                            + "(approving it would be a rollback). Each is exit 1, and nothing was queued.",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ApproveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RequestsCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The deployment request's id, as qits-deployments names it.")
        String request;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String id = SettingsCommand.required(request, "Name the deployment request: --request <id>.");
            JsonNode answer = parent.deployments(context).approveDeploymentRequest(id);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printApproved(context.out(), answer);
            return 0;
        }
    }

    /**
     * The request as name/value lines, its gate first among them, then {@code deployment <id>:
     * <STATUS>} for the deployment the approval queued.
     */
    static void printApproved(PrintStream out, JsonNode answer) {
        JsonNode request = answer.path("deploymentRequest");
        out.println("deployment request " + SafeText.line(text(request, "id")) + ": "
                + SafeText.line(text(request, "qualityGate")));
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("application", Table.cell(text(request, "applicationName"), 64)));
        rows.add(List.of("version", Table.cell(text(request, "version"), 64)));
        if (!text(request, "releaseRequestQualifiedId").isEmpty()) {
            rows.add(List.of("release request", Table.cell(text(request, "releaseRequestQualifiedId"), 64)));
        }
        rows.add(List.of("gate", Table.cell(text(request, "qualityGate"), 32)));
        rows.add(List.of("gate detail", Table.cell(text(request, "gateDetail"), 400)));
        rows.add(List.of("approved by", Table.cell(text(request, "approvedBy"), 64)));
        rows.add(List.of("approved at", Table.time(text(request, "approvedAt"))));
        Table.print(out, "  ", null, rows);
        JsonNode deployment = answer.path("deployment");
        String deploymentId = text(deployment, "id").isEmpty() ? text(request, "deploymentId") : text(deployment, "id");
        String status = text(deployment, "status").isEmpty() ? text(request, "deploymentStatus") : text(deployment, "status");
        if (!deploymentId.isEmpty()) {
            out.println("deployment " + SafeText.line(deploymentId) + ": " + SafeText.line(status.isEmpty() ? "-" : status));
        }
    }
}
