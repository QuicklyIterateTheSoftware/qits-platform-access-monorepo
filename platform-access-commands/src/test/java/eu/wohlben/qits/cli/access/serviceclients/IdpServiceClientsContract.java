package eu.wohlben.qits.cli.access.serviceclients;

import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits service-clients} asks the idp, and why</b> (qits-2531): the row of {@link
 * IdpServiceClientsApi}, part of {@code idp/IdpContract}'s table. A table of its own only because
 * the client is package-private here, as {@code users/IdpUsersContract} is.
 * <p>
 * The row sends {@code Authorization: {authorization}}, the admin's CLI bearer the provider state
 * hands out, and the call's credential answers the state's frozen token. Pending until the pinned
 * {@code qits-idp-golden-masters} records the state.
 */
public final class IdpServiceClientsContract {

    /** The client, for the contract's list of clients: package-private, as the commands use it. */
    public static final Class<?> CLIENT = IdpServiceClientsApi.class;

    static final String READ_BY_AN_ADMIN = "a database service client, read by an admin signed in to the CLI";

    @FunctionalInterface
    interface Call {
        void run(IdpServiceClientsApi api, Operation recorded) throws Exception;
    }

    /** The admin's api: the idp under {@code /idp}, the bearer the state's own, read through {@link Reached}. */
    private static CliRow.Call signedIn(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new IdpServiceClientsApi(new PlatformClient(credential), url + "/idp"), recorded);
        };
    }

    /** {@code list}: each client's id, when it was created, and when its secret was rotated. */
    private static final Call LIST = (api, recorded) -> assertThat(api.list()).isNotEmpty().allSatisfy(client -> {
        assertThat(ServiceClientsCommand.text(client, "clientId")).isNotBlank();
        assertThat(ServiceClientsCommand.text(client, "createdAt")).isNotBlank();
        assertThat(client.has("rotatedAt")).isTrue();
    });

    public static final List<CliRow> ROWS = List.of(
            CliRow.of("qits service-clients list", READ_BY_AN_ADMIN, "listServiceClients",
                            "IdpServiceClientsApi#list", signedIn(LIST))
                    .consumes("[].clientId", "[].createdAt", "[].rotatedAt")
                    .header("Authorization", "{authorization}")
                    .request("GET /idp/api/service-clients"));

    private IdpServiceClientsContract() {
    }
}
