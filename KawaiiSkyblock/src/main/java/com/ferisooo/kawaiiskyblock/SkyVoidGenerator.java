package com.ferisooo.kawaiiskyblock;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Empty-air world for the skyblock dimension. Islands are pasted in by the
 * plugin; the generator itself produces nothing so the sky stays clear.
 */
public final class SkyVoidGenerator extends ChunkGenerator {

    @Override public boolean shouldGenerateNoise()       { return false; }
    @Override public boolean shouldGenerateSurface()     { return false; }
    @Override public boolean shouldGenerateBedrock()     { return false; }
    @Override public boolean shouldGenerateCaves()       { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs()        { return true; }  // hostile mobs on islands
    @Override public boolean shouldGenerateStructures()  { return false; }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5, 101.0, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return null; // vanilla default biome (plains)
    }
}
