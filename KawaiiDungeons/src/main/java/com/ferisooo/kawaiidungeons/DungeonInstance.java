package com.ferisooo.kawaiidungeons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live runtime state for one dungeon run inside a cloned world. Created by
 * {@link InstanceManager} and keyed by its world name. Holds objective state,
 * boss tracking, downed-player state, endgame flags, timing, and the loaded
 * boss bar. Game logic that needs Bukkit scheduling/teleporting lives in
 * {@link InstanceManager}; this class is mostly state + small helpers.
 */
public final class DungeonInstance {

    public enum Difficulty {
        NORMAL, HARD, NIGHTMARE, MYTHIC;

        public static Difficulty from(String s) {
            if (s == null) return NORMAL;
            try { return Difficulty.valueOf(s.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { return NORMAL; }
        }
    }

    public final String worldName;
    public final DungeonDef def;
    public final Difficulty difficulty;
    public final UUID leader;
    private final Set<UUID> participants = new HashSet<>();

    // Endgame mode flags.
    public final boolean speedrun;
    public final boolean deathless;
    public final boolean hardcore;

    // Difficulty multipliers (resolved at construction).
    public final double healthMult;
    public final double damageMult;
    public final double lootMult;

    public final Objective objective;

    // Boss tracking.
    private UUID bossId;
    private double bossMaxHealth;
    private int bossPhaseIndex = -1; // last triggered phase index

    // Timing.
    public final long startMillis;
    public final long endMillis; // hard time limit (start + timeLimit)
    private int elapsedSeconds;
    private int wavesSpawned;

    // Downed players: id -> remaining ticks. Out players are removed from the run.
    private final Map<UUID, Integer> downed = new HashMap<>();
    private final Set<UUID> out = new HashSet<>();
    private final Map<UUID, Location> downedReturn = new HashMap<>();

    // Death bookkeeping for deathless tracking.
    private boolean anyDeath;

    // NPC for DEFEND/ESCORT objectives.
    private UUID npcId;

    private BossBar bossBar;
    private boolean finished;

    public DungeonInstance(String worldName, DungeonDef def, Difficulty difficulty, UUID leader,
                           boolean speedrun, boolean deathless, boolean hardcore,
                           double healthMult, double damageMult, double lootMult) {
        this.worldName = worldName;
        this.def = def;
        this.difficulty = difficulty;
        this.leader = leader;
        this.speedrun = speedrun;
        this.deathless = deathless;
        this.hardcore = hardcore;
        this.healthMult = healthMult;
        this.damageMult = damageMult;
        this.lootMult = lootMult;
        this.objective = new Objective(def.objectiveType, objectiveTarget(def));
        this.startMillis = System.currentTimeMillis();
        this.endMillis = startMillis + def.timeLimitSeconds * 1000L;
    }

    private static int objectiveTarget(DungeonDef def) {
        return switch (def.objectiveType) {
            case KILL_BOSS, ESCORT_NPC -> 1;
            case DEFEND_NPC, TIMED_CHALLENGE -> Math.max(1, def.objectiveDuration);
            default -> Math.max(1, def.objectiveAmount);
        };
    }

    public World world() { return Bukkit.getWorld(worldName); }

    public Set<UUID> participants() { return participants; }

    public void addParticipant(UUID id) { participants.add(id); }

    public boolean isParticipant(UUID id) { return participants.contains(id); }

    public List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    // ---- boss ----
    public UUID bossId() { return bossId; }

    public void setBoss(LivingEntity boss, double maxHealth) {
        this.bossId = boss.getUniqueId();
        this.bossMaxHealth = maxHealth;
    }

    public double bossMaxHealth() { return bossMaxHealth; }

    public int bossPhaseIndex() { return bossPhaseIndex; }

    public void setBossPhaseIndex(int idx) { this.bossPhaseIndex = idx; }

    // ---- npc ----
    public UUID npcId() { return npcId; }

    public void setNpc(UUID id) { this.npcId = id; }

    // ---- timing / waves ----
    public int elapsedSeconds() { return elapsedSeconds; }

    public void tickSecond() { elapsedSeconds++; }

    public int wavesSpawned() { return wavesSpawned; }

    public void incWavesSpawned() { wavesSpawned++; }

    public boolean timedOut() { return System.currentTimeMillis() > endMillis; }

    public long elapsedMillis() { return System.currentTimeMillis() - startMillis; }

    // ---- downed / out ----
    public Map<UUID, Integer> downed() { return downed; }

    public boolean isDowned(UUID id) { return downed.containsKey(id); }

    public boolean isOut(UUID id) { return out.contains(id); }

    public void setDowned(UUID id, int ticks, Location returnLoc) {
        downed.put(id, ticks);
        downedReturn.put(id, returnLoc);
    }

    public Location downedReturn(UUID id) { return downedReturn.get(id); }

    public void clearDowned(UUID id) {
        downed.remove(id);
        downedReturn.remove(id);
    }

    public void markOut(UUID id) {
        downed.remove(id);
        out.add(id);
    }

    /** True if every participant is either out or currently downed. */
    public boolean allDownOrOut() {
        if (participants.isEmpty()) return true;
        for (UUID id : participants) {
            if (out.contains(id)) continue;
            if (downed.containsKey(id)) continue;
            Player p = Bukkit.getPlayer(id);
            // A participant who logged off counts as not-active for this check too.
            if (p == null || !p.isOnline()) continue;
            return false; // someone is still up
        }
        return true;
    }

    public boolean anyDeath() { return anyDeath; }

    public void markDeath() { anyDeath = true; }

    // ---- boss bar ----
    public BossBar bossBar() { return bossBar; }

    public void ensureBossBar(String title) {
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar(title, BarColor.PURPLE, BarStyle.SEGMENTED_10);
        } else {
            bossBar.setTitle(title);
        }
    }

    public void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }
    }

    public boolean finished() { return finished; }

    public void setFinished(boolean v) { finished = v; }
}
