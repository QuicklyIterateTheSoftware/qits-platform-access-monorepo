package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits wait branch}: wait on one's own work by the branch it is on, not by the release
 * request that happens to carry it now (epic qits-1676). A request id goes stale when the request
 * refolds or is superseded; the branch does not. qits-projects publishes {@code
 * BranchDeliveryChanged} for every step a branch takes, and this preset selects on it.
 * <p>
 * Before declaring it reads the branch's deliveries once, so a step that already happened is said
 * at once instead of waited on forever. An event that lands between the read and the declaration
 * is caught by qits-workspaces' look-back (qits-1668).
 */
@CommandLine.Command(name = "branch", mixinStandardHelpOptions = true,
        description = {"Wait until a branch is joined, released or live, or fails, in one repository or in every "
                + "repository that knows it. Prefer this to `wait release-request` for your own work: a request "
                + "id goes stale when the request refolds or is superseded, the branch does not.",
                "The same as --event BranchDeliveryChanged with branch=<branch> in every group, repository=<name> "
                        + "with --repository and projectId=<uuid> with --project. With --repository the target is "
                        + "the repository's step: joined is JOINED or MOVED, released is RELEASED or LIVE, live is "
                        + "LIVE. Without it the target is everywhere, the least advanced step across the branch's "
                        + "repositories: joined is JOINED, RELEASED or LIVE, released is RELEASED or LIVE, live is "
                        + "LIVE. Every target also fires early on a step FAILED, CONFLICTED or WITHDRAWN, so a "
                        + "broken delivery wakes you. failed waits for a step FAILED or CONFLICTED.",
                "It reads the branch's deliveries first. When the target already holds it says so, declares "
                        + "nothing and exits 0; when only an early step (FAILED, CONFLICTED or WITHDRAWN) holds "
                        + "it says so, declares nothing and exits 1. With -o json either way it prints what it "
                        + "read, with declared added as false. When no repository knows the branch yet it warns "
                        + "and declares anyway, and so it does when the read fails."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits wait branch ticket/qits-123 --until released",
                "  qits wait branch ticket/qits-123 --until joined --repository qits-ci-service",
                "  qits wait branch ticket/qits-123 --until live --project qits --timeout 1d",
                "  qits wait branch ticket/qits-123 --until failed --repository qits-ci-service",
                "",
                "- Give the branch's whole name, as you pushed it: the wait matches it exactly.",
                "- --repository takes the repository's name, as the event carries it.",
                "- A branch can carry on into a new request when its request releases without it or is "
                        + "superseded (MOVED): the wait does not care which request it is on."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:The wait is declared and printed, or the branch already reached the target; it says "
                + "which. A wait that comes back already MATCHED is 0 too, for the same reason.",
                "1:An early step (FAILED, CONFLICTED or WITHDRAWN) already holds, so nothing was declared; the "
                        + "platform refused, or cannot be reached; or the wait came back BROKEN already (the "
                        + "message says why).",
                "2:Used wrongly (no holder, no branch, an --until that is not joined, released, live or failed, a "
                        + "project qits does not know), not signed in, or the session ended."})
public class BranchWait extends PlatformCommand {

    static final String EVENT = "BranchDeliveryChanged";
    static final List<String> UNTIL = List.of("joined", "released", "live", "failed");
    /** The steps that end a wait for any target but failed early: a delivery that broke. */
    static final List<String> EARLY = List.of("FAILED", "CONFLICTED", "WITHDRAWN");

    @CommandLine.ParentCommand
    WaitCommand parent;

    @CommandLine.Parameters(paramLabel = "<branch>", description = "The branch, by its whole name: ticket/qits-123.")
    String branch;

    @CommandLine.Option(names = "--until", paramLabel = "joined|released|live|failed", required = true,
            description = "What to wait for: joined (in a release request), released (tagged), live (deployed, or "
                    + "released for a library) or failed (a red fold or a conflict).")
    String until;

