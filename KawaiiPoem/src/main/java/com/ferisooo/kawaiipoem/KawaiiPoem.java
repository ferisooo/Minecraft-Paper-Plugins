package com.ferisooo.kawaiipoem;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KawaiiPoem — plays a custom, config-driven "End Poem" on a player's screen.
 *
 * Whatever an admin types into {@code lines:} in config.yml is revealed one
 * line at a time, drifting up the chat like the vanilla credits poem, with the
 * two narrator colours alternating. No NMS, no resource pack — works on any
 * 1.21.x server.
 *
 *   /kpoem            play for yourself
 *   /kpoem <player>   play for someone else (kawaiipoem.others)
 *   /kpoem stop       stop your currently-playing poem
 *   /kpoem reload     reload config (kawaiipoem.admin)
 */
public final class KawaiiPoem extends JavaPlugin {

    /** One running scroll task per viewer, so re-running / stopping is clean. */
    private final Map<UUID, ScheduledTask> playing = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("(✧) KawaiiPoem ready ~ type your poem in config.yml!");
    }

    @Override
    public void onDisable() {
        for (ScheduledTask t : playing.values()) t.cancel();
        playing.clear();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!"kpoem".equalsIgnoreCase(command.getName())) return false;

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiipoem.admin")) {
                sender.sendMessage(ChatColor.RED + "(✧) no permission~");
                return true;
            }
            reloadConfig();
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "(✧) KawaiiPoem reloaded ✨");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "(✧) players only~");
                return true;
            }
            stop(p.getUniqueId());
            p.sendMessage(ChatColor.GRAY + "(✧) poem stopped.");
            return true;
        }

        // Resolve the target player.
        Player target;
        if (args.length >= 1) {
            if (!sender.hasPermission("kawaiipoem.others")) {
                sender.sendMessage(ChatColor.RED + "(✧) you can't play the poem for others~");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "(✧) player not found: " + args[0]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(ChatColor.RED + "(✧) console must specify a player: /kpoem <player>");
            return true;
        }

        play(target);
        if (sender != target) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "(✧) playing the poem for "
                    + target.getName() + " ✨");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        // /kpoem <player> — suggest online player names at the target arg (args[0]).
        if ("kpoem".equalsIgnoreCase(command.getName()) && args.length == 1) {
            String pre = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(pre)) out.add(p.getName());
            }
            return out;
        }
        return Collections.emptyList();
    }

    // ----------------------------------------------------------------- playback

    /** Begin (or restart) the scrolling poem for one player. */
    public void play(Player player) {
        stop(player.getUniqueId());

        final List<String> lines = buildLines();
        if (lines.isEmpty()) {
            player.sendMessage(ChatColor.RED + "(✧) no poem lines configured~");
            return;
        }

        // Optional opening title + sound.
        String title = color(getConfig().getString("start-title", ""));
        String subtitle = color(getConfig().getString("start-subtitle", ""));
        if (!title.isEmpty() || !subtitle.isEmpty()) {
            player.sendTitle(title, subtitle, 10, 50, 20);
        }
        playSound(player, getConfig().getString("start-sound", ""));

        if (getConfig().getBoolean("clear-screen", true)) {
            for (int i = 0; i < 20; i++) player.sendMessage("");
        }

        final long delay = Math.max(1L, getConfig().getLong("line-delay-ticks", 30));
        final UUID id = player.getUniqueId();

        // Folia-safe: the poem is sent line-by-line to ONE specific player, so the
        // repeating task runs on that player's own entity scheduler (the only
        // thread allowed to touch the player on Folia). int[] holds the cursor
        // across runs. Init delay 10, period 'delay' (both already >= 1).
        final int[] index = {0};
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, t -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) { t.cancel(); playing.remove(id); return; }
            if (index[0] >= lines.size()) {
                t.cancel();
                playing.remove(id);
                return;
            }
            p.sendMessage(lines.get(index[0]));
            index[0]++;
        }, null, 10L, delay);

        playing.put(id, task);
    }

    /** Stop a player's poem if one is running. */
    public void stop(UUID id) {
        ScheduledTask t = playing.remove(id);
        if (t != null) t.cancel();
    }

    // ----------------------------------------------------------------- rendering

    /** Turn the configured raw lines into final, coloured, (optionally) centred lines. */
    private List<String> buildLines() {
        List<String> raw = getConfig().getStringList("lines");
        boolean alternate = getConfig().getBoolean("alternate-colors", true);
        ChatColor a = parseColor(getConfig().getString("color-a", "GREEN"), ChatColor.GREEN);
        ChatColor b = parseColor(getConfig().getString("color-b", "LIGHT_PURPLE"), ChatColor.LIGHT_PURPLE);
        int centerWidth = getConfig().getInt("center-width", 0);

        List<String> out = new ArrayList<>(raw.size());
        int speaker = 0;
        for (String line : raw) {
            String text = line == null ? "" : line;
            String translated = color(text);

            // Auto-colour only lines that don't already carry their own colour code.
            if (alternate && !text.contains("&") && !translated.isEmpty()) {
                translated = (speaker % 2 == 0 ? a : b) + translated;
            }
            // Each non-blank line counts as a speaker turn for the alternation.
            if (!translated.trim().isEmpty()) speaker++;

            out.add(center(translated, centerWidth));
        }
        return out;
    }

    /** Pad a line with leading spaces to roughly centre it in a column of {@code width}. */
    private String center(String line, int width) {
        if (width <= 0) return line;
        int visible = ChatColor.stripColor(line).length();
        if (visible >= width) return line;
        int pad = (width - visible) / 2;
        StringBuilder sb = new StringBuilder(pad + line.length());
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.append(line).toString();
    }

    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private ChatColor parseColor(String name, ChatColor fallback) {
        if (name == null) return fallback;
        try {
            return ChatColor.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private void playSound(Player p, String key) {
        if (key == null || key.isBlank()) return;
        float vol = (float) getConfig().getDouble("sound-volume", 0.8);
        float pitch = (float) getConfig().getDouble("sound-pitch", 1.0);
        Location loc = p.getLocation();
        // String overload — version-safe across the Sound enum→interface change.
        p.playSound(loc, key, vol, pitch);
    }
}
