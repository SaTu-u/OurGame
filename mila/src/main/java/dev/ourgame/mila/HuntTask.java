package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /mila hunt and /mila guard. Hunting goes after one kind of mob (or any monster) near her until enough are killed;
 * guarding keeps a spot clear of monsters until /mila stop. Drops go into her bag (MilaPlugin.onDeath).
 */
final class HuntTask extends Task {

    /** Farm animals she may hunt; pets, villagers, golems and players never. */
    static final Set<EntityType> GAME = Set.of(EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP,
            EntityType.RABBIT, EntityType.MOOSHROOM, EntityType.GOAT);

    static final Map<String, EntityType> NAMES = new HashMap<>();

    static {
        NAMES.put("корова", EntityType.COW);
        NAMES.put("коровы", EntityType.COW);
        NAMES.put("свинья", EntityType.PIG);
        NAMES.put("свиньи", EntityType.PIG);
        NAMES.put("курица", EntityType.CHICKEN);
        NAMES.put("курицы", EntityType.CHICKEN);
        NAMES.put("овца", EntityType.SHEEP);
        NAMES.put("овцы", EntityType.SHEEP);
        NAMES.put("кролик", EntityType.RABBIT);
        NAMES.put("кролики", EntityType.RABBIT);
        NAMES.put("зомби", EntityType.ZOMBIE);
        NAMES.put("скелет", EntityType.SKELETON);
        NAMES.put("скелеты", EntityType.SKELETON);
        NAMES.put("паук", EntityType.SPIDER);
        NAMES.put("пауки", EntityType.SPIDER);
        NAMES.put("крипер", EntityType.CREEPER);
        NAMES.put("криперы", EntityType.CREEPER);
        NAMES.put("слизень", EntityType.SLIME);
        NAMES.put("ведьма", EntityType.WITCH);
        NAMES.put("эндермен", EntityType.ENDERMAN);
        NAMES.put("утопленник", EntityType.DROWNED);
        NAMES.put("кадавр", EntityType.HUSK);
        NAMES.put("бродяга", EntityType.STRAY);
        NAMES.put("фантом", EntityType.PHANTOM);
    }

    /** null type = any monster except creepers. */
    private final EntityType type;
    private final int amount;
    private final Location chest;
    private final boolean guard;
    private final Location center;
    private final int radius;
    private LivingEntity target;
    private int cooldown;
    private int kills;
    private long noTargetSince = -1;
    private long chaseSince;
    private final Map<UUID, Long> giveUp = new HashMap<>();
    private Deliver deliver;

    HuntTask(Companion c, String title, EntityType type, int amount, Location chest, boolean guard, int radius) {
        super(c, title);
        this.type = type;
        this.amount = amount;
        this.chest = chest;
        this.guard = guard;
        this.center = c.entity.getLocation();
        this.radius = radius;
    }

    @Override
    String startMessage() {
        if (guard) return "Охраняю это место! Ни один монстр в радиусе " + radius + " блоков не пройдёт.";
        return "Иду на охоту!" + (chest != null ? " Добычу принесу в сундук." : " Добычу сложу в рюкзак.");
    }

    @Override
    String progress() {
        return guard ? "убито монстров: " + kills : "добыто " + kills + " из " + amount;
    }

    /** Called from the death event when she landed the last hit. */
    void killed(LivingEntity e) {
        kills++;
        if (target != null && target.getUniqueId().equals(e.getUniqueId())) target = null;
    }

