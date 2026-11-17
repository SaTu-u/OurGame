package dev.claudebridge;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * HTTPS for the API listener. Modes (http.tls.mode):
 * <ul>
 *   <li>off: plain HTTP (only for 127.0.0.1 behind a reverse proxy on the same machine);</li>
 *   <li>self-signed: generated certificate for a reverse proxy that pins plugins/ClaudeBridge/tls-cert.pem;</li>
 *   <li>acme: free publicly trusted certificate from Let's Encrypt (TLS-ALPN-01, port 443 only), renewed automatically;</li>
 *   <li>files: certificate and key from PEM files, reloaded when the files change.</li>
 * </ul>
 */
final class TlsManager {

    static final String LETS_ENCRYPT = "https://acme-v02.api.letsencrypt.org/directory";

    private final ClaudeBridgePlugin plugin;
    private final DynamicKeyManager keyManager = new DynamicKeyManager();
    private final Path dataDir;
    private String mode = "off";
    private SSLContext context;
    private BukkitTask timer;
    private volatile String state = "";
    private volatile boolean acmeIssued;
    private volatile boolean issuing;
    private volatile long nextAttemptAt;
    private long backoffMs = 3_600_000L;
    private long filesStamp;

    TlsManager(ClaudeBridgePlugin plugin) {
        this.plugin = plugin;
        this.dataDir = plugin.getDataFolder().toPath();
    }

