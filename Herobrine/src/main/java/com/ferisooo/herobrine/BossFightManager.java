package com.ferisooo.herobrine;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs the multi-phase Herobrine boss encounter. Phase is driven by remaining
 * health:
 * <ul>
 *   <li><b>Phase 1</b> — basic melee + teleportation.</li>
 *   <li><b>Phase 2</b> — adds lightning, hallucinations, darkness pulse.</li>
 *   <li><b>Phase 3</b> — faster, teleports often, summons shadow minions.</li>
 * </ul>
 * A boss bar titled "HEROBRINE HAS AWAKENED" tracks his health for everyone
 * in range. The fight ends when he is killed or the target leaves, in which
 * case the encounter manager re-targets without resetting progress.
 */
public final class BossFightManager {

    private final HerobrinePlugin plugin;
    private final ConfigManager cfg;

    private HerobrineEntity boss;
    private BossBar bar;
    private BukkitRunnable loop;
    private int phase;
    private final List<ShadowMinion> minions = new ArrayList<>();
    private boolean active;

    public BossFightManager(HerobrinePlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg();
    }

    public boolean isActive() { return active; }
    public HerobrineEntity boss() { return boss; }
    public int phase() { return phase; }

    /** Begin the boss fight against {@code target}, spawning Herobrine if needed. */
    public boolean start(Player target) {
        if (active || target == null) return false;
        Location at = target.getLocation().add(target.getLocation().getDirection().multiply(-3));
        boss = plugin.herobrine().forceSpawn(at, target, HerobrineEntity.Mode.BOSS, cfg.bossHealth());
        if (boss == null) return false;
        boss.setMode(HerobrineEntity.Mode.BOSS);
        active = true;
        phase = 1;

        bar = org.bukkit.Bukkit.createBossBar(cfg.bossBarTitle(), BarColor.PURPLE, BarStyle.SEGMENTED_10);
        bar.setProgress(1.0);
        bar.addPlayer(target);

        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.6f);
        plugin.broadcastNearby(target.getLocation(), 64, cfg.msg("boss-start", "&4&lHEROBRINE HAS AWAKENED."));

        startLoop();
        plugin.encounters().markBossActive(true);
        return true;
    }

    private void startLoop() {
        loop = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!active || boss == null || boss.isDead()) { stop(false); return; }
                Player target = boss.target();
                if (target == null || !target.isOnline()) {
                    // Target left — keep the boss & its progress, hand off to the
                    // encounter manager to pick the next victim.
                    Player next = plugin.encounters().nextBossTarget(target);
                    if (next == null) { return; } // wait for someone to return
                    boss.setTarget(next);
                    bar.removeAll();
                    bar.addPlayer(next);
                    return;
                }
                if (target.getWorld() != boss.getLocation().getWorld()) {
                    boss.teleport(target.getLocation().add(0, 0, 2));
                }
                updatePhase();
                refreshBar(target);
                runPhaseBehavior(target, tick);
                tick++;
            }
        };
        loop.runTaskTimer(plugin, 0L, 10L); // every half-second
    }

    private void updatePhase() {
        double pct = boss.getHealth() / boss.getMaxHealth();
        int newPhase = pct <= cfg.bossPhase3Pct() ? 3 : pct <= cfg.bossPhase2Pct() ? 2 : 1;
        if (newPhase != phase) {
            phase = newPhase;
            Player t = boss.target();
            if (t != null) {
                t.getWorld().playSound(t.getLocation(), Sound.ENTITY_WITHER_HURT, 1f, 0.5f);
                plugin.broadcastNearby(t.getLocation(), 48,
                        cfg.msg("boss-phase", "&5Herobrine grows stronger... &7(phase " + phase + ")")
                                .replace("{phase}", String.valueOf(phase)));
            }
            bar.setColor(phase == 3 ? BarColor.RED : phase == 2 ? BarColor.PURPLE : BarColor.PURPLE);
        }
    }

    private void refreshBar(Player target) {
        bar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / boss.getMaxHealth())));
        if (!bar.getPlayers().contains(target) && target.getLocation().distance(boss.getLocation()) < 64) {
            bar.addPlayer(target);
        }
    }

    private void runPhaseBehavior(Player target, int tick) {
        Location bl = boss.getLocation();
        double dist = bl.getWorld() == target.getWorld() ? bl.distance(target.getLocation()) : 999;
        AbilityManager ab = plugin.abilities();

        // movement: always close in; phase 3 is faster (handled via more frequent moves)
        boss.moveToward(target.getLocation());
        boss.face(target.getEyeLocation());

        // melee when in reach (all phases)
        if (dist < 3.0 && tick % 2 == 0) {
            boss.swing();
            target.damage(cfg.meleeDamage());
            target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.7f);
        }

        // teleport: phase 1 occasionally, phase 3 frequently
        int tpEvery = phase >= 3 ? 6 : 14;
        if (dist > 6 && tick % tpEvery == 0) {
            ab.shadowTeleport(boss, target);
        }

        if (phase >= 2) {
            if (tick % 8 == 0) ab.lightningStrike(target);
            if (tick % 20 == 0) plugin.hallucinations().spawnFake(target, 4);
            if (tick % 24 == 0) ab.darknessPulse(target.getLocation());
        }

        if (phase >= 3) {
            cleanupDeadMinions();
            if (tick % 16 == 0 && minions.size() < cfg.minionCount()) {
                summonMinions(target);
            }
        }
    }

    private void summonMinions(Player target) {
        int count = cfg.minionCount() - minions.size();
        for (int i = 0; i < count; i++) {
            Location at = target.getLocation().add(
                    ThreadLocalRandom.current().nextDouble(-4, 4), 1,
                    ThreadLocalRandom.current().nextDouble(-4, 4));
            minions.add(ShadowMinion.summon(plugin, at, target, cfg.minionLifespan()));
        }
        plugin.broadcastNearby(target.getLocation(), 48, cfg.msg("boss-minions", "&8Shadows rise to serve him..."));
    }

    private void cleanupDeadMinions() {
        minions.removeIf(m -> !m.isAlive());
    }

    /** Called when the boss's logical HP reaches zero. */
    public void onBossDeath() {
        if (!active) return;
        Player target = boss != null ? boss.target() : null;
        Location at = boss != null ? boss.getLocation() : (target != null ? target.getLocation() : null);
        if (at != null && at.getWorld() != null) {
            at.getWorld().playSound(at, Sound.ENTITY_WITHER_DEATH, 1f, 0.8f);
            at.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, at.clone().add(0, 1, 0), 5, 1, 1, 1, 0);
            plugin.broadcastNearby(at, 64, cfg.msg("boss-defeated", "&fHerobrine fades back into the code..."));
        }
        stop(true);
    }

    /** Tear the fight down. {@code defeated} distinguishes a kill from a forced despawn. */
    public void stop(boolean defeated) {
        active = false;
        if (loop != null) { loop.cancel(); loop = null; }
        if (bar != null) { bar.removeAll(); bar = null; }
        for (ShadowMinion m : minions) m.expire();
        minions.clear();
        if (boss != null) {
            boss.despawn();
            boss = null;
        }
        plugin.encounters().markBossActive(false);
        plugin.herobrine().clearActive();
    }
}
