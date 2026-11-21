package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /mila fish: finds open water nearby, stands on the shore and fishes with a rod from her bag.
 * The catch comes from the game's own fishing loot table; Lure and Luck of the Sea on the rod work.
 */
final class FishTask extends Task {

    private final int amount;
    private final Location chest;
    private Block water;
    private boolean cast;
    private long biteAt;
    private int caught;
    private int fails;
    private Deliver deliver;
    private final List<Block> badWater = new ArrayList<>();

    FishTask(Companion c, int amount, Location chest) {
        super(c, "рыбачит");
        this.amount = amount;
        this.chest = chest;
    }

    @Override
    String startMessage() {
        return "Иду рыбачить! Поймаю " + amount + (chest != null ? " и принесу в сундук." : " и сложу в рюкзак.");
    }

    @Override
    String progress() {
        return "поймано " + caught + " из " + amount;
    }

    private int rodSlot() {
        for (int i = 0; i < c.bag.getSize(); i++) {
            ItemStack it = c.bag.getItem(i);
            if (it != null && it.getType() == Material.FISHING_ROD) return i;
        }
        return -1;
    }

    @Override
    boolean tick(Player owner, long now) {
        if (deliver != null) {
            int r = deliver.tick(owner);
            if (r == Navigator.MOVING) return false;
            Deliver d = deliver;
            deliver = null;
            c.say(owner, "Отнесла улов в сундук: ", Companion.summary(d.stored), ".");
            return true;
        }
        if (caught >= amount || c.freeSlots() < 1) return finish(owner);
        int slot = rodSlot();
        if (slot < 0) {
            c.say(owner, caught == 0 ? "У меня нет удочки. Положи её в мой рюкзак: /mila bag" : "Удочка кончилась.");
            return caught == 0 || finish(owner);
        }
        if (water == null) {
            water = findWater();
            cast = false;
            if (water == null) {
                c.say(owner, "Рядом нет подходящей воды. Отведи меня к пруду или речке.");
                return caught == 0 || finish(owner);
            }
            c.nav.reset();
        }
        if (!cast) {
            int r = c.nav.go(owner, shore(water), speed(), false, false);
            if (r == Navigator.STUCK) {
                badWater.add(water);
                water = null;
                if (++fails > 5) {
                    c.say(owner, "Не могу подойти к воде.");
                    return caught == 0 || finish(owner);
                }
                return false;
            }
            if (r == Navigator.MOVING) return false;
            ItemStack rod = c.bag.getItem(slot);
            c.hold(rod.clone());
            c.face(water.getLocation().add(0.5, 1, 0.5));
            c.swing();
            c.sound("minecraft:entity.fishing_bobber.throw", 0.8f, 0.6f);
            int lure = rod.getEnchantmentLevel(Enchantment.LURE);
            biteAt = now + Math.max(40, ThreadLocalRandom.current().nextInt(100, 600) - lure * 100);
            cast = true;
            return false;
        }
        Location bobber = water.getLocation().add(0.5, 1.0, 0.5);
        World w = bobber.getWorld();
        if (now % 10 == 0) w.spawnParticle(Particle.BUBBLE_POP, bobber, 1, 0.05, 0, 0.05, 0);
        if (now < biteAt) {
            if (now % 20 == 0) c.face(bobber);
            return false;
        }
        // a bite: reel it in
        w.spawnParticle(Particle.SPLASH, bobber, 12, 0.2, 0.05, 0.2, 0);
        c.sound("minecraft:entity.fishing_bobber.splash", 0.6f, 1.2f);
        c.swing();
        c.sound("minecraft:entity.fishing_bobber.retrieve", 0.8f, 1.0f);
        ItemStack rod = c.bag.getItem(slot);
        List<ItemStack> loot = loot(owner, bobber, rod);
        for (ItemStack l : c.addToBag(loot)) w.dropItemNaturally(c.entity.getLocation(), l);
        caught += loot.stream().mapToInt(ItemStack::getAmount).sum();
        if (!Mining.damage(c.bag, slot)) c.say(owner, "Ой, удочка сломалась!");
        owner.giveExp(ThreadLocalRandom.current().nextInt(1, 7));
        cast = false;
        return false;
    }

    private boolean finish(Player owner) {
        if (chest != null && c.count(it -> !Mining.isEquipment(it)) > 0) {
            deliver = new Deliver(chest, Mining::isEquipment, false);
            return false;
        }
        c.say(owner, "Порыбачила! Поймано: " + caught + ".");
        return true;
    }

    private static List<ItemStack> loot(Player owner, Location at, ItemStack rod) {
        int luck = rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA);
        try {
            LootContext ctx = new LootContext.Builder(at).luck(luck).killer(owner).build();
            Collection<ItemStack> items = LootTables.FISHING.getLootTable().populateLoot(new Random(), ctx);
            if (!items.isEmpty()) return new ArrayList<>(items);
        } catch (RuntimeException ignored) {
            // the loot table wants context the API cannot give: fall back to plain fish
        }
        int r = ThreadLocalRandom.current().nextInt(100);
        Material fish = r < 60 ? Material.COD : r < 85 ? Material.SALMON : r < 98 ? Material.PUFFERFISH : Material.TROPICAL_FISH;
        return new ArrayList<>(List.of(new ItemStack(fish)));
    }

    /** Still water with air above and water around it (open water), nearest first, within 16 blocks. */
    private Block findWater() {
        Location me = c.entity.getLocation();
        World w = me.getWorld();
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = -6; y <= 3; y++) {
                    Block b = PathFinder.block(w, me.getBlockX() + x, me.getBlockY() + y, me.getBlockZ() + z);
                    if (b == null || b.getType() != Material.WATER || badWater.contains(b)) continue;
                    if (!b.getRelative(0, 1, 0).getType().isAir()) continue;
                    int around = 0;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (b.getRelative(dx, 0, dz).getType() == Material.WATER) around++;
                    if (around < 6) continue;
                    double d = b.getLocation().distanceSquared(me);
                    if (d < bd) {
                        bd = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    /** A dry spot within 4 blocks of the water, at about the same height. */
    private static DigPathFinder.Goal shore(Block water) {
        int wx = water.getX(), wy = water.getY(), wz = water.getZ();
        World w = water.getWorld();
        return new DigPathFinder.Goal() {
            @Override
            public boolean reached(int x, int y, int z) {
                double d = Math.hypot(x - wx, z - wz);
                if (d > 4 || d < 1 || y < wy || y > wy + 3) return false;
                Block feet = PathFinder.block(w, x, y, z);
                return feet != null && feet.getType() != Material.WATER;
            }

            @Override
            public double h(int x, int y, int z) {
                return Math.max(0, Math.hypot(x - wx, z - wz) - 3) + Math.abs(y - (wy + 1)) * 0.5;
            }
        };
    }
}
