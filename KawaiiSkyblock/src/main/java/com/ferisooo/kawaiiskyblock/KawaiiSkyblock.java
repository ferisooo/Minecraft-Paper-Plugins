package com.ferisooo.kawaiiskyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * KawaiiSkyblock — a skyblock core with full island management.
 *
 * <p>Each player gets their own island world, created by COPYING a saved
 * template world folder ({@code SKYBLOCK ISLAND ADVANCED}) placed in the server
 * root. {@code /island create} clones the template into {@code kawaii_isle_<id>}
 * and warps you there; {@code /island delete confirm} unloads and wipes it.
 *
 * <p>On top of that core it adds: a granular trust/role system, anti-grief
 * flags, a PvP toggle, an upgradable cobblestone generator, island visiting
 * with a visit policy, warps (incl. warp signs), and island-level leaderboards.
 * The data model lives in {@link IslandManager}; gameplay enforcement lives in
 * {@link IslandListeners}.
 */
public final class KawaiiSkyblock extends JavaPlugin implements Listener {

    private String templateWorld;
    private File dataFile;
    private IslandManager islands;

    // ---- animated GUI ----
    private static final int GUI_SIZE = 27;
    // Sub-GUIs with many entries (Flags) use 6 rows so every entry fits in the
    // interior without overlapping the shimmer border.
    private static final int BIG_SIZE = 54;
    // Back-button slots. They must be INTERIOR (never on the shimmer border) for
    // the given inventory size. For a 27-slot menu the only interior row is
    // 10..16, so we use slot 16 (rightmost interior cell). For a 54-slot menu the
    // interior spans rows 1..4, so slot 40 (centre of the bottom interior row)
    // is safely off the border.
    private static final int SLOT_BACK_SMALL = 16;
    private static final int SLOT_BACK_BIG = 40;
    // Main menu is 5 rows so the feature buttons (slots 19-24) live in a middle
    // row, not the bottom edge — otherwise the border shimmer repaints over them.
    private static final int MAIN_SIZE = 45;
    private static final Material[] SHIMMER = {
            Material.PINK_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE,
    };
    private int guiFrame = 0;

    // Round-robin cursor for recomputeAllLevels: at most one island is scanned per
    // timer tick to avoid a single multi-second main-thread stall.
    private int recomputeCursor = 0;

    // Last computed value breakdown per island owner, so the Value GUI can serve a
    // cached result on click instead of running a fresh full block scan inline.
    private final Map<UUID, ValueBreakdown> valueBreakdownCache = new HashMap<>();

