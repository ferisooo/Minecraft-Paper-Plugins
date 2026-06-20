package com.ferisooo.kawaiiworlds;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Pure-void world: every chunk is empty air. Spawn is set to (0, 64, 0)
 * by KawaiiWorlds after creation; the player will just fall forever
 * unless something else (a starter platform, a separate plugin, /tp)
 * intervenes.
 */
public final class VoidGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise()      { return false; }
    @Override
    public boolean shouldGenerateSurface()    { return false; }
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
        return new Location(world, 0.5, 64.0, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return null; // use vanilla default
    }
}
