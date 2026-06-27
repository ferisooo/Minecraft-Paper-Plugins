package com.ferisooo.kawaiiseasons;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * KawaiiSeasons — seasons driven by IN-GAME time (not real-world time).
 *
 * <p>The season advances by in-game days (read from the primary world's game
 * time, which is monotonic and unaffected by /time set). Each season nudges the
 * whole world:
 * <ul>
 *   <li><b>Player</b> — Winter chills you (frost vignette + Slowness when
 *       exposed to the sky); Summer's heat burns hunger faster.</li>
 *   <li><b>Crops</b> — growth slows in Autumn and mostly freezes in Winter.</li>
 *   <li><b>Environment</b> — seasonal ambient particles, and in Winter a forced
 *       storm plus simulated snow placed around players so it snows in EVERY
 *       biome (even deserts), with water freezing nearby.</li>
 * </ul>
 */
public final class KawaiiSeasons extends JavaPlugin implements Listener {

    private File dataFile;
    private long dayOffset;          // shifts the calendar (for /season set)
    private int lastSeasonIndex = -1;

    private boolean enabled;
    private long daysPerSeason;
    private long updateTicks;
    private boolean announce;
    private boolean ambienceParticles;
    private boolean actionBar;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private boolean winterCold;
    private boolean summerHeat;
    private float summerExhaustion;
    private boolean forceWeather;
    private boolean snowSimulation;
    private boolean snowNaturalOnly;
    private boolean freezeWater;
    private int snowRadius;
    private double winterGrowthCancel;
    private double autumnGrowthCancel;

    private NamespacedKey seasonKey;

    private final Set<String> enabledWorlds = new HashSet<>();
    private final Set<String> forcedStormWorlds = new HashSet<>();

    /** Tracks which season weather we last applied so we only touch it on changes. */
    private Season lastWeatherSeason;

    /**
     * Last block column (packed X/Z) we ran the snow scan for, per player. Lets us
     * skip the scan entirely while a player stands still in the same column.
     */
    private final Map<UUID, Long> lastSnowColumn = new HashMap<>();