    @Override
    boolean tick(Player owner, long now) {
        if (deliver != null) {
            int r = deliver.tick(owner);
            if (r == Navigator.MOVING) return false;
            Deliver d = deliver;
            deliver = null;
            c.say(owner, "Положила добычу в сундук: ", Companion.summary(d.stored), ".");
            return !guard || r == Navigator.STUCK;
        }
        if (!guard && kills >= amount) return finish(owner);
        if (c.freeSlots() < 2 && chest != null) {
            deliver = new Deliver(chest, Mining::isEquipment, false);
            return false;
        }
        if (cooldown > 0) cooldown--;
        if (target != null && (!target.isValid() || target.isDead() || !allowed(target))) target = null;
        if (target == null && now % 10 == 0) {
            target = find(now);
            if (target != null) {
                chaseSince = now;
                c.nav.reset();
            }
        }
        if (target == null) {
            if (noTargetSince < 0) noTargetSince = now;
            if (!guard && now - noTargetSince > 200) return finish(owner);   // nobody left for 10 s
            Location at = c.entity.getLocation();
            if (guard && at.distance(center) > 2.5) c.nav.go(owner, DigPathFinder.near(center.getX(), center.getY(), center.getZ(), 1.5), speed(), false, false);
            return false;
        }
        noTargetSince = -1;
        if (inReach(target)) {
            c.face(target.getEyeLocation());
            if (cooldown <= 0) {
                int slot = Mining.bestWeapon(c.bag);
                ItemStack weapon = slot < 0 ? null : c.bag.getItem(slot);
                c.hold(weapon == null ? null : weapon.clone());
                c.swing();
                double dmg = Math.max(c.settings().attackDamage(), Mining.attackDamage(weapon));
                target.damage(dmg, c.entity);
                c.sound("minecraft:entity.player.attack.strong", 0.8f, 1.2f);
                if (slot >= 0) Mining.damage(c.bag, slot);
                cooldown = Mining.attackCooldown(weapon);
                chaseSince = now;
            }
            return false;
        }
        if (now - chaseSince > 200) {                  // could not catch it for 10 s
            giveUp.put(target.getUniqueId(), now + 1200);
            target = null;
            return false;
        }
        int r = c.nav.go(owner, DigPathFinder.near(target.getX(), target.getY(), target.getZ(), 1.5), c.settings().runSpeed(), false, true);
        if (r == Navigator.STUCK) {
            giveUp.put(target.getUniqueId(), now + 1200);
            target = null;
        }
        return false;
    }

    private boolean finish(Player owner) {
        if (chest != null && c.count(it -> !Mining.isEquipment(it)) > 0) {
            deliver = new Deliver(chest, Mining::isEquipment, false);
            return false;
        }
        c.say(owner, kills > 0 ? "Охота окончена, добыто: " + kills + "." : "Никого подходящего рядом нет.");
        return true;
    }

    private boolean inReach(LivingEntity t) {
        Location a = c.entity.getLocation();
        Location b = t.getLocation();
        double horiz = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()) - t.getWidth() / 2;
        double dy = b.getY() - a.getY();
        return horiz <= 2.0 && dy > -1.5 && dy < 2.2 && c.entity.hasLineOfSight(t);
    }

    private LivingEntity find(long now) {
        giveUp.values().removeIf(until -> until < now);
        Location from = guard ? center : c.entity.getLocation();
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : from.getWorld().getNearbyEntities(from, radius, 8, radius)) {
            if (!(e instanceof LivingEntity le) || !allowed(le) || giveUp.containsKey(e.getUniqueId())) continue;
            if (guard && e.getLocation().distance(center) > radius) continue;
            double d = e.getLocation().distanceSquared(c.entity.getLocation());
            if (d < bd) {
                bd = d;
                best = le;
            }
        }
        return best;
    }

    private boolean allowed(LivingEntity e) {
        if (!(e instanceof Mob) || e.isDead()) return false;
        if (type == null) return e instanceof Enemy && !(e instanceof Creeper);
        if (e.getType() != type) return false;
        if (e instanceof Enemy) return true;
        // farm animals: adults only, no pets, no named or leashed ones, and two adults stay for breeding
        if (!GAME.contains(e.getType())) return false;
        if (e.customName() != null || ((Mob) e).isLeashed()) return false;
        if (e instanceof Tameable t && t.isTamed()) return false;
        if (e instanceof Ageable a && !a.isAdult()) return false;
        int adults = 0;
        for (Entity n : e.getNearbyEntities(16, 8, 16)) {
            if (n.getType() == type && n instanceof Ageable a && a.isAdult() && !n.isDead()) adults++;
        }
        return adults >= 2;                                // she leaves at least two others
    }

    static String typeName(EntityType t) {
        if (t == null) return "монстров";
        for (Map.Entry<String, EntityType> e : NAMES.entrySet()) if (e.getValue() == t) return e.getKey();
        return t.getKey().getKey().toLowerCase(Locale.ROOT);
    }
}
