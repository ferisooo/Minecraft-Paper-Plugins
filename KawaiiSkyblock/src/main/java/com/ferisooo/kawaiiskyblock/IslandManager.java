package com.ferisooo.kawaiiskyblock;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the {@code islands.yml} data model and all island bookkeeping:
 * membership/roles, anti-grief flags, visit policy, generator level, warps,
 * island level, plus an in-memory reverse lookup (worldName → owner) and the
 * in-memory co-op invite table.
 *
 * <p>Data layout (per owner UUID under {@code islands.<owner>}):
 * <ul>
 *   <li>{@code world} — folder name (legacy/existing).</li>
 *   <li>{@code members.<uuid>} — ROLE (LEADER/COOP/MEMBER/VISITOR).</li>
 *   <li>{@code flags.<flag>} — boolean.</li>
 *   <li>{@code visitPolicy} — PUBLIC|INVITE|TRUST.</li>
 *   <li>{@code generatorLevel} — int.</li>
 *   <li>{@code warps.<name>} — "world,x,y,z,yaw,pitch".</li>
 *   <li>{@code level} — cached island level (int).</li>
 *   <li>{@code levelComputed} — last compute time (millis).</li>
 *   <li>{@code lastSeen} — owner's last-seen time (epoch millis), for purge.</li>
 * </ul>
 */
public final class IslandManager {

    /** Membership roles, ordered weakest → strongest. */
    public enum Role { VISITOR, MEMBER, COOP, LEADER }

    /** Per-role abilities. */
    public enum Perm { BUILD, CONTAINERS, SPAWN_MOBS, MANAGE }

    /** Where people may visit from. */
    public enum VisitPolicy { PUBLIC, INVITE, TRUST }

    /**
     * The curated environment-flag key set (16). Each is a boolean stored
     * per-island under {@code islands.<owner>.flags.<key>}. Defaults come from
     * {@link #defaultFlag(String)} (and may be overridden in config under
     * {@code flag-defaults}). Protective/destructive flags default OFF (explosions,
     * fire*, pvp, *_explosions, entity_grief, lightning); natural growth/spread
     * default ON; damage-cause flags default ON. Only these flags have any in-game
     * effect — anything else is vanilla behaviour.
     */
    public static final String[] FLAG_KEYS = {
            "pvp", "tnt_explosions", "creeper_explosions", "explosion_block_damage",
            "fire_spread", "fire_burn", "fire_ignite", "entity_grief",
            "lightning_strike", "leaf_decay", "crop_growth", "liquids_flow",
            "fall_damage", "natural_breeding", "weather_change", "portal_create"
    };

    /**
     * Full data-driven member-permission key set (~120). Each is a boolean
     * allowance evaluated per (island, player) via {@link #hasPermission}. The
     * effective allowance is: owner / admin → always; otherwise the player's
     * role must grant the permission (role defaults come from config
     * {@code roles:}, overridable per-island under
     * {@code islands.<owner>.permissions.<role>.<key>}).
     */
    public static final String[] PERM_KEYS = {
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
            "use_bucket", "use_ender_pearl", "use_elytra", "eat"
    };

    private final KawaiiSkyblock plugin;
    private final File dataFile;
    private final YamlConfiguration data = new YamlConfiguration();

    /** worldName → owner UUID, rebuilt on enable. */
    private final Map<String, UUID> worldToOwner = new HashMap<>();

    /** in-memory co-op invites: invitee → owner (with expiry). */
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<UUID, Long> inviteExpiry = new HashMap<>();
    private static final long INVITE_TIMEOUT_MS = 120_000L;

    /**
     * Debounced-save state. Mutations mark {@code dirty} instead of writing the
     * whole YAML file synchronously every time; a periodic flush (see
     * {@link #flush()}) and {@link #saveNow()} on shutdown do the actual write.
     * Only ever touched on the main thread.
     */
    private boolean dirty = false;

    public IslandManager(KawaiiSkyblock plugin, File dataFile) {
        this.plugin = plugin;
        this.dataFile = dataFile;
    }

    // --------------------------------------------------------------- load/save

    /** Loads islands.yml flat (preserving every value), then rebuilds caches. */
    public void load() {
        if (dataFile != null && dataFile.exists()) {
            YamlConfiguration loaded = YamlConfiguration.loadConfiguration(dataFile);
            for (String key : loaded.getKeys(true)) {
                if (!loaded.isConfigurationSection(key)) data.set(key, loaded.get(key));
            }
        }
        rebuildWorldIndex();
    }

    /**
     * Debounced save: marks the data dirty instead of writing the whole YAML file
     * synchronously on every mutation. The actual write happens via {@link #flush()}
     * (periodic task) or {@link #saveNow()} (shutdown). Must be called on the main
     * thread (where all {@code data} mutations occur).
     */
    public void save() {
        dirty = true;
    }

