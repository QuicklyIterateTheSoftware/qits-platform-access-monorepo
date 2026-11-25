package eu.wohlben.qits.cli.access.egress;

import picocli.CommandLine;

/**
 * {@code qits egress}: the egress a repository has declared, as qits-deployments reads it off every
 * deployed version (qits-2611, qits-2616). {@code grants} is its only group today.
 */
@CommandLine.Command(name = "egress", mixinStandardHelpOptions = true,
        subcommands = {GrantsCommand.class},
        description = "The outbound routes a repository has declared it needs. grants reads them.")
public class EgressCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
