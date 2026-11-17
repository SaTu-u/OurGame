package dev.claudebridge;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Reverse SSH tunnel inside the JVM (no external program): connects to a tunnel service such as localhost.run or
 * tuna.am, asks it to forward its public HTTPS address to the local API port and streams the service's messages
 * (which contain the public address). {@link #run} blocks until the connection ends.
 */
final class SshTunnel {

    private final String host;
    private final int port;
    private final String user;
    private final Path privateKey;
    private final int remotePort;
    private final int localPort;
    private final String command;
    private volatile Session session;

    SshTunnel(String host, int port, String user, Path privateKey, int remotePort, int localPort, String command) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.privateKey = privateKey;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.command = command;
    }

    void run(Consumer<String> output) throws Exception {
        JSch jsch = new JSch();
        if (privateKey != null) jsch.addIdentity(privateKey.toString());
        Session s = jsch.getSession(user, host, port);
        // Tunnel services rotate host keys behind load balancers; the API itself is protected by its own token.
        s.setConfig("StrictHostKeyChecking", "no");
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        s.setUserInfo(new SilentUserInfo(output));
        s.setServerAliveInterval(30_000);
        s.setServerAliveCountMax(4);
        session = s;
        try {
            s.connect(30_000);
            // Same as `ssh -R <remotePort>:localhost:<localPort>`: the service forwards its public address to us.
            s.setPortForwardingR("localhost", remotePort, "127.0.0.1", localPort);
            ChannelExec ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(command);
            InputStream out = ch.getInputStream();
            InputStream err = ch.getExtInputStream();
            daemon("ClaudeBridge-ssh-stdout", () -> pump(out, output));
            daemon("ClaudeBridge-ssh-stderr", () -> pump(err, output));
            // Some services (localhost.run) never answer the exec request. OpenSSH does not wait for that answer,
            // JSch does (and gives up with "channel request: timeout"), so the request is sent from a helper thread
            // while the service's output (with the public address) is already being read.
            AtomicReference<Exception> openError = new AtomicReference<>();
            Thread opener = daemon("ClaudeBridge-ssh-open", () -> {
                try {
                    ch.connect(0);
                } catch (Exception e) {
                    openError.set(e);
                }
            });
            long started = System.currentTimeMillis();
            while (s.isConnected()) {
                Exception e = openError.get();
                if (e != null) throw new IOException("the tunnel service refused the session: " + e.getMessage(), e);
                if (!ch.isConnected() && !opener.isAlive() && System.currentTimeMillis() - started > 5_000) break;
                Thread.sleep(500);
            }
            int code = ch.getExitStatus();
            throw new IOException("the tunnel service closed the session" + (code >= 0 ? " (exit status " + code + ")" : ""));
        } finally {
            s.disconnect();
            session = null;
        }
    }

    void stop() {
        Session s = session;
        if (s != null) s.disconnect();
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void pump(InputStream in, Consumer<String> output) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String clean = line.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replace("\r", "").strip();
                if (!clean.isEmpty()) output.accept(clean);
            }
        } catch (IOException ignored) {
            // session closed
        }
    }

    static final String KEY_FILE = "id_rsa";

    /**
     * The plugin's SSH key, created on first use; the public part is registered with the tunnel service.
     * RSA 3072: JSch cannot write Ed25519 private keys, and RSA (with rsa-sha2 signatures) is accepted everywhere.
     */
    static Path ensureKey(Path dir) throws Exception {
        Path sshDir = dir.resolve("ssh");
        Path priv = sshDir.resolve(KEY_FILE);
        Path pub = sshDir.resolve(KEY_FILE + ".pub");
        boolean ok = Files.isRegularFile(priv) && Files.size(priv) > 0 && Files.isRegularFile(pub) && Files.size(pub) > 0;
        if (!ok) {
            Files.createDirectories(sshDir);
            Files.deleteIfExists(priv);
            Files.deleteIfExists(pub);
            KeyPair kp = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 3072);
            try {
                kp.writePrivateKey(priv.toString());
                kp.writePublicKey(pub.toString(), "claudebridge");
            } finally {
                kp.dispose();
            }
            TlsSupport.restrict(priv);
        }
        return priv;
    }

    static String publicKeyLine(Path privateKey) throws IOException {
        return Files.readString(privateKey.resolveSibling(privateKey.getFileName() + ".pub"), StandardCharsets.US_ASCII).trim();
    }

    /** Answers every prompt with nothing: free tunnels (user "nokey") accept that, keyed tunnels never ask. */
    private static final class SilentUserInfo implements UserInfo, UIKeyboardInteractive {
        private final Consumer<String> output;

        SilentUserInfo(Consumer<String> output) {
            this.output = output;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return "";
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
            if (message != null) for (String l : message.split("\\R")) if (!l.isBlank()) output.accept(l.strip());
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
            if (instruction != null && !instruction.isBlank()) output.accept(instruction.strip());
            String[] answers = new String[prompt == null ? 0 : prompt.length];
            java.util.Arrays.fill(answers, "");
            return answers;
        }
    }
}
