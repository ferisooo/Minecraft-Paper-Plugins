package com.ferisooo.kawaiidungeons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and serves named {@link LootTable}s from loot.yml.
 */
public final class LootManager {

    private final KawaiiDungeons plugin;
    private final File file;
    private final Map<String, LootTable> tables = new HashMap<>();

    public LootManager(KawaiiDungeons plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    public void load() {
        tables.clear();
        if (!file.exists()) {
            plugin.saveResource("loot.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("loot-tables");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s != null) tables.put(key, LootTable.parse(key, s));
        }
        plugin.getLogger().info("(✿) loaded " + tables.size() + " loot table(s).");
    }

    public LootTable table(String name) { return tables.get(name); }

    /** Convenience: roll a table by name; empty list if the table is missing. */
    public List<ItemStack> roll(String name, double lootMult, boolean boss) {
        LootTable t = tables.get(name);
        if (t == null) return java.util.Collections.emptyList();
        return t.roll(lootMult, boss);
    }
}
