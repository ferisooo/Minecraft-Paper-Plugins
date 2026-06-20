package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * A Wither Skeleton re-skinned by attributes into one of Herobrine's "shadow
 * minions" summoned during phase 3 of the boss fight. They are fast, dark,
 * trail particles, target the boss's victim, and expire on a timer so the
 * arena doesn't fill up with skeletons.
 */
public final class ShadowMinion {

    private final HerobrinePlugin plugin;
    private final WitherSkeleton entity;
    private BukkitRunnable particleTask;

    private ShadowMinion(HerobrinePlugin plugin, WitherSkeleton entity) {
        this.plugin = plugin;
        this.entity = entity;
    }

    public WitherSkeleton entity() { return entity; }

    /** Spawn a configured shadow minion that hunts {@code target} for {@code lifespanSeconds}. */
    public static ShadowMinion summon(HerobrinePlugin plugin, Location at, Player target, int lifespanSeconds) {
        WitherSkeleton ws = at.getWorld().spawn(at, WitherSkeleton.class, e -> {
            e.setCustomName("§8Shadow Minion");
            e.setCustomNameVisible(false);
            e.setRemoveWhenFarAway(true);
            e.getPersistentDataContainer().set(plugin.minionKey(), PersistentDataType.BYTE, (byte) 1);
            // Version-safe stats: deprecated health API + potion buffs instead
            // of the (version-sensitive) Attribute enum used elsewhere in repo.
            applyHealth(e, 20.0);
            // SPEED II ≈ a fast, relentless chaser without touching MOVEMENT_SPEED.
            e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
            e.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0, false, false));
            if (e.getEquipment() != null) e.getEquipment().clear();
            if (target != null) e.setTarget(target);
        });

        ShadowMinion minion = new ShadowMinion(plugin, ws);
        minion.startEffects();
        plugin.getServer().getScheduler().runTaskLater(plugin, minion::expire, lifespanSeconds * 20L);
        return minion;
    }

    @SuppressWarnings("deprecation")
    private static void applyHealth(LivingEntity e, double health) {
        e.setMaxHealth(health);
        e.setHealth(health);
    }

    private void startEffects() {
        particleTask = new BukkitRunnable() {
            @Override public void run() {
                if (entity.isDead() || !entity.isValid()) { cancel(); return; }
                Location l = entity.getLocation().add(0, 1, 0);
                l.getWorld().spawnParticle(Particle.SMOKE, l, 6, 0.25, 0.4, 0.25, 0.01);
                l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 2, 0.2, 0.3, 0.2, 0.0);
            }
        };
        particleTask.runTaskTimer(plugin, 0L, 4L);
    }

    public boolean isAlive() { return entity != null && entity.isValid() && !entity.isDead(); }

    public void expire() {
        if (particleTask != null) particleTask.cancel();
        if (entity != null && entity.isValid()) {
            Location l = entity.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 20, 0.3, 0.6, 0.3, 0.02);
            entity.remove();
        }
    }
}
