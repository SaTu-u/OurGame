package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * /mila farm: harvests ripe crops around the spot where it was started, plants the seeds back, sows empty farmland,
 * and carries the harvest to the chest. What counts as ripe follows Baritone's FarmProcess
 * (https://github.com/cabaletta/baritone, LGPL-3.0). Runs until /mila stop.
 */
final class FarmTask extends Task {

    /** Crop block -> what is planted to grow it again. */
    static final Map<Material, Material> SEED = new EnumMap<>(Material.class);

    static {
        SEED.put(Material.WHEAT, Material.WHEAT_SEEDS);
        SEED.put(Material.CARROTS, Material.CARROT);
        SEED.put(Material.POTATOES, Material.POTATO);
        SEED.put(Material.BEETROOTS, Material.BEETROOT_SEEDS);
        SEED.put(Material.NETHER_WART, Material.NETHER_WART);
        SEED.put(Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS);
    }

    private static final Set<Material> PLANTABLE = Set.of(Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO,
            Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.MELON_SEEDS, Material.PUMPKIN_SEEDS);

    private final Location center;
    private final int radius;
    private final Location chest;
    private Block target;
    private boolean planting;
    private long idleUntil;
    private long idleSince = -1;
    private int harvested;
    private int planted;
    private Deliver deliver;
    private final Set<Block> skip = new HashSet<>();

    FarmTask(Companion c, int radius, Location chest) {
        super(c, "работает на ферме");
        this.center = c.entity.getLocation();
        this.radius = radius;
        this.chest = chest;
    }

    @Override
    String startMessage() {
        return "Иду на ферму! Буду собирать урожай в радиусе " + radius + " блоков, сажать заново и носить в сундук.";
    }

    @Override
    String progress() {
        return "собрано " + harvested + ", посажено " + planted;
    }

    /** Seeds stay in the bag for planting (up to a stack of each); tools too. */
    private boolean keep(ItemStack it) {
        if (Mining.isEquipment(it)) return true;
        return PLANTABLE.contains(it.getType()) && c.count(x -> x.getType() == it.getType()) <= 64;
    }

    @Override
    boolean tick(Player owner, long now) {
        if (deliver != null) {
            int r = deliver.tick(owner);
            if (r == Navigator.MOVING) return false;
            Deliver d = deliver;
            deliver = null;
            if (r == Navigator.STUCK) return true;
            c.say(owner, "Отнесла урожай в сундук: ", Companion.summary(d.stored), ".");
            if (d.chestFull) {
                c.say(owner, "Сундук полон, остановилась.");
                return true;
            }
            c.nav.reset();
            return false;
        }
        int produce = c.count(it -> !keep(it));
        boolean idleLong = idleSince >= 0 && now - idleSince > 600;
        if (c.freeSlots() < 3 || produce >= 128 || (idleLong && produce > 0)) {
            deliver = new Deliver(chest, this::keep, false);
            idleSince = -1;
            return false;
        }
        if (target == null) {
            if (now < idleUntil) {
                if (now % 20 == 0) c.face(center.clone().add(0, 1, 0));
                return false;
            }
            findWork();
            if (target == null) {
                idleUntil = now + 100;                  // nothing ripe: look again in 5 seconds
                if (idleSince < 0) idleSince = now;
                Location at = c.entity.getLocation();
                if (at.getWorld().equals(center.getWorld()) && at.distance(center) > 3) {
                    c.nav.go(owner, DigPathFinder.near(center.getX(), center.getY(), center.getZ(), 2), speed(), false, false);
                }
                return false;
            }
            idleSince = -1;
            c.nav.reset();
        }
        if (!stillNeeded(target)) {
            target = null;
            return false;
        }
        int r = c.nav.go(owner, DigPathFinder.withinReach(target, 4.0), speed(), false, false);
        if (r == Navigator.STUCK) {
            skip.add(target);
            target = null;
            return false;
        }
        if (r == Navigator.MOVING) return false;
        Block t = target;
        if (planting) {
            target = null;
            plant(owner, t);
            return false;
        }
        Material crop = t.getType();
        int b = c.breakTick(owner, t, true);
        if (b == Companion.BUSY) return false;
        target = null;
        if (b == Companion.FAILED) {
            skip.add(t);
            return false;
        }
        harvested++;
        Material seed = SEED.get(crop);
        if (seed != null && t.getType().isAir()) {
            plantSeed(owner, t, crop, seed);
        } else if (crop == Material.COCOA) {
            replantCocoa(owner, t);
        }
        c.pickUp(3, it -> true);
        return false;
    }

    private boolean stillNeeded(Block b) {
        if (skip.contains(b) || c.forbidden.contains(b)) return false;
        return planting ? emptyBed(b) != null : ripe(b);
    }

    private void findWork() {
        World w = center.getWorld();
        Location me = c.entity.getLocation();
        Block best = null;
        boolean bestPlant = false;
        double bd = Double.MAX_VALUE;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = cy - 3; y <= cy + 3; y++) {
                    Block b = PathFinder.block(w, x, y, z);
                    if (b == null || skip.contains(b)) continue;
                    boolean plant = false;
                    if (!ripe(b)) {
                        if (emptyBed(b) == null) continue;
                        plant = true;
                    }
                    double d = b.getLocation().distanceSquared(me) + (plant ? 4 : 0);
                    if (d < bd) {
                        bd = d;
                        best = b;
                        bestPlant = plant;
                    }
                }
            }
        }
        target = best;
        planting = bestPlant;
    }

    /** Baritone's FarmProcess rules: fully grown crops; pumpkins and melons grown from a stem; cane, bamboo, cactus above the base. */
    static boolean ripe(Block b) {
        Material m = b.getType();
        if (SEED.containsKey(m) || m == Material.COCOA) {
            return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
        }
        if (m == Material.PUMPKIN || m == Material.MELON) {
            Material stem = m == Material.PUMPKIN ? Material.ATTACHED_PUMPKIN_STEM : Material.ATTACHED_MELON_STEM;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = b.getRelative(f);
                if (n.getType() == stem && n.getBlockData() instanceof Directional d && d.getFacing() == f.getOppositeFace()) return true;
            }
            return false;
        }
        if (m == Material.SUGAR_CANE || m == Material.BAMBOO || m == Material.CACTUS) {
            return b.getRelative(BlockFace.DOWN).getType() == m;
        }
        return false;
    }

    /** For empty farmland (or soul sand by nether wart): the seed she would plant there, if she has one. */
    private Material emptyBed(Block air) {
        if (!air.getType().isAir()) return null;
        Material below = air.getRelative(BlockFace.DOWN).getType();
        if (below == Material.FARMLAND) {
            Material best = null;
            int most = 0;
            for (Material s : new Material[]{Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT_SEEDS}) {
                int n = c.count(it -> it.getType() == s);
                if (n > most) {
                    most = n;
                    best = s;
                }
            }
            return best;
        }
        if (below == Material.SOUL_SAND && c.count(it -> it.getType() == Material.NETHER_WART) > 0) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (air.getRelative(f).getType() == Material.NETHER_WART) return Material.NETHER_WART;
            }
        }
        return null;
    }

    private void plant(Player owner, Block air) {
        Material seed = emptyBed(air);
        if (seed == null) return;
        Material crop = null;
        for (Map.Entry<Material, Material> e : SEED.entrySet()) if (e.getValue() == seed) crop = e.getKey();
        if (crop != null) plantSeed(owner, air, crop, seed);
    }

    private void plantSeed(Player owner, Block air, Material crop, Material seed) {
        if (c.count(it -> it.getType() == seed) <= 0) return;
        Material below = air.getRelative(BlockFace.DOWN).getType();
        if (crop == Material.NETHER_WART ? below != Material.SOUL_SAND : below != Material.FARMLAND) return;
        c.face(air.getLocation().add(0.5, 0.2, 0.5));
        c.swing();
        if (c.place(owner, air, air.getRelative(BlockFace.DOWN), crop)) {
            c.take(it -> it.getType() == seed, 1);
            planted++;
        } else {
            skip.add(air);
        }
    }

    private void replantCocoa(Player owner, Block spot) {
        if (c.count(it -> it.getType() == Material.COCOA_BEANS) <= 0) return;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block log = spot.getRelative(f);
            if (log.getType() != Material.JUNGLE_LOG) continue;
            if (c.place(owner, spot, log, Material.COCOA)) {
                if (spot.getBlockData() instanceof Directional d) {
                    d.setFacing(f);
                    spot.setBlockData(d);
                }
                c.take(it -> it.getType() == Material.COCOA_BEANS, 1);
                planted++;
            }
            return;
        }
    }
}
