package com.ferisooo.kawaiiclaims;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns all claim data, persistence (claims.yml), lookup helpers,
 * limits, the chunk index, flag defaults, and visualization.
 *
 * A claim is a SET of connected chunks. Two indexes are maintained:
 *   byId          : claim id -> Claim
 *   chunkToClaimId: "world;cx;cz" -> claim id (reverse index)
 */
public class ClaimManager {

    private final KawaiiClaims plugin;

    private final Map<String, Claim> byId = new HashMap<>();
    private final Map<String, String> chunkToClaimId = new HashMap<>();

    // per-player bonus chunk grants (persisted under bonus-chunks.<uuid>)
    private final Map<UUID, Integer> bonusChunks = new HashMap<>();

    private File claimsFile;
    private YamlConfiguration claimsYaml;

    private long idCounter = 0L;

    // ordered list of recognised flags (data-driven)
    private final List<String> flagNames = new ArrayList<>();
    private final Map<String, Boolean> flagDefaults = new LinkedHashMap<>();

    // config-driven flag presets: preset name -> (flag -> value)
    private final Map<String, Map<String, Boolean>> presets = new LinkedHashMap<>();

    // players currently toggled into a persistent border display
    private final Set<UUID> borderToggled = new HashSet<>();

    // ordered list of recognised member permissions (data-driven)
    private final List<String> permNames = new ArrayList<>();
    // role name -> (permission -> granted by default), loaded from config "roles:"
    private final Map<String, Map<String, Boolean>> roleDefaults = new LinkedHashMap<>();

