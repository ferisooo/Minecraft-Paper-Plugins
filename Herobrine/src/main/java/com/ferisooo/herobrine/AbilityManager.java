package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Herobrine's active abilities, each gated by its own cooldown. Used by both
 * the hunting mode and the boss fight. All effects are configurable through
 * {@link ConfigManager}.
 */
public final class AbilityManager {

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;
    private final Map<String, Long> cooldowns = new HashMap<>(); // ability -> tick ready

    public AbilityManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
    }

    private boolean ready(String key) {
        return plugin.currentTick() >= cooldowns.getOrDefault(key, 0L);
    }

    private void arm(String key, int seconds) {
        cooldowns.put(key, plugin.currentTick() + seconds * 20L);
    }

    // ---- Shadow Teleport: appear directly behind the target ----
    public boolean shadowTeleport(HerobrineEntity hb, Player target) {
        if (!ready("teleport") || target == null) return false;
        Location pl = target.getLocation();
        Vector behind = pl.getDirection().normalize().multiply(-2.0);
        Location dest = pl.clone().add(behind);
        dest.setY(findGround(dest));
        dest.setDirection(pl.toVector().subtract(dest.toVector()));
        Location from = hb.getLocation();
        if (from.getWorld() != null) {
            from.getWorld().spawnParticle(Particle.SMOKE, from.clone().add(0, 1, 0), 30, 0.4, 1.0, 0.4, 0.03);
            from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        }
        hb.teleport(dest);
        dest.getWorld().spawnParticle(Particle.SMOKE, dest.clone().add(0, 1, 0), 30, 0.4, 1.0, 0.4, 0.03);
        dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        arm("teleport", cfg.shadowTeleportCooldown());
        return true;
    }

    private double findGround(Location l) {
        Location probe = l.clone();
        for (int i = 0; i < 6; i++) {
            Block b = probe.getBlock();
            Block below = b.getRelative(BlockFace.DOWN);
            if (b.isPassable() && below.getType().isSolid()) return probe.getY();
            probe.add(0, -1, 0);
        }
        return l.getY();
    }

    // ---- Lightning Strike: bolt near the target dealing configurable damage ----
    public boolean lightningStrike(Player target) {
        if (!ready("lightning") || target == null) return false;
        Location l = target.getLocation();
        Location strike = l.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0,
                ThreadLocalRandom.current().nextDouble(-2, 2));
        l.getWorld().strikeLightningEffect(strike);
        l.getWorld().playSound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
        if (l.distanceSquared(strike) < 4.0 * 4.0) {
            target.damage(cfg.lightningDamage());
        }
        for (Player p : nearby(l, 3.5)) {
            if (p != target) p.damage(cfg.lightningDamage() * 0.5);
        }
        arm("lightning", cfg.lightningCooldown());
        return true;
    }

    // ---- Darkness Pulse: blindness + darkness + slowness in a radius ----
    public boolean darknessPulse(Location origin) {
        if (!ready("darkness")) return false;
        int radius = cfg.darknessRadius();
        int dur = cfg.darknessDuration() * 20;
        origin.getWorld().playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.6f);
        origin.getWorld().spawnParticle(Particle.SQUID_INK, origin.clone().add(0, 1, 0), 60, radius / 2.0, 2, radius / 2.0, 0.01);
        for (Player p : nearby(origin, radius)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, dur, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, dur, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, dur, 1));
        }
        arm("darkness", Math.max(cfg.darknessDuration() + 5, 10));
        return true;
    }

    // ---- Block Manipulation: place ominous blocks / warning signs near a player ----
    public boolean blockManipulation(Player target) {
        if (!cfg.blockManipulationEnabled() || !ready("blocks") || target == null) return false;
        Location base = target.getLocation();
        int roll = ThreadLocalRandom.current().nextInt(4);
        switch (roll) {
            case 0 -> placeRedstoneTorches(base);
            case 1 -> placeNetherrack(base);
            case 2 -> placeWarningSign(base);
            default -> alterNearbyBlocks(base);
        }
        arm("blocks", 12);
        return true;
    }

    /**
     * Place {@code mat} at {@code b} but snapshot the original block and
     * schedule its restoration, so Herobrine's "block manipulation" never
     * leaves permanent griefing the player has to clean up by hand.
     */
    private void placeTemp(Block b, Material mat) {
        final org.bukkit.block.data.BlockData orig = b.getBlockData();
        b.setType(mat, false);
        long ticks = Math.max(1L, cfg.blockRevertSeconds() * 20L);
        // Touches a specific block -> route to that block's region (Folia-safe).
        org.bukkit.Bukkit.getRegionScheduler().runDelayed(plugin, b.getLocation(), t -> {
            if (b.getType() == mat) b.setBlockData(orig, false); // only revert if still ours
        }, ticks);
    }

    private void placeRedstoneTorches(Location base) {
        for (int i = 0; i < 5; i++) {
            Location l = base.clone().add(ThreadLocalRandom.current().nextInt(-6, 7), 0,
                    ThreadLocalRandom.current().nextInt(-6, 7));
            l.setY(findGround(l));
            Block b = l.getBlock();
            if (b.getType().isAir() && b.getRelative(BlockFace.DOWN).getType().isSolid()) {
                placeTemp(b, Material.REDSTONE_TORCH);
            }
        }
    }

    private void placeNetherrack(Location base) {
        Location l = base.clone().add(ThreadLocalRandom.current().nextInt(-5, 6), -1,
                ThreadLocalRandom.current().nextInt(-5, 6));
        Block b = l.getBlock();
        if (b.getType().isSolid()) {
            placeTemp(b, Material.NETHERRACK);
            Block above = b.getRelative(BlockFace.UP);
            if (above.getType().isAir() && ThreadLocalRandom.current().nextBoolean()) {
                placeTemp(above, Material.FIRE);
            }
        }
    }

    private void placeWarningSign(Location base) {
        Location l = base.clone().add(ThreadLocalRandom.current().nextInt(-4, 5), 0,
                ThreadLocalRandom.current().nextInt(-4, 5));
        l.setY(findGround(l));
        Block b = l.getBlock();
        if (!b.getType().isAir() || !b.getRelative(BlockFace.DOWN).getType().isSolid()) return;
        placeTemp(b, Material.OAK_SIGN);
        if (b.getState() instanceof Sign sign) {
            String[] lines = pickWarning();
            for (int i = 0; i < 4 && i < lines.length; i++) {
                sign.setLine(i, lines[i]); // legacy front-side setter; widely supported
            }
            sign.update(true);
        }
    }

    private String[] pickWarning() {
        String[][] options = {
                {"", "RUN", "", ""},
                {"I", "AM", "WATCHING", ""},
                {"YOU", "ARE", "NOT", "ALONE"},
                {"", "LEAVE", "NOW", ""},
                {"HE", "IS", "COMING", ""},
        };
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private void alterNearbyBlocks(Location base) {
        // Snuff out nearby torches/light, mirroring the classic "lights go out" omen.
        org.bukkit.World world = base.getWorld();
        if (world == null) return;
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue; // never force-load
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == Material.TORCH || b.getType() == Material.WALL_TORCH) {
                        placeTemp(b, Material.AIR); // snuffed out, then restored later
                    }
                }
            }
        }
        world.playSound(base, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 0.8f);
    }

    private java.util.List<Player> nearby(Location l, double radius) {
        java.util.List<Player> out = new java.util.ArrayList<>();
        double radiusSq = radius * radius;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getLocation().distanceSquared(l) <= radiusSq) out.add(p);
        }
        return out;
    }

    public void resetCooldowns() { cooldowns.clear(); }
}
