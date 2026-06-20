package com.ferisooo.kawaiisparkles;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KawaiiSparkles — purely cosmetic, server-side visual effects, driven from a
 * clickable in-game GUI. Nothing needs a resource pack and everything is
 * Geyser/Bedrock-safe.
 *
 * <p>Per-player selectable particle effects for several <b>actions</b> — walking/
 * running (a footstep "trail"), attacking, mining, jumping, crouching and
 * swimming — each chosen independently from an Effects menu. Plus chest-open
 * sparkles and an animated action bar.
 */
public final class KawaiiSparkles extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer AMP =
            LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.builder().character('§').build();

    /** Containers whose opening should trigger sparkles. */
    private static final Set<Material> SPARKLY_CONTAINERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST,
            Material.BARREL,
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX);

    // ====================================================================
    // Effect model
    // ====================================================================

    /** The actions a player can attach an effect to. TRAIL is dual (walk + run). */
    private enum ActionType {
        TRAIL("Footstep Trail", "POPPY", true),
        ATTACK("Attack", "IRON_SWORD", false),
        MINING("Mining", "IRON_PICKAXE", false),
        JUMP("Jump", "FEATHER", false),
        SNEAK("Crouch", "COAL", false),
        SWIM("Swim", "WATER_BUCKET", false);

        final String label, menuIcon;
        final boolean dual;
        ActionType(String label, String menuIcon, boolean dual) {
            this.label = label; this.menuIcon = menuIcon; this.dual = dual;
        }
        /** config key for the non-trail actions, e.g. effects.attack */
        String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One choosable effect: a name, an icon and its particle(s). p1==null means "none/off". */
    private static final class Effect {
        final String id, name; final Material icon;
        final Particle p1, p2; final int c1, c2;
        Effect(String id, String name, Material icon, Particle p1, Particle p2, int c1, int c2) {
            this.id = id; this.name = name; this.icon = icon;
            this.p1 = p1; this.p2 = p2; this.c1 = c1; this.c2 = c2;
        }
        boolean isNone() { return p1 == null; }
    }

    /** Everything for one action: its options, default and per-player picks. */
    private static final class ActionData {
        final ActionType type;
        boolean enabled;
        final List<Effect> options = new ArrayList<>();
        final Map<String, Effect> byId = new HashMap<>();
        String defaultId;
        final Map<UUID, String> selected = new HashMap<>();
        ActionData(ActionType type) { this.type = type; }

        Effect selectedFor(UUID id) {
            Effect e = byId.get(selected.getOrDefault(id, defaultId));
            return e != null ? e : (options.isEmpty() ? null : options.get(0));
        }
        Material icon() {
            for (Effect e : options) if (!e.isNone()) return e.icon;
            return Material.matchMaterial(type.menuIcon) != null
                    ? Material.matchMaterial(type.menuIcon) : Material.SUGAR;
        }
        boolean hasRealOptions() {
            for (Effect e : options) if (!e.isNone()) return true;
            return false;
        }
    }

    private final Map<ActionType, ActionData> actions = new EnumMap<>(ActionType.class);

    // ---- config-backed settings ----
    private boolean chestEnabled;
    private Particle chestParticle;
    private int chestCount;
    private double chestHeight, chestRadius;
    private String chestOpenSound, chestCloseSound;
    private float chestOpenPitch, chestClosePitch;

    private boolean moveEnabled;
    private int walkInterval, sprintInterval;
    private Particle walkParticle, sprintParticle; // fallbacks for trail options
    private int walkCount, sprintCount;
    private String walkSound, sprintSound;
    private float walkVolume, sprintVolume;
    private boolean skipWhileSneaking;

    private boolean hotbarEnabled;
    private int hotbarInterval;
    private final List<Component> hotbarFramesJava = new ArrayList<>();
    private final List<Component> hotbarFramesBedrock = new ArrayList<>();

    private boolean menuEnabled;
    private String menuTitleRaw;
    private int menuRows, menuInterval;
    private final List<Material> menuPalette = new ArrayList<>();

    private boolean defaultFootsteps, defaultHotbar;

    // ---- per-player state ----
    private final Map<UUID, Boolean> footstepsOn = new HashMap<>();
    private final Map<UUID, Boolean> hotbarOn = new HashMap<>();
    private final Map<UUID, Long> lastMoveTick = new HashMap<>();

    private long ticks;
    private BukkitTask animator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startAnimator();
        getLogger().info("(✧) KawaiiSparkles ready ~ sparkle responsibly ✨");
    }

    @Override
    public void onDisable() {
        if (animator != null) animator.cancel();
    }

    // ====================================================================
    // Config
    // ====================================================================

    private void readConfig() {
        reloadConfig();
        FileConfiguration c = getConfig();

        chestEnabled = c.getBoolean("chest-effects.enabled", true);
        chestParticle = particle(c.getString("chest-effects.particle"), Particle.HEART);
        chestCount = c.getInt("chest-effects.count", 10);
        chestHeight = c.getDouble("chest-effects.height", 1.1);
        chestRadius = c.getDouble("chest-effects.radius", 0.45);
        chestOpenSound = soundKey(c.getString("chest-effects.open-sound", "BLOCK_NOTE_BLOCK_BELL"));
        chestOpenPitch = (float) c.getDouble("chest-effects.open-pitch", 1.6);
        chestCloseSound = soundKey(c.getString("chest-effects.close-sound", "BLOCK_NOTE_BLOCK_HAT"));
        chestClosePitch = (float) c.getDouble("chest-effects.close-pitch", 1.2);

        moveEnabled = c.getBoolean("movement-effects.enabled", true);
        walkInterval = Math.max(1, c.getInt("movement-effects.walk-interval-ticks", 6));
        sprintInterval = Math.max(1, c.getInt("movement-effects.sprint-interval-ticks", 3));
        walkParticle = particle(c.getString("movement-effects.walk-particle"), Particle.COMPOSTER);
        walkCount = c.getInt("movement-effects.walk-count", 3);
        sprintParticle = particle(c.getString("movement-effects.sprint-particle"), Particle.CLOUD);
        sprintCount = c.getInt("movement-effects.sprint-count", 6);
        walkSound = soundKey(c.getString("movement-effects.walk-sound", "BLOCK_WOOL_STEP"));
        walkVolume = (float) c.getDouble("movement-effects.walk-volume", 0.25);
        sprintSound = soundKey(c.getString("movement-effects.sprint-sound", "BLOCK_WOOL_STEP"));
        sprintVolume = (float) c.getDouble("movement-effects.sprint-volume", 0.5);
        skipWhileSneaking = c.getBoolean("movement-effects.skip-while-sneaking", true);

        hotbarEnabled = c.getBoolean("hotbar.enabled", true);
        hotbarInterval = Math.max(1, c.getInt("hotbar.interval-ticks", 8));
        hotbarFramesJava.clear();
        hotbarFramesBedrock.clear();
        for (String f : c.getStringList("hotbar.frames")) {
            hotbarFramesJava.add(AMP.deserialize(f));
            hotbarFramesBedrock.add(AMP.deserialize(bedrockText(f)));
        }
        if (hotbarFramesJava.isEmpty()) {
            hotbarFramesJava.add(AMP.deserialize("&d* &fsparkle &d*"));
            hotbarFramesBedrock.add(AMP.deserialize("&d* &fsparkle &d*"));
        }

        menuEnabled = c.getBoolean("menu.enabled", true);
        menuTitleRaw = c.getString("menu.title", "&d&l* KawaiiSparkles *");
        menuRows = Math.max(3, Math.min(6, c.getInt("menu.rows", 3)));
        menuInterval = Math.max(1, c.getInt("menu.interval-ticks", 6));
        menuPalette.clear();
        for (String name : c.getStringList("menu.palette")) {
            Material m = pane(name);
            if (m != null) menuPalette.add(m);
        }
        if (menuPalette.isEmpty()) {
            menuPalette.add(Material.PINK_STAINED_GLASS_PANE);
            menuPalette.add(Material.MAGENTA_STAINED_GLASS_PANE);
            menuPalette.add(Material.PURPLE_STAINED_GLASS_PANE);
        }

        for (ActionType t : ActionType.values()) {
            loadAction(c, actions.computeIfAbsent(t, ActionData::new));
        }

        defaultFootsteps = c.getBoolean("defaults.footsteps-on", true);
        defaultHotbar = c.getBoolean("defaults.hotbar-on", false);
    }

    /** (Re)loads one action's options + default, preserving players' existing picks. */
    private void loadAction(FileConfiguration c, ActionData a) {
        a.options.clear();
        a.byId.clear();
        ActionType t = a.type;

        String base;
        if (t == ActionType.TRAIL) {
            a.enabled = moveEnabled;
            base = "trails";
        } else {
            base = "effects." + t.key();
            a.enabled = c.getBoolean(base + ".enabled", true);
        }

        ConfigurationSection opts = c.getConfigurationSection(base + ".options");
        if (opts != null) {
            for (String id : opts.getKeys(false)) {
                ConfigurationSection s = opts.getConfigurationSection(id);
                if (s != null) addOption(a, id, s, t.dual);
            }
        }
        if (a.options.isEmpty()) builtins(a); // server config predates effects? ship sane defaults

        // A built-in "none" option so any effect can be switched off per player.
        if (!a.byId.containsKey("none")) {
            Effect none = new Effect("none", "§7None", Material.BARRIER, null, null, 0, 0);
            a.options.add(none);
            a.byId.put("none", none);
        }

        String def = c.getString(base + ".default", firstRealId(a));
        a.defaultId = a.byId.containsKey(def) ? def : firstRealId(a);
    }

    private void addOption(ActionData a, String id, ConfigurationSection s, boolean dual) {
        String name = amp(s.getString("name", id));
        Material icon = material(s.getString("icon", "SUGAR"));
        Particle p1, p2; int c1, c2;
        if (dual) {
            p1 = particle(s.getString("walk-particle"), walkParticle);
            p2 = particle(s.getString("sprint-particle"), sprintParticle);
            c1 = Math.max(0, s.getInt("walk-count", walkCount));
            c2 = Math.max(0, s.getInt("sprint-count", sprintCount));
        } else {
            p1 = particle(s.getString("particle"), Particle.HEART);
            p2 = p1;
            c1 = Math.max(0, s.getInt("count", 6));
            c2 = c1;
        }
        Effect e = new Effect(id, name, icon, p1, p2, c1, c2);
        a.options.add(e);
        a.byId.put(id, e);
    }

    /** First non-"none" option id, or "none" if that's all there is. */
    private static String firstRealId(ActionData a) {
        for (Effect e : a.options) if (!e.isNone()) return e.id;
        return a.options.isEmpty() ? "none" : a.options.get(0).id;
    }

    /** Built-in option sets, used when config.yml lacks a section (all via string lookups,
     *  so a typo or missing-in-this-version particle just falls back gracefully). */
    private void builtins(ActionData a) {
        switch (a.type) {
            case TRAIL -> {
                addDual(a, "hearts", "&dHearts", "POPPY", "HEART", "HEART", 2, 5);
                addDual(a, "clouds", "&fClouds", "WHITE_WOOL", "CLOUD", "CLOUD", 2, 6);
                addDual(a, "flames", "&6Flames", "BLAZE_POWDER", "FLAME", "FLAME", 3, 8);
            }
            case ATTACK -> {
                add(a, "crit", "&cCrit", "IRON_SWORD", "CRIT", 12);
                add(a, "sparks", "&eSparks", "GLOWSTONE_DUST", "ENCHANTED_HIT", 14);
                add(a, "flames", "&6Flames", "BLAZE_POWDER", "FLAME", 12);
                add(a, "hearts", "&dHearts", "POPPY", "HEART", 6);
            }
            case MINING -> {
                add(a, "sparks", "&fSparks", "IRON_PICKAXE", "CRIT", 8);
                add(a, "poof", "&7Poof", "WHITE_WOOL", "CLOUD", 10);
                add(a, "notes", "&bNotes", "NOTE_BLOCK", "NOTE", 5);
                add(a, "flame", "&6Flame", "BLAZE_POWDER", "FLAME", 8);
            }
            case JUMP -> {
                add(a, "poof", "&fPoof", "FEATHER", "CLOUD", 12);
                add(a, "happy", "&aHappy", "EMERALD", "HAPPY_VILLAGER", 8);
                add(a, "firework", "&eFirework", "FIREWORK_ROCKET", "FIREWORK", 10);
                add(a, "hearts", "&dHearts", "POPPY", "HEART", 6);
            }
            case SNEAK -> {
                add(a, "smoke", "&8Smoke", "COAL", "SMOKE", 6);
                add(a, "portal", "&5Portal", "ENDER_PEARL", "PORTAL", 10);
                add(a, "soul", "&3Soul", "SOUL_SAND", "SOUL", 6);
            }
            case SWIM -> {
                add(a, "bubbles", "&bBubbles", "PRISMARINE_SHARD", "BUBBLE", 8);
                add(a, "splash", "&3Splash", "WATER_BUCKET", "SPLASH", 8);
                add(a, "drip", "&9Drip", "BLUE_DYE", "DRIPPING_WATER", 4);
            }
        }
    }

    private void add(ActionData a, String id, String name, String icon, String particle, int count) {
        Particle p = particle(particle, Particle.HEART);
        Effect e = new Effect(id, amp(name), material(icon), p, p, count, count);
        a.options.add(e);
        a.byId.put(id, e);
    }

    private void addDual(ActionData a, String id, String name, String icon,
                         String walk, String sprint, int wc, int sc) {
        Effect e = new Effect(id, amp(name), material(icon),
                particle(walk, walkParticle), particle(sprint, sprintParticle), wc, sc);
        a.options.add(e);
        a.byId.put(id, e);
    }

    private Particle particle(String name, Particle fallback) {
        if (name == null) return fallback;
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("(✧) unknown particle '" + name + "', using " + fallback);
            return fallback;
        }
    }

    private Material material(String name) {
        Material m = name == null ? null : Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return (m != null && m.isItem()) ? m : Material.SUGAR;
    }

    private static String soundKey(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.indexOf('.') >= 0 || s.indexOf(':') >= 0) return s.toLowerCase(Locale.ROOT);
        return s.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private Material pane(String name) {
        if (name == null) return null;
        String n = name.trim().toUpperCase(Locale.ROOT);
        Material direct = Material.matchMaterial(n);
        if (direct != null && direct.name().endsWith("STAINED_GLASS_PANE")) return direct;
        Material byColour = Material.matchMaterial(n + "_STAINED_GLASS_PANE");
        if (byColour != null) return byColour;
        getLogger().warning("(✧) unknown menu palette colour '" + name + "', skipping");
        return null;
    }

    private static String amp(String s) { return s == null ? "" : s.replace('&', '§'); }

    private static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ====================================================================
    // Chest open / close sparkles
    // ====================================================================

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (!chestEnabled) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        Location at = sparkleSpot(p, e.getInventory());
        if (at == null) return;
        sparkle(at, chestParticle, chestCount);
        if (chestOpenSound != null) p.playSound(at, chestOpenSound, 0.6f, chestOpenPitch);
    }

    @EventHandler
    public void onCloseSparkle(InventoryCloseEvent e) {
        if (!chestEnabled || chestCloseSound == null) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        Location at = sparkleSpot(p, e.getInventory());
        if (at == null) return;
        p.playSound(at, chestCloseSound, 0.6f, chestClosePitch);
    }

    private Location sparkleSpot(Player p, Inventory inv) {
        if (inv == null) return null;
        Block b = containerBlock(inv);
        if (b != null && SPARKLY_CONTAINERS.contains(b.getType())) {
            return b.getLocation().add(0.5, 0.5, 0.5);
        }
        if (inv.getType() == InventoryType.ENDER_CHEST) {
            Block target = p.getTargetBlockExact(6);
            if (target != null && target.getType() == Material.ENDER_CHEST) {
                return target.getLocation().add(0.5, 0.5, 0.5);
            }
        }
        return null;
    }

    private Block containerBlock(Inventory inv) {
        if (inv.getHolder() instanceof org.bukkit.block.Container container) return container.getBlock();
        if (inv.getHolder() instanceof org.bukkit.block.DoubleChest dc
                && dc.getLeftSide() instanceof org.bukkit.block.Container left) {
            return left.getBlock();
        }
        Location loc = inv.getLocation();
        return loc == null ? null : loc.getBlock();
    }

    private void sparkle(Location at, Particle particle, int count) {
        Location centre = at.clone().add(0, chestHeight - 0.5, 0);
        for (int i = 0; i < count; i++) {
            double ang = (Math.PI * 2 * i) / count;
            centre.getWorld().spawnParticle(particle,
                    centre.clone().add(Math.cos(ang) * chestRadius, 0, Math.sin(ang) * chestRadius),
                    1, 0, 0, 0, 0);
        }
    }

    // ====================================================================
    // Action effects: movement (walk / run / swim), attack, mine, jump, sneak
    // ====================================================================

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        if (!enabled(footstepsOn, p, defaultFootsteps)) return; // master toggle for movement trails

        ActionData swim = actions.get(ActionType.SWIM);
        boolean swimming = swim != null && swim.enabled && p.isSwimming();
        if (!swimming) {
            if (!moveEnabled) return;
            if (!p.isOnGround()) return;
            if (skipWhileSneaking && p.isSneaking()) return;
        }

        boolean sprinting = p.isSprinting();
        int interval = swimming ? walkInterval : (sprinting ? sprintInterval : walkInterval);
        Long last = lastMoveTick.get(p.getUniqueId());
        if (last != null && ticks - last < interval) return;
        lastMoveTick.put(p.getUniqueId(), ticks);

        if (swimming) {
            Effect ef = swim.selectedFor(p.getUniqueId());
            if (ef != null && ef.p1 != null && ef.c1 > 0) {
                p.getWorld().spawnParticle(ef.p1, p.getLocation().add(0, 0.4, 0),
                        ef.c1, 0.2, 0.1, 0.2, 0.0);
            }
            return;
        }

        ActionData trail = actions.get(ActionType.TRAIL);
        Effect ef = trail == null ? null : trail.selectedFor(p.getUniqueId());
        Location feet = p.getLocation();
        if (ef != null && !ef.isNone()) {
            Particle particle = sprinting ? ef.p2 : ef.p1;
            int count = sprinting ? ef.c2 : ef.c1;
            if (particle != null && count > 0) {
                feet.getWorld().spawnParticle(particle, feet, count, 0.15, 0.02, 0.15, 0.0);
            }
        }
        String s = sprinting ? sprintSound : walkSound;
        float v = sprinting ? sprintVolume : walkVolume;
        if (s != null) p.playSound(feet, s, v, sprinting ? 1.4f : 1.1f);
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        spawnAt(ActionType.ATTACK, p, victimSpot(e.getEntity()), 0.3);
    }

    @EventHandler
    public void onMine(BlockBreakEvent e) {
        spawnAt(ActionType.MINING, e.getPlayer(), e.getBlock().getLocation().add(0.5, 0.5, 0.5), 0.3);
    }

    @EventHandler
    public void onJump(PlayerJumpEvent e) {
        spawnAt(ActionType.JUMP, e.getPlayer(), e.getPlayer().getLocation(), 0.2);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return; // only when starting to crouch
        spawnAt(ActionType.SNEAK, e.getPlayer(), e.getPlayer().getLocation().add(0, 0.1, 0), 0.25);
    }

    private Location victimSpot(Entity victim) {
        return victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
    }

    /** Spawns a player's chosen effect for an action at {@code at}, if that action is on. */
    private void spawnAt(ActionType type, Player p, Location at, double spread) {
        ActionData a = actions.get(type);
        if (a == null || !a.enabled || at == null) return;
        Effect ef = a.selectedFor(p.getUniqueId());
        if (ef == null || ef.p1 == null || ef.c1 <= 0) return;
        at.getWorld().spawnParticle(ef.p1, at, ef.c1, spread, spread, spread, 0.0);
    }

    // ====================================================================
    // Animator: action bar + menu border wave
    // ====================================================================

    private void startAnimator() {
        animator = Bukkit.getScheduler().runTaskTimer(this, () -> {
            ticks++;
            if (hotbarEnabled && !hotbarFramesJava.isEmpty() && ticks % hotbarInterval == 0) {
                int i = (int) ((ticks / hotbarInterval) % hotbarFramesJava.size());
                Component java = hotbarFramesJava.get(i);
                Component bedrock = hotbarFramesBedrock.get(i);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (enabled(hotbarOn, p, defaultHotbar)) {
                        p.sendActionBar(isBedrock(p.getUniqueId()) ? bedrock : java);
                    }
                }
            }
            if (menuEnabled && ticks % menuInterval == 0) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Inventory top = p.getOpenInventory().getTopInventory();
                    if (top.getHolder() instanceof MenuHolder) paintBorder(top);
                }
            }
        }, 1L, 1L);
    }

    // ====================================================================
    // Control panel GUI
    // ====================================================================

    private void openMenu(Player p) {
        boolean bedrock = isBedrock(p.getUniqueId());
        String title = bedrock ? bedrockText(menuTitleRaw) : menuTitleRaw;
        Inventory inv = Bukkit.createInventory(
                new MenuHolder(p.getUniqueId()), menuRows * 9, AMP.deserialize(title));
        paintBorder(inv);
        placeButtons(inv, p, bedrock);
        p.openInventory(inv);
        p.playSound(p.getLocation(), "block.amethyst_block.chime", 0.7f, 1.4f);
    }

    private int centreRowStart(int size) { return (size / 9 / 2) * 9; }

    private void placeButtons(Inventory inv, Player p, boolean bedrock) {
        int base = centreRowStart(inv.getSize());
        inv.setItem(base + 2, footstepsButton(p, bedrock));
        inv.setItem(base + 3, effectsButton(bedrock));
        inv.setItem(base + 4, hotbarButton(p, bedrock));
        inv.setItem(base + 6, chestButton(bedrock));
        if (p.hasPermission("kawaiisparkles.admin") || p.isOp()) {
            inv.setItem(base + 7, reloadButton(bedrock));
        }
    }

    private ItemStack footstepsButton(Player p, boolean bedrock) {
        boolean on = enabled(footstepsOn, p, defaultFootsteps);
        return button(on ? Material.LIME_DYE : Material.GRAY_DYE, bedrock,
                (on ? "§a✨ Footsteps: ON" : "§7✨ Footsteps: OFF"),
                "§7Walking / running / swimming",
                "§7particle trails.",
                "",
                "§e» Click to toggle");
    }

    private ItemStack effectsButton(boolean bedrock) {
        return button(Material.NETHER_STAR, bedrock,
                "§b✦ Effects",
                "§7Pick particle effects for each action:",
                "§7walk, attack, mine, jump, crouch, swim.",
                "§7Each can be different ✿",
                "",
                "§e» Click to open");
    }

    private ItemStack hotbarButton(Player p, boolean bedrock) {
        boolean on = enabled(hotbarOn, p, defaultHotbar);
        return button(on ? Material.LIME_DYE : Material.GRAY_DYE, bedrock,
                (on ? "§a✦ Action Bar: ON" : "§7✦ Action Bar: OFF"),
                "§7An animated banner above your hotbar.",
                "",
                "§e» Click to toggle");
    }

    private ItemStack chestButton(boolean bedrock) {
        return button(Material.ENDER_CHEST, bedrock,
                "§d✿ Chest Sparkles: " + (chestEnabled ? "§aON" : "§cOFF"),
                "§7Particles + a chime when you open",
                "§7chests, ender chests, barrels and",
                "§7shulker boxes. Global — edit config.");
    }

    private ItemStack reloadButton(boolean bedrock) {
        return button(Material.SUNFLOWER, bedrock,
                "§e⟳ Reload config", "§7Re-read config.yml.", "", "§e» Click to reload");
    }

    // ---- Effects menu (one icon per action) ----

    private List<ActionData> shownActions() {
        List<ActionData> list = new ArrayList<>();
        for (ActionType t : ActionType.values()) {
            ActionData a = actions.get(t);
            if (a != null && a.enabled && a.hasRealOptions()) list.add(a);
        }
        return list;
    }

    private void openEffectsMenu(Player p) {
        boolean bedrock = isBedrock(p.getUniqueId());
        List<ActionData> shown = shownActions();
        if (shown.isEmpty()) { p.sendMessage(msg(bedrock, "§d(✧) no effects are enabled~")); return; }
        int rows = Math.max(1, Math.min(6, (shown.size() + 1 + 8) / 9));
        int size = rows * 9;
        String title = bedrock ? bedrockText("&d&l✿ Effects ✿") : "&d&l✿ Effects ✿";
        Inventory inv = Bukkit.createInventory(new EffectsHolder(p.getUniqueId()), size, AMP.deserialize(title));
        for (int i = 0; i < shown.size() && i < size - 1; i++) {
            inv.setItem(i, actionIcon(shown.get(i), p, bedrock));
        }
        inv.setItem(size - 1, button(Material.BARRIER, bedrock, "§c« Back", "§7Return to the panel"));
        p.openInventory(inv);
        p.playSound(p.getLocation(), "block.amethyst_block.chime", 0.7f, 1.5f);
    }

    private ItemStack actionIcon(ActionData a, Player p, boolean bedrock) {
        Effect cur = a.selectedFor(p.getUniqueId());
        Material icon = (cur != null && !cur.isNone()) ? cur.icon : a.icon();
        return button(icon, bedrock,
                "§b" + a.type.label,
                "§7Current: §f" + (cur != null ? cur.name : "§7none"),
                "",
                "§e» Click to change");
    }

    // ---- Picker (options for a single action) ----

    private void openPicker(Player p, ActionType type) {
        boolean bedrock = isBedrock(p.getUniqueId());
        ActionData a = actions.get(type);
        if (a == null || a.options.size() <= 1) {
            p.sendMessage(msg(bedrock, "§d(✧) nothing to choose here~"));
            return;
        }
        int rows = Math.max(1, Math.min(6, (a.options.size() + 1 + 8) / 9));
        int size = rows * 9;
        String title = bedrock ? bedrockText("&d&l✿ " + a.type.label + " ✿") : "&d&l✿ " + a.type.label + " ✿";
        Inventory inv = Bukkit.createInventory(new PickerHolder(p.getUniqueId(), type), size, AMP.deserialize(title));
        Effect cur = a.selectedFor(p.getUniqueId());
        for (int i = 0; i < a.options.size() && i < size - 1; i++) {
            Effect t = a.options.get(i);
            inv.setItem(i, optionIcon(t, cur != null && cur.id.equals(t.id), bedrock, a.type.dual));
        }
        inv.setItem(size - 1, button(Material.ARROW, bedrock, "§c« Back", "§7Return to the effects menu"));
        p.openInventory(inv);
        p.playSound(p.getLocation(), "block.amethyst_block.chime", 0.7f, 1.5f);
    }

    private ItemStack optionIcon(Effect ef, boolean selected, boolean bedrock, boolean dual) {
        List<String> lore = new ArrayList<>();
        if (ef.isNone()) {
            lore.add("§7Turn this effect off.");
        } else if (dual) {
            lore.add("§7Walk: §f" + pretty(ef.p1.name()));
            lore.add("§7Run:  §f" + pretty(ef.p2.name()));
        } else {
            lore.add("§7Particle: §f" + pretty(ef.p1.name()));
        }
        lore.add("");
        lore.add(selected ? "§a✓ Currently selected" : "§e» Click to use");
        return button(ef.icon, bedrock, (selected ? "§a✓ " : "§d") + ef.name, lore.toArray(new String[0]));
    }

    private ItemStack button(Material mat, boolean bedrock, String name, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(text(bedrock, name));
            List<Component> lore = new ArrayList<>(loreLines.length);
            for (String l : loreLines) lore.add(text(bedrock, l));
            m.lore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private Component text(boolean bedrock, String legacy) {
        String s = bedrock ? bedrockText(legacy) : legacy;
        return SECTION.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private void paintBorder(Inventory inv) {
        int size = inv.getSize();
        int rows = size / 9;
        long step = ticks / menuInterval;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9, col = slot % 9;
            if (!(row == 0 || row == rows - 1 || col == 0 || col == 8)) continue;
            ItemStack p = new ItemStack(menuPalette.get((int) ((slot + step) % menuPalette.size())));
            ItemMeta meta = p.getItemMeta();
            if (meta != null) { meta.displayName(Component.text(" ")); p.setItemMeta(meta); }
            inv.setItem(slot, p);
        }
    }

    // ====================================================================
    // GUI clicks
    // ====================================================================

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int size = e.getInventory().getSize();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= size) return;
        int base = centreRowStart(size);
        boolean bedrock = isBedrock(p.getUniqueId());

        if (slot == base + 2) {
            boolean now = !enabled(footstepsOn, p, defaultFootsteps);
            footstepsOn.put(p.getUniqueId(), now);
            e.getInventory().setItem(slot, footstepsButton(p, bedrock));
            p.playSound(p.getLocation(), "block.note_block.hat", 0.6f, now ? 1.6f : 1.0f);
        } else if (slot == base + 3) {
            Bukkit.getScheduler().runTask(this, () -> openEffectsMenu(p));
        } else if (slot == base + 4) {
            boolean now = !enabled(hotbarOn, p, defaultHotbar);
            hotbarOn.put(p.getUniqueId(), now);
            e.getInventory().setItem(slot, hotbarButton(p, bedrock));
            if (!now) p.sendActionBar(Component.empty());
            p.playSound(p.getLocation(), "block.note_block.hat", 0.6f, now ? 1.6f : 1.0f);
        } else if (slot == base + 7 && (p.hasPermission("kawaiisparkles.admin") || p.isOp())) {
            readConfig();
            placeButtons(e.getInventory(), p, bedrock);
            p.sendMessage(msg(bedrock, "§d(✧) KawaiiSparkles reloaded ✨"));
            p.playSound(p.getLocation(), "block.amethyst_block.chime", 0.7f, 1.6f);
        }
    }

    @EventHandler
    public void onEffectsClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof EffectsHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int size = e.getInventory().getSize();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= size) return;
        if (slot == size - 1) { Bukkit.getScheduler().runTask(this, () -> openMenu(p)); return; }
        List<ActionData> shown = shownActions();
        if (slot < shown.size()) {
            ActionType type = shown.get(slot).type;
            Bukkit.getScheduler().runTask(this, () -> openPicker(p, type));
        }
    }

    @EventHandler
    public void onPickerClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PickerHolder ph)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int size = e.getInventory().getSize();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= size) return;
        boolean bedrock = isBedrock(p.getUniqueId());

        if (slot == size - 1) { Bukkit.getScheduler().runTask(this, () -> openEffectsMenu(p)); return; }

        ActionData a = actions.get(ph.type);
        if (a == null || slot >= a.options.size()) return;
        Effect picked = a.options.get(slot);
        a.selected.put(p.getUniqueId(), picked.id);
        for (int i = 0; i < a.options.size() && i < size - 1; i++) {
            Effect t = a.options.get(i);
            e.getInventory().setItem(i, optionIcon(t, t.id.equals(picked.id), bedrock, a.type.dual));
        }
        p.playSound(p.getLocation(), "block.note_block.bell", 0.7f, 1.6f);
        p.sendMessage(msg(bedrock, "§d(✧) " + a.type.label + " set to ") + picked.name);
        if (a.type != ActionType.TRAIL && !a.enabled) {
            p.sendMessage(msg(bedrock, "§7(this effect is disabled in config)"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastMoveTick.remove(id);
    }

    private static final class MenuHolder implements InventoryHolder {
        @SuppressWarnings("unused") private final UUID owner;
        private MenuHolder(UUID owner) { this.owner = owner; }
        @Override public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); }
    }

    private static final class EffectsHolder implements InventoryHolder {
        @SuppressWarnings("unused") private final UUID owner;
        private EffectsHolder(UUID owner) { this.owner = owner; }
        @Override public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); }
    }

    private static final class PickerHolder implements InventoryHolder {
        @SuppressWarnings("unused") private final UUID owner;
        private final ActionType type;
        private PickerHolder(UUID owner, ActionType type) { this.owner = owner; this.type = type; }
        @Override public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); }
    }

    // ====================================================================
    // Bedrock (Floodgate) detection + glyph sanitising
    // ====================================================================

    private static Boolean FG_PRESENT;
    private static java.lang.reflect.Method FG_IS;
    private static Object FG_API;

    static boolean isBedrock(UUID id) {
        try {
            if (FG_PRESENT == null) {
                try {
                    Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    FG_API = api.getMethod("getInstance").invoke(null);
                    FG_IS = api.getMethod("isFloodgatePlayer", UUID.class);
                    FG_PRESENT = (FG_API != null && FG_IS != null);
                } catch (Throwable t) {
                    FG_PRESENT = false;
                }
            }
            if (!FG_PRESENT) return false;
            Object r = FG_IS.invoke(FG_API, id);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    static String bedrockText(String s) {
        if (s == null) return null;
        String t = s
                .replace("✿", "*").replace("✦", "*").replace("✧", "*").replace("✨", "*")
                .replace("⟳", "~").replace("»", ">").replace("«", "<")
                .replace("→", "->").replace("←", "<-").replace("×", "x").replace("♥", "<3");
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t;
    }

    private String msg(boolean bedrock, String s) { return bedrock ? bedrockText(s) : s; }

    // ====================================================================
    // Commands
    // ====================================================================

    private boolean enabled(Map<UUID, Boolean> map, Player p, boolean def) {
        return map.getOrDefault(p.getUniqueId(), def);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!"ksparkles".equalsIgnoreCase(command.getName())) return false;
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("kawaiisparkles.admin") && !sender.isOp()) {
                    sender.sendMessage("§d(✧) you don't have permission~");
                    return true;
                }
                readConfig();
                sender.sendMessage("§d(✧) KawaiiSparkles reloaded ✨");
                return true;
            }
            case "info" -> {
                sender.sendMessage("§d(✧) KawaiiSparkles §7v" + getDescription().getVersion());
                StringBuilder sb = new StringBuilder("§7 chest=" + chestEnabled + " hotbar=" + hotbarEnabled);
                for (ActionType t : ActionType.values()) {
                    ActionData a = actions.get(t);
                    sb.append(" ").append(t.key()).append("=").append(a != null && a.enabled);
                }
                sender.sendMessage(sb.toString());
                return true;
            }
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c(✧) that has to be run by a player");
            return true;
        }
        if (!p.hasPermission("kawaiisparkles.use")) {
            p.sendMessage("§d(✧) you don't have permission~");
            return true;
        }
        boolean bedrock = isBedrock(p.getUniqueId());

        switch (sub) {
            case "menu" -> {
                if (!menuEnabled) { p.sendMessage(msg(bedrock, "§d(✧) the menu is disabled~")); return true; }
                openMenu(p);
            }
            case "effects", "fx" -> openEffectsMenu(p);
            case "trail", "trails" -> openPicker(p, ActionType.TRAIL);
            case "footsteps" -> {
                boolean now = !enabled(footstepsOn, p, defaultFootsteps);
                footstepsOn.put(p.getUniqueId(), now);
                p.sendMessage(msg(bedrock, "§d(✧) footstep trails ") + (now ? "§aON" : "§cOFF"));
            }
            case "hotbar" -> {
                boolean now = !enabled(hotbarOn, p, defaultHotbar);
                hotbarOn.put(p.getUniqueId(), now);
                p.sendMessage(msg(bedrock, "§d(✧) animated action bar ") + (now ? "§aON" : "§cOFF"));
                if (!now) p.sendActionBar(Component.empty());
            }
            default -> {
                if (menuEnabled) openMenu(p);
                else p.sendMessage(msg(bedrock, "§d(✧) /ksparkles <menu|effects|trail|footsteps|hotbar|reload|info>"));
            }
        }
        return true;
    }
}
