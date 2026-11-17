package dev.claudebridge;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-signed TLS certificate for the built-in HTTPS listener, generated with plain JDK APIs
 * (the server host may not have keytool or openssl).
 * The certificate always contains the DNS name {@value #PIN_NAME}; the reverse proxy verifies that name
 * against the pinned certificate file, so the hosting IP does not matter.
 */
final class TlsSupport {

    static final String PIN_NAME = "claudebridge.local";
    private static final String KEYSTORE = "tls.p12";
    private static final String PASSWORD = "tls.pass";
    static final String CERT_PEM = "tls-cert.pem";
    private static final String ALIAS = "claudebridge";

    record Loaded(SSLContext context, X509Certificate certificate, Path pemFile) {
    }

    private TlsSupport() {
    }

    static Loaded loadOrCreate(Path dir, List<String> extraHosts) throws Exception {
        Files.createDirectories(dir);
        Path ks = dir.resolve(KEYSTORE);
        Path pass = dir.resolve(PASSWORD);
        Path pem = dir.resolve(CERT_PEM);
        if (!Files.exists(ks) || !Files.exists(pass)) create(dir, extraHosts);
        char[] password = Files.readString(pass, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, password);
        }
        X509Certificate cert = (X509Certificate) store.getCertificate(ALIAS);
        if (!Files.exists(pem)) Files.writeString(pem, pem(cert), StandardCharsets.UTF_8);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return new Loaded(ctx, cert, pem);
    }

    /** Deletes the current key and certificate so that the next load creates new ones. */
    static void reset(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(KEYSTORE));
        Files.deleteIfExists(dir.resolve(PASSWORD));
        Files.deleteIfExists(dir.resolve(CERT_PEM));
    }

    private static void create(Path dir, List<String> extraHosts) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair kp = gen.generateKeyPair();
        X509Certificate cert = selfSigned(kp, extraHosts);

        byte[] pw = new byte[24];
        new SecureRandom().nextBytes(pw);
        char[] password = Base64.getUrlEncoder().withoutPadding().encodeToString(pw).toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, kp.getPrivate(), password, new Certificate[]{cert});

        Path pass = dir.resolve(PASSWORD);
        Files.writeString(pass, new String(password), StandardCharsets.UTF_8);
        restrict(pass);
        Path ks = dir.resolve(KEYSTORE);
        try (OutputStream out = Files.newOutputStream(ks)) {
            store.store(out, password);
        }
        restrict(ks);
        Files.writeString(dir.resolve(CERT_PEM), pem(cert), StandardCharsets.UTF_8);
    }

    private static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem.
        }
    }

    static String pem(X509Certificate cert) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    static String fingerprint(X509Certificate cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(d);
    }

    // ------------------------------------------------------------------ minimal X.509 v3 builder

    private static final int[] OID_SHA256_RSA = {1, 2, 840, 113549, 1, 1, 11};
    private static final int[] OID_CN = {2, 5, 4, 3};
    private static final int[] OID_BASIC_CONSTRAINTS = {2, 5, 29, 19};
    private static final int[] OID_SUBJECT_ALT_NAME = {2, 5, 29, 17};

    static X509Certificate selfSigned(KeyPair kp, List<String> extraHosts) throws Exception {
        byte[] algorithm = seq(oid(OID_SHA256_RSA), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(OID_CN), tlv(0x0C, "ClaudeBridge".getBytes(StandardCharsets.UTF_8)))));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        byte[] validity = seq(utcTime(now.minusDays(1)), utcTime(now.plusYears(10)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());

        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(PIN_NAME);
        hosts.add("localhost");
        hosts.add("127.0.0.1");
        for (String h : extraHosts) if (h != null && !h.isBlank()) hosts.add(h.trim());
        List<byte[]> names = new ArrayList<>();
        for (String h : hosts) {
            if (isIpLiteral(h)) names.add(tlv(0x87, InetAddress.getByName(h).getAddress()));
            else names.add(tlv(0x82, h.getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] basicConstraints = seq(oid(OID_BASIC_CONSTRAINTS), tlv(0x04, seq(new byte[]{0x01, 0x01, (byte) 0xFF})));
        byte[] subjectAltName = seq(oid(OID_SUBJECT_ALT_NAME), tlv(0x04, seq(names.toArray(new byte[0][]))));
        byte[] extensions = tlv(0xA3, seq(basicConstraints, subjectAltName));

        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[]{0x02})), // version v3
                serial,
                algorithm,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),
                extensions);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();
        byte[] bitString = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bitString, 1, signature.length);
        byte[] der = seq(tbs, algorithm, tlv(0x03, bitString));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    static boolean isIpLiteral(String s) {
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (s.contains(":") && s.matches("[0-9a-fA-F:.]+"));
    }

    private static byte[] utcTime(ZonedDateTime t) {
        return tlv(0x17, t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
