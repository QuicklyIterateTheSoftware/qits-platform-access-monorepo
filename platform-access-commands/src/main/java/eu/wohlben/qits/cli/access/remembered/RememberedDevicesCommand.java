package eu.wohlben.qits.cli.access.remembered;

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
import java.util.List;
import java.util.UUID;

/**
 * {@code qits remembered-devices}: the signed-in person's remembered browsers at the idp, the ones
 * that sign back in without a password (qits-1944). {@code list} shows them, {@code revoke} forgets
 * one and ends every session it opened.
 * <p>
 * The doors are a person's: the idp admits the CLI bearer {@code qits login} stores (or a browser
 * session) and refuses any other bearer, an agent's {@code QITS_TOKEN} among them, with 403. The
 * command adds one sentence above that refusal, as {@code qits users} does with its role, and
 * otherwise prints what the idp said.
 */
@CommandLine.Command(name = "remembered-devices", mixinStandardHelpOptions = true,
        subcommands = {RememberedDevicesCommand.ListCommand.class, RememberedDevicesCommand.RevokeCommand.class},
        description = {"Your remembered browsers at the idp: the browsers that sign back in without a password. "
                + "list shows them, and revoke forgets one and ends every session it opened.",
                "Only your own: the idp answers a person signed in with `qits login` (or a browser), and refuses "
                        + "any other credential, an agent's QITS_TOKEN among them, with 403."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --idp-url may come before or after the command."})
public class RememberedDevicesCommand implements Runnable {

    static final String PERSON = "Remembered devices are a person's own: only the session of `qits login` (or a "
            + "browser) may reach them";

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

    /** The idp's doors, called with this home's credential. */
    IdpRememberedDevicesApi api(CliContext context) throws CliFailure, InterruptedException {
        return new IdpRememberedDevicesApi(new PlatformClient(context.credential()), idp(context));
    }

    /**
     * The idp with its {@code /idp} prefix, found as {@code qits users} finds it: the flag first; on
     * a workstation the session's idp, which is where its bearer was minted; anywhere else {@code
     * PlatformEndpoints}, the public vhost of {@code QITS_DOMAIN} or {@code QITS_URL_IDP} over it.
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

    /** A 403 from the idp says whose doors these are, above the idp's own sentence. */
    static CliFailure forbidden(CliFailure refused) {
        return refused.status() == 403
                ? CliFailure.refused(PERSON + ". " + refused.getMessage(), 403)
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
            description = {"List your remembered browsers: each one's id, its label (the browser it was "
                    + "remembered from), when it last signed in, when it expires, and which one this is.",
                    "CURRENT marks the browser the calling session was opened through; a CLI session has none."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits remembered-devices list",
                    "  qits remembered-devices list -o json",
                    "",
                    "- -o json prints the idp's answer, with createdAt and absoluteExpiresAt too.",
                    "- Revoke one with `qits remembered-devices revoke --device <id>`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused (HTTP 403: the credential is not a person's `qits login` session), or "
                            + "cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RememberedDevicesCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode devices;
            try {
                devices = parent.api(context).list();
            } catch (CliFailure refused) {
                throw forbidden(refused);
            }
            if (json) {
                printJson(context.out(), devices);
                return 0;
            }
            if (!devices.isArray() || devices.isEmpty()) {
                context.out().println("No remembered devices.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode device : devices) {
                rows.add(List.of(
                        Table.cell(text(device, "id"), 36),
                        Table.cell(text(device, "label"), 40),
                        Table.time(text(device, "lastUsedAt")),
                        Table.time(text(device, "expiresAt")),
                        device.path("current").asBoolean(false) ? "yes" : "-"));
            }
            Table.print(context.out(), "", List.of("ID", "LABEL", "LAST USED", "EXPIRES", "CURRENT"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "revoke", mixinStandardHelpOptions = true,
            description = {"Forget one remembered browser: it signs in with a password again, and every session "
                    + "it opened ends now."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits remembered-devices revoke --device 6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f",
                    "",
                    "- The id is the ID column of `qits remembered-devices list`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused: no such device of yours (404), the credential is not a person's "
                            + "`qits login` session (403), or it cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand
    public static class RevokeCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RememberedDevicesCommand parent;

        @CommandLine.Option(names = "--device", paramLabel = "<id>", required = true,
                description = "The device to forget: its id, from `qits remembered-devices list`.")
        String device;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            parent.json();
            String id = device == null ? "" : device.strip();
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                throw new CliFailure("--device takes a device id, the ID column of `qits remembered-devices list`; '"
                        + SafeText.line(id) + "' is not one.", CliFailure.USAGE);
            }
            try {
                parent.api(context).revoke(id);
            } catch (CliFailure refused) {
                throw refused.status() == 404
                        ? CliFailure.refused("You have no remembered device " + id + " (HTTP 404).", 404)
                        : forbidden(refused);
            }
            context.out().println("Revoked remembered device " + id + ": it signs in with a password again.");
            return 0;
        }
    }
}
