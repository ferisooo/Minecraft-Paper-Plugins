package com.ferisooo.kawaiifurnace;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * KawaiiFurnace — makes furnaces, blast furnaces and smokers cook faster.
 *
 * <p>Each time a furnace-type block starts smelting an item, Paper/Spigot
 * fires {@link FurnaceStartSmeltEvent} carrying that item's total cook time
 * (200 ticks for a furnace, 100 for a blast furnace / smoker). We simply
 * divide that by a configurable multiplier, so the item finishes sooner —
 * no schedulers, no custom recipes, fully vanilla-compatible. Optionally we
 * also stretch fuel burn time via {@link FurnaceBurnEvent}.
 */
public final class KawaiiFurnace extends JavaPlugin implements Listener {

    private boolean enabled;
    private double defaultMult;
    private double furnaceMult;
    private double blastMult;
    private double smokerMult;
    private int minCookTicks;
    private double fuelEfficiency;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadValues();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KawaiiFurnace enabled - furnaces x" + furnaceMult
                + ", blast x" + blastMult + ", smoker x" + smokerMult
                + (enabled ? "" : " (currently DISABLED in config)"));
    }

    private void loadValues() {
        reloadConfig();
        enabled        = getConfig().getBoolean("enabled", true);
        defaultMult    = Math.max(1.0, getConfig().getDouble("speed-multiplier", 4.0));
        furnaceMult    = resolveMult("furnace-multiplier");
        blastMult      = resolveMult("blast-furnace-multiplier");
        smokerMult     = resolveMult("smoker-multiplier");
        minCookTicks   = Math.max(1, getConfig().getInt("min-cook-ticks", 5));
        fuelEfficiency = Math.max(1.0, getConfig().getDouble("fuel-efficiency", 1.0));
    }

    /** A per-type multiplier, falling back to the default when 0/unset/<1. */
    private double resolveMult(String key) {
        double v = getConfig().getDouble(key, 0.0);
        return (v >= 1.0) ? v : defaultMult;
    }

    private double multForType(Material type) {
        switch (type) {
            case BLAST_FURNACE: return blastMult;
            case SMOKER:        return smokerMult;
            case FURNACE:       return furnaceMult;
            default:            return defaultMult; // future furnace-likes
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(ignoreCancelled = true)
    public void onStartSmelt(FurnaceStartSmeltEvent event) {
        if (!enabled) return;
        double mult = multForType(event.getBlock().getType());
        if (mult <= 1.0) return;
        int total = event.getTotalCookTime();
        int sped = (int) Math.round(total / mult);
        if (sped < minCookTicks) sped = minCookTicks;
        if (sped < 1) sped = 1;
        if (sped < total) {
            event.setTotalCookTime(sped);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFuelBurn(FurnaceBurnEvent event) {
        if (!enabled || fuelEfficiency <= 1.0) return;
        int burn = event.getBurnTime();
        int boosted = (int) Math.round(burn * fuelEfficiency);
        // Burn time is a short under the hood; keep it in a safe range.
        if (boosted > 32760) boosted = 32760;
        if (boosted > burn) {
            event.setBurnTime(boosted);
        }
    }

    // -------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("kawaiifurnace.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "info";
        if (sub.equals("reload")) {
            loadValues();
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "✧ KawaiiFurnace reloaded.");
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "✧ KawaiiFurnace "
                + (enabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF")
                + ChatColor.GRAY + "  furnace x" + ChatColor.WHITE + furnaceMult
                + ChatColor.GRAY + ", blast x" + ChatColor.WHITE + blastMult
                + ChatColor.GRAY + ", smoker x" + ChatColor.WHITE + smokerMult
                + ChatColor.GRAY + ", fuel x" + ChatColor.WHITE + fuelEfficiency);
        return true;
    }
}
