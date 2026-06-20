package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the single live Herobrine and drives his moment-to-moment behaviour:
 * passive stalking (random sightings that stare and vanish), active hunting
 * (closing in and using abilities), and routing player melee hits into his
 * logical health. The boss fight borrows the same entity via
 * {@link #forceSpawn}.
 */
public final class HerobrineManager {

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;

    private HerobrineEntity active;
    private long nextStalkTick;

    /** Fixed client profile id so his (default Steve) skin is stable instead of
     *  re-rolling a random Steve/Alex every spawn. */
    private static final UUID PROFILE_ID =
            UUID.nameUUIDFromBytes("HerobrineNPC-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    public HerobrineManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
    }

    public HerobrineEntity active() { return active; }
    public boolean hasActive() { return active != null && !active.isDead(); }

    public void clearActive() {
        if (active != null && !active.isDead()) active.despawn();
        active = null;
    }

    // ---- spawning ----

    /** Spawn (or relocate) the stalker watching {@code target} at {@code at}. */
    public HerobrineEntity spawnStalker(Player target, Location at) {
        if (hasActive()) {
            active.setTarget(target);
            active.setMode(HerobrineEntity.Mode.STALKING);
            active.teleport(at);
            active.face(target.getEyeLocation());
            active.setStareTicks(cfg.stareSeconds() * 20);
            return active;
        }
        HerobrineNms nms = HerobrineNms.spawn(plugin, cfg.herobrineName(), PROFILE_ID,
                cfg.skinTexture(), cfg.skinSignature(), at, cfg.glowingEyes());
        if (nms == null) {
            plugin.getLogger().warning("[Herobrine] could not spawn NPC (NMS unavailable on this server build)");
            return null;
        }
        active = new HerobrineEntity(plugin, nms, cfg.health());
        active.setTarget(target);
        active.setMode(HerobrineEntity.Mode.STALKING);
        active.face(target.getEyeLocation());
        active.setStareTicks(cfg.stareSeconds() * 20);
        return active;
    }

    /** Spawn a fresh Herobrine in a specific mode (used by the boss fight). Replaces any existing. */
    public HerobrineEntity forceSpawn(Location at, Player target, HerobrineEntity.Mode mode, double health) {
        clearActive();
        HerobrineNms nms = HerobrineNms.spawn(plugin, cfg.herobrineName(), PROFILE_ID,
                cfg.skinTexture(), cfg.skinSignature(), at, cfg.glowingEyes());
        if (nms == null) return null;
        active = new HerobrineEntity(plugin, nms, health);
        active.setTarget(target);
        active.setMode(mode);
        return active;
    }

    public void despawn() {
        clearActive();
    }

    // ---- passive stalking tick (called ~every second from main tick) ----

    public void stalkingTick() {
        if (!cfg.stalkingEnabled()) return;
        if (hasActive() && active.getMode() != HerobrineEntity.Mode.STALKING) return;

        if (hasActive() && active.getMode() == HerobrineEntity.Mode.STALKING) {
            // currently staring at someone: keep facing, then vanish when the timer ends
            Player t = active.target();
            if (t == null || !t.isOnline() || t.getWorld() != active.getLocation().getWorld()) {
                vanish();
                return;
            }
            active.face(t.getEyeLocation());
            // ambient unease around the watcher
            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                Location l = active.getLocation();
                l.getWorld().spawnParticle(Particle.SMOKE, l.clone().add(0, 1, 0), 2, 0.2, 0.5, 0.2, 0.0);
            }
            for (int i = 0; i < 2; i++) active.decrementStare(); // behaviour tick = 2 game ticks
            if (active.stareTicksLeft() <= 0) vanish();
            return;
        }

        long now = plugin.currentTick();
        if (now < nextStalkTick) return;
        nextStalkTick = now + cfg.stalkInterval();

        Player p = pickStalkTarget();
        if (p == null) return;
        double roll = cfg.stalkBaseChance() * weatherMultiplier(p)
                * (1.0 + plugin.threats().getThreat(p) / 100.0);
        if (ThreadLocalRandom.current().nextDouble() > roll) return;

        Location spot = pickSighting(p);
        if (spot == null) return;
        HerobrineEntity e = spawnStalker(p, spot);
        if (e != null) {
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.6f, 0.7f);
            plugin.log("Herobrine watches " + p.getName() + " (threat "
                    + (int) plugin.threats().getThreat(p) + ")");
        }
    }

    private void vanish() {
        if (!hasActive()) { active = null; return; }
        Location l = active.getLocation();
        if (l.getWorld() != null) {
            l.getWorld().spawnParticle(Particle.SMOKE, l.clone().add(0, 1, 0), 25, 0.3, 0.9, 0.3, 0.02);
            l.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.6f);
        }
        active.despawn();
        active = null;
    }

    private double weatherMultiplier(Player p) {
        double m = 1.0;
        long time = p.getWorld().getTime();
        if (time >= 13000 && time <= 23000) m *= cfg.nightMultiplier();
        if (p.getWorld().hasStorm() || p.getWorld().isThundering()) m *= cfg.stormMultiplier();
        return m;
    }

    private Player pickStalkTarget() {
        java.util.List<Player> players = new java.util.ArrayList<>(plugin.getServer().getOnlinePlayers());
        players.removeIf(p -> p.getGameMode() == org.bukkit.GameMode.CREATIVE
                || p.getGameMode() == org.bukkit.GameMode.SPECTATOR);
        if (players.isEmpty()) return null;
        return players.get(ThreadLocalRandom.current().nextInt(players.size()));
    }

    /** A spot 20–60 blocks from the player, on the ground, ideally within sight. */
    private Location pickSighting(Player p) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double dist = ThreadLocalRandom.current().nextDouble(cfg.stalkMin(), cfg.stalkMax());
            Location l = p.getLocation().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            Location ground = groundAt(l);
            if (ground != null) return ground;
        }
        return null;
    }

    private Location groundAt(Location l) {
        if (l.getWorld() == null) return null;
        int top = l.getWorld().getHighestBlockYAt(l);
        Location g = new Location(l.getWorld(), l.getBlockX() + 0.5, top + 1, l.getBlockZ() + 0.5);
        if (g.getBlock().isPassable() && g.clone().add(0, 1, 0).getBlock().isPassable()) return g;
        return null;
    }

    // ---- active hunting tick ----

    public void huntingTick() {
        if (!hasActive() || active.getMode() != HerobrineEntity.Mode.HUNTING) return;
        Player t = active.target();
        if (t == null || !t.isOnline()) {
            // target gone: hand off to next without resetting (handled by encounters)
            Player next = plugin.encounters().nextHuntTarget(t);
            if (next == null) { vanish(); return; }
            active.setTarget(next);
            t = next;
        }
        if (t.getWorld() != active.getLocation().getWorld()) {
            active.teleport(t.getLocation().add(0, 0, 3));
        }
        Location bl = active.getLocation();
        double dist = bl.getWorld() == t.getWorld() ? bl.distance(t.getLocation()) : 999;

        active.face(t.getEyeLocation());
        if (dist > 5) active.moveToward(t.getLocation());

        AbilityManager ab = plugin.abilities();
        long tick = plugin.currentTick();
        if (dist < 3 && tick % 20 == 0) {
            active.swing();
            t.damage(cfg.meleeDamage());
        }
        if (dist > 8 && tick % 100 == 0) ab.shadowTeleport(active, t);
        if (tick % 140 == 0) ab.darknessPulse(t.getLocation());
        if (tick % 80 == 0) plugin.hallucinations().fakeSounds(t);
        if (tick % 160 == 0) ab.blockManipulation(t);
        if ((t.getWorld().isThundering()) && tick % 120 == 0) ab.lightningStrike(t);
    }

    /** Switch the live stalker into an aggressive hunt against {@code target}. */
    public void beginHunt(Player target) {
        if (!cfg.huntingEnabled() || target == null) return;
        Location near = target.getLocation().add(target.getLocation().getDirection().multiply(-4));
        Location g = groundAt(near);
        HerobrineEntity e = (g != null) ? spawnStalker(target, g) : spawnStalker(target, near);
        if (e != null) {
            e.setMode(HerobrineEntity.Mode.HUNTING);
            target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1f, 0.5f);
            plugin.sendPrefixed(target, cfg.msg("hunt-begin", "&8You feel a presence closing in..."));
        }
    }

    // ---- combat: route player melee into logical HP ----

    /** Called from an arm-swing event; ray-traces to see if the player struck Herobrine. */
    public void tryHit(Player attacker, double damage) {
        if (!hasActive()) return;
        if (attacker.getWorld() != active.getLocation().getWorld()) return;
        Location eye = attacker.getEyeLocation();
        Vector dir = eye.getDirection();
        Location hb = active.getLocation().add(0, 1.0, 0); // mid-body
        // simple capsule check: distance from boss center to the aim ray, within reach
        Vector toBoss = hb.toVector().subtract(eye.toVector());
        double along = toBoss.dot(dir);
        if (along < 0 || along > 4.5) return;
        Vector closest = eye.toVector().add(dir.clone().multiply(along));
        if (closest.distance(hb.toVector()) > 1.0) return;

        boolean killed = active.damage(damage, attacker);
        if (killed) handleKill();
    }

    /**
     * External damage source (e.g. a KawaiiCompanion fighting on the owner's
     * behalf) — bypasses the ray-trace and applies straight to his logical HP.
     * Returns {@code true} if it killed him. Exposed to other plugins through
     * {@link HerobrineService}.
     */
    public boolean damageExternal(double amount, String sourceName) {
        if (!hasActive()) return false;
        boolean killed = active.damage(amount, null);
        if (killed) handleKill();
        return killed;
    }

    /** Shared death handling for any damage source. Assumes {@code active != null}. */
    private void handleKill() {
        if (active.getMode() == HerobrineEntity.Mode.BOSS) {
            plugin.boss().onBossDeath();
        } else {
            Location l = active.getLocation();
            l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 1, 0), 30, 0.4, 1, 0.4, 0.03);
            l.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_DEATH, 1f, 0.6f);
            clearActive();
        }
    }

    /** Re-send the NPC to a player who just moved into range (packets are not auto-tracked). */
    public void resendIfNear(Player p) {
        if (!hasActive()) return;
        if (p.getWorld() != active.getLocation().getWorld()) return;
        if (p.getLocation().distance(active.getLocation()) <= cfg.followRange()) {
            active.resendTo(p);
        }
    }
}
