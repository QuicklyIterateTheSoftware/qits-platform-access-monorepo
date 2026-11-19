package eu.wohlben.qits.cli.access.networkaccess;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LinkMtu#DEFAULT} against the real network stack: no fake, because a UDP {@code connect}
 * sends nothing and needs no server on the other end — the kernel answers from its routing table
 * alone.
 */
class LinkMtuTest {

    @Test
    void loopbackHasARealMtu() {
        LinkMtu.Measurement measured = LinkMtu.DEFAULT.measure("127.0.0.1", 12345);

        assertThat(measured).isInstanceOf(LinkMtu.Measurement.Measured.class);
        LinkMtu.Measurement.Measured found = (LinkMtu.Measurement.Measured) measured;
        assertThat(found.mtu()).isPositive();
        // Documented as the usual loopback MTU; asserted loosely because it is the kernel's
        // interface, not this command's, that decides it.
        assertThat(found.mtu()).isGreaterThanOrEqualTo(1500);
        assertThat(found.interfaceName()).isNotBlank();
    }

    @Test
    void anUnresolvableHostIsUnmeasurable() {
        LinkMtu.Measurement measured =
                LinkMtu.DEFAULT.measure("this-host-does-not-resolve.invalid", 53);

        assertThat(measured).isInstanceOf(LinkMtu.Measurement.Unmeasurable.class);
        assertThat(((LinkMtu.Measurement.Unmeasurable) measured).reason()).isNotBlank();
    }
}
