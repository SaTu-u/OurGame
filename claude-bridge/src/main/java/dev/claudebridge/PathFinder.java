package dev.claudebridge;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** A* over the block grid for a player-sized walker (1x2), with steps up of 1 block and drops of up to 3. */
final class PathFinder {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] DY_ORDER = {0, 1, -1, -2, -3};

    record Result(List<int[]> path, boolean reachedGoal, int explored) {
    }

    private record Node(int x, int y, int z, double g, double f, Node parent) {
    }

    private PathFinder() {
    }

    static Result find(World w, int sx, int sy, int sz, double gx, double gy, double gz, double stopDist, int maxNodes) {
        int[] start = nearestWalkable(w, sx, sy, sz);
        if (start == null) return new Result(List.of(), false, 0);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Node s = new Node(start[0], start[1], start[2], 0, h(start[0], start[1], start[2], gx, gy, gz), null);
        open.add(s);
        best.put(key(s.x(), s.y(), s.z()), 0.0);
        Node bestNode = s;
        double bestH = s.f();
        int explored = 0;
        while (!open.isEmpty() && explored < maxNodes) {
            Node n = open.poll();
            Double known = best.get(key(n.x(), n.y(), n.z()));
            if (known != null && known < n.g() - 1e-9) continue;
            explored++;
            double hn = h(n.x(), n.y(), n.z(), gx, gy, gz);
            if (hn <= stopDist) return new Result(reconstruct(n), true, explored);
            if (hn < bestH) {
                bestH = hn;
                bestNode = n;
            }
            for (int[] d : DIRS) {
                boolean diag = d[0] != 0 && d[1] != 0;
                int nx = n.x() + d[0];
                int nz = n.z() + d[1];
                if (diag && (!clear(w, n.x() + d[0], n.y(), n.z()) || !clear(w, n.x(), n.y(), n.z() + d[1]))) continue;
                for (int dy : DY_ORDER) {
                    int ny = n.y() + dy;
                    if (dy == 1 && (diag || !passable(block(w, n.x(), n.y() + 2, n.z())))) continue;
                    if (dy < 0) {
                        boolean free = true;
                        for (int yy = ny + 2; yy <= n.y() + 1; yy++) {
                            if (!passable(block(w, nx, yy, nz))) {
                                free = false;
                                break;
                            }
                        }
                        if (!free) continue;
                    }
                    if (!walkable(w, nx, ny, nz)) continue;
                    double cost = (diag ? 1.414 : 1.0)
                            + (dy > 0 ? 0.6 : 0.0)
                            + (dy < 0 ? 0.3 * -dy : 0.0)
                            + (isWater(block(w, nx, ny, nz)) ? 1.5 : 0.0);
                    double g = n.g() + cost;
                    long k = key(nx, ny, nz);
                    Double prev = best.get(k);
                    if (prev == null || g < prev - 1e-9) {
                        best.put(k, g);
                        open.add(new Node(nx, ny, nz, g, g + h(nx, ny, nz, gx, gy, gz), n));
                    }
                    break;
                }
            }
        }
        if (bestNode != s) return new Result(reconstruct(bestNode), false, explored);
        return new Result(List.of(), false, explored);
    }

    private static List<int[]> reconstruct(Node n) {
        List<int[]> out = new ArrayList<>();
        for (Node c = n; c != null && c.parent() != null; c = c.parent()) out.add(new int[]{c.x(), c.y(), c.z()});
        Collections.reverse(out);
        return out;
    }

    private static double h(int x, int y, int z, double gx, double gy, double gz) {
        double dx = x + 0.5 - gx;
        double dy = y - gy;
        double dz = z + 0.5 - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    static int[] nearestWalkable(World w, int x, int y, int z) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            if (walkable(w, x, y + dy, z)) return new int[]{x, y + dy, z};
        }
        return null;
    }

    /** Feet at (x,y,z): feet and head free, something to stand on (or swimming in water). */
    static boolean walkable(World w, int x, int y, int z) {
        Block feet = block(w, x, y, z);
        if (!passable(feet) || !passable(block(w, x, y + 1, z))) return false;
        return standable(block(w, x, y - 1, z)) || isWater(feet);
    }

    private static boolean clear(World w, int x, int y, int z) {
        return passable(block(w, x, y, z)) && passable(block(w, x, y + 1, z));
    }

    /** Returns null for unloaded chunks and out-of-world coordinates, so the search never loads chunks. */
    static Block block(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return null;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        return w.getBlockAt(x, y, z);
    }

    static boolean passable(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        switch (m) {
            case LAVA, FIRE, SOUL_FIRE, COBWEB, SWEET_BERRY_BUSH, POWDER_SNOW, WITHER_ROSE, CAMPFIRE, SOUL_CAMPFIRE -> {
                return false;
            }
            default -> {
            }
        }
        if (Tag.WOODEN_DOORS.isTagged(m)) return true;
        if (Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return isOpen(b);
        return b.isPassable();
    }

    static boolean standable(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        if (!m.isSolid()) return false;
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return false;
        return m != Material.MAGMA_BLOCK && m != Material.CACTUS && m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE;
    }

    static boolean isWater(Block b) {
        return b != null && b.getType() == Material.WATER;
    }

    private static boolean isOpen(Block b) {
        BlockData d = b.getBlockData();
        return d instanceof Openable o && o.isOpen();
    }
}
