package com.ferisooo.kawaiidungeons;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An immutable definition of one dungeon, parsed from dungeons.yml. Locations
 * are stored as raw coordinates and resolved against a concrete instance world
 * at runtime (each run uses a freshly cloned world).
 */
public final class DungeonDef {

    /** A spawn definition for a group of mobs of a given tier. */
    public static final class MobSpawn {
        public final String type;       // EntityType name
        public final String tier;       // ELITE / MINIBOSS / BOSS
        public final int count;
        public final double x, y, z;

        public MobSpawn(String type, String tier, int count, double x, double y, double z) {
            this.type = type;
            this.tier = tier;
            this.count = count;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Location at(World w) { return new Location(w, x, y, z); }
    }

    /** A timed wave of mob spawns. */
    public static final class Wave {
        public final int delaySeconds;
        public final List<MobSpawn> mobs;

        public Wave(int delaySeconds, List<MobSpawn> mobs) {
            this.delaySeconds = delaySeconds;
            this.mobs = mobs;
        }
    }

    /** A boss phase with a health-percent range and a list of ability ids. */
    public static final class Phase {
        public final int from;   // upper bound percent (inclusive)
        public final int to;     // lower bound percent
        public final List<String> abilities;

        public Phase(int from, int to, List<String> abilities) {
            this.from = from;
            this.to = to;
            this.abilities = abilities;
        }
    }

    public final String id;
    public final String displayName;
    public final String templateWorld;
    public final double spawnX, spawnY, spawnZ;
    public final float spawnYaw, spawnPitch;
    public final int levelRequirement;
    public final int gearScoreRequirement;
    public final int timeLimitSeconds;

    public final Objective.Type objectiveType;
    public final int objectiveAmount;
    public final int objectiveDuration;
    public final double goalX, goalY, goalZ;
    public final double npcX, npcY, npcZ;

    public final List<MobSpawn> mobs;
    public final List<Wave> waves;

    public final String bossType;
    public final double bossBaseHealth;
    public final double bossX, bossY, bossZ;
    public final List<Phase> bossPhases;

    public final String lootTable;
    public final int tokenReward;
    public final int reputationReward;

    private DungeonDef(String id, ConfigurationSection s) {
        this.id = id;
        this.displayName = s.getString("display-name", id);
        this.templateWorld = s.getString("template-world", "");
        ConfigurationSection sp = s.getConfigurationSection("spawn");
        this.spawnX = sp != null ? sp.getDouble("x", 0.5) : 0.5;
        this.spawnY = sp != null ? sp.getDouble("y", 64.0) : 64.0;
        this.spawnZ = sp != null ? sp.getDouble("z", 0.5) : 0.5;
        this.spawnYaw = sp != null ? (float) sp.getDouble("yaw", 0.0) : 0f;
        this.spawnPitch = sp != null ? (float) sp.getDouble("pitch", 0.0) : 0f;
        this.levelRequirement = s.getInt("level-requirement", 0);
        this.gearScoreRequirement = s.getInt("gear-score-requirement", 0);
        this.timeLimitSeconds = s.getInt("time-limit-seconds", 600);

        ConfigurationSection obj = s.getConfigurationSection("objective");
        Objective.Type type = Objective.Type.KILL_BOSS;
        int amount = 1, duration = 120;
        double gx = 0, gy = 64, gz = 0, nx = 0, ny = 64, nz = 0;
        if (obj != null) {
            try {
                type = Objective.Type.valueOf(obj.getString("type", "KILL_BOSS").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) { /* keep default */ }
            amount = obj.getInt("amount", 1);
            duration = obj.getInt("duration-seconds", 120);
            ConfigurationSection g = obj.getConfigurationSection("goal");
            if (g != null) { gx = g.getDouble("x"); gy = g.getDouble("y"); gz = g.getDouble("z"); }
            ConfigurationSection n = obj.getConfigurationSection("npc");
            if (n != null) { nx = n.getDouble("x"); ny = n.getDouble("y"); nz = n.getDouble("z"); }
        }
        this.objectiveType = type;
        this.objectiveAmount = amount;
        this.objectiveDuration = duration;
        this.goalX = gx; this.goalY = gy; this.goalZ = gz;
        this.npcX = nx; this.npcY = ny; this.npcZ = nz;

        this.mobs = parseMobList(s.getMapList("mobs"));
        this.waves = parseWaves(s.getMapList("waves"));

        ConfigurationSection boss = s.getConfigurationSection("boss");
        if (boss != null) {
            this.bossType = boss.getString("type", "WITHER_SKELETON");
            this.bossBaseHealth = boss.getDouble("base-health", 200.0);
            ConfigurationSection bl = boss.getConfigurationSection("location");
            this.bossX = bl != null ? bl.getDouble("x", 0.5) : 0.5;
            this.bossY = bl != null ? bl.getDouble("y", 64.0) : 64.0;
            this.bossZ = bl != null ? bl.getDouble("z", 0.5) : 0.5;
            this.bossPhases = parsePhases(boss.getMapList("phases"));
        } else {
            this.bossType = null;
            this.bossBaseHealth = 0;
            this.bossX = this.bossY = this.bossZ = 0;
            this.bossPhases = new ArrayList<>();
        }

        this.lootTable = s.getString("loot-table", "");
        this.tokenReward = s.getInt("token-reward", 0);
        this.reputationReward = s.getInt("reputation-reward", 0);
    }

    public static DungeonDef parse(String id, ConfigurationSection s) {
        return new DungeonDef(id, s);
    }

    public boolean hasBoss() { return bossType != null; }

    public Location spawn(World w) {
        return new Location(w, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
    }

    public Location bossLoc(World w) { return new Location(w, bossX, bossY, bossZ); }

    public Location goalLoc(World w) { return new Location(w, goalX, goalY, goalZ); }

    public Location npcLoc(World w) { return new Location(w, npcX, npcY, npcZ); }

    @SuppressWarnings("unchecked")
    private static List<MobSpawn> parseMobList(List<?> raw) {
        List<MobSpawn> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            String type = m.get("type") == null ? "ZOMBIE" : m.get("type").toString();
            String tier = m.get("tier") == null ? "ELITE" : m.get("tier").toString();
            int count = toInt(m.get("count"), 1);
            Object locObj = m.get("location");
            double[] loc = parseLoc(locObj instanceof java.util.Map<?, ?> lm ? lm : null);
            out.add(new MobSpawn(type, tier, count, loc[0], loc[1], loc[2]));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Wave> parseWaves(List<?> raw) {
        List<Wave> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            int delay = toInt(m.get("delay-seconds"), 0);
            List<?> mobsRaw = (List<?>) m.get("mobs");
            out.add(new Wave(delay, parseMobList(mobsRaw)));
        }
        return out;
    }

    private static List<Phase> parsePhases(List<?> raw) {
        List<Phase> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            int from = toInt(m.get("from"), 100);
            int to = toInt(m.get("to"), 0);
            List<String> abilities = new ArrayList<>();
            Object ab = m.get("abilities");
            if (ab instanceof List<?> list) {
                for (Object a : list) abilities.add(String.valueOf(a));
            }
            out.add(new Phase(from, to, abilities));
        }
        return out;
    }

    private static double[] parseLoc(java.util.Map<?, ?> m) {
        if (m == null) return new double[]{0.5, 64.0, 0.5};
        return new double[]{
                toDouble(m.get("x"), 0.5),
                toDouble(m.get("y"), 64.0),
                toDouble(m.get("z"), 0.5)
        };
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(o.toString()); }
        catch (NumberFormatException e) { return def; }
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return def; }
    }
}