    // Main menu slots — laid out symmetrically in the 45-grid interior (rows 1-3:
    // 10-16, 19-25, 28-34). Row 1: five primary actions centred (11-15). Row 2:
    // five feature buttons centred (20-24). Row 3: four feature buttons in two
    // symmetric pairs around the centre (29,30,32,33).
    private static final int SLOT_CREATE = 11, SLOT_HOME = 12, SLOT_SPAWN = 13, SLOT_INFO = 14, SLOT_DELETE = 15;
    private static final int SLOT_FLAGS = 20, SLOT_TRUST = 21, SLOT_WARPS = 22, SLOT_GENERATOR = 23,
            SLOT_VISIT = 24;
    private static final int SLOT_PERMS = 29, SLOT_TOP = 30, SLOT_VALUE = 32, SLOT_KICK = 33;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        dataFile = new File(getDataFolder(), "islands.yml");
        islands = new IslandManager(this, dataFile);
        islands.load();
        Bukkit.getGlobalRegionScheduler().execute(this, this::loadKnownIslandWorlds);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new IslandListeners(this, islands), this);
        // Folia-safe: a global-region driver bumps the shared frame counter, then
        // hops each player's GUI repaint onto THAT player's entity scheduler (the
        // open inventory must only be touched on the player's region thread).
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> animateMenus(), 4L, 4L);
        // Debounced islands.yml flush: write dirty data every 30s (off-thread inside)
        // instead of a synchronous full-file write on every single mutation.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> islands.flush(), 600L, 600L);
        // Periodic island-level recompute (throttled inside).
        long period = Math.max(1L, getConfig().getLong("leaderboard.recompute-minutes", 5L)) * 60L * 20L;
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> recomputeAllLevels(), period, period);
        // Inactive-island auto-purge (opt-in: only when purge.inactive-days > 0).
        if (getConfig().getInt("purge.inactive-days", 0) > 0) {
            long checkTicks = Math.max(1L, getConfig().getLong("purge.check-hours", 6L)) * 60L * 60L * 20L;
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> purgeInactiveIslands(), checkTicks, checkTicks);
        }
        getLogger().info("(✧) KawaiiSkyblock ready ~ /island to begin! 🏝");
    }

    @Override
    public void onDisable() {
        // Folia-safe shutdown: cancel our region/async tasks (the legacy global
        // scheduler doesn't exist on Folia).
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        // Always flush dirty data synchronously on shutdown so nothing is lost.
        if (islands != null) islands.saveNow();
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        templateWorld = c.getString("template-world", "SKYBLOCK ISLAND ADVANCED");
    }

    IslandManager islands() { return islands; }

    /** Loads island worlds listed in islands.yml that aren't loaded yet. */
    private void loadKnownIslandWorlds() {
        for (Map.Entry<String, UUID> e : islands.worldIndex().entrySet()) {
            String folder = e.getKey();
            if (Bukkit.getWorld(folder) != null) continue;
            File f = new File(Bukkit.getWorldContainer(), folder);
            if (!f.isDirectory() || !new File(f, "level.dat").exists()) continue;
            try {
                new WorldCreator(folder).createWorld();
            } catch (Throwable t) {
                getLogger().warning("(✧) couldn't load island world '" + folder + "': " + t.getMessage());
            }
        }
    }

    /** The main/lobby world we send people back to (the server's primary world). */
    private World mainWorld() {
        return Bukkit.getWorlds().get(0);
    }

    // ----------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiiskyblock.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            readConfig();
            sender.sendMessage("§d(✧) KawaiiSkyblock reloaded ✨");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("§c(✧) players only~"); return true; }
        if (!p.hasPermission("kawaiiskyblock.use")) { p.sendMessage("§c(✧) no permission~"); return true; }

        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu":
                openMenu(p);
                return true;
            case "home": case "go": case "tp":
                goHome(p);
                return true;
            case "create":
                createIsland(p);
                return true;
            case "spawn":
                p.teleportAsync(mainWorld().getSpawnLocation());
                p.sendMessage("§d(✧) → spawn ✨");
                return true;
            case "delete": case "reset":
                if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) deleteIsland(p);
                else p.sendMessage("§c(✧) this DELETES your island world! confirm with §f/island delete confirm");
                return true;
            case "trust":
                cmdTrust(p, args);
                return true;
            case "untrust":
                cmdUntrust(p, args);
                return true;
            case "flags":
                openFlagsMenu(p);
                return true;
            case "pvp":
                cmdTogglePvp(p);
                return true;
            case "generator": case "gen":
                if (args.length >= 2 && args[1].equalsIgnoreCase("upgrade")) cmdGeneratorUpgrade(p);
                else openGeneratorMenu(p);
                return true;
            case "invite":
                cmdInvite(p, args);
                return true;
            case "accept":
                cmdAccept(p);
                return true;
            case "kick": case "eject":
                cmdKick(p, args);
                return true;
            case "perms": case "permissions":
                openPermRolePicker(p);
                return true;
            case "promote":
                cmdPromote(p, args);
                return true;
            case "demote":
                cmdDemote(p, args);
                return true;
            case "visit":
                cmdVisit(p, args);
                return true;
            case "setvisit":
                cmdSetVisit(p, args);
                return true;
            case "setwarp":
                cmdSetWarp(p, args);
                return true;
            case "delwarp":
                cmdDelWarp(p, args);
                return true;
            case "warps":
                openWarpsMenu(p);
                return true;
            case "warp":
                cmdWarp(p, args);
                return true;
            case "top":
                cmdTop(p);
                return true;
            case "level":
                cmdLevel(p);
                return true;
            case "value": case "worth":
                openValueMenu(p);
                return true;
            case "members":
                openTrustMenu(p);
                return true;
            default:
                help(p);
                return true;
        }
    }

    /** Subcommands whose second argument is a player name. */
    private static final List<String> PLAYER_ARG_SUBS = Arrays.asList(
            "invite", "trust", "untrust", "visit", "kick", "eject", "promote", "demote", "transfer");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        // /island (is/sb) <sub> <player> — suggest online player names at arg 2 (args[1])
        // for the member-management subcommands.
        if (args.length == 2 && PLAYER_ARG_SUBS.contains(args[0].toLowerCase(Locale.ROOT))) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            }
            return out;
        }
        return Collections.emptyList();
    }

    private void help(Player p) {
        p.sendMessage("§d(✧) §lKawaiiSkyblock");
        p.sendMessage("  §f/is §7menu §7• §f/is create §7• §f/is home §7• §f/is spawn §7• §f/is delete confirm");
        p.sendMessage("  §f/is trust <player> <role> §7• §f/is untrust <player> §7• §f/is members");
        p.sendMessage("  §f/is flags §7• §f/is perms §7• §f/is pvp §7• §f/is generator [upgrade]");
        p.sendMessage("  §f/is invite <player> §7• §f/is accept §7• §f/is kick/eject/promote/demote <player>");
        p.sendMessage("  §f/is visit <player> §7• §f/is setvisit <public|invite|trust>");
        p.sendMessage("  §f/is setwarp/delwarp/warp <name> §7• §f/is warps");
        p.sendMessage("  §f/is top §7• §f/is level §7• §f/is value");
    }

    // ----------------------------------------------------------------- islands

    private void goHome(Player p) {
        if (!islands.hasIsland(p.getUniqueId())) {
            p.sendMessage("§d(✧) you don't have an island yet~ use §f/island create");
            return;
        }
        World w = islandWorld(p.getUniqueId());
        if (w == null) {
            p.sendMessage("§c(✧) your island world isn't loaded — ask an admin to check the console~");
            return;
        }
        p.teleportAsync(w.getSpawnLocation());
        p.sendMessage("§d(✧) → your island 🏝");
    }

    private void createIsland(Player p) {
        if (islands.hasIsland(p.getUniqueId())) {
            p.sendMessage("§d(✧) you already have an island~ use §f/island home§d, or §f/island delete confirm§d first.");
            return;
        }

        File container = Bukkit.getWorldContainer();
        File template = new File(container, templateWorld);
        if (!template.isDirectory() || !new File(template, "level.dat").exists()) {
            p.sendMessage("§c(✧) the island template is missing!");
            p.sendMessage("§c(✧) admin: place the §f'" + templateWorld + "'§c folder in the server root (next to §fworld/§c).");
            return;
        }

        String folderName = "kawaii_isle_" + shortId(p.getUniqueId());
        File dest = new File(container, folderName);
        if (dest.exists()) {
            p.sendMessage("§c(✧) an island folder '" + folderName + "' already exists. /island delete confirm first~");
            return;
        }

        p.sendMessage("§d(✧) ✨ carving out your island... one moment~");
        final Path src = template.toPath();
        final Path dst = dest.toPath();
        Bukkit.getAsyncScheduler().runNow(this, asyncTask -> {
            try {
                copyRecursively(src, dst);
                Files.deleteIfExists(dst.resolve("uid.dat"));
                Files.deleteIfExists(dst.resolve("session.lock"));
            } catch (Throwable t) {
                p.getScheduler().run(this, st ->
                        p.sendMessage("§c(✧) failed to copy the island template: " + t.getMessage()), null);
                try { deleteRecursively(dst); } catch (Throwable ignored) {}
                return;
            }
            // World creation is global state; do it on the global region thread,
            // then hop the player-facing work onto the player's entity scheduler.
            Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                World w;
                try {
                    w = new WorldCreator(folderName).createWorld();
                } catch (Throwable t) {
                    p.sendMessage("§c(✧) failed to load your island world: " + t.getMessage());
                    try { deleteRecursively(dst); } catch (Throwable ignored) {}
                    return;
                }
                if (w == null) {
                    p.sendMessage("§c(✧) your island world failed to load — check console~");
                    try { deleteRecursively(dst); } catch (Throwable ignored) {}
                    return;
                }
                islands.createIsland(p.getUniqueId(), folderName);
                p.teleportAsync(w.getSpawnLocation());
                p.sendMessage("§d(✧) ✨ your island awaits! welcome home~ 🏝");
            });
        });
    }

    private void deleteIsland(Player p) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§d(✧) you don't have an island yet~"); return; }
        String folderName = islands.worldFolder(p.getUniqueId());
        if (folderName == null) { p.sendMessage("§d(✧) you don't have an island yet~"); return; }

        // If we're standing on it but it isn't currently loaded as a world ref, hop out.
        if (Bukkit.getWorld(folderName) == null && p.getWorld().getName().equals(folderName)) {
            p.teleportAsync(mainWorld().getSpawnLocation());
        }

        PurgeResult r = purgeIsland(p.getUniqueId());
        switch (r) {
            case UNLOAD_FAILED ->
                    p.sendMessage("§c(✧) couldn't unload your island world to delete it — try again in a moment~");
            case PARTIAL_DELETE ->
                    p.sendMessage("§e(✧) island removed, but the folder couldn't be fully deleted. "
                            + "It can be removed manually if it lingers.");
            case OK, NO_ISLAND ->
                    p.sendMessage("§d(✧) 🗑 your island has been deleted. /island create for a fresh start~");
        }
    }

    /** Outcome of {@link #purgeIsland(UUID)}. */
    private enum PurgeResult { OK, NO_ISLAND, UNLOAD_FAILED, PARTIAL_DELETE }

    /**
     * Deletes an island: teleports anyone out to the main spawn, unloads the
     * world (without saving), removes the islands.yml mapping, and deletes the
     * folder recursively. Shared by {@code /island delete confirm} and the
     * periodic inactive-island purge. Does no messaging; callers report.
     */
    private PurgeResult purgeIsland(UUID owner) {
        String folderName = islands.worldFolder(owner);
        if (folderName == null) return PurgeResult.NO_ISLAND;

        World w = Bukkit.getWorld(folderName);
        if (w != null) {
            World fallback = mainWorld();
            for (Player pl : new ArrayList<>(w.getPlayers())) {
                pl.teleportAsync(fallback.getSpawnLocation());
            }
            if (!Bukkit.unloadWorld(w, false)) {
                return PurgeResult.UNLOAD_FAILED;
            }
        }

        File folder = new File(Bukkit.getWorldContainer(), folderName);
        islands.deleteIsland(owner);
        if (folder.exists()) {
            try {
                deleteRecursively(folder.toPath());
            } catch (Throwable t) {
                return PurgeResult.PARTIAL_DELETE;
            }
        }
        return PurgeResult.OK;
    }

    // ------------------------------------------------- island world lifecycle

    /**
     * When the last player leaves an island world, unload it from memory a few
     * ticks later (the folder stays on disk). Re-checks emptiness right before
     * unloading and never touches the main world.
     */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent e) {
        scheduleUnloadIfEmpty(e.getFrom());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        // Persist last-seen for purge bookkeeping (only owners with islands).
        islands.touchLastSeen(p.getUniqueId());
        scheduleUnloadIfEmpty(p.getWorld());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        islands.touchLastSeen(e.getPlayer().getUniqueId());
    }

    /** Schedules an idle-island unload if {@code world} is an empty island world. */
    private void scheduleUnloadIfEmpty(World world) {
        if (world == null) return;
        if (world.equals(mainWorld())) return;
        final String name = world.getName();
        if (!islands.isIslandWorld(name)) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            World w = Bukkit.getWorld(name);
            if (w == null || w.equals(mainWorld())) return;
            if (!w.getPlayers().isEmpty()) return; // someone came back
            Bukkit.unloadWorld(w, true);
        }, 100L); // ~5s
    }

    // ----------------------------------------------------------------- purge

    /**
     * Periodic sweep that deletes islands whose OFFLINE owner hasn't been seen
     * for {@code purge.inactive-days}. Disabled when inactive-days &lt;= 0.
     */
    private void purgeInactiveIslands() {
        int days = getConfig().getInt("purge.inactive-days", 0);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        for (UUID owner : islands.allOwners()) {
            try {
                if (Bukkit.getPlayer(owner) != null) continue; // never purge an online owner
                if (islands.lastSeen(owner) >= cutoff) continue;
                String folder = islands.worldFolder(owner);
                PurgeResult r = purgeIsland(owner);
                if (r == PurgeResult.OK || r == PurgeResult.PARTIAL_DELETE) {
                    getLogger().info("(✧) purged inactive island '" + folder + "' (owner "
                            + owner + ", inactive > " + days + "d)"
                            + (r == PurgeResult.PARTIAL_DELETE ? " — folder partially removed" : ""));
                }
            } catch (Throwable t) {
                getLogger().warning("(✧) failed to purge island for owner " + owner + ": " + t.getMessage());
            }
        }
    }

    // ----------------------------------------------------------------- trust

    private void cmdTrust(Player p, String[] args) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        if (args.length < 3) { p.sendMessage("§c(✧) usage: §f/is trust <player> <leader|coop|member|visitor>"); return; }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) { p.sendMessage("§c(✧) never seen a player named §f" + args[1]); return; }
        if (target.getUniqueId().equals(owner)) { p.sendMessage("§c(✧) you're the leader already~"); return; }
        IslandManager.Role role;
        try { role = IslandManager.Role.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { p.sendMessage("§c(✧) role must be leader/coop/member/visitor"); return; }
        if (role == IslandManager.Role.LEADER) { p.sendMessage("§c(✧) there can only be one leader~"); return; }

        // Only LEADER may demote/change a COOP.
        IslandManager.Role current = islands.roleOf(owner, target.getUniqueId());
        if (current == IslandManager.Role.COOP && !p.getUniqueId().equals(owner)) {
            p.sendMessage("§c(✧) only the leader can change a co-op's role~");
            return;
        }
        islands.setRole(owner, target.getUniqueId(), role);
        p.sendMessage("§d(✧) §f" + name(target) + "§d is now §f" + role.name().toLowerCase(Locale.ROOT) + "§d ✿");
        chime(p);
    }

    private void cmdUntrust(Player p, String[] args) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is untrust <player>"); return; }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) { p.sendMessage("§c(✧) never seen a player named §f" + args[1]); return; }
        if (target.getUniqueId().equals(owner)) { p.sendMessage("§c(✧) you can't untrust yourself~"); return; }
        IslandManager.Role current = islands.roleOf(owner, target.getUniqueId());
        if (current == IslandManager.Role.COOP && !p.getUniqueId().equals(owner)) {
            p.sendMessage("§c(✧) only the leader can remove a co-op~");
            return;
        }
        islands.setRole(owner, target.getUniqueId(), IslandManager.Role.VISITOR);
        p.sendMessage("§d(✧) removed §f" + name(target) + "§d from the island~");
    }

    // ----------------------------------------------------------------- flags / pvp

    private void cmdTogglePvp(Player p) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        boolean v = islands.toggleFlag(owner, "pvp");
        p.sendMessage("§d(✧) PvP is now " + (v ? "§aON" : "§cOFF") + "§d on your island~");
        chime(p);
    }

    // ----------------------------------------------------------------- generator

    private void cmdGeneratorUpgrade(Player p) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        tryUpgradeGenerator(p, owner);
    }

    /**
     * Attempts to upgrade {@code owner}'s generator, charging the configured
     * item cost from {@code p}'s inventory. Returns true on success. Messages the
     * player on max-level or insufficient funds; does not check MANAGE perms
     * (callers do that).
     */
    private boolean tryUpgradeGenerator(Player p, UUID owner) {
        int max = getConfig().getInt("generator.max-level", 5);
        int cur = islands.generatorLevel(owner);
        if (cur >= max) {
            p.sendMessage("§d(✧) your generator is already at max level (§f" + max + "§d)~");
            return false;
        }
        Material costItem = upgradeCostItem();
        int cost = upgradeCost(cur);
        int have = countItem(p, costItem);
        if (have < cost) {
            p.sendMessage("§c(✧) you need §f" + cost + " " + costItem.name()
                    + "§c to upgrade — you have §f" + have + "§c~");
            return false;
        }
        p.getInventory().removeItem(new ItemStack(costItem, cost));
        islands.setGeneratorLevel(owner, cur + 1);
        p.sendMessage("§d(✧) ⛏ generator upgraded to level §f" + (cur + 1)
                + "§d! (paid §f" + cost + " " + costItem.name() + "§d) better ores await~");
        chime(p);
        return true;
    }

    /** Total count of a material in the player's inventory. */
    private static int countItem(Player p, Material m) {
        int total = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == m) total += it.getAmount();
        }
        return total;
    }

    // ----------------------------------------------------------------- co-op

    private void cmdInvite(Player p, String[] args) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§c(✧) you don't have an island~"); return; }
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is invite <player>"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { p.sendMessage("§c(✧) §f" + args[1] + "§c isn't online~"); return; }
        if (target.equals(p)) { p.sendMessage("§c(✧) you can't invite yourself~"); return; }
        islands.invite(p.getUniqueId(), target.getUniqueId());
        p.sendMessage("§d(✧) invited §f" + target.getName() + "§d to your island (they have 2 min to §f/is accept§d)~");
        target.sendMessage("§d(✧) §f" + p.getName() + "§d invited you to their island! type §f/is accept§d to join as co-op~");
    }

    private void cmdAccept(Player p) {
        UUID owner = islands.activeInvite(p.getUniqueId());
        if (owner == null) { p.sendMessage("§c(✧) you have no pending island invite~"); return; }
        islands.setRole(owner, p.getUniqueId(), IslandManager.Role.COOP);
        islands.clearInvite(p.getUniqueId());
        p.sendMessage("§d(✧) ✨ you joined the island as co-op! use §f/is visit " + ownerName(owner));
        Player leader = Bukkit.getPlayer(owner);
        if (leader != null) leader.sendMessage("§d(✧) §f" + p.getName() + "§d accepted your invite~ 🏝");
    }

    private void cmdKick(Player p, String[] args) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is kick <player>"); return; }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) { p.sendMessage("§c(✧) never seen a player named §f" + args[1]); return; }
        if (target.getUniqueId().equals(owner)) { p.sendMessage("§c(✧) you can't kick the island owner~"); return; }
        if (target.getUniqueId().equals(p.getUniqueId())) { p.sendMessage("§c(✧) you can't kick yourself~"); return; }
        kickFromIsland(p, owner, target);
    }

    /**
     * Kick / eject logic shared by {@code /is kick}, {@code /is eject} and the
     * Members GUI shift-click. If the target is a trusted MEMBER/COOP, their trust
     * is removed (set to VISITOR). If they are standing in the island world they
     * are teleported back to the main world spawn (an "eject"). Owners can't be
     * kicked; only the LEADER may kick a COOP. The kicked player is notified.
     */
    private void kickFromIsland(Player actor, UUID owner, OfflinePlayer target) {
        IslandManager.Role current = islands.roleOf(owner, target.getUniqueId());
        boolean isLeader = actor.getUniqueId().equals(owner) || actor.hasPermission("kawaiiskyblock.admin");
        if (current == IslandManager.Role.COOP && !isLeader) {
            actor.sendMessage("§c(✧) only the leader can kick a co-op~");
            return;
        }

        boolean wasTrusted = current.ordinal() >= IslandManager.Role.MEMBER.ordinal();
        if (wasTrusted) {
            islands.setRole(owner, target.getUniqueId(), IslandManager.Role.VISITOR);
        }

        // Eject: if the target is online and standing on this owner's island world,
        // send them back to the main world spawn.
        String folder = islands.worldFolder(owner);
        Player onlineTarget = target.getPlayer();
        boolean ejected = false;
        if (onlineTarget != null && folder != null
                && onlineTarget.getWorld().getName().equals(folder)) {
            onlineTarget.teleportAsync(mainWorld().getSpawnLocation());
            ejected = true;
        }

        if (!wasTrusted && !ejected) {
            actor.sendMessage("§c(✧) §f" + name(target)
                    + "§c isn't trusted and isn't on your island to eject~");
            return;
        }

        if (wasTrusted) {
            actor.sendMessage("§d(✧) kicked §f" + name(target) + "§d from your island~");
        } else {
            actor.sendMessage("§d(✧) ejected §f" + name(target) + "§d from your island~");
        }
        if (onlineTarget != null) {
            onlineTarget.sendMessage("§d(✧) you were "
                    + (wasTrusted ? "removed from" : "ejected from")
                    + " §f" + ownerName(owner) + "§d's island~");
        }
        chime(actor);
    }

    private void cmdPromote(Player p, String[] args) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§c(✧) you don't have an island~"); return; }
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is promote <player>"); return; }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) { p.sendMessage("§c(✧) never seen a player named §f" + args[1]); return; }
        IslandManager.Role r = islands.roleOf(p.getUniqueId(), target.getUniqueId());
        if (r == IslandManager.Role.MEMBER) {
            islands.setRole(p.getUniqueId(), target.getUniqueId(), IslandManager.Role.COOP);
            p.sendMessage("§d(✧) §f" + name(target) + "§d promoted to co-op~");
        } else {
            p.sendMessage("§c(✧) can only promote a member → co-op~");
        }
    }

    private void cmdDemote(Player p, String[] args) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§c(✧) you don't have an island~"); return; }
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is demote <player>"); return; }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) { p.sendMessage("§c(✧) never seen a player named §f" + args[1]); return; }
        IslandManager.Role r = islands.roleOf(p.getUniqueId(), target.getUniqueId());
        if (r == IslandManager.Role.COOP) {
            islands.setRole(p.getUniqueId(), target.getUniqueId(), IslandManager.Role.MEMBER);
            p.sendMessage("§d(✧) §f" + name(target) + "§d demoted to member~");
        } else {
            p.sendMessage("§c(✧) can only demote a co-op → member~");
        }
    }

    // ----------------------------------------------------------------- visiting

    private void cmdVisit(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is visit <player>"); return; }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null || !islands.hasIsland(target.getUniqueId())) {
            p.sendMessage("§c(✧) §f" + args[1] + "§c has no island~");
            return;
        }
        UUID owner = target.getUniqueId();
        if (!islands.canVisit(owner, p.getUniqueId()) && !p.hasPermission("kawaiiskyblock.admin")) {
            p.sendMessage("§c(✧) you're not allowed to visit that island~");
            return;
        }
        World w = islandWorld(owner);
        if (w == null) { p.sendMessage("§c(✧) that island world isn't loaded~"); return; }
        p.teleportAsync(w.getSpawnLocation());
        p.sendMessage("§d(✧) → visiting §f" + name(target) + "§d's island 🏝");
    }

    private void cmdSetVisit(Player p, String[] args) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§c(✧) you don't have an island~"); return; }
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is setvisit <public|invite|trust>"); return; }
        IslandManager.VisitPolicy policy;
        try { policy = IslandManager.VisitPolicy.valueOf(args[1].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { p.sendMessage("§c(✧) policy must be public/invite/trust"); return; }
        islands.setVisitPolicy(p.getUniqueId(), policy);
        p.sendMessage("§d(✧) visit policy set to §f" + policy.name().toLowerCase(Locale.ROOT) + "§d~");
    }

    // ----------------------------------------------------------------- warps

    private void cmdSetWarp(Player p, String[] args) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is setwarp <name>"); return; }
        if (!islands.isIslandWorld(p.getWorld().getName())
                || !p.getWorld().getName().equals(islands.worldFolder(owner))) {
            p.sendMessage("§c(✧) stand on your own island to set a warp~");
            return;
        }
        islands.setWarp(owner, args[1], p.getLocation());
        p.sendMessage("§d(✧) ✨ warp §f" + args[1].toLowerCase(Locale.ROOT) + "§d set here~");
    }

    private void cmdDelWarp(Player p, String[] args) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is delwarp <name>"); return; }
        islands.delWarp(owner, args[1]);
        p.sendMessage("§d(✧) removed warp §f" + args[1].toLowerCase(Locale.ROOT) + "§d~");
    }

    private void cmdWarp(Player p, String[] args) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§c(✧) you don't have an island~"); return; }
        if (args.length < 2) { p.sendMessage("§c(✧) usage: §f/is warp <name>"); return; }
        Location loc = islands.warpLocation(p.getUniqueId(), args[1]);
        if (loc == null) { p.sendMessage("§c(✧) no warp named §f" + args[1]); return; }
        p.teleportAsync(loc);
        p.sendMessage("§d(✧) → warp §f" + args[1].toLowerCase(Locale.ROOT) + "§d ✨");
    }

    /** Used by warp signs: teleports {@code p} to a warp on the island that owns {@code worldName}. */
    public void useWarpSign(Player p, String worldName, String warpName) {
        UUID owner = islands.ownerOfWorld(worldName);
        if (owner == null) { p.sendMessage("§c(✧) this isn't a valid island~"); return; }
        if (!islands.canVisit(owner, p.getUniqueId()) && !p.hasPermission("kawaiiskyblock.admin")) {
            p.sendMessage("§c(✧) you're not allowed to use this warp~");
            return;
        }
        Location loc = islands.warpLocation(owner, warpName);
        if (loc == null) { p.sendMessage("§c(✧) no warp named §f" + warpName); return; }
        p.teleportAsync(loc);
        p.sendMessage("§d(✧) → warp §f" + warpName.toLowerCase(Locale.ROOT) + "§d ✨");
    }

    // ----------------------------------------------------------------- leaderboard

    private void cmdLevel(Player p) {
        if (!islands.hasIsland(p.getUniqueId())) { p.sendMessage("§c(✧) you don't have an island~"); return; }
        p.sendMessage("§d(✧) computing island level... ✨");
        int lvl = computeLevel(p.getUniqueId());
        p.sendMessage("§d(✧) your island level is §f" + lvl + "§d!");
    }

    private void cmdTop(Player p) {
        List<UUID> owners = islands.allOwners();
        owners.sort(Comparator
                .comparingInt((UUID o) -> islands.level(o)).reversed()
                .thenComparing(Comparator.comparingInt((UUID o) -> islands.memberCount(o)).reversed()));
        p.sendMessage("§d(✧) §l✿ Top Islands ✿");
        int shown = Math.min(10, owners.size());
        if (shown == 0) { p.sendMessage("§7  (no islands yet~)"); return; }
        for (int i = 0; i < shown; i++) {
            UUID o = owners.get(i);
            p.sendMessage("  §f#" + (i + 1) + " §d" + ownerName(o)
                    + " §7— level §f" + islands.level(o) + " §7• members §f" + islands.memberCount(o));
        }
    }

    /**
     * Recompute the island level by summing a config block-value map over a
     * capped bounding box around the island spawn. Throttled by caller.
     */
    private int computeLevel(UUID owner) {
        World w = islandWorld(owner);
        if (w == null) return islands.level(owner);
        Map<Material, Integer> values = blockValues();
        int radius = Math.max(8, Math.min(96, getConfig().getInt("leaderboard.scan-radius", 48)));
        int minY = Math.max(w.getMinHeight(), getConfig().getInt("leaderboard.scan-min-y", 60));
        int maxY = Math.min(w.getMaxHeight() - 1, getConfig().getInt("leaderboard.scan-max-y", 140));
        Location center = w.getSpawnLocation();
        int cx = center.getBlockX(), cz = center.getBlockZ();
        long total = 0L;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                // Never force-load chunks: skip columns whose chunk isn't loaded.
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.AIR) continue;
                    Integer v = values.get(m);
                    if (v != null) total += v;
                }
            }
        }
        int level = (int) Math.min(Integer.MAX_VALUE, total / Math.max(1, getConfig().getInt("leaderboard.points-per-level", 100)));
        islands.setLevel(owner, level);
        return level;
    }

    /** A scanned island-value breakdown: per-material subtotal + grand total. */
    private static final class ValueBreakdown {
        final Map<Material, Long> subtotals = new HashMap<>(); // material → count*value
        final Map<Material, Integer> counts = new HashMap<>(); // material → block count
        long total = 0L;
    }

    /**
     * Scans the island's blocks (same capped bounding box as {@link #computeLevel})
     * and tallies, per block type, how much value it contributes. Also refreshes
     * the cached island level. Throttled by {@link #valueScanThrottled} so callers
     * never lag the server with back-to-back scans.
     */
    private ValueBreakdown computeValueBreakdown(UUID owner) {
        ValueBreakdown bd = new ValueBreakdown();
        World w = islandWorld(owner);
        if (w == null) return bd;
        Map<Material, Integer> values = blockValues();
        int radius = Math.max(8, Math.min(96, getConfig().getInt("leaderboard.scan-radius", 48)));
        int minY = Math.max(w.getMinHeight(), getConfig().getInt("leaderboard.scan-min-y", 60));
        int maxY = Math.min(w.getMaxHeight() - 1, getConfig().getInt("leaderboard.scan-max-y", 140));
        Location center = w.getSpawnLocation();
        int cx = center.getBlockX(), cz = center.getBlockZ();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                // Never force-load chunks: skip columns whose chunk isn't loaded.
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Material m = w.getBlockAt(x, y, z).getType();
                    if (m == Material.AIR) continue;
                    Integer v = values.get(m);
                    if (v == null || v <= 0) continue;
                    bd.counts.merge(m, 1, Integer::sum);
                    bd.subtotals.merge(m, (long) v, Long::sum);
                    bd.total += v;
                }
            }
        }
        int level = (int) Math.min(Integer.MAX_VALUE,
                bd.total / Math.max(1, getConfig().getInt("leaderboard.points-per-level", 100)));
        islands.setLevel(owner, level);
        valueBreakdownCache.put(owner, bd);
        return bd;
    }

    /** True if {@code owner}'s level was recomputed within the throttle window. */
    private boolean valueScanThrottled(UUID owner) {
        long throttle = Math.max(1L, getConfig().getLong("leaderboard.recompute-minutes", 5L)) * 60_000L;
        return System.currentTimeMillis() - islands.levelComputedAt(owner) < throttle;
    }

    private Map<Material, Integer> blockValues() {
        Map<Material, Integer> out = new HashMap<>();
        var sec = getConfig().getConfigurationSection("leaderboard.block-values");
        if (sec == null) return out;
        for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(k.toUpperCase(Locale.ROOT));
            if (m != null) out.put(m, sec.getInt(k));
        }
        return out;
    }

    /**
     * Periodic, throttled recompute. Processes AT MOST ONE eligible island per
     * invocation (round-robin via {@link #recomputeCursor}) so a server with many
     * islands spreads the cost across successive timer ticks instead of stalling
     * the main thread scanning every island back-to-back in a single tick. All
     * islands still get recomputed over successive runs.
     */
    private void recomputeAllLevels() {
        long throttle = Math.max(1L, getConfig().getLong("leaderboard.recompute-minutes", 5L)) * 60_000L;
        long now = System.currentTimeMillis();
        List<UUID> owners = islands.allOwners();
        if (owners.isEmpty()) { recomputeCursor = 0; return; }
        // Walk the owner list starting at the cursor, scanning the first eligible
        // island (loaded world + throttle window elapsed), then stop for this tick.
        int n = owners.size();
        for (int i = 0; i < n; i++) {
            int idx = (recomputeCursor + i) % n;
            UUID owner = owners.get(idx);
            String folder = islands.worldFolder(owner);
            if (folder == null || Bukkit.getWorld(folder) == null) continue; // only loaded worlds
            if (now - islands.levelComputedAt(owner) < throttle) continue;    // still fresh
            computeLevel(owner);
            recomputeCursor = (idx + 1) % n; // resume after this island next tick
            return;
        }
        // Nothing eligible this pass; nudge the cursor so it keeps rotating.
        recomputeCursor = (recomputeCursor + 1) % n;
    }

    // ----------------------------------------------------------------- helpers

    /** Resolves the island a player can MANAGE (their own, or one they co-op manage they're standing on). */
    private UUID requireManageOwner(Player p) {
        if (islands.hasIsland(p.getUniqueId())) return p.getUniqueId();
        // Allow COOP managers acting on the island they stand on.
        String world = p.getWorld().getName();
        UUID owner = islands.ownerOfWorld(world);
        if (owner != null && islands.canIn(world, p.getUniqueId(), IslandManager.Perm.MANAGE)) return owner;
        p.sendMessage("§c(✧) you don't have an island, or no manage access here~");
        return null;
    }

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        // Avoid iterating every offline player ever seen; use Paper's cached lookup.
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private static String name(OfflinePlayer op) {
        return op.getName() == null ? op.getUniqueId().toString().substring(0, 8) : op.getName();
    }

    private String ownerName(UUID owner) {
        return name(Bukkit.getOfflinePlayer(owner));
    }

    private void chime(Player p) {
        p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1.4f);
    }

    private static String shortId(UUID id) {
        // The full UUID with dashes removed (32 chars). Existing islands keep
        // their stored folder name in islands.yml, so they remain unaffected.
        return id.toString().replace("-", "");
    }

    private static void copyRecursively(Path src, Path dst) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(srcPath -> {
                try {
                    String fileName = srcPath.getFileName() == null ? "" : srcPath.getFileName().toString();
                    // Skip server-held lock/identity files: session.lock stays locked
                    // by the OS while the template world is loaded, and uid.dat must
                    // not be copied (each clone needs a fresh world UID anyway).
                    if (fileName.equals("session.lock") || fileName.equals("uid.dat")
                            || fileName.endsWith(".lock")) {
                        return;
                    }
                    Path rel = src.relativize(srcPath);
                    Path target = dst.resolve(rel.toString());
                    if (Files.isDirectory(srcPath)) {
                        Files.createDirectories(target);
                    } else {
                        Path parent = target.getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.copy(srcPath, target);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException io) throw io;
            throw re;
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> stream = Files.walk(p)) {
            stream.sorted(Comparator.reverseOrder()).forEach(child -> {
                try { Files.deleteIfExists(child); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException io) throw io;
            throw re;
        }
    }

    private World islandWorld(UUID owner) {
        String folder = islands.worldFolder(owner);
        if (folder == null) return null;
        World w = Bukkit.getWorld(folder);
        if (w != null) return w;
        File f = new File(Bukkit.getWorldContainer(), folder);
        if (f.isDirectory() && new File(f, "level.dat").exists()) {
            try { return new WorldCreator(folder).createWorld(); }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    // ----------------------------------------------------------------- GUI

    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.builder().character('§').build();

    /** Which screen a SkyGuiHolder represents. */
    private enum GuiKind { MAIN, FLAGS, TRUST, WARPS, GENERATOR, VISIT, TOP, PERM_PICK, PERMS, VALUE }

    /** Marks our inventory so clicks/animation only touch our menus. */
    private static final class SkyGuiHolder implements InventoryHolder {
        private Inventory inv;
        private final GuiKind kind;
        private UUID context; // island owner this GUI relates to
        private final List<UUID> rowTargets = new ArrayList<>(); // slot→member mapping for trust GUI
        private final List<String> rowNames = new ArrayList<>(); // slot→warp/owner names
        private int page = 0; // current page for paged GUIs (Flags / Perms)
        private IslandManager.Role role; // which role the Perms editor is editing
        SkyGuiHolder(GuiKind kind) { this.kind = kind; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    // Paging button slots in the bottom interior row of a 54-slot menu (clear of
    // the shimmer border and the Back button at slot 40).
    private static final int SLOT_PREV = 38, SLOT_NEXT = 42, SLOT_PAGE_INFO = 39;

    private Inventory newGui(GuiKind kind, int size, String title) {
        SkyGuiHolder holder = new SkyGuiHolder(kind);
        Inventory inv = Bukkit.createInventory(holder, size, gradientTitle(title));
        holder.inv = inv;
        return inv;
    }

    private void openMenu(Player p) {
        Inventory inv = newGui(GuiKind.MAIN, MAIN_SIZE, "✧ Skyblock ✧");
        paintBorder(inv, guiFrame);
        boolean has = islands.hasIsland(p.getUniqueId());

        inv.setItem(SLOT_CREATE, button(Material.OAK_SAPLING, "§a✚ Create island",
                has ? "§7You already have an island~" : "§7Clone the template world and",
                has ? "§8use §fHome§8 to visit it." : "§8warp to your very own island!"));
        inv.setItem(SLOT_HOME, button(Material.GRASS_BLOCK, "§a▶ Home / Go",
                has ? "§7Teleport to your island~" : "§7You don't have an island yet —",
                has ? "§8welcome home!" : "§8click Create first!"));
        inv.setItem(SLOT_SPAWN, button(Material.BEACON, "§b✦ Spawn", "§7Warp to the main world spawn."));
        inv.setItem(SLOT_INFO, button(Material.NETHER_STAR, "§d✿ Your Island ✿",
                has ? "§7world: §f" + islands.worldFolder(p.getUniqueId()) : "§7you don't have one yet —",
                has ? "§8level §f" + islands.level(p.getUniqueId()) + "§8 • members §f" + islands.memberCount(p.getUniqueId()) : "§8click \"Create\" to make one!"));
        inv.setItem(SLOT_DELETE, button(Material.TNT, "§c🗑 Delete island",
                "§7Unloads & permanently deletes", "§7your island world.",
                "§cSHIFT-click to confirm!"));

        inv.setItem(SLOT_FLAGS, button(Material.REPEATER, "§e⚑ Flags", "§7Anti-grief & PvP toggles."));
        inv.setItem(SLOT_TRUST, button(Material.PLAYER_HEAD, "§b✿ Trust / Members", "§7Manage roles & co-op."));
        inv.setItem(SLOT_WARPS, button(Material.ENDER_PEARL, "§a✦ Warps", "§7Your island warps."));
        inv.setItem(SLOT_GENERATOR, button(Material.COBBLESTONE, "§7⛏ Generator",
                "§7Level §f" + islands.generatorLevel(p.getUniqueId()), "§8upgrade for better ores!"));
        inv.setItem(SLOT_VISIT, button(Material.OAK_BOAT, "§d✈ Visit",
                "§7Policy: §f" + islands.visitPolicy(p.getUniqueId()).name().toLowerCase(Locale.ROOT),
                "§8click to cycle visit policy"));
        inv.setItem(SLOT_PERMS, button(Material.WRITABLE_BOOK, "§b✎ Permissions",
                "§7Per-role member permissions.", "§8pick a role, then toggle perms"));
        inv.setItem(SLOT_TOP, button(Material.GOLD_INGOT, "§6✪ Leaderboard", "§7Top islands by level."));
        inv.setItem(SLOT_VALUE, button(Material.DIAMOND, "§b✦ Island Value",
                has ? "§7Level §f" + islands.level(p.getUniqueId()) : "§7No island yet —",
                "§8top value-contributing blocks"));
        inv.setItem(SLOT_KICK, button(Material.IRON_DOOR, "§c⚠ Kick / Eject",
                "§7Remove a member or eject", "§8a visitor (opens Members)"));

        p.openInventory(inv);
    }

    /** Interior content slots of a 54-slot menu (rows 1..3), in reading order. The
     * bottom interior row (37..43) is reserved for navigation (Prev/Page/Back/Next). */
    private static final int[] BIG_INTERIOR = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
    };
    /** Number of toggle entries shown per page in a paged 54-slot GUI. */
    private static final int PAGE_SIZE = BIG_INTERIOR.length; // 21

    private void openFlagsMenu(Player p) {
        openFlagsMenu(p, 0);
    }

    private void openFlagsMenu(Player p, int page) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        int pages = pageCount(IslandManager.FLAG_KEYS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = newGui(GuiKind.FLAGS, BIG_SIZE, "✧ Flags " + (page + 1) + "/" + pages + " ✧");
        SkyGuiHolder holder = (SkyGuiHolder) inv.getHolder();
        holder.context = owner;
        holder.page = page;
        paintBorder(inv, guiFrame);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= IslandManager.FLAG_KEYS.length) break;
            String f = IslandManager.FLAG_KEYS[idx];
            boolean on = islands.flag(owner, f);
            inv.setItem(BIG_INTERIOR[i], button(flagIcon(f), (on ? "§a" : "§c") + flagLabel(f),
                    "§8" + f, on ? "§a● ENABLED" : "§c○ DISABLED", "§8click to toggle"));
        }
        addPaging(inv, page, pages);
        addBackButton(inv, SLOT_BACK_BIG);
        p.openInventory(inv);
    }

    /** Number of pages needed for {@code total} entries at {@link #PAGE_SIZE} per page. */
    private static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Adds Prev / page-info / Next buttons to a paged GUI's bottom interior row. */
    private void addPaging(Inventory inv, int page, int pages) {
        if (page > 0) {
            inv.setItem(SLOT_PREV, button(Material.ARROW, "§e« Prev page", "§8page " + page + "/" + pages));
        }
        if (page < pages - 1) {
            inv.setItem(SLOT_NEXT, button(Material.ARROW, "§eNext page »", "§8page " + (page + 2) + "/" + pages));
        }
        inv.setItem(SLOT_PAGE_INFO, button(Material.BOOK, "§dPage §f" + (page + 1) + "§7/§f" + pages,
                "§8use the arrows to flip"));
    }

    /** Icon for a flag's GUI button (data-driven keyword match, with a fallback). */
    private static Material flagIcon(String f) {
        if (f.contains("pvp")) return Material.DIAMOND_SWORD;
        if (f.contains("explos") || f.contains("tnt")) return Material.TNT;
        if (f.contains("fire") || f.contains("ignite") || f.contains("burn") || f.contains("combust"))
            return Material.FLINT_AND_STEEL;
        if (f.contains("lava")) return Material.LAVA_BUCKET;
        if (f.contains("liquid")) return Material.WATER_BUCKET;
        if (f.contains("lightning") || f.contains("weather")) return Material.LIGHTNING_ROD;
        if (f.contains("spawn")) return Material.ZOMBIE_HEAD;
        if (f.contains("crop") || f.contains("grow")) return Material.WHEAT;
        if (f.contains("leaf") || f.contains("flower") || f.contains("vine") || f.contains("mushroom"))
            return Material.OAK_LEAVES;
        if (f.contains("snow") || f.contains("ice")) return Material.SNOWBALL;
        if (f.contains("sculk")) return matIcon("SCULK", Material.PAPER);
        if (f.contains("redstone") || f.contains("observer")) return Material.REDSTONE;
        if (f.contains("portal")) return Material.OBSIDIAN;
        if (f.contains("vehicle")) return Material.MINECART;
        if (f.contains("damage")) return Material.IRON_SWORD;
        if (f.contains("grief") || f.contains("entity")) return Material.CREEPER_HEAD;
        if (f.contains("sponge")) return Material.SPONGE;
        if (f.contains("drop") || f.contains("exp")) return Material.EXPERIENCE_BOTTLE;
        return Material.PAPER;
    }

    /** Resolve a Material by name with a fallback (for version-unsafe materials). */
    private static Material matIcon(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private void openTrustMenu(Player p) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        Inventory inv = newGui(GuiKind.TRUST, GUI_SIZE, "✧ Members ✧");
        SkyGuiHolder holder = (SkyGuiHolder) inv.getHolder();
        holder.context = owner;
        paintBorder(inv, guiFrame);
        int slot = 10;
        for (UUID m : islands.members(owner)) {
            if (slot > 15) break; // 16 is reserved for the Back button
            IslandManager.Role role = islands.roleOf(owner, m);
            String mn = ownerName(m);
            inv.setItem(slot, button(Material.PLAYER_HEAD, "§f" + mn,
                    "§7role: §f" + role.name().toLowerCase(Locale.ROOT),
                    m.equals(owner) ? "§8(leader — locked)" : "§8left-click: cycle role",
                    m.equals(owner) ? "" : "§8shift-click: kick"));
            holder.rowTargets.add(m);
            slot++;
        }
        addBackButton(inv, SLOT_BACK_SMALL);
        p.openInventory(inv);
    }

    private void openWarpsMenu(Player p) {
        UUID owner = islands.hasIsland(p.getUniqueId()) ? p.getUniqueId()
                : islands.ownerOfWorld(p.getWorld().getName());
        if (owner == null) { p.sendMessage("§c(✧) no island here~"); return; }
        Inventory inv = newGui(GuiKind.WARPS, GUI_SIZE, "✧ Warps ✧");
        SkyGuiHolder holder = (SkyGuiHolder) inv.getHolder();
        holder.context = owner;
        paintBorder(inv, guiFrame);
        int slot = 10;
        List<String> names = islands.warpNames(owner);
        if (names.isEmpty()) {
            inv.setItem(13, button(Material.BARRIER, "§7No warps yet", "§8set one with §f/is setwarp <name>"));
        }
        for (String n : names) {
            if (slot > 15) break; // 16 is reserved for the Back button
            inv.setItem(slot, button(Material.ENDER_PEARL, "§a✦ " + n, "§8click to warp"));
            holder.rowNames.add(n);
            slot++;
        }
        addBackButton(inv, SLOT_BACK_SMALL);
        p.openInventory(inv);
    }

    private void openGeneratorMenu(Player p) {
        UUID owner = islands.hasIsland(p.getUniqueId()) ? p.getUniqueId()
                : islands.ownerOfWorld(p.getWorld().getName());
        if (owner == null) { p.sendMessage("§c(✧) no island here~"); return; }
        Inventory inv = newGui(GuiKind.GENERATOR, GUI_SIZE, "✧ Generator ✧");
        ((SkyGuiHolder) inv.getHolder()).context = owner;
        paintBorder(inv, guiFrame);
        int max = getConfig().getInt("generator.max-level", 5);
        int lvl = islands.generatorLevel(owner);
        Material costItem = upgradeCostItem();
        int cost = upgradeCost(lvl);
        inv.setItem(12, button(Material.COBBLESTONE, "§7⛏ Generator Level §f" + lvl + "§7/§f" + max,
                "§7Higher levels roll rarer ores", "§7when lava meets water."));
        if (lvl >= max) {
            inv.setItem(14, button(Material.BARRIER, "§c✖ Max level reached", "§8can't go higher~"));
        } else {
            inv.setItem(14, button(Material.EMERALD, "§a⬆ Upgrade",
                    "§7Level §f" + lvl + " §7→ §f" + (lvl + 1),
                    "§7Cost: §f" + cost + " " + costItem.name(),
                    "§8click to upgrade"));
        }
        addBackButton(inv, SLOT_BACK_SMALL);
        p.openInventory(inv);
    }

    // ----------------------------------------------------------------- island value

    /**
     * Island value / level breakdown GUI: scans the island (throttled) and lists
     * the top value-contributing block types (icon = the block, lore =
     * count × per-block value = subtotal), plus the total island level/value and
     * the player's leaderboard rank. Read-only + animated.
     */
    private void openValueMenu(Player p) {
        UUID owner = islands.hasIsland(p.getUniqueId()) ? p.getUniqueId()
                : islands.ownerOfWorld(p.getWorld().getName());
        if (owner == null) { p.sendMessage("§c(✧) no island here~"); return; }

        // Never run a fresh full block scan inline on the click — it would block the
        // main thread for potentially seconds. Serve the last cached breakdown if we
        // have one; otherwise schedule a scan for the next tick and show a
        // "calculating…" state this time. The periodic timer also keeps it warm.
        ValueBreakdown bd = valueBreakdownCache.get(owner);
        boolean fresh = bd != null && valueScanThrottled(owner);
        if (bd == null) {
            // No cached breakdown yet: compute it off the click, next tick.
            Bukkit.getGlobalRegionScheduler().execute(this, () -> computeValueBreakdown(owner));
        }
        Map<Material, Integer> perBlock = blockValues();

        Inventory inv = newGui(GuiKind.VALUE, BIG_SIZE, "✧ Island Value ✧");
        ((SkyGuiHolder) inv.getHolder()).context = owner;
        paintBorder(inv, guiFrame);

        // Summary tile (centre of the top interior row).
        int level = islands.level(owner);
        int rank = leaderboardRank(owner);
        long totalValue = bd != null ? bd.total : (long) level
                * Math.max(1, getConfig().getInt("leaderboard.points-per-level", 100));
        String scanState = bd == null ? "§8calculating… re-open in a moment~"
                : (fresh ? "§8scanned recently" : "§8using cached scan");
        inv.setItem(13, button(Material.NETHER_STAR, "§d✿ " + ownerName(owner) + "'s Island ✿",
                "§7Level: §f" + level,
                "§7Total value: §f" + totalValue,
                rank > 0 ? "§7Leaderboard rank: §f#" + rank : "§8(unranked)",
                scanState));

        if (bd == null) {
            inv.setItem(31, button(Material.CLOCK, "§7Calculating island value…",
                    "§8re-open in a moment for the breakdown~"));
        } else if (bd.subtotals.isEmpty()) {
            inv.setItem(31, button(Material.BARRIER, "§7No valued blocks found",
                    "§8build with valuable blocks to raise your level!"));
        } else {
            List<Map.Entry<Material, Long>> top = new ArrayList<>(bd.subtotals.entrySet());
            top.sort(Map.Entry.<Material, Long>comparingByValue().reversed());
            int shown = Math.min(BIG_INTERIOR.length - 7, top.size()); // leave top row for summary
            for (int i = 0; i < shown; i++) {
                Map.Entry<Material, Long> en = top.get(i);
                Material m = en.getKey();
                int count = bd.counts.getOrDefault(m, 0);
                int per = perBlock.getOrDefault(m, 0);
                Material icon = m.isItem() ? m : Material.PAPER;
                inv.setItem(BIG_INTERIOR[7 + i], button(icon, "§b" + humanize(m.name().toLowerCase(Locale.ROOT)),
                        "§7" + count + " §8×§7 " + per + " §8=§f " + en.getValue(),
                        "§8value contribution"));
            }
        }
        addBackButton(inv, SLOT_BACK_BIG);
        p.openInventory(inv);
    }

    /** 1-based leaderboard rank of {@code owner} by cached level (0 = not found). */
    private int leaderboardRank(UUID owner) {
        List<UUID> owners = islands.allOwners();
        owners.sort(Comparator
                .comparingInt((UUID o) -> islands.level(o)).reversed()
                .thenComparing(Comparator.comparingInt((UUID o) -> islands.memberCount(o)).reversed()));
        int idx = owners.indexOf(owner);
        return idx < 0 ? 0 : idx + 1;
    }

    // ----------------------------------------------------------------- generator cost

    /** The item charged for a generator upgrade (config {@code generator.upgrade-cost-item}). */
    private Material upgradeCostItem() {
        Material m = Material.matchMaterial(
                getConfig().getString("generator.upgrade-cost-item", "DIAMOND").toUpperCase(Locale.ROOT));
        return m == null ? Material.DIAMOND : m;
    }

    /**
     * Item cost to upgrade FROM level {@code curLevel} to {@code curLevel + 1}.
     * Formula: {@code base + per-level * curLevel}. With base=2, per-level=2 this
     * is L1→2 = 4, L2→3 = 6, L3→4 = 8, ... (clamped to a minimum of 1).
     */
    private int upgradeCost(int curLevel) {
        int base = getConfig().getInt("generator.upgrade-cost-base", 2);
        int per = getConfig().getInt("generator.upgrade-cost-per-level", 2);
        return Math.max(1, base + per * curLevel);
    }

    private void cycleVisitPolicy(Player p) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        IslandManager.VisitPolicy cur = islands.visitPolicy(owner);
        IslandManager.VisitPolicy[] all = IslandManager.VisitPolicy.values();
        IslandManager.VisitPolicy next = all[(cur.ordinal() + 1) % all.length];
        islands.setVisitPolicy(owner, next);
        p.sendMessage("§d(✧) visit policy → §f" + next.name().toLowerCase(Locale.ROOT) + "§d~");
        chime(p);
    }

    /** Turns a snake_case key like {@code tnt_explosions} into "Tnt explosions". */
    private static String humanize(String key) {
        if (key == null || key.isEmpty()) return "";
        String s = key.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String flagLabel(String f) {
        if (f.equals("pvp")) return "PvP";
        return humanize(f);
    }

    private void animateMenus() {
        guiFrame++;
        final int frame = guiFrame;
        for (Player p : Bukkit.getOnlinePlayers()) {
            // The open inventory belongs to the player's region thread — hop there.
            p.getScheduler().run(this, t -> {
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof SkyGuiHolder) {
                    paintBorder(top, frame);
                    p.updateInventory();
                }
            }, null);
        }
    }

    /** Shimmer the border panes with a rotating colour offset. */
    private void paintBorder(Inventory inv, int frame) {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) inv.setItem(i, pane(SHIMMER[Math.floorMod(i + frame, SHIMMER.length)]));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SkyGuiHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null
                || !(e.getClickedInventory().getHolder() instanceof SkyGuiHolder)) return;

        switch (holder.kind) {
            case MAIN -> onMainClick(p, e);
            case FLAGS -> onFlagsClick(p, holder, e);
            case TRUST -> onTrustClick(p, holder, e);
            case WARPS -> onWarpsClick(p, holder, e);
            case GENERATOR -> onGeneratorClick(p, holder, e);
            case PERM_PICK -> onPermPickClick(p, holder, e);
            case PERMS -> onPermsClick(p, holder, e);
            case VALUE -> { if (e.getRawSlot() == SLOT_BACK_BIG) openMenu(p); }
            default -> { /* TOP/VISIT — read only */ }
        }
    }

    private void onMainClick(Player p, InventoryClickEvent e) {
        switch (e.getRawSlot()) {
            case SLOT_CREATE -> { p.closeInventory(); createIsland(p); }
            case SLOT_HOME -> { p.closeInventory(); goHome(p); }
            case SLOT_SPAWN -> {
                p.closeInventory();
                p.teleportAsync(mainWorld().getSpawnLocation());
                p.sendMessage("§d(✧) → spawn ✨");
            }
            case SLOT_DELETE -> {
                if (e.isShiftClick()) { p.closeInventory(); deleteIsland(p); }
                else p.sendMessage("§c(✧) SHIFT-click the TNT to confirm deleting your island~");
            }
            case SLOT_FLAGS -> openFlagsMenu(p);
            case SLOT_TRUST -> openTrustMenu(p);
            case SLOT_WARPS -> openWarpsMenu(p);
            case SLOT_GENERATOR -> openGeneratorMenu(p);
            case SLOT_VISIT -> cycleVisitPolicy(p);
            case SLOT_PERMS -> openPermRolePicker(p);
            case SLOT_TOP -> { p.closeInventory(); cmdTop(p); }
            case SLOT_VALUE -> openValueMenu(p);
            case SLOT_KICK -> openTrustMenu(p);
            default -> { /* info / filler */ }
        }
    }

    private void onFlagsClick(Player p, SkyGuiHolder holder, InventoryClickEvent e) {
        if (holder.context == null) return;
        if (e.getRawSlot() == SLOT_BACK_BIG) { openMenu(p); return; }
        if (e.getRawSlot() == SLOT_PREV) { openFlagsMenu(p, holder.page - 1); return; }
        if (e.getRawSlot() == SLOT_NEXT) { openFlagsMenu(p, holder.page + 1); return; }
        if (e.getRawSlot() == SLOT_PAGE_INFO) return;
        if (!hasManage(p, holder.context)) {
            p.sendMessage("§c(✧) no manage access~");
            return;
        }
        int start = holder.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (e.getRawSlot() == BIG_INTERIOR[i]) {
                int idx = start + i;
                if (idx >= IslandManager.FLAG_KEYS.length) return;
                islands.toggleFlag(holder.context, IslandManager.FLAG_KEYS[idx]);
                chime(p);
                openFlagsMenu(p, holder.page);
                return;
            }
        }
    }

    /** Whether the player may manage (edit flags/perms of) the given island. */
    private boolean hasManage(Player p, UUID owner) {
        return p.getUniqueId().equals(owner)
                || p.hasPermission("kawaiiskyblock.admin")
                || islands.canIn(islands.worldFolder(owner), p.getUniqueId(), IslandManager.Perm.MANAGE);
    }

    // ----------------------------------------------------------------- permissions editor

    /** Roles a player can edit permissions for (excludes LEADER, who always has all). */
    private static final IslandManager.Role[] EDITABLE_ROLES = {
            IslandManager.Role.COOP, IslandManager.Role.MEMBER, IslandManager.Role.VISITOR };

    /** Step 1: pick which role's permissions to edit. */
    private void openPermRolePicker(Player p) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        Inventory inv = newGui(GuiKind.PERM_PICK, GUI_SIZE, "✧ Permissions ✧");
        ((SkyGuiHolder) inv.getHolder()).context = owner;
        paintBorder(inv, guiFrame);
        int[] slots = {11, 13, 15};
        Material[] icons = {Material.DIAMOND, Material.IRON_INGOT, Material.LEATHER};
        for (int i = 0; i < EDITABLE_ROLES.length; i++) {
            IslandManager.Role r = EDITABLE_ROLES[i];
            inv.setItem(slots[i], button(icons[i], "§b" + humanize(r.name().toLowerCase(Locale.ROOT)),
                    "§7edit which permissions", "§7this role is granted",
                    "§8click to open the editor"));
        }
        addBackButton(inv, SLOT_BACK_SMALL);
        p.openInventory(inv);
    }

    /** Step 2: paged editor toggling one role's permission grants. */
    private void openPermsMenu(Player p, IslandManager.Role role, int page) {
        UUID owner = requireManageOwner(p);
        if (owner == null) return;
        int pages = pageCount(IslandManager.PERM_KEYS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        String rn = humanize(role.name().toLowerCase(Locale.ROOT));
        Inventory inv = newGui(GuiKind.PERMS, BIG_SIZE, "✧ " + rn + " " + (page + 1) + "/" + pages + " ✧");
        SkyGuiHolder holder = (SkyGuiHolder) inv.getHolder();
        holder.context = owner;
        holder.role = role;
        holder.page = page;
        paintBorder(inv, guiFrame);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= IslandManager.PERM_KEYS.length) break;
            String key = IslandManager.PERM_KEYS[idx];
            boolean on = islands.rolePermission(owner, role, key);
            inv.setItem(BIG_INTERIOR[i], button(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "§a" : "§c") + humanize(key),
                    "§8" + key, on ? "§a● GRANTED" : "§c○ DENIED", "§8click to toggle"));
        }
        addPaging(inv, page, pages);
        addBackButton(inv, SLOT_BACK_BIG);
        p.openInventory(inv);
    }

    private void onPermPickClick(Player p, SkyGuiHolder holder, InventoryClickEvent e) {
        if (holder.context == null) return;
        if (e.getRawSlot() == SLOT_BACK_SMALL) { openMenu(p); return; }
        int[] slots = {11, 13, 15};
        for (int i = 0; i < EDITABLE_ROLES.length; i++) {
            if (e.getRawSlot() == slots[i]) {
                if (!hasManage(p, holder.context)) { p.sendMessage("§c(✧) no manage access~"); return; }
                openPermsMenu(p, EDITABLE_ROLES[i], 0);
                return;
            }
        }
    }

    private void onPermsClick(Player p, SkyGuiHolder holder, InventoryClickEvent e) {
        if (holder.context == null || holder.role == null) return;
        if (e.getRawSlot() == SLOT_BACK_BIG) { openPermRolePicker(p); return; }
        if (e.getRawSlot() == SLOT_PREV) { openPermsMenu(p, holder.role, holder.page - 1); return; }
        if (e.getRawSlot() == SLOT_NEXT) { openPermsMenu(p, holder.role, holder.page + 1); return; }
        if (e.getRawSlot() == SLOT_PAGE_INFO) return;
        if (!hasManage(p, holder.context)) { p.sendMessage("§c(✧) no manage access~"); return; }
        int start = holder.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (e.getRawSlot() == BIG_INTERIOR[i]) {
                int idx = start + i;
                if (idx >= IslandManager.PERM_KEYS.length) return;
                islands.toggleRolePermission(holder.context, holder.role, IslandManager.PERM_KEYS[idx]);
                chime(p);
                openPermsMenu(p, holder.role, holder.page);
                return;
            }
        }
    }

    private void onTrustClick(Player p, SkyGuiHolder holder, InventoryClickEvent e) {
        if (holder.context == null) return;
        if (e.getRawSlot() == SLOT_BACK_SMALL) { openMenu(p); return; }
        int idx = e.getRawSlot() - 10;
        if (idx < 0 || idx >= holder.rowTargets.size()) return;
        UUID target = holder.rowTargets.get(idx);
        if (target.equals(holder.context)) { p.sendMessage("§c(✧) the leader's role is locked~"); return; }

        boolean isLeader = p.getUniqueId().equals(holder.context) || p.hasPermission("kawaiiskyblock.admin");
        IslandManager.Role current = islands.roleOf(holder.context, target);

        if (e.isShiftClick()) {
            kickFromIsland(p, holder.context, Bukkit.getOfflinePlayer(target));
            openTrustMenu(p);
            return;
        }
        // Cycle MEMBER → COOP → MEMBER (leader-only for coop changes).
        IslandManager.Role next;
        switch (current) {
            case VISITOR: next = IslandManager.Role.MEMBER; break;
            case MEMBER: next = IslandManager.Role.COOP; break;
            case COOP: next = IslandManager.Role.MEMBER; break;
            default: next = IslandManager.Role.MEMBER; break;
        }
        if ((current == IslandManager.Role.COOP || next == IslandManager.Role.COOP) && !isLeader) {
            p.sendMessage("§c(✧) only the leader can change co-op roles~");
            return;
        }
        islands.setRole(holder.context, target, next);
        chime(p);
        openTrustMenu(p);
    }

    private void onWarpsClick(Player p, SkyGuiHolder holder, InventoryClickEvent e) {
        if (holder.context == null) return;
        if (e.getRawSlot() == SLOT_BACK_SMALL) { openMenu(p); return; }
        int idx = e.getRawSlot() - 10;
        if (idx < 0 || idx >= holder.rowNames.size()) return;
        String warp = holder.rowNames.get(idx);
        if (!islands.canVisit(holder.context, p.getUniqueId()) && !p.hasPermission("kawaiiskyblock.admin")) {
            p.sendMessage("§c(✧) you can't use this warp~");
            return;
        }
        Location loc = islands.warpLocation(holder.context, warp);
        if (loc == null) { p.sendMessage("§c(✧) that warp is gone~"); return; }
        p.closeInventory();
        p.teleportAsync(loc);
        p.sendMessage("§d(✧) → warp §f" + warp + "§d ✨");
    }

    private void onGeneratorClick(Player p, SkyGuiHolder holder, InventoryClickEvent e) {
        if (holder.context == null) return;
        if (e.getRawSlot() == SLOT_BACK_SMALL) { openMenu(p); return; }
        if (e.getRawSlot() == 14) {
            if (!islands.canIn(islands.worldFolder(holder.context), p.getUniqueId(), IslandManager.Perm.MANAGE)
                    && !p.getUniqueId().equals(holder.context) && !p.hasPermission("kawaiiskyblock.admin")) {
                p.sendMessage("§c(✧) no manage access~");
                return;
            }
            if (tryUpgradeGenerator(p, holder.context)) {
                openGeneratorMenu(p);
            }
        }
    }

    private static Component gradientTitle(String text) {
        var b = Component.text();
        int n = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            double t = n <= 1 ? 0 : (double) i / (n - 1);
            b.append(Component.text(String.valueOf(text.charAt(i))).color(lerp(0xFF8AD8, 0xC56BFF, t)));
        }
        return b.build().decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private static TextColor lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) Math.round(((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) Math.round((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return TextColor.color(r, g, bl);
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(SECTION.deserialize(name).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> l = new ArrayList<>();
                for (String s : lore) l.add(SECTION.deserialize(s).decoration(TextDecoration.ITALIC, false));
                meta.lore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Places a "« Back" button (to the main island menu) at the given slot. */
    private void addBackButton(Inventory inv, int slot) {
        inv.setItem(slot, button(Material.ARROW, "§7« Back", "§8return to the island menu"));
    }

    private ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            it.setItemMeta(meta);
        }
        return it;
    }
}
