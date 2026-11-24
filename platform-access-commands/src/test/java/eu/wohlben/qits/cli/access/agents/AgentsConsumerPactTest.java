package eu.wohlben.qits.cli.access.agents;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-agents contract: {@link AgentsContract}'s rows. See {@link CliPactTest}. */
class AgentsConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return AgentsContract.CONTRACT;
    }
}
