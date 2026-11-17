package dev.claudebridge;

import dev.claudebridge.ApiServer.ApiException;
import dev.claudebridge.ApiServer.Request;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The in-world AI player: a Mannequin entity (player model) controlled through the API.
 * All methods except {@link #await} must run on the server main thread.
 */
final class Avatar implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final int INV_SIZE = 36;
    private static final int CHUNK_RADIUS = 1;

    private final ClaudeBridgePlugin plugin;
    private final NamespacedKey key;
    private final Inventory inv;
    private final ItemStack[] armor = new ItemStack[4]; // boots, leggings, chestplate, helmet
    private final File stateFile;
    private final List<long[]> tickets = new ArrayList<>();

    private Mannequin entity;
    private int heldSlot;
    private Task task;
    private Location savedLocation;
    private String savedWorld;
    private boolean savedSpawned;
    private World ticketWorld;
    private int ticketCX = Integer.MIN_VALUE;
    private int ticketCZ = Integer.MIN_VALUE;
    private long tickCounter;

    Avatar(ClaudeBridgePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "avatar");
        this.inv = Bukkit.createInventory(null, INV_SIZE, Component.text("ClaudeBridge avatar"));
        this.stateFile = new File(plugin.getDataFolder(), "avatar.yml");
    }

    // ------------------------------------------------------------------ config

    String name() {
        return plugin.getConfig().getString("avatar.name", "Claude");
    }

    private double reach() {
        return plugin.getConfig().getDouble("avatar.reach", 4.5);
    }

    private double walkSpeed() {
        return Math.max(0.5, Math.min(plugin.getConfig().getDouble("avatar.walk-speed", 4.3), 20.0));
    }

    private boolean announce() {
        return plugin.getConfig().getBoolean("avatar.announce", true);
    }

    boolean isSpawned() {
        return entity != null && entity.isValid();
    }

    private Mannequin requireEntity() {
        if (!plugin.feature("avatar")) throw new ApiException(403, "avatar feature is disabled in config.yml");
        if (!isSpawned()) throw new ApiException(409, "avatar is not in the world; call POST /api/avatar/spawn first");
        return entity;
    }

    private boolean isAvatar(Entity e) {
        return entity != null && e.getUniqueId().equals(entity.getUniqueId());
    }

    // ------------------------------------------------------------------ lifecycle

    Map<String, Object> spawn(Request r) {
        if (!plugin.feature("avatar")) throw new ApiException(403, "avatar feature is disabled in config.yml");
        Location loc;
        if (r.has("player")) {
            Player p = player(r.str("player"));
            loc = p.getLocation().clone();
            Vector dir = loc.getDirection().setY(0);
            if (dir.lengthSquared() > 1e-6) loc.add(dir.normalize().multiply(2));
            int[] spot = PathFinder.nearestWalkable(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            loc = spot == null ? p.getLocation().clone() : new Location(loc.getWorld(), spot[0] + 0.5, spot[1], spot[2] + 0.5);
            Vector toPlayer = p.getLocation().toVector().subtract(loc.toVector());
            if (toPlayer.lengthSquared() > 1e-6) loc.setDirection(toPlayer);
        } else if (r.has("x") && r.has("z")) {
            World w = world(r);
            double x = r.dbl("x", 0);
            double z = r.dbl("z", 0);
            double y = r.has("y") ? r.dbl("y", 0) : w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            loc = new Location(w, x, y, z);
        } else if (savedLocation() != null) {
            loc = savedLocation();
        } else {
            loc = Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
        }
        if (isSpawned()) {
            cancelTask("avatar was moved by spawn");
            entity.teleport(loc);
            return map("status", "teleported", "state", state());
        }
        spawnAt(loc);
        return map("status", "spawned", "state", state());
    }

    private void spawnAt(Location loc) {
        String name = name();
        String description = plugin.getConfig().getString("avatar.description", "AI");
        String skin = plugin.getConfig().getString("avatar.skin", "");
        boolean invulnerable = plugin.getConfig().getBoolean("avatar.invulnerable", false);
        entity = loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.customName(Component.text(name));
            m.setCustomNameVisible(true);
            m.setDescription(description == null || description.isBlank() ? null : Component.text(description, NamedTextColor.GRAY));
            if (skin != null && !skin.isBlank()) {
                m.setProfile(ResolvableProfile.resolvableProfile().name(skin.trim()).build());
            }
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.setInvulnerable(invulnerable);
            m.setCanPickupItems(false);
            m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        syncEquipment();
        updateTickets();
        savedSpawned = true;
        saveState();
        plugin.chat().add("avatar", name, name + " joined the game");
        if (announce()) Bukkit.broadcast(Component.text(name + " joined the game", NamedTextColor.YELLOW));
    }

    Map<String, Object> despawn() {
        if (!isSpawned()) {
            entity = null;
            return map("status", "not spawned");
        }
        cancelTask("avatar despawned");
        savedLocation = entity.getLocation();
        savedSpawned = false;
        saveState();
        removeTickets();
        entity.remove();
        entity = null;
        plugin.chat().add("avatar", name(), name() + " left the game");
        if (announce()) Bukkit.broadcast(Component.text(name() + " left the game", NamedTextColor.YELLOW));
        return map("status", "despawned");
    }

    /** Called from onDisable: remembers the avatar so it can come back after a restart. */
    void shutdown() {
        if (task != null) task.cancel("server is stopping");
        task = null;
        if (isSpawned()) {
            savedLocation = entity.getLocation();
            savedSpawned = true;
            saveState();
            removeTickets();
            entity.remove();
        } else {
            saveState();
        }
        entity = null;
    }

    void respawnFromStateIfNeeded() {
        if (isSpawned() || !savedSpawned || !plugin.getConfig().getBoolean("avatar.respawn-on-start", true)) return;
        Location l = savedLocation();
        if (l != null && plugin.feature("avatar")) spawnAt(l);
    }

    private Location savedLocation() {
        if (savedLocation != null && savedLocation.getWorld() == null && savedWorld != null) {
            World w = Bukkit.getWorld(savedWorld);
            if (w != null) savedLocation.setWorld(w);
        }
        return savedLocation != null && savedLocation.getWorld() != null ? savedLocation : null;
    }

    void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        savedSpawned = y.getBoolean("spawned", false);
        heldSlot = Math.max(0, Math.min(y.getInt("held", 0), INV_SIZE - 1));
        if (y.isConfigurationSection("location")) {
            savedWorld = y.getString("location.world");
            savedLocation = new Location(savedWorld == null ? null : Bukkit.getWorld(savedWorld),
                    y.getDouble("location.x"), y.getDouble("location.y"), y.getDouble("location.z"),
                    (float) y.getDouble("location.yaw"), (float) y.getDouble("location.pitch"));
        }
        List<String> items = y.getStringList("inventory");
        for (int i = 0; i < Math.min(items.size(), INV_SIZE); i++) inv.setItem(i, decode(items.get(i)));
        List<String> arm = y.getStringList("armor");
        for (int i = 0; i < Math.min(arm.size(), 4); i++) armor[i] = decode(arm.get(i));
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("spawned", savedSpawned);
        Location l = isSpawned() ? entity.getLocation() : savedLocation;
        if (l != null && (l.getWorld() != null || savedWorld != null)) {
            y.set("location.world", l.getWorld() != null ? l.getWorld().getName() : savedWorld);
            y.set("location.x", l.getX());
            y.set("location.y", l.getY());
            y.set("location.z", l.getZ());
            y.set("location.yaw", l.getYaw());
            y.set("location.pitch", l.getPitch());
        }
        y.set("held", heldSlot);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < INV_SIZE; i++) items.add(encode(inv.getItem(i)));
        y.set("inventory", items);
        List<String> arm = new ArrayList<>();
        for (ItemStack a : armor) arm.add(encode(a));
        y.set("armor", arm);
        try {
            y.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save avatar.yml", e);
        }
    }

    private static String encode(ItemStack it) {
        return it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    private static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        if (entity == null) return;
        if (!entity.isValid()) {
            if (!entity.isDead()) plugin.chat().add("avatar", name(), "the avatar entity was removed from the world");
            Task t = task;
            task = null;
            if (t != null) t.cancel("avatar entity is gone");
            removeTickets();
            savedLocation = entity.getLocation();
            entity = null;
            return;
        }
        tickCounter++;
        if (tickCounter % 10 == 0) updateTickets();
        if (tickCounter % 4 == 0) autoPickup();
        if (tickCounter % 1200 == 0) saveState();
        Task t = task;
        if (t != null) {
            try {
                if (t.tick() && task == t) task = null;
            } catch (Exception ex) {
                t.finish(fail(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                if (task == t) task = null;
            }
        }
    }

    private void autoPickup() {
        Location l = entity.getLocation();
        for (Item it : l.getWorld().getNearbyEntitiesByType(Item.class, l, 1.5)) {
            if (!it.isValid() || it.getPickupDelay() > 0) continue;
            ItemStack st = it.getItemStack();
            int taken = addToInventory(st, it);
            if (taken > 0) {
                entity.playPickupItemAnimation(it, taken);
                plugin.chat().add("avatar-pickup", name(), "picked up " + taken + " x " + st.getType().name());
            }
        }
    }

    /** Moves as much of the item entity's stack as fits into the inventory; returns the amount taken. */
    private int addToInventory(ItemStack st, Item source) {
        int before = st.getAmount();
        Map<Integer, ItemStack> left = inv.addItem(st.clone());
        int remaining = left.values().stream().mapToInt(ItemStack::getAmount).sum();
        int taken = before - remaining;
        if (taken > 0) {
            if (remaining <= 0) {
                source.remove();
            } else {
                ItemStack rest = st.clone();
                rest.setAmount(remaining);
                source.setItemStack(rest);
            }
            syncEquipment();
        }
        return taken;
    }

    private void syncEquipment() {
        if (!isSpawned()) return;
        EntityEquipment eq = entity.getEquipment();
        ItemStack held = inv.getItem(heldSlot);
        eq.setItemInMainHand(held == null ? null : held.clone());
        eq.setBoots(armor[0] == null ? null : armor[0].clone());
        eq.setLeggings(armor[1] == null ? null : armor[1].clone());
        eq.setChestplate(armor[2] == null ? null : armor[2].clone());
        eq.setHelmet(armor[3] == null ? null : armor[3].clone());
    }

    /** Keeps the chunks around the avatar loaded (async) so it keeps living and can find paths. */
    private void updateTickets() {
        Location l = entity.getLocation();
        World w = l.getWorld();
        int cx = l.getBlockX() >> 4;
        int cz = l.getBlockZ() >> 4;
        if (w.equals(ticketWorld) && cx == ticketCX && cz == ticketCZ) return;
        removeTickets();
        ticketWorld = w;
        ticketCX = cx;
        ticketCZ = cz;
        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                tickets.add(new long[]{x, z});
                if (w.isChunkLoaded(x, z)) {
                    w.addPluginChunkTicket(x, z, plugin);
                } else {
                    w.getChunkAtAsync(x, z).thenAccept(c -> {
                        if (w.equals(ticketWorld) && containsTicket(x, z)) c.addPluginChunkTicket(plugin);
                    });
                }
            }
        }
    }

    private boolean containsTicket(int x, int z) {
        for (long[] t : tickets) if (t[0] == x && t[1] == z) return true;
        return false;
    }

    private void removeTickets() {
        if (ticketWorld != null) {
            for (long[] t : tickets) ticketWorld.removePluginChunkTicket((int) t[0], (int) t[1], plugin);
        }
        tickets.clear();
        ticketWorld = null;
        ticketCX = Integer.MIN_VALUE;
        ticketCZ = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ perception

    Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spawned", isSpawned());
        m.put("name", name());
        if (!isSpawned()) {
            Location l = savedLocation();
            if (l != null) m.put("lastLocation", pos(l));
            return m;
        }
        Mannequin e = entity;
        Location l = e.getLocation();
        World w = l.getWorld();
        m.put("world", w.getName());
        m.put("position", pos(l));
        m.put("block", List.of(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
        m.put("yaw", r1(l.getYaw()));
        m.put("pitch", r1(l.getPitch()));
        m.put("facing", facing(l.getYaw()));
        m.put("health", r1(e.getHealth()));
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        m.put("maxHealth", max == null ? null : r1(max.getValue()));
        m.put("onGround", e.isOnGround());
        m.put("inWater", e.isInWater());
        m.put("standingOn", l.clone().subtract(0, 0.2, 0).getBlock().getType().name());
        m.put("light", l.getBlock().getLightLevel());
        m.put("biome", l.getBlock().getBiome().getKey().toString());
        m.put("time", w.getTime());
        m.put("day", w.isDayTime());
        m.put("weather", w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear");
        m.put("held", map("slot", heldSlot, "item", itemInfo(inv.getItem(heldSlot))));
        List<Object> arm = new ArrayList<>();
        for (ItemStack a : armor) arm.add(itemInfo(a));
        m.put("armor", map("boots", arm.get(0), "leggings", arm.get(1), "chestplate", arm.get(2), "helmet", arm.get(3)));
        RayTraceResult rt = e.rayTraceBlocks(8.0);
        if (rt != null && rt.getHitBlock() != null) {
            Block hb = rt.getHitBlock();
            m.put("lookingAt", map("type", hb.getType().name(), "block", List.of(hb.getX(), hb.getY(), hb.getZ()),
                    "face", rt.getHitBlockFace() == null ? null : rt.getHitBlockFace().name(),
                    "distance", r1(e.getEyeLocation().distance(rt.getHitPosition().toLocation(w)))));
        } else {
            m.put("lookingAt", null);
        }
        m.put("task", task == null ? null : map("name", task.name, "status", task.status));
        List<Map<String, Object>> players = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        List<Entity> near = new ArrayList<>(e.getNearbyEntities(24, 12, 24));
        near.sort(Comparator.comparingDouble(x -> x.getLocation().distanceSquared(l)));
        for (Entity n : near) {
            if (n instanceof Player) players.add(entityInfo(n, l));
            else if (others.size() < 25) others.add(entityInfo(n, l));
        }
        m.put("nearbyPlayers", players);
        m.put("nearbyEntities", others);
        return m;
    }

    private static Map<String, Object> entityInfo(Entity n, Location from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", n.getType().name());
        m.put("uuid", n.getUniqueId().toString());
        String nm = n instanceof Player p ? p.getName() : n.customName() != null ? PLAIN.serialize(n.customName()) : null;
        m.put("name", nm);
        Location l = n.getLocation();
        m.put("position", List.of(r1(l.getX()), r1(l.getY()), r1(l.getZ())));
        m.put("distance", r1(l.distance(from)));
        m.put("hostile", n instanceof Enemy);
        if (n instanceof LivingEntity le) m.put("health", r1(le.getHealth()));
        if (n instanceof Item it) m.put("item", it.getItemStack().getAmount() + " x " + it.getItemStack().getType().name());
        return m;
    }

    Map<String, Object> map(Request r) {
        Mannequin e = requireEntity();
        int radius = Math.max(3, Math.min(r.integer("radius", 10), 24));
        Location l = e.getLocation();
        World w = l.getWorld();
        int cx = l.getBlockX();
        int cy = l.getBlockY();
        int cz = l.getBlockZ();
        Map<Long, Character> marks = new HashMap<>();
        for (Entity n : e.getNearbyEntities(radius, 8, radius)) {
            char c = n instanceof Player ? 'P' : n instanceof Enemy ? 'm' : n instanceof Item ? 'i' : n instanceof LivingEntity ? 'a' : 0;
            if (c == 0) continue;
            long k = colKey(n.getLocation().getBlockX(), n.getLocation().getBlockZ());
            Character prev = marks.get(k);
            if (prev == null || rank(c) > rank(prev)) marks.put(k, c);
        }
        List<String> rows = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                if (dx == 0 && dz == 0) {
                    sb.append('@');
                    continue;
                }
                Character mark = marks.get(colKey(x, z));
                sb.append(mark != null ? mark : terrain(w, x, cy, z));
            }
            rows.add(sb.toString());
        }
        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("@", "you (avatar)");
        legend.put("P", "player");
        legend.put("m", "hostile mob");
        legend.put("a", "other living entity");
        legend.put("i", "dropped item");
        legend.put(".", "walkable, same level");
        legend.put("+", "walkable, 1 block up");
        legend.put("-", "walkable, 1 block down");
        legend.put("^", "walkable, 2+ blocks up");
        legend.put("v", "walkable, 2-6 blocks down");
        legend.put("#", "wall / solid");
        legend.put("~", "water");
        legend.put("L", "lava");
        legend.put("T", "tree log");
        legend.put("C", "crafting table");
        legend.put("H", "chest / barrel");
        legend.put("F", "furnace");
        legend.put("D", "door / gate");
        legend.put("?", "chunk not loaded");
        legend.put(" ", "deep drop or nothing to stand on");
        return map("originTopLeft", map("x", cx - radius, "z", cz - radius), "centerY", cy,
                "orientation", "rows go north (-Z) to south (+Z), columns west (-X) to east (+X)",
                "facing", facing(l.getYaw()), "rows", rows, "legend", legend);
    }

    private static int rank(char c) {
        return switch (c) {
            case 'P' -> 4;
            case 'm' -> 3;
            case 'a' -> 2;
            default -> 1;
        };
    }

    private static long colKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static char terrain(World w, int x, int cy, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return '?';
        for (int dy = 0; dy <= 1; dy++) {
            Material m = w.getBlockAt(x, cy + dy, z).getType();
            if (Tag.LOGS.isTagged(m)) return 'T';
            if (m == Material.CRAFTING_TABLE) return 'C';
            if (m == Material.CHEST || m == Material.BARREL || m == Material.TRAPPED_CHEST) return 'H';
            if (m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER) return 'F';
            if (Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return 'D';
        }
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, -4, -5, -6}) {
            int y = cy + dy;
            if (PathFinder.walkable(w, x, y, z)) {
                if (PathFinder.isWater(PathFinder.block(w, x, y, z))) return '~';
                if (dy == 0) return '.';
                if (dy == 1) return '+';
                if (dy == -1) return '-';
                return dy > 0 ? '^' : 'v';
            }
        }
        Block feet = PathFinder.block(w, x, cy, z);
        Block below = PathFinder.block(w, x, cy - 1, z);
        if ((feet != null && feet.getType() == Material.LAVA) || (below != null && below.getType() == Material.LAVA)) return 'L';
        if (feet != null && (feet.getType() == Material.WATER || (below != null && below.getType() == Material.WATER))) return '~';
        if (feet != null && !feet.isPassable()) return '#';
        return ' ';
    }

    Map<String, Object> blocks(Request r) {
        Mannequin e = requireEntity();
        int radius = Math.max(1, Math.min(r.integer("radius", 3), 5));
        String filter = r.str("filter", null);
        String f = filter == null ? null : filter.toUpperCase(Locale.ROOT);
        Location l = e.getLocation();
        World w = l.getWorld();
        List<Object> out = new ArrayList<>();
        for (int dy = radius; dy >= -radius; dy--) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    Block b = PathFinder.block(w, l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
                    if (b == null || b.getType().isAir()) continue;
                    String t = b.getType().name();
                    if (f != null && !t.contains(f)) continue;
                    out.add(List.of(dx, dy, dz, t));
                }
            }
        }
        return map("origin", List.of(l.getBlockX(), l.getBlockY(), l.getBlockZ()),
                "format", "[dx, dy, dz, type] relative to origin (the block at the avatar's feet); air is omitted",
                "blocks", out);
    }

    Map<String, Object> find(Request r) {
        Mannequin e = requireEntity();
        String q = r.require("block").trim().toUpperCase(Locale.ROOT);
        boolean contains = r.bool("contains", Material.matchMaterial(q) == null);
        Material exact = contains ? null : Material.matchMaterial(q);
        int radius = Math.max(1, Math.min(r.integer("radius", 16), 32));
        int limit = Math.max(1, Math.min(r.integer("limit", 20), 100));
        Location l = e.getLocation();
        World w = l.getWorld();
        List<Object[]> found = new ArrayList<>();
        int minY = Math.max(w.getMinHeight(), l.getBlockY() - radius);
        int maxY = Math.min(w.getMaxHeight() - 1, l.getBlockY() + radius);
        for (int x = l.getBlockX() - radius; x <= l.getBlockX() + radius; x++) {
            for (int z = l.getBlockZ() - radius; z <= l.getBlockZ() + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Material m = w.getBlockAt(x, y, z).getType();
                    if (exact != null ? m == exact : m.name().contains(q)) {
                        double d = l.distance(new Location(w, x + 0.5, y + 0.5, z + 0.5));
                        found.add(new Object[]{d, x, y, z, m.name()});
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(o -> (double) o[0]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] o : found.subList(0, Math.min(limit, found.size()))) {
            out.add(map("type", o[4], "block", List.of(o[1], o[2], o[3]), "distance", r1((double) o[0])));
        }
        return map("query", q, "radius", radius, "totalFound", found.size(), "nearest", out);
    }

    Map<String, Object> inventory() {
        requireEntityOrState();
        List<Map<String, Object>> slots = new ArrayList<>();
        int free = 0;
        for (int i = 0; i < INV_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                free++;
                continue;
            }
            Map<String, Object> s = itemInfo(it);
            s.put("slot", i);
            slots.add(s);
        }
        List<Object> arm = new ArrayList<>();
        for (ItemStack a : armor) arm.add(itemInfo(a));
        return map("heldSlot", heldSlot, "slots", slots, "freeSlots", free,
                "armor", map("boots", arm.get(0), "leggings", arm.get(1), "chestplate", arm.get(2), "helmet", arm.get(3)));
    }

    private void requireEntityOrState() {
        if (!plugin.feature("avatar")) throw new ApiException(403, "avatar feature is disabled in config.yml");
    }

    private static Map<String, Object> itemInfo(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", it.getType().name());
        m.put("amount", it.getAmount());
        short max = it.getType().getMaxDurability();
        ItemMeta meta = it.getItemMeta();
        if (max > 0 && meta instanceof Damageable d) m.put("durability", (max - d.getDamage()) + "/" + max);
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) m.put("name", PLAIN.serialize(meta.displayName()));
        if (!it.getEnchantments().isEmpty()) {
            List<String> ench = new ArrayList<>();
            it.getEnchantments().forEach((en, lvl) -> ench.add(en.getKey().getKey() + " " + lvl));
            m.put("enchantments", ench);
        }
        return m;
    }

    // ------------------------------------------------------------------ simple actions

    Map<String, Object> look(Request r) {
        Mannequin e = requireEntity();
        if (r.has("player")) {
            face(player(r.str("player")).getEyeLocation());
        } else if (r.has("x") && r.has("z")) {
            double y = r.has("y") ? r.dbl("y", 0) : e.getEyeLocation().getY();
            face(new Location(e.getWorld(), r.dbl("x", 0), y, r.dbl("z", 0)));
        } else {
            Location l = e.getLocation();
            float yaw = (float) r.dbl("yaw", l.getYaw());
            float pitch = (float) Math.max(-90, Math.min(90, r.dbl("pitch", l.getPitch())));
            e.setRotation(yaw, pitch);
            e.setBodyYaw(yaw);
        }
        Location l = e.getLocation();
        return map("yaw", r1(l.getYaw()), "pitch", r1(l.getPitch()), "facing", facing(l.getYaw()));
    }

    private void face(Location target) {
        Location eye = entity.getEyeLocation();
        double dx = target.getX() - eye.getX();
        double dy = target.getY() - eye.getY();
        double dz = target.getZ() - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        entity.setRotation(yaw, pitch);
        entity.setBodyYaw(yaw);
    }

    Map<String, Object> jump() {
        Mannequin e = requireEntity();
        if (!e.isOnGround() && !e.isInWater()) return map("status", "failed", "reason", "not on the ground");
        e.setVelocity(e.getVelocity().setY(0.42));
        return map("status", "ok");
    }

    Map<String, Object> teleport(Request r) {
        Mannequin e = requireEntity();
        Location to;
        if (r.has("player")) {
            to = player(r.str("player")).getLocation();
        } else {
            World w = world(r);
            to = new Location(w, r.requireDbl("x"), r.requireDbl("y"), r.requireDbl("z"), e.getLocation().getYaw(), 0);
        }
        cancelTask("teleported");
        e.teleport(to);
        return map("status", "ok", "position", pos(e.getLocation()));
    }

    Map<String, Object> chat(Request r) {
        String msg = r.require("message");
        if (msg.length() > 256) msg = msg.substring(0, 256);
        String to = r.str("to", null);
        if (to != null && !to.isBlank()) {
            Player p = player(to);
            p.sendMessage(Component.text(name() + " whispers to you: " + msg, NamedTextColor.GRAY, TextDecoration.ITALIC));
            plugin.chat().add("whisper", name(), "-> " + p.getName() + ": " + msg);
        } else {
            Bukkit.broadcast(Component.text("<" + name() + "> " + msg));
            plugin.chat().add("chat", name(), msg);
        }
        return map("status", "sent");
    }

    Map<String, Object> hold(Request r) {
        requireEntityOrState();
        int slot;
        if (r.has("item")) {
            Material m = material(r.str("item"));
            slot = -1;
            for (int i = 0; i < INV_SIZE; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() == m) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) throw new ApiException(404, "no " + m.name() + " in the inventory");
        } else {
            slot = slotArg(r, "slot", -1);
        }
        heldSlot = slot;
        syncEquipment();
        return map("heldSlot", heldSlot, "item", itemInfo(inv.getItem(heldSlot)));
    }

    Map<String, Object> drop(Request r) {
        Mannequin e = requireEntity();
        int slot = slotArg(r, "slot", heldSlot);
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType().isAir()) throw new ApiException(422, "slot " + slot + " is empty");
        int amount = Math.max(1, Math.min(r.integer("amount", it.getAmount()), it.getAmount()));
        ItemStack part = it.clone();
        part.setAmount(amount);
        it.setAmount(it.getAmount() - amount);
        inv.setItem(slot, it.getAmount() <= 0 ? null : it);
        Location eye = e.getEyeLocation();
        Item dropped = e.getWorld().dropItem(eye.clone().add(eye.getDirection().multiply(0.5)), part);
        dropped.setVelocity(eye.getDirection().multiply(0.3));
        dropped.setPickupDelay(40);
        syncEquipment();
        return map("status", "ok", "dropped", amount + " x " + part.getType().name());
    }

    Map<String, Object> give(Request r) {
        requireEntityOrState();
        Material m = material(r.require("item"));
        if (!m.isItem() || m.isAir()) throw new ApiException(400, m.name() + " is not an item");
        int amount = Math.max(1, Math.min(r.integer("amount", 1), INV_SIZE * 64));
        int left = 0;
        int remaining = amount;
        while (remaining > 0) {
            int n = Math.min(remaining, m.getMaxStackSize());
            Map<Integer, ItemStack> rest = inv.addItem(new ItemStack(m, n));
            left += rest.values().stream().mapToInt(ItemStack::getAmount).sum();
            remaining -= n;
        }
        syncEquipment();
        return map("status", "ok", "added", amount - left, "didNotFit", left);
    }

    Map<String, Object> equip(Request r) {
        requireEntityOrState();
        int slot = slotArg(r, "slot", heldSlot);
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType().isAir()) throw new ApiException(422, "slot " + slot + " is empty");
        String n = it.getType().name();
        int idx = n.endsWith("_BOOTS") ? 0 : n.endsWith("_LEGGINGS") ? 1
                : (n.endsWith("_CHESTPLATE") || n.equals("ELYTRA")) ? 2
                : (n.endsWith("_HELMET") || n.equals("CARVED_PUMPKIN") || n.endsWith("_HEAD") || n.endsWith("_SKULL")) ? 3 : -1;
        if (idx < 0) throw new ApiException(422, n + " cannot be worn");
        ItemStack old = armor[idx];
        ItemStack one = it.clone();
        one.setAmount(1);
        armor[idx] = one;
        it.setAmount(it.getAmount() - 1);
        inv.setItem(slot, it.getAmount() <= 0 ? null : it);
        if (old != null) inv.addItem(old);
        syncEquipment();
        return inventory();
    }

    Map<String, Object> place(Request r) {
        Mannequin e = requireEntity();
        Block b = targetBlock(r);
        int slot = slotArg(r, "slot", heldSlot);
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType().isAir()) throw new ApiException(422, "slot " + slot + " is empty");
        Material m = it.getType();
        if (!m.isBlock()) throw new ApiException(422, m.name() + " is not a placeable block");
        if (Tag.DOORS.isTagged(m) || Tag.BEDS.isTagged(m)) throw new ApiException(422, "doors and beds are not supported yet");
        checkReach(b);
        if (!b.isReplaceable()) throw new ApiException(422, "the position is occupied by " + b.getType().name());
        if (!hasSupport(b)) throw new ApiException(422, "there is nothing next to this position to attach the block to");
        BoundingBox box = BoundingBox.of(b);
        if (!b.getWorld().getNearbyEntities(box, x -> x instanceof LivingEntity).isEmpty()) {
            throw new ApiException(422, "an entity is standing in the way");
        }
        BlockData bd = m.createBlockData();
        if (bd instanceof Directional d) {
            BlockFace toward = yawToFace(e.getLocation().getYaw());
            BlockFace face = Tag.STAIRS.isTagged(m) ? toward : toward.getOppositeFace();
            if (d.getFaces().contains(face)) d.setFacing(face);
        }
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        face(center);
        b.setBlockData(bd, true);
        b.getWorld().playSound(center, bd.getSoundGroup().getPlaceSound(), 1f, 0.8f);
        e.swingMainHand();
        it.setAmount(it.getAmount() - 1);
        inv.setItem(slot, it.getAmount() <= 0 ? null : it);
        syncEquipment();
        return map("status", "ok", "placed", m.name(), "block", List.of(b.getX(), b.getY(), b.getZ()));
    }

    private static boolean hasSupport(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (!b.getRelative(f).isReplaceable()) return true;
        }
        return false;
    }

    Map<String, Object> use(Request r) {
        Mannequin e = requireEntity();
        Block b = targetBlock(r);
        checkReach(b);
        face(b.getLocation().add(0.5, 0.5, 0.5));
        e.swingMainHand();
        Material type = b.getType();
        if (b.getState() instanceof Container) return containerView(b);
        BlockData bd = b.getBlockData();
        if (bd instanceof Openable o) {
            if (type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR) {
                throw new ApiException(422, "iron doors and trapdoors need redstone (a button or lever)");
            }
            o.setOpen(!o.isOpen());
            b.setBlockData(o, true);
            return map("status", "ok", "block", type.name(), "open", o.isOpen());
        }
        if (bd instanceof Switch s) {
            boolean button = type.name().endsWith("_BUTTON");
            s.setPowered(button || !s.isPowered());
            b.setBlockData(s, true);
            if (button) {
                long delay = type == Material.STONE_BUTTON || type == Material.POLISHED_BLACKSTONE_BUTTON ? 20L : 30L;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (b.getBlockData() instanceof Switch s2 && b.getType() == type) {
                        s2.setPowered(false);
                        b.setBlockData(s2, true);
                    }
                }, delay);
            }
            return map("status", "ok", "block", type.name(), "powered", s.isPowered());
        }
        if (type == Material.CRAFTING_TABLE) {
            return map("status", "ok", "hint", "stand within 4 blocks of this table and call POST /api/avatar/craft");
        }
        throw new ApiException(422, "nothing to use on " + type.name());
    }

    Map<String, Object> container(Request r) {
        requireEntity();
        Block b = targetBlock(r);
        checkReach(b);
        return containerView(b);
    }

    private Map<String, Object> containerView(Block b) {
        if (!(b.getState() instanceof Container c)) throw new ApiException(422, b.getType().name() + " is not a container");
        Inventory ci = c.getInventory();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int i = 0; i < ci.getSize(); i++) {
            ItemStack it = ci.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            Map<String, Object> s = itemInfo(it);
            s.put("slot", i);
            slots.add(s);
        }
        String hint = switch (b.getType()) {
            case FURNACE, BLAST_FURNACE, SMOKER -> "slot 0 = input, 1 = fuel, 2 = result";
            default -> null;
        };
        return map("container", b.getType().name(), "block", List.of(b.getX(), b.getY(), b.getZ()),
                "size", ci.getSize(), "slots", slots, "hint", hint);
    }

    Map<String, Object> containerPut(Request r) {
        Mannequin e = requireEntity();
        Block b = targetBlock(r);
        checkReach(b);
        if (!(b.getState() instanceof Container c)) throw new ApiException(422, b.getType().name() + " is not a container");
        Inventory ci = c.getInventory();
        int from = slotArg(r, "slot", heldSlot);
        ItemStack src = inv.getItem(from);
        if (src == null || src.getType().isAir()) throw new ApiException(422, "slot " + from + " is empty");
        int amount = Math.max(1, Math.min(r.integer("amount", src.getAmount()), src.getAmount()));
        ItemStack moving = src.clone();
        moving.setAmount(amount);
        int notMoved;
        if (r.has("to_slot")) {
            int to = r.integer("to_slot", 0);
            if (to < 0 || to >= ci.getSize()) throw new ApiException(400, "to_slot must be 0.." + (ci.getSize() - 1));
            ItemStack existing = ci.getItem(to);
            if (existing == null || existing.getType().isAir()) {
                ci.setItem(to, moving);
                notMoved = 0;
            } else if (existing.isSimilar(moving)) {
                int put = Math.min(existing.getMaxStackSize() - existing.getAmount(), amount);
                existing.setAmount(existing.getAmount() + put);
                ci.setItem(to, existing);
                notMoved = amount - put;
            } else {
                throw new ApiException(422, "container slot " + to + " holds a different item");
            }
        } else {
            notMoved = ci.addItem(moving).values().stream().mapToInt(ItemStack::getAmount).sum();
        }
        int moved = amount - notMoved;
        src.setAmount(src.getAmount() - moved);
        inv.setItem(from, src.getAmount() <= 0 ? null : src);
        face(b.getLocation().add(0.5, 0.5, 0.5));
        e.swingMainHand();
        syncEquipment();
        Map<String, Object> out = containerView(b);
        out.put("moved", moved);
        return out;
    }

    Map<String, Object> containerTake(Request r) {
        Mannequin e = requireEntity();
        Block b = targetBlock(r);
        checkReach(b);
        if (!(b.getState() instanceof Container c)) throw new ApiException(422, b.getType().name() + " is not a container");
        Inventory ci = c.getInventory();
        int slot = r.integer("slot", -1);
        if (slot < 0 || slot >= ci.getSize()) throw new ApiException(400, "slot must be 0.." + (ci.getSize() - 1));
        ItemStack src = ci.getItem(slot);
        if (src == null || src.getType().isAir()) throw new ApiException(422, "container slot " + slot + " is empty");
        int amount = Math.max(1, Math.min(r.integer("amount", src.getAmount()), src.getAmount()));
        ItemStack moving = src.clone();
        moving.setAmount(amount);
        int notMoved = inv.addItem(moving).values().stream().mapToInt(ItemStack::getAmount).sum();
        int moved = amount - notMoved;
        src.setAmount(src.getAmount() - moved);
        ci.setItem(slot, src.getAmount() <= 0 ? null : src);
        face(b.getLocation().add(0.5, 0.5, 0.5));
        e.swingMainHand();
        syncEquipment();
        Map<String, Object> out = containerView(b);
        out.put("taken", moved);
        return out;
    }

    Map<String, Object> craft(Request r) {
        Mannequin e = requireEntity();
        Material target = material(r.require("item"));
        int times = Math.max(1, Math.min(r.integer("times", 1), 64));
        boolean tableNear = blockNear(e.getLocation(), Material.CRAFTING_TABLE, 4);
        List<Recipe> recipes = Bukkit.getRecipesFor(new ItemStack(target));
        int made = 0;
        String reason = "there is no crafting recipe for " + target.name();
        for (int i = 0; i < times; i++) {
            boolean crafted = false;
            for (Recipe rec : recipes) {
                List<RecipeChoice> need = ingredients(rec);
                if (need == null) continue;
                if (needsTable(rec) && !tableNear) {
                    reason = "this recipe needs a crafting table within 4 blocks";
                    continue;
                }
                ItemStack[] copy = cloneContents();
                if (!consume(copy, need)) {
                    reason = "not enough ingredients; recipe needs " + describe(need);
                    continue;
                }
                inv.setContents(copy);
                ItemStack result = rec.getResult().clone();
                for (ItemStack rest : inv.addItem(result).values()) e.getWorld().dropItem(e.getLocation(), rest);
                made += result.getAmount();
                crafted = true;
                break;
            }
            if (!crafted) break;
        }
        syncEquipment();
        if (made == 0) throw new ApiException(422, reason);
        e.swingMainHand();
        return map("status", "ok", "crafted", target.name(), "amount", made, "inventory", inventory());
    }

    private static List<RecipeChoice> ingredients(Recipe rec) {
        if (rec instanceof ShapedRecipe s) {
            List<RecipeChoice> out = new ArrayList<>();
            Map<Character, RecipeChoice> cm = s.getChoiceMap();
            for (String row : s.getShape()) {
                for (char c : row.toCharArray()) {
                    RecipeChoice ch = cm.get(c);
                    if (ch != null) out.add(ch);
                }
            }
            return out;
        }
        if (rec instanceof ShapelessRecipe s) return new ArrayList<>(s.getChoiceList());
        return null;
    }

    private static boolean needsTable(Recipe rec) {
        if (rec instanceof ShapedRecipe s) {
            String[] shape = s.getShape();
            if (shape.length > 2) return true;
            for (String row : shape) if (row.length() > 2) return true;
            return false;
        }
        if (rec instanceof ShapelessRecipe s) return s.getChoiceList().size() > 4;
        return true;
    }

    private static boolean consume(ItemStack[] items, List<RecipeChoice> need) {
        for (RecipeChoice ch : need) {
            boolean found = false;
            for (int i = 0; i < items.length; i++) {
                ItemStack it = items[i];
                if (it == null || it.getType().isAir()) continue;
                if (ch.test(it)) {
                    it.setAmount(it.getAmount() - 1);
                    if (it.getAmount() <= 0) items[i] = null;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String describe(List<RecipeChoice> need) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RecipeChoice ch : need) {
            String label;
            if (ch instanceof RecipeChoice.MaterialChoice mc) {
                List<Material> ms = mc.getChoices();
                label = ms.size() == 1 ? ms.get(0).name() : ms.get(0).name() + " (or similar)";
            } else if (ch instanceof RecipeChoice.ExactChoice ec) {
                label = ec.getChoices().get(0).getType().name();
            } else {
                label = "special item";
            }
            counts.merge(label, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(v + " x " + k));
        return String.join(", ", parts);
    }

    private ItemStack[] cloneContents() {
        ItemStack[] src = inv.getContents();
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }

    private static boolean blockNear(Location l, Material type, int radius) {
        World w = l.getWorld();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = PathFinder.block(w, l.getBlockX() + x, l.getBlockY() + y, l.getBlockZ() + z);
                    if (b != null && b.getType() == type) return true;
                }
            }
        }
        return false;
    }

    Map<String, Object> attack(Request r) {
        Mannequin e = requireEntity();
        LivingEntity target = null;
        if (r.has("entity")) {
            Entity n;
            try {
                n = Bukkit.getEntity(UUID.fromString(r.str("entity")));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, "entity must be a UUID from GET /api/avatar/state");
            }
            if (n instanceof LivingEntity le) target = le;
        } else if (r.has("player")) {
            target = player(r.str("player"));
        } else {
            String type = r.str("type", null);
            double best = Double.MAX_VALUE;
            for (Entity n : e.getNearbyEntities(5, 3, 5)) {
                if (!(n instanceof LivingEntity le) || n instanceof Player) continue;
                if (type != null ? !n.getType().name().equalsIgnoreCase(type) : !(n instanceof Enemy)) continue;
                double d = n.getLocation().distanceSquared(e.getLocation());
                if (d < best) {
                    best = d;
                    target = le;
                }
            }
        }
        if (target == null || !target.isValid()) throw new ApiException(404, "no target found");
        if (isAvatar(target)) throw new ApiException(400, "the avatar cannot attack itself");
        double dist = e.getEyeLocation().distance(target.getLocation().add(0, target.getHeight() / 2, 0));
        if (dist > 4.0) throw new ApiException(422, String.format(Locale.ROOT, "target is too far (%.1f blocks); move closer first", dist));
        face(target.getEyeLocation());
        e.swingMainHand();
        double damage = attackDamage(inv.getItem(heldSlot));
        double before = target.getHealth();
        target.damage(damage, e);
        Vector dir = target.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() > 1e-6) target.knockback(0.4, -dir.getX(), -dir.getZ());
        ItemStack held = inv.getItem(heldSlot);
        if (held != null && (held.getType().name().endsWith("_SWORD") || held.getType().name().endsWith("_AXE"))) damageTool(1);
        syncEquipment();
        return map("status", "ok", "target", entityInfo(target, e.getLocation()), "damage", damage,
                "healthBefore", r1(before), "healthAfter", target.isDead() ? 0 : r1(target.getHealth()), "killed", target.isDead());
    }

    private static double attackDamage(ItemStack it) {
        if (it == null) return 1;
        String n = it.getType().name();
        boolean sword = n.endsWith("_SWORD");
        boolean axe = n.endsWith("_AXE");
        if (!sword && !axe) return n.equals("TRIDENT") ? 9 : n.equals("MACE") ? 6 : 1;
        if (n.startsWith("NETHERITE")) return sword ? 8 : 10;
        if (n.startsWith("DIAMOND")) return sword ? 7 : 9;
        if (n.startsWith("IRON")) return sword ? 6 : 9;
        if (n.startsWith("STONE") || n.startsWith("COPPER")) return sword ? 5 : 9;
        return sword ? 4 : 7;
    }

    private void damageTool(int amount) {
        ItemStack tool = inv.getItem(heldSlot);
        if (tool == null || tool.getType().getMaxDurability() <= 0) return;
        if (!(tool.getItemMeta() instanceof Damageable dm) || dm.isUnbreakable()) return;
        int dmg = dm.getDamage() + amount;
        if (dmg >= tool.getType().getMaxDurability()) {
            inv.setItem(heldSlot, null);
            plugin.chat().add("avatar", name(), "tool broke: " + tool.getType().name());
        } else {
            dm.setDamage(dmg);
            tool.setItemMeta(dm);
            inv.setItem(heldSlot, tool);
        }
    }

    Map<String, Object> stop() {
        requireEntity();
        boolean had = task != null;
        cancelTask("stopped by API");
        return map("status", had ? "stopped" : "idle");
    }

    // ------------------------------------------------------------------ long actions (tasks)

    Task startGoto(Request r) {
        Mannequin e = requireEntity();
        Supplier<Location> goal;
        String label;
        if (r.has("player")) {
            String pn = player(r.str("player")).getName();
            goal = () -> {
                Player p = Bukkit.getPlayerExact(pn);
                return p == null ? null : p.getLocation();
            };
            label = "goto player " + pn;
        } else {
            World w = e.getWorld();
            double x = r.requireDbl("x");
            double z = r.requireDbl("z");
            double y;
            if (r.has("y")) {
                y = r.dbl("y", 0);
            } else if (w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
                y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            } else {
                y = e.getLocation().getY();
            }
            Location fixed = new Location(w, x, y, z);
            if (fixed.distance(e.getLocation()) > 2000) throw new ApiException(422, "target is more than 2000 blocks away; use teleport");
            goal = () -> fixed;
            label = String.format(Locale.ROOT, "goto %.1f %.1f %.1f", x, y, z);
        }
        double stop = Math.max(0.5, r.dbl("distance", r.has("player") ? 2.0 : 1.0));
        MoveTask t = new MoveTask(label, goal, stop, false);
        start(t);
        return t;
    }

    Task startFollow(Request r) {
        requireEntity();
        String pn = player(r.require("player")).getName();
        double dist = Math.max(1.5, r.dbl("distance", 2.5));
        MoveTask t = new MoveTask("follow " + pn, () -> {
            Player p = Bukkit.getPlayerExact(pn);
            return p == null ? null : p.getLocation();
        }, dist, true);
        start(t);
        return t;
    }

    Task startBreak(Request r) {
        requireEntity();
        Block b = targetBlock(r);
        BreakTask t = new BreakTask(b, r.bool("approach", true));
        start(t);
        return t;
    }

    private void start(Task t) {
        Task old = task;
        task = t;
        if (old != null) old.cancel("replaced by " + t.name);
    }

    private void cancelTask(String reason) {
        Task t = task;
        task = null;
        if (t != null) t.cancel(reason);
    }

    /** Waits (off the main thread) for a task result, up to the request's timeout. */
    static Map<String, Object> await(Task t, Request r) {
        int timeout = Math.max(0, Math.min(r.integer("timeout", 30), 120));
        if (!r.bool("wait", true) || timeout == 0) return map("status", "started", "task", t.name);
        try {
            return t.done.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return map("status", "running", "task", t.name,
                    "note", "still in progress; poll GET /api/avatar/state or cancel with POST /api/avatar/stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return map("status", "running", "task", t.name);
        } catch (ExecutionException e) {
            return fail(String.valueOf(e.getCause()));
        }
    }

    abstract class Task {
        final String name;
        final CompletableFuture<Map<String, Object>> done = new CompletableFuture<>();
        volatile String status = "running";

        Task(String name) {
            this.name = name;
        }

        /** Called every tick; returns true when the task is finished. */
        abstract boolean tick();

        boolean finish(Map<String, Object> result) {
            status = String.valueOf(result.get("status"));
            result.putIfAbsent("task", name);
            done.complete(result);
            return true;
        }

        void cancel(String reason) {
            if (!done.isDone()) finish(map("status", "cancelled", "reason", reason));
        }
    }

    final class MoveTask extends Task {
        private final Supplier<Location> goal;
        private final double stopDist;
        private final boolean follow;
        private List<int[]> path = List.of();
        private int idx;
        private int replanTimer;
        private double bestDist = Double.MAX_VALUE;
        private int noProgressPlans;
        private int stuckTicks;
        private int ticks;
        private Location lastPos;

        MoveTask(String name, Supplier<Location> goal, double stopDist, boolean follow) {
            super(name);
            this.goal = goal;
            this.stopDist = stopDist;
            this.follow = follow;
        }

        @Override
        boolean tick() {
            ticks++;
            Mannequin e = entity;
            Location cur = e.getLocation();
            Location g = goal.get();
            if (g == null) return finish(fail("the target is gone (player left?)"));
            if (!g.getWorld().equals(cur.getWorld())) return finish(fail("the target is in another world"));
            double d = cur.distance(g);
            if (d <= stopDist) {
                if (!follow) return finish(ok("arrived", true, "distance", r1(d), "position", pos(cur)));
                path = List.of();
                idx = 0;
                face(g.clone().add(0, 1.5, 0));
                return false;
            }
            if (!follow && ticks > 20 * 600) return finish(fail("gave up after 10 minutes of walking"));
            if (follow) replanTimer--;
            // Following: refresh the path every second, or soon after the current one runs out.
            boolean needPlan = follow ? (replanTimer <= 0 || (idx >= path.size() && replanTimer <= 15)) : idx >= path.size();
            if (needPlan) {
                if (!e.isOnGround() && !e.isInWater()) return false; // wait until landed
                if (!follow) {
                    if (d < bestDist - 0.5) {
                        bestDist = d;
                        noProgressPlans = 0;
                    } else if (++noProgressPlans > 3) {
                        return finish(fail(String.format(Locale.ROOT, "cannot get closer: target is unreachable from here (%.1f blocks left)", d)));
                    }
                }
                PathFinder.Result res = PathFinder.find(cur.getWorld(), cur.getBlockX(), (int) Math.floor(cur.getY() + 0.01),
                        cur.getBlockZ(), g.getX(), g.getY(), g.getZ(), stopDist, 3000);
                path = res.path();
                idx = 0;
                replanTimer = 20;
                if (path.isEmpty()) {
                    if (follow) return false;
                    return finish(fail(String.format(Locale.ROOT, "no path found (%.1f blocks left)", d)));
                }
            }
            step(e, cur);
            if (lastPos != null && lastPos.distanceSquared(cur) < 0.0004) {
                if (++stuckTicks > 40) {
                    path = List.of();
                    idx = 0;
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            lastPos = cur;
            return false;
        }

        private void step(Mannequin e, Location cur) {
            int[] w = path.get(idx);
            double tx = w[0] + 0.5;
            double ty = w[1];
            double tz = w[2] + 0.5;
            double dx = tx - cur.getX();
            double dz = tz - cur.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double speed = walkSpeed() / 20.0;
            if (PathFinder.isWater(cur.getBlock())) speed *= 0.5;
            double ny = cur.getY();
            if (ty > cur.getY() + 0.01) ny = Math.min(ty, cur.getY() + 0.5);
            else if (ty < cur.getY() - 0.01 && horiz < 0.5) ny = Math.max(ty, cur.getY() - 0.5);
            double nx;
            double nz;
            if (horiz <= speed) {
                nx = tx;
                nz = tz;
            } else {
                nx = cur.getX() + dx / horiz * speed;
                nz = cur.getZ() + dz / horiz * speed;
            }
            float yaw = horiz > 1e-4 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : cur.getYaw();
            openDoor(cur.getWorld(), w[0], w[1], w[2]);
            e.teleport(new Location(cur.getWorld(), nx, ny, nz, yaw, 0f));
            e.setBodyYaw(yaw);
            e.setVelocity(new Vector());
            e.setFallDistance(0f);
            if (horiz <= speed && Math.abs(ny - ty) < 0.01) idx++;
        }

        private void openDoor(World w, int x, int y, int z) {
            for (int dy = 0; dy <= 1; dy++) {
                Block b = w.getBlockAt(x, y + dy, z);
                Material m = b.getType();
                if ((Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) && b.getBlockData() instanceof Openable o && !o.isOpen()) {
                    o.setOpen(true);
                    b.setBlockData(o, true);
                }
            }
        }
    }

    final class BreakTask extends Task {
        private final Block block;
        private MoveTask approach;
        private int total = -1;
        private int progress;
        private Material type;

        BreakTask(Block b, boolean approachIfFar) {
            super("break " + b.getType().name() + " at " + b.getX() + " " + b.getY() + " " + b.getZ());
            this.block = b;
            if (approachIfFar && !inReach(b)) {
                Location c = b.getLocation().add(0.5, 0, 0.5);
                approach = new MoveTask("approach", () -> c, Math.max(1.0, reach() - 1.5), false);
            }
        }

        @Override
        boolean tick() {
            Mannequin e = entity;
            if (approach != null) {
                if (!approach.tick()) return false;
                Map<String, Object> res = approach.done.getNow(null);
                approach = null;
                if (res == null || !"ok".equals(res.get("status"))) {
                    return finish(fail("could not get close to the block: " + (res == null ? "?" : res.get("reason"))));
                }
            }
            if (total < 0) {
                if (!inReach(block)) {
                    return finish(fail(String.format(Locale.ROOT, "block is out of reach (%.1f > %.1f); move closer or pass approach=true",
                            reachDistance(block), reach())));
                }
                type = block.getType();
                if (type.isAir() || block.isLiquid()) return finish(fail("there is no block to break at this position"));
                float hardness = type.getHardness();
                if (hardness < 0) return finish(fail(type.name() + " is unbreakable"));
                ItemStack tool = toolOrEmpty();
                BlockData bd = block.getBlockData();
                float speed = Math.max(0.1f, bd.getDestroySpeed(tool, true));
                boolean canHarvest = !bd.requiresCorrectToolForDrops() || bd.isPreferredTool(tool);
                total = hardness == 0 ? 1 : (int) Math.ceil((canHarvest ? 30.0 : 100.0) * hardness / speed);
                total = Math.max(1, Math.min(total, 20 * 60));
            }
            if (block.getType() != type) {
                sendDamage(e, 0f);
                return finish(fail("the block changed while mining"));
            }
            progress++;
            face(block.getLocation().add(0.5, 0.5, 0.5));
            if (progress % 5 == 1) e.swingMainHand();
            sendDamage(e, Math.min(0.99f, progress / (float) total));
            if (progress < total) return false;
            sendDamage(e, 0f);
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            boolean broken = block.breakNaturally(toolOrEmpty(), true, false);
            List<String> got = new ArrayList<>();
            for (Item it : center.getWorld().getNearbyEntitiesByType(Item.class, center, 1.5)) {
                if (it.getTicksLived() > 2) continue;
                ItemStack st = it.getItemStack();
                int taken = addToInventory(st, it);
                if (taken > 0) got.add(taken + " x " + st.getType().name());
            }
            damageTool(1);
            syncEquipment();
            return finish(ok("broken", broken, "block", type.name(), "ticks", total, "collected", got));
        }

        private void sendDamage(Mannequin e, float progress) {
            Location l = block.getLocation();
            for (Player p : l.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l) < 64 * 64) p.sendBlockDamage(l, progress, e);
            }
        }
    }

    private ItemStack toolOrEmpty() {
        ItemStack t = inv.getItem(heldSlot);
        return t == null ? ItemStack.empty() : t;
    }

    private boolean inReach(Block b) {
        return isSpawned() && reachDistance(b) <= reach() + 0.3;
    }

    private double reachDistance(Block b) {
        return entity.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5));
    }

    private void checkReach(Block b) {
        double d = reachDistance(b);
        if (d > reach() + 0.3) {
            throw new ApiException(422, String.format(Locale.ROOT, "block is too far (%.1f > %.1f); walk closer first (POST /api/avatar/goto)", d, reach()));
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!isAvatar(event.getEntity())) return;
        String source = event.getCause().name();
        if (event instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            source = d instanceof Player p ? "player " + p.getName() : d.getType().name();
        }
        double after = Math.max(0, entity.getHealth() - event.getFinalDamage());
        plugin.chat().add("avatar-damage", name(),
                String.format(Locale.ROOT, "took %.1f damage from %s (health %.1f)", event.getFinalDamage(), source, after));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        if (!isAvatar(event.getEntity())) return;
        boolean keep = plugin.getConfig().getBoolean("avatar.keep-inventory", false);
        event.getDrops().clear();
        if (!keep) {
            for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) event.getDrops().add(it.clone());
            for (int i = 0; i < armor.length; i++) {
                if (armor[i] != null) event.getDrops().add(armor[i].clone());
                armor[i] = null;
            }
            inv.clear();
        }
        String cause = event.getEntity().getLastDamageCause() == null ? "unknown" : event.getEntity().getLastDamageCause().getCause().name();
        String msg = name() + " died (" + cause + ")";
        plugin.chat().add("avatar-death", name(), msg);
        if (announce()) Bukkit.broadcast(Component.text(msg, NamedTextColor.RED));
        Task t = task;
        task = null;
        if (t != null) t.cancel("the avatar died");
        removeTickets();
        savedLocation = entity.getLocation();
        savedSpawned = false;
        entity = null;
        saveState();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isAvatar(event.getRightClicked())) return;
        event.setCancelled(true);
        plugin.chat().add("avatar-interact", event.getPlayer().getName(), event.getPlayer().getName() + " right-clicked " + name());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (isAvatar(event.getRightClicked())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ helpers

    private Block targetBlock(Request r) {
        Mannequin e = requireEntity();
        World w = r.has("world") ? world(r) : e.getWorld();
        int x = (int) Math.floor(r.requireDbl("x"));
        int y = (int) Math.floor(r.requireDbl("y"));
        int z = (int) Math.floor(r.requireDbl("z"));
        if (!w.isChunkLoaded(x >> 4, z >> 4)) throw new ApiException(422, "that chunk is not loaded");
        return w.getBlockAt(x, y, z);
    }

    private int slotArg(Request r, String key, int def) {
        int slot = r.integer(key, def);
        if (slot < 0 || slot >= INV_SIZE) throw new ApiException(400, key + " must be 0.." + (INV_SIZE - 1));
        return slot;
    }

    private static Player player(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) throw new ApiException(404, "player is not online: " + name);
        return p;
    }

    private static World world(Request r) {
        String wn = r.str("world", null);
        if (wn == null) return Bukkit.getWorlds().get(0);
        World w = Bukkit.getWorld(wn);
        if (w == null) throw new ApiException(404, "world not found: " + wn);
        return w;
    }

    private static Material material(String name) {
        Material m = Material.matchMaterial(name.trim());
        if (m == null) throw new ApiException(404, "unknown item or block: " + name);
        return m;
    }

    private static BlockFace yawToFace(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 45 && y < 135) return BlockFace.WEST;
        if (y >= 135 && y < 225) return BlockFace.NORTH;
        if (y >= 225 && y < 315) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }

    private static String facing(float yaw) {
        return yawToFace(yaw).name().toLowerCase(Locale.ROOT);
    }

    private static List<Double> pos(Location l) {
        return List.of(r2(l.getX()), r2(l.getY()), r2(l.getZ()));
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> ok(Object... kv) {
        Map<String, Object> m = map(kv);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.putAll(m);
        return out;
    }

    private static Map<String, Object> fail(String reason) {
        return map("status", "failed", "reason", reason);
    }

    private static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