    public ClaimManager(KawaiiClaims plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------
    //  Flag definitions (data-driven from config flag-defaults block)
    // -----------------------------------------------------------------
    public void loadFlagDefinitions() {
        flagNames.clear();
        flagDefaults.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("flag-defaults");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                flagNames.add(key);
                flagDefaults.put(key, sec.getBoolean(key));
            }
        }
        // safety fallback so the plugin always has the FULL flag set even if
        // the config block is missing/empty. Defaults mirror config.yml.
        if (flagNames.isEmpty()) {
            // curated set of exactly 16 flags, in canonical order. The boolean is
            // the built-in default (protective/destructive OFF, natural ON).
            addDefault("pvp", false);
            addDefault("tnt_explosions", false);
            addDefault("creeper_explosions", false);
            addDefault("explosion_block_damage", false);
            addDefault("fire_spread", false);
            addDefault("fire_burn", false);
            addDefault("fire_ignite", false);
            addDefault("entity_grief", false);
            addDefault("lightning_strike", false);
            addDefault("leaf_decay", true);
            addDefault("crop_growth", true);
            addDefault("liquids_flow", true);
            addDefault("fall_damage", true);
            addDefault("natural_breeding", true);
            addDefault("weather_change", false);
            addDefault("portal_create", false);
        }
        loadPermissionDefinitions();
        loadPresets();
    }

    // -----------------------------------------------------------------
    //  Flag presets (data-driven from config "presets:" block)
    // -----------------------------------------------------------------
    public void loadPresets() {
        presets.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("presets");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection ps = sec.getConfigurationSection(name);
            if (ps == null) continue;
            Map<String, Boolean> map = new LinkedHashMap<>();
            for (String flag : ps.getKeys(false)) {
                if (isKnownFlag(flag)) map.put(flag, ps.getBoolean(flag));
            }
            if (!map.isEmpty()) presets.put(name.toLowerCase(), map);
        }
    }

    /** Ordered preset names. */
    public List<String> getPresetNames() { return new ArrayList<>(presets.keySet()); }

    public boolean isKnownPreset(String name) {
        return name != null && presets.containsKey(name.toLowerCase());
    }

    public Map<String, Boolean> getPreset(String name) {
        if (name == null) return null;
        return presets.get(name.toLowerCase());
    }

    /** Apply a preset's flag values to a claim (only listed flags change). Saves. */
    public boolean applyPreset(Claim claim, String name) {
        Map<String, Boolean> map = getPreset(name);
        if (claim == null || map == null) return false;
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            claim.setFlag(e.getKey(), e.getValue());
        }
        touchAndSave(claim);
        return true;
    }

    private void addDefault(String name, boolean val) {
        flagNames.add(name);
        flagDefaults.put(name, val);
    }

    // -----------------------------------------------------------------
    //  Member permission registry (data-driven from config "roles:" block)
    // -----------------------------------------------------------------
    /**
     * The canonical, ordered list of member permission keys (SCS parity).
     * The actual role->permission DEFAULTS come from the config "roles:" block.
     */
    private static final String[] PERMISSION_KEYS = {
            "destroy_block", "place_block", "interact_door", "interact_trapdoor",
            "interact_fence_gate", "interact_button", "interact_lever",
            "interact_pressure_plate", "interact_chest", "interact_barrel",
            "interact_shulker_box", "interact_furnace", "interact_hopper",
            "interact_anvil", "interact_enchanting_table", "interact_crafting_table",
            "interact_brewing_stand", "interact_beacon", "interact_bed",
            "interact_item_frame", "interact_armor_stand", "interact_vehicle",
            "interact_villager", "interact_entity", "destroy_item_frame",
            "destroy_armor_stand", "destroy_vehicle", "damage_entity", "breed_entity",
            "tame_entity", "shear_entity", "trample_crops", "pickup_item", "drop_item",
            "use_bucket", "use_ender_pearl", "use_elytra", "eat" };

    public void loadPermissionDefinitions() {
        permNames.clear();
        roleDefaults.clear();
        // canonical key set (order is fixed by PERMISSION_KEYS)
        for (String k : PERMISSION_KEYS) permNames.add(k);

        ConfigurationSection roles = plugin.getConfig().getConfigurationSection("roles");
        for (String role : TrustLevel.ROLE_NAMES) {
            Map<String, Boolean> map = new LinkedHashMap<>();
            ConfigurationSection rs = roles == null ? null : roles.getConfigurationSection(role);
            for (String perm : permNames) {
                boolean def = defaultGrant(role, perm);
                if (rs != null && rs.isBoolean(perm)) def = rs.getBoolean(perm);
                map.put(perm, def);
            }
            roleDefaults.put(role, map);
        }
    }

    /**
     * Hard-coded fallback role->permission default when config is missing.
     * Roles are cumulative: access < container < build < manage. visitor gets
     * only harmless self-affecting permissions.
     */
    private boolean defaultGrant(String role, String perm) {
        int rank = roleRank(role);
        // self-only / harmless actions everyone (incl. visitor) may do
        switch (perm) {
            case "interact_ender_chest":
            case "enter":
            case "leave":
            case "eat":
            case "drink":
            case "use_elytra":
            case "use_firework":
            case "use_spyglass":
            case "use_bundle":
            case "use_map":
            case "ride_entity":
            case "mount_entity":
                return true;
            default:
                break;
        }
        // ACCESS (rank>=1): doors/buttons/levers/plates/simple interactables/vehicles
        if (perm.startsWith("interact_door") || perm.startsWith("interact_trapdoor")
                || perm.equals("interact_fence_gate") || perm.equals("interact_button")
                || perm.equals("interact_lever") || perm.equals("interact_pressure_plate")
                || perm.equals("interact_bell") || perm.equals("interact_jukebox")
                || perm.equals("interact_note_block") || perm.equals("interact_repeater")
                || perm.equals("interact_comparator") || perm.equals("interact_daylight_detector")
                || perm.equals("interact_crafting_table") || perm.equals("interact_enchanting_table")
                || perm.equals("interact_grindstone") || perm.equals("interact_stonecutter")
                || perm.equals("interact_loom") || perm.equals("interact_cartography_table")
                || perm.equals("interact_smithing_table") || perm.equals("interact_anvil")
                || perm.equals("interact_lectern_read") || perm.equals("interact_bookshelf")
                || perm.equals("interact_composter") || perm.equals("interact_cauldron")
                || perm.equals("interact_campfire") || perm.equals("interact_candle")
                || perm.equals("interact_flower_pot") || perm.equals("interact_vehicle")
                || perm.equals("ride_entity") || perm.equals("sleep")
                || perm.equals("use_fishing_rod") || perm.equals("use_bow_crossbow")
                || perm.equals("use_ender_pearl") || perm.equals("use_chorus_fruit")
                || perm.equals("use_potion") || perm.equals("use_egg") || perm.equals("use_snowball")
                || perm.equals("use_trident") || perm.equals("use_shield") || perm.equals("use_brush")
                || perm.equals("use_portal") || perm.equals("equip_armor")
                || perm.equals("trade_wandering_trader") || perm.equals("interact_villager")
                || perm.equals("interact_entity")) {
            return rank >= 1;
        }
        // CONTAINER (rank>=2): every container + item frame touch + pickup/drop
        if (perm.startsWith("interact_chest") || perm.equals("interact_trap_chest")
                || perm.equals("interact_barrel") || perm.equals("interact_shulker_box")
                || perm.equals("interact_furnace") || perm.equals("interact_blast_furnace")
                || perm.equals("interact_smoker") || perm.equals("interact_hopper")
                || perm.equals("interact_dropper") || perm.equals("interact_dispenser")
                || perm.equals("interact_brewing_stand") || perm.equals("interact_beacon")
                || perm.equals("interact_lectern_take") || perm.equals("interact_decorated_pot")
                || perm.equals("interact_vault") || perm.equals("interact_item_frame")
                || perm.equals("pickup_item") || perm.equals("drop_item")
                || perm.equals("use_bucket") || perm.equals("shear_entity")
                || perm.equals("milk_entity") || perm.equals("feed_entity")
                || perm.equals("breed_entity") || perm.equals("lead_entity")
                || perm.equals("name_tag_entity") || perm.equals("tame_entity")
                || perm.equals("capture_entity") || perm.equals("use_fire_charge")
                || perm.equals("use_wind_charge")) {
            return rank >= 2;
        }
        // MANAGE only: fly
        if (perm.equals("fly")) return rank >= 4;
        // everything else (build, destroy, place, ignite, trample, frostwalker, etc.) -> BUILD
        return rank >= 3;
    }

    private int roleRank(String role) {
        switch (role) {
            case "visitor": return 0;
            case "access": return 1;
            case "container": return 2;
            case "build": return 3;
            case "manage": return 4;
            default: return 0;
        }
    }

    public List<String> getPermissionNames() { return permNames; }

    public boolean isKnownPermission(String name) { return permNames.contains(name); }

    /** Default grant for a (role, permission) after config merge. */
    public boolean roleDefaultGrant(String role, String perm) {
        Map<String, Boolean> m = roleDefaults.get(role);
        if (m == null) return false;
        return m.getOrDefault(perm, false);
    }

    /** Effective grant for (role, permission): claim override else config default. */
    public boolean roleGrants(Claim claim, String role, String perm) {
        if (claim != null) {
            Boolean ov = claim.getRolePermOverride(role, perm);
            if (ov != null) return ov;
        }
        return roleDefaultGrant(role, perm);
    }

    /**
     * Whether a player is allowed to use a permission inside a claim.
     * Owner and admin-bypass always allowed. Otherwise the player's role
     * (their trust level, or "visitor") must grant the permission.
     */
    public boolean permissionAllowed(Player player, Claim claim, String perm) {
        if (claim == null) return true; // wilderness
        if (plugin.isBypassing(player)) return true;
        UUID id = player.getUniqueId();
        if (claim.getOwner().equals(id)) return true;
        TrustLevel lvl = claim.getTrustLevel(id);
        String role = TrustLevel.roleNameOf(lvl);
        return roleGrants(claim, role, perm);
    }

    public List<String> getFlagNames() { return flagNames; }

    public boolean isKnownFlag(String name) { return flagDefaults.containsKey(name); }

    public boolean getFlagDefault(String name) { return flagDefaults.getOrDefault(name, false); }

    /** Effective flag value for a claim, using config default as fallback. */
    public boolean flag(Claim claim, String name) {
        return claim.getFlag(name, getFlagDefault(name));
    }

    // -----------------------------------------------------------------
    //  Persistence
    // -----------------------------------------------------------------
    public void load() {
        byId.clear();
        chunkToClaimId.clear();
        bonusChunks.clear();
        idCounter = 0L;
        claimsFile = new File(plugin.getDataFolder(), "claims.yml");
        if (!claimsFile.exists()) {
            claimsYaml = new YamlConfiguration();
            return;
        }
        claimsYaml = YamlConfiguration.loadConfiguration(claimsFile);

        // bonus chunk grants
        ConfigurationSection bonusSec = claimsYaml.getConfigurationSection("bonus-chunks");
        if (bonusSec != null) {
            for (String u : bonusSec.getKeys(false)) {
                try {
                    bonusChunks.put(UUID.fromString(u), bonusSec.getInt(u));
                } catch (IllegalArgumentException ignored) { }
            }
        }

        ConfigurationSection root = claimsYaml.getConfigurationSection("claims");
        if (root == null) {
            plugin.getLogger().info("Loaded 0 claim(s).");
            return;
        }

        boolean migrated = false;
        for (String key : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(key);
            if (c == null) continue;
            try {
                // Old format detection: a per-chunk claim stored explicit world/chunkX/chunkZ
                // and NO "chunks" list. New format stores a "chunks" list.
                boolean oldFormat = !c.isList("chunks")
                        && c.contains("world")
                        && c.contains("chunkX");
                Claim claim;
                if (oldFormat) {
                    claim = deserializeOld(key, c);
                    migrated = true;
                } else {
                    claim = deserializeNew(key, c);
                }
                if (claim != null && !claim.isEmpty()) {
                    registerLoaded(claim);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipping malformed claim '" + key + "': " + ex.getMessage());
            }
        }

        if (migrated) {
            plugin.getLogger().info("Migrating old per-chunk claims.yml to multi-chunk format...");
            autoMergeAll();
            save();
        }
        plugin.getLogger().info("Loaded " + byId.size() + " claim(s).");
    }

    /** Register a claim loaded from disk into both indexes (no save). */
    private void registerLoaded(Claim claim) {
        byId.put(claim.getId(), claim);
        for (String ck : claim.getChunkKeys()) {
            chunkToClaimId.put(ck, claim.getId());
        }
        bumpIdCounter(claim.getId());
    }

    private void bumpIdCounter(String id) {
        if (id != null && id.startsWith("c")) {
            try {
                long n = Long.parseLong(id.substring(1));
                if (n >= idCounter) idCounter = n + 1;
            } catch (NumberFormatException ignored) { }
        }
    }

    private void readCommon(Claim claim, ConfigurationSection c) {
        ConfigurationSection trustSec = c.getConfigurationSection("trust");
        if (trustSec != null) {
            for (String u : trustSec.getKeys(false)) {
                TrustLevel lvl = TrustLevel.fromString(trustSec.getString(u));
                if (lvl != null) claim.setTrust(UUID.fromString(u), lvl);
            }
        }
        ConfigurationSection flagSec = c.getConfigurationSection("flags");
        if (flagSec != null) {
            for (String f : flagSec.getKeys(false)) {
                claim.setFlag(f, flagSec.getBoolean(f));
            }
        }
        ConfigurationSection permSec = c.getConfigurationSection("perms");
        if (permSec != null) {
            for (String role : permSec.getKeys(false)) {
                ConfigurationSection rs = permSec.getConfigurationSection(role);
                if (rs == null) continue;
                for (String perm : rs.getKeys(false)) {
                    claim.setRolePerm(role, perm, rs.getBoolean(perm));
                }
            }
        }
        if (c.getBoolean("home.set", false)) {
            claim.setHomeRaw(
                    c.getDouble("home.x"),
                    c.getDouble("home.y"),
                    c.getDouble("home.z"),
                    (float) c.getDouble("home.yaw"),
                    (float) c.getDouble("home.pitch"));
        }
        claim.setGreeting(c.getString("greeting", ""));
        claim.setFarewell(c.getString("farewell", ""));
        claim.setCreatedAt(c.getLong("createdAt", System.currentTimeMillis()));
        claim.setLastActive(c.getLong("lastActive", System.currentTimeMillis()));
    }

    /** New multi-chunk format. */
    private Claim deserializeNew(String key, ConfigurationSection c) {
        String ownerStr = c.getString("owner");
        if (ownerStr == null) return null;
        UUID owner = UUID.fromString(ownerStr);
        Claim claim = new Claim(key, owner);
        for (String ck : c.getStringList("chunks")) {
            if (ck != null && !ck.isEmpty()) claim.addChunk(ck);
        }
        readCommon(claim, c);
        return claim;
    }

    /** Legacy single-chunk format: wrap into a one-chunk claim. */
    private Claim deserializeOld(String key, ConfigurationSection c) {
        String world = c.getString("world");
        String ownerStr = c.getString("owner");
        if (world == null || ownerStr == null) return null;
        int cx = c.getInt("chunkX");
        int cz = c.getInt("chunkZ");
        UUID owner = UUID.fromString(ownerStr);
        // generate a fresh id (old keys were arbitrary)
        Claim claim = new Claim(nextId(), owner);
        claim.addChunk(Claim.makeKey(world, cx, cz));
        readCommon(claim, c);
        return claim;
    }

    public String nextId() {
        return "c" + (idCounter++);
    }

    public void save() {
        if (claimsFile == null) {
            claimsFile = new File(plugin.getDataFolder(), "claims.yml");
        }
        YamlConfiguration out = new YamlConfiguration();
        for (Claim claim : byId.values()) {
            if (claim.isEmpty()) continue;
            String path = "claims." + claim.getId();
            out.set(path + ".owner", claim.getOwner().toString());
            out.set(path + ".chunks", new ArrayList<>(claim.getChunkKeys()));
            for (Map.Entry<UUID, TrustLevel> e : claim.getTrust().entrySet()) {
                out.set(path + ".trust." + e.getKey(), e.getValue().name());
            }
            for (Map.Entry<String, Boolean> e : claim.getFlags().entrySet()) {
                out.set(path + ".flags." + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Map<String, Boolean>> re : claim.getRolePerms().entrySet()) {
                for (Map.Entry<String, Boolean> pe : re.getValue().entrySet()) {
                    out.set(path + ".perms." + re.getKey() + "." + pe.getKey(), pe.getValue());
                }
            }
            if (claim.hasHome()) {
                out.set(path + ".home.set", true);
                out.set(path + ".home.x", claim.getHomeX());
                out.set(path + ".home.y", claim.getHomeY());
                out.set(path + ".home.z", claim.getHomeZ());
                out.set(path + ".home.yaw", claim.getHomeYaw());
                out.set(path + ".home.pitch", claim.getHomePitch());
            }
            out.set(path + ".greeting", claim.getGreeting());
            out.set(path + ".farewell", claim.getFarewell());
            out.set(path + ".createdAt", claim.getCreatedAt());
            out.set(path + ".lastActive", claim.getLastActive());
        }
        for (Map.Entry<UUID, Integer> e : bonusChunks.entrySet()) {
            if (e.getValue() != null && e.getValue() != 0) {
                out.set("bonus-chunks." + e.getKey(), e.getValue());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            out.save(claimsFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save claims.yml: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Lookup
    // -----------------------------------------------------------------
    public Claim claimAt(String world, int chunkX, int chunkZ) {
        String id = chunkToClaimId.get(Claim.makeKey(world, chunkX, chunkZ));
        return id == null ? null : byId.get(id);
    }

    public Claim getClaimAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        Chunk ch = loc.getChunk();
        return claimAt(loc.getWorld().getName(), ch.getX(), ch.getZ());
    }

    public Claim getClaimById(String id) { return id == null ? null : byId.get(id); }

    public Claim getClaimInChunk(Chunk chunk) {
        return claimAt(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public boolean isChunkClaimed(String world, int cx, int cz) {
        return chunkToClaimId.containsKey(Claim.makeKey(world, cx, cz));
    }

    /** Number of distinct claims. */
    public int getClaimCount() { return byId.size(); }

    /** Total claimed chunks across all claims. */
    public int getChunkCount() { return chunkToClaimId.size(); }

    public Collection<Claim> getAllClaims() { return new ArrayList<>(byId.values()); }

    public List<Claim> getClaimsOf(UUID owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : byId.values()) {
            if (c.getOwner().equals(owner)) out.add(c);
        }
        return out;
    }

    /** Number of distinct claims owned by a player. */
    public int countClaims(UUID owner) {
        int n = 0;
        for (Claim c : byId.values()) {
            if (c.getOwner().equals(owner)) n++;
        }
        return n;
    }

    /** Total chunks owned by a player across all their claims. */
    public int countChunks(UUID owner) {
        int n = 0;
        for (Claim c : byId.values()) {
            if (c.getOwner().equals(owner)) n += c.getChunkCount();
        }
        return n;
    }

    public Map<UUID, Integer> chunksPerOwner() {
        Map<UUID, Integer> out = new HashMap<>();
        for (Claim c : byId.values()) {
            out.merge(c.getOwner(), c.getChunkCount(), Integer::sum);
        }
        return out;
    }

    // -----------------------------------------------------------------
    //  Reverse-index maintenance
    // -----------------------------------------------------------------
    private void indexChunks(Claim claim) {
        for (String ck : claim.getChunkKeys()) {
            chunkToClaimId.put(ck, claim.getId());
        }
    }

    private void unindexChunks(Claim claim) {
        for (String ck : claim.getChunkKeys()) {
            if (claim.getId().equals(chunkToClaimId.get(ck))) {
                chunkToClaimId.remove(ck);
            }
        }
    }

    private String[] parseKey(String key) {
        int a = key.indexOf(';');
        int b = key.lastIndexOf(';');
        if (a < 0 || b <= a) return null;
        return new String[]{ key.substring(0, a), key.substring(a + 1, b), key.substring(b + 1) };
    }

    // -----------------------------------------------------------------
    //  Limits
    // -----------------------------------------------------------------
    public boolean isWorldEnabled(String worldName) {
        List<String> worlds = plugin.getConfig().getStringList("enabled-worlds");
        return worlds.isEmpty() || worlds.contains(worldName);
    }

    public int getMaxChunksPerPlayer() {
        return plugin.getConfig().getInt("max-chunks-per-player", 16);
    }

    public int getMaxRadius() {
        return plugin.getConfig().getInt("max-radius", 5);
    }

    public int getBonusChunks(UUID owner) {
        return bonusChunks.getOrDefault(owner, 0);
    }

    public void setBonusChunks(UUID owner, int amount) {
        bonusChunks.put(owner, amount);
        save();
    }

    public void addBonusChunks(UUID owner, int delta) {
        bonusChunks.merge(owner, delta, Integer::sum);
        save();
    }

    /** Effective chunk limit for a player (base + bonus). */
    public int getChunkLimit(UUID owner) {
        return getMaxChunksPerPlayer() + getBonusChunks(owner);
    }

    public boolean isExempt(Player player) {
        return player.hasPermission("kawaiiclaims.admin");
    }

    // -----------------------------------------------------------------
    //  Claiming with auto-merge
    // -----------------------------------------------------------------
    public enum ClaimResult { CREATED, ADDED, MERGED, ALREADY_CLAIMED, LIMIT_REACHED, WORLD_DISABLED }

    /**
     * Claim one chunk for a player, auto-merging with same-owner orthogonal
     * neighbours. Saves on success. Returns the result code.
     */
    public ClaimResult claimChunk(Player player, String world, int cx, int cz) {
        if (!isWorldEnabled(world)) return ClaimResult.WORLD_DISABLED;
        String key = Claim.makeKey(world, cx, cz);
        if (chunkToClaimId.containsKey(key)) return ClaimResult.ALREADY_CLAIMED;

        UUID owner = player.getUniqueId();
        if (!isExempt(player) && countChunks(owner) >= getChunkLimit(owner)) {
            return ClaimResult.LIMIT_REACHED;
        }

        ClaimResult result = claimChunkInternal(owner, world, cx, cz);
        save();
        return result;
    }

    /** Core claim+merge logic without limit/world checks or save. */
    private ClaimResult claimChunkInternal(UUID owner, String world, int cx, int cz) {
        String key = Claim.makeKey(world, cx, cz);
        if (chunkToClaimId.containsKey(key)) return ClaimResult.ALREADY_CLAIMED;

        // distinct same-owner neighbour claim ids
        Set<String> neighborIds = new HashSet<>();
        int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] d : dirs) {
            String nId = chunkToClaimId.get(Claim.makeKey(world, cx + d[0], cz + d[1]));
            if (nId == null) continue;
            Claim nc = byId.get(nId);
            if (nc != null && nc.getOwner().equals(owner)) {
                neighborIds.add(nId);
            }
        }

        if (neighborIds.isEmpty()) {
            // new claim
            Claim claim = new Claim(nextId(), owner);
            for (String f : flagNames) claim.setFlag(f, getFlagDefault(f));
            claim.addChunk(key);
            byId.put(claim.getId(), claim);
            chunkToClaimId.put(key, claim.getId());
            return ClaimResult.CREATED;
        }

        // pick the oldest claim among neighbours as the survivor
        Claim survivor = null;
        for (String id : neighborIds) {
            Claim c = byId.get(id);
            if (c == null) continue;
            if (survivor == null || c.getCreatedAt() < survivor.getCreatedAt()) survivor = c;
        }
        if (survivor == null) {
            // shouldn't happen, fall back to new
            Claim claim = new Claim(nextId(), owner);
            for (String f : flagNames) claim.setFlag(f, getFlagDefault(f));
            claim.addChunk(key);
            byId.put(claim.getId(), claim);
            chunkToClaimId.put(key, claim.getId());
            return ClaimResult.CREATED;
        }

        survivor.addChunk(key);
        chunkToClaimId.put(key, survivor.getId());

        boolean merged = false;
        for (String id : neighborIds) {
            if (id.equals(survivor.getId())) continue;
            Claim other = byId.get(id);
            if (other == null) continue;
            for (String ck : other.getChunkKeys()) {
                survivor.addChunk(ck);
                chunkToClaimId.put(ck, survivor.getId());
            }
            byId.remove(id);
            merged = true;
        }
        survivor.touch();
        return merged ? ClaimResult.MERGED : ClaimResult.ADDED;
    }

    /** Run the auto-merge pass over ALL claims (used during migration). */
    private void autoMergeAll() {
        // For each pair of orthogonally-adjacent same-owner chunks in different
        // claims, merge. Implemented by union-find over chunk adjacency.
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (Claim claim : new ArrayList<>(byId.values())) {
                for (String ck : new ArrayList<>(claim.getChunkKeys())) {
                    String[] p = parseKey(ck);
                    if (p == null) continue;
                    String w = p[0];
                    int cx, cz;
                    try {
                        cx = Integer.parseInt(p[1]);
                        cz = Integer.parseInt(p[2]);
                    } catch (NumberFormatException ex) { continue; }
                    int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
                    for (int[] d : dirs) {
                        String nKey = Claim.makeKey(w, cx + d[0], cz + d[1]);
                        String nId = chunkToClaimId.get(nKey);
                        if (nId == null || nId.equals(claim.getId())) continue;
                        Claim other = byId.get(nId);
                        if (other == null || !other.getOwner().equals(claim.getOwner())) continue;
                        // merge other into the older of the two
                        Claim survivor = claim.getCreatedAt() <= other.getCreatedAt() ? claim : other;
                        Claim victim = survivor == claim ? other : claim;
                        for (String vk : victim.getChunkKeys()) {
                            survivor.addChunk(vk);
                            chunkToClaimId.put(vk, survivor.getId());
                        }
                        byId.remove(victim.getId());
                        changed = true;
                        break outer;
                    }
                }
            }
        }
    }

    /**
     * Claim a (2n+1)x(2n+1) block centered on (cx,cz). Free chunks are claimed
     * (auto-merging into one claim), occupied chunks are skipped, and the limit
     * is respected. Returns {claimed, skipped}.
     */
    public int[] claimRadius(Player player, String world, int cx, int cz, int n) {
        if (!isWorldEnabled(world)) return new int[]{0, 0};
        UUID owner = player.getUniqueId();
        boolean exempt = isExempt(player);
        int claimed = 0, skipped = 0;
        // claim center-out so the result stays contiguous as it grows
        for (int r = 0; r <= n; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // only the ring at radius r
                    int x = cx + dx, z = cz + dz;
                    String key = Claim.makeKey(world, x, z);
                    if (chunkToClaimId.containsKey(key)) { skipped++; continue; }
                    if (!exempt && countChunks(owner) >= getChunkLimit(owner)) { skipped++; continue; }
                    ClaimResult res = claimChunkInternal(owner, world, x, z);
                    if (res == ClaimResult.ALREADY_CLAIMED) skipped++;
                    else claimed++;
                }
            }
        }
        if (claimed > 0) save();
        return new int[]{claimed, skipped};
    }

    // -----------------------------------------------------------------
    //  Unclaiming with split
    // -----------------------------------------------------------------
    /**
     * Remove a single chunk from its claim. Deletes the claim if emptied;
     * splits it into connected components if removal disconnects it.
     * Returns true if a chunk was removed.
     */
    public boolean unclaimChunk(String world, int cx, int cz) {
        String key = Claim.makeKey(world, cx, cz);
        String id = chunkToClaimId.get(key);
        if (id == null) return false;
        Claim claim = byId.get(id);
        if (claim == null) { chunkToClaimId.remove(key); return false; }

        claim.removeChunk(key);
        chunkToClaimId.remove(key);

        if (claim.isEmpty()) {
            byId.remove(claim.getId());
            save();
            return true;
        }

        // recompute connected components over remaining chunks
        List<Set<String>> comps = connectedComponents(claim.getChunkKeys());
        if (comps.size() > 1) {
            // largest component keeps the original claim
            comps.sort((a, b) -> b.size() - a.size());
            Set<String> keep = comps.get(0);
            claim.getChunkKeys().clear();
            for (String ck : keep) {
                claim.addChunk(ck);
                chunkToClaimId.put(ck, claim.getId());
            }
            // remaining components become new claims copying owner/trust/flags
            for (int i = 1; i < comps.size(); i++) {
                Claim part = new Claim(nextId(), claim.getOwner());
                part.getTrust().putAll(claim.getTrust());
                part.getFlags().putAll(claim.getFlags());
                for (Map.Entry<String, Map<String, Boolean>> re : claim.getRolePerms().entrySet()) {
                    for (Map.Entry<String, Boolean> pe : re.getValue().entrySet()) {
                        part.setRolePerm(re.getKey(), pe.getKey(), pe.getValue());
                    }
                }
                part.setGreeting(claim.getGreeting());
                part.setFarewell(claim.getFarewell());
                part.setCreatedAt(claim.getCreatedAt());
                part.touch();
                for (String ck : comps.get(i)) {
                    part.addChunk(ck);
                    chunkToClaimId.put(ck, part.getId());
                }
                byId.put(part.getId(), part);
            }
        }
        claim.touch();
        save();
        return true;
    }

    /** Delete every claim owned by a player. Returns the number deleted. */
    public int deleteAllOf(UUID owner) {
        List<Claim> mine = getClaimsOf(owner);
        for (Claim c : mine) {
            unindexChunks(c);
            byId.remove(c.getId());
        }
        if (!mine.isEmpty()) save();
        return mine.size();
    }

    /** BFS over orthogonal adjacency to compute connected components. */
    private List<Set<String>> connectedComponents(Set<String> chunks) {
        List<Set<String>> comps = new ArrayList<>();
        Set<String> unvisited = new HashSet<>(chunks);
        while (!unvisited.isEmpty()) {
            Set<String> comp = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            String start = unvisited.iterator().next();
            queue.add(start);
            unvisited.remove(start);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                comp.add(cur);
                String[] p = parseKey(cur);
                if (p == null) continue;
                String w = p[0];
                int cx, cz;
                try {
                    cx = Integer.parseInt(p[1]);
                    cz = Integer.parseInt(p[2]);
                } catch (NumberFormatException ex) { continue; }
                int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
                for (int[] d : dirs) {
                    String nb = Claim.makeKey(w, cx + d[0], cz + d[1]);
                    if (unvisited.remove(nb)) queue.add(nb);
                }
            }
            comps.add(comp);
        }
        return comps;
    }

    // -----------------------------------------------------------------
    //  Other mutations (each saves)
    // -----------------------------------------------------------------
    public void deleteClaim(Claim claim) {
        if (claim == null) return;
        unindexChunks(claim);
        byId.remove(claim.getId());
        save();
    }

    public void transferClaim(Claim claim, UUID newOwner) {
        claim.setOwner(newOwner);
        claim.touch();
        save();
    }

    /** Persist after an in-place mutation (trust/flag/home/etc.). */
    public void touchAndSave(Claim claim) {
        if (claim != null) claim.touch();
        save();
    }

    // -----------------------------------------------------------------
    //  Permission helper: owner OR admin-bypass OR trust >= required
    // -----------------------------------------------------------------
    public boolean canAct(Player player, Claim claim, TrustLevel required) {
        if (claim == null) return true; // wilderness
        if (plugin.isBypassing(player)) return true;
        UUID id = player.getUniqueId();
        if (claim.getOwner().equals(id)) return true;
        TrustLevel lvl = claim.getTrustLevel(id);
        return lvl != null && lvl.atLeast(required);
    }

    public boolean isOwnerOrAdmin(Player player, Claim claim) {
        if (claim == null) return false;
        if (plugin.isBypassing(player)) return true;
        return claim.getOwner().equals(player.getUniqueId());
    }

    // -----------------------------------------------------------------
    //  Visualization: temporary particle border for one player (~5s)
    //  Draws the outline of every chunk in the claim.
    // -----------------------------------------------------------------
    public void showBorder(final Player player, final Claim claim) {
        showBorder(player, claim, 5);
    }

    /**
     * Draw the claim outline for {@code repeats} pulses (one per second) for this
     * player only. Cancels early if the player logs off.
     */
    public void showBorder(final Player player, final Claim claim, final int repeats) {
        if (claim == null) return;
        final String worldName = claim.getWorld();
        if (worldName == null) return;
        final World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        final List<int[]> bases = new ArrayList<>();
        for (String ck : claim.getChunkKeys()) {
            String[] p = parseKey(ck);
            if (p == null || !p[0].equals(worldName)) continue;
            try {
                bases.add(new int[]{ Integer.parseInt(p[1]) << 4, Integer.parseInt(p[2]) << 4 });
            } catch (NumberFormatException ignored) { }
        }
        if (bases.isEmpty()) return;

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || ticks >= repeats) {
                    cancel();
                    return;
                }
                double y = player.getLocation().getY() + 1.0;
                for (int[] base : bases) {
                    int baseX = base[0];
                    int baseZ = base[1];
                    for (int i = 0; i <= 16; i++) {
                        spawn(player, world, baseX + i, y, baseZ);
                        spawn(player, world, baseX + i, y, baseZ + 16);
                        spawn(player, world, baseX, y, baseZ + i);
                        spawn(player, world, baseX + 16, y, baseZ + i);
                    }
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public boolean isBorderToggled(UUID id) { return borderToggled.contains(id); }

    /**
     * Toggle a ~10s repeating border display for the claim the player is in.
     * Returns true if it was turned ON, false if turned OFF (or no claim here).
     * Running it again before it ends cancels the display early.
     */
    public boolean toggleBorder(final Player player, final Claim claim) {
        final UUID id = player.getUniqueId();
        if (borderToggled.remove(id)) {
            return false; // was on -> now off (the running task self-cancels)
        }
        if (claim == null) return false;
        borderToggled.add(id);

        final String worldName = claim.getWorld();
        final World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) { borderToggled.remove(id); return false; }

        final List<int[]> bases = new ArrayList<>();
        for (String ck : claim.getChunkKeys()) {
            String[] p = parseKey(ck);
            if (p == null || !p[0].equals(worldName)) continue;
            try {
                bases.add(new int[]{ Integer.parseInt(p[1]) << 4, Integer.parseInt(p[2]) << 4 });
            } catch (NumberFormatException ignored) { }
        }
        if (bases.isEmpty()) { borderToggled.remove(id); return false; }

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !borderToggled.contains(id) || ticks >= 10) {
                    borderToggled.remove(id);
                    cancel();
                    return;
                }
                double y = player.getLocation().getY() + 1.0;
                for (int[] base : bases) {
                    int baseX = base[0];
                    int baseZ = base[1];
                    for (int i = 0; i <= 16; i++) {
                        spawn(player, world, baseX + i, y, baseZ);
                        spawn(player, world, baseX + i, y, baseZ + 16);
                        spawn(player, world, baseX, y, baseZ + i);
                        spawn(player, world, baseX + 16, y, baseZ + i);
                    }
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 20L);
        return true;
    }

    private void spawn(Player player, World world, double x, double y, double z) {
        // shown only to this player; END_ROD is a stable, non-renamed particle
        player.spawnParticle(Particle.END_ROD, new Location(world, x, y, z), 1, 0, 0, 0, 0);
    }

    // -----------------------------------------------------------------
    //  Expiration sweep
    // -----------------------------------------------------------------
    public void runExpirationSweep() {
        int days = plugin.getConfig().getInt("expiration-days", 0);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - (long) days * 24L * 60L * 60L * 1000L;
        UUID serverUuid = plugin.getServerUuid();

        List<Claim> toDelete = new ArrayList<>();
        for (Claim claim : byId.values()) {
            UUID owner = claim.getOwner();
            if (serverUuid != null && owner.equals(serverUuid)) continue; // server claims never expire
            OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
            if (op.isOnline()) continue;
            // exempt: check stored permission via player; offline we approximate by op-status flag
            if (op.isOp()) continue;
            if (claim.getLastActive() > cutoff) continue;
            toDelete.add(claim);
        }
        if (toDelete.isEmpty()) return;
        for (Claim c : toDelete) {
            unindexChunks(c);
            byId.remove(c.getId());
        }
        save();
        plugin.getLogger().info("Expiration sweep removed " + toDelete.size() + " inactive claim(s).");
    }
}
