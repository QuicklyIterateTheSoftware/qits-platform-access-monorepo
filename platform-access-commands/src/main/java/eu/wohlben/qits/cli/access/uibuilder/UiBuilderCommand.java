package eu.wohlben.qits.cli.access.uibuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code qits ui-builder}: what the landing app's UI builder page reads, for the project's
 * ui-builder named agent (qits-2156). Every read is one of qits-artifacts' docs store, repository
 * {@code docs} ({@link DocsStore}): a frontend's frozen routes in {@code @routes/<application>} and a
 * service's golden masters and entity map in {@code @contracts/<application>}. Which site belongs to
 * which repository is read from the versions' metadata, as the page does ({@link Bundles}).
 * <p>
 * The version option is {@code --bundle-version}, not {@code --version}: the help mixin owns that
 * name, and the MCP catalog refuses a property called {@code version}.
 */
@CommandLine.Command(name = "ui-builder", mixinStandardHelpOptions = true,
        subcommands = {UiBuilderCommand.RoutesCommand.class, UiBuilderCommand.FreezeCommand.class,
                UiBuilderCommand.GoldenMastersCommand.class, UiBuilderCommand.EntitiesCommand.class},
        description = {"The reads for the project's ui-builder named agent (qits-2156): what the UI builder page "
                + "shows, read from the same place. routes prints a frontend's frozen routes (its manifest.json), "
                + "freeze one screenshot's entry in it or its frozen HTML, golden-masters a service's recorded "
                + "provider states or one recorded response, and entities a service's entity map.",
                "Everything comes from qits-artifacts' docs store, repository docs, under "
                        + "/artifacts/docs/docs: a frontend's release publishes @routes/<application> and a "
                        + "service's @contracts/<application>. Which site belongs to a repository is read from "
                        + "the git.repository.name its newest version records, as the page does. Without "
                        + "--bundle-version each command reads that newest version."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --output, --bundle-version and --artifacts-url may come before or after the command.",
                "- Reads only. The command sends the caller's own credential, as the UI builder page sends the person's.",
                "- A repository, screenshot, provider, state or operation the bundle does not have is exit 1, "
                        + "with one line naming it."})
public class UiBuilderCommand implements Runnable {

