package eu.wohlben.qits.cli.access.certificate;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-certificates contract: {@link CertificatesContract}'s rows. See {@link CliPactTest}. */
class CertificatesConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return CertificatesContract.CONTRACT;
    }
}
