package com.ferisooo.kawaiihearts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * KawaiiHearts — floats a cute health bar above every mob's head. ✿
 *
 * <p>Uses each mob's <b>custom name</b> (set visible) as the display. That's
 * the most compatible approach: it always faces the player, needs no packets,
 * and shows up for Bedrock players through Geyser too.
 *
 * <p><b>Name-tag friendly.</b> The first time we touch a mob we stash whatever
 * name it already had (e.g. a player-applied name tag) in its persistent data.
 * With {@code preserve-name-tags: true} that name is kept as a prefix in front
 * of the hearts, and it's fully restored if the bar is removed or the plugin
 * disables — so we never silently eat someone's "Mr. Whiskers" tag.
 *
 * <p><b>Modes.</b> {@code always} shows the bar at all times; {@code damaged}
 * only shows it while a mob is below full health and restores the original
 * name once it heals back up.
 *
 * <p><b>View radius.</b> A proximity scan only labels mobs within
 * {@code view-radius} blocks of a player and hides the bar again once everyone
 * walks away — so a big mob farm doesn't fill the screen with hearts. Note the
 * vanilla client only renders name tags within ~64 blocks anyway, so a radius
 * above that has no extra effect.
 */
public final class KawaiiHearts extends JavaPlugin implements Listener {

    /** Parses '&'-style color codes used in config into Adventure components. */
    private static final LegacyComponentSerializer AMP =
            LegacyComponentSerializer.legacyAmpersand();
    /** Serializes original names to/from persistent storage (§ form). */
    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.builder().character('\u00a7').hexColors().build();

    /** Hard cap used when config requests an unlimited (<= 0) view radius. */
    private static final double MAX_VIEW_RADIUS = 64.0;

    private enum Mode { ALWAYS, DAMAGED }

    private NamespacedKey managedKey;   // byte 1 once we've taken over the name
    private NamespacedKey origNameKey;  // original name as § string ("" = none)
    private NamespacedKey origVisKey;   // original custom-name-visible (byte)
    private NamespacedKey renderKey;    // last-rendered bar (§ string) to skip redundant repaints

    // ---- config ----
    private boolean enabled;
    private Mode    mode;
    private int     maxHearts;
    private boolean showNumber;
    private String  fullHeart, emptyHeart, fullColor, emptyColor, numberColor;
    private boolean   healthGradient;          // tint filled hearts by current HP %
    private TextColor gradHigh, gradMid, gradLow;
    private boolean preserveNames;
    private double  viewRadius;     // <= 0 in config is clamped to MAX_VIEW_RADIUS
    private long    scanTicks;      // how often the proximity scan runs
    private final Set<EntityType> blacklist = EnumSet.noneOf(EntityType.class);
    private final Set<EntityType> onlyTypes = EnumSet.noneOf(EntityType.class);