    /** Surfaces snow is allowed to settle on, so it never coats player builds. */
    private static final Set<Material> NATURAL_GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.MOSS_BLOCK,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.DEEPSLATE, Material.TUFF, Material.CALCITE,
            Material.SNOW_BLOCK, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.SANDSTONE, Material.RED_SANDSTONE, Material.TERRACOTTA);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        seasonKey = new NamespacedKey(this, "season");
        readConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        // Folia-safe: a global-region repeating driver runs the world-wide work
        // (season change, weather) and READS the online-player collection, then
        // hops each player's per-player work (effects, particles, action bar,
        // snow around them) onto THAT player's entity scheduler.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tick(), updateTicks, updateTicks);
        updateAllSeasonPdc(); // so the sidebar shows it immediately after a reload
        getLogger().info("(✧) KawaiiSeasons ready ~ it's " + currentSeason().display() + "§r! 🍃");
    }

    @Override
    public void onDisable() {
        // Release any weather we forced so we don't leave worlds stuck raining.
        for (String name : forcedStormWorlds) {
            World w = Bukkit.getWorld(name);
            if (w != null) w.setStorm(false);
        }
        forcedStormWorlds.clear();
        lastSnowColumn.clear();
        lastWeatherSeason = null;
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        enabled            = c.getBoolean("enabled", true);
        daysPerSeason      = Math.max(1, c.getLong("days-per-season", 20));
        updateTicks        = Math.max(20, c.getLong("update-ticks", 60));
        announce           = c.getBoolean("announce-changes", true);
        ambienceParticles  = c.getBoolean("ambience-particles", true);
        actionBar          = c.getBoolean("action-bar", true);
        winterCold         = c.getBoolean("player.winter-cold", true);
        summerHeat         = c.getBoolean("player.summer-heat", true);
        summerExhaustion   = (float) Math.max(0.0, c.getDouble("player.summer-heat-exhaustion", 0.25));
        forceWeather       = c.getBoolean("winter.force-weather", true);
        snowSimulation     = c.getBoolean("winter.snow-simulation", true);
        snowNaturalOnly    = c.getBoolean("winter.snow-on-natural-ground-only", true);
        freezeWater        = c.getBoolean("winter.freeze-water", false);
        snowRadius         = Math.max(1, Math.min(10, c.getInt("winter.snow-radius", 5)));
        winterGrowthCancel = clamp01(c.getDouble("crops.winter-growth-cancel-chance", 0.85));
        autumnGrowthCancel = clamp01(c.getDouble("crops.autumn-growth-cancel-chance", 0.25));

        enabledWorlds.clear();
        for (String s : c.getStringList("enabled-worlds")) enabledWorlds.add(s);
    }

    // ----------------------------------------------------------------- clock

    private long currentDay() {
        World ref = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        long gameTime = ref == null ? 0 : ref.getGameTime();
        return Math.floorDiv(gameTime, 24000L) + dayOffset;
    }

    private int seasonIndex() {
        long day = currentDay();
        return (int) (Math.floorMod(Math.floorDiv(day, daysPerSeason), 4L));
    }

    public Season currentSeason() {
        return Season.byIndex(seasonIndex());
    }

    private long dayWithinSeason() {
        return Math.floorMod(currentDay(), daysPerSeason) + 1;
    }

    private boolean applies(World w) {
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        return enabledWorlds.isEmpty() || enabledWorlds.contains(w.getName());
    }

    // ----------------------------------------------------------------- tick

    private void tick() {
        if (!enabled) return;

        int idx = seasonIndex();
        if (idx != lastSeasonIndex) {
            lastSeasonIndex = idx;
            onSeasonChange(Season.byIndex(idx));
        }
        Season season = Season.byIndex(idx);

        // Weather: force a storm in winter, release it otherwise (only worlds we forced).
        manageWeather(season);

        // Per-player effects + ambience. Each player's work touches that player
        // (and blocks in their region for snow), so hop it onto the player's own
        // entity scheduler — the driver above only READS the collection.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, t -> {
                World w = p.getWorld();
                if (!applies(w)) return;
                if (p.getGameMode() == GameMode.SPECTATOR) return;
                applyPlayerEffects(p, season);
                if (ambienceParticles) spawnAmbience(p, season);
                if (actionBar) p.sendActionBar(LEGACY.deserialize(actionBarText(season)));
                if (season == Season.WINTER && snowSimulation) simulateSnow(p);
            }, null);
        }
    }

    private void onSeasonChange(Season season) {
        getLogger().info("(✧) season is now " + season.name());
        updateAllSeasonPdc(); // refresh the sidebar bridge for everyone
        if (!announce) return;
        String sub = switch (season) {
            case SPRING -> "§7blossoms drift on a warm breeze~";
            case SUMMER -> "§7the sun blazes — stay cool & hydrated~";
            case AUTUMN -> "§7leaves fall; the harvest slows~";
            case WINTER -> "§7snow blankets the land — bundle up!";
        };
        for (Player p : Bukkit.getOnlinePlayers()) {
            // sendTitle touches the player — run it on the player's region thread.
            p.getScheduler().run(this, t -> {
                if (!applies(p.getWorld())) return;
                try {
                    p.sendTitle(season.display(), sub, 10, 60, 20);
                } catch (Throwable ignored) { /* title is garnish */ }
            }, null);
        }
    }

    private void manageWeather(Season season) {
        if (!forceWeather) return;
        // Only (re)apply weather when the season actually changes, not every cycle.
        // We still re-arm a winter storm that vanilla let expire, but that's a cheap
        // hasStorm() check rather than unconditional setStorm/setWeatherDuration churn.
        boolean seasonChanged = season != lastWeatherSeason;
        lastWeatherSeason = season;

        if (season == Season.WINTER) {
            // Use a long storm duration so vanilla rarely clears it between changes.
            for (World w : Bukkit.getWorlds()) {
                if (!applies(w)) continue;
                if (seasonChanged || !w.hasStorm()) {
                    w.setStorm(true);
                    w.setWeatherDuration(20 * 60 * 60); // 1h; refreshed only on (re)apply
                }
                forcedStormWorlds.add(w.getName());
            }
        } else if (seasonChanged && !forcedStormWorlds.isEmpty()) {
            for (String name : forcedStormWorlds) {
                World w = Bukkit.getWorld(name);
                if (w != null) w.setStorm(false);
            }
            forcedStormWorlds.clear();
        }
    }

    private void applyPlayerEffects(Player p, Season season) {
        int dur = (int) updateTicks + 40;
        if (season == Season.WINTER && winterCold && isSkyExposed(p)) {
            // Frost vignette without freeze damage: cap freeze well below max.
            int max = Math.max(1, p.getMaxFreezeTicks());
            int cap = (int) (max * 0.6);
            p.setFreezeTicks(Math.min(cap, p.getFreezeTicks() + (int) updateTicks));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, dur, 0, true, false, false));
        } else if (season == Season.SUMMER && summerHeat && isSkyExposed(p)) {
            // Heat burns energy faster — but only out in the sun, and gently.
            p.setExhaustion(p.getExhaustion() + summerExhaustion);
        }
    }

    private void spawnAmbience(Player p, Season season) {
        Particle particle = switch (season) {
            case SPRING -> Particle.CHERRY_LEAVES;
            case AUTUMN -> Particle.FALLING_SPORE_BLOSSOM;
            case WINTER -> Particle.SNOWFLAKE;
            case SUMMER -> null; // heat haze is felt, not shown
        };
        if (particle == null) return;
        Location at = p.getLocation().add(0, 2.2, 0);
        try {
            p.getWorld().spawnParticle(particle, at, 6, 5.0, 2.0, 5.0, 0.0);
        } catch (Throwable ignored) { /* particle name differs across builds */ }
    }

    /** Place thin snow on exposed ground around the player so it snows in any biome. */
    private void simulateSnow(Player p) {
        World w = p.getWorld();
        int px = p.getLocation().getBlockX();
        int pz = p.getLocation().getBlockZ();

        // Movement gate: if the player hasn't moved to a new block column since the
        // last cycle, the surrounding ground is already snowed — skip the scan.
        long column = (((long) px) << 32) ^ (pz & 0xFFFFFFFFL);
        Long previous = lastSnowColumn.put(p.getUniqueId(), column);
        if (previous != null && previous == column) return;

        for (int dx = -snowRadius; dx <= snowRadius; dx++) {
            for (int dz = -snowRadius; dz <= snowRadius; dz++) {
                int x = px + dx, z = pz + dz;
                // Never force a chunk to load just to snow it.
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = w.getHighestBlockYAt(x, z);
                Block top = w.getBlockAt(x, y, z);
                Material tm = top.getType();
                if (freezeWater && tm == Material.WATER) {
                    top.setType(Material.ICE, false);
                    continue;
                }
                Block above = w.getBlockAt(x, y + 1, z);
                if (above.getType() == Material.AIR && top.getType().isSolid()
                        && tm != Material.SNOW && tm != Material.ICE
                        && (!snowNaturalOnly || NATURAL_GROUND.contains(tm))) {
                    above.setType(Material.SNOW, false); // a single snow layer, builds untouched
                }
            }
        }
    }

    private boolean isSkyExposed(Player p) {
        Location l = p.getLocation();
        return p.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) <= l.getBlockY();
    }

    // ----------------------------------------------------------------- crops

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (!enabled) return;
        World w = e.getBlock().getWorld();
        if (!applies(w)) return;
        Season season = currentSeason();
        double cancelChance = season == Season.WINTER ? winterGrowthCancel
                : season == Season.AUTUMN ? autumnGrowthCancel : 0.0;
        if (cancelChance <= 0) return;
        // Only slow crops open to the sky, so lit/roofed greenhouses keep growing.
        Block b = e.getBlock();
        if (w.getHighestBlockYAt(b.getX(), b.getZ()) > b.getY()) return;
        if (ThreadLocalRandom.current().nextDouble() < cancelChance) {
            e.setCancelled(true); // crop "pauses" this growth tick
        }
    }

    // ----------------------------------------------------------- sidebar bridge

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        updateSeasonPdc(p);
        if (!announce || !applies(p.getWorld())) return;
        Season s = currentSeason();
        // Brief delay so the client is fully in before the title shows. Folia-safe:
        // sendTitle touches the player, so run it on the player's entity scheduler.
        p.getScheduler().runDelayed(this, t -> {
            if (!p.isOnline()) return;
            try {
                p.sendTitle(s.display(), "§7day " + dayWithinSeason() + "/" + daysPerSeason, 10, 50, 15);
            } catch (Throwable ignored) { /* title is garnish */ }
        }, null, Math.max(1, 30L));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        updateSeasonPdc(e.getPlayer());
        // Crossing worlds means a fresh column context; drop the cached one.
        lastSnowColumn.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Avoid leaking the per-player snow-column cache after disconnect.
        lastSnowColumn.remove(e.getPlayer().getUniqueId());
    }

    private void updateAllSeasonPdc() {
        // Called from the global-region tick driver and onEnable; touching each
        // player's PDC mutates that entity, so hop it onto the player's own
        // entity scheduler instead of writing from the global region thread.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, t -> updateSeasonPdc(p), null);
        }
    }

    /** "&b❄ Winter &7(&f3 days left)" for the action bar above the hotbar. */
    private String actionBarText(Season season) {
        long left = daysPerSeason - dayWithinSeason() + 1; // includes today
        String unit = left == 1 ? "day" : "days";
        return season.display() + " &7(&f" + left + " &7" + unit + " left)";
    }

    /** Mirror the season onto the player's PDC so KawaiiScoreboard can show it. */
    private void updateSeasonPdc(Player p) {
        if (seasonKey == null) return;
        if (applies(p.getWorld())) {
            p.getPersistentDataContainer().set(seasonKey, PersistentDataType.STRING, prettySeason());
        } else {
            p.getPersistentDataContainer().remove(seasonKey);
        }
    }

    /** "WINTER" -> "Winter" for display. */
    private String prettySeason() {
        String n = currentSeason().name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
    }

    // ----------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiiseasons.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            readConfig();
            // Force weather + snow scan to re-evaluate once after a config change.
            lastWeatherSeason = null;
            lastSnowColumn.clear();
            sender.sendMessage("§d(✧) KawaiiSeasons reloaded ✨");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("set")) {
            if (!sender.hasPermission("kawaiiseasons.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            Season target;
            try {
                target = Season.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                sender.sendMessage("§c(✧) /season set <spring|summer|autumn|winter>");
                return true;
            }
            setSeason(target);
            sender.sendMessage("§d(✧) season set to " + target.display() + "§d~");
            return true;
        }
        sender.sendMessage("§d(✧) Season: " + currentSeason().display() + " §7(day §f"
                + dayWithinSeason() + "§7/§f" + daysPerSeason + "§7)");
        return true;
    }

    /** Shift the calendar so the current season becomes {@code target}. */
    private void setSeason(Season target) {
        long totalCycle = daysPerSeason * 4L;
        long pos = Math.floorMod(currentDay(), totalCycle);
        long desired = (long) target.ordinal() * daysPerSeason;
        dayOffset += (desired - pos);
        saveData();
        lastSeasonIndex = -1; // force a re-announce on next tick
    }

    private double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    // ----------------------------------------------------------------- data

    private void loadData() {
        if (dataFile == null || !dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        dayOffset = y.getLong("day-offset", 0);
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("day-offset", dayOffset);
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("(✧) couldn't save data.yml: " + ex.getMessage());
        }
    }
}
