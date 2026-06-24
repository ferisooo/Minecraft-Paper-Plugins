package com.ferisooo.kawaiisaplings;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Forces planted saplings to grow into trees on a timer, ignoring the normal light and
 * space rules. Where the tree has no room, the plugin carves out the space it needs
 * (subject to {@code overwrite-scope}) so the tree grows anyway.
 *
 * <p>Performance: saplings are NOT found by scanning the world every tick. They are
 * tracked as they are placed (and, optionally, when chunks load), and a single throttled
 * timer grows a bounded number of them per cycle.
 */
public final class KawaiiSaplings extends JavaPlugin implements Listener {

    /** Sapling material name -> tree type name. Resolved lazily so a missing constant on
     *  an older/newer server is simply skipped instead of failing to load. */
    private static final Map<String, String> SAPLING_TREE = new LinkedHashMap<>();
    static {
        SAPLING_TREE.put("OAK_SAPLING", "TREE");
        SAPLING_TREE.put("BIRCH_SAPLING", "BIRCH");
        SAPLING_TREE.put("SPRUCE_SAPLING", "REDWOOD");
        SAPLING_TREE.put("JUNGLE_SAPLING", "SMALL_JUNGLE");
        SAPLING_TREE.put("ACACIA_SAPLING", "ACACIA");
        SAPLING_TREE.put("DARK_OAK_SAPLING", "DARK_OAK");
        SAPLING_TREE.put("CHERRY_SAPLING", "CHERRY");
        SAPLING_TREE.put("PALE_OAK_SAPLING", "PALE_OAK");
        SAPLING_TREE.put("MANGROVE_PROPAGULE", "MANGROVE");
        SAPLING_TREE.put("AZALEA", "AZALEA");
        SAPLING_TREE.put("FLOWERING_AZALEA", "AZALEA");
    }

    /** Materials that count as valid soil under a sapling (so we don't replace good ground). */
    private static final Set<Material> SOIL = new HashSet<>();
    static {
        for (String n : new String[]{
                "DIRT", "GRASS_BLOCK", "PODZOL", "COARSE_DIRT", "ROOTED_DIRT", "MYCELIUM",
                "MOSS_BLOCK", "MUD", "MUDDY_MANGROVE_ROOTS", "FARMLAND", "DIRT_PATH", "CLAY"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) SOIL.add(m);
        }
    }

    private enum Scope { ALL, NATURAL, AIR }

    private final Random random = new Random();

    /** Positions of saplings we know about, awaiting their turn to grow. */
    private final Set<BlockPos> tracked = new HashSet<>();

    // Resolved config / mappings.
    private final Map<Material, TreeType> saplingTree = new HashMap<>();
    private final Set<Material> protectedBlocks = new HashSet<>();
    private Scope scope = Scope.ALL;
    private boolean singleMega = true;
    private boolean fixSoil = true;
    private int maxClearRadius = 3;
    private int maxClearHeight = 24;
    private int maxPerCycle = 20;
    private long intervalTicks = 600L;
    private boolean scanOnLoad = false;
    private Set<String> enabledWorlds = new HashSet<>();
    private Set<String> disabledWorlds = new HashSet<>();

