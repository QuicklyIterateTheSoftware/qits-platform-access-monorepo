package eu.wohlben.qits.cli.access.users;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What {@code qits users} asks the idp, and why</b> (ticket qits-1149): the rows of {@link
 * IdpUsersApi}, part of {@code idp/IdpContract}'s table. A table of its own only because the client
 * is package-private here.
 * <p>
 * The three admin doors carry the admin's bearer, which the provider state hands out as its {@code
 * authorization} param: each row sends that header, and the call's credential answers the state's
 * frozen token, so the verifier can send the one it seeds. {@code register} sends no bearer: the
 * register token in its body is the credential.
 * <p>
 * {@code qits users subjects} (qits-2725) is pending until a pinned {@code qits-idp-golden-masters}
 * records {@code listUserSubjects} in both of its states; qits-maintenance's bump brings that pin.
 */
public final class IdpUsersContract {

    /** The client, for the contract's list of clients: package-private, as the commands use it. */
    public static final Class<?> CLIENT = IdpUsersApi.class;

    static final String ADMIN = "an admin signed in to the CLI";
    static final String NO_SUCH_USER = "no user with the given name";
    /** A person whose account holds no role: the configuring reads refuse it (qits-2725). */
    static final String NOT_AN_ADMIN = "a person without the admin role signed in to the CLI";
    static final String UNSPENT_TOKEN = "an unspent register token";
    static final String NO_SUCH_TOKEN = "no register token with the given value";

    @FunctionalInterface
    interface Call {
        void run(IdpUsersApi api, Operation recorded) throws Exception;
    }

    /** The admin's api: the idp under {@code /idp}, the bearer the state's own, read through {@link Reached}. */
    private static CliRow.Call signedIn(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new IdpUsersApi(new PlatformClient(credential), url + "/idp"), recorded);
        };
    }

    private static CliRow.Call anonymous(Call call) {
        return (url, recorded) -> call.run(new IdpUsersApi(null, url + "/idp"), recorded);
    }

    /** {@code invite} prints the token and names the register token by its id. */
    private static final Call INVITE = (api, recorded) -> {
        JsonNode minted = api.invite();
        assertThat(UsersCommand.text(minted, "token")).isNotBlank();
        assertThat(UsersCommand.text(minted, "id")).isNotBlank();
    };

    /** {@code list}: each user's name, when it was created and its roles. */
    private static final Call LIST = (api, recorded) -> assertThat(api.list()).isNotEmpty().allSatisfy(user -> {
        assertThat(UsersCommand.text(user, "username")).isNotBlank();
        assertThat(UsersCommand.text(user, "createdAt")).isNotBlank();
        assertThat(user.path("roles").isArray()).isTrue();
    });

    /** {@code subjects}: each user's subject id and name (qits-2725). */
    private static final Call SUBJECTS = (api, recorded) ->
            assertThat(api.subjects()).isNotEmpty().allSatisfy(user -> {
                assertThat(UsersCommand.text(user, "subject")).isNotBlank();
                assertThat(UsersCommand.text(user, "username")).isNotBlank();
            });

    /** A caller neither qits:admin nor the desk: the idp's 403, which the command reports as its refusal. */
    private static final Call SUBJECTS_REFUSED = (api, recorded) ->
            assertThatThrownBy(api::subjects)
                    .isInstanceOfSatisfying(CliFailure.class, f -> assertThat(f.status()).isEqualTo(403));

    private static final Call DELETE = (api, recorded) -> api.delete(recorded.params().get("username"));

    /** An unknown user is the idp's 404, which the command reports as its refusal. */
    private static final Call DELETE_UNKNOWN = (api, recorded) ->
            assertThatThrownBy(() -> api.delete(recorded.params().get("username")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> assertThat(f.status()).isEqualTo(404));

    /** {@code register} prints the generated password, once, and the name the idp registered. */
    private static final Call REGISTER = (api, recorded) -> {
        JsonNode answer = api.register(recorded.params().get("username"), recorded.params().get("token"));
        assertThat(UsersCommand.text(answer, "username")).isNotBlank();
        assertThat(UsersCommand.text(answer, "password")).isNotBlank();
    };

    /** A spent or unknown token: the refusal names the idp's error, and says to ask for a new one. */
    private static final Call REGISTER_REFUSED = (api, recorded) ->
            assertThatThrownBy(() -> api.register(recorded.params().get("username"), recorded.params().get("token")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> {
                        assertThat(f.status()).isEqualTo(401);
                        assertThat(f.getMessage()).contains("invalid_credentials");
                    });

    public static final List<CliRow> ROWS = List.of(
            row("qits users invite", ADMIN, "mintRegisterToken", "invite", signedIn(INVITE)).consumes("id", "token")
                    .header("Authorization", "{authorization}"),
            row("qits users list", ADMIN, "listUsers", "list", signedIn(LIST))
                    .consumes("[].username", "[].createdAt", "[].roles").header("Authorization", "{authorization}"),
            row("qits users subjects", ADMIN, "listUserSubjects", "subjects", signedIn(SUBJECTS))
                    .consumes("[].subject", "[].username").header("Authorization", "{authorization}")
                    .request("GET /idp/api/user-subjects"),
            row("qits users subjects", NOT_AN_ADMIN, "listUserSubjects", "subjects", signedIn(SUBJECTS_REFUSED))
                    .consumes("error", "error_description").header("Authorization", "{authorization}")
                    .request("GET /idp/api/user-subjects"),
            row("qits users delete", ADMIN, "deleteUser", "delete", signedIn(DELETE))
                    .header("Authorization", "{authorization}"),
            row("qits users delete", NO_SUCH_USER, "deleteUser", "delete", signedIn(DELETE_UNKNOWN))
                    .header("Authorization", "{authorization}"),
            row("qits users register", UNSPENT_TOKEN, "register", "register", anonymous(REGISTER))
                    .consumes("username", "password"),
            row("qits users register", NO_SUCH_TOKEN, "register", "register", anonymous(REGISTER_REFUSED))
                    .consumes("error", "error_description"));

    private static CliRow row(String command, String state, String operationId, String method, CliRow.Call call) {
        return CliRow.of(command, state, operationId, "IdpUsersApi#" + method, call);
    }

    private IdpUsersContract() {
    }
}
