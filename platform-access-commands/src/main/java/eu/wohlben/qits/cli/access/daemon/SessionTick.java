package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.session.Session;

import java.util.List;

/**
 * What the session daemon looks at between refreshes, at most every {@link SessionDaemon#TICK_EVERY}
 * while it runs: today the network-access companion, whose Git line it keeps in step (qits-2743).
 * Each call gets the session the daemon holds, and returns the lines for the daemon's log, none when
 * nothing changed. Never a token.
 */
public interface SessionTick {

    SessionTick NONE = session -> List.of();

    List<String> tick(Session session) throws Exception;
}