    /** Prepares certificates; returns null when TLS is off. */
    SSLContext start() throws Exception {
        FileConfiguration c = plugin.getConfig();
        String m = c.getString("http.tls.mode", null);
        if (m == null || m.isBlank()) m = c.getBoolean("http.tls.enabled", false) ? "self-signed" : "off"; // 1.1.0 configs
        mode = m.toLowerCase(Locale.ROOT).trim();
        switch (mode) {
            case "off", "false", "none" -> {
                mode = "off";
                state = "plain HTTP";
                return null;
            }
            case "self-signed", "selfsigned", "pinned" -> {
                mode = "self-signed";
                TlsSupport.KeyAndChain kc = TlsSupport.loadOrCreateSelfSigned(dataDir, c.getStringList("http.tls.hosts"));
                keyManager.setMain(kc.key(), kc.chain());
                state = "self-signed certificate plugins/ClaudeBridge/" + TlsSupport.CERT_PEM
                        + " (SHA-256 " + TlsSupport.fingerprint(kc.chain()[0]) + ")";
            }
            case "files", "file", "pem" -> {
                mode = "files";
                loadFiles();
                timer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::reloadFilesIfChanged, 20L * 600, 20L * 600);
            }
            case "acme", "letsencrypt", "lets-encrypt" -> {
                mode = "acme";
                startAcme();
            }
            default -> throw new IllegalArgumentException("unknown http.tls.mode '" + m + "' (use off, self-signed, acme or files)");
        }
        context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{keyManager}, null, null);
        return context;
    }

    HttpsConfigurator configurator() {
        boolean alpn = "acme".equals(mode);
        return new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters sp = getSSLContext().getDefaultSSLParameters();
                if (alpn) sp.setApplicationProtocols(new String[]{DynamicKeyManager.ACME_ALPN, "http/1.1"});
                params.setSSLParameters(sp);
            }
        };
    }

    void stop() {
        if (timer != null) timer.cancel();
        timer = null;
    }

    String mode() {
        return mode;
    }

    String describe() {
        return mode + ": " + state;
    }

    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("state", state);
        X509Certificate[] chain = keyManager.mainChain();
        if (chain != null && chain.length > 0) {
            m.put("subject", chain[0].getSubjectX500Principal().getName());
            m.put("issuer", chain[0].getIssuerX500Principal().getName());
            m.put("notAfter", chain[0].getNotAfter().toInstant().toString());
        }
        return m;
    }

    /** /claudebridge tls: new pinning certificate, immediate Let's Encrypt renewal, or reload of the PEM files. */
    String rotate() throws Exception {
        switch (mode) {
            case "self-signed" -> {
                TlsSupport.resetSelfSigned(dataDir);
                Bukkit.getScheduler().runTaskLater(plugin, plugin::restartApi, 10L);
                return "Создан новый самоподписанный сертификат: plugins/ClaudeBridge/" + TlsSupport.CERT_PEM
                        + ". Скопируйте его на веб-сервер заново.";
            }
            case "acme" -> {
                nextAttemptAt = 0;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> acmeCheck(true));
                return "Запрошен новый сертификат Let's Encrypt, результат появится в консоли.";
            }
            case "files" -> {
                loadFiles();
                return "Сертификат перечитан из файлов: " + state;
            }
            default -> {
                return "HTTPS выключен (http.tls.mode: off).";
            }
        }
    }

    // ------------------------------------------------------------------ files

    private void loadFiles() throws Exception {
        FileConfiguration c = plugin.getConfig();
        Path cert = plugin.serverRoot().resolve(c.getString("http.tls.files.certificate", "plugins/ClaudeBridge/cert.pem")).normalize();
        Path key = plugin.serverRoot().resolve(c.getString("http.tls.files.private-key", "plugins/ClaudeBridge/key.pem")).normalize();
        X509Certificate[] chain = TlsSupport.readChain(cert);
        PrivateKey k = TlsSupport.readPrivateKey(key);
        keyManager.setMain(k, chain);
        filesStamp = stamp(cert, key);
        state = "certificate " + plugin.serverRoot().relativize(cert) + " for " + chain[0].getSubjectX500Principal().getName()
                + ", valid until " + chain[0].getNotAfter().toInstant();
    }

    private void reloadFilesIfChanged() {
        FileConfiguration c = plugin.getConfig();
        Path cert = plugin.serverRoot().resolve(c.getString("http.tls.files.certificate", "plugins/ClaudeBridge/cert.pem")).normalize();
        Path key = plugin.serverRoot().resolve(c.getString("http.tls.files.private-key", "plugins/ClaudeBridge/key.pem")).normalize();
        if (stamp(cert, key) == filesStamp) return;
        try {
            loadFiles();
            plugin.getLogger().info("HTTPS certificate reloaded: " + state);
        } catch (Exception e) {
            plugin.getLogger().warning("HTTPS certificate files changed but could not be loaded: " + e.getMessage());
        }
    }

    private static long stamp(Path a, Path b) {
        try {
            return Files.getLastModifiedTime(a).toMillis() * 31 + Files.getLastModifiedTime(b).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ Let's Encrypt (ACME)

    private String acmeDomain() {
        return plugin.getConfig().getString("http.tls.acme.domain", "").trim().toLowerCase(Locale.ROOT);
    }

    private void startAcme() throws Exception {
        String domain = acmeDomain();
        if (domain.isEmpty()) throw new IllegalArgumentException("http.tls.acme.domain is empty");
        Path dir = dataDir.resolve("acme");
        Files.createDirectories(dir);
        Path crt = dir.resolve(domain + ".crt");
        Path key = dir.resolve(domain + ".key");
        if (Files.exists(crt) && Files.exists(key)) {
            try {
                X509Certificate[] chain = TlsSupport.readChain(crt);
                keyManager.setMain(TlsSupport.readPrivateKey(key), chain);
                acmeIssued = true;
                state = "Let's Encrypt certificate for " + domain + ", valid until " + chain[0].getNotAfter().toInstant();
            } catch (Exception e) {
                plugin.getLogger().warning("Stored certificate for " + domain + " is unreadable, requesting a new one: " + e.getMessage());
            }
        }
        if (keyManager.mainChain() == null) {
            KeyPair tmp = TlsSupport.rsaKeyPair();
            keyManager.setMain(tmp.getPrivate(), new X509Certificate[]{TlsSupport.selfSigned(tmp, domain, List.of(domain), 7, false)});
            state = "waiting for a certificate from Let's Encrypt (temporary self-signed certificate in use)";
        }
        // First check 2 seconds after start, then every 30 minutes; renewal happens when 1/3 of the lifetime is left.
        timer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> acmeCheck(false), 40L, 20L * 60 * 30);
    }

    private void acmeCheck(boolean force) {
        if (issuing) return;
        if (!force && !needsRenewal()) return;
        if (!force && System.currentTimeMillis() < nextAttemptAt) return;
        issuing = true;
        try {
            issueNow();
            backoffMs = 3_600_000L;
            nextAttemptAt = 0;
        } catch (Exception e) {
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            backoffMs = Math.min(backoffMs * 2, 12 * 3_600_000L);
            String why = describe(e);
            state = "certificate request failed: " + why;
            plugin.getLogger().log(Level.SEVERE, "ACME: could not get a certificate for " + acmeDomain() + ": " + why
                    + "\n  Let's Encrypt connects to " + acmeDomain() + ":443 and must reach this plugin there."
                    + " Check that the domain's DNS A record points to this server's public IP,"
                    + " that external port 443 is forwarded to http.port (" + plugin.getConfig().getInt("http.port") + ")"
                    + " and that http.bind is 0.0.0.0. Next attempt in " + (backoffMs / 2 / 60000) + " min.");
        } finally {
            issuing = false;
        }
    }

    /** Exception text that is never just "null" (network errors often have no message). */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 400; t = t.getCause()) {
            if (sb.length() > 0) sb.append(", caused by ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            if (t.getCause() == t) break;
        }
        return sb.toString();
    }

    private boolean needsRenewal() {
        if (!acmeIssued) return true;
        X509Certificate[] chain = keyManager.mainChain();
        if (chain == null || chain.length == 0) return true;
        long nb = chain[0].getNotBefore().getTime();
        long na = chain[0].getNotAfter().getTime();
        return na - System.currentTimeMillis() < (na - nb) / 3;
    }

    private void issueNow() throws Exception {
        FileConfiguration c = plugin.getConfig();
        String domain = acmeDomain();
        if (!c.getBoolean("http.tls.acme.agree-tos", false)) {
            throw new IllegalStateException("http.tls.acme.agree-tos is false: read the Let's Encrypt Subscriber Agreement "
                    + "(https://letsencrypt.org/repository/) and set it to true");
        }
        String directory = c.getString("http.tls.acme.directory", LETS_ENCRYPT);
        Path dir = dataDir.resolve("acme");
        Files.createDirectories(dir);
        KeyPair account = loadOrCreateAccountKey(dir.resolve("account.key"));
        AcmeClient client = new AcmeClient(directory, account, plugin.getLogger());
        plugin.getLogger().info("ACME: requesting a certificate for " + domain + " from " + directory);
        client.register(c.getString("http.tls.acme.email", ""), true);
        KeyPair certKey = TlsSupport.rsaKeyPair();
        X509Certificate[] chain = client.issue(domain, certKey, new AcmeClient.ChallengeResponder() {
            @Override
            public void deploy(KeyPair key, X509Certificate cert) {
                keyManager.setChallenge(key.getPrivate(), new X509Certificate[]{cert});
            }

            @Override
            public void cleanup() {
                keyManager.clearChallenge();
            }
        });
        StringBuilder pem = new StringBuilder();
        for (X509Certificate x : chain) pem.append(TlsSupport.pem(x));
        Path crt = dir.resolve(domain + ".crt");
        Path key = dir.resolve(domain + ".key");
        Files.writeString(key, TlsSupport.pemBlock("PRIVATE KEY", certKey.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        TlsSupport.restrict(key);
        Files.writeString(crt, pem.toString(), StandardCharsets.US_ASCII);
        keyManager.setMain(certKey.getPrivate(), chain);
        acmeIssued = true;
        state = "Let's Encrypt certificate for " + domain + ", valid until " + chain[0].getNotAfter().toInstant();
        plugin.getLogger().info("ACME: certificate issued for " + domain + " by " + chain[0].getIssuerX500Principal().getName()
                + ", valid until " + chain[0].getNotAfter().toInstant());
    }

    private static KeyPair loadOrCreateAccountKey(Path file) throws Exception {
        if (Files.exists(file)) {
            PrivateKey k = TlsSupport.readPrivateKey(file);
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) k;
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(pub, k);
        }
        KeyPair kp = TlsSupport.rsaKeyPair();
        Files.writeString(file, TlsSupport.pemBlock("PRIVATE KEY", kp.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        TlsSupport.restrict(file);
        return kp;
    }
}
