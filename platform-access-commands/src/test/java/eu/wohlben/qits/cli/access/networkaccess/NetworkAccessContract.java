package eu.wohlben.qits.cli.access.networkaccess;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits network-access} asks qits-network-access, and why</b> (epic qits-1977): the
 * companion's image for {@code up}, the saved peer it may keep, the device it registers, the tunnel
 * it hands the companion, and {@code down --revoke}'s revoke, through {@link NetworkAccessApi},
 * written to {@code pacts/qits-platform-access-monorepo_qits-network-access-service.json}.
 * <p>
 * The tunnel answer is piped to the companion's {@code configure} whole, so its row reads every
 * field the companion validates. The create row sends the recorded body ({@code {"name",
 * "publicKey"}}) through the client.
 */
public final class NetworkAccessContract {

    /** A private edge that announced itself: the companion, and a device to register. */
    static final String EDGE = "an announced private edge";
    /** A DEVICE peer of the operator, with its tunnel behind that edge. */
    static final String DEVICE = "a device peer behind an announced private edge";

    private static final CliPact BASE = CliPact.of("qits-network-access-service", "qits-network-access");

    /** What `up` reads of a peer: whether to keep it, and what to print. */
    static final List<String> PEER = List.of("id", "name", "publicKey", "tunnelIp", "status");

    @FunctionalInterface
    interface Call {
        void run(NetworkAccessApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new NetworkAccessApi(PlatformClient.direct(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static final Call COMPANION = (api, params, recorded) -> {
        NetworkAccessCommand.Companion companion = NetworkAccessCommand.Companion.of(api.companion());
        assertThat(companion.reference()).contains(":");
        assertThat(NetworkAccessCommand.companionRun(List.of("run", "--rm"), companion, "keygen"))
                .containsSubsequence("--user", companion.user(), "--entrypoint", companion.entrypoint());
    };

    private static final Call PEER_READ = (api, params, recorded) -> {
        JsonNode peer = api.peer(params.get("peerId"));
        assertThat(peer.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(peer.path("publicKey").asText()).hasSize(44);
    };

    private static final Call CREATE = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        JsonNode peer = api.createPeer(sent.path("name").asText(), sent.path("publicKey").asText());
        assertThat(peer.path("id").asText()).isNotBlank();
    };

    private static final Call TUNNEL = (api, params, recorded) -> {
        JsonNode tunnel = api.peerTunnel(params.get("peerId"));
        assertThat(tunnel.path("peerId").asText()).isEqualTo(params.get("peerId"));
        assertThat(tunnel.path("edge").path("listenPort").asInt()).isPositive();
    };

    private static final Call REVOKE = (api, params, recorded) ->
            assertThat(api.revokePeer(params.get("peerId")).path("status").asText()).isEqualTo("REVOKED");

    static final List<CliRow> ROWS = List.of(
            row("qits network-access up", EDGE, "getCompanion", "companion", COMPANION)
                    .consumes(BASE.held(EDGE, "getCompanion", List.of("image", "version", "entrypoint", "user")))
                    .request("GET /network-access/api/companion"),
            row("qits network-access up", DEVICE, "getPeer", "peer", PEER_READ)
                    .consumes(BASE.held(DEVICE, "getPeer", PEER))
                    .request("GET /network-access/api/peers/{peerId}"),
            row("qits network-access up", EDGE, "createPeer", "createPeer", CREATE)
                    .consumes(BASE.held(EDGE, "createPeer", PEER))
                    .request("POST /network-access/api/peers"),
            row("qits network-access up", DEVICE, "getPeerTunnel", "peerTunnel", TUNNEL)
                    .consumes(BASE.held(DEVICE, "getPeerTunnel", List.of("peerId", "tunnelIp",
                            "edge.publicKey", "edge.publicAddress", "edge.listenPort", "edge.dnsAddress",
                            "edge.tunnelCidr", "edge.mtu", "edge.domain")))
                    .request("GET /network-access/api/peers/{peerId}/tunnel"),
            row("qits network-access down", DEVICE, "revokePeer", "revokePeer", REVOKE)
                    .consumes(BASE.held(DEVICE, "revokePeer", List.of("status")))
                    .request("POST /network-access/api/peers/{peerId}/revoke"));

    public static final CliPact CONTRACT = BASE.client(NetworkAccessApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "NetworkAccessApi#" + method, api(call));
    }

    private NetworkAccessContract() {
    }
}
