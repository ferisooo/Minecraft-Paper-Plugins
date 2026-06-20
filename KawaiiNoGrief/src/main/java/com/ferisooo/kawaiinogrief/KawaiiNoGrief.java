package com.ferisooo.kawaiinogrief;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Stops explosion-driven block-breaking without touching anything else.
 *
 * Why this isn't covered by `/gamerule mobGriefing false`: that rule only
 * governs mob-driven block changes (creeper detonations, ghast fireballs
 * landing, enderman block pickups, etc.). Player-placed primed TNT, beds
 * in the nether, end crystals, and a few others use a different path —
 * the `mobGriefing` flag is never checked. Clearing
 * {@link EntityExplodeEvent#blockList()} (and the equivalent on
 * {@link BlockExplodeEvent}) covers all of them in one shot.
 *
 * Damage to entities and knockback are unchanged — those are computed
 * before the event fires and emptying the block list doesn't disturb them.
 */
public final class KawaiiNoGrief extends JavaPlugin implements Listener {

    private boolean enabled;
    private boolean defaultBlockDamage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadCfg();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("(✧) KawaiiNoGrief ready ~ enabled=" + enabled
                + ", default-block-damage=" + defaultBlockDamage);
    }

    private void reloadCfg() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        defaultBlockDamage = getConfig().getBoolean("default-block-damage", false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"knogrief".equalsIgnoreCase(command.getName())) return false;
        if (!sender.hasPermission("kawaiinogrief.admin") && !sender.isOp()) {
            sender.sendMessage("§d(✧) you don't have permission~");
            return true;
        }
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload":
                reloadCfg();
                sender.sendMessage("§d(✧) KawaiiNoGrief reloaded ✨  enabled=" + enabled);
                return true;
            case "on":
                setGriefProtection(true);
                sender.sendMessage("§d(✧) KawaiiNoGrief is now §aON§d ✨ explosions won't break blocks~");
                return true;
            case "off":
                setGriefProtection(false);
                sender.sendMessage("§d(✧) KawaiiNoGrief is now §cOFF§d ~ vanilla explosions are back");
                return true;
            case "toggle":
                setGriefProtection(!enabled);
                sender.sendMessage("§d(✧) KawaiiNoGrief toggled " + (enabled ? "§aON§d ✨" : "§cOFF§d ~"));
                return true;
            case "status":
                sender.sendMessage("§d(✧) KawaiiNoGrief is " + (enabled ? "§aON" : "§cOFF"));
                return true;
            default:
                sender.sendMessage("§d(✧) /knogrief <on|off|toggle|status|reload> §7(currently "
                        + (enabled ? "§aON§7)" : "§cOFF§7)"));
                return true;
        }
    }

    /** Flip the master switch at runtime and persist it to config.yml. */
    private void setGriefProtection(boolean value) {
        enabled = value;
        getConfig().set("enabled", value);
        saveConfig();
    }

    /**
     * Returns true if vanilla block damage should be allowed for the given
     * lower-cased source key. The key is looked up under `block-damage.`
     * in config; missing entries fall back to `default-block-damage`.
     */
    private boolean allowBlockDamage(String key) {
        if (!enabled) return true;
        return getConfig().getBoolean("block-damage." + key, defaultBlockDamage);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!enabled) return;
        if (e.blockList().isEmpty()) return; // nothing to gate
        String key = e.getEntityType().getKey().getKey().toLowerCase(Locale.ROOT);
        if (!allowBlockDamage(key)) {
            e.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!enabled) return;
        if (e.blockList().isEmpty()) return;
        String key = e.getBlock().getType().getKey().getKey().toLowerCase(Locale.ROOT);
        if (!allowBlockDamage(key)) {
            e.blockList().clear();
        }
    }
}
