package com.ferisooo.kawaiicompanion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every active build / revert job and the per-player session state
 * used by the build configuration GUI.
 *
 * <p>Threading: every method here runs on the main server thread. Block
 * placement, inventory creation, and player messaging all require the
 * main thread anyway, so no synchronization is needed for the maps.
 *
 * <p>Memory: snapshots are kept in memory for the lifetime of the player's
 * "last build". A 64×64×64 schematic of solid material is ~262k snapshots
 * ≈ 10 MB; bigger schematics will use proportionally more. We don't
 * persist these across server restarts (revert only works in the same
 * session as the build).
 */
public final class BuildManager {

    /** Title prefix used to identify our build GUIs in click events. */
    public static final String GUI_TITLE = "✿ Build";

    /** Allowed values for the delay-cycle button (in movement ticks). */
    private static final long[] DELAY_CYCLE = {1L, 2L, 5L, 10L, 20L, 40L};

    private final KawaiiCompanion plugin;
    private final File schemFolder;

    /** Currently-running jobs (build or revert), keyed by owner UUID. */
    private final Map<UUID, BuildJob> activeJobs = new HashMap<>();
    /** Most recent COMPLETED build per player. Holds the snapshots for revert. */
    private final Map<UUID, BuildJob> lastBuild = new HashMap<>();
    /** Per-player config session — what they've picked in the GUI but not yet started. */
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Players currently looking at a build GUI (used by the click handler). */
    private final Map<UUID, Boolean> guiOpen = new HashMap<>();

    public BuildManager(KawaiiCompanion plugin) {
        this.plugin = plugin;
        this.schemFolder = new File(plugin.getDataFolder(), "schematics");
        if (!schemFolder.exists() && !schemFolder.mkdirs()) {
            plugin.getLogger().warning("(✧) Failed to create schematics/ folder");
        }
    }

    /** Per-player draft settings for the next build. Default values match the GUI defaults. */
    static final class Session {
        File selectedFile;
        BuildJob.Mode buildMode = BuildJob.Mode.LINE;
        BuildJob.Mode revertMode = BuildJob.Mode.LINE;
        long delayTicks = 5L;
        /** false = build at companion's location, true = at player's location. */
        boolean originAtPlayer = false;
        int offsetX, offsetY, offsetZ;
        /** Quarter-turn rotation around Y axis: 0, 90, 180, or 270. */
        int rotation;
        int page;

        /** True while a client-side ghost preview is shown to the owner. */
        boolean previewActive;
        /** Block locations currently shown as ghosts (client-side). Used
         *  to revert them when the preview is cleared. */
        List<Location> previewedBlocks = new ArrayList<>();
        /** Origin location the current preview was built at — used to
         *  detect when offset changes and we need to re-render. */
        Location previewOrigin;
    }

    private Session sessionFor(UUID id) {
        return sessions.computeIfAbsent(id, k -> new Session());
    }

    // ====================================================================
    // ============== JOB LIFECYCLE =======================================
    // ====================================================================

    public boolean hasActiveJob(UUID id) {
        BuildJob j = activeJobs.get(id);
        return j != null && !j.complete;
    }

    public boolean canRevert(UUID id) {
        BuildJob last = lastBuild.get(id);
        return last != null && !last.snapshots.isEmpty();
    }

