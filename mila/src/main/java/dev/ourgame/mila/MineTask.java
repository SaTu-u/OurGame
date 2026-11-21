package dev.ourgame.mila;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /mila mine and /mila chop: finds the resource in the loaded chunks around her (scanned off the main thread),
 * digs her way to it, mines the whole vein, and carries the loot to the chest when her bag is full or she has enough.
 */
final class MineTask extends Task {

    private static final int SCAN_CHUNKS = 3;           // 7 x 7 chunks around her
    private static final int MAX_TARGET_FAILS = 12;

    private final Set<Material> blocks;
    private final String what;                          // "железа" (genitive), for messages
    private final String label;                         // "железо"
    private final int amount;
    private final Location chest;
    private final boolean trees;
    private final Location anchor;

    private final Deque<Block> queue = new ArrayDeque<>();
    private final Set<Block> skip = new HashSet<>();
    private final Set<Long> naturalTrunks = new HashSet<>();
    private Block target;
    private boolean scanning;
    private boolean scannedOnce;
    private int fails;
    private int got;
    private Deliver deliver;
    private boolean finishing;
    private long lastPickup;

    MineTask(Companion c, String title, String label, String what, Set<Material> blocks, int amount, Location chest, boolean trees) {
        super(c, title);
        this.label = label;
        this.what = what;
        this.blocks = blocks;
        this.amount = amount;
        this.chest = chest;
        this.trees = trees;
        this.anchor = c.entity.getLocation();
    }

    @Override
    String startMessage() {
        return trees ? "Иду рубить деревья! Принесу в сундук " + amount + " шт." : "Иду добывать: " + label + "! Принесу в сундук " + amount + " шт.";
    }

    @Override
    String progress() {
        return "добыто " + got + " из " + amount;
    }

    private boolean wanted(ItemStack it) {
        return !Mining.isEquipment(it);
    }

