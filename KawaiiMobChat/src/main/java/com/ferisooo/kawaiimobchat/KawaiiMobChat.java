package com.ferisooo.kawaiimobchat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class KawaiiMobChat extends JavaPlugin implements Listener {

    private boolean enabled;
    private String endpoint;
    private String apiKey;
    private String model;
    private int maxTokens;
    private double temperature;
    private int rangeBlocks;
    private long cooldownMs;
    private int maxReplyLength;
    private boolean chatBubbles;
    private boolean chatBroadcast;
    private int bubbleDurationSeconds;
    private boolean memoryEnabled;
    private int memoryMaxTurns;
    private long memoryIdleMs;
    private boolean soundsEnabled;
    private boolean reputationEnabled;
    private boolean banterEnabled;
    private double banterChance;
    private double banterRadius;
    private long banterCooldownMs;

    private DeepSeekClient client;

    // per-player cooldown timestamps (ms)
    private final ConcurrentHashMap<UUID, Long> lastChatAt = new ConcurrentHashMap<>();
    // players with an in-flight API call (so we don't fire concurrent calls per player)
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    // per-mob conversation history
    private final ConcurrentHashMap<UUID, MobMemory> memories = new ConcurrentHashMap<>();
    // player UUID -> (entity-type-name -> kill count)
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Integer>> killCounts = new ConcurrentHashMap<>();
    // mob UUID -> last banter timestamp (ms)
    private final ConcurrentHashMap<UUID, Long> banterCooldown = new ConcurrentHashMap<>();
    private File reputationFile;

    private static final class MobMemory {
        final Deque<DeepSeekClient.Message> turns = new ArrayDeque<>();
        volatile long lastTouchedMs = System.currentTimeMillis();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadCfg();
        loadReputation();
        getServer().getPluginManager().registerEvents(this, this);
        // Memory sweeper: every minute, drop stale or despawned-mob entries.
        Bukkit.getScheduler().runTaskTimer(this, this::sweepMemories, 20L * 60L, 20L * 60L);
        // Reputation autosave: every 5 minutes, async, so a server crash
        // doesn't lose recent kill data (onDisable doesn't run on crash).
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveReputation,
                20L * 60L * 5L, 20L * 60L * 5L);
        getLogger().info("(\u2727) KawaiiMobChat ready ~ enabled=" + enabled
                + ", model=" + model + ", range=" + rangeBlocks
                + ", memory=" + memoryEnabled
                + ", reputation=" + reputationEnabled
                + ", banter=" + banterEnabled);
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        saveReputation();
        lastChatAt.clear();
        inFlight.clear();
        memories.clear();
        banterCooldown.clear();
    }

    private void reloadCfg() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        endpoint = getConfig().getString("endpoint", "https://api.deepseek.com/chat/completions");
        apiKey = getConfig().getString("api-key", "");
        model = getConfig().getString("model", "deepseek-v4-flash");
        maxTokens = getConfig().getInt("max-tokens", 200);
        temperature = getConfig().getDouble("temperature", 0.9);
        rangeBlocks = Math.max(1, Math.min(64, getConfig().getInt("range-blocks", 12)));
        long cdSecs = getConfig().getLong("cooldown-seconds", 5L);
        cooldownMs = Math.max(0, cdSecs * 1000L);
        maxReplyLength = Math.max(20, Math.min(500, getConfig().getInt("max-reply-length", 200)));
        chatBubbles = getConfig().getBoolean("chat-bubbles", true);
        chatBroadcast = getConfig().getBoolean("chat-broadcast", false);
        bubbleDurationSeconds = Math.max(1, Math.min(30, getConfig().getInt("bubble-duration-seconds", 6)));
        memoryEnabled = getConfig().getBoolean("memory-enabled", true);
        memoryMaxTurns = Math.max(0, Math.min(20, getConfig().getInt("memory-max-turns", 4)));
        long idleMin = Math.max(1L, Math.min(120L, getConfig().getLong("memory-idle-minutes", 10L)));
        memoryIdleMs = idleMin * 60_000L;
        soundsEnabled = getConfig().getBoolean("sounds-enabled", true);
        reputationEnabled = getConfig().getBoolean("reputation-enabled", true);
        banterEnabled = getConfig().getBoolean("banter-enabled", true);
        banterChance = Math.max(0.0, Math.min(1.0, getConfig().getDouble("banter-chance", 0.25)));
        banterRadius = Math.max(2.0, Math.min(32.0, getConfig().getDouble("banter-radius", 8.0)));
        long banterCdSec = Math.max(5L, Math.min(600L, getConfig().getLong("banter-cooldown-seconds", 60L)));
        banterCooldownMs = banterCdSec * 1000L;

        if (apiKey == null || apiKey.isEmpty() || apiKey.contains("PASTE")) {
            getLogger().warning("(\u2727) no API key set! edit plugins/KawaiiMobChat/config.yml then /reload");
            client = null;
        } else {
            client = new DeepSeekClient(endpoint, apiKey, model, maxTokens, temperature);
        }
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label, String[] args) {
        if ("kmcreload".equalsIgnoreCase(command.getName())) {
            if (!sender.hasPermission("kawaiimobchat.admin") && !sender.isOp()) {
                sender.sendMessage("\u00a7d(\u2727) you don't have permission~");
                return true;
            }
            reloadCfg();
            sender.sendMessage("\u00a7d(\u2727) KawaiiMobChat reloaded \u2728");
            return true;
        }
        return false;
    }

    // ============== CHAT EVENT ==============

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastChatAt.remove(id);
        inFlight.remove(id);
    }

    /**
     * Floodgate assigns Bedrock players UUIDs whose most-significant 64 bits are 0.
     * On a Java-only server this is never true, so it's a safe fast check.
     */
    private static boolean isBedrockPlayer(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!enabled || client == null) return;

        Player player = e.getPlayer();
        UUID id = player.getUniqueId();

        // cooldown — checked here, but only "consumed" once we actually fire an API call
        long now = System.currentTimeMillis();
        Long last = lastChatAt.get(id);
        if (last != null && now - last < cooldownMs) return;
        // skip if already running for this player
        if (!inFlight.add(id)) return;

        Component messageComp = e.message();
        final String message;
        try {
            message = PlainTextComponentSerializer.plainText().serialize(messageComp);
        } catch (Throwable t) {
            inFlight.remove(id);
            return;
        }
        if (message == null || message.trim().isEmpty()) {
            inFlight.remove(id);
            return;
        }

        // entity lookups must happen on main thread
        Bukkit.getScheduler().runTask(this, () -> findTargetThenChat(player, message, id));
    }

    private void findTargetThenChat(Player player, String message, UUID id) {
        if (!player.isOnline()) { inFlight.remove(id); return; }

        Mob target = findTarget(player);
        if (target == null) {
            inFlight.remove(id);
            return;
        }

        // Consume the cooldown only now that we know an API call will fire.
        lastChatAt.put(id, System.currentTimeMillis());

        final String mobType = safeMobType(target);
        final UUID mobId = target.getUniqueId();
        final String playerName = player.getName();
        final String userTurn = "Player " + playerName + ": \"" + message + "\"";
        final String context = buildContext(target, player);
        final List<DeepSeekClient.Message> history = snapshotHistory(mobId);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            DeepSeekClient.Reply r;
            try {
                String system = Personas.forMob(mobType);
                String userPrompt = (context.isEmpty() ? "" : context + "\n")
                        + userTurn + "\n\n"
                        + "React in character. Don't analyze the player's tone \u2014 just feel what your character would feel and speak. "
                        + "Use fragments, interjections, dashes, ellipses, your character's voice. Speak, don't narrate. "
                        + "Length: 1-2 short sentences. No markdown.\n"
                        + "Output ONLY a JSON object: "
                        + "{\"mood\": one of [angry, scared, sad, neutral, curious, friendly, excited], "
                        + "\"reply\": \"your spoken line\"}";
                r = client.chat(system, userPrompt, history);
            } catch (Throwable t) {
                r = new DeepSeekClient.Reply(null, null, "exception: " + t.getMessage());
            }

            final DeepSeekClient.Reply reply = r;
            Bukkit.getScheduler().runTask(this, () -> {
                inFlight.remove(id);
                if (reply == null || reply.error != null || reply.reply == null) {
                    if (reply != null && reply.error != null) {
                        getLogger().warning("(\u2727) API error: " + reply.error);
                    }
                    return;
                }
                recordTurn(mobId, userTurn, reply);
                applyReply(player, mobId, mobType, reply);
            });
        });
    }

    private void applyReply(Player player, UUID mobId, String mobType, DeepSeekClient.Reply reply) {
        // resolve the mob by id since some time has passed
        Mob mob = resolveMob(mobId);
        // mob may have died/despawned — still broadcast the line tied to the mob type

        String spoken = sanitize(reply.reply);
        if (spoken.isEmpty()) return;

        String mobName = resolveMobName(mob, mobType);
        String mood = reply.mood == null ? "neutral" : reply.mood;

        boolean bubbleShown = false;
        if (chatBubbles && mob != null && !mob.isDead()) {
            showChatBubble(mob, mobName, mood, spoken);
            bubbleShown = true;
        }
        if (mob != null && !mob.isDead()) {
            playMoodSound(mob, mood);
        }

        // Broadcast to chat if explicitly enabled, or if the bubble couldn't be
        // shown (mob despawned mid-reply), OR if the speaker is on Bedrock —
        // Geyser doesn't translate TextDisplay entities, so Bedrock players
        // can't see chat bubbles.
        boolean speakerIsBedrock = player.isOnline() && isBedrockPlayer(player);
        if (chatBroadcast || !bubbleShown || speakerIsBedrock) {
            String c = legacyColorForMood(mood);
            String txt = "neutral".equals(mood) ? "\u00a7f" : c; // line text color
            String prefix = c + "<" + mobName + c + ">" + txt + " ";
            Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize(prefix + spoken));
        }

        // Apply behavior — re-check player is still online before targeting them.
        if (mob != null && player.isOnline()) {
            switch (mood) {
                case "angry":
                    applyAngryBehavior(mob, player);
                    break;
                case "scared":
                    fleeFromPlayer(mob, player);
                    emitMoodParticles(mob, Particle.SMOKE, 8);
                    break;
                case "friendly":
                case "excited":
                    applyFriendlyBehavior(mob, player, "excited".equals(mood));
                    break;
                case "sad":
                    clearTargetingPlayer(mob, player);
                    emitMoodParticles(mob, Particle.SPLASH, 6);
                    break;
                case "curious":
                    clearTargetingPlayer(mob, player);
                    emitMoodParticles(mob, Particle.NOTE, 4);
                    break;
                default: // neutral
                    break;
            }
        }

        // Maybe a nearby fellow of the same type chimes in.
        if (mob != null && !mob.isDead()) {
            maybeBanter(mob, mobType, spoken);
        }
    }

    private void applyAngryBehavior(Mob mob, Player player) {
        // Hostile mobs accept setTarget and aggro. Passive mobs (pig, sheep,
        // cow, chicken…) silently no-op, so we detect that and make them flee
        // instead so the visible reaction matches the angry text.
        boolean targeted = false;
        try {
            mob.setTarget(player);
            LivingEntity cur = mob.getTarget();
            targeted = cur != null && cur.getUniqueId().equals(player.getUniqueId());
        } catch (Throwable ignored) {}

        if (!targeted) {
            fleeFromPlayer(mob, player);
        }
        emitMoodParticles(mob, Particle.ANGRY_VILLAGER, 8);
    }

    private void applyFriendlyBehavior(Mob mob, Player player, boolean excited) {
        clearTargetingPlayer(mob, player);
        emitMoodParticles(mob, excited ? Particle.HAPPY_VILLAGER : Particle.HEART, excited ? 10 : 6);
    }

    private void clearTargetingPlayer(Mob mob, Player player) {
        try {
            LivingEntity cur = mob.getTarget();
            if (cur != null && cur.getUniqueId().equals(player.getUniqueId())) {
                mob.setTarget(null);
            }
        } catch (Throwable ignored) {}
    }

    private void emitMoodParticles(Mob mob, Particle particle, int count) {
        try {
            Location loc = mob.getLocation().add(0, mob.getHeight() * 0.9, 0);
            mob.getWorld().spawnParticle(particle, loc, count, 0.4, 0.3, 0.4, 0.0);
        } catch (Throwable ignored) {}
    }

    // ============== MOOD COLORS ==============

    private static NamedTextColor moodNameColor(String mood) {
        switch (mood) {
            case "angry":    return NamedTextColor.RED;
            case "scared":   return NamedTextColor.YELLOW;
            case "sad":      return NamedTextColor.BLUE;
            case "curious":  return NamedTextColor.AQUA;
            case "friendly": return NamedTextColor.LIGHT_PURPLE;
            case "excited":  return NamedTextColor.GOLD;
            default:         return NamedTextColor.GRAY;
        }
    }

    private static NamedTextColor moodTextColor(String mood) {
        // Line body text color — usually white, except when the mood IS a strong
        // color (anger, sadness) we colour the whole line for visual punch.
        switch (mood) {
            case "angry": return NamedTextColor.RED;
            case "sad":   return NamedTextColor.BLUE;
            default:      return NamedTextColor.WHITE;
        }
    }

    private static String legacyColorForMood(String mood) {
        switch (mood) {
            case "angry":    return "§c";
            case "scared":   return "§e";
            case "sad":      return "§9";
            case "curious":  return "§b";
            case "friendly": return "§d";
            case "excited":  return "§6";
            default:         return "§7";
        }
    }

    private void fleeFromPlayer(Mob mob, Player player) {
        Vector away = mob.getLocation().toVector().subtract(player.getLocation().toVector());
        if (away.lengthSquared() < 1.0e-4) {
            away = new Vector(1, 0, 0);
        }
        away.setY(0);
        if (away.lengthSquared() > 0) away.normalize();

        // Immediate knockback so the reaction is visible even if the
        // pathfinder is busy with the mob's default goals.
        try {
            Vector kb = away.clone().multiply(0.6);
            kb.setY(0.25);
            mob.setVelocity(kb);
        } catch (Throwable ignored) {}

        // Brief speed boost so the panic reads as a panic.
        try {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1, true, false));
        } catch (Throwable ignored) {}

        // Re-issue a flee path every half second for ~4s — passive AI keeps
        // overriding the path otherwise.
        final Vector dir = away.clone();
        final UUID mobId = mob.getUniqueId();
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                Entity e = Bukkit.getEntity(mobId);
                if (!(e instanceof Mob) || e.isDead() || ticks >= 80) {
                    cancel();
                    return;
                }
                Mob m = (Mob) e;
                try {
                    Location target = m.getLocation().add(dir.clone().multiply(8));
                    m.getPathfinder().moveTo(target, 1.4);
                } catch (Throwable ignored) {}
                ticks += 10;
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    // ============== CONTEXT ==============

    /**
     * Build a brief snapshot of what the mob is currently feeling/seeing — HP,
     * time of day, biome, what the player is holding, distance, current target.
     * Each fact is a short sentence so the model can use them naturally instead
     * of treating them as a structured analysis to perform.
     */
    private String buildContext(Mob mob, Player player) {
        StringBuilder ctx = new StringBuilder();
        try {
            double max = mob.getMaxHealth();
            if (max > 0) {
                double pct = mob.getHealth() / max;
                if (pct < 0.30) ctx.append("You are badly hurt. ");
                else if (pct < 0.70) ctx.append("You are wounded. ");
            }
        } catch (Throwable ignored) {}

        try {
            long t = mob.getWorld().getTime();
            boolean day = t < 12300L || t > 23850L;
            ctx.append(day ? "It is daytime. " : "It is night. ");
        } catch (Throwable ignored) {}

        try {
            String biome = mob.getLocation().getBlock().getBiome().toString().toLowerCase().replace('_', ' ');
            ctx.append("You are in a ").append(biome).append(" biome. ");
        } catch (Throwable ignored) {}

        try {
            org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
            if (held != null && held.getType() != org.bukkit.Material.AIR) {
                String item = held.getType().toString().toLowerCase().replace('_', ' ');
                ctx.append("The player is holding a ").append(item).append(". ");
            }
        } catch (Throwable ignored) {}

        try {
            double d = mob.getLocation().distance(player.getLocation());
            if (d < 3.0)       ctx.append("They are right next to you. ");
            else if (d > 10.0) ctx.append("They are several blocks away. ");
        } catch (Throwable ignored) {}

        try {
            LivingEntity tgt = mob.getTarget();
            if (tgt != null && tgt.getUniqueId().equals(player.getUniqueId())) {
                ctx.append("You are already chasing this player. ");
            }
        } catch (Throwable ignored) {}

        if (reputationEnabled) {
            String type = safeMobType(mob);
            int killed = reputationFor(player.getUniqueId(), type);
            if (killed > 0) {
                String severity =
                        killed >= 50 ? "slaughtered countless" :
                        killed >= 10 ? "killed many" :
                        killed >= 3  ? "killed several" :
                                       "killed";
                ctx.append("This player has ").append(severity).append(" of your kind before. ");
            }
        }

        return ctx.toString().trim();
    }

    // ============== MEMORY ==============

    private List<DeepSeekClient.Message> snapshotHistory(UUID mobId) {
        if (!memoryEnabled || memoryMaxTurns == 0) return java.util.Collections.emptyList();
        MobMemory mem = memories.get(mobId);
        if (mem == null) return java.util.Collections.emptyList();
        synchronized (mem) {
            return new ArrayList<>(mem.turns);
        }
    }

    private void recordTurn(UUID mobId, String userTurn, DeepSeekClient.Reply reply) {
        if (!memoryEnabled || memoryMaxTurns == 0) return;
        MobMemory mem = memories.computeIfAbsent(mobId, k -> new MobMemory());
        // Store assistant content as the JSON the model produced so future turns
        // see both the spoken line and the mood it was expressed in.
        String assistant = "{\"mood\":\"" + (reply.mood == null ? "neutral" : reply.mood)
                + "\",\"reply\":\"" + DeepSeekClient.jsonEscape(reply.reply) + "\"}";
        synchronized (mem) {
            mem.turns.addLast(new DeepSeekClient.Message("user", userTurn));
            mem.turns.addLast(new DeepSeekClient.Message("assistant", assistant));
            int max = memoryMaxTurns * 2;
            while (mem.turns.size() > max) mem.turns.pollFirst();
            mem.lastTouchedMs = System.currentTimeMillis();
        }
    }

    private void sweepMemories() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, MobMemory>> it = memories.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MobMemory> e = it.next();
            MobMemory mem = e.getValue();
            if (now - mem.lastTouchedMs > memoryIdleMs) {
                it.remove();
                continue;
            }
            // Drop entries whose mob no longer exists (despawned, dead, unloaded).
            try {
                Entity ent = Bukkit.getEntity(e.getKey());
                if (ent == null || ent.isDead()) {
                    it.remove();
                }
            } catch (Throwable ignored) {}
        }

        // Prune banterCooldown: drop entries whose mob is gone OR whose timestamp
        // is older than 4x the cooldown (already long-since eligible to banter).
        long banterStaleMs = banterCooldownMs * 4L;
        Iterator<Map.Entry<UUID, Long>> bit = banterCooldown.entrySet().iterator();
        while (bit.hasNext()) {
            Map.Entry<UUID, Long> e = bit.next();
            if (now - e.getValue() > banterStaleMs) { bit.remove(); continue; }
            try {
                Entity ent = Bukkit.getEntity(e.getKey());
                if (ent == null || ent.isDead()) bit.remove();
            } catch (Throwable ignored) {}
        }
    }

    // ============== REPUTATION ==============

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (!reputationEnabled) return;
        if (!(e.getEntity() instanceof Mob)) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        String type = safeMobType((Mob) e.getEntity());
        if (type == null) return;
        killCounts
                .computeIfAbsent(killer.getUniqueId(), k -> new ConcurrentHashMap<>())
                .merge(type, 1, Integer::sum);
        // Drop any per-mob memory for the killed mob.
        memories.remove(e.getEntity().getUniqueId());
    }

    private int reputationFor(UUID playerId, String mobType) {
        if (playerId == null || mobType == null) return 0;
        ConcurrentHashMap<String, Integer> m = killCounts.get(playerId);
        if (m == null) return 0;
        Integer n = m.get(mobType);
        return n == null ? 0 : n;
    }

    private void loadReputation() {
        reputationFile = new File(getDataFolder(), "reputation.yml");
        if (!reputationFile.exists()) return;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(reputationFile);
            for (String pid : cfg.getKeys(false)) {
                UUID puuid;
                try { puuid = UUID.fromString(pid); } catch (Throwable t) { continue; }
                ConfigurationSection sec = cfg.getConfigurationSection(pid);
                if (sec == null) continue;
                ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
                for (String type : sec.getKeys(false)) {
                    int n = sec.getInt(type, 0);
                    if (n > 0) map.put(type, n);
                }
                if (!map.isEmpty()) killCounts.put(puuid, map);
            }
        } catch (Throwable t) {
            getLogger().warning("(✧) failed to load reputation.yml: " + t.getMessage());
        }
    }

    private void saveReputation() {
        if (reputationFile == null) {
            reputationFile = new File(getDataFolder(), "reputation.yml");
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, ConcurrentHashMap<String, Integer>> e : killCounts.entrySet()) {
                for (Map.Entry<String, Integer> sub : e.getValue().entrySet()) {
                    cfg.set(e.getKey().toString() + "." + sub.getKey(), sub.getValue());
                }
            }
            cfg.save(reputationFile);
        } catch (IOException ex) {
            getLogger().warning("(✧) failed to save reputation.yml: " + ex.getMessage());
        }
    }

    // ============== SOUND ==============

    private void playMoodSound(Mob mob, String mood) {
        if (!soundsEnabled) return;
        String type = safeMobType(mob);
        if (type == null) return;
        // Pick a sound key likely to exist for the mob+mood. Keys missing on the
        // client play silently — no error, no harm.
        String key;
        switch (mood) {
            case "angry":   key = "entity." + type.toLowerCase() + ".angry"; break;
            case "scared":
            case "sad":     key = "entity." + type.toLowerCase() + ".hurt";  break;
            default:        key = "entity." + type.toLowerCase() + ".ambient";
        }
        float pitch;
        switch (mood) {
            case "angry":    pitch = 0.85f; break;
            case "scared":   pitch = 1.4f;  break;
            case "sad":      pitch = 0.7f;  break;
            case "curious":  pitch = 1.1f;  break;
            case "friendly": pitch = 1.15f; break;
            case "excited":  pitch = 1.4f;  break;
            default:         pitch = 1.0f;
        }
        float volume = "angry".equals(mood) ? 1.2f : 0.9f;
        try {
            mob.getWorld().playSound(mob.getLocation(), key, volume, pitch);
        } catch (Throwable ignored) {
            // Some servers reject string-keyed sounds; fall back to ambient.
            try {
                mob.getWorld().playSound(mob.getLocation(),
                        "entity." + type.toLowerCase() + ".ambient", volume, pitch);
            } catch (Throwable ignored2) {}
        }
    }

    // ============== BANTER ==============

    private void maybeBanter(Mob speaker, String mobType, String spokenLine) {
        if (!banterEnabled || client == null || mobType == null || spokenLine == null) return;
        if (ThreadLocalRandom.current().nextDouble() > banterChance) return;

        Mob picked = null;
        try {
            List<Entity> nearby = speaker.getNearbyEntities(banterRadius, banterRadius / 2.0, banterRadius);
            if (nearby == null) return;
            long now = System.currentTimeMillis();
            for (Entity e : nearby) {
                if (!(e instanceof Mob)) continue;
                Mob m = (Mob) e;
                if (m.getUniqueId().equals(speaker.getUniqueId())) continue;
                if (!mobType.equals(safeMobType(m))) continue;
                Long lastBanter = banterCooldown.get(m.getUniqueId());
                if (lastBanter != null && now - lastBanter < banterCooldownMs) continue;
                picked = m;
                break;
            }
        } catch (Throwable t) {
            return;
        }
        if (picked == null) return;

        banterCooldown.put(picked.getUniqueId(), System.currentTimeMillis());
        final UUID listenerId = picked.getUniqueId();
        final String type = mobType;
        final String overheard = spokenLine;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            DeepSeekClient.Reply r;
            try {
                String system = Personas.forMob(type);
                String userPrompt = "A nearby fellow " + type.toLowerCase().replace('_', ' ')
                        + " just said out loud: \"" + overheard + "\"\n\n"
                        + "React briefly in your own voice — agree, disagree, mock, comfort, ignore — whatever "
                        + "fits. ONE short sentence, in character, no narration.\n"
                        + "Output ONLY a JSON object: "
                        + "{\"mood\": one of [angry, scared, sad, neutral, curious, friendly, excited], "
                        + "\"reply\": \"your spoken line\"}";
                r = client.chat(system, userPrompt);
            } catch (Throwable t) {
                return;
            }
            if (r == null || r.error != null || r.reply == null) return;

            final DeepSeekClient.Reply rr = r;
            Bukkit.getScheduler().runTask(this, () -> {
                Mob banterMob = resolveMob(listenerId);
                if (banterMob == null || banterMob.isDead()) return;
                String spoken = sanitize(rr.reply);
                if (spoken.isEmpty()) return;
                String name = resolveMobName(banterMob, type);
                String mood = rr.mood == null ? "neutral" : rr.mood;
                if (chatBubbles) {
                    showChatBubble(banterMob, name, mood, spoken);
                }
                if (chatBroadcast || !chatBubbles) {
                    String c = legacyColorForMood(mood);
                    String txt = "neutral".equals(mood) ? "§f" : c;
                    Bukkit.broadcast(LegacyComponentSerializer.legacySection()
                            .deserialize(c + "<" + name + c + ">" + txt + " " + spoken));
                }
                playMoodSound(banterMob, mood);
            });
        });
    }

    // ============== CHAT BUBBLE ==============

    private void showChatBubble(Mob mob, String mobName, String mood, String spoken) {
        NamedTextColor nameColor = moodNameColor(mood);
        NamedTextColor textColor = moodTextColor(mood);

        Component text = Component.text()
                .append(Component.text(mobName, nameColor))
                .append(Component.newline())
                .append(Component.text(spoken, textColor))
                .build();

        final TextDisplay display;
        try {
            Location spawnLoc = mob.getLocation().add(0, mob.getHeight() + 0.4, 0);
            display = mob.getWorld().spawn(spawnLoc, TextDisplay.class, td -> {
                td.text(text);
                td.setBillboard(Display.Billboard.CENTER);
                td.setShadowed(true);
                td.setSeeThrough(false);
                td.setPersistent(false);
                try {
                    td.setAlignment(TextDisplay.TextAlignment.CENTER);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            getLogger().warning("(✧) failed to spawn chat bubble: " + t.getMessage());
            return;
        }

        // Geyser doesn't translate TextDisplay entities, so any Bedrock player
        // nearby (a mere viewer of the bubble) can't see it even when the bubble
        // was spawned because the speaker was on Java. Send those nearby Bedrock
        // players the line as normal chat so they don't miss it. Java players
        // still see the TextDisplay above.
        sendBubbleToNearbyBedrock(mob, mobName, mood, spoken);

        final UUID displayId = display.getUniqueId();
        final UUID mobId = mob.getUniqueId();
        final long maxTicks = bubbleDurationSeconds * 20L;

        new BukkitRunnable() {
            long ticks = 0;
            @Override
            public void run() {
                Entity disp = Bukkit.getEntity(displayId);
                Entity host = Bukkit.getEntity(mobId);
                if (!(disp instanceof TextDisplay) || ticks >= maxTicks) {
                    if (disp != null) disp.remove();
                    cancel();
                    return;
                }
                if (!(host instanceof LivingEntity) || host.isDead() || !host.isValid()) {
                    disp.remove();
                    cancel();
                    return;
                }
                LivingEntity le = (LivingEntity) host;
                disp.teleport(le.getLocation().add(0, le.getHeight() + 0.4, 0));
                ticks += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    /**
     * Bedrock players can't see TextDisplay chat bubbles (Geyser limitation), so
     * for any Bedrock player within the mob-interaction range of the speaking mob
     * we send the same line as a normal chat message. Reuses {@link #isBedrockPlayer}
     * and the existing {@code rangeBlocks} radius.
     */
    private void sendBubbleToNearbyBedrock(Mob mob, String mobName, String mood, String spoken) {
        try {
            String c = legacyColorForMood(mood);
            String txt = "neutral".equals(mood) ? "§f" : c;
            Component line = LegacyComponentSerializer.legacySection()
                    .deserialize(c + "<" + mobName + c + ">" + txt + " " + spoken);
            double r = rangeBlocks;
            Location origin = mob.getLocation();
            for (Player p : mob.getWorld().getPlayers()) {
                if (!isBedrockPlayer(p)) continue;
                if (p.getLocation().distanceSquared(origin) > r * r) continue;
                p.sendMessage(line);
            }
        } catch (Throwable ignored) {}
    }

    private static String resolveMobName(Mob mob, String mobType) {
        if (mob != null) {
            try {
                Component nameComp = mob.customName();
                if (nameComp != null) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(nameComp).trim();
                    if (!plain.isEmpty()) return plain;
                }
            } catch (Throwable ignored) {}
        }
        return Personas.fallbackName(mobType);
    }

    // ============== TARGETING ==============

    private Mob findTarget(Player player) {
        // 1. Try line-of-sight target first
        try {
            Entity tgt = player.getTargetEntity(rangeBlocks);
            if (tgt instanceof Mob) {
                Mob m = (Mob) tgt;
                if (!isExcluded(safeMobType(m))) return m;
            }
        } catch (Throwable ignored) {}

        // 2. Fallback: nearest mob within range box
        try {
            List<Entity> nearby = player.getNearbyEntities(rangeBlocks, rangeBlocks / 2.0, rangeBlocks);
            if (nearby == null) return null;
            Mob best = null;
            double bestDist = Double.MAX_VALUE;
            double px = player.getLocation().getX();
            double py = player.getLocation().getY();
            double pz = player.getLocation().getZ();
            for (Entity e : nearby) {
                if (!(e instanceof Mob)) continue;
                Mob m = (Mob) e;
                if (isExcluded(safeMobType(m))) continue;
                double dx = e.getLocation().getX() - px;
                double dy = e.getLocation().getY() - py;
                double dz = e.getLocation().getZ() - pz;
                double d = dx*dx + dy*dy + dz*dz;
                if (d < bestDist) { bestDist = d; best = m; }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final Set<String> EXCLUDED_TYPES = new HashSet<>();
    static {
        // skip armor stands, item frames, etc — not actually Mob anyway, but defensive
        EXCLUDED_TYPES.add("ARMOR_STAND");
        EXCLUDED_TYPES.add("ITEM_FRAME");
    }

    private static boolean isExcluded(String type) {
        return type != null && EXCLUDED_TYPES.contains(type);
    }

    private Mob resolveMob(UUID mobId) {
        try {
            Entity e = Bukkit.getEntity(mobId);
            return (e instanceof Mob) ? (Mob) e : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ============== HELPERS ==============

    private static String safeMobType(Mob m) {
        try { return m.getType().name(); }
        catch (Throwable t) { return null; }
    }

    private String sanitize(String s) {
        if (s == null) return "";
        // strip @everyone-style and section/control chars; cap length
        String x = s.replace("@everyone", "everyone")
                    .replace("@here", "here")
                    .replace('\u00a7', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim();
        if (x.length() > maxReplyLength) x = x.substring(0, maxReplyLength) + "...";
        return x;
    }
}