    /** Start a new build for {@code owner}. Returns false if a job is already active or load failed. */
    public boolean startBuild(Player owner, File schemFile, Location origin,
                              BuildJob.Mode mode, long ticksPerStep, int rotation) {
        UUID id = owner.getUniqueId();
        if (hasActiveJob(id)) {
            owner.sendMessage("§c(✧) A build is already running — cancel it first ~");
            return false;
        }
        SchematicLoader.Schematic sch;
        try {
            sch = SchematicLoader.load(schemFile);
        } catch (Exception e) {
            owner.sendMessage("§c(✧) Failed to load " + schemFile.getName() + ": " + e.getMessage());
            plugin.getLogger().warning("(✧) Schematic load failed for " + schemFile.getName() + ": " + e);
            return false;
        }
        // Snap origin to block coords so the build lines up cleanly with
        // the world grid. We use the player's block location (feet position).
        Location snapped = origin.getBlock().getLocation();
        BuildJob job = new BuildJob(id, sch, snapped, mode, ticksPerStep, false, null);
        job.rotation = ((rotation % 360) + 360) % 360;
        activeJobs.put(id, job);

        // Target-space dimensions for ETA + centering.
        int rW = rotatedWidth(sch, job.rotation);
        int rL = rotatedLength(sch, job.rotation);
        int rH = sch.height;

        // She's busy now — suppress follow/guard for the duration of the build.
        plugin.setCompanionBuilding(id, true);
        // Park her at the build origin facing into the schematic so the
        // first placement reads naturally even before the per-block teleport
        // logic kicks in.
        Location standInitial = snapped.clone().add(0.5, 0, 0.5);
        Location lookInitial  = snapped.clone().add(rW / 2.0, rH / 2.0, rL / 2.0);
        plugin.teleportCompanionForBuild(id, standInitial, lookInitial);

        // Estimate completion time so the user knows what they're in for.
        int total = rW * rH * rL;
        long stepsTotal;
        switch (mode) {
            case BLOCK -> stepsTotal = total;
            case LINE  -> stepsTotal = (long) rH * rL;
            case FULL  -> stepsTotal = rH;
            default    -> stepsTotal = total;
        }
        long etaTicks = stepsTotal * ticksPerStep;
        long etaSeconds = etaTicks / 20L;
        String eta = formatDuration(etaSeconds);

        owner.sendMessage("§a✿ §dBuilding §f" + sch.name + " §7(" + rW + "×"
                + rH + "×" + rL + ", rot " + job.rotation + "°) §d~");
        owner.sendMessage("§7  mode: §f" + mode.name().toLowerCase()
                + " §7~ delay: §f" + ticksPerStep + "t §7~ ETA: §f" + eta);
        if (etaSeconds > 600) {
            owner.sendMessage("§e(✧) That's a big build — try §fFull §emode for layer-by-layer speed!");
        }
        return true;
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 86400) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        return (seconds / 86400) + "d " + ((seconds % 86400) / 3600) + "h";
    }

    /**
     * Pick a passable stand-spot adjacent to the block at (bx,by,bz). Tries
     * each of the four cardinal neighbours at the same Y; on a non-air
     * neighbour, also tries one block higher (so she can stand on the
     * adjacent block looking down at the placement). Falls back to the
     * block's own position if nothing else works — she may briefly clip
     * into a placed block, which is fine for a 1-tick visual.
     */
    // ====================================================================
    // ============== ROTATION HELPERS ====================================
    // ====================================================================

    /** Width of the schematic after applying a Y-axis quarter-turn rotation. */
    private static int rotatedWidth(SchematicLoader.Schematic sch, int rotation) {
        return (rotation == 90 || rotation == 270) ? sch.length : sch.width;
    }

    /** Length of the schematic after applying a Y-axis quarter-turn rotation. */
    private static int rotatedLength(SchematicLoader.Schematic sch, int rotation) {
        return (rotation == 90 || rotation == 270) ? sch.width : sch.length;
    }

    /**
     * Look up the source-schematic block for a given <em>target</em> cell after
     * applying a Y-axis quarter-turn. The returned BlockData also has its
     * facing properties rotated so stairs/doors/etc point the right way.
     *
     * <p>Coordinate transform (target → source), with source dims (W, L):
     * <ul>
     *   <li>  0° → src(tx, ty, tz)</li>
     *   <li> 90° → src(tz, ty, L-1-tx)</li>
     *   <li>180° → src(W-1-tx, ty, L-1-tz)</li>
     *   <li>270° → src(W-1-tz, ty, tx)</li>
     * </ul>
     */
    private static BlockData rotatedBlockAt(SchematicLoader.Schematic sch, int rotation,
                                            int tx, int ty, int tz) {
        int sx, sz;
        switch (rotation) {
            case 90:  sx = tz;                sz = sch.length - 1 - tx; break;
            case 180: sx = sch.width - 1 - tx; sz = sch.length - 1 - tz; break;
            case 270: sx = sch.width - 1 - tz; sz = tx;                  break;
            default:  sx = tx;                sz = tz;                   break;
        }
        BlockData bd = sch.blockAt(sx, ty, sz);
        if (bd == null) return null;
        if (rotation == 0) return bd;
        return rotateBlockData(bd, rotation);
    }

    /**
     * Rotate the directional / rotatable / multi-facing properties of a
     * {@link BlockData} by the given quarter-turn. Returns a clone with the
     * rotation applied; the input is untouched.
     */
    private static BlockData rotateBlockData(BlockData bd, int rotation) {
        if (rotation == 0) return bd;
        BlockData out = bd.clone();

        if (out instanceof Directional dir) {
            BlockFace rotated = rotateFace(dir.getFacing(), rotation);
            if (rotated != null && dir.getFaces().contains(rotated)) {
                dir.setFacing(rotated);
            }
        }
        if (out instanceof Rotatable rot) {
            BlockFace rotated = rotateFace(rot.getRotation(), rotation);
            if (rotated != null) {
                try { rot.setRotation(rotated); } catch (Throwable ignored) {}
            }
        }
        if (out instanceof MultipleFacing mf) {
            // Snapshot which faces are currently true, clear them all, then
            // re-set the rotated equivalents. Up/down pass through untouched.
            Set<BlockFace> currentlyOn = new HashSet<>(mf.getFaces());
            for (BlockFace f : mf.getAllowedFaces()) {
                try { mf.setFace(f, false); } catch (Throwable ignored) {}
            }
            for (BlockFace f : currentlyOn) {
                BlockFace target = rotateFace(f, rotation);
                if (target != null && mf.getAllowedFaces().contains(target)) {
                    try { mf.setFace(target, true); } catch (Throwable ignored) {}
                }
            }
        }
        return out;
    }

    /** Rotate a {@link BlockFace} by a Y-axis quarter-turn. Up/down pass through unchanged. */
    private static BlockFace rotateFace(BlockFace face, int rotation) {
        if (face == BlockFace.UP || face == BlockFace.DOWN || face == BlockFace.SELF) return face;
        // CW cycle around Y when looking down: N → E → S → W → N
        BlockFace[] cycle = { BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST };
        int idx = -1;
        for (int i = 0; i < cycle.length; i++) if (cycle[i] == face) { idx = i; break; }
        if (idx == -1) return face; // diagonal / unsupported — leave alone
        int turns = ((rotation / 90) % 4 + 4) % 4;
        return cycle[(idx + turns) % 4];
    }

    private Location pickStandSpot(World w, int bx, int by, int bz) {
        int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] d : dirs) {
            int sx = bx + d[0];
            int sz = bz + d[1];
            // Same Y, two blocks of air (feet + head)
            if (isPassable(w, sx, by, sz) && isPassable(w, sx, by + 1, sz)) {
                return new Location(w, sx + 0.5, by, sz + 0.5);
            }
        }
        for (int[] d : dirs) {
            int sx = bx + d[0];
            int sz = bz + d[1];
            // One up — stand on top of the neighbour
            if (isPassable(w, sx, by + 1, sz) && isPassable(w, sx, by + 2, sz)) {
                return new Location(w, sx + 0.5, by + 1, sz + 0.5);
            }
        }
        return new Location(w, bx + 0.5, by + 1, bz + 0.5);
    }

    private static boolean isPassable(World w, int x, int y, int z) {
        try {
            return w.getBlockAt(x, y, z).isPassable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Start a revert of the player's last completed build. The revert job
     * SHARES the snapshot list with {@link #lastBuild}, so partial reverts
     * leave un-reverted snapshots behind and can be resumed later.
     */
    public boolean startRevert(Player owner, BuildJob.Mode mode, long ticksPerStep) {
        UUID id = owner.getUniqueId();
        if (hasActiveJob(id)) {
            owner.sendMessage("§c(✧) Cancel the current job first ~");
            return false;
        }
        BuildJob last = lastBuild.get(id);
        if (last == null || last.snapshots.isEmpty()) {
            owner.sendMessage("§c(✧) Nothing to revert ~");
            return false;
        }
        // Share snapshots list — revert pops from the end as it goes.
        BuildJob revert = new BuildJob(id, last.schematic, last.origin,
                mode, ticksPerStep, true, last.snapshots);
        activeJobs.put(id, revert);
        owner.sendMessage("§d(✧) Reverting §f" + last.snapshots.size() + "§d blocks ~");
        return true;
    }

    public boolean cancel(UUID id) {
        BuildJob job = activeJobs.remove(id);
        if (job == null) return false;
        plugin.setCompanionBuilding(id, false);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.sendMessage(job.isRevert
                    ? "§d(✧) Revert paused — " + job.snapshots.size() + " blocks remain ~"
                    : "§d(✧) Build cancelled — " + job.snapshots.size() + " blocks placed (revertable) ~");
        }
        // For a partial build, save it to lastBuild anyway so the player
        // can revert what was placed before they cancelled.
        if (!job.isRevert && !job.snapshots.isEmpty()) {
            lastBuild.put(id, job);
        }
        return true;
    }

    /** Called every movement tick. Cheap when no jobs are active. */
    public void tickAll(long tick) {
        if (activeJobs.isEmpty()) return;
        List<UUID> finished = null;
        for (Map.Entry<UUID, BuildJob> entry : activeJobs.entrySet()) {
            BuildJob job = entry.getValue();
            if (job.complete) {
                if (finished == null) finished = new ArrayList<>();
                finished.add(entry.getKey());
                continue;
            }
            if (tick < job.nextActionTick) continue;
            try {
                if (job.isRevert) tickRevert(job);
                else              tickBuild(job);
            } catch (Throwable t) {
                plugin.getLogger().warning("(✧) Build tick failed: " + t.getMessage());
                job.complete = true;
            }
            job.nextActionTick = tick + job.ticksPerStep;
        }
        if (finished != null) {
            for (UUID id : finished) finalizeJob(id);
        }
    }

    private void finalizeJob(UUID id) {
        BuildJob job = activeJobs.remove(id);
        if (job == null) return;
        // Release her from build-stand mode so she resumes normal behavior.
        plugin.setCompanionBuilding(id, false);
        Player p = Bukkit.getPlayer(id);
        if (job.isRevert) {
            if (p != null) p.sendMessage("§d(✧) Revert complete ~ ✨");
            // If the revert exhausted the snapshot list, drop the lastBuild
            // entry so canRevert() returns false. A partial revert leaves
            // the trimmed list ready for another pass.
            if (job.snapshots.isEmpty()) lastBuild.remove(id);
        } else {
            if (p != null) p.sendMessage("§d(✧) Build complete ~ ✨ §7("
                    + job.snapshots.size() + " blocks placed)");
            lastBuild.put(id, job);
        }
    }

    // ====================================================================
    // ============== BUILD / REVERT STEP =================================
    // ====================================================================

    private void tickBuild(BuildJob job) {
        World w = job.origin.getWorld();
        if (w == null) { job.complete = true; return; }
        SchematicLoader.Schematic sch = job.schematic;
        // After Y-axis rotation the iteration space is sized differently —
        // 90°/270° swap width and length. totalCells stays the same.
        int rW = rotatedWidth(sch, job.rotation);
        int rL = rotatedLength(sch, job.rotation);
        int rH = sch.height;
        int total = rW * rH * rL;
        if (job.progress >= total) { job.complete = true; return; }

        // How many linear indices to process this step. Iteration order in
        // target space is Y outer → Z middle → X inner, so:
        //   BLOCK = 1
        //   LINE  = rW (one full X-row in target space)
        //   FULL  = rW * rL (one full Y-layer in target space)
        int step;
        switch (job.mode) {
            case BLOCK -> step = 1;
            case LINE  -> step = rW;
            case FULL  -> step = rW * rL;
            default    -> step = 1;
        }

        // BLOCK mode special handling: most schematic cells are air, so a
        // straight 1-cell-per-step approach makes her freeze for many ticks
        // between visible placements. Instead, skip forward to the next
        // non-air cell, walk to it, and place that one. Every tick is a
        // visible action.
        if (job.mode == BuildJob.Mode.BLOCK) {
            int nextSolid = -1;
            for (int i = job.progress; i < total; i++) {
                int y = i / (rW * rL);
                int rem = i - y * rW * rL;
                int z = rem / rW;
                int x = rem - z * rW;
                BlockData bd = rotatedBlockAt(sch, job.rotation, x, y, z);
                if (bd == null) continue;
                if (bd.getMaterial().isAir()) continue;
                nextSolid = i;
                break;
            }
            if (nextSolid < 0) {
                // No solids left — finish.
                job.progress = total;
                job.complete = true;
                sendProgress(job);
                return;
            }
            int y = nextSolid / (rW * rL);
            int rem = nextSolid - y * rW * rL;
            int z = rem / rW;
            int x = rem - z * rW;
            int wx = job.origin.getBlockX() + x;
            int wy = job.origin.getBlockY() + y;
            int wz = job.origin.getBlockZ() + z;
            Location placeLoc = new Location(w, wx + 0.5, wy, wz + 0.5);
            Location stand = pickStandSpot(w, wx, wy, wz);
            plugin.teleportCompanionForBuild(job.ownerId, stand, placeLoc);

            int snapshotsBefore = job.snapshots.size();
            placeOneBlock(job, w, x, y, z);
            job.progress = nextSolid + 1;
            if (job.progress >= total) job.complete = true;

            if (job.snapshots.size() > snapshotsBefore) {
                plugin.swingCompanion(job.ownerId);
            }
            sendProgress(job);
            return;
        }

        int end = Math.min(total, job.progress + step);
        int snapshotsBefore = job.snapshots.size();

        for (int i = job.progress; i < end; i++) {
            int y = i / (rW * rL);
            int rem = i - y * rW * rL;
            int z = rem / rW;
            int x = rem - z * rW;
            placeOneBlock(job, w, x, y, z);
        }
        job.progress = end;
        if (job.progress >= total) job.complete = true;

        // Visual + progress feedback once per STEP (not per block — FULL
        // mode would otherwise fire thousands of swings/sec).
        if (job.snapshots.size() > snapshotsBefore) {
            plugin.swingCompanion(job.ownerId);
        }
        sendProgress(job);
    }

    /** Send action-bar progress update to the build's owner. Cheap on the wire. */
    private void sendProgress(BuildJob job) {
        Player p = Bukkit.getPlayer(job.ownerId);
        if (p == null) return;
        int total = job.isRevert ? job.snapshots.size() + job.progress : job.schematic.totalCells();
        int done  = job.isRevert ? job.progress : job.progress;
        int pct = total == 0 ? 100 : (int) ((done * 100L) / total);
        String label = job.isRevert ? "Reverting" : "Building";
        String bar = progressBar(pct);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                "§d✿ " + label + " " + job.schematic.name
                + " §7" + bar + " §f" + pct + "% §7("
                + done + "/" + total + ")"));
    }

    private static String progressBar(int pct) {
        int filled = Math.max(0, Math.min(20, pct / 5));
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < filled; i++) sb.append('|');
        sb.append("§8");
        for (int i = filled; i < 20; i++) sb.append('|');
        return sb.toString();
    }

    /**
     * Place one block from the schematic. Coordinates are in TARGET space
     * (i.e. post-rotation). The actual source-block lookup applies the
     * job's rotation so directional blocks (stairs/doors/fences) face the
     * right way after the turn.
     */
    private void placeOneBlock(BuildJob job, World w, int tx, int ty, int tz) {
        BlockData bd = rotatedBlockAt(job.schematic, job.rotation, tx, ty, tz);
        if (bd == null) return;
        Material m = bd.getMaterial();
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return;

        int wx = job.origin.getBlockX() + tx;
        int wy = job.origin.getBlockY() + ty;
        int wz = job.origin.getBlockZ() + tz;
        Block block = w.getBlockAt(wx, wy, wz);
        BlockData original = block.getBlockData();
        // Don't snapshot a no-op (placing the same block on itself) — keeps
        // memory down on schematics that mostly re-set existing terrain.
        if (original.matches(bd)) return;
        // Place through a BlockPlaceEvent attributed to the owner so land
        // protection (WorldGuard, GriefPrevention, ...) can veto building on
        // claims it doesn't own. Vetoed blocks are skipped and NOT snapshotted.
        Player owner = Bukkit.getPlayer(job.ownerId);
        if (!placeRespectingProtection(block, original, bd, owner)) return;
        job.snapshots.add(new BuildJob.Snapshot(wx, wy, wz, original, bd));
    }

    /**
     * Place {@code bd} at {@code block}, attributed to {@code owner} via a
     * {@link org.bukkit.event.block.BlockPlaceEvent} so land-protection
     * plugins can cancel it. Returns {@code true} if the block was placed,
     * {@code false} if a protection plugin vetoed it (block left untouched).
     *
     * <p>Uses the place-then-test-then-rollback pattern (the same one
     * WorldEdit-style tools use to respect claims): the block is set with
     * physics off, the event is fired, and if any listener cancels it the
     * original {@code beforeData} is restored. {@code applyPhysics=false}
     * keeps a high-volume build from triggering cascading sand/torch updates;
     * vanilla physics resumes for normal player interaction afterwards.
     *
     * <p>Fail-open: if the owner is offline (can't attribute) or the event
     * machinery throws, the block stays placed — on a server with no
     * protection plugin this is the normal, correct outcome.
     */
    private boolean placeRespectingProtection(Block block, BlockData beforeData, BlockData bd, Player owner) {
        org.bukkit.block.BlockState replaced = block.getState();
        block.setBlockData(bd, false);
        if (owner == null) return true; // offline owner — can't fire an attributable event
        try {
            Block against = block.getRelative(BlockFace.DOWN);
            org.bukkit.event.block.BlockPlaceEvent ev = new org.bukkit.event.block.BlockPlaceEvent(
                    block, replaced, against, new ItemStack(bd.getMaterial()), owner, true);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled() || !ev.canBuild()) {
                block.setBlockData(beforeData, false); // roll back the veto
                return false;
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("BlockPlaceEvent failed at "
                    + block.getLocation() + ": " + t.getMessage());
        }
        return true;
    }

    private void tickRevert(BuildJob job) {
        World w = job.origin.getWorld();
        if (w == null) { job.complete = true; return; }
        List<BuildJob.Snapshot> src = job.snapshots;
        if (src.isEmpty()) { job.complete = true; return; }

        // Determine how many snapshots to pop this step. We pop from the
        // END (LIFO) so the build is undone in reverse-placement order —
        // top-down, which reads naturally as "deconstructing".
        int popCount;
        switch (job.mode) {
            case BLOCK -> popCount = 1;
            case LINE  -> popCount = countSnapshotsSharing(src, true /* same y AND z */);
            case FULL  -> popCount = countSnapshotsSharing(src, false /* same y only */);
            default    -> popCount = 1;
        }

        for (int i = 0; i < popCount && !src.isEmpty(); i++) {
            BuildJob.Snapshot s = src.remove(src.size() - 1);
            Block block = w.getBlockAt(s.worldX, s.worldY, s.worldZ);
            block.setBlockData(s.original, false);
        }
        job.progress += popCount;
        if (src.isEmpty()) job.complete = true;

        if (popCount > 0) plugin.swingCompanion(job.ownerId);
        sendProgress(job);
    }

    /**
     * Count how many snapshots from the END of {@code src} share the same
     * Y (and optionally Z) as the last one. Used to pop a contiguous
     * line/layer of placements per revert step.
     */
    private static int countSnapshotsSharing(List<BuildJob.Snapshot> src, boolean alsoZ) {
        int n = src.size();
        if (n == 0) return 0;
        BuildJob.Snapshot last = src.get(n - 1);
        int count = 1;
        for (int i = n - 2; i >= 0; i--) {
            BuildJob.Snapshot s = src.get(i);
            if (s.worldY != last.worldY) break;
            if (alsoZ && s.worldZ != last.worldZ) break;
            count++;
        }
        return count;
    }

    // ====================================================================
    // ============== SCHEMATIC FILE LISTING ==============================
    // ====================================================================

    public List<File> listSchematics() {
        File[] files = schemFolder.listFiles((dir, name) -> {
            String n = name.toLowerCase();
            // Accept all three common schematic extensions:
            //   .schem      = modern Sponge format (the preferred one)
            //   .schematic  = legacy MCEdit / WorldEdit / Litematica export
            //   .litematic  = Litematica mod's native format
            return n.endsWith(".schem")
                    || n.endsWith(".schematic")
                    || n.endsWith(".litematic");
        });
        if (files == null) return new ArrayList<>();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName));
        return list;
    }

    // ====================================================================
    // ============== GUI: BUILD CONFIG CHEST =============================
    // ====================================================================

    /** Open / refresh the build configuration GUI for {@code player}. */
    public void openGui(Player player) {
        Session s = sessionFor(player.getUniqueId());
        Inventory gui = Bukkit.createInventory(null, 54, GUI_TITLE);
        layoutGui(gui, s, player);
        player.openInventory(gui);
        guiOpen.put(player.getUniqueId(), true);
    }

    /**
     * Update the menu contents WITHOUT re-opening it. Opening a fresh
     * inventory while one is already on screen fires an
     * {@link org.bukkit.event.inventory.InventoryCloseEvent} for the old
     * one — which used to trip the preview tear-down logic in
     * {@link #markGuiClosed(UUID)} and instantly erase the ghost blocks
     * we just sent. Editing the top inventory in place sidesteps that.
     */
    private void refreshGui(Player player) {
        if (!isGuiOpen(player.getUniqueId())) {
            openGui(player); // GUI wasn't open — fall back to full open.
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null || top.getSize() != 54) {
            openGui(player);
            return;
        }
        Session s = sessionFor(player.getUniqueId());
        layoutGui(top, s, player);
        try { player.updateInventory(); } catch (Throwable ignored) {}
    }

    public boolean isOurGui(String title) {
        return title != null && title.equals(GUI_TITLE);
    }

    public boolean isGuiOpen(UUID id) {
        return Boolean.TRUE.equals(guiOpen.get(id));
    }

    public void markGuiClosed(UUID id) {
        guiOpen.remove(id);
        // NOTE: preview is intentionally NOT cleared here — the player
        // wants to walk around and look at the ghost blocks they just
        // toggled on. Preview is cleared explicitly when the player:
        //   - toggles it off,
        //   - selects a new schematic,
        //   - starts a build (handled in startBuild flow),
        //   - logs out (handled by quit listener / shutdown).
    }

    /**
     * Called from the plugin's PlayerQuitEvent listener. Drop any preview
     * state for the disconnecting player so we don't try to re-send
     * sendBlockChange packets to a Player who's gone. Ghost blocks will
     * resolve naturally for the client when they reconnect (next chunk
     * load will give them the true world state).
     */
    public void onPlayerQuit(UUID id) {
        Session s = sessions.get(id);
        if (s == null) return;
        // Drop the preview list without trying to revert — there's no
        // player to revert to. The internal flag clears so the next
        // session starts fresh.
        s.previewedBlocks.clear();
        s.previewActive = false;
        s.previewOrigin = null;
    }

    /** Soft cap on preview blocks. Above this we render an outline only. */
    private static final int PREVIEW_BLOCK_CAP = 25_000;

    /**
     * Show a client-side ghost preview of the selected schematic. Only the
     * {@code player} sees these blocks — they're packets, not real placements.
     * Direction markers (gold front edge + red back-left corner) make the
     * facing unambiguous so the rotation choice is visible at a glance.
     */
    private void renderPreview(Player player, Session s) {
        if (s.selectedFile == null) return;
        SchematicLoader.Schematic sch;
        try {
            sch = SchematicLoader.load(s.selectedFile);
        } catch (Exception e) {
            player.sendMessage("§c(✧) Couldn't preview " + s.selectedFile.getName() + ": " + e.getMessage());
            return;
        }
        Location origin = resolveOrigin(player, s);
        if (origin == null) {
            player.sendMessage("§c(✧) No origin to preview at — summon her first ~");
            return;
        }

        clearPreview(player, s); // Wipe any previous ghosts first.

        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        // Target-space dimensions (rotated).
        int rW = rotatedWidth(sch, s.rotation);
        int rH = sch.height;
        int rL = rotatedLength(sch, s.rotation);

        // Count non-air cells first so we can decide between full preview
        // and bounding-box outline.
        int solidCount = 0;
        int total = rW * rH * rL;
        for (int i = 0; i < total; i++) {
            int y = i / (rW * rL);
            int rem = i - y * rW * rL;
            int z = rem / rW;
            int x = rem - z * rW;
            BlockData bd = rotatedBlockAt(sch, s.rotation, x, y, z);
            if (bd != null && !bd.getMaterial().isAir()) solidCount++;
        }

        boolean outlineOnly = solidCount > PREVIEW_BLOCK_CAP;

        BlockData glassPreview;
        BlockData frontMarker;
        try {
            glassPreview = Bukkit.createBlockData("minecraft:white_stained_glass");
            frontMarker  = Bukkit.createBlockData("minecraft:gold_block");
        } catch (Throwable t) {
            return;
        }

        if (outlineOnly) {
            renderBoxOutline(player, s, w, ox, oy, oz, rW, rH, rL, glassPreview);
            renderFrontMarker(player, s, w, ox, oy, oz, rW, rL, frontMarker);
            player.sendMessage("§e(✧) " + s.selectedFile.getName() + " is huge ("
                    + solidCount + " blocks) — showing outline only ~");
        } else {
            for (int i = 0; i < total; i++) {
                int y = i / (rW * rL);
                int rem = i - y * rW * rL;
                int z = rem / rW;
                int x = rem - z * rW;
                BlockData bd = rotatedBlockAt(sch, s.rotation, x, y, z);
                if (bd == null || bd.getMaterial().isAir()) continue;
                Location loc = new Location(w, ox + x, oy + y, oz + z);
                player.sendBlockChange(loc, bd);
                s.previewedBlocks.add(loc);
            }
            renderFrontMarker(player, s, w, ox, oy, oz, rW, rL, frontMarker);
            player.sendMessage("§d(✧) Preview shown ~ §7(" + solidCount
                    + " blocks, rotation: §f" + s.rotation + "°§7)");
        }

        s.previewActive = true;
        s.previewOrigin = origin;
    }

    /** Draw the 12 edges of the schematic's bounding box as ghost glass blocks. */
    private void renderBoxOutline(Player player, Session s, World w,
                                  int ox, int oy, int oz, int W, int H, int L,
                                  BlockData ghost) {
        // Edges along X
        for (int x = 0; x < W; x++) {
            addGhost(player, s, w, ox + x, oy,         oz,         ghost);
            addGhost(player, s, w, ox + x, oy,         oz + L - 1, ghost);
            addGhost(player, s, w, ox + x, oy + H - 1, oz,         ghost);
            addGhost(player, s, w, ox + x, oy + H - 1, oz + L - 1, ghost);
        }
        // Edges along Y
        for (int y = 0; y < H; y++) {
            addGhost(player, s, w, ox,         oy + y, oz,         ghost);
            addGhost(player, s, w, ox + W - 1, oy + y, oz,         ghost);
            addGhost(player, s, w, ox,         oy + y, oz + L - 1, ghost);
            addGhost(player, s, w, ox + W - 1, oy + y, oz + L - 1, ghost);
        }
        // Edges along Z
        for (int z = 0; z < L; z++) {
            addGhost(player, s, w, ox,         oy,         oz + z, ghost);
            addGhost(player, s, w, ox + W - 1, oy,         oz + z, ghost);
            addGhost(player, s, w, ox,         oy + H - 1, oz + z, ghost);
            addGhost(player, s, w, ox + W - 1, oy + H - 1, oz + z, ghost);
        }
    }

    /** Mark the (0,0,0) corner with gold + the (W-1,0,0) corner so the
     *  schematic's "facing" is unambiguous: the gold edge points along +X. */
    private void renderFrontMarker(Player player, Session s, World w,
                                   int ox, int oy, int oz, int W, int L,
                                   BlockData marker) {
        // Front row: along +X at z=0, y=0
        for (int x = 0; x < W; x++) {
            addGhost(player, s, w, ox + x, oy, oz, marker);
        }
        // A second marker at the back-left corner so you can tell front from back
        BlockData backCorner;
        try {
            backCorner = Bukkit.createBlockData("minecraft:redstone_block");
        } catch (Throwable t) {
            return;
        }
        addGhost(player, s, w, ox, oy, oz + L - 1, backCorner);
    }

    private void addGhost(Player p, Session s, World w, int x, int y, int z, BlockData ghost) {
        Location loc = new Location(w, x, y, z);
        p.sendBlockChange(loc, ghost);
        s.previewedBlocks.add(loc);
    }

    /** Wipe any ghost-preview blocks by re-sending the real world state. */
    private void clearPreview(Player player, Session s) {
        if (s.previewedBlocks.isEmpty()) {
            s.previewActive = false;
            s.previewOrigin = null;
            return;
        }
        if (player != null) {
            for (Location loc : s.previewedBlocks) {
                World w = loc.getWorld();
                if (w == null) continue;
                try {
                    player.sendBlockChange(loc, w.getBlockAt(loc).getBlockData());
                } catch (Throwable ignored) {}
            }
        }
        s.previewedBlocks.clear();
        s.previewActive = false;
        s.previewOrigin = null;
    }

    private void layoutGui(Inventory gui, Session s, Player player) {
        // Clear (in case we're refreshing in place)
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, null);

        // Rows 0-1 (slots 0-17): schematic picker, up to 18 per page
        List<File> files = listSchematics();
        int perPage = 18;
        int maxPage = Math.max(0, (files.size() - 1) / perPage);
        s.page = Math.max(0, Math.min(s.page, maxPage));
        int start = s.page * perPage;
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= files.size()) break;
            File f = files.get(idx);
            gui.setItem(i, schematicIcon(f, s.selectedFile != null
                    && s.selectedFile.getName().equals(f.getName())));
        }
        if (files.isEmpty()) {
            gui.setItem(4, hintItem("§7No schematics yet",
                    "Drop .schem / .litematic files into",
                    "§fplugins/KawaiiCompanion/schematics/"));
        }

        // Slot 18: prev page, slot 26: next page
        if (maxPage > 0) {
            gui.setItem(18, button(Material.ARROW, "§d« Prev page",
                    "Page " + (s.page + 1) + "/" + (maxPage + 1)));
            gui.setItem(26, button(Material.ARROW, "§dNext page »",
                    "Page " + (s.page + 1) + "/" + (maxPage + 1)));
        }

        // Row 3 (27-35): build settings
        gui.setItem(27, button(Material.STRUCTURE_BLOCK, "§dBuild mode",
                "Current: §f" + s.buildMode.name().toLowerCase(),
                "§7click to cycle"));
        gui.setItem(28, button(Material.CLOCK, "§dDelay",
                "Current: §f" + s.delayTicks + " tick" + (s.delayTicks == 1 ? "" : "s"),
                "§7" + ticksToHuman(s.delayTicks),
                "§7click to cycle"));
        gui.setItem(29, button(s.originAtPlayer ? Material.PLAYER_HEAD : Material.ARMOR_STAND,
                "§dBuild origin",
                "Current: §f" + (s.originAtPlayer ? "Your position" : "Her position"),
                "§7click to toggle"));

        gui.setItem(30, button(Material.RED_CONCRETE,   "§c-X", "§7left: -1   right: -5"));
        gui.setItem(31, button(Material.LIME_CONCRETE,  "§a+X", "§7left: +1   right: +5"));
        gui.setItem(32, button(Material.RED_CONCRETE,   "§c-Y", "§7left: -1   right: -5"));
        gui.setItem(33, button(Material.LIME_CONCRETE,  "§a+Y", "§7left: +1   right: +5"));
        gui.setItem(34, button(Material.RED_CONCRETE,   "§c-Z", "§7left: -1   right: -5"));
        gui.setItem(35, button(Material.LIME_CONCRETE,  "§a+Z", "§7left: +1   right: +5"));

        // Row 4 (36-44): revert mode + offset display + preview toggle
        gui.setItem(36, button(Material.TNT, "§dRevert mode",
                "Current: §f" + s.revertMode.name().toLowerCase(),
                "§7click to cycle"));
        gui.setItem(43, button(Material.COMPASS, "§dOffset",
                "§7X: §f" + s.offsetX,
                "§7Y: §f" + s.offsetY,
                "§7Z: §f" + s.offsetZ,
                "",
                "§7click to reset to 0,0,0"));
        gui.setItem(44, button(s.previewActive ? Material.ENDER_EYE : Material.SPYGLASS,
                s.previewActive ? "§aPreview: ON" : "§7Preview: OFF",
                "§7Shows ghost blocks of the schematic.",
                "§7Only you can see them.",
                "",
                "§6Gold §7row = §6front edge §7(+X)",
                "§cRed §7corner = back-left (+Z)",
                "",
                "§7click to toggle"));

        // Row 5 (45-53): action buttons
        boolean canStart = s.selectedFile != null && !hasActiveJob(player.getUniqueId());
        boolean canRevert = canRevert(player.getUniqueId()) && !hasActiveJob(player.getUniqueId());
        boolean hasActive = hasActiveJob(player.getUniqueId());

        gui.setItem(45, button(Material.AMETHYST_SHARD, "§dRotate §7(" + s.rotation + "°)",
                "§7Quarter-turn rotation around Y axis.",
                "§70° → 90° → 180° → 270° → 0°",
                "",
                "§7left-click: rotate clockwise",
                "§7right-click: rotate counter-clockwise"));

        if (hasActive) {
            gui.setItem(47, button(Material.BARRIER, "§cCancel current",
                    "§7Stop the running build/revert"));
        }
        if (canRevert) {
            gui.setItem(49, button(Material.NETHER_STAR, "§5Start Revert",
                    "§7Restore "
                            + lastBuild.get(player.getUniqueId()).snapshots.size()
                            + " block snapshots",
                    "§7Mode: §f" + s.revertMode.name().toLowerCase()));
        }
        if (canStart) {
            gui.setItem(51, button(Material.EMERALD_BLOCK, "§aStart Build",
                    "§7Schematic: §f" + s.selectedFile.getName(),
                    "§7Mode: §f" + s.buildMode.name().toLowerCase(),
                    "§7Delay: §f" + s.delayTicks + "t",
                    "§7Rotation: §f" + s.rotation + "°",
                    "§7Origin: §f" + (s.originAtPlayer ? "you" : "her") + " " + offsetStr(s)));
        } else if (s.selectedFile == null) {
            gui.setItem(51, hintItem("§7Pick a schematic first", "click an icon above"));
        }

        gui.setItem(53, button(Material.OAK_DOOR, "§7Close", "§7closes this menu"));
    }

    private static String offsetStr(Session s) {
        if (s.offsetX == 0 && s.offsetY == 0 && s.offsetZ == 0) return "";
        return "+(" + s.offsetX + "," + s.offsetY + "," + s.offsetZ + ")";
    }

    private static String ticksToHuman(long t) {
        if (t < 20) return (t * 50) + "ms per step";
        if (t == 20) return "1 sec per step";
        return (t / 20.0) + " sec per step";
    }

    private static ItemStack schematicIcon(File f, boolean selected) {
        ItemStack it = new ItemStack(selected ? Material.WRITTEN_BOOK : Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = f.getName();
            // Strip extension for display
            int dot = name.lastIndexOf('.');
            String display = dot > 0 ? name.substring(0, dot) : name;
            meta.setDisplayName((selected ? "§a✓ §f" : "§f") + display);
            long sizeKb = (f.length() + 512) / 1024;
            meta.setLore(List.of(
                    "§7" + sizeKb + " KiB",
                    selected ? "§a§oselected" : "§7click to select"));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack hintItem(String name, String... lore) {
        ItemStack it = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    // ====================================================================
    // ============== GUI: CLICK HANDLING =================================
    // ====================================================================

    /** Called from the plugin's inventory-click listener. Returns true if event was consumed. */
    public boolean handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return false;
        if (!isGuiOpen(p.getUniqueId())) return false;
        event.setCancelled(true);
        // Defense in depth — the GUI opener already checks the permission,
        // but if somehow the menu was opened anyway (reload, etc), we still
        // gate every click to make sure non-permitted players can't act.
        if (!p.hasPermission("kawaiicompanion.build")) {
            p.closeInventory();
            return true;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return true; // bottom inv click — eat it

        Session s = sessionFor(p.getUniqueId());

        // ---- Schematic picker (slots 0-17) ----
        int perPage = 18;
        if (slot >= 0 && slot < perPage) {
            int fileIdx = s.page * perPage + slot;
            List<File> files = listSchematics();
            if (fileIdx < files.size()) {
                s.selectedFile = files.get(fileIdx);
                p.sendMessage("§d(✧) Selected: §f" + s.selectedFile.getName());
                if (s.previewActive) renderPreview(p, s);
            }
            refreshGui(p);
            return true;
        }

        // ---- Pagination ----
        if (slot == 18) { s.page = Math.max(0, s.page - 1); refreshGui(p); return true; }
        if (slot == 26) {
            int maxPage = Math.max(0, (listSchematics().size() - 1) / perPage);
            s.page = Math.min(maxPage, s.page + 1);
            refreshGui(p);
            return true;
        }

        // ---- Settings row (27-29) ----
        if (slot == 27) { s.buildMode = cycleMode(s.buildMode); refreshGui(p); return true; }
        if (slot == 28) { s.delayTicks = cycleDelay(s.delayTicks); refreshGui(p); return true; }
        if (slot == 29) {
            s.originAtPlayer = !s.originAtPlayer;
            if (s.previewActive) renderPreview(p, s); // re-render at the new origin
            refreshGui(p);
            return true;
        }

        // ---- Offset adjusters (30-35) ----
        int delta = event.isRightClick() ? 5 : 1;
        boolean offsetClick = false;
        if (slot == 30) { s.offsetX -= delta; offsetClick = true; }
        else if (slot == 31) { s.offsetX += delta; offsetClick = true; }
        else if (slot == 32) { s.offsetY -= delta; offsetClick = true; }
        else if (slot == 33) { s.offsetY += delta; offsetClick = true; }
        else if (slot == 34) { s.offsetZ -= delta; offsetClick = true; }
        else if (slot == 35) { s.offsetZ += delta; offsetClick = true; }
        if (offsetClick) {
            if (s.previewActive) renderPreview(p, s);
            refreshGui(p);
            return true;
        }

        // ---- Revert mode (36) ----
        if (slot == 36) { s.revertMode = cycleMode(s.revertMode); refreshGui(p); return true; }

        // ---- Offset reset (43) ----
        if (slot == 43) {
            s.offsetX = s.offsetY = s.offsetZ = 0;
            if (s.previewActive) renderPreview(p, s);
            refreshGui(p);
            return true;
        }

        // ---- Preview toggle (44) ----
        if (slot == 44) {
            if (s.previewActive) {
                clearPreview(p, s);
                p.sendMessage("§7(✧) Preview off ~");
            } else {
                renderPreview(p, s);
                p.sendMessage("§7(✧) §dClose this menu §7to see it in the world ~");
            }
            refreshGui(p);
            return true;
        }

        // ---- Rotate (45) ----
        if (slot == 45) {
            int step = event.isRightClick() ? -90 : 90;
            s.rotation = ((s.rotation + step) % 360 + 360) % 360;
            p.sendMessage("§d(✧) Rotation: §f" + s.rotation + "°");
            if (s.previewActive) renderPreview(p, s);
            refreshGui(p);
            return true;
        }

        // ---- Cancel (47) ----
        if (slot == 47) {
            cancel(p.getUniqueId());
            refreshGui(p);
            return true;
        }

        // ---- Start revert (49) ----
        if (slot == 49) {
            p.closeInventory();
            startRevert(p, s.revertMode, s.delayTicks);
            return true;
        }

        // ---- Start build (51) ----
        if (slot == 51) {
            if (s.selectedFile == null) {
                p.sendMessage("§c(✧) Pick a schematic first ~");
                return true;
            }
            Location origin = resolveOrigin(p, s);
            if (origin == null) {
                p.sendMessage("§c(✧) Can't figure out where to build — companion offline?");
                return true;
            }
            p.closeInventory();
            // Wipe ghost preview now — the real blocks are about to take
            // its place and we don't want the client to render stale
            // packets on top of the new world state.
            if (s.previewActive) clearPreview(p, s);
            startBuild(p, s.selectedFile, origin, s.buildMode, s.delayTicks, s.rotation);
            return true;
        }

        // ---- Close (53) ----
        if (slot == 53) {
            p.closeInventory();
            return true;
        }

        return true;
    }

    /** Resolve the build origin from the session (companion vs player + offset). */
    private Location resolveOrigin(Player p, Session s) {
        Location base;
        if (s.originAtPlayer) {
            base = p.getLocation();
        } else {
            // Need the companion's current location from the plugin
            base = plugin.companionLocation(p.getUniqueId());
            if (base == null) return null;
        }
        return base.clone().add(s.offsetX, s.offsetY, s.offsetZ);
    }

    private static BuildJob.Mode cycleMode(BuildJob.Mode m) {
        return switch (m) {
            case BLOCK -> BuildJob.Mode.LINE;
            case LINE  -> BuildJob.Mode.FULL;
            case FULL  -> BuildJob.Mode.BLOCK;
        };
    }

    private static long cycleDelay(long current) {
        for (int i = 0; i < DELAY_CYCLE.length; i++) {
            if (DELAY_CYCLE[i] == current) {
                return DELAY_CYCLE[(i + 1) % DELAY_CYCLE.length];
            }
        }
        return DELAY_CYCLE[0];
    }

    /** Drop all in-memory state. Called on plugin disable. */
    public void shutdown() {
        // Wipe any active previews first so people don't see stale ghost
        // blocks lingering until they nudge a chunk.
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().previewActive) {
                clearPreview(Bukkit.getPlayer(e.getKey()), e.getValue());
            }
        }
        activeJobs.clear();
        lastBuild.clear();
        sessions.clear();
        guiOpen.clear();
    }
}
