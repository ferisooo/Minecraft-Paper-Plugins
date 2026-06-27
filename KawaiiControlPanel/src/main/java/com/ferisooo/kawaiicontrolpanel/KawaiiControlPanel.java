package com.ferisooo.kawaiicontrolpanel;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A chest-GUI hub for editing every other com.ferisooo ("Kawaii") plugin's
 * config live.
 *
 * <p>Menus are generated from each plugin's <em>live</em>
 * {@link Plugin#getConfig()} rather than from a hardcoded list of keys — so
 * the paths and current values are always correct, and every boolean/number
 * across every plugin is editable automatically (nested ones included, e.g.
 * KawaiiNoGrief's per-entity {@code block-damage.<mob>} flags).
 *
 * <p>Editing writes straight to the plugin's config and saves it, then
 * schedules a debounced soft-reload (disable + enable) so the change applies
 * without a server restart.
 */
public final class KawaiiControlPanel extends JavaPlugin implements Listener {

    private static final int PLUGIN_MENU_SIZE = 54;
    private static final int SETTINGS_PER_PAGE = 45; // top 5 rows; bottom row = nav

    /**
     * Each plugin's own reload command, dispatched (as console) to re-read +
     * re-apply its config after we edit it. This is the SAFE way to apply a
     * config change: it never touches the plugin's classloader.
     *
     * <p>We deliberately do NOT disable+enable the plugin. On modern Paper,
     * {@code PluginManager.disablePlugin()} closes the plugin's classloader
     * (its JAR), and {@code enablePlugin()} reuses the same closed loader, so
     * the plugin comes back "enabled" but throws "zip file closed" the next
     * time it lazy-loads a class. A config edit never needs a new classloader.
     */
    private static final Map<String, String> RELOAD_COMMANDS = Map.of(
            "KawaiiCompanion", "kc reload",
            "KawaiiEnderChest", "kec reload",
            "KawaiiMobChat", "kmcreload",
            "KawaiiNoGrief", "knogrief reload",
            "KawaiiRTP", "krtp reload",
            "KawaiiScoreboard", "kscoreboard reload",
            "KawaiiReload", "kreload self");

    private boolean autoReload;
    private long reloadDelayTicks;
    private final List<String> noAutoReload = new ArrayList<>();

    /** Pending debounced reload tasks, keyed by plugin name. */
    private final Map<String, ScheduledTask> pendingReloads = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KawaiiControlPanel enabled - /kpanel to open the control panel.");
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        autoReload = c.getBoolean("auto-reload", true);
        reloadDelayTicks = c.getLong("reload-delay-ticks", 40L);
        noAutoReload.clear();
        for (String s : c.getStringList("no-auto-reload")) noAutoReload.add(s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can open the control panel.");
            return true;
        }
        if (!p.hasPermission("kawaiicontrolpanel.admin")) {
            p.sendMessage(ChatColor.RED + "You don't have permission to use the control panel.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            p.sendMessage(ChatColor.LIGHT_PURPLE + "✿ Control panel settings reloaded.");
            return true;
        }
        openHub(p);
        return true;
    }

    // ---------------------------------------------------------------
    // Hub menu — one icon per configurable plugin.
    // ---------------------------------------------------------------

    private void openHub(Player p) {
        List<Plugin> plugins = configurablePlugins();
        int rows = Math.max(1, Math.min(6, (plugins.size() + 8) / 9));
        PanelHolder holder = new PanelHolder("HUB", null, 0);
        Inventory inv = Bukkit.createInventory(holder, rows * 9,
                ChatColor.LIGHT_PURPLE + "✿ Kawaii Control Panel ✿");
        holder.inv = inv;

        if (plugins.isEmpty()) {
            inv.setItem(4, item(Material.BARRIER, ChatColor.RED + "No configurable plugins found",
                    List.of(ChatColor.GRAY + "Nothing with an editable config.yml is loaded.")));
        } else {
            int slot = 0;
            for (Plugin pl : plugins) {
                int count = editableLeafCount(pl.getConfig());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "v" + pl.getDescription().getVersion());
                lore.add(ChatColor.GRAY + "" + count + " setting" + (count == 1 ? "" : "s"));
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to edit");
                inv.setItem(slot, item(iconFor(pl.getName()),
                        ChatColor.AQUA + "" + ChatColor.BOLD + pl.getName(), lore));
                holder.actions.put(slot, "open:" + pl.getName());
                slot++;
                if (slot >= inv.getSize()) break;
            }
        }
        p.openInventory(inv);
    }

    /** All enabled com.ferisooo plugins (except us) that have at least one config leaf. */
    private List<Plugin> configurablePlugins() {
        List<Plugin> out = new ArrayList<>();
        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            if (pl == this) continue;
            if (!pl.isEnabled()) continue;
            String main = pl.getDescription().getMain();
            if (main == null || !main.startsWith("com.ferisooo")) continue;
            if (allLeaves(pl.getConfig()).isEmpty()) continue;
            out.add(pl);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    // ---------------------------------------------------------------
    // Per-plugin settings menu.
    // ---------------------------------------------------------------

    private void openPlugin(Player p, String pluginName, int page) {
        PanelHolder holder = new PanelHolder("PLUGIN", pluginName, Math.max(0, page));
        holder.inv = Bukkit.createInventory(holder, PLUGIN_MENU_SIZE,
                ChatColor.LIGHT_PURPLE + "✿ " + pluginName + " ✿");
        populatePlugin(holder);
        p.openInventory(holder.inv);
    }

    /** (Re)build a plugin menu's contents + action map in place. */
    private void populatePlugin(PanelHolder holder) {
        Inventory inv = holder.inv;
        inv.clear();
        holder.actions.clear();

        Plugin target = Bukkit.getPluginManager().getPlugin(holder.pluginName);
        if (target == null) {
            inv.setItem(22, item(Material.BARRIER, ChatColor.RED + "Plugin not loaded",
                    List.of(ChatColor.GRAY + holder.pluginName + " is no longer enabled.")));
            holder.actions.put(49, "nav:hub");
            inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "Back", List.of()));
            return;
        }

        FileConfiguration cfg = target.getConfig();
        List<String> leaves = allLeaves(cfg);
        int maxPage = Math.max(0, (leaves.size() - 1) / SETTINGS_PER_PAGE);
        int page = Math.min(holder.page, maxPage);
        holder.page = page;

        int from = page * SETTINGS_PER_PAGE;
        int to = Math.min(leaves.size(), from + SETTINGS_PER_PAGE);
        int slot = 0;
        for (int i = from; i < to; i++) {
            String path = leaves.get(i);
            Object v = cfg.get(path);
            inv.setItem(slot, leafItem(path, v));
            if (v instanceof Boolean || v instanceof Number) {
                holder.actions.put(slot, "leaf:" + path);
            }
            slot++;
        }

        // Bottom navigation row.
        if (page > 0) {
            inv.setItem(45, item(Material.ARROW, ChatColor.YELLOW + "← Previous page", List.of()));
            holder.actions.put(45, "nav:prev");
        }
        inv.setItem(48, item(Material.PAPER,
                ChatColor.GRAY + "Page " + (page + 1) + " / " + (maxPage + 1),
                List.of(ChatColor.DARK_GRAY + holder.pluginName)));
        inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "Back to menu", List.of()));
        holder.actions.put(49, "nav:hub");
        boolean excluded = noAutoReload.contains(holder.pluginName) || !autoReload;
        inv.setItem(50, item(excluded ? Material.GRAY_DYE : Material.LIME_DYE,
                ChatColor.GRAY + "Live reload: "
                        + (excluded ? ChatColor.RED + "off" : ChatColor.GREEN + "on"),
                excluded
                        ? List.of(ChatColor.DARK_GRAY + "Edits are saved; reload manually.")
                        : List.of(ChatColor.DARK_GRAY + "Edits apply automatically.")));
        if (page < maxPage) {
            inv.setItem(53, item(Material.ARROW, ChatColor.YELLOW + "Next page →", List.of()));
            holder.actions.put(53, "nav:next");
        }
    }

    private ItemStack leafItem(String path, Object v) {
        String shortName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + path);
        Material mat;
        if (v instanceof Boolean b) {
            mat = b ? Material.LIME_DYE : Material.GRAY_DYE;
            lore.add(ChatColor.GRAY + "Current: " + (b ? ChatColor.GREEN + "true" : ChatColor.RED + "false"));
            lore.add(ChatColor.YELLOW + "Click to toggle");
        } else if (v instanceof Double || v instanceof Float) {
            mat = Material.COMPARATOR;
            lore.add(ChatColor.GRAY + "Current: " + ChatColor.YELLOW + v);
            lore.add(ChatColor.GREEN + "Left +0.1  " + ChatColor.RED + "Right -0.1");
            lore.add(ChatColor.GRAY + "Hold Shift = ×10");
        } else if (v instanceof Number) {
            mat = Material.COMPARATOR;
            lore.add(ChatColor.GRAY + "Current: " + ChatColor.YELLOW + v);
            lore.add(ChatColor.GREEN + "Left +1  " + ChatColor.RED + "Right -1");
            lore.add(ChatColor.GRAY + "Hold Shift = ×10");
        } else if (v instanceof String s) {
            mat = Material.NAME_TAG;
            String disp = isSecret(path) ? ChatColor.DARK_GRAY + "••••••"
                    : ChatColor.WHITE + truncate(s);
            lore.add(ChatColor.GRAY + "Current: " + disp);
            lore.add(ChatColor.DARK_GRAY + "(text — edit in config.yml)");
        } else if (v instanceof List<?> list) {
            mat = Material.BOOK;
            lore.add(ChatColor.GRAY + "List: " + list.size() + " entr" + (list.size() == 1 ? "y" : "ies"));
            lore.add(ChatColor.DARK_GRAY + "(edit in config.yml)");
        } else {
            mat = Material.PAPER;
            lore.add(ChatColor.GRAY + "Current: " + ChatColor.WHITE + truncate(String.valueOf(v)));
            lore.add(ChatColor.DARK_GRAY + "(read-only)");
        }
        return item(mat, ChatColor.WHITE + pretty(shortName), lore);
    }

    // ---------------------------------------------------------------
    // Editing.
    // ---------------------------------------------------------------

    private void editLeaf(Player p, PanelHolder holder, String path, ClickType click) {
        Plugin target = Bukkit.getPluginManager().getPlugin(holder.pluginName);
        if (target == null) {
            p.sendMessage(ChatColor.RED + holder.pluginName + " is no longer loaded.");
            return;
        }
        FileConfiguration cfg = target.getConfig();
        Object v = cfg.get(path);
        if (v instanceof Boolean b) {
            cfg.set(path, !b);
        } else if (v instanceof Integer i) {
            cfg.set(path, i + (int) numDelta(click, 1));
        } else if (v instanceof Long l) {
            cfg.set(path, l + (long) numDelta(click, 1));
        } else if (v instanceof Double d) {
            cfg.set(path, round(d + numDelta(click, 0.1)));
        } else if (v instanceof Float f) {
            cfg.set(path, (float) round(f + numDelta(click, 0.1)));
        } else {
            p.sendMessage(ChatColor.GRAY + "That value can only be edited in config.yml.");
            return;
        }
        target.saveConfig();
        scheduleReload(holder.pluginName);
        populatePlugin(holder);
        p.updateInventory();
    }

    private static double numDelta(ClickType c, double base) {
        return switch (c) {
            case LEFT, DOUBLE_CLICK -> base;
            case RIGHT -> -base;
            case SHIFT_LEFT -> base * 10;
            case SHIFT_RIGHT -> -base * 10;
            default -> 0.0;
        };
    }

    // ---------------------------------------------------------------
    // Debounced soft-reload.
    // ---------------------------------------------------------------

    private void scheduleReload(String pluginName) {
        if (!autoReload || noAutoReload.contains(pluginName)) return;
        ScheduledTask old = pendingReloads.remove(pluginName);
        if (old != null) old.cancel();
        // Reloading a plugin's config / dispatching its console reload command is
        // global/world-wide state with no direct cross-region entity mutation, so
        // route the debounced task onto the global region scheduler (Folia-safe).
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
            pendingReloads.remove(pluginName);
            applyReload(pluginName);
        }, Math.max(1L, reloadDelayTicks));
        pendingReloads.put(pluginName, task);
    }

    /**
     * Make a saved config change take effect, without ever disabling the
     * plugin (which would close its classloader). Prefer the plugin's own
     * reload command — it re-reads the file AND re-applies cached values.
     * If it has none, fall back to {@link Plugin#reloadConfig()}, which is
     * still safe but may need the plugin's own reload (or a restart) to
     * fully re-apply field-cached settings.
     */
    private void applyReload(String pluginName) {
        Plugin pl = Bukkit.getPluginManager().getPlugin(pluginName);
        if (pl == null || !pl.isEnabled()) return;
        try {
            String cmd = RELOAD_COMMANDS.get(pluginName);
            if (cmd != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                getLogger().info("Applied config to " + pluginName + " via '/" + cmd + "'.");
            } else {
                pl.reloadConfig();
                getLogger().info("Re-read " + pluginName + " config (no reload command; "
                        + "restart it if the change didn't apply).");
            }
        } catch (Throwable t) {
            getLogger().warning("Applying config to " + pluginName + " failed: " + t
                    + " — the config was still saved; reload it manually.");
        }
    }

    @Override
    public void onDisable() {
        for (ScheduledTask t : pendingReloads.values()) t.cancel();
        pendingReloads.clear();
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
    }

    // ---------------------------------------------------------------
    // Click / drag handling.
    // ---------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof PanelHolder holder)) return;
        e.setCancelled(true); // our menus are read/click-only; never move items
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return; // clicked own inventory
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String action = holder.actions.get(raw);
        if (action == null) return;
        if (action.startsWith("open:")) {
            openPlugin(p, action.substring(5), 0);
        } else if (action.equals("nav:hub")) {
            openHub(p);
        } else if (action.equals("nav:prev")) {
            openPlugin(p, holder.pluginName, holder.page - 1);
        } else if (action.equals("nav:next")) {
            openPlugin(p, holder.pluginName, holder.page + 1);
        } else if (action.startsWith("leaf:")) {
            editLeaf(p, holder, action.substring(5), e.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PanelHolder) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------

    /** Every leaf (non-section) config path, in file order. */
    private static List<String> allLeaves(FileConfiguration cfg) {
        List<String> out = new ArrayList<>();
        for (String key : cfg.getKeys(true)) {
            if (cfg.isConfigurationSection(key)) continue;
            out.add(key);
        }
        return out;
    }

    private static int editableLeafCount(FileConfiguration cfg) {
        int n = 0;
        for (String key : cfg.getKeys(true)) {
            if (cfg.isConfigurationSection(key)) continue;
            Object v = cfg.get(key);
            if (v instanceof Boolean || v instanceof Number) n++;
        }
        return n;
    }

    private static boolean isSecret(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("key") || p.contains("token") || p.contains("secret")
                || p.contains("password") || p.contains("webhook");
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        s = s.replace('\n', ' ');
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }

    private static String pretty(String key) {
        String s = key.replace('-', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) return key;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static double round(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }

    private static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(new ArrayList<>(lore));
            is.setItemMeta(meta);
        }
        return is;
    }

    private static Material iconFor(String pluginName) {
        return switch (pluginName) {
            case "KawaiiCompanion"  -> Material.PLAYER_HEAD;
            case "KawaiiEnderChest" -> Material.ENDER_CHEST;
            case "KawaiiLogger"     -> Material.WRITABLE_BOOK;
            case "KawaiiMobChat"    -> Material.OAK_SIGN;
            case "KawaiiNoGrief"    -> Material.OBSIDIAN;
            case "KawaiiRTP"        -> Material.ENDER_PEARL;
            case "KawaiiReload"     -> Material.CLOCK;
            case "KawaiiScoreboard" -> Material.OAK_HANGING_SIGN;
            case "KawaiiWorlds"     -> Material.GRASS_BLOCK;
            default                  -> Material.BOOK;
        };
    }

    /** Identifies our inventories and carries the per-slot click actions. */
    private static final class PanelHolder implements InventoryHolder {
        final String menu;          // "HUB" or "PLUGIN"
        final String pluginName;    // null for HUB
        int page;
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inv;

        PanelHolder(String menu, String pluginName, int page) {
            this.menu = menu;
            this.pluginName = pluginName;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
