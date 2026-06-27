package com.ferisooo.herobrine;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Entry point + wiring for the Herobrine system. Holds every manager, runs the
 * master tick loop, registers the command + listeners, applies the ambient
 * environmental effects, and handles persistence on enable/disable.
 */
public final class HerobrinePlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener, TabExecutor {

    private ConfigManager cfg;
    private ThreatManager threats;
    private StructureManager structures;
    private HallucinationManager hallucinations;
    private AbilityManager abilities;
    private HerobrineManager herobrine;
    private EncounterManager encounters;
    private BossFightManager boss;

    private NamespacedKey minionKey;
    private long lastDecayTick;
    private long lastSaveTick;
    // Monotonic game-tick clock advanced by the master tick (which runs once a
    // second, so this steps by 20 each cycle). Used everywhere instead of the
    // experimental Server#getCurrentTick so timing stays portable.
    private long currentTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.minionKey = new NamespacedKey(this, "shadow_minion");
        this.cfg = new ConfigManager(this);
        this.threats = new ThreatManager(this);
        this.structures = new StructureManager(this);
        this.hallucinations = new HallucinationManager(this);
        this.abilities = new AbilityManager(this);
        this.herobrine = new HerobrineManager(this);
        this.boss = new BossFightManager(this);
        this.encounters = new EncounterManager(this);

