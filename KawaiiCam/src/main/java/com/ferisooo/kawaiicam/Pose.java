package com.ferisooo.kawaiicam;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** A camera transform: a world position plus a yaw/pitch facing. */
final class Pose {

    double x, y, z, yaw, pitch;

    Pose(double x, double y, double z, double yaw, double pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    static Pose of(Vector pos, double yaw, double pitch) {
        return new Pose(pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    Location toLocation(World w) {
        Location l = new Location(w, x, y, z);
        l.setYaw((float) yaw);
        l.setPitch((float) pitch);
        return l;
    }
}
