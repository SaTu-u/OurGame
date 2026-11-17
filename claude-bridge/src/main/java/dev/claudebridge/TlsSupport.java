package dev.claudebridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Certificates and keys built with plain JDK APIs (the server host may not have keytool or openssl).
 * The pinning certificate always contains the DNS name {@value #PIN_NAME}; a reverse proxy verifies that name
 * against the pinned certificate file, so the hosting IP does not matter.
 */
final class TlsSupport {

    static final String PIN_NAME = "claudebridge.local";
    private static final String KEYSTORE = "tls.p12";
    private static final String PASSWORD = "tls.pass";
    static final String CERT_PEM = "tls-cert.pem";
    private static final String ALIAS = "claudebridge";

    record KeyAndChain(PrivateKey key, X509Certificate[] chain) {
    }

    private TlsSupport() {
    }

    // ------------------------------------------------------------------ self-signed (pinning) certificate

    /** Loads the pinning certificate from tls.p12, creating it on first use. */
    static KeyAndChain loadOrCreateSelfSigned(Path dir, List<String> extraHosts) throws Exception {
        Files.createDirectories(dir);
        Path ks = dir.resolve(KEYSTORE);
        Path pass = dir.resolve(PASSWORD);
        Path pem = dir.resolve(CERT_PEM);
        if (!Files.exists(ks) || !Files.exists(pass)) createSelfSigned(dir, extraHosts);
        char[] password = Files.readString(pass, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, password);
        }
        X509Certificate cert = (X509Certificate) store.getCertificate(ALIAS);
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, password);
        if (!Files.exists(pem)) Files.writeString(pem, pem(cert), StandardCharsets.UTF_8);
        return new KeyAndChain(key, new X509Certificate[]{cert});
    }

    /** Deletes the pinning key and certificate so that the next load creates new ones. */
    static void resetSelfSigned(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(KEYSTORE));
        Files.deleteIfExists(dir.resolve(PASSWORD));
        Files.deleteIfExists(dir.resolve(CERT_PEM));
    }

    private static void createSelfSigned(Path dir, List<String> extraHosts) throws Exception {
        KeyPair kp = rsaKeyPair();
        Set<String> names = new LinkedHashSet<>();
        names.add(PIN_NAME);
        names.add("localhost");
        names.add("127.0.0.1");
        for (String h : extraHosts) if (h != null && !h.isBlank()) names.add(h.trim());
        X509Certificate cert = selfSigned(kp, "ClaudeBridge", new ArrayList<>(names), 3650, true);

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

    // ------------------------------------------------------------------ builders

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        return gen.generateKeyPair();
    }

    /** Self-signed X.509 v3 certificate signed with SHA256withRSA. */
    static X509Certificate selfSigned(KeyPair kp, String commonName, List<String> altNames, int days, boolean ca,
                                      byte[]... extraExtensions) throws Exception {
        byte[] algorithm = Der.sha256WithRsa();
        byte[] name = Der.name(commonName);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        byte[] validity = Der.seq(Der.utcTime(now.minusDays(1)), Der.utcTime(now.plusDays(days)));
        byte[] serial = Der.integer(new BigInteger(63, new SecureRandom()).add(BigInteger.ONE));
        List<byte[]> ext = new ArrayList<>();
        ext.add(Der.basicConstraints(ca));
        ext.add(Der.subjectAltName(altNames));
        for (byte[] e : extraExtensions) ext.add(e);
        byte[] tbs = Der.seq(
                Der.tlv(0xA0, Der.integer(BigInteger.TWO)), // version v3
                serial,
                algorithm,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),
                Der.tlv(0xA3, Der.seq(ext.toArray(new byte[0][]))));
        byte[] der = Der.seq(tbs, algorithm, Der.bitString(sign(kp.getPrivate(), tbs)));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    /** PKCS#10 certificate signing request for the given DNS name. */
    static byte[] csr(KeyPair kp, String domain) throws Exception {
        byte[] extensions = Der.seq(Der.subjectAltName(List.of(domain)));
        byte[] attribute = Der.seq(Der.oid(Der.OID_EXTENSION_REQUEST), Der.set(extensions));
        byte[] info = Der.seq(
                Der.integer(BigInteger.ZERO),
                Der.name(domain),
                kp.getPublic().getEncoded(),
                Der.tlv(0xA0, attribute));
        return Der.seq(info, Der.sha256WithRsa(), Der.bitString(sign(kp.getPrivate(), info)));
    }

    private static byte[] sign(PrivateKey key, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(data);
        return sig.sign();
    }

    // ------------------------------------------------------------------ PEM

    static String pem(X509Certificate cert) throws Exception {
        return pemBlock("CERTIFICATE", cert.getEncoded());
    }

    static String pemBlock(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    static X509Certificate[] readChain(Path pemFile) throws Exception {
        try (InputStream in = Files.newInputStream(pemFile)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) throw new IOException("no certificates in " + pemFile.getFileName());
            return certs.stream().map(c -> (X509Certificate) c).toArray(X509Certificate[]::new);
        }
    }

    private static final Pattern PEM_KEY = Pattern.compile("-----BEGIN ([A-Z ]*)PRIVATE KEY-----(.*?)-----END \\1PRIVATE KEY-----", Pattern.DOTALL);

    /** Reads a PKCS#8 ("BEGIN PRIVATE KEY") or PKCS#1 RSA ("BEGIN RSA PRIVATE KEY") key. */
    static PrivateKey readPrivateKey(Path pemFile) throws Exception {
        String text = Files.readString(pemFile, StandardCharsets.US_ASCII);
        Matcher m = PEM_KEY.matcher(text);
        if (!m.find()) throw new IOException(pemFile.getFileName() + " does not contain a PEM private key");
        String kind = m.group(1).trim();
        byte[] der = Base64.getMimeDecoder().decode(m.group(2));
        if (kind.equals("RSA")) {
            der = Der.seq(Der.integer(BigInteger.ZERO), Der.seq(Der.oid(Der.OID_RSA_ENCRYPTION), Der.nul()), Der.octets(der));
        } else if (!kind.isEmpty()) {
            throw new IOException("unsupported key format '" + kind + " PRIVATE KEY'; convert it with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (InvalidKeySpecException ignored) {
                // try the next algorithm
            }
        }
        throw new IOException("unsupported private key algorithm in " + pemFile.getFileName());
    }

    // ------------------------------------------------------------------ misc

    static String fingerprint(X509Certificate cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(d);
    }

    static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem.
        }
    }
}
