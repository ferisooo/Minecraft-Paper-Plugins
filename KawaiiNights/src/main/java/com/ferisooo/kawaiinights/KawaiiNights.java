package com.ferisooo.kawaiinights;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * KawaiiNights — makes the world scarier without touching vanilla spawn rates.
 *
 * <p>On a timer the plugin spawns a few EXTRA hostile mobs around each player at
 * any time/weather/biome (more at night), and hostiles don't burn in daylight.
 * On top of that, <b>raids</b> occasionally strike at night: a single-species
 * horde (all zombies, all skeletons, …) descends on everyone in the world. How
 * many raids per night, how big, how close, and which species are all
 * configurable. Lit areas stay safe from the trickle spawner, a per-player cap
 * stops runaway swarms, and peaceful/creative players are left alone.
 */
public final class KawaiiNights extends JavaPlugin implements Listener {

    private boolean enabled;
    private long checkTicks;
    private int mobsPerPlayer;
    private double nightMultiplier;
    private boolean preventSunBurn;
    private boolean lagGuardEnabled;
    private double lagTpsThreshold;
    private boolean lagGuardCull;

    /** Never culled by the lag guard. */
    private static final java.util.Set<EntityType> BOSSES = java.util.EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN, EntityType.ELDER_GUARDIAN);
    private int minRadius;
    private int maxRadius;
    private int maxBlockLight;
    private int nearbyCap;
    private int spawnAttempts;
    private final Set<String> enabledWorlds = new HashSet<>();
    private final List<EntityType> mobs = new ArrayList<>();

    // ---- raids ----
    private boolean raidsEnabled;
    private int raidsPerNightMin;
    private int raidsPerNightMax;
    private int raidMobCount;
    private int raidMinRadius;
    private int raidMaxRadius;
    private boolean raidIgnoreLight;
    private boolean raidAnnounce;
    private boolean raidBlockSleep;
    private long raidDurationMillis;
    private String raidSoundKey;
    private final List<EntityType> raidMobs = new ArrayList<>();
    // Concurrent: on Folia these are read/written from multiple region threads
    // (the world-region raid bookkeeping, the victim-region horde spawn, and the
    // player-region bed/death events), so they must be thread-safe collections.
    private final Map<String, NightState> nights = new java.util.concurrent.ConcurrentHashMap<>();
    private NamespacedKey raidTagKey;

    /** UUIDs of currently-living raid-spawned mobs, so we needn't scan all world entities. */
    private final Set<UUID> raidMobIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Per-world night bookkeeping: night/raid state + pending raids for the night. */
    private static final class NightState {
        boolean wasNight;
        final List<Long> pending = new ArrayList<>(); // world times-of-day still to fire
        long activeUntilMillis;                       // a raid is "active" until this time
        boolean wasActive;                            // for the "raid over" notice
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        raidTagKey = new NamespacedKey(this, "raid");
        getServer().getPluginManager().registerEvents(this, this);
        // Folia-safe: a global-region repeating driver runs the bookkeeping that
        // only reads collections/plugin state, then hops each region-touching unit
        // of work onto the correct scheduler — per-player work onto that player's
        // entity scheduler, and per-world raid/cull work onto a region thread for
        // that world. Identical cadence on Paper/Purpur (single-threaded) and Folia.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tick(), checkTicks, checkTicks);
        getLogger().info("(✧) KawaiiNights ready ~ the dark is always hungry 🌙");
    }

    @Override
    public void onDisable() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        enabled         = c.getBoolean("enabled", true);
        checkTicks      = Math.max(20, c.getLong("check-ticks", 100));
        mobsPerPlayer   = Math.max(0, c.getInt("mobs-per-player", 3));
        nightMultiplier = Math.max(1.0, c.getDouble("night-multiplier", 2.0));
        preventSunBurn  = c.getBoolean("prevent-sunlight-burning", true);
        lagGuardEnabled = c.getBoolean("lag-guard.enabled", true);
        lagTpsThreshold = c.getDouble("lag-guard.tps-threshold", 17.0);
        lagGuardCull    = c.getBoolean("lag-guard.cull-hostiles", true);
        minRadius       = Math.max(4, c.getInt("min-radius", 8));
        maxRadius       = Math.max(minRadius + 1, c.getInt("max-radius", 28));
        maxBlockLight   = Math.max(0, Math.min(15, c.getInt("max-block-light", 7)));
        nearbyCap       = Math.max(1, c.getInt("nearby-hostile-cap", 40));
        spawnAttempts   = Math.max(1, c.getInt("spawn-attempts", 6));

        enabledWorlds.clear();
        for (String s : c.getStringList("enabled-worlds")) enabledWorlds.add(s);

        loadMobList(c.getStringList("mobs"),
                List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER"), mobs);

        raidsEnabled     = c.getBoolean("raids.enabled", true);
        raidsPerNightMin = Math.max(0, c.getInt("raids.per-night-min", 0));
        raidsPerNightMax = Math.max(raidsPerNightMin, c.getInt("raids.per-night-max", 2));
        raidMobCount     = Math.max(1, c.getInt("raids.mob-count-per-player", 12));
        raidMinRadius    = Math.max(4, c.getInt("raids.min-radius", 6));
        raidMaxRadius    = Math.max(raidMinRadius + 1, c.getInt("raids.max-radius", 24));
        raidIgnoreLight  = c.getBoolean("raids.ignore-light", true);
        raidAnnounce     = c.getBoolean("raids.announce", true);
        raidBlockSleep   = c.getBoolean("raids.block-sleep", true);
        raidDurationMillis = Math.max(5, c.getInt("raids.duration-seconds", 60)) * 1000L;
        raidSoundKey     = soundKey(c.getString("raids.sound", "ENTITY_ENDER_DRAGON_GROWL"));
        loadMobList(c.getStringList("raids.mobs"),
                List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER"), raidMobs);
    }

    private void loadMobList(List<String> names, List<String> fallback, List<EntityType> out) {
        out.clear();
        List<String> use = names.isEmpty() ? fallback : names;
        for (String s : use) {
            EntityType t = parseHostile(s);
            if (t != null) out.add(t);
            else getLogger().warning("(✧) '" + s + "' isn't a usable hostile mob — skipping.");
        }
        if (out.isEmpty()) out.add(EntityType.ZOMBIE);
    }

    // ----------------------------------------------------------------- ticking

    private void tick() {
        if (!enabled) return;

        // Lag guard: if the server is struggling, stop adding mobs and (optionally)
        // cull the existing hostile swarm — bosses excepted — to recover TPS.
        if (lagGuardEnabled && recentTps() <= lagTpsThreshold) {
            if (lagGuardCull) cullHostiles();
            return; // don't spawn or run raids while the server is choking
        }

        if (raidsEnabled) tickRaids();
        if (mobsPerPlayer == 0) return;

        // The driver only READS the online-player collection here; the actual
        // per-player spawning (which reads nearby blocks/entities and spawns mobs
        // around the player) must run on that player's own region thread.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, t -> trickleSpawn(p), null);
        }
    }

    /** Per-player trickle spawn — runs on the player's own region thread (Folia-safe). */
    private void trickleSpawn(Player p) {
        World w = p.getWorld();
        if (!applies(w)) return;
        if (w.getDifficulty() == Difficulty.PEACEFUL) return; // hostiles can't live
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (countNearbyHostiles(p) >= nearbyCap) return;

        // Trickle spawner: day or night, any weather/biome — just MORE at night.
        int count = isNight(w) ? (int) Math.round(mobsPerPlayer * nightMultiplier) : mobsPerPlayer;
        for (int i = 0; i < count; i++) {
            trySpawn(p, mobs.get(rnd(mobs.size())), minRadius, maxRadius, false, false);
        }
    }

    // ----------------------------------------------------------------- raids

    private void tickRaids() {
        if (raidMobs.isEmpty()) return;
        // The driver only READS the world list here; each world's raid bookkeeping
        // reads that world's time/difficulty/entities and spawns mobs, so it must
        // run on a region thread that owns that world's state (Folia-safe). We
        // anchor on the world's spawn location to pick the right region.
        for (World w : Bukkit.getWorlds()) {
            if (!applies(w)) continue;
            Bukkit.getRegionScheduler().execute(this, w.getSpawnLocation(), () -> tickRaidsForWorld(w));
        }
    }

    /** Per-world raid bookkeeping — runs on a region thread owning the world (Folia-safe). */
    private void tickRaidsForWorld(World w) {
        long now = System.currentTimeMillis();
        NightState st = nights.computeIfAbsent(w.getName(), k -> new NightState());
        boolean night = w.getDifficulty() != Difficulty.PEACEFUL && isNight(w);

        if (night && !st.wasNight) planNight(st); // dusk just fell — schedule the night's raids
        if (night && !st.pending.isEmpty()) {
            long tod = w.getTime();
            Iterator<Long> it = st.pending.iterator();
            while (it.hasNext()) {
                if (tod >= it.next()) {
                    it.remove();
                    fireRaid(w, raidMobs.get(rnd(raidMobs.size())));
                }
            }
        }
        st.wasNight = night;

        // Raid "active" window — drives the no-sleep rule + an end notice.
        // Ends as soon as all the raid's mobs are dead (or the timer caps it).
        boolean active = now < st.activeUntilMillis;
        if (active && raidMobsCleared(w)) {
            st.activeUntilMillis = 0; // horde wiped out — let players rest now
            active = false;
        }
        if (st.wasActive && !active && raidAnnounce) {
            for (Player p : w.getPlayers()) {
                p.sendMessage("§a(✧) the horde has thinned~ you can rest now. 🌙");
            }
        }
        st.wasActive = active;
    }

    /** True if no living raid-tagged mobs remain in the world. */
    private boolean raidMobsCleared(World w) {
        boolean cleared = true;
        Iterator<UUID> it = raidMobIds.iterator();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null || !e.isValid()) { it.remove(); continue; } // dead/removed — prune
            if (e.getWorld() == w) cleared = false;
        }
        return cleared;
    }

    /** True if a raid is currently raging in this world (so sleep is blocked). */
    private boolean raidActive(World w) {
        NightState st = nights.get(w.getName());
        if (st == null || System.currentTimeMillis() >= st.activeUntilMillis) return false;
        // Within the timer, but if the horde is already wiped, the raid is over.
        if (raidMobsCleared(w)) { st.activeUntilMillis = 0; return false; }
        return true;
    }

    private void planNight(NightState st) {
        st.pending.clear();
        int count = raidsPerNightMin + rnd(raidsPerNightMax - raidsPerNightMin + 1);
        for (int i = 0; i < count; i++) st.pending.add(13500L + rnd(8000)); // dusk..just before dawn
        Collections.sort(st.pending);
    }

    /** Unleash a single-species horde that hunts ONE chosen player in the world. */
    private void fireRaid(World w, EntityType type) {
        // Pick a victim: a random survival/adventure player actually in this world.
        List<Player> eligible = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            GameMode gm = p.getGameMode();
            if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) eligible.add(p);
        }
        if (eligible.isEmpty()) return; // nobody to raid
        Player victim = eligible.get(rnd(eligible.size()));

        // Mark the world as "under raid" so sleeping is blocked for a while.
        nights.computeIfAbsent(w.getName(), k -> new NightState())
                .activeUntilMillis = System.currentTimeMillis() + raidDurationMillis;

        if (raidAnnounce) {
            // Server-wide chat shout (names the victim) + raid sound for everyone.
            // sendMessage is thread-safe, but playSound touches each player, so it
            // hops onto that player's own region thread (Folia-safe).
            String shout = "§4⚔ §cRaid! §fA horde of " + pluralize(type)
                    + " §cis attacking §f" + victim.getName() + "§c! ⚔";
            for (Player p : getServer().getOnlinePlayers()) {
                p.sendMessage(shout);
                if (raidSoundKey != null) {
                    p.getScheduler().run(this, t -> {
                        try { p.playSound(p.getLocation(), raidSoundKey, 1.2f, 1.0f); } catch (Throwable ignored) {}
                    }, null);
                }
            }
        }

        // The title + horde spawn all touch the victim and the blocks/entities
        // around them, so they must run on the victim's own region thread (Folia).
        final EntityType raidType = type;
        final String worldName = w.getName();
        victim.getScheduler().run(this, t -> spawnRaidHorde(victim, raidType, worldName), null);
    }

    /** Spawn the horde around the victim + title them — runs on the victim's region thread. */
    private void spawnRaidHorde(Player victim, EntityType type, String worldName) {
        if (raidAnnounce) {
            // Big on-screen title for the player being hunted.
            try {
                victim.sendTitle("§4§l⚔ A raid is happening!",
                        "§ca horde of §f" + pluralize(type) + " §cis hunting you!", 10, 60, 20);
            } catch (Throwable ignored) {}
        }

        // Spawn the horde around the victim and point every mob AT them.
        int spawned = 0;
        for (int i = 0; i < raidMobCount; i++) {
            Entity m = trySpawn(victim, type, raidMinRadius, raidMaxRadius, raidIgnoreLight, true);
            if (m == null) continue;
            m.getPersistentDataContainer().set(raidTagKey, PersistentDataType.BYTE, (byte) 1);
            raidMobIds.add(m.getUniqueId()); // track so we don't have to scan all world entities
            if (m instanceof Mob mob) {
                try { mob.setTarget(victim); } catch (Throwable ignored) {}
            }
            spawned++;
        }
        getLogger().info("(✧) raid in " + worldName + " on " + victim.getName()
                + ": " + type + " (spawned " + spawned + ")");
    }

    // ----------------------------------------------------------------- spawning

    private static final int NO_SPOT = Integer.MIN_VALUE;

    /**
     * Try once to spawn {@code type} in a ring around the player.
     * {@code nearPlayerY} scans near the player's own Y (so raids reach players
     * in caves/underground), otherwise it uses the surface column.
     */
    private Entity trySpawn(Player p, EntityType type, int minR, int maxR,
                            boolean ignoreLight, boolean nearPlayerY) {
        World w = p.getWorld();
        Location pl = p.getLocation();
        int px = pl.getBlockX(), pz = pl.getBlockZ(), py = pl.getBlockY();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < spawnAttempts; attempt++) {
            double ang = r.nextDouble() * Math.PI * 2.0;
            double dist = minR + r.nextDouble() * Math.max(1, maxR - minR);
            int x = px + (int) Math.round(Math.cos(ang) * dist);
            int z = pz + (int) Math.round(Math.sin(ang) * dist);

            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue; // don't force-load chunks
            int feetY = nearPlayerY ? findStandY(w, x, z, py) : surfaceStandY(w, x, z);
            if (feetY == NO_SPOT) continue;

            Block feet = w.getBlockAt(x, feetY, z);
            if (!ignoreLight && feet.getLightFromBlocks() > maxBlockLight) continue; // torch-lit → safe

            Location loc = new Location(w, x + 0.5, feetY, z + 0.5, (float) Math.toDegrees(ang), 0f);
            try {
                // Spawn as NATURAL (not CUSTOM) so these count for KILL quests and
                // despawn like normal mobs. Fall back to the basic call if the
                // reason-aware overload isn't available on this server.
                try {
                    return w.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.NATURAL);
                } catch (Throwable noOverload) {
                    return w.spawnEntity(loc, type);
                }
            } catch (Throwable ignored) { /* some types refuse certain spots */ }
        }
        return null;
    }

    /** Mob-feet Y on the surface column, or {@link #NO_SPOT} if unsuitable. */
    private static int surfaceStandY(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z);
        Block ground = w.getBlockAt(x, y, z);
        Block feet = w.getBlockAt(x, y + 1, z);
        Block head = w.getBlockAt(x, y + 2, z);
        if (!ground.getType().isSolid() || ground.isLiquid()) return NO_SPOT;
        if (feet.getType() != Material.AIR || head.getType() != Material.AIR) return NO_SPOT;
        return y + 1;
    }

    /** Mob-feet Y near the player's level (scans a small band) for cave raids. */
    private static int findStandY(World w, int x, int z, int py) {
        int top = Math.min(w.getMaxHeight() - 2, py + 3);
        int bottom = Math.max(w.getMinHeight() + 1, py - 6);
        for (int y = top; y >= bottom; y--) {
            Block ground = w.getBlockAt(x, y - 1, z);
            Block feet = w.getBlockAt(x, y, z);
            Block head = w.getBlockAt(x, y + 1, z);
            if (ground.getType().isSolid() && !ground.isLiquid()
                    && feet.getType() == Material.AIR && head.getType() == Material.AIR) {
                return y;
            }
        }
        return NO_SPOT;
    }

    private int countNearbyHostiles(Player p) {
        int n = 0;
        double r = maxRadius + 4;
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (e instanceof Monster) n++;
        }
        return n;
    }

    /**
     * No sleeping your way out of a raid. If one is raging you can't sleep at
     * all; and if a raid is scheduled for tonight, trying to sleep summons it
     * NOW instead of letting you skip the night — so the raid happens whether
     * you sleep or not.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        if (!raidsEnabled || !raidBlockSleep) return;
        World w = e.getPlayer().getWorld();
        if (!applies(w)) return;

        if (raidActive(w)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§c(✧) you can't sleep during a raid! ⚔ survive it first~");
            return;
        }
        NightState st = nights.get(w.getName());
        if (st != null && isNight(w) && !st.pending.isEmpty()) {
            st.pending.remove(0); // consume one of tonight's scheduled raids
            e.setCancelled(true);
            e.getPlayer().sendMessage("§c(✧) no resting tonight — a raid strikes! ⚔");
            fireRaid(w, raidMobs.get(rnd(raidMobs.size())));
        }
    }

    /** Stop hostile mobs burning in daylight (keep fire/lava/flaming-arrow damage). */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (!preventSunBurn) return;
        if (e instanceof EntityCombustByBlockEvent || e instanceof EntityCombustByEntityEvent) return;
        if (e.getEntity() instanceof Monster) e.setCancelled(true);
    }

    /** Drop dead raid mobs from the tracked set so it stays small and accurate. */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (!raidMobIds.isEmpty()) raidMobIds.remove(e.getEntity().getUniqueId());
    }

    /** Most recent server TPS (defaults to 20 if the API is unavailable). */
    private static double recentTps() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps != null && tps.length > 0 ? tps[0] : 20.0;
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    /** Remove all non-persistent hostile mobs (except bosses) in applicable worlds. */
    private void cullHostiles() {
        // Iterating a world's entities and removing them must happen on a region
        // thread owning that world (Folia-safe). The driver only reads the world
        // list; each world's cull hops to a region thread anchored at its spawn.
        for (World w : Bukkit.getWorlds()) {
            if (!applies(w)) continue;
            Bukkit.getRegionScheduler().execute(this, w.getSpawnLocation(), () -> cullHostilesIn(w));
        }
    }

    /** Cull non-persistent hostiles in one world — runs on that world's region thread. */
    private void cullHostilesIn(World w) {
        int removed = 0;
        for (Monster e : w.getEntitiesByClass(Monster.class)) {
            if (!BOSSES.contains(e.getType()) && !e.isPersistent()) {
                e.remove();
                removed++;
            }
        }
        if (removed > 0) {
            getLogger().warning("(✧) low TPS (" + String.format("%.1f", recentTps())
                    + ") — culled " + removed + " hostile mob(s) to recover.");
        }
    }

    private boolean isNight(World w) {
        long t = w.getTime();
        // Monster-spawn window-ish: dusk (~13000) to dawn (~23000). Storms count too.
        return (t >= 13000 && t <= 23000) || w.isThundering();
    }

    private boolean applies(World w) {
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        return enabledWorlds.isEmpty() || enabledWorlds.contains(w.getName());
    }

    // ----------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("kawaiinights.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            readConfig();
            sender.sendMessage("§d(✧) KawaiiNights reloaded ✨ (§f" + mobs.size() + "§d spawn / §f"
                    + raidMobs.size() + "§d raid mob types)");
            return true;
        }
        if (sub.equals("raid")) {
            if (!sender.hasPermission("kawaiinights.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            World w = (sender instanceof Player pl) ? pl.getWorld()
                    : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
            if (w == null) { sender.sendMessage("§c(✧) no world to raid~"); return true; }
            EntityType type = raidMobs.get(rnd(raidMobs.size()));
            if (args.length >= 2) {
                EntityType parsed = parseHostile(args[1]);
                if (parsed == null) { sender.sendMessage("§c(✧) '" + args[1] + "' isn't a hostile mob~"); return true; }
                type = parsed;
            }
            fireRaid(w, type);
            sender.sendMessage("§d(✧) ⚔ raid started in §f" + w.getName() + "§d: §f" + pluralize(type) + "~");
            return true;
        }
        sender.sendMessage("§d(✧) KawaiiNights — hostile mobs any time + nightly raids.");
        sender.sendMessage("§7  /knights raid [mob] §8• §7/knights reload");
        return true;
    }

    // ----------------------------------------------------------------- helpers

    private EntityType parseHostile(String s) {
        try {
            EntityType t = EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
            Class<?> cls = t.getEntityClass();
            return (cls != null && Monster.class.isAssignableFrom(cls)) ? t : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Turn a configured sound into a Minecraft sound key for the String-based
     * playSound overload (avoids the Sound enum/interface split across 1.21.x).
     * Accepts ENTITY_ENDER_DRAGON_GROWL or entity.ender_dragon.growl style.
     */
    private static String soundKey(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.indexOf('.') >= 0 || s.indexOf(':') >= 0) return s.toLowerCase(Locale.ROOT);
        return s.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static int rnd(int bound) {
        return ThreadLocalRandom.current().nextInt(Math.max(1, bound));
    }

    private static String prettyName(EntityType t) {
        String s = t.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (char ch : s.toCharArray()) {
            if (cap && Character.isLetter(ch)) { sb.append(Character.toUpperCase(ch)); cap = false; }
            else sb.append(ch);
            if (ch == ' ') cap = true;
        }
        return sb.toString();
    }

    private static String pluralize(EntityType t) {
        String n = prettyName(t);
        return n.endsWith("s") ? n : n + "s";
    }
}
