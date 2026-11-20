package dev.claudebridge;

import dev.claudebridge.ApiServer.ApiException;
import dev.claudebridge.ApiServer.Request;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Read-only access to files inside the server directory. */
final class FileService {

    private final ClaudeBridgePlugin plugin;

    FileService(ClaudeBridgePlugin plugin) {
        this.plugin = plugin;
    }

    Path root() {
        Path root = plugin.serverRoot().resolve(plugin.getConfig().getString("files.root", ".")).normalize();
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /** Resolves a client path inside the root; rejects anything that escapes it (including via symlinks). */
    Path resolve(String raw) {
        Path root = root();
        String s = raw == null ? "" : raw.trim().replace('\\', '/');
        Path p = Paths.get(s);
        Path target = (p.isAbsolute() ? p : root.resolve(s)).normalize();
        if (!target.startsWith(root)) throw new ApiException(403, "path is outside of the server directory");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = target.toRealPath();
                if (!real.startsWith(root)) throw new ApiException(403, "symlink leads outside of the server directory");
                target = real;
            } catch (IOException e) {
                throw new ApiException(500, "cannot resolve path: " + e.getMessage());
            }
        }
        String rel = rel(target);
        if (isSecret(rel)) {
            throw new ApiException(403, "this path holds a ClaudeBridge secret (API token or private key) and is never readable through the API");
        }
        if (isDeniedByConfig(rel)) throw new ApiException(403, "path is denied by files.deny in config.yml");
        return target;
    }

    private boolean isDeniedByConfig(String rel) {
        for (String deny : plugin.getConfig().getStringList("files.deny")) {
            String d = deny.trim().replace('\\', '/');
            while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
            if (!d.isEmpty() && (rel.equals(d) || rel.startsWith(d + "/"))) return true;
        }
        return false;
    }

    /**
     * The plugin's own secrets: the API token, the SSH key of the tunnel, TLS and ACME private keys. Whoever holds the
     * token can already use the API, but these files must not end up in an agent's context, logs or answers.
     */
    private boolean isSecret(String rel) {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            if (Files.exists(data)) data = data.toRealPath();
        } catch (IOException ignored) {
            // keep the normalized path
        }
        String d = rel(data);
        if (rel.equals(d + "/token.txt") || rel.equals(d + "/tls.p12") || rel.equals(d + "/tls.pass") || rel.equals(d + "/key.pem")) return true;
        if (rel.equals(d + "/acme") || rel.startsWith(d + "/acme/")) return true;
        if (rel.startsWith(d + "/ssh/") && !rel.endsWith(".pub")) return true;
        String key = plugin.getConfig().getString("http.tls.files.private-key", "");
        if (key != null && !key.isBlank()) {
            Path k = root().resolve(key.trim()).normalize();
            try {
                if (Files.exists(k)) k = k.toRealPath();
            } catch (IOException ignored) {
                // compare the normalized path
            }
            if (k.startsWith(root()) && rel.equals(rel(k))) return true;
        }
        return false;
    }

    String rel(Path p) {
        String s = root().relativize(p).toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }

    private void checkEnabled() {
        if (!plugin.feature("files")) throw new ApiException(403, "files feature is disabled in config.yml");
    }

    Object list(Request r) throws IOException {
        checkEnabled();
        Path dir = resolve(r.str("path", ""));
        if (!Files.isDirectory(dir)) throw new ApiException(400, "not a directory: " + rel(dir));
        int depth = Math.max(1, Math.min(r.integer("depth", 1), 5));
        int max = Math.max(1, Math.min(r.integer("max", 1000), 5000));
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated;
        try (Stream<Path> s = Files.walk(dir, depth)) {
            List<Path> paths = s.filter(x -> !x.equals(dir)).sorted().limit(max + 1L).toList();
            truncated = paths.size() > max;
            for (Path x : paths.subList(0, Math.min(max, paths.size()))) entries.add(entry(x));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(dir));
        m.put("entries", entries);
        m.put("truncated", truncated);
        return m;
    }

    private Map<String, Object> entry(Path x) {
        Map<String, Object> e = new LinkedHashMap<>();
        boolean isDir = Files.isDirectory(x);
        e.put("path", rel(x));
        e.put("dir", isDir);
        try {
            e.put("size", isDir ? null : Files.size(x));
            e.put("modified", Instant.ofEpochMilli(Files.getLastModifiedTime(x).toMillis()).toString());
        } catch (IOException ex) {
            e.put("size", null);
            e.put("modified", null);
        }
        return e;
    }

    Object read(Request r) throws IOException {
        checkEnabled();
        Path f = resolve(r.require("path"));
        if (!Files.isRegularFile(f)) throw new ApiException(404, "file not found: " + rel(f));
        long size = Files.size(f);
        int maxRead = plugin.getConfig().getInt("files.max-read-bytes", 1048576);
        long offset = Math.max(0, r.lng("offset", 0));
        int limit = Math.max(1, Math.min(r.integer("limit", maxRead), maxRead));
        byte[] data = offset >= size ? new byte[0] : ServerService.readRange(f, offset, (int) Math.min(limit, size - offset));
        String encoding = r.str("encoding", "auto").toLowerCase(Locale.ROOT);
        String content;
        if (encoding.equals("base64") || (encoding.equals("auto") && !looksText(data))) {
            encoding = "base64";
            content = Base64.getEncoder().encodeToString(data);
        } else {
            encoding = "utf-8";
            content = new String(data, StandardCharsets.UTF_8);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(f));
        m.put("size", size);
        m.put("offset", offset);
        m.put("length", data.length);
        m.put("truncated", offset + data.length < size);
        m.put("encoding", encoding);
        m.put("content", content);
        return m;
    }

    Object find(Request r) throws IOException {
        checkEnabled();
        Path dir = resolve(r.str("path", ""));
        String glob = r.require("pattern");
        int max = Math.max(1, Math.min(r.integer("max", 200), 2000));
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir, 12)) {
            s.filter(x -> !x.equals(dir))
                    .filter(x -> matcher.matches(dir.relativize(x)) || matcher.matches(x.getFileName()))
                    .limit(max)
                    .forEach(x -> out.add(entry(x)));
        }
        return Map.of("matches", out);
    }

    Object grep(Request r) throws IOException {
        checkEnabled();
        Path dir = resolve(r.str("path", ""));
        String text = r.require("text").toLowerCase(Locale.ROOT);
        String glob = r.str("pattern", null);
        int max = Math.max(1, Math.min(r.integer("max", 200), 2000));
        PathMatcher matcher = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir, 12)) {
            files = s.filter(Files::isRegularFile)
                    .filter(x -> !isSecret(rel(x)) && !isDeniedByConfig(rel(x)))
                    .filter(x -> matcher == null || matcher.matches(x.getFileName()) || matcher.matches(dir.relativize(x)))
                    .filter(x -> {
                        try {
                            return Files.size(x) <= 2L * 1024 * 1024;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .limit(20000)
                    .toList();
        }
        for (Path f : files) {
            if (out.size() >= max) break;
            byte[] head = ServerService.readRange(f, 0, 8192);
            if (!looksText(head)) continue;
            try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                int no = 0;
                while ((line = br.readLine()) != null && out.size() < max) {
                    no++;
                    if (line.toLowerCase(Locale.ROOT).contains(text)) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("path", rel(f));
                        m.put("line", no);
                        m.put("text", line.length() > 300 ? line.substring(0, 300) + "..." : line);
                        out.add(m);
                    }
                }
            } catch (IOException ignored) {
                // Not valid UTF-8 or unreadable: skip.
            }
        }
        return Map.of("matches", out, "truncated", out.size() >= max);
    }

    static boolean looksText(byte[] data) {
        int n = Math.min(data.length, 8192);
        for (int i = 0; i < n; i++) if (data[i] == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data, 0, Math.max(0, n - 4)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
