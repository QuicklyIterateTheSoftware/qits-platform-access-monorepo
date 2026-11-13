package eu.wohlben.qits.cli.access.deployments;

import picocli.CommandLine;

/**
 * {@code qits deployments}: the deployer's doors. {@code settings} (epic qits-765) and {@code
 * database} (qits-2175).
 */
@CommandLine.Command(name = "deployments", mixinStandardHelpOptions = true,
        subcommands = {SettingsCommand.class, DatabaseCommand.class},
        description = "The deployer, qits-deployments. `settings` reads and writes the values it hands each "
                + "application in each environment. `database` moves an application's database to another server or name, "
                + "and lists every move.")
public class DeploymentsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
