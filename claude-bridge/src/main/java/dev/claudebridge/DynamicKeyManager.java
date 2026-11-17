package dev.claudebridge;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Key manager whose certificate can be replaced at runtime (renewal without restarting the listener).
 * While an ACME TLS-ALPN-01 challenge is active, handshakes that negotiate ALPN "acme-tls/1"
 * get the challenge certificate; everyone else keeps getting the main certificate.
 */
final class DynamicKeyManager extends X509ExtendedKeyManager {

    static final String ACME_ALPN = "acme-tls/1";
    private static final String MAIN = "main";
    private static final String CHALLENGE = "acme-challenge";

    private volatile PrivateKey mainKey;
    private volatile X509Certificate[] mainChain;
    private volatile PrivateKey challengeKey;
    private volatile X509Certificate[] challengeChain;

    void setMain(PrivateKey key, X509Certificate[] chain) {
        this.mainChain = chain;
        this.mainKey = key;
    }

    X509Certificate[] mainChain() {
        return mainChain;
    }

    void setChallenge(PrivateKey key, X509Certificate[] chain) {
        this.challengeChain = chain;
        this.challengeKey = key;
    }

    void clearChallenge() {
        this.challengeKey = null;
        this.challengeChain = null;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        String protocol = null;
        try {
            if (engine != null) protocol = engine.getHandshakeApplicationProtocol();
        } catch (RuntimeException ignored) {
            // no handshake in progress
        }
        return choose(keyType, protocol);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        String protocol = null;
        try {
            if (socket instanceof SSLSocket s) protocol = s.getHandshakeApplicationProtocol();
        } catch (RuntimeException ignored) {
            // no handshake in progress
        }
        return choose(keyType, protocol);
    }

    private String choose(String keyType, String protocol) {
        boolean challenge = ACME_ALPN.equals(protocol) && challengeChain != null;
        PrivateKey key = challenge ? challengeKey : mainKey;
        if (key == null || !matches(keyType, key)) return null;
        return challenge ? CHALLENGE : MAIN;
    }

    private static boolean matches(String keyType, PrivateKey key) {
        if (keyType == null) return true;
        String alg = key.getAlgorithm();
        return keyType.equals(alg) || keyType.startsWith(alg + "_") || ("RSASSA-PSS".equals(keyType) && "RSA".equals(alg));
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        PrivateKey key = mainKey;
        return key != null && matches(keyType, key) ? new String[]{MAIN} : null;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        if (CHALLENGE.equals(alias)) return challengeChain;
        if (MAIN.equals(alias)) return mainChain;
        return null;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        if (CHALLENGE.equals(alias)) return challengeKey;
        if (MAIN.equals(alias)) return mainKey;
        return null;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return null;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return null;
    }
}
