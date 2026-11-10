package eu.wohlben.qits.cli.access.networkaccess;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-network-access contract: {@link NetworkAccessContract}'s rows. See {@link CliPactTest}. */
class NetworkAccessConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return NetworkAccessContract.CONTRACT;
    }
}
