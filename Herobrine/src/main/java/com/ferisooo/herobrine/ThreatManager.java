package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player threat tracking + statistics, persisted to {@code data.yml}.
 *
 * <p>Threat is a 0–100 score that climbs as a player "disturbs" the world the
 * way the legend says provokes Herobrine: mining, lingering underground,
 * sleeping, dying, and ranging far from spawn. It decays slowly when the
 * player behaves. Higher threat feeds sighting frequency, anomaly rate,
 * aggression, and boss probability across the other managers.
 */
public final class ThreatManager {

    /** Mutable per-player state. Public fields keep the persistence code terse. */
    public static final class Stats {
        public double threat;
        public long miningTicks;        // accumulated ticks actively mining
        public long undergroundTicks;   // accumulated ticks below the cave threshold
        public int sleepCount;
        public int deathCount;
        public double maxDistanceFromSpawn;
        public long isolationTicks;      // ticks with no other player nearby
        public long lastSeen;
    }

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;
    private final Map<UUID, Stats> data = new HashMap<>();
    private final File file;

    public ThreatManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    public Stats stats(UUID id) {
        return data.computeIfAbsent(id, k -> new Stats());
    }

    public double getThreat(Player p) { return stats(p.getUniqueId()).threat; }

    public void setThreat(Player p, double value) {
        stats(p.getUniqueId()).threat = clamp(value);
    }

    public void addThreat(Player p, double delta) {
        Stats s = stats(p.getUniqueId());
        s.threat = clamp(s.threat + delta);
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(cfg.threatMax(), v));
    }

    // ---- event hooks called by the plugin's listeners / tick ----

    /** One mined stone/ore block ≈ a second of "mining time". */
    public void onBlockMined(Player p) {
        if (!cfg.threatEnabled()) return;
        Stats s = stats(p.getUniqueId());
        s.miningTicks += 20;
        addThreat(p, cfg.miningPerMinute() / 60.0);
    }

    public void onUndergroundTick(Player p, int seconds) {
        if (!cfg.threatEnabled()) return;
        Stats s = stats(p.getUniqueId());
        s.undergroundTicks += seconds * 20L;
        addThreat(p, cfg.undergroundPerMinute() * (seconds / 60.0));
    }

    public void onSleep(Player p) {
        if (!cfg.threatEnabled()) return;
        stats(p.getUniqueId()).sleepCount++;
        addThreat(p, cfg.sleepIncrease());
    }

    public void onDeath(Player p) {
        if (!cfg.threatEnabled()) return;
        stats(p.getUniqueId()).deathCount++;
        addThreat(p, cfg.deathIncrease());
    }

    public void onIsolationTick(Player p, int seconds) {
        stats(p.getUniqueId()).isolationTicks += seconds * 20L;
    }

    public void updateDistance(Player p) {
        if (!cfg.threatEnabled()) return;
        Location spawn = p.getWorld().getSpawnLocation();
        if (p.getWorld() != spawn.getWorld()) return;
        double dist = p.getLocation().distance(spawn);
        Stats s = stats(p.getUniqueId());
        if (dist > s.maxDistanceFromSpawn) {
            double gained = (dist - s.maxDistanceFromSpawn) / 1000.0 * cfg.distancePer1000();
            s.maxDistanceFromSpawn = dist;
            addThreat(p, gained);
        }
    }

    /** Slow passive decay; called roughly once per minute by the plugin tick. */
    public void decayTick() {
        double dec = cfg.threatDecayPerMinute();
        if (dec <= 0) return;
        for (Stats s : data.values()) {
            s.threat = Math.max(0, s.threat - dec);
        }
    }

    public long isolationTicks(Player p) { return stats(p.getUniqueId()).isolationTicks; }
    public void resetIsolation(Player p) { stats(p.getUniqueId()).isolationTicks = 0; }

    // ---- persistence ----

    public void load() {
        if (!file.exists()) return;
        FileConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            ConfigurationSection sec = players.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Stats s = new Stats();
                s.threat = sec.getDouble("threat");
                s.miningTicks = sec.getLong("mining-ticks");
                s.undergroundTicks = sec.getLong("underground-ticks");
                s.sleepCount = sec.getInt("sleep-count");
                s.deathCount = sec.getInt("death-count");
                s.maxDistanceFromSpawn = sec.getDouble("max-distance");
                s.isolationTicks = sec.getLong("isolation-ticks");
                s.lastSeen = sec.getLong("last-seen");
                data.put(id, s);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save() {
        FileConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Stats> e : data.entrySet()) {
            String base = "players." + e.getKey();
            Stats s = e.getValue();
            y.set(base + ".threat", s.threat);
            y.set(base + ".mining-ticks", s.miningTicks);
            y.set(base + ".underground-ticks", s.undergroundTicks);
            y.set(base + ".sleep-count", s.sleepCount);
            y.set(base + ".death-count", s.deathCount);
            y.set(base + ".max-distance", s.maxDistanceFromSpawn);
            y.set(base + ".isolation-ticks", s.isolationTicks);
            y.set(base + ".last-seen", s.lastSeen);
        }
        // Let the encounter manager fold its boss/target state into the same file.
        plugin.encounters().writePersistentState(y);
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("[Herobrine] could not save data.yml: " + ex.getMessage());
        }
    }

    /** Used by the encounter manager to restore the active-target list it persisted. */
    public FileConfiguration rawFile() {
        return YamlConfiguration.loadConfiguration(file);
    }
}
