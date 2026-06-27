package com.ferisooo.kawaiisigns;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * KawaiiSigns - classic [command] signs with multi-line wrapping.
 *
 *   While building (long command wraps across lines):
 *      Line 1: [gamemode
 *      Line 2: survival]
 *      Line 3: click to
 *      Line 4: become wimp
 *
 *   After placing:
 *      Line 1: [command]       <- label, real cmd tucked away
 *      Line 2: click to        <- label text shifts up
 *      Line 3: become wimp
 *      Line 4: (blank)
 *
 *   Right-click  -> runs the stored command as the clicking player.
 *   Op + AXE + right-click -> reveals the stored command in chat (inspect mode).
 *   Only ops (or kawaiisigns.create) can create/break command signs.
 */
public final class KawaiiSigns extends JavaPlugin implements Listener {

    private static final String LABEL = ChatColor.LIGHT_PURPLE + "[command]";
    private NamespacedKey cmdKey;

    @Override
    public void onEnable() {
        cmdKey = new NamespacedKey(this, "command");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("(\u2727) KawaiiSigns ready ~ [command] signs, multi-line + axe-inspect!");
    }

    // ============================================================
    //   CREATION (with multi-line wrapping)
    // ============================================================

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        // Read all 4 lines stripped of color.
        String[] in = new String[4];
        for (int i = 0; i < 4; i++) {
            String l = event.getLine(i);
            in[i] = (l == null) ? "" : ChatColor.stripColor(l);
        }

        // Skip if line 0 is already our finalized label (defensive).
        if (in[0].trim().equalsIgnoreCase("[command]")) return;

        // Need an opening [ on line 0 (allow leading whitespace).
        int openIdx = in[0].indexOf('[');
        if (openIdx < 0) {
            // Regular sign (not a command sign) - still translate & color codes.
            translateColorsIfAllowed(event);
            return;
        }

        // Scan forward through lines, building the command until we hit ].
        StringBuilder cmdBuf = new StringBuilder();
        int closingLine = -1;
        int closingIdx = -1;

        String line0Rest = in[0].substring(openIdx + 1);
        int cb0 = line0Rest.indexOf(']');
        if (cb0 >= 0) {
            cmdBuf.append(line0Rest, 0, cb0);
            closingLine = 0;
            closingIdx = openIdx + 1 + cb0;
        } else {
            cmdBuf.append(line0Rest);
            for (int i = 1; i < 4; i++) {
                String li = in[i];
                int j = li.indexOf(']');
                if (j >= 0) {
                    appendWithSpace(cmdBuf, li.substring(0, j));
                    closingLine = i;
                    closingIdx = j;
                    break;
                } else {
                    appendWithSpace(cmdBuf, li);
                }
            }
        }

        if (closingLine < 0) {
            // Had a [ but no closing ] - treat as a regular sign and translate.
            translateColorsIfAllowed(event);
            return;
        }
        String command = cmdBuf.toString().trim();
        // Collapse any runs of whitespace from line wraps.
        command = command.replaceAll("\\s+", " ");
        if (command.isEmpty()) return;

        Player p = event.getPlayer();
        if (!p.isOp() && !p.hasPermission("kawaiisigns.create")) {
            p.sendMessage(ChatColor.RED + "(\u2727) Only ops can create command signs.");
            event.setLine(0, ChatColor.RED + "[denied]");
            return;
        }

        // Build the new visible sign:
        //   line 0 = [command] label
        //   labels = (anything after ] on closingLine) + lines after closingLine
        String[] label = new String[3];
        int li = 0;

        String afterClose = in[closingLine].substring(closingIdx + 1).trim();
        if (!afterClose.isEmpty() && li < 3) label[li++] = afterClose;

        for (int i = closingLine + 1; i < 4 && li < 3; i++) {
            label[li++] = in[i];
        }

        event.setLine(0, LABEL);
        for (int i = 0; i < 3; i++) {
            String text = label[i] == null ? "" : label[i];
            // Translate &a, &c, &l, etc. to actual color codes (ops only).
            if (p.isOp() || p.hasPermission("kawaiisigns.color")) {
                text = ChatColor.translateAlternateColorCodes('&', text);
            }
            event.setLine(i + 1, text);
        }

        final Block block = event.getBlock();
        final String storedCmd = command;
        // Stash the real command in PDC on the next tick (after the sign state commits).
        // Folia-safe: the task touches a specific block, so route it to that
        // block's region thread via the RegionScheduler.
        getServer().getRegionScheduler().run(this, block.getLocation(), task -> {
            if (block.getState() instanceof Sign s) {
                s.getPersistentDataContainer().set(cmdKey, PersistentDataType.STRING, storedCmd);
                s.update();
            }
        });

        p.sendMessage(ChatColor.LIGHT_PURPLE + "(\u2727) Command sign created ~ runs "
                + ChatColor.WHITE + "/" + command);
    }

    private static void appendWithSpace(StringBuilder buf, String add) {
        if (add == null || add.isEmpty()) return;
        if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' ') buf.append(' ');
        buf.append(add);
    }

    /** Translate & color codes on every line of the sign, if the placer is allowed. */
    private void translateColorsIfAllowed(SignChangeEvent event) {
        Player p = event.getPlayer();
        if (!p.isOp() && !p.hasPermission("kawaiisigns.color")) return;
        for (int i = 0; i < 4; i++) {
            String l = event.getLine(i);
            if (l == null || l.isEmpty()) continue;
            event.setLine(i, ChatColor.translateAlternateColorCodes('&', l));
        }
    }

    // ============================================================
    //   CLICK -> RUN  (or AXE+OP -> INSPECT)
    // ============================================================

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSignClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!(block.getState() instanceof Sign sign)) return;

        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String cmd = pdc.get(cmdKey, PersistentDataType.STRING);
        if (cmd == null || cmd.isEmpty()) return;

        Player p = event.getPlayer();

        // Axe + op = inspect mode (don't run, show the hidden command).
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && isAxe(hand.getType())
                && (p.isOp() || p.hasPermission("kawaiisigns.create"))) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "(\u2727) This sign runs: "
                    + ChatColor.WHITE + "/" + cmd);
            p.sendMessage(ChatColor.GRAY
                    + "Break the sign and place a new one to change it.");
            return;
        }

        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        event.setCancelled(true);
        p.performCommand(cmd);
    }

    // ============================================================
    //   PROTECTION
    // ============================================================

    @EventHandler(ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isSign(block.getType())) return;
        if (!(block.getState() instanceof Sign sign)) return;
        if (!sign.getPersistentDataContainer().has(cmdKey, PersistentDataType.STRING)) return;

        Player p = event.getPlayer();
        if (!p.isOp() && !p.hasPermission("kawaiisigns.create")) {
            p.sendMessage(ChatColor.RED + "(\u2727) You can't break a command sign.");
            event.setCancelled(true);
        }
    }

    // ============================================================
    //   helpers
    // ============================================================

    private boolean isSign(Material m) {
        String n = m.name();
        return n.endsWith("_SIGN") || n.endsWith("_WALL_SIGN") || n.endsWith("_HANGING_SIGN");
    }

    private boolean isAxe(Material m) {
        return m != null && m.name().endsWith("_AXE");
    }
}
