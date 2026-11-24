package eu.wohlben.qits.cli.access.agents;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.access.projects.WorkCommand;
import eu.wohlben.qits.cli.session.Credential;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What the commands ask qits-agents-service, and why</b> (epic qits-2520), through {@link
 * AgentsApi}, written to {@code pacts/qits-platform-access-monorepo_qits-agents-service.json}.
 * <p>
 * One door: {@code qits work list --assigned-to-me} (and {@code --unassigned} without a project) asks
 * {@code GET /agents/api/me} who the caller is, for the name it lists the items of and the project it
 * lists them in. The provider records it with the agent's own bearer ({@code authorization}), which
 * the row sends as its {@code Authorization} header.
 */
public final class AgentsContract {

    static final String CALLING_AS_ITSELF = "a running agent calling as itself";

    /** {@code /me} as the command reads it: the name it lists by, the project it lists in. */
    private static final CliRow.Call ME = (url, recorded) -> {
        String bearer = recorded.params().get("authorization").substring("Bearer ".length());
        Credential credential = () -> {
            Reached.CREDENTIAL.bearer();
            return bearer;
        };
        JsonNode me = new AgentsApi(new PlatformClient(credential), url).me();
        assertThat(ProjectsApi.text(me, "name")).isEqualTo(recorded.params().get("name"));
        assertThat(WorkCommand.ListCommand.ownProject(me)).isNotBlank();
    };

    static final List<CliRow> ROWS = List.of(
            CliRow.of("qits work list", CALLING_AS_ITSELF, "getMe", "AgentsApi#me", ME)
                    .consumes("name", "projectId", "project")
                    .header("Authorization", "{authorization}"));

    public static final CliPact CONTRACT = CliPact.of("qits-agents-service", "qits-agents")
            .client(AgentsApi.class).rows(ROWS);

    private AgentsContract() {
    }
}
