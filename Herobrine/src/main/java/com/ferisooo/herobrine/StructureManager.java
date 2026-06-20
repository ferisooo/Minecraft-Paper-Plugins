package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the mysterious structures the legend is famous for, dropped near a
 * player out of their immediate sight: sand pyramids, netherrack crosses,
 * redstone-torch tunnels, leafless trees, and strange underground passages.
 * Generation frequency scales with the player's threat level.
 */
public final class StructureManager {

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;

    public StructureManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
    }

    /** Roll for and build a random structure near the player. Returns the type built, or null. */
    public String maybeGenerate(Player p, double threat) {
        if (!cfg.structuresEnabled()) return null;
        double chance = cfg.structureChance() * (1.0 + threat / 50.0);
        if (ThreadLocalRandom.current().nextDouble() > chance) return null;
        return generate(p);
    }

    /** Force-build a random surface/underground structure near the player. */
    public String generate(Player p) {
        Location base = pickLocation(p);
        if (base == null) return null;
        int roll = ThreadLocalRandom.current().nextInt(5);
        return switch (roll) {
            case 0 -> { sandPyramid(base); yield "sand pyramid"; }
            case 1 -> { netherrackCross(surface(base)); yield "netherrack cross"; }
            case 2 -> { redstoneTunnel(p.getLocation()); yield "redstone tunnel"; }
            case 3 -> { leaflessTree(surface(base)); yield "leafless tree"; }
            default -> { undergroundPassage(p.getLocation()); yield "underground passage"; }
        };
    }

    private Location pickLocation(Player p) {
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double dist = ThreadLocalRandom.current().nextDouble(cfg.structureMin(), cfg.structureMax());
        Location l = p.getLocation().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        return surface(l);
    }

    /** Highest non-air block + 1 at the column. */
    private Location surface(Location l) {
        World w = l.getWorld();
        if (w == null) return null;
        int x = l.getBlockX(), z = l.getBlockZ();
        int y = w.getHighestBlockYAt(x, z);
        return new Location(w, x, y + 1, z);
    }

    // ---- Sand pyramid ----
    private void sandPyramid(Location apexBase) {
        World w = apexBase.getWorld();
        int size = 4; // half-width at the base
        int bx = apexBase.getBlockX(), by = apexBase.getBlockY() - 1, bz = apexBase.getBlockZ();
        for (int layer = 0; layer <= size; layer++) {
            int half = size - layer;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    Block b = w.getBlockAt(bx + dx, by + layer, bz + dz);
                    if (b.getType().isAir() || b.isLiquid() || b.getType().name().contains("LEAVES")) {
                        b.setType(Material.SAND, false);
                    }
                }
            }
        }
    }

    // ---- Netherrack cross (occasionally on fire) ----
    private void netherrackCross(Location base) {
        if (base == null) return;
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        // vertical beam
        for (int dy = 0; dy < 5; dy++) {
            w.getBlockAt(bx, by + dy, bz).setType(Material.NETHERRACK, false);
        }
        // horizontal beam
        for (int dx = -2; dx <= 2; dx++) {
            w.getBlockAt(bx + dx, by + 3, bz).setType(Material.NETHERRACK, false);
        }
        if (ThreadLocalRandom.current().nextBoolean()) {
            Block top = w.getBlockAt(bx, by + 5, bz);
            if (top.getType().isAir()) top.setType(Material.FIRE, false);
        }
    }

    // ---- Redstone torch tunnel: a short lit corridor bored into the ground ----
    private void redstoneTunnel(Location playerLoc) {
        World w = playerLoc.getWorld();
        Location start = playerLoc.clone().add(playerLoc.getDirection().setY(0).normalize().multiply(8));
        start.setY(Math.min(start.getY(), w.getHighestBlockYAt(start) - 2));
        int dirX = ThreadLocalRandom.current().nextBoolean() ? 1 : 0;
        int dirZ = dirX == 0 ? 1 : 0;
        int bx = start.getBlockX(), by = start.getBlockY(), bz = start.getBlockZ();
        for (int i = 0; i < 12; i++) {
            int x = bx + dirX * i, z = bz + dirZ * i;
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(x, by + dy, z).setType(Material.AIR, false);
            }
            // walls
            w.getBlockAt(x + dirZ, by, z + dirX).setType(Material.STONE, false);
            w.getBlockAt(x - dirZ, by, z - dirX).setType(Material.STONE, false);
            if (i % 3 == 0) {
                Block wall = w.getBlockAt(x + dirZ, by + 1, z + dirX);
                wall.setType(Material.STONE, false);
                Block torch = w.getBlockAt(x, by + 1, z);
                if (torch.getType().isAir()) torch.setType(Material.REDSTONE_WALL_TORCH, false);
            }
        }
    }

    // ---- Leafless tree: bare log trunk with bare branches ----
    private void leaflessTree(Location base) {
        if (base == null) return;
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int height = ThreadLocalRandom.current().nextInt(5, 9);
        for (int dy = 0; dy < height; dy++) {
            w.getBlockAt(bx, by + dy, bz).setType(Material.DARK_OAK_LOG, false);
        }
        // a few stubby bare branches near the top
        int top = by + height - 1;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (ThreadLocalRandom.current().nextBoolean()) {
                Block branch = w.getBlockAt(bx + f.getModX(), top - 1, bz + f.getModZ());
                if (branch.getType().isAir()) branch.setType(Material.DARK_OAK_LOG, false);
            }
        }
    }

    // ---- Strange underground passage: a hidden corridor beneath the player ----
    private void undergroundPassage(Location playerLoc) {
        World w = playerLoc.getWorld();
        int bx = playerLoc.getBlockX(), bz = playerLoc.getBlockZ();
        int by = Math.max(w.getMinHeight() + 5, playerLoc.getBlockY() - ThreadLocalRandom.current().nextInt(12, 25));
        int dirX = ThreadLocalRandom.current().nextBoolean() ? 1 : 0;
        int dirZ = dirX == 0 ? 1 : 0;
        for (int i = 0; i < 16; i++) {
            int x = bx + dirX * i, z = bz + dirZ * i;
            for (int dy = 0; dy < 2; dy++) {
                w.getBlockAt(x, by + dy, z).setType(Material.AIR, false);
            }
            // cobblestone floor + occasional soul/red torch lighting
            w.getBlockAt(x, by - 1, z).setType(Material.COBBLESTONE, false);
            if (i % 4 == 0) {
                Block t = w.getBlockAt(x, by + 1, z);
                if (t.getRelative(BlockFace.UP).getType().isSolid()) {
                    w.getBlockAt(x, by, z).setType(Material.REDSTONE_TORCH, false);
                }
            }
        }
    }
}
