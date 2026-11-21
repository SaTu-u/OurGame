package dev.ourgame.mila;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** One player's companion: her saved state and, while she is out, the Mannequin entity that shows her. */
final class Companion {

    enum Mode { FOLLOW, STAY }

    enum Action { HUG, KISS }

    static final int BAG_SIZE = 27;
    private static final int[] MILESTONES = {10, 40, 70, 100};

    private final MilaPlugin plugin;
    private final File file;
    final UUID owner;
    String ownerName;

    // saved state
    String name;
    int affection;
    boolean out;
    boolean met;
    Mode mode = Mode.FOLLOW;
    private String stayWorld;
    private double stayX, stayY, stayZ;
    private float stayYaw;
    private int togetherTicks;
    private String giftDay = "";
    private int giftsToday;
    final Inventory bag;

    // live state
    Mannequin entity;
    boolean greetOnSpawn;
    long spawnNotBefore;
    private List<int[]> path = List.of();
    private int idx;
    private int replanTimer;
    private double fallVelocity;
    private boolean movedThisTick;
    private int buriedChecks;
    private int stuckTicks;
    private double stuckBest = Double.MAX_VALUE;
    private long lastTeleport = -1000;
    private Action pending;
    private long pendingDeadline;
    private long hugReadyAt;
    private long kissReadyAt;
    private long nextChatter = -1;
    private long lastOwnerHit = -1000;
    UUID ownerTarget;
    long ownerTargetTick;
    private LivingEntity fightTarget;
    private long fightProgress;
    private int attackCooldown;
    private final Map<UUID, Long> unreachable = new HashMap<>();
    private final Map<Block, Long> openedDoors = new HashMap<>();

    /** Marks the bag inventory as hers, so the close event can find the owner. */
    static final class BagHolder implements InventoryHolder {
        final Companion companion;
        private Inventory inventory;

        BagHolder(Companion companion) {
            this.companion = companion;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    Companion(MilaPlugin plugin, File file, UUID owner, String ownerName) {
        this.plugin = plugin;
        this.file = file;
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = plugin.settings().defaultName();
        BagHolder holder = new BagHolder(this);
        this.bag = Bukkit.createInventory(holder, BAG_SIZE, Component.text("Рюкзак"));
        holder.inventory = bag;
        load();
    }

    private Settings s() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ saving

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        name = y.getString("name", name);
        affection = Math.max(0, Math.min(100, y.getInt("affection", 0)));
        out = y.getBoolean("out", false);
        met = y.getBoolean("met", false);
        mode = "stay".equalsIgnoreCase(y.getString("mode")) ? Mode.STAY : Mode.FOLLOW;
        if (y.isConfigurationSection("stay")) {
            stayWorld = y.getString("stay.world");
            stayX = y.getDouble("stay.x");
            stayY = y.getDouble("stay.y");
            stayZ = y.getDouble("stay.z");
            stayYaw = (float) y.getDouble("stay.yaw");
        }
        togetherTicks = y.getInt("together-ticks", 0);
        giftDay = y.getString("gift-day", "");
        giftsToday = y.getInt("gifts-today", 0);
        List<String> items = y.getStringList("bag");
        for (int i = 0; i < Math.min(items.size(), BAG_SIZE); i++) bag.setItem(i, decode(items.get(i)));
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("owner-name", ownerName);
        y.set("name", name);
        y.set("affection", affection);
        y.set("out", out);
        y.set("met", met);
        y.set("mode", mode.name().toLowerCase(Locale.ROOT));
        if (stayWorld != null) {
            y.set("stay.world", stayWorld);
            y.set("stay.x", stayX);
            y.set("stay.y", stayY);
            y.set("stay.z", stayZ);
            y.set("stay.yaw", stayYaw);
        }
        y.set("together-ticks", togetherTicks);
        y.set("gift-day", giftDay);
        y.set("gifts-today", giftsToday);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < BAG_SIZE; i++) items.add(encode(bag.getItem(i)));
        y.set("bag", items);
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
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

    // ------------------------------------------------------------------ entity

    boolean isSpawned() {
        return entity != null && entity.isValid();
    }

    /*
     * Two entities show her. The main one wears her own skin from the server resource pack; players without the pack
     * would see a "missing texture" there, so they are shown the twin instead: the same companion with a built-in skin.
     * The twin copies the main entity's position every tick; MilaPlugin.applyVisibility hides one of the two from each
     * player. Only the main entity walks and fights.
     */
    Mannequin twin;

