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

    private final ClaudeBridgePlugin plugin;
    private final Path dataDir;
    private final Deque<String> lastLines = new ArrayDeque<>();
    private volatile String provider = "none";
    private volatile String state = "off";
    private volatile String publicUrl;
    private volatile boolean running;
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
        if (!provider.equals("cloudflare") && !provider.equals("ngrok")) {
            state = "unknown tunnel.provider '" + provider + "' (use none, cloudflare or ngrok)";
            plugin.getLogger().severe("Tunnel: " + state);
            return;
        }
        if (provider.equals("ngrok") && c.getString("tunnel.ngrok.authtoken", "").isBlank()) {
            state = "tunnel.ngrok.authtoken is empty (get it at https://dashboard.ngrok.com/get-started/your-authtoken)";
            plugin.getLogger().severe("Tunnel: " + state);
            return;
        }
        String target = (localTls ? "https" : "http") + "://127.0.0.1:" + localPort;
        running = true;
        state = "starting";
        supervisor = new Thread(() -> supervise(target, localTls), "ClaudeBridge-tunnel");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    void stop() {
        running = false;
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

    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("state", state);
        m.put("url", publicUrl);
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
                state = "starting " + provider + " agent";
                publicUrl = null;
                plugin.getLogger().info("Tunnel: starting " + provider + " agent for " + target);
                Process p = pb.start();
                process = p;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        remember(line);
                        String url = extractUrl(provider, line);
                        if (url != null && !url.equals(publicUrl)) {
                            publicUrl = url;
                            state = "online";
                            backoff = 10_000L;
                            Files.writeString(dataDir.resolve("tunnel-url.txt"), url + System.lineSeparator(), StandardCharsets.UTF_8);
                            plugin.getLogger().info("Tunnel ready: " + url + "  (give this address to the AI; it is also saved in plugins/ClaudeBridge/tunnel-url.txt)");
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
    static String extractUrl(String provider, String line) {
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
        Path bin = dir.resolve(provider.equals("ngrok") ? "ngrok" : "cloudflared");
        if (Files.isRegularFile(bin) && Files.size(bin) > 1_000_000) {
            bin.toFile().setExecutable(true);
            return bin;
        }
        state = "downloading " + provider + " agent";
        if (provider.equals("ngrok")) {
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
