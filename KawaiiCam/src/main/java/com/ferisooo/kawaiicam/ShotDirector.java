package com.ferisooo.kawaiicam;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The "director" — it decides, on its own, how to film the subject. Every tick
 * it reports the subject's state ({@link Subject}); the director holds a current
 * shot, computes where the camera should be, eases toward it, keeps it from
 * clipping into terrain, and cuts to a new, context-appropriate shot when the
 * current one has run its length.
 */
final class ShotDirector {

    /** What the camera is filming this tick. */
    static final class Subject {
        final Location eye;     // subject eye location (world + position)
        final double yaw;       // subject facing (degrees)
        final double eyeHeight; // eye height above feet
        final double speed;     // blocks/second (horizontal-ish)
        final int hostiles;     // nearby hostile mobs

        Subject(Location eye, double yaw, double eyeHeight, double speed, int hostiles) {
            this.eye = eye; this.yaw = yaw; this.eyeHeight = eyeHeight;
            this.speed = speed; this.hostiles = hostiles;
        }
    }

    enum Style { CHILL, ACTION, EPIC }

    private enum ShotType { ORBIT, DOLLY_IN, PULL_BACK, CRANE_UP, LOW_HERO, TRACK_SIDE, OVER_SHOULDER }

    private final Style style;
    private final double posSmoothing;
    private final double rotSmoothing;
    private final boolean collision;
    private final int minTicks;
    private final int maxTicks;
    private final Random rng = new Random();

    // Current shot parameters.
    private ShotType type;
    private int elapsed;
    private int duration;
    private double baseAngle;   // radians
    private double dist;
    private double height;
    private double angleSpeed;  // rad/sec for orbit/hero
    private double distSpeed;   // blocks/sec for dolly/pull
    private double craneSpeed;  // blocks/sec for crane
    private double dir = 1;     // orbit direction
    private double side = 1;    // left/right bias
    private double thirdsNudge; // rule-of-thirds yaw offset (degrees)

    private Pose current; // smoothed, ready-to-apply pose

    ShotDirector(Style style, double posSmoothing, double rotSmoothing,
                 boolean collision, int minTicks, int maxTicks) {
        this.style = style;
        this.posSmoothing = posSmoothing;
        this.rotSmoothing = rotSmoothing;
        this.collision = collision;
        this.minTicks = Math.max(20, minTicks);
        this.maxTicks = Math.max(this.minTicks, maxTicks);
    }

    String currentShot() {
        return type == null ? "—" : type.name().toLowerCase().replace('_', ' ');
    }

    /** Advance one tick and return the smoothed camera pose to apply. */
    Pose tick(Subject s) {
        if (type == null || elapsed >= duration) chooseShot(s);
        elapsed++;

        Pose target = targetPose(s);

        if (current == null) {
            current = target; // snap on the first frame of a session
        } else {
            current.x = CamMath.lerp(current.x, target.x, posSmoothing);
            current.y = CamMath.lerp(current.y, target.y, posSmoothing);
            current.z = CamMath.lerp(current.z, target.z, posSmoothing);
            current.yaw = CamMath.lerpAngle(current.yaw, target.yaw, rotSmoothing);
            current.pitch = CamMath.lerp(current.pitch, target.pitch, rotSmoothing);
        }
        return current;
    }

    // ---- shot geometry ----

    private Pose targetPose(Subject s) {
        double t = elapsed / 20.0;
        Vector eye = s.eye.toVector();
        double yawRad = Math.toRadians(s.yaw);
        Vector forward = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vector right = new Vector(forward.getZ(), 0, -forward.getX());

        Vector cam;
        Vector lookAt = eye.clone();
        switch (type) {
            case DOLLY_IN: {
                double d = Math.max(2.2, dist - distSpeed * t);
                cam = eye.clone().add(new Vector(Math.cos(baseAngle) * d, height, Math.sin(baseAngle) * d));
                break;
            }
            case PULL_BACK: {
                double d = dist + distSpeed * t;
                cam = eye.clone().add(new Vector(Math.cos(baseAngle) * d, height, Math.sin(baseAngle) * d));
                break;
            }
            case CRANE_UP: {
                double h = height + craneSpeed * t;
                cam = eye.clone().add(new Vector(Math.cos(baseAngle) * dist, h, Math.sin(baseAngle) * dist));
                break;
            }
            case LOW_HERO: {
                double a = baseAngle + angleSpeed * t * dir;
                cam = eye.clone().add(new Vector(Math.cos(a) * dist, -s.eyeHeight + 0.4, Math.sin(a) * dist));
                break;
            }
            case TRACK_SIDE: {
                cam = eye.clone()
                        .add(right.clone().multiply(dist * side))
                        .add(forward.clone().multiply(-1.0))
                        .add(new Vector(0, height, 0));
                break;
            }
            case OVER_SHOULDER: {
                cam = eye.clone()
                        .add(forward.clone().multiply(-dist * 0.6))
                        .add(right.clone().multiply(0.9 * side))
                        .add(new Vector(0, height, 0));
                lookAt = eye.clone().add(forward.clone().multiply(5));
                break;
            }
            case ORBIT:
            default: {
                double a = baseAngle + angleSpeed * t * dir;
                cam = eye.clone().add(new Vector(Math.cos(a) * dist, height, Math.sin(a) * dist));
            }
        }

        if (collision) cam = avoidWalls(s.eye, cam);

        double[] yp = CamMath.look(cam, lookAt);
        return Pose.of(cam, yp[0] + thirdsNudge, yp[1]);
    }