    /**
     * If dirty, serialize the current data on the (main) calling thread and write
     * it to disk off the main thread. Clears the dirty flag eagerly so concurrent
     * mutations after the snapshot re-mark it for the next flush.
     */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        final String yaml = data.saveToString(); // snapshot on the main thread
        Bukkit.getAsyncScheduler().runNow(plugin, t -> writeYaml(yaml));
    }

    /**
     * Synchronous, blocking write of the current data. Used on shutdown so dirty
     * data is always flushed before the server stops.
     */
    public void saveNow() {
        dirty = false;
        writeYaml(data.saveToString());
    }

    /** Writes the serialized YAML to {@link #dataFile} (creating the folder). */
    private void writeYaml(String yaml) {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Files.write(dataFile.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("(✧) couldn't save islands.yml: " + ex.getMessage());
        }
    }

    public YamlConfiguration raw() { return data; }

    private void rebuildWorldIndex() {
        worldToOwner.clear();
        ConfigurationSection sec = data.getConfigurationSection("islands");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            String folder = data.getString("islands." + key + ".world");
            if (folder == null) continue;
            try {
                worldToOwner.put(folder, UUID.fromString(key));
            } catch (IllegalArgumentException ignored) { /* malformed key */ }
        }
    }

    // --------------------------------------------------------------- ownership

    public boolean hasIsland(UUID owner) {
        return data.contains("islands." + owner + ".world");
    }

    public String worldFolder(UUID owner) {
        return data.getString("islands." + owner + ".world");
    }

    /** O(1) reverse lookup: which owner does this world belong to (or null). */
    public UUID ownerOfWorld(String worldName) {
        return worldToOwner.get(worldName);
    }

    public boolean isIslandWorld(String worldName) {
        return worldName != null && worldName.startsWith("kawaii_isle_");
    }

    /** Registers a freshly created island with sensible defaults. */
    public void createIsland(UUID owner, String folder) {
        String base = "islands." + owner;
        data.set(base + ".world", folder);
        data.set(base + ".members." + owner, Role.LEADER.name());
        data.set(base + ".visitPolicy", VisitPolicy.INVITE.name());
        data.set(base + ".generatorLevel", 1);
        for (String f : FLAG_KEYS) data.set(base + ".flags." + f, defaultFlag(f));
        worldToOwner.put(folder, owner);
        save();
    }

    public void deleteIsland(UUID owner) {
        String folder = worldFolder(owner);
        if (folder != null) worldToOwner.remove(folder);
        data.set("islands." + owner, null);
        save();
    }

    // --------------------------------------------------------------- roles

    public Role roleOf(UUID owner, UUID player) {
        if (owner == null) return Role.VISITOR;
        if (owner.equals(player)) return Role.LEADER;
        String r = data.getString("islands." + owner + ".members." + player);
        if (r == null) return Role.VISITOR;
        try { return Role.valueOf(r.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Role.VISITOR; }
    }

    /** Role of a player on whatever island the given world belongs to. */
    public Role roleInWorld(String worldName, UUID player) {
        return roleOf(ownerOfWorld(worldName), player);
    }

    public void setRole(UUID owner, UUID player, Role role) {
        if (owner.equals(player)) return; // owner is always LEADER
        if (role == Role.VISITOR) {
            data.set("islands." + owner + ".members." + player, null);
        } else {
            data.set("islands." + owner + ".members." + player, role.name());
        }
        save();
    }

    public List<UUID> members(UUID owner) {
        List<UUID> out = new ArrayList<>();
        ConfigurationSection sec = data.getConfigurationSection("islands." + owner + ".members");
        if (sec == null) return out;
        for (String k : sec.getKeys(false)) {
            try { out.add(UUID.fromString(k)); } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    public int memberCount(UUID owner) {
        return members(owner).size();
    }

    /** Whether the given role grants the given permission (hardcoded defaults). */
    public static boolean roleHas(Role role, Perm perm) {
        switch (role) {
            case LEADER:
                return true;
            case COOP:
                return perm == Perm.BUILD || perm == Perm.CONTAINERS
                        || perm == Perm.SPAWN_MOBS || perm == Perm.MANAGE;
            case MEMBER:
                return perm == Perm.BUILD || perm == Perm.CONTAINERS;
            case VISITOR:
            default:
                return false;
        }
    }

    public boolean canIn(String worldName, UUID player, Perm perm) {
        return roleHas(roleInWorld(worldName, player), perm);
    }

    /** True if the player is a trusted member (MEMBER or higher) on this world's island. */
    public boolean isTrustedInWorld(String worldName, UUID player) {
        return roleInWorld(worldName, player).ordinal() >= Role.MEMBER.ordinal();
    }

    // --------------------------------------------------------------- flags

    /**
     * Hard-coded fallback default for a flag, used when config has no
     * {@code flag-defaults.<flag>} override. Protective/destructive flags default
     * OFF; natural growth/spread and damage-cause flags default ON.
     */
    public static boolean builtinFlagDefault(String flag) {
        switch (flag) {
            // Protective / destructive — default OFF.
            case "pvp":
            case "fire_spread": case "fire_ignite": case "fire_burn":
            case "explosion_block_damage": case "creeper_explosions": case "tnt_explosions":
            case "entity_grief": case "lightning_strike":
                return false;
            // Everything else (natural growth/spread, breeding, weather, flows,
            // fall damage, portals…) ON.
            default:
                return true;
        }
    }

    /**
     * Config-aware default for a flag: {@code flag-defaults.<flag>} if present,
     * otherwise {@link #builtinFlagDefault(String)}.
     */
    public boolean defaultFlag(String flag) {
        if (plugin != null) {
            String path = "flag-defaults." + flag;
            if (plugin.getConfig().contains(path)) {
                return plugin.getConfig().getBoolean(path, builtinFlagDefault(flag));
            }
        }
        return builtinFlagDefault(flag);
    }

    public boolean flag(UUID owner, String flag) {
        if (owner == null) return defaultFlag(flag);
        return data.getBoolean("islands." + owner + ".flags." + flag, defaultFlag(flag));
    }

    public boolean flagInWorld(String worldName, String flag) {
        return flag(ownerOfWorld(worldName), flag);
    }

    public void setFlag(UUID owner, String flag, boolean value) {
        data.set("islands." + owner + ".flags." + flag, value);
        save();
    }

    public boolean toggleFlag(UUID owner, String flag) {
        boolean v = !flag(owner, flag);
        setFlag(owner, flag, v);
        return v;
    }

    // --------------------------------------------------------------- member permissions

    /**
     * Built-in fallback: does a role grant a permission when neither config
     * {@code roles:} nor a per-island override says otherwise? Coarse defaults
     * derived from the legacy {@link #roleHas} matrix so the system is sane out
     * of the box: COOP gets everything, MEMBER gets build/interact/use but not
     * entity destruction or the ender chest, VISITOR gets only passive things.
     */
    public static boolean builtinRolePermission(Role role, String key) {
        switch (role) {
            case LEADER:
            case COOP:
                return true;
            case MEMBER:
                // Members can do almost everything in the curated set.
                return true;
            case VISITOR:
            default:
                // Visitors only get harmless, passive permissions.
                switch (key) {
                    case "eat":
                        return true;
                    default:
                        return false;
                }
        }
    }

    /**
     * Config-aware default for (role, permission): per-island override at
     * {@code islands.<owner>.permissions.<role>.<key>} wins, then config
     * {@code roles.<role>.<key>}, then {@link #builtinRolePermission}.
     */
    public boolean rolePermission(UUID owner, Role role, String key) {
        String lr = role.name().toLowerCase(Locale.ROOT);
        if (owner != null) {
            String ovr = "islands." + owner + ".permissions." + lr + "." + key;
            if (data.contains(ovr)) return data.getBoolean(ovr, builtinRolePermission(role, key));
        }
        if (plugin != null) {
            String cfgPath = "roles." + lr + "." + key;
            if (plugin.getConfig().contains(cfgPath)) {
                return plugin.getConfig().getBoolean(cfgPath, builtinRolePermission(role, key));
            }
        }
        return builtinRolePermission(role, key);
    }

    /** Sets a per-island role→permission override (used by the role editor GUI). */
    public void setRolePermission(UUID owner, Role role, String key, boolean value) {
        data.set("islands." + owner + ".permissions." + role.name().toLowerCase(Locale.ROOT) + "." + key, value);
        save();
    }

    public boolean toggleRolePermission(UUID owner, Role role, String key) {
        boolean v = !rolePermission(owner, role, key);
        setRolePermission(owner, role, key, v);
        return v;
    }

    /**
     * Effective allowance for {@code player} on {@code owner}'s island for the
     * given permission key: the owner always has it; otherwise the player's role
     * on that island must grant it.
     */
    public boolean hasPermission(UUID owner, UUID player, String key) {
        if (owner == null) return true; // not an island we track → don't block
        if (owner.equals(player)) return true;
        Role role = roleOf(owner, player);
        return rolePermission(owner, role, key);
    }

    /** Convenience: effective permission for a player on whatever island owns {@code worldName}. */
    public boolean hasPermissionInWorld(String worldName, UUID player, String key) {
        return hasPermission(ownerOfWorld(worldName), player, key);
    }

    // --------------------------------------------------------------- visit policy

    public VisitPolicy visitPolicy(UUID owner) {
        String v = data.getString("islands." + owner + ".visitPolicy", VisitPolicy.INVITE.name());
        try { return VisitPolicy.valueOf(v.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return VisitPolicy.INVITE; }
    }

    public void setVisitPolicy(UUID owner, VisitPolicy policy) {
        data.set("islands." + owner + ".visitPolicy", policy.name());
        save();
    }

    /** May {@code visitor} visit {@code owner}'s island? */
    public boolean canVisit(UUID owner, UUID visitor) {
        if (owner == null) return false;
        if (owner.equals(visitor)) return true;
        Role role = roleOf(owner, visitor);
        switch (visitPolicy(owner)) {
            case PUBLIC:
                return true;
            case TRUST:
                return role.ordinal() >= Role.MEMBER.ordinal(); // any trusted member
            case INVITE:
            default:
                // members count as "invited"; or a live in-memory invite
                if (role.ordinal() >= Role.MEMBER.ordinal()) return true;
                return owner.equals(activeInvite(visitor));
        }
    }

    // --------------------------------------------------------------- invites

    public void invite(UUID owner, UUID invitee) {
        invites.put(invitee, owner);
        inviteExpiry.put(invitee, System.currentTimeMillis() + INVITE_TIMEOUT_MS);
    }

    /** Returns the owner who invited this player if the invite is still valid. */
    public UUID activeInvite(UUID invitee) {
        Long exp = inviteExpiry.get(invitee);
        if (exp == null) return null;
        if (System.currentTimeMillis() > exp) {
            invites.remove(invitee);
            inviteExpiry.remove(invitee);
            return null;
        }
        return invites.get(invitee);
    }

    public void clearInvite(UUID invitee) {
        invites.remove(invitee);
        inviteExpiry.remove(invitee);
    }

    // --------------------------------------------------------------- warps

    /** Saves a warp at the given location. */
    public void setWarp(UUID owner, String name, Location loc) {
        String enc = loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + ","
                + loc.getZ() + "," + loc.getYaw() + "," + loc.getPitch();
        data.set("islands." + owner + ".warps." + name.toLowerCase(Locale.ROOT), enc);
        save();
    }

    public void delWarp(UUID owner, String name) {
        data.set("islands." + owner + ".warps." + name.toLowerCase(Locale.ROOT), null);
        save();
    }

    public List<String> warpNames(UUID owner) {
        List<String> out = new ArrayList<>();
        ConfigurationSection sec = data.getConfigurationSection("islands." + owner + ".warps");
        if (sec == null) return out;
        out.addAll(sec.getKeys(false));
        return out;
    }

    /** Decodes a saved warp into a Location (or null if missing/world unloaded). */
    public Location warpLocation(UUID owner, String name) {
        String enc = data.getString("islands." + owner + ".warps." + name.toLowerCase(Locale.ROOT));
        if (enc == null) return null;
        String[] parts = enc.split(",");
        if (parts.length < 6) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        try {
            return new Location(w,
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // --------------------------------------------------------------- island level

    public int level(UUID owner) {
        return data.getInt("islands." + owner + ".level", 0);
    }

    public long levelComputedAt(UUID owner) {
        return data.getLong("islands." + owner + ".levelComputed", 0L);
    }

    public void setLevel(UUID owner, int level) {
        data.set("islands." + owner + ".level", level);
        data.set("islands." + owner + ".levelComputed", System.currentTimeMillis());
        save();
    }

    public int generatorLevel(UUID owner) {
        return data.getInt("islands." + owner + ".generatorLevel", 1);
    }

    public void setGeneratorLevel(UUID owner, int level) {
        data.set("islands." + owner + ".generatorLevel", level);
        save();
    }

    // --------------------------------------------------------------- last seen

    /**
     * Last time the owner was seen (epoch millis). For existing islands with no
     * stored value, this returns "now" (and does not persist), so the periodic
     * purge never instantly deletes pre-existing islands on first load.
     */
    public long lastSeen(UUID owner) {
        return data.getLong("islands." + owner + ".lastSeen", System.currentTimeMillis());
    }

    /** Records the owner's last-seen time as now (only if they have an island). */
    public void touchLastSeen(UUID owner) {
        if (!hasIsland(owner)) return;
        data.set("islands." + owner + ".lastSeen", System.currentTimeMillis());
        save();
    }

    /** All island owner UUIDs known to us. */
    public List<UUID> allOwners() {
        List<UUID> out = new ArrayList<>();
        ConfigurationSection sec = data.getConfigurationSection("islands");
        if (sec == null) return out;
        for (String k : sec.getKeys(false)) {
            try { out.add(UUID.fromString(k)); } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    /** worldName → owner snapshot (for iteration). */
    public Map<String, UUID> worldIndex() {
        return new LinkedHashMap<>(worldToOwner);
    }
}
