package eu.wohlben.qits.cli.access.remembered;

import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits remembered-devices} asks the idp, and why</b> (qits-1944): the rows of {@link
 * IdpRememberedDevicesApi}, part of {@code idp/IdpContract}'s table. A table of its own only
 * because the client is package-private here, as {@code users/IdpUsersContract} is.
 * <p>
 * Both doors are a person's: the idp admits a CLI bearer ({@code credential_type=cli}) and refuses
 * any other with 403. So each row sends {@code Authorization: {authorization}}, the person's CLI
 * bearer the provider state {@code a remembered device} hands out as its {@code authorization}
 * param, and the call's credential answers the state's frozen token, so the verifier can send the
 * one it seeds. A browser's cookie, which the state also records with, is never what the CLI sends.
 * <p>
 * Pending until the pinned {@code qits-idp-golden-masters} records the state with that param.
 */
public final class IdpRememberedDevicesContract {

    /** The client, for the contract's list of clients: package-private, as the commands use it. */
    public static final Class<?> CLIENT = IdpRememberedDevicesApi.class;

    static final String A_REMEMBERED_DEVICE = "a remembered device";

    @FunctionalInterface
    interface Call {
        void run(IdpRememberedDevicesApi api, Operation recorded) throws Exception;
    }

    /** The person's api: the idp under {@code /idp}, the bearer the state's own, read through {@link Reached}. */
    private static CliRow.Call asThePerson(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new IdpRememberedDevicesApi(new PlatformClient(credential), url + "/idp"), recorded);
        };
    }

    /** {@code list}: each device's id, label, when it was last used, when it expires, and whether it is this one. */
    private static final Call LIST = (api, recorded) -> assertThat(api.list()).isNotEmpty().allSatisfy(device -> {
        assertThat(RememberedDevicesCommand.text(device, "id")).isNotBlank();
        assertThat(device.path("label").isTextual()).isTrue();
        assertThat(RememberedDevicesCommand.text(device, "lastUsedAt")).isNotBlank();
        assertThat(RememberedDevicesCommand.text(device, "expiresAt")).isNotBlank();
        assertThat(device.path("current").isBoolean()).isTrue();
    });

    private static final Call REVOKE = (api, recorded) -> api.revoke(recorded.params().get("deviceId"));

    public static final List<CliRow> ROWS = List.of(
            row("qits remembered-devices list", "listRememberedDevices", "list", asThePerson(LIST))
                    .consumes("[].id", "[].label", "[].lastUsedAt", "[].expiresAt", "[].current")
                    .header("Authorization", "{authorization}")
                    .request("GET /idp/api/remembered-devices"),
            row("qits remembered-devices revoke", "revokeRememberedDevice", "revoke", asThePerson(REVOKE))
                    .header("Authorization", "{authorization}")
                    .request("DELETE /idp/api/remembered-devices/{deviceId}"));

    private static CliRow row(String command, String operationId, String method, CliRow.Call call) {
        return CliRow.of(command, A_REMEMBERED_DEVICE, operationId, "IdpRememberedDevicesApi#" + method, call);
    }

    private IdpRememberedDevicesContract() {
    }
}
