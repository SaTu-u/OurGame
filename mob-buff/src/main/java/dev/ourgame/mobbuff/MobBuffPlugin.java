package dev.ourgame.mobbuff;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.Zoglin;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stronger hostile mobs. Health, damage and aggro range are attribute modifiers (saved with the mob, so they survive
 * restarts and are updated when the config changes). Melee reach and attack speed are not attributes in Minecraft, so a
 * small task lets melee mobs hit a target that is slightly farther away, and more often than the vanilla 20 ticks.
 */
public final class MobBuffPlugin extends JavaPlugin implements Listener {

    /** Vanilla melee reach added to the attacker's hitbox (Mob.DEFAULT_ATTACK_REACH = sqrt(2.04) - 0.6). */
    private static final double VANILLA_REACH = Math.sqrt(2.04) - 0.6;

    private NamespacedKey healthKey;
    private NamespacedKey damageKey;
    private NamespacedKey followKey;
    private final Map<UUID, Integer> lastAttack = new HashMap<>();

    private boolean enabled;
    private double health;
    private double damage;
    private double follow;
    private double extraReach;
    private int interval;
    private boolean bosses;
    private boolean debug;

    @Override
    public void onEnable() {
        healthKey = new NamespacedKey(this, "health");
        damageKey = new NamespacedKey(this, "damage");
        followKey = new NamespacedKey(this, "follow_range");
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        applyToLoaded();
        getServer().getScheduler().runTaskTimer(this, this::meleeTick, 20L, 1L);
        getLogger().info(summary());
    }

    private void loadSettings() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        health = Math.max(0.1, getConfig().getDouble("health-multiplier", 1.5));
        damage = Math.max(0.0, getConfig().getDouble("damage-multiplier", 1.25));
        follow = Math.max(0.1, getConfig().getDouble("follow-range-multiplier", 1.25));
        extraReach = Math.max(0.0, Math.min(3.0, getConfig().getDouble("extra-melee-reach", 0.5)));
        interval = Math.max(5, getConfig().getInt("attack-interval-ticks", 16));
        bosses = getConfig().getBoolean("include-bosses", false);
        debug = getConfig().getBoolean("debug", false);
    }

    private String summary() {
        if (!enabled) return "MobBuff is disabled: modifiers are removed from mobs as they load";
        return String.format(java.util.Locale.ROOT, "hostile mobs: health x%.2f, damage x%.2f, aggro range x%.2f, melee reach +%.1f, attack every %d ticks",
                health, damage, follow, extraReach, interval);
    }

    // ------------------------------------------------------------------ attributes

    private boolean targeted(Entity e) {
        if (!(e instanceof Mob) || !(e instanceof Enemy)) return false;
        return bosses || !(e instanceof Boss);
    }

    /** Adds, updates or (when disabled) removes the modifiers; keeps the health ratio when max health changes. */
    private void apply(LivingEntity e, boolean fresh) {
        AttributeInstance maxHealth = e.getAttribute(Attribute.MAX_HEALTH);
        double oldMax = maxHealth == null ? 0 : maxHealth.getValue();
        double ratio = oldMax > 0 ? e.getHealth() / oldMax : 1.0;
        set(e, Attribute.MAX_HEALTH, healthKey, enabled ? health : 1.0);
        set(e, Attribute.ATTACK_DAMAGE, damageKey, enabled ? damage : 1.0);
        set(e, Attribute.FOLLOW_RANGE, followKey, enabled ? follow : 1.0);
        if (maxHealth != null) {
            double newMax = maxHealth.getValue();
            if (fresh) e.setHealth(newMax);
            else if (Math.abs(newMax - oldMax) > 1e-6) e.setHealth(Math.max(0.5, Math.min(newMax, ratio * newMax)));
        }
    }

    private static void set(LivingEntity e, Attribute attribute, NamespacedKey key, double multiplier) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier current = inst.getModifier(key);
        double amount = multiplier - 1.0;
        if (current != null && Math.abs(current.getAmount() - amount) < 1e-9) return;
        if (current != null) inst.removeModifier(key);
        if (Math.abs(amount) > 1e-9) inst.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_SCALAR));
    }

    private void applyToLoaded() {
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) if (targeted(e)) apply(e, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (targeted(event.getEntity())) apply((LivingEntity) event.getEntity(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) if (targeted(e)) apply((LivingEntity) e, false);
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        lastAttack.remove(event.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ melee reach and speed

    /** Melee mobs only: ranged ones (skeletons, witches, pillagers, piglins with crossbows, drowned with tridents) shoot. */
    private static boolean melee(Mob m) {
        if (m instanceof Drowned d) return d.getEquipment().getItemInMainHand().getType() != Material.TRIDENT;
        if (m instanceof Piglin p) return p.getEquipment().getItemInMainHand().getType() != Material.CROSSBOW;
        return m instanceof Zombie || m instanceof Spider || m instanceof Enderman || m instanceof Vindicator
                || m instanceof PiglinBrute || m instanceof Silverfish || m instanceof Endermite || m instanceof Zoglin
                || m instanceof Hoglin;
    }

    /** Remembers every melee hit (vanilla ones too) so the next extra hit waits the configured interval. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !(event.getDamager() instanceof Mob m)) return;
        if (!targeted(m)) return;
        lastAttack.put(m.getUniqueId(), Bukkit.getCurrentTick());
        if (debug) {
            getLogger().info(String.format(java.util.Locale.ROOT, "tick %d: %s hit %s for %.2f", Bukkit.getCurrentTick(),
                    m.getType(), event.getEntity().getType(), event.getFinalDamage()));
        }
    }

    private void meleeTick() {
        if (!enabled || (extraReach <= 0 && interval >= 20)) return;
        int now = Bukkit.getCurrentTick();
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                LivingEntity target = m.getTarget();
                if (target == null || !target.isValid() || target.isDead() || !target.getWorld().equals(w)) continue;
                if (!targeted(m) || !melee(m) || !m.hasAI() || !m.isAware()) continue;
                if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) continue;
                Integer last = lastAttack.get(m.getUniqueId());
                if (last != null && now - last < interval) continue;
                double reach = VANILLA_REACH + extraReach;
                BoundingBox attack = m.getBoundingBox().expand(reach, 0, reach);
                if (!attack.overlaps(target.getBoundingBox())) continue;
                if (!m.hasLineOfSight(target)) continue;
                lastAttack.put(m.getUniqueId(), now);
                m.swingMainHand();
                m.attack(target);
            }
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            applyToLoaded();
            sender.sendMessage("MobBuff reloaded: " + summary());
            return true;
        }
        sender.sendMessage("MobBuff: " + summary() + ". Use /mobbuff reload after editing config.yml");
        return true;
    }
}
