package dev.claudebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Minimal ACME (RFC 8555) client that obtains a certificate with the TLS-ALPN-01 challenge (RFC 8737),
 * which needs only port 443 on the server. Account and certificate keys are RSA 2048, JWS uses RS256.
 */
final class AcmeClient {

    /** Installs and removes the TLS-ALPN-01 challenge certificate on the listener. */
    interface ChallengeResponder {
        void deploy(KeyPair key, X509Certificate cert);

        void cleanup();
    }

    static final class AcmeException extends IOException {
        AcmeException(String message) {
            super(message);
        }
    }

    private record Response(int status, java.net.http.HttpHeaders headers, String body) {
        JsonObject json() {
            JsonElement e = JsonParser.parseString(body.isBlank() ? "{}" : body);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        }

        String location() {
            return headers.firstValue("Location").orElse(null);
        }
    }

    private final HttpClient http;
    private final String directoryUrl;
    private final KeyPair account;
    private final Logger log;
    private JsonObject directory;
    private String nonce;
    private String kid;

    AcmeClient(String directoryUrl, KeyPair account, Logger log) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.directoryUrl = directoryUrl;
        this.account = account;
        this.log = log;
    }

    // ------------------------------------------------------------------ protocol

    void register(String email, boolean agreeToTerms) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("termsOfServiceAgreed", agreeToTerms);
        if (email != null && !email.isBlank()) {
            JsonArray contact = new JsonArray();
            contact.add("mailto:" + email.trim());
            payload.add("contact", contact);
        }
        Response r = post(dir("newAccount"), payload, true, null);
        expect(r, "account registration", 200, 201);
        kid = r.location();
        if (kid == null) throw new AcmeException("ACME server did not return an account URL");
    }

    X509Certificate[] issue(String domain, KeyPair certKey, ChallengeResponder responder) throws Exception {
        JsonObject orderReq = new JsonObject();
        JsonArray ids = new JsonArray();
        JsonObject id = new JsonObject();
        id.addProperty("type", "dns");
        id.addProperty("value", domain);
        ids.add(id);
        orderReq.add("identifiers", ids);
        Response created = post(dir("newOrder"), orderReq, false, null);
        expect(created, "new order", 201);
        String orderUrl = created.location();
        JsonObject order = created.json();

        for (JsonElement authzUrl : order.getAsJsonArray("authorizations")) {
            authorize(authzUrl.getAsString(), responder);
        }

        order = poll(orderUrl, "order", Set.of("ready", "valid"), 120);
        if ("ready".equals(order.get("status").getAsString())) {
            JsonObject fin = new JsonObject();
            fin.addProperty("csr", b64(TlsSupport.csr(certKey, domain)));
            Response r = post(order.get("finalize").getAsString(), fin, false, null);
            expect(r, "finalize", 200);
            order = poll(orderUrl, "order", Set.of("valid"), 180);
        }
        String certUrl = order.get("certificate").getAsString();
        Response certResp = post(certUrl, null, false, "application/pem-certificate-chain");
        expect(certResp, "certificate download", 200);
        Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(certResp.body().getBytes(StandardCharsets.US_ASCII)));
        if (certs.isEmpty()) throw new AcmeException("ACME server returned an empty certificate chain");
        return certs.stream().map(c -> (X509Certificate) c).toArray(X509Certificate[]::new);
    }

    private void authorize(String authzUrl, ChallengeResponder responder) throws Exception {
        Response r = post(authzUrl, null, false, null);
        expect(r, "authorization", 200);
        JsonObject authz = r.json();
        if ("valid".equals(authz.get("status").getAsString())) return;
        String domain = authz.getAsJsonObject("identifier").get("value").getAsString();
        JsonObject challenge = null;
        for (JsonElement c : authz.getAsJsonArray("challenges")) {
            if ("tls-alpn-01".equals(c.getAsJsonObject().get("type").getAsString())) challenge = c.getAsJsonObject();
        }
        if (challenge == null) throw new AcmeException("the ACME server did not offer a tls-alpn-01 challenge for " + domain);

        String keyAuthorization = challenge.get("token").getAsString() + "." + thumbprint();
        KeyPair challengeKey = TlsSupport.rsaKeyPair();
        X509Certificate challengeCert = challengeCertificate(challengeKey, domain, keyAuthorization);

        responder.deploy(challengeKey, challengeCert);
        try {
            log.info("ACME: answering tls-alpn-01 challenge for " + domain);
            Response ack = post(challenge.get("url").getAsString(), new JsonObject(), false, null);
            expect(ack, "challenge response", 200);
            poll(authzUrl, "authorization for " + domain, Set.of("valid"), 120);
        } finally {
            responder.cleanup();
        }
    }

    // ------------------------------------------------------------------ transport

    private Response post(String url, JsonObject payload, boolean useJwk, String accept) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (nonce == null) nonce = freshNonce();
            JsonObject header = new JsonObject();
            header.addProperty("alg", "RS256");
            header.addProperty("nonce", nonce);
            header.addProperty("url", url);
            if (useJwk) header.add("jwk", jwk((RSAPublicKey) account.getPublic()));
            else header.addProperty("kid", kid);
            JsonObject jws = jws(account, header, payload);

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/jose+json")
                    .header("User-Agent", "ClaudeBridge-ACME/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(jws.toString()));
            if (accept != null) b.header("Accept", accept);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            nonce = resp.headers().firstValue("Replay-Nonce").orElse(null);
            if (resp.statusCode() == 400 && resp.body().contains("urn:ietf:params:acme:error:badNonce")) continue;
            return new Response(resp.statusCode(), resp.headers(), resp.body());
        }
        throw new AcmeException("the ACME server rejected the request nonce 5 times in a row");
    }

    private String freshNonce() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(dir("newNonce")))
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        return resp.headers().firstValue("Replay-Nonce")
                .orElseThrow(() -> new AcmeException("the ACME server did not return a Replay-Nonce"));
    }

    private String dir(String key) throws Exception {
        if (directory == null) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(directoryUrl)).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new AcmeException("cannot read ACME directory " + directoryUrl + ": HTTP " + resp.statusCode());
            directory = JsonParser.parseString(resp.body()).getAsJsonObject();
        }
        JsonElement v = directory.get(key);
        if (v == null) throw new AcmeException("ACME directory has no " + key);
        return v.getAsString();
    }

    private JsonObject poll(String url, String what, Set<String> done, int maxSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (true) {
            Response r = post(url, null, false, null);
            expect(r, what, 200);
            JsonObject j = r.json();
            String status = j.get("status").getAsString();
            if (done.contains(status)) return j;
            if ("invalid".equals(status)) throw new AcmeException(what + " failed: " + problemOf(j));
            if (System.currentTimeMillis() > deadline) throw new AcmeException(what + " is still '" + status + "' after " + maxSeconds + "s");
            long wait = r.headers().firstValue("Retry-After").map(v -> {
                try {
                    return Long.parseLong(v.trim()) * 1000L;
                } catch (NumberFormatException e) {
                    return 2000L;
                }
            }).orElse(2000L);
            Thread.sleep(Math.max(1000L, Math.min(wait, 10000L)));
        }
    }

    private static String problemOf(JsonObject obj) {
        if (obj.has("error")) return obj.get("error").toString();
        if (obj.has("challenges")) {
            for (JsonElement c : obj.getAsJsonArray("challenges")) {
                JsonObject co = c.getAsJsonObject();
                if (co.has("error")) return co.get("error").toString();
            }
        }
        return obj.toString();
    }

    private static void expect(Response r, String what, int... ok) throws AcmeException {
        for (int s : ok) if (r.status() == s) return;
        String detail = r.body();
        try {
            JsonObject j = r.json();
            if (j.has("detail")) detail = j.get("type") + ": " + j.get("detail").getAsString();
        } catch (RuntimeException ignored) {
            // body is not JSON
        }
        throw new AcmeException(what + " failed (HTTP " + r.status() + "): " + detail);
    }

    // ------------------------------------------------------------------ JWS / JWK helpers

    /** Flattened JWS JSON serialization signed with RS256; a null payload means POST-as-GET (empty payload). */
    static JsonObject jws(KeyPair key, JsonObject protectedHeader, JsonObject payload) throws Exception {
        String protectedB64 = b64(protectedHeader.toString().getBytes(StandardCharsets.UTF_8));
        String payloadB64 = payload == null ? "" : b64(payload.toString().getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key.getPrivate());
        sig.update((protectedB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII));
        JsonObject jws = new JsonObject();
        jws.addProperty("protected", protectedB64);
        jws.addProperty("payload", payloadB64);
        jws.addProperty("signature", b64(sig.sign()));
        return jws;
    }

    static JsonObject jwk(RSAPublicKey pub) {
        JsonObject k = new JsonObject();
        k.addProperty("e", b64(unsigned(pub.getPublicExponent())));
        k.addProperty("kty", "RSA");
        k.addProperty("n", b64(unsigned(pub.getModulus())));
        return k;
    }

    /** RFC 7638 thumbprint: SHA-256 over the required members in lexicographic order, without whitespace. */
    static String thumbprint(RSAPublicKey pub) throws Exception {
        JsonObject k = jwk(pub);
        String canonical = "{\"e\":\"" + k.get("e").getAsString() + "\",\"kty\":\"RSA\",\"n\":\"" + k.get("n").getAsString() + "\"}";
        return b64(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String thumbprint() throws Exception {
        return thumbprint((RSAPublicKey) account.getPublic());
    }

    /** The certificate presented for TLS-ALPN-01 (RFC 8737): one dNSName and a critical acmeIdentifier extension. */
    static X509Certificate challengeCertificate(KeyPair key, String domain, String keyAuthorization) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyAuthorization.getBytes(StandardCharsets.US_ASCII));
        byte[] acmeIdentifier = Der.extension(Der.OID_ACME_IDENTIFIER, true, Der.octets(digest));
        return TlsSupport.selfSigned(key, domain, List.of(domain), 7, false, acmeIdentifier);
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) return java.util.Arrays.copyOfRange(b, 1, b.length);
        return b;
    }

    static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
