package com.ferisooo.kawaiiclaims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A land claim: a SET of connected 16x16 chunks owned by one player.
 * Each chunk is keyed "world;chunkX;chunkZ". The claim itself has a stable
 * String id. Trust/flags/home/greeting/farewell are stored per claim (not per chunk).
 */
public class Claim {

    private final String id;
    private UUID owner;

    /** Member chunks, each "world;cx;cz". */
    private final Set<String> chunkKeys = new HashSet<>();

    private final Map<UUID, TrustLevel> trust = new HashMap<>();
    private final Map<String, Boolean> flags = new HashMap<>();

    /**
     * Per-claim role->permission overrides. Outer key is a role name
     * ("visitor","access","container","build","manage"); inner map is
     * permissionKey -> granted. Absent entries fall back to config defaults.
     */
    private final Map<String, Map<String, Boolean>> rolePerms = new HashMap<>();

    // optional home (stored as components so we never hold a stale World ref)
    private boolean hasHome = false;
    private double homeX, homeY, homeZ;
    private float homeYaw, homePitch;

    private String greeting = "";
    private String farewell = "";

    private long createdAt;
    private long lastActive;

    public Claim(String id, UUID owner) {
        this.id = id;
        this.owner = owner;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastActive = now;
    }

    /** Build the canonical chunk key. */
    public static String makeKey(String world, int chunkX, int chunkZ) {
        return world + ";" + chunkX + ";" + chunkZ;
    }

    public String getId() { return id; }

    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }

    // -----------------------------------------------------------------
    //  Chunk set
    // -----------------------------------------------------------------
    public Set<String> getChunkKeys() { return chunkKeys; }

    public int getChunkCount() { return chunkKeys.size(); }

    public boolean containsChunk(String key) { return chunkKeys.contains(key); }

    public boolean containsChunk(String world, int cx, int cz) {
        return chunkKeys.contains(makeKey(world, cx, cz));
    }

    public void addChunk(String key) { chunkKeys.add(key); }

    public void addChunk(String world, int cx, int cz) { chunkKeys.add(makeKey(world, cx, cz)); }

    public void removeChunk(String key) { chunkKeys.remove(key); }

    public boolean isEmpty() { return chunkKeys.isEmpty(); }

    /** Any one chunk key (used for menus / borders). Returns null if empty. */
    public String anyChunkKey() {
        for (String k : chunkKeys) return k;
        return null;
    }

    /** The world of this claim (derived from any member chunk). Null if empty. */
    public String getWorld() {
        String k = anyChunkKey();
        if (k == null) return null;
        int i = k.indexOf(';');
        return i < 0 ? null : k.substring(0, i);
    }

    // -----------------------------------------------------------------
    //  Trust
    // -----------------------------------------------------------------
    public Map<UUID, TrustLevel> getTrust() { return trust; }
    public Map<String, Boolean> getFlags() { return flags; }

    public TrustLevel getTrustLevel(UUID uuid) { return trust.get(uuid); }

    public void setTrust(UUID uuid, TrustLevel level) {
        if (level == null) trust.remove(uuid);
        else trust.put(uuid, level);
    }

    public void removeTrust(UUID uuid) { trust.remove(uuid); }

    /** Get a flag value, falling back to the supplied default. */
    public boolean getFlag(String name, boolean def) {
        return flags.getOrDefault(name, def);
    }

    public void setFlag(String name, boolean value) {
        flags.put(name, value);
    }

    // -----------------------------------------------------------------
    //  Role permission overrides
    // -----------------------------------------------------------------
    public Map<String, Map<String, Boolean>> getRolePerms() { return rolePerms; }

    /** Per-claim override for (role, permission), or null if not overridden. */
    public Boolean getRolePermOverride(String role, String perm) {
        Map<String, Boolean> m = rolePerms.get(role);
        if (m == null) return null;
        return m.get(perm);
    }

    public void setRolePerm(String role, String perm, boolean value) {
        rolePerms.computeIfAbsent(role, k -> new HashMap<>()).put(perm, value);
    }

    public void clearRolePerm(String role, String perm) {
        Map<String, Boolean> m = rolePerms.get(role);
        if (m != null) {
            m.remove(perm);
            if (m.isEmpty()) rolePerms.remove(role);
        }
    }

    public boolean hasHome() { return hasHome; }

    public Location getHome() {
        if (!hasHome) return null;
        String world = getWorld();
        if (world == null) return null;
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    public void setHome(Location loc) {
        if (loc == null) {
            hasHome = false;
            return;
        }
        hasHome = true;
        homeX = loc.getX();
        homeY = loc.getY();
        homeZ = loc.getZ();
        homeYaw = loc.getYaw();
        homePitch = loc.getPitch();
    }

    // raw home accessors for persistence
    public double getHomeX() { return homeX; }
    public double getHomeY() { return homeY; }
    public double getHomeZ() { return homeZ; }
    public float getHomeYaw() { return homeYaw; }
    public float getHomePitch() { return homePitch; }

    public void setHomeRaw(double x, double y, double z, float yaw, float pitch) {
        this.hasHome = true;
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        this.homeYaw = yaw;
        this.homePitch = pitch;
    }

    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting == null ? "" : greeting; }

    public String getFarewell() { return farewell; }
    public void setFarewell(String farewell) { this.farewell = farewell == null ? "" : farewell; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastActive() { return lastActive; }
    public void setLastActive(long lastActive) { this.lastActive = lastActive; }

    public void touch() { this.lastActive = System.currentTimeMillis(); }
}
