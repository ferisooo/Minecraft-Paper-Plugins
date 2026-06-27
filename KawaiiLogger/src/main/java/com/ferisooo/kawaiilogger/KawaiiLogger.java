package com.ferisooo.kawaiilogger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KawaiiLogger extends JavaPlugin implements Listener {

    // ============== CONFIG ==============
    private String webhookUrl;
    private String botName;
    private long batchTicks = 160L;
    private HttpClient http;

    // ============== LOG STORAGE ==============
    private LogWriter logWriter;
    private PlayerState playerState;

    // ============== SESSION STATE ==============
    private final Map<UUID, Set<String>> seenBiomes = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastBiome = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentStructure = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastElytraNotify = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageNotify = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastConsumeNotify = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEnchantNotify = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDiamondNotify = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStructureNotify = new ConcurrentHashMap<>(); // key = uuid|structureKey
    private final Map<UUID, Set<String>> rareDropsNotified = new ConcurrentHashMap<>();
    // Folia: events fire on per-region threads while the batch-flush driver runs
    // on the global-region thread, so this map is touched from multiple threads.
    // (On legacy Paper it was main-thread-only; a plain HashMap is no longer safe.)
    private final Map<UUID, Map<String, Bucket>> buckets = new ConcurrentHashMap<>();

    // ============== COOLDOWNS (ms) ==============
    private static final long CD_DAMAGE   = 30_000L;
    private static final long CD_CONSUME  = 30_000L;
    private static final long CD_ENCHANT  = 30_000L;
    private static final long CD_DIAMOND  = 60_000L;
    private static final long CD_ELYTRA   = 60_000L;
    private static final long CD_STRUCTURE = 5L * 60_000L;

    // ============== CATEGORIES ==============
    private static final String CAT_MINE   = "mine";
    private static final String CAT_LOG    = "log";
    private static final String CAT_CRAFT  = "craft";
    private static final String CAT_DAMAGE = "damage";
    private static final String CAT_KILL   = "kill";
    private static final String CAT_TRADE  = "trade";
    private static final String CAT_FISH   = "fish";
    private static final String CAT_BREED  = "breed";
    private static final String CAT_SHEAR  = "shear";

    private static final class Bucket {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        ScheduledTask task;
    }

    // ============== COLORS ==============
    private static final int COLOR_JOIN        = 0xFFB6D9;
    private static final int COLOR_LEAVE       = 0xC3B1E1;
    private static final int COLOR_DEATH       = 0xFF6B9D;
    private static final int COLOR_CHAT        = 0xA8E6CF;
    private static final int COLOR_CRAFT       = 0xFFE5A0;
    private static final int COLOR_DIAMOND     = 0x9FE2E8;
    private static final int COLOR_LOG         = 0xD4A574;
    private static final int COLOR_MINE        = 0xB8B8C8;
    private static final int COLOR_DAMAGE      = 0xFFB347;
    private static final int COLOR_BIOME       = 0xB5EAD7;
    private static final int COLOR_ADVANCEMENT = 0xFFD700;
    private static final int COLOR_PVP         = 0xFF6B6B;
    private static final int COLOR_BOSS        = 0xC084FC;
    private static final int COLOR_KILL        = 0xFFA07A;
    private static final int COLOR_FISHING     = 0x6BCBFF;
    private static final int COLOR_TAME        = 0xFFB6D9;
    private static final int COLOR_BREED       = 0xFFB6E1;
    private static final int COLOR_SHEAR       = 0xE0E0E0;
    private static final int COLOR_EAT         = 0xFFE5A0;
    private static final int COLOR_POTION      = 0xC4B5FD;
    private static final int COLOR_ELYTRA      = 0x9FE2E8;
    private static final int COLOR_ENCHANT     = 0xC084FC;
    private static final int COLOR_RARE        = 0xFFD700;
    private static final int COLOR_TRADE       = 0x10B981;
    private static final int COLOR_NETHER      = 0xDC2626;
    private static final int COLOR_END         = 0x6B21A8;
    private static final int COLOR_OVERWORLD   = 0x84CC16;
    private static final int COLOR_STRUCTURE   = 0xD2B48C;
    private static final int COLOR_RARE_BIOME  = 0xFFD700;
    private static final int COLOR_SLEEP       = 0x4F46E5;
    private static final int COLOR_NEW_PLAYER  = 0xFFD700;
    private static final int COLOR_BIRTHDAY    = 0xFF69B4;
    private static final int COLOR_MILESTONE   = 0x60A5FA;

    // ============== LOOKUP SETS ==============
    private static final Set<String> RARE_DROPS = new HashSet<>(Arrays.asList(
            "HEART_OF_THE_SEA", "NAUTILUS_SHELL", "DRAGON_EGG", "DRAGON_HEAD",
            "WITHER_SKELETON_SKULL", "ELYTRA", "TOTEM_OF_UNDYING", "NETHER_STAR",
            "TRIDENT", "CONDUIT", "BEACON", "ECHO_SHARD", "RECOVERY_COMPASS"
    ));
    private static final Set<String> SPECIAL_FOODS = new HashSet<>(Arrays.asList(
            "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "CHORUS_FRUIT",
            "GOLDEN_CARROT", "MILK_BUCKET", "HONEY_BOTTLE"
    ));
    private static final Set<String> POTION_TYPES = new HashSet<>(Arrays.asList(
            "POTION", "SPLASH_POTION", "LINGERING_POTION"
    ));
    private static final Set<String> SKIP_KILLS = new HashSet<>(Arrays.asList(
            "CHICKEN", "PIG", "COW", "SHEEP", "RABBIT", "BAT",
            "FISH", "COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH",
            "FROG", "BEE", "PARROT", "MOOSHROOM", "TURTLE",
            "SQUID", "GLOW_SQUID", "AXOLOTL"
    ));
    private static final Set<String> BOSSES = new HashSet<>(Arrays.asList(
            "ENDER_DRAGON", "WITHER", "WARDEN", "ELDER_GUARDIAN"
    ));
    private static final Set<String> RARE_BIOMES_TOKENS = new HashSet<>(Arrays.asList(
            "cherry_grove", "mushroom_fields", "deep_dark", "jagged_peaks",
            "mangrove_swamp", "ice_spikes", "bamboo_jungle", "flower_forest",
            "eroded_badlands", "lush_caves", "stony_peaks", "frozen_peaks"
    ));
    private static final Set<String> FISH_TREASURE = new HashSet<>(Arrays.asList(
            "NAUTILUS_SHELL", "NAME_TAG", "SADDLE", "ENCHANTED_BOOK"
    ));
    private static final Set<String> FISH_JUNK = new HashSet<>(Arrays.asList(
            "LILY_PAD", "LEATHER", "BONE", "STRING", "BOWL",
            "INK_SAC", "ROTTEN_FLESH", "STICK", "TRIPWIRE_HOOK", "WATER_BOTTLE"
    ));

    // ============== LIFECYCLE ==============

    @Override
    public void onEnable() {
        saveDefaultConfig();
        webhookUrl = getConfig().getString("webhook-url", "");
        botName = getConfig().getString("bot-name", "Stella ✨");
        int batchSecs = getConfig().getInt("batch-seconds", 8);
        if (batchSecs < 1) batchSecs = 1;
        batchTicks = (long) batchSecs * 20L;

        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("PASTE")) {
            getLogger().warning("(\u2727) no webhook URL set! edit plugins/KawaiiLogger/config.yml then /reload");
        }

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // log file dir + persistent state
        File logsDir = new File(getDataFolder(), "logs");
        logWriter = new LogWriter(logsDir, getLogger());
        playerState = new PlayerState(new File(getDataFolder(), "player-state.properties"), getLogger());

        getServer().getPluginManager().registerEvents(this, this);

        // milestone scan every 60s
        // Folia-safe: a global-region repeating driver reads the online-player
        // collection, then hops each player's statistic read + milestone work
        // onto THAT player's entity scheduler (getStatistic touches the player).
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> checkMilestones(), 1200L, 1200L);
        // periodic state save every 60s — file I/O, so run it off-main on the
        // async scheduler (real time: 1200 ticks = 60s = 60000ms).
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> playerState.saveIfDirty(),
                1200L * 50L, 1200L * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);

        getLogger().info("(\u2727) KawaiiLogger ready ~ batching every " + batchSecs + "s, logs in " + logsDir.getPath());
    }

    @Override
    public void onDisable() {
        // Cancel our repeating Folia tasks (milestone driver on the global-region
        // scheduler, state-save timer on the async scheduler) so nothing fires
        // after disable / during a reload.
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        getServer().getAsyncScheduler().cancelTasks(this);
        // flush pending buckets
        for (UUID id : new ArrayList<>(buckets.keySet())) {
            Map<String, Bucket> per = buckets.remove(id);
            if (per == null) continue;
            for (Map.Entry<String, Bucket> entry : per.entrySet()) {
                flushBucket(id, entry.getKey(), entry.getValue());
            }
        }
        if (playerState != null) playerState.saveIfDirty();
        if (logWriter != null) logWriter.shutdown();
    }

    // ============== JOIN / QUIT ==============

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // first-join + birthday
        String firstJoinStr = playerState.get("firstjoin", id, "");
        LocalDate today = LocalDate.now();
        boolean isNew = firstJoinStr == null || firstJoinStr.isEmpty();

        if (isNew) {
            playerState.set("firstjoin", id, today.toString());
            playerState.set("lastname", id, p.getName());
            sendDiscord("\u2728 NEW FRIEND!! \u2728",
                    "**" + p.getName() + "** joined for the first time!! welcome \uD83D\uDC96",
                    COLOR_NEW_PLAYER);
        } else {
            try {
                LocalDate firstJoin = LocalDate.parse(firstJoinStr);
                if (today.getMonth() == firstJoin.getMonth()
                        && today.getDayOfMonth() == firstJoin.getDayOfMonth()
                        && today.getYear() != firstJoin.getYear()) {
                    String lastBd = playerState.get("lastbirthday", id, "");
                    if (!today.toString().equals(lastBd)) {
                        playerState.set("lastbirthday", id, today.toString());
                        int years = today.getYear() - firstJoin.getYear();
                        sendDiscord("\uD83C\uDF82 BIRTHDAY!! \uD83C\uDF89",
                                "**" + p.getName() + "** has been on the server for **"
                                        + years + " year" + (years > 1 ? "s" : "") + "**!! \uD83E\uDD7A\uD83D\uDC96",
                                COLOR_BIRTHDAY);
                    }
                }
            } catch (Exception ignored) {}
            playerState.set("lastname", id, p.getName());
        }

        if (!isNew) {
            sendDiscord("\uD83D\uDC96 yay!", "**" + p.getName() + "** joined the world~", COLOR_JOIN);
        }

        // Seed biome tracking from persisted state so we don't re-announce biomes
        // the player already discovered, and so their *login* biome is never
        // reported as a fresh "discovery".
        Set<String> seen = ConcurrentHashMap.newKeySet();
        String saved = playerState.get("biomes", id, "");
        if (saved != null && !saved.isEmpty()) {
            for (String s : saved.split(",")) {
                if (!s.isEmpty()) seen.add(s);
            }
        }
        String currentBiome = currentBiomeName(p);
        if (currentBiome != null) {
            seen.add(currentBiome);
            lastBiome.put(id, currentBiome);
        }
        seenBiomes.put(id, seen);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        Map<String, Bucket> per = buckets.remove(id);
        if (per != null) {
            for (Map.Entry<String, Bucket> entry : per.entrySet()) {
                flushBucket(id, entry.getKey(), entry.getValue());
            }
        }
        seenBiomes.remove(id);
        lastBiome.remove(id);
        currentStructure.remove(id);
        rareDropsNotified.remove(id);
        lastElytraNotify.remove(id);
        lastDamageNotify.remove(id);
        lastConsumeNotify.remove(id);
        lastEnchantNotify.remove(id);
        lastDiamondNotify.remove(id);
        // structure cooldowns: keep them (so coming back within 5min is still quiet)
        // but prune old keys to bound memory
        long now = System.currentTimeMillis();
        lastStructureNotify.entrySet().removeIf(en -> now - en.getValue() > CD_STRUCTURE * 4);

        sendDiscord("\uD83C\uDF19 see ya soon~", "**" + p.getName() + "** left the world", COLOR_LEAVE);
    }

    // ============== DEATH (player) ==============

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();

        Component msg = e.deathMessage();
        String text = (msg != null)
                ? PlainTextComponentSerializer.plainText().serialize(msg)
                : victim.getName() + " died";

        if (killer != null && killer != victim) {
            sendDiscord("\u2694\uFE0F PVP!", "**" + killer.getName()
                    + "** killed **" + victim.getName() + "**!! \u2694\uFE0F", COLOR_PVP);
        } else {
            sendDiscord("\uD83D\uDC80 nooo...", text + " ;-;", COLOR_DEATH);
        }
    }

    // ============== ENTITY DEATH (mobs / bosses, killed by player) ==============

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim instanceof Player) return; // PvP handled by PlayerDeathEvent
        Player killer = victim.getKiller();
        if (killer == null) return;
        String type = victim.getType().name();

        if (BOSSES.contains(type)) {
            sendDiscord("\uD83D\uDC51 BOSS DEFEATED!! \uD83C\uDF89",
                    "**" + killer.getName() + "** killed " + Emojis.forItem(type) + " "
                            + prettyName(type) + "!! \u2728",
                    COLOR_BOSS);
            return;
        }
        if (SKIP_KILLS.contains(type)) return;
        addToBucket(killer.getUniqueId(), CAT_KILL, type);
    }

    // ============== ADVANCEMENTS ==============

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Advancement adv = e.getAdvancement();
        if (adv == null) return;
        NamespacedKey key = adv.getKey();
        if (key == null) return;
        String path = key.getKey();
        if (path == null) return;
        if (path.startsWith("recipes/")) return; // skip recipe unlocks (spammy)
        if (path.equals("root") || path.endsWith("/root")) return; // auto-granted tab roots

        String title;
        try {
            AdvancementDisplay display = adv.getDisplay();
            if (display != null) {
                Component t = display.title();
                title = (t != null) ? PlainTextComponentSerializer.plainText().serialize(t) : prettyName(path);
            } else {
                title = prettyName(path);
            }
        } catch (Throwable t) {
            title = prettyName(path);
        }

        Player p = e.getPlayer();
        sendDiscord("\uD83C\uDFC6 advancement!",
                "**" + p.getName() + "** got [" + title + "]!", COLOR_ADVANCEMENT);
    }

    // ============== CHAT ==============

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        sendDiscord("\uD83D\uDCAC " + p.getName(), msg, COLOR_CHAT);
    }

    // ============== DAMAGE ==============

    @EventHandler
    public void onAttacked(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastDamageNotify.get(id);
        if (last != null && now - last < CD_DAMAGE) return;
        lastDamageNotify.put(id, now);

        Entity damager = e.getDamager();
        // Resolve projectiles to their shooter so a skeleton's arrow is credited
        // to the skeleton (or player), not reported as "a wild arrow".
        if (damager instanceof Projectile proj) {
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Entity src) damager = src;
        }
        String attackerName = (damager instanceof Player)
                ? "**" + ((Player) damager).getName() + "**"
                : "a wild " + Emojis.forItem(damager.getType().name()) + " **"
                        + prettyName(damager.getType().name()) + "**";
        sendDiscord("\u2694\uFE0F attacked!!",
                "**" + p.getName() + "** is being attacked by " + attackerName,
                COLOR_DAMAGE);
    }

    @EventHandler
    public void onAnyDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        addToBucket(p.getUniqueId(), CAT_DAMAGE, e.getCause().name());
    }

    // ============== BLOCK BREAK (mining/logs/diamonds) ==============

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        String mat = b.getType().name();

        if (mat.equals("DIAMOND_ORE") || mat.equals("DEEPSLATE_DIAMOND_ORE")) {
            UUID id = p.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastDiamondNotify.get(id);
            if (last == null || now - last >= CD_DIAMOND) {
                lastDiamondNotify.put(id, now);
                sendDiscord("\uD83D\uDC8E DIAMONDS!! \u2728",
                        "**" + p.getName() + "** found diamonds!! omg",
                        COLOR_DIAMOND);
            } else {
                // already announced recently — the rest of the vein flows into mine batch
                addToBucket(id, CAT_MINE, mat);
            }
            return;
        }
        if (mat.endsWith("_LOG") || mat.endsWith("_STEM")
                || mat.endsWith("_WOOD") || mat.endsWith("_HYPHAE")) {
            addToBucket(p.getUniqueId(), CAT_LOG, mat);
            return;
        }
        if (mat.endsWith("_ORE") || mat.equals("ANCIENT_DEBRIS")) {
            addToBucket(p.getUniqueId(), CAT_MINE, mat);
        }
    }

    // ============== CRAFT ==============

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == null) return;
        addToBucket(p.getUniqueId(), CAT_CRAFT, item.getType().name());
    }

    // ============== FISHING ==============

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Entity caught = e.getCaught();
        if (!(caught instanceof Item)) return;
        ItemStack stack = ((Item) caught).getItemStack();
        if (stack == null || stack.getType() == null) return;
        String mat = stack.getType().name();
        Player p = e.getPlayer();

        if (FISH_TREASURE.contains(mat)) {
            sendDiscord("\uD83D\uDC8E TREASURE!!",
                    "**" + p.getName() + "** fished up TREASURE: "
                            + Emojis.forItem(mat) + " " + prettyName(mat) + " \u2728",
                    COLOR_RARE);
        } else if (FISH_JUNK.contains(mat)) {
            sendDiscord("\uD83E\uDD7E fishing!",
                    "**" + p.getName() + "** fished up junk: "
                            + Emojis.forItem(mat) + " " + prettyName(mat),
                    COLOR_FISHING);
        } else {
            // normal fish — batch so grinding doesn't spam
            addToBucket(p.getUniqueId(), CAT_FISH, mat);
        }
    }

    // ============== TAMING ==============

    @EventHandler
    public void onTame(EntityTameEvent e) {
        if (!(e.getOwner() instanceof Player)) return;
        Player p = (Player) e.getOwner();
        String type = e.getEntity().getType().name();
        sendDiscord("\uD83D\uDC95 new friend!",
                "**" + p.getName() + "** tamed a " + Emojis.forItem(type) + " "
                        + prettyName(type) + " uwu",
                COLOR_TAME);
    }

    // ============== BREEDING ==============

    @EventHandler
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player)) return;
        Player p = (Player) e.getBreeder();
        String type = e.getEntity().getType().name();
        addToBucket(p.getUniqueId(), CAT_BREED, type);
    }

    // ============== SHEARING ==============

    @EventHandler
    public void onShear(PlayerShearEntityEvent e) {
        Player p = e.getPlayer();
        String type = e.getEntity().getType().name();
        addToBucket(p.getUniqueId(), CAT_SHEAR, type);
    }

    // ============== EAT / POTION ==============

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null || item.getType() == null) return;
        String mat = item.getType().name();

        boolean isPotion = POTION_TYPES.contains(mat);
        boolean isSpecial = SPECIAL_FOODS.contains(mat);
        if (!isPotion && !isSpecial) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastConsumeNotify.get(id);
        if (last != null && now - last < CD_CONSUME) return;
        lastConsumeNotify.put(id, now);

        if (isPotion) {
            sendDiscord("\uD83E\uDDEA sip~",
                    "**" + p.getName() + "** drank a potion",
                    COLOR_POTION);
        } else {
            sendDiscord("\uD83C\uDF4E yum!",
                    "**" + p.getName() + "** ate "
                            + Emojis.forItem(mat) + " " + prettyName(mat),
                    COLOR_EAT);
        }
    }

    // ============== ELYTRA ==============

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding()) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        long now = System.currentTimeMillis();
        Long last = lastElytraNotify.get(p.getUniqueId());
        if (last != null && now - last < CD_ELYTRA) return;
        lastElytraNotify.put(p.getUniqueId(), now);
        sendDiscord("\uD83E\uDD8B lift off!",
                "**" + p.getName() + "** is flying with elytra \u2728",
                COLOR_ELYTRA);
    }

    // ============== ENCHANT ==============

    @EventHandler
    public void onEnchant(EnchantItemEvent e) {
        Player p = e.getEnchanter();
        if (p == null) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() == null) return;
        String matName = item.getType().name();

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastEnchantNotify.get(id);
        if (last != null && now - last < CD_ENCHANT) return;
        lastEnchantNotify.put(id, now);

        Map<Enchantment, Integer> ench = e.getEnchantsToAdd();
        StringBuilder sb = new StringBuilder();
        if (ench != null) {
            boolean first = true;
            for (Map.Entry<Enchantment, Integer> entry : ench.entrySet()) {
                if (entry.getKey() == null) continue;
                if (!first) sb.append(", ");
                first = false;
                String key;
                try {
                    NamespacedKey nk = entry.getKey().getKey();
                    key = nk != null ? nk.getKey() : "?";
                } catch (Throwable t) { key = "?"; }
                sb.append(key.replace('_', ' ')).append(" ").append(entry.getValue());
            }
        }
        if (sb.length() == 0) sb.append("something magical");

        sendDiscord("\u2728 enchanted!",
                "**" + p.getName() + "** enchanted "
                        + Emojis.forItem(matName) + " " + prettyName(matName) + " with " + sb,
                COLOR_ENCHANT);
    }

    // ============== RARE DROPS (pickup) ==============

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        Item itemEntity = e.getItem();
        if (itemEntity == null) return;
        ItemStack stack = itemEntity.getItemStack();
        if (stack == null || stack.getType() == null) return;
        String mat = stack.getType().name();
        if (!RARE_DROPS.contains(mat)) return;

        UUID id = p.getUniqueId();
        Set<String> notified = rareDropsNotified.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        if (notified.add(mat)) {
            sendDiscord("\u2728 RARE DROP!! \uD83C\uDF89",
                    "**" + p.getName() + "** found "
                            + Emojis.forItem(mat) + " **" + prettyName(mat) + "**!!",
                    COLOR_RARE);
        }
    }

    // ============== TRADE ==============

    @EventHandler
    public void onTrade(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Inventory inv = e.getInventory();
        if (inv == null) return;
        InventoryType invType;
        try { invType = inv.getType(); } catch (Throwable t) { return; }
        if (invType == null || !"MERCHANT".equals(invType.name())) return;
        if (e.getRawSlot() != 2) return; // result slot

        ItemStack result = e.getCurrentItem();
        if (result == null || result.getType() == null) return;
        String mat = result.getType().name();
        if ("AIR".equals(mat)) return;

        Player p = (Player) e.getWhoClicked();
        addToBucket(p.getUniqueId(), CAT_TRADE, mat);
    }

    // ============== WORLD CHANGE ==============

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        World w = p.getWorld();
        if (w == null) return;
        String env;
        try { env = w.getEnvironment().name(); } catch (Throwable t) { return; }

        if ("NETHER".equals(env)) {
            sendDiscord("\uD83D\uDD25 the nether!",
                    "**" + p.getName() + "** entered the nether",
                    COLOR_NETHER);
        } else if ("THE_END".equals(env)) {
            sendDiscord("\uD83C\uDF0C the end!",
                    "**" + p.getName() + "** entered the end",
                    COLOR_END);
        } else if ("NORMAL".equals(env)) {
            sendDiscord("\uD83C\uDF33 home~",
                    "**" + p.getName() + "** returned to the overworld",
                    COLOR_OVERWORLD);
        }
    }

    // ============== SLEEP ==============

    @EventHandler
    public void onSleep(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player p = e.getPlayer();
        sendDiscord("\uD83D\uDCA4 sleepy~",
                "**" + p.getName() + "** is sleeping zzz",
                COLOR_SLEEP);
    }

    // ============== MOVE (biome + structure) ==============

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return; // getTo() is nullable; another plugin may have cleared it
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()
                && from.getBlockY() == to.getBlockY()) {
            return;
        }

        // Only do the (relatively expensive) biome + structure work when the
        // player crosses a chunk boundary. Block-by-block detection is hot on
        // sprinting players (~4-5 calls/sec) and biomes very rarely change
        // within a 16x16 chunk, so this is a big saving for a tiny accuracy
        // tradeoff (intra-chunk biome transitions are missed until the next
        // chunk cross).
        Chunk fromChunk = from.getChunk();
        Chunk toChunk = to.getChunk();
        if (fromChunk == null || toChunk == null) return;
        if (fromChunk.getX() == toChunk.getX() && fromChunk.getZ() == toChunk.getZ()) {
            return;
        }

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // biome
        Block toBlock = to.getBlock();
        if (toBlock != null) {
            String biome = String.valueOf(toBlock.getBiome());
            String last = lastBiome.get(id);
            if (!biome.equals(last)) {
                lastBiome.put(id, biome);
                Set<String> seen = seenBiomes.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
                if (seen.add(biome)) {
                    persistBiomes(id, seen);
                    boolean rare = isRareBiome(biome);
                    sendDiscord(
                            rare ? "\uD83C\uDF1F rare biome!" : "\uD83C\uDF38 new biome!",
                            "**" + p.getName() + "** discovered "
                                    + (rare ? "the rare " : "")
                                    + "**" + prettyName(biome) + "**" + (rare ? " biome!! \u2728" : " \u2728"),
                            rare ? COLOR_RARE_BIOME : COLOR_BIOME);
                }
            }
        }

        // structure
        checkStructure(p, to);
    }

    private void checkStructure(Player p, Location loc) {
        World w = loc.getWorld();
        Chunk c = loc.getChunk();
        if (w == null || c == null) return;

        String inStructure = null;
        try {
            Collection<GeneratedStructure> structures = w.getStructures(c.getX(), c.getZ());
            if (structures != null) {
                for (GeneratedStructure gs : structures) {
                    BoundingBox bb = gs.getBoundingBox();
                    if (bb == null) continue;
                    if (bb.contains(loc.getX(), loc.getY(), loc.getZ())) {
                        Structure s = gs.getStructure();
                        if (s == null) continue;
                        NamespacedKey k = s.getKey();
                        if (k == null) continue;
                        String keyStr = k.toString();
                        if (isInterestingStructure(keyStr)) {
                            inStructure = keyStr;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            return;
        }

        UUID id = p.getUniqueId();
        String prev = currentStructure.get(id);
        if (inStructure != null && !inStructure.equals(prev)) {
            // entering a (potentially) new structure — check cooldown
            currentStructure.put(id, inStructure);
            String cdKey = id + "|" + inStructure;
            long now = System.currentTimeMillis();
            Long last = lastStructureNotify.get(cdKey);
            if (last == null || now - last >= CD_STRUCTURE) {
                lastStructureNotify.put(cdKey, now);
                sendDiscord("\uD83C\uDFDB\uFE0F structure!",
                        "**" + p.getName() + "** entered " + prettyStructureName(inStructure),
                        COLOR_STRUCTURE);
            }
        } else if (inStructure == null && prev != null) {
            currentStructure.remove(id);
        }
    }

    private static boolean isInterestingStructure(String key) {
        if (key == null) return false;
        String k = key.toLowerCase();
        return k.contains("village") || k.contains("fortress")
                || k.contains("stronghold") || k.contains("mansion")
                || k.contains("monument") || k.contains("end_city")
                || k.contains("ancient_city") || k.contains("trial_chambers")
                || k.contains("bastion") || k.contains("ruined_portal")
                || k.contains("pillager_outpost") || k.contains("igloo");
    }

    private static String prettyStructureName(String key) {
        if (key == null) return "a structure";
        String tail = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        return "a **" + tail.replace('_', ' ') + "**";
    }

    /** Current biome at the player, in the same string form used as the seen-set key. */
    private String currentBiomeName(Player p) {
        try {
            return String.valueOf(p.getLocation().getBlock().getBiome());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Save the player's discovered-biome set so it survives relogs. */
    private void persistBiomes(UUID id, Set<String> seen) {
        playerState.set("biomes", id, String.join(",", seen));
    }

    private static boolean isRareBiome(String biome) {
        if (biome == null) return false;
        String lower = biome.toLowerCase();
        for (String token : RARE_BIOMES_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    // ============== MILESTONES ==============

    private void checkMilestones() {
        // Folia-safe: only READ the online-player collection here, then hop each
        // player's stat read + milestone work onto that player's entity scheduler.
        for (Player p : getServer().getOnlinePlayers()) {
            p.getScheduler().run(this, t -> checkMilestone(p), null);
        }
    }

    private void checkMilestone(Player p) {
        long stepBlocks = 1000;
        long stepHours = 10;
        {
            UUID id = p.getUniqueId();
            try {
                long cm = 0;
                cm += safeStat(p, "WALK_ONE_CM");
                cm += safeStat(p, "SPRINT_ONE_CM");
                cm += safeStat(p, "CROUCH_ONE_CM");
                cm += safeStat(p, "WALK_ON_WATER_ONE_CM");
                long blocks = cm / 100L;
                long lastDist = playerState.getLong("dist", id, 0L);
                long current = (blocks / stepBlocks) * stepBlocks;
                if (current > lastDist && current >= stepBlocks) {
                    playerState.setLong("dist", id, current);
                    sendDiscord("\uD83D\uDC63 milestone!",
                            "**" + p.getName() + "** has walked **" + formatNumber(current) + "** blocks! \uD83C\uDF38",
                            COLOR_MILESTONE);
                }

                long ticks = safeStat(p, "PLAY_ONE_MINUTE");
                long hours = ticks / (20L * 60L * 60L);
                long lastPlay = playerState.getLong("play", id, 0L);
                long currentH = (hours / stepHours) * stepHours;
                if (currentH > lastPlay && currentH >= stepHours) {
                    playerState.setLong("play", id, currentH);
                    sendDiscord("\u23F0 milestone!",
                            "**" + p.getName() + "** has played for **" + currentH + " hours**! \uD83C\uDF89",
                            COLOR_MILESTONE);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static long safeStat(Player p, String name) {
        try { return p.getStatistic(Statistic.valueOf(name)); }
        catch (Throwable t) { return 0L; }
    }

    private static String formatNumber(long n) {
        return String.format("%,d", n);
    }

    // ============== BATCHING ==============

    private void addToBucket(UUID id, String category, String key) {
        Map<String, Bucket> per = buckets.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Bucket b = per.computeIfAbsent(category, k -> new Bucket());
        b.counts.merge(key, 1, Integer::sum);
        if (b.task == null) {
            // Folia-safe: the flush only touches shared collections + sends the
            // batched Discord/file log (no direct entity mutation), so route it
            // to the global-region scheduler. Delay must be >= 1 tick.
            b.task = getServer().getGlobalRegionScheduler().runDelayed(this,
                    t -> flushScheduled(id, category), Math.max(1L, batchTicks));
        }
    }

    private void flushScheduled(UUID id, String category) {
        Map<String, Bucket> per = buckets.get(id);
        if (per == null) return;
        Bucket b = per.remove(category);
        if (per.isEmpty()) buckets.remove(id);
        flushBucket(id, category, b);
    }

    private void flushBucket(UUID id, String category, Bucket b) {
        if (b == null || b.counts.isEmpty()) return;
        if (b.task != null) {
            try { b.task.cancel(); } catch (Throwable ignored) {}
            b.task = null;
        }
        Player p = getServer().getPlayer(id);
        String name = p != null ? p.getName() : playerState.get("lastname", id, "someone");
        String breakdown = formatCounts(b.counts);

        switch (category) {
            case CAT_MINE:
                sendDiscord("\u26CF\uFE0F mining~", "**" + name + "** mined " + breakdown, COLOR_MINE);
                break;
            case CAT_LOG:
                sendDiscord("\uD83E\uDEB5 chop chop~", "**" + name + "** chopped " + breakdown, COLOR_LOG);
                break;
            case CAT_CRAFT:
                sendDiscord("\uD83D\uDEE0\uFE0F crafted~", "**" + name + "** crafted " + breakdown, COLOR_CRAFT);
                break;
            case CAT_DAMAGE:
                sendDiscord("\uD83D\uDC94 ouch!", "**" + name + "** took " + breakdown, COLOR_DAMAGE);
                break;
            case CAT_KILL:
                sendDiscord("\u2694\uFE0F kills~", "**" + name + "** killed " + breakdown, COLOR_KILL);
                break;
            case CAT_TRADE:
                sendDiscord("\uD83E\uDD1D traded~", "**" + name + "** traded for " + breakdown, COLOR_TRADE);
                break;
            case CAT_FISH:
                sendDiscord("\uD83D\uDC1F fishing!", "**" + name + "** caught " + breakdown, COLOR_FISHING);
                break;
            case CAT_BREED:
                sendDiscord("\uD83E\uDD7A baby!", "**" + name + "** bred " + breakdown + " \u2728", COLOR_BREED);
                break;
            case CAT_SHEAR:
                sendDiscord("\u2702\uFE0F snip snip~", "**" + name + "** sheared " + breakdown, COLOR_SHEAR);
                break;
            default:
                break;
        }
    }

    private String formatCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getValue()).append("\u00d7 ")
              .append(Emojis.forItem(e.getKey())).append(" ")
              .append(prettyName(e.getKey()));
        }
        return sb.toString();
    }

    // ============== HELPERS ==============

    private static String prettyName(String name) {
        if (name == null) return "unknown";
        if (name.startsWith("minecraft:")) name = name.substring(10);
        int colon = name.lastIndexOf(':');
        if (colon != -1 && colon < name.length() - 1) {
            String tail = name.substring(colon + 1);
            int bracket = tail.indexOf(']');
            if (bracket != -1) tail = tail.substring(0, bracket);
            name = tail;
        }
        return name.toLowerCase().replace('_', ' ').replace('/', ' ');
    }

    private void sendDiscord(String title, String description, int color) {
        // always log to file regardless of webhook
        if (logWriter != null) {
            logWriter.log(title, description);
        }

        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("PASTE")) return;

        String json = "{"
                + "\"username\":\"" + jsonEscape(botName) + "\","
                + "\"embeds\":[{"
                + "\"title\":\"" + jsonEscape(title) + "\","
                + "\"description\":\"" + jsonEscape(description) + "\","
                + "\"color\":" + color + ","
                + "\"timestamp\":\"" + Instant.now() + "\""
                + "}]}";

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "KawaiiLogger/1.2")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            getLogger().warning("(\u2727) invalid webhook URL: " + ex.getMessage());
            return;
        }

        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(t -> {
                    getLogger().warning("(\u2727) webhook send failed: " + t.getMessage());
                    return null;
                });
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
