package com.ferisooo.kawaiidungeons;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A named, weighted loot table. Rolls pick entries by weight and respect the
 * {@code boss-exclusive} flag. Item names are coloured by rarity.
 */
public final class LootTable {

    public enum Rarity {
        COMMON("&f", "Common"),
        RARE("&9", "Rare"),
        EPIC("&5", "Epic"),
        LEGENDARY("&6", "Legendary"),
        MYTHIC("&d", "Mythic");

        public final String colorCode;
        public final String display;

        Rarity(String colorCode, String display) {
            this.colorCode = colorCode;
            this.display = display;
        }

        static Rarity from(String s) {
            if (s == null) return COMMON;
            try { return Rarity.valueOf(s.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return COMMON; }
        }
    }

    public static final class Entry {
        public final Material material;
        public final int min, max;
        public final int weight;
        public final Rarity rarity;
        public final boolean bossExclusive;

        Entry(Material material, int min, int max, int weight, Rarity rarity, boolean bossExclusive) {
            this.material = material;
            this.min = min;
            this.max = max;
            this.weight = weight;
            this.rarity = rarity;
            this.bossExclusive = bossExclusive;
        }
    }

    private final String name;
    private final int rolls;
    private final List<Entry> entries = new ArrayList<>();

    private LootTable(String name, int rolls) {
        this.name = name;
        this.rolls = Math.max(1, rolls);
    }

    public String name() { return name; }

    @SuppressWarnings("unchecked")
    public static LootTable parse(String name, ConfigurationSection s) {
        LootTable t = new LootTable(name, s.getInt("rolls", 1));
        List<Map<?, ?>> raw = s.getMapList("entries");
        for (Map<?, ?> m : raw) {
            Object itemObj = m.get("item");
            String itemName = itemObj == null ? "STONE" : itemObj.toString();
            Material mat = Material.matchMaterial(itemName.toUpperCase(Locale.ROOT));
            if (mat == null) continue;
            int min = toInt(m.get("min"), 1);
            int max = toInt(m.get("max"), 1);
            int weight = toInt(m.get("weight"), 1);
            Rarity rarity = Rarity.from(m.get("rarity") == null ? null : m.get("rarity").toString());
            Object exObj = m.get("boss-exclusive");
            boolean exclusive = exObj != null && Boolean.parseBoolean(exObj.toString());
            t.entries.add(new Entry(mat, Math.max(1, min), Math.max(min, max), Math.max(1, weight), rarity, exclusive));
        }
        return t;
    }

    /**
     * Rolls the table. {@code lootMult} scales the number of rolls; {@code boss}
     * controls whether boss-exclusive entries are eligible.
     */
    public List<ItemStack> roll(double lootMult, boolean boss) {
        List<ItemStack> out = new ArrayList<>();
        int effectiveRolls = Math.max(1, (int) Math.round(rolls * Math.max(0.1, lootMult)));
        List<Entry> pool = new ArrayList<>();
        int totalWeight = 0;
        for (Entry e : entries) {
            if (e.bossExclusive && !boss) continue;
            pool.add(e);
            totalWeight += e.weight;
        }
        if (pool.isEmpty() || totalWeight <= 0) return out;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < effectiveRolls; i++) {
            int pick = rng.nextInt(totalWeight);
            int acc = 0;
            for (Entry e : pool) {
                acc += e.weight;
                if (pick < acc) {
                    out.add(build(e, rng));
                    break;
                }
            }
        }
        return out;
    }

    private ItemStack build(Entry e, ThreadLocalRandom rng) {
        int amount = e.min >= e.max ? e.min : e.min + rng.nextInt(e.max - e.min + 1);
        ItemStack item = new ItemStack(e.material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String pretty = prettyName(e.material);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    e.rarity.colorCode + pretty));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    e.rarity.colorCode + "&l" + e.rarity.display));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String prettyName(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(o.toString()); }
        catch (NumberFormatException e) { return def; }
    }
}