    void spawnEntity(Location loc) {
        despawnEntity();
        // With the fallback skin on, nobody sees a body until applyVisibility picks the right one for each player.
        entity = spawnBody(loc, plugin.profile(), plugin.fallbackProfile() == null);
        updateLook();
        syncTwin();
        plugin.applyVisibility(this);
        path = List.of();
        idx = 0;
        fallVelocity = 0;
        stuckTicks = 0;
        stuckBest = Double.MAX_VALUE;
        fightTarget = null;
    }

    private Mannequin spawnBody(Location loc, ResolvableProfile profile, boolean visibleByDefault) {
        NamespacedKey key = plugin.ownerKey();
        return loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setVisibleByDefault(visibleByDefault);
            m.customName(Component.text(name, MilaPlugin.PINK));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text("подруга " + ownerName, NamedTextColor.GRAY));
            m.setProfile(profile);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            m.setCollidable(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, owner.toString());
        });
    }

    /** True for both of her entities. */
    boolean owns(Entity e) {
        return (entity != null && entity.getUniqueId().equals(e.getUniqueId()))
                || (twin != null && twin.getUniqueId().equals(e.getUniqueId()));
    }

    /** Keeps the twin next to the main entity (or removes it when the fallback skin is off). */
    void syncTwin() {
        ResolvableProfile fallback = plugin.fallbackProfile();
        if (!isSpawned() || fallback == null) {
            removeTwin();
            return;
        }
        Location l = entity.getLocation();
        if (twin == null || !twin.isValid() || !twin.getWorld().equals(l.getWorld())) {
            removeTwin();
            twin = spawnBody(l, fallback, false);
            twin.getEquipment().setItemInMainHand(entity.getEquipment().getItemInMainHand());
            plugin.applyVisibility(this);
            return;
        }
        Location t = twin.getLocation();
        if (t.distanceSquared(l) > 1e-6 || t.getYaw() != l.getYaw() || t.getPitch() != l.getPitch()) twin.teleport(l);
        if (twin.getBodyYaw() != entity.getBodyYaw()) twin.setBodyYaw(entity.getBodyYaw());
    }

    private void removeTwin() {
        if (twin != null && twin.isValid()) twin.remove();
        twin = null;
    }

    private void swing() {
        entity.swingMainHand();
        if (twin != null && twin.isValid()) twin.swingMainHand();
    }

    private void hurtAnimation() {
        entity.playHurtAnimation(0);
        if (twin != null && twin.isValid()) twin.playHurtAnimation(0);
    }

    /** Name, skin and the flower she carries once she likes her owner a lot. */
    void updateLook() {
        if (!isSpawned()) return;
        ItemStack hand = affection >= 70 ? new ItemStack(Material.POPPY) : null;
        entity.customName(Component.text(name, MilaPlugin.PINK));
        entity.setProfile(plugin.profile());
        entity.getEquipment().setItemInMainHand(hand);
        if (twin != null && twin.isValid()) {
            twin.customName(Component.text(name, MilaPlugin.PINK));
            ResolvableProfile fallback = plugin.fallbackProfile();
            if (fallback != null) twin.setProfile(fallback);
            twin.getEquipment().setItemInMainHand(hand);
        }
    }

    void despawnEntity() {
        closeAllDoors();
        if (entity != null && entity.isValid()) entity.remove();
        removeTwin();
        entity = null;
        pending = null;
        fightTarget = null;
    }

    void closeBagViews() {
        for (HumanEntity viewer : new ArrayList<>(bag.getViewers())) viewer.closeInventory();
    }

    // ------------------------------------------------------------------ messages

    private Component speech(String text) {
        return Component.text()
                .append(Component.text("<", NamedTextColor.GRAY))
                .append(Component.text(name, MilaPlugin.PINK))
                .append(Component.text("> ", NamedTextColor.GRAY))
                .append(Component.text(text.replace("{player}", ownerName), NamedTextColor.WHITE))
                .build();
    }

    void say(Player p, String text) {
        p.sendMessage(speech(text));
    }

    private static void hint(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    /** A hug or a kiss: everyone within 24 blocks sees it in chat. */
    private void announce(String text) {
        Component c = Component.text("❤ " + text, MilaPlugin.PINK).decorate(TextDecoration.ITALIC);
        Location at = entity.getLocation();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= 24 * 24) p.sendMessage(c);
        }
    }

    private void sound(String key, float volume, float pitch) {
        if (isSpawned()) entity.getWorld().playSound(entity.getLocation(), key, SoundCategory.NEUTRAL, volume, pitch);
    }

    private void hearts(Location at, int count) {
        at.getWorld().spawnParticle(Particle.HEART, at, count, 0.35, 0.25, 0.35, 0);
    }

    private void poof() {
        if (!isSpawned()) return;
        Location l = entity.getLocation().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.CLOUD, l, 12, 0.3, 0.5, 0.3, 0.02);
    }

    private String random(List<String> list, String fallback) {
        if (list == null || list.isEmpty()) return fallback;
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    // ------------------------------------------------------------------ commands

    void call(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) {
            hint(p, "В режиме наблюдателя её нельзя позвать.");
            return;
        }
        boolean first = !met;
        mode = Mode.FOLLOW;
        pending = null;
        if (!isSpawned()) {
            Location spot = spotNear(p);
            out = true;
            spawnEntity(spot);
            poof();
            sound("minecraft:entity.allay.ambient_with_item", 0.8f, 1.3f);
            if (first) {
                met = true;
                say(p, "Привет! Я " + name + ". Буду гулять с тобой, защищать от монстров и носить вещи в рюкзаке.");
                hint(p, "Что я умею: /mila help");
            } else {
                say(p, "Я тут, {player}!");
            }
        } else if (!entity.getWorld().equals(p.getWorld()) || entity.getLocation().distance(p.getLocation()) > s().teleportDistance()) {
            Location spot = spotNear(p);
            if (!entity.getWorld().equals(spot.getWorld())) spawnEntity(spot);
            else entity.teleport(spot);
            poof();
            say(p, "Бегу, {player}!");
        } else {
            say(p, "Иду за тобой, {player}!");
        }
        save();
    }

    void follow(Player p) {
        if (!requireOut(p)) return;
        mode = Mode.FOLLOW;
        say(p, "Иду за тобой!");
        save();
    }

    void stay(Player p) {
        if (!requireOut(p)) return;
        mode = Mode.STAY;
        Location l = entity.getLocation();
        stayWorld = l.getWorld().getName();
        stayX = l.getX();
        stayY = l.getY();
        stayZ = l.getZ();
        stayYaw = l.getYaw();
        pending = null;
        fightTarget = null;
        say(p, "Хорошо, подожду тут.");
        hint(p, "Позвать её снова: /mila");
        save();
    }

    void dismiss(Player p) {
        if (!out) {
            hint(p, name + " сейчас не с тобой. Позвать: /mila");
            return;
        }
        if (isSpawned()) {
            say(p, "Пока! Позови, если что ❤");
            poof();
            sound("minecraft:entity.allay.ambient_without_item", 0.8f, 1.1f);
        }
        despawnEntity();
        out = false;
        mode = Mode.FOLLOW;
        save();
    }

    void request(Player p, Action action) {
        if (!requireOut(p)) return;
        int need = action == Action.KISS ? s().kissAt() : s().hugAt();
        if (affection < need) {
            if (action == Action.KISS) say(p, "Давай не так быстро… Сначала погуляем вместе ❤");
            else say(p, "Мы ведь только познакомились…");
            hint(p, "Нужно отношение " + need + ", сейчас " + affection + ". Гуляйте вместе и дарите подарки: /mila info");
            return;
        }
        long now = System.currentTimeMillis();
        long ready = action == Action.KISS ? kissReadyAt : hugReadyAt;
        if (now < ready) {
            say(p, "Подожди немного ❤");
            return;
        }
        if (!entity.getWorld().equals(p.getWorld()) || entity.getLocation().distance(p.getLocation()) > 16) {
            hint(p, name + " слишком далеко. Позови её: /mila");
            return;
        }
        pending = action;
        pendingDeadline = plugin.tick() + 160;
        fightTarget = null;
    }

    private void perform(Player p, Action action) {
        pending = null;
        face(p.getEyeLocation());
        Location mid = entity.getLocation().add(p.getLocation()).multiply(0.5).add(0, 1.9, 0);
        mid.setWorld(p.getWorld());
        long now = System.currentTimeMillis();
        if (action == Action.KISS) {
            kissReadyAt = now + s().kissCooldown() * 1000L;
            hearts(p.getEyeLocation().add(0, 0.4, 0), 6);
            hearts(mid, 4);
            sound("minecraft:entity.allay.item_given", 0.9f, 1.7f);
            announce(name + " целует " + p.getName() + " в щёчку");
        } else {
            hugReadyAt = now + s().hugCooldown() * 1000L;
            hearts(mid, 5);
            sound("minecraft:entity.allay.ambient_with_item", 0.9f, 1.2f);
            announce(name + " обнимает " + p.getName());
        }
    }

    void gift(Player p, boolean fromCommand) {
        if (!requireOut(p)) return;
        if (!entity.getWorld().equals(p.getWorld()) || entity.getLocation().distance(p.getLocation()) > 6) {
            hint(p, "Подойди к ней поближе, чтобы подарить подарок.");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            hint(p, "Возьми в руку то, что хочешь подарить. Что ей нравится: цветы, сладости, НМК, торт, алмазы.");
            return;
        }
        int value = giftValue(hand);
        if (value <= 0) {
            if (fromCommand) say(p, "Это мне? Спасибо, но оставь себе.");
            return;
        }
        String today = LocalDate.now().toString();
        if (!today.equals(giftDay)) {
            giftDay = today;
            giftsToday = 0;
        }
        if (giftsToday >= s().giftsPerDay()) {
            say(p, "Спасибо, но на сегодня подарков хватит ❤");
            return;
        }
        ItemStack given = hand.clone();
        given.setAmount(1);
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else {
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(hand);
        }
        giftsToday++;
        String nmk = nmkName(given);
        if (nmk != null) {
            say(p, "*пьёт " + nmk + "* Мой любимый! Спасибо ❤");
            for (ItemStack left : p.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE)).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        } else if (isFlower(given.getType())) {
            say(p, "Какие красивые цветы! Спасибо ❤");
        } else if (given.getType() == Material.CAKE) {
            say(p, "Целый торт?! Спасибо огромное!");
        } else if (given.getType() == Material.DIAMOND) {
            say(p, "Алмаз?! Он такой красивый!");
        } else if (given.getType() == Material.GOLDEN_APPLE || given.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            say(p, "Золотое яблоко! Ничего себе!");
        } else {
            say(p, "Вкусно! Спасибо ❤");
        }
        hearts(entity.getLocation().add(0, 2.0, 0), 3);
        sound("minecraft:block.amethyst_block.chime", 1.0f, 1.4f);
        addAffection(p, value);
        save();
    }

    void openBag(Player p) {
        if (!requireOut(p)) return;
        if (!entity.getWorld().equals(p.getWorld()) || entity.getLocation().distance(p.getLocation()) > 8) {
            hint(p, "Рюкзак у неё. Подойди поближе.");
            return;
        }
        p.openInventory(bag);
        sound("minecraft:item.armor.equip_leather", 0.7f, 1.2f);
    }

    void info(Player p) {
        int full = affection / 10;
        String bar = "❤".repeat(full) + "♡".repeat(10 - full);
        String today = LocalDate.now().toString();
        int gifts = today.equals(giftDay) ? giftsToday : 0;
        p.sendMessage(Component.text(name + " — подруга " + ownerName, MilaPlugin.PINK).decorate(TextDecoration.BOLD));
        p.sendMessage(Component.text("Отношения: ", NamedTextColor.GRAY)
                .append(Component.text(bar, MilaPlugin.PINK))
                .append(Component.text(" " + affection + "/100, " + relation(), NamedTextColor.GRAY)));
        String where = !out ? "отдыхает (позвать: /mila)" : mode == Mode.STAY ? "ждёт на месте" : "ходит за тобой";
        hint(p, "Сейчас: " + where);
        hint(p, "Подарков сегодня: " + gifts + "/" + s().giftsPerDay());
        hint(p, "Обнять: от " + s().hugAt() + ", поцеловать: от " + s().kissAt()
                + ". Отношения растут, когда вы гуляете вместе (+1 за " + s().minutesPerPoint() + " мин) и от подарков.");
    }

    void rename(Player p, String newName) {
        String n = newName.trim();
        if (!n.matches("[\\p{L}\\p{N} _-]{2,16}")) {
            hint(p, "Имя: от 2 до 16 букв или цифр. Пример: /mila name Мила");
            return;
        }
        name = n;
        updateLook();
        if (isSpawned()) say(p, "Теперь меня зовут " + n + "? Мне нравится!");
        else hint(p, "Теперь её зовут " + n + ".");
        save();
    }

    private boolean requireOut(Player p) {
        if (isSpawned()) return true;
        hint(p, out ? name + " сейчас далеко. Позови её: /mila" : name + " сейчас не с тобой. Позови её: /mila");
        return false;
    }

    // ------------------------------------------------------------------ interaction events

    void clickedByOwner(Player p) {
        if (p.isSneaking()) {
            openBag(p);
            return;
        }
        if (giftValue(p.getInventory().getItemInMainHand()) > 0) {
            gift(p, false);
            return;
        }
        face(p.getEyeLocation());
        say(p, random(s().clickPhrases(), "Да, {player}?"));
        if (affection >= s().kissAt()) hearts(entity.getLocation().add(0, 2.1, 0), 1);
    }

    void clickedByStranger(Player stranger) {
        face(stranger.getEyeLocation());
        stranger.sendMessage(Component.text()
                .append(Component.text("<", NamedTextColor.GRAY))
                .append(Component.text(name, MilaPlugin.PINK))
                .append(Component.text("> ", NamedTextColor.GRAY))
                .append(Component.text("Привет! Я гуляю с " + ownerName + ".", NamedTextColor.WHITE)));
    }

    void hitByOwner(Player p) {
        long now = plugin.tick();
        if (now - lastOwnerHit < 40) return;
        lastOwnerHit = now;
        if (isSpawned()) {
            hurtAnimation();
            sound("minecraft:entity.allay.hurt", 1.0f, 1.0f);
            entity.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, entity.getLocation().add(0, 2.0, 0), 1);
        }
        say(p, "Ай! Ты чего?!");
        addAffection(p, s().ownerHit());
        save();
    }

    void hitByStranger(Player attacker) {
        if (isSpawned()) hurtAnimation();
        attacker.sendMessage(Component.text()
                .append(Component.text("<", NamedTextColor.GRAY))
                .append(Component.text(name, MilaPlugin.PINK))
                .append(Component.text("> ", NamedTextColor.GRAY))
                .append(Component.text("Эй, не надо!", NamedTextColor.WHITE)));
    }

    // ------------------------------------------------------------------ affection and gifts

    private void addAffection(Player p, int delta) {
        int old = affection;
        affection = Math.max(0, Math.min(100, affection + delta));
        for (int m : MILESTONES) {
            if (old < m && affection >= m) milestone(p, m);
        }
        if ((old >= 70) != (affection >= 70)) updateLook();
    }

    private void milestone(Player p, int m) {
        switch (m) {
            case 10 -> {
                say(p, "Кажется, мы подружились!");
                hint(p, "Теперь можно обнять её: /mila hug");
            }
            case 40 -> {
                say(p, "Ты мне очень нравишься ❤");
                hint(p, "Теперь можно поцеловать её: /mila kiss");
            }
            case 70 -> say(p, "Я так рада, что ты есть! Держи цветочек ❤");
            default -> say(p, "Ты для меня самый важный человек ❤");
        }
        if (isSpawned()) {
            hearts(entity.getLocation().add(0, 2.0, 0), 8);
            sound("minecraft:entity.player.levelup", 0.6f, 1.6f);
        }
    }

    int giftValue(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        Map<String, Integer> gifts = s().gifts();
        if (nmkName(it) != null) return gifts.getOrDefault("nmk", 0);
        Integer v = gifts.get(it.getType().getKey().getKey());
        if (v != null) return v;
        for (Map.Entry<String, Integer> e : gifts.entrySet()) {
            if (!e.getKey().startsWith("#")) continue;
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(e.getKey().substring(1)), Material.class);
            if (tag != null && tag.isTagged(it.getType())) return e.getValue();
        }
        return 0;
    }

    private static boolean isFlower(Material m) {
        Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft("flowers"), Material.class);
        return tag != null && tag.isTagged(m);
    }

    /** The server's resource pack shows these four drinkable potions as NMK milk cocktails. */
    static String nmkName(ItemStack it) {
        if (it == null || it.getType() != Material.POTION || !(it.getItemMeta() instanceof PotionMeta pm)) return null;
        PotionType t = pm.getBasePotionType();
        if (t == null) return null;
        String k = t.getKey().getKey().replaceFirst("^(long|strong)_", "");
        return switch (k) {
            case "regeneration" -> "Клубничный НМК";
            case "strength" -> "Шоколадный НМК";
            case "swiftness" -> "Кофейный НМК";
            case "invisibility" -> "Карамельный НМК";
            default -> null;
        };
    }

    private String relation() {
        if (affection >= 100) return "лучшая пара";
        if (affection >= 70) return "влюблённые";
        if (affection >= 40) return "близкие друзья";
        if (affection >= 10) return "друзья";
        return "знакомые";
    }

    // ------------------------------------------------------------------ tick

    void tick(Player p, long now) {
        if (entity != null && !entity.isValid()) {
            closeAllDoors();
            removeTwin();
            entity = null;
            pending = null;
            fightTarget = null;
        }
        if (!out) return;
        if (entity == null) {
            if (now % 20 == 0) respawn(p, now);
            return;
        }
        movedThisTick = false;
        if (entity.getFireTicks() > 0) entity.setFireTicks(0);
        Location cur = entity.getLocation();
        boolean sameWorld = cur.getWorld().equals(p.getWorld());
        double dist = sameWorld ? cur.distance(p.getLocation()) : Double.MAX_VALUE;
        boolean ownerAway = p.isDead() || p.getGameMode() == GameMode.SPECTATOR;

        if (ownerAway) {
            pending = null;
        } else if (pending != null) {
            pendingTick(p, dist, now);
        } else if (mode == Mode.FOLLOW) {
            if (dist > s().teleportDistance()) {
                bringToOwner(p, now);
            } else if (!fightTick(p, now)) {
                if (dist > s().followDistance() + 0.4) {
                    walkTowards(p.getLocation(), s().followDistance(), dist > 8 ? s().runSpeed() : s().walkSpeed());
                    checkStuck(p, dist, now);
                } else {
                    path = List.of();
                    stuckTicks = 0;
                    stuckBest = Double.MAX_VALUE;
                    if (now % 5 == 0) face(p.getEyeLocation());
                }
            }
        } else {
            Location home = stayLocation();
            if (home != null && home.getWorld().equals(cur.getWorld()) && cur.distance(home) > 0.8) {
                walkTowards(home, 0.5, s().walkSpeed());
            } else if (now % 5 == 0) {
                Player near = nearestPlayer(8);
                if (near != null) face(near.getEyeLocation());
            }
        }
        if (isSpawned() && !movedThisTick) applyGravity();
        syncTwin();
        if (now % 10 == 0) unstickIfBuried();
        closeDoorsBehind(now);
        if (sameWorld && dist <= 16 && !ownerAway) {
            if (++togetherTicks >= s().minutesPerPoint() * 1200) {
                togetherTicks = 0;
                addAffection(p, 1);
            }
            chatter(p, dist, now);
        }
    }

    private void respawn(Player p, long now) {
        if (now < spawnNotBefore || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;
        if (mode == Mode.FOLLOW) {
            spawnEntity(spotNear(p));
            poof();
            if (greetOnSpawn) {
                say(p, "Привет, {player}! Я скучала ❤");
                greetOnSpawn = false;
            }
            return;
        }
        Location home = stayLocation();
        if (home == null) {
            mode = Mode.FOLLOW;
            return;
        }
        if (home.getWorld().equals(p.getWorld()) && home.distance(p.getLocation()) < 64
                && home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) {
            spawnEntity(Body.safeSpot(home));
        }
    }

    private Location stayLocation() {
        if (stayWorld == null) return null;
        World w = Bukkit.getWorld(stayWorld);
        return w == null ? null : new Location(w, stayX, stayY, stayZ, stayYaw, 0f);
    }

    private Player nearestPlayer(double radius) {
        Player best = null;
        double bd = radius * radius;
        Location at = entity.getLocation();
        for (Player q : at.getWorld().getPlayers()) {
            if (q.getGameMode() == GameMode.SPECTATOR) continue;
            double d = q.getLocation().distanceSquared(at);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    private void chatter(Player p, double dist, long now) {
        int minutes = s().chatterMinutes();
        if (minutes <= 0) return;
        long period = minutes * 1200L;
        if (nextChatter < 0) nextChatter = now + period / 2 + ThreadLocalRandom.current().nextLong(period);
        if (now < nextChatter) return;
        nextChatter = now + period / 2 + ThreadLocalRandom.current().nextLong(period);
        if (dist <= 12 && fightTarget == null && pending == null) say(p, random(s().phrases(), "Как хорошо гулять вместе!"));
    }

    private void pendingTick(Player p, double dist, long now) {
        Action a = pending;
        if (dist <= 1.7) {
            perform(p, a);
            return;
        }
        if (now > pendingDeadline || dist > 20) {
            pending = null;
            if (dist <= 4) perform(p, a);
            else hint(p, name + " не смогла к тебе пройти. Подойди к ней поближе.");
            return;
        }
        walkTowards(p.getLocation(), 1.2, s().runSpeed());
    }

    // ------------------------------------------------------------------ fighting

    void debug(String msg) {
        if (s().debug()) plugin.getLogger().info("[" + ownerName + "] " + msg);
    }

    private static String fmt(Location l) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", l.getX(), l.getY(), l.getZ());
    }

    private boolean fightTick(Player p, long now) {
        if (!s().defendOwner()) return false;
        if (attackCooldown > 0) attackCooldown--;
        if (fightTarget == null) {
            if (now % 10 != 0) return false;
            fightTarget = findThreat(p, now);
            if (fightTarget == null) return false;
            fightProgress = now;
            debug("fight: target " + fightTarget.getType() + " at " + fmt(fightTarget.getLocation()));
        }
        LivingEntity t = fightTarget;
        if (!t.isValid() || t.isDead() || !t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > 20) {
            debug("fight: target gone (valid=" + t.isValid() + ", dead=" + t.isDead() + ")");
            fightTarget = null;
            return false;
        }
        if (now - fightProgress > 120) {           // 6 seconds without landing a hit: leave it alone for a while
            debug("fight: no hit for 6 s, giving up; me " + fmt(entity.getLocation()) + " target " + fmt(t.getLocation()));
            unreachable.put(t.getUniqueId(), now + 400);
            fightTarget = null;
            return false;
        }
        if (inReach(t)) {
            face(t.getEyeLocation());
            path = List.of();
            if (attackCooldown <= 0) {
                swing();
                t.damage(s().attackDamage(), entity);      // the server adds the usual knockback
                sound("minecraft:entity.player.attack.strong", 0.8f, 1.3f);
                attackCooldown = 15;
                fightProgress = now;
                debug("fight: hit " + t.getType() + ", health now " + String.format(Locale.ROOT, "%.1f", t.getHealth()));
            }
        } else {
            walkTowards(t.getLocation(), 1.2, s().runSpeed());
        }
        return true;
    }

    private LivingEntity findThreat(Player p, long now) {
        unreachable.values().removeIf(until -> until < now);
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        Location me = entity.getLocation();
        Map<UUID, Entity> around = new HashMap<>();
        for (Entity n : p.getNearbyEntities(10, 6, 10)) around.put(n.getUniqueId(), n);
        for (Entity n : entity.getNearbyEntities(8, 4, 8)) around.put(n.getUniqueId(), n);
        for (Entity n : around.values()) {
            if (!(n instanceof Mob m) || !(n instanceof Enemy) || n instanceof Creeper || m.isDead()) continue;
            if (unreachable.containsKey(n.getUniqueId())) continue;
            LivingEntity target = m.getTarget();
            boolean threat = p.equals(target) || entity.equals(target)
                    || (n.getUniqueId().equals(ownerTarget) && now - ownerTargetTick < 200);
            if (!threat) continue;
            double d = n.getLocation().distanceSquared(me);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    private boolean inReach(LivingEntity t) {
        Location a = entity.getLocation();
        Location b = t.getLocation();
        double horiz = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()) - t.getWidth() / 2;
        double dy = b.getY() - a.getY();
        return horiz <= 1.9 && dy > -1.5 && dy < 2.0 && entity.hasLineOfSight(t);
    }

    // ------------------------------------------------------------------ moving

    /** A free spot about 1.5 blocks behind the player, or next to them. */
    Location spotNear(Player p) {
        Location pl = p.getLocation();
        Vector back = pl.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(0, 0, 1);
        Location want = pl.clone().subtract(back.normalize().multiply(1.5));
        Location spot = Body.safeSpot(want);
        if (!Body.clear(spot) || spot.distance(pl) > 6) spot = Body.safeSpot(pl.clone());
        Vector look = pl.toVector().subtract(spot.toVector()).setY(0);
        if (look.lengthSquared() > 1e-4) spot.setDirection(look);
        spot.setPitch(0);
        return spot;
    }

    private void bringToOwner(Player p, long now) {
        if (now - lastTeleport < 40) return;
        if (p.isFlying() || p.isGliding()) return;     // wait until the owner lands
        lastTeleport = now;
        Location spot = spotNear(p);
        if (!spot.getWorld().equals(entity.getWorld())) spawnEntity(spot);
        else {
            closeAllDoors();
            entity.teleport(spot);
        }
        poof();
        path = List.of();
        idx = 0;
        fallVelocity = 0;
        stuckTicks = 0;
        stuckBest = Double.MAX_VALUE;
        movedThisTick = true;
    }

    private void checkStuck(Player p, double dist, long now) {
        if (dist < stuckBest - 1.0) {
            stuckBest = dist;
            stuckTicks = 0;
        } else if (++stuckTicks > 100 && dist > 6) {
            bringToOwner(p, now);
        }
    }

    private void walkTowards(Location goal, double stopDist, double speedPerSecond) {
        Location cur = entity.getLocation();
        if (cur.distance(goal) <= stopDist) {
            path = List.of();
            return;
        }
        replanTimer--;
        boolean need = replanTimer <= 0 || (idx >= path.size() && replanTimer <= 15);
        if (need && (Body.grounded(cur) || Body.inWater(cur))) {
            PathFinder.Result res = PathFinder.find(cur.getWorld(), cur.getBlockX(), (int) Math.floor(cur.getY() + 0.01), cur.getBlockZ(),
                    goal.getX(), goal.getY(), goal.getZ(), stopDist, s().pathNodes());
            path = res.path();
            idx = 0;
            replanTimer = 20;
        }
        if (idx < path.size()) step(cur, speedPerSecond);
    }

    private void step(Location cur, double speedPerSecond) {
        int[] w = path.get(idx);
        double tx = w[0] + 0.5;
        double ty = w[1];
        double tz = w[2] + 0.5;
        double dx = tx - cur.getX();
        double dz = tz - cur.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double speed = speedPerSecond / 20.0;
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
        entity.teleport(new Location(cur.getWorld(), nx, ny, nz, yaw, 0f));
        entity.setBodyYaw(yaw);
        entity.setVelocity(new Vector());
        movedThisTick = true;
        fallVelocity = 0;
        if (horiz <= speed && Math.abs(ny - ty) < 0.01) idx++;
    }

    private void face(Location target) {
        if (!isSpawned()) return;
        Location eye = entity.getEyeLocation();
        double dx = target.getX() - eye.getX();
        double dy = target.getY() - eye.getY();
        double dz = target.getZ() - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        entity.setRotation(yaw, pitch);
        entity.setBodyYaw(yaw);
    }

    private void applyGravity() {
        Location l = entity.getLocation();
        if (Body.inWater(l) || Body.grounded(l)) {
            fallVelocity = 0;
            return;
        }
        World w = l.getWorld();
        fallVelocity = Math.min(fallVelocity + 0.08, 3.92);
        double target = l.getY() - fallVelocity;
        double landing = Double.NaN;
        for (int y = (int) Math.floor(l.getY() - 0.05); y >= (int) Math.floor(target) && y >= w.getMinHeight(); y--) {
            Block b = w.getBlockAt(l.getBlockX(), y, l.getBlockZ());
            if (b.getType() == Material.WATER) {
                landing = Math.max(target, y);
                fallVelocity = 0;
                break;
            }
            if (!b.isPassable()) {
                double top = b.getBoundingBox().getMaxY();
                if (top >= target) landing = top;
                break;
            }
        }
        double ny = Double.isNaN(landing) ? target : landing;
        if (ny < w.getMinHeight() - 32) {             // fell out of the world: she comes back to her owner
            despawnEntity();
            return;
        }
        entity.teleport(new Location(w, l.getX(), ny, l.getZ(), l.getYaw(), l.getPitch()));
    }

    private void unstickIfBuried() {
        if (!isSpawned()) return;
        Location l = entity.getLocation();
        if (Body.clear(l)) {
            buriedChecks = 0;
            return;
        }
        if (++buriedChecks < 2) return;
        buriedChecks = 0;
        Location to = Body.safeSpot(l);
        if (Body.clear(to)) {
            entity.teleport(to);
            fallVelocity = 0;
        }
    }

    /** Opens wooden doors and fence gates on her way and remembers them, so they can be closed behind her. */
    private void openDoor(World w, int x, int y, int z) {
        for (int dy = 0; dy <= 1; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            Material m = b.getType();
            if ((Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) && b.getBlockData() instanceof Openable o && !o.isOpen()) {
                o.setOpen(true);
                b.setBlockData(o, true);
                w.playSound(b.getLocation(), Tag.FENCE_GATES.isTagged(m) ? "minecraft:block.fence_gate.open" : "minecraft:block.wooden_door.open",
                        SoundCategory.BLOCKS, 0.8f, 1.0f);
                openedDoors.put(b, plugin.tick());
                return;
            }
        }
    }

    private void closeDoorsBehind(long now) {
        if (openedDoors.isEmpty() || now % 10 != 0) return;
        Location me = entity == null ? null : entity.getLocation();
        Iterator<Map.Entry<Block, Long>> it = openedDoors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Long> e = it.next();
            Block b = e.getKey();
            boolean away = me == null || !me.getWorld().equals(b.getWorld()) || me.distanceSquared(b.getLocation().add(0.5, 0, 0.5)) > 2.5 * 2.5;
            if (away && now - e.getValue() > 20) {
                close(b);
                it.remove();
            }
        }
    }

    private void closeAllDoors() {
        for (Block b : openedDoors.keySet()) close(b);
        openedDoors.clear();
    }

    private static void close(Block b) {
        Material m = b.getType();
        if ((Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) && b.getBlockData() instanceof Openable o && o.isOpen()) {
            o.setOpen(false);
            b.setBlockData(o, true);
            b.getWorld().playSound(b.getLocation(), Tag.FENCE_GATES.isTagged(m) ? "minecraft:block.fence_gate.close" : "minecraft:block.wooden_door.close",
                    SoundCategory.BLOCKS, 0.8f, 1.0f);
        }
    }
}
