package com.ferisooo.kawaiiworlds;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class KawaiiWorlds extends JavaPlugin implements TabExecutor, Listener {

    public static final String PERM_ADMIN = "kawaiiworlds.admin";

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "create", "tp", "list", "info", "load", "unload", "delete", "setspawn", "gui", "help"
    );
    private static final List<String> TYPES = Arrays.asList(
            "normal", "nether", "end", "void", "flat"
    );

    private static final long PENDING_TIMEOUT_MS = 30_000L;
    private final Map<UUID, PendingCreate> pendingCreate = new ConcurrentHashMap<>();

    private boolean perWorldInventory;
    private String defaultSpawnWorld;
    private final Map<UUID, String> deathWorld = new ConcurrentHashMap<>();

    // Cache of per-world spawn-protection radius, populated on enable/config-load
    // and updated by cycleSpawnProtection. Avoids walking the YAML tree on every
    // block break/place event. Absence of a key means radius <= 0 (no protection).
    private final Map<String, Integer> spawnProtectionRadius = new ConcurrentHashMap<>();

    private static final class PendingCreate {
        final String type;
        final long createdMs;
        PendingCreate(String type) { this.type = type; this.createdMs = System.currentTimeMillis(); }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        perWorldInventory = getConfig().getBoolean("per-world-inventory", true);
        defaultSpawnWorld = getConfig().getString("default-spawn-world", null);
        reloadSpawnProtectionCache();

        PluginCommand cmd = getCommand("kawaiiworlds");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTask(this, this::loadConfiguredWorlds);

        getLogger().info("(✧) KawaiiWorlds ready ~");
    }

    @Override
    public void onDisable() {
        // Worlds stay loaded; Paper saves them itself on shutdown.
    }

    private void loadConfiguredWorlds() {
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection w = sec.getConfigurationSection(name);
            if (w == null) continue;
            if (!w.getBoolean("auto-load", true)) continue;
            if (Bukkit.getWorld(name) != null) continue;

            String type = w.getString("type", "normal");
            long seed = w.getLong("seed", ThreadLocalRandom.current().nextLong());
            try {
                World loaded = createOrLoadWorld(name, type, seed);
                if (loaded != null) {
                    getLogger().info("(✧) loaded world '" + name + "' (" + type + ")");
                }
            } catch (Throwable t) {
                getLogger().warning("(✧) failed to load world '" + name + "': " + t.getMessage());
            }
        }
    }

    // ============================================================
    //   COMMAND DISPATCH
    // ============================================================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN) && !sender.isOp()) {
            sender.sendMessage("§d(✧) you don't have permission~");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player) {
                openMainGui((Player) sender);
            } else {
                sendHelp(sender);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create":   return cmdCreate(sender, args);
            case "tp":
            case "teleport": return cmdTp(sender, args);
            case "list":
            case "ls":       return cmdList(sender);
            case "info":     return cmdInfo(sender, args);
            case "load":     return cmdLoad(sender, args);
            case "unload":   return cmdUnload(sender, args);
            case "delete":
            case "del":
            case "remove":   return cmdDelete(sender, args);
            case "setspawn": return cmdSetSpawn(sender);
            case "gui":
                if (sender instanceof Player) { openMainGui((Player) sender); return true; }
                sender.sendMessage("§c(✧) /kw gui must be run by a player");
                return true;
            case "help":
            default:         sendHelp(sender); return true;
        }
    }

    // ============================================================
    //   SUBCOMMANDS
    // ============================================================

    private boolean cmdCreate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c(✧) usage: /kw create <name> [type]   (type: " + String.join("|", TYPES) + ")");
            return true;
        }
        String name = args[1];
        if (!isValidWorldName(name)) {
            sender.sendMessage("§c(✧) world name '" + name + "' must be alphanumeric / underscore / hyphen, no spaces");
            return true;
        }
        if (Bukkit.getWorld(name) != null) {
            sender.sendMessage("§c(✧) a world named '" + name + "' is already loaded");
            return true;
        }
        if (worldFolderExists(name)) {
            sender.sendMessage("§c(✧) folder '" + name + "' already exists. /kw load " + name + " ?");
            return true;
        }

        String type = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "normal";
        if (!TYPES.contains(type)) {
            sender.sendMessage("§c(✧) unknown type '" + type + "'. valid: " + String.join(", ", TYPES));
            return true;
        }

        long seed = ThreadLocalRandom.current().nextLong();
        sender.sendMessage("§d(✧) creating '" + name + "' (" + type + ", seed " + seed + ")...");

        World world;
        try {
            world = createOrLoadWorld(name, type, seed);
        } catch (Throwable t) {
            sender.sendMessage("§c(✧) creation failed: " + t.getMessage());
            return true;
        }
        if (world == null) {
            sender.sendMessage("§c(✧) creation returned null world (check console)");
            return true;
        }

        String base = "worlds." + name + ".";
        getConfig().set(base + "type", type);
        getConfig().set(base + "seed", seed);
        getConfig().set(base + "auto-load", true);
        saveConfig();

        sender.sendMessage("§a(✧) world '" + name + "' ready ✨  /kw tp " + name);
        return true;
    }

    private boolean cmdTp(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c(✧) usage: /kw tp <world> [player]");
            return true;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) {
            sender.sendMessage("§c(✧) no loaded world named '" + args[1] + "'");
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§c(✧) player '" + args[2] + "' is not online");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c(✧) console must specify a player: /kw tp <world> <player>");
                return true;
            }
            target = (Player) sender;
        }

        // Lock check
        if (isWorldLocked(w) && !target.hasPermission(PERM_ADMIN) && !target.isOp()) {
            sender.sendMessage("§c(✧) world '" + w.getName() + "' is locked – you cannot enter.");
            return true;
        }

        Location spawn = w.getSpawnLocation();
        target.teleportAsync(spawn);
        sender.sendMessage("§d(✧) " + target.getName() + " → " + w.getName());
        return true;
    }

    private boolean cmdList(CommandSender sender) {
        sender.sendMessage("§d(✧) loaded worlds:");
        for (World w : Bukkit.getWorlds()) {
            if (isSkyblockHidden(w)) continue; // skyblock islands are managed by KawaiiSkyblock
            ConfigurationSection cfg = getConfig().getConfigurationSection("worlds." + w.getName());
            String type = cfg != null ? cfg.getString("type", "primary") : "primary";
            int players = w.getPlayers().size();
            String lock = isWorldLocked(w) ? " §c[LOCKED]" : "";
            sender.sendMessage("  §7- §f" + w.getName() + lock
                    + " §8(" + type + ", " + w.getEnvironment().name().toLowerCase()
                    + ", " + players + " player" + (players == 1 ? "" : "s") + ")");
        }
        return true;
    }

    private boolean cmdInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c(✧) usage: /kw info <world>");
            return true;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) {
            sender.sendMessage("§c(✧) no loaded world named '" + args[1] + "'");
            return true;
        }
        Location s = w.getSpawnLocation();
        sender.sendMessage("§d(✧) " + w.getName() + (isWorldLocked(w) ? " §c[LOCKED]" : ""));
        sender.sendMessage("  §7env:    §f" + w.getEnvironment().name().toLowerCase());
        sender.sendMessage("  §7seed:   §f" + w.getSeed());
        sender.sendMessage("  §7spawn:  §f" + (int) s.getX() + ", " + (int) s.getY() + ", " + (int) s.getZ());
        sender.sendMessage("  §7time:   §f" + w.getTime());
        sender.sendMessage("  §7players:§f " + w.getPlayers().size());
        sender.sendMessage("  §7entities:§f " + w.getEntities().size());
        sender.sendMessage("  §7pvp:    §f" + w.getPVP());
        return true;
    }

    private boolean cmdLoad(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c(✧) usage: /kw load <name>");
            return true;
        }
        String name = args[1];
        if (Bukkit.getWorld(name) != null) {
            sender.sendMessage("§e(✧) '" + name + "' is already loaded");
            return true;
        }
        if (!worldFolderExists(name)) {
            sender.sendMessage("§c(✧) no folder '" + name + "' under " + Bukkit.getWorldContainer());
            return true;
        }

        ConfigurationSection cfg = getConfig().getConfigurationSection("worlds." + name);
        String type = cfg != null ? cfg.getString("type", "normal") : "normal";
        long seed = cfg != null ? cfg.getLong("seed", 0L) : 0L;

        World w;
        try {
            w = createOrLoadWorld(name, type, seed);
        } catch (Throwable t) {
            sender.sendMessage("§c(✧) load failed: " + t.getMessage());
            return true;
        }
        if (w == null) {
            sender.sendMessage("§c(✧) load returned null (check console)");
            return true;
        }
        if (cfg == null) {
            // Register the world so it survives restarts (matches the GUI import path).
            String base = "worlds." + name + ".";
            getConfig().set(base + "type", type);
            getConfig().set(base + "seed", w.getSeed());
            getConfig().set(base + "auto-load", true);
            saveConfig();
        }
        sender.sendMessage("§a(✧) loaded '" + name + "'");
        return true;
    }

    private boolean cmdUnload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c(✧) usage: /kw unload <name>");
            return true;
        }
        String name = args[1];
        World w = Bukkit.getWorld(name);
        if (w == null) {
            sender.sendMessage("§c(✧) '" + name + "' is not loaded");
            return true;
        }
        if (w.equals(Bukkit.getWorlds().get(0))) {
            sender.sendMessage("§c(✧) cannot unload the primary world");
            return true;
        }
        World fallback = Bukkit.getWorlds().get(0);
        Location safe = fallback.getSpawnLocation();
        for (Player p : new ArrayList<>(w.getPlayers())) {
            p.teleport(safe);
        }
        boolean ok = Bukkit.unloadWorld(w, true);
        if (ok) {
            sender.sendMessage("§a(✧) unloaded '" + name + "'");
        } else {
            sender.sendMessage("§c(✧) unload failed (something held the world open)");
        }
        return true;
    }

    private boolean cmdDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c(✧) usage: /kw delete <name> --confirm");
            return true;
        }
        String name = args[1];
        boolean confirm = args.length >= 3 && "--confirm".equalsIgnoreCase(args[2]);
        if (!confirm) {
            sender.sendMessage("§e(✧) this DELETES the world folder permanently. re-run with --confirm:");
            sender.sendMessage("§7   /kw delete " + name + " --confirm");
            return true;
        }

        World w = Bukkit.getWorld(name);
        if (w != null) {
            if (w.equals(Bukkit.getWorlds().get(0))) {
                sender.sendMessage("§c(✧) cannot delete the primary world");
                return true;
            }
            World fallback = Bukkit.getWorlds().get(0);
            for (Player p : new ArrayList<>(w.getPlayers())) {
                p.teleport(fallback.getSpawnLocation());
            }
            boolean unloaded = Bukkit.unloadWorld(w, false);
            if (!unloaded) {
                sender.sendMessage("§c(✧) couldn't unload '" + name + "' before delete");
                return true;
            }
        }

        // Purge the config entry first so the world is gone from KawaiiWorlds
        // even if the folder can't be removed right now (locked region files).
        getConfig().set("worlds." + name, null);
        saveConfig();
        spawnProtectionRadius.remove(name);

        File folder = new File(Bukkit.getWorldContainer(), name);
        if (!folder.exists()) {
            sender.sendMessage("§e(✧) no folder on disk; cleared config entry for '" + name + "'");
            return true;
        }
        // Deleting a world folder can mean removing thousands of region files —
        // do it off the main thread so it doesn't stall the tick loop.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                deleteRecursively(folder.toPath());
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§a(✧) deleted '" + name + "'"));
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§e(✧) removed '" + name + "' from config, but the folder "
                                + "couldn't be fully deleted (" + t.getMessage() + "). Delete it manually if it lingers."));
            }
        });
        return true;
    }

    private boolean cmdSetSpawn(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c(✧) /kw setspawn must be run by a player");
            return true;
        }
        Player p = (Player) sender;
        Location loc = p.getLocation();
        p.getWorld().setSpawnLocation(loc);
        sender.sendMessage("§d(✧) spawn for '" + p.getWorld().getName() + "' set to "
                + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ());
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§d(✧) §fKawaiiWorlds §d~§r");
        s.sendMessage("  §7/kw create <name> [type]   §8new world (random seed)");
        s.sendMessage("  §7/kw tp <world> [player]    §8teleport to a world's spawn");
        s.sendMessage("  §7/kw list                   §8list loaded worlds");
        s.sendMessage("  §7/kw info <world>           §8world details");
        s.sendMessage("  §7/kw load <name>            §8load existing world folder");
        s.sendMessage("  §7/kw unload <name>          §8unload (saves first)");
        s.sendMessage("  §7/kw delete <name> --confirm§8 unload + permanently delete folder");
        s.sendMessage("  §7/kw setspawn               §8set this world's spawn to where you stand");
        s.sendMessage("  §7/kw gui                    §8open the chest UI (players only)");
        s.sendMessage("  §8types: " + String.join(", ", TYPES));
    }

    // ============================================================
    //   CHEST GUI
    // ============================================================

    private static final class WorldsGuiHolder implements InventoryHolder {
        enum Mode { MAIN, SETTINGS_PICKER, SETTINGS, DELETE_PICKER, DELETE_CONFIRM, IMPORT_PICKER }
        final Mode mode;
        final String worldName;
        Inventory inv;
        WorldsGuiHolder(Mode mode, String worldName) { this.mode = mode; this.worldName = worldName; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private static final int GUI_SIZE = 27;
    private static final int SLOT_CREATE_NORMAL = 0;
    private static final int SLOT_CREATE_FLAT   = 1;
    private static final int SLOT_CREATE_VOID   = 2;
    private static final int SLOT_CREATE_SKY    = 3;
    private static final int SLOT_TITLE         = 4;
    private static final int SLOT_SETTINGS      = 5;
    private static final int SLOT_DELETE        = 6;
    private static final int SLOT_IMPORT        = 7;
    private static final int SLOT_CLOSE         = 8;
    private static final int WORLDS_FIRST_SLOT  = 9;

    private static final int SLOT_BACK = 0;

    private static final List<GameRule<Boolean>> RULE_TOGGLES = List.of(
            GameRule.KEEP_INVENTORY,
            GameRule.DO_DAYLIGHT_CYCLE,
            GameRule.DO_MOB_SPAWNING,
            GameRule.DO_FIRE_TICK,
            GameRule.MOB_GRIEFING,
            GameRule.DO_WEATHER_CYCLE,
            GameRule.NATURAL_REGENERATION,
            GameRule.DO_MOB_LOOT,
            GameRule.ANNOUNCE_ADVANCEMENTS,
            // ---- "hidden / secret" extras, plain-English labels below ----
            GameRule.DO_IMMEDIATE_RESPAWN,
            GameRule.SHOW_DEATH_MESSAGES,
            GameRule.FALL_DAMAGE,
            GameRule.FIRE_DAMAGE,
            GameRule.DROWNING_DAMAGE,
            GameRule.FREEZE_DAMAGE,
            GameRule.DO_INSOMNIA,
            GameRule.DO_PATROL_SPAWNING,
            GameRule.DO_TRADER_SPAWNING,
            GameRule.DO_WARDEN_SPAWNING,
            GameRule.DISABLE_RAIDS,
            GameRule.DO_ENTITY_DROPS,
            GameRule.DO_TILE_DROPS,
            GameRule.FORGIVE_DEAD_PLAYERS
    );
    /** Technical gamerule ids — used as preset-map keys (do NOT change to labels). */
    private static final List<String> RULE_DISPLAY_NAMES = List.of(
            "keepInventory", "doDaylightCycle", "doMobSpawning", "doFireTick",
            "mobGriefing", "doWeatherCycle", "naturalRegeneration", "doMobLoot",
            "announceAdvancements", "doImmediateRespawn", "showDeathMessages",
            "fallDamage", "fireDamage", "drowningDamage", "freezeDamage",
            "doInsomnia", "doPatrolSpawning", "doTraderSpawning", "doWardenSpawning",
            "disableRaids", "doEntityDrops", "doTileDrops", "forgiveDeadPlayers"
    );
    /** Plain-English labels shown in the GUI (parallel to RULE_TOGGLES). */
    private static final List<String> RULE_FRIENDLY_NAMES = List.of(
            "Keep items on death", "Day / night cycle", "Monsters spawn",
            "Fire spreads & burns", "Mobs can change blocks", "Weather changes",
            "Heal from full hunger", "Mobs drop loot", "Announce advancements",
            "Skip the death screen", "Death messages in chat", "Fall damage",
            "Fire damage", "Drowning damage", "Freeze damage", "Phantoms at night",
            "Pillager patrols", "Wandering traders", "Wardens spawn",
            "Disable raids", "Entities drop items", "Blocks drop items",
            "Mobs forgive dead players"
    );
    // Controls live on the bottom two rows (36-47); the toggle rules fill 9-31.
    private static final int SLOT_PVP        = 36;
    private static final int SLOT_DIFFICULTY = 37;
    private static final int SLOT_TIME       = 38;
    private static final int SLOT_WEATHER    = 39;
    private static final int SLOT_AUTOLOAD   = 40;
    private static final int SLOT_SETSPAWN   = 41;
    private static final int SLOT_ALLOW_FLY        = 42;
    private static final int SLOT_GAMEMODE         = 43;
    private static final int SLOT_DEFAULT_SPAWN    = 44;
    private static final int SLOT_PRESET           = 45;
    private static final int SLOT_SPAWN_PROTECTION = 46;
    private static final int SLOT_LOCK             = 47;
    private static final int SETTINGS_SIZE         = 54;

    private static final int CONFIRM_SIZE       = 9;
    private static final int SLOT_CONFIRM_YES   = 3;
    private static final int SLOT_CONFIRM_ICON  = 4;
    private static final int SLOT_CONFIRM_NO    = 5;

    private void openMainGui(Player p) {
        Inventory inv = newInv(WorldsGuiHolder.Mode.MAIN, null, GUI_SIZE, "✿ KawaiiWorlds ✿");
        populateMain(inv);
        p.openInventory(inv);
    }

    private void openSettingsPicker(Player p) {
        Inventory inv = newInv(WorldsGuiHolder.Mode.SETTINGS_PICKER, null, GUI_SIZE,
                "⚙ Settings — pick a world");
        populatePicker(inv, false);
        p.openInventory(inv);
    }

    private void openSettings(Player p, String worldName) {
        Inventory inv = newInv(WorldsGuiHolder.Mode.SETTINGS, worldName, SETTINGS_SIZE,
                "⚙ " + worldName);
        World w = Bukkit.getWorld(worldName);
        if (w == null) { p.sendMessage("§c(✧) world '" + worldName + "' is not loaded"); return; }
        populateSettings(inv, w);
        p.openInventory(inv);
    }

    private void openImportPicker(Player p) {
        Inventory inv = newInv(WorldsGuiHolder.Mode.IMPORT_PICKER, null, GUI_SIZE,
                "📂 Import — pick a folder");
        populateImportPicker(inv);
        p.openInventory(inv);
    }

    private void openDeletePicker(Player p) {
        Inventory inv = newInv(WorldsGuiHolder.Mode.DELETE_PICKER, null, GUI_SIZE,
                "🗑 Delete — pick a world");
        populatePicker(inv, true);
        p.openInventory(inv);
    }

    private void openDeleteConfirm(Player p, String worldName) {
        Inventory inv = newInv(WorldsGuiHolder.Mode.DELETE_CONFIRM, worldName, CONFIRM_SIZE,
                "Delete '" + worldName + "'?");
        populateDeleteConfirm(inv, worldName);
        p.openInventory(inv);
    }

    private Inventory newInv(WorldsGuiHolder.Mode mode, String worldName, int size, String title) {
        WorldsGuiHolder holder = new WorldsGuiHolder(mode, worldName);
        Inventory inv = Bukkit.createInventory(holder, size, gradientTitle(title));
        holder.inv = inv;
        return inv;
    }

    private void populateMain(Inventory inv) {
        inv.clear();
        inv.setItem(SLOT_CREATE_NORMAL, glint(button(Material.GRASS_BLOCK,  "§a+ Create normal",   "§7Standard overworld with biomes.")));
        inv.setItem(SLOT_CREATE_FLAT,   glint(button(Material.SMOOTH_STONE, "§f+ Create flat",     "§7Superflat — vanilla preset.")));
        inv.setItem(SLOT_CREATE_VOID,   glint(button(Material.STRUCTURE_VOID, "§8+ Create void",   "§7Empty chunks. Bring a platform.")));
        // Skyblock creation lives in the dedicated KawaiiSkyblock plugin now.

        inv.setItem(SLOT_TITLE,    glint(button(Material.NETHER_STAR, "§d✿ KawaiiWorlds ✿",
                "§8click a world below to teleport.",
                "§8right-click for info, shift-click to unload.")));
        inv.setItem(SLOT_SETTINGS, glint(button(Material.COMPARATOR,  "§b⚙ Settings",  "§7configure a world's rules,",
                "§7difficulty, time, weather, etc.")));
        inv.setItem(SLOT_DELETE,   glint(button(Material.TNT,         "§4🗑 Delete world",
                "§7pick a world to permanently delete.",
                "§cConfirmation required.")));
        inv.setItem(SLOT_IMPORT,   glint(button(Material.CHEST,        "§e📂 Import existing",
                "§7load a world folder dropped",
                "§7into the server directory.")));
        inv.setItem(SLOT_CLOSE,    button(Material.BARRIER,     "§c✕ Close",    "§7Closes this menu."));

        int slot = WORLDS_FIRST_SLOT;
        for (World w : Bukkit.getWorlds()) {
            if (slot >= GUI_SIZE) break;
            if (isSkyblockHidden(w)) continue; // skyblock islands live in KawaiiSkyblock, not here
            inv.setItem(slot++, glint(worldIcon(w, "main")));
        }
        while (slot < GUI_SIZE) inv.setItem(slot++, filler()); // frame out empty world slots
    }

    private void populatePicker(Inventory inv, boolean delete) {
        inv.clear();
        inv.setItem(SLOT_BACK, button(Material.ARROW, "§7← Back", "§8back to main menu"));
        if (delete) {
            inv.setItem(SLOT_TITLE, glint(button(Material.TNT, "§4🗑 Pick a world to delete",
                    "§7click a world below.",
                    "§cYou will be asked to confirm.")));
        } else {
            inv.setItem(SLOT_TITLE, glint(button(Material.COMPARATOR, "§b⚙ Pick a world to configure")));
        }
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, "§c✕ Close"));
        int slot = WORLDS_FIRST_SLOT;
        for (World w : Bukkit.getWorlds()) {
            if (slot >= GUI_SIZE) break;
            if (isSkyblockHidden(w)) continue; // skyblock islands are managed by KawaiiSkyblock
            inv.setItem(slot++, glint(worldIcon(w, delete ? "delete" : "settings")));
        }
        while (slot < GUI_SIZE) inv.setItem(slot++, filler());
    }

    private void populateSettings(Inventory inv, World w) {
        inv.clear();
        inv.setItem(SLOT_BACK,  button(Material.ARROW,   "§7← Back", "§8back to settings picker"));
        inv.setItem(SLOT_TITLE, worldIcon(w, "settings-header"));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, "§c✕ Close"));

        for (int i = 0; i < RULE_TOGGLES.size(); i++) {
            inv.setItem(9 + i, ruleToggleIcon(w, RULE_TOGGLES.get(i), RULE_FRIENDLY_NAMES.get(i)));
        }
        inv.setItem(SLOT_PVP,        pvpIcon(w));
        inv.setItem(SLOT_DIFFICULTY, difficultyIcon(w));
        inv.setItem(SLOT_TIME,       timeIcon(w));
        inv.setItem(SLOT_WEATHER,    weatherIcon(w));
        inv.setItem(SLOT_AUTOLOAD,   autoLoadIcon(w));
        inv.setItem(SLOT_SETSPAWN,   button(Material.COMPASS, "§dSet spawn here",
                "§7sets §f" + w.getName() + "§7's spawn",
                "§7to where you're standing.",
                "§8(must be IN this world)"));
        inv.setItem(SLOT_ALLOW_FLY,  allowFlyIcon(w));
        inv.setItem(SLOT_GAMEMODE,   gamemodeIcon(w));
        inv.setItem(SLOT_DEFAULT_SPAWN,    defaultSpawnIcon(w));
        inv.setItem(SLOT_PRESET,           presetIcon(w));
        inv.setItem(SLOT_SPAWN_PROTECTION, spawnProtectionIcon(w));
        inv.setItem(SLOT_LOCK,             lockIcon(w));   // NEW

        // Frame the empty slots so the settings screen reads as a tidy panel.
        for (int s : SETTINGS_FRAME_SLOTS) {
            if (inv.getItem(s) == null) inv.setItem(s, filler());
        }
    }

    /** Slots in the 54-slot settings menu that hold no control — framed with panes. */
    private static final int[] SETTINGS_FRAME_SLOTS =
            {1, 2, 3, 5, 6, 7, 32, 33, 34, 35, 48, 49, 50, 51, 52, 53};

    private ItemStack defaultSpawnIcon(World w) {
        boolean isDefault = w.getName().equals(defaultSpawnWorld);
        ItemStack it = button(isDefault ? Material.BEACON : Material.ENDER_PEARL,
                (isDefault ? "§a" : "§7") + "Default join world",
                "§7current default: §f" + (defaultSpawnWorld == null ? "(primary)" : defaultSpawnWorld),
                "",
                isDefault
                    ? "§e▶ click to UNSET (use primary)"
                    : "§e▶ click to make THIS the default");
        return isDefault ? glint(it) : it;
    }

    private ItemStack presetIcon(World w) {
        String last = getConfig().getString("worlds." + w.getName() + ".last-preset", "vanilla");
        return button(Material.WRITABLE_BOOK,
                "§6Game-rule preset",
                "§7last applied: §f" + last,
                "",
                "§e▶ click to cycle presets",
                "§7vanilla → creative-build → adventure → hardcore");
    }

    private ItemStack spawnProtectionIcon(World w) {
        int r = getConfig().getInt("worlds." + w.getName() + ".spawn-protection", 0);
        return button(Material.SHIELD,
                "§6Spawn protection",
                "§7current: §f" + (r <= 0 ? "off" : r + " blocks"),
                "",
                "§e▶ click to cycle (off → 8 → 16 → 32 → 64)");
    }

    private ItemStack allowFlyIcon(World w) {
        String setting = getConfig().getString("worlds." + w.getName() + ".allow-fly", "default");
        String label;
        switch (setting) {
            case "true":    label = "§aforce on";  break;
            case "false":   label = "§cforce off"; break;
            default:        label = "§7server default";
        }
        return button(Material.FEATHER,
                "§6Allow fly",
                "§7current: " + label,
                "",
                "§e▶ click to cycle (default → on → off)");
    }

    private ItemStack gamemodeIcon(World w) {
        String gm = getConfig().getString("worlds." + w.getName() + ".gamemode", "none");
        return button(Material.COMMAND_BLOCK,
                "§6Force gamemode",
                "§7current: §f" + gm.toLowerCase(),
                "",
                "§e▶ click to cycle (none → survival → creative → adventure → spectator)");
    }

    // NEW: lock icon
    private ItemStack lockIcon(World w) {
        boolean locked = isWorldLocked(w);
        ItemStack it = button(locked ? Material.IRON_DOOR : Material.OAK_DOOR,
                (locked ? "§c" : "§a") + "Lock world",
                "§7current: " + (locked ? "§cLOCKED" : "§aunlocked"),
                "",
                "§e▶ click to toggle",
                locked ? "§7When locked, only admins can enter." : "§7Prevents non‑admins from entering.");
        return locked ? glint(it) : it;
    }

    private void populateImportPicker(Inventory inv) {
        inv.clear();
        inv.setItem(SLOT_BACK, button(Material.ARROW, "§7← Back", "§8back to main menu"));
        inv.setItem(SLOT_TITLE, button(Material.CHEST, "§e📂 Import existing folder",
                "§7folders here have a level.dat",
                "§7but aren't registered yet.",
                "§7click one to load it as §fnormal§7 type.",
                "§8(edit config.yml to change type later)"));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, "§c✕ Close"));

        int slot = WORLDS_FIRST_SLOT;
        for (String name : unregisteredWorldFolderNames()) {
            if (slot >= GUI_SIZE) break;
            inv.setItem(slot++, glint(button(Material.GRASS_BLOCK,
                    "§e" + name,
                    "§7folder: §f" + name,
                    "",
                    "§e▶ click to load as §fnormal§7 type")));
        }
        if (slot == WORLDS_FIRST_SLOT) {
            inv.setItem(WORLDS_FIRST_SLOT, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "§7nothing to import",
                    "§8drop a world folder into the server",
                    "§8root next to your primary world,",
                    "§8then come back here."));
            slot++;
        }
        while (slot < GUI_SIZE) inv.setItem(slot++, filler());
    }

    private List<String> unregisteredWorldFolderNames() {
        List<String> all = allWorldFolderNames();
        ConfigurationSection cfgWorlds = getConfig().getConfigurationSection("worlds");
        java.util.Set<String> registered = cfgWorlds == null ? java.util.Set.of() : cfgWorlds.getKeys(false);
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (World w : Bukkit.getWorlds()) loaded.add(w.getName());
        List<String> out = new ArrayList<>();
        for (String n : all) {
            if (!registered.contains(n) && !loaded.contains(n)) out.add(n);
        }
        return out;
    }

    private void populateDeleteConfirm(Inventory inv, String worldName) {
        inv.clear();
        ItemStack pane = button(Material.RED_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < CONFIRM_SIZE; i++) inv.setItem(i, pane);
        inv.setItem(SLOT_CONFIRM_YES, glint(button(Material.LIME_TERRACOTTA, "§a✓ CONFIRM DELETE",
                "§7this PERMANENTLY deletes",
                "§7the '§f" + worldName + "§7' folder.")));
        World w = Bukkit.getWorld(worldName);
        if (w != null) inv.setItem(SLOT_CONFIRM_ICON, glint(worldIcon(w, "settings-header")));
        else inv.setItem(SLOT_CONFIRM_ICON, button(Material.MAP, "§d" + worldName, "§7(not currently loaded)"));
        inv.setItem(SLOT_CONFIRM_NO, button(Material.RED_TERRACOTTA, "§c✕ CANCEL", "§7back to picker"));
    }

    private ItemStack worldIcon(World w, String mode) {
        ConfigurationSection cfg = getConfig().getConfigurationSection("worlds." + w.getName());
        String type = cfg != null ? cfg.getString("type", "primary") : "primary";

        Material mat;
        switch (type) {
            case "nether":   mat = Material.NETHERRACK;    break;
            case "end":      mat = Material.END_STONE;     break;
            case "flat":     mat = Material.SMOOTH_STONE;  break;
            case "void":     mat = Material.LIGHT_GRAY_STAINED_GLASS; break;
            case "skyblock": mat = Material.OAK_SAPLING;   break;
            case "normal":   mat = Material.GRASS_BLOCK;   break;
            case "primary":
            default:         mat = Material.MAP;
        }

        List<String> lore = new ArrayList<>();
        lore.add("§7type: §f" + type);
        lore.add("§7env:  §f" + w.getEnvironment().name().toLowerCase());
        lore.add("§7players: §f" + w.getPlayers().size());
        if (isWorldLocked(w)) lore.add("§c🔒 LOCKED");
        switch (mode) {
            case "delete":
                lore.add("");
                lore.add("§c▶ click to delete");
                break;
            case "settings":
                lore.add("");
                lore.add("§e▶ click to configure");
                break;
            case "settings-header":
                lore.add("§7seed: §f" + w.getSeed());
                break;
            case "main":
            default:
                lore.add("");
                lore.add("§e▶ left-click §7teleport");
                lore.add("§e▶ right-click §7info");
                lore.add("§e▶ shift-click §7unload");
        }
        return button(mat, "§d" + w.getName(), lore.toArray(new String[0]));
    }

    private ItemStack ruleToggleIcon(World w, GameRule<Boolean> rule, String displayName) {
        Boolean v = w.getGameRuleValue(rule);
        boolean on = Boolean.TRUE.equals(v);
        ItemStack it = button(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "§a" : "§7") + displayName,
                "§7current: " + (on ? "§atrue" : "§cfalse"),
                "",
                "§e▶ click to toggle");
        return on ? glint(it) : it;
    }

    private ItemStack pvpIcon(World w) {
        boolean on = w.getPVP();
        ItemStack it = button(on ? Material.IRON_SWORD : Material.WOODEN_SWORD,
                (on ? "§c" : "§7") + "PvP",
                "§7current: " + (on ? "§aenabled" : "§cdisabled"),
                "",
                "§e▶ click to toggle");
        return on ? glint(it) : it;
    }

    private ItemStack difficultyIcon(World w) {
        Difficulty d = w.getDifficulty();
        return button(Material.REDSTONE_LAMP,
                "§6Difficulty",
                "§7current: §f" + d.name().toLowerCase(),
                "",
                "§e▶ click to cycle (peaceful → easy → normal → hard)");
    }

    private ItemStack timeIcon(World w) {
        long t = w.getTime();
        String label = t < 6000 ? "day" : t < 13000 ? "noon" : t < 18000 ? "night" : "midnight";
        return button(Material.CLOCK,
                "§6Time",
                "§7current: §f" + label + " §8(" + t + ")",
                "",
                "§e▶ click to cycle (day → noon → night → midnight)");
    }

    private ItemStack weatherIcon(World w) {
        String label = w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear";
        Material mat = w.isThundering() ? Material.LIGHTNING_ROD
                     : w.hasStorm()    ? Material.WATER_BUCKET
                     :                   Material.GLASS;
        return button(mat,
                "§6Weather",
                "§7current: §f" + label,
                "",
                "§e▶ click to cycle (clear → rain → thunder)");
    }

    private ItemStack autoLoadIcon(World w) {
        if (Bukkit.getWorlds().get(0).equals(w)) {
            return button(Material.REPEATER, "§7auto-load",
                    "§7the primary world is always loaded.");
        }
        boolean al = getConfig().getBoolean("worlds." + w.getName() + ".auto-load", true);
        ItemStack it = button(Material.REPEATER,
                (al ? "§a" : "§7") + "auto-load",
                "§7current: " + (al ? "§atrue" : "§cfalse"),
                "",
                "§e▶ click to toggle");
        return al ? glint(it) : it;
    }

    private static ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacy(name).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> loreLines = new ArrayList<>();
                for (String l : lore) loreLines.add(legacy(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(loreLines);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---- visual polish: glint, frame panes, gradient titles ----

    /** Decorative pane used to frame the roomier menus. */
    private static final Material FILLER = Material.MAGENTA_STAINED_GLASS_PANE;
    private static final int GRAD_A = 0xFF8AD8; // pink
    private static final int GRAD_B = 0xC56BFF; // purple

    /** Add an enchant-glint sheen to an item (no real enchantment) and return it. */
    private static ItemStack glint(ItemStack it) {
        if (it == null) return null;
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            try {
                m.setEnchantmentGlintOverride(true);
            } catch (Throwable ignored) {
                // Older API without the glint override — skip the sheen silently.
            }
            it.setItemMeta(m);
        }
        return it;
    }

    /** A blank decorative frame pane. */
    private static ItemStack filler() {
        return button(FILLER, " ");
    }

    /** True if this is one of our decorative frame panes (clicks are no-ops). */
    private static boolean isFiller(ItemStack it) {
        return it != null && it.getType() == FILLER;
    }

    /** A pink→purple bold gradient component for menu title bars. */
    private static Component gradientTitle(String text) {
        var b = Component.text();
        int n = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            double t = n <= 1 ? 0 : (double) i / (n - 1);
            b.append(Component.text(String.valueOf(text.charAt(i))).color(lerp(GRAD_A, GRAD_B, t)));
        }
        return b.build().decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    /** Linear RGB interpolation between two packed 0xRRGGBB colors. */
    private static TextColor lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) Math.round(((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) Math.round((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return TextColor.color(r, g, bl);
    }

    private static Component legacy(String s) {
        if (s == null) return Component.empty();
        Component out = Component.empty();
        NamedTextColor color = null;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                if (buf.length() > 0) {
                    out = out.append(Component.text(buf.toString(), color));
                    buf.setLength(0);
                }
                char code = Character.toLowerCase(s.charAt(++i));
                color = colorForCode(code);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) out = out.append(Component.text(buf.toString(), color));
        return out;
    }

    private static NamedTextColor colorForCode(char c) {
        switch (c) {
            case '0': return NamedTextColor.BLACK;
            case '1': return NamedTextColor.DARK_BLUE;
            case '2': return NamedTextColor.DARK_GREEN;
            case '3': return NamedTextColor.DARK_AQUA;
            case '4': return NamedTextColor.DARK_RED;
            case '5': return NamedTextColor.DARK_PURPLE;
            case '6': return NamedTextColor.GOLD;
            case '7': return NamedTextColor.GRAY;
            case '8': return NamedTextColor.DARK_GRAY;
            case '9': return NamedTextColor.BLUE;
            case 'a': return NamedTextColor.GREEN;
            case 'b': return NamedTextColor.AQUA;
            case 'c': return NamedTextColor.RED;
            case 'd': return NamedTextColor.LIGHT_PURPLE;
            case 'e': return NamedTextColor.YELLOW;
            case 'f': return NamedTextColor.WHITE;
            default:  return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuiClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof WorldsGuiHolder)) return;
        WorldsGuiHolder holder = (WorldsGuiHolder) top.getHolder();
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (!p.hasPermission(PERM_ADMIN) && !p.isOp()) {
            p.sendMessage("§c(✧) you don't have permission~");
            p.closeInventory();
            return;
        }
        if (e.getClickedInventory() != top) return;

        int slot = e.getRawSlot();
        ClickType click = e.getClick();

        switch (holder.mode) {
            case MAIN:             handleMainClick(p, top, slot, click); return;
            case SETTINGS_PICKER:  handlePickerClick(p, slot, false);    return;
            case SETTINGS:         handleSettingsClick(p, top, holder.worldName, slot); return;
            case DELETE_PICKER:    handlePickerClick(p, slot, true);     return;
            case DELETE_CONFIRM:   handleDeleteConfirmClick(p, holder.worldName, slot); return;
            case IMPORT_PICKER:    handleImportPickerClick(p, slot);                   return;
        }
    }

    private void handleImportPickerClick(Player p, int slot) {
        if (slot == SLOT_BACK)  { p.closeInventory(); openMainGui(p); return; }
        if (slot == SLOT_CLOSE) { p.closeInventory(); return; }
        if (slot == SLOT_TITLE) return;
        if (slot < WORLDS_FIRST_SLOT || slot >= GUI_SIZE) return;

        ItemStack it = p.getOpenInventory().getTopInventory().getItem(slot);
        if (it == null || it.getType() == Material.AIR) return;
        // Decorative frame panes and the "nothing to import" placeholder aren't folders.
        if (isFiller(it) || it.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE) return;
        ItemMeta meta = it.getItemMeta();
        if (meta == null || meta.displayName() == null) return;
        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();

        long seed = ThreadLocalRandom.current().nextLong();
        World w;
        try {
            w = createOrLoadWorld(name, "normal", seed);
        } catch (Throwable t) {
            p.sendMessage("§c(✧) import failed: " + t.getMessage());
            return;
        }
        if (w == null) {
            p.sendMessage("§c(✧) import returned null world (check console)");
            return;
        }
        getConfig().set("worlds." + name + ".type", "normal");
        getConfig().set("worlds." + name + ".seed", w.getSeed());
        getConfig().set("worlds." + name + ".auto-load", true);
        saveConfig();
        p.sendMessage("§a(✧) imported '" + name + "' as normal type ✨");
        p.closeInventory();
        openMainGui(p);
    }

    private void handleMainClick(Player p, Inventory top, int slot, ClickType click) {
        switch (slot) {
            case SLOT_CREATE_NORMAL: beginCreatePrompt(p, "normal");   return;
            case SLOT_CREATE_FLAT:   beginCreatePrompt(p, "flat");     return;
            case SLOT_CREATE_VOID:   beginCreatePrompt(p, "void");     return;
            case SLOT_SETTINGS:      p.closeInventory(); openSettingsPicker(p); return;
            case SLOT_DELETE:        p.closeInventory(); openDeletePicker(p);   return;
            case SLOT_IMPORT:        p.closeInventory(); openImportPicker(p);   return;
            case SLOT_CLOSE:         p.closeInventory(); return;
            case SLOT_TITLE:         return;
            default: break;
        }
        if (slot < WORLDS_FIRST_SLOT || slot >= GUI_SIZE) return;
        if (isFiller(top.getItem(slot))) return; // decorative frame pane

        World w = readWorldFromIcon(top, slot);
        if (w == null) { p.sendMessage("§c(✧) world unloaded"); populateMain(top); return; }

        // Lock check for teleport (left-click)
        if (!click.isShiftClick() && !click.isRightClick() && isWorldLocked(w) && !p.hasPermission(PERM_ADMIN) && !p.isOp()) {
            p.sendMessage("§c(✧) world '" + w.getName() + "' is locked – you cannot enter.");
            return;
        }

        if (click.isShiftClick()) {
            p.closeInventory();
            if (w.equals(Bukkit.getWorlds().get(0))) {
                p.sendMessage("§c(✧) cannot unload the primary world");
                return;
            }
            for (Player pl : new ArrayList<>(w.getPlayers())) {
                pl.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            if (Bukkit.unloadWorld(w, true)) p.sendMessage("§a(✧) unloaded '" + w.getName() + "'");
            else p.sendMessage("§c(✧) unload failed (something held it open)");
            return;
        }
        if (click.isRightClick()) {
            Location s = w.getSpawnLocation();
            p.sendMessage("§d(✧) " + w.getName());
            p.sendMessage("  §7env:    §f" + w.getEnvironment().name().toLowerCase());
            p.sendMessage("  §7seed:   §f" + w.getSeed());
            p.sendMessage("  §7spawn:  §f" + (int) s.getX() + ", " + (int) s.getY() + ", " + (int) s.getZ());
            p.sendMessage("  §7players:§f " + w.getPlayers().size());
            return;
        }
        // left-click teleport (already checked lock above)
        p.closeInventory();
        p.teleportAsync(w.getSpawnLocation());
        p.sendMessage("§d(✧) → " + w.getName());
    }

    private void handlePickerClick(Player p, int slot, boolean delete) {
        if (slot == SLOT_BACK)  { p.closeInventory(); openMainGui(p); return; }
        if (slot == SLOT_CLOSE) { p.closeInventory(); return; }
        if (slot == SLOT_TITLE) return;
        if (slot < WORLDS_FIRST_SLOT || slot >= GUI_SIZE) return;

        Inventory pick = p.getOpenInventory().getTopInventory();
        if (isFiller(pick.getItem(slot))) return; // decorative frame pane
        World w = readWorldFromIcon(pick, slot);
        if (w == null) { p.sendMessage("§c(✧) world unloaded"); return; }

        p.closeInventory();
        if (delete) openDeleteConfirm(p, w.getName());
        else        openSettings(p, w.getName());
    }

    private void handleSettingsClick(Player p, Inventory top, String worldName, int slot) {
        if (slot == SLOT_BACK)  { p.closeInventory(); openSettingsPicker(p); return; }
        if (slot == SLOT_CLOSE) { p.closeInventory(); return; }
        if (slot == SLOT_TITLE) return;

        World w = Bukkit.getWorld(worldName);
        if (w == null) { p.sendMessage("§c(✧) world unloaded"); p.closeInventory(); return; }

        int ruleIdx = slot - 9;
        if (ruleIdx >= 0 && ruleIdx < RULE_TOGGLES.size()) {
            GameRule<Boolean> rule = RULE_TOGGLES.get(ruleIdx);
            Boolean cur = w.getGameRuleValue(rule);
            w.setGameRule(rule, !Boolean.TRUE.equals(cur));
            // Force refresh the GUI to show the new state
            populateSettings(top, w);
            return;
        }
        switch (slot) {
            case SLOT_PVP:        w.setPVP(!w.getPVP()); break;
            case SLOT_DIFFICULTY: cycleDifficulty(w);    break;
            case SLOT_TIME:       cycleTime(w);          break;
            case SLOT_WEATHER:    cycleWeather(w);       break;
            case SLOT_AUTOLOAD:   toggleAutoLoad(w);     break;
            case SLOT_SETSPAWN:
                if (!p.getWorld().equals(w)) {
                    p.sendMessage("§c(✧) you must be IN '" + w.getName() + "' to set its spawn");
                    return;
                }
                Location loc = p.getLocation();
                w.setSpawnLocation(loc);
                p.sendMessage("§d(✧) spawn updated for " + w.getName() + " → "
                        + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ());
                return;
            case SLOT_ALLOW_FLY:  cycleAllowFly(w); break;
            case SLOT_GAMEMODE:   cycleGamemode(w); break;
            case SLOT_DEFAULT_SPAWN:    toggleDefaultSpawn(w); break;
            case SLOT_PRESET:           cyclePreset(w);        break;
            case SLOT_SPAWN_PROTECTION: cycleSpawnProtection(w); break;
            case SLOT_LOCK:             toggleLock(w);         break; // NEW
            default: return;
        }
        populateSettings(top, w);
    }

    private void handleDeleteConfirmClick(Player p, String worldName, int slot) {
        if (slot == SLOT_CONFIRM_NO) {
            p.closeInventory();
            openDeletePicker(p);
            return;
        }
        if (slot != SLOT_CONFIRM_YES) return;

        p.closeInventory();
        boolean ok = performDelete(p, worldName);
        if (ok) p.sendMessage("§a(✧) deleted '" + worldName + "'");
    }

    private boolean performDelete(Player p, String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            if (w.equals(Bukkit.getWorlds().get(0))) {
                p.sendMessage("§c(✧) cannot delete the primary world");
                return false;
            }
            for (Player pl : new ArrayList<>(w.getPlayers())) {
                pl.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
            if (!Bukkit.unloadWorld(w, false)) {
                p.sendMessage("§c(✧) couldn't unload '" + worldName + "' before delete");
                return false;
            }
        }
        // Purge the config entry FIRST, before touching the folder. Region
        // files can stay locked by the OS for a moment after unload, so the
        // folder delete may throw — but we still want the world gone from
        // KawaiiWorlds' config either way (otherwise it "just unloads" and
        // reappears). Config removal is the source of truth.
        getConfig().set("worlds." + worldName, null);
        saveConfig();
        spawnProtectionRadius.remove(worldName);

        File folder = new File(Bukkit.getWorldContainer(), worldName);
        if (folder.exists()) {
            // Off the main thread — a big world folder can take seconds to delete.
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    deleteRecursively(folder.toPath());
                } catch (Throwable t) {
                    Bukkit.getScheduler().runTask(this, () ->
                            p.sendMessage("§e(✧) removed '" + worldName + "' from config, but the folder "
                                    + "couldn't be fully deleted (" + t.getMessage() + "). "
                                    + "Delete it manually if it lingers."));
                }
            });
        }
        return true;
    }

    private static World readWorldFromIcon(Inventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType() == Material.AIR) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null || meta.displayName() == null) return null;
        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
        return Bukkit.getWorld(name);
    }

    private void cycleDifficulty(World w) {
        Difficulty[] all = Difficulty.values();
        w.setDifficulty(all[(w.getDifficulty().ordinal() + 1) % all.length]);
    }

    private void cycleTime(World w) {
        long t = w.getTime();
        long next;
        if (t < 6000)        next = 6000;
        else if (t < 13000)  next = 13000;
        else if (t < 18000)  next = 18000;
        else                 next = 1000;
        w.setTime(next);
    }

    private void cycleWeather(World w) {
        if (w.isThundering()) { w.setStorm(false); w.setThundering(false); }
        else if (w.hasStorm()) { w.setThundering(true); }
        else                   { w.setStorm(true); }
    }

    private void toggleAutoLoad(World w) {
        if (Bukkit.getWorlds().get(0).equals(w)) return;
        String key = "worlds." + w.getName() + ".auto-load";
        boolean cur = getConfig().getBoolean(key, true);
        getConfig().set(key, !cur);
        saveConfig();
    }

    private void cycleAllowFly(World w) {
        String key = "worlds." + w.getName() + ".allow-fly";
        String cur = getConfig().getString(key, "default");
        String next;
        switch (cur) {
            case "default": next = "true";  break;
            case "true":    next = "false"; break;
            default:        next = "default";
        }
        if ("default".equals(next)) getConfig().set(key, null);
        else getConfig().set(key, next);
        saveConfig();
        for (Player pl : w.getPlayers()) applyWorldForces(pl, w);
    }

    private void toggleDefaultSpawn(World w) {
        if (w.getName().equals(defaultSpawnWorld)) {
            defaultSpawnWorld = null;
            getConfig().set("default-spawn-world", null);
        } else {
            defaultSpawnWorld = w.getName();
            getConfig().set("default-spawn-world", w.getName());
        }
        saveConfig();
    }

    private static final java.util.LinkedHashMap<String, java.util.Map<String, Boolean>> PRESETS = new java.util.LinkedHashMap<>();
    static {
        PRESETS.put("vanilla", java.util.Map.of(
                "keepInventory", false, "doDaylightCycle", true, "doMobSpawning", true,
                "doFireTick", true, "mobGriefing", true, "doWeatherCycle", true,
                "naturalRegeneration", true, "doMobLoot", true, "announceAdvancements", true));
        PRESETS.put("creative-build", java.util.Map.of(
                "keepInventory", true, "doDaylightCycle", false, "doMobSpawning", false,
                "doFireTick", false, "mobGriefing", false, "doWeatherCycle", false,
                "naturalRegeneration", true, "doMobLoot", false, "announceAdvancements", false));
        PRESETS.put("adventure", java.util.Map.of(
                "keepInventory", true, "doDaylightCycle", true, "doMobSpawning", true,
                "doFireTick", true, "mobGriefing", false, "doWeatherCycle", true,
                "naturalRegeneration", true, "doMobLoot", true, "announceAdvancements", true));
        PRESETS.put("hardcore", java.util.Map.of(
                "keepInventory", false, "doDaylightCycle", true, "doMobSpawning", true,
                "doFireTick", true, "mobGriefing", true, "doWeatherCycle", true,
                "naturalRegeneration", false, "doMobLoot", true, "announceAdvancements", true));
    }

    private void cyclePreset(World w) {
        String last = getConfig().getString("worlds." + w.getName() + ".last-preset", "vanilla");
        List<String> keys = new ArrayList<>(PRESETS.keySet());
        int idx = keys.indexOf(last);
        if (idx < 0) idx = -1;
        String next = keys.get((idx + 1) % keys.size());
        java.util.Map<String, Boolean> values = PRESETS.get(next);
        for (int i = 0; i < RULE_TOGGLES.size(); i++) {
            String displayName = RULE_DISPLAY_NAMES.get(i);
            Boolean v = values.get(displayName);
            if (v != null) w.setGameRule(RULE_TOGGLES.get(i), v);
        }
        getConfig().set("worlds." + w.getName() + ".last-preset", next);
        saveConfig();
    }

    // Rebuild the spawn-protection radius cache from the current config.
    // Only positive radii are stored; missing entries mean "no protection".
    private void reloadSpawnProtectionCache() {
        spawnProtectionRadius.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection w = sec.getConfigurationSection(name);
            if (w == null) continue;
            int r = w.getInt("spawn-protection", 0);
            if (r > 0) spawnProtectionRadius.put(name, r);
        }
    }

    private void cycleSpawnProtection(World w) {
        String key = "worlds." + w.getName() + ".spawn-protection";
        int cur = getConfig().getInt(key, 0);
        int next;
        if (cur <= 0)       next = 8;
        else if (cur < 16)  next = 16;
        else if (cur < 32)  next = 32;
        else if (cur < 64)  next = 64;
        else                next = 0;
        if (next <= 0) getConfig().set(key, null);
        else getConfig().set(key, next);
        saveConfig();
        // Keep the hot-path cache in sync with the new value.
        if (next <= 0) spawnProtectionRadius.remove(w.getName());
        else spawnProtectionRadius.put(w.getName(), next);
    }

    // NEW: lock toggle logic
    private boolean isWorldLocked(World w) {
        return getConfig().getBoolean("worlds." + w.getName() + ".locked", false);
    }

    private void toggleLock(World w) {
        String key = "worlds." + w.getName() + ".locked";
        boolean cur = isWorldLocked(w);
        getConfig().set(key, !cur);
        saveConfig();
        // Notify any players in the world that it's now locked/unlocked (optional)
        String msg = !cur ? "§cWorld '" + w.getName() + "' is now LOCKED – no entry for non‑admins."
                          : "§aWorld '" + w.getName() + "' is now unlocked.";
        for (Player p : w.getPlayers()) {
            if (!p.hasPermission(PERM_ADMIN) && !p.isOp()) {
                p.sendMessage("§e(✧) " + msg);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (isInProtectedSpawn(e.getBlock().getLocation(), e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text(
                    "✕ spawn-protected area", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isInProtectedSpawn(e.getBlock().getLocation(), e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text(
                    "✕ spawn-protected area", NamedTextColor.RED));
        }
    }

    private boolean isInProtectedSpawn(Location at, Player p) {
        World w = at.getWorld();
        if (w == null) return false;
        // Cached radius lookup: absent / non-positive means no protection -> early out
        // before touching permissions or the world spawn location.
        Integer cached = spawnProtectionRadius.get(w.getName());
        if (cached == null) return false;
        int r = cached;
        if (r <= 0) return false;
        if (p.isOp() || p.hasPermission("kawaiiworlds.bypass-protection")) return false;
        Location spawn = w.getSpawnLocation();
        double dx = at.getX() - spawn.getX();
        double dz = at.getZ() - spawn.getZ();
        return (dx * dx + dz * dz) <= (double) r * r;
    }

    private void cycleGamemode(World w) {
        String key = "worlds." + w.getName() + ".gamemode";
        String cur = getConfig().getString(key, "none").toLowerCase(Locale.ROOT);
        String next;
        switch (cur) {
            case "none":      next = "survival"; break;
            case "survival":  next = "creative"; break;
            case "creative":  next = "adventure"; break;
            case "adventure": next = "spectator"; break;
            default:          next = "none";
        }
        if ("none".equals(next)) getConfig().set(key, null);
        else getConfig().set(key, next);
        saveConfig();
        for (Player pl : w.getPlayers()) applyWorldForces(pl, w);
    }

    // ============================================================
    //   PER-WORLD INVENTORY + WORLD FORCES
    // ============================================================

    private String groupOf(World w) {
        String n = w.getName();
        if (n.endsWith("_nether")) {
            String base = n.substring(0, n.length() - "_nether".length());
            if (Bukkit.getWorld(base) != null) return base;
        }
        if (n.endsWith("_the_end")) {
            String base = n.substring(0, n.length() - "_the_end".length());
            if (Bukkit.getWorld(base) != null) return base;
        }
        return n;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore() && defaultSpawnWorld != null && !defaultSpawnWorld.isEmpty()) {
            World w = Bukkit.getWorld(defaultSpawnWorld);
            if (w != null) {
                getServer().getScheduler().runTask(this, () -> p.teleport(w.getSpawnLocation()));
            }
        }
        applyWorldForces(p, p.getWorld());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (perWorldInventory) {
            saveSnapshot(e.getPlayer(), groupOf(e.getPlayer().getWorld()));
        }
        // Clean up per-player state so the maps don't grow forever.
        deathWorld.remove(e.getPlayer().getUniqueId());
        pendingCreate.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        deathWorld.put(e.getEntity().getUniqueId(), e.getEntity().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent e) {
        String wname = deathWorld.remove(e.getPlayer().getUniqueId());
        if (wname == null) return;
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        World w = Bukkit.getWorld(wname);
        if (w != null) e.setRespawnLocation(w.getSpawnLocation());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        World from = e.getFrom();
        World to = p.getWorld();
        if (perWorldInventory) {
            String fromGroup = groupOf(from);
            String toGroup   = groupOf(to);
            if (!fromGroup.equals(toGroup)) {
                saveSnapshot(p, fromGroup);
                loadSnapshot(p, toGroup);
            }
        }
        applyWorldForces(p, to);
    }

    private void applyWorldForces(Player p, World w) {
        if (w == null) return;
        ConfigurationSection cfg = getConfig().getConfigurationSection("worlds." + w.getName());
        if (cfg == null) return;

        if (cfg.contains("allow-fly")) {
            String setting = cfg.getString("allow-fly", "default");
            if ("true".equals(setting))       { p.setAllowFlight(true); }
            else if ("false".equals(setting)) { p.setAllowFlight(false); p.setFlying(false); }
        }
        String gm = cfg.getString("gamemode", null);
        if (gm != null && !"none".equalsIgnoreCase(gm)) {
            try { p.setGameMode(GameMode.valueOf(gm.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private File snapshotFile(UUID playerId, String groupName) {
        return new File(getDataFolder(),
                "playerdata" + File.separator + groupName + File.separator + playerId + ".yml");
    }

    private void saveSnapshot(Player p, String groupName) {
        final File file = snapshotFile(p.getUniqueId(), groupName);
        final String yaml;
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            PlayerInventory inv = p.getInventory();
            cfg.set("contents", Arrays.asList(inv.getContents()));
            cfg.set("armor",    Arrays.asList(inv.getArmorContents()));
            cfg.set("offhand",  inv.getItemInOffHand());
            cfg.set("xp.level",    p.getLevel());
            cfg.set("xp.progress", p.getExp());
            cfg.set("xp.total",    p.getTotalExperience());
            cfg.set("health",      p.getHealth());
            cfg.set("food",        p.getFoodLevel());
            cfg.set("saturation",  p.getSaturation());
            cfg.set("gamemode",    p.getGameMode().name());
            yaml = cfg.saveToString();
        } catch (Throwable t) {
            getLogger().warning("(✧) failed to serialize snapshot for " + p.getName()
                    + " in group '" + groupName + "': " + t.getMessage());
            return;
        }
        final String pname = p.getName();
        Runnable writer = () -> {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                getLogger().warning("(✧) failed to save snapshot for " + pname
                        + " in group '" + groupName + "': " + ex.getMessage());
            }
        };
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, writer);
        } else {
            writer.run();
        }
    }

    private void loadSnapshot(Player p, String groupName) {
        File file = snapshotFile(p.getUniqueId(), groupName);
        PlayerInventory inv = p.getInventory();
        if (!file.exists()) {
            inv.clear();
            inv.setArmorContents(new ItemStack[4]);
            inv.setItemInOffHand(null);
            p.setLevel(0);
            p.setExp(0f);
            p.setTotalExperience(0);
            return;
        }
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            inv.setContents(toItemArray(cfg.getList("contents"), 41));
            inv.setArmorContents(toItemArray(cfg.getList("armor"), 4));
            Object oh = cfg.get("offhand");
            inv.setItemInOffHand(oh instanceof ItemStack ? (ItemStack) oh : null);
            p.setLevel(cfg.getInt("xp.level", 0));
            p.setExp((float) cfg.getDouble("xp.progress", 0.0));
            p.setTotalExperience(cfg.getInt("xp.total", 0));
            if (cfg.isSet("health")) {
                double h = cfg.getDouble("health", p.getHealth());
                // getMaxHealth() (deprecated) avoids Attribute.MAX_HEALTH, whose enum
                // constant differs across 1.21.x builds (GENERIC_MAX_HEALTH on 1.21).
                if (h > 0) p.setHealth(Math.min(h, p.getMaxHealth()));
            }
            if (cfg.isSet("food"))       p.setFoodLevel(cfg.getInt("food", 20));
            if (cfg.isSet("saturation")) p.setSaturation((float) cfg.getDouble("saturation", 5.0));
        } catch (Throwable t) {
            getLogger().warning("(✧) failed to load snapshot for " + p.getName()
                    + " in group '" + groupName + "': " + t.getMessage());
        }
    }

    private static ItemStack[] toItemArray(List<?> list, int defaultLen) {
        if (list == null) return new ItemStack[defaultLen];
        ItemStack[] arr = new ItemStack[Math.max(list.size(), defaultLen)];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v instanceof ItemStack) arr[i] = (ItemStack) v;
        }
        return arr;
    }

    @EventHandler
    public void onGuiClose(InventoryCloseEvent e) {
        // nothing needed
    }

    private void beginCreatePrompt(Player p, String type) {
        pendingCreate.put(p.getUniqueId(), new PendingCreate(type));
        p.closeInventory();
        p.sendMessage("§d(✧) creating a §f" + type + "§d world.");
        p.sendMessage("§7type a name in chat (a-z, 0-9, _ -). type §fcancel§7 to abort.");
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            PendingCreate pc = pendingCreate.get(id);
            if (pc != null && System.currentTimeMillis() - pc.createdMs >= PENDING_TIMEOUT_MS) {
                pendingCreate.remove(id);
                Player still = Bukkit.getPlayer(id);
                if (still != null && still.isOnline()) {
                    still.sendMessage("§7(✧) world-name prompt timed out");
                }
            }
        }, PENDING_TIMEOUT_MS / 50L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        PendingCreate pc = pendingCreate.remove(id);
        if (pc == null) return;
        e.setCancelled(true);

        String name = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        if (name.isEmpty() || name.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(this, () -> p.sendMessage("§7(✧) cancelled"));
            return;
        }

        final String chosen = name;
        final String type = pc.type;
        Bukkit.getScheduler().runTask(this, () -> runGuiCreate(p, chosen, type));
    }

    private void runGuiCreate(Player p, String name, String type) {
        if (!isValidWorldName(name)) {
            p.sendMessage("§c(✧) name '" + name + "' must be alphanumeric / _ / -, no spaces");
            return;
        }
        if (Bukkit.getWorld(name) != null) {
            p.sendMessage("§c(✧) world '" + name + "' is already loaded");
            return;
        }
        if (worldFolderExists(name)) {
            p.sendMessage("§c(✧) folder '" + name + "' already exists. /kw load " + name + " ?");
            return;
        }
        long seed = ThreadLocalRandom.current().nextLong();
        p.sendMessage("§d(✧) creating '" + name + "' (" + type + ", seed " + seed + ")...");
        World w;
        try {
            w = createOrLoadWorld(name, type, seed);
        } catch (Throwable t) {
            p.sendMessage("§c(✧) creation failed: " + t.getMessage());
            return;
        }
        if (w == null) {
            p.sendMessage("§c(✧) creation returned null world (check console)");
            return;
        }
        String base = "worlds." + name + ".";
        getConfig().set(base + "type", type);
        getConfig().set(base + "seed", seed);
        getConfig().set(base + "auto-load", true);
        saveConfig();
        p.sendMessage("§a(✧) world '" + name + "' ready ✨");
        p.teleportAsync(w.getSpawnLocation());
    }

    // ============================================================
    //   WORLD CREATION HELPER
    // ============================================================

    private World createOrLoadWorld(String name, String type, long seed) {
        WorldCreator wc = new WorldCreator(name).seed(seed);
        switch (type.toLowerCase(Locale.ROOT)) {
            case "nether":
                wc.environment(World.Environment.NETHER);
                break;
            case "end":
                wc.environment(World.Environment.THE_END);
                break;
            case "flat":
                wc.environment(World.Environment.NORMAL);
                wc.type(WorldType.FLAT);
                break;
            case "void":
                wc.environment(World.Environment.NORMAL);
                wc.generator((ChunkGenerator) new VoidGenerator());
                break;
            case "skyblock":
                wc.environment(World.Environment.NORMAL);
                wc.generator((ChunkGenerator) new SkyblockGenerator());
                break;
            case "normal":
            default:
                wc.environment(World.Environment.NORMAL);
                break;
        }
        // NOTE: createWorld() blocks the main thread while the world is generated.
        // This stall is inherent to the Bukkit API — worlds must be created on the
        // main thread and cannot be moved off it. Do not attempt to make this async.
        World w = wc.createWorld();
        if (w != null && ("void".equals(type) || "skyblock".equals(type))) {
            try {
                w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            } catch (Throwable ignored) {}
        }
        return w;
    }

    // ============================================================
    //   TAB COMPLETION
    // ============================================================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN) && !sender.isOp()) return List.of();

        if (args.length == 1) {
            return prefixFilter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "create":
                    return List.of();
                case "tp":
                case "teleport":
                case "info":
                case "unload":
                    return prefixFilter(loadedWorldNames(), args[1]);
                case "load":
                case "delete":
                case "del":
                case "remove":
                    return prefixFilter(allWorldFolderNames(), args[1]);
                default:
                    return List.of();
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("create".equals(sub)) return prefixFilter(TYPES, args[2]);
            if ("tp".equals(sub) || "teleport".equals(sub)) {
                return prefixFilter(onlinePlayerNames(), args[2]);
            }
            if ("delete".equals(sub) || "del".equals(sub) || "remove".equals(sub)) {
                return prefixFilter(List.of("--confirm"), args[2]);
            }
        }
        return List.of();
    }

    // ============================================================
    //   HELPERS
    // ============================================================

    private static List<String> prefixFilter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }

    private static List<String> loadedWorldNames() {
        return Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
    }

    /**
     * Per-player skyblock islands (managed by the KawaiiSkyblock plugin) and the
     * skyblock template folder should NOT clutter the world hub — they'd flood
     * the listing with one entry per player. Hide them from the main world list,
     * pickers and import screen.
     */
    private static boolean isSkyblockHidden(String name) {
        if (name == null) return false;
        return name.startsWith("kawaii_isle_") || name.equals("SKYBLOCK ISLAND ADVANCED");
    }

    private static boolean isSkyblockHidden(World w) {
        return w != null && isSkyblockHidden(w.getName());
    }

    private List<String> allWorldFolderNames() {
        File container = Bukkit.getWorldContainer();
        File[] entries = container.listFiles();
        if (entries == null) return List.of();
        List<String> out = new ArrayList<>();
        for (File f : entries) {
            if (!f.isDirectory()) continue;
            if (isSkyblockHidden(f.getName())) continue; // skyblock islands are managed by KawaiiSkyblock
            if (new File(f, "level.dat").exists()) out.add(f.getName());
        }
        return out;
    }

    private static List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private static boolean isValidWorldName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !(lower.equals("world_nether") || lower.equals("world_the_end"));
    }

    private static boolean worldFolderExists(String name) {
        File f = new File(Bukkit.getWorldContainer(), name);
        return f.isDirectory() && new File(f, "level.dat").exists();
    }

    private static void deleteRecursively(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> stream = Files.walk(p)) {
            stream.sorted(Comparator.reverseOrder()).forEach(child -> {
                try { Files.deleteIfExists(child); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
