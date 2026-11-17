package dev.claudebridge;

import dev.claudebridge.ApiServer.ApiException;
import dev.claudebridge.ApiServer.Request;
import io.papermc.paper.plugin.configuration.PluginMeta;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Server status, console commands, logs and plugin information. */
final class ServerService {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final ClaudeBridgePlugin plugin;

    ServerService(ClaudeBridgePlugin plugin) {
        this.plugin = plugin;
    }

    Object status() throws Exception {
        return plugin.sync(() -> {
            Server s = Bukkit.getServer();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("version", s.getVersion());
            m.put("minecraftVersion", s.getMinecraftVersion());
            double[] tps = s.getTPS();
            m.put("tps", List.of(r2(tps[0]), r2(tps[1]), r2(tps[2])));
            m.put("mspt", r2(s.getAverageTickTime()));
            Runtime rt = Runtime.getRuntime();
            Map<String, Object> mem = new LinkedHashMap<>();
            mem.put("usedMB", (rt.totalMemory() - rt.freeMemory()) / 1048576);
            mem.put("allocatedMB", rt.totalMemory() / 1048576);
            mem.put("maxMB", rt.maxMemory() / 1048576);
            m.put("memory", mem);
            m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
            m.put("onlineMode", s.getOnlineMode());
            m.put("motd", PLAIN.serialize(s.motd()));
            m.put("maxPlayers", s.getMaxPlayers());
            List<Map<String, Object>> players = new ArrayList<>();
            for (Player p : s.getOnlinePlayers()) players.add(playerInfo(p));
            m.put("players", players);
            List<Map<String, Object>> worlds = new ArrayList<>();
            for (World w : s.getWorlds()) {
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("name", w.getName());
                wm.put("environment", w.getEnvironment().name());
                wm.put("players", w.getPlayers().size());
                wm.put("entities", w.getEntityCount());
                wm.put("loadedChunks", w.getChunkCount());
                wm.put("time", w.getTime());
                wm.put("storm", w.hasStorm());
                wm.put("thundering", w.isThundering());
                wm.put("difficulty", w.getDifficulty().name());
                Location sp = w.getSpawnLocation();
                wm.put("spawn", List.of(sp.getBlockX(), sp.getBlockY(), sp.getBlockZ()));
                worlds.add(wm);
            }
            m.put("worlds", worlds);
            m.put("plugins", s.getPluginManager().getPlugins().length);
            m.put("avatarSpawned", plugin.avatar().isSpawned());
            m.put("tls", plugin.tls() == null ? null : plugin.tls().info());
            return m;
        });
    }

    static Map<String, Object> playerInfo(Player p) {
        Map<String, Object> m = new LinkedHashMap<>();
        Location l = p.getLocation();
        m.put("name", p.getName());
        m.put("uuid", p.getUniqueId().toString());
        m.put("world", l.getWorld().getName());
        m.put("x", r2(l.getX()));
        m.put("y", r2(l.getY()));
        m.put("z", r2(l.getZ()));
        m.put("health", r2(p.getHealth()));
        m.put("food", p.getFoodLevel());
        m.put("level", p.getLevel());
        m.put("gameMode", p.getGameMode().name());
        m.put("ping", p.getPing());
        m.put("op", p.isOp());
        return m;
    }

    Object console(Request r) throws Exception {
        if (!plugin.feature("console")) throw new ApiException(403, "console feature is disabled in config.yml");
        String command = r.require("command").trim();
        if (command.startsWith("/")) command = command.substring(1);
        boolean asConsole = r.bool("console", false);
        int waitMs = Math.max(0, Math.min(r.integer("wait_ms", 500), 10000));
        Path log = plugin.serverRoot().resolve("logs").resolve("latest.log");
        long before = Files.isRegularFile(log) ? Files.size(log) : 0;
        List<String> feedback = Collections.synchronizedList(new ArrayList<>());
        final String cmd = command;
        boolean dispatched = plugin.sync(() -> {
            CommandSender sender = asConsole
                    ? Bukkit.getConsoleSender()
                    : Bukkit.createCommandSender(c -> feedback.add(PLAIN.serialize(c)));
            return Bukkit.dispatchCommand(sender, cmd);
        });
        if (waitMs > 0) Thread.sleep(waitMs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        m.put("dispatched", dispatched);
        m.put("feedback", new ArrayList<>(feedback));
        m.put("log", readAppended(log, before, 256 * 1024));
        if (!dispatched) m.put("note", "the command does not exist or reported failure");
        return m;
    }

    Object logs(Request r) throws IOException {
        String name = r.str("file", "latest.log");
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ApiException(400, "file must be a plain file name from GET /api/logs/list");
        }
        Path f = plugin.serverRoot().resolve("logs").resolve(name);
        if (!Files.isRegularFile(f)) throw new ApiException(404, "log file not found: " + name);
        int lines = Math.max(1, Math.min(r.integer("lines", 200), 5000));
        String grep = r.str("grep", null);
        List<String> all;
        if (name.endsWith(".gz")) {
            all = readGzLines(f, 32L * 1024 * 1024);
        } else {
            long window = grep == null ? Math.min(Files.size(f), lines * 400L + 65536) : Math.min(Files.size(f), 16L * 1024 * 1024);
            all = tailLines(f, window);
        }
        if (grep != null && !grep.isBlank()) {
            String needle = grep.toLowerCase(Locale.ROOT);
            all = all.stream().filter(l -> l.toLowerCase(Locale.ROOT).contains(needle)).toList();
        }
        List<String> out = all.size() > lines ? all.subList(all.size() - lines, all.size()) : all;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", name);
        m.put("lines", out);
        return m;
    }

