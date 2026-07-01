package com.ferisooo.kawaiiblockhp;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows a per-player "HP" bar ABOVE THE HOTBAR (the action bar) while a block
 * is being broken. The bar drains from full to empty as the block's break
 * progress climbs to 100%, recoloring green -> yellow -> red on the way down
 * so you can see, at a glance, how close the block is to popping.
 *
 * <p>Vanilla/Paper give us three hooks: {@link BlockDamageEvent} fires once
 * when a player starts digging a block, {@link BlockDamageAbortEvent} when
 * they stop early, and {@link BlockBreakEvent} when it finally breaks. There
 * is no per-tick "still digging" event, so once digging starts we run our own
 * 1-tick task that re-reads {@link Block#getBreakSpeed(Player)} every tick
 * (it changes with tool, haste, water, being on the ground, etc.) and
 * accumulates progress exactly the way the client does. Each tick we re-send
 * the action bar so it stays on screen the whole time.
 */
public final class KawaiiBlockHP extends JavaPlugin implements Listener {

    /** Renders &-color codes (and § too) the way the rest of the suite does. */
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    /** Active digging session for one player. */
    private static final class Session {
        Location block;       // the block currently being mined
        double progress;      // 0.0 -> 1.0 (1.0 == broken)
        int idleTicks;        // ticks since progress last advanced
        boolean broke;        // set when BlockBreakEvent fires, for a clean finish
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>(); // players who /kblockhp off

    // "Still mining" heartbeat. getBreakSpeed() only says how fast a player
    // COULD break a block, not whether they're actually digging it — so on its
    // own the bar drains even when they've stopped. While a player genuinely
    // mines, the client streams arm-swing animations; we only advance progress
    // when they've swung within SWING_GRACE ticks.
    private long tickClock = 0L;
    private static final long SWING_GRACE = 8L;
    private final Map<UUID, Long> lastSwing = new HashMap<>();

    // Config-backed knobs.
    private String titleTemplate;
    private int barLength;
    private char filledChar;
    private char emptyChar;
    private boolean colorByHealth;
    private double yellowThreshold;
    private double redThreshold;
    private int lingerTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);

        // Drive every active dig session once per tick.
        new BukkitRunnable() {
            @Override public void run() { tickAll(); }
        }.runTaskTimer(this, 1L, 1L);

        getLogger().info("KawaiiBlockHP enabled - block HP bars show above the hotbar.");
    }

    @Override
    public void onDisable() {
        sessions.clear();
    }

    private void loadConfigValues() {
        reloadConfig();
        titleTemplate = getConfig().getString("title", "&f{block} {bar} &a{percent}%");
        barLength = Math.max(1, getConfig().getInt("bar-length", 20));
        String f = getConfig().getString("filled-char", "|");
        String em = getConfig().getString("empty-char", "|");
        filledChar = f.isEmpty() ? '|' : f.charAt(0);
        emptyChar = em.isEmpty() ? '|' : em.charAt(0);
        colorByHealth = getConfig().getBoolean("color-by-health", true);
        yellowThreshold = getConfig().getDouble("yellow-threshold", 0.5);
        redThreshold = getConfig().getDouble("red-threshold", 0.2);
        lingerTicks = Math.max(1, getConfig().getInt("linger-ticks", 8));
    }

    // ---------------------------------------------------------------- events

    @EventHandler(ignoreCancelled = true)
    public void onStartBreak(BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (disabled.contains(p.getUniqueId())) return;
        if (!p.hasPermission("kawaiiblockhp.use")) return;
        // Instant-break blocks (tall grass, the last hit of a fast block) pop
        // immediately — a bar would flicker in and out for one frame, so skip.
        if (e.getInstaBreak()) {
            clear(p.getUniqueId());
            return;
        }
        Block b = e.getBlock();
        double speed = safeBreakSpeed(b, p);
        // speed <= 0 means the block is effectively unbreakable for this player
        // (e.g. wrong tool with a "requires tool" gamerule). Don't show a bar.
        if (speed <= 0.0) {
            clear(p.getUniqueId());
            return;
        }

        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            s = new Session();
            sessions.put(p.getUniqueId(), s);
        }
        // Starting (or restarting) on a block resets progress.
        s.block = b.getLocation();
        s.progress = 0.0;
        s.idleTicks = 0;
        s.broke = false;
        render(p, s);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAbort(BlockDamageAbortEvent e) {
        // Let it linger a moment instead of yanking it instantly — feels less
        // jittery when a player taps and re-taps. tickAll() expires it.
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.idleTicks = lingerTicks; // expire on next tick
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        // Only finish the session if THIS block is the one it tracks — an
        // instant-break block popped mid-linger would otherwise blank a bar
        // that belongs to a different block.
        if (s.block == null || !s.block.equals(e.getBlock().getLocation())) return;
        // Show a final, empty bar for one frame then clear.
        s.progress = 1.0;
        s.broke = true;
        render(e.getPlayer(), s);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer().getUniqueId());
    }

    // ----------------------------------------------------------------- ticks

    private void tickAll() {
        tickClock++;
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> en = it.next();
            UUID id = en.getKey();
            Player p = Bukkit.getPlayer(id);
            Session s = en.getValue();
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            if (s.broke) {
                // Block already broke last tick; nothing more to draw.
                it.remove();
                continue;
            }
            if (s.block == null) {
                it.remove();
                continue;
            }

            Block b = s.block.getBlock();
            double speed = safeBreakSpeed(b, p);
            boolean swinging = (tickClock - lastSwing.getOrDefault(id, -999L)) <= SWING_GRACE;
            // Only advance while she's actually mining (recent arm-swing) and
            // not already at full progress. Otherwise count toward expiry —
            // this kills both the "drains while not hitting" and the
            // "stuck at 0%" bugs (which came from idleTicks resetting forever
            // once progress saturated but the block never actually broke).
            if (speed > 0.0 && swinging && s.progress < 1.0) {
                s.progress = Math.min(1.0, s.progress + speed);
                s.idleTicks = 0;
            } else {
                s.idleTicks++;
            }

            if (s.idleTicks >= lingerTicks) {
                it.remove();
                continue;
            }
            render(p, s);
        }
    }

    /** Treat any arm-swing as a "still mining" heartbeat for the dig tracker. */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        lastSwing.put(event.getPlayer().getUniqueId(), tickClock);
    }

    /**
     * Block break progress per tick for this player, guarded so an older
     * server without {@link Block#getBreakSpeed(Player)} (or a quirky block)
     * can never throw and kill the tick loop.
     */
    private double safeBreakSpeed(Block b, Player p) {
        try {
            return b.getBreakSpeed(p);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    // ---------------------------------------------------------------- render

    private void render(Player p, Session s) {
        double remaining = Math.max(0.0, Math.min(1.0, 1.0 - s.progress));
        String txt = buildBarText(s.block, remaining);
        if (isBedrock(p.getUniqueId())) txt = bedrockText(txt);
        p.sendActionBar(LEGACY.deserialize(txt));
    }

    /** Assemble the full action-bar line, with &-color codes baked in. */
    private String buildBarText(Location loc, double remaining) {
        String blockName = prettyName(loc);
        int pct = (int) Math.round(remaining * 100.0);
        String bar = buildBar(remaining);
        return titleTemplate
                .replace("{block}", blockName)
                .replace("{bar}", bar)
                .replace("{percent}", Integer.toString(pct));
    }

    /** A coloured "|||||·····" style bar: filled part by health colour, rest gray. */
    private String buildBar(double remaining) {
        int filled = (int) Math.round(remaining * barLength);
        if (filled < 0) filled = 0;
        if (filled > barLength) filled = barLength;
        String fill;
        if (!colorByHealth) fill = "&a";
        else if (remaining > yellowThreshold) fill = "&a";
        else if (remaining > redThreshold) fill = "&e";
        else fill = "&c";
        StringBuilder sb = new StringBuilder();
        sb.append(fill);
        for (int i = 0; i < filled; i++) sb.append(filledChar);
        sb.append("&8");
        for (int i = filled; i < barLength; i++) sb.append(emptyChar);
        return sb.toString();
    }

    private String prettyName(Location loc) {
        if (loc == null) return "Block";
        String key = loc.getBlock().getType().name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder(key.length());
        boolean cap = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ' ') { cap = true; sb.append(c); continue; }
            sb.append(cap ? Character.toUpperCase(c) : c);
            cap = false;
        }
        return sb.toString();
    }

    private void clear(UUID id) {
        sessions.remove(id);
        lastSwing.remove(id);
    }

    // ------------------------------------------------------------ bedrock

    // Detect Bedrock (Geyser) players via the Floodgate API by reflection, so
    // we never hard-depend on Floodgate being installed. Cached after the
    // first lookup. When Floodgate isn't present everyone is treated as Java.
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

    /** Replace glyphs Bedrock's font can't render with plain ASCII look-alikes. */
    static String bedrockText(String s) {
        if (s == null) return null;
        String t = s
                .replace("✿", "*").replace("✦", "*").replace("✧", "*").replace("✈", ">")
                .replace("✚", "+").replace("▶", ">").replace("◀", "<").replace("↩", "<")
                .replace("→", "->").replace("←", "<-").replace("━", "-").replace("─", "-")
                .replace("×", "x").replace("♥", "<3")
                .replace("🏠", "").replace("🛏", "").replace("⚙", "").replace("🗑", "")
                .replace("🏝", "").replace("✨", "").replace("💧", "");
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.trim();
    }

    private void send(CommandSender to, String msg) {
        if (to instanceof Player p && isBedrock(p.getUniqueId())) msg = bedrockText(msg);
        to.sendMessage(msg);
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "toggle";
        if (sub.equals("reload")) {
            if (!sender.hasPermission("kawaiiblockhp.admin")) {
                send(sender, ChatColor.RED + "You can't reload KawaiiBlockHP.");
                return true;
            }
            loadConfigValues();
            send(sender, ChatColor.LIGHT_PURPLE + "✿ KawaiiBlockHP config reloaded.");
            return true;
        }
        if (!(sender instanceof Player)) {
            send(sender, "Only players can toggle the block HP bar.");
            return true;
        }
        Player p = (Player) sender;
        UUID id = p.getUniqueId();
        boolean off;
        switch (sub) {
            case "on":  off = false; break;
            case "off": off = true;  break;
            default:    off = !disabled.contains(id); break; // toggle
        }
        if (off) {
            disabled.add(id);
            clear(id);
            send(p, ChatColor.LIGHT_PURPLE + "✿ Block HP bar hidden.");
        } else {
            disabled.remove(id);
            send(p, ChatColor.LIGHT_PURPLE + "✿ Block HP bar shown.");
        }
        return true;
    }
}
