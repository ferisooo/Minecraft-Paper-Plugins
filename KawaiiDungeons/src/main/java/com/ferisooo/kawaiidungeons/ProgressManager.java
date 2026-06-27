package com.ferisooo.kawaiidungeons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads, serves, and persists {@link PlayerProgress} to playerdata.yml. Saved on
 * mutation (via {@link #save()} called by callers after a change) and on disable.
 */
public final class ProgressManager {

    private final KawaiiDungeons plugin;
    private final File file;
    private final Map<UUID, PlayerProgress> data = new HashMap<>();

    /** Set on mutation; the periodic flush writes playerdata.yml only when true. */
    private volatile boolean dirty = false;
    /** How often (ticks) the background flush checks the dirty flag. */
    private static final long SAVE_INTERVAL_TICKS = 600L; // 30s

    public ProgressManager(KawaiiDungeons plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    /**
     * Start the debounced background flush. Mutations call {@link #save()} to flag
     * the data dirty; this timer snapshots on the main thread and writes the bytes
     * off-thread, replacing the old per-mutation synchronous main-thread write.
     */
    public void startAutoSave() {
        // Folia-safe: the flush only reads/writes the in-memory progress map
        // (global server state, no entity mutation), so a global-region timer
        // is correct. The actual disk write is handed to the async scheduler.
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> flush(),
                SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    public PlayerProgress get(UUID id) {
        return data.computeIfAbsent(id, k -> new PlayerProgress());
    }

    public List<Map.Entry<UUID, PlayerProgress>> allEntries() {
        return new ArrayList<>(data.entrySet());
    }

    public void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); }
            catch (IllegalArgumentException e) { continue; }
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null) continue;
            PlayerProgress p = new PlayerProgress();
            p.setTokens(s.getInt("tokens", 0));
            p.setCompletions(s.getInt("completions", 0));
            p.setLastWeeklyClaim(s.getLong("last-weekly-claim", 0L));
            p.setEverDeathless(s.getBoolean("ever-deathless", false));
            ConfigurationSection rep = s.getConfigurationSection("reputation");
            if (rep != null) {
                for (String d : rep.getKeys(false)) p.setReputation(d, rep.getInt(d));
            }
            for (String a : s.getStringList("achievements")) p.grantAchievement(a);
            ConfigurationSection sr = s.getConfigurationSection("speedrun-best");
            if (sr != null) {
                for (String k : sr.getKeys(false)) p.speedrunBestMap().put(k, sr.getLong(k));
            }
            data.put(id, p);
        }
        plugin.getLogger().info("(✿) loaded progress for " + data.size() + " player(s).");
    }

    /**
     * Flag the in-memory progress as needing persistence. The actual write is deferred
     * to the periodic {@link #flush()} timer (or {@link #saveSync()} on disable), so
     * per-mutation main-thread file writes are avoided.
     */
    public void save() {
        dirty = true;
    }

    /** Build the YAML view of all player progress (must run on the main thread). */
    private YamlConfiguration snapshot() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerProgress> e : data.entrySet()) {
            String base = "players." + e.getKey();
            PlayerProgress p = e.getValue();
            cfg.set(base + ".tokens", p.tokens());
            cfg.set(base + ".completions", p.completions());
            cfg.set(base + ".last-weekly-claim", p.lastWeeklyClaim());
            cfg.set(base + ".ever-deathless", p.everDeathless());
            for (Map.Entry<String, Integer> r : p.reputationMap().entrySet()) {
                cfg.set(base + ".reputation." + r.getKey(), r.getValue());
            }
            cfg.set(base + ".achievements", new ArrayList<>(p.achievements()));
            for (Map.Entry<String, Long> sr : p.speedrunBestMap().entrySet()) {
                cfg.set(base + ".speedrun-best." + sr.getKey(), sr.getValue());
            }
        }
        return cfg;
    }

    /** Write the given serialized YAML to playerdata.yml. May run off the main thread. */
    private void write(String yaml) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("(✿) failed to save playerdata.yml: " + ex.getMessage());
        }
    }

    /**
     * Periodic flush: if dirty, snapshot on the main thread (safe), then write the
     * bytes asynchronously so disk I/O never blocks the server tick.
     */
    private void flush() {
        if (!dirty) return;
        dirty = false;
        final String yaml = snapshot().saveToString();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> write(yaml));
    }

    /** Synchronous flush for shutdown: snapshot and write inline so no data is lost. */
    public void saveSync() {
        dirty = false;
        write(snapshot().saveToString());
    }

    /** Top players by best speedrun time for a given dungeon+difficulty key. */
    public List<Map.Entry<UUID, Long>> speedrunLeaderboard(String key, int limit) {
        List<Map.Entry<UUID, Long>> rows = new ArrayList<>();
        for (Map.Entry<UUID, PlayerProgress> e : data.entrySet()) {
            Long best = e.getValue().speedrunBest(key);
            if (best != null) rows.add(Map.entry(e.getKey(), best));
        }
        rows.sort(Comparator.comparingLong(Map.Entry::getValue));
        if (rows.size() > limit) return rows.subList(0, limit);
        return rows;
    }
}
