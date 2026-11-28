package eu.wohlben.qits.cli.access.certificate;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.session.PrivateFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * <b>A workload certificate on disk, and its renewal</b> (epic qits-2626, qits-2761): what {@code
 * qits certificate issue} does once and {@code keep} does until it is stopped.
 *
 * <ul>
 *   <li>Each issue makes a new EC P-256 key ({@link WorkloadKey}) and sends only its CSR. The answer
 *       must be for that key.
 *   <li>Files in {@code dir} (0700): {@value #KEY} (0600), {@value #CERT} and {@value #CHAIN}, each
 *       written to a temporary file and renamed into place ({@link PrivateFiles}), the key first, so
 *       there is never a moment without them and never half a file.
 *   <li>{@code keep} renews at 2/3 of the certificate's remaining life, give or take 10%; a failed
 *       renewal keeps the files and retries after 30 s, doubling to 10 min. A refusal the door
 *       will repeat (400, 401, 403) ends it with exit 1.
 *   <li>One line per issue on the log, naming the principal, serial and notAfter, and one per
 *       failure streak. Never the key, the CSR or the bearer.
 * </ul>
 */
final class CertificateKeeper {

    static final String KEY = "key.pem";
    static final String CERT = "cert.pem";
    static final String CHAIN = "chain.pem";

    static final double RENEW_AT = 2.0 / 3.0;
    static final double JITTER = 0.1;

    /** What one issue wrote. */
    record Issued(String principal, String serial, Instant notAfter) {
    }

    @FunctionalInterface
    interface Door {
        JsonNode issue(String csrPem) throws CliFailure, InterruptedException;
    }

    private final Door door;
    private final Path dir;
    private final Clock clock;
    private final Sleeper sleeper;
    private final PrintStream log;
    private final DoubleSupplier random;
    private final Duration retry;
    private final Duration retryMax;

    private volatile boolean stopped;

    CertificateKeeper(Door door, Path dir, Clock clock, Sleeper sleeper, PrintStream log, DoubleSupplier random,
            Duration retry, Duration retryMax) {
        this.door = door;
        this.dir = dir;
        this.clock = clock;
        this.sleeper = sleeper;
        this.log = log;
        this.random = random;
        this.retry = retry;
        this.retryMax = retryMax;
    }

    /** One certificate into {@code dir}. */
    Issued issue() throws CliFailure, InterruptedException {
        WorkloadKey key;
        String csr;
        try {
            key = WorkloadKey.generate();
            csr = key.csrPem();
        } catch (GeneralSecurityException e) {
            throw new CliFailure("No key could be made on this machine: " + e.getClass().getSimpleName() + ".",
                    CliFailure.FAILED);
        }
        JsonNode answer = door.issue(csr);
        String chainPem = text(answer, "chain");
        String certificatePem = text(answer, "certificate");
        List<X509Certificate> chain = certificates(chainPem.isEmpty() ? certificatePem : chainPem);
        List<X509Certificate> leaf = certificates(certificatePem.isEmpty() ? chainPem : certificatePem);
        if (chain.isEmpty() || leaf.isEmpty()) {
            throw new CliFailure("The certificates service answered no certificate.", CliFailure.FAILED);
        }
        if (!Arrays.equals(leaf.get(0).getPublicKey().getEncoded(), key.publicKey().getEncoded())) {
            throw new CliFailure("The certificates service answered a certificate for another key.",
                    CliFailure.FAILED);
        }
        try {
            PrivateFiles.writeAtomically(dir, KEY, key.keyPem());
            PrivateFiles.writeAtomically(dir, CERT,
                    (certificatePem.isEmpty() ? WorkloadKey.pem("CERTIFICATE", encoded(leaf.get(0))) : certificatePem)
                            .getBytes(StandardCharsets.US_ASCII));
            PrivateFiles.writeAtomically(dir, CHAIN,
                    (chainPem.isEmpty() ? certificatePem : chainPem).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new CliFailure("Cannot write the certificate into " + dir + ": " + e.getMessage(), CliFailure.FAILED);
        }
        Issued issued = new Issued(SafeText.line(text(answer, "principal")),
                leaf.get(0).getSerialNumber().toString(16), leaf.get(0).getNotAfter().toInstant());
        log.println("Workload certificate for " + issued.principal() + " written to " + dir + ": serial "
                + issued.serial() + ", notAfter " + issued.notAfter());
        return issued;
    }

    /** Issue, then renew until {@link #stop}. 0 when stopped, 1 on a refusal the door will repeat. */
    int keep() throws InterruptedException {
        int failures = 0;
        Instant held = null;
        while (!stopped) {
            Duration pause;
            try {
                Issued issued = issue();
                if (failures > 0) {
                    log.println("Workload certificates are issued again.");
                }
                failures = 0;
                held = issued.notAfter();
                pause = renewal(issued.notAfter());
            } catch (CliFailure failure) {
                if (permanent(failure)) {
                    log.println(failure.getMessage());
                    log.println("The certificates service will refuse this token every time; stopping. "
                            + (held == null ? "No certificate was written." : "The files stay until " + held + "."));
                    return CliFailure.FAILED;
                }
                failures++;
                pause = backoff(failures);
                if (failures == 1) {
                    log.println("A workload certificate could not be had: " + failure.getMessage() + " "
                            + (held != null && held.isAfter(clock.instant())
                                    ? "The files stay; they are valid until " + held + "."
                                    : "No valid certificate is on disk.")
                            + " Retrying in " + pause.toSeconds() + " s; said once until it works again.");
                }
            }
            if (stopped) {
                break;
            }
            sleeper.sleep(pause);
        }
        log.println("Stopped.");
        return 0;
    }

    void stop() {
        stopped = true;
        sleeper.wake();
    }

    Duration renewal(Instant notAfter) {
        long life = Duration.between(clock.instant(), notAfter).toMillis();
        double factor = 1 + JITTER * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.max((long) (life * RENEW_AT * factor), 1000));
    }

    Duration backoff(int failuresInARow) {
        Duration pause = retry;
        for (int i = 1; i < failuresInARow && pause.compareTo(retryMax) < 0; i++) {
            pause = pause.multipliedBy(2);
        }
        return pause.compareTo(retryMax) > 0 ? retryMax : pause;
    }

    /** 400 (a CSR the CA never takes), 401 (no valid token), 403 (a token that may never have one). */
    static boolean permanent(CliFailure failure) {
        int status = failure.status();
        return status == 400 || status == 401 || status == 403 || failure.exitCode() == CliFailure.USAGE;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static List<X509Certificate> certificates(String pem) throws CliFailure {
        List<X509Certificate> parsed = new ArrayList<>();
        if (pem.isEmpty()) {
            return parsed;
        }
        try {
            for (Certificate certificate : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
                if (certificate instanceof X509Certificate x509) {
                    parsed.add(x509);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new CliFailure("The certificates service answered a certificate that does not parse.",
                    CliFailure.FAILED);
        }
        return parsed;
    }

    private static byte[] encoded(X509Certificate certificate) throws CliFailure {
        try {
            return certificate.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new CliFailure("The certificate cannot be encoded.", CliFailure.FAILED);
        }
    }
}
