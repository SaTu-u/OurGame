package dev.claudebridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Public HTTPS address without open ports: runs a tunnel agent (Cloudflare quick tunnel or ngrok) that connects
 * out to the provider and forwards https://&lt;public-name&gt; to the local API listener.
 * Used when the hosting cannot expose port 443. The agent binary is downloaded on first use (Linux amd64/arm64).
 */
final class TunnelManager {

    private static final Pattern CLOUDFLARE_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Pattern NGROK_URL = Pattern.compile("https://[a-zA-Z0-9.-]+\\.ngrok(?:-free)?\\.(?:app|dev|io)");
    private static final Pattern LHR_HOST = Pattern.compile(
            "(?<![a-z0-9.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+lhr\\.(?:life|rocks|pro))(?![a-z0-9-])");
    private static final Pattern TUNA_HOST = Pattern.compile("(?<![a-z0-9.-])([a-z0-9][a-z0-9-]*\\.[a-z]{2}\\.tuna\\.am)");

    private final ClaudeBridgePlugin plugin;
    private final Path dataDir;
    private final Deque<String> lastLines = new ArrayDeque<>();
    private volatile String provider = "none";
    private volatile String state = "off";
    private volatile String publicUrl;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile Process process;
    private Thread supervisor;

    TunnelManager(ClaudeBridgePlugin plugin) {
        this.plugin = plugin;
        // Absolute: the agent is started with its own working directory, so a relative program path would not resolve.
        this.dataDir = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    }

