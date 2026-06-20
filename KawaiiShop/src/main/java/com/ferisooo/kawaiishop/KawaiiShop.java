package com.ferisooo.kawaiishop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KawaiiShop — a buy/sell shop that ONLY opens on a player's skyblock island.
 *
 * <p>It ships its own tiny coin economy (no other plugin needed): you start
 * with a configurable balance, earn coins by selling the things you can farm
 * or grind on your island, and spend them on materials an island can't easily
 * produce (sand, ores, obsidian, sponge…). Sell prices are always below buy
 * prices, so there's no spread to farm. Prices are tuned by rarity / how hard
 * an item is to obtain.
 *
 * <p>To keep the island economy sealed off from the normal world, items that
 * can ferry contents across worlds — chiefly the ender chest, whose inventory
 * is shared everywhere — can't be opened or placed while you're on an island.
 */
public final class KawaiiShop extends JavaPlugin implements Listener {

    /** One catalogue line: a buy price and a sell price (−1 == disabled). */
    private static final class Entry {
        final Material mat;
        final long buy;
        final long sell;
        Entry(Material mat, long buy, long sell) { this.mat = mat; this.buy = buy; this.sell = sell; }
    }

    /** A named, icon'd group of catalogue items shown on its own page set. */
    private static final class ShopCategory {
        final String name;
        final Material icon;
        final List<Entry> items = new ArrayList<>();
        ShopCategory(String name, Material icon) { this.name = name; this.icon = icon; }
    }

    /** Which kind of shop screen a holder is. */
    private enum Screen { CATEGORIES, ITEMS }

    /** Marks our shop inventories and remembers what they're showing. */
    private static final class ShopHolder implements InventoryHolder {
        private Inventory inv;
        final Screen screen;
        final int categoryIndex; // valid for ITEMS
        final int page;          // valid for ITEMS
        ShopHolder(Screen screen, int categoryIndex, int page) {
            this.screen = screen; this.categoryIndex = categoryIndex; this.page = page;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    // Interior slots (rows 1-4, columns 1-7): 28 per page for items / categories.
    private static final int[] ITEM_SLOTS = buildItemSlots();
    private static final int PER_PAGE = ITEM_SLOTS.length;
    private static final int SLOT_PREV = 45, SLOT_BACK = 48, SLOT_BAL = 49, SLOT_NEXT = 53;
    private static final Material BORDER_PANE = Material.PINK_STAINED_GLASS_PANE;

    private static int[] buildItemSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        int[] out = new int[slots.size()];
        for (int i = 0; i < out.length; i++) out[i] = slots.get(i);
        return out;
    }

    // ---- config-backed ----
    private String worldPrefix;
    private long startingBalance;
    private String currency;
    private final Set<Material> banned = EnumSet.noneOf(Material.class);
    private final List<ShopCategory> categories = new ArrayList<>();