    Object logList() throws IOException {
        Path dir = plugin.serverRoot().resolve("logs");
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .sorted((a, b) -> Long.compare(modified(b), modified(a)))
                        .limit(200)
                        .forEach(p -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("file", p.getFileName().toString());
                            m.put("size", size(p));
                            m.put("modified", Instant.ofEpochMilli(modified(p)).toString());
                            files.add(m);
                        });
            }
        }
        return Map.of("files", files);
    }

    Object plugins() throws Exception {
        return plugin.sync(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            Plugin[] all = Bukkit.getPluginManager().getPlugins();
            Arrays.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (Plugin p : all) out.add(pluginInfo(p));
            return Map.of("plugins", out);
        });
    }

    Object pluginInfo(Request r) throws Exception {
        String name = r.require("name");
        return plugin.sync(() -> {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p == null) throw new ApiException(404, "plugin not found: " + name);
            Map<String, Object> m = pluginInfo(p);
            List<String> files = new ArrayList<>();
            Path data = p.getDataFolder().toPath();
            if (Files.isDirectory(data)) {
                try (Stream<Path> s = Files.walk(data, 2)) {
                    s.filter(x -> !x.equals(data)).limit(300).forEach(x -> files.add(relative(x) + (Files.isDirectory(x) ? "/" : "")));
                }
            }
            m.put("dataFiles", files);
            return m;
        });
    }

    private Map<String, Object> pluginInfo(Plugin p) {
        PluginMeta meta = p.getPluginMeta();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", meta.getName());
        m.put("version", meta.getVersion());
        m.put("enabled", p.isEnabled());
        m.put("description", meta.getDescription());
        m.put("authors", meta.getAuthors());
        m.put("website", meta.getWebsite());
        m.put("main", meta.getMainClass());
        m.put("apiVersion", meta.getAPIVersion());
        m.put("depend", meta.getPluginDependencies());
        m.put("softDepend", meta.getPluginSoftDependencies());
        m.put("dataFolder", relative(p.getDataFolder().toPath()));
        try {
            Path jar = Path.of(p.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            m.put("jar", relative(jar));
        } catch (Exception ignored) {
            m.put("jar", null);
        }
        return m;
    }

    private String relative(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path root = plugin.serverRoot();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    /** Lines appended to a file after the given offset (handles log rotation). */
    static List<String> readAppended(Path file, long from, int maxBytes) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        long size = Files.size(file);
        if (size < from) from = 0;
        long len = Math.min(size - from, maxBytes);
        if (len <= 0) return List.of();
        byte[] buf = readRange(file, from, (int) len);
        String text = new String(buf, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) if (!line.isEmpty()) out.add(line);
        return out;
    }

    static List<String> tailLines(Path file, long window) throws IOException {
        long size = Files.size(file);
        long from = Math.max(0, size - window);
        byte[] buf = readRange(file, from, (int) (size - from));
        String text = new String(buf, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\r?\\n")));
        if (from > 0 && !lines.isEmpty()) lines.remove(0);
        return lines;
    }

    static byte[] readRange(Path file, long from, int len) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ch.position(from);
            ByteBuffer bb = ByteBuffer.allocate(len);
            while (bb.hasRemaining() && ch.read(bb) > 0) {
                // keep reading
            }
            return Arrays.copyOf(bb.array(), bb.position());
        }
    }

    private static List<String> readGzLines(Path f, long maxBytes) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0 && total < maxBytes) {
                out.write(buf, 0, n);
                total += n;
            }
            return Arrays.asList(out.toString(StandardCharsets.UTF_8).split("\\r?\\n"));
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
