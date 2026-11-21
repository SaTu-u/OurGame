package dev.ourgame.mila;

import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* for a player-sized walker that may also dig, modelled on Baritone's pathfinder
 * (https://github.com/cabaletta/baritone, LGPL-3.0): costs are in ticks, a block in the way costs its break time,
 * moves are traverse, ascend (one up), descend/fall (up to three down) and dig straight down. Without a break cost
 * function it only walks. When the goal is not reached within the node limit, the path to the node closest to the
 * goal is returned, and the caller walks it and plans again (Baritone's segmented pathing).
 */
final class DigPathFinder {

    /** Ticks to break a block, or a negative number when she must not break it. */
    interface BreakCost {
        double ticks(Block b);
    }

    interface Goal {
        boolean reached(int x, int y, int z);

        /** Estimated distance to the goal in blocks. */
        double h(int x, int y, int z);
    }

    record Result(List<int[]> path, boolean reachedGoal, double bestH) {
    }

    // Baritone ActionCosts, in ticks
    static final double WALK = 20 / 4.317;
    static final double JUMP = 2.2;
    static final double BREAK_EXTRA = 2.0;
    static final double WATER = 20 / 2.2 - WALK;
    private static final double INF = 1e9;

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] DY_ORDER = {0, 1, -1, -2, -3};

    private record Node(int x, int y, int z, double g, double f, Node parent) {
    }

    private DigPathFinder() {
    }

    static Result find(World w, int sx, int sy, int sz, Goal goal, BreakCost cost, int maxNodes) {
        int[] start = PathFinder.nearestWalkable(w, sx, sy, sz);
        if (start == null) start = new int[]{sx, sy, sz};
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Double> breakCache = new HashMap<>();
        Node s = new Node(start[0], start[1], start[2], 0, goal.h(start[0], start[1], start[2]) * WALK, null);
        open.add(s);
        best.put(key(s.x(), s.y(), s.z()), 0.0);
        Node bestNode = s;
        double bestH = goal.h(s.x(), s.y(), s.z());
        int explored = 0;
        while (!open.isEmpty() && explored < maxNodes) {
            Node n = open.poll();
            Double known = best.get(key(n.x(), n.y(), n.z()));
            if (known != null && known < n.g() - 1e-9) continue;
            explored++;
            if (goal.reached(n.x(), n.y(), n.z())) return new Result(reconstruct(n), true, 0);
            double hn = goal.h(n.x(), n.y(), n.z());
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
                    double extra = 0;
                    if (dy == 1) {
                        if (diag) continue;
                        extra += clearCost(w, n.x(), n.y() + 2, n.z(), cost, breakCache);   // head room for the jump
                    }
                    int top = dy < 0 ? n.y() + 1 : ny + 1;
                    for (int yy = ny; yy <= top && extra < INF; yy++) extra += clearCost(w, nx, yy, nz, cost, breakCache);
                    if (extra >= INF || (diag && extra > 0)) continue;
                    Block floor = PathFinder.block(w, nx, ny - 1, nz);
                    Block feet = PathFinder.block(w, nx, ny, nz);
                    boolean water = PathFinder.isWater(feet);
                    if (!PathFinder.standable(floor) && !water) continue;
                    double step = WALK * (diag ? Math.sqrt(2) : 1)
                            + (dy > 0 ? JUMP : 0)
                            + (dy < 0 ? WALK * 0.2 + 2.0 * -dy : 0)
                            + (water ? WATER : 0)
                            + extra;
                    relax(open, best, n, nx, ny, nz, step, goal);
                    if (cost == null || extra == 0) break;     // walking: the first height that works, like PathFinder
                }
            }
            if (cost != null) {                                // dig straight down (Baritone's MovementDownward)
                Block below = PathFinder.block(w, n.x(), n.y() - 1, n.z());
                Block floor = PathFinder.block(w, n.x(), n.y() - 2, n.z());
                if (below != null && !PathFinder.passable(below) && PathFinder.standable(floor)) {
                    double t = breakTicks(below, cost, breakCache);
                    if (t < INF) relax(open, best, n, n.x(), n.y() - 1, n.z(), WALK + t, goal);
                }
            }
        }
        if (bestNode != s) return new Result(reconstruct(bestNode), false, bestH);
        return new Result(List.of(), false, bestH);
    }

    private static void relax(PriorityQueue<Node> open, Map<Long, Double> best, Node n, int x, int y, int z, double step, Goal goal) {
        double g = n.g() + step;
        long k = key(x, y, z);
        Double prev = best.get(k);
        if (prev == null || g < prev - 1e-9) {
            best.put(k, g);
            open.add(new Node(x, y, z, g, g + goal.h(x, y, z) * WALK, n));
        }
    }

    private static double clearCost(World w, int x, int y, int z, BreakCost cost, Map<Long, Double> cache) {
        Block b = PathFinder.block(w, x, y, z);
        if (b == null) return INF;
        if (PathFinder.passable(b)) return 0;
        if (cost == null) return INF;
        return breakTicks(b, cost, cache);
    }

    private static double breakTicks(Block b, BreakCost cost, Map<Long, Double> cache) {
        long k = key(b.getX(), b.getY(), b.getZ());
        Double c = cache.get(k);
        if (c == null) {
            double t = cost.ticks(b);
            c = t < 0 ? INF : t + BREAK_EXTRA;
            cache.put(k, c);
        }
        return c;
    }

    private static boolean clear(World w, int x, int y, int z) {
        return PathFinder.passable(PathFinder.block(w, x, y, z)) && PathFinder.passable(PathFinder.block(w, x, y + 1, z));
    }

    private static List<int[]> reconstruct(Node n) {
        List<int[]> out = new ArrayList<>();
        for (Node c = n; c != null && c.parent() != null; c = c.parent()) out.add(new int[]{c.x(), c.y(), c.z()});
        Collections.reverse(out);
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    // ------------------------------------------------------------------ common goals

    /** Stand where the block is right next to the feet or the head (6 directions), so it can be broken. */
    static Goal nextTo(Block target) {
        int tx = target.getX(), ty = target.getY(), tz = target.getZ();
        return new Goal() {
            @Override
            public boolean reached(int x, int y, int z) {
                int dfeet = Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(tz - z);
                int dhead = Math.abs(tx - x) + Math.abs(ty - y - 1) + Math.abs(tz - z);
                return dfeet == 1 || dhead == 1;
            }

            @Override
            public double h(int x, int y, int z) {
                double dx = tx - x, dy = ty - (y + 0.5), dz = tz - z;
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 1);
            }
        };
    }

    /** Stand where the eyes are within reach of the block's centre. */
    static Goal withinReach(Block target, double reach) {
        double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
        return new Goal() {
            @Override
            public boolean reached(int x, int y, int z) {
                if (x == target.getX() && z == target.getZ() && (y == target.getY() || y + 1 == target.getY())) return false;
                return dist(x, y, z) <= reach;
            }

            @Override
            public double h(int x, int y, int z) {
                return Math.max(0, dist(x, y, z) - reach);
            }

            private double dist(int x, int y, int z) {
                double dx = tx - (x + 0.5), dy = ty - (y + 1.62), dz = tz - (z + 0.5);
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        };
    }

    /** Get within a distance of a point (a player, a mob, a chest). */
    static Goal near(double gx, double gy, double gz, double dist) {
        return new Goal() {
            @Override
            public boolean reached(int x, int y, int z) {
                return h0(x, y, z) <= dist;
            }

            @Override
            public double h(int x, int y, int z) {
                return Math.max(0, h0(x, y, z) - dist);
            }

            private double h0(int x, int y, int z) {
                double dx = gx - (x + 0.5), dy = gy - y, dz = gz - (z + 0.5);
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        };
    }
}
