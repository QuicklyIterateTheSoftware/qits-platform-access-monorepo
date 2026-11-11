package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits checkout}: a workstation clone of a wrapper, kept on {@code main}. Today that is one
 * leaf, {@code sync}. Pure git on the caller's machine, so the MCP service does not serve it; the
 * release-following side is {@code qits checkout-daemon}.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "checkout", mixinStandardHelpOptions = true,
        subcommands = {CheckoutSyncCommand.class},
        description = "A workstation clone of a wrapper, every submodule on its own main: `sync` fast-forwards "
                + "it to origin/main. A checkout that follows releases is `qits checkout-daemon`'s.")
public class CheckoutCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
