package com.ferisooo.fivehearts;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class FiveHearts extends JavaPlugin implements Listener {

    private static final double FORCED_HEALTH = 10.0;  // 5 hearts
    private static final int FORCED_FOOD = 10;         // 5 drumsticks
    private static final float MAX_SATURATION = 5.0f;  // saturation cap
    private static final double HEAL_PER_SECOND = 1.0; // 0.5 heart/sec

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        for (Player p : getServer().getOnlinePlayers()) applyLimits(p);

        // Clamp task: runs twice per second to enforce hard caps
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : getServer().getOnlinePlayers()) applyLimits(p);
            }
        }.runTaskTimer(this, 10L, 10L);

        // Custom health regeneration (once per second). Food is capped at
        // 10 (below vanilla's regen threshold of 18), so natural regen never
        // fires — we drive it ourselves instead.
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : getServer().getOnlinePlayers()) regenerate(p);
            }
        }.runTaskTimer(this, 20L, 20L); // 20 ticks = 1 second

        getLogger().info("FiveHearts enabled - 5 hearts, 5 drumsticks max, with custom regeneration.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyLimits(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        getServer().getScheduler().runTask(this, () -> applyLimits(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (e.getFoodLevel() > FORCED_FOOD) {
            e.setFoodLevel(FORCED_FOOD);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> applyLimits(p));
    }

    /**
     * Heal the player while their hunger is full.
     *
     * <p>Healing is gated only on food being at the cap — NOT on saturation.
     * The previous version required {@code saturation > 0}, so once the
     * saturation buffer ran dry (which happens quickly after taking a hit
     * from a mob) regen stopped and the player was stuck below full hearts.
     * Now a fed player always recovers to 5 hearts. Saturation is still
     * spent first as a soft buffer when available, but its absence no longer
     * blocks healing. Food itself is left to natural exhaustion, so the
     * player still has to eat to keep regen going.
     */
    private void regenerate(Player p) {
        // Never touch a dead player's health: setting it above 0 while the
        // client is on the death screen revives them server-side only,
        // leaving them stuck (forces a quit-to-menu to recover).
        if (p.isDead() || p.getHealth() <= 0.0) return;
        if (p.getFoodLevel() < FORCED_FOOD) return;
        double forced = forcedHealthFor(p);
        double health = p.getHealth();
        if (health >= forced) return;
        p.setHealth(Math.min(forced, health + HEAL_PER_SECOND));
        // Spend saturation as the buffer when there's some, vanilla-style.
        float sat = p.getSaturation();
        if (sat > 0f) {
            p.setSaturation(Math.max(0f, sat - 0.5f));
        }
    }

    private void applyLimits(Player player) {
        // CRITICAL: touch NOTHING on a dead / respawning player. Not health,
        // and NOT max-health — calling setMaxHealth() recalculates the player's
        // health and revives them server-side while their client is on the
        // death screen, so they're stuck at ~1 heart and can't respawn (a
        // quit-to-menu is the only recovery). This bit especially in
        // KawaiiWorlds worlds, where the player arrives at the default 20 max,
        // so the "!= FORCED_HEALTH" check fired setMaxHealth() mid-death.
        // Max-health is (re)applied on the next tick after respawn (onRespawn).
        if (player.isDead() || player.getHealth() <= 0.0) return;

        // The forced max is normally 5 hearts, but KawaiiGroups can raise it for
        // grouped players via the "group-max-health" metadata it sets — we honour
        // that so the two plugins never fight over max health. No KawaiiGroups
        // (or no bonus) → metadata absent → plain 5 hearts as before.
        double forced = forcedHealthFor(player);

        // Use the deprecated setMaxHealth(): it maps to the max-health
        // attribute internally and works across every 1.21.x build,
        // sidestepping the Attribute enum rename (GENERIC_MAX_HEALTH on
        // 1.21–1.21.1, MAX_HEALTH on 1.21.3+) that would throw
        // NoSuchFieldError at runtime on the wrong server.
        if (player.getMaxHealth() != forced) {
            player.setMaxHealth(forced);
        }
        if (player.getHealth() > forced) {
            player.setHealth(forced);
        }
        if (player.getFoodLevel() > FORCED_FOOD) {
            player.setFoodLevel(FORCED_FOOD);
        }
        if (player.getSaturation() > MAX_SATURATION) {
            player.setSaturation(MAX_SATURATION);
        }
    }

    /**
     * The forced max health for this player: the base (5 hearts) unless
     * KawaiiGroups has set a higher "group-max-health" metadata value. Reading
     * by string key means we never depend on the KawaiiGroups classes.
     */
    private double forcedHealthFor(Player player) {
        try {
            for (org.bukkit.metadata.MetadataValue mv : player.getMetadata("group-max-health")) {
                double v = mv.asDouble();
                if (v > FORCED_HEALTH) return v; // never drop below the 5-heart base
            }
        } catch (Throwable ignored) {}
        return FORCED_HEALTH;
    }
}
