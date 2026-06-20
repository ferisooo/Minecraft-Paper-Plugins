package com.ferisooo.kawaiicompanion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

/**
 * Pure-data helpers about the surrounding world that the companion uses
 * for chat context, spotter logic, and pathfinding cost decisions.
 *
 * <p>All methods are read-only and side-effect-free — they just inspect
 * the world's current state. Safe to call frequently from movement/
 * spotter ticks since each call is just a handful of block lookups.
 *
 * <h2>Why this lives in its own class</h2>
 *
 * <p>Most of these helpers are independent of the rest of the plugin
 * (no {@code Companion} state, no DeepSeek API, no NMS reflection). Keeping
 * them isolated makes them easier to reason about and lets the main
 * plugin file stay focused on companion behavior rather than world
 * trivia.
 */
public final class WorldAwareness {

    private WorldAwareness() {}

    // =================== Time / weather ===================

    /** True if it's roughly nighttime at the given world (vanilla 13000–23000 ticks). */
    public static boolean isNight(World w) {
        if (w == null) return false;
        long t = w.getTime();
        return t >= 13000 && t < 23000;
    }

    /** True if rain or thunder is currently active (raises mob spawning + reduces light). */
    public static boolean isRaining(World w) {
        if (w == null) return false;
        return w.hasStorm() || w.isThundering();
    }

    /** Short label like "day", "dusk", "night", "dawn" — for chat context. */
    public static String timeLabel(World w) {
        if (w == null) return "unknown";
        long t = w.getTime();
        if (t < 1000)  return "dawn";
        if (t < 12000) return "day";
        if (t < 13000) return "dusk";
        if (t < 23000) return "night";
        return "dawn";
    }

    // =================== Dimension ===================

    /**
     * Short label for the current dimension. We use the API enum names
     * directly so future Mojang-added dimensions show up sensibly even
     * if we don't have a custom name for them yet.
     */
    public static String dimensionLabel(World w) {
        if (w == null) return "unknown";
        switch (w.getEnvironment()) {
            case NORMAL:    return "overworld";
            case NETHER:    return "nether";
            case THE_END:   return "the end";
            case CUSTOM:    return "custom";
            default:        return w.getEnvironment().name().toLowerCase();
        }
    }

    /**
     * In the Nether, sleeping in a bed explodes — and water buckets
     * evaporate. Spotter code uses this to nudge ambient warnings.
     */
    public static boolean isNether(World w) {
        return w != null && w.getEnvironment() == World.Environment.NETHER;
    }

    /**
     * In the End, water + lava + portals all behave differently. Combat
     * code can use this to reason about Endermen always being present.
     */
    public static boolean isEnd(World w) {
        return w != null && w.getEnvironment() == World.Environment.THE_END;
    }

    // =================== Biome ===================

