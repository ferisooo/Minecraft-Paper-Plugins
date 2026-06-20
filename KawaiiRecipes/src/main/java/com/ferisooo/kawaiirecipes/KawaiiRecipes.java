package com.ferisooo.kawaiirecipes;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * KawaiiRecipes — keeps every player's recipe book fully stocked.
 *
 * <p>The recipe book (the E-menu side panel and the crafting-table panel)
 * only shows recipes a player has <i>discovered</i>. Vanilla unlocks them
 * as you pick up ingredients, but that misses items from /give, creative,
 * chests, trades, etc. — so you can end up holding everything for a blast
 * furnace yet never see it offered. We just discover ALL recipes for each
 * player, which makes the book list everything and correctly green-highlight
 * whatever you can craft from your current inventory.
 *
 * <p>This also matters if the {@code doLimitedCrafting} gamerule is on: with
 * that rule, players can only craft recipes they've unlocked — so unlocking
 * everything restores normal crafting too.
 */
public final class KawaiiRecipes extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private boolean enabled;
    private boolean unlockOnJoin;
    private long    joinDelayTicks;
    private boolean announce;
    private String  announceMessage;
    private boolean celebrate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
        // Catch anyone already online (e.g. after a /reload).
        for (Player p : Bukkit.getOnlinePlayers()) unlockAll(p);
        getLogger().info("KawaiiRecipes enabled ~ recipe book fully stocked (\u2727)");
    }

    private void loadConfigValues() {
        reloadConfig();
        var cfg = getConfig();
        enabled         = cfg.getBoolean("enabled", true);
        unlockOnJoin    = cfg.getBoolean("unlock-on-join", true);
        joinDelayTicks  = Math.max(0, cfg.getLong("join-delay-ticks", 20));
        announce        = cfg.getBoolean("announce", true);
        announceMessage = cfg.getString("announce-message",
                "&d(\u2727) recipe book topped up~ everything you can make now shows up! &c\u2661");
        celebrate       = cfg.getBoolean("celebrate", true);
    }

    /** A little title + note-particle + chime flourish when recipes unlock. */
    private void celebrate(Player p) {
        if (!celebrate) return;
        p.showTitle(Title.title(
                LEGACY.deserialize("&d&l\u2728 Recipes Unlocked \u2728"),
                LEGACY.deserialize("&7press &fE&7 to browse them all~"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500),
                        Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
        p.getWorld().spawnParticle(Particle.NOTE, p.getLocation().add(0, 1.4, 0),
                14, 0.5, 0.5, 0.5, 1.0);
    }

    /**
     * Discover every keyed recipe currently registered on the server for
     * {@code p}. Returns how many were newly unlocked (already-known ones
     * are skipped by the server).
     */
    private int unlockAll(Player p) {
        if (!enabled) return 0;
        List<NamespacedKey> keys = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            // Most recipes (shaped, shapeless, furnace, blasting, smoking,
            // stonecutting, smithing) implement Keyed. A few internal
            // special recipes don't — those are handled by the client
            // anyway, so skipping them is fine.
            if (r instanceof Keyed k) keys.add(k.getKey());
        }
        return p.discoverRecipes(keys);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled || !unlockOnJoin) return;
        final Player p = event.getPlayer();
        // Small delay so the client is fully in before we push the unlocks.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            int n = unlockAll(p);
            if (n > 0) {
                if (announce && announceMessage != null && !announceMessage.isBlank()) {
                    p.sendMessage(LEGACY.deserialize(announceMessage));
                }
                celebrate(p);
            }
        }, joinDelayTicks);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiirecipes.admin")) return noPerm(sender);
            loadConfigValues();
            sender.sendMessage(LEGACY.deserialize("&d(\u2727) KawaiiRecipes reloaded~"));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("all")) {
            if (!sender.hasPermission("kawaiirecipes.admin")) return noPerm(sender);
            int players = 0;
            for (Player p : Bukkit.getOnlinePlayers()) { unlockAll(p); players++; }
            sender.sendMessage(LEGACY.deserialize(
                    "&d(\u2727) unlocked every recipe for &f" + players + "&d player(s)~"));
            return true;
        }

        // No args → unlock the sender's own recipes.
        if (!(sender instanceof Player p)) {
            sender.sendMessage(LEGACY.deserialize("&d(\u2727) usage: &f/kr [reload|all]"));
            return true;
        }
        int n = unlockAll(p);
        p.sendMessage(LEGACY.deserialize(
                "&d(\u2727) unlocked &f" + n + "&d new recipe(s)~ press &fE&d to see them all! &c\u2661"));
        if (n > 0) celebrate(p);
        return true;
    }

    private boolean noPerm(CommandSender sender) {
        sender.sendMessage(LEGACY.deserialize("&c(\u2727) you don't have permission for that ~"));
        return true;
    }
}
