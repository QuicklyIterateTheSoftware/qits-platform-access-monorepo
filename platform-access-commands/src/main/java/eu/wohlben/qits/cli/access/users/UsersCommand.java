package eu.wohlben.qits.cli.access.users;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.idp.IdpUrl;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code qits users}: the idp's people. An admin invites (a register token) and the person it is
 * handed to registers with it; the idp generates the password and shows it once.
 * <p>
 * {@code invite} and {@code register} print a secret, so they are {@link Interaction#LOCAL}, the
 * way {@code git-credential} is: the MCP service does not serve them, because its answer lands in
 * an agent's transcript rather than in front of the one person meant to read it.
 */
@CommandLine.Command(name = "users", mixinStandardHelpOptions = true,
        subcommands = {UsersCommand.InviteCommand.class, UsersCommand.RegisterCommand.class,
                UsersCommand.ListCommand.class, UsersCommand.SubjectsCommand.class, UsersCommand.DeleteCommand.class},
        description = {"The people who sign in to the platform, on the idp: invite mints a register token, "
                + "register spends one on a new username and shows the generated password once, list shows "
                + "the users and their roles, subjects shows each user's subject id (what a toggle's user rule "
                + "names), and delete removes one.",
                "invite, list and delete need qits:admin; subjects needs qits:admin or the delivery desk; register "
                        + "needs only the token, and no sign-in."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --idp-url may come before or after the command."})
public class UsersCommand implements Runnable {

    static final String ADMIN = "Managing users needs qits:admin";

    static final String CONFIGURING = "Listing user subjects needs qits:admin or the delivery desk";

    @CommandLine.Option(names = "--idp-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The idp's base URL, with /idp. Default: the session's idp; inside the platform "
                    + "https://idp.qits.<QITS_DOMAIN>/idp; for register with no session, QITS_IDP_URL, else the address `qits login` uses.")
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
    IdpUsersApi signedIn(CliContext context) throws CliFailure, InterruptedException {
        return new IdpUsersApi(new PlatformClient(context.credential()), idp(context, true));
    }

    /** The idp's doors, for a call that sends no credential. */
    IdpUsersApi anonymous(CliContext context) throws CliFailure, InterruptedException {
        return new IdpUsersApi(null, idp(context, false));
    }

    /**
     * The idp with its {@code /idp} prefix. The flag first. On a workstation the session's idp, which
     * is where its bearer was minted; a registration needs no session, so without one it takes the
     * address {@code qits login} would ({@link IdpUrl}). Anywhere else {@code PlatformEndpoints}
     * decides, as for every service: the public vhost of {@code QITS_DOMAIN} inside and in the
     * token home, {@code QITS_URL_IDP} over either.
     */
    String idp(CliContext context, boolean session) throws CliFailure, InterruptedException {
        if (idpUrl != null && !idpUrl.isBlank()) {
            return IdpUrl.resolve(idpUrl, context.env());
        }
        if (context.mode() == Mode.WORKSTATION) {
            if (session) {
                return IdpUrl.resolve(context.idpUrl(), context.env());
            }
            Optional<String> stored = storedSession(context).map(Session::idpUrl);
            return IdpUrl.resolve(stored.orElse(null), context.env());
        }
        // No session address outside a workstation: the endpoints work it out from the environment.
        return context.endpoints(null).base("idp") + "/idp";
    }

    private static Optional<Session> storedSession(CliContext context) {
        try {
            return context.sessionFile().read();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** A 403 from the idp says which role the door wants, above the idp's own sentence. */
    static CliFailure forbidden(CliFailure refused) {
        return refused.status() == 403
                ? CliFailure.refused(ADMIN + ". " + refused.getMessage(), 403)
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

    static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    @CommandLine.Command(name = "invite", mixinStandardHelpOptions = true,
            description = {"Mint a register token for one new user, and print it.",
                    "The token registers one user, once, with the platform's bootstrap roles. It is printed alone "
                            + "on stdout and nowhere else: hand it to the person privately, and they run `qits users "
                            + "register`. Lost, it is simply never spent; mint another. Needs qits:admin."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits users invite",
                    "  qits users invite -o json",
                    "",
                    "- -o json prints the idp's answer: the token's id, the token and when it was minted.",
                    "- Not served by the qits MCP server: the token would land in a transcript."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused (HTTP 403: the caller is not qits:admin), or cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand(interaction = Interaction.LOCAL)
    public static class InviteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UsersCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode minted;
            try {
                minted = parent.signedIn(context).invite();
            } catch (CliFailure refused) {
                throw forbidden(refused);
            }
            String token = text(minted, "token");
            if (token.isEmpty()) {
                throw new CliFailure("The idp minted a register token, but its answer carries none.", CliFailure.FAILED);
            }
            if (json) {
                printJson(context.out(), minted);
                return 0;
            }
            context.out().println(token);
            context.err().println("Register token " + SafeText.line(text(minted, "id")) + ": one registration, once. "
                    + "Hand it over privately; it is spent by `qits users register --token <token> <username>`.");
            return 0;
        }
    }

    @CommandLine.Command(name = "register", mixinStandardHelpOptions = true,
            description = {"Spend a register token on a new username, and print the password the idp generated for it.",
                    "THE PASSWORD IS SHOWN ONLY THIS ONCE: it is printed alone on stdout, never written to a file or a "
                            + "log, and nobody can show it again. Store it before you do anything else. Needs no "
                            + "sign-in: the token is the credential. A refused username leaves the token unspent."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits users register --token <token> jan",
                    "  qits users register --token <token> jan -o json",
                    "",
                    "- -o json prints {\"username\": ..., \"password\": ...} and nothing of the session the idp opens.",
                    "- Then sign in with `qits login`.",
                    "- Not served by the qits MCP server: the password would land in a transcript."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused the token (unknown or spent) or the username (taken, or not a valid one), "
                            + "or cannot be reached.",
                    "2:Used wrongly."})
    @TuiCommand(interaction = Interaction.LOCAL)
    public static class RegisterCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UsersCommand parent;

        @CommandLine.Option(names = "--token", paramLabel = "<token>", required = true,
                description = "The register token `qits users invite` printed. This call spends it.")
        String token;

        @CommandLine.Parameters(index = "0", paramLabel = "<username>", description = "The new user's name.")
        String username;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String name = required(username, "Name the user: qits users register --token <token> <username>.");
            String spent = required(token, "Give the register token: --token <token>.");
            JsonNode answer = parent.anonymous(context).register(name, spent);
            String registered = text(answer, "username").isEmpty() ? name : text(answer, "username");
            String password = text(answer, "password");
            if (json) {
                ObjectNode shown = SafeText.JSON.createObjectNode();
                shown.put("username", registered);
                shown.put("password", password);
                printJson(context.out(), shown);
                return 0;
            }
            context.out().println(password);
            context.err().println("Registered " + SafeText.line(registered) + ". The password on stdout is shown only "
                    + "this once: store it now. Then sign in with `qits login`.");
            return 0;
        }
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the users: name, when each registered, and their roles. Needs qits:admin."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits users list",
                    "  qits users list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused (HTTP 403: the caller is not qits:admin), or cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UsersCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode users;
            try {
                users = parent.signedIn(context).list();
            } catch (CliFailure refused) {
                throw forbidden(refused);
            }
            if (json) {
                printJson(context.out(), users);
                return 0;
            }
            if (!users.isArray() || users.isEmpty()) {
                context.out().println("No users.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode user : users) {
                List<String> roles = new ArrayList<>();
                user.path("roles").forEach(role -> roles.add(role.asText("")));
                rows.add(List.of(
                        Table.cell(text(user, "username"), 40),
                        Table.time(text(user, "createdAt")),
                        Table.cell(String.join(", ", roles), 80)));
            }
            Table.print(context.out(), "", List.of("USERNAME", "CREATED", "ROLES"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "subjects", mixinStandardHelpOptions = true,
            description = {"List each user's subject id beside their name: the id a toggle's user rule names "
                    + "(`qits toggles set-rules`). People only; an agent's subject is its tok-... id. Needs qits:admin "
                    + "or the delivery desk."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits users subjects",
                    "  qits users subjects -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused (HTTP 403: the caller is neither qits:admin nor the delivery desk), or "
                            + "cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class SubjectsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UsersCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode subjects;
            try {
                subjects = parent.signedIn(context).subjects();
            } catch (CliFailure refused) {
                throw refused.status() == 403
                        ? CliFailure.refused(CONFIGURING + ". " + refused.getMessage(), 403)
                        : refused;
            }
            if (json) {
                printJson(context.out(), subjects);
                return 0;
            }
            if (!subjects.isArray() || subjects.isEmpty()) {
                context.out().println("No users.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode user : subjects) {
                rows.add(List.of(Table.cell(text(user, "username"), 40), Table.cell(text(user, "subject"), 40)));
            }
            Table.print(context.out(), "", List.of("USERNAME", "SUBJECT"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Delete a user: their sign-ins and sessions go with them. Needs qits:admin, and nobody "
                    + "can delete themselves."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits users delete jan"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The idp refused: no such user (404), it is you (409), the caller is not qits:admin (403), "
                            + "or it cannot be reached.",
                    HelpText.USAGE})
    @TuiCommand
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UsersCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<username>", description = "The user to delete.")
        String username;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            parent.json();
            String name = required(username, "Name the user: qits users delete <username>.");
            try {
                parent.signedIn(context).delete(name);
            } catch (CliFailure refused) {
                throw switch (refused.status()) {
                    case 404 -> CliFailure.refused("There is no user '" + SafeText.line(name) + "' (HTTP 404).", 404);
                    case 409 -> CliFailure.refused("You cannot delete yourself (HTTP 409): '" + SafeText.line(name)
                            + "' is the user this call is signed in as.", 409);
                    default -> forbidden(refused);
                };
            }
            context.out().println("Deleted user " + SafeText.line(name) + ".");
            return 0;
        }
    }
}
