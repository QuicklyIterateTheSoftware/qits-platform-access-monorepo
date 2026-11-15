package eu.wohlben.qits.cli.access.deployments;

import picocli.CommandLine;

/**
 * {@code qits deployments}: the deployer's doors. {@code settings} (epic qits-765) and {@code
 * database} (qits-2175), {@code requests} (qits-2268).
 */
@CommandLine.Command(name = "deployments", mixinStandardHelpOptions = true,
        subcommands = {SettingsCommand.class, DatabaseCommand.class, RequestsCommand.class},
        description = "The deployer, qits-deployments. `settings` reads and writes the values it hands each "
                + "application in each environment. `database` moves an application's database to another server or name, "
                + "and lists every move. `requests` approves a deployment request held for a person's approval.")
public class DeploymentsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
