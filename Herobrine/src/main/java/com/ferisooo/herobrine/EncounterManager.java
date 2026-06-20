package com.ferisooo.herobrine;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides <i>when</i> Herobrine escalates: trigger conditions for active
 * hunting (sleeping, deep caves, isolation, thunderstorms, high threat) and
 * spawn conditions for the boss (midnight + storm + threat over threshold).
 *
 * <p>Crucially it owns the set of "active targets" — players Herobrine is
 * locked onto. When a target leaves mid-encounter the next victim is chosen
 * from this set <b>without resetting progress</b> (the boss keeps its current
 * health and phase). The set is persisted across restarts.
 */
public final class EncounterManager {

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;

    private final Set<UUID> activeTargets = new HashSet<>();
    private boolean bossActive;

    public EncounterManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
        restore();
    }

    public boolean isBossActive() { return bossActive; }
    public void markBossActive(boolean active) { this.bossActive = active; }
    public Set<UUID> activeTargets() { return activeTargets; }
    public void addTarget(Player p) { if (p != null) activeTargets.add(p.getUniqueId()); }

    /**
     * Per-player escalation check, run periodically. Returns true if an
     * encounter was triggered for this player this call.
     */
    public boolean evaluate(Player p) {
        if (bossActive) return false;
        double threat = plugin.threats().getThreat(p);
        World w = p.getWorld();

        // ---- boss conditions: midnight + storm + threat over threshold ----
        if (cfg.bossEnabled() && threat >= cfg.bossThreshold()) {
            boolean midnight = !cfg.bossRequireMidnight() || isMidnight(w);
            boolean storm = !cfg.bossRequireStorm() || w.isThundering();
            if (midnight && storm && ThreadLocalRandom.current().nextDouble() < cfg.bossSpawnChance()) {
                addTarget(p);
                if (plugin.boss().start(p)) return true;
            }
        }

        // ---- hunting triggers ----
        if (!cfg.huntingEnabled()) return false;
        if (plugin.herobrine().hasActive()
                && plugin.herobrine().active().getMode() == HerobrineEntity.Mode.HUNTING) return false;

        boolean deepCave = p.getLocation().getBlockY() <= cfg.deepCaveY()
                && w.getBlockAt(p.getLocation().getBlockX(), p.getLocation().getBlockY() + 6,
                        p.getLocation().getBlockZ()).getType().isSolid();
        boolean isolated = plugin.threats().isolationTicks(p) >= cfg.isolationSeconds() * 20L;
        boolean storming = w.isThundering();
        boolean highThreat = threat >= cfg.threatAggressive();

        // probability rises with threat; any trigger condition can kick it off
        double huntChance = 0.0;
        if (deepCave) huntChance += 0.15;
        if (isolated) huntChance += 0.15;
        if (storming) huntChance += 0.10;
        if (highThreat) huntChance += 0.25;
        huntChance *= (0.5 + threat / 100.0);

        if (huntChance > 0 && ThreadLocalRandom.current().nextDouble() < huntChance) {
            addTarget(p);
            plugin.herobrine().beginHunt(p);
            return true;
        }
        return false;
    }

    /** Sleeping is an instant, strong hunting trigger. */
    public void onSleepTrigger(Player p) {
        if (!cfg.huntingEnabled() || bossActive) return;
        addTarget(p);
        if (ThreadLocalRandom.current().nextDouble() < 0.5 + plugin.threats().getThreat(p) / 200.0) {
            plugin.herobrine().beginHunt(p);
        }
    }

    private boolean isMidnight(World w) {
        long t = w.getTime();
        return t >= 17000 && t <= 19000; // ~midnight window
    }

    // ---- re-targeting without progress reset ----

    /** Pick the next boss victim from the active-target set (excluding {@code leaving}). */
    public Player nextBossTarget(Player leaving) {
        return nextFromActive(leaving);
    }

    /** Pick the next hunt victim from the active-target set (excluding {@code leaving}). */
    public Player nextHuntTarget(Player leaving) {
        return nextFromActive(leaving);
    }

    private Player nextFromActive(Player leaving) {
        UUID skip = leaving == null ? null : leaving.getUniqueId();
        List<Player> candidates = new ArrayList<>();
        for (UUID id : activeTargets) {
            if (id.equals(skip)) continue;
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline()) candidates.add(p);
        }
        // fall back to any online non-creative player so the hunt continues
        if (candidates.isEmpty()) {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (skip != null && p.getUniqueId().equals(skip)) continue;
                if (p.getGameMode() == org.bukkit.GameMode.CREATIVE
                        || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return null;
        Player chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        addTarget(chosen);
        return chosen;
    }

    // ---- persistence (folded into data.yml by ThreatManager.save) ----

    public void writePersistentState(FileConfiguration y) {
        List<String> ids = new ArrayList<>();
        for (UUID id : activeTargets) ids.add(id.toString());
        y.set("encounter.active-targets", ids);
        y.set("encounter.boss-active", bossActive);
    }

    private void restore() {
        FileConfiguration y = plugin.threats() != null ? plugin.threats().rawFile() : null;
        if (y == null) {
            // threats() not ready yet at construction time; read file directly
            java.io.File f = new java.io.File(plugin.getDataFolder(), "data.yml");
            if (!f.exists()) return;
            y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        }
        for (String s : y.getStringList("encounter.active-targets")) {
            try { activeTargets.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        // boss-active is intentionally NOT auto-resumed; a live boss fight needs
        // an online target and is restarted by conditions. We keep the flag for
        // diagnostics only.
    }
}
