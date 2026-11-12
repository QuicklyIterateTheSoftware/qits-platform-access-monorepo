package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** In one JVM the daemon's lock is its semaphore; SessionDaemonTest proves the fcntl side between processes. */
class DaemonProbeTest {

    @TempDir
    Path home;

    @Test
    void aHeldDaemonLockIsARunningDaemon() throws Exception {
        SessionFile store = new SessionFile(home.resolve("qits"));
        Optional<ExclusiveLock> daemon = store.tryLockForDaemon();
        assertThat(daemon).isPresent();

        assertThat(DaemonProbe.running(store)).isTrue();

        daemon.get().close();
        assertThat(DaemonProbe.running(store)).isFalse();
        // The probe let go of the lock: a daemon starting now gets it.
        Optional<ExclusiveLock> next = store.tryLockForDaemon();
        assertThat(next).isPresent();
        next.get().close();
    }

    @Test
    void noSessionDirectoryIsNoDaemon() {
        assertThat(DaemonProbe.running(new SessionFile(home.resolve("absent")))).isFalse();
        assertThat(home.resolve("absent")).doesNotExist();
    }
}
