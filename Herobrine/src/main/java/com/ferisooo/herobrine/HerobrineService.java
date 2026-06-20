package com.ferisooo.herobrine;

import org.bukkit.Location;

/**
 * Tiny public, static facade other plugins can call to detect and fight
 * Herobrine without a compile-time dependency on this plugin. Intended to be
 * reached by reflection (load the class through Herobrine's plugin
 * classloader, then invoke these static methods).
 *
 * <p>Example consumer: KawaiiCompanion's companion uses this to spot an active
 * Herobrine near its owner and whittle down his logical HP with its weapon.
 */
public final class HerobrineService {

    private static HerobrinePlugin plugin;

    private HerobrineService() { }

    static void init(HerobrinePlugin p) { plugin = p; }

    /** Is a Herobrine (stalking, hunting, or boss) currently live? */
    public static boolean isActive() {
        return plugin != null && plugin.herobrine().hasActive();
    }

    /** Current world-location of the live Herobrine, or {@code null} if none. */
    public static Location getLocation() {
        return isActive() ? plugin.herobrine().active().getLocation() : null;
    }

    /** His remaining logical health (0 if none active). */
    public static double getHealth() {
        return isActive() ? plugin.herobrine().active().getHealth() : 0.0;
    }

    /** His maximum logical health (0 if none active). */
    public static double getMaxHealth() {
        return isActive() ? plugin.herobrine().active().getMaxHealth() : 0.0;
    }

    /** True if the live Herobrine is in the multi-phase boss encounter. */
    public static boolean isBoss() {
        return isActive() && plugin.herobrine().active().getMode() == HerobrineEntity.Mode.BOSS;
    }

    /**
     * Apply external damage to the live Herobrine (e.g. from a companion).
     * Returns {@code true} if this killed him. No-op returning {@code false}
     * if none is active.
     *
     * @param amount     damage to deal to his logical HP
     * @param sourceName a label for who dealt it (for messages/attribution)
     */
    public static boolean damage(double amount, String sourceName) {
        if (!isActive()) return false;
        return plugin.herobrine().damageExternal(amount, sourceName);
    }
}
