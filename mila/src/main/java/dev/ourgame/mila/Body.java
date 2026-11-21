package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/** Where a player-sized body (0.6 x 1.8) can stand. Same rules as the ClaudeBridge avatar, which uses the same entity. */
final class Body {

    private Body() {
    }

    /** Standing on something solid (checks the block just below the feet, so slabs and stairs count). */
    static boolean grounded(Location l) {
        Block below = l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.05), l.getBlockZ());
        return !below.isPassable() && below.getBoundingBox().getMaxY() >= l.getY() - 0.06;
    }

    static boolean inWater(Location l) {
        return l.getBlock().getType() == Material.WATER
                || l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() + 0.5), l.getBlockZ()).getType() == Material.WATER;
    }

    /** Moves a location out of solid blocks and down onto the floor. */
    static Location snapToGround(Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = (int) Math.floor(loc.getY());
        for (int i = 0; i < 64 && y < w.getMaxHeight() - 2; i++, y++) {
            if (PathFinder.passable(w.getBlockAt(x, y, z)) && PathFinder.passable(w.getBlockAt(x, y + 1, z))) break;
        }
        for (int i = 0; i < 160 && y > w.getMinHeight(); i++) {
            Block below = w.getBlockAt(x, y - 1, z);
            if (!below.isPassable() || below.getType() == Material.WATER) break;
            y--;
        }
        Block below = w.getBlockAt(x, y - 1, z);
        double floor = below.isPassable() ? y : Math.max(below.getBoundingBox().getMaxY(), y - 1.0);
        Location out = loc.clone();
        out.setY(floor);
        return out;
    }

    /** The requested spot when the whole body fits there, otherwise the nearest free block with ground (up to 4 away). */
    static Location safeSpot(Location wanted) {
        Location snapped = snapToGround(wanted);
        if (clear(snapped)) return snapped;
        World w = wanted.getWorld();
        int bx = snapped.getBlockX();
        int by = (int) Math.floor(snapped.getY() + 1e-6);
        int bz = snapped.getBlockZ();
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
                        if (!PathFinder.walkable(w, bx + dx, by + dy, bz + dz)) continue;
                        Location c = snapToGround(new Location(w, bx + dx + 0.5, by + dy, bz + dz + 0.5, wanted.getYaw(), wanted.getPitch()));
                        if (clear(c)) return c;
                    }
                }
            }
        }
        return snapped;
    }

    /** True when a body standing at the location touches no solid block. */
    static boolean clear(Location l) {
        World w = l.getWorld();
        BoundingBox body = new BoundingBox(l.getX() - 0.3, l.getY() + 0.01, l.getZ() - 0.3, l.getX() + 0.3, l.getY() + 1.8, l.getZ() + 0.3);
        for (int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(body.getMaxX()); x++) {
            for (int y = (int) Math.floor(body.getMinY()); y <= (int) Math.floor(body.getMaxY()); y++) {
                for (int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(body.getMaxZ()); z++) {
                    Block b = PathFinder.block(w, x, y, z);
                    if (b == null || b.isPassable() || b.isLiquid()) continue;
                    for (BoundingBox part : b.getCollisionShape().getBoundingBoxes()) {
                        if (part.shift(x, y, z).overlaps(body)) return false;
                    }
                }
            }
        }
        return true;
    }
}
