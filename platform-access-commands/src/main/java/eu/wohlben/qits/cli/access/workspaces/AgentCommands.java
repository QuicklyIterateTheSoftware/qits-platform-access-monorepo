package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.terminal.LocalTerminal;
import eu.wohlben.qits.cli.access.terminal.TerminalAttach;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.Json;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.cell;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.entries;

/**
 * The verbs of {@code qits agents} (epic qits-1667): one agent, named by its id or by {@code --work}
 * ({@link AgentRef}), and what can be done to it on qits-workspaces. The JSON API's agent doors for
 * the agent itself; the agent proxy, {@code /workspaces/agent/{id}/…}, for what its daemon holds (its
 * chat, its terminal, its worktree), so no verb needs the slot the agent happens to be in.
 * <p>
 * {@code qits agents} itself is {@code agents/AgentsCommand}, which lists these beside the Claude
 * Code commands. The tables and the draft are {@link AgentViews}, shared with {@code qits workspaces
 * show}, which prints a slot's agents the same way.
 */
public final class AgentCommands {

    private AgentCommands() {
    }

    /** A verb's options: the service addresses and -o. */
    abstract static class Verb extends PlatformCommand {

        @CommandLine.Mixin
        AgentOptions options;
    }

    /** A verb on one agent: its id, or --work. */
    abstract static class OnAgent extends Verb {

        @CommandLine.Mixin
        AgentRef ref;

        String agent(CliContext context, String given) throws CliFailure, InterruptedException {
            return ref.resolve(context, options, given);
        }
    }

    private static final String AGENT = "The agent's id, as `qits agents list` shows it. Leave it out with --work.";

