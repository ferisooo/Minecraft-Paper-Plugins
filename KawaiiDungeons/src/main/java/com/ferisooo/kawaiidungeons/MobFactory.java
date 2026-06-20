package com.ferisooo.kawaiidungeons;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * Spawns vanilla {@link EntityType}s as tagged dungeon mobs. Tags are stored in
 * each entity's {@link PersistentDataContainer} so listeners can resolve the
 * owning instance, the tier, and the damage multiplier to apply.
 *
 * <p>Version safety: health is set with the DEPRECATED {@code setMaxHealth} /
 * {@code setHealth} pair (no Attribute enum). Damage is NOT scaled via
 * attributes or potion effects — instead the stored {@code DAMAGE_MULT} tag is
 * read by the listener which multiplies {@code event.getDamage()}.
 */
public final class MobFactory {

    public enum Tier { ELITE, MINIBOSS, BOSS }

    private final KawaiiDungeons plugin;
    public final NamespacedKey keyInstance;
    public final NamespacedKey keyTier;
    public final NamespacedKey keyDamageMult;
    public final NamespacedKey keyBoss;
    public final NamespacedKey keyRole; // generic role tag: "relic", "shrine", "crystal", "npc", "add"

    public MobFactory(KawaiiDungeons plugin) {
        this.plugin = plugin;
        this.keyInstance = new NamespacedKey(plugin, "instance");
        this.keyTier = new NamespacedKey(plugin, "tier");
        this.keyDamageMult = new NamespacedKey(plugin, "damage_mult");
        this.keyBoss = new NamespacedKey(plugin, "boss");
        this.keyRole = new NamespacedKey(plugin, "role");
    }

    public static Tier tier(String s) {
        if (s == null) return Tier.ELITE;
        try { return Tier.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return Tier.ELITE; }
    }

    private static EntityType entityType(String s) {
        if (s == null) return EntityType.ZOMBIE;
        try { return EntityType.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return EntityType.ZOMBIE; }
    }

    /**
     * Spawns a single tagged mob.
     *
     * @param instanceId the instance world name (used as the instance tag)
     * @param healthMult difficulty health multiplier
     * @param damageMult difficulty damage multiplier
     * @param isBoss     marks the entity as the run's boss
     */
    public LivingEntity spawn(Location loc, String typeName, Tier tier, String instanceId,
                              double healthMult, double damageMult, boolean isBoss) {
        if (loc.getWorld() == null) return null;
        EntityType type = entityType(typeName);
        Entity e = loc.getWorld().spawnEntity(loc, type);
        if (!(e instanceof LivingEntity le)) {
            e.remove();
            return null;
        }

        double tierHealth = plugin.getConfig().getDouble("tiers." + tier.name().toLowerCase(Locale.ROOT) + ".health-mult", 2.0);
        double tierDamage = plugin.getConfig().getDouble("tiers." + tier.name().toLowerCase(Locale.ROOT) + ".damage-mult", 1.25);
        boolean glowDefault = plugin.getConfig().getBoolean("tiers." + tier.name().toLowerCase(Locale.ROOT) + ".glowing", false);
        boolean glowGlobal = plugin.getConfig().getBoolean("toggles.glowing-mobs", true);

        // ---- HEALTH (deprecated, version-safe; no Attribute enum) ----
        double baseHealth = le.getHealth();
        double finalHealth = Math.max(1.0, baseHealth * tierHealth * healthMult);
        applyHealth(le, finalHealth);

        // ---- DAMAGE (stored as a tag, applied in the damage listener) ----
        double finalDamageMult = tierDamage * damageMult;

        le.setRemoveWhenFarAway(false);
        le.setPersistent(true);
        if (glowGlobal && glowDefault) le.setGlowing(true);

        le.setCustomNameVisible(true);
        le.setCustomName(displayName(tier, type));

        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.set(keyInstance, PersistentDataType.STRING, instanceId);
        pdc.set(keyTier, PersistentDataType.STRING, tier.name());
        pdc.set(keyDamageMult, PersistentDataType.DOUBLE, finalDamageMult);
        pdc.set(keyBoss, PersistentDataType.INTEGER, isBoss ? 1 : 0);
        return le;
    }

    /** Spawns a generic tagged role entity (relic/shrine/npc/crystal) with light health boost. */
    public LivingEntity spawnRole(Location loc, String typeName, String instanceId, String role, double healthMult) {
        if (loc.getWorld() == null) return null;
        EntityType type = entityType(typeName);
        Entity e = loc.getWorld().spawnEntity(loc, type);
        if (!(e instanceof LivingEntity le)) {
            e.remove();
            return null;
        }
        le.setRemoveWhenFarAway(false);
        le.setPersistent(true);
        le.setCustomNameVisible(true);
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.set(keyInstance, PersistentDataType.STRING, instanceId);
        pdc.set(keyRole, PersistentDataType.STRING, role);
        if (healthMult > 1.0) {
            applyHealth(le, Math.max(1.0, le.getHealth() * healthMult));
        }
        return le;
    }

    /** Sets max health using the deprecated API (intentional — no Attribute enum). */
    @SuppressWarnings("deprecation")
    private void applyHealth(LivingEntity le, double health) {
        le.setMaxHealth(health);
        le.setHealth(health);
    }

    public String instanceOf(Entity e) {
        return e.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING);
    }

    public String roleOf(Entity e) {
        return e.getPersistentDataContainer().get(keyRole, PersistentDataType.STRING);
    }

    public double damageMultOf(Entity e) {
        Double d = e.getPersistentDataContainer().get(keyDamageMult, PersistentDataType.DOUBLE);
        return d == null ? 1.0 : d;
    }

    public boolean isBoss(Entity e) {
        Integer i = e.getPersistentDataContainer().get(keyBoss, PersistentDataType.INTEGER);
        return i != null && i == 1;
    }

    public boolean isDungeonMob(Entity e) {
        return e.getPersistentDataContainer().has(keyInstance, PersistentDataType.STRING)
                && e.getPersistentDataContainer().has(keyTier, PersistentDataType.STRING);
    }

    private static String displayName(Tier tier, EntityType type) {
        String color = switch (tier) {
            case ELITE -> "&a";
            case MINIBOSS -> "&6";
            case BOSS -> "&c";
        };
        String pretty = prettyType(type);
        String prefix = switch (tier) {
            case ELITE -> "Elite ";
            case MINIBOSS -> "Miniboss ";
            case BOSS -> "&l[BOSS] &r" + color;
        };
        return ChatColor.translateAlternateColorCodes('&', color + prefix + pretty);
    }

    private static String prettyType(EntityType type) {
        String[] parts = type.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
