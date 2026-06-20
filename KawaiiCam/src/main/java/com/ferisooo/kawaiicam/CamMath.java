package com.ferisooo.kawaiicam;

import org.bukkit.util.Vector;

/** Small math helpers for camera interpolation and aiming. */
final class CamMath {

    private CamMath() {}

    /** Plain linear interpolation. */
    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Interpolate between two angles (degrees) along the shortest arc, so a
     * camera panning past the ±180° seam doesn't whip the long way around.
     */
    static double lerpAngle(double a, double b, double t) {
        double diff = ((b - a + 540.0) % 360.0) - 180.0; // shortest signed delta
        return a + diff * t;
    }

    /**
     * Yaw/pitch (Minecraft convention, degrees) needed to look from {@code from}
     * toward {@code to}. Returns {yaw, pitch}.
     */
    static double[] look(Vector from, Vector to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double pitch = Math.toDegrees(-Math.atan2(dy, horiz));
        return new double[] { yaw, pitch };
    }
}
