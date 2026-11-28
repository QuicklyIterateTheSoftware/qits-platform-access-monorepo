package eu.wohlben.qits.cli.access.certificate;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits certificate issue|keep} against a fake qits-certificates that signs each CSR with a
 * throwaway CA (qits-2761): the files, their modes, the key that never leaves, the renewal and the
 * refusals.
 */
class CertificateCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-28T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOOR = "/certificates/api/workload-certificates";

    @TempDir
    Path home;

    @TempDir
    Path work;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private KeyPair ca;
    private X509Certificate caCertificate;
    private final List<String> csrs = new ArrayList<>();
    private final AtomicInteger failWith = new AtomicInteger();
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        ca = ec();
        X500Name name = new X500Name("CN=test workload CA");
        caCertificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(T0.minusSeconds(3600)),
                        Date.from(T0.plus(Duration.ofDays(30))), name, ca.getPublic())
                        .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(ca.getPrivate())));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_CERTIFICATES_URL", platform.url());
        env.put("QITS_WORKLOAD_CERT_DIR", work.resolve("workload").toString());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.route("POST", DOOR, request -> {
            try {
                String csr = JSON.readTree(request.body()).path("csr").asText();
                synchronized (csrs) {
                    csrs.add(csr);
                }
                if (failWith.get() != 0) {
                    return new FakePlatform.Answer(failWith.get(), "{\"error\":\"a person's token\"}",
                            Map.of("Content-Type", "application/json"));
                }
                X509Certificate issued = sign(csr);
                String pem = pem(issued);
                String body = JSON.createObjectNode()
                        .put("certificate", pem)
                        .put("chain", pem + pem(caCertificate))
                        .put("serial", issued.getSerialNumber().toString(16))
                        .put("notAfter", issued.getNotAfter().toInstant().toString())
                        .put("anchorId", "test-anchor")
                        .put("principal", "project:qits")
                        .toString();
                return new FakePlatform.Answer(201, body, Map.of("Content-Type", "application/json"));
            } catch (Exception e) {
                return new FakePlatform.Answer(500, "{\"error\":\"" + e + "\"}", Map.of());
            }
        });
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(AtomicReference<Runnable> stop, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop::set);
        int exit = TestCli.execute(context, args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET).doesNotContain("PRIVATE KEY")
                .doesNotContain("CERTIFICATE REQUEST");
        return result;
    }

    private Result run(String... args) {
        return run(new AtomicReference<>(), args);
    }

    @Test
    void issueWritesTheKeyAndTheCertificateForItAndPrintsNoKey() throws Exception {
        Result result = run("certificate", "issue");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("principal  project:qits").contains("notAfter   2026-11-29T10:00:00Z");
        Path dir = work.resolve("workload");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("key.pem"))))
                .isEqualTo("rw-------");
        X509Certificate cert = parse(Files.readString(dir.resolve("cert.pem"))).get(0);
        assertThat(parse(Files.readString(dir.resolve("chain.pem")))).containsExactly(cert, caCertificate);
        PrivateKey key = key(Files.readString(dir.resolve("key.pem")));
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(new byte[] {1, 2, 3});
        byte[] signature = signer.sign();
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(cert.getPublicKey());
        verifier.update(new byte[] {1, 2, 3});
        assertThat(verifier.verify(signature)).as("key.pem is the key cert.pem is for").isTrue();
        assertThat(platform.requests("POST", DOOR).get(0).body()).doesNotContain("PRIVATE KEY");
        assertThat(platform.requests("POST", DOOR).get(0).authorization()).startsWith("Bearer ");
        try (var listing = Files.list(dir)) {
            assertThat(listing.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("key.pem", "cert.pem", "chain.pem");
        }
    }

    @Test
    void theCsrNamesNothingAndVerifies() throws Exception {
        run("certificate", "issue", "--dir", work.resolve("named").toString());
        PKCS10CertificationRequest csr;
        try (PEMParser parser = new PEMParser(new StringReader(csrs.get(0)))) {
            csr = (PKCS10CertificationRequest) parser.readObject();
        }
        assertThat(csr.isSignatureValid(new JcaContentVerifierProviderBuilder()
                .build(new JcaPKCS10CertificationRequest(csr).getPublicKey()))).isTrue();
        assertThat(csr.getSubject().getRDNs()).isEmpty();
        assertThat(csr.getAttributes()).isEmpty();
        assertThat(Files.exists(work.resolve("named").resolve("key.pem"))).isTrue();
    }

    @Test
    void keepRenewsWithANewKeyAtTwoThirdsOfTheLifeUntilStopped() throws Exception {
        AtomicReference<Runnable> stop = new AtomicReference<>();
        List<String> keys = new ArrayList<>();
        Path key = work.resolve("workload").resolve("key.pem");
        time.afterSleep = () -> {
            try {
                keys.add(Files.readString(key));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (time.sleeps.size() == 2) {
                stop.get().run();
            }
        };
        Result result = run(stop, "certificate", "keep");

        assertThat(result.exit()).isZero();
        assertThat(time.sleeps).hasSize(2);
        // 2/3 of 24 h, give or take 10%.
        assertThat(time.sleeps.get(0)).isBetween(Duration.ofMinutes(16 * 60 * 9 / 10), Duration.ofMinutes(16 * 60 * 11 / 10));
        assertThat(csrs).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
        assertThat(result.err()).contains("Workload certificate for project:qits").contains("Stopped.");
    }

    @Test
    void aFailedRenewalKeepsTheFilesAndBacksOff() throws Exception {
        AtomicReference<Runnable> stop = new AtomicReference<>();
        Path cert = work.resolve("workload").resolve("cert.pem");
        AtomicReference<String> first = new AtomicReference<>();
        time.afterSleep = () -> {
            try {
                if (first.get() == null) {
                    first.set(Files.readString(cert));
                    failWith.set(503);
                } else {
                    assertThat(Files.readString(cert)).isEqualTo(first.get());
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            if (time.sleeps.size() == 4) {
                stop.get().run();
            }
        };
        Result result = run(stop, "certificate", "keep");

        assertThat(result.exit()).isZero();
        assertThat(time.sleeps.subList(1, 4))
                .containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120));
        assertThat(result.err()).containsOnlyOnce("could not be had").contains("The files stay");
    }

    @Test
    void aRefusalTheDoorRepeatsStopsKeepWithExit1() {
        failWith.set(403);
        Result result = run("certificate", "keep");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("403").contains("stopping").contains("No certificate was written");
        assertThat(time.sleeps).isEmpty();
        assertThat(Files.exists(work.resolve("workload").resolve("key.pem"))).isFalse();
    }

    @Test
    void issueRefusedPrintsTheServicesSentence() {
        failWith.set(403);
        Result result = run("certificate", "issue");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("a person's token");
    }

    // --- the fake CA ------------------------------------------------------------------------------

    private X509Certificate sign(String csrPem) throws Exception {
        PKCS10CertificationRequest csr;
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            csr = (PKCS10CertificationRequest) parser.readObject();
        }
        Instant now = time.instant();
        return new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(new X500Name("CN=test workload CA"), BigInteger.valueOf(now.toEpochMilli()),
                        Date.from(now.minusSeconds(300)), Date.from(now.plus(Duration.ofDays(1))),
                        new X500Name("CN=project:qits"), new JcaPKCS10CertificationRequest(csr).getPublicKey())
                        .addExtension(Extension.extendedKeyUsage, false,
                                new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
                        .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                                new GeneralName(GeneralName.uniformResourceIdentifier, "urn:qits:principal:project:qits")))
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(ca.getPrivate())));
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return WorkloadKey.pem("CERTIFICATE", certificate.getEncoded());
    }

    private static List<X509Certificate> parse(String pem) throws Exception {
        List<X509Certificate> out = new ArrayList<>();
        CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
                .forEach(c -> out.add((X509Certificate) c));
        return out;
    }

    private static PrivateKey key(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return generator.generateKeyPair();
    }
}
