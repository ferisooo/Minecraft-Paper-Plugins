package com.ferisooo.kawaiidungeons;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Owns all live dungeon runs. Responsible for:
 * <ul>
 *   <li>Cloning a per-dungeon TEMPLATE world (async copy, skipping lock files)
 *       into a fresh {@code kawaii_dgn_<id>_<runid>} folder, then loading it on
 *       the main thread and teleporting the party in.</li>
 *   <li>Running a per-second tick: objective progress, timers, wave spawning,
 *       boss-phase detection, downed-timer countdown, and win/lose checks.</li>
 *   <li>Cleanup on completion/failure/empty/timeout: teleport players out,
 *       unload the world, and recursively delete the folder.</li>
 * </ul>
 *
 * <p>The world-clone technique mirrors KawaiiSkyblock's proven approach.
 */
public final class InstanceManager {

    private final KawaiiDungeons plugin;
    private final MobFactory mobs;
    private final LootManager loot;
    private final ProgressManager progress;

    /** worldName -> live instance. */
    private final Map<String, DungeonInstance> instances = new HashMap<>();

    public InstanceManager(KawaiiDungeons plugin, MobFactory mobs, LootManager loot, ProgressManager progress) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.loot = loot;
        this.progress = progress;
    }

    public DungeonInstance byWorld(String worldName) { return instances.get(worldName); }

    public DungeonInstance instanceOfPlayer(Player p) {
        DungeonInstance inst = instances.get(p.getWorld().getName());
        if (inst != null && inst.isParticipant(p.getUniqueId())) return inst;
        // Fall back to a scan (player might have wandered worlds).
        for (DungeonInstance i : instances.values()) {
            if (i.isParticipant(p.getUniqueId())) return i;
        }
        return null;
    }

    public boolean isInstanceWorld(String worldName) { return instances.containsKey(worldName); }

    private World mainWorld() { return Bukkit.getWorlds().get(0); }

    // --------------------------------------------------------------- start

    /**
     * Starts a run for the given party. Validates the template, clones it async,
     * then loads + teleports on the main thread. Returns false if a hard
     * precondition (missing template) fails synchronously.
     */
    public boolean start(DungeonDef def, DungeonInstance.Difficulty difficulty, List<Player> party, UUID leader,
                         boolean speedrun, boolean deathless, boolean hardcore) {
        File container = Bukkit.getWorldContainer();
        File template = new File(container, def.templateWorld);
        if (!template.isDirectory() || !new File(template, "level.dat").exists()) {
            for (Player p : party) {
                p.sendMessage("§c(✿) the dungeon template world '" + def.templateWorld + "' is missing!");
                p.sendMessage("§c(✿) admin: place that folder in the server root (next to §fworld/§c).");
            }
            return false;
        }

        String runId = Long.toHexString(System.nanoTime() & 0xffffff);
        String folderName = "kawaii_dgn_" + def.id + "_" + runId;
        File dest = new File(container, folderName);
        if (dest.exists()) {
            for (Player p : party) p.sendMessage("§c(✿) instance folder collision — try again~");
            return false;
        }

        double healthMult = plugin.getConfig().getDouble("difficulty." + difficulty.name().toLowerCase(Locale.ROOT) + ".health-mult", 1.0);
        double damageMult = plugin.getConfig().getDouble("difficulty." + difficulty.name().toLowerCase(Locale.ROOT) + ".damage-mult", 1.0);
        double lootMult = plugin.getConfig().getDouble("difficulty." + difficulty.name().toLowerCase(Locale.ROOT) + ".loot-mult", 1.0);
        if (hardcore) lootMult *= plugin.getConfig().getDouble("hardcore.loot-mult", 1.5);
        final double finalLootMult = lootMult; // capture for the async lambda (lootMult is reassigned above)

        final List<UUID> partyIds = new ArrayList<>();
        for (Player p : party) partyIds.add(p.getUniqueId());

        for (Player p : party) p.sendMessage("§d(✿) ✨ weaving your dungeon instance... one moment~");

        final Path src = template.toPath();
        final Path dst = dest.toPath();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyRecursively(src, dst);
                Files.deleteIfExists(dst.resolve("uid.dat"));
                Files.deleteIfExists(dst.resolve("session.lock"));
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (UUID id : partyIds) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) p.sendMessage("§c(✿) failed to copy the dungeon template: " + t.getMessage());
                    }
                });
                try { deleteRecursively(dst); } catch (Throwable ignored) { /* best effort */ }
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () ->
                    finishStart(def, difficulty, folderName, dst, partyIds, leader, speedrun, deathless, hardcore,
                            healthMult, damageMult, finalLootMult));
        });
        return true;
    }

    private void finishStart(DungeonDef def, DungeonInstance.Difficulty difficulty, String folderName, Path dst,
                             List<UUID> partyIds, UUID leader, boolean speedrun, boolean deathless, boolean hardcore,
                             double healthMult, double damageMult, double lootMult) {
        World w;
        try {
            w = new WorldCreator(folderName).createWorld();
        } catch (Throwable t) {
            announce(partyIds, "§c(✿) failed to load the dungeon world: " + t.getMessage());
            try { deleteRecursively(dst); } catch (Throwable ignored) { /* best effort */ }
            return;
        }
        if (w == null) {
            announce(partyIds, "§c(✿) the dungeon world failed to load — check console~");
            try { deleteRecursively(dst); } catch (Throwable ignored) { /* best effort */ }
            return;
        }

        DungeonInstance inst = new DungeonInstance(folderName, def, difficulty, leader,
                speedrun, deathless, hardcore, healthMult, damageMult, lootMult);
        instances.put(folderName, inst);

        Location spawn = def.spawn(w);
        for (UUID id : partyIds) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            inst.addParticipant(id);
            p.teleport(spawn);
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.sendMessage("§d(✿) ✨ welcome to §f" + ChatColor.translateAlternateColorCodes('&', def.displayName)
                    + "§d — difficulty §f" + difficulty.name().toLowerCase(Locale.ROOT)
                    + (hardcore ? " §c[HARDCORE]" : "") + (speedrun ? " §e[SPEEDRUN]" : "")
                    + (deathless ? " §b[DEATHLESS]" : ""));
            p.playSound(p.getLocation(), "minecraft:block.beacon.activate", 1f, 1f);
        }

        // Spawn static mobs.
        for (DungeonDef.MobSpawn ms : def.mobs) {
            spawnGroup(inst, w, ms, false);
        }

        // Spawn boss for KILL_BOSS (and any boss-bearing dungeon).
        if (def.hasBoss() && def.objectiveType == Objective.Type.KILL_BOSS) {
            spawnBoss(inst, w);
        }

        // Objective-specific setup.
        setupObjective(inst, w);

        // Boss bar.
        if (plugin.getConfig().getBoolean("toggles.boss-bar-objective", true)) {
            inst.ensureBossBar(barTitle(inst));
            for (Player p : inst.onlineParticipants()) inst.bossBar().addPlayer(p);
        }
    }

    private void setupObjective(DungeonInstance inst, World w) {
        switch (inst.def.objectiveType) {
            case DEFEND_NPC, ESCORT_NPC -> {
                LivingEntity npc = mobs.spawnRole(inst.def.npcLoc(w), "VILLAGER", inst.worldName, "npc", 4.0);
                if (npc != null) {
                    npc.setCustomName(ChatColor.translateAlternateColorCodes('&', "&e&lProtect Me!"));
                    inst.setNpc(npc.getUniqueId());
                }
            }
            case COLLECT_RELICS -> {
                // Relics represented as armor stands the players right-click.
                for (int i = 0; i < inst.objective.target(); i++) {
                    Location l = inst.def.spawn(w).clone().add(2 + i * 2, 0, 4);
                    LivingEntity relic = mobs.spawnRole(l, "ARMOR_STAND", inst.worldName, "relic", 1.0);
                    if (relic != null) relic.setCustomName(ChatColor.translateAlternateColorCodes('&', "&b✧ Relic"));
                }
            }
            case ACTIVATE_SHRINES -> {
                for (int i = 0; i < inst.objective.target(); i++) {
                    Location l = inst.def.spawn(w).clone().add(2 + i * 2, 0, -4);
                    LivingEntity shrine = mobs.spawnRole(l, "ARMOR_STAND", inst.worldName, "shrine", 1.0);
                    if (shrine != null) shrine.setCustomName(ChatColor.translateAlternateColorCodes('&', "&d✦ Shrine (inactive)"));
                }
            }
            default -> { /* KILL_BOSS, DESTROY_CRYSTALS, SURVIVE_WAVES, TIMED_CHALLENGE handled via events/ticks */ }
        }
    }

    private void spawnGroup(DungeonInstance inst, World w, DungeonDef.MobSpawn ms, boolean isWave) {
        MobFactory.Tier tier = MobFactory.tier(ms.tier);
        for (int i = 0; i < ms.count; i++) {
            Location l = ms.at(w).clone().add(ThreadLocalRandom.current().nextDouble(-1, 1), 0,
                    ThreadLocalRandom.current().nextDouble(-1, 1));
            mobs.spawn(l, ms.type, tier, inst.worldName, inst.healthMult, inst.damageMult, false);
        }
    }

    private void spawnBoss(DungeonInstance inst, World w) {
        double base = inst.def.bossBaseHealth;
        if (inst.hardcore) base *= plugin.getConfig().getDouble("hardcore.boss-health-mult", 2.0);
        double finalHealth = Math.max(1.0, base * inst.healthMult);
        LivingEntity boss = mobs.spawn(inst.def.bossLoc(w), inst.def.bossType, MobFactory.Tier.BOSS,
                inst.worldName, 1.0 /* health applied explicitly below */, inst.damageMult, true);
        if (boss == null) return;
        applyBossHealth(boss, finalHealth);
        inst.setBoss(boss, finalHealth);
    }

    @SuppressWarnings("deprecation")
    private void applyBossHealth(LivingEntity boss, double health) {
        boss.setMaxHealth(health);
        boss.setHealth(health);
    }

    // --------------------------------------------------------------- tick

    /** Called every second (resolved from instance-tick-ticks). */
    public void tickAll() {
        if (instances.isEmpty()) return;
        for (DungeonInstance inst : new ArrayList<>(instances.values())) {
            try {
                tickInstance(inst);
            } catch (Throwable t) {
                plugin.getLogger().warning("(✿) error ticking instance " + inst.worldName + ": " + t.getMessage());
            }
        }
    }

    private void tickInstance(DungeonInstance inst) {
        if (inst.finished()) return;
        World w = inst.world();
        if (w == null) { fail(inst, "the dungeon world vanished"); return; }

        inst.tickSecond();

        // Downed timers (this task is ~1s; ticks stored are absolute seconds remaining).
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(inst.downed().entrySet())) {
            int remaining = e.getValue() - 1;
            if (remaining <= 0) expired.add(e.getKey());
            else inst.downed().put(e.getKey(), remaining);
        }
        for (UUID id : expired) downOut(inst, id);

        // Spectator-mode revive: a living teammate sneaking within 2 blocks of a
        // downed spectator revives them (spectators can't be right-clicked).
        if (plugin.getConfig().getBoolean("revive.use-spectator", true)) {
            tickSpectatorRevive(inst);
        }

        // Empty instance? (no online participants left)
        boolean anyOnline = !inst.onlineParticipants().isEmpty();
        if (!anyOnline) { fail(inst, "everyone left the dungeon"); return; }

        // All down or out -> wipe.
        if (inst.allDownOrOut()) { fail(inst, "the whole party fell"); return; }

        // Hard time limit.
        if (inst.timedOut()) { fail(inst, "you ran out of time"); return; }

        // Objective-specific per-second logic.
        tickObjective(inst, w);

        // Boss phase detection.
        if (inst.bossId() != null) tickBoss(inst, w);

        // Wave spawning for SURVIVE_WAVES / DEFEND_NPC.
        tickWaves(inst, w);

        // Boss-bar / action-bar progress.
        updateProgressDisplay(inst);

        if (inst.objective.isComplete()) { complete(inst); return; }
        if (inst.objective.isFailed()) { fail(inst, "objective failed"); }
    }

    private void tickObjective(DungeonInstance inst, World w) {
        switch (inst.def.objectiveType) {
            case DEFEND_NPC -> {
                LivingEntity npc = npcEntity(inst, w);
                if (npc == null || npc.isDead()) { inst.objective.fail(); return; }
                inst.objective.setProgress(inst.elapsedSeconds());
            }
            case ESCORT_NPC -> {
                LivingEntity npc = npcEntity(inst, w);
                if (npc == null || npc.isDead()) { inst.objective.fail(); return; }
                // Simple escort: teleport the NPC a step toward the goal each second.
                Location goal = inst.def.goalLoc(w);
                Location cur = npc.getLocation();
                if (cur.distanceSquared(goal) <= 4.0) {
                    inst.objective.forceComplete();
                } else {
                    org.bukkit.util.Vector dir = goal.toVector().subtract(cur.toVector()).normalize().multiply(1.0);
                    npc.teleport(cur.clone().add(dir));
                }
            }
            case TIMED_CHALLENGE -> {
                // Sub-goal: progress is bumped by kills (via listener); just fail on overtime.
                if (inst.elapsedSeconds() >= inst.def.objectiveDuration && !inst.objective.isComplete()) {
                    inst.objective.fail();
                }
            }
            default -> { /* counter-based objectives advanced by events */ }
        }
    }

    private void tickBoss(DungeonInstance inst, World w) {
        LivingEntity boss = bossEntity(inst, w);
        if (boss == null) return; // dead boss handled by death listener
        double pct = (boss.getHealth() / inst.bossMaxHealth()) * 100.0;
        List<DungeonDef.Phase> phases = inst.def.bossPhases;
        for (int i = 0; i < phases.size(); i++) {
            DungeonDef.Phase ph = phases.get(i);
            if (pct <= ph.from && pct > ph.to && i > inst.bossPhaseIndex()) {
                inst.setBossPhaseIndex(i);
                triggerPhase(inst, w, boss, ph, i);
                break;
            }
        }
    }

    private void triggerPhase(DungeonInstance inst, World w, LivingEntity boss, DungeonDef.Phase ph, int idx) {
        for (Player p : inst.onlineParticipants()) {
            p.sendTitle("§c§lPhase " + (idx + 1), "§7the boss grows stronger...", 5, 40, 10);
            p.playSound(p.getLocation(), "minecraft:entity.wither.spawn", 1f, 1f);
        }
        for (String ability : ph.abilities) {
            switch (ability.toLowerCase(Locale.ROOT)) {
                case "summon_adds" -> {
                    for (int i = 0; i < 3; i++) {
                        Location l = boss.getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 0,
                                ThreadLocalRandom.current().nextDouble(-3, 3));
                        mobs.spawn(l, "ZOMBIE", MobFactory.Tier.ELITE, inst.worldName,
                                inst.healthMult, inst.damageMult, false);
                    }
                }
                case "aoe_burst" -> {
                    // No-block-damage, no-fire explosion at the boss.
                    w.createExplosion(boss.getLocation(), 2.0f, false, false);
                }
                case "speed_self" -> {
                    // Avoid potion enums: nudge the boss's velocity toward the nearest player.
                    Player target = nearestParticipant(inst, boss.getLocation());
                    if (target != null) {
                        org.bukkit.util.Vector dir = target.getLocation().toVector()
                                .subtract(boss.getLocation().toVector());
                        if (dir.lengthSquared() > 0.001) {
                            boss.setVelocity(dir.normalize().multiply(0.6));
                        }
                    }
                }
                case "message" -> {
                    for (Player p : inst.onlineParticipants()) {
                        p.sendMessage("§c(✿) the boss roars with renewed fury!");
                    }
                }
                default -> { /* unknown ability id — ignore */ }
            }
        }
    }

    private void tickWaves(DungeonInstance inst, World w) {
        Objective.Type type = inst.def.objectiveType;
        if (type != Objective.Type.SURVIVE_WAVES && type != Objective.Type.DEFEND_NPC) return;
        List<DungeonDef.Wave> waves = inst.def.waves;
        if (waves.isEmpty()) return;
        // Spawn the next wave whose delay has elapsed and which hasn't spawned yet.
        for (int i = inst.wavesSpawned(); i < waves.size(); i++) {
            DungeonDef.Wave wave = waves.get(i);
            if (inst.elapsedSeconds() >= wave.delaySeconds) {
                for (DungeonDef.MobSpawn ms : wave.mobs) spawnGroup(inst, w, ms, true);
                inst.incWavesSpawned();
                if (type == Objective.Type.SURVIVE_WAVES) {
                    inst.objective.setProgress(inst.wavesSpawned());
                }
            } else {
                break; // waves are time-ordered
            }
        }
    }

    private void updateProgressDisplay(DungeonInstance inst) {
        if (inst.bossBar() != null) {
            inst.bossBar().setTitle(barTitle(inst));
            inst.bossBar().setProgress(inst.objective.fraction());
        }
        if (plugin.getConfig().getBoolean("toggles.action-bar-progress", true)) {
            String msg = ChatColor.translateAlternateColorCodes('&',
                    "&d" + inst.objective.label() + "  &7| &f" + timeLeft(inst));
            for (Player p : inst.onlineParticipants()) {
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg));
            }
        }
    }

    private String barTitle(DungeonInstance inst) {
        return ChatColor.translateAlternateColorCodes('&',
                "&d" + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', inst.def.displayName))
                        + " &7— &f" + inst.objective.label() + " &7(" + timeLeft(inst) + ")");
    }

    private String timeLeft(DungeonInstance inst) {
        long ms = inst.endMillis - System.currentTimeMillis();
        if (ms < 0) ms = 0;
        long secs = ms / 1000L;
        return (secs / 60) + ":" + String.format("%02d", secs % 60);
    }

    // --------------------------------------------------------------- events feed in here

    /** Called by the death listener when a tagged dungeon mob dies. */
    public void onDungeonMobDeath(DungeonInstance inst, LivingEntity mob) {
        if (inst.def.objectiveType == Objective.Type.TIMED_CHALLENGE) {
            inst.objective.increment();
        }
    }

    /** Called by the death listener when the run's boss dies. */
    public void onBossDeath(DungeonInstance inst, LivingEntity boss) {
        if (inst.def.objectiveType == Objective.Type.KILL_BOSS) {
            inst.objective.forceComplete();
        }
        // Boss loot rolls (boss-exclusive entries eligible). Dropped at boss location.
        World w = inst.world();
        if (w != null) {
            List<ItemStack> drops = loot.roll(inst.def.lootTable, inst.lootMult, true);
            for (ItemStack it : drops) w.dropItemNaturally(boss.getLocation(), it);
        }
    }

    /** Called when a participant right-clicks a tagged relic/shrine entity. */
    public void onRoleInteract(DungeonInstance inst, LivingEntity ent, String role, Player p) {
        switch (role) {
            case "relic" -> {
                if (inst.def.objectiveType == Objective.Type.COLLECT_RELICS) {
                    inst.objective.increment();
                    ent.remove();
                    p.sendMessage("§b(✿) relic collected! " + inst.objective.label());
                    p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1.5f);
                }
            }
            case "shrine" -> {
                if (inst.def.objectiveType == Objective.Type.ACTIVATE_SHRINES) {
                    inst.objective.increment();
                    ent.setCustomName(ChatColor.translateAlternateColorCodes('&', "&a✦ Shrine (active)"));
                    p.sendMessage("§d(✿) shrine activated! " + inst.objective.label());
                    p.playSound(p.getLocation(), "minecraft:block.beacon.power_select", 1f, 1.2f);
                }
            }
            default -> { /* npc etc. — no-op */ }
        }
    }

    /** Called when a participant breaks a tagged crystal block (handled via block PDC fallback by location). */
    public void onCrystalDestroyed(DungeonInstance inst, Player p) {
        if (inst.def.objectiveType == Objective.Type.DESTROY_CRYSTALS) {
            inst.objective.increment();
            p.sendMessage("§d(✿) crystal destroyed! " + inst.objective.label());
        }
    }

    // --------------------------------------------------------------- downed / revive

    public void downPlayer(DungeonInstance inst, Player p) {
        int secs = plugin.getConfig().getInt("revive.revive-seconds", 30);
        inst.setDowned(p.getUniqueId(), secs, p.getLocation());
        inst.markDeath();
        boolean spectator = plugin.getConfig().getBoolean("revive.use-spectator", true);
        if (spectator) {
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
        } else {
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
        }
        p.sendTitle("§c§lDOWNED", "§7a teammate can revive you (" + secs + "s)", 5, 60, 10);
        p.playSound(p.getLocation(), "minecraft:entity.player.hurt", 1f, 0.6f);
        for (Player m : inst.onlineParticipants()) {
            if (!m.equals(p)) m.sendMessage("§c(✿) §f" + p.getName() + "§c is DOWNED — right-click them to revive!");
        }
    }

    public void revivePlayer(DungeonInstance inst, Player downed, Player rescuer) {
        if (!inst.isDowned(downed.getUniqueId())) return;
        inst.clearDowned(downed.getUniqueId());
        downed.setGameMode(org.bukkit.GameMode.SURVIVAL);
        downed.setWalkSpeed(0.2f);
        downed.setFlySpeed(0.1f);
        applyReviveHealth(downed);
        downed.sendTitle("§a§lREVIVED", "§7back in the fight!", 5, 40, 10);
        downed.playSound(downed.getLocation(), "minecraft:entity.player.levelup", 1f, 1.4f);
        rescuer.sendMessage("§a(✿) you revived §f" + downed.getName() + "§a!");
    }

    @SuppressWarnings("deprecation")
    private void applyReviveHealth(Player p) {
        double max = p.getMaxHealth();
        p.setHealth(Math.max(1.0, max * 0.5));
    }

    private void tickSpectatorRevive(DungeonInstance inst) {
        List<UUID> downedIds = new ArrayList<>(inst.downed().keySet());
        for (UUID dId : downedIds) {
            Player downed = Bukkit.getPlayer(dId);
            if (downed == null) continue;
            Location dloc = inst.downedReturn(dId);
            if (dloc == null) dloc = downed.getLocation();
            for (Player rescuer : inst.onlineParticipants()) {
                if (rescuer.getUniqueId().equals(dId)) continue;
                if (inst.isDowned(rescuer.getUniqueId()) || inst.isOut(rescuer.getUniqueId())) continue;
                if (!rescuer.isSneaking()) continue;
                if (!rescuer.getWorld().getName().equals(inst.worldName)) continue;
                if (rescuer.getLocation().distanceSquared(dloc) <= 4.0) {
                    // Bring the spectator back to the spot they fell, then revive.
                    downed.teleport(dloc);
                    revivePlayer(inst, downed, rescuer);
                    break;
                }
            }
        }
    }

    private void downOut(DungeonInstance inst, UUID id) {
        inst.markOut(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.sendTitle("§4§lOUT", "§7you're out of this run~", 5, 50, 10);
            p.teleport(mainWorld().getSpawnLocation());
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
            if (inst.bossBar() != null) inst.bossBar().removePlayer(p);
        }
    }

    // --------------------------------------------------------------- completion / fail

    private void complete(DungeonInstance inst) {
        if (inst.finished()) return;
        inst.setFinished(true);
        World w = inst.world();

        // Rewards.
        List<ItemStack> reward = loot.roll(inst.def.lootTable, inst.lootMult, false);
        for (Player p : inst.onlineParticipants()) {
            p.sendTitle("§a§l✿ CLEARED ✿", "§7" + ChatColor.translateAlternateColorCodes('&', inst.def.displayName), 5, 60, 20);
            p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1f);
            // Give loot directly; overflow drops at feet.
            for (ItemStack it : reward) {
                Map<Integer, ItemStack> over = p.getInventory().addItem(it.clone());
                for (ItemStack o : over.values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
            }
            grantRewards(inst, p);
        }

        long elapsed = inst.elapsedMillis();
        // Speedrun / deathless bookkeeping.
        for (Player p : inst.onlineParticipants()) {
            PlayerProgress pr = progress.get(p.getUniqueId());
            if (inst.speedrun) {
                String key = inst.def.id + ":" + inst.difficulty.name().toLowerCase(Locale.ROOT);
                if (pr.recordSpeedrun(key, elapsed)) {
                    p.sendMessage("§e(✿) ⏱ new personal best: §f" + formatTime(elapsed));
                    announceAchievement(p, pr, "speedrun_" + inst.def.id);
                }
            }
            if (inst.deathless && !inst.anyDeath()) {
                pr.setEverDeathless(true);
                announceAchievement(p, pr, "deathless_clear");
            }
            announceAchievement(p, pr, "first_clear");
            announceAchievement(p, pr, "clear_" + inst.difficulty.name().toLowerCase(Locale.ROOT));
        }
        progress.save();

        cleanup(inst);
    }

    private void grantRewards(DungeonInstance inst, Player p) {
        PlayerProgress pr = progress.get(p.getUniqueId());
        int tokens = (int) Math.round(inst.def.tokenReward * inst.lootMult);
        pr.addTokens(tokens);
        pr.addReputation(inst.def.id, inst.def.reputationReward);
        pr.addCompletion();
        p.sendMessage("§d(✿) +§f" + tokens + "§d tokens, +§f" + inst.def.reputationReward
                + "§d rep with §f" + inst.def.id + "§d. (dungeon level §f" + pr.dungeonLevel() + "§d)");
    }

    private void announceAchievement(Player p, PlayerProgress pr, String id) {
        if (pr.grantAchievement(id)) {
            p.sendMessage("§6(✿) ★ Achievement unlocked: §f" + id);
            p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 1f, 1.2f);
        }
    }

    public void fail(DungeonInstance inst, String reason) {
        if (inst.finished()) return;
        inst.setFinished(true);
        for (Player p : inst.onlineParticipants()) {
            p.sendTitle("§4§l✖ FAILED ✖", "§7" + reason, 5, 60, 20);
            p.playSound(p.getLocation(), "minecraft:entity.wither.death", 1f, 0.8f);
        }
        cleanup(inst);
    }

    /** Manual leave for a single player; fails the run if it empties. */
    public void leave(Player p) {
        DungeonInstance inst = instanceOfPlayer(p);
        if (inst == null) { p.sendMessage("§c(✿) you're not in a dungeon~"); return; }
        inst.participants().remove(p.getUniqueId());
        inst.clearDowned(p.getUniqueId());
        if (inst.bossBar() != null) inst.bossBar().removePlayer(p);
        p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        p.setWalkSpeed(0.2f);
        p.teleport(mainWorld().getSpawnLocation());
        p.sendMessage("§d(✿) you left the dungeon~");
        if (inst.onlineParticipants().isEmpty()) {
            fail(inst, "everyone left");
        }
    }

    /** Teleports everyone out, unloads the world, deletes the folder. */
    private void cleanup(DungeonInstance inst) {
        instances.remove(inst.worldName);
        inst.removeBossBar();
        World fallback = mainWorld();
        World w = Bukkit.getWorld(inst.worldName);
        if (w != null) {
            for (Player p : new ArrayList<>(w.getPlayers())) {
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                p.setWalkSpeed(0.2f);
                p.setFlySpeed(0.1f);
                p.teleport(fallback.getSpawnLocation());
            }
        }
        final String name = inst.worldName;
        // Unload + delete a tick later so teleports settle.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World ww = Bukkit.getWorld(name);
            if (ww != null) {
                for (Player p : new ArrayList<>(ww.getPlayers())) p.teleport(mainWorld().getSpawnLocation());
                Bukkit.unloadWorld(ww, false);
            }
            File folder = new File(Bukkit.getWorldContainer(), name);
            if (folder.exists()) {
                try { deleteRecursively(folder.toPath()); }
                catch (Throwable t) {
                    plugin.getLogger().warning("(✿) couldn't fully delete instance folder '" + name + "': " + t.getMessage());
                }
            }
        }, 20L);
    }

    /** Shuts down all instances (called on disable). */
    public void shutdownAll() {
        for (DungeonInstance inst : new ArrayList<>(instances.values())) {
            try { cleanup(inst); }
            catch (Throwable ignored) { /* best effort on disable */ }
        }
    }

    // --------------------------------------------------------------- helpers

    private LivingEntity bossEntity(DungeonInstance inst, World w) {
        if (inst.bossId() == null) return null;
        org.bukkit.entity.Entity e = Bukkit.getEntity(inst.bossId());
        if (e instanceof LivingEntity le && !le.isDead()) return le;
        return null;
    }

    private LivingEntity npcEntity(DungeonInstance inst, World w) {
        if (inst.npcId() == null) return null;
        org.bukkit.entity.Entity e = Bukkit.getEntity(inst.npcId());
        if (e instanceof LivingEntity le && !le.isDead()) return le;
        return null;
    }

    private Player nearestParticipant(DungeonInstance inst, Location from) {
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player p : inst.onlineParticipants()) {
            if (!p.getWorld().getName().equals(inst.worldName)) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestSq) { bestSq = d; best = p; }
        }
        return best;
    }

    private void announce(List<UUID> ids, String msg) {
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(msg);
        }
    }

    private static String formatTime(long millis) {
        long secs = millis / 1000L;
        return (secs / 60) + "m " + (secs % 60) + "s";
    }

    // --------------------------------------------------------------- fs (mirrors KawaiiSkyblock)

    private static void copyRecursively(Path src, Path dst) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(srcPath -> {
                try {
                    String fileName = srcPath.getFileName() == null ? "" : srcPath.getFileName().toString();
                    if (fileName.equals("session.lock") || fileName.equals("uid.dat")
                            || fileName.endsWith(".lock")) {
                        return;
                    }
                    Path rel = src.relativize(srcPath);
                    Path target = dst.resolve(rel.toString());
                    if (Files.isDirectory(srcPath)) {
                        Files.createDirectories(target);
                    } else {
                        Path parent = target.getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.copy(srcPath, target);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException io) throw io;
            throw re;
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> stream = Files.walk(p)) {
            stream.sorted(Comparator.reverseOrder()).forEach(child -> {
                try { Files.deleteIfExists(child); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException io) throw io;
            throw re;
        }
    }

    // Marker materials used for DESTROY_CRYSTALS objective (admin places these in the template).
    public static boolean isCrystalBlock(Block b) {
        Material m = b.getType();
        return m == Material.END_CRYSTAL || m == Material.BEACON || m == Material.DIAMOND_BLOCK;
    }
}
