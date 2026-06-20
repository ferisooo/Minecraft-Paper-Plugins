package com.ferisooo.kawaiiclaims;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Player;

/**
 * Handles chunk-crossing detection for greeting/farewell + entry control + the
 * "fly" flag, and bumps owner lastActive on login.
 */
public class MovementListener implements Listener {

    private final KawaiiClaims plugin;
    private final ClaimManager manager;

    public MovementListener(KawaiiClaims plugin) {
        this.plugin = plugin;
        this.manager = plugin.getClaimManager();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // bump lastActive on all of the owner's claims so login counts as activity
        Player p = e.getPlayer();
        for (Claim c : manager.getClaimsOf(p.getUniqueId())) {
            c.touch();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        // only act when the chunk actually changes
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld()) {
            return;
        }

        Player player = e.getPlayer();
        Claim oldClaim = manager.getClaimAt(from);
        Claim newClaim = manager.getClaimAt(to);

        String oldId = oldClaim == null ? null : oldClaim.getId();
        String newId = newClaim == null ? null : newClaim.getId();
        if (oldId != null && oldId.equals(newId)) return; // same claim, nothing to do

        // Entry control: deny entry if the player's role lacks the "enter" permission
        if (newClaim != null && !manager.permissionAllowed(player, newClaim, "enter")) {
            e.setTo(from);
            plugin.denied(player, "enter this claim");
            return;
        }
        // Leave control: deny leaving if the player's role lacks the "leave" permission
        if (oldClaim != null && newClaim == null
                && !manager.permissionAllowed(player, oldClaim, "leave")) {
            e.setTo(from);
            plugin.denied(player, "leave this claim");
            return;
        }

        // Flight control: grant flight while inside a claim whose "fly" flag is on
        // (for trusted players), and revoke it on leaving (unless creative/spectator
        // or another plugin already granted it).
        applyFlight(player, newClaim);

        // Briefly flash the border when entering one of the player's OWN claims.
        if (newClaim != null
                && newClaim.getOwner().equals(player.getUniqueId())
                && plugin.getConfig().getBoolean("show-border-on-enter", true)) {
            manager.showBorder(player, newClaim, 2);
        }

        // Farewell from the claim we left
        if (oldClaim != null && oldClaim.getFarewell() != null && !oldClaim.getFarewell().isEmpty()) {
            plugin.sendCrossingMessage(player, oldClaim.getFarewell());
        }
        // Greeting for the claim we entered
        if (newClaim != null && newClaim.getGreeting() != null && !newClaim.getGreeting().isEmpty()) {
            plugin.sendCrossingMessage(player, newClaim.getGreeting());
        }
    }

    private void applyFlight(Player player, Claim newClaim) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return; // already flying

        boolean shouldFly = newClaim != null
                && manager.permissionAllowed(player, newClaim, "fly");

        if (shouldFly) {
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
        } else {
            // only revoke flight we plausibly granted (survival/adventure)
            if (player.getAllowFlight()) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }
}
