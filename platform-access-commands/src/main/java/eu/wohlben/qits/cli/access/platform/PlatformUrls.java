package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.session.PlatformEndpoints;

import java.util.Map;

/**
 * Where a platform service is, as a base URL without its path prefix ({@code
 * https://projects.qits.<QITS_DOMAIN>}). The one place that decides it.
 * <p>
 * Order: the command's flag, else {@code QITS_<APP>_URL}, else whatever {@link PlatformEndpoints}
 * works out — the public vhost, in every home (from the session on a workstation, from
 * {@code QITS_DOMAIN} inside the platform). The commands
 * keep their own flags and variables, and the addresses themselves are decided in one class.
 */
public final class PlatformUrls {

    private PlatformUrls() {
    }

    public static String projects(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("projects", "--projects-url", "QITS_PROJECTS_URL", flag, env, idpUrl);
    }

    public static String events(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("events", "--events-url", "QITS_EVENTS_URL", flag, env, idpUrl);
    }

    public static String observability(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("observability", "--observability-url", "QITS_OBSERVABILITY_URL", flag, env, idpUrl);
    }

    public static String ci(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("ci", "--ci-url", "QITS_CI_URL", flag, env, idpUrl);
    }

    public static String maintenance(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("maintenance", "--maintenance-url", "QITS_MAINTENANCE_URL", flag, env, idpUrl);
    }

    /** qits-deployments: the settings doors under {@code /deployments/api/settings}. */
    public static String deployments(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("deployments", "--deployments-url", "QITS_DEPLOYMENTS_URL", flag, env, idpUrl);
    }

    public static String workspaces(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("workspaces", "--workspaces-url", "QITS_WORKSPACES_URL", flag, env, idpUrl);
    }

    /** qits-system-service: the targets and leases doors under {@code /system/api}. */
    public static String system(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("system", "--system-url", "QITS_SYSTEM_URL", flag, env, idpUrl);
    }

    /** qits-network-access: the peers and the companion under {@code /network-access/api} (epic qits-1977). */
    public static String networkAccess(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("network-access", "--network-access-url", "QITS_NETWORK_ACCESS_URL", flag, env, idpUrl);
    }

    /**
     * qits-artifacts, which answers on the host {@code registry}: {@code
     * https://registry.qits.<QITS_DOMAIN>}. {@code QITS_ARTIFACTS_URL} is the variable install.sh reads
     * for the same address.
     */
    public static String artifacts(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("registry", "--artifacts-url", "QITS_ARTIFACTS_URL", flag, env, idpUrl);
    }

    /** qits-mirror, the pull-through caches of npmjs and Maven Central: {@code https://mirror.qits.<QITS_DOMAIN>}. */
    public static String mirror(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("mirror", "--mirror-url", "QITS_MIRROR_URL", flag, env, idpUrl);
    }

    /** The git host's origin, which Git pushes to through the edge. */
    public static String gitHost(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("githost", "--git-host", "QITS_GIT_HOST_URL", flag, env, idpUrl);
    }

    /** The landing app, which a person's browser opens: {@code https://qits.<QITS_DOMAIN>}. */
    public static String landing(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        if (!blank(flag)) {
            return trim(flag);
        }
        String configured = env.get("QITS_LANDING_URL");
        if (!blank(configured)) {
            return trim(configured);
        }
        return new PlatformEndpoints(Mode.of(env), env, idpUrl).landing();
    }

    static String resolve(String app, String flagName, String variable, String flag, Map<String, String> env,
                          String idpUrl) throws CliFailure {
        if (!blank(flag)) {
            return trim(flag);
        }
        String configured = env.get(variable);
        if (!blank(configured)) {
            return trim(configured);
        }
        return new PlatformEndpoints(Mode.of(env), env, idpUrl).base(app,
                "Cannot tell the " + app + " address from the idp address " + idpUrl
                        + ". Pass " + flagName + " or set " + variable + ".");
    }

    private static String trim(String url) {
        String stripped = url.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