    void start(int localPort, boolean localTls) {
        FileConfiguration c = plugin.getConfig();
        provider = c.getString("tunnel.provider", "none").trim().toLowerCase(Locale.ROOT);
        if (provider.isEmpty() || provider.equals("none") || provider.equals("off") || provider.equals("false")) {
            provider = "none";
            state = "off";
            return;
        }
        if (provider.equals("localhost.run") || provider.equals("lhr")) provider = "localhostrun";
        if (!List.of("cloudflare", "ngrok", "localhostrun", "tuna", "tuna-ssh").contains(provider)) {
            state = "unknown tunnel.provider '" + provider + "' (use none, localhostrun, tuna, tuna-ssh, cloudflare or ngrok)";
            plugin.getLogger().severe("Tunnel: " + state);
            return;
        }
        if (provider.equals("ngrok") && c.getString("tunnel.ngrok.authtoken", "").isBlank()) {
            state = "tunnel.ngrok.authtoken is empty (get it at https://dashboard.ngrok.com/get-started/your-authtoken)";
            plugin.getLogger().severe("Tunnel: " + state);
            return;
        }
        if (provider.equals("tuna") && c.getString("tunnel.tuna.api-key", "").isBlank()) {
            state = "tunnel.tuna.api-key is empty (create it in your tuna.am dashboard)";
            plugin.getLogger().severe("Tunnel: " + state);
            return;
        }
        if (localTls && isSsh()) {
            state = "SSH tunnels need http.tls.mode: off (the tunnel service provides HTTPS)";
            plugin.getLogger().severe("Tunnel: " + state);
            return;
        }
        String target = (localTls ? "https" : "http") + "://127.0.0.1:" + localPort;
        running = true;
        state = "starting";
        supervisor = new Thread(() -> {
            if (isSsh()) superviseSsh(localPort);
            else supervise(target, localTls);
        }, "ClaudeBridge-tunnel");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    private boolean isSsh() {
        return provider.equals("localhostrun") || provider.equals("tuna-ssh");
    }

    // ------------------------------------------------------------------ SSH tunnels (no external program)

    private volatile SshTunnel sshTunnel;

    private void superviseSsh(int localPort) {
        FileConfiguration c = plugin.getConfig();
        long backoff = 10_000L;
        while (running) {
            try {
                boolean lhr = provider.equals("localhostrun");
                boolean useKey = !lhr || c.getBoolean("tunnel.localhostrun.use-key", false);
                Path key = null;
                if (useKey) {
                    key = SshTunnel.ensureKey(dataDir);
                    plugin.getLogger().info("Tunnel: SSH public key (" + (lhr ? "register it at https://admin.localhost.run to keep your address"
                            : "add it in your tuna.am dashboard, SSH keys") + "):\n  " + SshTunnel.publicKeyLine(key)
                            + "\n  (also in plugins/ClaudeBridge/ssh/id_rsa.pub)");
                }
                String host = c.getString("tunnel.ssh.host", "").isBlank() ? (lhr ? "localhost.run" : "ssh-ru.tuna.am") : c.getString("tunnel.ssh.host").trim();
                int port = c.getInt("tunnel.ssh.port", 0) > 0 ? c.getInt("tunnel.ssh.port") : 22;
                String user = lhr ? (useKey ? "claudebridge" : "nokey") : "claudebridge";
                int remotePort = lhr ? 80 : 443;
                String command = lhr ? "--output json" : "http";
                SshTunnel t = new SshTunnel(host, port, user, key, remotePort, localPort, command);
                sshTunnel = t;
                state = "connecting to " + host;
                publicUrl = null;
                connected = false;
                plugin.getLogger().info("Tunnel: connecting to " + host + ":" + port + " over SSH for http://127.0.0.1:" + localPort);
                SshSession st = new SshSession();
                currentSsh = st;
                try {
                    t.run(line -> onSshLine(st, line), msg -> {
                        if (msg.startsWith("SSH problem")) plugin.getLogger().warning("Tunnel: " + msg);
                        else plugin.getLogger().info("Tunnel: " + msg);
                    }, () -> abortReason(st));
                } finally {
                    st.active = false;
                    if (st.hadUrl) backoff = 10_000L;
                }
            } catch (Exception e) {
                if (!running) break;
                SshSession last = currentSsh;
                boolean wasOnline = connected || (last != null && last.hadUrl);
                publicUrl = null;
                connected = false;
                check = null;
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                state = "disconnected: " + msg;
                String hint = "";
                if (msg.contains("Auth fail") || msg.contains("Auth cancel")) {
                    hint = provider.equals("tuna-ssh") ? " Add the SSH public key shown above in your tuna.am dashboard."
                            : " If use-key is true, register the public key at https://admin.localhost.run or set use-key: false.";
                } else if (msg.contains("socket is not established") || msg.contains("connect timed out") || msg.contains("Connection refused")
                        || msg.contains("UnknownHost") || msg.contains("No route to host")) {
                    // Only when the TCP/SSH connection itself failed, not when a later step timed out.
                    hint = " The hosting may block outgoing SSH (port 22).";
                }
                plugin.getLogger().warning("Tunnel: " + (wasOnline ? "connection lost" : "could not connect") + ": " + msg + "." + hint
                        + " Reconnecting in " + backoff / 1000 + " s.");
                if (wasOnline) backoff = 10_000L;
            } finally {
                sshTunnel = null;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 300_000L);
        }
    }

    /** One SSH connection to the tunnel service. */
    private static final class SshSession {
        final long started = System.currentTimeMillis();
        volatile boolean active = true;
        volatile boolean hadUrl;
        volatile long removedAt;
        volatile String abortReason;
        int logged;
    }

    private volatile SshSession currentSsh;
    private volatile String check;

    private final java.util.Set<String> shownLines = new java.util.LinkedHashSet<>();
    private int throttledSinceNote;
    private long lastThrottleNote;

    /**
     * Console output of the service's messages without flooding: plain lines (the banner) only the first time they are
     * seen, tunnel events always, and speed-limit notices (localhost.run free plan, one per minute while traffic is
     * heavy) as one explanation and then a summary at most every 30 minutes.
     */
    private void logServiceLine(SshSession st, String line, LhrEvent ev) {
        String type = ev == null || ev.type() == null ? "" : ev.type().toLowerCase(Locale.ROOT);
        if (type.endsWith("throttled")) {
            throttledSinceNote++;
            long now = System.currentTimeMillis();
            if (lastThrottleNote == 0 || now - lastThrottleNote > 30 * 60_000L) {
                plugin.getLogger().warning("Tunnel: localhost.run is slowing this tunnel (free plan speed limit"
                        + (throttledSinceNote > 1 ? ", " + throttledSinceNote + " notices since the last report" : "")
                        + "): " + readable(line).replaceFirst("^tunnel\\.throttled \\S+: ", "")
                        + ". Large API transfers are slow; small requests still work. Further notices are summarised every 30 min.");
                lastThrottleNote = now;
                throttledSinceNote = 0;
            }
            return;
        }
        String text = readable(line);
        if (ev == null) {
            String key = text.replaceAll("\\d+", "#");
            synchronized (shownLines) {
                if (!shownLines.add(key)) return;
                if (shownLines.size() > 500) shownLines.remove(shownLines.iterator().next());
            }
        }
        if (st.logged >= 80) return;
        st.logged++;
        plugin.getLogger().info("Tunnel service: " + text);
        if (st.logged == 80) plugin.getLogger().info("Tunnel: further messages from the service are not shown");
    }

    private void onSshLine(SshSession st, String line) {
        remember(line);
        LhrEvent ev = provider.equals("localhostrun") ? lhrEvent(line) : null;
        logServiceLine(st, line, ev);
        if (ev != null && ev.removal()) {
            String gone = ev.host() == null ? null : "https://" + ev.host();
            String current = publicUrl;
            if (current == null || gone == null || gone.equals(current)) {
                publicUrl = null;
                connected = false;
                check = null;
                st.removedAt = System.currentTimeMillis();
                String what = gone == null ? "the tunnel" : gone;
                state = "the service removed " + what + ", waiting for a new address";
                plugin.getLogger().warning("Tunnel: the service removed " + what
                        + ". Waiting for a new address; reconnecting in 20 s if none comes.");
            }
            return;
        }
        String url = ev != null && ev.host() != null ? "https://" + ev.host() : extractUrl(provider, line);
        if (url == null || url.equals(publicUrl)) return;
        boolean replacing = st.hadUrl;
        publicUrl = url;
        connected = true;
        st.hadUrl = true;
        st.removedAt = 0;
        state = "online";
        try {
            Files.writeString(dataDir.resolve("tunnel-url.txt"), url + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the address is in the log anyway
        }
        plugin.getLogger().info("Tunnel ready: " + url + (replacing ? "  (NEW address, the old one no longer works)" : "")
                + "  (give this address to the AI; it is also saved in plugins/ClaudeBridge/tunnel-url.txt)");
        startSelfCheck(st, url);
    }

    /**
     * The service message for the console: for localhost.run events "type subject: message" without the QR code
     * (dozens of terminal colour codes) that follows the address; other lines without escape codes, shortened.
     */
    static String readable(String line) {
        String t = line.trim();
        if (t.startsWith("{")) {
            try {
                JsonElement e = JsonParser.parseString(t);
                if (e.isJsonObject() && e.getAsJsonObject().has("type")) {
                    JsonObject o = e.getAsJsonObject();
                    String type = string(o, "type");
                    String subject = string(o, "subject");
                    String message = null;
                    JsonElement data = o.get("data");
                    if (data != null && data.isJsonObject()) message = string(data.getAsJsonObject(), "message");
                    if (message == null && data != null) message = data.toString();
                    StringBuilder b = new StringBuilder(type == null ? "event" : type.replaceFirst("^run\\.localhost\\.", ""));
                    if (subject != null) b.append(' ').append(subject);
                    if (message != null) {
                        int qr = message.indexOf("Open your tunnel address on your mobile");
                        if (qr >= 0) message = message.substring(0, qr);
                        message = cleanEscapes(message).replaceAll("\\s*\\R\\s*", " | ").replaceAll("[ |]+$", "").strip();
                        if (!message.isEmpty()) b.append(": ").append(message);
                    }
                    return shorten(maskEmails(b.toString()), 600);
                }
            } catch (RuntimeException ignored) {
                // not JSON after all
            }
        }
        return shorten(maskEmails(cleanEscapes(line).strip()), 600);
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** The service names the account's e-mail address; console screenshots get shared, so it is never printed. */
    static String maskEmails(String s) {
        return EMAIL.matcher(s).replaceAll("<account e-mail>");
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String abortReason(SshSession st) {
        if (st.abortReason != null) return st.abortReason;
        long now = System.currentTimeMillis();
        if (publicUrl == null && st.removedAt > 0 && now - st.removedAt > 20_000L) return "the service removed the tunnel and gave no new address";
        if (publicUrl == null && st.removedAt == 0 && now - st.started > 90_000L) return "the service gave no public address within 90 s";
        return null;
    }

    /**
     * Opens the public address from the server itself: proves that the tunnel really reaches the API, and replaces a
     * session whose address worked and then stopped answering. Failures before the first success are only reported:
     * some hostings cannot open their own public address, which does not mean that others cannot.
     */
    private void startSelfCheck(SshSession st, String url) {
        if (!plugin.getConfig().getBoolean("tunnel.self-check", true)) return;
        Thread th = new Thread(() -> {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            Boolean last = null;
            boolean everOk = false;
            int fails = 0;
            long delay = 3_000L;
            while (running && st.active && url.equals(publicUrl)) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                delay = 60_000L;
                if (!(running && st.active && url.equals(publicUrl))) return;
                String problem = probe(http, url);
                if (problem == null) {
                    check = "ok";
                    if (!Boolean.TRUE.equals(last)) plugin.getLogger().info("Tunnel check: " + url + " answers, the tunnel works.");
                    last = true;
                    everOk = true;
                    fails = 0;
                } else {
                    check = "failing: " + problem;
                    fails++;
                    if (!Boolean.FALSE.equals(last)) {
                        plugin.getLogger().warning("Tunnel check: " + url + " did not answer from this server (" + problem + "). "
                                + (everOk ? "Reconnecting if this continues."
                                : "The hosting may not be able to open its own public address; it can still work from outside."));
                    }
                    last = false;
                    if (everOk && fails >= 3) {
                        st.abortReason = "the public address stopped answering";
                        return;
                    }
                }
            }
        }, "ClaudeBridge-tunnel-check");
        th.setDaemon(true);
        th.start();
    }

    private static String probe(HttpClient http, String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + "/health"))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "ClaudeBridge-selfcheck").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200 && r.body().contains(ApiServer.INSTANCE_ID)) return null;
            String body = r.body() == null ? "" : r.body().strip().replaceAll("\\s+", " ");
            return "HTTP " + r.statusCode() + (body.isEmpty() ? "" : ": " + (body.length() > 120 ? body.substring(0, 120) + "..." : body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /** One message of `ssh ... localhost.run -- --output json` (CloudEvents: type, subject = tunnel domain, data). */
    record LhrEvent(String type, String host, boolean removal) {
    }

    static LhrEvent lhrEvent(String rawLine) {
        String t = rawLine.trim();
        if (!t.startsWith("{")) return null;
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(t);
            if (!e.isJsonObject()) return null;
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        String type = string(o, "type");
        if (type == null) type = string(o, "event");
        String host = null;
        String subject = string(o, "subject");
        if (subject != null) host = lhrHost(subject);
        if (host == null && o.has("data")) host = lhrHost(cleanEscapes(o.get("data").toString()));
        if (host == null) host = lhrHost(cleanEscapes(t));
        String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
        // Only the tunnel itself (e.g. run.localhost.tunnel.removed), not events about single HTTP connections.
        boolean removal = lower.contains("tunnel") && (lower.endsWith("removed") || lower.endsWith("deleted") || lower.endsWith("closed")
                || lower.endsWith("expired") || lower.endsWith("stopped") || lower.endsWith("down"));
        return new LhrEvent(type, host, removal);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** First localhost.run tunnel domain in the text: any name under lhr.life / lhr.rocks / lhr.pro, e.g. 33a8deeca0b657.app.lhr.life. */
    static String lhrHost(String text) {
        Matcher m = LHR_HOST.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String h = m.group(1);
            if (h.startsWith("admin.") || h.startsWith("www.") || h.startsWith("docs.")) continue;
            return h;
        }
        return null;
    }

    private static String cleanEscapes(String raw) {
        return raw.replaceAll("\\\\u001[bB]\\[[0-9;?]*[ -/]*[@-~]", " ").replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", " ");
    }

    void stop() {
        running = false;
        SshTunnel t = sshTunnel;
        if (t != null) t.stop();
        Process p = process;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (supervisor != null) supervisor.interrupt();
        publicUrl = null;
        state = "off";
    }

    String publicUrl() {
        return publicUrl;
    }

    boolean connected() {
        return connected;
    }

    String state() {
        return state;
    }

    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("state", state);
        m.put("url", publicUrl);
        m.put("connected", connected);
        if (check != null) m.put("check", check);
        return m;
    }

    // ------------------------------------------------------------------ supervisor

    private void supervise(String target, boolean localTls) {
        long backoff = 10_000L;
        while (running) {
            try {
                Path bin = ensureBinary().toAbsolutePath().normalize();
                List<String> cmd = command(bin, target, localTls);
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(bin.getParent().toFile());
                Path home = dataDir.resolve("bin").resolve("home");
                Files.createDirectories(home);
                pb.environment().put("HOME", home.toString());
                if (provider.equals("ngrok")) pb.environment().put("NGROK_AUTHTOKEN", plugin.getConfig().getString("tunnel.ngrok.authtoken", "").trim());
                if (provider.equals("tuna")) {
                    String key = plugin.getConfig().getString("tunnel.tuna.api-key", "").trim();
                    pb.environment().put("TUNA_API_KEY", key);
                    pb.environment().put("TUNA_TOKEN", key);
                }
                state = "starting " + provider + " agent";
                publicUrl = null;
                plugin.getLogger().info("Tunnel: starting " + provider + " agent for " + target);
                Process p = pb.start();
                process = p;
                connected = false;
                long urlAt = 0;
                long lastForward = 0;
                boolean hinted = false;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        remember(line);
                        long now = System.currentTimeMillis();
                        String url = extractUrl(provider, line);
                        if (url != null && !url.equals(publicUrl)) {
                            publicUrl = url;
                            urlAt = now;
                            // ngrok and tuna print the URL once the session is up; cloudflared prints it before connecting to the edge.
                            connected = !provider.equals("cloudflare");
                            state = connected ? "online" : "address assigned, connecting to Cloudflare";
                            backoff = 10_000L;
                            Files.writeString(dataDir.resolve("tunnel-url.txt"), url + System.lineSeparator(), StandardCharsets.UTF_8);
                            plugin.getLogger().info((connected ? "Tunnel ready: " : "Tunnel address: ") + url
                                    + (connected ? "  (give this address to the AI; it is also saved in plugins/ClaudeBridge/tunnel-url.txt)"
                                    : "  (connecting to Cloudflare...)"));
                        }
                        if (!connected && provider.equals("cloudflare") && line.contains("Registered tunnel connection")) {
                            connected = true;
                            state = "online";
                            plugin.getLogger().info("Tunnel ready: " + publicUrl
                                    + "  (connected; give this address to the AI, it is also saved in plugins/ClaudeBridge/tunnel-url.txt)");
                        }
                        if (isProblem(line) && now - lastForward > 15_000L) {
                            lastForward = now;
                            plugin.getLogger().warning("Tunnel agent: " + (line.length() > 240 ? line.substring(0, 240) + "..." : line));
                        }
                        if (!connected && !hinted && urlAt > 0 && now - urlAt > 60_000L) {
                            hinted = true;
                            plugin.getLogger().warning("Tunnel: not connected 60 s after getting an address. The hosting probably blocks"
                                    + " outgoing port 7844 used by Cloudflare. Try tunnel.cloudflare.protocol: http2, or tunnel.provider: ngrok"
                                    + " (ngrok uses outgoing port 443).");
                        }
                    }
                }
                int code = p.waitFor();
                if (!running) break;
                publicUrl = null;
                state = "agent exited with code " + code;
                plugin.getLogger().warning("Tunnel: " + provider + " agent exited with code " + code
                        + ", restarting in " + backoff / 1000 + " s. Last output:\n  " + String.join("\n  ", lastLines()));
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (!running) break;
                publicUrl = null;
                state = "failed: " + e.getMessage();
                String hint = String.valueOf(e.getMessage()).contains("Permission denied") || String.valueOf(e.getMessage()).contains("error=13")
                        ? " The hosting does not allow running programs from the server folder; ask the hosting support or use another way to expose port 443."
                        : "";
                plugin.getLogger().severe("Tunnel: " + e.getMessage() + "." + hint + " Retrying in " + backoff / 1000 + " s.");
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 300_000L);
        }
    }

    private static boolean isProblem(String line) {
        return line.contains(" ERR ") || line.contains(" WRN ") || line.contains("\"lvl\":\"eror\"")
                || line.contains("\"lvl\":\"crit\"") || line.contains("\"lvl\":\"warn\"");
    }

    private synchronized void remember(String line) {
        lastLines.addLast(line.length() > 300 ? line.substring(0, 300) : line);
        while (lastLines.size() > 15) lastLines.removeFirst();
    }

    private synchronized List<String> lastLines() {
        return new ArrayList<>(lastLines);
    }

    List<String> command(Path bin, String target, boolean localTls) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.toString());
        FileConfiguration c = plugin.getConfig();
        if (provider.equals("cloudflare")) {
            cmd.add("tunnel");
            cmd.add("--no-autoupdate");
            String protocol = c.getString("tunnel.cloudflare.protocol", "auto").trim();
            if (!protocol.isEmpty() && !protocol.equals("auto")) {
                cmd.add("--protocol");
                cmd.add(protocol);
            }
            if (localTls) cmd.add("--no-tls-verify");
            cmd.add("--url");
            cmd.add(target);
        } else if (provider.equals("tuna")) {
            cmd.add("http");
            cmd.add(target.substring(target.lastIndexOf(':') + 1));
            String sub = c.getString("tunnel.tuna.subdomain", "").trim();
            if (!sub.isEmpty()) cmd.add("--subdomain=" + sub);
        } else {
            cmd.add("http");
            cmd.add(target);
            cmd.add("--log");
            cmd.add("stdout");
            cmd.add("--log-format");
            cmd.add("json");
            String domain = c.getString("tunnel.ngrok.domain", "").trim();
            if (!domain.isEmpty()) {
                cmd.add("--url");
                cmd.add(domain.startsWith("https://") ? domain : "https://" + domain);
            }
        }
        return cmd;
    }

    /** Finds the public URL in one line of agent output (ngrok: JSON logs, cloudflared: plain text). */
    static String extractUrl(String provider, String rawLine) {
        // Terminal styling may arrive as real escape codes or, inside JSON strings, as the text "\u001b[1m".
        String line = rawLine.replaceAll("\\\\u001[bB]\\[[0-9;?]*[ -/]*[@-~]", " ")
                .replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", " ");
        if (provider.equals("localhostrun")) {
            String h = lhrHost(line);
            return h == null ? null : "https://" + h;
        }
        if (provider.equals("tuna") || provider.equals("tuna-ssh")) {
            Matcher m = TUNA_HOST.matcher(line);
            return m.find() ? "https://" + m.group(1) : null;
        }
        if (provider.equals("ngrok")) {
            String t = line.trim();
            if (t.startsWith("{")) {
                try {
                    JsonElement e = JsonParser.parseString(t);
                    if (e.isJsonObject()) {
                        JsonObject o = e.getAsJsonObject();
                        if (o.has("url") && o.get("url").getAsString().startsWith("https://")) return o.get("url").getAsString();
                    }
                } catch (RuntimeException ignored) {
                    // not JSON after all
                }
            }
            Matcher m = NGROK_URL.matcher(line);
            return m.find() ? m.group() : null;
        }
        Matcher m = CLOUDFLARE_URL.matcher(line);
        if (m.find() && !m.group().startsWith("https://api.")) return m.group();
        return null;
    }

    // ------------------------------------------------------------------ agent binary

    private Path ensureBinary() throws Exception {
        String custom = plugin.getConfig().getString("tunnel.binary", "").trim();
        if (!custom.isEmpty()) {
            Path p = plugin.serverRoot().resolve(custom).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) throw new IOException("tunnel.binary " + p + " does not exist");
            p.toFile().setExecutable(true);
            return p;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            throw new IOException("automatic download is only for Linux; put the " + provider
                    + " program into plugins/ClaudeBridge/bin and set tunnel.binary");
        }
        String archProp = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = archProp.equals("amd64") || archProp.equals("x86_64") ? "amd64"
                : archProp.equals("aarch64") || archProp.equals("arm64") ? "arm64" : null;
        if (arch == null) throw new IOException("unsupported CPU architecture " + archProp);
        Path dir = dataDir.resolve("bin");
        Files.createDirectories(dir);
        Path bin = dir.resolve(provider.equals("ngrok") ? "ngrok" : provider.equals("tuna") ? "tuna" : "cloudflared");
        if (Files.isRegularFile(bin) && Files.size(bin) > 1_000_000) {
            bin.toFile().setExecutable(true);
            return bin;
        }
        state = "downloading " + provider + " agent";
        if (provider.equals("tuna")) {
            String url = "https://releases.tuna.am/tuna/latest/tuna_linux_" + arch + ".tar.gz";
            plugin.getLogger().info("Tunnel: downloading " + url);
            Path tgz = download(url, dir.resolve("tuna.tgz.part"));
            try {
                extractFromTarGz(tgz, "tuna", bin);
            } finally {
                Files.deleteIfExists(tgz);
            }
        } else if (provider.equals("ngrok")) {
            String url = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-" + arch + ".tgz";
            plugin.getLogger().info("Tunnel: downloading " + url);
            Path tgz = download(url, dir.resolve("ngrok.tgz.part"));
            try {
                extractFromTarGz(tgz, "ngrok", bin);
            } finally {
                Files.deleteIfExists(tgz);
            }
        } else {
            String url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;
            plugin.getLogger().info("Tunnel: downloading " + url);
            Path part = download(url, dir.resolve("cloudflared.part"));
            Files.move(part, bin, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(bin) < 1_000_000) throw new IOException("downloaded " + provider + " agent looks broken (" + Files.size(bin) + " bytes)");
        if (!bin.toFile().setExecutable(true)) throw new IOException("cannot mark " + bin + " as executable");
        plugin.getLogger().info("Tunnel: " + provider + " agent saved to " + plugin.serverRoot().relativize(bin.toAbsolutePath()));
        return bin;
    }

    static Path download(String url, Path to) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "ClaudeBridge").GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(to));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(to);
            throw new IOException("download of " + url + " failed: HTTP " + resp.statusCode());
        }
        return to;
    }

    /** Extracts one regular file from a .tar.gz archive (plain ustar parsing, no external tools). */
    static void extractFromTarGz(Path tgz, String name, Path to) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(tgz))) {
            byte[] header = new byte[512];
            while (true) {
                if (in.readNBytes(header, 0, 512) < 512) break;
                boolean empty = true;
                for (byte b : header) {
                    if (b != 0) {
                        empty = false;
                        break;
                    }
                }
                if (empty) break;
                String entry = new String(header, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
                String sizeOctal = new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim();
                long size = sizeOctal.isEmpty() ? 0 : Long.parseLong(sizeOctal, 8);
                char type = (char) header[156];
                String base = entry.contains("/") ? entry.substring(entry.lastIndexOf('/') + 1) : entry;
                long padded = (size + 511) / 512 * 512;
                if ((type == '0' || type == 0) && base.equals(name)) {
                    Path part = to.resolveSibling(to.getFileName() + ".part");
                    try (OutputStream out = Files.newOutputStream(part)) {
                        long left = size;
                        byte[] buf = new byte[65536];
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("truncated archive");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                in.skipNBytes(padded);
            }
        }
        throw new IOException("'" + name + "' not found in " + tgz.getFileName());
    }
}
