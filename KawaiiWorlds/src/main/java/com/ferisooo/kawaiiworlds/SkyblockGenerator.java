package com.ferisooo.kawaiiworlds;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Empty world with a small starter island stamped into the chunk that
 * contains world spawn (0,0). Island layout (top-down, 5x5):
 *
 *   . . . . .         y = 65: top — grass with one oak sapling at center
 *   . . . . .         y = 64-63: dirt block (3x3 inner footprint)
 *   . . S . .         y = 62: cobble core (1x1) so the island has a stem
 *   . . . . .
 *   . . . . .
 *
 * A small chest with starter items would be nice; left for a phase-2
 * pass. For now, sapling + dirt platform = recognizable skyblock seed.
 */
public final class SkyblockGenerator extends ChunkGenerator {

    private static final int ISLAND_Y = 64;

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData data) {
        // Only stamp the island into chunk (0,0). All other chunks stay empty air.
        if (chunkX != 0 || chunkZ != 0) return;

        // Center the 5x5 platform on local coords (7,7) — that's world (7,7) in chunk (0,0).
        // World spawn will be set to (0.5, 66, 0.5) by KawaiiWorlds; the platform extends
        // from world x=-2..2 z=-2..2. Chunk (0,0) covers world x=0..15, so we put the
        // platform at local x=6..10 z=6..10 to keep it centered around world (8,8).
        // (Players spawn at the world spawn we set, not at 8,8 — close enough.)
        int cx = 8;
        int cz = 8;

        // 5x5 grass top
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                data.setBlock(cx + dx, ISLAND_Y, cz + dz, Material.GRASS_BLOCK);
            }
        }
        // 3x3 dirt one block down
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                data.setBlock(cx + dx, ISLAND_Y - 1, cz + dz, Material.DIRT);
            }
        }
        // 1x1 cobble stem
        data.setBlock(cx, ISLAND_Y - 2, cz, Material.COBBLESTONE);

        // Oak sapling on top, slightly offset so the spawn point isn't
        // inside the sapling block.
        data.setBlock(cx + 1, ISLAND_Y + 1, cz + 1, Material.OAK_SAPLING);
    }

    @Override
    public boolean shouldGenerateNoise()      { return false; }
    @Override
    public boolean shouldGenerateSurface()    { return true; } // we override generateSurface
    @Override
    public boolean shouldGenerateBedrock()    { return false; }
    @Override
    public boolean shouldGenerateCaves()      { return false; }
    @Override
    public boolean shouldGenerateDecorations(){ return false; }
    @Override
    public boolean shouldGenerateMobs()       { return false; }
    @Override
    public boolean shouldGenerateStructures() { return false; }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 8.5, ISLAND_Y + 1, 8.5);
    }
}
