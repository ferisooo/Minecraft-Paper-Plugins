package com.ferisooo.kawaiiscoreboard;

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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player sidebar with the basics: online count, current world,
 * X/Y/Z, and Java vs Bedrock.
 *
 * <p>State lives in two maps: {@link #boards} for the visible sidebars,
 * {@link #suppressed} for players who explicitly /ksb off so we don't
 * re-attach a sidebar on the next tick.
 */
public final class KawaiiScoreboard extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('§').build();

    /** Guards against a clock jump (e.g. NTP correction, server hibernate)
     *  dumping a giant delta into one world's total. 10 minutes. */
    private static final long MAX_SAMPLE_DELTA_MS = 10L * 60L * 1000L;

    // KawaiiQuests mirrors the active quest into the player's PDC under its own
    // namespace ("kawaiiquests"); we read it to show the quest on the sidebar.
    // No hard dependency: if KawaiiQuests isn't installed, these are just absent.
    private static final NamespacedKey QUEST_TITLE_KEY =
            new NamespacedKey("kawaiiquests", "quest_title");
    private static final NamespacedKey QUEST_PROGRESS_KEY =
            new NamespacedKey("kawaiiquests", "quest_progress");
    // KawaiiSeasons mirrors the current season onto the player's PDC too.
    private static final NamespacedKey SEASON_KEY =
            new NamespacedKey("kawaiiseasons", "season");

    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private final Set<UUID> suppressed = new HashSet<>();

    // Per-player, per-world accumulated real playtime in milliseconds, plus
    // the timestamp of the last sample so we can attribute elapsed time to the
    // world the player was actually in.
    private final Map<UUID, Map<String, Long>> playtime = new HashMap<>();
    private final Map<UUID, Long> lastSample = new HashMap<>();
    private File playtimeFile;

    private ScheduledTask tickTask;
    private ScheduledTask saveTask;
    private long    updateTicks;
    private boolean showOnJoin;
    private boolean showCoords;
    private boolean showPlaytime;
    private boolean showQuest;
    private boolean showSeason;
    private boolean clearBelowNameHealth;
    private Component title;
    private String  titleText;     // plain text of the title, for the gradient
    private boolean animatedTitle;
    private int     titleFrame;    // advances each tick cycle to sweep the gradient

    // Pink → purple → white shimmer stops the title sweeps through (packed RGB).
    private static final int[] TITLE_STOPS = {0xFF8AD8, 0xC56BFF, 0xFFFFFF};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        clearMainBelowNameHealth(); // strip leftover mob-health-through-walls display
        playtimeFile = new File(getDataFolder(), "playtime.yml");
        loadPlaytime();
        getServer().getPluginManager().registerEvents(this, this);
        scheduleTickTask();
        // Start counting for anyone already online (e.g. /reload).
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) lastSample.put(p.getUniqueId(), now);
        if (showOnJoin) {
            for (Player p : Bukkit.getOnlinePlayers()) attach(p);
        }
        getLogger().info("(✧) KawaiiScoreboard ready ~");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        // Flush any pending playtime for everyone still online, then persist.
        for (Player p : Bukkit.getOnlinePlayers()) accrue(p, p.getWorld().getName());
        savePlaytime();
        for (PlayerBoard b : boards.values()) b.detach();
        boards.clear();
        suppressed.clear();
    }

    private void readConfig() {
        reloadConfig();
        updateTicks  = Math.max(1L, getConfig().getLong("update-ticks", 10L));
        showOnJoin   = getConfig().getBoolean("show-on-join",  true);
        showCoords   = getConfig().getBoolean("show-coords",   true);
        showPlaytime = getConfig().getBoolean("show-playtime", true);
        showQuest    = getConfig().getBoolean("show-quest",    true);
        showSeason   = getConfig().getBoolean("show-season",   true);
        clearBelowNameHealth = getConfig().getBoolean("clear-belowname-health", true);
        animatedTitle = getConfig().getBoolean("animated-title", true);
        String rawTitle = getConfig().getString("title", "§d§l✿ Kawaii ✿");
        title        = LEGACY.deserialize(rawTitle).decoration(TextDecoration.ITALIC, false);
        titleText    = stripLegacy(rawTitle);
    }

    /** Drop legacy color/format codes ('§x' or '&x') to get plain title text. */
    private static String stripLegacy(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    private void scheduleTickTask() {
        if (tickTask != null) tickTask.cancel();
        // Folia-safe: a global-region repeating driver does the shared per-cycle
        // work (title frame, leftover-objective sweep, building the gradient
        // title) and reads the online-player collection, then hops each player's
        // own work onto THAT player's entity scheduler — scoreboard/location
        // touches must only happen on the player's region thread.
        tickTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,
                task -> tickAll(), Math.max(1L, updateTicks), Math.max(1L, updateTicks));
        // Periodic autosave (every 60s) so a crash doesn't lose much progress.
        // Reads global state and writes a file — global-region scheduler.
        if (saveTask != null) saveTask.cancel();
        saveTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,
                task -> savePlaytime(), 20L * 60L, 20L * 60L);
    }

    private void tickAll() {
        if (animatedTitle) titleFrame++;
        // Periodically strip any leftover health objective from the main
        // scoreboard's below-name slot — that's what shows mob health through
        // walls when a player is on the main board. Throttled to ~5s.
        if ((titleFrame & 0xFF) == 0) clearMainBelowNameHealth();
        // Build the animated title ONCE per cycle and share it across all
        // players: the gradient for a given frame is identical for everyone,
        // so there's no need to recompute (allocating a Component per title
        // character) once per player.
        Component frameTitle = animatedTitle ? gradientTitle(titleText, titleFrame) : null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Each player's work touches that player (scoreboard, location,
            // playtime sample) — hop it onto the player's own region thread.
            p.getScheduler().run(this, t -> tickPlayer(p, frameTitle), null);
        }
    }

    private void tickPlayer(Player p, Component frameTitle) {
        // Accrue playtime for everyone online, even if their sidebar is
        // hidden, so the per-world totals stay accurate.
        accrue(p, p.getWorld().getName());
        PlayerBoard b = boards.get(p.getUniqueId());
        if (b == null) {
            // Self-heal: a player who should have the sidebar but doesn't
            // (e.g. another plugin reset their scoreboard, or attach was
            // missed) gets it (re)attached. Respects the per-player toggle.
            if (showOnJoin && !suppressed.contains(p.getUniqueId())) attach(p);
            return;
        }
        // The quest row appears/disappears as the player's quest state
        // changes, so the row count can shift — rebuild when it does.
        if (b.rowCount() != rowsFor(p)) {
            attach(p);
        } else {
            // Re-assert our board if something swapped the player back to
            // the main scoreboard (which is what hid the sidebar).
            if (p.getScoreboard() != b.scoreboard()) b.show();
            refresh(p, b, frameTitle);
        }
    }

    /**
     * Remove a {@code health}-criteria objective from the main scoreboard's
     * BELOW_NAME slot. Such an objective (often left behind in scoreboard.dat
     * by a removed plugin or an old {@code /scoreboard} command) renders every
     * entity's health under its name — visible through walls. We only clear the
     * display slot, we don't delete anyone's objective.
     */
    private void clearMainBelowNameHealth() {
        if (!clearBelowNameHealth) return;
        try {
            org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            if (main.getObjective(org.bukkit.scoreboard.DisplaySlot.BELOW_NAME) != null) {
                main.clearSlot(org.bukkit.scoreboard.DisplaySlot.BELOW_NAME);
            }
        } catch (Throwable ignored) { }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        lastSample.put(p.getUniqueId(), System.currentTimeMillis());
        if (showOnJoin && !suppressed.contains(p.getUniqueId())) attach(p);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        // Bank the time spent in the world the player just left BEFORE we start
        // counting against the new one, so each world gets credited correctly.
        accrue(e.getPlayer(), e.getFrom().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        accrue(p, p.getWorld().getName());
        lastSample.remove(id);
        // The quitting player's playtime is now captured in the in-memory map
        // (accrue above). Persist it off the main thread instead of doing a
        // synchronous full-map YAML write on every quit. The 60s autosave and
        // the onDisable flush still guard against data loss.
        savePlaytimeAsync();
        PlayerBoard b = boards.remove(id);
        if (b != null) b.detach();
        // suppressed is intentionally NOT cleared on quit — preference
        // sticks until the player re-toggles or the plugin reloads.
    }

    // ============== playtime tracking ==============

    /**
     * Credit the time elapsed since this player's last sample to {@code worldName},
     * then reset the sample clock. Reading the world name explicitly (rather than
     * always {@code p.getWorld()}) lets {@link #onChangedWorld} attribute the
     * final slice to the world being left.
     */
    private void accrue(Player p, String worldName) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSample.get(id);
        if (last != null) {
            long delta = now - last;
            if (delta > 0 && delta <= MAX_SAMPLE_DELTA_MS) {
                playtime.computeIfAbsent(id, k -> new HashMap<>())
                        .merge(worldName, delta, Long::sum);
            }
        }
        lastSample.put(id, now);
    }

    /** Live total (stored + un-banked slice) for the player's current world, in ms. */
    private long currentWorldPlaytimeMs(Player p) {
        UUID id = p.getUniqueId();
        String worldName = p.getWorld().getName();
        long stored = playtime.getOrDefault(id, Collections.emptyMap())
                .getOrDefault(worldName, 0L);
        Long last = lastSample.get(id);
        if (last != null) {
            long delta = System.currentTimeMillis() - last;
            if (delta > 0 && delta <= MAX_SAMPLE_DELTA_MS) stored += delta;
        }
        return stored;
    }

    private void loadPlaytime() {
        playtime.clear();
        if (playtimeFile == null || !playtimeFile.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(playtimeFile);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) return;
        for (String idStr : players.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(idStr); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection worlds = players.getConfigurationSection(idStr);
            if (worlds == null) continue;
            Map<String, Long> byWorld = new HashMap<>();
            for (String w : worlds.getKeys(false)) {
                byWorld.put(w, worlds.getLong(w, 0L));
            }
            if (!byWorld.isEmpty()) playtime.put(id, byWorld);
        }
    }

    /** Synchronous full-map write. Used by the 60s autosave and onDisable flush. */
    private void savePlaytime() {
        if (playtimeFile == null) return;
        writeSnapshot(snapshotPlaytime());
    }

    /**
     * Snapshot the playtime map on the (current) main thread, then perform the
     * file write off-thread. Used on quit so a synchronous YAML write doesn't
     * stall the server tick on every disconnect.
     */
    private void savePlaytimeAsync() {
        if (playtimeFile == null) return;
        final Map<UUID, Map<String, Long>> snapshot = snapshotPlaytime();
        try {
            Bukkit.getAsyncScheduler().runNow(this, t -> writeSnapshot(snapshot));
        } catch (IllegalStateException ex) {
            // Scheduler refuses new async tasks during shutdown — write inline so
            // we never drop the data.
            writeSnapshot(snapshot);
        }
    }

    /** Deep copy of the in-memory playtime map, safe to hand to an async task. */
    private Map<UUID, Map<String, Long>> snapshotPlaytime() {
        Map<UUID, Map<String, Long>> copy = new HashMap<>(playtime.size());
        for (Map.Entry<UUID, Map<String, Long>> e : playtime.entrySet()) {
            copy.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return copy;
    }

    /** Serialize and write a playtime snapshot to disk. Thread-safe (no shared state). */
    private void writeSnapshot(Map<UUID, Map<String, Long>> snapshot) {
        if (playtimeFile == null) return;
        FileConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Long>> e : snapshot.entrySet()) {
            for (Map.Entry<String, Long> w : e.getValue().entrySet()) {
                cfg.set("players." + e.getKey() + "." + w.getKey(), w.getValue());
            }
        }
        try {
            cfg.save(playtimeFile);
        } catch (IOException ex) {
            getLogger().warning("(✧) couldn't save playtime.yml: " + ex.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!"kscoreboard".equalsIgnoreCase(command.getName())) return false;
        if (!sender.hasPermission("kawaiiscoreboard.use")) {
            sender.sendMessage("§d(✧) you don't have permission~");
            return true;
        }
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                if (!sender.hasPermission("kawaiiscoreboard.admin") && !sender.isOp()) {
                    sender.sendMessage("§d(✧) you don't have permission~");
                    return true;
                }
                readConfig();
                scheduleTickTask();
                // Rebuild visible boards so row-layout changes (coords/playtime
                // toggles, new title) take effect immediately.
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (boards.containsKey(viewer.getUniqueId())) attach(viewer);
                }
                sender.sendMessage("§d(✧) KawaiiScoreboard config reloaded ✨");
                return true;
            case "on":
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§c(✧) /ksb on must be run by a player");
                    return true;
                }
                suppressed.remove(p.getUniqueId());
                attach(p);
                p.sendMessage("§d(✧) sidebar on ✨");
                return true;
            case "off":
                if (!(sender instanceof Player p2)) {
                    sender.sendMessage("§c(✧) /ksb off must be run by a player");
                    return true;
                }
                suppressed.add(p2.getUniqueId());
                detach(p2);
                p2.sendMessage("§d(✧) sidebar off ~");
                return true;
            case "toggle":
                if (!(sender instanceof Player p3)) {
                    sender.sendMessage("§c(✧) /ksb toggle must be run by a player");
                    return true;
                }
                if (boards.containsKey(p3.getUniqueId())) {
                    suppressed.add(p3.getUniqueId());
                    detach(p3);
                    p3.sendMessage("§d(✧) sidebar off ~");
                } else {
                    suppressed.remove(p3.getUniqueId());
                    attach(p3);
                    p3.sendMessage("§d(✧) sidebar on ✨");
                }
                return true;
            default:
                sender.sendMessage("§d(✧) /kscoreboard <on|off|toggle|reload>");
                return true;
        }
    }

    /** Friendly label for a world name — hides the long per-player skyblock folder. */
    private static String prettyWorld(String worldName) {
        if (worldName != null && worldName.startsWith("kawaii_isle_")) return "Your Skyblock";
        return worldName;
    }

    // ============== sidebar lifecycle ==============

    /**
     * Number of sidebar rows for this player: Players + World (+coords)(+playtime)
     * (+quest title & progress, when they have an active KawaiiQuest) + Edition.
     */
    private int rowsFor(Player p) {
        int rows = 2;                 // Players, World
        if (showCoords)   rows += 3;  // X, Y, Z
        if (showPlaytime) rows += 1;  // per-world playtime
        if (hasSeason(p)) rows += 1;  // Season
        if (hasQuest(p))  rows += 2;  // Quest title + progress
        rows += 1;                    // Edition
        return rows;
    }

    private void attach(Player p) {
        PlayerBoard b = boards.get(p.getUniqueId());
        int wanted = rowsFor(p);
        if (b != null && b.rowCount() != wanted) {
            // Layout changed (e.g. after a config reload) — rebuild from scratch.
            b.detach();
            boards.remove(p.getUniqueId());
            b = null;
        }
        if (b == null) {
            b = new PlayerBoard(p, title, wanted);
            boards.put(p.getUniqueId(), b);
        } else {
            b.title(title);
        }
        b.show();
        refresh(p, b, animatedTitle ? gradientTitle(titleText, titleFrame) : null);
    }

    private void detach(Player p) {
        PlayerBoard b = boards.remove(p.getUniqueId());
        if (b != null) b.detach();
    }

    private void refresh(Player p, PlayerBoard b, Component frameTitle) {
        int onlineNow = Bukkit.getOnlinePlayers().size();
        int onlineMax = Bukkit.getMaxPlayers();
        Location loc = p.getLocation();
        String worldName = p.getWorld().getName();
        boolean bedrock = isBedrock(p);

        // frameTitle is the shared animated title for this cycle (null when the
        // animation is off); PlayerBoard.title() no-ops if it's unchanged.
        if (frameTitle != null) b.title(frameTitle);

        int row = 0;
        b.setRow(row++, label("Players: ").append(value(onlineNow + "§7/§f" + onlineMax)));
        b.setRow(row++, label("World: ").append(value(prettyWorld(worldName), NamedTextColor.GREEN)));
        if (showCoords && row + 3 <= b.rowCount()) {
            b.setRow(row++, label("X: ").append(value(coord(loc.getX()))));
            b.setRow(row++, label("Y: ").append(value(coord(loc.getY()))));
            b.setRow(row++, label("Z: ").append(value(coord(loc.getZ()))));
        }
        if (showPlaytime && row + 1 <= b.rowCount()) {
            // Real time spent in THIS world (each world is tracked separately).
            b.setRow(row++, label("Time here: ")
                    .append(value(formatPlaytime(currentWorldPlaytimeMs(p)), NamedTextColor.LIGHT_PURPLE)));
        }
        if (hasSeason(p) && row + 1 <= b.rowCount()) {
            String season = questString(p, SEASON_KEY, "—");
            b.setRow(row++, label("Season: ").append(value(season, seasonColor(season))));
        }
        if (hasQuest(p) && row + 2 <= b.rowCount()) {
            String questTitle = questString(p, QUEST_TITLE_KEY, "Quest");
            String questProgress = questString(p, QUEST_PROGRESS_KEY, "0/0");
            b.setRow(row++, label("Quest: ")
                    .append(value(questTitle, NamedTextColor.LIGHT_PURPLE)));
            b.setRow(row++, label("Goal: ").append(questGoal(questProgress)));
        }
        b.setRow(row++, label("Edition: ").append(bedrock
                ? Component.text("Bedrock", NamedTextColor.AQUA)
                : Component.text("Java",    NamedTextColor.YELLOW)));
        // Wipe any leftover rows (e.g. after a config reload trimmed coords).
        while (row < b.rowCount()) b.setRow(row++, Component.empty());
    }

    // ============== KawaiiQuests bridge ==============

    /** True if quest display is enabled and this player has an active quest. */
    private boolean hasQuest(Player p) {
        return showQuest && p.getPersistentDataContainer()
                .has(QUEST_TITLE_KEY, PersistentDataType.STRING);
    }

    /** Read a KawaiiQuests PDC string off the player, or {@code def} if absent. */
    private static String questString(Player p, NamespacedKey key, String def) {
        String v = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** True if season display is enabled and KawaiiSeasons set one on this player. */
    private boolean hasSeason(Player p) {
        return showSeason && p.getPersistentDataContainer()
                .has(SEASON_KEY, PersistentDataType.STRING);
    }

    private static NamedTextColor seasonColor(String season) {
        return switch (season.toLowerCase(Locale.ROOT)) {
            case "spring" -> NamedTextColor.GREEN;
            case "summer" -> NamedTextColor.YELLOW;
            case "autumn" -> NamedTextColor.GOLD;
            case "winter" -> NamedTextColor.AQUA;
            default -> NamedTextColor.LIGHT_PURPLE;
        };
    }

    /** "5/8" → the numbers plus a compact dark-green→lime gradient bar. */
    private static Component questGoal(String prog) {
        int p = 0, a = 0;
        int slash = prog.indexOf('/');
        if (slash > 0) {
            try {
                p = Integer.parseInt(prog.substring(0, slash).trim());
                a = Integer.parseInt(prog.substring(slash + 1).trim());
            } catch (NumberFormatException ignored) { /* leave 0/0 */ }
        }
        int slots = 10;
        int filled = a <= 0 ? slots : Math.max(0, Math.min(slots, Math.round((float) p / a * slots)));
        var bar = Component.text();
        for (int i = 0; i < slots; i++) {
            if (i < filled) {
                double t = slots <= 1 ? 0 : (double) i / (slots - 1);
                bar.append(Component.text("▰").color(lerp(0x1E7F3C, 0x7CFF6B, t)));
            } else {
                bar.append(Component.text("▱", NamedTextColor.DARK_GRAY));
            }
        }
        return value(prog).append(Component.text(" ")).append(
                bar.build().decoration(TextDecoration.ITALIC, false));
    }

    // ============== gradient helpers ==============

    /** Build the title with a pink→purple→white shimmer that sweeps with {@code frame}. */
    private static Component gradientTitle(String text, int frame) {
        var b = Component.text();
        int span = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') { b.append(Component.text(" ")); continue; }
            double phase = (double) i / span + frame * 0.06;
            b.append(Component.text(String.valueOf(ch)).color(stopColor(phase)));
        }
        return b.build().decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    /** Color at a position around the looping ring of {@link #TITLE_STOPS}. */
    private static TextColor stopColor(double phase) {
        double p = phase - Math.floor(phase);          // 0..1
        int n = TITLE_STOPS.length;
        double scaled = p * n;
        int idx = (int) Math.floor(scaled) % n;
        double f = scaled - Math.floor(scaled);
        return lerp(TITLE_STOPS[idx], TITLE_STOPS[(idx + 1) % n], f);
    }

    /** Linear RGB interpolation between two packed 0xRRGGBB colors. */
    private static TextColor lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) Math.round(((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) Math.round((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return TextColor.color(r, g, bl);
    }

    // ============== formatting helpers ==============

    private static Component label(String s) {
        return Component.text(s, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component value(String s) {
        return Component.text(s, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component value(String s, NamedTextColor color) {
        return Component.text(s, color)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Coords as integers (block-aligned). Math.floor handles negatives like the F3 menu does. */
    private static String coord(double v) {
        return Long.toString((long) Math.floor(v));
    }

    /** Render milliseconds as a compact "Xd Yh Zm" string (days/hours/minutes). */
    private static String formatPlaytime(long millis) {
        long totalMinutes = millis / 60_000L;
        long days  = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long mins  = totalMinutes % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(hours).append("h ");
        sb.append(mins).append("m");
        return sb.toString();
    }

    /**
     * Detects Bedrock players coming through Geyser/Floodgate without
     * needing Floodgate as a hard dependency:
     * <ul>
     *   <li>Floodgate-issued UUIDs have all zeros in the most-significant
     *       64 bits ({@code 00000000-0000-0000-XXXX-XXXXXXXXXXXX}).</li>
     *   <li>If Floodgate's "prefix linked players" option is on, names
     *       arrive with a leading dot — match that as a fallback.</li>
     * </ul>
     */
    private static boolean isBedrock(Player p) {
        if (p.getUniqueId().getMostSignificantBits() == 0L) return true;
        String name = p.getName();
        return name != null && name.startsWith(".");
    }
}
