package com.ferisooo.kawaiiquests;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KawaiiQuests — pick Easy/Medium/Hard in a chest menu, an AI (DeepSeek) writes
 * you a quest, finish it, and a loot crate spins up a reward (harder = better).
 * The menu is a normal chest GUI so it works for Bedrock players through Geyser.
 */
public final class KawaiiQuests extends JavaPlugin implements TabCompleter {

    /** Shared '&'-code serializer for messages and item names. */
    public static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final QuestManager questManager = new QuestManager();
    private final LootTable lootTable = new LootTable();

    private DeepSeekClient deepSeek;
    private QuestGui gui;
    private AntiExploit antiExploit;

    /** PDC keys mirroring the active quest onto the player, so other plugins
     *  (e.g. KawaiiScoreboard's sidebar) can display it without a hard depend. */
    private NamespacedKey questTitleKey;
    private NamespacedKey questProgressKey;

    /** Targets the AI is never allowed to use (impossible / creative-only / bosses). */
    private final Set<String> blockedTargets = new HashSet<>();

    /** Players whose quest is currently being generated (anti double-click). */
    private final Set<UUID> requesting = new HashSet<>();
    /** When each player last finished a quest (completion cooldown). */
    private final Map<UUID, Long> lastComplete = new HashMap<>();
    /** When each player last asked for a quest (request rate-limit / API budget guard). */
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    /** Recently-used targets / types per difficulty, so the AI varies them. */
    private final Map<Difficulty, Deque<String>> recentTargets = new EnumMap<>(Difficulty.class);
    private final Map<Difficulty, Deque<String>> recentTypes = new EnumMap<>(Difficulty.class);
    private static final int RECENT_MEMORY = 6;
    /** Abandon penalty: when the player may request again, their current stack, and last abandon time. */
    private final Map<UUID, Long> abandonReadyAt = new HashMap<>();
    private final Map<UUID, Integer> abandonStacks = new HashMap<>();
    private final Map<UUID, Long> abandonLast = new HashMap<>();