    @CommandLine.Option(names = "--artifacts-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else "
                    + "https://registry.qits.<domain>.")
    String artifactsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): a readable summary. json: the published document as it was read.")
    String output;

    @CommandLine.Option(names = "--bundle-version", paramLabel = "<version>", scope = CommandLine.ScopeType.INHERIT,
            description = "Read this version of the bundle instead of its newest: a version the site published, "
                    + "such as 2026.1113.154308.")
    String bundleVersion;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    DocsStore store(CliContext context) throws CliFailure, InterruptedException {
        return new DocsStore(new PlatformClient(context.credential()),
                PlatformUrls.artifacts(artifactsUrl, context.env(), context.idpUrl()));
    }

    String version() throws CliFailure {
        if (bundleVersion == null) {
            return null;
        }
        String value = bundleVersion.strip();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+-]*")) {
            throw new CliFailure("--bundle-version is a published version, such as 2026.1113.154308, not "
                    + SafeText.line(bundleVersion) + ". Nothing was sent.", CliFailure.USAGE);
        }
        return value;
    }

    static String required(String value, String option, String what) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure("Name the " + what + ": " + option + " <" + what + ">.", CliFailure.USAGE);
        }
        return value.strip();
    }

    /** A published file, or exit 1 naming it when the version does not hold it (or the site has no such version). */
    static JsonNode file(DocsStore store, Bundles.Bundle bundle, String path, String what)
            throws CliFailure, InterruptedException {
        try {
            return store.file(bundle.site(), bundle.version(), path);
        } catch (CliFailure failure) {
            throw missing(failure, bundle, path, what);
        }
    }

    static CliFailure missing(CliFailure failure, Bundles.Bundle bundle, String path, String what) {
        if (failure.status() == 404) {
            return new CliFailure(SafeText.line(bundle.site()) + " has no " + what + " at version "
                    + SafeText.line(bundle.version()) + " (" + SafeText.line(path) + ", HTTP 404).", CliFailure.FAILED);
        }
        return failure;
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

    static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }

    /** The site and version a summary is of, as its first lines. */
    static void printBundle(PrintStream out, Bundles.Bundle bundle) {
        out.println("SITE     " + SafeText.line(bundle.site()));
        out.println("VERSION  " + SafeText.line(bundle.version()));
    }

    // --- routes ---

    @CommandLine.Command(name = "routes", mixinStandardHelpOptions = true,
            description = {"A frontend repository's frozen routes: the manifest.json of the @routes/<application> "
                    + "site whose newest version was released from the repository. It holds the routes, every "
                    + "freeze (a screenshot's frozen HTML, its route, kind, viewport, color scheme and the "
                    + "provider states it recorded), the freezes by route, and the color schemes and outcomes.",
                    "Reads GET /artifacts/docs/docs, then GET /artifacts/docs/docs/{site} of each @routes/ site "
                            + "until one names the repository, then GET "
                            + "/artifacts/docs/docs/{site}/-/{version}/manifest.json. The table shows the site, "
                            + "the version, and one line per freeze; -o json prints the site, the version and "
                            + "the manifest as it was published."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ui-builder routes --repository qits-landing-app",
                    "  qits ui-builder routes --repository qits-landing-app --bundle-version 2026.1113.154308 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:No routes bundle names the repository, the version holds no manifest, or the platform "
                            + "refused or cannot be reached.",
                    "2:Used wrongly (for example no --repository), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class RoutesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UiBuilderCommand group;

        @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
                description = "The frontend repository (required), by name, such as qits-landing-app.")
        String repository;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(group.output);
            String repo = required(repository, "--repository", "repository");
            String version = group.version();
            DocsStore store = group.store(context);
            Bundles.Bundle bundle = Bundles.routes(store, repo, version);
            JsonNode manifest = file(store, bundle, Bundles.MANIFEST, "manifest.json");
            if (json) {
                ObjectNode answer = SafeText.JSON.createObjectNode();
                answer.put("site", bundle.site());
                answer.put("version", bundle.version());
                answer.set("manifest", manifest);
                printJson(context.out(), answer);
                return 0;
            }
            PrintStream out = context.out();
            printBundle(out, bundle);
            out.println("ROUTES   " + manifest.path("byRoute").size() + " with freezes");
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode freeze : manifest.path("freezes")) {
                rows.add(List.of(cell(text(freeze, "file"), 120), cell(text(freeze, "route"), 50),
                        cell(text(freeze, "kind"), 10), cell(text(freeze, "viewport"), 10),
                        cell(text(freeze, "colorScheme"), 10), String.valueOf(freeze.path("records").size())));
            }
            if (rows.isEmpty()) {
                out.println("No freezes.");
                return 0;
            }
            out.println();
            Table.print(out, "", List.of("SCREENSHOT", "ROUTE", "KIND", "VIEWPORT", "SCHEME", "RECORDS"), rows);
            return 0;
        }
    }

    // --- freeze ---

    @CommandLine.Command(name = "freeze", mixinStandardHelpOptions = true,
            description = {"One screenshot of a frontend's frozen routes: its entry in the manifest.json of the "
                    + "repository's @routes/<application> site (file, name, route, the provider states it "
                    + "recorded, triggers, kind, viewport, color scheme, data marks, connectivity), or with --html "
                    + "its frozen HTML.",
                    "Finds the site and reads the manifest as routes does, then, with --html, GET "
                            + "/artifacts/docs/docs/{site}/-/{version}/{file}. The HTML is printed as it was "
                            + "published, so it can go straight to a file; -o has no effect on it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ui-builder freeze --repository qits-landing-app "
                            + "--screenshot src/app/routes/work/work.page.browser.spec.ts/board-desktop.html",
                    "  qits ui-builder freeze --repository qits-landing-app "
                            + "--screenshot src/app/routes/work/work.page.browser.spec.ts/board-desktop.html --html "
                            + "> board.html",
                    "",
                    "- --screenshot is a freeze's file as routes lists it."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:No routes bundle names the repository, its manifest has no such screenshot, or the "
                            + "platform refused or cannot be reached.",
                    "2:Used wrongly (for example no --screenshot), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class FreezeCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UiBuilderCommand group;

        @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
                description = "The frontend repository (required), by name, such as qits-landing-app.")
        String repository;

        @CommandLine.Option(names = "--screenshot", paramLabel = "<file>",
                description = "The screenshot (required): the freeze's file in the manifest.")
        String screenshot;

        @CommandLine.Option(names = "--html", description = "Print the freeze's frozen HTML instead of its entry.")
        boolean html;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(group.output);
            String repo = required(repository, "--repository", "repository");
            String file = required(screenshot, "--screenshot", "screenshot");
            String version = group.version();
            DocsStore store = group.store(context);
            Bundles.Bundle bundle = Bundles.routes(store, repo, version);
            JsonNode manifest = file(store, bundle, Bundles.MANIFEST, "manifest.json");
            JsonNode freeze = null;
            for (JsonNode candidate : manifest.path("freezes")) {
                if (file.equals(text(candidate, "file"))) {
                    freeze = candidate;
                    break;
                }
            }
            if (freeze == null) {
                throw new CliFailure("The manifest of " + SafeText.line(bundle.site()) + " at "
                        + SafeText.line(bundle.version()) + " has no screenshot " + SafeText.line(file) + ".",
                        CliFailure.FAILED);
            }
            if (html) {
                if (!Bundles.insideBundle(file)) {
                    throw new CliFailure("The screenshot " + SafeText.line(file) + " names a path outside the "
                            + "bundle; it is not read.", CliFailure.FAILED);
                }
                String page;
                try {
                    page = store.text(bundle.site(), bundle.version(), file);
                } catch (CliFailure failure) {
                    throw missing(failure, bundle, file, "frozen HTML for the screenshot");
                }
                context.out().print(page);
                return 0;
            }
            if (json) {
                printJson(context.out(), freeze);
                return 0;
            }
            PrintStream out = context.out();
            printBundle(out, bundle);
            out.println();
            List<List<String>> rows = new ArrayList<>();
            for (String field : List.of("file", "name", "route", "kind", "viewport", "colorScheme", "connectivity",
                    "spec", "test")) {
                rows.add(List.of(field, cell(text(freeze, field), 200)));
            }
            for (String field : List.of("triggers", "dataMarks")) {
                JsonNode value = freeze.path(field);
                rows.add(List.of(field, value.isMissingNode() || value.isNull() ? "-"
                        : cell(value.toString(), 200)));
            }
            Table.print(out, "", null, rows);
            JsonNode records = freeze.path("records");
            out.println();
            if (records.isEmpty()) {
                out.println("No recorded provider states.");
                return 0;
            }
            List<List<String>> recorded = new ArrayList<>();
            for (JsonNode record : records) {
                recorded.add(List.of(cell(text(record, "provider"), 40), cell(text(record, "state"), 80),
                        cell(text(record, "operationId"), 50)));
            }
            Table.print(out, "", List.of("PROVIDER", "STATE", "OPERATION"), recorded);
            return 0;
        }
    }

    // --- golden-masters ---

    @CommandLine.Command(name = "golden-masters", mixinStandardHelpOptions = true,
            description = {"A service's golden masters: the golden-masters/index.json of its "
                    + "@contracts/<application> site (formatVersion, provider, and each provider state with its "
                    + "slug, params and recorded operations). With --state, that state alone; with --state and "
                    + "--operation, the response the state recorded for that operation.",
                    "Reads GET /artifacts/docs/docs, then (when no @contracts/<provider> site is listed) "
                            + "GET /artifacts/docs/docs/{site} of each @contracts/ site until one names the "
                            + "provider as its repository, then GET "
                            + "/artifacts/docs/docs/{site}/-/{version}/golden-masters/index.json and, for a "
                            + "recorded response, golden-masters/<file> as the index names it. A recorded "
                            + "response is JSON in both forms."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ui-builder golden-masters --provider qits-projects",
                    "  qits ui-builder golden-masters --provider qits-projects-service --state a-project-exists",
                    "  qits ui-builder golden-masters --provider qits-projects --state a-project-exists "
                            + "--operation listProjects",
                    "",
                    "- --provider is the application (qits-projects) or the repository (qits-projects-service), "
                            + "as a freeze's records name it.",
                    "- --state is a state's slug or its name."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:No contracts bundle for the provider, the index has no such state or the state no such "
                            + "operation, it recorded no response file, or the platform refused or cannot be reached.",
                    "2:Used wrongly (for example --operation without --state), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class GoldenMastersCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UiBuilderCommand group;

        @CommandLine.Option(names = "--provider", paramLabel = "<application>",
                description = "The service (required): its application, such as qits-projects, or its repository, "
                        + "such as qits-projects-service.")
        String provider;

        @CommandLine.Option(names = "--state", paramLabel = "<slug>",
                description = "Only this provider state: its slug, or its name.")
        String state;

        @CommandLine.Option(names = "--operation", paramLabel = "<operationId>",
                description = "With --state: print the response the state recorded for this operation.")
        String operation;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(group.output);
            String app = required(provider, "--provider", "provider");
            if (operation != null && state == null) {
                throw new CliFailure("--operation needs --state: an operation is recorded per provider state.",
                        CliFailure.USAGE);
            }
            String version = group.version();
            DocsStore store = group.store(context);
            Bundles.Bundle bundle = Bundles.contracts(store, app, version);
            JsonNode index = file(store, bundle, Bundles.GOLDEN_INDEX, "golden-masters/index.json");
            if (state == null) {
                if (json) {
                    printJson(context.out(), index);
                    return 0;
                }
                PrintStream out = context.out();
                printBundle(out, bundle);
                out.println("PROVIDER " + SafeText.line(text(index, "provider")));
                out.println();
                List<List<String>> rows = new ArrayList<>();
                for (JsonNode s : index.path("states")) {
                    rows.add(List.of(cell(text(s, "slug"), 80), String.valueOf(s.path("operations").size()),
                            cell(text(s, "name"), 100)));
                }
                if (rows.isEmpty()) {
                    out.println("No provider states.");
                    return 0;
                }
                Table.print(out, "", List.of("STATE", "OPERATIONS", "NAME"), rows);
                return 0;
            }
            String wanted = state.strip();
            JsonNode found = null;
            for (JsonNode s : index.path("states")) {
                if (wanted.equals(text(s, "slug")) || wanted.equals(text(s, "name"))) {
                    found = s;
                    break;
                }
            }
            if (found == null) {
                throw new CliFailure("The golden masters of " + SafeText.line(bundle.site()) + " at "
                        + SafeText.line(bundle.version()) + " have no state " + SafeText.line(wanted) + ".",
                        CliFailure.FAILED);
            }
            if (operation == null) {
                if (json) {
                    printJson(context.out(), found);
                    return 0;
                }
                PrintStream out = context.out();
                printBundle(out, bundle);
                out.println("STATE    " + SafeText.line(text(found, "slug")) + " (" + SafeText.line(text(found, "name"))
                        + ")");
                JsonNode params = found.path("params");
                out.println("PARAMS   " + (params.isObject() && !params.isEmpty() ? cell(params.toString(), 200) : "-"));
                out.println();
                List<List<String>> rows = new ArrayList<>();
                for (JsonNode op : found.path("operations")) {
                    rows.add(List.of(cell(text(op, "operationId"), 50), cell(text(op, "method"), 8),
                            cell(text(op, "path"), 80), cell(text(op, "status"), 4), cell(text(op, "file"), 100)));
                }
                if (rows.isEmpty()) {
                    out.println("No recorded operations.");
                    return 0;
                }
                Table.print(out, "", List.of("OPERATION", "METHOD", "PATH", "STATUS", "FILE"), rows);
                return 0;
            }
            String operationId = operation.strip();
            JsonNode op = null;
            for (JsonNode candidate : found.path("operations")) {
                if (operationId.equals(text(candidate, "operationId"))) {
                    op = candidate;
                    break;
                }
            }
            if (op == null) {
                throw new CliFailure("The state " + SafeText.line(text(found, "slug")) + " of "
                        + SafeText.line(bundle.site()) + " recorded no operation " + SafeText.line(operationId) + ".",
                        CliFailure.FAILED);
            }
            String recorded = text(op, "file");
            if (recorded.isEmpty()) {
                throw new CliFailure("The state " + SafeText.line(text(found, "slug")) + " recorded no response file "
                        + "for " + SafeText.line(operationId) + ": the answer had no body, or bytes the index keeps "
                        + "no file for.", CliFailure.FAILED);
            }
            if (!Bundles.safeRecordedFile(recorded)) {
                throw new CliFailure("The index names the recorded file " + SafeText.line(recorded)
                        + ", a path outside its own tree; it is not read.", CliFailure.FAILED);
            }
            printJson(context.out(), file(store, bundle, "golden-masters/" + recorded, "recorded response"));
            return 0;
        }
    }

    // --- entities ---

    @CommandLine.Command(name = "entities", mixinStandardHelpOptions = true,
            description = {"A service's entity map: the entities.json of its @contracts/<application> site "
                    + "(formatVersion, application, and each persisted entity with its table, its marking "
                    + "(domain, technical or projection), the DTOs it is exposed through and the operations that "
                    + "return or take them).",
                    "Finds the site as golden-masters does, then reads GET "
                            + "/artifacts/docs/docs/{site}/-/{version}/entities.json. The table shows one line "
                            + "per entity; -o json prints the map as it was published."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ui-builder entities --provider qits-projects",
                    "  qits ui-builder entities --provider qits-projects-service -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:No contracts bundle for the provider, its version publishes no entity map, or the "
                            + "platform refused or cannot be reached.",
                    "2:Used wrongly (for example no --provider), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class EntitiesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        UiBuilderCommand group;

        @CommandLine.Option(names = "--provider", paramLabel = "<application>",
                description = "The service (required): its application, such as qits-projects, or its repository, "
                        + "such as qits-projects-service.")
        String provider;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(group.output);
            String app = required(provider, "--provider", "provider");
            String version = group.version();
            DocsStore store = group.store(context);
            Bundles.Bundle bundle = Bundles.contracts(store, app, version);
            JsonNode map = file(store, bundle, Bundles.ENTITIES, "entity map (entities.json)");
            if (json) {
                printJson(context.out(), map);
                return 0;
            }
            PrintStream out = context.out();
            printBundle(out, bundle);
            out.println("APPLICATION " + SafeText.line(text(map, "application")));
            out.println();
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode entity : map.path("entities")) {
                rows.add(List.of(cell(text(entity, "entity"), 100), cell(text(entity, "table"), 40),
                        cell(text(entity, "marking"), 12), String.valueOf(entity.path("dtos").size()),
                        String.valueOf(entity.path("operations").size())));
            }
            if (rows.isEmpty()) {
                out.println("No entities.");
                return 0;
            }
            Table.print(out, "", List.of("ENTITY", "TABLE", "MARKING", "DTOS", "OPERATIONS"), rows);
            return 0;
        }
    }
}