    /** UUIDs of mobs currently wearing a bar (so we know which to hide). */
    private final Set<UUID> activeBars = new HashSet<>();
    private BukkitTask scanTask;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        managedKey  = new NamespacedKey(this, "managed");
        origNameKey = new NamespacedKey(this, "orig_name");
        origVisKey  = new NamespacedKey(this, "orig_vis");
        renderKey   = new NamespacedKey(this, "render");

        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);

        // Proximity scan handles showing/hiding bars near players.
        scanTask = Bukkit.getScheduler().runTaskTimer(this, this::proximityScan,
                scanTicks, scanTicks);

        getLogger().info("(\u2727) KawaiiHearts ready ~ mode=" + mode
                + " hearts=" + maxHearts + " radius=" + viewRadius + " \u2764");
    }

    @Override
    public void onDisable() {
        if (scanTask != null) scanTask.cancel();
        // Be a good citizen: hand every managed mob its original name back.
        for (var world : Bukkit.getWorlds()) {
            for (LivingEntity le : world.getLivingEntities()) {
                if (le.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE)) {
                    restore(le);
                }
            }
        }
        activeBars.clear();
    }

    private void loadConfigValues() {
        reloadConfig();
        var cfg = getConfig();
        cfg.options().copyDefaults(true);
        saveConfig();

        enabled    = cfg.getBoolean("enabled", true);
        mode       = "damaged".equalsIgnoreCase(cfg.getString("mode", "always"))
                ? Mode.DAMAGED : Mode.ALWAYS;
        maxHearts  = Math.max(1, Math.min(40, cfg.getInt("max-hearts", 3)));
        showNumber = cfg.getBoolean("show-number", true);
        fullHeart  = cfg.getString("full-heart", "\u2764");
        emptyHeart = cfg.getString("empty-heart", "\u2661");
        fullColor  = cfg.getString("full-color", "&d");
        emptyColor = cfg.getString("empty-color", "&7");
        numberColor = cfg.getString("number-color", "&f");
        healthGradient = cfg.getBoolean("health-gradient", true);
        gradHigh = parseColor(cfg.getString("gradient-high", "#55ff55"), NamedTextColor.GREEN);
        gradMid  = parseColor(cfg.getString("gradient-mid",  "#ffe14d"), NamedTextColor.YELLOW);
        gradLow  = parseColor(cfg.getString("gradient-low",  "#ff5555"), NamedTextColor.RED);
        preserveNames = cfg.getBoolean("preserve-name-tags", true);
        viewRadius = cfg.getDouble("view-radius", 24.0);
        // A non-positive radius once meant "unlimited" (scan every mob in every
        // world) — unbounded and dangerous on busy servers. Clamp it to a sane
        // maximum instead (the vanilla client only renders name tags ~64 blocks
        // away anyway, so this is effectively the same visually).
        if (viewRadius <= 0) viewRadius = MAX_VIEW_RADIUS;
        scanTicks  = Math.max(1L, cfg.getLong("scan-interval-ticks", 20L));

        blacklist.clear();
        for (String s : cfg.getStringList("blacklist")) {
            EntityType t = parseType(s);
            if (t != null) blacklist.add(t);
        }
        onlyTypes.clear();
        for (String s : cfg.getStringList("only-types")) {
            EntityType t = parseType(s);
            if (t != null) onlyTypes.add(t);
        }
    }

    private EntityType parseType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("(\u2727) unknown entity type in config: '" + s + "'");
            return null;
        }
    }

    /** Parse a gradient anchor color. Accepts "#rrggbb" hex; falls back on bad input. */
    private TextColor parseColor(String s, TextColor fallback) {
        if (s == null || s.isBlank()) return fallback;
        TextColor c = TextColor.fromHexString(s.trim());
        if (c == null) {
            getLogger().warning("(\u2727) invalid gradient color in config: '" + s
                    + "' (expected #rrggbb)");
            return fallback;
        }
        return c;
    }

    /**
     * Color for the filled hearts at a given health ratio (0..1): interpolates
     * {@code gradient-low} -> {@code gradient-mid} over the lower half and
     * {@code gradient-mid} -> {@code gradient-high} over the upper half.
     */
    private TextColor gradientColor(double ratio) {
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return ratio <= 0.5
                ? TextColor.lerp((float) (ratio / 0.5), gradLow, gradMid)
                : TextColor.lerp((float) ((ratio - 0.5) / 0.5), gradMid, gradHigh);
    }

    // ------------------------------------------------------------------ eligibility

    private boolean eligible(Entity e) {
        if (!(e instanceof LivingEntity)) return false;
        if (e instanceof Player) return false;       // never touch players
        if (e instanceof ArmorStand) return false;   // not a "mob"
        EntityType t = e.getType();
        if (blacklist.contains(t)) return false;
        if (!onlyTypes.isEmpty() && !onlyTypes.contains(t)) return false;
        return true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!enabled) return;
        if (!eligible(e.getEntity())) return;
        scheduleTrack((LivingEntity) e.getEntity()); // health updates next tick
    }

    /**
     * Our heart-bar lives in each mob's custom name, so vanilla death messages
     * say "slain by ♥♥♥ 20/20". Rewrite the message to name the actual mob.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!enabled) return;
        Player victim = e.getEntity();
        EntityDamageEvent cause = victim.getLastDamageCause();
        if (!(cause instanceof EntityDamageByEntityEvent ede)) return;

        Entity raw = ede.getDamager();
        LivingEntity killer = raw instanceof LivingEntity le ? le
                : (raw instanceof Projectile pr && pr.getShooter() instanceof LivingEntity sh ? sh : null);
        if (killer == null || killer instanceof Player) return;
        // Only fix mobs WE renamed (so we don't clobber other plugins' messages).
        if (!killer.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE)) return;

        String verb = raw instanceof Projectile ? "was shot by" : "was slain by";
        e.deathMessage(Component.text(victim.getName() + " " + verb + " " + killerName(killer)));
    }

    /** A clean display name for a mob: its player-given name if any, else the type. */
    private String killerName(LivingEntity killer) {
        String orig = killer.getPersistentDataContainer().get(origNameKey, PersistentDataType.STRING);
        if (orig != null && !orig.isBlank()) return orig.replaceAll("§.", "").trim();
        String s = killer.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
            if (c == ' ') cap = true;
        }
        return sb.toString();
    }

    /**
     * The heart-bar lives in the mob's custom name, so it gets saved into
     * chunk data (crash, unload, restart). After a restart {@code activeBars}
     * is empty, so a stale persisted bar on a mob far from players would never
     * be cleaned up. Restore any managed-but-untracked mob as its chunk loads;
     * the proximity scan re-applies a fresh bar if a player is nearby.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof LivingEntity le
                    && !activeBars.contains(le.getUniqueId())
                    && le.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE)) {
                restore(le);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (!enabled) return;
        if (!eligible(e.getEntity())) return;
        scheduleTrack((LivingEntity) e.getEntity());
    }

    // ------------------------------------------------------------------ proximity scan

    /**
     * Runs every {@code scan-interval-ticks}. Shows/updates bars on eligible
     * mobs within {@code view-radius} of any player, and hides the bar on mobs
     * everyone has walked away from.
     */
    private void proximityScan() {
        if (!enabled) {
            return;
        }
        Set<UUID> near = collectNearbyEligible();

        // Show / refresh nearby mobs.
        for (UUID id : near) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                track(le);
            }
        }

        // Hide mobs that are no longer near any player.
        for (UUID id : new ArrayList<>(activeBars)) {
            if (near.contains(id)) continue;
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le) restore(le);
            activeBars.remove(id);
        }
    }

    /** Collect UUIDs of eligible mobs within view-radius of any online player. */
    private Set<UUID> collectNearbyEligible() {
        Set<UUID> set = new HashSet<>();

        // viewRadius is always positive here (clamped in loadConfigValues).
        double r2 = viewRadius * viewRadius;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Entity e : p.getNearbyEntities(viewRadius, viewRadius, viewRadius)) {
                if (!eligible(e)) continue;
                if (e.getLocation().distanceSquared(p.getLocation()) <= r2) {
                    set.add(e.getUniqueId());
                }
            }
        }
        return set;
    }

    // ------------------------------------------------------------------ core

    private void scheduleTrack(Entity e) {
        if (!(e instanceof LivingEntity le)) return;
        // Respect view-radius on the event path too: mob farms far from any
        // player fire constant damage events, and renaming those mobs is
        // wasted churn. The proximity scan picks a mob up if a player later
        // comes within range.
        if (!nearAnyPlayer(le)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (le.isValid() && !le.isDead()) track(le);
        });
    }

    /** True if any player in the mob's world is within view-radius of it. */
    private boolean nearAnyPlayer(LivingEntity le) {
        double r2 = viewRadius * viewRadius;
        Location at = le.getLocation();
        for (Player p : le.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= r2) return true;
        }
        return false;
    }

    /** Apply/refresh a mob's bar and keep the active-set membership in sync. */
    private void track(LivingEntity le) {
        if (updateEntity(le)) {
            activeBars.add(le.getUniqueId());
        } else {
            activeBars.remove(le.getUniqueId());
        }
    }

    private void refreshAllLoaded() {
        proximityScan();
    }

    /**
     * Recompute and apply (or remove) the health bar for one mob.
     * @return true if a bar is now shown, false if it was hidden/not shown.
     */
    private boolean updateEntity(LivingEntity le) {
        if (!enabled || !eligible(le)) return false;

        double max = maxHealthOf(le);
        if (max <= 0) return false;
        double hp = Math.max(0.0, Math.min(le.getHealth(), max));
        boolean full = hp >= max;

        if (mode == Mode.DAMAGED && full) {
            restore(le);   // back to original name once healed up
            return false;
        }

        capture(le);       // stash original name once

        int filled = (int) Math.round(hp / max * maxHearts);
        if (filled < 0) filled = 0;
        if (filled > maxHearts) filled = maxHearts;
        int empty = maxHearts - filled;

        // Filled hearts: either a flat color (full-color) or a health-based
        // gradient tint applied to the whole filled run.
        Component filledComp = healthGradient
                ? Component.text(fullHeart.repeat(filled)).color(gradientColor(hp / max))
                : AMP.deserialize(fullColor + fullHeart.repeat(filled));

        // Empty hearts + optional "current/max" number, in their legacy colors.
        StringBuilder rest = new StringBuilder();
        rest.append(emptyColor).append(emptyHeart.repeat(empty));
        if (showNumber) {
            rest.append(' ').append(numberColor).append(ceil(hp))
                .append(emptyColor).append('/')
                .append(numberColor).append(ceil(max));
        }

        Component hearts = filledComp.append(AMP.deserialize(rest.toString()))
                .decoration(TextDecoration.ITALIC, false);

        Component out = hearts;
        if (preserveNames) {
            String orig = le.getPersistentDataContainer()
                    .getOrDefault(origNameKey, PersistentDataType.STRING, "");
            if (!orig.isEmpty()) {
                out = SECTION.deserialize(orig)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(" "))
                        .append(hearts);
            }
        }

        // Skip re-setting the name (and its metadata packets) when the rendered
        // bar is identical to what we last applied to this mob.
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        String rendered = SECTION.serialize(out);
        String lastRendered = pdc.get(renderKey, PersistentDataType.STRING);
        if (lastRendered != null && lastRendered.equals(rendered)
                && le.isCustomNameVisible()) {
            return true;
        }

        le.customName(out);
        le.setCustomNameVisible(true);
        pdc.set(renderKey, PersistentDataType.STRING, rendered);
        return true;
    }

    /** Save the mob's pre-existing name + visibility exactly once. */
    private void capture(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        if (pdc.has(managedKey, PersistentDataType.BYTE)) return;

        Component existing = le.customName();
        String origName = existing == null ? "" : SECTION.serialize(existing);
        pdc.set(origNameKey, PersistentDataType.STRING, origName);
        pdc.set(origVisKey, PersistentDataType.BYTE, (byte) (le.isCustomNameVisible() ? 1 : 0));
        pdc.set(managedKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Put the mob's original name + visibility back and forget about it. */
    private void restore(LivingEntity le) {
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        if (!pdc.has(managedKey, PersistentDataType.BYTE)) return;

        String orig = pdc.getOrDefault(origNameKey, PersistentDataType.STRING, "");
        byte vis    = pdc.getOrDefault(origVisKey, PersistentDataType.BYTE, (byte) 0);

        if (orig.isEmpty()) {
            le.customName(null);
        } else {
            le.customName(SECTION.deserialize(orig));
        }
        le.setCustomNameVisible(vis == 1);

        pdc.remove(managedKey);
        pdc.remove(origNameKey);
        pdc.remove(origVisKey);
        pdc.remove(renderKey);
    }

    /** Max health without touching the renamed Attribute enum (varies by 1.21.x). */
    @SuppressWarnings("deprecation")
    private double maxHealthOf(LivingEntity le) {
        try {
            return le.getMaxHealth();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static long ceil(double v) {
        return (long) Math.ceil(v - 1e-6);
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            // Strip current bars so a mode/format change applies cleanly.
            for (var world : Bukkit.getWorlds()) {
                for (LivingEntity le : world.getLivingEntities()) {
                    if (le.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE)) {
                        restore(le);
                    }
                }
            }
            activeBars.clear();
            loadConfigValues();
            // Reschedule the scan in case scan-interval-ticks changed.
            if (scanTask != null) scanTask.cancel();
            scanTask = Bukkit.getScheduler().runTaskTimer(this, this::proximityScan,
                    scanTicks, scanTicks);
            refreshAllLoaded();
            sender.sendMessage(AMP.deserialize("&d(\u2727) KawaiiHearts reloaded~ "
                    + (enabled ? "&abeating!" : "&7(disabled)")));
            return true;
        }
        sender.sendMessage(AMP.deserialize("&d(\u2727) usage: &f/khearts reload"));
        return true;
    }
}
