package com.ferisooo.kawaiicam;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * KawaiiCam — an autonomous cinematic camera.
 *
 * <p>{@code /cam follow <player>} (best from a 2nd account) or {@code /cam solo}
 * puts you into a spectator view that an AI "director" flies around the subject,
 * choosing shots on its own — orbits, cranes, dollies, low hero angles,
 * over-the-shoulder tracking — with eased motion and terrain-aware framing.
 *
 * <p>It can't write a video (a server has no renderer), but {@code /cam record}
 * logs the exact camera track to a JSON file; {@code /cam play <name>} replays
 * it so you can screen-capture a clean run to MP4.
 */
public final class KawaiiCam extends JavaPlugin implements TabCompleter, Listener {

    private final Map<UUID, CamSession> sessions = new HashMap<>();
    private File recordingsDir;

    private ShotDirector.Style defaultStyle;
    private double posSmoothing;
    private double rotSmoothing;
    private boolean collision;
    private int shotMinTicks;
    private int shotMaxTicks;
    private boolean restoreOnStop;
    private int maxRecordFrames;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        recordingsDir = new File(getDataFolder(), "recordings");
        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            getLogger().warning("(✧) couldn't create recordings folder!");
        }
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("cam");
        if (cmd != null) cmd.setTabCompleter(this);
        getLogger().info("(✧) KawaiiCam ready ~ lights, camera, action! 🎬");
    }

    @Override
    public void onDisable() {
        // Restore everyone we left in spectator and flush any open recordings.
        for (CamSession s : new ArrayList<>(sessions.values())) {
            if (s.isRecording()) trySave(s);
            s.restore(restoreOnStop);
        }
        sessions.clear();
    }

    private void readConfig() {
        reloadConfig();
        var cfg = getConfig();
        defaultStyle = parseStyle(cfg.getString("default-style", "epic"));
        posSmoothing = clamp01(cfg.getDouble("position-smoothing", 0.15));
        rotSmoothing = clamp01(cfg.getDouble("rotation-smoothing", 0.2));
        collision    = cfg.getBoolean("collision-avoidance", true);
        shotMinTicks = (int) Math.round(Math.max(1.0, cfg.getDouble("shot-min-seconds", 4)) * 20);
        shotMaxTicks = (int) Math.round(Math.max(
                cfg.getDouble("shot-min-seconds", 4), cfg.getDouble("shot-max-seconds", 8)) * 20);
        restoreOnStop = cfg.getBoolean("restore-on-stop", true);
        maxRecordFrames = (int) Math.round(Math.max(5, cfg.getInt("max-record-seconds", 120)) * 20);
    }

    // -------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("kawaiicam.admin")) { sender.sendMessage("§c(✧) no permission~"); return true; }
            readConfig();
            sender.sendMessage("§d(✧) KawaiiCam reloaded ✨");
            return true;
        }
        if (sub.equals("list")) { listRecordings(sender); return true; }

        if (!(sender instanceof Player p)) { sender.sendMessage("§c(✧) players only~"); return true; }
        if (!p.hasPermission("kawaiicam.use")) { p.sendMessage("§c(✧) no permission~"); return true; }

        switch (sub) {
            case "follow":  return cmdFollow(p, args);
            case "solo":    return cmdSolo(p);
            case "stop":    return cmdStop(p);
            case "style":   return cmdStyle(p, args);
            case "record":  return cmdRecord(p, args);
            case "play":    return cmdPlay(p, args);
            default:        return help(p);
        }
    }

    private boolean cmdFollow(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§d(✧) usage: §f/cam follow <player>"); return true; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { p.sendMessage("§c(✧) '" + args[1] + "' isn't online~"); return true; }
        if (target.equals(p)) {
            p.sendMessage("§d(✧) you can't film yourself with one account — your body "
                    + "vanishes in camera view. Use §f/cam solo §dfor your spot, or run this "
                    + "from a 2nd (spectator) account aimed at your main~");
            return true;
        }
        endExisting(p);
        CamSession s = CamSession.follow(this, p, target, newDirector());
        sessions.put(p.getUniqueId(), s);
        s.start();
        p.sendMessage("§d(✧) 🎬 now filming §f" + target.getName()
                + "§d~ §8(/cam record to capture, /cam stop to end)");
        return true;
    }

    private boolean cmdSolo(Player p) {
        endExisting(p);
        CamSession s = CamSession.solo(this, p, newDirector());
        sessions.put(p.getUniqueId(), s);
        s.start();
        p.sendMessage("§d(✧) 🎬 cinematic of your spot~ §8(/cam record to capture, /cam stop to end)");
        return true;
    }

    private boolean cmdStop(Player p) {
        CamSession s = sessions.get(p.getUniqueId());
        if (s == null) { p.sendMessage("§d(✧) you have no camera running~"); return true; }
        endSession(s, true);
        p.sendMessage("§d(✧) cut! camera stopped ✨");
        return true;
    }

    private boolean cmdStyle(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§d(✧) usage: §f/cam style <chill|action|epic>"); return true; }
        ShotDirector.Style style = parseStyle(args[1]);
        defaultStyle = style; // applies to the next started camera
        p.sendMessage("§d(✧) style set to §f" + style.name().toLowerCase()
                + "§d~ §8(applies when you start a camera)");
        return true;
    }

    private boolean cmdRecord(Player p, String[] args) {
        CamSession s = sessions.get(p.getUniqueId());
        if (s == null || s.mode() == CamSession.Mode.PLAYBACK) {
            p.sendMessage("§d(✧) start a camera first (§f/cam follow§d or §f/cam solo§d), then record~");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            if (!s.isRecording()) { p.sendMessage("§d(✧) you're not recording~"); return true; }
            finishRecording(s);
            return true;
        }
        if (s.isRecording()) {
            p.sendMessage("§d(✧) already recording → §f" + s.recordName
                    + "§d. §f/cam record stop§d to save.");
            return true;
        }
        String name = args.length >= 2 ? sanitize(args[1])
                : "take-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        s.recorder = new Recorder(p.getName(), defaultStyle.name().toLowerCase(),
                p.getWorld().getName());
        s.recordName = name;
        s.maxFrames = maxRecordFrames;
        p.sendMessage("§d(✧) 🔴 recording → §f" + name + "§d. §f/cam record stop§d to save~");
        return true;
    }

    private boolean cmdPlay(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§d(✧) usage: §f/cam play <name>"); return true; }
        File f = new File(recordingsDir, sanitize(args[1]) + ".json");
        if (!f.exists()) { p.sendMessage("§c(✧) no recording named '" + args[1] + "' — see §f/cam list"); return true; }
        Recorder.Recording rec;
        try {
            rec = Recorder.load(f);
        } catch (IOException ex) {
            p.sendMessage("§c(✧) couldn't read that recording: " + ex.getMessage());
            return true;
        }
        if (Bukkit.getWorld(rec.world) == null) {
            p.sendMessage("§c(✧) the world it was filmed in ('" + rec.world + "') isn't loaded~");
            return true;
        }
        endExisting(p);
        CamSession s = CamSession.playback(this, p, rec);
        sessions.put(p.getUniqueId(), s);
        s.start();
        p.sendMessage("§d(✧) ▶ playing §f" + args[1] + "§d (" + rec.frames.size()
                + " frames)~ hit record in OBS! 🎥");
        return true;
    }

    // -------------------------------------------------------------- sessions

    private ShotDirector newDirector() {
        return new ShotDirector(defaultStyle, posSmoothing, rotSmoothing,
                collision, shotMinTicks, shotMaxTicks);
    }

    private void endExisting(Player p) {
        CamSession s = sessions.get(p.getUniqueId());
        if (s != null) endSession(s, true);
    }

    /** Stop a session, saving any open recording and restoring the operator. */
    void endSession(CamSession s, boolean teleportBack) {
        if (s.isRecording()) trySave(s);
        // Gamemode is always restored; position only when asked AND configured to.
        s.restore(teleportBack && restoreOnStop);
        sessions.remove(s.operator());
    }

    /** Save and close an in-progress recording but keep the camera rolling. */
    void finishRecording(CamSession s) {
        File f = trySave(s);
        s.recorder = null;
        Player op = Bukkit.getPlayer(s.operator());
        if (op != null && f != null) {
            op.sendMessage("§d(✧) 💾 saved §f" + s.recordName + "§d → replay with §f/cam play "
                    + s.recordName + "§d, then capture in OBS~");
        }
        s.recordName = null;
    }

    private File trySave(CamSession s) {
        if (s.recorder == null || s.recordName == null) return null;
        File f = new File(recordingsDir, s.recordName + ".json");
        try {
            s.recorder.save(f);
            return f;
        } catch (IOException ex) {
            getLogger().warning("(✧) couldn't save recording '" + s.recordName + "': " + ex.getMessage());
            Player op = Bukkit.getPlayer(s.operator());
            if (op != null) op.sendMessage("§c(✧) couldn't save the recording: " + ex.getMessage());
            return null;
        }
    }

    private void listRecordings(CommandSender sender) {
        File[] files = recordingsDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) {
            sender.sendMessage("§d(✧) no recordings yet~ make one with §f/cam record");
            return;
        }
        sender.sendMessage("§d(✧) recordings:");
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - ".json".length());
            sender.sendMessage("  §7• §f" + name);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        // Operator left: save recording, drop the session, and put their
        // gamemode back so they don't rejoin stuck in spectator.
        CamSession own = sessions.get(id);
        if (own != null) endSession(own, false);
        // Subject left: any follow-session targeting them ends on its next tick.
    }

    // -------------------------------------------------------------- helpers

    private boolean help(Player p) {
        p.sendMessage("§d(✧) §lKawaiiCam §r§7— autonomous cinematic camera");
        p.sendMessage("  §f/cam follow <player> §7film someone (best from a 2nd account)");
        p.sendMessage("  §f/cam solo §7cinematic of your current spot");
        p.sendMessage("  §f/cam record [name] §7| §f/cam record stop §7capture the camera track");
        p.sendMessage("  §f/cam play <name> §7replay a track (record it in OBS → MP4)");
        p.sendMessage("  §f/cam style <chill|action|epic> §7• §f/cam list §7• §f/cam stop");
        return true;
    }

    private static ShotDirector.Style parseStyle(String s) {
        try {
            return ShotDirector.Style.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ShotDirector.Style.EPIC;
        }
    }

    private static String sanitize(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return s.isEmpty() ? "take" : s;
    }

    private static double clamp01(double v) {
        return Math.max(0.02, Math.min(1.0, v));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("follow", "solo", "stop", "style", "record", "play", "list", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("style")) {
            return filter(List.of("chill", "action", "epic"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("follow")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("record")) {
            return filter(List.of("stop"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            File[] files = recordingsDir.listFiles((d, n) -> n.endsWith(".json"));
            List<String> names = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    names.add(f.getName().substring(0, f.getName().length() - ".json".length()));
                }
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String pre = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(pre)) out.add(o);
        return out;
    }
}
