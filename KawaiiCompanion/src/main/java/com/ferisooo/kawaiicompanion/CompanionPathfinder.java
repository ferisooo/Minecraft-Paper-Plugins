package com.ferisooo.kawaiicompanion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Block-based A* pathfinder for the Kawaii companion.
 *
 * <p>The companion is a fake {@code ServerPlayer} not registered with
 * {@code ServerLevel}, which means vanilla NMS path navigation (which
 * needs a real {@code Mob} entity for context) doesn't apply. So we
 * roll our own A* over integer block coordinates, treating the
 * companion as a 2-block-tall, 1-block-wide entity.
 *
 * <h2>Capability options</h2>
 *
 * <p>The walker (in {@code KawaiiCompanion}) can be configured to open
 * doors, break soft plants, dig through soft terrain, and how strongly
 * to avoid water. The pathfinder MUST receive the same {@link PathOptions}
 * the walker uses, otherwise the path it returns will route through
 * blocks the walker won't actually clear → companion gets stuck on
 * waypoint 3, repaths, gets stuck again, repaths, ad infinitum.
 *
 * <p>The three "I'll touch this block" flags split into different
 * categories on purpose:
 *
 * <ul>
 *   <li>{@link #isHandOpenable} — non-destructive (just opens). Default ON.</li>
 *   <li>{@link #isBreakablePlant} — destructive but only natural / decoration
 *       blocks players normally don't mind losing (cobwebs, vines, leaves).
 *       OFF by default — opt-in.</li>
 *   <li>{@link #isBreakableTerrain} — destructive on soft minable terrain
 *       (dirt, sand, gravel, snow, clay, mud, moss, mycelium, podzol).
 *       OFF by default — strongly opt-in. This is the "she carved a tunnel
 *       through the hill to reach you" feature, useful but griefable. Stone,
 *       wood, glass, ores, and player-built blocks are NEVER in this list.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 *
 * <p><b>Main-thread safe.</b> Every block read goes through the live
 * world, so this MUST run on the server thread. The risks (chunk loads,
 * main-thread stalls) are bounded by:
 * <ul>
 *   <li>The {@code maxNodes} cap (default 800). 800 × ~24 neighbor checks
 *       ≈ 20k block reads, ~1ms on modern hardware.</li>
 *   <li>{@link #isChunkLoaded} short-circuits any neighbor in an
 *       unloaded chunk so we never trigger generation.</li>
 *   <li>The straight-line distance cap rejects pathological start/goal
 *       pairs up front.</li>
 * </ul>
 *
 * <p>If the goal is unreachable within the budget, we return a partial
 * path to the closest node we touched — so a companion stuck behind a
 * complex maze still makes useful progress each repath instead of
 * standing in place.
 */
public final class CompanionPathfinder {

    /** Sentinel for "this cell is not walkable, at any cost." */
    static final double BLOCKED = -1.0;

    /** Largest single-step drop we'll consider. */
    private static final int MAX_DROP = 3;

    /** Companion height in blocks (foot + head). */
    private static final int HEAD_CLEARANCE = 2;

    /** Cost of a straight cardinal step. */
    private static final double COST_STRAIGHT = 1.0;
    /** Cost of a diagonal step (sqrt 2). */
    private static final double COST_DIAGONAL = 1.41421356;
    /** Extra cost added when stepping up. */
    private static final double COST_JUMP_PENALTY = 0.4;
    /** Per-block extra cost when dropping down. */
    private static final double COST_DROP_PER_BLOCK = 0.15;
    /** Tiny cost for stepping through an openable so dry paths still win when both work. */
    private static final double COST_DOOR = 0.5;

    private CompanionPathfinder() {}

    private static final int[][] HORIZONTAL_OFFSETS = {
            { 1, 0}, {-1, 0}, { 0, 1}, { 0,-1},
            { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1}
    };

    /** Vertical neighbors tried per (dx, dz) — same level, +1 (jump), then drops. */
    private static final int[] VERTICAL_TRY_ORDER;
    static {
        VERTICAL_TRY_ORDER = new int[2 + MAX_DROP];
        VERTICAL_TRY_ORDER[0] = 0;
        VERTICAL_TRY_ORDER[1] = 1;
        for (int i = 0; i < MAX_DROP; i++) VERTICAL_TRY_ORDER[2 + i] = -1 - i;
    }

    /**
     * Knobs the walker passes in. Must match the walker's
     * configured behavior — if {@code allowBreakingPlants} is false here
     * but the walker would happily break, we'd just be leaving paths on
     * the table; if true here but the walker won't break, the companion
     * walks into the leaves and gets stuck.
     */
    public static final class PathOptions {
        /** True → closed wooden doors and fence gates count as passable. */
        public boolean allowOpenDoors = true;
        /** True → soft plant blocks (cobweb, leaves, vines, ...) count as
         *  passable, with {@link #blockBreakCost} added per cell. */
        public boolean allowBreakingPlants = false;
        /** True → soft terrain blocks (dirt, sand, gravel, snow, ...) count as
         *  passable, with {@link #blockBreakCost} added per cell. */
        public boolean allowTunneling = false;
        /** Multiplier on the base step cost when wading through water.
         *  3.0 = "she'll cut across small ponds but walk around big ones". */
        public double  swimCostMultiplier = 3.0;
        /** Per-block penalty added when she has to break through. Higher = stronger
         *  preference for clear routes. 4.0 means breaking 2 dirt blocks costs
         *  as much as walking 10 blocks around it. */
        public double  blockBreakCost = 4.0;
        /** True → post-process the grid path into straight runs (string-pulling)
         *  for smooth, non-robotic movement. Conservative: only collapses
         *  waypoints across genuinely passable, ground-supported space. */
        public boolean smooth = true;
    }

    /** How many waypoints ahead string-pulling will look (bounds the cost). */
    private static final int MAX_SMOOTH_LOOKAHEAD = 16;

    /**
     * Compute a path from {@code start} to {@code goal}. Returns the list
     * of block-center {@link Location}s the companion should walk through
     * (excluding the start), or {@code null} if no useful path was found
     * within {@code maxNodes} expansions.
     */
    public static List<Location> findPath(World world, Location start, Location goal,
                                           int maxNodes, double maxRange,
                                           PathOptions opt) {
        if (world == null || start == null || goal == null) return null;
        if (start.getWorld() != world || goal.getWorld() != world) return null;
        if (opt == null) opt = new PathOptions(); // defaults

        int sx = start.getBlockX();
        int sz = start.getBlockZ();
        int sy = snapToGround(world, sx, start.getBlockY(), sz, opt);
        int gx = goal.getBlockX();
        int gz = goal.getBlockZ();
        int gy = snapToGround(world, gx, goal.getBlockY(), gz, opt);

        if (sy == Integer.MIN_VALUE || gy == Integer.MIN_VALUE) return null;
        if (sx == gx && sy == gy && sz == gz) return Collections.emptyList();

        double dxr = gx - sx, dyr = gy - sy, dzr = gz - sz;
        if (Math.sqrt(dxr * dxr + dyr * dyr + dzr * dzr) > maxRange) return null;

        PriorityQueue<Node> open = new PriorityQueue<>();
        HashMap<Long, Double> bestG = new HashMap<>();

        double startH = heuristic(sx, sy, sz, gx, gy, gz);
        Node startNode = new Node(sx, sy, sz, null, 0.0, startH);
        open.add(startNode);
        bestG.put(packKey(sx, sy, sz), 0.0);

        Node closest = startNode;
        double closestH = startH;

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            expanded++;

            if (cur.x == gx && cur.y == gy && cur.z == gz) {
                return finish(world, sx, sy, sz, cur, opt);
            }

            double curH = heuristic(cur.x, cur.y, cur.z, gx, gy, gz);
            if (curH < closestH) { closestH = curH; closest = cur; }

            expand(world, cur, gx, gy, gz, open, bestG, opt);
        }

        // Budget exhausted but we got somewhere → return the partial path
        // to the closest cell so movement still progresses.
        if (closest != startNode && closestH < startH * 0.7) {
            return finish(world, sx, sy, sz, closest, opt);
        }
        return null;
    }

    /** Reconstruct the node chain, then (optionally) string-pull it smooth. */
    private static List<Location> finish(World world, int sx, int sy, int sz, Node end, PathOptions opt) {
        List<Location> path = reconstruct(end, world);
        if (!opt.smooth || path.size() < 2) return path;
        return smoothPath(world, sx + 0.5, sy, sz + 0.5, path, opt);
    }

    /**
     * String-pulling: greedily replace runs of grid waypoints with the
     * farthest one reachable by a straight, walkable, ground-supported line
     * from the current anchor. Collapses the A* staircase into smooth diagonal
     * runs without ever cutting a corner through a solid, floating over a gap,
     * or skipping a block the walker would need to break/open (those cells are
     * not {@code isPassable()} so the strict line check refuses to cross them).
     */
    private static List<Location> smoothPath(World world, double ax, double ay, double az,
                                             List<Location> path, PathOptions opt) {
        int n = path.size();
        List<Location> out = new ArrayList<>(n);
        int i = 0;
        while (i < n) {
            int farthest = i; // path[i] is always reachable (adjacent to the anchor)
            int limit = Math.min(n, i + 1 + MAX_SMOOTH_LOOKAHEAD);
            for (int j = i + 1; j < limit; j++) {
                Location cand = path.get(j);
                // Only collapse runs at the SAME height. Smoothing across a Y
                // change is what could leave her gliding a block above/below the
                // ground (the reported "floating") — so we never do it. Slopes,
                // stairs, drops and climbs keep their original stepped waypoints.
                if (Math.abs(cand.getY() - ay) > 0.001) break;
                if (walkableLineStrict(world, ax, ay, az, cand.getX(), cand.getY(), cand.getZ())) {
                    farthest = j;
                } else {
                    break; // conservative: stop at the first non-straight-walkable hop
                }
            }
            Location keep = path.get(farthest);
            out.add(keep);
            ax = keep.getX(); ay = keep.getY(); az = keep.getZ();
            i = farthest + 1;
        }
        return out;
    }

    /**
     * True if a straight line from (x0,y0,z0) to (x1,y1,z1) keeps the
     * companion's 2-tall body in passable space with ground/water support
     * the whole way. Samples every ~half block; dedupes repeated cells.
     */
    private static boolean walkableLineStrict(World world, double x0, double y0, double z0,
                                              double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-6) return true;
        int steps = Math.max(1, (int) Math.ceil(dist / 0.25)); // fine sampling — no corner grazing
        int lastBx = Integer.MIN_VALUE, lastBy = Integer.MIN_VALUE, lastBz = Integer.MIN_VALUE;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            int bx = (int) Math.floor(x0 + dx * t);
            int by = (int) Math.floor(y0 + dy * t);
            int bz = (int) Math.floor(z0 + dz * t);
            if (bx == lastBx && by == lastBy && bz == lastBz) continue;
            lastBx = bx; lastBy = by; lastBz = bz;
            if (!isChunkLoaded(world, bx, bz)) return false;
            if (!clearForSmoothing(world, bx, by, bz)) return false;       // foot
            if (!clearForSmoothing(world, bx, by + 1, bz)) return false;   // head
            if (!hasSupport(world, bx, by, bz)) return false;             // stay grounded
        }
        return true;
    }

    /** A cell the body may pass straight through: passable + not a hazard.
     *  Solids, closed doors, plants, and tunnelable terrain are NOT passable
     *  here, so smoothing never crosses anything the walker would have to clear. */
    private static boolean clearForSmoothing(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 > world.getMaxHeight()) return false;
        Block b = world.getBlockAt(x, y, z);
        if (isHazard(b.getType())) return false;
        return b.isPassable();
    }

    /** Ground (or water) support beneath this cell, so a smoothed run can't
     *  float her across a chasm. */
    private static boolean hasSupport(World world, int x, int y, int z) {
        Block below = world.getBlockAt(x, y - 1, z);
        if (!below.isPassable()) return true;          // solid floor
        if (isWater(below.getType())) return true;     // swimming
        return isWater(world.getBlockAt(x, y, z).getType()); // foot floating in water
    }

    // ============== A* internals ==============

    /**
     * Expand all 8×5 neighbors of {@code cur}. For each (dx, dz) we pick
     * the first valid vertical offset in preference order: same level →
     * +1 (jump) → -1, -2, -3 (drops). The +1 jump check requires
     * head-clearance ABOVE the current spot too.
     *
     * <p><b>Diagonal corner-clip prevention.</b> A diagonal move requires
     * BOTH adjacent cardinals to be passable at the current height —
     * otherwise the entity would visually slide through a wall corner.
     */
    private static void expand(World world, Node cur, int gx, int gy, int gz,
                               PriorityQueue<Node> open, HashMap<Long, Double> bestG,
                               PathOptions opt) {
        for (int[] off : HORIZONTAL_OFFSETS) {
            int dx = off[0];
            int dz = off[1];
            int nx = cur.x + dx;
            int nz = cur.z + dz;

            boolean diagonal = (dx != 0 && dz != 0);
            if (diagonal) {
                if (!isPassableColumn(world, cur.x + dx, cur.y, cur.z, opt)
                        || !isPassableColumn(world, cur.x, cur.y, cur.z + dz, opt)) {
                    continue;
                }
            }

            for (int dy : VERTICAL_TRY_ORDER) {
                int ny = cur.y + dy;
                if (!isChunkLoaded(world, nx, nz)) break;

                // Jump-up needs head-clearance above the current spot —
                // can't jump if there's a ceiling.
                if (dy > 0 && !isPassableForMove(world, cur.x, cur.y + HEAD_CLEARANCE, cur.z, opt)) {
                    continue;
                }

                double extraCost = terrainCost(world, nx, ny, nz, opt);
                if (extraCost == BLOCKED) continue;

                double baseStep = diagonal ? COST_DIAGONAL : COST_STRAIGHT;
                // Water multiplier replaces the swim-cost knob — applied
                // when the destination foot is water (handled inside
                // terrainCost via opt.swimCostMultiplier).
                double stepCost = baseStep + extraCost;
                if (dy > 0) stepCost += COST_JUMP_PENALTY;
                if (dy < 0) stepCost += COST_DROP_PER_BLOCK * (-dy);

                long k = packKey(nx, ny, nz);
                double newG = cur.g + stepCost;
                Double existing = bestG.get(k);
                if (existing != null && existing <= newG) continue;
                bestG.put(k, newG);
                open.add(new Node(nx, ny, nz, cur,
                        newG, heuristic(nx, ny, nz, gx, gy, gz)));
                break; // one valid destination per (dx, dz)
            }
        }
    }

    private static double heuristic(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Location> reconstruct(Node end, World world) {
        ArrayList<Location> reversed = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) {
            reversed.add(new Location(world, n.x + 0.5, n.y, n.z + 0.5));
        }
        Collections.reverse(reversed);
        return reversed;
    }

    // ============== Block-level checks ==============

    /**
     * Per-step extra cost at {@code (x, y, z)}, or {@link #BLOCKED} if
     * standing here is not allowed. Cost stacks by category — a flooded
     * doorway with a vine in it costs swim + door + plant-break.
     *
     * <p>Walkability requires:
     * <ul>
     *   <li>foot + head cells passable (or clearable per options),</li>
     *   <li>floor (y-1) is solid ground (water-floor isn't supported —
     *       deep swimming would need physics we don't simulate),</li>
     *   <li>no hazards in foot, head, or floor cell.</li>
     * </ul>
     */
    private static double terrainCost(World world, int x, int y, int z, PathOptions opt) {
        if (y <= world.getMinHeight() || y + HEAD_CLEARANCE > world.getMaxHeight()) {
            return BLOCKED;
        }

        Block foot  = world.getBlockAt(x, y, z);
        Block head  = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        Material fm = foot.getType(), hm = head.getType(), flm = floor.getType();

        if (isHazard(fm) || isHazard(hm) || isHazard(flm)) return BLOCKED;

        // Cell-by-cell passability + per-cell costs.
        double footCost = passabilityCost(foot, fm, opt);
        if (footCost == BLOCKED) return BLOCKED;
        double headCost = passabilityCost(head, hm, opt);
        if (headCost == BLOCKED) return BLOCKED;

        // Floor cases:
        //   • solid block below = regular standing or 1-block wading;
        //   • water below + water foot + air head = surface swimming
        //     (her foot occupies the topmost water block, head is in
        //     air, just like a player floating on a lake);
        //   • water foot + water head = fully-submerged swimming
        //     (the diving case — owner went underwater, she follows
        //     down through the water column instead of being trapped
        //     at the surface);
        //   • everything else = no support, fall.
        boolean floorSolid    = !floor.isPassable();
        boolean surfaceSwim   = isWater(fm) && isWater(flm) && !isWater(hm);
        boolean underwaterSwim = isWater(fm) && isWater(hm);
        if (!floorSolid && !surfaceSwim && !underwaterSwim) return BLOCKED;

        double cost = footCost + headCost;

        // Wading: wet foot. Multiplier replaces the cardinal-step
        // assumption — applied additively here so the (1.0 + (mult-1)*1.0)
        // total matches a "your speed is divided by mult" intuition.
        if (isWater(fm)) {
            cost += (opt.swimCostMultiplier - 1.0) * COST_STRAIGHT;
        }
        return cost;
    }

    /**
     * Cost of including this single block in the path's foot or head
     * slot. Returns {@link #BLOCKED} if it can't be cleared at all under
     * the current options (e.g. a stone wall).
     */
    private static double passabilityCost(Block b, Material m, PathOptions opt) {
        // Air, grass, water, lily pads, etc. — naturally walk-through.
        if (b.isPassable()) {
            return 0.0;
        }
        if (opt.allowOpenDoors && isHandOpenable(m)) {
            return COST_DOOR;
        }
        if (opt.allowBreakingPlants && isBreakablePlant(m)) {
            return opt.blockBreakCost;
        }
        if (opt.allowTunneling && isBreakableTerrain(m)) {
            return opt.blockBreakCost;
        }
        return BLOCKED;
    }

    private static boolean isPassableColumn(World world, int x, int y, int z, PathOptions opt) {
        return isPassableForMove(world, x, y, z, opt)
                && isPassableForMove(world, x, y + 1, z, opt);
    }

    /**
     * "Can the companion's body occupy this single cell?" Used by the
     * diagonal corner-clip check. Honors the same options as the main
     * cost function so paths through doors / breakables aren't rejected
     * here when they would have been accepted by the main expander.
     */
    private static boolean isPassableForMove(World world, int x, int y, int z, PathOptions opt) {
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        if (isHazard(m)) return false;
        if (b.isPassable()) return true;
        if (opt.allowOpenDoors && isHandOpenable(m)) return true;
        if (opt.allowBreakingPlants && isBreakablePlant(m)) return true;
        if (opt.allowTunneling && isBreakableTerrain(m)) return true;
        return false;
    }

    /** Materials we never want the companion to step into, even with cost. */
    private static boolean isHazard(Material m) {
        if (m == null) return false;
        switch (m) {
            case LAVA:
            case FIRE:
            case SOUL_FIRE:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case MAGMA_BLOCK:
            case CACTUS:
            case SWEET_BERRY_BUSH:
            case POWDER_SNOW:
            case WITHER_ROSE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Doors and fence gates the companion can hand-open. <b>Iron doors
     * are excluded</b> — vanilla requires redstone to open those, so we
     * treat them as walls.
     *
     * <p>Trapdoors are also excluded: they sit at floor or ceiling level
     * and pathing through them needs vertical-clearance logic the rest
     * of the pathfinder doesn't have. Players can route the companion
     * around them.
     *
     * <p>Name-based to be robust to new wood types in future versions.
     */
    public static boolean isHandOpenable(Material m) {
        if (m == null) return false;
        if (m == Material.IRON_DOOR) return false;
        String n = m.name();
        return n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE");
    }

    /**
     * "Soft plant" obstacles she'll break when {@code allowBreakingPlants}
     * is on. Curated to natural / decoration blocks only — never blocks
     * a player would consider part of their build (no wood, stone, glass,
     * crops with growth stages, hoppers/chests/etc.).
     */
    public static boolean isBreakablePlant(Material m) {
        if (m == null) return false;
        switch (m) {
            // Webs — common in old strongholds, mineshafts, basements.
            case COBWEB:
            // Climbing decoration — these block player movement when in path.
            case VINE:
            case GLOW_LICHEN:
            case HANGING_ROOTS:
            // Underwater foliage — she'll path through these when wading.
            case SEAGRASS:
            case TALL_SEAGRASS:
            case KELP:
            case KELP_PLANT:
            // Wild-growth blocks she'll find in jungle / forest paths.
            case OAK_LEAVES:
            case BIRCH_LEAVES:
            case SPRUCE_LEAVES:
            case JUNGLE_LEAVES:
            case ACACIA_LEAVES:
            case DARK_OAK_LEAVES:
            case AZALEA_LEAVES:
            case FLOWERING_AZALEA_LEAVES:
            case MANGROVE_LEAVES:
            case CHERRY_LEAVES:
            // Tall plants — sugar cane and bamboo grow in straight columns
            // that block paths near rivers / jungles.
            case SUGAR_CANE:
            case BAMBOO:
            case BAMBOO_SAPLING:
            // Saplings — obstruct ground-level movement in forests.
            case OAK_SAPLING:
            case BIRCH_SAPLING:
            case SPRUCE_SAPLING:
            case JUNGLE_SAPLING:
            case ACACIA_SAPLING:
            case DARK_OAK_SAPLING:
            case CHERRY_SAPLING:
            case MANGROVE_PROPAGULE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Soft minable terrain she'll dig through when {@code allowTunneling}
     * is on. Stone, ores, wood, glass, concrete, terracotta, and other
     * "player-built" or hard blocks are NEVER in this list — even with
     * tunneling on, those will still block the path.
     *
     * <p>This is a careful list: the worst it should look on a shared
     * server is "she made a hole through a dirt hill to follow me."
     * Never "she dug through someone's stone basement wall."
     */
    public static boolean isBreakableTerrain(Material m) {
        if (m == null) return false;
        switch (m) {
            // Dirt family.
            case DIRT:
            case GRASS_BLOCK:
            case COARSE_DIRT:
            case ROOTED_DIRT:
            case PODZOL:
            case MYCELIUM:
            case MUD:
            case PACKED_MUD:
            case DIRT_PATH:
            // Sand / gravel.
            case SAND:
            case RED_SAND:
            case GRAVEL:
            case SUSPICIOUS_SAND:
            case SUSPICIOUS_GRAVEL:
            // Snow / ice (loose).
            case SNOW:
            case SNOW_BLOCK:
            // Clay & moss — soft natural blocks.
            case CLAY:
            case MOSS_BLOCK:
            case MOSS_CARPET:
            case PALE_MOSS_BLOCK:
            case PALE_MOSS_CARPET:
            case PINK_PETALS:
            // Soul soil / sand — soft nether terrain.
            case SOUL_SAND:
            case SOUL_SOIL:
                return true;
            default:
                return false;
        }
    }

    /** Water (and water-like fluids that should slow her down). */
    public static boolean isWater(Material m) {
        if (m == null) return false;
        return m == Material.WATER || m == Material.BUBBLE_COLUMN;
    }

    /**
     * Drop into the floor under {@code (x, y0, z)}. Used to snap a
     * start/goal location onto a walkable surface.
     *
     * <p><b>Underwater special case.</b> If {@code y0} is itself in a
     * water column with a passable head, we return {@code y0} directly
     * — i.e., honor the requested depth instead of always surfacing it.
     * This is what makes the companion follow the owner DOWN into a
     * lake instead of routing to the lake's top every time. Without
     * this short-circuit, the top-down scan would find the surface-
     * swim cell first and lock the path to the surface.
     */
    private static int snapToGround(World world, int x, int y0, int z, PathOptions opt) {
        if (!isChunkLoaded(world, x, z)) return Integer.MIN_VALUE;

        // Underwater honor — owner's actual diving depth.
        if (y0 > world.getMinHeight() && y0 + 1 < world.getMaxHeight()) {
            Material atY = world.getBlockAt(x, y0, z).getType();
            if (isWater(atY) && terrainCost(world, x, y0, z, opt) != BLOCKED) {
                return y0;
            }
        }

        int top = Math.min(y0 + 2, world.getMaxHeight() - 1);
        int bottom = Math.max(world.getMinHeight() + 1, y0 - 16);
        for (int y = top; y >= bottom; y--) {
            if (terrainCost(world, x, y, z, opt) != BLOCKED) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * True if the chunk at world coords {@code (x, z)} is currently
     * loaded. Refusing to expand into unloaded chunks keeps a single
     * A* call from triggering a chain of synchronous chunk loads.
     */
    private static boolean isChunkLoaded(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    // ============== Node ==============

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF))
                | (((long) (y & 0x1FFFFF)) << 21)
                | (((long) (z & 0x1FFFFF)) << 42);
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final Node parent;
        final double g;
        final double f;

        Node(int x, int y, int z, Node parent, double g, double h) {
            this.x = x; this.y = y; this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = g + h;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }
}
