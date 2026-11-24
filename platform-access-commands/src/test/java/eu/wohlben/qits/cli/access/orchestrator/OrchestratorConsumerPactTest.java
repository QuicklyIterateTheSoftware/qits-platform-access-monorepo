package eu.wohlben.qits.cli.access.orchestrator;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-orchestrator contract: {@link OrchestratorContract}'s rows. See {@link CliPactTest}. */
class OrchestratorConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return OrchestratorContract.CONTRACT;
    }
}
