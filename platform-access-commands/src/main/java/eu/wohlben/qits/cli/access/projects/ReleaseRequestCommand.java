package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.ReleaseRequestSource;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "release-request", mixinStandardHelpOptions = true,
        subcommands = {ReleaseRequestCommand.ListCommand.class, ReleaseRequestCommand.CreateCommand.class,
                ReleaseRequestCommand.JoinCommand.class, ReleaseRequestCommand.MoveCommand.class,
                ReleaseRequestCommand.WithdrawCommand.class,
                ReleaseRequestCommand.ApproveCommand.class, ReleaseRequestCommand.DeclineCommand.class,
                ReleaseRequestCommand.RegenerateCommand.class, ReleaseRequestCommand.OverrideCommand.class,
                ReleaseRequestCommand.RerunCommand.class},
        description = {"Release requests: the one way to release a repository. list shows them (of the whole project, "
                        + "or of one repository), create "
                        + "asks for a branch to be released, join adds a branch to an open request, move "
                        + "takes one branch off it onto the request queued after it, withdraw "
                        + "ends a request that must not ship, approve lets the fold a request is on go ahead, "
                        + "decline sends it back, regenerate runs one release output again, override lifts the "
                        + "upstream-in-flight gate for one upstream request that is itself still building, and rerun "
                        + "fires one pipeline phase again: QA or PUBLISH re-fire that phase's CI run, DEPLOY "
                        + "redeploys the already-released version.",
                "A request folds main and its branches into one commit, and the builds of that commit are its gate. "
                        + "States: PENDING (waiting for its builds), READY, RELEASED (the tag is cut, waiting on its "
                        + "remaining gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build "
                        + "was red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), "
                        + "WITHDRAWN, OBSOLETE (a later request for the repository superseded this one before it "
                        + "finished).",
                "create, join, withdraw, approve and decline print the request's quality gates as the platform "
                        + "sends them: one row per gate (kind, position, state, detail), and, under a gate that is "
                        + "waiting on something specific, one indented line per check it is waiting for (such as "
                        + "the upstream request an upstream-in-flight gate names). An older platform with no "
                        + "qualityGates[] yet gets the deprecated gates table instead (kind, state, reason)."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push. "
                        + "Fix the branch and push; do not open a new request.",
                "- When a red build was the platform's fault and not the code's (a flaked container, a registry "
                        + "that was down), retry that run with `qits ci retry <run id>`: it builds the same commit "
                        + "again. `qits ci runs --release-request <id>` finds the request's runs. A red "
                        + "automation's run (a dependency bump, golden recordings, the entity diagram, screenshot "
                        + "baselines, cli-tapes, estate pins) is re-run with `qits release-request regenerate "
                        + "--kind <kind>` instead, not `qits ci retry`. Do not open a new request, and do not "
                        + "withdraw this one.",
                "- When one branch alone holds a shared request back (it conflicts, its check is red, it is not "
                        + "ready), move it with `move`: do not force-push it back to main, and do not hold the "
                        + "others. An agent whose own branch blocks a shared request moves it itself.",
                "- `withdraw` is only for a request that must not ship: the change is wrong, or nobody wants it any "
                        + "more. WITHDRAWN is final.",
                "- approve and decline judge one fold: the request's merged sha. A push that folds the request "
                        + "again leaves the decision behind, and the new fold waits for a decision of its own.",
                "- decline is not withdraw. A declined request goes REJECTED and comes back with the next push to "
                        + "one of its branches; WITHDRAWN is final.",
                "- A release request carries a list of approval requirements, each naming the roles that may "
                        + "satisfy it: person, agent (an agent of the request's own project) or agent@<project "
                        + "slug>. approve and decline print them, with whether each is satisfied and by whom, "
                        + "before reading the request and again after deciding. A person holding qits:admin may "
                        + "satisfy a requirement admitting person; an agent may satisfy one admitting agent or "
                        + "agent@<its project>, including a requirement on the very request it asked for, unless "
                        + "that requirement excludes the request's own authors. Asserted identity headers, "
                        + "service clients and qits:system are refused (HTTP 403) either way.",
                "- --project and --repository may come before or after the command. list, approve and decline work "
                        + "without --repository; approve and decline find the request in the project."})
public class ReleaseRequestCommand implements Runnable {

    /** The tag is cut but the request is still open; version and merged sha are already stamped. */
    static final String RELEASED = "RELEASED";

    /**
     * Open means every state but this one, WITHDRAWN and OBSOLETE. The one the default list leaves
     * out on top of those two: the service adds the last 10 of them.
     */
    static final String FINALIZED = "FINALIZED";

    /** The states that take no more branches: the tag is already cut, or the request is done for. */
    static final Set<String> CLOSED = Set.of(RELEASED, FINALIZED, "WITHDRAWN", "OBSOLETE");

    /** The list query for every request, of every state. */
    static final String ALL = "all";

    @CommandLine.Mixin
    ProjectsOptions options;

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @Completes(RepositorySource.class)
    @CommandLine.Option(names = "--repository", paramLabel = "<repository>", scope = CommandLine.ScopeType.INHERIT,
            description = "The repository: its id or name.")
    String repository;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** Checks the names, then finds the repository. */
    private Target target(CliContext context) throws CliFailure, InterruptedException {
        String wantedRepository = RepositoriesCommand.required(repository, "Name the repository: --repository <id or name>.");
        InProject in = inProject(context);
        JsonNode repo = in.api().repository(in.project(), wantedRepository);
        return new Target(in.api(), in.label(), text(repo, "id"), text(repo, "name"));
    }

    private record Target(ProjectsApi api, String projectLabel, String repoId, String repoName) {
    }

    /**
     * Checks --project, then finds the project. What every command needs before it knows a
     * repository, and all that list, approve and decline need: the project's doors take no
     * repository.
     */
    private InProject inProject(CliContext context) throws CliFailure, InterruptedException {
        String wantedProject = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
        ProjectsApi api = ProjectsApi.connect(context, options.projectsUrl);
        JsonNode found = api.project(wantedProject);
        return new InProject(api, found, ProjectsApi.projectLabel(found));
    }

    private record InProject(ProjectsApi api, JsonNode project, String label) {
    }

