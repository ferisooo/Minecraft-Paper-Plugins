package com.ferisooo.kawaiidungeons;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * All gameplay enforcement for dungeon instances. Critically version-safe:
 * <ul>
 *   <li>Mob damage is scaled by MULTIPLYING {@code event.getDamage()} for tagged
 *       mobs — never via Attribute or PotionEffectType enums.</li>
 *   <li>"No healing" in hardcore cancels {@link EntityRegainHealthEvent}.</li>
 *   <li>Lethal damage to a participant is intercepted into the "downed" state
 *       instead of a real death.</li>
 * </ul>
 */
public final class DungeonListeners implements Listener {

    private final KawaiiDungeons plugin;
    private final InstanceManager instances;
    private final MobFactory mobs;

    public DungeonListeners(KawaiiDungeons plugin, InstanceManager instances, MobFactory mobs) {
        this.plugin = plugin;
        this.instances = instances;
        this.mobs = mobs;
    }

    // --------------------------------------------------- damage scaling (no attributes)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        // Resolve a projectile back to its shooter if applicable.
        if (damager instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (mobs.isDungeonMob(damager)) {
            double mult = mobs.damageMultOf(damager);
            if (mult != 1.0) e.setDamage(e.getDamage() * mult);
        }
    }

    // --------------------------------------------------- downed instead of death

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        DungeonInstance inst = instances.byWorld(p.getWorld().getName());
        if (inst == null || !inst.isParticipant(p.getUniqueId())) return;
        if (inst.isDowned(p.getUniqueId()) || inst.isOut(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        // Would this hit be lethal? If so, intercept into the downed state.
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            // Restore to full so they don't instantly re-trigger, then down them.
            applyFullHealth(p);
            instances.downPlayer(inst, p);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyFullHealth(Player p) {
        p.setHealth(Math.max(1.0, p.getMaxHealth()));
        p.setFoodLevel(20);
        p.setFireTicks(0);
    }

    // --------------------------------------------------- no healing (hardcore)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        DungeonInstance inst = instances.byWorld(p.getWorld().getName());
        if (inst == null || !inst.isParticipant(p.getUniqueId())) return;
        if (inst.hardcore) e.setCancelled(true);
    }

    // --------------------------------------------------- mob / boss death

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        String world = dead.getWorld().getName();
        DungeonInstance inst = instances.byWorld(world);
        if (inst == null) return;
        if (!mobs.isDungeonMob(dead)) return;

        // Strip vanilla drops/xp; loot comes from our tables.
        e.getDrops().clear();
        e.setDroppedExp(0);

        if (mobs.isBoss(dead)) {
            instances.onBossDeath(inst, dead);
        } else {
            instances.onDungeonMobDeath(inst, dead);
            // Hardcore: mobs explode on death (no block damage, no fire).
            if (inst.hardcore) {
                World w = dead.getWorld();
                float power = (float) plugin.getConfig().getDouble("hardcore.mob-explode-power", 2.0);
                w.createExplosion(dead.getLocation(), power, false, false);
            }
        }
    }

    // --------------------------------------------------- interactions: revive + relics/shrines

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        DungeonInstance inst = instances.byWorld(p.getWorld().getName());
        if (inst == null || !inst.isParticipant(p.getUniqueId())) return;
        Entity clicked = e.getRightClicked();

        // Revive a downed teammate (non-spectator branch — spectators can't be clicked,
        // so for spectator mode revive is triggered by proximity in the tick; here we
        // handle the frozen branch and any clickable body).
        if (clicked instanceof Player other && inst.isDowned(other.getUniqueId())) {
            e.setCancelled(true);
            instances.revivePlayer(inst, other, p);
            return;
        }

        // Relic / shrine entities.
        if (clicked instanceof LivingEntity le && mobs.instanceOf(le) != null) {
            String role = mobs.roleOf(le);
            if (role != null && (role.equals("relic") || role.equals("shrine"))) {
                e.setCancelled(true);
                instances.onRoleInteract(inst, le, role, p);
            }
        }
    }

    // Spectator-mode revive (when revive.use-spectator is true) can't go through a
    // right-click — spectators aren't clickable — so it's handled by a sneak-proximity
    // scan in InstanceManager's per-second tick. The click path above covers the
    // frozen (non-spectator) revive mode.

    @EventHandler
    public void onCrystalBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        DungeonInstance inst = instances.byWorld(p.getWorld().getName());
        if (inst == null || !inst.isParticipant(p.getUniqueId())) return;
        if (inst.def.objectiveType != Objective.Type.DESTROY_CRYSTALS) return;
        if (InstanceManager.isCrystalBlock(e.getBlock())) {
            instances.onCrystalDestroyed(inst, p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        DungeonInstance inst = instances.instanceOfPlayer(p);
        if (inst == null) return;
        // Treat a quit as leaving the run; if it empties, the tick will fail it.
        inst.clearDowned(p.getUniqueId());
        if (inst.bossBar() != null) inst.bossBar().removePlayer(p);
    }
}
