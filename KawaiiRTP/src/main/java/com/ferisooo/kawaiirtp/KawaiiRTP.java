package com.ferisooo.kawaiirtp;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /krtp — random teleport to a safe spot a configurable distance away.
 *
 * <p>The search runs as a sequence of async chunk loads off the main
 * thread (Paper's {@link World#getChunkAtAsync(int, int)}); only the
 * actual block reads + the final teleport hop back to the main thread.
 * Each candidate is rejected if any of the following holds:
 * <ul>
 *   <li>the highest non-passable block is water/lava or otherwise unsafe to stand on</li>
 *   <li>the biome is in the blocked list (oceans by default)</li>
 *   <li>the Y of the standing block is outside the configured min-y..max-y</li>
 *   <li>required headroom above the standing block isn't all passable air</li>
 *   <li>the spot is outside the world border</li>
 * </ul>
 * If no candidate passes within {@code max-attempts} rolls the player
 * stays put and gets a "couldn't find a safe spot" message.
 */
public final class KawaiiRTP extends JavaPlugin {

    private int minDistance;
    private int maxDistance;
    private int maxAttempts;
    private int cooldownSeconds;
    private boolean avoidWater;
    private boolean avoidOcean;
    private List<String> allowedWorlds;
    private List<String> blockedWorlds;
    private int minY;
    private int maxY;
    private int requiredHeadroom;
    private int warmupTicks;
    private boolean effects;
    private final Set<Material> unsafeStandBlocks = new HashSet<>();
    private final Set<String> blockedBiomes = new HashSet<>();

    private final Map<UUID, Long> lastUsedMillis = new ConcurrentHashMap<>();
    /** Players with a search currently in flight (anti command-spam: the
     *  cooldown only starts on a successful teleport, so without this a
     *  player could stack many parallel chunk-load chains). */
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readCfg();
        getLogger().info("(✧) KawaiiRTP ready ~ ring=" + minDistance + ".." + maxDistance
                + " attempts=" + maxAttempts);
    }

    private void readCfg() {
        reloadConfig();
        var cfg = getConfig();
        minDistance      = Math.max(1, cfg.getInt("min-distance", 500));
        maxDistance      = Math.max(minDistance, cfg.getInt("max-distance", 2000));
        maxAttempts      = Math.max(1, cfg.getInt("max-attempts", 40));
        cooldownSeconds  = Math.max(0, cfg.getInt("cooldown-seconds", 30));
        avoidWater       = cfg.getBoolean("avoid-water", true);
        avoidOcean       = cfg.getBoolean("avoid-ocean", true);
        allowedWorlds    = cfg.getStringList("allowed-worlds");
        blockedWorlds    = cfg.getStringList("blocked-worlds");
        minY             = cfg.getInt("min-y", 50);
        maxY             = cfg.getInt("max-y", 250);
        requiredHeadroom = Math.max(1, cfg.getInt("required-headroom", 2));
        warmupTicks      = Math.max(0, cfg.getInt("warmup-ticks", 4));
        effects          = cfg.getBoolean("teleport-effects", true);

        unsafeStandBlocks.clear();
        for (String s : cfg.getStringList("unsafe-stand-blocks")) {
            if (s == null) continue;
            Material m = Material.matchMaterial(s.trim().toUpperCase(Locale.ROOT));
            if (m != null) unsafeStandBlocks.add(m);
            else getLogger().warning("(✧) unknown material in unsafe-stand-blocks: " + s);
        }

        blockedBiomes.clear();
        for (String s : cfg.getStringList("blocked-biomes")) {
            if (s == null) continue;
            blockedBiomes.add(s.trim().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!"kawaiirtp".equalsIgnoreCase(command.getName())) return false;

        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("kawaiirtp.admin")) {
                sender.sendMessage("§d(✧) you don't have permission~");
                return true;
            }
            readCfg();
            sender.sendMessage("§d(✧) KawaiiRTP reloaded ✨  ring="
                    + minDistance + ".." + maxDistance);
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§c(✧) /krtp must be run by a player");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("kawaiirtp.use")) {
            p.sendMessage("§d(✧) you don't have permission~");
            return true;
        }

        World world = p.getWorld();
        String wname = world.getName();
        // RTP is allowed in every world EXCEPT the Nether and the End. We key
        // off the world's environment rather than its name so custom-named
        // nether/end worlds (and Multiverse-style setups) are still caught.
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER || env == World.Environment.THE_END) {
            p.sendMessage("§d(✧) /krtp is not allowed in this world (" + wname + ")");
            return true;
        }
        // Explicit per-world blocklist still wins if an admin sets one.
        if (blockedWorlds.contains(wname)) {
            p.sendMessage("§d(✧) /krtp is disabled in this world (" + wname + ")");
            return true;
        }
        // allowed-worlds left empty (the default) means "every world" — only a
        // non-empty list restricts /krtp to the worlds it names.
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(wname)) {
            p.sendMessage("§d(✧) /krtp isn't enabled for this world (" + wname + ")");
            return true;
        }

        // Cooldown.
        if (cooldownSeconds > 0 && !p.hasPermission("kawaiirtp.bypass-cooldown")) {
            long now = System.currentTimeMillis();
            Long last = lastUsedMillis.get(p.getUniqueId());
            if (last != null) {
                long elapsedSec = (now - last) / 1000L;
                if (elapsedSec < cooldownSeconds) {
                    long remaining = cooldownSeconds - elapsedSec;
                    p.sendMessage("§d(✧) cooldown~ try again in §f"
                            + remaining + "§d s");
                    return true;
                }
            }
        }

        if (!searching.add(p.getUniqueId())) {
            p.sendMessage("§d(✧) already rolling a spot for you~ hang on ✨");
            return true;
        }

        p.sendMessage("§d(✧) rolling a safe spot~ ✨");
        Location origin = p.getLocation();
        // Brief delay so the chat message renders before the freeze of chunk loads.
        Bukkit.getScheduler().runTaskLater(this,
                () -> beginSearch(p, world, origin, 0), warmupTicks);
        return true;
    }

    /**
     * One iteration of the random-spot search. Picks a (x, z), loads the
     * chunk async, then on the main thread checks whether the highest
     * solid block there is safe. On success teleport; on failure recurse
     * up to {@code maxAttempts}. Recursion is deep at worst
     * {@code maxAttempts} — at default 40 that's nowhere near a stack
     * issue and chains naturally off the chunk-load future.
     */
    private void beginSearch(Player p, World world, Location origin, int attempt) {
        if (!p.isOnline()) {
            searching.remove(p.getUniqueId());
            return;
        }
        if (attempt >= maxAttempts) {
            searching.remove(p.getUniqueId());
            p.sendMessage("§c(✧) couldn't find a safe spot after "
                    + maxAttempts + " tries — try again in a sec~");
            return;
        }

        int[] xz = randomXZ(origin);
        int chunkX = xz[0] >> 4;
        int chunkZ = xz[1] >> 4;

        // World border check before paying for a chunk load.
        WorldBorder border = world.getWorldBorder();
        if (!border.isInside(new Location(world, xz[0] + 0.5, world.getMinHeight() + 1, xz[1] + 0.5))) {
            scheduleNext(p, world, origin, attempt + 1);
            return;
        }

        CompletableFuture<Chunk> fut;
        try {
            fut = world.getChunkAtAsync(chunkX, chunkZ, true);
        } catch (Throwable t) {
            // Some server forks expose getChunkAtAsync slightly differently;
            // fall back to the sync load on the main thread.
            Bukkit.getScheduler().runTask(this, () -> {
                world.getChunkAt(chunkX, chunkZ);
                evaluateCandidate(p, world, origin, xz[0], xz[1], attempt);
            });
            return;
        }

        fut.whenComplete((chunk, ex) -> {
            if (ex != null) {
                getLogger().warning("(✧) chunk load failed at "
                        + chunkX + "," + chunkZ + ": " + ex.getMessage());
                scheduleNext(p, world, origin, attempt + 1);
                return;
            }
            // Block reads + teleport must happen on the main thread.
            Bukkit.getScheduler().runTask(this,
                    () -> evaluateCandidate(p, world, origin, xz[0], xz[1], attempt));
        });
    }

    private void scheduleNext(Player p, World world, Location origin, int next) {
        // Spread retries across ticks so a pathological run doesn't stall a tick.
        Bukkit.getScheduler().runTask(this, () -> beginSearch(p, world, origin, next));
    }

    private void evaluateCandidate(Player p, World world, Location origin, int x, int z, int attempt) {
        if (!p.isOnline()) {
            searching.remove(p.getUniqueId());
            return;
        }

        // MOTION_BLOCKING_NO_LEAVES skips leaves but treats water as solid,
        // which is exactly what we want for the first cut: anything reported
        // here as the highest block is what the player would land on. We
        // then re-check that the block isn't water/lava etc.
        int topY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (topY < minY || topY > maxY) {
            beginSearch(p, world, origin, attempt + 1);
            return;
        }

        Block stand = world.getBlockAt(x, topY, z);
        if (!isSafeStandBlock(stand)) {
            beginSearch(p, world, origin, attempt + 1);
            return;
        }

        // Headroom: the next N blocks above must be passable + non-liquid.
        for (int i = 1; i <= requiredHeadroom; i++) {
            Block above = world.getBlockAt(x, topY + i, z);
            if (!isPassableAir(above)) {
                beginSearch(p, world, origin, attempt + 1);
                return;
            }
        }

        // Biome check. org.bukkit.block.Biome flipped from an enum (a class) to
        // an interface across the 1.21.x line, so binding getKey() to the Biome
        // type is fragile: a plugin compiled against one shape blows up with
        // IncompatibleClassChangeError on the other. Both shapes implement
        // org.bukkit.Keyed, so we hold the result as Keyed — getKey() then
        // dispatches via Keyed (invokeinterface) and works on every 1.21 build.
        Keyed biome = world.getBiome(x, topY, z);
        String biomeKey = biome.getKey().getKey().toLowerCase(Locale.ROOT);
        if (blockedBiomes.contains(biomeKey)) {
            beginSearch(p, world, origin, attempt + 1);
            return;
        }
        if (avoidOcean && biomeKey.contains("ocean")) {
            beginSearch(p, world, origin, attempt + 1);
            return;
        }

        // All checks passed — teleport. Center on the block and preserve
        // facing so the player isn't disoriented.
        Location dest = new Location(world,
                x + 0.5, topY + 1.0, z + 0.5,
                origin.getYaw(), origin.getPitch());
        final int distance = (int) Math.floor(
                origin.toVector().setY(0).distance(dest.toVector().setY(0)));
        final String biomePretty = prettyBiome(biomeKey);

        // Departure puff at the spot they're leaving (chunk is loaded — they're here).
        if (effects) {
            Location from = p.getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.PORTAL, from, 60, 0.4, 1.0, 0.4, 0.6);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
        }

        p.teleportAsync(dest).whenComplete((success, err) -> {
            // Clear the in-flight flag however the teleport ends (success,
            // refusal, or exceptional completion) so /krtp never gets stuck.
            searching.remove(p.getUniqueId());
            if (Boolean.TRUE.equals(success)) {
                lastUsedMillis.put(p.getUniqueId(), System.currentTimeMillis());
                Bukkit.getScheduler().runTask(this, () -> {
                    p.sendMessage("§d(✧) tp'd ✨ §f" + distance
                            + "§d blocks away §8(" + x + ", " + topY + ", " + z
                            + " §8in " + biomeKey + ")");
                    if (effects) {
                        Location at = dest.clone().add(0, 1.0, 0);
                        world.spawnParticle(Particle.REVERSE_PORTAL, at, 60, 0.4, 1.0, 0.4, 0.4);
                        world.spawnParticle(Particle.FIREWORK, at, 24, 0.3, 0.6, 0.3, 0.05);
                        p.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.2f);
                        try {
                            p.sendTitle("§d✦ Teleported ✦",
                                    "§f" + distance + "§7 blocks · §b" + biomePretty,
                                    8, 40, 12);
                        } catch (Throwable ignored) {
                            // Title is just garnish — chat message already sent.
                        }
                    }
                });
            } else {
                Bukkit.getScheduler().runTask(this, () -> p.sendMessage(
                        "§c(✧) teleport refused — try again~"));
            }
        });
    }

    /** "snowy_taiga" → "Snowy Taiga" for friendly display. */
    private static String prettyBiome(String key) {
        String s = key.replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
            if (c == ' ') cap = true;
        }
        return sb.toString();
    }

    /** True if a player can safely stand ON this block (i.e. block is the floor). */
    private boolean isSafeStandBlock(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;            // nothing to stand on
        if (b.isLiquid()) return false;         // water or lava — avoid-water gates the water case
        if (avoidWater && (m == Material.WATER
                || m == Material.BUBBLE_COLUMN
                || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS)) {
            return false;
        }
        if (unsafeStandBlocks.contains(m)) return false;
        // Generic catch for any block whose name screams "ow":
        String n = m.name();
        if (n.endsWith("_PRESSURE_PLATE")) return false;
        // Must be solid enough that the player won't fall through (passable
        // blocks like tall grass don't count as "ground").
        if (m.isSolid()) return true;
        return false;
    }

    /** True if a player can occupy this block (head/torso). */
    private boolean isPassableAir(Block b) {
        if (b.isLiquid()) return false;
        Material m = b.getType();
        if (m.isAir()) return true;
        // Tall grass, snow layers, etc. are passable but not air. Reject anything
        // solid; allow things that genuinely don't push the player.
        if (m.isSolid()) return false;
        // Belt-and-braces: a few non-solid blocks still hurt to stand inside.
        if (unsafeStandBlocks.contains(m)) return false;
        return true;
    }

    /** Random (x, z) in a ring of [minDistance, maxDistance] around origin. */
    private int[] randomXZ(Location origin) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2.0;
        // Sqrt for uniform distribution over the annulus area; without it the
        // inner ring gets way more hits than the outer.
        double minSq = (double) minDistance * minDistance;
        double maxSq = (double) maxDistance * maxDistance;
        double dist  = Math.sqrt(minSq + r.nextDouble() * (maxSq - minSq));
        int x = (int) Math.round(origin.getX() + Math.cos(angle) * dist);
        int z = (int) Math.round(origin.getZ() + Math.sin(angle) * dist);
        return new int[] { x, z };
    }
}