    @CommandLine.Option(names = "--repository", paramLabel = "<name>",
            description = "Only this repository's delivery, by name. Default: every repository that knows the "
                    + "branch, by their least advanced step.")
    String repository;

    @CommandLine.Option(names = "--project", paramLabel = "<project>",
            description = "Only this project's deliveries: its id, slug or name. Needed when several projects know "
                    + "the branch.")
    String project;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>",
            description = "The projects service's base URL, without /projects, where the deliveries and the project "
                    + "are read. Default: QITS_PROJECTS_URL, else derived from the idp address.")
    String projectsUrl;

    @CommandLine.Mixin
    WaitCommand.Declaration declaration;

    /** What --until asks of one scope, and what wakes it early. */
    record Target(String path, List<String> steps, List<String> early) {
    }

    /**
     * The table of epic qits-1676: with a repository the repository's own {@code step}; without one
     * {@code everywhere}, but for failed, which is a step of some repository either way.
     */
    static Target target(String until, boolean oneRepository) {
        return switch (until) {
            case "joined" -> oneRepository ? new Target("step", List.of("JOINED", "MOVED"), EARLY)
                    : new Target("everywhere", List.of("JOINED", "RELEASED", "LIVE"), EARLY);
            case "released" -> new Target(oneRepository ? "step" : "everywhere", List.of("RELEASED", "LIVE"), EARLY);
            case "live" -> new Target(oneRepository ? "step" : "everywhere", List.of("LIVE"), EARLY);
            case "failed" -> new Target("step", List.of("FAILED", "CONFLICTED"), List.of());
            default -> throw new IllegalArgumentException(until);
        };
    }

    /** The groups: the common conditions, then one value of the target's path, then one early step, each a group. */
    static List<List<WhenGrammar.Condition>> groups(String branch, String repository, String projectId, Target target) {
        List<WhenGrammar.Condition> common = new ArrayList<>();
        common.add(new WhenGrammar.Condition("branch", WhenGrammar.Op.EXACT, branch));
        if (repository != null) {
            common.add(new WhenGrammar.Condition("repository", WhenGrammar.Op.EXACT, repository));
        }
        if (projectId != null) {
            common.add(new WhenGrammar.Condition("projectId", WhenGrammar.Op.EXACT, projectId));
        }
        List<List<WhenGrammar.Condition>> groups = new ArrayList<>();
        for (String step : target.steps()) {
            groups.add(with(common, target.path(), step));
        }
        for (String step : target.early()) {
            groups.add(with(common, "step", step));
        }
        return groups;
    }

    private static List<WhenGrammar.Condition> with(List<WhenGrammar.Condition> common, String path, String value) {
        List<WhenGrammar.Condition> group = new ArrayList<>(common);
        group.add(new WhenGrammar.Condition(path, WhenGrammar.Op.EXACT, value));
        return List.copyOf(group);
    }

    static String label(String branch, String until, String repository) {
        return "branch " + branch + " " + until + (repository == null ? " everywhere" : " in " + repository);
    }

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = json(parent.output);
        String name = branch == null ? "" : branch.strip();
        if (name.isEmpty()) {
            throw new CliFailure("Name the branch: its whole name, like ticket/qits-123.", CliFailure.USAGE);
        }
        if (name.length() > WhenGrammar.MAX_VALUE_LENGTH) {
            throw new CliFailure("The branch's name is longer than " + WhenGrammar.MAX_VALUE_LENGTH
                    + " characters.", CliFailure.USAGE);
        }
        String wanted = until == null ? "" : until.strip().toLowerCase(Locale.ROOT);
        if (!UNTIL.contains(wanted)) {
            throw new CliFailure("--until must be joined, released, live or failed, not '"
                    + SafeText.line(until == null ? "" : until.strip()) + "'.", CliFailure.USAGE);
        }
        String repo = repository == null || repository.isBlank() ? null : repository.strip();
        // Refuses without a holder, or with a timeout it cannot read, before anything goes out.
        parent.holder(context);
        WaitTimeout.iso(declaration.timeout != null ? declaration.timeout : parent.declaration.timeout);
        ProjectsApi projects = ProjectsApi.connect(context, projectsUrl);
        String projectId = project == null || project.isBlank() ? null : text(projects.project(project.strip()), "id");
        Target target = target(wanted, repo != null);

        JsonNode read = look(context, projects, name, repo, projectId);
        if (read != null) {
            Already already = already(read, name, repo, target);
            if (already != null) {
                if (json) {
                    WaitCommand.printJson(context.out(), declaredFalse((ObjectNode) read, already.line()));
                }
                if (already.early()) {
                    context.err().println(already.line() + ": nothing was declared and nothing will wake you; "
                            + "fix and push, then wait again");
                    return CliFailure.FAILED;
                }
                if (!json) {
                    context.out().println(already.line());
                }
                return 0;
            }
            if (!read.path("deliveries").elements().hasNext()) {
                context.err().println("no repository knows " + SafeText.line(name) + " yet; waiting anyway");
            }
        }
        return parent.declare(context, json, EVENT, groups(name, repo, projectId, target), declaration,
                label(name, wanted, repo));
    }

    /** The deliveries as they stand, or null when they cannot be read: the wait is declared all the same. */
    private static JsonNode look(CliContext context, ProjectsApi projects, String branch, String repository,
                                 String projectId) throws InterruptedException {
        try {
            JsonNode read = projects.branchDeliveries(branch, repository, projectId);
            return read != null && read.isObject() ? read : null;
        } catch (CliFailure failure) {
            context.err().println("cannot read where " + SafeText.line(branch) + " stands ("
                    + SafeText.line(failure.getMessage()) + "); waiting anyway");
            return null;
        }
    }

    /**
     * What the read says has already happened: the line to print, and whether it is only an early
     * step (one that ends the wait early, rather than the target itself) that holds. An early step
     * declares nothing and refuses; the target itself declares nothing and is done.
     */
    record Already(String line, boolean early) {
    }

    /**
     * What the read says has already happened, as an {@link Already}, or null when nothing has: the
     * target first, then an early step. With a repository both are that repository's step; without
     * one the target is {@code everywhere} (failed excepted) and an early step is any repository's.
     */
    static Already already(JsonNode read, String branch, String repository, Target target) {
        List<JsonNode> deliveries = new ArrayList<>();
        for (JsonNode delivery : read.path("deliveries")) {
            if (repository == null || repository.equals(text(delivery, "repository"))
                    || repository.equals(text(delivery, "repoId"))) {
                deliveries.add(delivery);
            }
        }
        String safeBranch = SafeText.line(branch);
        if (target.path().equals("everywhere")) {
            String everywhere = text(read, "everywhere");
            if (target.steps().contains(everywhere)) {
                return new Already("branch " + safeBranch + " already " + SafeText.line(everywhere) + " everywhere",
                        false);
            }
        } else {
            JsonNode hit = first(deliveries, target.steps());
            if (hit != null) {
                return new Already(reached(safeBranch, hit), false);
            }
        }
        JsonNode early = first(deliveries, target.early());
        return early == null ? null : new Already(reached(safeBranch, early), true);
    }

    /** {@code read}, copied, with {@code declared: false} and the line {@code -o json} owes as {@code already}. */
    private static ObjectNode declaredFalse(ObjectNode read, String already) {
        ObjectNode copy = read.deepCopy();
        copy.put("declared", false);
        copy.put("already", already);
        return copy;
    }

    private static JsonNode first(List<JsonNode> deliveries, List<String> steps) {
        return deliveries.stream().filter(d -> steps.contains(text(d, "step"))).findFirst().orElse(null);
    }

    private static String reached(String branch, JsonNode delivery) {
        return "branch " + branch + " already " + SafeText.line(text(delivery, "step")) + " in "
                + SafeText.line(text(delivery, "repository")) + " (" + SafeText.line(text(delivery, "qualifiedId"))
                + ")";
    }
}