    /** Return a short, human-friendly biome label for the given location. */
    public static String biomeLabel(Location loc) {
        if (loc == null || loc.getWorld() == null) return "unknown";
        try {
            Biome b = loc.getBlock().getBiome();
            // Try the modern Keyed API first (returns "minecraft:plains" → take "plains").
            try {
                Object key = b.getClass().getMethod("getKey").invoke(b);
                if (key != null) {
                    String full = key.toString();
                    int slash = full.indexOf(':');
                    return slash >= 0 ? full.substring(slash + 1) : full;
                }
            } catch (Throwable ignored) {
                // Older API — fall through.
            }
            return b.toString().toLowerCase();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Coarse temperature category. Cold biomes drop snow; warm biomes
     * spawn husks at night; freezing biomes apply freezing damage to
     * the owner if she's in powder snow without leather armor.
     *
     * <p>We don't trust {@code Biome.getTemperature()} since it varies
     * by biome and was deprecated in favor of per-block temperature in
     * later API versions; we go via the {@code Block.getTemperature()}
     * route which is stable.
     */
    public static String temperatureLabel(Location loc) {
        if (loc == null || loc.getWorld() == null) return "temperate";
        try {
            double t = loc.getBlock().getTemperature();
            if (t <= 0.15) return "freezing";
            if (t <= 0.5)  return "cold";
            if (t <= 0.95) return "temperate";
            return "warm";
        } catch (Throwable th) {
            return "temperate";
        }
    }

    // =================== Light ===================

    /**
     * Light level (0–15) at the location's block. Used for hostile-
     * spawn checks: any block at level &lt; 8 with a sky view at night
     * (or anywhere in the Nether/End) can spawn hostiles.
     */
    public static int lightLevel(Location loc) {
        if (loc == null) return 15;
        return loc.getBlock().getLightLevel();
    }

    /** True if the spot is dark enough that vanilla hostile mobs could spawn here. */
    public static boolean canSpawnHostiles(Location loc) {
        if (loc == null) return false;
        // Quick gates: in the Nether / End hostiles spawn freely; in the
        // Overworld, light level needs to be < 8 (post-1.18 changed this
        // from 7 to 0 via a smooth gradient, but 8 is a safe pessimistic
        // threshold for our spotter use).
        World w = loc.getWorld();
        if (w == null) return false;
        if (w.getEnvironment() != World.Environment.NORMAL) return true;
        return loc.getBlock().getLightLevel() < 8;
    }

    // =================== Structures ===================

    /**
     * Short label for the nearest generated structure within
     * {@code radius} blocks (or {@code null} if none / API unavailable).
     * Used for chat queries like "where's the nearest village?". The
     * underlying {@code locateNearestStructure} is a Paper API method
     * that may be slow and can search across multiple chunks; callers
     * should not invoke it from per-tick paths.
     *
     * <p>We catch any throwable because the method has been moved /
     * renamed across recent Paper versions and we'd rather degrade
     * gracefully than crash the chat handler.
     */
    public static StructureHit nearestStructureLabel(World w, Location origin, int radius) {
        if (w == null || origin == null) return null;
        try {
            // Paper's API: World#locateNearestStructure(Location, StructureType, int, boolean)
            // The returned Location is the structure center; its distance
            // tells us how close the player is to it.
            // We try a couple of common structures; anything that returns
            // gets reported. This is a small whitelist for clarity.
            org.bukkit.StructureType[] tries = {
                    org.bukkit.StructureType.VILLAGE,
                    org.bukkit.StructureType.STRONGHOLD,
                    org.bukkit.StructureType.OCEAN_MONUMENT,
                    org.bukkit.StructureType.WOODLAND_MANSION,
                    org.bukkit.StructureType.NETHER_FORTRESS,
                    org.bukkit.StructureType.END_CITY,
                    org.bukkit.StructureType.IGLOO,
                    org.bukkit.StructureType.JUNGLE_PYRAMID,
                    org.bukkit.StructureType.DESERT_PYRAMID,
                    org.bukkit.StructureType.OCEAN_RUIN,
                    org.bukkit.StructureType.SHIPWRECK,
                    org.bukkit.StructureType.BURIED_TREASURE,
                    org.bukkit.StructureType.SWAMP_HUT,
                    org.bukkit.StructureType.MINESHAFT
            };
            StructureHit best = null;
            for (org.bukkit.StructureType type : tries) {
                if (type == null) continue;
                Location at;
                try {
                    at = w.locateNearestStructure(origin, type, radius, false);
                } catch (Throwable ignored) {
                    continue; // some types throw if the dimension doesn't have them
                }
                if (at == null) continue;
                double d = at.distance(origin);
                if (d > radius) continue;
                if (best == null || d < best.distance) {
                    best = new StructureHit(type.getName(), at, d);
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * #1: locate a SPECIFIC structure named in a free-text query (e.g.
     * "where's the nearest village"). Returns the closest match of that type,
     * or null if the query doesn't name a known structure / none is found
     * within {@code radiusChunks}. The radius is in chunks (Bukkit API).
     */
    public static StructureHit locateNamedStructure(World w, Location origin, String query, int radiusChunks) {
        if (w == null || origin == null || query == null) return null;
        org.bukkit.StructureType type = matchStructureType(query);
        if (type == null) return null;
        try {
            Location at = w.locateNearestStructure(origin, type, radiusChunks, false);
            if (at == null) return null;
            return new StructureHit(type.getName(), at, at.distance(origin));
        } catch (Throwable t) {
            return null; // type not valid for this dimension, or API shifted
        }
    }

    /** True if the text names a structure {@link #matchStructureType} understands. */
    public static boolean mentionsKnownStructure(String query) {
        return matchStructureType(query) != null;
    }

    /** Map common words in a query to a Bukkit StructureType. Null = no match. */
    private static org.bukkit.StructureType matchStructureType(String query) {
        if (query == null) return null;
        String s = query.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("village"))                              return org.bukkit.StructureType.VILLAGE;
        if (s.contains("stronghold"))                           return org.bukkit.StructureType.STRONGHOLD;
        if (s.contains("monument") || s.contains("guardian"))   return org.bukkit.StructureType.OCEAN_MONUMENT;
        if (s.contains("mansion") || s.contains("woodland"))    return org.bukkit.StructureType.WOODLAND_MANSION;
        if (s.contains("fortress"))                             return org.bukkit.StructureType.NETHER_FORTRESS;
        if (s.contains("end city") || s.contains("endcity"))    return org.bukkit.StructureType.END_CITY;
        if (s.contains("igloo"))                                return org.bukkit.StructureType.IGLOO;
        if (s.contains("jungle"))                               return org.bukkit.StructureType.JUNGLE_PYRAMID;
        if (s.contains("desert") || s.contains("pyramid")
                || s.contains("temple"))                        return org.bukkit.StructureType.DESERT_PYRAMID;
        if (s.contains("shipwreck") || s.contains("ship"))      return org.bukkit.StructureType.SHIPWRECK;
        if (s.contains("ocean ruin") || s.contains("ruin"))     return org.bukkit.StructureType.OCEAN_RUIN;
        if (s.contains("treasure"))                             return org.bukkit.StructureType.BURIED_TREASURE;
        if (s.contains("swamp") || s.contains("witch"))         return org.bukkit.StructureType.SWAMP_HUT;
        if (s.contains("mineshaft") || s.contains("mine"))      return org.bukkit.StructureType.MINESHAFT;
        return null;
    }

    /** Human-friendly structure label from a StructureType name. */
    public static String prettyStructure(String typeName) {
        if (typeName == null) return "structure";
        return typeName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /**
     * 8-way compass direction from {@code from} toward {@code to}, in Minecraft
     * terms (+X east, +Z south). Returns e.g. "north", "south-east".
     */
    public static String cardinalDirection(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double ax = Math.abs(dx), az = Math.abs(dz);
        StringBuilder sb = new StringBuilder();
        if (az >= ax * 0.4) sb.append(dz > 0 ? "south" : "north");
        if (ax >= az * 0.4) {
            if (sb.length() > 0) sb.append("-");
            sb.append(dx > 0 ? "east" : "west");
        }
        return sb.length() == 0 ? "here" : sb.toString();
    }

    /** A located structure result. */
    public static final class StructureHit {
        public final String type;
        public final Location location;
        public final double distance;

        StructureHit(String type, Location location, double distance) {
            this.type = type;
            this.location = location;
            this.distance = distance;
        }
    }

    // =================== Block-level helpers ===================

    /**
     * Highly hazardous to walk into — pathfinder should treat as
     * blocked even with tunneling on. Used by both nav and stuck-escape
     * (which would be a really bad idea if it dug INTO lava).
     */
    public static boolean isLethalHazard(Material m) {
        if (m == null) return false;
        switch (m) {
            case LAVA:
            case FIRE:
            case SOUL_FIRE:
            case MAGMA_BLOCK:
                return true;
            default:
                return false;
        }
    }

    /** True if a non-air block above {@code b} would suffocate her if she walked into it. */
    public static boolean wouldSuffocate(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        if (b.isPassable()) return false;
        // Specific allowlist of dense blocks that suffocate is too long
        // to enumerate; the conservative "is solid + occluding" check is
        // captured by Block#isSolid() + isOccluding().
        try {
            return b.getType().isSolid() && b.getType().isOccluding();
        } catch (Throwable t) {
            return !m.isAir();
        }
    }

    /**
     * "Is this block one that drops if its support is removed?" Used by
     * the dig-out logic to refuse digging UP into sand/gravel — that
     * just buries her again.
     */
    public static boolean isFallingBlock(Material m) {
        if (m == null) return false;
        switch (m) {
            case SAND:
            case RED_SAND:
            case GRAVEL:
            case SUSPICIOUS_SAND:
            case SUSPICIOUS_GRAVEL:
            case ANVIL:
            case CHIPPED_ANVIL:
            case DAMAGED_ANVIL:
            case POINTED_DRIPSTONE:
                return true;
            default:
                // Any "concrete powder" block falls. We pattern-match
                // by name to avoid 16 enum cases.
                return m.name().endsWith("_CONCRETE_POWDER");
        }
    }

    /**
     * Coarse "is this a player-built / valuable" block. We refuse to
     * break these in emergency dig mode even if the player has tunnel
     * mode enabled — diamond ore, beacons, chests, redstone components.
     */
    public static boolean isProtectedFromEmergencyDig(Material m) {
        if (m == null) return true;
        // Catch obvious "player thinks of this as mine" categories.
        if (Tag.LOGS.isTagged(m)) return true;
        if (Tag.PLANKS.isTagged(m)) return true;
        if (Tag.DOORS.isTagged(m)) return true;
        if (Tag.SLABS.isTagged(m)) return true;
        if (Tag.STAIRS.isTagged(m)) return true;
        if (Tag.WALLS.isTagged(m)) return true;
        if (Tag.WOOL.isTagged(m)) return true;
        if (Tag.BEDS.isTagged(m)) return true;
        if (Tag.WOODEN_TRAPDOORS.isTagged(m)) return true;
        if (Tag.BANNERS.isTagged(m)) return true;
        switch (m) {
            case BEDROCK:
            case OBSIDIAN:
            case CRYING_OBSIDIAN:
            case CHEST:
            case TRAPPED_CHEST:
            case ENDER_CHEST:
            case BARREL:
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
            case CRAFTING_TABLE:
            case ANVIL:
            case CHIPPED_ANVIL:
            case DAMAGED_ANVIL:
            case ENCHANTING_TABLE:
            case BREWING_STAND:
            case GRINDSTONE:
            case STONECUTTER:
            case LOOM:
            case SMITHING_TABLE:
            case CARTOGRAPHY_TABLE:
            case FLETCHING_TABLE:
            case BEACON:
            case RESPAWN_ANCHOR:
            case LODESTONE:
            case JUKEBOX:
            case NOTE_BLOCK:
            case BELL:
            case END_PORTAL:
            case END_PORTAL_FRAME:
            case NETHER_PORTAL:
            case SPAWNER:
            case DIAMOND_ORE:
            case DEEPSLATE_DIAMOND_ORE:
            case ANCIENT_DEBRIS:
            case EMERALD_ORE:
            case DEEPSLATE_EMERALD_ORE:
            case NETHERITE_BLOCK:
            case DIAMOND_BLOCK:
            case EMERALD_BLOCK:
            case GOLD_BLOCK:
            case IRON_BLOCK:
            case REDSTONE_BLOCK:
            case LAPIS_BLOCK:
            case AMETHYST_BLOCK:
            case BUDDING_AMETHYST:
            case CONDUIT:
            case DRAGON_EGG:
            case PISTON:
            case STICKY_PISTON:
            case PISTON_HEAD:
            case OBSERVER:
            case DISPENSER:
            case DROPPER:
            case HOPPER:
            case REPEATER:
            case COMPARATOR:
            case REDSTONE_WIRE:
            case REDSTONE_LAMP:
                return true;
            default:
                return false;
        }
    }

    /**
     * Soft natural blocks she's allowed to dig through in emergency
     * mode even when the configured tunneling flag is off. This is a
     * superset of the pathfinder's {@code isBreakableTerrain} list,
     * intentionally — escape mode is a "last resort" so the dirt-grade
     * filter is a bit more permissive.
     */
    public static boolean isEmergencyDiggable(Material m) {
        if (m == null) return false;
        if (isProtectedFromEmergencyDig(m)) return false;
        if (CompanionPathfinder.isBreakableTerrain(m)) return true;
        if (CompanionPathfinder.isBreakablePlant(m)) return true;
        switch (m) {
            // Cobble, andesite, granite, diorite — natural stone she
            // generated into. Allowed in emergency only.
            case STONE:
            case COBBLESTONE:
            case ANDESITE:
            case DIORITE:
            case GRANITE:
            case TUFF:
            case DEEPSLATE:
            case COBBLED_DEEPSLATE:
            case CALCITE:
            case NETHERRACK:
            case END_STONE:
            case BLACKSTONE:
            case BASALT:
            case SMOOTH_BASALT:
                return true;
            default:
                return false;
        }
    }
}
