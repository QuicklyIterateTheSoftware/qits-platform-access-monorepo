package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "projects", mixinStandardHelpOptions = true,
        subcommands = {ProjectsCommand.ListCommand.class, ProjectsCommand.DeliveryCommand.class,
                ProjectsCommand.DeskCommand.class, ProjectsCommand.AgentsCommand.class},
        description = "The platform's projects: list shows them, delivery shows one's delivery desk, desk talks to "
                + "one's front desk, agents talks to one's named agents. Other commands take a project's id, slug "
                + "or name as --project.")
public class ProjectsCommand implements Runnable {

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = "List the projects: slug, name and id.",
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits projects list", "  qits projects list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            JsonNode answer = ProjectsApi.connect(context, parent.options.projectsUrl).projects();
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> projects = ProjectsApi.entries(answer, "project");
            if (projects.isEmpty()) {
                context.out().println("No projects.");
                return 0;
            }
            Table.print(context.out(), "", List.of("SLUG", "NAME", "ID"), projects.stream()
                    .map(p -> List.of(Table.cell(text(p, "slug"), 40), Table.cell(text(p, "name"), 40),
                            Table.cell(text(p, "id"), 64)))
                    .toList());
            return 0;
        }
    }

    /**
     * {@code qits projects delivery}: the project's front desk delivery settings, as the project read
     * carries it (epic qits-1440). Read from the project list, which every {@code --project} lookup
     * reads anyway: the single-project door takes the id only. A field the service does not send — a
     * service older than the delivery desk sends no {@code delivery} at all — prints as the default the
     * service applies when a wrapper declares nothing, marked so.
     */
    @CommandLine.Command(name = "delivery", mixinStandardHelpOptions = true,
            description = {"A project's delivery desk: whether its front desk holds the delivery rights, how many "
                    + "items it keeps running at once, whether it may move a REFINED item to READY_FOR_DEV "
                    + "itself, and the approvals it may give. The desk runs only when a person prompts it.",
                    "The project's wrapper declares it in .config/qits/project.yml under front_desk.delivery. A "
                            + "setting it does not declare prints as the default, marked (default)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits projects delivery --project qits", "  qits projects delivery --project qits -o json",
                    "",
                    "- -o json prints the project's delivery object as the service sent it, null when it sent none."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example no --project, or one the platform does not know), not signed in, "
                            + "or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class DeliveryCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id, slug or name.")
        String project;

        /** What the service answers for a wrapper that declares nothing ({@code ProjectDeliveryDto}). */
        static final List<List<String>> DEFAULTS = List.of(
                List.of("enabled", "enabled", "false"),
                List.of("maxRunning", "max_running", "2"),
                List.of("startRefined", "start_refined", "false"),
                List.of("approves", "approves", "none"));

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            JsonNode found = ProjectsApi.connect(context, parent.options.projectsUrl).project(wanted);
            JsonNode delivery = found.path("delivery");
            if (json) {
                ProjectsApi.printJson(context.out(), delivery.isObject() ? delivery
                        : NullNode.getInstance());
                return 0;
            }
            context.out().println("Project " + SafeText.line(ProjectsApi.projectLabel(found)) + ": delivery desk");
            List<List<String>> rows = new ArrayList<>();
            for (List<String> setting : DEFAULTS) {
                JsonNode value = delivery.path(setting.get(0));
                rows.add(List.of(setting.get(1), value.isMissingNode() || value.isNull()
                        ? setting.get(2) + " (default)" : shown(value)));
            }
            Table.print(context.out(), "", List.of("SETTING", "VALUE"), rows);
            return 0;
        }

        private static String shown(JsonNode value) {
            if (value.isArray()) {
                List<String> all = new ArrayList<>();
                value.forEach(v -> all.add(v.asText()));
                return all.isEmpty() ? "none" : Table.cell(String.join(", ", all), 120);
            }
            return Table.cell(value.asText(), 60);
        }
    }

    /**
     * {@code qits projects desk}: a turn typed into the project's front desk session, that
     * session's last answer (qits-1572), and the messages its desk triggers sent it (epic
     * qits-1604). The session is the one the projects daemon's front desk keeper launched and
     * keeps. Nothing types into it on a schedule (qits-1597): it runs only when a person prompts
     * it, here or from the desk panel. The answer is what an agent
     * wrote, so its table form goes through {@link SafeText} a line at a time and its JSON form
     * through {@link SafeJson}.
     */
    @CommandLine.Command(name = "desk", mixinStandardHelpOptions = true,
            subcommands = {DeskCommand.PromptCommand.class, DeskCommand.AnswerCommand.class,
                    DeskCommand.TriggersCommand.class},
            description = {"A project's front desk: prompt types a turn into its kept front desk session, answer "
                    + "shows that session's last answer, triggers shows the messages its desk triggers sent it.",
                    "The session is the one the project's front desk keeps running in its agent container. It runs "
                            + "only when a person prompts it; nothing prompts it on a schedule. It is not a "
                            + "session a person started from the desk panel."},
            footerHeading = "%nNotes:%n",
            footer = {"- prompt and answer take qits:admin, qits:admin-agent or qits:desk-admin; an agent with "
                    + "qits:agent alone is refused (HTTP 403). triggers is a plain read, open to qits:agent too."})
    public static class DeskCommand implements Runnable {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }

        @CommandLine.Command(name = "prompt", mixinStandardHelpOptions = true,
                description = {"Type a turn into the project's front desk session. The words after the options "
                        + "are joined with spaces into the turn's text.",
                        "Sends POST /projects/api/projects/{projectId}/front-desk/prompt. When the session is "
                                + "idle the turn is typed at once (delivered). While it is busy or waiting on a "
                                + "permission prompt, the turn waits in the desk's queue and is typed in order "
                                + "once the session is idle (queued, with its position). Either way it prints the "
                                + "command id; `qits projects desk answer` shows what the desk answered."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects desk prompt --project qits What is in flight right now?",
                        "  qits projects desk prompt --project qits -o json Summarise the open release requests",
                        "  qits projects desk prompt --project qits Run one pass of the delivery loop.",
                        "",
                        "- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is "
                                + "refused (HTTP 403).",
                        "- The turn goes to the session the front desk keeps. While the turn runs the session is "
                                + "busy, and a later turn waits in the queue until it is idle again.",
                        "- A delivery pass runs only when prompted, as in the last example; nothing starts one "
                                + "on a schedule (qits-1597).",
                        "- -o json prints the service's answer: commandId, state, delivered, queued, position."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:The desk took the turn: delivered, or queued.",
                        "1:The platform refused (for example your roles, HTTP 403, a project it does not know, "
                                + "HTTP 404, a front desk that is not running, HTTP 409, a full queue, HTTP 429, "
                                + "or no projects daemon to reach the desk, HTTP 503), or cannot be reached.",
                        "2:Used wrongly (for example no --project, one the platform does not know, or no text), "
                                + "not signed in, or the session ended."})
        @TuiCommand(effect = Effect.WRITE)
        public static class PromptCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            DeskCommand desk;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @CommandLine.Parameters(paramLabel = "<text>", arity = "0..*",
                    description = "The turn's text (required). The words are joined with spaces.")
            List<String> words;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(desk.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                String text = words == null ? "" : String.join(" ", words).strip();
                if (text.isEmpty()) {
                    throw new CliFailure("Name the turn's text: qits projects desk prompt --project <project> "
                            + "<text...>.", CliFailure.USAGE);
                }
                ProjectsApi api = ProjectsApi.connect(context, desk.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.deskPrompt(text(found, "id"), text);
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                String command = SafeText.line(text(answer, "commandId"));
                String to = command.isEmpty() ? "" : " (command " + command + ")";
                if (answer.path("delivered").asBoolean(false)) {
                    context.out().println("Project " + label + ": delivered to the front desk" + to + ".");
                } else if (answer.path("queued").asBoolean(false)) {
                    JsonNode position = answer.path("position");
                    String state = SafeText.line(text(answer, "state"));
                    context.out().println("Project " + label + ": queued at "
                            + (position.isNumber() ? "position " + position.asInt() : "the desk")
                            + to + ". The desk is " + (state.isEmpty() ? "not idle" : state)
                            + "; the turn is typed once it is idle.");
                } else {
                    context.out().println("Project " + label + ": the front desk took the turn" + to
                            + ", neither delivered nor queued by its own account.");
                }
                return 0;
            }
        }

        @CommandLine.Command(name = "answer", mixinStandardHelpOptions = true,
                description = {"Show the last answer of the project's front desk session: its text, when it "
                        + "was given, the command and session it came from, and the session's state.",
                        "Reads GET /projects/api/projects/{projectId}/front-desk/last-answer. A desk that has "
                                + "not answered yet says so and exits with 0."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects desk answer --project qits",
                        "  qits projects desk answer --project qits -o json",
                        "",
                        "- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is "
                                + "refused (HTTP 403), although it changes nothing.",
                        "- The last answer is the session's, whatever turn it answered: a prompt of yours or "
                                + "somebody else's.",
                        "- -o json prints the service's answer: commandId, sessionId, state, text, at."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:Done, also when the desk has not answered yet.",
                        "1:The platform refused (for example your roles, HTTP 403, a project it does not know, "
                                + "HTTP 404, or no projects daemon to reach the desk, HTTP 503), or cannot be "
                                + "reached.",
                        "2:Used wrongly (for example no --project, or one the platform does not know), not "
                                + "signed in, or the session ended."})
        @TuiCommand(effect = Effect.READ)
        public static class AnswerCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            DeskCommand desk;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(desk.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                ProjectsApi api = ProjectsApi.connect(context, desk.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.deskAnswer(text(found, "id"));
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                JsonNode text = answer.path("text");
                if (!text.isTextual()) {
                    context.out().println("Project " + label + ": the front desk has not answered yet.");
                    return 0;
                }
                context.out().println("Project " + label + ": the front desk's last answer");
                List<List<String>> rows = new ArrayList<>();
                for (String field : List.of("at", "state", "commandId", "sessionId")) {
                    String value = SafeText.line(text(answer, field));
                    rows.add(List.of(field, Table.cell(value.isEmpty() ? "-" : value, 80)));
                }
                Table.print(context.out(), "", List.of("FIELD", "VALUE"), rows);
                context.out().println();
                WorkEntities.lines(context.out(), "", text.asText());
                return 0;
            }
        }

        /**
         * {@code qits projects desk triggers}: what a desk trigger source admitted into the
         * project's delivery desk, and whether the desk took it (epic qits-1604). A plain read: the
         * windows a trigger source (work verified, a release request waiting on approval) opened,
         * closed into a message and tried to deliver, newest first. Unlike {@code prompt} and
         * {@code answer}, this one is open to {@code qits:agent} alone.
         */
        @CommandLine.Command(name = "triggers", mixinStandardHelpOptions = true,
                description = {"The messages the project's desk triggers sent its front desk: one row per window, "
                        + "newest first by when it opened.",
                        "Reads GET /projects/api/projects/{projectId}/desk-triggers. A window is OPEN (still "
                                + "collecting), OWED (closed, not yet delivered), DELIVERED or QUEUED (handed to "
                                + "the desk, with the command it became) or FAILED (never to be delivered)."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects desk triggers --project qits",
                        "  qits projects desk triggers --project qits --limit 50",
                        "  qits projects desk triggers --project qits -o json",
                        "",
                        "- Open to qits:agent as well as a person or admin agent: it reads but does not write.",
                        "- --limit is 1 to 100; the service defaults to 20 when it is not given.",
                        "- The table shows one row per message: when it opened, its state, how many lines it "
                                + "collected, their sources, how many deliveries were tried and when the desk "
                                + "accepted it. -o json prints the service's answer in full, each message's "
                                + "events included."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:Done, also when there are no messages.",
                        "1:The platform refused (for example a --limit outside 1..100, HTTP 400, or a project it "
                                + "does not know, HTTP 404), or cannot be reached.",
                        "2:Used wrongly (for example no --project, or one the platform does not know), not "
                                + "signed in, or the session ended."})
        @TuiCommand(effect = Effect.READ)
        public static class TriggersCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            DeskCommand desk;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                    description = "At most this many messages, the newest. 1 to 100. Default: 20.")
            Integer limit;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(desk.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                ProjectsApi api = ProjectsApi.connect(context, desk.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.deskTriggers(text(found, "id"), limit);
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                List<JsonNode> messages = new ArrayList<>();
                answer.path("messages").forEach(messages::add);
                if (messages.isEmpty()) {
                    context.out().println("Project " + label + ": no desk trigger messages.");
                    return 0;
                }
                context.out().println("Project " + label + ": desk trigger messages");
                Table.print(context.out(), "", List.of("OPENED", "STATE", "EVENTS", "SOURCES", "ATTEMPTS", "ACCEPTED"),
                        messages.stream().map(TriggersCommand::row).toList());
                return 0;
            }

            private static List<String> row(JsonNode message) {
                Set<String> sources = new LinkedHashSet<>();
                message.path("events").forEach(event -> sources.add(SafeText.line(text(event, "source"))));
                String accepted = SafeText.line(text(message, "acceptedAt"));
                return List.of(SafeText.line(text(message, "openedAt")), SafeText.line(text(message, "state")),
                        String.valueOf(message.path("events").size()), Table.cell(String.join(", ", sources), 60),
                        SafeText.line(text(message, "attempts")), accepted.isEmpty() ? "-" : accepted);
            }
        }
    }

    /**
     * {@code qits projects agents}: the project's named agents (qits-2154) — kept Claude
     * sessions in the desk's container, generalising the front desk: {@code desk} is the front
     * desk itself, listed the same as any other; {@code ui-builder} is the pilot. list shows them,
     * prompt types a turn into one, answer shows what it last answered. Modelled on {@link
     * DeskCommand}'s prompt and answer, which keep working exactly as they are: this group is a
     * second, more general door onto the same kind of session, and a new agent surface needs no
     * command of its own here, only an entry in the service's registry.
     */
    @CommandLine.Command(name = "agents", mixinStandardHelpOptions = true,
            subcommands = {AgentsCommand.ListCommand.class, AgentsCommand.PromptCommand.class,
                    AgentsCommand.AnswerCommand.class},
            description = {"A project's named agents: list shows them, prompt types a turn into one's kept "
                    + "session, answer shows that session's last answer.",
                    "A named agent is a kept Claude session in the project's desk container (qits-2154): "
                            + "`desk` is the front desk, `ui-builder` the UI builder's agent. Each runs only when "
                            + "prompted and stops when idle; prompting one starts the desk container when it is "
                            + "stopped."},
            footerHeading = "%nNotes:%n",
            footer = {"- Every subcommand takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with "
                    + "qits:agent alone is refused (HTTP 403), list and answer included, although neither writes."})
    public static class AgentsCommand implements Runnable {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        static final String NAME_THE_AGENT = "Name the agent: --name <name>.";

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }

        @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
                description = {"List the project's named agents: their surface, state, session and terminal "
                        + "command id.",
                        "Sends GET /projects/api/projects/{projectId}/agents. state is one of STOPPED, STARTING, "
                                + "IDLE, BUSY or FAILED; the desk is listed first."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects agents list --project qits",
                        "  qits projects agents list --project qits -o json",
                        "",
                        "- -o json prints the service's answer: agents[].{name,surface,state,sessionId,commandId}."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
        @TuiCommand(effect = Effect.READ)
        public static class ListCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            AgentsCommand agents;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(agents.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                ProjectsApi api = ProjectsApi.connect(context, agents.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.agents(text(found, "id"));
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                List<JsonNode> agentRows = new ArrayList<>();
                answer.path("agents").forEach(agentRows::add);
                if (agentRows.isEmpty()) {
                    context.out().println("Project " + label + ": no named agents.");
                    return 0;
                }
                context.out().println("Project " + label + ": named agents");
                Table.print(context.out(), "", List.of("NAME", "SURFACE", "STATE", "SESSION", "COMMAND"),
                        agentRows.stream().map(ListCommand::row).toList());
                return 0;
            }

            private static List<String> row(JsonNode agent) {
                return List.of(SafeText.line(text(agent, "name")), SafeText.line(text(agent, "surface")),
                        SafeText.line(text(agent, "state")), dash(text(agent, "sessionId")),
                        dash(text(agent, "commandId")));
            }

            private static String dash(String value) {
                String line = SafeText.line(value);
                return line.isEmpty() ? "-" : line;
            }
        }

        @CommandLine.Command(name = "prompt", mixinStandardHelpOptions = true,
                description = {"Type a turn into a named agent's kept session. The words after the options are "
                        + "joined with spaces into the turn's text.",
                        "Sends POST /projects/api/projects/{projectId}/agents/{name}/prompt. When the agent's "
                                + "session is idle the turn is typed at once (delivered). While it is busy, the "
                                + "turn waits in the agent's own queue and is typed once it is idle (queued, with "
                                + "its position). When the desk container is stopped, prompting it starts the "
                                + "container; the turn then waits and is delivered once the agent's session "
                                + "first comes up. Either way it prints the command id; `qits projects agents "
                                + "answer` shows what the agent answered.",
                        "A named agent is a kept Claude session in the project's desk container (qits-2154): "
                                + "`desk` is the front desk, `ui-builder` the UI builder's agent."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects agents prompt --project qits --name ui-builder Pitch a calmer header.",
                        "  qits projects agents prompt --project qits --name ui-builder -o json Mock the empty state.",
                        "",
                        "- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone "
                                + "is refused (HTTP 403).",
                        "- The turn goes to the named agent's own session, beside the desk's; prompting it never "
                                + "disturbs the front desk's session or queue.",
                        "- -o json prints the service's answer: commandId, state, delivered, queued, position."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:The agent took the turn: delivered, queued, or the desk container is starting "
                        + "and the turn waits.",
                        "1:The platform refused (for example your roles, HTTP 403, a project it does not know, or "
                                + "an agent the project does not name, HTTP 404, a prompt already waiting or the "
                                + "agent not running, HTTP 409, a full inbox, HTTP 429, or no projects daemon to "
                                + "reach the desk container, HTTP 503), or cannot be reached.",
                        "2:Used wrongly (for example no --project, one the platform does not know, no --name, or "
                                + "no text), not signed in, or the session ended."})
        @TuiCommand(effect = Effect.WRITE)
        public static class PromptCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            AgentsCommand agents;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @CommandLine.Option(names = "--name", paramLabel = "<name>",
                    description = "The named agent (required): desk, ui-builder, or another the project declares.")
            String name;

            @CommandLine.Parameters(paramLabel = "<text>", arity = "0..*",
                    description = "The turn's text (required). The words are joined with spaces.")
            List<String> words;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(agents.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                String wantedName = RepositoriesCommand.required(name, AgentsCommand.NAME_THE_AGENT);
                String text = words == null ? "" : String.join(" ", words).strip();
                if (text.isEmpty()) {
                    throw new CliFailure("Name the turn's text: qits projects agents prompt --project <project> "
                            + "--name <name> <text...>.", CliFailure.USAGE);
                }
                ProjectsApi api = ProjectsApi.connect(context, agents.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.agentPrompt(text(found, "id"), wantedName, text);
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                String agentLabel = SafeText.line(wantedName);
                String command = SafeText.line(text(answer, "commandId"));
                String to = command.isEmpty() ? "" : " (command " + command + ")";
                String state = SafeText.line(text(answer, "state"));
                boolean delivered = answer.path("delivered").asBoolean(false);
                boolean queued = answer.path("queued").asBoolean(false);
                if (delivered) {
                    context.out().println("Project " + label + ": delivered to agent " + agentLabel + to + ".");
                } else if (queued && command.isEmpty() && "STARTING".equals(state)) {
                    // The desk container was stopped: the service started it (202), and the turn waits in the
                    // agent's own inbox, with no command id yet, until that agent's session first reports
                    // running.
                    context.out().println("Project " + label + ": the desk container was stopped; it is starting. "
                            + "The turn waits and is delivered to agent " + agentLabel
                            + " once its session is up.");
                } else if (queued) {
                    JsonNode position = answer.path("position");
                    context.out().println("Project " + label + ": queued for agent " + agentLabel + " at "
                            + (position.isNumber() ? "position " + position.asInt() : "its inbox")
                            + to + ". The agent is " + (state.isEmpty() ? "not idle" : state)
                            + "; the turn is typed once it is idle.");
                } else {
                    context.out().println("Project " + label + ": agent " + agentLabel + " took the turn" + to
                            + ", neither delivered nor queued by its own account.");
                }
                return 0;
            }
        }

        @CommandLine.Command(name = "answer", mixinStandardHelpOptions = true,
                description = {"Show a named agent's last answer: its text, when it was given, the command and "
                        + "session it came from, and the session's state.",
                        "Sends GET /projects/api/projects/{projectId}/agents/{name}/last-answer. An agent that "
                                + "has not answered yet says so and exits with 0."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects agents answer --project qits --name ui-builder",
                        "  qits projects agents answer --project qits --name ui-builder -o json",
                        "",
                        "- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone "
                                + "is refused (HTTP 403), although it changes nothing.",
                        "- The last answer is that agent's own session's, whatever turn it answered: a prompt of "
                                + "yours or somebody else's.",
                        "- -o json prints the service's answer: commandId, sessionId, state, text, at."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:Done, also when the agent has not answered yet.",
                        "1:The platform refused (for example your roles, HTTP 403, a project it does not know, or "
                                + "an agent the project does not name, HTTP 404, or no projects daemon to reach "
                                + "the desk container, HTTP 503), or cannot be reached.",
                        "2:Used wrongly (for example no --project, one the platform does not know, or no --name), "
                                + "not signed in, or the session ended."})
        @TuiCommand(effect = Effect.READ)
        public static class AnswerCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            AgentsCommand agents;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @CommandLine.Option(names = "--name", paramLabel = "<name>",
                    description = "The named agent (required): desk, ui-builder, or another the project declares.")
            String name;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(agents.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                String wantedName = RepositoriesCommand.required(name, AgentsCommand.NAME_THE_AGENT);
                ProjectsApi api = ProjectsApi.connect(context, agents.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.agentAnswer(text(found, "id"), wantedName);
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                String agentLabel = SafeText.line(wantedName);
                JsonNode text = answer.path("text");
                if (!text.isTextual()) {
                    context.out().println("Project " + label + ": agent " + agentLabel + " has not answered yet.");
                    return 0;
                }
                context.out().println("Project " + label + ": agent " + agentLabel + "'s last answer");
                List<List<String>> rows = new ArrayList<>();
                for (String field : List.of("at", "state", "commandId", "sessionId")) {
                    String value = SafeText.line(text(answer, field));
                    rows.add(List.of(field, Table.cell(value.isEmpty() ? "-" : value, 80)));
                }
                Table.print(context.out(), "", List.of("FIELD", "VALUE"), rows);
                context.out().println();
                WorkEntities.lines(context.out(), "", text.asText());
                return 0;
            }
        }
    }
}
