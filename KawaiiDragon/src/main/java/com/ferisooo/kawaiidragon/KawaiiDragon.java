package com.ferisooo.kawaiidragon;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

/**
 * KawaiiDragon — the Ender Dragon gets stronger every time it's beaten.
 *
 * <p>A persistent global "defeats" counter sets the dragon's <b>tier</b>: each
 * tier raises its max health and the damage it deals. Scaling is applied when a
 * dragon spawns (and lazily on first interaction, so the very first dragon — and
 * any that loaded before this plugin — gets buffed too). When a dragon dies the
 * counter ticks up and the next one comes back harder.
 *
 * <p>This is the v1 framework — health/damage scaling, announcements and XP
 * bonus. Tier-gated special abilities are hooked in {@link #onTierAbilities}
 * for the "more detailed" pass later.
 */
public final class KawaiiDragon extends JavaPlugin implements Listener {

    private NamespacedKey tierKey;
    private File dataFile;
    private int defeats;

    private boolean enabled;
    private boolean announce;
    private double baseHealth;
    private double healthPerTier;
    private double healthCap;
    private double damagePerTier;
    private double bonusXpPerTier;
    private int maxTier;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tierKey = new NamespacedKey(this, "tier");
        readConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        // Buff any dragon that's already loaded (e.g. /reload while in the End).
        if (enabled) {
            for (World w : Bukkit.getWorlds()) {
                for (EnderDragon d : w.getEntitiesByClass(EnderDragon.class)) scaleDragon(d);
            }
        }
        getLogger().info("(✧) KawaiiDragon ready ~ the dragon remembers (" + defeats + " defeats) 🐉");
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        enabled        = c.getBoolean("enabled", true);
        announce       = c.getBoolean("announce", true);
        baseHealth     = Math.max(1.0, c.getDouble("base-health", 200));
        healthPerTier  = Math.max(0.0, c.getDouble("health-per-tier", 40));
        healthCap      = Math.max(baseHealth, c.getDouble("health-cap", 1500));
        damagePerTier  = Math.max(0.0, c.getDouble("damage-per-tier", 0.15));
        bonusXpPerTier = Math.max(0.0, c.getDouble("bonus-xp-per-tier", 0.5));
        maxTier        = Math.max(0, c.getInt("max-tier", 25));
    }

    // ----------------------------------------------------------------- scaling

    private int currentTier() {
        return Math.min(defeats, maxTier);
    }

    private void scaleDragon(EnderDragon d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        if (pdc.has(tierKey, PersistentDataType.INTEGER)) return; // already buffed

        int tier = currentTier();
        double hp = Math.min(healthCap, baseHealth + healthPerTier * tier);
        try {
            d.setMaxHealth(hp);
            d.setHealth(hp);
        } catch (Throwable ex) {
            getLogger().warning("(✧) couldn't set dragon health: " + ex.getMessage());
        }
        pdc.set(tierKey, PersistentDataType.INTEGER, tier);
        onTierAbilities(d, tier);

        if (tier > 0 && announce) {
            Bukkit.broadcastMessage("§5✦ §dThe Ender Dragon returns — §5§lTier " + (tier + 1)
                    + " §r§5✦ §7(§f" + (int) hp + " §7HP)");
        }
    }

    /**
     * Hook for tier-gated special abilities (the "more detail later" part).
     * v1 leaves this light; extend it with adds, breath buffs, phases, etc.
     */
    private void onTierAbilities(EnderDragon d, int tier) {
        // Intentionally minimal for v1. Example to build on:
        //   if (tier >= 5)  d.setGlowing(true);
        //   if (tier >= 10) summon extra endermites / faster crystals / etc.
    }

    private void ensureScaled(EnderDragon d) {
        if (!d.getPersistentDataContainer().has(tierKey, PersistentDataType.INTEGER)) scaleDragon(d);
    }

    private int tierOf(EnderDragon d) {
        ensureScaled(d);
        Integer v = d.getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (!enabled) return;
        if (e.getEntity() instanceof EnderDragon d) {
            // Defer a tick so the entity's attributes are fully initialised.
            Bukkit.getScheduler().runTaskLater(this, () -> { if (d.isValid()) scaleDragon(d); }, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!enabled) return;
        // Make sure a dragon being hit is already buffed (covers pre-existing dragons).
        if (e.getEntity() instanceof EnderDragon victim) ensureScaled(victim);

        EnderDragon src = dragonSource(e.getDamager());
        if (src != null) {
            int tier = tierOf(src);
            if (tier > 0) e.setDamage(e.getDamage() * (1.0 + damagePerTier * tier));
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!enabled) return;
        if (e.getEntityType() != EntityType.ENDER_DRAGON) return;

        defeats++;
        saveData();
        int tier = currentTier();
        if (bonusXpPerTier > 0) {
            e.setDroppedExp((int) Math.round(e.getDroppedExp() * (1.0 + bonusXpPerTier * tier)));
        }
        if (announce) {
            Bukkit.broadcastMessage("§d✦ The Ender Dragon has fallen! ✦ §7It will rise again — "
                    + "next: §fTier " + (Math.min(defeats, maxTier) + 1));
        }
    }

    /** The dragon behind a damage source (itself, its breath cloud, or its fireball). */
    private static EnderDragon dragonSource(Entity damager) {
        if (damager instanceof EnderDragon d) return d;
        if (damager instanceof AreaEffectCloud cloud) {
            ProjectileSource s = cloud.getSource();
            if (s instanceof EnderDragon d) return d;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof EnderDragon d) {
            return d;
        }
        return null;
    }

    // ----------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiidragon.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            readConfig();
            sender.sendMessage("§d(✧) KawaiiDragon reloaded ✨");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("setdefeats")
                && sender.hasPermission("kawaiidragon.admin")) {
            try {
                defeats = Math.max(0, Integer.parseInt(args[1]));
                saveData();
                sender.sendMessage("§d(✧) defeats set to §f" + defeats + " §7(next dragon: Tier "
                        + (Math.min(defeats, maxTier) + 1) + ")");
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c(✧) /dragon setdefeats <number>");
            }
            return true;
        }
        sender.sendMessage("§d(✧) Ender Dragon defeats: §f" + defeats
                + " §7— next dragon is §fTier " + (Math.min(defeats, maxTier) + 1));
        return true;
    }

    // ----------------------------------------------------------------- data

    private void loadData() {
        if (dataFile == null || !dataFile.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        defeats = Math.max(0, y.getInt("defeats", 0));
    }

    private void saveData() {
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("defeats", defeats);
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("(✧) couldn't save data.yml: " + ex.getMessage());
        }
    }
}
