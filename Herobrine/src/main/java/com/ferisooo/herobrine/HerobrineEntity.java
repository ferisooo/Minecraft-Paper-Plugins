package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * High-level Herobrine: wraps the visual {@link HerobrineNms} packet NPC and
 * adds the game-logic the NPC itself has no concept of — 500 HP tracked
 * logically, a current target, movement speed, full knockback resistance
 * (the NPC has no physics so knockback is simply ignored), and a behaviour
 * mode that the managers drive.
 *
 * <p>Because the packet NPC has no real hitbox, melee damage from a player is
 * detected by {@link HerobrineManager} via an arm-swing ray-trace and routed
 * here through {@link #damage}.
 */
public final class HerobrineEntity {

    public enum Mode { STALKING, HUNTING, BOSS }

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;
    private HerobrineNms nms;

    private UUID targetId;
    private Mode mode = Mode.STALKING;
    private double health;
    private final double maxHealth;
    private int stareTicksLeft;
    private long lastDamageTick;

    public HerobrineEntity(HerobrinePlugin plugin, HerobrineNms nms, double maxHealth) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
        this.nms = nms;
        this.maxHealth = maxHealth;
        this.health = maxHealth;
    }

    public HerobrineNms nms() { return nms; }
    public Location getLocation() { return nms.getLocation(); }
    public int getEntityId() { return nms.getEntityId(); }
    public boolean isDead() { return nms == null || nms.isDead(); }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public double getHealth() { return health; }
    public double getMaxHealth() { return maxHealth; }
    public void heal(double amount) { health = Math.min(maxHealth, health + amount); }

    public UUID getTargetId() { return targetId; }
    public void setTarget(Player p) { this.targetId = p == null ? null : p.getUniqueId(); }

    public Player target() {
        return targetId == null ? null : plugin.getServer().getPlayer(targetId);
    }

    public void setStareTicks(int ticks) { this.stareTicksLeft = ticks; }
    public int stareTicksLeft() { return stareTicksLeft; }
    public void decrementStare() { if (stareTicksLeft > 0) stareTicksLeft--; }

    /** Apply logical damage. Returns {@code true} if this killed Herobrine. */
    public boolean damage(double amount, Player source) {
        health -= amount;
        lastDamageTick = plugin.currentTick();
        Location l = getLocation();
        if (l.getWorld() != null) {
            l.getWorld().spawnParticle(Particle.SMOKE, l.clone().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.02);
            l.getWorld().playSound(l, Sound.ENTITY_PLAYER_HURT, 0.8f, 0.6f);
        }
        return health <= 0;
    }

    public long lastDamageTick() { return lastDamageTick; }

    /** Move toward a location at the configured speed (block-per-tick budget). */
    public void moveToward(Location dest) {
        if (isDead() || dest.getWorld() == null) return;
        Location cur = getLocation();
        if (cur.getWorld() != dest.getWorld()) { nms.teleport(dest); return; }
        double dx = dest.getX() - cur.getX();
        double dy = dest.getY() - cur.getY();
        double dz = dest.getZ() - cur.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.05) { face(dest); return; }
        double step = Math.min(cfg.speed(), dist);
        double nx = cur.getX() + dx / dist * step;
        double ny = cur.getY() + dy / dist * step;
        double nz = cur.getZ() + dz / dist * step;
        Location next = new Location(cur.getWorld(), nx, ny, nz);
        // face movement direction
        next.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        next.setPitch(0f);
        nms.smoothMoveTo(next);
    }

    public void face(Location target) {
        if (!isDead()) nms.lookAt(target);
    }

    public void teleport(Location loc) { if (!isDead()) nms.teleport(loc); }
    public void swing() { if (!isDead()) nms.swing(); }

    public void resendTo(Player p) { if (!isDead()) nms.resendTo(p); }

    public void despawn() {
        if (nms != null) nms.despawn();
    }
}
