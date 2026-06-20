package com.ferisooo.kawaiiessentials;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KawaiiEssentials (✧) - a cute little all-in-one essentials plugin.
 *
 *   /sethome /home /homes /delhome  -> named homes per player
 *   /tpa /tpaccept /tpdeny          -> teleport requests (expire in 60s)
 *   /hub /sethub                    -> server hub
 *   /back                           -> back to last death spot
 *   /kit                            -> starter kit (30 min cooldown)
 *   /trash [undo]                   -> a trash bin you can undo once
 *
 * Everything is stored in plain YAML files in the data folder.
 * Version-safe: sounds via String, no Attribute enum, ChatColor for colors.
 */
public final class KawaiiEssentials extends JavaPlugin implements Listener {

    private static final long TPA_EXPIRY_MS = 60_000L;        // 60 seconds
    private static final long KIT_COOLDOWN_MS = 30L * 60_000L; // 30 minutes

    // ---- animated shimmer border (mirrors KawaiiSkyblock/KawaiiDungeons) ----
    private static final Material[] SHIMMER = {
        Material.PINK_STAINED_GLASS_PANE,
        Material.MAGENTA_STAINED_GLASS_PANE,
        Material.PURPLE_STAINED_GLASS_PANE,
    };
    private int guiFrame = 0;

