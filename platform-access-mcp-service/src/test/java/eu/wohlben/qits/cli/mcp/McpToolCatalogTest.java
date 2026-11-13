package eu.wohlben.qits.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.mcp.ToolCatalog.Tool;
import eu.wohlben.qits.cli.session.RequestCredential;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.TuiCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract between the CLI and the MCP server, over the real {@code AccessCli} tree: every
 * command that runs is a tool unless its nearest {@code @TuiCommand(interaction)} says otherwise, and
 * a tool's schema is its options. A new option or command on the CLI cannot be missing here, and one
 * that should not be served cannot slip in, without this test failing.
 * <p>
 * The calls run through {@link ToolRunner} against {@link FakePlatform}, the library's own fake
 * platform, with {@link CliContext#MCP_SERVICE} set as the service sets it: a command that fell back
 * to the process's own context would throw rather than call the fake.
 */
class McpToolCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The strictest tool-name rule among the clients in use. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** An agent's bearer, as the service hands it on: the signature is the service's to check, not the command's. */
    private static final String AGENT = token("""
            {"sub":"workspace-7f3a","aud":["qits-platform"],"groups":["qits:agent"]}""");

    private static final String QITS = "0a0b0c0d-0000-4000-8000-000000000001";

    private static String previousGuard;

    private final ToolCatalog catalog = ToolCatalog.of(new CommandLine(new AccessCli()));
    private FakePlatform platform;
    private final CountDownLatch closing = new CountDownLatch(1);

    @BeforeAll
    static void armTheGuard() {
        previousGuard = System.getProperty(CliContext.MCP_SERVICE);
        System.setProperty(CliContext.MCP_SERVICE, "true");
    }

    @AfterAll
    static void disarmTheGuard() {
        if (previousGuard == null) {
            System.clearProperty(CliContext.MCP_SERVICE);
        } else {
            System.setProperty(CliContext.MCP_SERVICE, previousGuard);
        }
    }

    @BeforeEach
    void start() throws Exception {
        platform = new FakePlatform();
    }

    @AfterEach
    void stop() {
        closing.countDown();
        platform.close();
    }

    private static String token(String claims) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    // --- the tree --------------------------------------------------------------------------------

    /** Every command that does something when named last, by path, with the nearest declaration above it. */
    private static Map<String, CommandSpec> runnable(CommandSpec spec, String path, Map<String, CommandSpec> found) {
        Map<String, CommandSpec> children = new LinkedHashMap<>();
        for (CommandLine child : spec.subcommands().values()) {
            children.putIfAbsent(child.getCommandSpec().name(), child.getCommandSpec());
        }
        if (!path.isEmpty() && (children.isEmpty() || spec.userObject() instanceof java.util.concurrent.Callable<?>)) {
            found.put(path, spec);
        }
        children.values().forEach(child -> runnable(child, path.isEmpty() ? child.name() : path + "_" + child.name(), found));
        return found;
    }

    private static Map<String, CommandSpec> realTree() {
        return runnable(new CommandLine(new AccessCli()).getCommandSpec(), "", new LinkedHashMap<>());
    }

    private static Interaction nearest(CommandSpec spec) {
        for (CommandSpec at = spec; at != null; at = at.parent()) {
            TuiCommand declared = TuiCommands.declaredOn(at.userObject());
            if (declared != null) {
                return declared.interaction();
            }
        }
        return Interaction.PLAIN;
    }

    private Tool tool(String name) {
        return catalog.tool(name).orElseThrow(() -> new AssertionError("no tool " + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Tool tool, String name) {
        return (Map<String, Object>) properties(tool).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Tool tool) {
        return (List<String>) tool.inputSchema().getOrDefault("required", List.of());
    }

    @Test
    void everyCommandIsExcludedExactlyWhenItsNearestDeclarationSaysSo() {
        Map<String, CommandSpec> commands = realTree();
        assertThat(commands).isNotEmpty();
        Set<String> excludedCommands = new TreeSet<>();
        catalog.excluded().forEach(e -> excludedCommands.add(e.command().substring("qits ".length()).replace(' ', '_')));

        commands.forEach((name, spec) -> {
            Interaction interaction = nearest(spec);
            boolean excluded = interaction != Interaction.PLAIN;
            assertThat(excludedCommands.contains(name)).as(name + " excluded, nearest declaration " + interaction)
                    .isEqualTo(excluded);
            assertThat(catalog.tool(name).isPresent()).as(name + " is a tool").isEqualTo(!excluded);
        });
        assertThat(catalog.tools().size() + catalog.excluded().size()).isEqualTo(commands.size());
    }

    @Test
    void theExclusionsAreTheOnesTheEpicNamesWithTheirReasons() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));
        assertThat(byCommand).containsKeys("qits login", "qits git-login", "qits session-daemon",
                "qits checkout-daemon", "qits events", "qits observe", "qits git-credential",
                "qits docker-credential", "qits help skill");
        assertThat(byCommand.keySet()).anySatisfy(command -> assertThat(command).startsWith("qits artifacts publish"));
        assertThat(byCommand.get("qits login").reason()).isEqualTo("needs a browser and a person");
        assertThat(byCommand.get("qits events").reason()).isEqualTo("runs until stopped");
        assertThat(byCommand.get("qits git-credential").reason()).isEqualTo("only means something on the caller's machine");
        assertThat(byCommand.get("qits docker-credential").reason()).isEqualTo("only means something on the caller's machine");
        assertThat(byCommand.get("qits checkout sync").reason()).isEqualTo("only means something on the caller's machine");
        byCommand.values().stream().filter(e -> e.command().startsWith("qits artifacts publish"))
                .forEach(e -> assertThat(e.reason()).isEqualTo("CI-step only, reads local files"));

        String instructions = catalog.instructions();
        catalog.excluded().forEach(e -> assertThat(instructions).contains(e.line()));
        assertThat(instructions).contains("Not served here (" + catalog.excluded().size() + ")");
    }

    /** qits-1765: it writes pacts to a local directory, which in this service nobody would read. */
    @Test
    void fetchingDeployedPactsIsLocalAndNoTool() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(byCommand.get("qits artifacts fetch deployed-pacts")).isNotNull();
        assertThat(byCommand.get("qits artifacts fetch deployed-pacts").reason())
                .isEqualTo("only means something on the caller's machine");
        assertThat(catalog.tool("artifacts_fetch_deployed-pacts")).isEmpty();
        assertThat(catalog.tool("artifacts_fetch")).isEmpty();
    }

    @Test
    void theReportSubmitIsCiOnlyAndTheReportShowIsATool() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(byCommand.get("qits ci report submit")).isNotNull();
        assertThat(byCommand.get("qits ci report submit").reason()).isEqualTo("CI-step only, reads local files");
        assertThat(catalog.tool("ci_report_submit")).isEmpty();
        assertThat(catalog.tool("ci_report")).isEmpty();
        assertThat(properties(tool("ci_report_show"))).containsKeys("run-id", "kind", "project", "repository", "output");
        assertThat(required(tool("ci_report_show"))).containsExactly("run-id");
    }

    /** qits-1940: it reads a checkout's OpenAPI document and golden-master index, which this service has none of. */
    @Test
    void theContractsCoverageIsCiOnlyAndNoTool() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(byCommand.get("qits contracts coverage")).isNotNull();
        assertThat(byCommand.get("qits contracts coverage").reason()).isEqualTo("CI-step only, reads local files");
        assertThat(catalog.tool("contracts_coverage")).isEmpty();
        assertThat(catalog.tool("contracts")).isEmpty();
    }

    @Test
    void theQueriesAreToolsUnderTheirStreamsAndTheLocalCommandsAreNot() {
        assertThat(catalog.tool("events_query")).isPresent();
        assertThat(catalog.tool("observe_query")).isPresent();
        assertThat(catalog.tool("events")).isEmpty();
        assertThat(catalog.tool("observe")).isEmpty();
        assertThat(catalog.tool("git-credential")).isEmpty();
        assertThat(catalog.tool("docker-credential")).isEmpty();
        assertThat(catalog.tool("login")).isEmpty();
        assertThat(catalog.tool("help_skill")).isEmpty();
        assertThat(catalog.tool("update")).as("it replaces a file on the caller's machine").isEmpty();
        assertThat(catalog.tool("checkout_sync")).as("it moves repositories on the caller's machine").isEmpty();
        assertThat(catalog.tool("checkout")).isEmpty();
        assertThat(catalog.tools()).extracting(Tool::name)
                .contains("projects_list", "projects_delivery", "work_update", "work_dispatch", "work_comment_create",
                        "release-request_join", "ci_runs", "repositories_create", "wait_branch");
    }

    @Test
    void attachingToATerminalIsNotServedAndSignInOpenNeverAttachesHere() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(catalog.tool("workspaces_attach")).isEmpty();
        assertThat(byCommand.get("qits workspaces attach").interaction()).isEqualTo(Interaction.TERMINAL);
        assertThat(byCommand.get("qits workspaces attach").reason())
                .isEqualTo("needs a terminal; runs until the command it attaches to ends");
        assertThat(catalog.instructions()).contains(byCommand.get("qits workspaces attach").line());
        assertThat(catalog.tool("agents_attach")).isEmpty();
        assertThat(byCommand.get("qits agents attach").interaction()).isEqualTo(Interaction.TERMINAL);
        // qits agents itself is no longer LOCAL (epic qits-1667): its verbs are tools, its claude group is not.
        assertThat(catalog.tool("agents_stop")).isPresent();
        assertThat(catalog.tool("agents_claude_hook_work-links")).isEmpty();
        assertThat(catalog.tool("repositories_remote-login")).isEmpty();
        assertThat(byCommand.get("qits repositories remote-login").interaction()).isEqualTo(Interaction.TERMINAL);
        assertThat(catalog.instructions()).contains(byCommand.get("qits repositories remote-login").line());

        // The flag is in the schema, as every option is; a call that sets it is refused before anything opens.
        assertThat(properties(tool("workspaces_agents_sign-in_open"))).containsKey("attach");
        Map<String, String> config = Map.of("QITS_ENV", "dev", "QITS_WORKSPACES_URL", platform.url());
        ToolRunner runner = new ToolRunner(factory -> new CommandLine(new AccessCli(), factory),
                ToolRunner.environment(config::get), Clock.systemUTC());
        Map<String, Object> arguments = new HashMap<>(Map.of("workspace", 1, "attach", true));
        ToolRunner.Result result = runner.run(tool("workspaces_agents_sign-in_open"), arguments,
                new RequestCredential(AGENT));

        assertThat(result.error()).isTrue();
        assertThat(result.texts().get(0)).contains("needs a terminal");
        assertThat(result.texts().get(1)).isEqualTo("exit 2");
        assertThat(platform.requests).as("nothing was opened").isEmpty();
    }

    @Test
    void theUserCommandsThatPrintASecretAreNotServed() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(catalog.tool("users_invite")).isEmpty();
        assertThat(catalog.tool("users_register")).isEmpty();
        assertThat(byCommand.get("qits users register").reason()).isEqualTo("only means something on the caller's machine");
        assertThat(byCommand.get("qits users invite").reason()).isEqualTo("only means something on the caller's machine");
        assertThat(catalog.tool("users_list")).isPresent();
        assertThat(catalog.tool("users_delete")).isPresent();
        assertThat(properties(tool("users_list"))).doesNotContainKey("idp-url");
    }

    /**
     * A person's remembered browsers (qits-1944) are served like any command that needs nothing of
     * the caller's machine: the idp, not the catalog, decides who may reach them, and it refuses an
     * agent's bearer here as it does on the CLI.
     */
    @Test
    void theRememberedDevicesCommandsAreServed() {
        assertThat(catalog.tool("remembered-devices_list")).isPresent();
        assertThat(catalog.tool("remembered-devices_revoke")).isPresent();
        assertThat(properties(tool("remembered-devices_list"))).doesNotContainKey("idp-url");
        assertThat(properties(tool("remembered-devices_revoke"))).containsKey("device").doesNotContainKey("idp-url");
        assertThat(required(tool("remembered-devices_revoke"))).containsExactly("device");
    }

    @Test
    void theTargetCommandsThatPrintAnInstallLineAreNotServed() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(catalog.tool("targets_create")).isEmpty();
        assertThat(catalog.tool("targets_rotate-token")).isEmpty();
        assertThat(byCommand.get("qits targets create").reason()).isEqualTo("only means something on the caller's machine");
        assertThat(byCommand.get("qits targets rotate-token").reason())
                .isEqualTo("only means something on the caller's machine");
        assertThat(catalog.tool("targets_update")).isPresent();
        assertThat(catalog.tool("targets_list")).isPresent();
    }

    @Test
    void everyToolsSchemaIsItsOptionsByTheRules() {
        Map<String, CommandSpec> commands = realTree();
        for (Tool tool : catalog.tools()) {
            CommandSpec spec = commands.get(tool.name());
            Set<String> expected = new LinkedHashSet<>();
            Set<String> expectedRequired = new TreeSet<>();
            for (OptionSpec option : spec.options()) {
                if (option.usageHelp() || option.versionHelp() || option.longestName().endsWith("-url")) {
                    continue;
                }
                String name = option.longestName().replaceFirst("^-+", "");
                expected.add(name);
                if (option.required()) {
                    expectedRequired.add(name);
                }
                Map<String, Object> schema = property(tool, name);
                assertThat(schema).as(tool.name() + " " + name).isNotNull();
                assertThat(schema.get("type")).as(tool.name() + " " + name).isEqualTo(expectedType(option.type()));
                if (option.defaultValue() != null && !name.equals("output") && !"array".equals(schema.get("type"))) {
                    assertThat(String.valueOf(schema.get("default"))).as(tool.name() + " " + name + " default")
                            .isEqualTo(option.defaultValue());
                }
                if (option.description().length > 0) {
                    assertThat(schema.get("description")).as(tool.name() + " " + name).isNotNull();
                }
            }
            for (PositionalParamSpec positional : spec.positionalParameters()) {
                String name = positional.paramLabel().replaceAll("[<>]", "").strip().replaceAll("\\s+", "-");
                expected.add(name);
                if (positional.arity().min() >= 1) {
                    expectedRequired.add(name);
                }
            }
            if (TuiCommands.inputOf(spec.userObject()) != Input.NONE) {
                expected.add(ToolCatalog.PAYLOAD);
            }
            if (TuiCommands.inputOf(spec.userObject()) == Input.TEXT) {
                // A value on stdin is all such a command is for: without one it only refuses.
                expectedRequired.add(ToolCatalog.PAYLOAD);
            }
            assertThat(properties(tool).keySet()).as(tool.name()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(new TreeSet<>(required(tool))).as(tool.name() + " required").isEqualTo(expectedRequired);
        }
    }

    private static String expectedType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (List.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        return "string";
    }

    @Test
    void inheritedOptionsShowUpOnTheirLeaves() {
        for (String leaf : List.of("work_list", "work_details", "work_create", "work_update", "work_transition",
                "work_status", "work_comment_create", "work_comment_update")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("entity", "output");
        }
        for (String leaf : List.of("ci_runs", "ci_run", "ci_retry")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("project", "repository", "output");
        }
        for (String leaf : List.of("release-request_list", "release-request_create", "release-request_join", "release-request_move",
                "release-request_withdraw", "release-request_approve", "release-request_decline")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("project", "repository", "output");
        }
        for (String leaf : List.of("repositories_list", "repositories_create")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("project", "output");
        }
    }

    @Test
    void noAddressIsEverAnArgumentAndNoNameBreaksTheClientRule() {
        for (Tool tool : catalog.tools()) {
            assertThat(tool.name()).matches(NAME);
            assertThat(properties(tool).keySet()).as(tool.name())
                    .noneSatisfy(name -> assertThat(name).endsWith("-url"))
                    .doesNotContain("help", "version")
                    .allSatisfy(name -> assertThat(name).matches("^[A-Za-z0-9_.-]{1,64}$"));
        }
    }

    /**
     * The commands marked READ, every one of them on purpose. A command added without a marker is a
     * write and is not on this list, so the read-only mode never offers it; one marked READ by
     * mistake fails here until somebody adds it knowingly.
     */
    @Test
    void exactlyTheseCommandsAreReads() {
        Set<String> reads = new TreeSet<>();
        realTree().forEach((name, spec) -> {
            if (TuiCommands.effectOf(spec.userObject()) == Effect.READ) {
                reads.add(name);
            }
        });
        assertThat(reads).containsExactlyInAnyOrder(
                "projects_list", "projects_delivery", "projects_desk_answer", "projects_desk_triggers",
                "projects_agents_list", "projects_agents_answer", "users_list", "remembered-devices_list", "repositories_list", "repositories_branches", "repositories_commits",
                "repositories_commit-changes", "repositories_commit-diff", "repositories_commit-subjects",
                "work_list", "work_details", "ideas_list", "ideas_details", "pitches_list", "pitches_show", "work_dossier_list", "work_dossier_get",
                "work_refinement_show", "work_design_list", "work_design_get",
                "release-request_list", "ci_runs", "ci_run", "ci_report_show", "maintenance_automations",
                "maintenance_bump", "deployments_settings_list", "deployments_settings_preview",
                "deployments_database_moves", "deployments_database_move-status",
                "agent-mcp_credentials_list",
                "events", "events_query", "observe", "observe_query", "observe_sources", "observe_errors",
                "observe_slow-spans", "observe_metrics", "help_skill", "help_guide", "agents_claude_hook_work-links",
                "agents_claude_statusline_work-items", "artifacts_publish_exists", "artifacts_fetch_deployed-pacts",
                "changelog_bump-message",
                "workspaces_list", "workspaces_show", "workspaces_active-process", "workspaces_items",
                "workspaces_history", "workspaces_log", "workspaces_agents_sign-in_status",
                "workspaces_agents_plugins_list", "agents_list", "agents_show", "agents_sessions", "agents_transcript",
                "agents_chat", "agents_files_list", "agents_files_read", "agents_files_detect", "agents_draft_show");
        // The default is what keeps a new command out: no marker, or a marker without an effect, is a write.
        assertThat(TuiCommands.effectOf(new Object())).isEqualTo(Effect.WRITE);
        assertThat(TuiCommands.effectOf(new eu.wohlben.qits.cli.access.projects.WorkCommand.CreateCommand()))
                .isEqualTo(Effect.WRITE);
    }

    @Test
    void thePayloadIsOnExactlyTheCommandsThatReadOne() {
        Map<String, CommandSpec> commands = realTree();
        Set<String> marked = new TreeSet<>();
        commands.forEach((name, spec) -> {
            if (TuiCommands.inputOf(spec.userObject()) == Input.PAYLOAD) {
                marked.add(name);
            }
        });
        assertThat(marked).containsExactlyInAnyOrder("work_create", "work_update", "work_transition", "work_status",
                "work_comment_create", "work_comment_update", "work_dossier_put", "work_design_put",
                "ideas_create", "ideas_update", "ideas_spawn", "pitches_create",
                "pitches_add-version");
        Set<String> text = new TreeSet<>();
        commands.forEach((name, spec) -> {
            if (TuiCommands.inputOf(spec.userObject()) == Input.TEXT) {
                text.add(name);
            }
        });
        assertThat(text).containsExactlyInAnyOrder("deployments_settings_set", "agent-mcp_credentials_set");
        for (Tool tool : catalog.tools()) {
            boolean payload = marked.contains(tool.name());
            boolean value = text.contains(tool.name());
            assertThat(tool.payload()).as(tool.name()).isEqualTo(payload || value);
            assertThat(properties(tool).containsKey(ToolCatalog.PAYLOAD)).as(tool.name()).isEqualTo(payload || value);
            assertThat(tool.description().endsWith(ToolCatalog.PAYLOAD_HINT)).as(tool.name()).isEqualTo(payload);
        }
        assertThat(property(tool("work_update"), ToolCatalog.PAYLOAD)).containsEntry("type", "object");
        assertThat(property(tool("deployments_settings_set"), ToolCatalog.PAYLOAD)).containsEntry("type", "string")
                .containsEntry("description", ToolCatalog.TEXT_PAYLOAD);
        assertThat(required(tool("agent-mcp_credentials_set"))).contains(ToolCatalog.PAYLOAD);
    }

    /** A text payload is the command's stdin byte for byte: a string, never re-encoded as JSON. */
    @Test
    void aTextPayloadIsStdinAsItIs() {
        Tool set = tool("agent-mcp_credentials_set");
        assertThat(ToolRunner.stdin(set, Map.of("key", "GITHUB_TOKEN", ToolCatalog.PAYLOAD, "s3cr\"et\n")))
                .isEqualTo("s3cr\"et\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ToolRunner.argv(set, Map.of("key", "GITHUB_TOKEN", ToolCatalog.PAYLOAD, "s3cr\"et")))
                .doesNotContain("s3cr\"et").noneMatch(a -> a.contains("s3cr"));
    }

    /**
     * qits-248: an agent drops work through work_status, so the tool's description is where it
     * learns that a drop says why. The six outcomes, the reference rule and "block instead" reach it
     * from the command's own help; work_list takes the outcome filter.
     */
    @Test
    void workStatusTellsAnAgentHowToDropAndWorkListFiltersByOutcome() {
        String status = tool("work_status").description();
        for (String outcome : List.of("DUPLICATE", "RESOLVED_ELSEWHERE", "SUPERSEDED", "OBSOLETE", "INVALID",
                "WONT_DO")) {
            assertThat(status).as(outcome).contains(outcome + " (");
        }
        assertThat(status).contains("\"supersededBy\"")
                .contains("Do not drop an item merely because it is hard or stale: block it instead.")
                .contains("{\"target\":\"DROPPED\",\"outcome\":\"DUPLICATE\",\"supersededBy\":\"qits-90\"}")
                .endsWith(ToolCatalog.PAYLOAD_HINT);
        assertThat(property(tool("work_list"), "outcome")).containsEntry("type", "string");
        assertThat((String) property(tool("work_list"), "outcome").get("description"))
                .contains("DUPLICATE, RESOLVED_ELSEWHERE, SUPERSEDED, OBSOLETE, INVALID or WONT_DO");
        assertThat(tool("work_details").description()).contains("what it closes");
    }

    @Test
    void theDescriptionIsTheUsageTextAndTheOutputDefaultsToJson() {
        Tool runs = tool("ci_runs");
        assertThat(runs.description()).startsWith(new CommandLine(new AccessCli()).getSubcommands().get("ci")
                .getSubcommands().get("runs").getCommandSpec().usageMessage().description()[0].replace("%n", "\n"));
        // The footer as a person reads it: under its heading, every example indented, the first too.
        assertThat(tool("projects_list").description()).contains("\n\nExamples:\n  qits projects list\n");
        assertThat(runs.outputJson()).isEqualTo("--output");
        assertThat(property(runs, "output")).containsEntry("default", "json");
        assertThat(property(runs, "limit")).containsEntry("type", "integer").containsEntry("default", 20L);
        assertThat(property(tool("events_query"), "output")).containsEntry("default", "json");
        assertThat(property(tool("observe_query"), "filter")).containsEntry("type", "array");
        assertThat(required(tool("observe_query"))).contains("filter");
        assertThat(property(tool("ci_run"), "logs")).containsEntry("type", "boolean");
        assertThat(required(tool("ci_run"))).contains("run-id");
    }

    // --- a command nobody has written yet -----------------------------------------------------------

    enum Shade {LIGHT, DARK}

    /** Exists only here. It prints what it was handed, so a call shows the runner gave it the caller. */
    @CommandLine.Command(name = "fictional", description = "Not a real command.", footer = "  qits fictional --thing x")
    static class FictionalCommand extends PlatformCommand {

        @CommandLine.Option(names = "--thing", required = true, description = "The thing it needs.")
        String thing;

        @CommandLine.Option(names = "--times", defaultValue = "1", description = "How often.")
        int times;

        @CommandLine.Option(names = "--shade", description = "Which shade.")
        Shade shade;

        @CommandLine.Option(names = "--tag", description = "Tags.")
        List<String> tags;

        @CommandLine.Option(names = "--loud", description = "A flag.")
        boolean loud;

        @CommandLine.Option(names = "--elsewhere-url", description = "An address.")
        String elsewhere;

        @CommandLine.Parameters(index = "0", paramLabel = "<the subject>", description = "What about.")
        String subject;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            context.out().println(String.join("|", thing, String.valueOf(times), String.valueOf(shade),
                    String.valueOf(tags), String.valueOf(loud), subject, context.credential().who(),
                    String.valueOf(context.mode())));
            return 0;
        }
    }

    private static CommandLine withFictional(CommandLine.IFactory factory) {
        CommandLine cli = new CommandLine(new AccessCli(), factory);
        cli.addSubcommand(FictionalCommand.class);
        return cli;
    }

    @Test
    void aCommandThisTestInventedIsAToolWithNoOtherChange() {
        ToolCatalog withIt = ToolCatalog.of(withFictional(CommandLine.defaultFactory()));
        Tool fictional = withIt.tool("fictional").orElseThrow();
        assertThat(fictional.description()).startsWith("Not a real command.").contains("qits fictional --thing x");
        assertThat(properties(fictional).keySet()).containsExactlyInAnyOrder("thing", "times", "shade", "tag", "loud",
                "the-subject");
        assertThat(required(fictional)).containsExactlyInAnyOrder("thing", "the-subject");
        assertThat(property(fictional, "shade")).containsEntry("enum", List.of("LIGHT", "DARK"));
        assertThat(property(fictional, "tag")).containsEntry("type", "array");
        assertThat(property(fictional, "times")).containsEntry("default", 1L);
        assertThat(withIt.tools()).hasSize(catalog.tools().size() + 1);

        ToolRunner runner = new ToolRunner(McpToolCatalogTest::withFictional, ToolRunner.environment(n -> null),
                Clock.systemUTC());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("thing", "a value; rm -rf /");
        arguments.put("times", 3);
        arguments.put("shade", "DARK");
        arguments.put("tag", List.of("one", "-two"));
        arguments.put("loud", true);
        arguments.put("the-subject", "--looks-like-an-option");
        ToolRunner.Result result = runner.run(fictional, arguments, new RequestCredential(AGENT));
        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(result.texts().getFirst().strip()).isEqualTo(
                "a value; rm -rf /|3|DARK|[one, -two]|true|--looks-like-an-option|agent (qits:agent)|IN_PLATFORM");
    }

    @Test
    void anArgumentTheToolDoesNotHaveIsAUsageErrorAndNothingRuns() {
        ToolRunner.Result result = runner().run(tool("projects_list"), Map.of("projects-url", "http://evil"),
                new RequestCredential(AGENT));
        assertThat(result.error()).isTrue();
        assertThat(result.texts()).containsExactly("Unknown argument 'projects-url' for projects_list.", "exit 2");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aParseErrorIsAResultWithPicocliMessageAndExit2() {
        ToolRunner.Result result = runner().run(tool("ci_runs"), Map.of("limit", "many"), new RequestCredential(AGENT));
        assertThat(result.error()).isTrue();
        assertThat(result.texts()).hasSize(2);
        assertThat(result.texts().get(0)).contains("--limit").contains("many");
        assertThat(result.texts().get(1)).isEqualTo("exit 2");
    }

    // --- calls against the fake platform -------------------------------------------------------------

    private ToolRunner runner() {
        Map<String, String> config = Map.of("QITS_ENV", "dev", "QITS_PROJECTS_URL", platform.url(),
                "QITS_EVENTS_URL", platform.url(), "QITS_OBSERVABILITY_URL", platform.url(),
                "QITS_CI_URL", platform.url());
        return new ToolRunner(factory -> new CommandLine(new AccessCli(), factory),
                ToolRunner.environment(config::get), Clock.systemUTC());
    }

    @Test
    void aReadAnswersWithTheServicesJsonAsTheCaller() throws Exception {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}}]}
                """.formatted(QITS));

        ToolRunner.Result result = runner().run(tool("projects_list"), Map.of(), new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(result.texts()).hasSize(1);
        JsonNode answer = JSON.readTree(result.texts().getFirst());
        assertThat(answer.toString()).contains("qits platform");
        assertThat(platform.requests("GET", "/projects/api/projects")).singleElement()
                .satisfies(sent -> assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT));
    }

    @Test
    void aRefusedWriteIsAnErrorResultInTheCredentialsOwnWords() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}}]}
                """.formatted(QITS));
        platform.answer("POST", "/projects/api/projects/" + QITS + "/repositories", 403, "{\"message\":\"forbidden\"}");

        ToolRunner.Result result = runner().run(tool("repositories_create"),
                Map.of("project", "qits", "name", "qits-docs-app"), new RequestCredential(AGENT));

        assertThat(result.error()).isTrue();
        assertThat(result.texts().get(0)).contains("403 - this credential is qits:agent, which reads but does not write");
        assertThat(result.texts().get(1)).isEqualTo("exit 1");
        assertThat(platform.requests("POST", "/projects/api/projects/" + QITS + "/repositories")).singleElement()
                .satisfies(sent -> assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT));
    }

    private static final String THREAD = "/projects/api/work/qits-100/comments";
    private static final String DOCUMENT = """
            {"openapi":"3.1.0",
             "components":{"schemas":{
               "CreateCommentRequest":{"type":"object","required":["body"],
                 "properties":{"body":{"type":"string"}}}}},
             "paths":{
               "/projects/api/work/{qualifiedId}/comments":{
                 "post":{"requestBody":{"required":true,"content":{"application/json":
                   {"schema":{"$ref":"#/components/schemas/CreateCommentRequest"}}}}}}}}
            """;

    @Test
    void aPayloadWriteSendsThePayloadAsTheCommandsStdin() throws Exception {
        platform.answer("POST", THREAD, """
                {"comment":{"id":"c3","entityId":"e","author":"agent","body":"hi",
                  "createdAt":"2026-09-29T10:00:00Z","updatedAt":"2026-09-29T10:00:00Z"}}
                """);
        Map<String, Object> payload = Map.of("body", "I can reproduce it", "note", Map.of("kept", List.of(1, 2)));

        ToolRunner.Result result = runner().run(tool("work_comment_create"),
                Map.of("entity", "qits-100", "payload", payload), new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        FakePlatform.Request sent = platform.requests("POST", THREAD).getFirst();
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.valueToTree(payload));
        assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT);
        assertThat(JSON.readTree(result.texts().getFirst()).path("comment").path("id").asText()).isEqualTo("c3");
    }

    @Test
    void aPayloadWriteCalledWithoutOneAnswersWithTheSchemaAndSendsNothing() throws Exception {
        platform.answer("GET", "/projects/q/openapi", DOCUMENT);

        ToolRunner.Result result = runner().run(tool("work_comment_create"), Map.of("entity", "qits-100"),
                new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(JSON.readTree(result.texts().getFirst()).path("required").toString()).isEqualTo("[\"body\"]");
        assertThat(platform.requests("POST", THREAD)).isEmpty();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theEventsQueryReturnsWhileTheLiveStreamNeverEnds() throws Exception {
        // A live stream that never ends: a query that touched it would hang here.
        platform.streams.add(platform.stream(true, ": keepalive"));
        platform.answer("GET", "/events/api/events", "{\"events\":[],\"nextCursor\":null}");

        ToolRunner.Result result = runner().run(tool("events_query"), Map.of("since", "15m"),
                new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(JSON.readTree(result.texts().getFirst()).has("events")).isTrue();
        assertThat(platform.requests("GET", "/events/api/stream")).isEmpty();
        assertThat(platform.requests("GET", "/events/api/events")).isNotEmpty()
                .allSatisfy(sent -> assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theObserveQueryReturnsWhileTheLiveStreamNeverEnds() throws Exception {
        // The live socket's handshake never answers: a query that opened it would hang here.
        platform.route("GET", "/observability/stream", request -> {
            try {
                closing.await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new FakePlatform.Answer(500, "", Map.of());
        });
        platform.answer("POST", "/observability/api/telemetry/records/search",
                "{\"records\":[],\"truncated\":false,\"bufferedSince\":\"2020-01-01T00:00:00Z\"}");

        ToolRunner.Result result = runner().run(tool("observe_query"), Map.of("filter", List.of("kind=log level>=ERROR")),
                new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(JSON.readTree(result.texts().getFirst()).has("records")).isTrue();
        assertThat(platform.requests("GET", "/observability/stream")).isEmpty();
        FakePlatform.Request sent = platform.requests("POST", "/observability/api/telemetry/records/search").getFirst();
        assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT);
        assertThat(sent.body()).contains("kind");
    }

    @Test
    void theServicesOwnContextIsNeverReachedForTheGuardIsArmed() {
        assertThat(System.getProperty(CliContext.MCP_SERVICE)).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(CliContext::system)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCommandsSeeTheServicesEnvironmentAndNothingElse() {
        Map<String, String> env = ToolRunner.environment(Map.of("QITS_ENV", "prod", "QITS_TOKEN", "x",
                "HOME", "/root", "QITS_OBSERVABILITY_URL", "http://prod-qits-observability:8080",
                "QITS_DOMAIN", "wohlben.eu",
                "QITS_AGENT_ID", "0a0b0c0d-0000-4000-8000-000000000009")::get);
        assertThat(env).containsExactlyInAnyOrderEntriesOf(Map.of("QITS_PLATFORM", "true", "QITS_ENV", "prod",
                "QITS_DOMAIN", "wohlben.eu",
                "QITS_OBSERVABILITY_URL", "http://prod-qits-observability:8080"));
    }

    /** qits-1213: the agent comes from the caller's token, and only a uuid counts. */
    @Test
    void aCallAddsTheAgentOfTheCallersToken() {
        String agent = "07a79d74-b3ff-40a3-9b66-bceda7435a37";
        assertThat(ToolRunner.callEnvironment(agent, null, null, null)).isEqualTo(Map.of("QITS_AGENT_ID", agent));
        assertThat(ToolRunner.callEnvironment(jakarta.json.Json.createValue(agent), null, null, null))
                .isEqualTo(Map.of("QITS_AGENT_ID", agent));
        assertThat(ToolRunner.callEnvironment(null, null, null, null)).isEmpty();
        assertThat(ToolRunner.callEnvironment("", null, null, null)).isEmpty();
        assertThat(ToolRunner.callEnvironment("../projects", null, null, null)).isEmpty();
        assertThat(ToolRunner.callEnvironment(42, null, null, null)).isEmpty();
    }

    /**
     * qits-1618: with no agent claim, a desk's token (the role qits:desk-admin, or a context_kind of
     * desk-session or desk-delivery) adds its project claim as QITS_DESK_PROJECT instead.
     */
    @Test
    void aCallAddsTheDeskProjectOfADesksTokenWithNoAgent() {
        String project = "17a79d74-b3ff-40a3-9b66-bceda7435a38";
        assertThat(ToolRunner.callEnvironment(null, project, "desk-session", null))
                .isEqualTo(Map.of("QITS_DESK_PROJECT", project));
        assertThat(ToolRunner.callEnvironment(null, project, "desk-delivery", Set.of()))
                .isEqualTo(Map.of("QITS_DESK_PROJECT", project));
        assertThat(ToolRunner.callEnvironment(null, jakarta.json.Json.createValue(project), "desk-session", null))
                .isEqualTo(Map.of("QITS_DESK_PROJECT", project));
        // the role alone is enough, with no desk context_kind
        assertThat(ToolRunner.callEnvironment(null, project, null, Set.of("qits:desk-admin")))
                .isEqualTo(Map.of("QITS_DESK_PROJECT", project));
        assertThat(ToolRunner.callEnvironment(null, project, "agent-container", Set.of("qits:desk-admin")))
                .isEqualTo(Map.of("QITS_DESK_PROJECT", project));
        // an agent claim wins over a project claim, whatever the context_kind or roles say
        String agent = "07a79d74-b3ff-40a3-9b66-bceda7435a37";
        assertThat(ToolRunner.callEnvironment(agent, project, "desk-session", Set.of("qits:desk-admin")))
                .isEqualTo(Map.of("QITS_AGENT_ID", agent));
        // neither the role nor a desk context_kind: the project claim is not a desk's, so nothing
        assertThat(ToolRunner.callEnvironment(null, project, "agent-container", Set.of())).isEmpty();
        assertThat(ToolRunner.callEnvironment(null, project, null, null)).isEmpty();
        assertThat(ToolRunner.callEnvironment(null, project, null, Set.of("qits:agent"))).isEmpty();
        // not a uuid: nothing, whatever the context_kind or roles say
        assertThat(ToolRunner.callEnvironment(null, "../projects", "desk-session", Set.of("qits:desk-admin")))
                .isEmpty();
        assertThat(ToolRunner.callEnvironment(null, null, "desk-session", Set.of("qits:desk-admin"))).isEmpty();
    }

    @Test
    void theResultMapping() {
        assertThat(ToolRunner.result(0, "out", "")).isEqualTo(new ToolRunner.Result(false, List.of("out"), 0));
        assertThat(ToolRunner.result(0, "out", "a note")).isEqualTo(new ToolRunner.Result(false, List.of("out", "a note"), 0));
        assertThat(ToolRunner.result(1, "", "refused")).isEqualTo(new ToolRunner.Result(true, List.of("refused", "exit 1"), 1));
        assertThat(ToolRunner.result(3, "half", "broke"))
                .isEqualTo(new ToolRunner.Result(true, List.of("broke", "exit 3", "half"), 3));
    }
}
