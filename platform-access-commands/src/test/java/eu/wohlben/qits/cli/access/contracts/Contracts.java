package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.ci.CiContract;
import eu.wohlben.qits.cli.access.deployments.DeploymentsContract;
import eu.wohlben.qits.cli.access.events.EventsContract;
import eu.wohlben.qits.cli.access.idp.IdpContract;
import eu.wohlben.qits.cli.access.maintenance.MaintenanceContract;
import eu.wohlben.qits.cli.access.networkaccess.NetworkAccessContract;
import eu.wohlben.qits.cli.access.observe.ObservabilityContract;
import eu.wohlben.qits.cli.access.projects.ProjectsContract;
import eu.wohlben.qits.cli.access.publish.ArtifactsContract;
import eu.wohlben.qits.cli.access.workspaces.WorkspaceDaemonContract;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesContract;

import java.util.List;

/**
 * <b>Every provider the commands call, with its contract</b> (ticket qits-1149): one {@link CliPact}
 * each. {@code ClientCallsGuardTest} reads this list, so a provider's rows count only once it is
 * here; each also has its own {@link CliPactTest} beside its rows.
 */
public final class Contracts {

    public static final List<CliPact> ALL = List.of(
            ProjectsContract.CONTRACT,
            WorkspacesContract.CONTRACT,
            WorkspaceDaemonContract.CONTRACT,
            ArtifactsContract.CONTRACT,
            CiContract.CONTRACT,
            MaintenanceContract.CONTRACT,
            DeploymentsContract.CONTRACT,
            ObservabilityContract.CONTRACT,
            EventsContract.CONTRACT,
            IdpContract.CONTRACT,
            NetworkAccessContract.CONTRACT);

    private Contracts() {
    }
}
