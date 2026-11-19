package eu.wohlben.qits.cli.access.networkaccess;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;

/**
 * The MTU of the HOST network interface that routes toward an address — what {@code up} hands the
 * companion container as {@code QITS_COMPANION_LINK_MTU}, raw, for the companion to turn into
 * {@code min(edge MTU, link MTU - 80)} itself. Inside the companion's container every interface
 * reads the container runtime's default, usually 1500, no matter what the host
 * actually carries packets over; a WSL2 workstation is the case that bites, because its own
 * {@code eth0} is lowered to match whatever MTU a Windows VPN client negotiated, and nothing
 * running inside a container can see that. Only a probe run on the host, before docker is even
 * involved, has a chance to.
 */
interface LinkMtu {

    /** What probing the link came to: a measurement, or why not, never thrown. */
    sealed interface Measurement {

        /** The interface the kernel would send through, and its MTU. */
        record Measured(String interfaceName, int mtu) implements Measurement {
        }

        /** Nothing could be measured. Worth a warning, never a failure: the companion falls back. */
        record Unmeasurable(String reason) implements Measurement {
        }
    }

    /**
     * The host link's MTU toward {@code host:port}. Never sends a packet: connecting a UDP socket
     * only asks the kernel which route and local address it would use for that destination, and
     * {@link NetworkInterface#getByInetAddress} reads the interface back off that local address.
     */
    Measurement measure(String host, int port);

    /** What {@code up} asks in production; a test replaces {@code UpCommand.LINK_MTU} with a fake. */
    LinkMtu DEFAULT = (host, port) -> {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(host, port));
            InetAddress local = socket.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) {
                return new Measurement.Unmeasurable(
                        "the kernel named no local address for " + host + ":" + port);
            }
            NetworkInterface nif = NetworkInterface.getByInetAddress(local);
            if (nif == null) {
                return new Measurement.Unmeasurable("no network interface answers to " + local.getHostAddress());
            }
            int mtu = nif.getMTU();
            if (mtu <= 0) {
                return new Measurement.Unmeasurable(nif.getName() + " reports no usable MTU");
            }
            return new Measurement.Measured(nif.getName(), mtu);
        } catch (IOException | IllegalArgumentException | SecurityException unmeasurable) {
            String reason = unmeasurable.getMessage();
            return new Measurement.Unmeasurable(reason == null || reason.isBlank()
                    ? unmeasurable.getClass().getSimpleName() : reason);
        }
    };
}
