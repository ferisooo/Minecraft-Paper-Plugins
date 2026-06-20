package com.ferisooo.herobrine;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, cached accessor over {@code config.yml}. Every tunable the rest of
 * the plugin reads goes through here so a {@code /herobrine reload} re-reads
 * one object and the managers pick up new values on their next tick.
 */
public final class ConfigManager {

    private final HerobrinePlugin plugin;
    private FileConfiguration cfg;

    public ConfigManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
    }

    // ---- generic helpers ----
    public double d(String path, double def) { return cfg.getDouble(path, def); }
    public int i(String path, int def) { return cfg.getInt(path, def); }
    public boolean b(String path, boolean def) { return cfg.getBoolean(path, def); }
    public String s(String path, String def) { return cfg.getString(path, def); }

    // ---- skin / entity ----
    public String skinTexture() { return s("general.steve-skin-texture", ""); }
    public String skinSignature() { return s("general.steve-skin-signature", ""); }
    public boolean glowingEyes() { return b("general.glowing-eyes", true); }
    public String herobrineName() { return s("general.herobrine-name", "Herobrine"); }

    public double health() { return d("entity.health", 500.0); }
    public double speed() { return d("entity.speed", 0.45); }
    public double followRange() { return d("entity.follow-range", 128.0); }

    // ---- stalking ----
    public boolean stalkingEnabled() { return b("stalking.enabled", true); }
    public int stalkMin() { return i("stalking.min-distance", 20); }
    public int stalkMax() { return i("stalking.max-distance", 60); }
    public int stareSeconds() { return i("stalking.stare-seconds", 5); }
    public double stalkBaseChance() { return d("stalking.base-chance", 0.06); }
    public double nightMultiplier() { return d("stalking.night-multiplier", 2.0); }
    public double stormMultiplier() { return d("stalking.storm-multiplier", 2.5); }
    public int stalkInterval() { return i("stalking.check-interval-ticks", 1200); }

    // ---- threat ----
    public boolean threatEnabled() { return b("threat.enabled", true); }
    public double threatMax() { return d("threat.max", 100.0); }
    public double miningPerMinute() { return d("threat.mining-per-minute", 1.0); }
    public double undergroundPerMinute() { return d("threat.underground-per-minute", 1.5); }
    public double sleepIncrease() { return d("threat.sleep-increase", 3.0); }
    public double deathIncrease() { return d("threat.death-increase", 5.0); }
    public double distancePer1000() { return d("threat.distance-per-1000", 4.0); }
    public double threatDecayPerMinute() { return d("threat.decay-per-minute", 0.5); }
    public int threatSightings() { return i("threat.thresholds.sightings", 20); }
    public int threatAnomalies() { return i("threat.thresholds.anomalies", 40); }
    public int threatAggressive() { return i("threat.thresholds.aggressive", 60); }
    public int threatBoss() { return i("threat.thresholds.boss", 80); }

    // ---- structures ----
    public boolean structuresEnabled() { return b("structures.enabled", true); }
    public double structureChance() { return d("structures.chance", 0.03); }
    public int structureInterval() { return i("structures.check-interval-ticks", 2400); }
    public int structureMin() { return i("structures.min-distance", 24); }
    public int structureMax() { return i("structures.max-distance", 64); }

    // ---- abilities ----
    public int shadowTeleportCooldown() { return i("abilities.shadow-teleport-cooldown", 15); }
    public double lightningDamage() { return d("abilities.lightning-damage", 16.0); }
    public int lightningCooldown() { return i("abilities.lightning-cooldown", 20); }
    public int darknessRadius() { return i("abilities.darkness-radius", 16); }
    public int darknessDuration() { return i("abilities.darkness-duration", 8); }
    public boolean blockManipulationEnabled() { return b("abilities.block-manipulation-enabled", false); }
    public int blockRevertSeconds() { return i("abilities.block-revert-seconds", 45); }
    public double meleeDamage() { return d("abilities.melee-damage", 8.0); }

    // ---- hunting ----
    public boolean huntingEnabled() { return b("hunting.enabled", true); }
    public int isolationSeconds() { return i("hunting.isolation-seconds", 300); }
    public int deepCaveY() { return i("hunting.deep-cave-y", 20); }

    // ---- boss ----
    public boolean bossEnabled() { return b("boss.enabled", true); }
    public double bossHealth() { return d("boss.health", 500.0); }
    public int bossThreshold() { return i("boss.threshold", 80); }
    public boolean bossRequireMidnight() { return b("boss.require-midnight", true); }
    public boolean bossRequireStorm() { return b("boss.require-storm", true); }
    public String bossBarTitle() { return color(s("boss.bar-title", "HEROBRINE HAS AWAKENED")); }
    public double bossPhase2Pct() { return d("boss.phase2-health-pct", 0.66); }
    public double bossPhase3Pct() { return d("boss.phase3-health-pct", 0.33); }
    public int minionCount() { return i("boss.minion-count", 3); }
    public int minionLifespan() { return i("boss.minion-lifespan-seconds", 30); }
    public double bossSpawnChance() { return d("boss.spawn-chance", 0.25); }

    // ---- environment ----
    public boolean environmentEnabled() { return b("environment.enabled", true); }
    public boolean caveSounds() { return b("environment.cave-sounds", true); }
    public boolean fog() { return b("environment.fog", true); }
    public boolean animalPanic() { return b("environment.animal-panic", true); }
    public boolean doorInteractions() { return b("environment.door-interactions", true); }
    public boolean compassInterference() { return b("environment.compass-interference", true); }

    // ---- messages ----
    public String prefix() { return color(s("messages.prefix", "&8[&fHerobrine&8] &7")); }

    public String msg(String key, String def) {
        return color(s("messages." + key, def));
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
