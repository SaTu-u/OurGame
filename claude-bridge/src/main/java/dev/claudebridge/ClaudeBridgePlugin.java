package dev.claudebridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class ClaudeBridgePlugin extends JavaPlugin {

    private ApiServer api;
    private volatile boolean apiEnabled = true;
    private volatile String token;
    private Audit audit;
    private ChatLog chat;
    private Avatar avatar;
    private Path serverRoot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            serverRoot = Paths.get("").toAbsolutePath().toRealPath();
        } catch (IOException e) {
            serverRoot = Paths.get("").toAbsolutePath().normalize();
        }
        audit = new Audit(getDataFolder().toPath().resolve("audit.log"), getConfig().getBoolean("audit-log", true));
        chat = new ChatLog(getConfig().getInt("chat.buffer-size", 1000));
        getServer().getPluginManager().registerEvents(chat, this);

        avatar = new Avatar(this);
        getServer().getPluginManager().registerEvents(avatar, this);
        avatar.loadState();
        getServer().getScheduler().runTaskTimer(this, avatar::tick, 1L, 1L);
        getServer().getScheduler().runTaskLater(this, avatar::respawnFromStateIfNeeded, 40L);

        loadToken();
        startApi();
    }

    @Override
    public void onDisable() {
        if (avatar != null) avatar.shutdown();
        if (api != null) api.stop();
        if (audit != null) audit.close();
    }

    private void startApi() {
        String bind = getConfig().getString("http.bind", "127.0.0.1");
        int port = getConfig().getInt("http.port", 8765);
        api = new ApiServer(this);
        Routes.register(this, api);
        try {
            api.start(bind, port);
            getLogger().info("ClaudeBridge API listening on http://" + bind + ":" + port + " (token: plugins/ClaudeBridge/token.txt)");
            if (!"127.0.0.1".equals(bind) && !"localhost".equalsIgnoreCase(bind)) {
                getLogger().warning("API is bound to " + bind + ". Expose it to the internet only through an HTTPS reverse proxy.");
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not start ClaudeBridge API on " + bind + ":" + port, e);
        }
    }

    void restartApi() {
        if (api != null) api.stop();
        startApi();
    }

    private void loadToken() {
        String fromConfig = getConfig().getString("auth.token", "");
        if (fromConfig != null && !fromConfig.isBlank()) {
            token = fromConfig.trim();
            return;
        }
        Path file = tokenFile();
        try {
            if (Files.exists(file)) {
                String t = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (t.length() >= 32) {
                    token = t;
                    return;
                }
            }
            regenerateToken();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not read or create token.txt; API will reject all requests", e);
            token = null;
        }
    }

    /** Creates a new random token, stores it in token.txt and returns the file path. */
    Path regenerateToken() throws IOException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path file = tokenFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, t + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or a filesystem without POSIX permissions.
        }
        token = t;
        return file;
    }

    private Path tokenFile() {
        return getDataFolder().toPath().resolve("token.txt");
    }

    /** Runs code on the server main thread and waits for the result. */
    <T> T sync(Callable<T> callable) throws Exception {
        if (Bukkit.isPrimaryThread()) return callable.call();
        try {
            return Bukkit.getScheduler().callSyncMethod(this, callable).get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw e;
        } catch (TimeoutException e) {
            throw new ApiServer.ApiException(504, "server main thread did not respond within 20 seconds");
        }
    }

    String token() { return token; }
    boolean isApiEnabled() { return apiEnabled; }
    Audit audit() { return audit; }
    ChatLog chat() { return chat; }
    Avatar avatar() { return avatar; }
    Path serverRoot() { return serverRoot; }

    boolean feature(String name) {
        return getConfig().getBoolean("features." + name, true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                sender.sendMessage(Component.text("ClaudeBridge API: " + (apiEnabled ? "включен" : "выключен")
                        + ", порт " + getConfig().getInt("http.port", 8765)
                        + ", аватар: " + (avatar.isSpawned() ? "в мире" : "не в мире"), NamedTextColor.AQUA));
            }
            case "on" -> {
                apiEnabled = true;
                audit.log("console", "CMD", "/claudebridge on", sender.getName());
                sender.sendMessage(Component.text("API включен.", NamedTextColor.GREEN));
            }
            case "off" -> {
                apiEnabled = false;
                audit.log("console", "CMD", "/claudebridge off", sender.getName());
                sender.sendMessage(Component.text("API выключен: все запросы отклоняются.", NamedTextColor.YELLOW));
            }
            case "token" -> {
                try {
                    Path file = regenerateToken();
                    audit.log("console", "CMD", "/claudebridge token", sender.getName());
                    sender.sendMessage(Component.text("Новый токен записан в " + serverRoot.relativize(file.toAbsolutePath())
                            + ". Старый токен больше не действует.", NamedTextColor.GREEN));
                } catch (IOException e) {
                    sender.sendMessage(Component.text("Не удалось создать токен: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "reload" -> {
                reloadConfig();
                loadToken();
                restartApi();
                sender.sendMessage(Component.text("Конфиг перечитан, API перезапущен.", NamedTextColor.GREEN));
            }
            case "despawn" -> {
                avatar.despawn();
                sender.sendMessage(Component.text("Аватар убран из мира.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Использование: /" + label + " <status|on|off|token|reload|despawn>", NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("status", "on", "off", "token", "reload", "despawn")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        }
        return out;
    }
}
