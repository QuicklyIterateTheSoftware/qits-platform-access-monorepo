package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.SessionFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Whether a {@code qits session-daemon} runs for this session directory: it holds {@code
 * session-daemon.lock} for its whole life, so a lock that cannot be taken now means one runs.
 * Another process's daemon holds the fcntl lock; one in this process holds the lock's semaphore.
 * <p>
 * Taking the lock to look is a race: a daemon that starts in the instant the probe holds it finds
 * the lock taken and exits 1. The systemd unit's {@code Restart=on-failure} starts it again within
 * 30 seconds, and a daemon started by hand is started again by the person who sees it stop.
 */
public final class DaemonProbe {

    private DaemonProbe() {
    }

    /** True when a daemon holds the lock. A directory that is not there has no daemon. */
    public static boolean running(SessionFile store) {
        if (!Files.isDirectory(store.directory())) {
            return false;
        }
        Optional<ExclusiveLock> free;
        try {
            free = ExclusiveLock.tryAcquire(store.daemonLockPath());
        } catch (IOException e) {
            // Cannot tell. Saying none runs gets the person told how to start one.
            return false;
        }
        free.ifPresent(ExclusiveLock::close);
        return free.isEmpty();
    }
}
