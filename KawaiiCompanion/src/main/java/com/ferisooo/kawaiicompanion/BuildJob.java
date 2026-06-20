package com.ferisooo.kawaiicompanion;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Active build (or revert) state for a single player.
 *
 * <p>Lifecycle: created by {@link BuildManager#startBuild} or
 * {@link BuildManager#startRevert}, ticked by {@link BuildManager#tickAll}
 * each movement tick, and either runs to completion or is cancelled.
 *
 * <p>This is a pure data carrier — all stepping logic lives in
 * {@link BuildManager} so it has access to the world, the companion, and
 * the player for visual feedback (arm swings, chat bubbles, completion
 * messages).
 *
 * <p>Snapshots are used for two purposes:
 * <ul>
 *   <li><b>Build job</b> — each placed block appends its pre-build state to
 *       {@link #snapshots} so a later revert can restore the original block.</li>
 *   <li><b>Revert job</b> — shares the SAME snapshot list with its source
 *       build job (not a copy). Each restore pops the last entry off,
 *       so a cancelled partial revert leaves the still-placed snapshots
 *       in the list, ready for a follow-up revert.</li>
 * </ul>
 */
public final class BuildJob {

    /**
     * Step granularity. All three respect the same {@link #ticksPerStep}
     * delay; what changes is how much they place per step.
     */
    public enum Mode {
        /** One block per step. Slowest, most "watch her work" style. */
        BLOCK,
        /** One full X-axis row per step (a horizontal line). */
        LINE,
        /** One full Y layer (the entire X×Z plane at a given height) per step. */
        FULL
    }

    /** Snapshot of one block that was placed (or restored). */
    public static final class Snapshot {
        public final int worldX, worldY, worldZ;
        public final BlockData original;
        public final BlockData placed;
        public Snapshot(int x, int y, int z, BlockData original, BlockData placed) {
            this.worldX = x;
            this.worldY = y;
            this.worldZ = z;
            this.original = original;
            this.placed = placed;
        }
    }

    public final UUID ownerId;
    public final SchematicLoader.Schematic schematic;
    /** Bottom-NW-corner in world coordinates: schematic (0,0,0) → this. */
    public final Location origin;
    public final boolean isRevert;
    /** Snapshots — owned by the build job; SHARED by reference with a follow-up revert job. */
    public final List<Snapshot> snapshots;

    /** Step granularity. Can be flipped between modes mid-job. */
    public Mode mode;
    /** Delay between successive steps, in movement ticks. >=1. */
    public long ticksPerStep;
    /** Server tick at which the next step is allowed to fire. */
    public long nextActionTick;
    /** Build: linear schematic index already processed. Revert: snapshots popped. */
    public int progress;
    /** Set to true when the job has finished (or aborted) so the manager can clean up. */
    public boolean complete;

    /** Quarter-turn rotation around Y axis: 0, 90, 180, or 270 degrees. */
    public int rotation;

    public BuildJob(UUID ownerId, SchematicLoader.Schematic sch, Location origin,
                    Mode mode, long ticksPerStep, boolean isRevert,
                    List<Snapshot> sharedSnapshots) {
        this.ownerId = ownerId;
        this.schematic = sch;
        this.origin = origin;
        this.mode = mode;
        this.ticksPerStep = Math.max(1L, ticksPerStep);
        this.isRevert = isRevert;
        this.snapshots = sharedSnapshots != null ? sharedSnapshots : new ArrayList<>();
    }
}
