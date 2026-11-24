package eu.wohlben.qits.cli.access.help;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code qits help guide} (qits-1679): the platform's development guide, as qits-projects serves it
 * now ({@code GET /projects/api/guide}). Nothing of it is baked into the binary.
 * <p>
 * <b>Which variant.</b> {@code --for} names one. Without it, a workstation session asks for {@code
 * workstation}; a token home ({@code QITS_TOKEN}) or in-platform mode names none, and the service
 * picks the variant from the token's roles and claims (the desk's {@code qits:desk}, a refinement
 * room's {@code entity} claim, else an agent). The CLI does not read the token itself: a workspace
 * token is opaque, and only the service sees what it carries.
 * <p>
 * <b>The hash.</b> qits-1734: the answer may carry a {@code hash}, the first 16 hex characters of
 * the SHA-256 of the markdown's UTF-8 bytes. When it does, the normal output leads with a
 * {@code guide-hash: <hash>} line before the markdown, and {@code --hash-only} prints that line
 * alone — a cheap way to notice the guide changed without paying for the markdown every time. An
 * older service sends no {@code hash}: the normal output is then markdown alone, as before, and
 * {@code --hash-only} refuses.
 */
@TuiCommand(effect = Effect.READ)
@CommandLine.Command(name = "guide", mixinStandardHelpOptions = true,
        description = {"Print the platform's development guide: how to develop on the qits platform, as markdown.",
                "The platform serves it, so it is always the current text. Without --for it prints the variant for "
                        + "where it runs: on a workstation signed in with `qits login`, the workstation guide (sign-in, "
                        + "Git, registries, builds, releases); inside the platform (QITS_TOKEN, or in-platform mode), "
                        + "the brief of the agent, project agent, desk or refinement room the token belongs to, with the development "
                        + "flow.",
                "When the service sends a guide-hash, the output leads with a `guide-hash: <hash>` line before the "
                        + "markdown; an older service that sends none prints the markdown alone. --hash-only prints "
                        + "just that line, to check cheaply whether the guide changed."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits help guide", "  qits help guide --list", "  qits help guide --for agent",
                "  qits help guide --for project-agent",
                "  qits help guide --hash-only",
                "",
                "- Variants: workstation, agent, project-agent, desk, refinement. --for picks one, to read what another "
                        + "holder reads. project-agent is a project's long-running agent (qits-2520).",
                "- Reads GET /projects/api/guide (with ?for=<variant>), and GET /projects/api/guide/variants for "
                        + "--list.",
                "- --hash-only prints `guide-hash: <hash>` alone and nothing else; it fails when the service sends "
                        + "no hash. --list and --hash-only do not mix."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE,
                "1:The platform refused, or (with --hash-only) the service sends no guide-hash.",
                "2:Used wrongly (for example an unknown --for, or --list with --hash-only), not signed in, or the "
                        + "session ended."})
public class GuideCommand extends PlatformCommand {

    static final List<String> VARIANTS = List.of("workstation", "agent", "project-agent", "desk", "refinement");

    @CommandLine.Option(names = "--for", paramLabel = "<variant>",
            description = "The variant to print: workstation, agent, project-agent, desk or refinement. Default: the one for where "
                    + "this runs.")
    String variant;

    @CommandLine.Option(names = "--list", description = "List the variants instead.")
    boolean list;

    @CommandLine.Option(names = "--hash-only",
            description = "Print only the guide-hash line (`guide-hash: <hash>`), not the guide: compare it with "
                    + "the hash you last read and re-read the guide only when it differs.")
    boolean hashOnly;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>",
            description = "The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the "
                    + "session's idp address with `idp` swapped for `projects`.")
    String projectsUrl;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (list && variant != null) {
            throw new CliFailure("Give --list or --for, not both.", CliFailure.USAGE);
        }
        if (list && hashOnly) {
            throw new CliFailure("Give --list or --hash-only, not both.", CliFailure.USAGE);
        }
        ProjectsApi api = ProjectsApi.connect(context, projectsUrl);
        if (list) {
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode one : api.developmentGuideVariants().path("variants")) {
                rows.add(List.of(ProjectsApi.text(one, "name"), ProjectsApi.text(one, "description")));
            }
            Table.print(context.out(), "", List.of("VARIANT", "WHAT IT IS FOR"), rows);
            return 0;
        }
        JsonNode answer = api.developmentGuide(wanted(context.mode()));
        String hash = ProjectsApi.text(answer, "hash");
        if (hashOnly) {
            if (hash.isBlank()) {
                throw new CliFailure("The service sends no guide-hash: it predates qits-1734.", CliFailure.FAILED);
            }
            context.out().println("guide-hash: " + hash);
            return 0;
        }
        if (!hash.isBlank()) {
            context.out().println("guide-hash: " + hash);
        }
        String markdown = ProjectsApi.text(answer, "markdown");
        context.out().print(markdown.endsWith("\n") ? markdown : markdown + "\n");
        return 0;
    }

    /** The variant to ask for: --for, else workstation on a workstation, else none (the service picks). */
    String wanted(Mode mode) throws CliFailure {
        if (variant != null) {
            String named = variant.strip().toLowerCase(Locale.ROOT);
            if (!VARIANTS.contains(named)) {
                throw new CliFailure("--for must be one of " + String.join(", ", VARIANTS) + ", not '" + variant
                        + "'.", CliFailure.USAGE);
            }
            return named;
        }
        return mode == Mode.WORKSTATION ? "workstation" : null;
    }
}
