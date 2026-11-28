package eu.wohlben.qits.cli.access.certificate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * <b>A workload key, made on this machine and never sent</b> (qits-2761): an EC P-256 pair, the
 * PKCS#10 request over it, and the private key as a PKCS#8 PEM for {@code key.pem}. The CSR is
 * DER-encoded by hand (an empty subject and no attributes: the CA names the certificate from the
 * bearer), so the binary needs no Bouncy Castle. A {@link SecureRandom} is made per key, never held
 * statically (the native image refuses one in its heap). {@link #toString} names no key material.
 */
final class WorkloadKey {

    /** ecdsa-with-SHA256, 1.2.840.10045.4.3.2, with absent parameters (RFC 5758). */
    private static final byte[] ECDSA_WITH_SHA256 = {
            0x30, 0x0a, 0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x02};

    private final KeyPair keys;

    private WorkloadKey(KeyPair keys) {
        this.keys = keys;
    }

    /** A fresh pair: every issue and every renewal makes its own. */
    static WorkloadKey generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return new WorkloadKey(generator.generateKeyPair());
    }

    PublicKey publicKey() {
        return keys.getPublic();
    }

    /** The certificate request, one {@code CERTIFICATE REQUEST} PEM block. */
    String csrPem() throws GeneralSecurityException {
        return pem("CERTIFICATE REQUEST", csr());
    }

    /** The private key, one {@code PRIVATE KEY} PEM block (PKCS#8), for key.pem only. */
    byte[] keyPem() {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public String toString() {
        return "an EC P-256 workload key";
    }

    private byte[] csr() throws GeneralSecurityException {
        byte[] info = tlv(0x30, concat(
                new byte[] {0x02, 0x01, 0x00}, // version v1
                new byte[] {0x30, 0x00}, // subject: the empty Name
                keys.getPublic().getEncoded(), // SubjectPublicKeyInfo
                new byte[] {(byte) 0xa0, 0x00})); // attributes [0]: none
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(info);
        byte[] signature = signer.sign();
        byte[] bits = new byte[signature.length + 1]; // no unused bits
        System.arraycopy(signature, 0, bits, 1, signature.length);
        return tlv(0x30, concat(info, ECDSA_WITH_SHA256, tlv(0x03, bits)));
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 4);
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
