package com.ferisooo.kawaiinocheat;

import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Cancels "cheaty" commands for players who lack
 * {@code kawaiinocheat.bypass} (default: op) and shows a cute popup.
 *
 * <p>Only player-typed commands are intercepted ({@link
 * PlayerCommandPreprocessEvent}). Console and command blocks are never
 * touched, so automation / datapacks keep working.
 */
public final class KawaiiNoCheat extends JavaPlugin implements Listener {

    private final Set<String> blocked = new HashSet<>();
    private String title;
    private String subtitle;
    private String chatMessage;
    private int fadeIn, stay, fadeOut;
    private boolean playSound;
    private String soundKey;
    private boolean blockOps;
    private boolean particles;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KawaiiNoCheat enabled - blocking " + blocked.size() + " commands for non-bypass players.");
    }

    private void loadSettings() {
        reloadConfig();
        blocked.clear();
        for (String c : getConfig().getStringList("blocked-commands")) {
            if (c != null && !c.isBlank()) blocked.add(normalize(c));
        }
        blockOps    = getConfig().getBoolean("block-ops", false);
        particles   = getConfig().getBoolean("particles", true);
        title       = color(getConfig().getString("title", "§d✿ no cheating~ ✿"));
        subtitle    = color(getConfig().getString("subtitle", "§7survival means survival, silly!"));
        chatMessage = getConfig().getString("chat-message", "§d(✧) ehe~ no §c/{command}§d for you~ 💖");
        fadeIn      = getConfig().getInt("title-fade-in", 8);
        stay        = getConfig().getInt("title-stay", 50);
        fadeOut     = getConfig().getInt("title-fade-out", 12);
        playSound   = getConfig().getBoolean("play-sound", true);
        // Resolve the sound to a string key (e.g. "entity.villager.no") rather than
        // Sound.valueOf — that avoids the enum/interface split between 1.21.x builds.
        soundKey = playSound ? soundKey(getConfig().getString("sound", "ENTITY_VILLAGER_NO")) : null;
        if (playSound && soundKey == null) playSound = false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        // block-ops: false → players with kawaiinocheat.bypass (default op) are
        // exempt. block-ops: true → nobody is exempt, ops included.
        if (!blockOps && p.hasPermission("kawaiinocheat.bypass")) return;

        String root = commandRoot(e.getMessage());
        if (root == null || !blocked.contains(root)) return;

        e.setCancelled(true);

        String msg = chatMessage.replace("{command}", root);
        p.sendMessage(color(msg));
        try {
            p.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        } catch (Throwable ignored) {
            // Older/newer API shims — chat message already delivered.
        }
        if (playSound && soundKey != null) {
            p.playSound(p.getLocation(), soundKey, 1.0f, 1.0f);
        }
        if (particles) {
            // A little puff of "nope" only the offending player sees.
            p.spawnParticle(Particle.ANGRY_VILLAGER, p.getLocation().add(0, 2.1, 0),
                    6, 0.4, 0.3, 0.4, 0.0);
            p.spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1.0, 0),
                    14, 0.3, 0.5, 0.3, 0.02);
        }
    }

    /** Root command word of a raw "/cmd args" message, namespace-stripped + lowercased. */
    private static String commandRoot(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        return normalize(s);
    }

    private static String normalize(String token) {
        String s = token.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1); // strip "minecraft:" etc.
        return s;
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * Turn a configured sound into a Minecraft sound key for the String-based
     * playSound overload. Accepts either an enum-style name (ENTITY_VILLAGER_NO)
     * or a raw key (entity.villager.no / minecraft:entity.villager.no).
     */
    private static String soundKey(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.indexOf('.') >= 0 || s.indexOf(':') >= 0) return s.toLowerCase(Locale.ROOT);
        return s.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiinocheat.admin")) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            loadSettings();
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "✿ KawaiiNoCheat reloaded ("
                    + blocked.size() + " blocked commands).");
            return true;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "KawaiiNoCheat — /knc reload");
        return true;
    }
}
