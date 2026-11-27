package eu.wohlben.qits.cli.access.networkaccess;

import eu.wohlben.qits.cli.access.daemon.SessionTick;
import eu.wohlben.qits.cli.access.git.GitCompanionLine;
import eu.wohlben.qits.cli.access.git.GitOrigin;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.session.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code qits session-daemon}'s eye on the network-access companion (qits-2743): it keeps the git
 * host's Git proxy line ({@link GitCompanionLine}) in step with the companion for as long as the
 * daemon runs, so no person has to.
 * <p>
 * It syncs the line at the daemon's start, whenever {@code access.json} changes ({@code up} wrote
 * a proxy, {@code down} dropped it, {@code --revoke} deleted the file), and each time the companion
 * answers healthy again after it did not: a reconnect, which also puts back a line somebody removed.
 * Between those it does nothing but read {@code access.json} and, while that names a proxy, ask the
 * companion's {@code /healthz} on loopback. The line follows {@code access.json}, not health: a
 * companion that is down for a moment keeps it, one taken down for good loses it.
 * <p>
 * The daemon runs as the person, so {@code --global} is the person's Git config. A missing git is
 * said once, not every look.
 */
public final class CompanionGitWatch implements SessionTick {

    /** Whether the companion at {@code proxy} answers healthy. Replaced by tests. */
    interface Health {
        boolean healthy(String proxy) throws InterruptedException;
    }

    private final Path directory;
    private final Map<String, String> env;
    private final Health health;

    private boolean started;
    private String seenState;
    private boolean wasHealthy;
    private boolean warnedNoGit;

    public CompanionGitWatch(Path directory, Map<String, String> env) {
        this(directory, env, proxy -> CompanionHealth.ask(proxy).map(CompanionHealth.Answer::healthy).orElse(false));
    }

    CompanionGitWatch(Path directory, Map<String, String> env, Health health) {
        this.directory = directory;
        this.env = env;
        this.health = health;
    }

    @Override
    public List<String> tick(Session session) throws InterruptedException {
        String state = raw();
        String proxy = AccessState.readQuietly(directory).map(AccessState::proxy)
                .filter(value -> !value.isBlank()).orElse(null);
        boolean healthy = proxy != null && health.healthy(proxy);
        boolean reconnected = healthy && !wasHealthy;
        boolean changed = !state.equals(seenState);
        wasHealthy = healthy;
        if (started && !changed && !reconnected) {
            return List.of();
        }
        String origin;
        try {
            origin = GitOrigin.normalize(PlatformUrls.gitHost(null, env, session.idpUrl()));
        } catch (CliFailure | IllegalArgumentException noGitHost) {
            // Looked again next time: the session or QITS_GIT_HOST_URL may name it then.
            return List.of();
        }
        started = true;
        seenState = state;
        GitCompanionLine.Result result = GitCompanionLine.sync(origin, proxy, env);
        List<String> lines = new ArrayList<>();
        if (result.change() == GitCompanionLine.Change.NO_GIT) {
            if (!warnedNoGit) {
                result.line(proxy).ifPresent(lines::add);
                warnedNoGit = true;
            }
        } else {
            warnedNoGit = false;
            result.line(proxy).ifPresent(lines::add);
        }
        return lines;
    }

    /** access.json as it is on disk, or empty when there is none. */
    private String raw() {
        try {
            return Files.readString(directory.resolve(AccessState.FILE_NAME), StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return "";
        } catch (IOException | RuntimeException unreadable) {
            return "?";
        }
    }
}