        // Expose the cross-plugin API (KawaiiCompanion reaches this by reflection).
        HerobrineService.init(this);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("herobrine") != null) getCommand("herobrine").setExecutor(this);

        // behaviour tick — every 2 game ticks (10 Hz) so Herobrine moves/looks
        // smoothly; heavy per-player work inside is gated to ~1 Hz.
        // Folia-safe: a global-region driver reads server/world state and the
        // online-player collection; per-player mutation hops to each player's
        // entity scheduler (see tick()).
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> tick(), 2L, 2L);
        // structure generation tick — its own slower cadence
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> structureTick(),
                Math.max(1L, cfg.structureInterval()), Math.max(1L, cfg.structureInterval()));
        // packet NPC re-send tick — keeps the stalker visible to players moving into range
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> resendTick(), 40L, 40L);

        getLogger().info("Herobrine is watching. (" + getServer().getOnlinePlayers().size() + " online)");
    }

    @Override
    public void onDisable() {
        if (boss != null && boss.isActive()) boss.stop(false);
        if (herobrine != null) herobrine.despawn();
        if (threats != null) threats.save();
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        getServer().getAsyncScheduler().cancelTasks(this);
        getLogger().info("Herobrine fades away.");
    }

    // ===================== master tick =====================

    private void tick() {
        currentTick += 2; // this task runs every 2 game ticks
        long now = currentTick;

        // ---- Behaviour: runs every call (10 Hz) so he actually moves + looks ----
        herobrine.stalkingTick();
        herobrine.huntingTick();

        // ---- Heavy per-player work: only ~once per second ----
        if (now % 20 != 0) return;

        final boolean escalate = now % 200 == 0; // escalation check every 10s
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            // Per-player work touches the player + the blocks around them, so it
            // must run on that player's region thread (Folia-safe).
            p.getScheduler().run(this, t -> {
                threats.updateDistance(p);
                trackUnderground(p);
                trackIsolation(p);
                if (escalate) encounters.evaluate(p);
                applyEnvironment(p);
            }, null);
        }

        if (now - lastDecayTick >= 1200) { // ~1 minute
            threats.decayTick();
            lastDecayTick = now;
        }
        if (now - lastSaveTick >= 6000) { // ~5 minutes autosave
            threats.save();
            lastSaveTick = now;
        }
    }

    private void structureTick() {
        if (!cfg.structuresEnabled()) return;
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            // Generates blocks around the player -> run on the player's region thread.
            p.getScheduler().run(this, t -> {
                String built = structures.maybeGenerate(p, threats.getThreat(p));
                if (built != null) log("Built a " + built + " near " + p.getName());
            }, null);
        }
    }

    private void resendTick() {
        for (Player p : getServer().getOnlinePlayers()) {
            // Reads the player's location + sends packets to them -> player region thread.
            p.getScheduler().run(this, t -> herobrine.resendIfNear(p), null);
        }
    }

    // ---- tracking helpers ----

    private void trackUnderground(Player p) {
        Block above = p.getWorld().getBlockAt(p.getLocation().getBlockX(),
                Math.min(p.getWorld().getMaxHeight() - 1, p.getLocation().getBlockY() + 8),
                p.getLocation().getBlockZ());
        boolean underground = p.getLocation().getBlockY() < 55 && above.getType().isSolid();
        if (underground) threats.onUndergroundTick(p, 1);
    }

    private void trackIsolation(Player p) {
        boolean alone = true;
        for (Player other : p.getWorld().getPlayers()) {
            if (other == p) continue;
            if (other.getLocation().distanceSquared(p.getLocation()) < 64 * 64) { alone = false; break; }
        }
        if (alone) threats.onIsolationTick(p, 1);
        else threats.resetIsolation(p);
    }

    // ===================== environmental ambience =====================

    private void applyEnvironment(Player p) {
        if (!cfg.environmentEnabled()) return;
        double threat = threats.getThreat(p);
        boolean herobrineNear = herobrine.hasActive()
                && herobrine.active().getLocation().getWorld() == p.getWorld()
                && herobrine.active().getLocation().distanceSquared(p.getLocation()) < 64 * 64;
        // ambience scales with threat; intensifies when he's actually near
        double intensity = threat / 100.0 + (herobrineNear ? 0.5 : 0.0);
        if (intensity <= 0.05) return;

        if (cfg.caveSounds() && ThreadLocalRandom.current().nextDouble() < intensity * 0.15) {
            Sound[] pool = {Sound.AMBIENT_CAVE, Sound.ENTITY_WARDEN_NEARBY_CLOSE, Sound.AMBIENT_BASALT_DELTAS_MOOD};
            p.playSound(p.getLocation(), pool[ThreadLocalRandom.current().nextInt(pool.length)], 0.5f, 0.7f);
        }
        if (cfg.fog() && ThreadLocalRandom.current().nextDouble() < intensity * 0.4) {
            p.spawnParticle(Particle.LARGE_SMOKE,
                    p.getLocation().add(ThreadLocalRandom.current().nextDouble(-5, 5), 0,
                            ThreadLocalRandom.current().nextDouble(-5, 5)), 4, 0.5, 0.3, 0.5, 0.0);
        }
        if (cfg.animalPanic() && herobrineNear && ThreadLocalRandom.current().nextDouble() < 0.2) {
            panicAnimals(p);
        }
        if (cfg.doorInteractions() && herobrineNear && ThreadLocalRandom.current().nextDouble() < 0.1) {
            toggleNearbyDoor(p);
        }
        if (cfg.compassInterference() && intensity > 0.6 && ThreadLocalRandom.current().nextDouble() < 0.2) {
            // Compass "wavers": a low drone + an unsettling action-bar flicker.
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.3f, 0.5f);
            p.sendActionBar(net.kyori.adventure.text.Component.text("Your compass spins wildly...")
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.ITALIC));
        }
    }

    private void panicAnimals(Player p) {
        for (Entity e : p.getNearbyEntities(12, 6, 12)) {
            if (e instanceof Animals animal && animal instanceof LivingEntity le) {
                le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, false));
                Location away = animal.getLocation().add(
                        animal.getLocation().toVector().subtract(p.getLocation().toVector()).normalize().multiply(2));
                animal.setVelocity(away.toVector().subtract(animal.getLocation().toVector()).normalize().multiply(0.4));
            }
        }
    }

    private void toggleNearbyDoor(Player p) {
        Location base = p.getLocation();
        org.bukkit.World world = base.getWorld();
        if (world == null) return;
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue; // never force-load
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getBlockData() instanceof Openable openable
                            && b.getType().name().contains("DOOR")) {
                        openable.setOpen(!openable.isOpen());
                        b.setBlockData(openable, true);
                        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 0.8f);
                        return;
                    }
                }
            }
        }
    }

    // ===================== listeners =====================

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!cfg.threatEnabled()) return;
        Material m = e.getBlock().getType();
        if (m.name().endsWith("_ORE") || m.name().contains("STONE") || m == Material.DEEPSLATE
                || m == Material.NETHERRACK) {
            threats.onBlockMined(e.getPlayer());
        }
    }

    @EventHandler
    public void onBed(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        threats.onSleep(e.getPlayer());
        encounters.onSleepTrigger(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        threats.onDeath(p);
        // If Herobrine's current target dies, that encounter ends (he got them).
        HerobrineEntity active = herobrine.active();
        boolean wasTarget = active != null && p.getUniqueId().equals(active.getTargetId());
        if (boss.isActive() && boss.boss() != null && p.getUniqueId().equals(boss.boss().getTargetId())) {
            // boss's victim died — boss claims victory and recedes
            broadcastNearby(p.getLocation(), 64, cfg.msg("boss-victory", "&4Herobrine claims another soul..."));
            boss.stop(false);
            encounters.activeTargets().remove(p.getUniqueId());
        } else if (wasTarget) {
            herobrine.despawn();
            encounters.activeTargets().remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Touches the joining player -> their entity scheduler (Folia-safe).
        p.getScheduler().runDelayed(this, t -> herobrine.resendIfNear(p), null, 20L);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        herobrine.tryHit(e.getPlayer(), weaponDamage(e.getPlayer()));
    }

    private double weaponDamage(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return 1.0;
        return switch (hand.getType()) {
            case NETHERITE_SWORD -> 8.0;
            case DIAMOND_SWORD -> 7.0;
            case IRON_SWORD -> 6.0;
            case STONE_SWORD, GOLDEN_SWORD -> 5.0;
            case WOODEN_SWORD -> 4.0;
            case NETHERITE_AXE -> 10.0;
            case DIAMOND_AXE -> 9.0;
            case IRON_AXE -> 9.0;
            default -> 2.0;
        };
    }

    // ===================== command =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(cfg.prefix() + "§7/herobrine spawn|despawn|hunt|boss|threat|reload");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "spawn" -> {
                if (!perm(sender, "herobrine.spawn")) return true;
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Location at = p.getLocation().add(p.getLocation().getDirection().multiply(8));
                Location g = new Location(at.getWorld(), at.getBlockX() + 0.5,
                        at.getWorld().getHighestBlockYAt(at) + 1, at.getBlockZ() + 0.5);
                HerobrineEntity e = herobrine.spawnStalker(p, g);
                sender.sendMessage(cfg.prefix() + (e != null ? "§7Herobrine appears." : "§cSpawn failed (NMS unavailable)."));
            }
            case "despawn" -> {
                if (!perm(sender, "herobrine.spawn")) return true;
                if (boss.isActive()) boss.stop(false);
                herobrine.despawn();
                sender.sendMessage(cfg.prefix() + "§7Herobrine vanishes.");
            }
            case "hunt" -> {
                if (!perm(sender, "herobrine.spawn")) return true;
                Player tgt = resolveTarget(sender, args);
                if (tgt == null) { sender.sendMessage(cfg.prefix() + "§cPlayer not found."); return true; }
                encounters.addTarget(tgt);
                herobrine.beginHunt(tgt);
                sender.sendMessage(cfg.prefix() + "§7Herobrine now hunts §f" + tgt.getName());
            }
            case "boss" -> {
                if (!perm(sender, "herobrine.boss")) return true;
                Player tgt = resolveTarget(sender, args);
                if (tgt == null) { sender.sendMessage(cfg.prefix() + "§cPlayer not found."); return true; }
                encounters.addTarget(tgt);
                boolean ok = boss.start(tgt);
                sender.sendMessage(cfg.prefix() + (ok ? "§4The boss encounter begins for §f" + tgt.getName()
                        : "§cCould not start boss (already active or NMS unavailable)."));
            }
            case "threat" -> {
                if (!perm(sender, "herobrine.admin")) return true;
                Player tgt = resolveTarget(sender, args);
                if (tgt == null) { sender.sendMessage(cfg.prefix() + "§cPlayer not found."); return true; }
                if (args.length >= 3) {
                    try {
                        double v = Double.parseDouble(args[2]);
                        threats.setThreat(tgt, v);
                        sender.sendMessage(cfg.prefix() + "§7Set §f" + tgt.getName() + "§7's threat to §c"
                                + (int) threats.getThreat(tgt));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(cfg.prefix() + "§cInvalid number.");
                    }
                } else {
                    ThreatManager.Stats s = threats.stats(tgt.getUniqueId());
                    sender.sendMessage(cfg.prefix() + "§f" + tgt.getName() + " §7threat: §c" + (int) s.threat
                            + " §8| mined " + (s.miningTicks / 1200) + "m, underground "
                            + (s.undergroundTicks / 1200) + "m, slept " + s.sleepCount
                            + ", deaths " + s.deathCount + ", maxDist " + (int) s.maxDistanceFromSpawn);
                }
            }
            case "reload" -> {
                if (!perm(sender, "herobrine.reload")) return true;
                cfg.reload();
                sender.sendMessage(cfg.prefix() + "§7Configuration reloaded.");
            }
            default -> sender.sendMessage(cfg.prefix() + "§7/herobrine spawn|despawn|hunt|boss|threat|reload");
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 2) return getServer().getPlayer(args[1]);
        return sender instanceof Player p ? p : null;
    }

    private boolean perm(CommandSender sender, String node) {
        if (sender.hasPermission(node) || sender.hasPermission("herobrine.admin")) return true;
        sender.sendMessage(cfg.prefix() + "§cYou don't have permission.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("spawn", "despawn", "hunt", "boss", "threat", "reload"), args[0]);
        }
        if (args.length == 2 && Arrays.asList("hunt", "boss", "threat").contains(args[0].toLowerCase())) {
            List<String> names = new ArrayList<>();
            for (Player p : getServer().getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }

    // ===================== shared helpers =====================

    public ConfigManager cfg() { return cfg; }
    public ThreatManager threats() { return threats; }
    public StructureManager structures() { return structures; }
    public HallucinationManager hallucinations() { return hallucinations; }
    public AbilityManager abilities() { return abilities; }
    public HerobrineManager herobrine() { return herobrine; }
    public EncounterManager encounters() { return encounters; }
    public BossFightManager boss() { return boss; }
    public NamespacedKey minionKey() { return minionKey; }
    public long currentTick() { return currentTick; }

    public void log(String msg) { getLogger().info(msg); }

    public void sendPrefixed(Player p, String msg) {
        if (msg != null && !msg.isEmpty()) p.sendMessage(cfg.prefix() + msg);
    }

    public void broadcastNearby(Location center, double radius, String msg) {
        if (msg == null || msg.isEmpty() || center.getWorld() == null) return;
        double radiusSq = radius * radius;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= radiusSq) p.sendMessage(cfg.prefix() + msg);
        }
    }
}
