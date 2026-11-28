package eu.wohlben.qits.cli.access.certificate;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What {@code qits certificate} asks qits-certificates, and why</b> (qits-2761, epic qits-2626),
 * through {@link CertificatesApi}, written to {@code
 * pacts/qits-platform-access-monorepo_qits-certificates-service.json}.
 * <p>
 * One door, issueWorkloadCertificate, in the three states the provider records: issued (qits-edge's
 * service token; the command reads the certificate, the chain, the principal and notAfter), refused
 * (a person's token, 403) and a CSR the CA does not take (400). Each row sends the recorded CSR, so
 * the request is exactly the provider's; the command makes its own key and CSR, which the CA reads
 * the same way. The provider's verification calls each state as its own caller, so no row binds a
 * bearer of its own.
 */
public final class CertificatesContract {

    static final String ISSUED = "workload certificate issued";
    static final String REFUSED = "workload certificate refused";
    static final String BAD_CSR = "workload csr refused";

    private static final CliPact BASE = CliPact.of("qits-certificates-service", "qits-certificates");

    @FunctionalInterface
    interface Call {
        void run(CertificatesApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> {
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return "contract-workload";
            };
            call.run(new CertificatesApi(new PlatformClient(credential), url), recorded.params(), recorded);
        };
    }

    private static String csr(Operation recorded) {
        return CliRow.exampleBody(recorded).path("csr").asText();
    }

    private static final Call ISSUE = (api, params, recorded) -> {
        JsonNode answer = api.issue(csr(recorded));
        assertThat(answer.path("certificate").asText()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(answer.path("chain").asText()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(answer.path("principal").asText()).isEqualTo("application:qits-edge");
        assertThat(answer.path("notAfter").asText()).isNotBlank();
    };

    private static Call refused(int status, String says) {
        return (api, params, recorded) ->
                assertThatThrownBy(() -> api.issue(csr(recorded)))
                        .isInstanceOfSatisfying(CliFailure.class, f -> {
                            assertThat(f.status()).isEqualTo(status);
                            assertThat(CertificateKeeper.permanent(f)).isTrue();
                            assertThat(f.getMessage()).contains(says);
                        });
    }

    static final List<CliRow> ROWS = List.of(
            row(ISSUED, ISSUE).consumes("certificate", "chain", "serial", "notAfter", "principal"),
            row(REFUSED, refused(403, "a person's token")).consumes("error"),
            row(BAD_CSR, refused(400, "the csr is not a PKCS#10 PEM")).consumes("error"));

    public static final CliPact CONTRACT = BASE.client(CertificatesApi.class).rows(ROWS);

    private static CliRow row(String state, Call call) {
        return CliRow.of("qits certificate issue", state, "issueWorkloadCertificate", "CertificatesApi#issue",
                api(call));
    }

    private CertificatesContract() {
    }
}