    // --------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        questTitleKey = new NamespacedKey(this, "quest_title");
        questProgressKey = new NamespacedKey(this, "quest_progress");
        lootTable.load(getConfig(), getLogger());
        loadBlockedTargets();
        antiExploit = new AntiExploit(this);
        deepSeek = new DeepSeekClient(this);
        gui = new QuestGui(this);
        getServer().getPluginManager().registerEvents(new QuestListener(this), this);
        var cmd = getCommand("kquest");
        if (cmd != null) cmd.setTabCompleter(this);
        loadData();
        getLogger().info("(✧) KawaiiQuests enabled ~ /kquest to begin! ✿");
    }

    @Override
    public void onDisable() {
        saveData();                     // persist ongoing quests across restarts
        questManager.clearAll();
        if (antiExploit != null) antiExploit.clearPlaced();
    }

    private void loadBlockedTargets() {
        blockedTargets.clear();
        for (String s : getConfig().getStringList("blocked-targets")) {
            blockedTargets.add(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    // ----------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiiquests.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            lootTable.load(getConfig(), getLogger());
            loadBlockedTargets();
            antiExploit.reload();
            sender.sendMessage(LEGACY.deserialize("&d(✧) KawaiiQuests reloaded~"));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(LEGACY.deserialize("&d(✧) only players can use the quest menu."));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("abandon")) {
            abandonQuest(p);
            return true;
        }
        gui.open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String pre = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (sender instanceof Player p && questManager.has(p.getUniqueId())
                && "abandon".startsWith(pre)) {
            out.add("abandon");
        }
        if (sender.hasPermission("kawaiiquests.admin") && "reload".startsWith(pre)) {
            out.add("reload");
        }
        return out;
    }

    // ------------------------------------------------------------ quest flow

    /** Kick off AI generation for a chosen difficulty (called from the menu). */
    public void requestQuest(Player p, Difficulty diff) {
        UUID id = p.getUniqueId();
        if (questManager.has(id)) {
            p.sendMessage(msg("already-active"));
            return;
        }
        if (requesting.contains(id)) {
            p.sendMessage(msg("busy"));
            return;
        }
        long now = System.currentTimeMillis();
        // Completion cooldown: rest after finishing a quest.
        long cdMillis = getConfig().getLong("cooldown-seconds", 0) * 1000L;
        if (remaining(lastComplete.get(id), cdMillis, now) > 0) {
            p.sendMessage(msg("cooldown", "%seconds%",
                    String.valueOf(remaining(lastComplete.get(id), cdMillis, now) / 1000 + 1)));
            return;
        }
        // Request rate-limit: caps AI/API calls even across abandon spam.
        long reqMillis = getConfig().getLong("request-cooldown-seconds", 10) * 1000L;
        if (remaining(lastRequest.get(id), reqMillis, now) > 0) {
            p.sendMessage(msg("cooldown", "%seconds%",
                    String.valueOf(remaining(lastRequest.get(id), reqMillis, now) / 1000 + 1)));
            return;
        }
        // Abandon penalty: a stacking, difficulty-scaled timeout after bailing on quests.
        long readyAt = abandonReadyAt.getOrDefault(id, 0L);
        if (now < readyAt) {
            p.sendMessage(msg("abandon-cooldown", "%seconds%", String.valueOf((readyAt - now) / 1000 + 1)));
            return;
        }

        lastRequest.put(id, now);
        requesting.add(id);
        p.sendMessage(msg("thinking"));

        // Open the crate and let it spin while the AI writes the quest. When the
        // quest is ready it eases to a stop, reveals it, then opens the menu.
        QuestCrateAnimation crate = new QuestCrateAnimation(this, p, diff);
        crate.start();
        deepSeek.generate(diff, recentList(diff), recentTypeList(diff), quest -> {
            requesting.remove(id);
            if (!p.isOnline()) return;
            if (quest == null) {
                // DeepSeek is required but unavailable/failed — abort, no fallback.
                p.closeInventory(); // stop the spinning crate
                p.sendMessage(msg("ai-failed"));
                return;
            }
            assignQuest(p, quest);
            crate.reveal(quest, () -> gui.open(p));
        });
    }

    private void assignQuest(Player p, Quest q) {
        QuestManager.Active a = questManager.start(p.getUniqueId(), q);
        recordTarget(q.getDifficulty(), q.getTarget());
        recordType(q.getDifficulty(), q.getType().name());
        saveData();
        updateQuestDisplay(p);
        p.sendMessage(msg("assigned", "%title%", q.getTitle()));
        p.sendMessage(LEGACY.deserialize("&7" + q.getDescription()));
        p.sendMessage(LEGACY.deserialize("&dObjective: &f" + objective(q)));
        p.sendActionBar(progressActionBar(a));
    }

    /** Called from the listener whenever a player mines/kills/collects. */
    public void handleProgress(Player p, Quest.Type type, String target, int amount) {
        QuestManager.Active a = questManager.get(p.getUniqueId());
        if (a == null || a.completed) return;
        Quest q = a.quest;
        if (q.getType() != type) return;
        // "ANY" target counts every event of this type (e.g. catch any fish).
        if (!"ANY".equalsIgnoreCase(q.getTarget()) && !q.getTarget().equalsIgnoreCase(target)) return;

        a.progress = Math.min(q.getAmount(), a.progress + amount);
        if (a.progress >= q.getAmount()) {
            a.completed = true;
            completeQuest(p, q);
        } else {
            updateQuestDisplay(p);
            p.sendActionBar(progressActionBar(a));
        }
    }

    private void completeQuest(Player p, Quest q) {
        questManager.clear(p.getUniqueId());
        saveData();
        updateQuestDisplay(p);
        lastComplete.put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(msg("completed"));
        celebrate(p, q);
        announceBigCompletion(p, q);
        new CrateAnimation(this, p, q.getDifficulty(), forcedReward(q)).start();
    }

    /** Server-wide shout (with a fanfare sound) when someone clears a Hard/Brutal quest. */
    private void announceBigCompletion(Player p, Quest q) {
        Difficulty d = q.getDifficulty();
        if (d != Difficulty.HARD && d != Difficulty.BRUTAL) return;
        if (!getConfig().getBoolean("announce-hard-completions", true)) return;
        String raw = getConfig().getString("messages.big-complete",
                "&6✦ &e%player% &6just beat a %difficulty% &6quest: &f%title%&6! ✦")
                .replace("%player%", p.getName())
                .replace("%difficulty%", d.display())
                .replace("%title%", q.getTitle());
        Component line = LEGACY.deserialize(raw);
        for (Player o : getServer().getOnlinePlayers()) {
            o.sendMessage(line);
            try { o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f); } catch (Throwable ignored) {}
        }
    }

    /** The AI-chosen reward as a loot entry, or null to roll the config loot table. */
    private LootTable.Entry forcedReward(Quest q) {
        if (!q.hasReward()) return null;
        Material m = Material.matchMaterial(q.getRewardItem());
        if (m == null || !m.isItem()) return null;
        return new LootTable.Entry(m, q.getRewardAmount(), 1);
    }

    // --------------------------------------------------- anti-repeat memory

    private List<String> recentList(Difficulty diff) {
        Deque<String> d = recentTargets.get(diff);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    private List<String> recentTypeList(Difficulty diff) {
        Deque<String> d = recentTypes.get(diff);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    private void recordTarget(Difficulty diff, String target) {
        Deque<String> d = recentTargets.computeIfAbsent(diff, k -> new ArrayDeque<>());
        d.remove(target);            // de-dup, move to most-recent
        d.addLast(target);
        while (d.size() > RECENT_MEMORY) d.removeFirst();
    }

    private void recordType(Difficulty diff, String type) {
        Deque<String> d = recentTypes.computeIfAbsent(diff, k -> new ArrayDeque<>());
        d.addLast(type);             // keep duplicates so over-use of KILL is visible
        while (d.size() > RECENT_MEMORY) d.removeFirst();
    }

    /** Big on-screen flourish when a quest is finished, just before the loot crate. */
    private void celebrate(Player p, Quest q) {
        p.showTitle(Title.title(
                text("&d&l✦ Quest Complete ✦"),
                text("&f" + q.getTitle() + " &7~"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500),
                        Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (!getConfig().getBoolean("effects", true)) return;
        var loc = p.getLocation().add(0, 1.0, 0);
        p.spawnParticle(Particle.HEART, loc, 18, 0.6, 0.8, 0.6, 0);
        p.spawnParticle(Particle.FIREWORK, loc, 45, 0.5, 0.6, 0.5, 0.08);
        p.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 28, 0.5, 0.6, 0.5, 0.25);
    }

    /** Abandon the current quest (from the ACTIVE menu's barrier or /kquest abandon). */
    public void abandonQuest(Player p) {
        UUID id = p.getUniqueId();
        if (questManager.has(id)) {
            Difficulty diff = questManager.get(id).quest.getDifficulty();
            questManager.clear(id);
            saveData();
            updateQuestDisplay(p);
            applyAbandonPenalty(p, diff);
            p.sendMessage(msg("abandoned"));
        } else {
            p.sendMessage(LEGACY.deserialize(getConfig().getString("messages.prefix", "")
                    + "&7you have no active quest~"));
        }
    }

    /**
     * Difficulty-scaled, STACKING timeout before the player can take a new quest.
     * Each abandon (within the reset window) multiplies the tier's base penalty by
     * the current stack count, capped; the stack resets after a quiet period.
     */
    private void applyAbandonPenalty(Player p, Difficulty diff) {
        if (!getConfig().getBoolean("abandon-penalty.enabled", true)) return;
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long resetMs = Math.max(1, getConfig().getLong("abandon-penalty.stack-reset-seconds", 300)) * 1000L;

        Long last = abandonLast.get(id);
        int stacks = (last != null && now - last <= resetMs) ? abandonStacks.getOrDefault(id, 0) : 0;
        stacks++;
        abandonStacks.put(id, stacks);
        abandonLast.put(id, now);

        long base = baseAbandonSeconds(diff);
        long maxS = Math.max(1, getConfig().getLong("abandon-penalty.max-seconds", 300));
        long penalty = Math.min(maxS, base * stacks);
        if (penalty <= 0) return;
        abandonReadyAt.put(id, now + penalty * 1000L);
        p.sendMessage(msg("abandon-penalty",
                "%seconds%", String.valueOf(penalty), "%stack%", String.valueOf(stacks)));
    }

    private int baseAbandonSeconds(Difficulty d) {
        return switch (d) {
            case EASY   -> getConfig().getInt("abandon-penalty.easy-seconds", 5);
            case MEDIUM -> getConfig().getInt("abandon-penalty.medium-seconds", 15);
            case HARD   -> getConfig().getInt("abandon-penalty.hard-seconds", 30);
            case BRUTAL -> getConfig().getInt("abandon-penalty.brutal-seconds", 60);
        };
    }

    // ----------------------------------------------------------------- helpers

    public QuestManager questManager() { return questManager; }
    public LootTable lootTable()       { return lootTable; }
    public AntiExploit antiExploit()   { return antiExploit; }

    /**
     * Mirror the player's current quest (title + "progress/amount") into their
     * persistent data container, or clear it when they have no quest. This is
     * the bridge KawaiiScoreboard reads to show the quest on its sidebar —
     * no plugin-to-plugin dependency required. Call this whenever the quest
     * state changes (assign / progress / complete / abandon) and on join.
     */
    public void updateQuestDisplay(Player p) {
        if (questTitleKey == null) return; // not enabled yet
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        QuestManager.Active a = questManager.get(p.getUniqueId());
        if (a == null) {
            pdc.remove(questTitleKey);
            pdc.remove(questProgressKey);
        } else {
            pdc.set(questTitleKey, PersistentDataType.STRING, a.quest.getTitle());
            pdc.set(questProgressKey, PersistentDataType.STRING,
                    a.progress + "/" + a.quest.getAmount());
        }
    }

    /** True if the AI is forbidden from using this Material/EntityType as a target. */
    public boolean isBlockedTarget(String canonical) {
        return canonical != null && blockedTargets.contains(canonical.toUpperCase(Locale.ROOT));
    }

    /** On quit: drop the in-flight request flag and persist the current quest. */
    public void clearTransient(UUID id) {
        requesting.remove(id);
        saveData();
    }

    // ------------------------------------------------------------ persistence

    /** Load ongoing quests saved from a previous session. */
    private void loadData() {
        File f = new File(getDataFolder(), "data.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("quests");
        if (root == null) return;
        int loaded = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Quest.Type type = Quest.Type.valueOf(s.getString("type", "MINE"));
                Difficulty diff = Difficulty.from(s.getString("difficulty"), Difficulty.EASY);
                Quest q = new Quest(
                        s.getString("title", "Quest"),
                        s.getString("description", ""),
                        type,
                        s.getString("target", "STONE"),
                        Math.max(1, s.getInt("amount", 1)),
                        diff,
                        s.getString("reward-item", null),
                        s.getInt("reward-amount", 0));
                questManager.restore(id, q, s.getInt("progress", 0));
                loaded++;
            } catch (Exception ex) {
                getLogger().warning("(✧) skipping bad saved quest '" + key + "': " + ex.getMessage());
            }
        }
        if (loaded > 0) getLogger().info("(✧) restored " + loaded + " ongoing quest(s).");
    }

    /** Persist all ongoing quests so they survive restarts/crashes. */
    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, QuestManager.Active> e : questManager.all().entrySet()) {
            Quest q = e.getValue().quest;
            String base = "quests." + e.getKey();
            y.set(base + ".title", q.getTitle());
            y.set(base + ".description", q.getDescription());
            y.set(base + ".type", q.getType().name());
            y.set(base + ".target", q.getTarget());
            y.set(base + ".amount", q.getAmount());
            y.set(base + ".difficulty", q.getDifficulty().name());
            y.set(base + ".progress", e.getValue().progress);
            if (q.hasReward()) {
                y.set(base + ".reward-item", q.getRewardItem());
                y.set(base + ".reward-amount", q.getRewardAmount());
            }
        }
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(new File(getDataFolder(), "data.yml"));
        } catch (IOException ex) {
            getLogger().warning("(✧) could not save quest data: " + ex.getMessage());
        }
    }

    /** Milliseconds left on a cooldown started at {@code last}, or 0 if none/expired. */
    private static long remaining(Long last, long windowMillis, long now) {
        if (last == null || windowMillis <= 0) return 0;
        return Math.max(0, (last + windowMillis) - now);
    }

    public int minAmount(Difficulty d) {
        return Math.max(1, getConfig().getInt("difficulty." + d.key() + ".min-amount", 4));
    }
    public int maxAmount(Difficulty d) {
        return Math.max(minAmount(d), getConfig().getInt("difficulty." + d.key() + ".max-amount", 12));
    }

    private Component progressActionBar(QuestManager.Active a) {
        String raw = getConfig().getString("messages.progress", "%title% %progress%/%amount%")
                .replace("%title%", a.quest.getTitle())
                .replace("%progress%", String.valueOf(a.progress))
                .replace("%amount%", String.valueOf(a.quest.getAmount()));
        return LEGACY.deserialize(raw);
    }

    /** A non-italic component from a '&'-coded string (for GUI items/titles). */
    public Component text(String s) {
        return LEGACY.deserialize(s)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** A prefixed chat message from config with %placeholder% replacements. */
    public Component msg(String key, String... replacements) {
        String raw = getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        return LEGACY.deserialize(prefix + raw);
    }

    /** "MINE OAK_LOG" -> "Mine 8x Oak Log" style objective text. */
    public static String objective(Quest q) {
        int n = q.getAmount();
        boolean any = "ANY".equalsIgnoreCase(q.getTarget());
        String t = any ? "" : pretty(q.getTarget());
        return switch (q.getType()) {
            case MINE    -> "Mine " + n + "x " + t;
            case KILL    -> "Defeat " + n + "x " + t;
            case COLLECT -> "Collect " + n + "x " + t;
            case FISH    -> "Catch " + n + "x " + (any ? "fish" : t);
            case BREED   -> "Breed " + n + "x " + (any ? "animals" : t);
            case TAME    -> "Tame " + n + "x " + (any ? "animals" : t);
            case SMELT   -> "Smelt " + n + "x " + t;
            case CRAFT   -> "Craft " + n + "x " + t;
            case ENCHANT -> "Enchant " + n + "x " + (any ? "items" : t);
            case TRADE   -> "Trade " + n + "x with villagers";
        };
    }

    /** "OAK_LOG" -> "Oak Log". */
    public static String pretty(String enumName) {
        String s = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
            if (c == ' ') cap = true;
        }
        return sb.toString();
    }
}
