package com.ferisooo.kawaiireload;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * In-game plugin reloader.
 *
 * <p>Reloads a plugin's <b>config</b> live via {@link Plugin#reloadConfig()},
 * which re-reads its {@code config.yml} from disk without touching the running
 * plugin. This covers the common "tweaked the config and want it live" case
 * (for plugins that read {@code getConfig()} on the fly, or have their own
 * reload command that re-applies it).
 *
 * <p>It deliberately does <b>not</b> disable+enable the plugin. On modern
 * Paper, {@code PluginManager.disablePlugin()} closes the plugin's classloader
 * (its JAR), and {@code enablePlugin()} reuses that same closed loader — so the
 * plugin comes back "enabled" but throws {@code IllegalStateException: zip file
 * closed} the next time it lazy-loads a class. An earlier version did exactly
 * that and bricked plugins until a restart.
 *
 * <p>It also can't re-read the JAR: modern Paper (≥1.20.5) only registers
 * plugin providers at boot, so runtime {@code loadPlugin(file)} fails. So for
 * <b>new code</b>, run {@code /kreload server} (full restart via
 * {@code Bukkit.spigot().restart()}). For config-only changes the live re-read
 * is plenty.
 */
public final class KawaiiReload extends JavaPlugin implements TabExecutor {

    private boolean allowServerRestart;
    private boolean allowMassReload;
    private long    restartDelayTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        PluginCommand cmd = getCommand("kreload");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("(✧) KawaiiReload ready ~ /kreload help");
    }

    private void readConfig() {
        reloadConfig();
        allowServerRestart = getConfig().getBoolean("allow-server-restart", true);
        allowMassReload    = getConfig().getBoolean("allow-mass-reload",    true);
        restartDelayTicks  = Math.max(0L, getConfig().getLong("restart-delay-ticks", 20L));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!"kreload".equalsIgnoreCase(command.getName())) return false;
        if (!sender.hasPermission("kawaiireload.use")) {
            sender.sendMessage("§d(✧) you don't have permission~");
            return true;
        }
        if (args.length == 0) { sendUsage(sender); return true; }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help":
            case "?":
                sendUsage(sender);
                return true;
            case "list":
                listPlugins(sender);
                return true;
            case "self":
                readConfig();
                sender.sendMessage("§d(✧) KawaiiReload config reloaded ✨");
                return true;
            case "data":
                Bukkit.reloadData();
                sender.sendMessage("§d(✧) server data reloaded ✨ §7(recipes, advancements, loot tables)");
                return true;
            case "all":
                if (!allowMassReload) {
                    sender.sendMessage("§c(✧) mass reload is disabled in config (allow-mass-reload)");
                    return true;
                }
                reloadAllPlugins(sender);
                return true;
            case "server":
            case "restart":
                if (!allowServerRestart) {
                    sender.sendMessage("§c(✧) server restart is disabled in config (allow-server-restart)");
                    return true;
                }
                sender.sendMessage("§d(✧) restarting the server~ ✨ §7(needs a restart-script in spigot.yml)");
                Bukkit.getScheduler().runTaskLater(this,
                        () -> Bukkit.spigot().restart(), restartDelayTicks);
                return true;
            case "config":
                if (args.length < 2) {
                    sender.sendMessage("§d(✧) usage: /kreload config <plugin>");
                    return true;
                }
                reloadPluginConfig(sender, args[1]);
                return true;
            case "plugin":
                if (args.length < 2) {
                    sender.sendMessage("§d(✧) usage: /kreload plugin <name>");
                    return true;
                }
                reloadPlugin(sender, args[1]);
                return true;
            default:
                // Implicit: /kreload <pluginName>
                reloadPlugin(sender, args[0]);
                return true;
        }
    }

    private void sendUsage(CommandSender s) {
        s.sendMessage("§d✿ KawaiiReload ✿");
        s.sendMessage("§7/kreload §f<plugin>           §8— re-read that plugin's config.yml (live)");
        s.sendMessage("§7/kreload §fall                §8— re-read every plugin's config (this one stays put)");
        s.sendMessage("§7/kreload §fconfig <plugin>    §8— same as <plugin>: config re-read");
        s.sendMessage("§7/kreload §fdata               §8— recipes, advancements, loot tables");
        s.sendMessage("§7/kreload §fserver             §8— full server restart (needed to pick up new code)");
        s.sendMessage("§7/kreload §flist               §8— show plugins and their state");
        s.sendMessage("§7/kreload §fself               §8— reload KawaiiReload's own config");
    }

    private void listPlugins(CommandSender s) {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        s.sendMessage("§d(✧) " + plugins.length + " plugins:");
        for (Plugin p : plugins) {
            String state = p.isEnabled() ? "§aenabled" : "§cdisabled";
            s.sendMessage("§7- §f" + p.getName()
                    + " §7v" + p.getDescription().getVersion()
                    + " §8[" + state + "§8]");
        }
    }

    private void reloadPluginConfig(CommandSender s, String name) {
        Plugin p = findPlugin(name);
        if (p == null) {
            s.sendMessage("§c(✧) plugin not found: " + name);
            return;
        }
        try {
            p.reloadConfig();
            s.sendMessage("§d(✧) reloaded config for §f" + p.getName() + " ✨");
        } catch (Throwable t) {
            s.sendMessage("§c(✧) reloadConfig failed: " + t.getMessage());
        }
    }

    private void reloadAllPlugins(CommandSender s) {
        // Snapshot the list to be safe against concurrent modification.
        List<Plugin> targets = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == this) continue;
            targets.add(p);
        }
        int ok = 0, fail = 0;
        StringBuilder failed = new StringBuilder();
        for (Plugin p : targets) {
            try {
                p.reloadConfig();
                ok++;
            } catch (Throwable t) {
                fail++;
                if (failed.length() > 0) failed.append(", ");
                failed.append(p.getName());
            }
        }
        String tail = fail > 0 ? " §c(" + fail + " failed: " + failed + ")" : "";
        s.sendMessage("§d(✧) re-read config for §f" + ok + "§d plugins" + tail + " ✨");
        s.sendMessage("§7    (config-only — for new code use §f/kreload server§7)");
    }

    private void reloadPlugin(CommandSender s, String name) {
        if (name.equalsIgnoreCase(getName())) {
            s.sendMessage("§c(✧) i can't reload myself — try /kreload self for config, "
                    + "or /kreload server for everything");
            return;
        }
        Plugin p = findPlugin(name);
        if (p == null) {
            s.sendMessage("§c(✧) plugin not found: " + name);
            return;
        }
        try {
            p.reloadConfig();
            s.sendMessage("§d(✧) re-read config for §f" + p.getName()
                    + " §dv" + p.getDescription().getVersion() + " ✨");
            s.sendMessage("§7    (config-only — for new code use §f/kreload server§7)");
        } catch (Throwable t) {
            s.sendMessage("§c(✧) reload failed: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage());
            getLogger().warning("Reload of '" + name + "' failed: " + t);
        }
    }

    private static Plugin findPlugin(String name) {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!"kreload".equalsIgnoreCase(cmd.getName())) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList(
                    "all", "config", "data", "help", "list", "plugin", "self", "server"));
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) options.add(p.getName());
            return prefixFilter(options, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("plugin")
                || args[0].equalsIgnoreCase("config"))) {
            List<String> names = new ArrayList<>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) names.add(p.getName());
            return prefixFilter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> prefixFilter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
