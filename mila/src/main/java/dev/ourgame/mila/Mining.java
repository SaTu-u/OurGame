package dev.ourgame.mila;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Breaking rules. The "what not to break" checks follow Baritone's MovementHelper.avoidBreaking
 * (https://github.com/cabaletta/baritone, LGPL-3.0), rewritten for the Bukkit API.
 */
final class Mining {

    private Mining() {
    }

    /** Natural terrain she may dig through on her way; nothing a player is likely to have built with. */
    private static final Set<Material> EXTRA_NATURAL = EnumSet.noneOf(Material.class);

    static {
        for (String n : new String[]{"GRAVEL", "CLAY", "CALCITE", "DRIPSTONE_BLOCK", "SMOOTH_BASALT", "SOUL_SAND", "SOUL_SOIL",
                "END_STONE", "SNOW_BLOCK", "SANDSTONE", "RED_SANDSTONE", "TERRACOTTA", "AMETHYST_BLOCK", "POWDER_SNOW"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) EXTRA_NATURAL.add(m);
        }
    }

    static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    static boolean natural(Material m) {
        if (m == Material.SUSPICIOUS_SAND || m == Material.SUSPICIOUS_GRAVEL) return false;
        return Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m) || Tag.DIRT.isTagged(m)
                || Tag.SAND.isTagged(m) || isOre(m) || EXTRA_NATURAL.contains(m);
    }

    /** Baritone's avoidBreaking: ice and infested blocks, and blocks that hold back liquids or unsupported gravel. */
    static boolean avoidBreaking(Block b) {
        Material m = b.getType();
        if (m == Material.ICE || m.name().startsWith("INFESTED_") || m.getHardness() < 0) return true;
        World w = b.getWorld();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        return avoidAdjacent(w, x, y + 1, z, true)
                || avoidAdjacent(w, x + 1, y, z, false) || avoidAdjacent(w, x - 1, y, z, false)
                || avoidAdjacent(w, x, y, z + 1, false) || avoidAdjacent(w, x, y, z - 1, false);
    }

    private static boolean avoidAdjacent(World w, int x, int y, int z, boolean directlyAbove) {
        Block n = PathFinder.block(w, x, y, z);
        if (n == null) return false;
        Material m = n.getType();
        if (!directlyAbove && m.hasGravity()) {
            Block below = PathFinder.block(w, x, y - 1, z);
            if (below != null && (below.getType().isAir() || below.isLiquid())) return true;   // would fall into the hole
        }
        if (m == Material.WATER || m == Material.LAVA) {
            if (directlyAbove) return true;
            if (n.getBlockData() instanceof Levelled lv && lv.getLevel() == 0) return true;   // sources flow sideways
            Block below = PathFinder.block(w, x, y - 1, z);
            return below == null || !(below.getType() == Material.WATER || below.getType() == Material.LAVA);
        }
        return false;
    }

    /** Break time in ticks with this tool (vanilla formula); -1 when the block cannot be broken. */
    static int breakTicks(Block b, ItemStack tool) {
        Material type = b.getType();
        float hardness = type.getHardness();
        if (hardness < 0) return -1;
        if (hardness == 0) return 1;
        ItemStack t = tool == null ? ItemStack.empty() : tool;
        BlockData bd = b.getBlockData();
        float speed = Math.max(0.1f, bd.getDestroySpeed(t, true));
        int ticks = (int) Math.ceil((canHarvest(b, tool) ? 30.0 : 100.0) * hardness / speed);
        return Math.max(1, Math.min(ticks, 1200));
    }

    /** True when breaking the block with this tool gives drops (stone needs a pickaxe, iron ore a stone pickaxe...). */
    static boolean canHarvest(Block b, ItemStack tool) {
        BlockData bd = b.getBlockData();
        return !bd.requiresCorrectToolForDrops() || (tool != null && !tool.getType().isAir() && bd.isPreferredTool(tool));
    }

    static boolean isTool(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS");
    }

    static boolean isWeapon(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.equals("TRIDENT") || n.equals("MACE");
    }

    /** Things she keeps in her backpack when she empties it into a chest. */
    static boolean isEquipment(ItemStack it) {
        return it != null && (isTool(it) || isWeapon(it) || it.getType() == Material.FISHING_ROD || it.getType() == Material.TORCH);
    }

    /** Bag slot of the best tool for the block: one that gets drops first, then the fastest; -1 = bare hands. */
    static int bestTool(Inventory bag, Block b) {
        int best = -1;
        boolean bestHarvest = canHarvest(b, null);
        int bestTicks = breakTicks(b, null);
        for (int i = 0; i < bag.getSize(); i++) {
            ItemStack it = bag.getItem(i);
            if (!isTool(it)) continue;
            boolean h = canHarvest(b, it);
            int t = breakTicks(b, it);
            if ((h && !bestHarvest) || (h == bestHarvest && t >= 0 && t < bestTicks)) {
                best = i;
                bestHarvest = h;
                bestTicks = t;
            }
        }
        return best;
    }

    /** Bag slot of the strongest weapon, -1 = bare hands. */
    static int bestWeapon(Inventory bag) {
        int best = -1;
        double bestDamage = 1;
        for (int i = 0; i < bag.getSize(); i++) {
            ItemStack it = bag.getItem(i);
            if (!isWeapon(it)) continue;
            double d = attackDamage(it);
            if (d > bestDamage) {
                best = i;
                bestDamage = d;
            }
        }
        return best;
    }

    static double attackDamage(ItemStack it) {
        if (it == null) return 1;
        String n = it.getType().name();
        boolean sword = n.endsWith("_SWORD");
        boolean axe = n.endsWith("_AXE");
        if (!sword && !axe) return n.equals("TRIDENT") ? 9 : n.equals("MACE") ? 6 : 1;
        if (n.startsWith("NETHERITE")) return sword ? 8 : 10;
        if (n.startsWith("DIAMOND")) return sword ? 7 : 9;
        if (n.startsWith("IRON")) return sword ? 6 : 9;
        if (n.startsWith("STONE") || n.startsWith("COPPER")) return sword ? 5 : 9;
        return sword ? 4 : 7;
    }

    /** Ticks between hits, like the attack cooldown of a player. */
    static int attackCooldown(ItemStack it) {
        if (it == null) return 10;
        String n = it.getType().name();
        if (n.endsWith("_SWORD")) return 12;
        if (n.endsWith("_AXE")) return 20;
        return 14;
    }

    /** One use of the item in a bag slot; Unbreaking works like in vanilla. Returns false when it broke. */
    static boolean damage(Inventory bag, int slot) {
        ItemStack it = bag.getItem(slot);
        if (it == null || it.getType().getMaxDurability() <= 0) return true;
        if (!(it.getItemMeta() instanceof Damageable dm) || dm.isUnbreakable()) return true;
        int unbreaking = it.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return true;
        int dmg = dm.getDamage() + 1;
        if (dmg >= it.getType().getMaxDurability()) {
            bag.setItem(slot, null);
            return false;
        }
        dm.setDamage(dmg);
        it.setItemMeta(dm);
        bag.setItem(slot, it);
        return true;
    }

    /** A log that belongs to a grown tree: natural (non-persistent) leaves near the trunk. Player builds use persistent leaves. */
    static boolean naturalTree(Block log) {
        World w = log.getWorld();
        for (int dy = 0; dy <= 10; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block b = PathFinder.block(w, log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                    if (b != null && Tag.LEAVES.isTagged(b.getType()) && b.getBlockData() instanceof Leaves lv && !lv.isPersistent()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
