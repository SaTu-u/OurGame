package dev.ourgame.mila;

import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.profile.PlayerTextures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** config.yml, read once on start and on /mila reload. */
record Settings(
        String defaultName,
        Key texture,
        PlayerTextures.SkinModel model,
        Key fallbackTexture,
        double walkSpeed,
        double runSpeed,
        double followDistance,
        double teleportDistance,
        int pathNodes,
        int minutesPerPoint,
        int hugAt,
        int kissAt,
        int ownerHit,
        int hugCooldown,
        int kissCooldown,
        int giftsPerDay,
        Map<String, Integer> gifts,
        boolean defendOwner,
        double attackDamage,
        int chatterMinutes,
        List<String> phrases,
        List<String> clickPhrases,
        boolean debug) {

    static Settings load(FileConfiguration c, Logger log) {
        Key texture;
        try {
            texture = Key.key(c.getString("skin.texture", "mila:entity/mila"));
        } catch (Exception e) {
            log.warning("skin.texture is not a valid key, using mila:entity/mila");
            texture = Key.key("mila", "entity/mila");
        }
        PlayerTextures.SkinModel model = "wide".equalsIgnoreCase(c.getString("skin.model", "slim"))
                ? PlayerTextures.SkinModel.CLASSIC : PlayerTextures.SkinModel.SLIM;
        Key fallback = null;
        String fb = c.getString("skin.fallback", "minecraft:entity/player/slim/kai");
        if (fb != null && !fb.isBlank()) {
            try {
                fallback = Key.key(fb.trim());
            } catch (Exception e) {
                log.warning("skin.fallback is not a valid key, the fallback skin is off");
            }
        }
        Map<String, Integer> gifts = new LinkedHashMap<>();
        ConfigurationSection g = c.getConfigurationSection("gifts");
        if (g != null) {
            for (String k : g.getKeys(false)) gifts.put(k.toLowerCase(Locale.ROOT), g.getInt(k));
        }
        return new Settings(
                c.getString("name", "Мила"),
                texture,
                model,
                fallback,
                Math.max(1, c.getDouble("walk-speed", 4.6)),
                Math.max(1, c.getDouble("run-speed", 6.2)),
                Math.max(1, c.getDouble("follow-distance", 2.5)),
                Math.max(8, c.getDouble("teleport-distance", 24)),
                Math.max(200, c.getInt("path-nodes", 2000)),
                Math.max(1, c.getInt("affection.minutes-per-point", 3)),
                c.getInt("affection.hug", 10),
                c.getInt("affection.kiss", 40),
                c.getInt("affection.owner-hit", -5),
                Math.max(0, c.getInt("cooldown-seconds.hug", 15)),
                Math.max(0, c.getInt("cooldown-seconds.kiss", 45)),
                Math.max(0, c.getInt("gifts-per-day", 6)),
                gifts,
                c.getBoolean("defend-owner", true),
                Math.max(0, c.getDouble("attack-damage", 4.0)),
                Math.max(0, c.getInt("chatter-minutes", 5)),
                c.getStringList("phrases"),
                c.getStringList("click-phrases"),
                c.getBoolean("debug", false));
    }
}
