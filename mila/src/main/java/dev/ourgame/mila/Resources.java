package dev.ourgame.mila;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** What /mila mine can dig, by name (English or Russian). */
final class Resources {

    record Resource(String id, String label, String genitive, Set<Material> blocks, boolean needsPickaxe) {
    }

    private static final Map<String, Resource> BY_NAME = new LinkedHashMap<>();
    static final List<Resource> ALL;

    static {
        add("coal", "уголь", "угля", true, "COAL_ORE", "DEEPSLATE_COAL_ORE");
        add("iron", "железо", "железа", true, "IRON_ORE", "DEEPSLATE_IRON_ORE");
        add("copper", "медь", "меди", true, "COPPER_ORE", "DEEPSLATE_COPPER_ORE");
        add("gold", "золото", "золота", true, "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "NETHER_GOLD_ORE");
        add("redstone", "редстоун", "редстоуна", true, "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE");
        add("lapis", "лазурит", "лазурита", true, "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE");
        add("diamond", "алмазы", "алмазов", true, "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE");
        add("emerald", "изумруды", "изумрудов", true, "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE");
        add("quartz", "кварц", "кварца", true, "NETHER_QUARTZ_ORE");
        add("debris", "древние обломки", "древних обломков", true, "ANCIENT_DEBRIS");
        add("stone", "камень", "камня", true, "STONE");
        add("deepslate", "глубинный сланец", "глубинного сланца", true, "DEEPSLATE");
        add("dirt", "земля", "земли", false, "DIRT", "GRASS_BLOCK");
        add("sand", "песок", "песка", false, "SAND", "RED_SAND");
        add("gravel", "гравий", "гравия", false, "GRAVEL");
        add("clay", "глина", "глины", false, "CLAY");
        alias("уголь", "coal");
        alias("железо", "iron");
        alias("медь", "copper");
        alias("золото", "gold");
        alias("редстоун", "redstone");
        alias("лазурит", "lapis");
        alias("алмазы", "diamond");
        alias("алмаз", "diamond");
        alias("diamonds", "diamond");
        alias("изумруды", "emerald");
        alias("изумруд", "emerald");
        alias("emeralds", "emerald");
        alias("кварц", "quartz");
        alias("незерит", "debris");
        alias("обломки", "debris");
        alias("netherite", "debris");
        alias("камень", "stone");
        alias("cobblestone", "stone");
        alias("булыжник", "stone");
        alias("сланец", "deepslate");
        alias("земля", "dirt");
        alias("песок", "sand");
        alias("гравий", "gravel");
        alias("глина", "clay");
        ALL = BY_NAME.values().stream().distinct().toList();
    }

    private Resources() {
    }

    private static void add(String id, String label, String genitive, boolean pickaxe, String... materials) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String m : materials) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) set.add(mat);
        }
        BY_NAME.put(id, new Resource(id, label, genitive, set, pickaxe));
    }

    private static void alias(String name, String id) {
        BY_NAME.put(name, BY_NAME.get(id));
    }

    static Resource find(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT).replace('ё', 'е'));
    }

    /** All logs (trees) for /mila chop, without stripped logs and wood blocks. */
    static Set<Material> logs() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (Material m : Tag.LOGS.getValues()) {
            String n = m.name();
            if (!n.startsWith("STRIPPED_") && (n.endsWith("_LOG") || n.endsWith("_STEM"))) set.add(m);
        }
        return set;
    }
}
