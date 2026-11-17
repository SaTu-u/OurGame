package dev.claudebridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ring buffer of chat and game events, so the API client can "hear" the server. Commands are not recorded. */
final class ChatLog implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private record Entry(long id, long time, String type, String who, String text) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final int capacity;
    private long nextId = 1;

    ChatLog(int capacity) {
        this.capacity = Math.max(50, capacity);
    }

    synchronized void add(String type, String who, String text) {
        entries.addLast(new Entry(nextId++, System.currentTimeMillis(), type, who, text));
        while (entries.size() > capacity) entries.removeFirst();
    }

    synchronized Map<String, Object> since(long afterId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.id() <= afterId) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("time", Instant.ofEpochMilli(e.time()).toString());
            m.put("type", e.type());
            m.put("who", e.who());
            m.put("text", e.text());
            out.add(m);
        }
        if (out.size() > limit) out = new ArrayList<>(out.subList(out.size() - limit, out.size()));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("latestId", nextId - 1);
        res.put("entries", out);
        return res;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        add("chat", event.getPlayer().getName(), PLAIN.serialize(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        add("join", event.getPlayer().getName(), event.getPlayer().getName() + " joined the game");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        add("quit", event.getPlayer().getName(), event.getPlayer().getName() + " left the game");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Component msg = event.deathMessage();
        add("death", event.getPlayer().getName(), msg != null ? PLAIN.serialize(msg) : event.getPlayer().getName() + " died");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component msg = event.message();
        if (msg != null) add("advancement", event.getPlayer().getName(), PLAIN.serialize(msg));
    }
}
