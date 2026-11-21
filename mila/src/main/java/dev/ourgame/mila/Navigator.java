package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks her to a goal for a task. Plans with DigPathFinder, follows the path one node at a time and, when digging is
 * allowed, breaks the blocks in the way first (natural terrain only, never next to lava or water). Partial paths are
 * walked and planned again, like Baritone's path segments.
 */
final class Navigator {

    static final int MOVING = 0, ARRIVED = 1, STUCK = 2;

    private final Companion c;
    private List<int[]> path = List.of();
    private int idx;
    private int replanIn;
    private double bestH = Double.MAX_VALUE;
    private int badPlans;
    private Block obstacle;

    Navigator(Companion c) {
        this.c = c;
    }

    void reset() {
        path = List.of();
        idx = 0;
        replanIn = 0;
        bestH = Double.MAX_VALUE;
        badPlans = 0;
        obstacle = null;
    }

    /**
     * One tick toward the goal.
     *
     * @param dig       may break natural blocks in the way
     * @param moving    the goal moves (a mob, a player): plan again twice a second
     */
    int go(Player owner, DigPathFinder.Goal goal, double speed, boolean dig, boolean moving) {
        Location cur = c.entity.getLocation();
        int fx = cur.getBlockX(), fy = (int) Math.floor(cur.getY() + 0.01), fz = cur.getBlockZ();
        boolean onGround = Body.grounded(cur) || Body.inWater(cur);
        if (obstacle != null) {
            int r = c.breakTick(owner, obstacle, c.settings().keepDugBlocks());
            if (r == Companion.BUSY) return MOVING;
            if (r == Companion.FAILED) {
                path = List.of();
                replanIn = 0;
            }
            obstacle = null;
            return MOVING;
        }
        if (onGround && goal.reached(fx, fy, fz)) {
            path = List.of();
            return ARRIVED;
        }
        replanIn--;
        boolean need = idx >= path.size() ? replanIn <= 15 : replanIn <= 0;
        if (need && onGround) {
            World w = cur.getWorld();
            DigPathFinder.BreakCost cost = dig ? this::breakCost : null;
            DigPathFinder.Result res = DigPathFinder.find(w, fx, fy, fz, goal, cost, c.settings().pathNodes() * (dig ? 3 : 1));
            path = res.path();
            idx = 0;
            replanIn = moving ? 10 : 40;
            if (!res.reachedGoal()) {
                if (res.bestH() < bestH - 0.5) {
                    bestH = res.bestH();
                    badPlans = 0;
                } else if (++badPlans > 4) {
                    return STUCK;
                }
            }
            if (path.isEmpty()) {
                if (++badPlans > 4) return STUCK;
                return MOVING;
            }
        }
        if (idx < path.size()) {
            int[] w = path.get(idx);
            Block block = firstObstacle(cur.getWorld(), fx, fy, fz, w);
            if (block != null) {
                if (dig && breakCost(block) >= 0) {
                    obstacle = block;
                } else {
                    path = List.of();
                    replanIn = 0;
                }
                return MOVING;
            }
            if (c.moveStep(w, speed)) idx++;
        }
        return MOVING;
    }

    /** The first block that still has to go before she can step from her block to node w. */
    private static Block firstObstacle(World world, int fx, int fy, int fz, int[] w) {
        List<Block> need = new ArrayList<>(4);
        int dy = w[1] - fy;
        if (w[0] == fx && w[2] == fz) {
            if (dy < 0) need.add(world.getBlockAt(fx, w[1], fz));            // digging down
        } else if (dy > 0) {
            need.add(world.getBlockAt(fx, fy + 2, fz));                     // head room for the step up
            need.add(world.getBlockAt(w[0], w[1], w[2]));
            need.add(world.getBlockAt(w[0], w[1] + 1, w[2]));
        } else {
            for (int y = w[1]; y <= Math.max(w[1] + 1, fy + 1); y++) need.add(world.getBlockAt(w[0], y, w[2]));
        }
        for (Block b : need) if (!PathFinder.passable(b)) return b;
        return null;
    }

    /** Ticks to break a block on the way, or -1 when she must not. */
    private double breakCost(Block b) {
        if (!Mining.natural(b.getType()) || c.forbidden.contains(b) || Mining.avoidBreaking(b)) return -1;
        int slot = Mining.bestTool(c.bag, b);
        ItemStack tool = slot < 0 ? null : c.bag.getItem(slot);
        return Mining.breakTicks(b, tool);
    }
}
