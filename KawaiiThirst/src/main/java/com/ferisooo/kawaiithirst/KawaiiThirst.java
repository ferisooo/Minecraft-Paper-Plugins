package com.ferisooo.kawaiithirst;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KawaiiThirst — a survival thirst stat that sits "above" hunger in importance:
 * it drains a little faster than hunger and, when it runs dry, hurts more.
 *
 * <p>Minecraft can't add a real bar above the hunger bar without a resource
 * pack/client mod, so thirst is shown as a boss bar at the top of the screen
 * (which Geyser renders for Bedrock too). It drains over time — faster when
 * sprinting or in hot biomes — and refills by drinking water bottles, standing
 * in water, or eating juicy foods. Low thirst slows you; empty thirst dehydrates
 * you for damage.
 */
public final class KawaiiThirst extends JavaPlugin implements Listener {

    private NamespacedKey key;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    private boolean enabled;
    private boolean showBar;
    private double max;
    private long intervalTicks;
    private double drainPerInterval;
    private double sprintMultiplier;
    private double hotMultiplier;
    private double waterRestore;
    private double bottleRestore;
    private double foodRestore;
    private double lowThreshold;
    private double criticalThreshold;
    private double dehydrationDamage;

    private final Set<String> hotBiomes = new HashSet<>();
    private final Set<Biome> hotBiomeSet = new HashSet<>();
    private final Set<Material> juicyFoods = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "thirst");
        readConfig();
        getServer().getPluginManager().registerEvents(this, this);
        // Folia-safe: global-region repeating driver, per-player work hops to
        // each player's entity scheduler (damage/effects/thirst touch the player).
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tickAll(), intervalTicks, intervalTicks);
        for (Player p : Bukkit.getOnlinePlayers()) ensureBar(p);
        getLogger().info("(✧) KawaiiThirst ready ~ stay hydrated! 💧");
    }

    @Override
    public void onDisable() {
        for (BossBar b : bars.values()) b.removeAll();
        bars.clear();
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        enabled            = c.getBoolean("enabled", true);
        showBar            = c.getBoolean("show-bar", true);
        max                = Math.max(1.0, c.getDouble("max-thirst", 20));
        intervalTicks      = Math.max(20L, c.getLong("update-ticks", 60));
        drainPerInterval   = Math.max(0.0, c.getDouble("drain-per-update", 0.6));
        sprintMultiplier   = Math.max(1.0, c.getDouble("sprint-multiplier", 2.0));
        hotMultiplier      = Math.max(1.0, c.getDouble("hot-biome-multiplier", 1.8));
        waterRestore       = Math.max(0.0, c.getDouble("water-restore", 1.5));
        bottleRestore      = Math.max(0.0, c.getDouble("water-bottle-restore", 6.0));
        foodRestore        = Math.max(0.0, c.getDouble("juicy-food-restore", 2.5));
        lowThreshold       = c.getDouble("low-threshold", 6);
        criticalThreshold  = c.getDouble("critical-threshold", 3);
        dehydrationDamage  = Math.max(0.0, c.getDouble("dehydration-damage", 1.0));

        hotBiomes.clear();
        for (String s : c.getStringList("hot-biome-keywords")) hotBiomes.add(s.toLowerCase(Locale.ROOT));
        if (hotBiomes.isEmpty()) {
            for (String s : new String[]{"desert", "savanna", "badlands", "mesa",
                    "nether", "basalt", "crimson", "warped", "soul_sand"}) hotBiomes.add(s);
        }
        // Resolve the configured keyword substrings against the biome registry
        // once on (re)load, so the per-tick hot-biome check is a single Set
        // lookup instead of a substring scan over every keyword.
        hotBiomeSet.clear();
        for (Biome b : Registry.BIOME) {
            String k = b.getKey().getKey().toLowerCase(Locale.ROOT);
            for (String s : hotBiomes) {
                if (k.contains(s)) { hotBiomeSet.add(b); break; }
            }
        }

        juicyFoods.clear();
        for (String s : new String[]{"APPLE", "MELON_SLICE", "SWEET_BERRIES", "GLOW_BERRIES",
                "CARROT", "CHORUS_FRUIT", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE"}) {
            Material m = Material.matchMaterial(s);
            if (m != null) juicyFoods.add(m);
        }
    }

    // ----------------------------------------------------------------- ticking

    private void tickAll() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, t -> update(p), null);
        }
    }

    private void update(Player p) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            setThirst(p, max);
            BossBar b = bars.get(p.getUniqueId());
            if (b != null) b.setVisible(false);
            return;
        }

        double t = getThirst(p);
        if (p.isInWater()) {
            t += waterRestore;
        } else {
            double drain = drainPerInterval;
            if (p.isSprinting()) drain *= sprintMultiplier;
            if (isHot(p)) drain *= hotMultiplier;
            t -= drain;
        }
        t = Math.max(0, Math.min(max, t));
        setThirst(p, t);

        applyEffects(p, t);
        if (t <= 0 && dehydrationDamage > 0) p.damage(dehydrationDamage);

        renderBar(p, t);
    }

    private void applyEffects(Player p, double t) {
        int dur = (int) intervalTicks + 40;
        if (t <= criticalThreshold) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, dur, 1, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, dur, 0, true, false, false));
        } else if (t <= lowThreshold) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, dur, 0, true, false, false));
        }
    }

    private void renderBar(Player p, double t) {
        BossBar b = ensureBar(p);
        if (!showBar) { b.setVisible(false); return; }
        double frac = Math.max(0, Math.min(1, t / max));
        b.setProgress(frac);
        b.setColor(t <= criticalThreshold ? BarColor.RED : t <= lowThreshold ? BarColor.YELLOW : BarColor.BLUE);
        b.setTitle("§b💧 Thirst §f" + (int) Math.ceil(t) + "§7/§f" + (int) max
                + (t <= criticalThreshold ? " §c(parched!)" : ""));
        b.setVisible(true);
    }

    // ------------------------------------------------------------ restore hooks

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null) return;

        if (item.getType() == Material.POTION
                && item.getItemMeta() instanceof PotionMeta pm
                && pm.getBasePotionType() == PotionType.WATER) {
            add(p, bottleRestore);
            p.sendActionBar(net.kyori.adventure.text.Component.text("💧 refreshing~",
                    net.kyori.adventure.text.format.NamedTextColor.AQUA));
        } else if (juicyFoods.contains(item.getType())) {
            add(p, foodRestore);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getThirstSet(e.getPlayer())) setThirst(e.getPlayer(), max);
        ensureBar(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        // Death clears thirst just like it clears hunger — respawn fully hydrated.
        Player p = e.getPlayer();
        setThirst(p, max);
        ensureBar(p);
        renderBar(p, max);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        BossBar b = bars.remove(e.getPlayer().getUniqueId());
        if (b != null) b.removeAll();
    }

    // ----------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiithirst.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            readConfig();
            sender.sendMessage("§d(✧) KawaiiThirst reloaded ✨");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("set")
                && sender.hasPermission("kawaiithirst.admin")) {
            Player t = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                    : (sender instanceof Player p ? p : null);
            if (t == null) { sender.sendMessage("§c(✧) player not found~"); return true; }
            try {
                setThirst(t, Math.max(0, Math.min(max, Double.parseDouble(args[1]))));
                sender.sendMessage("§d(✧) set " + t.getName() + "'s thirst~");
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c(✧) /thirst set <amount> [player]");
            }
            return true;
        }
        if (sender instanceof Player p) {
            sender.sendMessage("§d(✧) your thirst: §f" + (int) Math.ceil(getThirst(p)) + "§7/§f" + (int) max);
        } else {
            sender.sendMessage("§d(✧) KawaiiThirst — /thirst [reload|set <amount> [player]]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        // /thirst set <amount> [player] — suggest online player names at the player arg (arg 3 -> args[2]).
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return filterPlayers(args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPlayers(String prefix) {
        String pre = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
        }
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private boolean isHot(Player p) {
        var loc = p.getLocation();
        Biome biome = p.getWorld().getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return hotBiomeSet.contains(biome);
    }

    private BossBar ensureBar(Player p) {
        return bars.computeIfAbsent(p.getUniqueId(), id -> {
            BossBar b = Bukkit.createBossBar("§b💧 Thirst", BarColor.BLUE, BarStyle.SEGMENTED_20);
            b.addPlayer(p);
            return b;
        });
    }

    private void add(Player p, double amount) {
        setThirst(p, Math.max(0, Math.min(max, getThirst(p) + amount)));
        renderBar(p, getThirst(p));
    }

    private boolean getThirstSet(Player p) {
        return p.getPersistentDataContainer().has(key, PersistentDataType.DOUBLE);
    }

    private double getThirst(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Double v = pdc.get(key, PersistentDataType.DOUBLE);
        return v == null ? max : v;
    }

    private void setThirst(Player p, double v) {
        // Skip the write when the stored value is already identical — the tick
        // loop calls this every interval per player, and the value is often
        // unchanged (e.g. already full, or standing still at the cap).
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Double cur = pdc.get(key, PersistentDataType.DOUBLE);
        if (cur != null && cur == v) return;
        pdc.set(key, PersistentDataType.DOUBLE, v);
    }
}
