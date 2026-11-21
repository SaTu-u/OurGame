package dev.ourgame.mila;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Mila: every player can call their own companion with /mila. She is a Mannequin with a skin from the server resource pack. */
public final class MilaPlugin extends JavaPlugin implements Listener, TabExecutor {

    static final TextColor PINK = TextColor.color(0xFF7EB6);
    private static final List<String> SUBCOMMANDS = List.of("follow", "stay", "hug", "kiss", "gift", "bag", "info", "name", "bye", "help");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("позвать", "call"), Map.entry("иди", "follow"), Map.entry("следуй", "follow"),
            Map.entry("жди", "stay"), Map.entry("стой", "stay"), Map.entry("обнять", "hug"),
            Map.entry("поцеловать", "kiss"), Map.entry("подарок", "gift"), Map.entry("подарить", "gift"),
            Map.entry("рюкзак", "bag"), Map.entry("инфо", "info"), Map.entry("имя", "name"),
            Map.entry("пока", "bye"), Map.entry("помощь", "help"));

    private final Map<UUID, Companion> companions = new HashMap<>();
    private NamespacedKey ownerKey;
    private Settings settings;
    private ResolvableProfile profile;
    private ResolvableProfile fallbackProfile;
    /** Players whose client reported the server resource pack as loaded: they see her own skin. */
    private final Set<UUID> withPack = new HashSet<>();
    private File playersDir;
    private long tick;
    private boolean tickErrorLogged;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ownerKey = new NamespacedKey(this, "owner");
        playersDir = new File(getDataFolder(), "players");
        if (!playersDir.isDirectory() && !playersDir.mkdirs()) getLogger().warning("Could not create " + playersDir);
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("mila");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) if (isCompanion(m)) m.remove();
        }
        for (Player p : Bukkit.getOnlinePlayers()) companion(p);
        getServer().getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Companion c : companions.values()) {
            c.closeBagViews();
            c.despawnEntity();
            c.save();
        }
        companions.clear();
    }

    private void loadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        profile = ResolvableProfile.resolvableProfile()
                .skinPatch(p -> p.body(settings.texture()).model(settings.model()))
                .build();
        fallbackProfile = settings.fallbackTexture() == null ? null : ResolvableProfile.resolvableProfile()
                .skinPatch(p -> p.body(settings.fallbackTexture()).model(settings.model()))
                .build();
    }

    /** The built-in skin shown to players without the server resource pack; null when that is turned off. */
    ResolvableProfile fallbackProfile() {
        return fallbackProfile;
    }

    /** Shows each player the entity with the skin their client can draw, and hides the other one. */
    void applyVisibility(Companion c) {
        for (Player viewer : Bukkit.getOnlinePlayers()) applyVisibility(c, viewer);
    }

    private void applyVisibility(Companion c, Player viewer) {
        boolean ownSkin = fallbackProfile == null || withPack.contains(viewer.getUniqueId());
        // visibility is switched both ways explicitly, so turning the fallback skin off and on with /mila reload works too
        if (c.entity != null && c.entity.isValid() && c.entity.isVisibleByDefault() == (fallbackProfile != null)) {
            c.entity.setVisibleByDefault(fallbackProfile == null);
        }
        if (c.entity != null && c.entity.isValid()) {
            if (ownSkin) viewer.showEntity(this, c.entity);
            else viewer.hideEntity(this, c.entity);
        }
        if (c.twin != null && c.twin.isValid()) {
            if (ownSkin) viewer.hideEntity(this, c.twin);
            else viewer.showEntity(this, c.twin);
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player p = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> withPack.add(p.getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> withPack.remove(p.getUniqueId());
            default -> {
                return;
            }
        }
        for (Companion c : companions.values()) applyVisibility(c, p);
    }

    Settings settings() {
        return settings;
    }

    ResolvableProfile profile() {
        return profile;
    }

    NamespacedKey ownerKey() {
        return ownerKey;
    }

    long tick() {
        return tick;
    }

    private Companion companion(Player p) {
        return companions.computeIfAbsent(p.getUniqueId(),
                id -> new Companion(this, new File(playersDir, id + ".yml"), id, p.getName()));
    }

    boolean isCompanion(Entity e) {
        return e instanceof Mannequin && e.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    /** The online owner's companion for one of our mannequins; null (and the stray entity is removed) otherwise. */
    private Companion companionOf(Entity e) {
        String id = e.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        Companion c = null;
        try {
            c = id == null ? null : companions.get(UUID.fromString(id));
        } catch (IllegalArgumentException ignored) {
        }
        if (c == null || !c.owns(e)) {
            e.remove();
            return null;
        }
        return c;
    }

    private void tickAll() {
        tick++;
        for (Companion c : new ArrayList<>(companions.values())) {
            Player p = Bukkit.getPlayer(c.owner);
            if (p == null) continue;
            try {
                c.tick(p, tick);
            } catch (Exception ex) {
                if (!tickErrorLogged) {
                    tickErrorLogged = true;
                    getLogger().log(Level.WARNING, "Companion tick failed (logged once)", ex);
                }
            }
        }
        if (tick % 6000 == 0) for (Companion c : companions.values()) c.save();
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "call" : args[0].toLowerCase(Locale.ROOT);
        sub = ALIASES.getOrDefault(sub, sub);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("mila.admin")) {
                sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            for (Companion c : companions.values()) {
                c.updateLook();
                c.syncTwin();
                applyVisibility(c);
            }
            sender.sendMessage(Component.text("Mila: config reloaded", NamedTextColor.GREEN));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players have a companion. Admin: /mila reload");
            return true;
        }
        Companion c = companion(p);
        c.ownerName = p.getName();
        switch (sub) {
            case "call" -> c.call(p);
            case "follow" -> c.follow(p);
            case "stay" -> c.stay(p);
            case "hug" -> c.request(p, Companion.Action.HUG);
            case "kiss" -> c.request(p, Companion.Action.KISS);
            case "gift" -> c.gift(p, true);
            case "bag" -> c.openBag(p);
            case "info" -> c.info(p);
            case "name" -> {
                if (args.length < 2) p.sendMessage(Component.text("Пример: /mila name Мила", NamedTextColor.GRAY));
                else c.rename(p, String.join(" ", List.of(args).subList(1, args.length)));
            }
            case "bye" -> c.dismiss(p);
            default -> help(p, c);
        }
        return true;
    }

    private void help(Player p, Companion c) {
        p.sendMessage(Component.text(c.name + " — твоя подруга", PINK));
        String[][] lines = {
                {"/mila", "позвать её (или позвать к себе)"},
                {"/mila follow", "ходить за тобой"},
                {"/mila stay", "ждать на месте"},
                {"/mila hug", "обнять (отношения от " + settings.hugAt() + ")"},
                {"/mila kiss", "поцеловать в щёчку (отношения от " + settings.kissAt() + ")"},
                {"/mila gift", "подарить то, что в руке: цветы, сладости, НМК, торт…"},
                {"/mila bag", "её рюкзак на " + Companion.BAG_SIZE + " слотов (или Shift + ПКМ по ней)"},
                {"/mila info", "отношения и подарки"},
                {"/mila name <имя>", "переименовать"},
                {"/mila bye", "отпустить её отдыхать"},
        };
        for (String[] l : lines) {
            p.sendMessage(Component.text(l[0], NamedTextColor.YELLOW).append(Component.text(" — " + l[1], NamedTextColor.GRAY)));
        }
        p.sendMessage(Component.text("ПКМ по ней — поговорить или отдать подарок из руки. Она защищает тебя от монстров.", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : SUBCOMMANDS) if (s.startsWith(prefix)) out.add(s);
        if (sender.hasPermission("mila.admin") && "reload".startsWith(prefix)) out.add("reload");
        return out;
    }

    // ------------------------------------------------------------------ players

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        withPack.remove(p.getUniqueId());
        for (Companion other : companions.values()) applyVisibility(other, p);
        Companion c = companion(p);
        c.ownerName = p.getName();
        if (c.out) {
            c.spawnNotBefore = tick + 40;
            c.greetOnSpawn = c.mode == Companion.Mode.FOLLOW;
            if (c.mode == Companion.Mode.STAY) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (p.isOnline()) c.say(p, "Я жду тебя на прежнем месте ❤");
                }, 40L);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
        withPack.remove(event.getPlayer().getUniqueId());
        Companion c = companions.remove(event.getPlayer().getUniqueId());
        if (c == null) return;
        c.despawnEntity();
        c.save();
    }

    @EventHandler
    public void onOwnerDeath(PlayerDeathEvent event) {
        Companion c = companions.get(event.getEntity().getUniqueId());
        if (c != null && c.isSpawned()) c.say(event.getEntity(), "Нет! {player}, я буду ждать тебя!");
    }

    // ------------------------------------------------------------------ the companion entity

    /*
     * Paper 26.2 fires PlayerInteractAtEntityEvent first for every entity and skips PlayerInteractEntityEvent when the
     * first one is cancelled, so a right click is handled in whichever of the two arrives (once per player and tick).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        handleClick(event.getPlayer(), event.getRightClicked(), event.getHand());
        if (isCompanion(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        handleClick(event.getPlayer(), event.getRightClicked(), event.getHand());
        if (isCompanion(event.getRightClicked())) event.setCancelled(true);
    }

    private final Map<UUID, Long> lastClick = new HashMap<>();

    private void handleClick(Player p, Entity clicked, EquipmentSlot hand) {
        if (settings.debug()) getLogger().info("click: " + p.getName() + " -> " + clicked.getType() + " hand " + hand);
        if (!isCompanion(clicked) || hand != EquipmentSlot.HAND) return;
        Long last = lastClick.put(p.getUniqueId(), tick);
        if (last != null && last == tick) return;
        Companion c = companionOf(clicked);
        if (c == null) return;
        if (p.getUniqueId().equals(c.owner)) c.clickedByOwner(p);
        else c.clickedByStranger(p);
    }

    /** She cannot be hurt. /kill and the void still remove her; she then comes back to her owner. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (!isCompanion(event.getEntity())) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.KILL || cause == EntityDamageEvent.DamageCause.VOID) return;
        event.setCancelled(true);
        if (!(event instanceof EntityDamageByEntityEvent by)) return;
        Player attacker = playerBehind(by.getDamager());
        if (attacker == null) return;
        Companion c = companionOf(event.getEntity());
        if (c == null) return;
        if (attacker.getUniqueId().equals(c.owner)) c.hitByOwner(attacker);
        else c.hitByStranger(attacker);
    }

    /** Remembers which monster an owner is fighting, so their companion can join in. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        Player attacker = playerBehind(event.getDamager());
        if (attacker == null) return;
        Companion c = companions.get(attacker.getUniqueId());
        if (c == null) return;
        c.ownerTarget = event.getEntity().getUniqueId();
        c.ownerTargetTick = tick;
    }

    private static Player playerBehind(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        if (!isCompanion(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (isCompanion(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (isCompanion(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        if (isCompanion(event.getEntity())) event.setCancelled(true);
    }

    /** Companions are never saved with the world; anything that still turns up is a leftover. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) if (isCompanion(e)) companionOf(e);
    }

    @EventHandler
    public void onBagClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Companion.BagHolder h) h.companion.save();
    }
}
