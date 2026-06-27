package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fake Herobrine sightings: short-lived packet NPCs spawned at the edge of a
 * player's vision plus disembodied sounds and footsteps. These exist purely
 * to unsettle and carry no logic / health — they are despawned on a timer.
 */
public final class HallucinationManager {

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;

    public HallucinationManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
    }

    /** Spawn a fleeting fake Herobrine near the player that vanishes after {@code seconds}. */
    public void spawnFake(Player target, int seconds) {
        Location at = pickSpot(target, 10, 20);
        if (at == null) return;
        UUID id = UUID.randomUUID();
        HerobrineNms fake = HerobrineNms.spawn(plugin,
                cfg.herobrineName(), id, cfg.skinTexture(), cfg.skinSignature(), at, cfg.glowingEyes());
        if (fake == null) return;
        fake.lookAt(target.getEyeLocation());
        // Despawns the fake + spawns particles at its location -> route to that
        // location's region (Folia-safe).
        org.bukkit.Bukkit.getRegionScheduler().runDelayed(plugin, at, t -> {
            if (!fake.isDead()) {
                Location l = fake.getLocation();
                if (l.getWorld() != null) {
                    l.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, l.clone().add(0, 1, 0),
                            20, 0.3, 0.8, 0.3, 0.02);
                }
                fake.despawn();
            }
        }, Math.max(20L, seconds * 20L));
    }

    /** Play fake footstep / whisper sounds around the player without anything visible. */
    public void fakeSounds(Player target) {
        Location at = pickSpot(target, 4, 9);
        if (at == null) at = target.getLocation();
        Sound[] pool = {
                Sound.ENTITY_PLAYER_BIG_FALL,
                Sound.BLOCK_GRAVEL_STEP,
                Sound.BLOCK_STONE_STEP,
                Sound.ENTITY_PLAYER_BREATH,
                Sound.AMBIENT_CAVE,
        };
        Sound s = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        target.playSound(at, s, 0.6f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f);
    }

    /** Footstep trail behind the player — a sequence of step sounds approaching them. */
    public void footsteps(Player target) {
        Vector back = target.getLocation().getDirection().normalize().multiply(-1);
        for (int i = 0; i < 4; i++) {
            final int step = i;
            // Reads the target's location + plays a sound to them -> their entity
            // scheduler (Folia-safe). Delay must be >= 1.
            target.getScheduler().runDelayed(plugin, t -> {
                if (!target.isOnline()) return;
                Location l = target.getLocation().add(back.clone().multiply(5 - step));
                target.playSound(l, Sound.BLOCK_GRAVEL_STEP, 0.7f, 0.7f);
            }, null, Math.max(1L, i * 6L));
        }
    }

    private Location pickSpot(Player target, int min, int max) {
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double dist = ThreadLocalRandom.current().nextDouble(min, max);
        Location l = target.getLocation().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        // settle onto ground within a few blocks
        for (int dy = 3; dy >= -3; dy--) {
            Location probe = l.clone().add(0, dy, 0);
            if (probe.getBlock().isPassable()
                    && probe.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                return probe;
            }
        }
        return l;
    }
}
