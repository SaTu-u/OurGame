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

    // ------------------------------------------------------------------ writing (opt-in: features.file-write)

    private void checkWritable() {
        checkEnabled();
        if (!plugin.getConfig().getBoolean("features.file-write", false)) {
            throw new ApiException(403, "file writing is disabled; the server owner can enable it with features.file-write: true "
                    + "in plugins/ClaudeBridge/config.yml (then /claudebridge reload)");
        }
    }

    private String dataRel() {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            if (Files.exists(data)) data = data.toRealPath();
        } catch (IOException ignored) {
            // keep the normalized path
        }
        return rel(data);
    }

    /** A path the API may change: inside the server folder, not a secret, not ClaudeBridge itself, no symlink escape. */
    private Path writableTarget(String raw) throws IOException {
        Path target = resolve(raw);
        String rel = rel(target);
        String data = dataRel();
        if (rel.equals(".")) throw new ApiException(403, "the server folder itself cannot be changed");
        if (rel.equals(data) || rel.startsWith(data + "/")) {
            throw new ApiException(403, "ClaudeBridge's own folder (config, token, backups) cannot be changed through the API");
        }
        if (rel.matches("plugins/[^/]*[Cc]laude[Bb]ridge[^/]*\\.jar")) throw new ApiException(403, "the ClaudeBridge jar cannot be changed through the API");
        Path parent = target.getParent();
        while (parent != null && !Files.exists(parent)) parent = parent.getParent();
        if (parent != null && !parent.toRealPath().startsWith(root())) throw new ApiException(403, "path leads outside of the server directory");
        return target;
    }

    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** Copies (or moves) a file to plugins/ClaudeBridge/backups/<time>/<path>; answers the backup's path. */
    private String backup(Path f, boolean move) throws IOException {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path dest = data.resolve("backups").resolve(java.time.LocalDateTime.now().format(STAMP)).resolve(rel(f)).normalize();
        Files.createDirectories(dest.getParent());
        if (move) Files.move(f, dest);
        else Files.copy(f, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        return rel(dest);
    }

    private static String sha1(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".claudebridge-", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** POST {path, content, encoding?=utf-8|base64, expected_sha1?}: creates or overwrites a file (the old one is backed up). */
    Object write(Request r) throws IOException {
        checkWritable();
        Path f = writableTarget(r.require("path"));
        String content = r.str("content");
        if (content == null) throw new ApiException(400, "missing parameter: content");
        byte[] data = r.str("encoding", "utf-8").equalsIgnoreCase("base64")
                ? Base64.getDecoder().decode(content) : content.getBytes(StandardCharsets.UTF_8);
        if (Files.isDirectory(f)) throw new ApiException(422, "that path is a directory");
        String backup = null;
        if (Files.isRegularFile(f)) {
            String expected = r.str("expected_sha1");
            if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(sha1(Files.readAllBytes(f)))) {
                throw new ApiException(409, "the file changed since you read it (expected_sha1 does not match); read it again");
            }
            backup = backup(f, false);
        } else if (r.has("expected_sha1")) {
            throw new ApiException(409, "the file does not exist any more");
        }
        writeAtomically(f, data);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(f));
        m.put("size", data.length);
        m.put("sha1", sha1(data));
        m.put("backup", backup);
        plugin.getLogger().info("API changed file " + rel(f) + (backup == null ? " (new file)" : " (backup: " + backup + ")"));
        return m;
    }

    /** POST {path, find, replace, all?=false}: replaces text in a text file (exactly one match unless all is true). */
    Object replace(Request r) throws IOException {
        checkWritable();
        Path f = writableTarget(r.require("path"));
        if (!Files.isRegularFile(f)) throw new ApiException(404, "file not found: " + rel(f));
        String find = r.str("find");
        String with = r.str("replace");
        if (find == null || find.isEmpty()) throw new ApiException(400, "missing parameter: find");
        if (with == null) throw new ApiException(400, "missing parameter: replace");
        byte[] old = Files.readAllBytes(f);
        if (!looksText(old)) throw new ApiException(422, "not a text file; use files/write with base64");
        String text = new String(old, StandardCharsets.UTF_8);
        int count = 0;
        for (int i = text.indexOf(find); i >= 0; i = text.indexOf(find, i + find.length())) count++;
        if (count == 0) throw new ApiException(422, "the text to replace was not found in " + rel(f));
        if (count > 1 && !r.bool("all", false)) {
            throw new ApiException(422, "the text occurs " + count + " times; pass all: true or a longer, unique find text");
        }
        String updated = r.bool("all", false) ? text.replace(find, with) : text.replaceFirst(java.util.regex.Pattern.quote(find),
                java.util.regex.Matcher.quoteReplacement(with));
        String backup = backup(f, false);
        byte[] data = updated.getBytes(StandardCharsets.UTF_8);
        writeAtomically(f, data);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(f));
        m.put("replaced", r.bool("all", false) ? count : 1);
        m.put("sha1", sha1(data));
        m.put("backup", backup);
        plugin.getLogger().info("API edited file " + rel(f) + " (backup: " + backup + ")");
        return m;
    }

    /** POST {path}: removes a file or folder by moving it into the backups (so it can be restored). */
    Object delete(Request r) throws IOException {
        checkWritable();
        Path f = writableTarget(r.require("path"));
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) throw new ApiException(404, "not found: " + rel(f));
        String backup = backup(f, true);
        plugin.getLogger().info("API removed " + rel(f) + " (kept in " + backup + ")");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(f));
        m.put("backup", backup);
        m.put("note", f.toString().endsWith(".jar") ? "a plugin stays loaded until the server restarts" : null);
        return m;
    }

    /** POST {from, to}: moves or renames a file or folder; the target must not exist. */
    Object move(Request r) throws IOException {
        checkWritable();
        Path from = writableTarget(r.require("from"));
        Path to = writableTarget(r.require("to"));
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) throw new ApiException(404, "not found: " + rel(from));
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new ApiException(409, "the target already exists: " + rel(to));
        Files.createDirectories(to.getParent());
        Files.move(from, to);
        plugin.getLogger().info("API moved " + rel(from) + " to " + rel(to));
        return Map.of("from", rel(from), "to", rel(to));
    }

    /** POST {backup}: puts a file from plugins/ClaudeBridge/backups/<time>/<path> back to <path> (the current one is backed up). */
    Object restore(Request r) throws IOException {
        checkWritable();
        String b = r.require("backup").trim().replace('\\', '/');
        String prefix = dataRel() + "/backups/";
        if (!b.startsWith(prefix)) throw new ApiException(400, "backup must be a path under " + prefix);
        Path src = resolve(b);
        if (!Files.isRegularFile(src)) throw new ApiException(404, "backup not found: " + b);
        String rest = b.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash < 0) throw new ApiException(400, "backup must look like " + prefix + "<time>/<path>");
        Path target = writableTarget(rest.substring(slash + 1));
        String previous = Files.isRegularFile(target) ? backup(target, false) : null;
        writeAtomically(target, Files.readAllBytes(src));
        plugin.getLogger().info("API restored " + rel(target) + " from " + b);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(target));
        m.put("from", b);
        m.put("backup", previous);
        return m;
    }

    /** Checksums of one file computed on the server, so its integrity can be checked without downloading it. */
    Object hash(Request r) throws IOException {
        checkEnabled();
        Path f = resolve(r.require("path"));
        if (!Files.isRegularFile(f)) throw new ApiException(404, "file not found: " + rel(f));
        java.security.MessageDigest sha1;
        java.security.MessageDigest sha256;
        java.security.MessageDigest sha512;
        try {
            sha1 = java.security.MessageDigest.getInstance("SHA-1");
            sha256 = java.security.MessageDigest.getInstance("SHA-256");
            sha512 = java.security.MessageDigest.getInstance("SHA-512");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ApiException(500, "hash algorithm missing: " + e.getMessage());
        }
        long size = 0;
        byte[] buf = new byte[1 << 16];
        try (java.io.InputStream in = Files.newInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                sha1.update(buf, 0, n);
                sha256.update(buf, 0, n);
                sha512.update(buf, 0, n);
                size += n;
            }
        }
        java.util.HexFormat hex = java.util.HexFormat.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel(f));
        m.put("size", size);
        m.put("sha1", hex.formatHex(sha1.digest()));
        m.put("sha256", hex.formatHex(sha256.digest()));
        m.put("sha512", hex.formatHex(sha512.digest()));
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