    private BukkitTask growTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        startGrowTask();
        getLogger().info("KawaiiSaplings enabled: " + saplingTree.size()
                + " sapling types managed, scope=" + scope + ", interval=" + (intervalTicks / 20) + "s.");
    }

    @Override
    public void onDisable() {
        if (growTask != null) {
            growTask.cancel();
            growTask = null;
        }
        tracked.clear();
    }

    // ------------------------------------------------------------------ config

    private void loadSettings() {
        reloadConfig();

        intervalTicks = Math.max(20L, getConfig().getLong("growth-interval-seconds", 30L) * 20L);
        maxPerCycle = Math.max(1, getConfig().getInt("max-per-cycle", 20));
        singleMega = getConfig().getBoolean("single-mega-trees", true);
        fixSoil = getConfig().getBoolean("fix-soil", true);
        maxClearRadius = clamp(getConfig().getInt("max-clear-radius", 3), 0, 8);
        maxClearHeight = clamp(getConfig().getInt("max-clear-height", 24), 1, 48);
        scanOnLoad = getConfig().getBoolean("scan-chunks-on-load", false);

        scope = switch (getConfig().getString("overwrite-scope", "all").toLowerCase()) {
            case "natural" -> Scope.NATURAL;
            case "air", "none" -> Scope.AIR;
            default -> Scope.ALL;
        };

        protectedBlocks.clear();
        for (String n : getConfig().getStringList("protected-blocks")) {
            Material m = Material.matchMaterial(n.trim().toUpperCase());
            if (m != null) protectedBlocks.add(m);
        }

        enabledWorlds = lowerSet(getConfig().getStringList("enabled-worlds"));
        disabledWorlds = lowerSet(getConfig().getStringList("disabled-worlds"));

        // Build the active sapling -> tree map, honouring an optional whitelist.
        saplingTree.clear();
        List<String> wanted = getConfig().getStringList("sapling-types");
        Set<String> whitelist = wanted.isEmpty() ? null : upperSet(wanted);
        for (Map.Entry<String, String> e : SAPLING_TREE.entrySet()) {
            if (whitelist != null && !whitelist.contains(e.getKey())) continue;
            Material mat = Material.matchMaterial(e.getKey());
            TreeType type = treeType(e.getValue());
            if (mat != null && type != null) saplingTree.put(mat, type);
        }
    }

    private void startGrowTask() {
        if (growTask != null) growTask.cancel();
        growTask = getServer().getScheduler().runTaskTimer(this, this::growCycle, intervalTicks, intervalTicks);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (saplingTree.containsKey(b.getType()) && worldAllowed(b.getWorld())) {
            tracked.add(BlockPos.of(b));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (saplingTree.containsKey(e.getBlock().getType())) {
            tracked.remove(BlockPos.of(e.getBlock()));
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!scanOnLoad || !worldAllowed(e.getWorld())) return;
        scanChunk(e.getChunk());
    }

    /** Snapshot the chunk on the main thread, scan it off-thread, add finds back on-thread. */
    private void scanChunk(Chunk chunk) {
        final UUID worldId = chunk.getWorld().getUID();
        // includeMaxBlockY = true so getHighestBlockYAt is populated on the snapshot.
        final ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
        final World world = chunk.getWorld();
        final int minY = world.getMinHeight();
        final int baseX = chunk.getX() << 4;
        final int baseZ = chunk.getZ() << 4;
        // Capture the managed materials on the main thread so the async loop never reads the live map.
        final Set<Material> types = new HashSet<>(saplingTree.keySet());
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<BlockPos> found = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int top = snap.getHighestBlockYAt(x, z); // saplings never sit above the highest block
                    for (int y = minY; y <= top; y++) {
                        if (types.contains(snap.getBlockType(x, y, z))) {
                            found.add(new BlockPos(worldId, baseX + x, y, baseZ + z));
                        }
                    }
                }
            }
            if (found.isEmpty()) return;
            getServer().getScheduler().runTask(this, () -> tracked.addAll(found));
        });
    }

    // ------------------------------------------------------------------ growth

    private void growCycle() {
        if (tracked.isEmpty()) return;
        int budget = maxPerCycle;
        // Iterate a snapshot so we can mutate `tracked` while looping.
        for (BlockPos pos : new ArrayList<>(tracked)) {
            if (budget <= 0) break;
            World world = Bukkit.getWorld(pos.world());
            if (world == null) { tracked.remove(pos); continue; }
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue; // try again when loaded
            tracked.remove(pos);
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            TreeType type = saplingTree.get(block.getType());
            if (type == null) continue; // no longer a managed sapling
            if (!singleMega && isMegaOnly(block.getType())) continue; // respect vanilla 2x2 rule
            budget--;
            forceGrow(block, type);
        }
    }

    /** Forces one sapling to become a tree, carving room first if needed. */
    private boolean forceGrow(Block sapling, TreeType type) {
        World world = sapling.getWorld();
        Location loc = sapling.getLocation();

        ensureSoil(sapling.getRelative(0, -1, 0));
        clearSpace(sapling, type);
        sapling.setType(Material.AIR, false); // free the trunk base so the generator can use it

        // Typed variable so this resolves to the Predicate overload, not the deprecated Consumer one.
        Predicate<BlockState> predicate = this::canPlaceTreeBlock;
        boolean ok = world.generateTree(loc, random, type, predicate);
        if (!ok) {
            getLogger().fine("Tree generation failed at " + loc + " (" + type + ").");
        }
        return ok;
    }

    /** Predicate handed to generateTree: allow the tree to set a block only if we're allowed
     *  to overwrite whatever is currently there. Cleared space is air, which is always allowed. */
    private boolean canPlaceTreeBlock(BlockState state) {
        return canOverwrite(state.getBlock().getType());
    }

    /** Carve out the space the tree needs above the sapling, within bounded limits. */
    private void clearSpace(Block sapling, TreeType type) {
        int radius = Math.min(maxClearRadius, radiusFor(type));
        int height = Math.min(maxClearHeight, heightFor(type));
        World world = sapling.getWorld();
        int bx = sapling.getX(), by = sapling.getY(), bz = sapling.getZ();
        int maxY = world.getMaxHeight() - 1;
        for (int dy = 1; dy <= height; dy++) {
            int y = by + dy;
            if (y > maxY) break;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(bx + dx, y, bz + dz);
                    if (canOverwrite(b.getType())) {
                        b.setType(Material.AIR, false); // physics-less to avoid update cascades
                    }
                }
            }
        }
    }

    /** Replace the block under the sapling with dirt if it isn't valid soil and we're allowed to. */
    private void ensureSoil(Block below) {
        if (!fixSoil) return;
        Material m = below.getType();
        if (SOIL.contains(m)) return;
        if (protectedBlocks.contains(m)) return;
        below.setType(Material.DIRT, false);
    }

    private boolean canOverwrite(Material existing) {
        if (existing.isAir()) return true;
        if (protectedBlocks.contains(existing)) return false;
        return switch (scope) {
            case ALL -> true;
            case AIR -> !existing.isSolid();
            case NATURAL -> isNatural(existing);
        };
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§d(✿) KawaiiSaplings — /ks reload | /ks grow");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                loadSettings();
                startGrowTask();
                sender.sendMessage("§d(✿) KawaiiSaplings reloaded ✨");
            }
            case "grow" -> {
                int before = tracked.size();
                growCycle();
                sender.sendMessage("§d(✿) Grew up to " + maxPerCycle
                        + " saplings (" + before + " were queued).");
            }
            default -> sender.sendMessage("§dUsage: /ks reload | /ks grow");
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private boolean worldAllowed(World world) {
        String name = world.getName().toLowerCase();
        if (!enabledWorlds.isEmpty()) return enabledWorlds.contains(name);
        return !disabledWorlds.contains(name);
    }

    private static boolean isMegaOnly(Material m) {
        return m == Material.DARK_OAK_SAPLING;
    }

    private static int radiusFor(TreeType type) {
        return switch (type.name()) {
            case "JUNGLE", "DARK_OAK", "MEGA_REDWOOD", "TALL_MANGROVE" -> 3;
            default -> 2;
        };
    }

    private static int heightFor(TreeType type) {
        return switch (type.name()) {
            case "JUNGLE", "SMALL_JUNGLE", "MEGA_REDWOOD" -> 24;
            case "DARK_OAK", "REDWOOD", "TALL_REDWOOD", "MANGROVE", "TALL_MANGROVE", "CHERRY", "PALE_OAK" -> 16;
            default -> 9;
        };
    }

    /** Heuristic: is this a natural / terrain / plant block (vs. a manufactured building block)? */
    private static boolean isNatural(Material m) {
        if (m.isAir()) return true;
        String n = m.name();
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_LEAVES")
                || n.endsWith("_SAPLING") || n.endsWith("_ROOTS") || n.endsWith("_FUNGUS")
                || n.endsWith("_MUSHROOM") || n.endsWith("_CORAL") || n.endsWith("_CORAL_BLOCK")
                || n.endsWith("_CORAL_FAN")) {
            return true;
        }
        for (String key : new String[]{
                "DIRT", "GRASS", "STONE", "SAND", "GRAVEL", "SNOW", "ICE", "CLAY", "MOSS",
                "MUD", "FLOWER", "FERN", "VINE", "BAMBOO", "KELP", "SEAGRASS", "LICHEN",
                "DRIPLEAF", "AZALEA", "TUBE_CORAL", "ORE", "DEEPSLATE", "TUFF", "GRANITE",
                "DIORITE", "ANDESITE", "WATER", "LAVA", "PODZOL", "MYCELIUM", "ROOTED",
                "TERRACOTTA", "NETHERRACK", "BASALT", "BLACKSTONE", "MAGMA", "SOUL", "OBSIDIAN",
                "WART", "SHROOMLIGHT", "GLOW_LICHEN", "POINTED_DRIPSTONE", "DRIPSTONE", "CACTUS",
                "SUGAR_CANE", "LILY", "PUMPKIN", "MELON", "BERRY", "GRASS_BLOCK"}) {
            if (n.contains(key)) return true;
        }
        return n.equals("TALL_GRASS") || n.equals("SHORT_GRASS") || n.equals("DEAD_BUSH");
    }

    private static TreeType treeType(String name) {
        try {
            return TreeType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Set<String> lowerSet(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toLowerCase());
        return out;
    }

    private static Set<String> upperSet(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.trim().toUpperCase());
        return out;
    }

    /** Immutable world+position key for the tracked set. */
    private record BlockPos(UUID world, int x, int y, int z) {
        static BlockPos of(Block b) {
            return new BlockPos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }
}
