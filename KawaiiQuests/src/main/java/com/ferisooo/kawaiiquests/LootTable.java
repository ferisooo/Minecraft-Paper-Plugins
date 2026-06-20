package com.ferisooo.kawaiiquests;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/** Weighted random reward tables per difficulty, loaded from config. */
public final class LootTable {

    /** One possible reward: an item stack + its relative weight. */
    public record Entry(Material material, int amount, int weight) {}

    private final Map<Difficulty, List<Entry>> tables = new EnumMap<>(Difficulty.class);
    private final Random rng = new Random();

    public void load(FileConfiguration cfg, Logger log) {
        tables.clear();
        for (Difficulty d : Difficulty.values()) {
            List<Entry> list = new ArrayList<>();
            String path = "loot." + d.key();
            for (Map<?, ?> raw : cfg.getMapList(path)) {
                Object matName = raw.get("material");
                if (matName == null) continue;
                Material m = Material.matchMaterial(matName.toString().trim().toUpperCase(Locale.ROOT));
                if (m == null || !m.isItem()) {
                    log.warning("(✧) skipping invalid loot material '" + matName + "' in " + path);
                    continue;
                }
                int amount = toInt(raw.get("amount"), 1);
                int weight = toInt(raw.get("weight"), 1);
                if (amount < 1) amount = 1;
                if (weight < 1) weight = 1;
                list.add(new Entry(m, amount, weight));
            }
            if (list.isEmpty()) {
                // Never leave a tier empty — fall back to a sensible default.
                list.add(new Entry(Material.GOLD_INGOT, 1, 1));
                log.warning("(✧) loot table '" + path + "' was empty; using a default reward.");
            }
            tables.put(d, list);
        }
    }

    /** Pick a reward for the tier using weighted random selection. */
    public Entry roll(Difficulty d) {
        List<Entry> list = tables.get(d);
        int total = 0;
        for (Entry e : list) total += e.weight();
        int pick = rng.nextInt(Math.max(1, total));
        for (Entry e : list) {
            pick -= e.weight();
            if (pick < 0) return e;
        }
        return list.get(list.size() - 1);
    }

    /** The actual item handed to the player (plain, no custom name). */
    public ItemStack reward(Entry e) {
        return new ItemStack(e.material(), e.amount());
    }

    /** A labelled icon for the spinning crate display. */
    public ItemStack icon(Entry e) {
        int stack = Math.max(1, Math.min(e.amount(), e.material().getMaxStackSize()));
        ItemStack it = new ItemStack(e.material(), stack);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(KawaiiQuests.LEGACY
                    .deserialize("&f" + e.amount() + "x &d" + KawaiiQuests.pretty(e.material().name()))
                    .decoration(TextDecoration.ITALIC, false));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return def;
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
