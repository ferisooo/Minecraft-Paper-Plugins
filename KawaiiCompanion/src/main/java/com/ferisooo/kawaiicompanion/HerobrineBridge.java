package com.ferisooo.kawaiicompanion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Soft, reflection-only bridge to the optional <b>Herobrine</b> plugin's
 * {@code com.ferisooo.herobrine.HerobrineService}. Lets the companion detect
 * and fight Herobrine without a compile-time dependency — if Herobrine isn't
 * installed (or is disabled), every call cheaply no-ops.
 *
 * <p>The service class is resolved through Herobrine's own plugin classloader
 * so it works regardless of declared dependencies; {@code softdepend} in
 * {@code plugin.yml} only nudges load order.
 */
final class HerobrineBridge {

    private static volatile boolean available;
    private static Method mIsActive, mLocation, mDamage, mIsBoss, mGetHealth;

    private HerobrineBridge() { }

    /** Resolve (once) the service methods. Cheap + safe to call every tick. */
    private static boolean ensure() {
        if (available) return true;
        Plugin hb = Bukkit.getPluginManager().getPlugin("Herobrine");
        if (hb == null || !hb.isEnabled()) return false;
        try {
            Class<?> svc = Class.forName("com.ferisooo.herobrine.HerobrineService",
                    true, hb.getClass().getClassLoader());
            mIsActive = svc.getMethod("isActive");
            mLocation = svc.getMethod("getLocation");
            mDamage = svc.getMethod("damage", double.class, String.class);
            mIsBoss = svc.getMethod("isBoss");
            mGetHealth = svc.getMethod("getHealth");
            available = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isActive() {
        if (!ensure()) return false;
        try { return (boolean) mIsActive.invoke(null); } catch (Throwable t) { return false; }
    }

    static boolean isBoss() {
        if (!ensure()) return false;
        try { return (boolean) mIsBoss.invoke(null); } catch (Throwable t) { return false; }
    }

    static Location location() {
        if (!ensure()) return null;
        try { return (Location) mLocation.invoke(null); } catch (Throwable t) { return null; }
    }

    static double health() {
        if (!ensure()) return 0.0;
        try { return (double) mGetHealth.invoke(null); } catch (Throwable t) { return 0.0; }
    }

    /** Deal {@code amount} to Herobrine's logical HP. Returns true if it killed him. */
    static boolean damage(double amount, String sourceName) {
        if (!ensure()) return false;
        try { return (boolean) mDamage.invoke(null, amount, sourceName); }
        catch (Throwable t) { return false; }
    }
}