    // ---- economy store ----
    private File moneyFile;
    private YamlConfiguration money;
    private final Map<UUID, Long> balances = new java.util.HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KawaiiShop enabled - " + categories.size() + " categories, "
                + itemCount() + " items, skyblock-only.");
    }

    private int itemCount() {
        int n = 0;
        for (ShopCategory c : categories) n += c.items.size();
        return n;
    }

    @Override
    public void onDisable() {
        saveMoney();
    }

    private void loadAll() {
        reloadConfig();
        worldPrefix = getConfig().getString("skyblock-world-prefix", "kawaii_isle_");
        startingBalance = getConfig().getLong("starting-balance", 100);
        currency = getConfig().getString("currency-symbol", "✦");

        banned.clear();
        for (String s : getConfig().getStringList("banned-in-skyblock")) {
            Material m = Material.matchMaterial(s.trim().toUpperCase());
            if (m != null) banned.add(m);
        }

        categories.clear();
        ConfigurationSection shop = getConfig().getConfigurationSection("shop");
        if (shop != null) {
            for (String catKey : shop.getKeys(false)) {
                ConfigurationSection cs = shop.getConfigurationSection(catKey);
                if (cs == null) continue;
                String name = cs.getString("name", catKey);
                Material icon = Material.matchMaterial(
                        cs.getString("icon", "CHEST").trim().toUpperCase());
                if (icon == null) icon = Material.CHEST;
                ShopCategory cat = new ShopCategory(name, icon);
                ConfigurationSection items = cs.getConfigurationSection("items");
                if (items != null) {
                    for (String key : items.getKeys(false)) {
                        Material m = Material.matchMaterial(key.trim().toUpperCase());
                        if (m == null) { getLogger().warning("Unknown shop material: " + key); continue; }
                        long buy = items.getLong(key + ".buy", -1);
                        long sell = items.getLong(key + ".sell", -1);
                        if (buy < 0 && sell < 0) continue; // nothing to do with it
                        cat.items.add(new Entry(m, buy, sell));
                    }
                }
                if (!cat.items.isEmpty()) categories.add(cat);
            }
        }
        // Bulletproofing: if the config's shop section is missing, empty, or
        // unparseable (e.g. an old broken config.yml already on disk that
        // saveDefaultConfig() won't overwrite), fall back to a built-in
        // catalogue so the shop is NEVER empty.
        if (categories.isEmpty()) {
            getLogger().warning("No shop items loaded from config — using built-in defaults. "
                    + "Delete plugins/KawaiiShop/config.yml to regenerate the editable one.");
            loadDefaultCatalogue();
        }

        moneyFile = new File(getDataFolder(), "money.yml");
        if (!moneyFile.exists()) {
            try { getDataFolder().mkdirs(); moneyFile.createNewFile(); }
            catch (IOException e) { getLogger().warning("Could not create money.yml: " + e.getMessage()); }
        }
        money = YamlConfiguration.loadConfiguration(moneyFile);
        balances.clear();
    }

    /** Built-in catalogue, used when config.yml has no usable shop section. */
    private void loadDefaultCatalogue() {
        categories.clear();
        ShopCategory blocks = new ShopCategory("&aBlocks", Material.GRASS_BLOCK);
        addDef(blocks, "DIRT", 2, 1); addDef(blocks, "COBBLESTONE", 2, 1);
        addDef(blocks, "NETHERRACK", 3, 1); addDef(blocks, "GRAVEL", 4, 1);
        addDef(blocks, "SAND", 6, 2); addDef(blocks, "RED_SAND", 8, 2);
        addDef(blocks, "CLAY_BALL", 6, 2); addDef(blocks, "GLASS", 6, 2);
        addDef(blocks, "OAK_LOG", 8, 3); addDef(blocks, "OAK_SAPLING", 20, 5);
        addDef(blocks, "ICE", 5, 2); addDef(blocks, "OBSIDIAN", 100, 40);
        addDef(blocks, "SPONGE", 250, 100); addDef(blocks, "PACKED_ICE", 40, 16);
        addDef(blocks, "BLUE_ICE", 300, 120); addDef(blocks, "SOUL_SAND", 25, 10);
        if (!blocks.items.isEmpty()) categories.add(blocks);

        ShopCategory ores = new ShopCategory("&bOres & Minerals", Material.IRON_INGOT);
        addDef(ores, "COAL", 15, 6); addDef(ores, "CHARCOAL", 15, 6);
        addDef(ores, "RAW_COPPER", 18, 7); addDef(ores, "COPPER_INGOT", 30, 12);
        addDef(ores, "RAW_IRON", 40, 16); addDef(ores, "IRON_INGOT", 60, 24);
        addDef(ores, "RAW_GOLD", 55, 22); addDef(ores, "GOLD_INGOT", 80, 32);
        addDef(ores, "REDSTONE", 20, 8); addDef(ores, "LAPIS_LAZULI", 25, 10);
        addDef(ores, "QUARTZ", 20, 8); addDef(ores, "GLOWSTONE_DUST", 30, 12);
        addDef(ores, "AMETHYST_SHARD", 45, 18); addDef(ores, "EMERALD", 120, 48);
        addDef(ores, "DIAMOND", 300, 120); addDef(ores, "ANCIENT_DEBRIS", -1, 1200);
        addDef(ores, "NETHERITE_SCRAP", -1, 1400); addDef(ores, "NETHERITE_INGOT", 5000, 2000);
        if (!ores.items.isEmpty()) categories.add(ores);

        ShopCategory farming = new ShopCategory("&eFarming", Material.WHEAT);
        addDef(farming, "WHEAT_SEEDS", 4, 2); addDef(farming, "WHEAT", 6, 3);
        addDef(farming, "CARROT", 8, 3); addDef(farming, "POTATO", 8, 3);
        addDef(farming, "SUGAR_CANE", 10, 4); addDef(farming, "BAMBOO", 8, 3);
        addDef(farming, "CACTUS", 12, 4); addDef(farming, "MELON_SLICE", 4, 1);
        addDef(farming, "PUMPKIN", 12, 5); addDef(farming, "BREAD", 14, 5);
        addDef(farming, "NETHER_WART", 30, 12); addDef(farming, "HONEYCOMB", 30, 12);
        if (!farming.items.isEmpty()) categories.add(farming);

        ShopCategory drops = new ShopCategory("&dMob Drops", Material.ROTTEN_FLESH);
        addDef(drops, "STRING", 12, 5); addDef(drops, "BONE", 10, 4);
        addDef(drops, "ROTTEN_FLESH", 4, 1); addDef(drops, "SPIDER_EYE", 16, 6);
        addDef(drops, "GUNPOWDER", 40, 16); addDef(drops, "SLIME_BALL", 60, 24);
        addDef(drops, "ENDER_PEARL", 150, 50); addDef(drops, "BLAZE_ROD", 200, 70);
        addDef(drops, "GHAST_TEAR", -1, 250); addDef(drops, "LEATHER", 25, 10);
        addDef(drops, "FEATHER", 12, 5); addDef(drops, "EGG", 10, 4);
        addDef(drops, "INK_SAC", 14, 5);
        if (!drops.items.isEmpty()) categories.add(drops);
    }

    private void addDef(ShopCategory cat, String mat, long buy, long sell) {
        Material m = Material.matchMaterial(mat);
        if (m != null) cat.items.add(new Entry(m, buy, sell));
    }

    private void saveMoney() {
        try { money.save(moneyFile); }
        catch (IOException e) { getLogger().warning("Could not save money.yml: " + e.getMessage()); }
    }

    // ============================================================ economy

    private long getBalance(UUID id) {
        Long cached = balances.get(id);
        if (cached != null) return cached;
        long v = money.contains(id.toString()) ? money.getLong(id.toString()) : startingBalance;
        balances.put(id, v);
        return v;
    }

    private void setBalance(UUID id, long v) {
        if (v < 0) v = 0;
        balances.put(id, v);
        money.set(id.toString(), v);
        saveMoney();
    }

    private void addBalance(UUID id, long delta) {
        setBalance(id, getBalance(id) + delta);
    }

    // ============================================================ helpers

    private boolean isSkyblock(World w) {
        return w != null && worldPrefix != null && w.getName().startsWith(worldPrefix);
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void msg(CommandSender to, String s) {
        String c = color(s);
        if (to instanceof Player p && isBedrock(p.getUniqueId())) c = bedrockText(c);
        to.sendMessage(c);
    }

    /** A title coloured for {@code p}, sanitised if they're on Bedrock. */
    private String titleFor(Player p, String rawAmp) {
        String c = color(rawAmp);
        return isBedrock(p.getUniqueId()) ? bedrockText(c) : c;
    }

    // Detect Bedrock (Geyser) players via the Floodgate API by reflection, so
    // we never hard-depend on Floodgate. When it's absent everyone is Java.
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

    /** Replace glyphs Bedrock's font can't render with plain ASCII look-alikes. */
    static String bedrockText(String s) {
        if (s == null) return null;
        String t = s
                .replace("✿", "*").replace("✦", "*").replace("✧", "*").replace("✈", ">")
                .replace("✚", "+").replace("▶", ">").replace("◀", "<").replace("↩", "<")
                .replace("→", "->").replace("←", "<-").replace("━", "-").replace("─", "-")
                .replace("×", "x").replace("♥", "<3")
                .replace("🏠", "").replace("🛏", "").replace("⚙", "").replace("🗑", "")
                .replace("🏝", "").replace("✨", "").replace("💧", "");
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.trim();
    }

    /** For Bedrock viewers, rewrite every item name/lore in an inventory to ASCII-safe text. */
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

    private String coins(long amount) { return amount + " " + currency; }

    private String prettyName(Material m) {
        String[] parts = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(color(s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private int countItems(Player p, Material mat) {
        int total = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == mat) total += it.getAmount();
        }
        return total;
    }

    /** Remove up to {@code max} of {@code mat} from the player. Returns how many were removed. */
    private int removeItems(Player p, Material mat, int max) {
        int removed = 0;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && removed < max; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(it.getAmount(), max - removed);
            removed += take;
            if (take >= it.getAmount()) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - take);
        }
        return removed;
    }

    // ============================================================ GUI

    private void paintFrame(Inventory inv) {
        for (int i = 0; i < 54; i++) {
            boolean edge = i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8;
            if (edge) inv.setItem(i, item(BORDER_PANE, " ", null));
        }
    }

    /** The top-level menu: one button per category. */
    private void openCategories(Player player) {
        ShopHolder holder = new ShopHolder(Screen.CATEGORIES, -1, 0);
        Inventory inv = Bukkit.createInventory(holder, 54, titleFor(player, "&d✿ Island Shop ✿"));
        holder.inv = inv;
        paintFrame(inv);

        for (int s = 0; s < PER_PAGE && s < categories.size(); s++) {
            ShopCategory cat = categories.get(s);
            List<String> lore = new ArrayList<>();
            lore.add("&7" + cat.items.size() + " items");
            lore.add("&8Click to browse");
            inv.setItem(ITEM_SLOTS[s], item(cat.icon, "&f" + cat.name, lore));
        }

        List<String> info = new ArrayList<>();
        info.add("&7Pick a category, then:");
        info.add("&7Left-click an item to &abuy&7,");
        info.add("&7right-click to &esell&7.");
        info.add("&7Hold &fShift &7for a stack of 64.");
        inv.setItem(SLOT_BACK, item(Material.BOOK, "&b✦ How it works", info));
        inv.setItem(SLOT_BAL, balanceItem(player));

        applyBedrock(player, inv);
        player.openInventory(inv);
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1f);
    }

    /** One category's items, paginated. */
    private void openItems(Player player, int catIndex, int page) {
        if (catIndex < 0 || catIndex >= categories.size()) { openCategories(player); return; }
        ShopCategory cat = categories.get(catIndex);
        int pages = Math.max(1, (cat.items.size() + PER_PAGE - 1) / PER_PAGE);
        if (page < 0) page = 0;
        if (page >= pages) page = pages - 1;

        ShopHolder holder = new ShopHolder(Screen.ITEMS, catIndex, page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                titleFor(player, "&d✿ " + cat.name + " ✿ &8(" + (page + 1) + "/" + pages + ")"));
        holder.inv = inv;
        paintFrame(inv);

        int start = page * PER_PAGE;
        for (int s = 0; s < PER_PAGE; s++) {
            int idx = start + s;
            if (idx >= cat.items.size()) break;
            inv.setItem(ITEM_SLOTS[s], shopItem(cat.items.get(idx)));
        }

        if (page > 0) inv.setItem(SLOT_PREV, item(Material.ARROW, "&e◀ Previous page", null));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, item(Material.ARROW, "&eNext page ▶", null));
        inv.setItem(SLOT_BACK, item(Material.BARRIER, "&c↩ Categories", java.util.List.of("&7Back to the category menu.")));
        inv.setItem(SLOT_BAL, balanceItem(player));

        applyBedrock(player, inv);
        player.openInventory(inv);
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1f);
    }

    private ItemStack shopItem(Entry e) {
        List<String> lore = new ArrayList<>();
        lore.add(e.buy >= 0 ? "&7Buy: &a" + coins(e.buy) : "&8Not for sale");
        lore.add(e.sell >= 0 ? "&7Sell: &e" + coins(e.sell) : "&8Can't be sold");
        lore.add("&8━━━━━━━━");
        if (e.buy >= 0) {
            lore.add("&aLeft-click&7: buy 1");
            lore.add("&aShift-left&7: buy 64");
        }
        if (e.sell >= 0) {
            lore.add("&eRight-click&7: sell 1");
            lore.add("&eShift-right&7: sell 64");
        }
        return item(e.mat, "&f" + prettyName(e.mat), lore);
    }

    private ItemStack balanceItem(Player p) {
        List<String> lore = new ArrayList<>();
        lore.add("&7You have &a" + coins(getBalance(p.getUniqueId())));
        lore.add("&8Sell things you grind, buy what");
        lore.add("&8your island can't make.");
        return item(Material.EMERALD, "&a✦ Your coins", lore);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof ShopHolder)) {
            return; // clicked their own inventory
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_BAL) return;

        // Which catalogue/category slot (if any) was clicked?
        int pos = -1;
        for (int s = 0; s < ITEM_SLOTS.length; s++) {
            if (ITEM_SLOTS[s] == slot) { pos = s; break; }
        }

        if (holder.screen == Screen.CATEGORIES) {
            if (slot == SLOT_BACK) return; // info book
            if (pos >= 0 && pos < categories.size()) openItems(player, pos, 0);
            return;
        }

        // Screen.ITEMS
        if (slot == SLOT_BACK) { openCategories(player); return; }
        if (slot == SLOT_PREV) { openItems(player, holder.categoryIndex, holder.page - 1); return; }
        if (slot == SLOT_NEXT) { openItems(player, holder.categoryIndex, holder.page + 1); return; }
        if (pos < 0) return;
        ShopCategory cat = categories.get(holder.categoryIndex);
        int idx = holder.page * PER_PAGE + pos;
        if (idx < 0 || idx >= cat.items.size()) return;
        Entry e = cat.items.get(idx);

        boolean shift = event.isShiftClick();
        if (event.isLeftClick()) {
            buy(player, e, shift ? 64 : 1);
        } else if (event.isRightClick()) {
            sell(player, e, shift ? 64 : 1);
        }
        // Refresh the coin counter in place (no reopen / sound spam).
        event.getInventory().setItem(SLOT_BAL, balanceItem(player));
        applyBedrock(player, event.getInventory());
        player.updateInventory();
    }

    private void buy(Player player, Entry e, int amount) {
        if (e.buy < 0) { msg(player, "&c" + prettyName(e.mat) + " isn't for sale."); return; }
        long bal = getBalance(player.getUniqueId());
        long affordable = e.buy == 0 ? amount : Math.min(amount, bal / e.buy);
        if (affordable <= 0) {
            msg(player, "&cYou need &f" + coins(e.buy) + "&c for one &f" + prettyName(e.mat)
                    + "&c. You have &f" + coins(bal) + "&c.");
            return;
        }
        long cost = affordable * e.buy;
        addBalance(player.getUniqueId(), -cost);
        // Give the items; drop anything that doesn't fit at their feet.
        ItemStack stack = new ItemStack(e.mat, (int) affordable);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        for (ItemStack left : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        msg(player, "&aBought &f" + affordable + "× " + prettyName(e.mat)
                + " &afor &f" + coins(cost) + "&a. Balance: &f"
                + coins(getBalance(player.getUniqueId())));
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1.2f);
    }

    private void sell(Player player, Entry e, int amount) {
        if (e.sell < 0) { msg(player, "&c" + prettyName(e.mat) + " can't be sold here."); return; }
        int have = countItems(player, e.mat);
        int toSell = Math.min(amount, have);
        if (toSell <= 0) {
            msg(player, "&cYou don't have any &f" + prettyName(e.mat) + "&c to sell.");
            return;
        }
        int removed = removeItems(player, e.mat, toSell);
        long gain = (long) removed * e.sell;
        addBalance(player.getUniqueId(), gain);
        msg(player, "&eSold &f" + removed + "× " + prettyName(e.mat)
                + " &efor &f" + coins(gain) + "&e. Balance: &f"
                + coins(getBalance(player.getUniqueId())));
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 0.8f);
    }

    // ============================================================ bans

    /** Stop the ender chest (shared cross-world storage) from opening on an island. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!banned.contains(Material.ENDER_CHEST)) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!isSkyblock(p.getWorld())) return;
        if (p.hasPermission("kawaiishop.admin")) return;
        event.setCancelled(true);
        msg(p, "&c(✧) ender chests are sealed on the island - no smuggling loot to the overworld~");
        p.playSound(p.getLocation(), "minecraft:block.note_block.bass", 1f, 0.6f);
    }

    /** Block placing any banned (cross-world) item while on an island. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (!isSkyblock(p.getWorld())) return;
        if (p.hasPermission("kawaiishop.admin")) return;
        if (!banned.contains(event.getBlockPlaced().getType())) return;
        event.setCancelled(true);
        msg(p, "&c(✧) you can't place &f" + prettyName(event.getBlockPlaced().getType())
                + " &con the island~");
    }

    // ============================================================ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("kshopadmin")) {
            return cmdAdmin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            msg(sender, "&dThis command is for players only ~");
            return true;
        }
        if (name.equals("balance")) {
            msg(player, "&a✦ You have &f" + coins(getBalance(player.getUniqueId())) + "&a.");
            return true;
        }
        // /kshop
        if (!isSkyblock(player.getWorld())) {
            msg(player, "&c(✧) the island shop only opens on your skyblock island~ go &f/island home&c first.");
            return true;
        }
        openCategories(player);
        return true;
    }

    private boolean cmdAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kawaiishop.admin")) {
            msg(sender, "&cYou don't have permission.");
            return true;
        }
        if (args.length < 1) {
            msg(sender, "&cUsage: &f/kshopadmin <give|take|set|reload> [player] [amount]");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            loadAll();
            msg(sender, "&a✦ KawaiiShop reloaded (&f" + itemCount() + "&a items, &f"
                    + categories.size() + "&a categories).");
            return true;
        }
        if (args.length < 3) {
            msg(sender, "&cUsage: &f/kshopadmin " + sub + " <player> <amount>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            msg(sender, "&cUnknown player: &f" + args[1]);
            return true;
        }
        long amt;
        try { amt = Long.parseLong(args[2]); }
        catch (NumberFormatException ex) { msg(sender, "&cAmount must be a number."); return true; }

        UUID id = target.getUniqueId();
        switch (sub) {
            case "give" -> addBalance(id, Math.abs(amt));
            case "take" -> addBalance(id, -Math.abs(amt));
            case "set"  -> setBalance(id, amt);
            default -> { msg(sender, "&cUnknown sub-command: &f" + sub); return true; }
        }
        msg(sender, "&a✦ &f" + args[1] + "&a now has &f" + coins(getBalance(id)) + "&a.");
        return true;
    }
}