    /**
     * The repository's request that {@code wanted} names: its id ({@code <repository>-rr-<n>}, any
     * case), its uuid, or the start of the uuid. It reads every request, of every state. This lookup
     * is also what keeps a command off another repository's request.
     */
    private static JsonNode find(Target target, String wanted) throws CliFailure, InterruptedException {
        return ReleaseRequestRefs.find(target.api().releaseRequests(target.repoId(), ALL).path("requests"), wanted,
                target.repoName(), "`qits release-request --project " + target.projectLabel() + " --repository "
                        + target.repoName() + " list --state all` shows them.");
    }

    /** The state the list showed, when it already takes no more branches; else every state that would. */
    private static String settledState(JsonNode found) {
        String listed = text(found, "state").toUpperCase(Locale.ROOT);
        return CLOSED.contains(listed) ? listed : "RELEASED, FINALIZED, WITHDRAWN or OBSOLETE";
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List release requests: of the whole project, or of one repository with --repository.",
                    "Without --repository it shows every repository's requests, as the landing app does: the open "
                            + "ones and the last 10 FINALIZED, most recently moved first, with a REPOSITORY and an "
                            + "APPROVAL column.",
                    "With --repository it shows that repository's open requests only. Open means every state but "
                            + "FINALIZED, WITHDRAWN and OBSOLETE.",
                    "--state asks for other ones in both forms. The ID column shows the request's id, "
                            + "<repository>-rr-<n>, which `join` and `withdraw` take. -o json also has its uuid (id); "
                            + "the commands take that too.",
                    "The FREEZE column is the soft feature freeze: counted branches of the threshold in force "
                            + "(1/3), 3/3 frozen once the request takes no more new branches, off when the "
                            + "threshold is 0, and queued for the request that waits behind a frozen one. A dash "
                            + "means the platform sent no freeze."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits list",
                    "  qits release-request --project qits list --state all --limit 20",
                    "  qits release-request --project qits --repository qits-ci-service list",
                    "  qits release-request --project qits --repository qits-ci-service list --state all -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE|all>",
                description = "Only this state (PENDING, READY, RELEASED, FINALIZED, REJECTED, FAILED, CONFLICTED, "
                        + "WITHDRAWN, OBSOLETE), or all for every request. Default: the open ones (and, for the "
                        + "whole project, the last 10 FINALIZED).")
        String state;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "Show at most this many requests, the most recent first. Default: all that came back.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (limit != null && limit < 1) {
                throw new CliFailure("--limit must be 1 or more.", CliFailure.USAGE);
            }
            boolean openOnly = state == null || state.isBlank();
            boolean wholeProject = parent.repository == null || parent.repository.isBlank();
            String scope;
            JsonNode answer;
            if (wholeProject) {
                InProject in = parent.inProject(context);
                scope = "project " + in.label();
                answer = in.api().projectReleaseRequests(text(in.project(), "id"), state);
            } else {
                Target target = parent.target(context);
                scope = target.repoName();
                answer = target.api().releaseRequests(target.repoId(), state);
                if (openOnly) {
                    answer = withoutFinalized(answer);
                }
            }
            answer = limited(answer, limit);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> requests = new ArrayList<>();
            answer.path("requests").forEach(requests::add);
            if (requests.isEmpty()) {
                context.out().println(openOnly
                        ? "No open release requests for " + scope + "."
                        : "No release requests in state " + state.strip() + " for " + scope + ".");
                return 0;
            }
            if (wholeProject) {
                Table.print(context.out(), "", List.of("ID", "REPOSITORY", "STATE", "FREEZE", "APPROVAL", "PRIORITY",
                                "ITEMS", "VERSION", "UPDATED"),
                        requests.stream().map(r -> List.of(
                                Table.cell(ReleaseRequestRefs.label(r), 60),
                                Table.cell(text(r, "repoName"), 40),
                                Table.cell(text(r, "state"), 12),
                                Table.cell(freezeCell(r), 12),
                                Table.cell(text(r, "approvalState"), 12),
                                Table.cell(text(r, "priority"), 10),
                                Table.cell(ReleaseRequestRefs.items(r), 60),
                                Table.cell(text(r, "version"), 24),
                                Table.time(text(r, "updatedAt")))).toList());
                return 0;
            }
            Table.print(context.out(), "", List.of("ID", "STATE", "FREEZE", "PRIORITY", "ITEMS", "VERSION", "UPDATED"),
                    requests.stream().map(r -> List.of(
                            Table.cell(ReleaseRequestRefs.label(r), 60),
                            Table.cell(text(r, "state"), 12),
                            Table.cell(freezeCell(r), 12),
                            Table.cell(text(r, "priority"), 10),
                            Table.cell(ReleaseRequestRefs.items(r), 60),
                            Table.cell(text(r, "version"), 24),
                            Table.time(text(r, "updatedAt")))).toList());
            return 0;
        }

        /**
         * The service's default answer holds the open requests and the last 10 finalized. The
         * repository list shows open ones, so the finalized are dropped here, in both output forms.
         */
        static JsonNode withoutFinalized(JsonNode answer) {
            return keep(answer, Integer.MAX_VALUE, r -> !FINALIZED.equalsIgnoreCase(text(r, "state")));
        }

        /** The first {@code limit} requests, in both output forms; all of them without a limit. */
        static JsonNode limited(JsonNode answer, Integer limit) {
            return limit == null ? answer : keep(answer, limit, r -> true);
        }

        private static JsonNode keep(JsonNode answer, int most, Predicate<JsonNode> wanted) {
            ObjectNode copy = answer.isObject() ? ((ObjectNode) answer).deepCopy() : JsonNodeFactory.instance.objectNode();
            ArrayNode kept = copy.arrayNode();
            answer.path("requests").forEach(r -> {
                if (kept.size() < most && wanted.test(r)) {
                    kept.add(r);
                }
            });
            copy.set("requests", kept);
            return copy;
        }
    }

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Ask for a branch to be released once its builds are green.",
                    "The platform puts the branch where it belongs: in the repository's active request (opened "
                            + "if there is none), or, while that request is frozen, in the one request queued "
                            + "behind it. The first line printed says where the branch went; the request follows.",
                    "The soft feature freeze: an active request is frozen once it holds as many counted "
                            + "branches as the threshold. Counted are branches that name a work item (epics, "
                            + "tasks, tickets of any type, bug fixes included); main, maintenance/* and release/* "
                            + "never count. The threshold is the repository's .config/qits/release-requests.yml "
                            + "feature_freeze, else the project's .config/qits/project.yml "
                            + "release_requests.feature_freeze, else 3; 0 turns the freeze off."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service create --branch "
                            + "feature/log-view",
                    "  qits release-request --project qits --repository qits-ci-service create --branch main "
                            + "--priority HIGH",
                    "",
                    "- While the active request is frozen, ANY new branch (a feature, a bug fix, a maintenance "
                            + "bump) goes into the request queued behind it, opened if there is none. The queued "
                            + "request folds nothing and runs no gate until the active one has released or been "
                            + "withdrawn.",
                    "- To put a branch into the frozen request anyway, name it: `join --request <its id>`.",
                    "- A branch that is already on a request that has not released answers that request; it "
                            + "opens no second one. Check the id that comes back.",
                    "- On a project wrapper the answer can be a request somebody else opened. A red gate holds "
                            + "every branch on it.",
                    "- There is no summary to give: the request lists its items, one per branch somebody named, "
                            + "with the work item the branch names, and the people who asked for it.",
                    "- To add another branch to a request you have, use `join`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>", required = true,
                description = "The branch to release.")
        String branch;

        @CommandLine.Option(names = "--priority", paramLabel = "<priority>",
                description = "LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: the platform's (MEDIUM).")
        String priority;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (branch.isBlank()) {
                throw new CliFailure("--branch must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode answer = target.api().createReleaseRequest(target.repoId(), branch.strip(), priority);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printPlacement(context.out(), answer);
            printRequest(context.out(), answer.path("request"));
            return 0;
        }
    }

    @CommandLine.Command(name = "join", mixinStandardHelpOptions = true,
            description = {"Add a branch to an open release request.",
                    "The platform folds the request again with the branch and, if that makes a new commit, "
                            + "builds that commit. The first line printed says where the branch went; the request "
                            + "that came back follows.",
                    "Naming the request is how a branch gets past the soft feature freeze: a frozen request "
                            + "takes any branch you join to it by name, and the request marks that source as an "
                            + "explicit join. `create` would have queued it behind the frozen request instead."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service join --request qits-ci-service-rr-12 "
                            + "--branch feature/log-search",
                    "  qits release-request --project qits --repository qits-ci-service join --request qits-ci-service-rr-12 "
                            + "--branch feature/log-search --priority BLOCKING",
                    "",
                    "- Safe to repeat: a branch already on a request that has not released adds nothing and "
                            + "answers that request. With --priority it states that priority again; without, the "
                            + "branch keeps its priority.",
                    "- From RELEASED on a request takes no more branches (HTTP 409, and the message names the "
                            + "next request): join that one, or use `create`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class JoinCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the "
                        + "uuid.")
        String request;

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>", required = true,
                description = "The branch to add.")
        String branch;

        @CommandLine.Option(names = "--priority", paramLabel = "<priority>",
                description = "LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: MEDIUM for a new branch; "
                        + "a branch already on the request keeps its priority.")
        String priority;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || branch.isBlank()) {
                throw new CliFailure("--request and --branch must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            JsonNode answer;
            try {
                answer = target.api().joinReleaseRequest(target.repoId(), text(found, "id"), branch.strip(), priority);
            } catch (CliFailure refused) {
                throw explain(refused, found);
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printPlacement(context.out(), answer);
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /** The two refusals a join can expect get a sentence that says what to do next. */
        private static CliFailure explain(CliFailure refused, JsonNode found) {
            if (refused.status() == 409) {
                return new CliFailure("Release request " + ReleaseRequestRefs.name(found) + " is " + settledState(found)
                        + " and takes no more branches (HTTP 409). Join the next request the platform names, or "
                        + "use `qits release-request create`. " + refused.getMessage(), CliFailure.FAILED);
            }
            if (refused.status() == 404) {
                return new CliFailure("No such request or branch. " + refused.getMessage(), CliFailure.FAILED);
            }
            return refused;
        }
    }

    @CommandLine.Command(name = "withdraw", mixinStandardHelpOptions = true,
            description = {"Withdraw an open release request, so it does not ship.",
                    "WITHDRAWN is final: the request is not built or released again, and its branches are free. "
                            + "The next `create` for one of them opens a new request. It prints the request that "
                            + "came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service withdraw --request qits-ci-service-rr-12",
                    "  qits release-request --project qits --repository qits-ci-service withdraw --request qits-ci-service-rr-12 "
                            + "--reason \"The log view moves to qits-observability\"",
                    "",
                    "- Only for a request that must not ship. A gating build that was red because of the platform, "
                            + "not the code, runs again with `qits ci retry <run id>`. A REJECTED or CONFLICTED request "
                            + "comes back by itself when one of its branches gets a new push.",
                    "- Without --reason the platform writes who withdrew it.",
                    "- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request cannot be withdrawn (HTTP 409)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class WithdrawCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the "
                        + "uuid.")
        String request;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                description = "Why it must not ship, in a sentence. The request shows it as its detail. "
                        + "Default: the platform writes who withdrew it.")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank()) {
                throw new CliFailure("--request must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            JsonNode answer;
            try {
                answer = target.api().withdrawReleaseRequest(target.repoId(), text(found, "id"), reason);
            } catch (CliFailure refused) {
                throw explain(refused, found);
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /** The two refusals a withdraw can expect get a sentence of their own. */
        private static CliFailure explain(CliFailure refused, JsonNode found) {
            if (refused.status() == 409) {
                return new CliFailure("Release request " + ReleaseRequestRefs.name(found) + " is " + settledState(found)
                        + " already and cannot be withdrawn (HTTP 409).", CliFailure.FAILED);
            }
            if (refused.status() == 404) {
                return new CliFailure("No such request. " + refused.getMessage(), CliFailure.FAILED);
            }
            return refused;
        }
    }

    @CommandLine.Command(name = "move", mixinStandardHelpOptions = true,
            description = {"Move one branch off an open release request, onto the request queued after it.",
                    "For a source that alone holds a shared request back: it conflicts with the others, its "
                            + "check is red, or it is not ready. The request folds again without it and goes back "
                            + "to PENDING; the branch joins the request queued after this one (opened queued "
                            + "behind it if there is none), keeping its priority, author and feature stamp, and "
                            + "releases from there once this request has released or been withdrawn.",
                    "This replaces force-pushing the branch back to main, withdrawing every other branch with "
                            + "it, or holding them all. The branch's owner is told, and both requests list the "
                            + "move with who made it and why.",
                    "The first line printed says where the branch went; the request that now holds it follows. "
                            + "-o json prints both requests (from, to)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service move --request qits-ci-service-rr-12 "
                            + "--branch ticket/qits-1660 --reason \"It conflicts with ticket/qits-1658.\"",
                    "",
                    "- Who may: qits:admin and qits:admin-agent, and the project's delivery desk, any branch; an "
                            + "agent only its own branch, one its token may push (HTTP 403 for another's).",
                    "- Refused (HTTP 409): a request that is RELEASED, FINALIZED, WITHDRAWN or OBSOLETE; a "
                            + "request that is itself queued; the default branch and a released tag, which every "
                            + "request folds; and the request's only branch, which `withdraw` is for.",
                    "- A branch that is not on the request is HTTP 404."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one, or an "
                            + "empty --reason), not signed in, or the session ended."})
    public static class MoveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request the branch is on: its id as list shows it (qits-ci-service-rr-12), its "
                        + "uuid, or the start of the uuid.")
        String request;

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>", required = true,
                description = "The branch to move.")
        String branch;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>", required = true,
                description = "Why it moves, in a sentence: both requests list it, and the branch's owner reads it.")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || branch.isBlank() || reason.isBlank()) {
                throw new CliFailure("--request, --branch and --reason must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            JsonNode answer;
            try {
                answer = target.api().moveReleaseRequestSource(target.repoId(), text(found, "id"), branch.strip(),
                        reason.strip());
            } catch (CliFailure refused) {
                throw explain(refused, found, branch.strip());
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println(branch.strip() + " moved from " + ReleaseRequestRefs.name(answer.path("from"))
                    + " to " + ReleaseRequestRefs.name(answer.path("to")));
            printRequest(context.out(), answer.path("to"));
            return 0;
        }

        /** The refusals a move can expect, each with what to do instead. */
        private static CliFailure explain(CliFailure refused, JsonNode found, String branch) {
            String name = ReleaseRequestRefs.name(found);
            return switch (refused.status()) {
                case 400 -> new CliFailure("A move says why: give --reason a sentence (HTTP 400). "
                        + refused.getMessage(), CliFailure.USAGE);
                case 403 -> new CliFailure("You may not move " + branch + " (HTTP 403): an agent moves only its own "
                        + "branch. Ask the branch's own agent or the delivery desk. " + refused.getMessage(),
                        CliFailure.FAILED);
                case 404 -> new CliFailure("No such request, or " + branch + " is not a source of " + name
                        + " (HTTP 404). " + refused.getMessage(), CliFailure.FAILED);
                case 409 -> new CliFailure("Release request " + name + " cannot give up " + branch + " (HTTP 409). "
                        + refused.getMessage(), CliFailure.FAILED);
                default -> refused;
            };
        }
    }

    @CommandLine.Command(name = "override", mixinStandardHelpOptions = true,
            description = {"Lift the upstream-in-flight gate (epic qits-897) for one upstream request that is "
                            + "still building, so this request's QA may start without waiting for it.",
                    "The gate holds a fold while an open request upstream of this one, in another repository of "
                            + "the same project, is still building its own QA, publishing or releasing. Override "
                            + "tells the platform to stop waiting on exactly the named upstream request; any other "
                            + "upstream request the gate still names keeps holding the fold. It prints the request "
                            + "that came back.",
                    "Only a person's own CLI sign-in counts for this decision. An agent, a workspace credential or "
                            + "a service client is refused (HTTP 403), in the door's own words."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-frontend override "
                            + "--request qits-ci-frontend-rr-12 --upstream qits-ci-service-rr-7",
                    "  qits release-request --project qits --repository qits-ci-frontend override "
                            + "--request qits-ci-frontend-rr-12 --upstream qits-ci-service-rr-7 "
                            + "--note \"qits-ci-service-rr-7 is a doc-only change; safe to go ahead.\"",
                    "",
                    "- --upstream is the upstream request exactly as the gate's check names it: its id "
                            + "(qits-ci-service-rr-7) or its whole uuid.",
                    "- HTTP 409 means the held request is not pending a verdict of its own to override "
                            + "(it has already got one, or it is not open), or the override is moot because the "
                            + "named upstream request is not building any more. Read the request again.",
                    "- HTTP 404 means no such held request or no such upstream request."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class OverrideCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The held request: its id as list shows it (qits-ci-frontend-rr-12), its uuid, or the "
                        + "start of the uuid.")
        String request;

        @CommandLine.Option(names = "--upstream", paramLabel = "<id>", required = true,
                description = "The upstream request the gate is waiting for: its id (qits-ci-service-rr-7), in "
                        + "any case, or its whole uuid.")
        String upstream;

        @CommandLine.Option(names = "--note", paramLabel = "<text>",
                description = "Why, in a sentence, for the record. Default: none.")
        String note;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || upstream.isBlank()) {
                throw new CliFailure("--request and --upstream must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            JsonNode answer = target.api().overrideReleaseRequestUpstream(target.repoId(), text(found, "id"),
                    upstream.strip(), note);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }
    }

    /**
     * What approve and decline share: the request found in the project by its logical id or uuid,
     * the fold being judged, the note, and the one door call. Neither needs --repository: the
     * project's door answers the request with its repository, which is all the decision door needs.
     * <p>
     * <b>The fold is the decision's subject.</b> The service records a decision against a merged
     * sha and refuses one whose sha is not the request's current fold (409), so a decision can never
     * land on a fold its maker did not look at. --sha is that check in full. Without it, the command
     * sends the sha it has just read, which only narrows the race to the moment between the read and
     * the write; the help says so. A request with no fold has nothing to judge, and is refused here
     * before anything is sent.
     * <p>
     * <b>The refusals are the service's.</b> Who may decide (a person, by their own sign-in) and when
     * (a fold that is current, a request that waits for one) are the door's rules, and its sentences
     * say which one refused; they reach the person as {@link eu.wohlben.qits.cli.access.platform.PlatformClient}
     * worded them, with no second sentence on top.
     */
    abstract static class DecisionCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-221), in any case, or its "
                        + "whole uuid. It is found in the project, so --repository is not needed.")
        String request;

        @CommandLine.Option(names = "--sha", paramLabel = "<merged sha>",
                description = "The fold you judged: the request's merged sha, as the landing app or -o json shows "
                        + "it. This is the strict form: the platform refuses the decision (HTTP 409) if the request "
                        + "is on another fold by then. Without it the command judges whatever fold the request is "
                        + "on at the moment it is read.")
        String sha;

        @CommandLine.Option(names = "--note", paramLabel = "<text>",
                description = "Why, in a sentence, for the record. A decline's note becomes the request's detail, "
                        + "the first thing whoever answers the decline reads. Default: none.")
        String note;

        /** The door's verb, as a sentence says it: approve, decline. */
        abstract String verb();

        /** What the first line of the table form says was done: Approved, Declined. */
        abstract String done();

        abstract JsonNode decide(ProjectsApi api, String repoId, String requestId, String mergedSha, String note)
                throws CliFailure, InterruptedException;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || (sha != null && sha.isBlank())) {
                throw new CliFailure("--request and --sha must not be empty.", CliFailure.USAGE);
            }
            InProject in = parent.inProject(context);
            JsonNode found = in.api().projectReleaseRequest(text(in.project(), "id"), request.strip())
                    .path("request");
            String name = ReleaseRequestRefs.name(found);
            String fold = sha != null ? sha.strip() : text(found, "mergedSha");
            if (fold.isBlank()) {
                throw new CliFailure("Release request " + name + " has not been folded yet, so there is no fold to "
                        + verb() + ".", CliFailure.FAILED);
            }
            if (!json) {
                printRequirements(context.out(), "Requirements before " + verb() + ":", found);
            }
            JsonNode answer = decide(in.api(), text(found, "repoId"), text(found, "id"), fold, note);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println(done() + " " + name + " at " + fold + ".");
            printRequest(context.out(), answer.path("request"));
            printRequirements(context.out(), "Requirements after " + verb() + ":", answer.path("request"));
            return 0;
        }
    }

    @CommandLine.Command(name = "approve", mixinStandardHelpOptions = true,
            description = {"Approve the fold a release request is on, so it may release once its gates are green.",
                    "The approval is for one fold, the request's merged sha. A push that folds the request again "
                            + "leaves it behind, and the new fold needs an approval of its own. It prints the "
                            + "request's approval requirements, with their admitted roles and whether each is "
                            + "satisfied, before the decision and again after, then the request that came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits approve --request qits-ci-service-rr-221 "
                            + "--sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e",
                    "  qits release-request --project qits approve --request qits-ci-service-rr-221 "
                            + "--note \"Read the diff: the pin moves to the export release.\"",
                    "",
                    "- Give --sha to approve exactly the fold you read. Without it the command approves whatever "
                            + "fold the request is on when it reads it.",
                    "- A person holding qits:admin may satisfy a requirement that admits person. An agent may "
                            + "satisfy a requirement that admits agent or agent@<its project>, including one on "
                            + "the very request it asked for, unless that requirement excludes the request's own "
                            + "authors. Asserted identity headers, service clients and qits:system are refused "
                            + "(HTTP 403) either way; the message names each open requirement and the roles it "
                            + "admits.",
                    "- HTTP 409 means the request is on another fold than the one sent (the message names the "
                            + "one it is on now), has no fold, has concluded or is being released, or every "
                            + "requirement at the fold is already satisfied. Read it again and judge the fold it "
                            + "is on."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (the message names the status and says why), cannot be reached, or "
                            + "the request has no fold yet, so there is nothing to approve.",
                    HelpText.USAGE})
    public static class ApproveCommand extends DecisionCommand {

        @Override
        String verb() {
            return "approve";
        }

        @Override
        String done() {
            return "Approved";
        }

        @Override
        JsonNode decide(ProjectsApi api, String repoId, String requestId, String mergedSha, String note)
                throws CliFailure, InterruptedException {
            return api.approveReleaseRequest(repoId, requestId, mergedSha, note);
        }
    }

    @CommandLine.Command(name = "decline", mixinStandardHelpOptions = true,
            description = {"Decline the fold a release request is on, so it does not release.",
                    "The request goes REJECTED and comes back by itself when one of its branches gets a new push, "
                            + "which makes a new fold to judge. It is not withdraw: WITHDRAWN is final. It prints "
                            + "the request's approval requirements, with their admitted roles and whether each is "
                            + "satisfied, before the decision and again after, then the request that came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits decline --request qits-ci-service-rr-221 "
                            + "--sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e "
                            + "--note \"The README promises an approval policy nobody has signed off yet.\"",
                    "",
                    "- Give --sha to decline exactly the fold you read. Without it the command declines whatever "
                            + "fold the request is on when it reads it.",
                    "- To stop a request for good, use `withdraw` instead.",
                    "- A person holding qits:admin may satisfy a requirement that admits person. An agent may "
                            + "satisfy a requirement that admits agent or agent@<its project>, including one on "
                            + "the very request it asked for, unless that requirement excludes the request's own "
                            + "authors. Asserted identity headers, service clients and qits:system are refused "
                            + "(HTTP 403) either way; the message names each requirement it concerns and the "
                            + "roles it admits.",
                    "- HTTP 409 means the request is on another fold than the one sent (the message names the "
                            + "one it is on now), has no fold, has concluded or is being released, or its fold has "
                            + "no approval requirement at all. Read it again and judge the fold it is on."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (the message names the status and says why), cannot be reached, or "
                            + "the request has no fold yet, so there is nothing to decline.",
                    HelpText.USAGE})
    public static class DeclineCommand extends DecisionCommand {

        @Override
        String verb() {
            return "decline";
        }

        @Override
        String done() {
            return "Declined";
        }

        @Override
        JsonNode decide(ProjectsApi api, String repoId, String requestId, String mergedSha, String note)
                throws CliFailure, InterruptedException {
            return api.declineReleaseRequest(repoId, requestId, mergedSha, note);
        }
    }

    @CommandLine.Command(name = "regenerate", mixinStandardHelpOptions = true,
            description = {"Generate one release output again on an open release request: the entity diagram, the "
                    + "screenshot baselines, a CLI's tapes, the dependency bump, the estate pins, or the golden "
                    + "recordings (maven or npm).",
                    "QA checks the entity diagram, the screenshot baselines and a CLI's tapes and fails when they "
                            + "are stale; it "
                            + "does not regenerate them. This command asks for the run. The automation commits the "
                            + "output to maintenance/automations/<kind>/<request id>, the request folds again with "
                            + "it, and QA then runs again. It prints the run's id."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-landing-app regenerate "
                            + "--kind screenshot-baselines",
                    "  qits release-request --project qits --repository qits-projects-service regenerate "
                            + "--request qits-projects-service-rr-12 --kind entity-diagram",
                    "  qits release-request --project qits --repository qits-platform-access-monorepo regenerate "
                            + "--kind cli-tapes",
                    "",
                    "- Without --request it takes the repository's one open request. A REJECTED request is open: "
                            + "a red QA is the usual reason to regenerate.",
                    "- 409 means a run of this kind is already active for the request, the request is not open or "
                            + "has no fold, or bumping is off. 404 means the platform knows no such kind or "
                            + "request. 503 means qits-maintenance cannot be reached; nothing ran. 504 means it was "
                            + "asked and did not answer in time; whether it ran is unknown, so check `qits "
                            + "maintenance automations --request <id>` before pressing again.",
                    "- `qits maintenance automations --request <id>` shows each kind's state, and "
                            + "`qits maintenance bump <run id>` how the run went."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example no --request when the repository has no open request or more "
                            + "than one), not signed in, or the session ended."})
    public static class RegenerateCommand extends PlatformCommand {

        /** The kinds the platform runs today; the service is the authority, this list is for the sentences. */
        static final String KINDS = "entity-diagram, entity-exposure, screenshot-baselines, cli-tapes, "
                + "dependency-bump, estate-pins, golden-recordings-maven and golden-recordings-npm";

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>",
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start "
                        + "of the uuid. Default: the repository's one open request.")
        String request;

        @CommandLine.Option(names = "--kind", paramLabel = "<kind>", required = true,
                description = "What to generate: entity-diagram, entity-exposure, screenshot-baselines, cli-tapes, "
                        + "dependency-bump, estate-pins, golden-recordings-maven or golden-recordings-npm.")
        String kind;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (kind.isBlank() || (request != null && request.isBlank())) {
                throw new CliFailure("--kind and --request must not be empty.", CliFailure.USAGE);
            }
            String wantedKind = kind.strip().toLowerCase(Locale.ROOT);
            Target target = parent.target(context);
            JsonNode found = request == null ? onlyOpen(target) : find(target, request.strip());
            String requestId = text(found, "id");
            String requestName = ReleaseRequestRefs.name(found);
            JsonNode answer;
            try {
                answer = target.api().rerunReleaseRequestAutomation(target.repoId(), requestId, wantedKind);
            } catch (CliFailure refused) {
                throw explain(refused, requestName, ReleaseRequestRefs.label(found), wantedKind);
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            String runId = text(answer, "id");
            context.out().println("Asked for " + wantedKind + " on release request " + requestName + ": run " + runId);
            context.out().println("The request folds again when the output is committed to maintenance/automations/"
                    + wantedKind + "/" + requestId + "; QA then runs again.");
            context.out().println("Follow the run with `qits maintenance bump " + runId + "`.");
            return 0;
        }

        /** The repository's one request that is still open; anything else needs --request. */
        private static JsonNode onlyOpen(Target target) throws CliFailure, InterruptedException {
            List<JsonNode> open = new ArrayList<>();
            target.api().releaseRequests(target.repoId(), null).path("requests").forEach(r -> {
                if (!CLOSED.contains(text(r, "state").toUpperCase(Locale.ROOT))) {
                    open.add(r);
                }
            });
            if (open.isEmpty()) {
                throw new CliFailure("Repository " + target.repoName() + " has no open release request. "
                        + "Name one with --request.", CliFailure.USAGE);
            }
            if (open.size() > 1) {
                throw new CliFailure("Repository " + target.repoName() + " has more than one open release request: "
                        + open.stream().map(r -> ReleaseRequestRefs.label(r) + " (" + text(r, "state") + ")")
                                .collect(Collectors.joining(", "))
                        + ". Name one with --request.", CliFailure.USAGE);
            }
            return open.getFirst();
        }

        /** The four refusals the door names get a sentence that says what to do next. */
        private static CliFailure explain(CliFailure refused, String requestName, String requestLabel, String kind) {
            return switch (refused.status()) {
                case 404 -> new CliFailure("The platform knows no kind '" + kind + "' or no release request "
                        + requestName + " (HTTP 404). The kinds are " + KINDS + ". " + refused.getMessage(),
                        CliFailure.FAILED);
                case 409 -> new CliFailure("The platform will not run " + kind + " on release request " + requestName
                        + " now (HTTP 409): a run of it may be active already, the request may not be open or have "
                        + "no fold, or bumping may be off. `qits maintenance automations --request " + requestLabel
                        + "` shows the kind's state. " + refused.getMessage(), CliFailure.FAILED);
                case 503 -> new CliFailure("qits-maintenance cannot be reached or is not set up, so nothing ran "
                        + "(HTTP 503). Try again later. " + refused.getMessage(), CliFailure.FAILED);
                case 504 -> new CliFailure("qits-maintenance did not answer in time, so whether " + kind
                        + " was started on release request " + requestName + " is unknown (HTTP 504). "
                        + "`qits maintenance automations --request " + requestLabel + "` shows it; do not press "
                        + "again until it says. " + refused.getMessage(), CliFailure.FAILED);
                default -> refused;
            };
        }
    }

    @CommandLine.Command(name = "rerun", mixinStandardHelpOptions = true,
            description = {"Rerun one phase of a release request's pipeline.",
                    "QA and PUBLISH re-fire that phase's CI run. DEPLOY redeploys the request's already-released "
                            + "version, the remedy for a deployment that got rolled back.",
                    "The request's own state does not change here: the result of the rerun arrives later, on "
                            + "the event bus, the same way the first run did. This command only asks for it and "
                            + "prints the request as it stands now, which still shows the previous run's outcome."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service rerun --request "
                            + "qits-ci-service-rr-12 --phase QA",
                    "  qits release-request --project qits --repository qits-ci-service rerun --request "
                            + "qits-ci-service-rr-12 --phase deploy",
                    "",
                    "- --phase is QA, PUBLISH or DEPLOY, in any case; the platform refuses anything else (HTTP "
                            + "400).",
                    "- 404 means the platform knows no such release request.",
                    "- 409 means the platform, or qits-ci for a QA or PUBLISH rerun, refuses to run that phase "
                            + "now; the message says why.",
                    "- 503 means qits-ci cannot be reached, or this repository is not set up with it; nothing ran."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one, or a "
                            + "--phase that is none of QA, PUBLISH or DEPLOY), not signed in, or the session ended."})
    public static class RerunCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the "
                        + "start of the uuid.")
        String request;

        @CommandLine.Option(names = "--phase", paramLabel = "<phase>", required = true,
                description = "The phase to rerun: QA, PUBLISH or DEPLOY, in any case.")
        String phase;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || phase.isBlank()) {
                throw new CliFailure("--request and --phase must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            String requestName = ReleaseRequestRefs.name(found);
            String wantedPhase = phase.strip().toUpperCase(Locale.ROOT);
            JsonNode answer;
            try {
                answer = target.api().rerunReleasePipelinePhase(target.repoId(), text(found, "id"), wantedPhase);
            } catch (CliFailure refused) {
                throw explain(refused, requestName);
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /**
         * 400 and 404 get a sentence of their own. 409 and 503 are qits-ci's or the service's own
         * words, printed exactly as they came: which of them refused, and why, is not something this
         * command can say better than the door that decided it.
         */
        private static CliFailure explain(CliFailure refused, String requestName) {
            return switch (refused.status()) {
                case 400 -> new CliFailure("The phase must be QA, PUBLISH or DEPLOY (HTTP 400). "
                        + refused.getMessage(), CliFailure.FAILED);
                case 404 -> new CliFailure("No such release request " + requestName + " (HTTP 404). "
                        + refused.getMessage(), CliFailure.FAILED);
                default -> refused;
            };
        }
    }

    static void printRequest(PrintStream out, JsonNode request) {
        out.println("Release request " + ReleaseRequestRefs.name(request));
        List<List<String>> rows = new ArrayList<>();
        if (!text(request, "qualifiedId").isBlank()) {
            rows.add(List.of("uuid", Table.cell(text(request, "id"), 200)));
        }
        rows.addAll(List.of(
                List.of("repository", Table.cell(text(request, "repoName"), 200)),
                List.of("state", Table.cell(text(request, "state"), 200))));
        String freeze = freeze(request.path("featureFreeze"));
        if (freeze != null) {
            rows.add(List.of("freeze", Table.cell(freeze, 200)));
        }
        String behind = ReleaseRequestRefs.label(text(request, "queuedBehindQualifiedId"), text(request, "queuedBehind"));
        String next = ReleaseRequestRefs.label(text(request, "queuedNextQualifiedId"), text(request, "queuedNext"));
        if (!behind.isBlank()) {
            rows.add(List.of("queued", Table.cell("behind " + behind, 200)));
        } else if (!next.isBlank()) {
            rows.add(List.of("next", Table.cell(next, 200)));
        }
        rows.addAll(List.of(
                List.of("superseded by", Table.cell(supersededBy(request), 200)),
                List.of("priority", Table.cell(text(request, "priority"), 200)),
                List.of("participants", Table.cell(participants(request), 200)),
                List.of("approval", Table.cell(text(request, "approvalState"), 200)),
                List.of("merged sha", Table.cell(text(request, "mergedSha"), 200)),
                List.of("version", Table.cell(text(request, "version"), 200)),
                List.of("detail", Table.cell(text(request, "detail"), 200)),
                List.of("created", Table.time(text(request, "createdAt"))),
                List.of("updated", Table.time(text(request, "updatedAt")))));
        Table.print(out, "  ", null, rows);
        printItems(out, request);
        printGates(out, request);
        List<List<String>> sources = new ArrayList<>();
        boolean counted = false;
        for (JsonNode s : request.path("sources")) {
            counted |= s.has("feature");
        }
        boolean freezeColumn = counted;
        request.path("sources").forEach(s -> {
            List<String> row = new ArrayList<>(List.of(
                    Table.cell(text(s, "kind"), 20),
                    Table.cell(text(s, "name"), 60),
                    Table.cell(text(s, "ref"), 80),
                    Table.cell(s.path("implicit").asBoolean() ? "implicit" : "named", 10)));
            if (freezeColumn) {
                row.add(Table.cell(sourceFreeze(s), 30));
            }
            row.add(Table.cell(text(s, "priority"), 10));
            row.add(Table.cell(text(s, "addedBy"), 40));
            sources.add(row);
        });
        if (!sources.isEmpty()) {
            out.println("Sources:");
            Table.print(out, "  ", freezeColumn
                    ? List.of("KIND", "NAME", "REF", "HOW", "FREEZE", "PRIORITY", "ADDED BY")
                    : List.of("KIND", "NAME", "REF", "HOW", "PRIORITY", "ADDED BY"), sources);
        }
        printMoves(out, request);
    }

    /**
     * The branches moved into or out of the request (qits-1665): one row per move, with who moved
     * it and why. An older platform sends no sourceMoves, and a request nothing moved through has
     * none; nothing is printed then.
     */
    static void printMoves(PrintStream out, JsonNode request) {
        List<List<String>> rows = new ArrayList<>();
        request.path("sourceMoves").forEach(m -> rows.add(List.of(
                Table.cell(text(m, "branch"), 60),
                Table.cell(text(m, "fromQualifiedId"), 40),
                Table.cell(text(m, "toQualifiedId"), 40),
                Table.cell(text(m, "movedBy"), 40),
                Table.time(text(m, "movedAt")),
                Table.cell(text(m, "reason"), 120))));
        if (!rows.isEmpty()) {
            out.println("Moves:");
            Table.print(out, "  ", List.of("BRANCH", "FROM", "TO", "BY", "AT", "REASON"), rows);
        }
    }

    /** Everyone who asked for the request, comma-joined; blank (a dash) from an older platform. */
    static String participants(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.path("participants").forEach(p -> {
            if (!p.asText("").isBlank()) {
                names.add(p.asText());
            }
        });
        return String.join(", ", names);
    }

    /**
     * What the request releases (qits-1673): one line per item, the work item's id and title where
     * the branch names one, else the item's label. An older platform sends no items; nothing is
     * printed for them then, and the sources below still say which branches are on it.
     */
    static void printItems(PrintStream out, JsonNode request) {
        List<List<String>> rows = new ArrayList<>();
        request.path("items").forEach(item -> {
            JsonNode work = item.path("workItem");
            String line = work.isObject() && !text(work, "qualifiedId").isBlank()
                    ? (text(work, "qualifiedId") + " " + text(work, "title")).strip()
                    : text(item, "label");
            if (line.isBlank()) {
                line = text(item, "source");
            }
            rows.add(List.of(Table.cell(line, 200)));
        });
        if (!rows.isEmpty()) {
            out.println("Items:");
            Table.print(out, "  ", null, rows);
        }
    }

    /**
     * Where create or join put the branch (epic qits-1580), in the platform's own sentence, as the
     * first line. An older platform sends no placement, and then nothing is printed for it.
     */
    static void printPlacement(PrintStream out, JsonNode answer) {
        String detail = Table.cell(text(answer.path("placement"), "detail"), 300);
        if (!detail.equals("-")) {
            out.println(detail);
        }
    }

    /**
     * The request's freeze row: "1 of 3 counted branches (platform default)", with ", frozen" once
     * it holds the threshold, or "off (repository)" when the level in force set it to 0. Null when
     * the platform sent no freeze, so the row is left out.
     */
    static String freeze(JsonNode freeze) {
        if (!freeze.isObject()) {
            return null;
        }
        String level = freezeLevel(text(freeze, "source"));
        int threshold = freeze.path("threshold").asInt();
        if (threshold <= 0) {
            return "off" + level;
        }
        return freeze.path("features").asInt() + " of " + threshold + " counted branches"
                + (freeze.path("frozen").asBoolean() ? ", frozen" : "") + level;
    }

    /** The level that set the threshold, as the freeze row names it. */
    private static String freezeLevel(String source) {
        return switch (source.toUpperCase(Locale.ROOT)) {
            case "" -> "";
            case "PLATFORM" -> " (platform default)";
            default -> " (" + source.toLowerCase(Locale.ROOT) + ")";
        };
    }

    /** The list's FREEZE cell: queued, off, 1/3 or 3/3 frozen; a dash when the platform sent no freeze. */
    static String freezeCell(JsonNode request) {
        if (!text(request, "queuedBehind").isBlank() || !text(request, "queuedBehindQualifiedId").isBlank()) {
            return "queued";
        }
        JsonNode freeze = request.path("featureFreeze");
        if (!freeze.isObject()) {
            return "";
        }
        int threshold = freeze.path("threshold").asInt();
        if (threshold <= 0) {
            return "off";
        }
        return freeze.path("features").asInt() + "/" + threshold + (freeze.path("frozen").asBoolean() ? " frozen" : "");
    }

    /** A source's FREEZE cell: whether it counts toward the freeze, and whether it was joined past it. */
    private static String sourceFreeze(JsonNode source) {
        String counts = source.path("feature").asBoolean() ? "counted" : "";
        if (source.path("explicitJoin").asBoolean()) {
            return counts.isEmpty() ? "explicit join" : counts + ", explicit join";
        }
        return counts;
    }

    /** The request that superseded this one, by its logical id, else its short uuid; blank when none. */
    private static String supersededBy(JsonNode request) {
        return ReleaseRequestRefs.label(text(request, "supersededByQualifiedId"), text(request, "supersededBy"));
    }

    /**
     * The request's gates (epic qits-897, ticket qits-904). {@code qualityGates[]} is the current
     * shape: one row per gate (kind, position, state, detail), and, directly under a gate whose
     * {@code checks[]} is not empty, one indented line per check (name, state, detail) &mdash; what
     * it is waiting for, such as the upstream request an {@code upstream-in-flight} gate names
     * (qits-913). The check rows are interleaved into the same list of rows the gate rows are, right
     * after the gate they belong to, and printed with one {@link Table#print} call so every column
     * lines up across gates and checks alike. Falls back to the deprecated {@code gates[]} table
     * (kind, state, reason) when the answer has no {@code qualityGates}, which is an older service
     * that has not grown the field yet.
     */
    private static void printGates(PrintStream out, JsonNode request) {
        JsonNode qualityGates = request.path("qualityGates");
        if (qualityGates.isArray() && !qualityGates.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            qualityGates.forEach(g -> {
                rows.add(List.of(
                        Table.cell(text(g, "kind"), 20),
                        Table.cell(text(g, "position"), 20),
                        Table.cell(text(g, "state"), 12),
                        Table.cell(text(g, "detail"), 80)));
                g.path("checks").forEach(c -> rows.add(List.of(
                        Table.cell("  " + text(c, "name"), 60),
                        "",
                        Table.cell(text(c, "state"), 12),
                        Table.cell(text(c, "detail"), 80))));
            });
            out.println("Quality gates:");
            Table.print(out, "  ", List.of("KIND", "POSITION", "STATE", "DETAIL"), rows);
            return;
        }
        List<List<String>> gates = new ArrayList<>();
        request.path("gates").forEach(g -> gates.add(List.of(
                Table.cell(text(g, "kind"), 20),
                Table.cell(text(g, "state"), 12),
                Table.cell(text(g, "detail"), 80))));
        if (!gates.isEmpty()) {
            out.println("Gates:");
            Table.print(out, "  ", List.of("KIND", "STATE", "REASON"), gates);
        }
    }

    /**
     * The request's approval requirements (epic qits-1038): one row per requirement, with its
     * admitted roles, whether the request's own authors are excluded, and whether it is satisfied
     * and by whom. Prints nothing when {@code approvalRequirements} is absent or empty, which is
     * both an older service (the field does not exist yet) and a request with no requirement at
     * all; either way there is nothing to show.
     */
    static void printRequirements(PrintStream out, String heading, JsonNode request) {
        JsonNode requirements = request.path("approvalRequirements");
        if (!requirements.isArray() || requirements.isEmpty()) {
            return;
        }
        out.println(heading);
        List<List<String>> rows = new ArrayList<>();
        requirements.forEach(requirement -> rows.add(List.of(
                Table.cell(text(requirement, "id"), 40),
                Table.cell(text(requirement, "reason"), 70),
                Table.cell(admits(requirement), 30),
                requirement.path("excludesAuthors").asBoolean() ? "yes" : "no",
                requirement.path("satisfied").asBoolean() ? "yes" : "no",
                Table.cell(satisfiedBy(requirement), 60))));
        Table.print(out, "  ", List.of("ID", "REASON", "ADMITS", "EXCLUDES AUTHORS", "SATISFIED", "SATISFIED BY"),
                rows);
    }

    /** {@code admits}, joined for one cell: "person", "person, agent", "person, agent@qits". */
    private static String admits(JsonNode requirement) {
        List<String> roles = new ArrayList<>();
        requirement.path("admits").forEach(role -> roles.add(role.asText()));
        return String.join(", ", roles);
    }

    /** Who satisfied the requirement, holding which roles, and when; a dash when nobody has. */
    private static String satisfiedBy(JsonNode requirement) {
        JsonNode by = requirement.path("satisfiedBy");
        if (!by.isObject()) {
            return "-";
        }
        List<String> roles = new ArrayList<>();
        by.path("roles").forEach(role -> roles.add(role.asText()));
        return text(by, "approver") + " (" + String.join(", ", roles) + ") at " + Table.time(text(by, "at"));
    }
}