    // --- the agents ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"Agents, newest first: what each works on, its slot, how it runs (RUNNING, YIELDED, "
                    + "STOPPED, ...; QUEUED, PROVISIONING or STARTING until its slot is READY), what it waits on and what it needs a person for. Bare, every agent; --work, "
                    + "one item's; --workspace, one slot's; --state ACTIVE, the live ones; --project, one project's.",
                    "WAITING ON is built from each open wait's subject: a release request or work item shows its "
                            + "qualified id and state, a CI run the first 8 characters of its id and state, a "
                            + "comments wait \"comments on <item>\", anything else its label; several are joined "
                            + "with \"; \". With no waits array (an older service), it falls back to waitingOn. "
                            + "ATTENTION is attention.reason, a dash when there is none.",
                    "GET /workspaces/api/agents[?workId=&workspaceId=&state=&projectId=]. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents list --state ACTIVE",
                    "  qits agents list --work qits-123",
                    "  qits agents list --project qits --state ACTIVE",
                    "  qits agents list --workspace 1 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends Verb {

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "Only the agents whose repository is this project's: its id, slug or name. Sent as "
                        + "projectId. Without it, every project's.")
        String project;

        @CommandLine.Option(names = "--work", paramLabel = "<work>",
                description = "Only this work item's agents: its qualified id (qits-123) or its id.")
        String work;

        @CommandLine.Option(names = "--workspace", paramLabel = "<n>", description = "Only the agents this slot hosts.")
        Long workspace;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE>",
                description = "Only the agents in this state: ACTIVE or REMOVED. Default: all. How each runs is the "
                        + "RUN column, not a filter.")
        String state;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            return AgentViews.list(context, options, project, work, workspace, state);
        }
    }

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"One agent, in any state: what it works on, how it runs and what it waits for to start, its slot, branch and session, "
                    + "what it waits on and what it needs a person for.",
                    "GET /workspaces/api/agents/{id}. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents show 00000000-0000-4000-8000-0000000000a1",
                    "  qits agents show --work qits-123 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (HTTP 404: no such agent), cannot be reached, or "
                    + "--work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ShowCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            JsonNode answer = options.api(context).agent(agent(context, agent));
            if (json) {
                Json.print(context.out(), answer);
            } else {
                AgentViews.printAgent(context.out(), answer);
            }
            return 0;
        }
    }

    // --- control ---

    @CommandLine.Command(name = "prompt", mixinStandardHelpOptions = true,
            description = {"Say a turn to one agent: it hears it once its current turn ends (at once if it is idle), "
                    + "as its first turn, or, yielded or stopped, it starts again to hear it.",
                    "POST /workspaces/api/agents/{id}/prompt with {text, compactFirst}. " + Doors.CONTROL},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents prompt 00000000-0000-4000-8000-0000000000a1 'Check the rounding first.'",
                    "  qits agents prompt --work qits-123 'The ticket moved to REVIEW.'",
                    "  qits agents prompt --work qits-123 --compact-first 'Start on the second half.'",
                    "",
                    "- A blank text is refused before anything goes out; a removed agent is HTTP 409 AGENT_REMOVED."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    public static class PromptCommand extends OnAgent {

        @CommandLine.Parameters(index = "0", paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @CommandLine.Parameters(index = "1", paramLabel = "<text>", arity = "0..1", description = "The prompt.")
        String text;

        @CommandLine.Option(names = "--compact-first",
                description = "Have the harness compact its context before it hears the prompt.")
        boolean compactFirst;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            AgentRef.Given given = ref.split(agent, text);
            String prompt = AgentViews.required(given.second(), "The prompt is empty. Say something.");
            String id = agent(context, given.agent());
            JsonNode answer = options.api(context).promptAgent(id, prompt, compactFirst);
            if (json) {
                Json.print(context.out(), answer);
            } else {
                AgentViews.printDelivery(context.out(), answer, "Agent " + SafeText.line(id) + " was not told.");
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "interrupt", mixinStandardHelpOptions = true,
            description = {"Interrupt the agent's current turn: ESC into its running harness, as a person at its "
                    + "terminal would press it. How it runs does not change.",
                    "POST /workspaces/api/agents/{id}/interrupt, no body. " + Doors.CONTROL},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents interrupt --work qits-123",
                    "",
                    "- HTTP 409 AGENT_NOT_RUNNING (no running session) or AGENT_NOT_INTERRUPTIBLE (no harness, not a "
                            + "terminal, not started yet); HTTP 503 AGENT_DAEMON_UNREACHABLE. Nothing was sent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    public static class InterruptCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            String id = agent(context, agent);
            JsonNode answer = options.api(context).agentVerb(id, "interrupt");
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Agent " + SafeText.line(id) + ": "
                    + (answer.path("interrupted").asBoolean(false) ? "interrupted." : "not interrupted."));
            if (!text(answer, "detail").isEmpty()) {
                context.out().println(SafeText.line(text(answer, "detail")));
            }
            return 0;
        }
    }

    /** stop, restart and yield: no body, each answered with the agent as it now is. */
    abstract static class RunVerb extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        abstract String verb();

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            String id = agent(context, agent);
            JsonNode answer = options.api(context).agentVerb(id, verb());
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Agent " + SafeText.line(id) + ": " + cell(answer, "runState", 20) + ".");
            return 0;
        }
    }

    @CommandLine.Command(name = "stop", mixinStandardHelpOptions = true,
            description = {"Stop the agent: STOPPED. A placed agent's harness is stopped on its daemon, and its "
                    + "worktree and slot stay; a queued one gives up its place. Nothing places, resumes or drives a "
                    + "stopped agent; a wait that fires for it is heard once it is restarted or prompted.",
                    "POST /workspaces/api/agents/{id}/stop, no body. " + Doors.CONTROL},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents stop 00000000-0000-4000-8000-0000000000a1",
                    "",
                    "- An agent already STOPPED stays so. A removed agent is HTTP 409 AGENT_REMOVED."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    public static class StopCommand extends RunVerb {
        @Override
        String verb() {
            return "stop";
        }
    }

    @CommandLine.Command(name = "restart", mixinStandardHelpOptions = true,
            description = {"Restart the agent's harness: a RUNNING agent's is stopped and started again on its "
                    + "session; a STOPPED or YIELDED agent goes back to the scheduler as YIELDED.",
                    "POST /workspaces/api/agents/{id}/restart, no body. " + Doors.CONTROL},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents restart --work qits-123",
                    "",
                    "- HTTP 409 AGENT_REMOVED, or AGENT_NOT_STARTED for a queued agent; HTTP 503 "
                            + "AGENT_DAEMON_UNREACHABLE when a running agent's daemon does not answer."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    public static class RestartCommand extends RunVerb {
        @Override
        String verb() {
            return "restart";
        }
    }

    @CommandLine.Command(name = "yield", mixinStandardHelpOptions = true,
            description = {"Give up the agent's slot: YIELDED. The slot is free at once and the harness keeps "
                    + "running; anything the agent should hear brings it back.",
                    "POST /workspaces/api/agents/{id}/yield, no body. " + Doors.YIELD},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents yield 00000000-0000-4000-8000-0000000000a1",
                    "",
                    "- Only a running agent yields: HTTP 409 AGENT_NOT_RUNNING otherwise."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    public static class YieldCommand extends RunVerb {
        @Override
        String verb() {
            return "yield";
        }
    }

    @CommandLine.Command(name = "discard", mixinStandardHelpOptions = true,
            description = {"Remove an agent: its worktrees, credential and draft go. --force removes it anyway.",
                    "POST /workspaces/api/agents/{id}/discard with {\"force\": false|true}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents discard 00000000-0000-4000-8000-0000000000a1",
                    "  qits agents discard --work qits-123 --force",
                    "",
                    "- Without --force the service refuses (HTTP 409) an agent whose worktrees hold work that never "
                            + "reached the git host (AGENT_HAS_LEFTOVER_WORK), whose daemon does not answer "
                            + "(AGENT_DAEMON_UNREACHABLE) or that is still busy (AGENT_BUSY). --force removes it "
                            + "anyway and throws the leftover work away."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    public static class DiscardCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @CommandLine.Option(names = "--force", description = "Remove it despite leftover work or a daemon that does "
                + "not answer.")
        boolean force;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            options.json();
            return AgentViews.discard(context, options, agent(context, agent), force);
        }
    }

    // --- its sessions ---

    @CommandLine.Command(name = "sessions", mixinStandardHelpOptions = true,
            description = {"The agent's harness sessions, oldest first, read off the shared harness volume: a "
                    + "removed agent's too. Each with when it started and ended, its messages and its subagents.",
                    "GET /workspaces/api/agents/{id}/sessions. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents sessions 00000000-0000-4000-8000-0000000000a1",
                    "",
                    "- `qits agents transcript <agent> <session>` reads one of them."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (HTTP 404: no such agent), cannot be reached, or "
                    + "--work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class SessionsCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            JsonNode answer = options.api(context).agentSessions(agent(context, agent));
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> sessions = entries(answer, "sessions", null);
            if (sessions.isEmpty()) {
                context.out().println("No sessions.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode session : sessions) {
                rows.add(List.of(cell(session, "sessionId", 40), Table.time(text(session, "startedAt")),
                        Table.time(text(session, "endedAt")), cell(session, "messageCount", 10),
                        String.valueOf(session.path("subagents").size())));
            }
            Table.print(context.out(), "", List.of("SESSION", "STARTED", "ENDED", "MESSAGES", "SUBAGENTS"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "transcript", mixinStandardHelpOptions = true,
            description = {"One of the agent's sessions, turn by turn: who said what, the tools it used named. A "
                    + "removed agent's sessions answer too.",
                    "GET /workspaces/api/agents/{id}/sessions/{sessionId}/transcript. With -o json, the session's raw "
                            + "JSON lines as the service answers them. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents transcript 00000000-0000-4000-8000-0000000000a1 "
                    + "00000000-0000-4000-8000-0000000000d1",
                    "  qits agents transcript --work qits-123 00000000-0000-4000-8000-0000000000d1 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (HTTP 404: no such agent, or a session that is not "
                    + "its), cannot be reached, or --work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class TranscriptCommand extends OnAgent {

        @CommandLine.Parameters(index = "0", paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @CommandLine.Parameters(index = "1", paramLabel = "<session>", arity = "0..1",
                description = "The session's id, as `qits agents sessions` shows it.")
        String session;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            AgentRef.Given given = ref.split(agent, session);
            String sessionId = AgentViews.required(given.second(), "Name the session: its id, as `qits agents "
                    + "sessions` shows it.");
            JsonNode answer = options.api(context).agentTranscript(agent(context, given.agent()), sessionId);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<String> lines = new ArrayList<>();
            answer.path("lines").forEach(line -> lines.add(line.asText()));
            AgentViews.printTurns(context.out(), lines);
            return 0;
        }
    }

    // --- its daemon, through the agent proxy ---

    @CommandLine.Command(name = "chat", mixinStandardHelpOptions = true,
            description = {"Read the agent's conversation: its harness command's transcript, in order. The agent's "
                    + "Chat tab, without the live socket.",
                    "GET /workspaces/agent/{id}/log?channel=TRANSCRIPT: the agent's last command, found by the "
                            + "service. With -o json, the transcript as the daemon answers it. " + Doors.AGENT_PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents chat 00000000-0000-4000-8000-0000000000a1",
                    "  qits agents chat --work qits-123 -o json",
                    "",
                    "- An agent waiting for a slot is HTTP 409: it has no daemon to ask."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ChatCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            JsonNode answer = options.api(context).agentLog(agent(context, agent));
            if (json) {
                Json.print(context.out(), answer);
            } else {
                AgentViews.printChat(context.out(), answer);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "attach", mixinStandardHelpOptions = true,
            description = {"Attach this terminal to the agent's harness terminal: what you type goes to the harness, "
                    + "what it prints comes here. The agent's Terminal tab in the landing app.",
                    "Ctrl-] detaches and leaves the harness running; Ctrl-C and every other key go to it. The "
                            + "window's size goes along. A dropped connection attaches again after 0.5, 1, 2, 4 and "
                            + "4 s. With stdin not a terminal it only streams what the harness prints.",
                    "WS /workspaces/agent/{id}/terminal: the agent's running command on the daemon of the slot it is "
                            + "placed in now. " + Doors.AGENT_PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents attach 00000000-0000-4000-8000-0000000000a1",
                    "  qits agents attach --work qits-123",
                    "",
                    "- An agent that is not RUNNING is refused (HTTP 409): it has no terminal.",
                    "- Not an MCP tool: it needs a terminal and runs until the harness ends."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The harness ended, you detached (Ctrl-]), or it was stopped (SIGINT, SIGTERM); only "
                    + "the first ends the harness.",
                    "1:The platform refused the socket, the connection dropped and five attempts to attach again "
                            + "failed, or --work names no active agent or several.",
                    HelpText.USAGE})
    @TuiCommand(interaction = Interaction.TERMINAL)
    public static class AttachCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        /** The caller's terminal; a test hands its own. */
        LocalTerminal.Factory terminals = LocalTerminal::of;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand.refuseWithoutATerminal();
            String id = agent(context, agent);
            WorkspacesApi api = options.api(context);
            return WorkspacesCommand.attach(context, api, api.agentTerminalSocket(id), TerminalAttach.Subject.agent(id),
                    terminals);
        }
    }

    @CommandLine.Command(name = "files", mixinStandardHelpOptions = true,
            subcommands = {FilesListCommand.class, FilesReadCommand.class, FilesDetectCommand.class},
            description = {"The agent's worktree, as its Files tab shows it: the tree, one file, and the frameworks "
                    + "and test links the tab draws."})
    public static class FilesCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The agent's worktree: without --path the whole tree, every gitignored directory a stub "
                    + "with its entry count; with --path that directory, one level deep.",
                    "GET /workspaces/agent/{id}/files[?path=]. " + Doors.AGENT_PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents files list 00000000-0000-4000-8000-0000000000a1",
                    "  qits agents files list --work qits-123 --path node_modules"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class FilesListCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @CommandLine.Option(names = "--path", paramLabel = "<dir>",
                description = "A directory of the worktree, relative to its root. Default: the whole tree.")
        String path;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            String dir = path == null || path.isBlank() ? null : path.strip();
            JsonNode answer = options.api(context).agentFiles(agent(context, agent), dir);
            if (json) {
                Json.print(context.out(), answer);
            } else {
                AgentViews.printFiles(context.out(), answer);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "read", mixinStandardHelpOptions = true,
            description = {"One file of the agent's worktree, its text. A binary file, or one over the daemon's 2 MB "
                    + "cap, is not shown.",
                    "GET /workspaces/agent/{id}/files/content?path=. In the table form each line is made safe for a "
                            + "terminal (a tab becomes a space); -o json keeps the text exact. " + Doors.AGENT_PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents files read 00000000-0000-4000-8000-0000000000a1 src/main/java/app/Login.java",
                    "  qits agents files read --work qits-123 README.md"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (HTTP 404: the path names nothing), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class FilesReadCommand extends OnAgent {

        @CommandLine.Parameters(index = "0", paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @CommandLine.Parameters(index = "1", paramLabel = "<path>", arity = "0..1",
                description = "The file, relative to the worktree's root.")
        String path;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            AgentRef.Given given = ref.split(agent, path);
            String file = AgentViews.required(given.second(), "Name the file: its path in the worktree.");
            JsonNode answer = options.api(context).agentFile(agent(context, given.agent()), file);
            if (json) {
                Json.print(context.out(), answer);
            } else {
                AgentViews.printFile(context.out(), file, answer);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "detect", mixinStandardHelpOptions = true,
            description = {"The frameworks the agent's worktree holds (each with its member paths) and which tests "
                    + "reach each source file: the Files tab's footer and its test links.",
                    "GET /workspaces/agent/{id}/detection. " + Doors.AGENT_PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents files detect 00000000-0000-4000-8000-0000000000a1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (the message names the status), cannot be "
                    + "reached, or --work names no active agent or several.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class FilesDetectCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = options.json();
            JsonNode answer = options.api(context).agentDetection(agent(context, agent));
            if (json) {
                Json.print(context.out(), answer);
            } else {
                AgentViews.printDetection(context.out(), answer);
            }
            return 0;
        }
    }

    // --- the prompt draft ---

    @CommandLine.Command(name = "draft", mixinStandardHelpOptions = true,
            subcommands = {DraftShowCommand.class, DraftSaveCommand.class, DraftDiscardCommand.class},
            description = {"An agent's saved prompt draft: what the prompt box on its Chat tab holds, kept on the "
                    + "service so it survives a reload and another device."})
    public static class DraftCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"The agent's saved prompt draft: its text and when it was written. An agent with none "
                    + "says so.",
                    "GET /workspaces/api/agents/{id}/prompt-draft (HTTP 404: nothing was composed). " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents draft show 00000000-0000-4000-8000-0000000000a1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class DraftShowCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            options.json();
            return AgentViews.draftShow(context, options, agent(context, agent));
        }
    }

    @CommandLine.Command(name = "save", mixinStandardHelpOptions = true,
            description = {"Save the agent's prompt draft, as typing in its prompt box does. The draft holds the text "
                    + "and no picked file ranges.",
                    "PUT /workspaces/api/agents/{id}/prompt-draft with {content: '{\"text\":<text>,\"picked\":[]}', "
                            + "serializedPrompt: <text, trimmed>}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents draft save 00000000-0000-4000-8000-0000000000a1 'Check the rounding.'",
                    "  qits agents draft save --work qits-123 'Check the rounding.'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DraftSaveCommand extends OnAgent {

        @CommandLine.Parameters(index = "0", paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @CommandLine.Parameters(index = "1", paramLabel = "<text>", arity = "0..1", description = "The draft's text.")
        String text;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            options.json();
            AgentRef.Given given = ref.split(agent, text);
            if (given.second() == null) {
                throw new CliFailure("Give the draft's text.", CliFailure.USAGE);
            }
            return AgentViews.draftSave(context, options, agent(context, given.agent()), given.second());
        }
    }

    @CommandLine.Command(name = "discard", mixinStandardHelpOptions = true,
            description = {"Delete the agent's saved prompt draft: Discard on a restored draft.",
                    "DELETE /workspaces/api/agents/{id}/prompt-draft. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agents draft discard 00000000-0000-4000-8000-0000000000a1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DraftDiscardCommand extends OnAgent {

        @CommandLine.Parameters(paramLabel = "<agent>", arity = "0..1", description = AGENT)
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            options.json();
            return AgentViews.draftDiscard(context, options, agent(context, agent));
        }
    }
}