    /** Pull the camera in if terrain sits between it and the subject. */
    private Vector avoidWalls(Location eye, Vector cam) {
        Vector from = eye.toVector();
        Vector delta = cam.clone().subtract(from);
        double dlen = delta.length();
        if (dlen < 0.3 || eye.getWorld() == null) return cam;
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                eye, delta.clone().normalize(), dlen, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition() == null) return cam;
        Vector p = hit.getHitPosition();
        // Step back 0.4 toward the subject so we sit just inside the wall.
        Vector back = from.clone().subtract(p).normalize().multiply(0.4);
        return p.add(back);
    }

    // ---- shot selection (the "thinks for itself" bit) ----

    private void chooseShot(Subject s) {
        ShotType previous = type;
        List<ShotType> pool = new ArrayList<>();
        if (s.hostiles > 0) {                 // combat → punchy, low, tracking
            add(pool, ShotType.LOW_HERO, 3);
            add(pool, ShotType.TRACK_SIDE, 3);
            add(pool, ShotType.ORBIT, 2);
            add(pool, ShotType.OVER_SHOULDER, 2);
        } else if (s.speed > 3.0) {           // moving → follow the motion
            add(pool, ShotType.TRACK_SIDE, 3);
            add(pool, ShotType.OVER_SHOULDER, 3);
            add(pool, ShotType.PULL_BACK, 2);
            add(pool, ShotType.ORBIT, 1);
        } else {                              // idle → slow, scenic
            add(pool, ShotType.ORBIT, 3);
            add(pool, ShotType.CRANE_UP, 2);
            add(pool, ShotType.DOLLY_IN, 2);
            add(pool, ShotType.PULL_BACK, 1);
            add(pool, ShotType.LOW_HERO, 1);
        }
        // Avoid repeating the same shot back-to-back when we have alternatives.
        ShotType pick;
        do {
            pick = pool.get(rng.nextInt(pool.size()));
        } while (pick == previous && pool.size() > 1 && rng.nextInt(3) != 0);
        type = pick;

        elapsed = 0;
        duration = minTicks + rng.nextInt(maxTicks - minTicks + 1);
        baseAngle = rng.nextDouble() * Math.PI * 2.0;
        dir = rng.nextBoolean() ? 1 : -1;
        side = rng.nextBoolean() ? 1 : -1;
        thirdsNudge = (rng.nextBoolean() ? 1 : -1) * (2.5 + rng.nextDouble() * 2.0);

        double[] dr = distanceRange();
        dist = dr[0] + rng.nextDouble() * (dr[1] - dr[0]);
        height = 1.0 + rng.nextDouble() * 2.2;
        angleSpeed = (0.18 + rng.nextDouble() * 0.35) * speedScale();
        // Dolly/pull should roughly travel a few blocks over the shot's length.
        double secs = duration / 20.0;
        distSpeed = (dist * 0.5) / secs;
        craneSpeed = (1.5 + rng.nextDouble() * 2.5);

        if (type == ShotType.LOW_HERO) {
            dist = Math.max(2.5, dist * 0.6);
            angleSpeed *= 0.5;
        }
        if (type == ShotType.CRANE_UP) {
            height = 1.0; // start low, rise
        }
    }

    private double[] distanceRange() {
        switch (style) {
            case CHILL:  return new double[] { 6.0, 10.0 };
            case ACTION: return new double[] { 3.5, 7.0 };
            case EPIC:
            default:     return new double[] { 5.0, 11.0 };
        }
    }

    private double speedScale() {
        switch (style) {
            case CHILL:  return 0.7;
            case ACTION: return 1.5;
            case EPIC:
            default:     return 1.0;
        }
    }

    private static void add(List<ShotType> pool, ShotType type, int weight) {
        for (int i = 0; i < weight; i++) pool.add(type);
    }
}
