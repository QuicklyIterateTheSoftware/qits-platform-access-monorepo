package eu.wohlben.qits.cli.access.toggles;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-toggles contract: {@link TogglesContract}'s rows. See {@link CliPactTest}. */
class TogglesConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return TogglesContract.CONTRACT;
    }
}