    @Override
    boolean tick(Player owner, long now) {
        if (deliver != null) {
            int r = deliver.tick(owner);
            if (r == Navigator.MOVING) return false;
            Deliver d = deliver;
            deliver = null;
            if (r == Navigator.STUCK) return true;
            c.say(owner, "Положила в сундук: ", Companion.summary(d.stored), ".");
            if (d.chestFull) {
                c.say(owner, "Сундук полон, остальное у меня в рюкзаке.");
                return true;
            }
            if (finishing || got >= amount) {
                c.say(owner, trees ? "Готово, дерево в сундуке!" : "Готово! Добыла " + got + " " + what + ".");
                return true;
            }
            c.nav.reset();
            return false;
        }
        if (got >= amount || c.freeSlots() < 2) {
            deliver = new Deliver(chest, Mining::isEquipment, !trees);
            return false;
        }
        if (trees && now - lastPickup > 40) {          // saplings, sticks and apples falling from the leaves
            lastPickup = now;
            c.pickUp(5, it -> Tag.SAPLINGS.isTagged(it.getType()) || it.getType() == Material.STICK || it.getType() == Material.APPLE
                    || blocks.contains(it.getType()));
        }
        if (target == null) {
            target = next(owner);
            if (target == null) {
                if (!scanning && scannedOnce && queue.isEmpty()) return giveUp(owner, "рядом больше нет " + what);
                return false;
            }
            c.nav.reset();
        }
        if (!valid(target)) {
            target = null;
            return false;
        }
        DigPathFinder.Goal goal = trees ? DigPathFinder.withinReach(target, 4.0) : DigPathFinder.nextTo(target);
        int r = c.nav.go(owner, goal, speed(), !trees, false);
        if (r == Navigator.STUCK) {
            skip.add(target);
            target = null;
            if (++fails > MAX_TARGET_FAILS) return giveUp(owner, "не могу добраться до " + what);
            return false;
        }
        if (r == Navigator.MOVING) return false;
        int b = c.breakTick(owner, target, true);
        if (b == Companion.BUSY) return false;
        Block done = target;
        target = null;
        if (b == Companion.FAILED) {
            skip.add(done);
            if (c.forbidden.contains(done)) c.say(owner, "Здесь мне нельзя копать.");
            return false;
        }
        fails = 0;
        for (ItemStack it : c.lastDrops) if (wanted(it)) got += it.getAmount();
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = done.getRelative(f);                // the rest of the vein (or trunk) first
            if (blocks.contains(n.getType()) && !skip.contains(n)) queue.addFirst(n);
        }
        if (trees) replant(owner, done);
        return false;
    }

    private boolean giveUp(Player owner, String why) {
        if (got > 0 || c.count(this::wanted) > 0) {
            c.say(owner, "Больше не нахожу: " + why + ". Несу, что добыла.");
            finishing = true;
            deliver = new Deliver(chest, Mining::isEquipment, !trees);
            return false;
        }
        c.say(owner, "Не получилось: " + why + ". Отведи меня в другое место.");
        return true;
    }

    private boolean valid(Block b) {
        if (!blocks.contains(b.getType()) || skip.contains(b) || c.forbidden.contains(b)) return false;
        if (b.getLocation().distanceSquared(anchor) > 64 * 64) return false;
        if (!trees && Mining.avoidBreaking(b)) return false;
        if (trees) {
            long col = ((long) b.getX() << 32) | (b.getZ() & 0xFFFFFFFFL);
            if (naturalTrunks.contains(col)) return true;
            if (!Mining.naturalTree(b)) return false;
            naturalTrunks.add(col);
        }
        return true;
    }

    private Block next(Player owner) {
        while (!queue.isEmpty()) {
            Block b = queue.pollFirst();
            if (valid(b)) return b;
        }
        if (!scanning) scan(owner);
        return null;
    }

    /** Copies the chunks around her (main thread), looks for the blocks off the main thread, nearest first. */
    private void scan(Player owner) {
        scanning = true;
        Location at = c.entity.getLocation();
        World w = at.getWorld();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        List<ChunkSnapshot> snaps = new ArrayList<>();
        for (int dx = -SCAN_CHUNKS; dx <= SCAN_CHUNKS; dx++) {
            for (int dz = -SCAN_CHUNKS; dz <= SCAN_CHUNKS; dz++) {
                if (w.isChunkLoaded(cx + dx, cz + dz)) snaps.add(w.getChunkAt(cx + dx, cz + dz).getChunkSnapshot(false, false, false));
            }
        }
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        double ax = at.getX(), ay = at.getY(), az = at.getZ();
        Set<Material> types = Set.copyOf(blocks);
        Bukkit.getScheduler().runTaskAsynchronously(c.plugin(), () -> {
            List<int[]> found = new ArrayList<>();
            for (ChunkSnapshot s : snaps) {
                int bx = s.getX() << 4, bz = s.getZ() << 4;
                for (int y = minY; y < maxY; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (types.contains(s.getBlockType(x, y, z))) found.add(new int[]{bx + x, y, bz + z});
                        }
                    }
                }
            }
            // nearest first; going down costs more than going sideways or up
            found.sort(Comparator.comparingDouble(p -> {
                double dx = p[0] - ax, dy = p[1] - ay, dz = p[2] - az;
                return dx * dx + dz * dz + dy * dy * (dy < 0 ? 2.5 : 1);
            }));
            List<int[]> top = found.size() > 400 ? found.subList(0, 400) : found;
            Bukkit.getScheduler().runTask(c.plugin(), () -> {
                if (c.task != MineTask.this) return;
                for (int[] p : top) queue.addLast(w.getBlockAt(p[0], p[1], p[2]));
                scanning = false;
                scannedOnce = true;
            });
        });
    }

    /** After the lowest log of a tree is gone, a sapling from her bag goes back into the ground. */
    private void replant(Player owner, Block log) {
        Block below = log.getRelative(BlockFace.DOWN);
        if (!Tag.DIRT.isTagged(below.getType()) || !log.getType().isAir()) return;
        String kind = log.getType().name().replace("_LOG", "").replace("_STEM", "");
        ItemStack sapling = null;
        for (ItemStack it : c.bag.getStorageContents()) {
            if (it == null || !Tag.SAPLINGS.isTagged(it.getType())) continue;
            if (sapling == null || it.getType().name().startsWith(kind)) sapling = it;
        }
        if (sapling == null) return;
        Material type = sapling.getType();
        if (c.place(owner, log, below, type)) c.take(it -> it.getType() == type, 1);
    }
}