    // Default junk materials offered by the /trash filter GUI (overridable in config.yml).
    private static final List<String> DEFAULT_TRASH_FILTER_ITEMS = Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(
        "COBBLESTONE", "DIRT", "GRAVEL", "SAND", "COBBLED_DEEPSLATE",
        "NETHERRACK", "ANDESITE", "DIORITE", "GRANITE", "FLINT", "ROTTEN_FLESH"
    )));

    // ---- persisted stores ----
    private File homesFile;
    private YamlConfiguration homes;   // keyed by player UUID -> homeName -> location
    private File dataFile;
    private YamlConfiguration data;    // hub, deaths, kit cooldowns
    private File warpsFile;
    private YamlConfiguration warps;   // keyed by owner UUID -> name + warps.<name> -> location

    // Keys used to stash a warp owner / warp name on a GUI button so clicks
    // resolve unambiguously (instead of parsing display names).
    private NamespacedKey ownerKey;
    private NamespacedKey warpKey;

    // ---- in-memory state ----
    // target UUID -> pending request (requester + timestamp)
    private final Map<UUID, TpaRequest> tpaRequests = new HashMap<>();
    // player UUID -> last trashed snapshot (cleared after undo)
    private final Map<UUID, List<ItemStack>> trashSnapshots = new HashMap<>();
    // player UUID -> warp name they're renaming (waiting for their next chat
    // line). Touched from both the main thread (GUI) and the async chat event,
    // so it must be concurrent.
    private final Map<UUID, String> pendingWarpRename = new java.util.concurrent.ConcurrentHashMap<>();

    /** A pending teleport request. {@code here} == true means /tpahere: the
     *  acceptor comes to the requester (instead of the requester going to them). */
    private static final class TpaRequest {
        final UUID requester;
        final long sentAt;
        final boolean here;
        TpaRequest(UUID requester, long sentAt, boolean here) {
            this.requester = requester;
            this.sentAt = sentAt;
            this.here = here;
        }
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();
        ownerKey = new NamespacedKey(this, "warp_owner");
        warpKey = new NamespacedKey(this, "warp_name");
        loadStores();
        getServer().getPluginManager().registerEvents(this, this);
        // Shimmer-animate any open KawaiiEssentials menu every 4 ticks.
        Bukkit.getScheduler().runTaskTimer(this, this::animateMenus, 4L, 4L);
        getLogger().info("(✧) KawaiiEssentials ready ~ homes, tpa, hub, back, kit & trash!");
    }

    @Override
    public void onDisable() {
        saveHomes();
        saveData();
        saveWarps();
    }

    // ============================================================
    //   STORAGE
    // ============================================================

    private void loadStores() {
        homesFile = new File(getDataFolder(), "homes.yml");
        if (!homesFile.exists()) {
            try {
                homesFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create homes.yml: " + e.getMessage());
            }
        }
        homes = YamlConfiguration.loadConfiguration(homesFile);

        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        warpsFile = new File(getDataFolder(), "warps.yml");
        if (!warpsFile.exists()) {
            try {
                warpsFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create warps.yml: " + e.getMessage());
            }
        }
        warps = YamlConfiguration.loadConfiguration(warpsFile);
    }

    private void saveWarps() {
        try {
            warps.save(warpsFile);
        } catch (IOException e) {
            getLogger().warning("Could not save warps.yml: " + e.getMessage());
        }
    }

    private void saveHomes() {
        try {
            homes.save(homesFile);
        } catch (IOException e) {
            getLogger().warning("Could not save homes.yml: " + e.getMessage());
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    /** Write a Location into a YAML section path. */
    private void writeLocation(YamlConfiguration cfg, String path, Location loc) {
        cfg.set(path + ".world", loc.getWorld().getName());
        cfg.set(path + ".x", loc.getX());
        cfg.set(path + ".y", loc.getY());
        cfg.set(path + ".z", loc.getZ());
        cfg.set(path + ".yaw", (double) loc.getYaw());
        cfg.set(path + ".pitch", (double) loc.getPitch());
    }

    /** Read a Location from a YAML section path (null if missing or world gone). */
    private Location readLocation(YamlConfiguration cfg, String path) {
        if (!cfg.isConfigurationSection(path)) {
            return null;
        }
        String worldName = cfg.getString(path + ".world");
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = cfg.getDouble(path + ".x");
        double y = cfg.getDouble(path + ".y");
        double z = cfg.getDouble(path + ".z");
        float yaw = (float) cfg.getDouble(path + ".yaw");
        float pitch = (float) cfg.getDouble(path + ".pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    // ============================================================
    //   HELPERS
    // ============================================================

    private void msg(CommandSender to, String text) {
        String c = ChatColor.translateAlternateColorCodes('&', text);
        if (to instanceof Player p && isBedrock(p.getUniqueId())) c = bedrockText(c);
        to.sendMessage(c);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** A title coloured for {@code p}, sanitised if they're on Bedrock. */
    private String titleFor(Player p, String rawAmp) {
        String c = color(rawAmp);
        return isBedrock(p.getUniqueId()) ? bedrockText(c) : c;
    }

    // ---- Bedrock (Geyser/Floodgate) text safety ----
    // Detect Bedrock players via the Floodgate API by reflection (soft
    // dependency — no Floodgate, everyone is treated as Java), then swap the
    // kawaii glyphs Bedrock's font can't render for plain ASCII look-alikes.
    private static Boolean FG_PRESENT;
    private static java.lang.reflect.Method FG_IS;
    private static Object FG_API;

    static boolean isBedrock(UUID id) {
        try {
            if (FG_PRESENT == null) {
                try {
                    Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    FG_API = api.getMethod("getInstance").invoke(null);
                    FG_IS = api.getMethod("isFloodgatePlayer", UUID.class);
                    FG_PRESENT = (FG_API != null && FG_IS != null);
                } catch (Throwable t) {
                    FG_PRESENT = false;
                }
            }
            if (!FG_PRESENT) return false;
            Object r = FG_IS.invoke(FG_API, id);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    static String bedrockText(String s) {
        if (s == null) return null;
        String t = s
                .replace("✿", "*").replace("✦", "*").replace("✧", "*").replace("✈", ">")
                .replace("✚", "+").replace("▶", ">").replace("◀", "<").replace("↩", "<")
                .replace("→", "->").replace("←", "<-").replace("━", "-").replace("─", "-")
                .replace("×", "x").replace("♥", "<3").replace("●", "*")
                .replace("🏠", "").replace("🛏", "").replace("⚙", "").replace("🗑", "")
                .replace("🏝", "").replace("✨", "").replace("💧", "");
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.trim();
    }

    /** For Bedrock viewers, rewrite every item name/lore in a menu to ASCII-safe text. */
    private void applyBedrock(Player p, Inventory inv) {
        if (!isBedrock(p.getUniqueId())) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            ItemMeta meta = it.getItemMeta();
            if (meta == null) continue;
            boolean changed = false;
            if (meta.hasDisplayName()) { meta.setDisplayName(bedrockText(meta.getDisplayName())); changed = true; }
            if (meta.hasLore()) {
                List<String> nl = new ArrayList<>();
                for (String l : meta.getLore()) nl.add(bedrockText(l));
                meta.setLore(nl);
                changed = true;
            }
            if (changed) it.setItemMeta(meta);
        }
    }

    /** Safely teleport a player, loading the destination chunk first. */
    private void safeTeleport(Player player, Location dest) {
        World world = dest.getWorld();
        world.getChunkAt(dest); // loads the chunk if needed
        player.teleport(dest);
        // version-safe sound: String overload only.
        player.playSound(player.getLocation(), "minecraft:entity.enderman.teleport", 1f, 1f);
    }

    private void pickupSound(Player player) {
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1f);
    }

    // ============================================================
    //   COMMANDS
    // ============================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&dThis command is for players only ~");
            return true;
        }

        String name = command.getName().toLowerCase();
        switch (name) {
            case "sethome":  return cmdSetHome(player, args);
            case "home":     return cmdHome(player, args);
            case "homes":    return cmdHomes(player);
            case "delhome":  return cmdDelHome(player, args);
            case "tpa":      return cmdTpa(player, args);
            case "tpahere":  return cmdTpaHere(player, args);
            case "tpaccept": return cmdTpAccept(player);
            case "tpdeny":   return cmdTpDeny(player);
            case "hub":      return cmdHub(player);
            case "sethub":   return cmdSetHub(player);
            case "back":     return cmdBack(player);
            case "kit":      return cmdKit(player);
            case "trash":    return cmdTrash(player, args);
            case "setwarp":  return cmdSetWarp(player, args);
            case "delwarp":  return cmdDelWarp(player, args);
            case "warp":     return cmdWarp(player, args);
            case "warps":    return cmdWarps(player);
            case "kess":     return cmdMenu(player);
            default:         return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase();
        // /tpa|/tpahere <player> — suggest online player names at arg 1.
        if (("tpa".equals(cmd) || "tpahere".equals(cmd)) && args.length == 1) {
            String pre = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(pre)) out.add(p.getName());
            }
            return out;
        }
        // /warp <name> — suggest every warp name; /delwarp <name> — only the
        // sender's own warps.
        if (("warp".equals(cmd) || "delwarp".equals(cmd)) && args.length == 1) {
            String pre = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            if ("delwarp".equals(cmd) && sender instanceof Player p) {
                ConfigurationSection sec = warps.getConfigurationSection(p.getUniqueId() + ".warps");
                if (sec != null) for (String w : sec.getKeys(false)) if (w.startsWith(pre)) out.add(w);
            } else {
                for (String owner : warps.getKeys(false)) {
                    ConfigurationSection sec = warps.getConfigurationSection(owner + ".warps");
                    if (sec == null) continue;
                    for (String w : sec.getKeys(false)) if (w.startsWith(pre) && !out.contains(w)) out.add(w);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }

    // ---- Essentials menu (/kess) ----

    // Main menu is 45 slots; buttons live in the interior (non-border) rows 2-4.
    // Row 2: slots 10-16, Row 3: slots 19-25, Row 4: slots 28-34.
    private static final int M_HOME = 10, M_HOMES = 11, M_SETHOME = 12, M_TPA = 13, M_TPAHERE = 14,
            M_HUB = 15, M_BACK = 19, M_KIT = 20, M_TRASH = 21, M_TRASH_FILTER = 22, M_WARPS = 23;

    private boolean cmdMenu(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 45, titleFor(player, "&d✿ Essentials ✿"));
        holder.setInventory(inv);

        // animated shimmer border (buttons live in the interior rows, so they're safe)
        paintBorder(inv, guiFrame);

        inv.setItem(M_HOME,    menuItem(Material.RED_BED, "&a▶ Home", "&7Teleport to your default home.", "&8command: &f/home"));
        inv.setItem(M_HOMES,   menuItem(Material.CYAN_BED, "&b🏠 Homes", "&7Browse all your homes.", "&7Left-click a home to teleport,", "&7right-click to delete it.", "&8command: &f/homes"));
        inv.setItem(M_SETHOME, menuItem(Material.OAK_DOOR, "&a✚ Set Home", "&7Save your current spot as", "&7a home named &fhome&7.", "&8command: &f/sethome"));
        inv.setItem(M_TPA,     menuItem(Material.ENDER_PEARL, "&b✈ TPA", "&7Pick an online player to", "&7send a teleport request to.", "&7You teleport to them.", "&8command: &f/tpa <player>"));
        inv.setItem(M_TPAHERE, menuItem(Material.ENDER_EYE, "&b✈ TPA Here", "&7Pick an online player to", "&7summon to you (they accept).", "&7They teleport to you.", "&8command: &f/tpahere <player>"));
        inv.setItem(M_HUB,     menuItem(Material.BEACON, "&e✦ Hub", "&7Warp to the server hub.", "&8command: &f/hub"));
        inv.setItem(M_BACK,    menuItem(Material.COMPASS, "&c↩ Back", "&7Return to where you last died.", "&8command: &f/back"));
        inv.setItem(M_KIT,     menuItem(Material.LEATHER_CHESTPLATE, "&6✿ Kit", "&7Claim your starter kit.", "&830 min cooldown", "&8command: &f/kit"));
        inv.setItem(M_TRASH,   menuItem(Material.CAULDRON, "&7🗑 Trash Bin", "&7Open a bin to throw items away.", "&8command: &f/trash"));
        inv.setItem(M_TRASH_FILTER, menuItem(Material.HOPPER, "&7⚙ Trash Filter", "&7Clear junk by material type,", "&7with an undo button.", "&8command: &f/trash filter"));
        inv.setItem(M_WARPS,   menuItem(Material.LODESTONE, "&d✦ Warps", "&7Browse player warps - see who", "&7has set warps, then teleport.", "&7Set your own: &f/setwarp <name>", "&8command: &f/warps"));

        applyBedrock(player, inv);
        player.openInventory(inv);
        pickupSound(player);
        return true;
    }

    private ItemStack menuItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(ChatColor.translateAlternateColorCodes('&', s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Repaint the shimmer border of every open KawaiiEssentials menu, then refresh it. */
    private void animateMenus() {
        guiFrame++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof MenuHolder) {
                paintBorder(top, guiFrame);
                p.updateInventory();
            }
        }
    }

    /** Paint a rotating shimmer onto the perimeter slots only (never the middle buttons). */
    private void paintBorder(Inventory inv, int frame) {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) {
                inv.setItem(i, menuItem(SHIMMER[Math.floorMod(i + frame, SHIMMER.length)], " "));
            }
        }
    }

    @EventHandler
    public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true); // it's a menu, not a real container
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Route to the right GUI based on which menu this holder belongs to.
        if (holder.getKind() == MenuHolder.Kind.TRASH_FILTER) {
            onTrashFilterClick(player, event);
            return;
        }
        if (holder.getKind() == MenuHolder.Kind.TPA_PICKER) {
            onTpaPickerClick(player, event, false);
            return;
        }
        if (holder.getKind() == MenuHolder.Kind.TPAHERE_PICKER) {
            onTpaPickerClick(player, event, true);
            return;
        }
        if (holder.getKind() == MenuHolder.Kind.HOMES) {
            onHomesClick(player, event);
            return;
        }
        if (holder.getKind() == MenuHolder.Kind.WARPS_PLAYERS) {
            onWarpsPlayersClick(player, event);
            return;
        }
        if (holder.getKind() == MenuHolder.Kind.WARPS_LIST) {
            onWarpsListClick(player, holder, event);
            return;
        }

        switch (event.getRawSlot()) {
            case M_HOME    -> { player.closeInventory(); cmdHome(player, new String[0]); }
            case M_HOMES   -> openHomesMenu(player);
            case M_SETHOME -> { player.closeInventory(); cmdSetHome(player, new String[0]); }
            case M_TPA     -> openTpaPicker(player, false);
            case M_TPAHERE -> openTpaPicker(player, true);
            case M_HUB     -> { player.closeInventory(); cmdHub(player); }
            case M_BACK    -> { player.closeInventory(); cmdBack(player); }
            case M_KIT     -> { player.closeInventory(); cmdKit(player); }
            case M_TRASH   -> { player.closeInventory(); cmdTrash(player, new String[0]); }
            case M_TRASH_FILTER -> openTrashFilter(player);
            case M_WARPS   -> openWarpsPlayers(player);
            default -> { /* border or empty */ }
        }
    }

    // ---- TPA picker GUI (Kind.TPA_PICKER) ----

    private static final int BACK_SLOT_54 = 49; // bottom-centre back button for 54-slot GUIs

    /** Open an animated GUI listing online players as heads; click one to /tpa
     *  (or /tpahere when {@code here}) them. */
    private void openTpaPicker(Player player, boolean here) {
        MenuHolder holder = new MenuHolder(here ? MenuHolder.Kind.TPAHERE_PICKER : MenuHolder.Kind.TPA_PICKER);
        Inventory inv = Bukkit.createInventory(holder, 54,
                titleFor(player, here ? "&d✿ TPA Here: pick a player ✿" : "&d✿ TPA: pick a player ✿"));
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        fillTpaPicker(player, inv);
        applyBedrock(player, inv);
        player.openInventory(inv);
        pickupSound(player);
    }

    /** (Re)place online-player heads into interior slots, plus a Back button. */
    private void fillTpaPicker(Player viewer, Inventory inv) {
        int size = inv.getSize();
        List<Player> others = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(viewer.getUniqueId())) {
                others.add(p);
            }
        }
        int idx = 0;
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) continue;
            if (i == BACK_SLOT_54) continue; // reserved for the back button
            if (others.isEmpty() && idx == 0) {
                inv.setItem(i, menuItem(Material.BARRIER, "&cNo one else online",
                        "&7There are no other players", "&7to teleport to right now."));
                idx++;
                continue;
            }
            if (idx < others.size()) {
                inv.setItem(i, playerHead(others.get(idx++)));
            } else {
                inv.setItem(i, null);
            }
        }
        inv.setItem(BACK_SLOT_54, menuItem(Material.ARROW, "&7↩ Back", "&7Return to the essentials menu."));
    }

    /** Build a player-head button naming the given online player. */
    private ItemStack playerHead(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer((OfflinePlayer) target);
            skull.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b✈ " + target.getName()));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7Click to send a teleport"));
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7request to &f" + target.getName() + "&7."));
            lore.add(ChatColor.translateAlternateColorCodes('&', "&8command: &f/tpa " + target.getName()));
            skull.setLore(lore);
            head.setItemMeta(skull);
        } else if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b✈ " + target.getName()));
            head.setItemMeta(meta);
        }
        return head;
    }

    /** Handle a click in either TPA picker GUI ({@code here} routes to /tpahere). */
    private void onTpaPickerClick(Player player, org.bukkit.event.inventory.InventoryClickEvent event, boolean here) {
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == BACK_SLOT_54) {
            cmdMenu(player);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
        String name = ChatColor.stripColor(
                clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "");
        if (name == null) return;
        // Strip the prefix glyph ('✈' on Java, '>' after Bedrock sanitising).
        name = name.replace("✈", "").replace(">", "").trim();
        if (name.isEmpty()) return;
        player.closeInventory();
        if (here) cmdTpaHere(player, new String[]{ name });
        else cmdTpa(player, new String[]{ name });
    }

    // ---- Homes GUI (Kind.HOMES) ----

    /** Open an animated GUI listing the player's homes; left-click tp, right-click delete. */
    private void openHomesMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.HOMES);
        Inventory inv = Bukkit.createInventory(holder, 54, titleFor(player, "&d✿ Your Homes ✿"));
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        fillHomesMenu(player, inv);
        applyBedrock(player, inv);
        player.openInventory(inv);
        pickupSound(player);
    }

    /** (Re)place a BED button per home into interior slots, plus a Back button. */
    private void fillHomesMenu(Player player, Inventory inv) {
        int size = inv.getSize();
        List<String> names = new ArrayList<>();
        ConfigurationSection sec = homes.getConfigurationSection(player.getUniqueId() + ".homes");
        if (sec != null) {
            names.addAll(sec.getKeys(false));
        }
        int idx = 0;
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) continue;
            if (i == BACK_SLOT_54) continue;
            if (names.isEmpty() && idx == 0) {
                inv.setItem(i, menuItem(Material.BARRIER, "&cNo homes yet",
                        "&7Set one with &f/sethome [name]", "&7or the Set Home button."));
                idx++;
                continue;
            }
            if (idx < names.size()) {
                String home = names.get(idx++);
                Location loc = readLocation(homes, player.getUniqueId() + ".homes." + home);
                String coords = (loc == null) ? "&8(unknown world)"
                        : "&7" + loc.getWorld().getName() + " &f"
                        + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ();
                inv.setItem(i, menuItem(Material.WHITE_BED,
                        "&a🛏 " + home,
                        coords,
                        "&8Left-click: &7teleport &8(/home " + home + ")",
                        "&8Right-click: &7delete &8(/delhome " + home + ")"));
            } else {
                inv.setItem(i, null);
            }
        }
        inv.setItem(BACK_SLOT_54, menuItem(Material.ARROW, "&7↩ Back", "&7Return to the essentials menu."));
    }

    /** Handle a click in the Homes GUI. */
    private void onHomesClick(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == BACK_SLOT_54) {
            cmdMenu(player);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.WHITE_BED) return;
        String home = ChatColor.stripColor(
                clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "");
        if (home == null) return;
        home = home.replace("🛏", "").trim();
        if (home.isEmpty()) return;
        if (event.isRightClick()) {
            cmdDelHome(player, new String[]{ home });
            fillHomesMenu(player, event.getInventory());
            player.updateInventory();
        } else {
            player.closeInventory();
            cmdHome(player, new String[]{ home });
        }
    }

    // ---- Homes ----

    private boolean cmdSetHome(Player player, String[] args) {
        String homeName = (args.length >= 1) ? args[0].toLowerCase() : "home";
        String path = player.getUniqueId() + ".homes." + homeName;
        writeLocation(homes, path, player.getLocation());
        saveHomes();
        msg(player, "&dHome &f" + homeName + " &dset! (✧)");
        pickupSound(player);
        return true;
    }

    private boolean cmdHome(Player player, String[] args) {
        String homeName = (args.length >= 1) ? args[0].toLowerCase() : "home";
        String path = player.getUniqueId() + ".homes." + homeName;
        Location loc = readLocation(homes, path);
        if (loc == null) {
            msg(player, "&cYou don't have a home called &f" + homeName + "&c. Try &f/homes&c.");
            return true;
        }
        safeTeleport(player, loc);
        msg(player, "&dWelcome home, &f" + player.getName() + "&d~");
        return true;
    }

    private boolean cmdHomes(Player player) {
        ConfigurationSection sec = homes.getConfigurationSection(player.getUniqueId() + ".homes");
        if (sec == null || sec.getKeys(false).isEmpty()) {
            msg(player, "&cYou have no homes yet. Set one with &f/sethome [name]&c.");
            return true;
        }
        msg(player, "&dYour homes: &f" + String.join("&d, &f", sec.getKeys(false)));
        return true;
    }

    private boolean cmdDelHome(Player player, String[] args) {
        String homeName = (args.length >= 1) ? args[0].toLowerCase() : "home";
        String path = player.getUniqueId() + ".homes." + homeName;
        if (!homes.isConfigurationSection(path)) {
            msg(player, "&cNo home called &f" + homeName + "&c to delete.");
            return true;
        }
        homes.set(path, null);
        saveHomes();
        msg(player, "&dDeleted home &f" + homeName + "&d.");
        return true;
    }

    // ---- TPA ----

    private boolean cmdTpa(Player player, String[] args) {
        return sendTpaRequest(player, args, false);
    }

    private boolean cmdTpaHere(Player player, String[] args) {
        return sendTpaRequest(player, args, true);
    }

    /** Send a teleport request. {@code here} == /tpahere (the target comes to us). */
    private boolean sendTpaRequest(Player player, String[] args, boolean here) {
        if (args.length < 1) {
            msg(player, "&cUsage: &f/" + (here ? "tpahere" : "tpa") + " <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            msg(player, "&cThat player isn't online.");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "&cYou can't teleport to yourself, silly~");
            return true;
        }
        // latest incoming request for the target wins
        tpaRequests.put(target.getUniqueId(), new TpaRequest(player.getUniqueId(), System.currentTimeMillis(), here));
        if (here) {
            msg(player, "&dAsked &f" + target.getName() + " &dto come to you. It expires in 60s.");
            msg(target, "&f" + player.getName() + " &dwants &lYOU&r&d to teleport to &lthem&r&d. &f/tpaccept &dor &f/tpdeny&d.");
        } else {
            msg(player, "&dRequest sent to &f" + target.getName() + "&d! It expires in 60s.");
            msg(target, "&f" + player.getName() + " &dwants to teleport to you. &f/tpaccept &dor &f/tpdeny&d.");
        }
        pickupSound(target);
        return true;
    }

    private boolean cmdTpAccept(Player player) {
        TpaRequest req = tpaRequests.get(player.getUniqueId());
        if (req == null) {
            msg(player, "&cYou have no pending teleport requests.");
            return true;
        }
        if (System.currentTimeMillis() - req.sentAt > TPA_EXPIRY_MS) {
            tpaRequests.remove(player.getUniqueId());
            msg(player, "&cThat request has expired.");
            return true;
        }
        Player requester = Bukkit.getPlayer(req.requester);
        tpaRequests.remove(player.getUniqueId());
        if (requester == null || !requester.isOnline()) {
            msg(player, "&cThat player is no longer online.");
            return true;
        }
        if (req.here) {
            // /tpahere: the acceptor (this player) travels to the requester.
            safeTeleport(player, requester.getLocation());
            msg(player, "&dTeleporting you to &f" + requester.getName() + "&d~");
            msg(requester, "&f" + player.getName() + " &daccepted - they're on their way to you!");
        } else {
            // /tpa: the requester travels to the acceptor (this player).
            safeTeleport(requester, player.getLocation());
            msg(requester, "&dTeleporting you to &f" + player.getName() + "&d~");
            msg(player, "&dAccepted &f" + requester.getName() + "&d's request.");
        }
        return true;
    }

    private boolean cmdTpDeny(Player player) {
        TpaRequest req = tpaRequests.remove(player.getUniqueId());
        if (req == null) {
            msg(player, "&cYou have no pending teleport requests.");
            return true;
        }
        Player requester = Bukkit.getPlayer(req.requester);
        if (requester != null && requester.isOnline()) {
            msg(requester, "&c" + player.getName() + " denied your teleport request.");
        }
        msg(player, "&dRequest denied.");
        return true;
    }

    // ---- Hub ----

    private boolean cmdHub(Player player) {
        Location hub = readLocation(data, "hub");
        if (hub == null) {
            msg(player, "&cNo hub has been set yet. Ask an op to use &f/sethub&c.");
            return true;
        }
        safeTeleport(player, hub);
        msg(player, "&dWhoosh~ welcome to the hub! (✧)");
        return true;
    }

    private boolean cmdSetHub(Player player) {
        if (!player.hasPermission("kawaiiessentials.sethub")) {
            msg(player, "&cYou don't have permission to set the hub.");
            return true;
        }
        writeLocation(data, "hub", player.getLocation());
        saveData();
        msg(player, "&dHub set to your current location! (✧)");
        pickupSound(player);
        return true;
    }

    // ---- Player warps ----

    /** A warp name: lowercase letters / numbers / underscores, 1-24 chars. */
    private boolean validWarpName(String name) {
        return name != null && name.matches("[a-z0-9_]{1,24}");
    }

    private boolean cmdSetWarp(Player player, String[] args) {
        if (args.length < 1) {
            msg(player, "&cUsage: &f/setwarp <name>");
            return true;
        }
        String warp = args[0].toLowerCase();
        if (!validWarpName(warp)) {
            msg(player, "&cWarp names use letters, numbers and _ (max 24 chars).");
            return true;
        }
        String base = player.getUniqueId().toString();
        warps.set(base + ".name", player.getName()); // remember the owner's name for the GUI
        writeLocation(warps, base + ".warps." + warp, player.getLocation());
        saveWarps();
        msg(player, "&dWarp &f" + warp + " &dset! Anyone can &f/warp " + warp + "&d. (✧)");
        pickupSound(player);
        return true;
    }

    private boolean cmdDelWarp(Player player, String[] args) {
        if (args.length < 1) {
            msg(player, "&cUsage: &f/delwarp <name>");
            return true;
        }
        String warp = args[0].toLowerCase();
        String base = player.getUniqueId().toString();
        if (!warps.isConfigurationSection(base + ".warps." + warp)) {
            msg(player, "&cYou don't have a warp called &f" + warp + "&c.");
            return true;
        }
        warps.set(base + ".warps." + warp, null);
        // If that was their last warp, drop the whole owner entry so they
        // disappear from the "who has warps" GUI.
        ConfigurationSection left = warps.getConfigurationSection(base + ".warps");
        if (left == null || left.getKeys(false).isEmpty()) {
            warps.set(base, null);
        }
        saveWarps();
        msg(player, "&dDeleted warp &f" + warp + "&d.");
        return true;
    }

    private boolean cmdWarp(Player player, String[] args) {
        if (args.length < 1) {
            // No name given — open the picker so they can browse.
            openWarpsPlayers(player);
            return true;
        }
        String warp = args[0].toLowerCase();
        String self = player.getUniqueId().toString();
        List<String> ownersWith = new ArrayList<>();
        for (String owner : warps.getKeys(false)) {
            String path = owner + ".warps." + warp;
            if (!warps.isConfigurationSection(path)) continue;
            // A locked warp is usable only by its owner.
            if (warps.getBoolean(path + ".locked", false) && !owner.equals(self)) continue;
            ownersWith.add(owner);
        }
        if (ownersWith.isEmpty()) {
            msg(player, "&cNo warp called &f" + warp + "&c you can use. Try &f/warps&c.");
            return true;
        }
        String owner;
        if (ownersWith.size() == 1) {
            owner = ownersWith.get(0);
        } else if (ownersWith.contains(player.getUniqueId().toString())) {
            owner = player.getUniqueId().toString(); // prefer your own when names collide
        } else {
            msg(player, "&eSeveral players have a warp named &f" + warp + "&e. Use &f/warps&e to pick one.");
            return true;
        }
        Location loc = readLocation(warps, owner + ".warps." + warp);
        if (loc == null) {
            msg(player, "&cThat warp's world isn't loaded right now.");
            return true;
        }
        safeTeleport(player, loc);
        msg(player, "&dWhoosh~ → warp &f" + warp + "&d ✨");
        return true;
    }

    private boolean cmdWarps(Player player) {
        openWarpsPlayers(player);
        return true;
    }

    // ---- Warps GUI: who has warps (Kind.WARPS_PLAYERS) ----

    // A reserved interior slot for the "set a warp here" button (interior, so
    // the shimmer animation never repaints over it).
    private static final int WARP_ADD_SLOT = 43;

    /** Open a GUI of player heads — one per owner who has at least one warp. */
    private void openWarpsPlayers(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.WARPS_PLAYERS);
        Inventory inv = Bukkit.createInventory(holder, 54, titleFor(player, "&d✿ Warps: who has warps ✿"));
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        fillWarpsPlayers(inv);
        inv.setItem(WARP_ADD_SLOT, menuItem(Material.LIME_DYE, "&a✚ Set a warp here",
                "&7Adds a public warp at your", "&7current spot, anyone can use.",
                "&8Auto-named warp1, warp2, ...", "&8Custom name: &f/setwarp <name>"));
        inv.setItem(BACK_SLOT_54, menuItem(Material.ARROW, "&7↩ Back", "&7Return to the essentials menu."));
        applyBedrock(player, inv);
        player.openInventory(inv);
        pickupSound(player);
    }

    /** Collect owner UUIDs that currently have warps. */
    private List<String> warpOwners() {
        List<String> owners = new ArrayList<>();
        for (String key : warps.getKeys(false)) {
            ConfigurationSection sec = warps.getConfigurationSection(key + ".warps");
            if (sec != null && !sec.getKeys(false).isEmpty()) owners.add(key);
        }
        return owners;
    }

    private void fillWarpsPlayers(Inventory inv) {
        int size = inv.getSize();
        List<String> owners = warpOwners();
        int idx = 0;
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) continue;
            if (i == BACK_SLOT_54 || i == WARP_ADD_SLOT) continue;
            if (owners.isEmpty() && idx == 0) {
                inv.setItem(i, menuItem(Material.BARRIER, "&cNo warps yet",
                        "&7Be the first! Click the green", "&7dye below to set one here."));
                idx++;
                continue;
            }
            if (idx < owners.size()) {
                inv.setItem(i, warpOwnerHead(owners.get(idx++)));
            } else {
                inv.setItem(i, null);
            }
        }
    }

    /** A player head naming a warp owner, with the owner UUID stashed on it. */
    private ItemStack warpOwnerHead(String ownerUuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(ownerUuid));
        String stored = warps.getString(ownerUuid + ".name");
        String nm = (stored != null) ? stored : (op.getName() != null ? op.getName() : "someone");
        ConfigurationSection sec = warps.getConfigurationSection(ownerUuid + ".warps");
        int count = (sec != null) ? sec.getKeys(false).size() : 0;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(op);
            skull.setDisplayName(color("&b✦ " + nm));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7" + count + " warp" + (count == 1 ? "" : "s") + " set"));
            lore.add(color("&8Click to view their warps"));
            skull.setLore(lore);
            skull.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, ownerUuid);
            head.setItemMeta(skull);
        }
        return head;
    }

    private void onWarpsPlayersClick(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        if (event.getRawSlot() == BACK_SLOT_54) {
            cmdMenu(player);
            return;
        }
        if (event.getRawSlot() == WARP_ADD_SLOT) {
            String name = setWarpAuto(player);
            msg(player, "&dWarp &f" + name + " &dset at your spot! Anyone can &f/warp "
                    + name + "&d. (✧)");
            pickupSound(player);
            // Refresh so the player's own head now shows up.
            fillWarpsPlayers(event.getInventory());
            event.getInventory().setItem(WARP_ADD_SLOT, menuItem(Material.LIME_DYE, "&a✚ Set a warp here",
                    "&7Adds a public warp at your", "&7current spot, anyone can use.",
                    "&8Auto-named warp1, warp2, ...", "&8Custom name: &f/setwarp <name>"));
            applyBedrock(player, event.getInventory());
            player.updateInventory();
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String owner = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner == null) return;
        openWarpsList(player, owner);
    }

    /** Create a warp at the player's location with the first free auto name. */
    private String setWarpAuto(Player player) {
        String base = player.getUniqueId().toString();
        int n = 1;
        String name;
        do { name = "warp" + n; n++; } while (warps.isConfigurationSection(base + ".warps." + name));
        warps.set(base + ".name", player.getName());
        writeLocation(warps, base + ".warps." + name, player.getLocation());
        saveWarps();
        return name;
    }

    // ---- Warps GUI: one owner's warps (Kind.WARPS_LIST) ----

    private void openWarpsList(Player player, String ownerUuid) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.WARPS_LIST);
        holder.setContext(ownerUuid);
        String nm = warps.getString(ownerUuid + ".name", "Warps");
        Inventory inv = Bukkit.createInventory(holder, 54, titleFor(player, "&d✿ " + nm + "'s Warps ✿"));
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        fillWarpsList(player, ownerUuid, inv);
        inv.setItem(BACK_SLOT_54, menuItem(Material.ARROW, "&7↩ Back", "&7Back to the warp owners list."));
        applyBedrock(player, inv);
        player.openInventory(inv);
        pickupSound(player);
    }

    private void fillWarpsList(Player viewer, String ownerUuid, Inventory inv) {
        boolean own = ownerUuid.equals(viewer.getUniqueId().toString());
        int size = inv.getSize();
        List<String> names = new ArrayList<>();
        ConfigurationSection sec = warps.getConfigurationSection(ownerUuid + ".warps");
        if (sec != null) names.addAll(sec.getKeys(false));
        int idx = 0;
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) continue;
            if (i == BACK_SLOT_54) continue;
            if (names.isEmpty() && idx == 0) {
                inv.setItem(i, menuItem(Material.BARRIER, "&cNo warps here anymore"));
                idx++;
                continue;
            }
            if (idx < names.size()) {
                String warp = names.get(idx++);
                String path = ownerUuid + ".warps." + warp;
                boolean locked = warps.getBoolean(path + ".locked", false);
                Location loc = readLocation(warps, path);
                String coords = (loc == null) ? "&8(world not loaded)"
                        : "&7" + loc.getWorld().getName() + " &f"
                        + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ();
                List<String> lore = new ArrayList<>();
                lore.add(coords);
                lore.add(locked ? "&c● locked &7(owner only)" : "&a● public");
                if (locked && !own) {
                    lore.add("&8You can't use this warp");
                } else {
                    lore.add("&8Left-click to warp");
                }
                if (own) {
                    lore.add("&8Right-click: " + (locked ? "&aunlock" : "&6lock"));
                    lore.add("&8Shift-left: rename");
                    lore.add("&8Shift-right: delete");
                }
                ItemStack it = menuItem(locked ? Material.IRON_DOOR : Material.LODESTONE,
                        "&d✦ " + warp + (locked ? " &7(locked)" : ""),
                        lore.toArray(new String[0]));
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(warpKey, PersistentDataType.STRING, warp);
                    it.setItemMeta(meta);
                }
                inv.setItem(i, it);
            } else {
                inv.setItem(i, null);
            }
        }
    }

    private void onWarpsListClick(Player player, MenuHolder holder, org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        if (event.getRawSlot() == BACK_SLOT_54) {
            openWarpsPlayers(player);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String warp = meta.getPersistentDataContainer().get(warpKey, PersistentDataType.STRING);
        String owner = holder.getContext();
        if (warp == null || owner == null) return;
        boolean own = owner.equals(player.getUniqueId().toString());
        String path = owner + ".warps." + warp;
        boolean locked = warps.getBoolean(path + ".locked", false);

        // ---- owner-only management ----
        if (own && event.isShiftClick() && event.isLeftClick()) {       // rename
            startWarpRename(player, warp);
            return;
        }
        if (own && event.isShiftClick() && event.isRightClick()) {      // delete
            warps.set(path, null);
            ConfigurationSection left = warps.getConfigurationSection(owner + ".warps");
            if (left == null || left.getKeys(false).isEmpty()) {
                warps.set(owner, null);
                saveWarps();
                msg(player, "&dDeleted warp &f" + warp + "&d. That was your last one.");
                openWarpsPlayers(player);
                return;
            }
            saveWarps();
            msg(player, "&dDeleted warp &f" + warp + "&d.");
            fillWarpsList(player, owner, event.getInventory());
            applyBedrock(player, event.getInventory());
            player.updateInventory();
            return;
        }
        if (own && event.isRightClick()) {                              // lock / unlock
            warps.set(path + ".locked", !locked);
            saveWarps();
            msg(player, !locked
                    ? "&dWarp &f" + warp + " &dlocked &7(only you can use it)&d."
                    : "&dWarp &f" + warp + " &dunlocked &7(public)&d.");
            fillWarpsList(player, owner, event.getInventory());
            applyBedrock(player, event.getInventory());
            player.updateInventory();
            return;
        }

        // ---- teleport (respect locks) ----
        if (locked && !own) {
            msg(player, "&cThat warp is locked — only its owner can use it.");
            return;
        }
        Location loc = readLocation(warps, path);
        if (loc == null) {
            msg(player, "&cThat warp's world isn't loaded right now.");
            return;
        }
        player.closeInventory();
        safeTeleport(player, loc);
        msg(player, "&dWhoosh~ → warp &f" + warp + "&d ✨");
    }

    /** Begin a chat-driven rename of one of the player's own warps. */
    private void startWarpRename(Player player, String warp) {
        pendingWarpRename.put(player.getUniqueId(), warp);
        player.closeInventory();
        msg(player, "&dType the new name for warp &f" + warp
                + " &din chat &7(letters, numbers, _ — max 24)&d, or &fcancel&d.");
    }

    /** Apply a rename typed in chat (runs on the main thread). */
    private void applyWarpRename(Player p, String oldName, String input) {
        String base = p.getUniqueId().toString();
        if (input.equalsIgnoreCase("cancel")) {
            msg(p, "&7Rename cancelled.");
            openWarpsList(p, base);
            return;
        }
        String newName = input.toLowerCase();
        if (!validWarpName(newName)) {
            msg(p, "&cInvalid name — use letters, numbers and _ (max 24). Try again from the warp menu.");
            openWarpsList(p, base);
            return;
        }
        String oldPath = base + ".warps." + oldName;
        if (!warps.isConfigurationSection(oldPath)) {
            msg(p, "&cThat warp no longer exists.");
            openWarpsList(p, base);
            return;
        }
        String newPath = base + ".warps." + newName;
        if (warps.isConfigurationSection(newPath)) {
            msg(p, "&cYou already have a warp named &f" + newName + "&c.");
            openWarpsList(p, base);
            return;
        }
        // Copy every stored field (location + locked) to the new key, drop the old.
        for (String f : new String[]{ "world", "x", "y", "z", "yaw", "pitch", "locked" }) {
            if (warps.contains(oldPath + "." + f)) {
                warps.set(newPath + "." + f, warps.get(oldPath + "." + f));
            }
        }
        warps.set(oldPath, null);
        saveWarps();
        msg(p, "&dRenamed &f" + oldName + " &d→ &f" + newName + "&d.");
        openWarpsList(p, base);
    }

    @EventHandler
    public void onWarpRenameChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String oldName = pendingWarpRename.remove(id);
        if (oldName == null) return;
        event.setCancelled(true); // consume the line, don't broadcast it
        String input = event.getMessage().trim();
        Player p = event.getPlayer();
        // Chat fires async — touch config + GUIs back on the main thread.
        Bukkit.getScheduler().runTask(this, () -> applyWarpRename(p, oldName, input));
    }

    // ---- Back ----

    private boolean cmdBack(Player player) {
        Location death = readLocation(data, "deaths." + player.getUniqueId());
        if (death == null) {
            msg(player, "&cNo death recorded yet. Go... live a little? (✧)");
            return true;
        }
        safeTeleport(player, death);
        msg(player, "&dTaking you back to where you fell~");
        return true;
    }

    // ---- Kit ----

    private boolean cmdKit(Player player) {
        // The items in the starter kit.
        Material[] kitMaterials = {
            Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_BOOTS,
            Material.STONE_SWORD
        };

        // 1) Cooldown check.
        String cdPath = "kitCooldowns." + player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = data.getLong(cdPath, 0L);
        long elapsed = now - last;
        if (last > 0L && elapsed < KIT_COOLDOWN_MS) {
            long remainingMs = KIT_COOLDOWN_MS - elapsed;
            long minutes = remainingMs / 60_000L;
            long seconds = (remainingMs % 60_000L) / 1000L;
            msg(player, "&cKit on cooldown! Come back in &f" + minutes + "m " + seconds + "s&c.");
            return true;
        }

        // 2) Already-has-kit check (by material presence).
        boolean hasAll = true;
        for (Material mat : kitMaterials) {
            if (!player.getInventory().contains(mat)) {
                hasAll = false;
                break;
            }
        }
        if (hasAll) {
            msg(player, "&cYou still have your kit! Use it up first~");
            return true;
        }

        // 3) Give the kit.
        for (Material mat : kitMaterials) {
            player.getInventory().addItem(new ItemStack(mat));
        }
        data.set(cdPath, now);
        saveData();
        msg(player, "&dHere's your starter kit! Stay safe out there~ (✧)");
        pickupSound(player);
        return true;
    }

    // ---- Trash ----

    private boolean cmdTrash(Player player, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("undo")) {
            return trashUndo(player);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("filter")) {
            openTrashFilter(player);
            return true;
        }
        // Open a fresh 54-slot trash bin with our marker holder.
        TrashHolder holder = new TrashHolder();
        Inventory bin = Bukkit.createInventory(holder, 54,
                ChatColor.LIGHT_PURPLE + "Trash Bin (✧)");
        holder.setInventory(bin);
        player.openInventory(bin);
        msg(player, "&dDrop anything in to trash it. Closing empties the bin~ &7(/trash filter to clear by type)");
        return true;
    }

    // ---- Trash filter GUI ----

    /** Resolve the configured (or default) junk material list, skipping unknown names. */
    private List<Material> trashFilterMaterials() {
        List<String> names = getConfig().getStringList("trash-filter-items");
        if (names == null || names.isEmpty()) {
            names = DEFAULT_TRASH_FILTER_ITEMS;
        }
        List<Material> out = new ArrayList<>();
        for (String n : names) {
            if (n == null) continue;
            Material mat = Material.matchMaterial(n.trim().toUpperCase());
            if (mat != null && !out.contains(mat)) {
                out.add(mat);
            }
        }
        return out;
    }

    /** Open the animated filter GUI letting the player clear specific junk materials. */
    private void openTrashFilter(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.TRASH_FILTER);
        // Big enough to hold the border plus the material buttons.
        Inventory inv = Bukkit.createInventory(holder, 54, titleFor(player, "&d✿ Trash Filter ✿"));
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        fillTrashFilter(player, inv);
        applyBedrock(player, inv);
        player.openInventory(inv);
        pickupSound(player);
    }

    // Control slots inside the 54-slot trash filter (bottom interior row).
    private static final int TF_UNDO_SLOT = 48;
    private static final int TF_BACK_SLOT = 50;

    /** (Re)place the material buttons in the interior slots, with current counts in lore. */
    private void fillTrashFilter(Player player, Inventory inv) {
        int size = inv.getSize();
        List<Material> mats = trashFilterMaterials();
        int idx = 0;
        for (int i = 0; i < size && idx < mats.size(); i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) continue; // keep the perimeter for the shimmer
            if (i == TF_UNDO_SLOT || i == TF_BACK_SLOT) continue; // reserved for controls
            Material mat = mats.get(idx++);
            int have = countMaterial(player, mat);
            inv.setItem(i, menuItem(mat,
                    "&f" + prettyName(mat),
                    "&7You have: &f" + have,
                    have > 0 ? "&8Click to clear them all" : "&8(none to clear)"));
        }
        // Undo + Back controls.
        int pending = trashSnapshots.getOrDefault(player.getUniqueId(), Collections.emptyList()).size();
        inv.setItem(TF_UNDO_SLOT, menuItem(Material.LIME_DYE, "&aUndo last trash",
                "&7Restore the items you just cleared.",
                pending > 0 ? "&8" + pending + " stack(s) waiting" : "&8(nothing to undo)",
                "&8command: &f/trash undo"));
        inv.setItem(TF_BACK_SLOT, menuItem(Material.ARROW, "&7↩ Back",
                "&7Return to the essentials menu."));
    }

    /** Count how many of a material the player holds across their whole inventory. */
    private int countMaterial(Player player, Material mat) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private String prettyName(Material mat) {
        String[] parts = mat.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** Handle a click inside the trash filter GUI. */
    private void onTrashFilterClick(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        // Only react to clicks on the GUI itself.
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == TF_BACK_SLOT) {
            cmdMenu(player);
            return;
        }
        if (slot == TF_UNDO_SLOT) {
            trashUndo(player);
            fillTrashFilter(player, event.getInventory());
            player.updateInventory();
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        Material mat = clicked.getType();
        // Border panes and control buttons aren't clearable targets.
        for (Material pane : SHIMMER) {
            if (mat == pane) return;
        }
        if (mat == Material.LIME_DYE || mat == Material.ARROW) return;

        int removed = removeAllAndSnapshot(player, mat);
        if (removed <= 0) {
            msg(player, "&7You don't have any &f" + prettyName(mat) + "&7 to clear.");
        } else {
            msg(player, "&dCleared &f" + removed + "&d × &f" + prettyName(mat)
                    + "&d. &7(/trash undo to recover)");
            pickupSound(player);
        }
        // Refresh the displayed counts.
        fillTrashFilter(player, event.getInventory());
        player.updateInventory();
    }

    /**
     * Remove every stack of {@code mat} from the player's inventory and fold them into the
     * SAME undo snapshot used by the trash bin (so /trash undo returns them exactly once).
     * Returns the total item count removed.
     */
    private int removeAllAndSnapshot(Player player, Material mat) {
        List<ItemStack> removed = new ArrayList<>();
        int total = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == mat) {
                total += item.getAmount();
                removed.add(item.clone());
                player.getInventory().setItem(i, null);
            }
        }
        if (!removed.isEmpty()) {
            // Append to any existing snapshot so a single undo restores everything once.
            List<ItemStack> snapshot = trashSnapshots.computeIfAbsent(
                    player.getUniqueId(), k -> new ArrayList<>());
            snapshot.addAll(removed);
        }
        return total;
    }

    private boolean trashUndo(Player player) {
        List<ItemStack> snapshot = trashSnapshots.remove(player.getUniqueId());
        if (snapshot == null || snapshot.isEmpty()) {
            msg(player, "&cNothing to undo - your trash is empty.");
            return true;
        }
        // Return items; drop any overflow at the player's feet.
        for (ItemStack item : snapshot) {
            if (item == null) {
                continue;
            }
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        msg(player, "&dRestored your last trashed items! (✧)");
        pickupSound(player);
        return true;
    }

    // ============================================================
    //   EVENTS
    // ============================================================

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        writeLocation(data, "deaths." + victim.getUniqueId(), victim.getLocation());
        saveData();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof TrashHolder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        // Snapshot every non-null stack, then discard the bin contents.
        List<ItemStack> snapshot = new ArrayList<>();
        for (ItemStack item : inv.getContents()) {
            if (item != null) {
                snapshot.add(item.clone());
            }
        }
        inv.clear(); // items are trashed (not returned)
        if (!snapshot.isEmpty()) {
            trashSnapshots.put(player.getUniqueId(), snapshot);
            msg(player, "&dTrashed &f" + snapshot.size() + "&d stack(s). &7(/trash undo to recover)");
        }
    }
}
