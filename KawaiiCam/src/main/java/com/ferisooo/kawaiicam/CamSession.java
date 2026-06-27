package com.ferisooo.kawaiicam;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * One running camera. Drives the operator's spectator view every tick — either
 * by asking the {@link ShotDirector} for a live cinematic pose (FOLLOW/SOLO) or
 * by stepping through a recorded track (PLAYBACK) — and restores the operator
 * when it ends.
 */
final class CamSession {

    enum Mode { FOLLOW, SOLO, PLAYBACK }

    private final KawaiiCam plugin;
    private final UUID operator;
    private final Mode mode;

    // FOLLOW
    private final UUID targetId;
    // SOLO
    private final Location anchor;
    // live director (FOLLOW/SOLO)
    private final ShotDirector director;
    // PLAYBACK
    private final Recorder.Recording playback;
    private int playIndex;
    private World playWorld;

    // recording (FOLLOW/SOLO only)
    Recorder recorder;
    String recordName;
    int maxFrames;

    private GameMode prevMode;
    private Location prevLoc;
    private ScheduledTask task;
    private Vector lastSubjectPos;

    // FOLLOW: cached hostile count, refreshed on a throttle instead of every tick.
    private static final int HOSTILE_REFRESH_TICKS = 20;
    private int hostileCooldown;
    private int cachedHostiles;

    private CamSession(KawaiiCam plugin, UUID operator, Mode mode, UUID targetId,
                       Location anchor, ShotDirector director, Recorder.Recording playback) {
        this.plugin = plugin;
        this.operator = operator;
        this.mode = mode;
        this.targetId = targetId;
        this.anchor = anchor;
        this.director = director;
        this.playback = playback;
    }

    static CamSession follow(KawaiiCam plugin, Player operator, Player target, ShotDirector dir) {
        return new CamSession(plugin, operator.getUniqueId(), Mode.FOLLOW,
                target.getUniqueId(), null, dir, null);
    }

    static CamSession solo(KawaiiCam plugin, Player operator, ShotDirector dir) {
        return new CamSession(plugin, operator.getUniqueId(), Mode.SOLO,
                null, operator.getLocation().clone(), dir, null);
    }

    static CamSession playback(KawaiiCam plugin, Player operator, Recorder.Recording rec) {
        return new CamSession(plugin, operator.getUniqueId(), Mode.PLAYBACK,
                null, null, null, rec);
    }

    UUID operator() { return operator; }
    Mode mode() { return mode; }
    boolean isRecording() { return recorder != null; }

    String shotName() {
        return director != null ? director.currentShot() : "playback";
    }

    void start() {
        Player op = Bukkit.getPlayer(operator);
        if (op == null) return;
        prevMode = op.getGameMode();
        prevLoc = op.getLocation().clone();
        op.setGameMode(GameMode.SPECTATOR);
        try { op.setSpectatorTarget(null); } catch (Throwable ignored) {}
        if (mode == Mode.PLAYBACK && playback != null) {
            playWorld = Bukkit.getWorld(playback.world);
        }
        // Folia-safe: the camera drives the OPERATOR every tick (teleports them,
        // sets their pose/gamemode), so it runs on the operator's own entity
        // scheduler — that entity may only be touched from its region thread.
        // EntityScheduler delays must be >= 1; period stays 1 tick as before.
        task = op.getScheduler().runAtFixedRate(plugin, t -> tick(), null, 1L, 1L);
    }

    private void tick() {
        Player op = Bukkit.getPlayer(operator);
        if (op == null) { plugin.endSession(this, false); return; }

        switch (mode) {
            case FOLLOW:   tickFollow(op);   break;
            case SOLO:     tickSolo(op);     break;
            case PLAYBACK: tickPlayback(op); break;
        }
    }

    private void tickFollow(Player op) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            op.sendMessage("§d(✧) the subject left — stopping the camera~");
            plugin.endSession(this, true);
            return;
        }
        Location eye = target.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) return;

        Vector feet = target.getLocation().toVector();
        double speed = lastSubjectPos == null ? 0 : feet.distance(lastSubjectPos) * 20.0;
        lastSubjectPos = feet;

        ShotDirector.Subject subject = new ShotDirector.Subject(
                eye, target.getLocation().getYaw(), target.getEyeHeight(),
                speed, hostiles(target));

        apply(op, world, director.tick(subject));
    }

    private void tickSolo(Player op) {
        World world = anchor.getWorld();
        if (world == null) { plugin.endSession(this, true); return; }
        // Aim at a point at roughly eye height above the anchor spot.
        Location eye = anchor.clone().add(0, 1.62, 0);
        ShotDirector.Subject subject = new ShotDirector.Subject(
                eye, anchor.getYaw(), 1.62, 0, 0);
        apply(op, world, director.tick(subject));
    }

    private void tickPlayback(Player op) {
        if (playback == null || playWorld == null
                || playIndex >= playback.frames.size()) {
            op.sendMessage("§d(✧) playback finished ✨");
            plugin.endSession(this, true);
            return;
        }
        Recorder.Frame f = playback.frames.get(playIndex++);
        Location l = new Location(playWorld, f.x, f.y, f.z, (float) f.yaw, (float) f.pitch);
        op.teleportAsync(l);
    }

    private void apply(Player op, World world, Pose pose) {
        op.teleportAsync(pose.toLocation(world));
        if (recorder != null) {
            recorder.add(pose, director.currentShot());
            if (maxFrames > 0 && recorder.frameCount() >= maxFrames) {
                op.sendMessage("§d(✧) recording hit the length cap — saving~");
                plugin.finishRecording(this);
            }
        }
    }

    /** Restore the operator's gamemode + position. Safe to call once. */
    void restore(boolean teleportBack) {
        if (task != null) { task.cancel(); task = null; }
        Player op = Bukkit.getPlayer(operator);
        if (op == null) return;
        if (prevMode != null) op.setGameMode(prevMode);
        if (teleportBack && prevLoc != null) op.teleportAsync(prevLoc);
    }

    /**
     * Nearby hostile count, refreshed only every {@link #HOSTILE_REFRESH_TICKS}
     * ticks. The director consumes this just when it cuts to a new shot (every
     * few seconds), so a slightly stale count is harmless and avoids a costly
     * entity scan on every tick.
     */
    private int hostiles(Player target) {
        if (hostileCooldown <= 0) {
            cachedHostiles = countHostiles(target);
            hostileCooldown = HOSTILE_REFRESH_TICKS;
        }
        hostileCooldown--;
        return cachedHostiles;
    }

    private static int countHostiles(Player target) {
        int n = 0;
        for (Entity e : target.getNearbyEntities(12, 8, 12)) {
            if (e instanceof Monster) n++;
        }
        return n;
    }
}
