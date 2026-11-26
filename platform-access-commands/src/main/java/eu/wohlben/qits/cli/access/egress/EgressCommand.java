package eu.wohlben.qits.cli.access.egress;

import picocli.CommandLine;

/**
 * {@code qits egress}: the egress a repository has declared, as qits-deployments reads it off every
 * deployed version (qits-2611, qits-2616), and {@code forward}, the local proxy address that carries
 * a container's outbound connections to qits-egress (epic qits-2525).
 */
@CommandLine.Command(name = "egress", mixinStandardHelpOptions = true,
        subcommands = {GrantsCommand.class, ForwardCommand.class},
        description = "Outbound access through qits-egress. grants reads the routes a repository has declared it "
                + "needs; forward carries this machine's outbound connections to the egress proxy.")
public class EgressCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
