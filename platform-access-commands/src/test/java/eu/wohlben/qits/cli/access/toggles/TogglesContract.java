package eu.wohlben.qits.cli.access.toggles;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What {@code qits toggles} asks qits-toggles, and why</b> (qits-2704, epic qits-2542), through
 * {@link TogglesApi}, written to {@code pacts/qits-platform-access-monorepo_qits-toggles-service.json}.
 * <p>
 * Exactly the three operations the landing app's Toggles section calls (getToggles, getToggle,
 * putToggleRules): qits-ci's pact parity check holds both consumers of the provider to the same set.
 * The provider records every state with the caller's bearer ({@code authorization}), which each row
 * sends as its {@code Authorization} header. The refusals are rows too, because the command prints
 * the service's own sentence: 400 malformed rules, 403 an agent, 404 an unknown slug, 409 a stale
 * version.
 */
public final class TogglesContract {

    static final String ASKED_BY_AN_ADMIN = "a toggle is declared, asked by an admin";
    static final String ASKED_BY_AN_AGENT = "a toggle is declared, asked by an agent";
    static final String MALFORMED_RULES = "a toggle is declared, sent malformed rules by an admin";
    static final String STALE_VERSION = "a toggle's rules changed since an admin read them";
    static final String NOT_DECLARED = "a toggle slug is not declared";

    private static final CliPact BASE = CliPact.of("qits-toggles-service", "qits-toggles");

    /**
     * One toggle as {@code TogglesCommand.printToggle} prints it, but for a rule's {@code userSubjects}
     * and {@code afterInstant}: each is null on every rule but its own kind, and qits-pact-consumer
     * cannot yet express an array or an instant that is null in some elements of a list. The command
     * prints both as text and an absent one as nothing, so not binding them costs no failure mode.
     */
    static final List<String> TOGGLE = List.of("slug", "type", "description", "declaredDefault", "firstDeclaredAt",
            "version", "lastUsedAt", "removalTicket", "rules[].kind", "rules[].value");

    /** The list as {@code TogglesCommand.printToggles} prints it: the rules are only counted. */
    static final List<String> TOGGLES = List.of("[].slug", "[].declaredDefault", "[].version", "[].rules",
            "[].lastUsedAt", "[].removalTicket");

    @FunctionalInterface
    interface Call {
        void run(TogglesApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    /** The api with the state's own bearer, read through {@link Reached}. */
    private static CliRow.Call api(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new TogglesApi(new PlatformClient(credential), url), recorded.params(), recorded);
        };
    }

    private static String printed(Consumer<PrintStream> print) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        print.accept(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static final Call LIST = (api, params, recorded) -> {
        JsonNode toggles = api.list();
        assertThat(toggles).isNotEmpty().allSatisfy(t -> assertThat(t.path("slug").asText()).isNotBlank());
        assertThat(printed(out -> TogglesCommand.printToggles(out, toggles))).contains("SLUG")
                .contains(params.get("slug"));
    };

    private static final Call SHOW = (api, params, recorded) -> {
        JsonNode toggle = api.get(params.get("slug"));
        assertThat(toggle.path("slug").asText()).isEqualTo(params.get("slug"));
        assertThat(toggle.path("rules")).isNotEmpty();
        assertThat(printed(out -> TogglesCommand.printToggle(out, toggle))).contains("KIND").contains("STATIC");
    };

    private static final Call SHOW_UNKNOWN = (api, params, recorded) ->
            assertThatThrownBy(() -> api.get(params.get("slug")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> {
                        assertThat(f.status()).isEqualTo(404);
                        assertThat(f.getMessage()).contains(params.get("slug"));
                    });

    private static final Call SET_RULES = (api, params, recorded) -> {
        JsonNode toggle = api.putRules(params.get("slug"), CliRow.exampleBody(recorded));
        assertThat(toggle.path("version").asLong()).isPositive();
        assertThat(printed(out -> TogglesCommand.printToggle(out, toggle))).contains("KIND").contains("USER");
    };

    /**
     * A refusal: its status, exit 1, and {@code says} (a piece of the service's own sentence, or null
     * where the door says none) in what the command prints.
     */
    private static Call refused(int status, String says) {
        return (api, params, recorded) ->
                assertThatThrownBy(() -> api.putRules(params.get("slug"), CliRow.exampleBody(recorded)))
                        .isInstanceOfSatisfying(CliFailure.class, f -> {
                            assertThat(f.status()).isEqualTo(status);
                            assertThat(f.exitCode()).isEqualTo(CliFailure.FAILED);
                            String printed = TogglesCommand.SetRulesCommand.explained(params.get("slug"), f)
                                    .getMessage();
                            assertThat(printed).contains("HTTP " + status);
                            if (says != null) {
                                assertThat(printed).contains(says);
                            }
                        });
    }

    static final List<CliRow> ROWS = List.of(
            row("qits toggles list", ASKED_BY_AN_ADMIN, "getToggles", "list", LIST)
                    .consumes(TOGGLES)
                    // Toggles come and go with their declarations, and their rules with each edit.
                    .anyLength("$", "[].rules"),
            row("qits toggles show", ASKED_BY_AN_ADMIN, "getToggle", "get", SHOW)
                    .consumes(BASE.held(ASKED_BY_AN_ADMIN, "getToggle", TOGGLE))
                    .anyLength("rules"),
            row("qits toggles show", NOT_DECLARED, "getToggle", "get", SHOW_UNKNOWN)
                    .consumes("error"),
            row("qits toggles set-rules", ASKED_BY_AN_ADMIN, "putToggleRules", "putRules", SET_RULES)
                    .consumes(BASE.held(ASKED_BY_AN_ADMIN, "putToggleRules", TOGGLE))
                    .anyLength("rules"),
            row("qits toggles set-rules", MALFORMED_RULES, "putToggleRules", "putRules", refused(400, "the STATIC rule must be the last one"))
                    .consumes("error"),
            row("qits toggles set-rules", ASKED_BY_AN_AGENT, "putToggleRules", "putRules", refused(403, null)),
            row("qits toggles set-rules", STALE_VERSION, "putToggleRules", "putRules", refused(409, "changed since version"))
                    .consumes("error"));

    public static final CliPact CONTRACT = BASE.client(TogglesApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "TogglesApi#" + method, api(call))
                .header("Authorization", "{authorization}");
    }

    private TogglesContract() {
    }
}
