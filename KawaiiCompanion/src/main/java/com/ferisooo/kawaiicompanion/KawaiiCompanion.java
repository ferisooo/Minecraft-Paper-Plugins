package com.ferisooo.kawaiicompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Per-player companion. One {@link Companion} per online player at a time:
 * a NMS {@code ServerPlayer} (rendered to clients via {@link NmsCompanion})
 * dressed in pink leather armor, ferried around by a scheduler-driven
 * follow loop. Chat goes through DeepSeek's chat-completions endpoint with
 * rolling per-player conversation memory persisted under {@code memory/<uuid>.yml}.
 *
 * <p>Phase 1 of the NMS rewrite: the companion is a real {@code ServerPlayer}
 * object so the skin/profile/walk-animation render correctly, but visibility
 * is driven by manually broadcasting packets. Mobs/pressure plates ignore
 * it. Phase 2 will register the entity with the level's player list so the
 * world reacts to it.
 */
public final class KawaiiCompanion extends JavaPlugin implements Listener, TabCompleter {

    // ---------- config snapshot ----------
    private String  apiKey;
    private String  apiUrl;
    private String  model;
    private int     maxTokens;
    private double  temperature;
    private long    httpTimeoutSeconds;
    private long    chatCooldownMillis;
    private int     maxHistoryTurns;
    private String  personalityTemplate;
    private String  defaultName;
    private String  defaultSkin;
    private double  followDistance;
    private double  teleportThreshold;
    private double  moveStep;
    private long    movementTickPeriod;
    private boolean worldIntegrated;
    /** Wandering radius for SCOUT mode, in blocks, around the anchor. */
    private double  scoutRadius;
    /** Search radius for GUARD mode (looks for hostile mobs within this). */
    private double  guardRadius;
    /** Ticks between attack swings + damage applications. 12 ≈ 1.6/sec. */
    private long    attackCooldownTicks;
    /** Only target mobs the companion can actually see (line-of-sight), so she
     *  doesn't react to / path toward mobs through solid walls. */
    private boolean combatRequireLineOfSight;
    /** Master toggle: detect + attack a live Herobrine (via the optional
     *  Herobrine plugin's API) when it's near the companion or owner. */
    private boolean fightHerobrine;
    /** Radius (blocks) around the owner that {@code /kc kill} scans for targets. */
    private double  killRadius;
    /** Whether {@code /kc kill} may target players (also gated by permission). */
    private boolean allowKillPlayers;
    /** Max op-summoned helper companions per owner (the chatting primary is separate). */
    private int     maxExtras;

    // ---- Bow vs flying mobs ----
    /** Master toggle: auto-switch to bow when target is flying / out of melee reach. */
    private boolean autoEquipBowVsFlying;
    /** Ticks between bow shots. 30 = 1.5sec, like a half-charged player bow. */
    private long    bowShootCooldownTicks;
    /** Power enchant level baked into her auto-equipped bow. 0 = no enchant. */
    private int     bowPowerLevel;
    /** How far she'll spot a flying threat (blocks). */
    private double  bowDetectionRange;
    /** Initial arrow velocity (vanilla bow at full charge ≈ 3.0). */
    private double  bowArrowSpeed;

    // ---- Combat smarts ----
    /** Master toggle: re-target whatever just hit the owner. */
    private boolean assistOwnerEnabled;
    /** How long the assist target stays prioritized (ticks). */
    private long    assistMemoryTicks;
    /** Master toggle: announce nearby flying / dangerous mobs in chat-bubble. */
    private boolean spotterEnabled;
    /** How far around her she'll scan for spotter announcements. */
    private double  spotterRange;
    /** Re-announce the same threat type after this many ticks. */
    private long    spotterCooldownTicks;
    /** Master toggle: focus-fire targets attacking the owner over closer ones. */
    private boolean focusFireEnabled;
    /** Master toggle: special tactics vs Wither / Ender Dragon. */
    private boolean bossModeEnabled;
    /** Bow cooldown override during boss fights (faster). */
    private long    bossBowCooldownTicks;

    // ---- SCOUT idle pauses ----
    private double  scoutPauseChance;       // 0..1 per re-pick
    private long    scoutPauseMinTicks;
    private long    scoutPauseMaxTicks;

    // ---- Chat bubble + ambient chat ----
    private boolean chatBubbleEnabled;
    private long    chatBubbleDurationTicks;
    private boolean ambientChatEnabled;
    private long    ambientChatMinTicks;
    private long    ambientChatMaxTicks;
    private double  chatBroadcastRadius;
    private List<String> ambientPhrases = List.of();

    /** Whether to dress her in tinted leather armor on spawn. */
    private boolean wearLeatherArmor;
    /** Tint color for the leather armor (only used if wear-leather-armor). */
    private Color   leatherTint;
    /** How many slots her bag has. Multiple of 9, between 9 and 54. */
    private int     inventorySize;

    // ---- Pathfinding capability flags ----
    /** Whether she opens wooden doors / fence gates as she walks through them. */
    private boolean pathOpenDoors;
    /** Smooth (string-pull) her A* paths for non-robotic movement. */
    private boolean pathSmooth;
    /** Use an invisible navigator mob (native pathfinding) to drive FOLLOW —
     *  handles stairs/jumps/gaps properly instead of the hand-rolled A*. */
    private boolean smartNavigator;
    /** UUIDs of all live navigator mobs, so she never targets her own navigator. */
    private final java.util.Set<UUID> navIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Whether she breaks soft plants (cobwebs, leaves, vines, etc.) blocking her path.
     *  Off by default — destructive. Fires {@link BlockBreakEvent} so protection plugins
     *  can cancel. */
    private boolean pathBreakPlants;
    /** Whether she'll dig through dirt/sand/gravel/snow when no other path exists.
     *  Strongly off by default — this is the "she carved a tunnel through the side of
     *  my mountain to reach me" feature, useful but griefable. */
    private boolean pathTunneling;
    /** Cost multiplier for swimming through water. 3.0 = "she'd rather walk 3 blocks
     *  around than 1 block through water". */
    private double  pathSwimCostMultiplier;
    /** Per-block penalty added to step cost when she has to break through. Higher =
     *  stronger preference for unobstructed routes. */
    private double  pathBlockBreakCost;

    // ----- Behavior extras (smarter physics + poses + personality) -----
    /** Use a higher per-tick step + sprint flag when she's far behind in FOLLOW. */
    private boolean sprintCatchUpEnabled;
    /** Distance (blocks) at which catch-up sprint kicks in. */
    private double  sprintTriggerDistance;
    /** Multiplier applied to {@link #moveStep} while sprinting. */
    private double  sprintMoveStepMultiplier;
    /** Refuse SCOUT targets that would drop her further than {@link #edgeGuardMaxDrop} blocks. */
    private boolean edgeGuardScout;
    /** Max acceptable drop (blocks) below the SCOUT anchor when picking a wander target. */
    private int     edgeGuardMaxDrop;
    /** Allow vertical motion while standing on / against ladders + vines. */
    private boolean ladderClimbEnabled;
    /** Wave (arm-swing) once when the owner walks back into close range after being far. */
    private boolean greetWaveEnabled;
    /** Distance (blocks) at which the greet trigger arms — owner must have been *outside*
     *  this radius and just stepped into 1.5×{@link #followDistance} to fire the wave. */
    private double  greetWaveDistance;
    /** Min ticks between greet waves. Prevents wave-spam if the owner is yo-yo'ing. */
    private long    greetWaveCooldownTicks;
    /** Brief crouch + arm-swing right before the SLEEPING transition fires. */
    private boolean preSleepYawnEnabled;
    /** Random idle fidgets (arm swings, head shakes) layered on top of {@link #idleLook}. */
    private boolean randomFidgetsEnabled;
    /** In STAY/SCOUT, occasionally turn her head toward a nearby cute mob/animal. */
    private boolean lookAtNearbyEntitiesEnabled;
    /** Radius (blocks) within which {@link #lookAtNearbyEntitiesEnabled} scans. */
    private double  lookAtEntityRadius;
    /** In STAY mode + owner-nearby, slow head-track the owner instead of pure random looks. */
    private boolean headTrackOwnerInStay;
    /** Small upward bob when she steps up onto a higher block — sells the "hop" feel. */
    private boolean hopOnStepUpEnabled;
    /** Height (blocks) of the step-up hop overshoot. ~vanilla jump apex, brief. */
    private static final double HOP_BOB_HEIGHT = 0.42;

    // ----- Item & XP pickup -----
    /** Master toggle: she scans for nearby items and exp orbs and vacuums them up. */
    private boolean pickupEnabled;
    /** Pickup radius (blocks). Vanilla player default is ~1.0; we go a bit
     *  wider so items don't fall right next to her without being grabbed. */
    private double  pickupRadius;
    /** How many movement ticks between scans. 1 = every tick, 2 = every other,
     *  &c. Higher = cheaper but laggier feel. */
    private int     pickupScanInterval;
    /** When her bag is full, she still picks the item up (per spec) but the
     *  excess is silently discarded. Set false to make her ignore items
     *  she can't fully fit. */
    private boolean pickupDiscardOverflow;
    /** Pick up XP orbs. They get awarded to the OWNER (since the companion
     *  doesn't have a real player level), not to her. */
    private boolean pickupXpOrbs;
    /** Cooldown ticks before the same dropped item can be picked up — vanilla
     *  has a 10-tick "no pickup" delay on freshly-dropped stacks so the
     *  player who dropped them has a chance to pick them back up. We respect
     *  that by skipping items whose pickup delay isn't 0. */
    private boolean pickupRespectVanillaDelay;

    // ----- Smarts: stuck-escape, player state monitoring, DeepSeek context -----

    /** Master toggle for the dig-out-of-stuck system. */
    private boolean stuckEscapeEnabled;
    /** Stuck ticks past the normal repath threshold before escape mode kicks in. */
    private int     stuckEscapeTriggerTicks;
    /** Max real seconds an escape can run before we give up + emergency-teleport. */
    private long    stuckEscapeTimeoutTicks;
    /** Max blocks she's allowed to break in a single escape window. */
    private int     stuckEscapeMaxBlocks;
    /** True → on timeout, teleport her to the owner with an apology bubble. */
    private boolean stuckEscapeEmergencyTeleport;
    /** While in escape mode, also allow breaking soft natural stone (cobble, deepslate, …). */
    private boolean stuckEscapeAllowStoneBreak;

    /** Master toggle for player-state ambient awareness (low HP / hunger / fire / drowning warnings). */
    private boolean playerStateMonitorEnabled;
    /** Min ticks between any two ambient state warnings — keeps her from bubble-spamming. */
    private long    playerStateWarnCooldownTicks;
    /** HP threshold below which a "you're hurt!" bubble fires. Out of 20. */
    private double  playerStateLowHpThreshold;
    /** Hunger threshold below which a "you're starving!" bubble fires. Out of 20. */
    private int     playerStateLowFoodThreshold;

    /** Master toggle for stuffing world/entity context into the DeepSeek system prompt. */
    private boolean deepSeekContextEnabled;
    /** True → context block also includes nearby creatures (chickens, cows, hostile mobs). */
    private boolean deepSeekContextNearbyMobs;
    /** True → context block describes contents of her bag (best weapon, food, etc.). */
    private boolean deepSeekContextInventory;

    /** Maps an Interaction hitbox's Bukkit UUID → the companion's owner UUID. */
    private final Map<UUID, UUID> hitboxToOwner = new HashMap<>();
    /** Players currently viewing a control GUI. Maps player UUID → companion owner UUID. */
    private final Map<UUID, UUID> activeControlGuis = new HashMap<>();
    /** Players viewing the Hunt picker / Dig submenus. */
    private final Map<UUID, UUID> activeHuntGuis = new HashMap<>();
    private final Map<UUID, UUID> activeDigGuis  = new HashMap<>();
    /** Per-owner Hunt selection (mob-type names + "HOSTILE"/"ANIMAL"/"PLAYERS"). */
    private final Map<UUID, java.util.Set<String>> huntPicks = new ConcurrentHashMap<>();
    /** Server tick counter — driven from the movement task, used for idle scheduling. */
    private long behaviorTickCount;
    private boolean showNameTag;
    private String  glowColor;
    private boolean privateReplies;
    private double  broadcastRadius;
    private boolean autoUploadPngs;
    private String  mineskinUrl;
    private long    mineskinBackoffSeconds;
    /** Optional mineskin.org API key. Free signup at https://account.mineskin.org/.
     *  Without it the plugin uses anonymous mode, which mineskin has tightened
     *  to ~0 uploads/hour as of 2026. With a key the limit is much higher. */
    private String  mineskinApiKey;

    // ---- FEATURE 1: Bedrock real-entity companion ----
    /** Force the real-entity companion even for Java owners (debug/preference). */
    private boolean bedrockRealEntity;
    /** EntityType name for the real-entity companion. Validated at spawn via
     *  EntityType.valueOf in try/catch with a safe fallback. */
    private String  bedrockCompanionType;

    // ---- FEATURE 2: leveling & abilities ----
    private boolean levelingEnabled;
    private int     maxLevel;
    /** XP needed for level N = xpBase + xpPerLevel * (N-1). */
    private int     xpBase;
    private int     xpPerLevel;
    /** XP awarded when the owner kills a mob within {@link #xpKillRadius}. */
    private int     xpPerKill;
    private double  xpKillRadius;
    /** Passive XP awarded every {@link #passiveXpPeriodTicks} while summoned. */
    private int     passiveXpAmount;
    private long    passiveXpPeriodTicks;
    /** Ability toggles. */
    private boolean abilitySpeed;
    private boolean abilityHealAura;
    private boolean abilityCombatAssist;
    /** Level at which the heal aura unlocks. */
    private int     healAuraUnlockLevel;
    /** Ticks between heal-aura pulses. */
    private long    healAuraPeriodTicks;
    /** Hearts (×2 = HP) restored per aura pulse. */
    private double  healAuraAmount;
    /** Range (blocks) within which the owner is healed by the aura. */
    private double  healAuraRange;

    // ---- FEATURE 4: mount mode ----
    private boolean allowMount;
    private double  rideSpeedLand;
    private double  rideSpeedFly;

    // ---- FEATURE 5: mob forms & form combat ----
    /** Master toggle for mob-form companions fighting hostile mobs. */
    private boolean formCombatEnabled;
    /** Radius (blocks) the mob-form companion scans for hostiles. */
    private double  formCombatRange;
    /** Base ticks between attacks (style multipliers apply on top). */
    private long    formAttackPeriodTicks;
    /** Damage per form-combat hit in HP (hearts × 2). Fixed — never scales with level. */
    private double  formDamageBase;
    /** All living, spawnable Mob types offered as forms (cached at enable). */
    private List<String> mobForms = List.of();

    // ---------- runtime state ----------
    private final Map<UUID, Companion> companions = new ConcurrentHashMap<>();
    /** Op-only extra "helper" companions (drones). Keyed by owner → her extras.
     *  They obey broadcast orders but never chat / use the DeepSeek token. */
    private final Map<UUID, List<Companion>> extras = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      lastChatMillis = new ConcurrentHashMap<>();
    // Owners (Bedrock players) already shown the one-time "companion is Java-only" notice.
    private final Set<UUID> bedrockNoticeShown = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // PNGs that already have an upload in flight (or finished). Keyed by file
    // name; cleared when the file disappears from disk.
    private final java.util.Set<String> pngUploadInFlight = ConcurrentHashMap.newKeySet();
    // After a failure (rate-limit, network), back off for a while so the
    // plugin doesn't hammer mineskin on every /kc summon.
    private final Map<String, Long> pngBackoffUntilMs = new ConcurrentHashMap<>();
    // Skin files we've already warned about (parses but no usable texture).
    // Keyed by "name@lastModified" so editing the file re-arms the warning.
    private final java.util.Set<String> warnedSkinFiles = ConcurrentHashMap.newKeySet();

    /** Live state for one player's companion. */
    /**
     * What the companion is doing right now. Set via the right-click GUI;
     * default {@link #FOLLOW} preserves the legacy behavior so existing
     * users don't have to do anything to get the old experience.
     */
    private enum BehaviorMode { FOLLOW, STAY, GUARD, SCOUT, STANDBY }

    private static final class Companion {
        final UUID owner;
        NmsCompanion entity;
        String name;
        String skin;
        final List<Message> history = new ArrayList<>();

        // ----- Behavior state (right-click GUI) -----
        BehaviorMode mode = BehaviorMode.FOLLOW;
        /** Anchor point for STAY/GUARD/SCOUT (where she returns to / wanders around). */
        Location anchor;
        /** Lazy-init bag for items the player gives her. */
        Inventory inventory;
        /** Lazy-init second bag (the extra chest GUI). */
        Inventory inventory2;
        /** Explicit kill order — entity UUIDs (mobs/players) she should hunt
         *  down regardless of mode, set by {@code /kc kill}. */
        final java.util.Set<UUID> killTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Persistent hunt criteria (EntityType names + HOSTILE/ANIMAL/PLAYERS).
         *  While non-empty she keeps hunting NEW matching mobs as they appear. */
        final java.util.Set<String> huntFilter = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** True while a kill order is in progress (drives the "all done" bubble). */
        boolean killAnnounced;
        /** The kill target she's currently chasing + when she started, so she can
         *  give up on one she can't reach instead of staring at it forever. */
        UUID killPursuingId;
        long killPursueStartTick;
        /** Invisible navigator mob that does native pathfinding; the visible
         *  packet-NPC mirrors its position when {@code smart-navigator} is on. */
        org.bukkit.entity.Mob navMob;
        /** Behaviour tick on which the navigator was actively driving FOLLOW, so
         *  the post-dispatch park doesn't cancel the path it just issued. */
        long navDrivenTick = -1;
        /** True while the visible NPC should mirror the navigator every game tick
         *  (i.e. she's actively follow-moving). The 20 Hz mirror task reads this. */
        volatile boolean navMirroring;

        // ---- /kc dig — teach-and-repeat mining macro ----
        /** True while the owner is teaching a dig pattern (recording their breaks). */
        boolean recordingDig;
        /** Reference block the recorded offsets are relative to (owner's foot at record start). */
        Location digAnchor;
        /** Owner's facing when recording started — offsets are stored relative to it. */
        float digRecordYaw;
        /** Recorded breaks as FACING-LOCAL {forward, dy, right} (not world dx,dz),
         *  so "dig straight" follows whichever way she's pointed at /kc dig go. */
        final java.util.List<int[]> digPattern = new java.util.ArrayList<>();
        /** Per-loop shift in facing-local coords (the last break's local offset). */
        int[] digLoopShift;
        /** True while she's reproducing the pattern. */
        boolean digging;
        int digIndex;            // current block in the pattern
        int digLoop;             // how many full passes done
        Location digBase;        // base of the current pass (in world coords)
        float digGoYaw;          // owner's facing at "go" — used to map local→world
        int[] digWorldShift;     // per-loop shift in WORLD coords (digLoopShift rotated by digGoYaw)
        long nextDigActTick;     // throttle for the dig executor
        int digStallTicks;       // ticks spent failing to reach the current block
        /** Bukkit UUID of the {@link Interaction} hitbox we spawn for click detection. */
        UUID hitboxId;
        /** Current scout destination (random point around {@link #anchor}). */
        Location scoutTarget;
        /** Server tick at which we'll next pick a random idle head turn. */
        long nextIdleTick;
        /** Server tick at which we'll next pick a fresh scout target if she's stalled. */
        long nextScoutPickTick;
        /** While < this tick, SCOUT pauses in place (for immersion). */
        long scoutPauseUntil;

        // ----- Chat bubble + ambient chat -----
        /** Bukkit UUID of the TextDisplay floating above her head. */
        UUID bubbleId;
        /** Tick at which we should clear the bubble's text. 0 = no active bubble. */
        long bubbleClearTick;
        /** Tick at which she'll next try to say a random ambient phrase. */
        long nextAmbientChatTick;

        /** True while a BuildManager job owns her — suppresses normal mode dispatch. */
        boolean isBuilding;

        // ----- Combat: bow mode for flying threats -----
        /** True when she's currently wielding the auto-bow (vs the sword). */
        boolean bowEquipped;
        /** Tick at which she's allowed to fire her next arrow. */
        long nextBowShotTick;
        /** Tick at which she's allowed to switch back to bow against this wither. */
        long witherMeleeUntil;
        /** Have we already announced the switch-to-sword for the current melee window? */
        boolean witherSwitchAnnounced;

        // ----- Combat: assist + focus + spotter -----
        /** UUID of the mob currently prioritized because it just hit owner. null = none. */
        UUID assistTargetId;
        /** Tick at which assist priority expires. */
        long assistTargetUntil;
        /** Per-EntityType last-announce tick for spotter, so we don't spam. */
        final Map<EntityType, Long> lastSpotterTick = new HashMap<>();

        /** True while repositioning toward a live entity (combat). Makes the
         *  stepper WALK instead of teleport, and suppresses the dig-out
         *  stuck-escape — chasing a moving mob isn't a terrain trap, so she
         *  shouldn't warp onto it or declare herself "stuck". Set only for the
         *  duration of the reposition step, then cleared. */
        boolean approachingEntity;

        // ----- Pathfinding state -----
        /** Current path waypoints (block-center locations). null = no active path. */
        List<Location> currentPath;
        /** Index of the next waypoint to walk toward in {@link #currentPath}. */
        int pathIndex;
        /** Goal we computed {@link #currentPath} for — repath if the real goal drifts away from this. */
        Location pathGoal;
        /** Tick at which the path was computed; we recompute every ~5 sec to follow moving owners. */
        long pathComputedTick;
        /** Tick we became "stuck" — used to throttle stuck-triggered repaths. */
        int stuckTicks;
        /** Earliest tick we're allowed to kick off another A* search. Throttles main-thread cost. */
        long nextRepathAllowedTick;
        /** While {@code tick < combatGiveUpUntil} she ignores combat targets and
         *  returns to the owner. Set when she's been stuck in water chasing a
         *  target she can't reach (walled off), so she stops drowning at the
         *  barrier instead of bobbing there forever. */
        long combatGiveUpUntil;
        /** Last position we sampled for stuck-detection. */
        double lastStuckSampleX, lastStuckSampleY, lastStuckSampleZ;

        /** Last pose we broadcast for this companion. Used to dedupe so
         *  we only fire a SetEntityData packet on actual transitions.
         *  Defaults to {@code "STANDING"} which matches a freshly-spawned
         *  ServerPlayer's default state. */
        String currentPose = "STANDING";
        /** Pose we'd like to be in this tick. Hysteresis filter requires
         *  the desired pose to stay stable for a few ticks before
         *  promoting it to {@link #currentPose} — kills pose flicker at
         *  water edges and on momentary stuck-at-1-block-puddle ticks. */
        String desiredPose = "STANDING";
        /** Movement ticks the desired pose has been stable. */
        int    desiredPoseStableTicks;
        /** Last broadcast pitch while swimming, for smooth lerping. */
        double swimPitchSmoothed;
        /** Y position last sampled by {@link #applyDivePitch}; used to
         *  compute descent / ascent velocity for the dive tilt. */
        double swimLastY;
        /** Currently-applied vertical bob offset (additive to base Y).
         *  Tracked so we can undo it cleanly when she leaves water. */
        double swimBobOffset;
        /** Tick of the last "doing something" event — owner near, threat
         *  engaged, mode change. When she's been still for long enough
         *  in STAY mode, she dozes off (SLEEPING pose). */
        long lastActivityTick;
        /** Last tick she announced spotting Herobrine — throttles the
         *  "Herobrine! I'll protect you!" bubble while fighting him. */
        long herobrineSpotTick = -10000;
        /** While &lt; this tick, she stays in CROUCHING pose (ambient idle
         *  fidget, randomly triggered from {@link #idleLook}). */
        long ambientCrouchUntil;

        // ----- Behavior extras (sprint catch-up, greet wave, look-at-entity) -----

        /** Last sprint-flag we broadcast — dedupes the metadata packet
         *  so we only fire one when she actually starts/stops sprinting. */
        boolean sprintFlag;
        /** Tick of the last greet-wave we played. Throttled so she doesn't
         *  wave once per tick when you're hovering at the trigger
         *  distance. */
        long lastGreetWaveTick;
        /** True if the owner was &gt; greet-wave-distance last tick — used
         *  to detect the "owner just walked back into range" transition
         *  that triggers the wave. */
        boolean ownerWasFarLastTick;
        /** Tick of the last entity-look (head-track an animal/player). */
        long lastEntityLookTick;
        /** Tick of the last random fidget animation (idle arm swing /
         *  head shake). Independent throttle from the head-look one so
         *  fidgets don't all bunch up. */
        long lastFidgetTick;
        /** Tick of the last "hop" arm swing during a step-up. Throttled so
         *  walking up a staircase doesn't fire 6 swings in a row. */
        long lastHopSwingTick;
        /** Tick of the last item/xp pickup scan. Used to throttle the scan
         *  to {@link #pickupScanInterval}; cheap when nothing's around but
         *  worth not running every tick. */
        long lastPickupScanTick;
        /** Tick of the last successful pickup — drives the swing+sound feedback
         *  but throttled so a pile of items doesn't fire 20 swings in a row. */
        long lastPickupTick;
        /** While &lt; this tick, she's in a brief "thinking" crouch after
         *  hitting a stuck-and-repath event. Pure cosmetic. */
        long thinkingCrouchUntil;
        /** While &lt; this tick, she's pre-emptively crouching as a "yawn"
         *  / stretch shortly before the SLEEPING transition. */
        long preSleepYawnUntil;
        /** True if we already played the yawn for this sleep cycle —
         *  reset whenever lastActivityTick refreshes. */
        boolean yawnPlayedThisIdle;

        // ----- Stuck-escape state machine -----

        /** Tick at which escape mode started (0 = not currently escaping). */
        long escapeStartTick;
        /** Number of blocks broken in the current escape window. */
        int  escapeBlocksBroken;
        /** Tick of the last escape break — throttled to one per ~6 ticks
         *  so the digging looks human-paced rather than instantaneous. */
        long escapeLastBreakTick;
        /** Tick after which we'll allow another full escape attempt (cooldown
         *  on the trigger so she doesn't immediately re-escape after teleport). */
        long escapeNextAllowedTick;
        /** Last position we observed when stuck — used as the escape origin
         *  reference so we can tell when she's actually moved out. */
        double escapeOriginX, escapeOriginY, escapeOriginZ;

        // ----- Player state warnings -----

        /** Tick of the last ambient state warning bubble. Cooldown shared
         *  across HP/food/fire/drowning so we don't spam them all at once. */
        long lastStateWarnTick;
        /** Last sampled owner HP (out of 20). Edge-triggered: warning only
         *  fires on a fresh "just dropped below threshold" event, not while
         *  HP stays below threshold. */
        double  lastSampledOwnerHp = 20.0;
        int     lastSampledOwnerFood = 20;
        int     lastSampledOwnerFire = 0;
        int     lastSampledOwnerAir = 300;

        // ----- FEATURE 1: real-entity (Bedrock) companion -----
        /** True when this companion is rendered as a REAL Bukkit entity
         *  (Bedrock owners / forced via config) instead of the NMS
         *  fake-player. The two render paths are mutually exclusive. */
        boolean realEntity;
        /** Bukkit UUID of the live real-entity mob (when {@link #realEntity}).
         *  null = not currently spawned. */
        UUID realEntityId;
        /** EntityType name for the real-entity companion (Bedrock). Persisted
         *  so the cosmetic choice survives re-summon. null = use config default. */
        String bedrockType;
        /** Tick at which the real-entity follow loop last teleported her near
         *  the owner — throttles the periodic catch-up. */
        long realFollowLastTick;

        // ----- FEATURE 5: mob forms & form combat -----
        /** Owner explicitly morphed into a mob form (Java owners included).
         *  Persisted so the form survives re-summon; Bedrock owners are
         *  real-entity regardless of this flag. */
        boolean mobForm;
        /** Earliest behavior tick the mob-form companion may attack again. */
        long nextFormAttackTick;
        /** Cached form-combat target + the next tick we're allowed to re-scan
         *  for one. Throttles the per-tick getNearbyEntities sweep in
         *  {@code pickFormTarget} — between scans the cached target is cheaply
         *  re-validated instead of re-acquired. */
        UUID formTargetId;
        long nextFormTargetScanTick;

        /** Cached priority-combat target + next tick we may re-scan. Same
         *  throttle pattern as {@code formTargetId}: between scans the cached
         *  threat is cheaply re-validated instead of re-running the full
         *  getNearbyEntities priority sweep every behaviour tick. */
        UUID priorityTargetId;
        long nextPriorityScanTick;
        /** Next tick the spotter chat-warning sweep is allowed to run. */
        long nextSpotterScanTick;

        // ----- FEATURE 2: leveling & abilities -----
        /** Persisted companion level (1..max-level). */
        int level = 1;
        /** Persisted XP toward the next level. */
        int xp = 0;
        /** Tick of the last passive-XP award (throttle). */
        long lastPassiveXpTick;
        /** Tick of the last heal/regen aura pulse. */
        long lastHealAuraTick;

        // ----- FEATURE 4: mount mode -----
        /** True while the owner is riding the real-entity companion. */
        boolean mounted;

        /** Optional salt mixed into the client profile UUID so helper drones
         *  sharing the primary's skin still get distinct profiles (no tablist
         *  / render collision). Null for the primary (unchanged behaviour). */
        String profileSalt;

        Companion(UUID owner, String name, String skin) {
            this.owner = owner; this.name = name; this.skin = skin;
        }
    }

    /** Chat message used both in-memory and serialized to YAML. */
    private static final class Message {
        final String role;     // "user" or "assistant"
        final String content;
        Message(String role, String content) { this.role = role; this.content = content; }
    }

    // ============== LIFECYCLE ==============

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureSkinsFolder();
        readCfg();
        buildManager = new BuildManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("kawaiicompanion");
        if (cmd != null) cmd.setTabCompleter(this);

        // Phase 1 NMS companions are *not* registered with ServerLevel.players,
        // so they don't survive a JVM exit. Real-entity (mob form) companions
        // ARE world entities though — sweep up any orphans left by a crash or
        // by chunk saves from older builds (they used to be persistent), since
        // an orphan is an invulnerable mob with full vanilla hostile AI.
        Bukkit.getScheduler().runTask(this, () -> {
            int n = 0;
            for (World w : Bukkit.getWorlds()) n += sweepOrphanCompanions(w.getEntities());
            if (n > 0) getLogger().info("(✧) removed " + n + " orphaned companion mob(s)");
        });

        Bukkit.getScheduler().runTaskTimer(this,
                this::movementTick, movementTickPeriod, movementTickPeriod);

        // Mirror the visible NPC onto its navigator EVERY game tick (20 Hz), so
        // following looks smooth instead of stepping at the 10 Hz behaviour rate.
        Bukkit.getScheduler().runTaskTimer(this, this::navMirrorTick, 1L, 1L);

        // Steer a ridden companion every game tick (20 Hz) for smooth control.
        Bukkit.getScheduler().runTaskTimer(this, this::rideTick, 1L, 1L);

        // World-integrated companions have a stub Connection whose
        // pendingActions queue accumulates packets from server-internal
        // broadcasts (time, weather, etc) that target the level's player
        // list. Drain every few seconds so the queue stays small. Cheap:
        // just clears a ConcurrentLinkedQueue per companion. Skip when
        // world-integrated is off — there's nothing queueing in that mode.
        if (worldIntegrated) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (Companion c : companions.values()) {
                    if (c.entity != null) c.entity.clearStubPending();
                }
            }, 100L, 100L); // every 5 seconds
        }

        // Convert any PNGs the player dropped into skins/ before the server
        // started; later /kc summon calls trigger another sweep for files
        // added at runtime.
        maybeConvertPngs();

        getLogger().info("(✧) KawaiiCompanion ready ~ apiKey="
                + (resolvedApiKey().isEmpty() ? "missing" : "configured"));
    }

    @Override
    public void onDisable() {
        if (buildManager != null) buildManager.shutdown();
        // Scheduler is shutting down — saveMemory's async write won't fire.
        // Use the synchronous path so memory actually hits disk on stop.
        for (Companion c : new ArrayList<>(companions.values())) {
            try { saveMemoryNow(c); } catch (Throwable ignored) {}
            if (c.realEntity) despawnRealEntity(c); else despawnEntity(c);
        }
        companions.clear();
        // Helper drones are ephemeral — just despawn them (no persistence).
        for (List<Companion> list : extras.values()) {
            for (Companion c : list) { try { despawnCompanion(c); } catch (Throwable ignored) {} }
        }
        extras.clear();
    }

    // ===== Exposed for BuildManager (schematic build / preview / revert) =====

    /** Companion's current location for {@code ownerId}, or null if not summoned. */
    Location companionLocation(UUID ownerId) {
        Companion c = companions.get(ownerId);
        if (c == null || c.entity == null || c.entity.isDead()) return null;
        return c.entity.getLocation();
    }

    /** Swing her main hand (visual feedback during a build). */
    void swingCompanion(UUID ownerId) {
        Companion c = companions.get(ownerId);
        if (c == null || c.entity == null || c.entity.isDead()) return;
        try { c.entity.swingMainHand(); } catch (Throwable ignored) {}
    }

    /** Toggle the build-suppression flag so normal mode dispatch is skipped. */
    void setCompanionBuilding(UUID ownerId, boolean building) {
        Companion c = companions.get(ownerId);
        if (c == null) return;
        c.isBuilding = building;
        if (!building) {
            c.currentPath = null;
            c.pathIndex = 0;
            c.pathGoal = null;
            c.stuckTicks = 0;
        }
    }

    /** Teleport her to a build placement position, aimed at the block being placed. */
    void teleportCompanionForBuild(UUID ownerId, Location stand, Location lookAt) {
        Companion c = companions.get(ownerId);
        if (c == null || c.entity == null || c.entity.isDead()) return;
        if (stand == null) return;
        Location dest = stand.clone();
        if (lookAt != null) {
            double dx = lookAt.getX() - stand.getX();
            double dz = lookAt.getZ() - stand.getZ();
            double dy = lookAt.getY() - stand.getY();
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double pitch = Math.toDegrees(Math.atan2(-dy, Math.max(0.001, horiz)));
            dest.setYaw((float) yaw);
            dest.setPitch((float) Math.max(-89.0, Math.min(89.0, pitch)));
        }
        try { c.entity.teleport(dest); } catch (Throwable ignored) {}
    }

    /** Schematic build / preview / revert system — see {@link BuildManager}. */
    private BuildManager buildManager;
    /** #3: reply when a player's chat message contains her current name (no /kc say). */
    private boolean nameChatEnabled;
    /** #1: search radius (in chunks) for "where is the nearest <structure>" answers. */
    private int structureSearchChunks;
    /** #2: keep blocks near her intact through explosions (tnt/creeper/ghast/wither). */
    private boolean blockRestoreEnabled;
    private double  blockRestoreRadius;
    private boolean blockRestoreBubble;

    private void readCfg() {
        reloadConfig();
        var cfg = getConfig();
        apiKey               = cfg.getString("api-key", "");
        apiUrl               = cfg.getString("api-url", "https://api.deepseek.com/chat/completions");
        model                = cfg.getString("model", "deepseek-chat");
        maxTokens            = Math.max(16, cfg.getInt("max-tokens", 200));
        temperature          = Math.max(0.0, Math.min(2.0, cfg.getDouble("temperature", 0.9)));
        httpTimeoutSeconds   = Math.max(5, cfg.getLong("http-timeout-seconds", 30));
        chatCooldownMillis   = Math.max(0, cfg.getLong("chat-cooldown-millis", 1500));
        maxHistoryTurns      = Math.max(1, cfg.getInt("max-history-turns", 20));
        personalityTemplate  = cfg.getString("personality",
                "You are {name}, a kawaii companion. Reply briefly and stay in character.");
        defaultName          = cfg.getString("default-name", "Kohaku");
        defaultSkin          = cfg.getString("default-skin", "example");
        followDistance       = Math.max(0.5, cfg.getDouble("follow-distance", 3.0));
        teleportThreshold    = Math.max(followDistance + 1.0, cfg.getDouble("teleport-threshold", 24.0));
        moveStep             = Math.max(0.05, cfg.getDouble("move-step-blocks", 0.5));
        movementTickPeriod   = Math.max(1, cfg.getLong("movement-tick-period", 2));
        showNameTag          = cfg.getBoolean("show-name-tag", true);
        glowColor            = cfg.getString("glow-color", "LIGHT_PURPLE");
        privateReplies       = cfg.getBoolean("private-replies", true);
        broadcastRadius      = Math.max(1.0, cfg.getDouble("broadcast-radius", 16.0));
        autoUploadPngs       = cfg.getBoolean("auto-upload-pngs", true);
        mineskinUrl          = cfg.getString("mineskin-url", "https://api.mineskin.org/v2/generate");
        mineskinBackoffSeconds = Math.max(10, cfg.getLong("mineskin-backoff-seconds", 60));
        mineskinApiKey        = cfg.getString("mineskin-api-key", "");
        worldIntegrated      = cfg.getBoolean("world-integrated", true);
        scoutRadius          = Math.max(2.0, cfg.getDouble("scout-radius", 8.0));
        guardRadius          = Math.max(4.0, cfg.getDouble("guard-radius", 16.0));
        attackCooldownTicks  = Math.max(1, cfg.getLong("attack-cooldown-ticks", 12));
        combatRequireLineOfSight = cfg.getBoolean("combat-require-line-of-sight", true);
        fightHerobrine       = cfg.getBoolean("fight-herobrine", true);
        killRadius           = Math.max(4.0, cfg.getDouble("kill.radius", 24.0));
        allowKillPlayers     = cfg.getBoolean("kill.allow-players", true);
        // 0 (or negative) = unlimited helpers. Summon as many as you want.
        int cfgMaxHelpers    = cfg.getInt("multiple.max-helpers", 0);
        maxExtras            = cfgMaxHelpers <= 0 ? Integer.MAX_VALUE : cfgMaxHelpers;

        // Bow vs flying mobs
        autoEquipBowVsFlying = cfg.getBoolean("ranged-combat.auto-equip-bow-vs-flying", true);
        bowShootCooldownTicks = Math.max(5, cfg.getLong("ranged-combat.bow-shoot-cooldown-ticks", 30));
        bowPowerLevel        = Math.max(0, Math.min(5, cfg.getInt("ranged-combat.bow-power-level", 2)));
        bowDetectionRange    = Math.max(4.0, cfg.getDouble("ranged-combat.bow-detection-range", 24.0));
        bowArrowSpeed        = Math.max(0.5, cfg.getDouble("ranged-combat.bow-arrow-speed", 3.0));

        // Combat smarts
        assistOwnerEnabled   = cfg.getBoolean("combat-smarts.assist-owner", true);
        assistMemoryTicks    = Math.max(20, cfg.getLong("combat-smarts.assist-memory-seconds", 8) * 20L);
        spotterEnabled       = cfg.getBoolean("combat-smarts.spotter.enabled", true);
        spotterRange         = Math.max(8.0, cfg.getDouble("combat-smarts.spotter.range", 24.0));
        spotterCooldownTicks = Math.max(40, cfg.getLong("combat-smarts.spotter.cooldown-seconds", 15) * 20L);
        focusFireEnabled     = cfg.getBoolean("combat-smarts.focus-fire", true);
        bossModeEnabled      = cfg.getBoolean("combat-smarts.boss-mode.enabled", true);
        bossBowCooldownTicks = Math.max(5, cfg.getLong("combat-smarts.boss-mode.bow-cooldown-ticks", 15));

        scoutPauseChance     = Math.max(0.0, Math.min(1.0, cfg.getDouble("scout.pause-chance", 0.3)));
        scoutPauseMinTicks   = Math.max(0, cfg.getLong("scout.pause-min-seconds", 2)) * 20L;
        scoutPauseMaxTicks   = Math.max(scoutPauseMinTicks, cfg.getLong("scout.pause-max-seconds", 5) * 20L);

        chatBubbleEnabled    = cfg.getBoolean("chat-bubble.enabled", true);
        chatBubbleDurationTicks = Math.max(20, cfg.getLong("chat-bubble.duration-seconds", 4) * 20L);
        ambientChatEnabled   = cfg.getBoolean("ambient-chat.enabled", true);
        ambientChatMinTicks  = Math.max(20, cfg.getLong("ambient-chat.interval-min-seconds", 90) * 20L);
        ambientChatMaxTicks  = Math.max(ambientChatMinTicks, cfg.getLong("ambient-chat.interval-max-seconds", 240) * 20L);
        chatBroadcastRadius  = Math.max(1.0, cfg.getDouble("ambient-chat.chat-radius", 16.0));
        wearLeatherArmor     = cfg.getBoolean("companion-equipment.wear-leather-armor", false);
        String tintHex       = cfg.getString("companion-equipment.leather-tint-color", "FFB4DC");
        leatherTint          = parseHexColor(tintHex);
        // Inventory size — clamp to a multiple of 9, between 9 and 54.
        int rawSize          = cfg.getInt("companion-equipment.inventory-size", 27);
        int clamped          = Math.max(9, Math.min(54, rawSize));
        inventorySize        = (clamped / 9) * 9;
        if (inventorySize < 9) inventorySize = 9;

        // Pathfinding capability flags. Defaults match the safest setting:
        // open doors yes (non-destructive), no breaking, no tunneling.
        pathOpenDoors          = cfg.getBoolean("pathfinding.open-doors", true);
        pathSmooth             = cfg.getBoolean("pathfinding.smooth", false);
        smartNavigator         = cfg.getBoolean("pathfinding.smart-navigator", true);
        pathBreakPlants        = cfg.getBoolean("pathfinding.break-soft-blocks", false);
        pathTunneling          = cfg.getBoolean("pathfinding.allow-tunneling", false);
        pathSwimCostMultiplier = Math.max(1.0, cfg.getDouble("pathfinding.swim-cost-multiplier", 3.0));
        pathBlockBreakCost     = Math.max(0.0, cfg.getDouble("pathfinding.block-break-cost", 4.0));

        // Behavior extras — smarter physics + poses + personality.
        // Defaults are chosen so out-of-the-box behavior is the new
        // (richer) one; players can opt back into the old behavior by
        // flipping individual flags.
        sprintCatchUpEnabled       = cfg.getBoolean("behavior-extras.sprint-when-falling-behind", true);
        sprintTriggerDistance      = Math.max(followDistance + 1.0,
                                              cfg.getDouble("behavior-extras.sprint-trigger-distance", 7.0));
        sprintMoveStepMultiplier   = Math.max(1.0, Math.min(3.0,
                                              cfg.getDouble("behavior-extras.sprint-move-step-multiplier", 1.6)));
        edgeGuardScout             = cfg.getBoolean("behavior-extras.edge-guard-in-scout", true);
        edgeGuardMaxDrop           = Math.max(2, Math.min(16,
                                              cfg.getInt("behavior-extras.edge-guard-max-drop", 4)));
        ladderClimbEnabled         = cfg.getBoolean("behavior-extras.ladder-climbing", true);
        greetWaveEnabled           = cfg.getBoolean("behavior-extras.greet-wave", true);
        greetWaveDistance          = Math.max(followDistance + 1.0,
                                              cfg.getDouble("behavior-extras.greet-wave-trigger-distance", 12.0));
        greetWaveCooldownTicks     = Math.max(40,
                                              cfg.getLong("behavior-extras.greet-wave-cooldown-seconds", 20) * 20L);
        preSleepYawnEnabled        = cfg.getBoolean("behavior-extras.pre-sleep-yawn", true);
        randomFidgetsEnabled       = cfg.getBoolean("behavior-extras.random-idle-fidgets", true);
        lookAtNearbyEntitiesEnabled= cfg.getBoolean("behavior-extras.look-at-nearby-entities", true);
        lookAtEntityRadius         = Math.max(2.0, Math.min(32.0,
                                              cfg.getDouble("behavior-extras.look-at-entity-radius", 8.0)));
        headTrackOwnerInStay       = cfg.getBoolean("behavior-extras.head-track-owner-in-stay", true);
        hopOnStepUpEnabled         = cfg.getBoolean("behavior-extras.hop-on-step-up", true);

        // Item & XP pickup
        pickupEnabled              = cfg.getBoolean("pickup.enabled", true);
        pickupRadius               = Math.max(0.5, Math.min(8.0,
                                          cfg.getDouble("pickup.radius", 2.0)));
        pickupScanInterval         = Math.max(1, Math.min(20,
                                          cfg.getInt("pickup.scan-interval-ticks", 2)));
        pickupDiscardOverflow      = cfg.getBoolean("pickup.discard-overflow-when-full", true);
        pickupXpOrbs               = cfg.getBoolean("pickup.collect-xp-orbs", true);
        pickupRespectVanillaDelay  = cfg.getBoolean("pickup.respect-vanilla-pickup-delay", true);

        // Smarts: stuck-escape, player-state monitoring, DeepSeek context.
        stuckEscapeEnabled            = cfg.getBoolean("smarts.stuck-escape.enabled", true);
        stuckEscapeTriggerTicks       = Math.max(STUCK_TICK_THRESHOLD,
                                          cfg.getInt("smarts.stuck-escape.trigger-ticks", 12));
        stuckEscapeMaxBlocks          = Math.max(1, Math.min(64,
                                          cfg.getInt("smarts.stuck-escape.max-blocks", 12)));
        // These three are FORCED (config ignored) so a stale config.yml can't
        // keep her stuck: give up fast (~2s), blink to the owner instead of
        // staying jammed/clipping, and never smash stone/builds to escape.
        stuckEscapeTimeoutTicks       = 40;    // ~2s @ 20 game-ticks
        stuckEscapeEmergencyTeleport  = true;  // teleport out when she can't free herself
        stuckEscapeAllowStoneBreak    = false; // never grief-dig stone to escape

        playerStateMonitorEnabled     = cfg.getBoolean("smarts.player-state-monitor.enabled", true);
        playerStateWarnCooldownTicks  = Math.max(60,
                                          cfg.getLong("smarts.player-state-monitor.warn-cooldown-seconds", 4) * 20L);
        playerStateLowHpThreshold     = Math.max(1.0, Math.min(20.0,
                                          cfg.getDouble("smarts.player-state-monitor.low-hp-threshold", 8.0)));
        playerStateLowFoodThreshold   = Math.max(1, Math.min(20,
                                          cfg.getInt("smarts.player-state-monitor.low-food-threshold", 6)));

        deepSeekContextEnabled        = cfg.getBoolean("smarts.deepseek-context.enabled", true);
        deepSeekContextNearbyMobs     = cfg.getBoolean("smarts.deepseek-context.include-nearby-mobs", true);
        deepSeekContextInventory      = cfg.getBoolean("smarts.deepseek-context.include-inventory", true);

        nameChatEnabled               = cfg.getBoolean("name-chat.enabled", true);
        structureSearchChunks         = Math.max(8, Math.min(400,
                                          cfg.getInt("smarts.structure-search-chunks", 100)));
        blockRestoreEnabled           = cfg.getBoolean("block-restore.enabled", true);
        blockRestoreRadius            = Math.max(1.0, Math.min(32.0,
                                          cfg.getDouble("block-restore.radius", 8.0)));
        blockRestoreBubble            = cfg.getBoolean("block-restore.bubble", true);

        List<String> phrases = cfg.getStringList("ambient-chat.phrases");
        if (phrases.isEmpty()) {
            phrases = List.of(
                    "(\u2727)",
                    "ehe~",
                    "the wind feels nice...",
                    "i wonder what's over there~",
                    "hmm hmm \u266a",
                    "\u2661",
                    "what should we do next?",
                    "...sleepy ~",
                    "is that a butterfly?",
                    "fufu~"
            );
        }
        ambientPhrases = phrases;

        // FEATURE 1: Bedrock real-entity companion
        bedrockRealEntity     = cfg.getBoolean("bedrock-real-entity", false);
        bedrockCompanionType  = cfg.getString("bedrock-companion-type", "ALLAY");

        // FEATURE 2: leveling & abilities
        levelingEnabled       = cfg.getBoolean("leveling.enabled", true);
        // 0 (or negative) = unlimited / indefinite leveling. Any positive
        // value caps it. Default is now unlimited.
        int cfgMaxLevel       = cfg.getInt("leveling.max-level", 0);
        maxLevel              = cfgMaxLevel <= 0 ? Integer.MAX_VALUE : Math.max(1, cfgMaxLevel);
        xpBase                = Math.max(1, cfg.getInt("leveling.xp-base", 30));
        xpPerLevel            = Math.max(0, cfg.getInt("leveling.xp-per-level", 20));
        xpPerKill             = Math.max(0, cfg.getInt("leveling.xp-per-kill", 5));
        xpKillRadius          = Math.max(1.0, Math.min(64.0, cfg.getDouble("leveling.xp-kill-radius", 24.0)));
        passiveXpAmount       = Math.max(0, cfg.getInt("leveling.passive-xp-amount", 1));
        passiveXpPeriodTicks  = Math.max(20, cfg.getLong("leveling.passive-xp-period-seconds", 30) * 20L);
        abilitySpeed          = cfg.getBoolean("leveling.abilities.movement-speed", true);
        abilityHealAura       = cfg.getBoolean("leveling.abilities.heal-aura", true);
        abilityCombatAssist   = cfg.getBoolean("leveling.abilities.combat-assist", true);
        healAuraUnlockLevel   = Math.max(1, cfg.getInt("leveling.abilities.heal-aura-unlock-level", 3));
        healAuraPeriodTicks   = Math.max(20, cfg.getLong("leveling.abilities.heal-aura-period-seconds", 4) * 20L);
        healAuraAmount        = Math.max(0.5, cfg.getDouble("leveling.abilities.heal-aura-hearts", 1.0)) * 2.0;
        healAuraRange         = Math.max(2.0, Math.min(48.0, cfg.getDouble("leveling.abilities.heal-aura-range", 8.0)));

        // FEATURE 4: mount mode
        allowMount            = cfg.getBoolean("allow-mount", true);
        rideSpeedLand         = Math.max(0.05, cfg.getDouble("ride.land-speed", 0.42));
        rideSpeedFly          = Math.max(0.05, cfg.getDouble("ride.fly-speed", 0.55));

        // FEATURE 5: mob forms & form combat
        formCombatEnabled     = cfg.getBoolean("form-combat.enabled", true);
        formCombatRange       = Math.max(4.0, Math.min(32.0, cfg.getDouble("form-combat.range", 12.0)));
        // behaviorTickCount advances once per MOVEMENT tick (every
        // movement-tick-period server ticks), so convert real seconds into
        // behavior ticks — otherwise attacks land 2x slower than configured.
        formAttackPeriodTicks = Math.max(5, cfg.getLong("form-combat.attack-period-seconds", 2) * 20L
                / Math.max(1, movementTickPeriod));
        formDamageBase        = Math.max(0.5, cfg.getDouble("form-combat.damage-hearts", 2.0)) * 2.0;
        // Attack damage is intentionally flat — it does NOT scale with level, so
        // she never gets "stronger and stronger". The old
        // form-combat.damage-per-level-hearts knob is ignored if still in config.
        mobForms              = allMobForms();
    }

    /** Prefer config value; fall back to env var so prod can keep secrets out of git. */
    private String resolvedApiKey() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        String env = System.getenv("DEEPSEEK_API_KEY");
        return env == null ? "" : env.trim();
    }

    private void ensureSkinsFolder() {
        File skinsDir = new File(getDataFolder(), "skins");
        if (!skinsDir.isDirectory()) skinsDir.mkdirs();
        File example = new File(skinsDir, "example.json");
        if (!example.exists()) {
            try {
                Files.writeString(example.toPath(),
                        "{\n" +
                        "  \"_comment\": \"Replace value+signature with real ones from https://mineskin.org/. " +
                        "Until you do the companion will appear as a default head.\",\n" +
                        "  \"value\": \"\",\n" +
                        "  \"signature\": \"\"\n" +
                        "}\n", StandardCharsets.UTF_8);
            } catch (IOException ex) {
                getLogger().warning("(✧) couldn't write default example skin: " + ex.getMessage());
            }
        }
    }

    /** Find armor stands tagged as our companions and remove them on startup. */
    // Removed in the Phase-1 NMS rewrite: the companion is no longer an
    // ArmorStand, and Phase-1 NMS ServerPlayers aren't level-registered, so
    // there's nothing to find. Kept the spot intentionally — re-add when
    // Phase 2 lands and companions become persistent world entities.

    // ============== COMMAND ==============

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!"kawaiicompanion".equalsIgnoreCase(cmd.getName())) return false;

        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("kawaiicompanion.admin")) {
                sender.sendMessage("§d(✧) you don't have permission~");
                return true;
            }
            readCfg();
            sender.sendMessage("§d(✧) KawaiiCompanion reloaded ✨");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§c(✧) /kc must be run by a player");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("kawaiicompanion.use")) {
            p.sendMessage("§d(✧) you don't have permission~");
            return true;
        }

        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "summon":   doSummon(p);   return true;
            case "dismiss":  doDismiss(p);  return true;
            case "rename":   doRename(p, joinArgs(args, 1));   return true;
            case "skin":     doSkin(p, joinArgs(args, 1));     return true;
            case "skins":    doSkinsGui(p); return true;
            case "form":
            case "morph":    doForm(p, joinArgs(args, 1));     return true;
            case "info":     doInfo(p);     return true;
            case "mount":
            case "ride":     doMount(p);    return true;
            case "reset":    doReset(p);    return true;
            case "say":      doSay(p, joinArgs(args, 1));      return true;
            case "standby":  doStandby(p);  return true;
            case "clearinv":
            case "clearbag": doClearInv(p); return true;
            case "kill":     doKill(p, joinArgs(args, 1));     return true;
            case "build":
                if (!p.hasPermission("kawaiicompanion.build")) {
                    p.sendMessage("§c(✧) you don't have permission to build~");
                    return true;
                }
                if (buildManager != null) buildManager.openGui(p);
                return true;
            default:
                // Treat anything that isn't a known subcommand as a message:
                // /kc hello there → /kc say hello there
                doSay(p, joinArgs(args, 0));
                return true;
        }
    }

    private static String joinArgs(String[] args, int from) {
        if (from >= args.length) return "";
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    /** Put her on standby: hold position here and fight what's near (her or you). */
    private void doStandby(Player p) {
        Companion c = companions.get(p.getUniqueId());
        if (c == null || !isCompanionLive(c)) { p.sendMessage("§d(✧) summon her first ~"); return; }
        setMode(c, BehaviorMode.STANDBY, p, "on standby — holding here, ready to fight ♥");
    }

    /** Empty the bags of every companion this owner controls. */
    private void doClearInv(Player p) {
        List<Companion> all = ownerCompanions(p.getUniqueId());
        if (all.isEmpty()) { p.sendMessage("§d(✧) summon her first ~"); return; }
        for (Companion c : all) {
            if (c.inventory != null) c.inventory.clear();
            if (c.inventory2 != null) c.inventory2.clear();
        }
        // Only the primary is persisted (helpers share the owner's save file).
        Companion primary = companions.get(p.getUniqueId());
        if (primary != null) saveMemory(primary);
        p.sendMessage("§d(✧) her bags are empty now ~");
    }

    /**
     * Order her to hunt targets near you. Forms:
     *   /kc kill                → nearby hostile mobs
     *   /kc kill all            → all mobs (hostile + passive)
     *   /kc kill passive        → passive mobs only
     *   /kc kill players        → nearby players (needs permission + config)
     *   /kc kill &lt;type&gt;        → a specific entity type (e.g. zombie, creeper)
     *   /kc kill &lt;playerName&gt;  → a specific player
     *   /kc kill stop           → cancel the order
     */
    private void doKill(Player p, String arg) {
        Companion c = companions.get(p.getUniqueId());
        if (c == null || !isCompanionLive(c)) { p.sendMessage("§d(✧) summon her first ~"); return; }
        String a = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        if (a.equals("stop") || a.equals("clear") || a.equals("cancel") || a.equals("off")) {
            for (Companion cc : ownerCompanions(p.getUniqueId())) {
                cc.killTargets.clear(); cc.huntFilter.clear(); cc.killAnnounced = false;
            }
            p.sendMessage("§d(✧) standing down ~");
            return;
        }
        boolean wantHostile = false, wantPassive = false, wantPlayers = false;
        EntityType wantType = null; String wantName = null;
        switch (a) {
            case "", "hostile", "hostiles", "mobs", "mob" -> wantHostile = true;
            case "all", "everything", "any" -> { wantHostile = true; wantPassive = true; }
            case "passive", "passives", "animals", "animal" -> wantPassive = true;
            case "players", "player" -> wantPlayers = true;
            default -> {
                EntityType t = null;
                try { t = EntityType.valueOf(a.toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) { }
                if (t != null) wantType = t; else wantName = a;
            }
        }
        boolean canPlayers = allowKillPlayers && p.hasPermission("kawaiicompanion.kill.players");

        // ---- One-shot: a specific player by name ----
        if (wantName != null) {
            if (!canPlayers) { p.sendMessage("§c(✧) targeting players isn't allowed ~"); return; }
            Player tp = Bukkit.getPlayerExact(wantName);
            if (tp == null || tp.getUniqueId().equals(p.getUniqueId())) { p.sendMessage("§d(✧) can't find them ~"); return; }
            for (Companion cc : ownerCompanions(p.getUniqueId())) {
                cc.killTargets.add(tp.getUniqueId());
                cc.killAnnounced = true;
                showChatBubble(cc, "On it! ♥");
            }
            p.sendMessage("§d(✧) hunting §f" + tp.getName() + " ~");
            return;
        }

        // ---- Persistent hunt by type/category: she keeps after NEW ones too ----
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (wantHostile) keys.add("HOSTILE");
        if (wantPassive) keys.add("ANIMAL");
        if (wantPlayers) {
            if (!canPlayers) { p.sendMessage("§c(✧) targeting players isn't allowed ~"); return; }
            keys.add("PLAYERS");
        }
        if (wantType != null) keys.add(wantType.name());
        if (keys.isEmpty()) { p.sendMessage("§d(✧) hunt what? ~"); return; }
        for (Companion cc : ownerCompanions(p.getUniqueId())) {
            cc.huntFilter.clear();
            cc.huntFilter.addAll(keys);
            cc.killAnnounced = true;
            refillHuntTargets(cc, p);
            showChatBubble(cc, "On it! ♥");
        }
        p.sendMessage("§d(✧) hunting §f" + a + "§d — i'll keep after them ~ (§f/kc kill stop§d to end)");
    }

    /** Nearest live kill-list target to {@code from} (same world); prunes dead
     *  ones. Used by mob-form companions to drive their native AI. */
    private LivingEntity nearestKillTarget(Companion c, Location from) {
        if (c.killTargets.isEmpty() || from.getWorld() == null) return null;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        java.util.Iterator<UUID> it = c.killTargets.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity ent = Bukkit.getEntity(id);
            if (!(ent instanceof LivingEntity le) || le.isDead()) { it.remove(); continue; }
            if (le.getWorld() != from.getWorld()) continue;
            double d = le.getLocation().distanceSquared(from);
            if (d < bestSq) { bestSq = d; best = le; }
        }
        return best;
    }

    /** Add nearby, visible, matching mobs to the kill list so she keeps hunting
     *  the chosen type/category as new ones appear. */
    private void refillHuntTargets(Companion c, Player owner) {
        // NB: don't gate on c.entity — in mob form the packet NPC is despawned
        // (c.entity == null), so requiring it would stop mob forms from ever
        // picking up hunt targets. We just scan around the owner.
        if (owner == null || c.huntFilter.isEmpty()) return;
        boolean canPlayers = allowKillPlayers
                && (owner.isOp() || owner.hasPermission("kawaiicompanion.kill.players"));
        for (Entity e : owner.getNearbyEntities(killRadius, killRadius, killRadius)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (le.getUniqueId().equals(owner.getUniqueId())) continue;
            if (isCompanionEntity(le.getUniqueId())) continue;
            if (c.killTargets.contains(le.getUniqueId())) continue;
            if (matchesHunt(c.huntFilter, le, canPlayers)) c.killTargets.add(le.getUniqueId());
        }
    }

    /** Does {@code le} match the persistent hunt filter? */
    private boolean matchesHunt(java.util.Set<String> filter, LivingEntity le, boolean canPlayers) {
        if (le instanceof Player) return canPlayers && filter.contains("PLAYERS");
        if (filter.contains(le.getType().name())) return true;
        boolean h = isHostile(le);
        return (h && filter.contains("HOSTILE")) || (!h && filter.contains("ANIMAL"));
    }

    /**
     * Teach-and-repeat mining. You mine a pattern once; she records the block
     * sequence and reproduces it, looping onward so she keeps digging.
     *   /kc dig record  → start teaching (then mine your tunnel/staircase)
     *   /kc dig stop    → finish teaching, or halt an in-progress dig
     *   /kc dig go      → she digs your pattern from where you stand, looping
     *   /kc dig cancel  → forget the pattern
     */
    private void doDig(Player p, String arg) {
        Companion c = companions.get(p.getUniqueId());
        if (c == null || !isCompanionLive(c)) { p.sendMessage("§d(✧) summon her first ~"); return; }
        String a = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "record", "rec", "teach" -> {
                c.recordingDig = true;
                c.digging = false;
                c.digAnchor = p.getLocation().getBlock().getLocation();
                c.digRecordYaw = p.getLocation().getYaw(); // direction you're facing now = "forward"
                c.digPattern.clear();
                c.digLoopShift = null;
                p.sendMessage("§d(✧) recording — face the way you'll dig, mine your pattern, then §f/kc dig stop");
            }
            case "stop", "end", "halt" -> {
                if (c.recordingDig) {
                    c.recordingDig = false;
                    if (c.digPattern.isEmpty()) { p.sendMessage("§d(✧) you didn't break anything — try again ~"); return; }
                    c.digLoopShift = c.digPattern.get(c.digPattern.size() - 1).clone();
                    p.sendMessage("§d(✧) got it — §f" + c.digPattern.size() + "§d blocks. Use §f/kc dig go");
                } else if (c.digging) {
                    stopDig(c, "stopping ~");
                    p.sendMessage("§d(✧) stopped digging ~");
                } else {
                    p.sendMessage("§d(✧) nothing to stop ~");
                }
            }
            case "go", "play", "start", "repeat" -> {
                if (c.digPattern.isEmpty()) { p.sendMessage("§d(✧) teach me first: §f/kc dig record"); return; }
                if (c.digLoopShift == null && !c.digPattern.isEmpty()) {
                    c.digLoopShift = c.digPattern.get(c.digPattern.size() - 1).clone();
                }
                c.recordingDig = false;
                c.digging = true;
                c.digIndex = 0;
                c.digLoop = 0;
                c.digBase = p.getLocation().getBlock().getLocation();
                c.digWorldShift = c.digLoopShift == null ? null : c.digLoopShift.clone(); // world per-loop step
                c.nextDigActTick = 0;
                showChatBubble(c, "Digging your way! ♥");
                p.sendMessage("§d(✧) digging your pattern from here ~ (§f/kc dig stop§d to halt)");
            }
            case "cancel", "clear", "forget" -> {
                c.recordingDig = false;
                stopDig(c, null);
                c.digPattern.clear();
                c.digLoopShift = null;
                p.sendMessage("§d(✧) forgot the pattern ~");
            }
            default -> p.sendMessage("§7/kc dig record|stop|go|cancel");
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage("§d✿ KawaiiCompanion ✿");
        p.sendMessage("§7/kc summon §8— spawn your companion");
        p.sendMessage("§7/kc dismiss §8— despawn it");
        p.sendMessage("§7/kc say <msg> §8— talk to it (or just /kc <msg>)");
        p.sendMessage("§7/kc rename <name> §8— give it a new name");
        p.sendMessage("§7/kc skin <fileName> §8— skin from skins/<fileName>.json");
        p.sendMessage("§7/kc skins §8— open the appearances menu");
        p.sendMessage("§7/kc form <mob|human> §8— morph into any mob (she fights hostiles!)");
        p.sendMessage("§7/kc info §8— level, xp + abilities");
        p.sendMessage("§7/kc standby §8— hold position here & fight what's near");
        p.sendMessage("§7/kc kill <hostile|all|players|type|name> §8— hunt targets near you");
        p.sendMessage("§7/kc clearinv §8— empty her bags");
        p.sendMessage("§7/kc mount §8— ride your companion (real-entity only)");
        p.sendMessage("§7/kc reset §8— wipe your conversation memory");
        if (p.hasPermission("kawaiicompanion.build")) {
            p.sendMessage("§7/kc build §8— open the schematic build menu");
        }
        if (p.hasPermission("kawaiicompanion.admin")) {
            p.sendMessage("§7/kc reload §8— reload config (admin)");
        }
    }

    // ============== TAB COMPLETE ==============

    private static final List<String> TOP_LEVEL_SUBS =
            List.of("summon", "dismiss", "say", "rename", "skin", "skins", "form",
                    "info", "mount", "ride", "reset", "build",
                    "standby", "kill", "clearinv");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!"kawaiicompanion".equalsIgnoreCase(cmd.getName())) return Collections.emptyList();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> subs = new ArrayList<>(TOP_LEVEL_SUBS);
            if (sender.hasPermission("kawaiicompanion.admin")) subs.add("reload");
            return subs.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && "skin".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(listValidSkins());
            options.add("auto");
            return options.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && ("form".equalsIgnoreCase(args[0]) || "morph".equalsIgnoreCase(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            options.add("human");
            for (String f : mobForms) options.add(f.toLowerCase(Locale.ROOT));
            return options.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && "kill".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(List.of("hostile", "all", "passive", "players", "stop"));
            for (Player pl : Bukkit.getOnlinePlayers()) options.add(pl.getName());
            return options.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ============== SUMMON / DISMISS ==============

    /**
     * Should this owner get the REAL-ENTITY companion instead of the NMS
     * fake-player? Bedrock owners always do (Geyser can't render the fake
     * player), and any owner does when {@code bedrock-real-entity: true}.
     */
    private boolean useRealEntity(Player p) {
        return bedrockRealEntity || isBedrockPlayer(p);
    }

    private void doSummon(Player p) {
        Companion existing = companions.get(p.getUniqueId());
        // Already-summoned check covers BOTH render paths.
        if (existing != null && isCompanionLive(existing)) {
            p.sendMessage("§d(✧) " + existing.name + " is already with you~");
            if (existing.realEntity) {
                Entity re = liveRealEntity(existing);
                if (re != null) re.teleport(spawnLocFor(p));
            } else if (existing.entity != null) {
                existing.entity.teleport(spawnLocFor(p));
            }
            return;
        }
        // Real-entity path: Bedrock owners, forced via config, or a persisted
        // mob-form choice (FEATURE 5 — Java owners can morph too).
        Companion c = loadOrCreate(p);
        if (useRealEntity(p) || c.mobForm) {
            doSummonReal(p);
            return;
        }
        // Fire any pending PNG → JSON conversions before the spawn so a
        // freshly-dropped .png has a chance to become usable on this summon.
        // Uploads are async; if this is the first time the player runs /kc
        // summon after dropping a PNG they may need to re-summon once the
        // upload finishes.
        boolean uploadStarted = maybeConvertPngs();
        spawnEntity(p, c);
        companions.put(p.getUniqueId(), c);
        maybeNotifyBedrockOwner(p);
        String skin = resolveSkinName(c);
        if (skin == null) {
            if (uploadStarted) {
                p.sendMessage("§d(✧) §f" + c.name + "§d appeared ✨ §8(uploading your "
                        + ".png to mineskin.org — re-run §7/kc summon§8 in a few seconds)");
            } else {
                p.sendMessage("§d(✧) §f" + c.name + "§d appeared ✨ §8(no skin in skins/ — drop a "
                        + ".png or .json into plugins/KawaiiCompanion/skins/)");
            }
        } else {
            p.sendMessage("§d(✧) §f" + c.name + "§d appeared ✨ §8(skin: " + skin + ")");
        }
    }

    /**
     * Floodgate assigns Bedrock players UUIDs whose most-significant 64 bits are 0
     * (same fast check KawaiiMobChat uses). The companion is an NMS fake-player that
     * Geyser can't render, so Bedrock owners would see an invisible/broken companion.
     * Send them a one-time, non-blocking heads-up. Doesn't affect spawning.
     */
    private static boolean isBedrockPlayer(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L;
    }

    private void maybeNotifyBedrockOwner(Player p) {
        if (!isBedrockPlayer(p)) return;
        if (!bedrockNoticeShown.add(p.getUniqueId())) return;
        p.sendMessage("§d(✧) heads up~ on §fBedrock§d your companion is a friendly "
                + "real mob so you can actually see her ✨ (use §7/companion skins§d to "
                + "change her form)");
    }

    private void doDismiss(Player p) {
        // Send any op-summoned helper drones away too.
        List<Companion> ex = extras.remove(p.getUniqueId());
        if (ex != null) for (Companion e : ex) despawnCompanion(e);
        Companion c = companions.remove(p.getUniqueId());
        if (c == null) {
            if (ex != null && !ex.isEmpty()) { p.sendMessage("§d(✧) helpers dismissed ~"); return; }
            p.sendMessage("§d(✧) no companion to dismiss~");
            return;
        }
        if (c.realEntity) despawnRealEntity(c); else despawnEntity(c);
        saveMemory(c);
        p.sendMessage("§d(✧) §f" + c.name + "§d waved goodbye ~");
    }

    private void doRename(Player p, String newName) {
        if (newName == null || newName.isBlank()) {
            p.sendMessage("§d(✧) usage: /kc rename <name>");
            return;
        }
        if (newName.length() > 32) newName = newName.substring(0, 32);
        Companion c = companions.computeIfAbsent(p.getUniqueId(), u -> loadOrCreate(p));
        c.name = newName;
        // Custom name is set at spawn; mid-flight rename means a respawn so
        // the new tag is broadcast to everyone watching.
        if (c.entity != null && !c.entity.isDead()) {
            despawnEntity(c);
            spawnEntity(p, c);
        }
        saveMemory(c);
        p.sendMessage("§d(✧) renamed to §f" + newName);
    }

    private void doSkin(Player p, String skinName) {
        Companion c = companions.computeIfAbsent(p.getUniqueId(), u -> loadOrCreate(p));
        // Kick off any pending PNG → JSON uploads so a freshly-dropped
        // PNG starts converting whether or not /kc summon was run since.
        // Cheap when there's nothing to do (single directory listing).
        boolean uploadStarted = maybeConvertPngs();
        // No args → list what's available + show what's currently in use.
        if (skinName == null || skinName.isBlank()) {
            List<String> available = listValidSkins();
            List<String> pending = listPendingPngs();
            String resolved = resolveSkinName(c);
            p.sendMessage("§d(✧) skins in §fskins/§d folder:");
            if (available.isEmpty() && pending.isEmpty()) {
                p.sendMessage("  §8(none — drop <name>.json or <name>.png into plugins/KawaiiCompanion/skins/)");
            } else {
                for (String s : available) {
                    String marker = s.equals(resolved) ? " §a← in use" : "";
                    String over   = s.equals(c.skin)   ? " §7(your override)" : "";
                    p.sendMessage("  §7- §f" + s + marker + over);
                }
                for (String s : pending) {
                    p.sendMessage("  §7- §f" + s + " §e(uploading…)");
                }
            }
            p.sendMessage("§7/kc skin <name> §8— pick one  §8|  §7/kc skin auto §8— back to auto-pick");
            return;
        }
        if ("auto".equalsIgnoreCase(skinName)) {
            c.skin = null;
            if (c.entity != null && !c.entity.isDead()) {
                despawnEntity(c);
                spawnEntity(p, c);
            }
            saveMemory(c);
            String resolved = resolveSkinName(c);
            p.sendMessage("§d(✧) skin override cleared — auto-using §f"
                    + (resolved == null ? "(no skin)" : resolved));
            return;
        }
        // Strip ".png" or ".json" if the user typed the filename including
        // the extension — it's a natural mistake when they're looking at
        // the file in their explorer. Internally we always store the stem.
        String requested = skinName;
        String low = requested.toLowerCase(Locale.ROOT);
        if (low.endsWith(".png") || low.endsWith(".json")) {
            requested = requested.substring(0,
                    low.endsWith(".png") ? requested.length() - 4 : requested.length() - 5);
        }
        // Reject unknown names up front instead of silently falling back to
        // auto-pick — that fallback made it look like the command had no
        // effect and users couldn't tell whether they typed the name wrong
        // or the file itself was unreadable. Match case-insensitively but
        // store the canonical filename so file lookups still work on
        // case-sensitive filesystems.
        List<String> available = listValidSkins();
        String matched = null;
        for (String s : available) {
            if (s.equalsIgnoreCase(requested)) { matched = s; break; }
        }
        if (matched == null) {
            // Special case: a PNG with that name exists but hasn't finished
            // uploading yet. Tell the player explicitly so they don't keep
            // hammering the command thinking it's broken.
            List<String> pending = listPendingPngs();
            String pendingMatch = null;
            for (String s : pending) {
                if (s.equalsIgnoreCase(requested)) { pendingMatch = s; break; }
            }
            if (pendingMatch != null) {
                p.sendMessage("§d(✧) §f" + pendingMatch + ".png§d is still uploading to mineskin.org §8(takes ~3-10 sec)");
                p.sendMessage("§7  try §f/kc skin " + pendingMatch + "§7 again in a moment ~");
                return;
            }
            p.sendMessage("§c(✧) no skin named §f" + requested + "§c in skins/");
            if (available.isEmpty() && pending.isEmpty()) {
                p.sendMessage("§7  drop a .png or .json into plugins/KawaiiCompanion/skins/");
            } else {
                StringBuilder hint = new StringBuilder("§7  available: ");
                if (!available.isEmpty()) hint.append("§f").append(String.join("§7, §f", available));
                if (!pending.isEmpty()) {
                    if (!available.isEmpty()) hint.append("§7, ");
                    hint.append("§e").append(String.join(" (uploading), §e", pending)).append(" (uploading)");
                }
                p.sendMessage(hint.toString());
            }
            if (uploadStarted) {
                p.sendMessage("§7  §8(noticed a new .png — started uploading just now)");
            }
            return;
        }
        c.skin = matched;
        // Respawn so the new texture is broadcast to all watchers — NMS
        // doesn't easily support live skin updates without this.
        if (c.entity != null && !c.entity.isDead()) {
            despawnEntity(c);
            spawnEntity(p, c);
        }
        saveMemory(c);
        p.sendMessage("§d(✧) skin set to §f" + matched);
    }

    /**
     * List PNG file stems in {@code skins/} that don't yet have a paired
     * JSON. Used by {@link #doSkin} to surface "kitty.png is uploading"
     * states clearly so the player isn't left guessing whether the file
     * was even noticed.
     *
     * <p>Pending = PNG present, no matching JSON. The PNG might be
     * actively uploading, in backoff after a previous failure, or queued
     * for the next /kc skin / /kc summon. From the player's perspective
     * the answer is the same — "wait a moment".
     */
    private List<String> listPendingPngs() {
        List<String> out = new ArrayList<>();
        File skinsDir = new File(getDataFolder(), "skins");
        if (!skinsDir.isDirectory()) return out;
        File[] pngs = skinsDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (pngs == null) return out;
        Arrays.sort(pngs, Comparator.comparing(File::getName));
        for (File png : pngs) {
            String stem = png.getName().substring(0, png.getName().length() - 4);
            File json = new File(skinsDir, stem + ".json");
            if (json.isFile()) continue;
            out.add(stem);
        }
        return out;
    }

    private void doReset(Player p) {
        Companion c = companions.get(p.getUniqueId());
        if (c != null) {
            c.history.clear();
            saveMemory(c);
        } else {
            File f = memoryFile(p.getUniqueId());
            if (f.exists()) f.delete();
        }
        p.sendMessage("§d(✧) memory cleared ~");
    }

    /** Open the cosmetic appearances GUI (FEATURE 3). */
    private void doSkinsGui(Player p) {
        Companion c = companions.get(p.getUniqueId());
        if (c == null || !isCompanionLive(c)) {
            p.sendMessage("§d(✧) summon your companion first ~");
            return;
        }
        openSkinsGui(p, c);
    }

    /**
     * FEATURE 5: /kc form <mob|human> — morph the companion into any
     * living mob (it then fights nearby hostile mobs in that mob's own
     * style) or back to the fake-player ("human", Java owners only).
     */
    private void doForm(Player p, String arg) {
        // Use (or create) the record but DON'T gate on isCompanionLive: if her
        // mob body just died / is mid-respawn, the player must still be able to
        // pull her back to human form (or open the picker) to escape — those
        // paths rebuild her, so requiring a live body would trap her.
        Companion c = companions.computeIfAbsent(p.getUniqueId(), u -> loadOrCreate(p));
        if (arg == null || arg.isBlank()) {
            companions.put(p.getUniqueId(), c);
            openSkinsGui(p, c);
            return;
        }
        String key = arg.trim();
        if ("human".equalsIgnoreCase(key) || "skin".equalsIgnoreCase(key)
                || "player".equalsIgnoreCase(key) || "off".equalsIgnoreCase(key)) {
            companions.put(p.getUniqueId(), c);
            revertToHumanForm(p, c);
            return;
        }
        EntityType t = parseLivingEntityType(key.replace(' ', '_'));
        if (t == null) {
            p.sendMessage("§c(✧) unknown mob §f" + key + "§c — try tab-complete or §7/kc skins");
            return;
        }
        applyMobForm(p, c, t);
    }

    /** Morph the primary into {@code t} — and all her helpers with her. */
    private void applyMobForm(Player p, Companion c, EntityType t) {
        Entity spawned = applyMobFormTo(p, c, t);
        companions.put(p.getUniqueId(), c);
        saveMemory(c);
        if (spawned == null) {
            p.sendMessage("§c(✧) couldn't take that form — try another mob ~");
            return;
        }
        // Helpers match the new form too (they were staying human before).
        List<Companion> ex = extras.get(p.getUniqueId());
        if (ex != null) for (Companion h : ex) applyMobFormTo(p, h, t);
        String style = formCombatEnabled
                ? " §8(fights hostiles: " + styleLabel(formStyle(t)) + ")" : "";
        p.sendMessage("§d(✧) §f" + c.name + "§d is now a §f" + prettyForm(t.name()) + "§d ✨" + style);
    }

    /** Switch ONE companion's render path to a real-entity mob form. */
    private Entity applyMobFormTo(Player p, Companion c, EntityType t) {
        c.bedrockType = t.name();
        despawnRealEntity(c);
        despawnEntity(c); // also clears her packet NPC + navigator
        c.realEntity = true;
        c.mobForm = true;
        return spawnRealEntity(p, c);
    }

    /** Back to the NMS fake-player — for the primary AND her helpers. */
    private void revertToHumanForm(Player p, Companion c) {
        if (isBedrockPlayer(p) || bedrockRealEntity) {
            p.sendMessage("§d(✧) the human form isn't available here — Bedrock "
                    + "(and §7bedrock-real-entity: true§d) can only render mob forms ~");
            return;
        }
        if (!c.realEntity && !c.mobForm) {
            p.sendMessage("§d(✧) " + c.name + " is already in her human form ~");
            return;
        }
        revertToHumanFormTo(p, c);
        companions.put(p.getUniqueId(), c);
        saveMemory(c);
        List<Companion> ex = extras.get(p.getUniqueId());
        if (ex != null) for (Companion h : ex) revertToHumanFormTo(p, h);
        p.sendMessage("§d(✧) §f" + c.name + "§d is back in her human form ✨");
    }

    private void revertToHumanFormTo(Player p, Companion c) {
        despawnRealEntity(c);
        despawnEntity(c);
        c.realEntity = false;
        c.mobForm = false;
        spawnEntity(p, c);
    }

    /** Print companion level / xp / abilities status (FEATURE 2). */
    private void doInfo(Player p) {
        Companion c = companions.computeIfAbsent(p.getUniqueId(), u -> loadOrCreate(p));
        p.sendMessage("§d✿ " + c.name + " ✿");
        if (levelingEnabled) {
            if (c.level >= maxLevel) {
                p.sendMessage("§7Level: §f" + c.level + " §8(max)");
            } else {
                p.sendMessage("§7Level: §f" + c.level + "  §7XP: §f" + c.xp + "§7/§f" + xpForLevel(c.level));
            }
            StringBuilder ab = new StringBuilder("§7Abilities: ");
            List<String> on = new ArrayList<>();
            if (abilitySpeed) on.add("speed");
            if (abilityHealAura) on.add("heal-aura" + (c.level >= healAuraUnlockLevel ? "§a(active)§7" : "§8(Lv" + healAuraUnlockLevel + ")§7"));
            if (abilityCombatAssist) on.add("assist");
            ab.append(on.isEmpty() ? "§8none" : "§f" + String.join("§7, §f", on));
            p.sendMessage(ab.toString());
        } else {
            p.sendMessage("§7Leveling is disabled on this server.");
        }
        p.sendMessage("§7Form: §f" + (c.realEntity
                ? resolveBedrockType(c).name().toLowerCase(Locale.ROOT) + " (real entity)"
                : "fake-player"));
    }

    // ============== ENTITY ==============

    /**
     * Pick a spawn / teleport position next to {@code p}. We offset 1.5
     * blocks to the player's right so the companion isn't standing
     * inside them, BUT we validate the offset spot is over solid ground
     * before returning it — otherwise an owner standing on a 1-wide
     * ledge would emergency-teleport the companion into the void next
     * to him, dropping her all the way to the bottom.
     *
     * <p>Validation is "is the block below the offset cell non-passable?".
     * If not, we fall back to the owner's exact location, which is
     * presumably standing on something (the player can't realistically
     * stay still on a position with no ground). Worst case the
     * companion overlaps the player for one tick before nudging out.
     */
    private Location spawnLocFor(Player p) {
        Vector right = p.getLocation().getDirection().setY(0).normalize()
                .rotateAroundY(-Math.PI / 2.0).multiply(1.5);
        Location candidate = p.getLocation().add(right);
        World w = candidate.getWorld();
        if (w == null) return p.getLocation();
        // Validate ground under the offset spot AND that the offset spot
        // itself is passable (not a wall). Either failure → use owner's
        // exact location, which is presumably safe.
        try {
            int bx = candidate.getBlockX();
            int by = candidate.getBlockY();
            int bz = candidate.getBlockZ();
            Block belowOffset = w.getBlockAt(bx, by - 1, bz);
            Block atOffset    = w.getBlockAt(bx, by, bz);
            // Allow the offset spot to be water/air/grass; reject if it's
            // a solid wall block we'd be teleporting INTO.
            if (atOffset.getType().isSolid() && atOffset.getType().isOccluding()) {
                return p.getLocation();
            }
            // Reject "no ground" — if there's nothing below to stand on
            // (e.g. owner on a peninsula edge looking out over a cliff)
            // fall back to owner's spot.
            if (belowOffset.isPassable()
                    && belowOffset.getType() != Material.WATER
                    && belowOffset.getType() != Material.LAVA) {
                return p.getLocation();
            }
        } catch (Throwable ignored) {
            return p.getLocation();
        }
        return candidate;
    }

    /** True when there's no solid block within {@code depth} blocks below — i.e.
     *  she's hanging over open void (used to catch falls off a skyblock island). */
    private boolean noGroundBelow(Location from, int depth) {
        World w = from.getWorld();
        if (w == null) return false;
        int x = from.getBlockX(), y = from.getBlockY(), z = from.getBlockZ();
        int min = w.getMinHeight();
        for (int d = 1; d <= depth; d++) {
            int yy = y - d;
            if (yy < min) break;
            try {
                if (w.getBlockAt(x, yy, z).getType().isSolid()) return false;
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private void spawnEntity(Player owner, Companion c) {
        Location loc = spawnLocFor(owner);

        // Resolve the skin and read its texture pair (value, signature).
        // No skin in skins/ → blank profile (companion appears as default Steve).
        String skinName = resolveSkinName(c);
        String texValue = null, texSig = null;
        if (skinName != null) {
            File f = new File(getDataFolder(), "skins" + File.separator + skinName + ".json");
            try {
                String[] tex = readTexture(f);
                if (tex != null) { texValue = tex[0]; texSig = tex[1]; }
                else {
                    getLogger().warning("(✧) skin '" + skinName + "' parsed but has no texture");
                }
            } catch (Throwable t) {
                getLogger().warning("(✧) couldn't load skin '" + skinName + "': " + t.getMessage());
            }
        }

        // Profile UUID is derived from the texture *and* the owner so the
        // client treats each owner's companion as a distinct profile (two
        // players using the same skin would otherwise collide in the
        // tablist). The texture cache itself is keyed by the texture URL
        // inside the property, so we don't lose any caching benefit. No
        // skin → random UUID so the client doesn't conflate two unskinned
        // companions.
        UUID profileId = (texValue != null)
                ? UUID.nameUUIDFromBytes(
                        (texValue + ":" + owner.getUniqueId()
                                + (c.profileSalt != null ? ":" + c.profileSalt : ""))
                                .getBytes(StandardCharsets.UTF_8))
                : UUID.randomUUID();
        String profileName = sanitizeProfileName(c.name);

        Color tint = leatherTint != null ? leatherTint : Color.fromRGB(255, 180, 220);
        ItemStack chestplate = wearLeatherArmor ? dyedLeather(Material.LEATHER_CHESTPLATE, tint) : null;
        ItemStack leggings   = wearLeatherArmor ? dyedLeather(Material.LEATHER_LEGGINGS,   tint) : null;
        ItemStack boots      = wearLeatherArmor ? dyedLeather(Material.LEATHER_BOOTS,      tint) : null;

        boolean glow = !(glowColor == null || glowColor.equalsIgnoreCase("none"));
        String displayName = showNameTag ? companionDisplayName(c) : null;

        NmsCompanion npc = NmsCompanion.spawn(this, profileName, profileId,
                texValue, texSig, loc, displayName,
                chestplate, leggings, boots, glow, worldIntegrated);
        if (npc == null) {
            owner.sendMessage("§c(✧) couldn't spawn companion — NMS failed (see server console)");
            return;
        }
        c.entity = npc;

        // Spawn the click hitbox: a real (but invisible / non-rendering)
        // Interaction entity sized like a player. PlayerInteractEntityEvent
        // fires when the owner right-clicks it, which is how we open the
        // control GUI. Without this the right-click goes nowhere — fake
        // ServerPlayers aren't in the entity tracker so the vanilla
        // interaction packet can't resolve them.
        spawnHitbox(c, loc);

        // If she's been given a weapon previously (came back from /kc
        // dismiss + summon, or the skin was reapplied), put it back in
        // her hand on respawn so the equip survives.
        equipBestWeapon(c);
    }

    private void despawnEntity(Companion c) {
        if (c.entity != null) c.entity.despawn();
        c.entity = null;
        despawnNavigator(c);
        despawnHitbox(c);
        despawnBubble(c);
        // Drop any active path — the next summon will start somewhere
        // new and the old waypoints would walk her back to wherever she
        // was when dismissed.
        clearPath(c);
        c.stuckTicks = 0;
        // Fresh ServerPlayer on next spawn defaults to STANDING pose;
        // clear our cached state so the first movement tick after a
        // re-summon will re-broadcast whatever pose she should be in.
        c.currentPose = "STANDING";
        c.desiredPose = "STANDING";
        c.desiredPoseStableTicks = 0;
        c.ambientCrouchUntil = 0;
        // Swim animation residue — pitch and bob offset.
        c.swimPitchSmoothed = 0;
        c.swimLastY = 0;
        c.swimBobOffset = 0;
        // Behavior-extras state — same rationale: a re-summon should
        // start with a clean slate so a stale "owner was far" flag from
        // last session doesn't trigger a phantom greet wave on respawn.
        c.sprintFlag = false;
        c.lastGreetWaveTick = 0;
        c.ownerWasFarLastTick = false;
        c.lastEntityLookTick = 0;
        c.lastFidgetTick = 0;
        c.lastHopSwingTick = 0;
        c.thinkingCrouchUntil = 0;
        c.preSleepYawnUntil = 0;
        c.yawnPlayedThisIdle = false;
        // Stuck-escape — clear so a re-summon doesn't think she's still digging.
        c.escapeStartTick = 0;
        c.escapeBlocksBroken = 0;
        c.escapeLastBreakTick = 0;
        c.escapeNextAllowedTick = 0;
        // Player-state monitor — reset baselines so the first sample
        // after re-summon doesn't fire a spurious "you just dropped HP" warning.
        c.lastSampledOwnerHp = 20.0;
        c.lastSampledOwnerFood = 20;
        c.lastSampledOwnerFire = 0;
        c.lastSampledOwnerAir = 300;
        c.lastStateWarnTick = 0;
    }

    /**
     * Pick the skin to use for a companion. Order of preference:
     *   1. the player's explicit override ({@code c.skin}) if that file is still
     *      present and has a non-blank {@code value}
     *   2. the configured {@code default-skin} if valid
     *   3. the first valid skin in {@code skins/} sorted alphabetically
     *   4. {@code null} (companion wears a blank head)
     * This is what makes "drop a skin file in skins/ and it just works"
     * work without /kc skin <name>.
     */
    private String resolveSkinName(Companion c) {
        List<String> available = listValidSkins();
        if (c.skin != null && available.contains(c.skin)) return c.skin;
        if (defaultSkin != null && available.contains(defaultSkin)) return defaultSkin;
        if (!available.isEmpty()) return available.get(0);
        return null;
    }

    /** All {@code skins/*.json} whose JSON has a parseable texture value. */
    private List<String> listValidSkins() {
        List<String> out = new ArrayList<>();
        File skinsDir = new File(getDataFolder(), "skins");
        if (!skinsDir.isDirectory()) return out;
        File[] files = skinsDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            try {
                String[] tex = readTexture(f);
                if (tex == null) {
                    // The plugin ships a placeholder "example.json" so users
                    // see what the file format looks like. Don't warn about
                    // that one specifically — it's expected to be a stub.
                    String stem = f.getName().substring(0, f.getName().length() - 5);
                    if ("example".equalsIgnoreCase(stem)) continue;
                    // Warn once per (file, mtime) so we surface the diagnosis
                    // without spamming the log on every /kc summon.
                    String key = f.getName() + "@" + f.lastModified();
                    if (warnedSkinFiles.add(key)) {
                        getLogger().warning("(✧) skin file " + f.getName()
                                + " parsed but has no usable texture — expected "
                                + "{value,signature}, a mineskin v2 response, or a "
                                + "Mojang sessionserver dump");
                    }
                    continue;
                }
                String n = f.getName();
                out.add(n.substring(0, n.length() - 5));   // strip ".json"
            } catch (Throwable t) {
                getLogger().warning("(✧) skin file " + f.getName()
                        + " unreadable: " + t.getMessage());
            }
        }
        return out;
    }

    /**
     * Read a skin file and return {@code [value, signature]} (signature may be
     * empty), or {@code null} if no usable texture is found.
     *
     * <p>Tolerates the common shapes a player might paste in:
     * <ul>
     *   <li>{@code {"value":"...","signature":"..."}} — what the README documents</li>
     *   <li>{@code {"skin":{"texture":{"data":{"value":"...","signature":"..."}}}}} —
     *       mineskin v2 (current; what {@code /v2/generate} actually returns)</li>
     *   <li>{@code {"data":{"texture":{"value":"...","signature":"..."}}}} — older mineskin shape</li>
     *   <li>{@code {"texture":{"value":"...","signature":"..."}}} — mineskin v1 / minetools</li>
     *   <li>{@code {"properties":[{"name":"textures","value":"...","signature":"..."}]}} —
     *       Mojang sessionserver dump</li>
     * </ul>
     */
    private String[] readTexture(File f) throws IOException {
        String raw = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        String[] direct = pickTexturePair(root);
        if (direct != null) return direct;
        // mineskin v2: {"skin":{"texture":{"data":{"value":"...","signature":"..."}}}}
        if (root.has("skin") && root.get("skin").isJsonObject()) {
            JsonObject skin = root.getAsJsonObject("skin");
            if (skin.has("texture") && skin.get("texture").isJsonObject()) {
                JsonObject texture = skin.getAsJsonObject("texture");
                if (texture.has("data") && texture.get("data").isJsonObject()) {
                    String[] m = pickTexturePair(texture.getAsJsonObject("data"));
                    if (m != null) return m;
                }
                String[] m = pickTexturePair(texture);
                if (m != null) return m;
            }
        }
        if (root.has("data") && root.get("data").isJsonObject()) {
            JsonObject data = root.getAsJsonObject("data");
            if (data.has("texture") && data.get("texture").isJsonObject()) {
                String[] m = pickTexturePair(data.getAsJsonObject("texture"));
                if (m != null) return m;
            }
        }
        if (root.has("texture") && root.get("texture").isJsonObject()) {
            String[] m = pickTexturePair(root.getAsJsonObject("texture"));
            if (m != null) return m;
        }
        if (root.has("properties") && root.get("properties").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("properties")) {
                if (!el.isJsonObject()) continue;
                JsonObject prop = el.getAsJsonObject();
                String name = prop.has("name") && !prop.get("name").isJsonNull()
                        ? prop.get("name").getAsString() : "";
                if (!"textures".equalsIgnoreCase(name)) continue;
                String[] m = pickTexturePair(prop);
                if (m != null) return m;
            }
        }
        return null;
    }

    private static String[] pickTexturePair(JsonObject o) {
        if (!o.has("value") || o.get("value").isJsonNull()) return null;
        String v = o.get("value").getAsString();
        if (v.isBlank()) return null;
        String s = (o.has("signature") && !o.get("signature").isJsonNull())
                ? o.get("signature").getAsString() : "";
        return new String[]{v, s};
    }

    // ============== PNG → MINESKIN AUTO-UPLOAD ==============

    /**
     * Look for {@code skins/*.png} files that don't have a matching {@code .json}
     * yet and kick off an async upload to mineskin.org. The response is saved
     * verbatim as {@code <stem>.json}; {@link #readTexture} already understands
     * mineskin's nested layout so no post-processing is needed.
     *
     * @return true if at least one upload was started this call
     */
    private boolean maybeConvertPngs() {
        if (!autoUploadPngs) return false;
        File skinsDir = new File(getDataFolder(), "skins");
        if (!skinsDir.isDirectory()) return false;
        File[] pngs = skinsDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (pngs == null || pngs.length == 0) return false;
        boolean any = false;
        long now = System.currentTimeMillis();
        for (File png : pngs) {
            String stem = png.getName().substring(0, png.getName().length() - 4);
            File json = new File(skinsDir, stem + ".json");
            if (json.isFile()) continue;
            // Don't re-upload while one's in flight, and respect backoff after errors.
            if (!pngUploadInFlight.add(png.getName())) continue;
            Long until = pngBackoffUntilMs.get(png.getName());
            if (until != null && now < until) {
                pngUploadInFlight.remove(png.getName());
                continue;
            }
            any = true;
            getLogger().info("(✧) uploading " + png.getName() + " to mineskin.org…");
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    uploadPng(png, json);
                } finally {
                    pngUploadInFlight.remove(png.getName());
                }
            });
        }
        return any;
    }

    /** Blocking; caller must run off the main thread. */
    private void uploadPng(File png, File json) {
        try {
            String boundary = "kc-" + Long.toHexString(System.nanoTime());
            byte[] body = buildMultipartPng(png, boundary);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(mineskinUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "KawaiiCompanion/1.0 (+https://github.com/ferisooo/minecraft-plugins)")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            // API key (optional). Mineskin's free anonymous tier gives ~0
            // uploads/hour as of 2026; signing up at account.mineskin.org
            // and pasting the key into config.yml lifts that significantly.
            String apiKey = mineskinApiKey;
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // Friendlier rate-limit detection — when the response is the
                // standard mineskin {"success":false,"rateLimit":{...}} envelope
                // we surface a concrete next-retry hint AND mention the API
                // key option so users understand the fix instead of the wall-
                // of-JSON.
                String hint = describeMineskinError(resp.body());
                getLogger().warning("(✧) mineskin upload failed for " + png.getName()
                        + " — HTTP " + resp.statusCode() + (hint.isEmpty() ? "" : " — " + hint));
                pngBackoffUntilMs.put(png.getName(),
                        System.currentTimeMillis() + mineskinBackoffSeconds * 1000L);
                return;
            }
            // Sanity-check: response must contain something readTexture can use.
            // If not, save it under a .failed.json suffix so we don't overwrite
            // and keep retrying.
            JsonObject preview;
            try {
                preview = JsonParser.parseString(resp.body()).getAsJsonObject();
            } catch (Throwable bad) {
                getLogger().warning("(✧) mineskin returned non-JSON for "
                        + png.getName() + ": " + bad.getMessage());
                pngBackoffUntilMs.put(png.getName(),
                        System.currentTimeMillis() + mineskinBackoffSeconds * 1000L);
                return;
            }
            File parent = json.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.writeString(json.toPath(), resp.body(), StandardCharsets.UTF_8);
            getLogger().info("(✧) saved " + json.getName()
                    + " — /kc summon (or /kc skin " + json.getName().replace(".json", "")
                    + ") to use it");
        } catch (Throwable t) {
            getLogger().warning("(✧) couldn't upload " + png.getName() + ": " + t);
            pngBackoffUntilMs.put(png.getName(),
                    System.currentTimeMillis() + mineskinBackoffSeconds * 1000L);
        }
    }

    /** Build a multipart/form-data body containing one {@code file=<png bytes>} part. */
    private static byte[] buildMultipartPng(File png, String boundary) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + png.getName().replace("\"", "")
                + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(png.toPath()));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * Pretty-print a mineskin error response. The standard envelope is
     * {@code {"success":false,"rateLimit":{...},"error":"..."}}; we look
     * for the rateLimit + error fields and produce a single readable
     * sentence the player / server admin can act on.
     *
     * <p>Specifically we detect the "anonymous tier hour limit = 0"
     * shape (which is the common one in 2026) and explicitly tell the
     * user to set {@code mineskin-api-key} in config.yml. Without this
     * hint they'd just see the raw JSON and conclude the plugin is
     * broken when it's actually a service-side policy change.
     */
    private static String describeMineskinError(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            // Pull the human-readable error first if present.
            String err = null;
            if (obj.has("error") && !obj.get("error").isJsonNull()) {
                err = obj.get("error").getAsString();
            }
            // Detect "anonymous tier — 0/hour" shape.
            if (obj.has("rateLimit") && obj.get("rateLimit").isJsonObject()) {
                JsonObject rl = obj.getAsJsonObject("rateLimit");
                JsonObject limit = rl.has("limit") && rl.get("limit").isJsonObject()
                        ? rl.getAsJsonObject("limit") : null;
                JsonObject hour = limit != null && limit.has("hour") && limit.get("hour").isJsonObject()
                        ? limit.getAsJsonObject("hour") : null;
                if (hour != null && hour.has("limit") && hour.get("limit").getAsInt() == 0) {
                    return "mineskin's anonymous tier is rate-limited to 0/hour. "
                            + "set mineskin-api-key in config.yml (free at https://account.mineskin.org/) "
                            + "or upload manually at https://mineskin.org/";
                }
                // Generic rate-limit message.
                if (rl.has("next") && rl.get("next").isJsonObject()) {
                    JsonObject next = rl.getAsJsonObject("next");
                    if (next.has("relative")) {
                        long ms = next.get("relative").getAsLong();
                        return "rate limited (try again in ~" + Math.max(1, ms / 1000) + "s)"
                                + (err != null ? " — " + err : "");
                    }
                }
            }
            if (err != null) return err;
        } catch (Throwable ignored) {
            // fall through to the raw-body fallback
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    /**
     * Strip {@code s} to a Mojang-legal profile name: at most 16 chars of
     * {@code [A-Za-z0-9_]}, never blank. Long filenames or names with hyphens
     * cause the GameProfile to be rejected by the client and the skin won't
     * render — this keeps the profile valid regardless of what the user
     * named the companion or the skin file.
     */
    private static String sanitizeProfileName(String s) {
        if (s == null) return "Companion";
        String stripped = s.replaceAll("[^A-Za-z0-9_]", "");
        if (stripped.isBlank()) return "Companion";
        return stripped.length() > 16 ? stripped.substring(0, 16) : stripped;
    }

    private static ItemStack dyedLeather(Material mat, Color color) {
        ItemStack it = new ItemStack(mat);
        var meta = it.getItemMeta();
        if (meta instanceof LeatherArmorMeta lam) {
            lam.setColor(color);
            it.setItemMeta(lam);
        }
        return it;
    }

    /** Parse a 6-digit hex string ("FFB4DC" or "#FFB4DC") into a Color. */
    private static Color parseHexColor(String hex) {
        if (hex == null) return Color.fromRGB(255, 180, 220);
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return Color.fromRGB(255, 180, 220);
        try {
            int r = Integer.parseInt(s.substring(0, 2), 16);
            int g = Integer.parseInt(s.substring(2, 4), 16);
            int b = Integer.parseInt(s.substring(4, 6), 16);
            return Color.fromRGB(r, g, b);
        } catch (NumberFormatException e) {
            return Color.fromRGB(255, 180, 220);
        }
    }

    // ============== MOVEMENT ==============

    private void movementTick() {
        long tick = ++behaviorTickCount;
        // Run scheduled build / revert steps even when no companions are
        // present this tick (a build can outlive a brief dismiss + resummon).
        if (buildManager != null) buildManager.tickAll(tick);
        // FEATURE 5 self-heal: periodically remove any loaded marker-tagged
        // companion mob that no live record owns. Catches duplicates from
        // whatever race slipped past the tracked despawns (relogs, world
        // changes, chunk timing) within a minute. Marker-only — never
        // touches other plugins' mobs.
        if (tick % 600 == 0) {
            org.bukkit.NamespacedKey markerKey = companionMarkerKey();
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    boolean marked = false;
                    try {
                        marked = e.getPersistentDataContainer().has(markerKey,
                                org.bukkit.persistence.PersistentDataType.STRING);
                    } catch (Throwable ignored) {}
                    if (!marked) continue;
                    // Only an UNTRACKED marker-tagged mob is an orphan to clean.
                    if (isCompanionEntity(e.getUniqueId())) continue;
                    try { e.remove(); } catch (Throwable ignored) {}
                }
            }
        }
        if (companions.isEmpty() && extras.isEmpty()) return;
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Companion> e : companions.entrySet()) {
            Companion c = e.getValue();
          try {
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner == null || !owner.isOnline()) { stale.add(e.getKey()); continue; }

            // FEATURE 1: real-entity (Bedrock) companions take a completely
            // separate, lightweight tick — follow + abilities — and never
            // touch the NMS fake-player path below.
            if (c.realEntity) {
                tickRealEntity(c, owner, tick);
                continue;
            }

            // No entity → spawn failed earlier or it was despawned. Don't
            // auto-respawn here; that turns a one-shot reflection error
            // into a 5x/sec chat spam. /kc summon is the explicit retry.
            if (c.entity == null || c.entity.isDead()) continue;

            // Keep the click hitbox glued to the companion's current
            // position. Cheap (per-companion teleport on a non-rendering
            // entity), and means players can right-click her wherever
            // she actually is.
            updateHitbox(c);

            // Mode dispatch. FOLLOW is the original behavior; the others
            // are added for the right-click GUI. Skip when a build job owns
            // her — BuildManager teleports her around directly, and normal
            // follow/guard ticks would just yank her back to the owner.
            if (!c.isBuilding) {
                // Keep doors near her path open so she stops getting trapped
                // (skip in STAY where she's deliberately holding still).
                if (c.mode != BehaviorMode.STAY) openNearbyDoors(c);
                // Priority overrides (any mode): a dig job first, then an
                // explicit kill order, then Herobrine. Otherwise normal mode.
                if (c.digging) {
                    runDigJob(c, owner, tick);
                } else if (engageKillTargets(c, tick, owner)) {
                    // hunting an ordered target — overrides normal behaviour
                } else if (!engageHerobrine(c, tick, owner)) {
                    switch (c.mode) {
                        case FOLLOW  -> stepToward(c, owner);
                        case STAY    -> idleAnimate(c, tick);
                        case GUARD   -> guardTick(c, tick);
                        case SCOUT   -> scoutTick(c, tick);
                        case STANDBY -> standbyTick(c, tick);
                    }
                }
            } else {
                idleAnimate(c, tick); // look alive while standing at the build site
            }

            // Keep her navigator glued to her on any tick FOLLOW didn't drive it
            // (other modes, kill/dig override, building), so it's ready + doesn't wander.
            if (c.navMob != null && c.navDrivenTick != tick) parkNavigator(c);

            // Per-tick chat housekeeping (independent of mode).
            clearChatBubbleIfDue(c, tick);
            tryAmbientChat(c, tick);

            // Refresh activity timer when the owner's nearby — keeps her
            // from dozing off while you're standing right next to her.
            if (owner.getWorld() == c.entity.getWorld()
                    && owner.getLocation().distance(c.entity.getLocation()) < ACTIVITY_OWNER_RADIUS) {
                c.lastActivityTick = tick;
                // Activity reset → next idle cycle gets a fresh yawn.
                c.yawnPlayedThisIdle = false;
            }

            // Pre-sleep yawn — fire a single arm-swing + brief crouch
            // window 1 s before the SLEEPING transition. Cheap when
            // disabled or already played (one boolean check).
            tryPreSleepYawn(c, tick);

            // Player-state monitor — sample owner HP / hunger / fire /
            // air and bubble a contextual warning on edge transitions.
            // Independent of pose / movement; runs every movement tick
            // but the warnings themselves are throttled per-companion.
            if (playerStateMonitorEnabled) {
                monitorPlayerState(c, owner, tick);
            }

            // Vacuum nearby dropped items and XP orbs into her bag.
            // Internally throttled by pickupScanInterval — cheap when
            // there's nothing in range (one getNearbyEntities call).
            if (pickupEnabled) {
                tickPickup(c, owner, tick);
            }

            // Sync pose. Cheap when state's unchanged (just block-type
            // lookups + a String compare); only broadcasts a packet on
            // actual transitions.
            updatePoseState(c, tick);

            // Swim animation polish — only meaningful while the pose is
            // SWIMMING but cheap to call unconditionally (early-returns
            // when not swimming). applyDivePitch must run BEFORE
            // applySwimBob so the pitch's Y-velocity sample isn't
            // contaminated by the bob's per-tick Y delta.
            applyDivePitch(c);
            applySwimBob(c);

            // FEATURE 2: passive XP + ability pulses (heal aura). Cheap when
            // disabled (a couple of boolean checks). Combat-kill XP comes in
            // through onEntityDeath; this handles the passive + aura side.
            tickLeveling(c, owner, tick);
          } catch (Throwable t) {
            // Isolate a single companion's failure so it can't abort the
            // rest of this tick's companions. The timer itself survives
            // (Bukkit catches), but without this one bad entity would freeze
            // every other player's companion for that tick.
            getLogger().warning("(✧) companion tick failed for " + e.getKey() + ": " + t);
          }
        }
        for (UUID id : stale) {
            Companion c = companions.remove(id);
            if (c != null) { saveMemory(c); if (c.realEntity) despawnRealEntity(c); else despawnEntity(c); }
        }

        // Op-only helper drones — lean tick (no chat). Despawn an owner's
        // helpers when they log off.
        if (!extras.isEmpty()) {
            for (Map.Entry<UUID, List<Companion>> en : extras.entrySet()) {
                Player owner = Bukkit.getPlayer(en.getKey());
                java.util.Iterator<Companion> it = en.getValue().iterator();
                while (it.hasNext()) {
                    Companion c = it.next();
                    if (owner == null || !owner.isOnline()) { despawnCompanion(c); it.remove(); continue; }
                    tickExtra(c, owner, tick);
                }
            }
        }
    }

    /**
     * Pick the right render pose for the companion this tick and
     * broadcast it (only on transitions). Priority is intentionally
     * fixed — situational poses (swimming, falling) always win over
     * personality poses (sleeping, crouching) so she never looks like
     * she's napping during a 30-block freefall.
     *
     * <p>Order:
     * <ol>
     *   <li><b>SWIMMING</b> — foot in water AND block below also in
     *       water. The deep-water case where the swim animation looks
     *       right; 1-block puddles ("wading") stay STANDING.</li>
     *   <li><b>FALL_FLYING</b> — at least 3 passable blocks below her
     *       feet. Renders as the elytra dive pose, which without an
     *       actual elytra equipped looks like she's plummeting with her
     *       arms forward — exactly the right vibe for a yoinked-off-
     *       a-cliff moment.</li>
     *   <li><b>SLEEPING</b> — STAY mode AND no activity for 90+ seconds.
     *       Player model lies flat at her position, like she dozed off
     *       waiting. Visually pretty cute even without a bed.</li>
     *   <li><b>CROUCHING</b> — ambient idle fidget set by
     *       {@link #idleLook}. Brief sneak pose for personality.</li>
     *   <li><b>STANDING</b> — default.</li>
     * </ol>
     *
     * <p>Cheap when state's unchanged (a few block-type lookups + a
     * String comparison); only broadcasts a packet on actual
     * transitions thanks to {@link Companion#currentPose} caching.
     */
    private void updatePoseState(Companion c, long tick) {
        if (c.entity == null || c.entity.isDead()) return;
        String desired = computeDesiredPose(c, tick);

        // Hysteresis — require the desired pose to be stable for a few
        // movement ticks before we actually switch. Kills flicker at
        // water edges (foot just barely touched water then left) and
        // makes transitions feel deliberate rather than twitchy.
        // FALL_FLYING is exempt — falling needs to register immediately
        // since the visual mismatch is most jarring there.
        if (!desired.equals(c.desiredPose)) {
            c.desiredPose = desired;
            c.desiredPoseStableTicks = 0;
        } else {
            c.desiredPoseStableTicks++;
        }
        // FALL_FLYING needs to register instantly (the visual mismatch is most
        // jarring there). Leaving SWIMMING is also exempt from hysteresis: once
        // she's out of the water she should stand upright immediately rather
        // than spend several ticks looking like she's swimming across the
        // ground.
        boolean leavingSwim = "SWIMMING".equals(c.currentPose) && !"SWIMMING".equals(desired);
        int requiredStable = ("FALL_FLYING".equals(desired) || leavingSwim) ? 0 : POSE_HYSTERESIS_TICKS;
        if (c.desiredPoseStableTicks < requiredStable) return;

        if (desired.equals(c.currentPose)) return;
        c.currentPose = desired;
        c.entity.setPose(desired);
    }

    /** How many movement ticks a pose change must be stable before applying. */
    private static final int POSE_HYSTERESIS_TICKS = 4; // ~0.4s @ 10 Hz movement

    private String computeDesiredPose(Companion c, long tick) {
        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) return "STANDING";
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // 1. Swimming — foot in water AND not just dry-wading. Two
        //    cases trigger it:
        //      • below also water → surface swim (floating at the top
        //        of a body of water);
        //      • head also water → underwater swim (diving with the
        //        owner inside a water column).
        //    Pure 1-block puddle (foot=water, below=solid, head=air)
        //    keeps STANDING pose so wading doesn't trip a swim animation.
        Material foot  = w.getBlockAt(x, y, z).getType();
        Material below = w.getBlockAt(x, y - 1, z).getType();
        Material head  = w.getBlockAt(x, y + 1, z).getType();
        if (foot == Material.WATER
                && (below == Material.WATER || head == Material.WATER)) {
            return "SWIMMING";
        }

        // 2. Falling — 3+ passable blocks below her feet means she's
        //    in mid-air. Bound the scan at 6 to keep it cheap.
        int airBelow = 0;
        for (int dy = 1; dy <= 6; dy++) {
            if (w.getBlockAt(x, y - dy, z).isPassable()) airBelow++;
            else break;
        }
        if (airBelow >= 3) return "FALL_FLYING";

        // 3. Sleeping — long idle in STAY mode. We refresh
        //    lastActivityTick from movementTick whenever something
        //    "active" happens (owner nearby, threat engaged, mode
        //    change), so this only fires when she's genuinely been
        //    still for a while.
        if (c.mode == BehaviorMode.STAY
                && tick - c.lastActivityTick > SLEEP_AFTER_TICKS) {
            return "SLEEPING";
        }

        // 4. Pre-sleep yawn — 1 second window right before SLEEPING
        //    triggers. Renders as a brief crouch so it reads as a
        //    "stretch / settle in" gesture. We pair it with a single
        //    arm-swing fired by tryPreSleepYawn the moment we cross
        //    into the window.
        if (tick < c.preSleepYawnUntil) return "CROUCHING";

        // 5. Thinking crouch — DISABLED. It made her crouch every time she
        //    re-pathed, so when she was stuck at a door / floating she crouched
        //    nonstop. Re-pathing is now invisible.
        // if (tick < c.thinkingCrouchUntil) return "CROUCHING";

        // 6. Ambient crouch — brief sneak fidget, set occasionally by idleLook.
        if (tick < c.ambientCrouchUntil) return "CROUCHING";

        return "STANDING";
    }

    /** How long she has to be still in STAY mode before she dozes off. 90 sec. */
    private static final long SLEEP_AFTER_TICKS = 90L * 20L;
    /** Lead time for the pre-sleep yawn — fires this many ticks BEFORE
     *  the SLEEPING transition. ~20 ticks = 1 s, comfortable beat. */
    private static final long PRE_SLEEP_YAWN_LEAD = 20L;
    /** How close the owner has to be to count as "active" for the sleep timer. */
    private static final double ACTIVITY_OWNER_RADIUS = 8.0;

    /**
     * If she's about to fall asleep (STAY mode + idle for almost long
     * enough to trigger SLEEPING), play a single arm-swing + open a
     * brief CROUCHING window so it reads as "yawn / stretch". Only
     * fires once per sleep cycle — {@link Companion#yawnPlayedThisIdle}
     * is reset whenever {@link Companion#lastActivityTick} refreshes.
     */
    private void tryPreSleepYawn(Companion c, long tick) {
        if (!preSleepYawnEnabled) return;
        if (c.yawnPlayedThisIdle) return;
        if (c.mode != BehaviorMode.STAY) return;
        long idleTicks = tick - c.lastActivityTick;
        // The yawn fires when we're past the threshold by exactly the
        // lead time. Using >= keeps it from missing a tick if we
        // schedule slightly out.
        if (idleTicks < SLEEP_AFTER_TICKS - PRE_SLEEP_YAWN_LEAD) return;
        if (idleTicks >= SLEEP_AFTER_TICKS) return; // already asleep
        c.yawnPlayedThisIdle = true;
        // 1.2 s crouch — overlaps the SLEEPING transition slightly so
        // there's no flicker back to STANDING in between.
        c.preSleepYawnUntil = tick + PRE_SLEEP_YAWN_LEAD + 4;
        if (c.entity != null) c.entity.swingMainHand();
    }

    /**
     * Tilt the companion's body pitch forward when descending in water
     * and backward when ascending, so a dive looks like an actual dive
     * (head-first plunge) rather than a teleporting Y change. Pitch is
     * lerped toward target each tick for smoothness.
     *
     * <p>Only runs while she's in SWIMMING pose — outside water the
     * pitch decays back to neutral so she's not stuck looking sideways
     * after climbing out of a lake.
     *
     * <p>Y delta is sampled from her actual rendered Y, with a small
     * threshold ({@link #DIVE_PITCH_DEAD_ZONE}) so the swim bob noise
     * (~0.025/tick) doesn't trigger spurious pitch wobble.
     */
    private void applyDivePitch(Companion c) {
        if (c.entity == null || c.entity.isDead()) return;
        Location loc = c.entity.getLocation();
        double currY = loc.getY();
        double prevY = c.swimLastY;
        c.swimLastY = currY;

        if (!"SWIMMING".equals(c.currentPose)) {
            // Drift pitch back toward 0 when not swimming.
            if (Math.abs(c.swimPitchSmoothed) > 0.5) {
                c.swimPitchSmoothed *= 0.5;
                c.entity.setHeadPitch((float) c.swimPitchSmoothed);
            } else {
                c.swimPitchSmoothed = 0;
            }
            return;
        }

        double yDelta = currY - prevY;
        // Dead zone keeps the bob from trembling pitch.
        if (Math.abs(yDelta) < DIVE_PITCH_DEAD_ZONE) yDelta = 0;

        // Map velocity → pitch. yDelta < 0 (descending) → +pitch (head down).
        double targetPitch = -yDelta * DIVE_PITCH_VELOCITY_GAIN;
        targetPitch = Math.max(-DIVE_PITCH_LIMIT, Math.min(DIVE_PITCH_LIMIT, targetPitch));

        // First-order smoothing so the body angle eases instead of snapping.
        c.swimPitchSmoothed = c.swimPitchSmoothed * 0.7 + targetPitch * 0.3;
        c.entity.setHeadPitch((float) c.swimPitchSmoothed);
    }

    private static final double DIVE_PITCH_DEAD_ZONE = 0.04;
    private static final double DIVE_PITCH_VELOCITY_GAIN = 25.0;
    private static final double DIVE_PITCH_LIMIT = 45.0;

    /**
     * Apply a small sinusoidal Y bob while she's swimming, so the
     * surface idle looks like treading water instead of a frozen statue
     * floating perfectly still. Each tick we send the *delta* between
     * the new and old bob offsets — accumulating the absolute Y drift
     * would slowly desynchronize her from where the path puts her.
     *
     * <p>When she leaves water we send one final negative delta to
     * undo any leftover offset so she snaps back to the proper Y
     * instead of carrying the bob into walking on land.
     */
    private void applySwimBob(Companion c) {
        if (c.entity == null || c.entity.isDead()) return;

        if (!"SWIMMING".equals(c.currentPose)) {
            if (c.swimBobOffset != 0.0) {
                Location loc = c.entity.getLocation();
                loc.setY(loc.getY() - c.swimBobOffset);
                c.entity.smoothMoveTo(loc);
                c.swimBobOffset = 0.0;
            }
            return;
        }

        // ~1 Hz oscillation at 10 Hz movement tick rate (0.6 rad/tick),
        // ±4 cm amplitude. Subtle but visible.
        double phase = behaviorTickCount * 0.6;
        double newOffset = Math.sin(phase) * SWIM_BOB_AMPLITUDE;
        double delta = newOffset - c.swimBobOffset;
        c.swimBobOffset = newOffset;

        if (Math.abs(delta) < 1e-3) return; // nothing client-visible to send
        Location loc = c.entity.getLocation();
        loc.setY(loc.getY() + delta);
        c.entity.smoothMoveTo(loc);
    }

    private static final double SWIM_BOB_AMPLITUDE = 0.04;

    /**
     * True if the companion is fully submerged (foot AND head in water).
     * Used by combat to skip the bow path — vanilla arrows have brutal
     * underwater drag and would just plink uselessly on a guardian.
     */
    private boolean isCompanionSubmerged(Companion c) {
        if (c.entity == null) return false;
        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return w.getBlockAt(x, y, z).getType() == Material.WATER
                && w.getBlockAt(x, y + 1, z).getType() == Material.WATER;
    }

    /** Past this distance while following, she blinks to the owner instead of pathing. */
    private static final double FOLLOW_WARP_DISTANCE = 20.0;

    /** Count consecutive passable blocks directly under {@code loc}, up to {@code max}. */
    private int airBelowCount(Location loc, int max) {
        World w = loc.getWorld();
        if (w == null) return 0;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        int n = 0;
        for (int i = 1; i <= max; i++) {
            if (w.getBlockAt(x, y - i, z).isPassable()) n++;
            else break;
        }
        return n;
    }

    /**
     * Follow spot for {@code c}, fanned out to the side by her slot among the
     * owner's companions so multiple Kohaku don't all stack into one blob (they
     * have no collision). The primary keeps the normal spot; helpers spread
     * left/right of it.
     */
    private Location formationTarget(Player owner, Companion c) {
        Location base = spawnLocFor(owner);
        List<Companion> all = ownerCompanions(owner.getUniqueId());
        int idx = all.indexOf(c);
        if (idx <= 0 || all.size() <= 1) return base; // primary / only one
        // 1 → +1.4, 2 → -1.4, 3 → +2.8, 4 → -2.8 ... fan out beside the base spot.
        double lateral = ((idx + 1) / 2) * 1.4 * ((idx % 2 == 1) ? 1.0 : -1.0);
        Vector right = owner.getLocation().getDirection().setY(0).normalize()
                .rotateAroundY(-Math.PI / 2.0).multiply(lateral);
        Location t = base.clone().add(right);
        // Keep her on solid-ish ground: if the offset spot has no floor, fall back.
        World w = t.getWorld();
        if (w == null) return base;
        if (w.getBlockAt(t.getBlockX(), t.getBlockY() - 1, t.getBlockZ()).isPassable()
                && !w.getBlockAt(t.getBlockX(), t.getBlockY(), t.getBlockZ()).isPassable()) {
            return base;
        }
        return t;
    }

    // ============== Smart navigator (native pathfinding) ==============

    /** Ensure this companion has a live invisible navigator mob. */
    private boolean ensureNavigator(Companion c, Player owner) {
        if (!smartNavigator || c.entity == null || c.entity.isDead()) return false;
        org.bukkit.entity.Mob nav = c.navMob;
        if (nav != null && nav.isValid() && !nav.isDead()) return true;
        Location at = c.entity.getLocation();
        World w = at.getWorld();
        if (w == null) return false;
        try {
            org.bukkit.entity.Villager v = w.spawn(at, org.bukkit.entity.Villager.class, m -> {
                m.setSilent(true);
                m.setInvulnerable(true);
                m.setCollidable(false);
                m.setPersistent(true);
                m.setRemoveWhenFarAway(false);
                try {
                    m.getPersistentDataContainer().set(companionMarkerKey(),
                            org.bukkit.persistence.PersistentDataType.STRING, c.owner.toString());
                } catch (Throwable ignored) {}
            });
            try {
                v.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.INVISIBILITY,
                        Integer.MAX_VALUE, 0, false, false));
            } catch (Throwable ignored) {}
            c.navMob = v;
            navIds.add(v.getUniqueId());
            return true;
        } catch (Throwable t) {
            getLogger().warning("(✧) navigator spawn failed: " + t.getMessage());
            return false;
        }
    }

    private void despawnNavigator(Companion c) {
        c.navMirroring = false;
        if (c.navMob == null) return;
        try { navIds.remove(c.navMob.getUniqueId()); } catch (Throwable ignored) {}
        try { c.navMob.remove(); } catch (Throwable ignored) {}
        c.navMob = null;
    }

    /** Drive FOLLOW via the navigator: it natively paths to her formation spot
     *  and the visible NPC mirrors its position (proper stairs/jumps/drops). */
    private void driveFollowWithNavigator(Companion c, Player owner) {
        org.bukkit.entity.Mob nav = c.navMob;
        if (nav == null) return;
        c.navDrivenTick = behaviorTickCount; // mark so the post-dispatch park skips this tick
        c.navMirroring = true;               // the 20 Hz task mirrors her onto the nav
        Location dest = formationTarget(owner, c);
        if (nav.getWorld() != owner.getWorld()
                || nav.getLocation().distance(owner.getLocation()) > FOLLOW_WARP_DISTANCE) {
            nav.teleport(dest); // strayed / wrong world — snap back
        } else {
            double speed = 1.1;
            if (abilitySpeed && levelingEnabled) speed = Math.min(1.8, 1.0 + (c.level - 1) * 0.08);
            try { nav.getPathfinder().moveTo(dest, speed); } catch (Throwable ignored) {}
        }
        mirrorToNavigator(c);
    }

    /** Mirror every visible companion onto its navigator each game tick (20 Hz)
     *  so following looks smooth instead of stepping ½ block at the 10 Hz
     *  behaviour rate. */
    private void navMirrorTick() {
        // Fast early-return: nothing to mirror when there are no companions at all.
        if (companions.isEmpty() && extras.isEmpty()) return;
        for (Companion c : companions.values()) navMirrorOne(c);
        for (List<Companion> list : extras.values()) {
            for (Companion c : list) navMirrorOne(c);
        }
    }

    private void navMirrorOne(Companion c) {
        if (!c.navMirroring || c.navMob == null) return;
        if (!c.navMob.isValid() || c.navMob.isDead()) return;
        if (c.entity == null || c.entity.isDead()) return;
        mirrorToNavigator(c);
    }

    /** Snap the visible packet-NPC onto the navigator's position, facing travel. */
    private void mirrorToNavigator(Companion c) {
        if (c.navMob == null || c.entity == null || c.entity.isDead()) return;
        Location nl = c.navMob.getLocation();
        Location cur = c.entity.getLocation();
        if (nl.getWorld() != cur.getWorld()) { c.entity.teleport(nl); return; }
        double dx = nl.getX() - cur.getX(), dz = nl.getZ() - cur.getZ();
        float yaw = (dx * dx + dz * dz > 0.0004)
                ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : cur.getYaw();
        c.entity.smoothMoveTo(new Location(nl.getWorld(), nl.getX(), nl.getY(), nl.getZ(), yaw, 0f));
    }

    /** Keep the navigator glued to her when she's NOT being navigator-driven
     *  (other modes), so it's ready the moment she returns to FOLLOW. */
    private void parkNavigator(Companion c) {
        c.navMirroring = false; // she's standing / not navigator-driven this tick
        if (c.navMob == null || c.entity == null) return;
        if (!c.navMob.isValid() || c.navMob.isDead()) { despawnNavigator(c); return; }
        try { c.navMob.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
        Location el = c.entity.getLocation();
        if (c.navMob.getWorld() != el.getWorld() || c.navMob.getLocation().distanceSquared(el) > 4.0) {
            c.navMob.teleport(el);
        }
    }

    private void stepToward(Companion c, Player owner) {
        Location ownerLoc = owner.getLocation();
        Location standLoc = c.entity.getLocation();
        World w = standLoc.getWorld();

        // Cross-world: just teleport.
        if (ownerLoc.getWorld() != w) {
            c.entity.teleport(spawnLocFor(owner));
            despawnNavigator(c); // it'll respawn in the new world on the next tick
            // Also clear sprint state so a re-emerge in another world
            // doesn't carry the sprint flag across.
            applySprintFlag(c, false);
            return;
        }

        // ---- Smart navigator: an invisible mob does the real pathfinding
        //      (stairs/jumps/drops) and the visible Kohaku mirrors it. ----
        if (ensureNavigator(c, owner)) {
            long ntick = behaviorTickCount;
            double ndist = standLoc.distance(owner.getLocation());
            boolean ownerFarN = ndist > greetWaveDistance;
            tryGreetWave(c, ownerFarN, ntick);
            c.ownerWasFarLastTick = ownerFarN;
            applySprintFlag(c, sprintCatchUpEnabled && ndist > sprintTriggerDistance && ndist > followDistance);
            if (ndist > followDistance) {
                driveFollowWithNavigator(c, owner);
                faceToward(c, owner.getEyeLocation());
            } else {
                parkNavigator(c);
                faceTarget(c, owner.getEyeLocation());
            }
            return;
        }

        // Aim a bit to the side of the player so the companion isn't standing
        // inside them.
        Location target = formationTarget(owner, c);
        double dist = standLoc.distance(target);

        // Greet wave detection — fires the first tick the owner re-enters
        // close range after being far. Cheap (one distance compare + one
        // boolean), and the wave itself is a single arm-swing animation.
        long tick = behaviorTickCount;
        boolean ownerFar = dist > greetWaveDistance;
        tryGreetWave(c, ownerFar, tick);
        c.ownerWasFarLastTick = ownerFar;

        // Sprint catch-up — only meaningful while she's actually walking
        // (dist > followDistance). We compute the desired sprint state
        // here so the same flag is used by both the stepper (for the
        // larger move-step) and the metadata broadcast (for the client
        // animation).
        boolean wantSprint = sprintCatchUpEnabled
                && dist > sprintTriggerDistance
                && dist > followDistance;
        applySprintFlag(c, wantSprint);

        // Reliability over realism: if she's fallen far behind, or ended up
        // floating in mid-air (stuck above cave stairs, hung up on a door,
        // etc.), just blink to your side instead of re-pathing / floating /
        // crouching forever. A companion that occasionally pops to you beats
        // one stuck at a doorway.
        boolean floating = airBelowCount(standLoc, 3) >= 3;
        boolean ownerGrounded = airBelowCount(ownerLoc, 1) == 0;
        boolean stuckFollowing = (c.stuckTicks > 25 || c.escapeStartTick > 0) && dist > followDistance;
        if (dist > FOLLOW_WARP_DISTANCE
                || stuckFollowing
                || (floating && ownerGrounded && dist > followDistance)) {
            clearPath(c);
            c.stuckTicks = 0;
            c.escapeStartTick = 0;
            c.entity.teleport(spawnLocFor(owner));
            applySprintFlag(c, false);
            faceTarget(c, owner.getEyeLocation());
            return;
        }

        if (dist > followDistance) {
            // Walking — body yaw is set by smoothMoveTo; head tracks the
            // owner with a ±90° clamp so she doesn't owl-twist.
            pathStepToward(c, target, ownerLoc.getY(), followDistance);
            faceToward(c, owner.getEyeLocation());
        } else {
            // Standing close — pivot body+head to face the owner so she
            // actually looks at them instead of staring past them.
            faceTarget(c, owner.getEyeLocation());
        }
    }

    /**
     * Set sprint flag — broadcast metadata only when state actually
     * changes so we're not firing a packet every movement tick.
     */
    private void applySprintFlag(Companion c, boolean sprinting) {
        if (c.sprintFlag == sprinting) return;
        c.sprintFlag = sprinting;
        if (c.entity != null) c.entity.setSprinting(sprinting);
    }

    /**
     * Effective per-tick step distance — the configured {@link #moveStep}
     * scaled by the sprint multiplier when she's currently sprinting.
     * Centralized so both the swim and land branches of
     * {@link #stepTowardLocation} use the same rule.
     */
    private double effectiveMoveStep(Companion c) {
        return c.sprintFlag ? moveStep * sprintMoveStepMultiplier : moveStep;
    }

    /**
     * If the owner just transitioned from {@code far → near}, swing her
     * main hand once as a "greet wave" tic. Throttled by
     * {@link #greetWaveCooldownTicks} so a yo-yo'ing owner doesn't get
     * spammed waves. Skipped entirely when greet-wave is disabled.
     */
    private void tryGreetWave(Companion c, boolean ownerFarNow, long tick) {
        if (!greetWaveEnabled) return;
        if (c.entity == null) return;
        // Trigger only on the *transition* far → near, not while staying
        // close. Avoids waving every tick.
        if (!c.ownerWasFarLastTick || ownerFarNow) return;
        if (tick - c.lastGreetWaveTick < greetWaveCooldownTicks) return;
        c.lastGreetWaveTick = tick;
        c.entity.swingMainHand();
    }

    /**
     * Walk one step toward {@code target}, stopping when within
     * {@code stopDist} of it. Returns {@code true} if a movement decision
     * was made and the caller should still run idle/face logic; {@code
     * false} on cross-world bail.
     *
     * <p>{@code referenceY} is the Y we use when scanning for the floor —
     * for FOLLOW it's the owner's Y; for SCOUT it's the anchor Y. This
     * keeps the floor-scan from drifting around with companion's own
     * position. {@code stopDist} differs by mode: FOLLOW wants to keep
     * a respectful 3-block bubble around the owner, SCOUT wants to walk
     * basically all the way to the chosen point.
     */
    private boolean stepTowardLocation(Companion c, Location target, double referenceY, double stopDist) {
        Location standLoc = c.entity.getLocation();
        World w = standLoc.getWorld();
        if (target.getWorld() != w) return false;

        double dist = standLoc.distance(target);
        if (dist <= stopDist) {
            return true; // close enough — caller may still do idle/face
        }
        // Long-range catch-up teleport. Skipped while approaching a live
        // entity (combat): warping onto a mob instead of walking up to it
        // looks awful and breaks the chase, and it must NOT depend on the
        // configurable teleport-threshold (a low value would otherwise warp
        // her onto threats). In that case fall through and walk a step.
        if (dist >= teleportThreshold && !c.approachingEntity) {
            c.entity.teleport(target);
            return true;
        }

        Vector delta = target.toVector().subtract(standLoc.toVector());
        double horiz = Math.hypot(delta.getX(), delta.getZ());

        // Per-tick step length — scaled by sprint multiplier when she's
        // sprinting to catch up. Cached once here so swim + land paths
        // both pick it up consistently.
        double step = effectiveMoveStep(c);

        // Swim path — when she's in water (foot+water with head or
        // below also water) we allow 3D motion. The land path is
        // horizontal-only and uses findGroundY for Y, which can't chase
        // a target directly above or below her (horiz≈0 would early-
        // return and she'd freeze under a guardian). Swim mode steps
        // along the full 3D delta vector instead.
        boolean swimming = isCompanionSwimming(c);

        // Ladder/vine climb — when she's standing in (or against) a
        // climbable block AND the target is above her, prefer a vertical
        // step over the land path. Without this she'd just shimmy into
        // the ladder and stop. Treated as a 3D step like swimming, but
        // only along the climbable column.
        //
        // Triggers on Y-delta > 0.2 (not 0.6) so the *last* step out the
        // top — where Y-delta naturally shrinks as she approaches the
        // target — still uses the climb path. Without this lowered
        // threshold she'd freeze the moment her body got near level with
        // the target Y because climbing turned off but she's still INSIDE
        // a ladder column with no horizontal motion to take over.
        boolean climbing = !swimming && ladderClimbEnabled
                && delta.getY() > 0.2
                && isAtClimbable(w, standLoc);

        Location next;
        boolean stepUp = false; // set in the land-path branch when y-delta > 0
        if (swimming || climbing) {
            double total = Math.sqrt(delta.getX() * delta.getX()
                                   + delta.getY() * delta.getY()
                                   + delta.getZ() * delta.getZ());
            if (total < 1e-4) return true;
            double scale = Math.min(step / total, 1.0);
            next = standLoc.clone().add(
                    delta.getX() * scale,
                    delta.getY() * scale,
                    delta.getZ() * scale);
            // Validate destination — water is passable, but we still
            // don't want to step into a solid block (e.g., diving
            // toward a target whose path passes through stone).
            if (isBlocked(next)) {
                // Surface-swim special case: she's bobbing against a bank
                // one block above her foot (the classic "stuck in the water
                // at the edge of a cliff" bug). The 3D swim step rams the
                // wall and never rises the last block to climb out, so the
                // straight-step is blocked, she's flagged stuck, repaths up
                // the same bank, and rams it again — forever. Try to hop out
                // onto the ledge top, mirroring a vanilla player's auto-jump
                // out of water. Ladder/vine climbers have their own top-out
                // handling below, so this is gated to genuine swimming.
                Location exit = swimming ? waterExitStep(standLoc, w, delta) : null;
                if (exit == null) return true;
                next = exit;
            }
            // Climb-specific: when the next cell is no longer a climbable
            // column (she's about to top out), STILL accept the step —
            // the next tick will use the land path naturally. The
            // previous "return true without moving" caused a one-tick
            // freeze at the top of every ladder/vine, which combined
            // with stuck-detection sometimes made her think she was
            // stuck. Letting the step happen lets her smoothly transition
            // to walking on the top block.
            if (climbing && !isAtClimbable(w, next)) {
                // Top-out: nudge her up by a flat 1.0 block AND a small
                // horizontal nudge in the goal direction, so she steps
                // *off* the ladder onto the top block instead of just
                // hovering at the top of the climbable column. Without
                // this micro-step she'd alternate between "in column"
                // (climb path) and "above column" (land path needs ground
                // — which is exactly the block she's supposedly standing
                // on now) every other tick, looking like a stutter.
                next = standLoc.clone();
                next.setY(standLoc.getY() + 1.0);
                if (horiz > 1e-4) {
                    double horizNudge = Math.min(0.4, horiz);
                    next.add(delta.getX() / horiz * horizNudge, 0,
                             delta.getZ() / horiz * horizNudge);
                }
                if (isBlocked(next)) return true;
            }
        } else {
            // Land path — horizontal step + findGroundY for Y.
            if (horiz < 1e-4) { tryCombatClimb(c, standLoc, w, target, step); return true; }
            double scale = Math.min(step / horiz, 1.0);
            next = standLoc.clone().add(delta.getX() * scale, 0, delta.getZ() * scale);

            double scanFromY = Math.max(standLoc.getY(), referenceY) + 2.0;
            double groundY = findGroundY(w, next.getBlockX(), next.getBlockZ(),
                    scanFromY, referenceY, standLoc.getY());

            double yDelta = groundY - standLoc.getY();
            if (yDelta > 1.0) {
                Location flatNext = next.clone();
                flatNext.setY(standLoc.getY());
                if (isBlocked(flatNext)) { tryCombatClimb(c, standLoc, w, target, step); return true; }
                next = flatNext;
            } else {
                next.setY(groundY);
                // Detect the "step up onto a higher block" case so we
                // can fire a small arm-swing for the hop animation
                // below. >0.4 covers full blocks + slabs but ignores
                // micro-Y wiggles from sand/snow layers.
                if (yDelta > 0.4) stepUp = true;
            }

            if (isBlocked(next)) { tryCombatClimb(c, standLoc, w, target, step); return true; }
        }

        Vector look = target.toVector().subtract(next.toVector());
        if (look.lengthSquared() > 1e-6) {
            double desiredYaw = Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
            double smoothedYaw = lerpAngleDeg(standLoc.getYaw(), desiredYaw, 0.25);
            next.setYaw((float) smoothedYaw);
        } else {
            next.setYaw(standLoc.getYaw());
        }
        // Hop animation — on a step-up frame, give her a small upward
        // bob (a genuine little jump) rather than an arm-swing. The swing
        // packet is action-0, the exact same animation the client plays
        // for an attack, so reusing it made her look like she was punching
        // the air every time she climbed a slab/stair. A vertical overshoot
        // reads as a real hop and settles back to the block top on the next
        // move tick. Throttled so a long staircase doesn't bob every step.
        if (stepUp && hopOnStepUpEnabled) {
            long tickNow = behaviorTickCount;
            if (tickNow - c.lastHopSwingTick > 6) {
                c.lastHopSwingTick = tickNow;
                next.setY(next.getY() + HOP_BOB_HEIGHT);
            }
        }

        c.entity.smoothMoveTo(next);

        return true;
    }

    /**
     * Climb out of the water onto a one-block-high bank in the direction
     * of travel. Fixes the "companion gets stuck in the water at the edge
     * of a cliff" bug: a surface swimmer whose route continues onto land
     * keeps stepping horizontally into the bank wall (which is solid at her
     * foot level) and never rises the final block to stand on top of it.
     *
     * <p>Returns the ledge-top stand {@link Location} to hop onto, or
     * {@code null} when there's no clean single-block ledge to climb —
     * in which case the caller leaves her swimming. Guards keep this from
     * firing while diving (an underwater wall has water, not air, above it,
     * so the clearance check rejects it) or when the move is purely
     * vertical (no bank to climb toward).
     *
     * @param standLoc her current position (foot block).
     * @param w        the world.
     * @param delta    full 3D vector toward the target.
     */
    private Location waterExitStep(Location standLoc, World w, Vector delta) {
        double horiz = Math.hypot(delta.getX(), delta.getZ());
        if (horiz < 1e-4) return null; // straight up/down — no bank to climb onto

        // The bank column one short step ahead in the travel direction.
        int ax = (int) Math.floor(standLoc.getX() + delta.getX() / horiz * 0.6);
        int az = (int) Math.floor(standLoc.getZ() + delta.getZ() / horiz * 0.6);
        int footY = standLoc.getBlockY();

        // It's only a climbable bank if the block at her foot level ahead is
        // solid (the wall she's stuck against)...
        if (w.getBlockAt(ax, footY, az).isPassable()) return null;
        // ...with TWO dry, passable cells above it for her 2-tall body to
        // stand in. Requiring non-water here is what keeps a submerged wall
        // (water above it) from being mistaken for a shoreline ledge.
        Block stand = w.getBlockAt(ax, footY + 1, az);
        Block headroom = w.getBlockAt(ax, footY + 2, az);
        if (!stand.isPassable() || stand.getType() == Material.WATER) return null;
        if (!headroom.isPassable() || headroom.getType() == Material.WATER) return null;

        // Hop up onto the ledge top, keeping her facing yaw/pitch for the tick.
        return new Location(w, ax + 0.5, footY + 1, az + 0.5,
                standLoc.getYaw(), standLoc.getPitch());
    }

    /**
     * True if the location's foot block is a climbable block (ladder,
     * vine, scaffolding, twisting/weeping vines, or cave vines). Used
     * by {@link #stepTowardLocation} to decide whether to allow the
     * companion to step vertically.
     *
     * <p>We treat the foot block as the climbable cell — vines spread
     * across multiple Y coords so the foot block is what determines
     * whether she's currently "on" a ladder/vine column.
     */
    private static boolean isAtClimbable(World w, Location loc) {
        if (w == null || loc == null) return false;
        Material foot = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).getType();
        return foot == Material.LADDER
                || foot == Material.VINE
                || foot == Material.SCAFFOLDING
                || foot == Material.TWISTING_VINES
                || foot == Material.TWISTING_VINES_PLANT
                || foot == Material.WEEPING_VINES
                || foot == Material.WEEPING_VINES_PLANT
                || foot == Material.CAVE_VINES
                || foot == Material.CAVE_VINES_PLANT;
    }

    /**
     * True if the companion is in a "swimming situation" — foot in
     * water AND either below or head also water (i.e., not just 1-block
     * wading). Mirrors the SWIMMING pose check, used by
     * {@link #stepTowardLocation} to enable 3D motion.
     */
    private boolean isCompanionSwimming(Companion c) {
        if (c.entity == null) return false;
        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        Material foot = w.getBlockAt(x, y, z).getType();
        if (foot != Material.WATER) return false;
        Material head = w.getBlockAt(x, y + 1, z).getType();
        Material below = w.getBlockAt(x, y - 1, z).getType();
        return head == Material.WATER || below == Material.WATER;
    }

    // ============== PATHFINDING ==============

    /**
     * Pathfinding-aware wrapper around {@link #stepTowardLocation}. The
     * companion's a fake {@code ServerPlayer} not registered with the
     * level, so vanilla NMS path navigation doesn't apply — we run a
     * lightweight A* (see {@link CompanionPathfinder}) on the main
     * thread, *only* when straight-line walking gets stuck.
     *
     * <p>Strategy:
     * <ol>
     *   <li>If we already have a live path, advance through its waypoints,
     *       walking each via the existing straight-line stepper.</li>
     *   <li>Otherwise, just walk straight toward the goal. Track movement;
     *       if she barely moves for ~0.6 sec while the goal is still far,
     *       kick off an A* repath.</li>
     *   <li>Throttle A* to at most once every {@link #PATH_REPATH_COOLDOWN_TICKS}
     *       ticks per companion to keep the main-thread budget bounded
     *       even if many companions get stuck simultaneously.</li>
     * </ol>
     *
     * <p>This means the common case (open-terrain following) pays zero
     * pathfinding cost — A* only runs when the dumb stepper actually
     * fails. Caller contract matches {@link #stepTowardLocation}.
     */
    /** A* node budget per search — tight enough to stay sub-millisecond in the common case. */
    private static final int PATH_NODE_BUDGET = 800;
    /** Bigger node budget used as a *last chance* search before declaring her
     *  stuck and entering escape mode. ~3-5 ms but bounded, runs at most once
     *  per stuck event. */
    private static final int PATH_NODE_BUDGET_RESCUE = 2000;
    /** Don't path further than this — caps both the search cost and the "pathing across the world" case. */
    private static final double PATH_MAX_RANGE = 48.0;
    /** Wider rescue search radius — looks 12 blocks further for a way out. */
    private static final double PATH_MAX_RANGE_RESCUE = 60.0;
    /** How close to a waypoint counts as "reached" before advancing to the next. */
    private static final double PATH_WAYPOINT_REACHED = 0.9;
    /** Re-evaluate path freshness every this many ticks (5 sec @ 20 Hz). */
    private static final long PATH_FRESHNESS_TICKS = 100;
    /** Goal drifted further than this from {@code pathGoal} → cached path is stale. */
    private static final double PATH_GOAL_DRIFT = 3.0;
    /** At most one repath this many ticks (2 sec @ 20 Hz). */
    private static final long PATH_REPATH_COOLDOWN_TICKS = 40;
    /** Behavior ticks between full form-combat target re-scans. Between scans the
     *  cached target is re-validated cheaply, so the costly getNearbyEntities
     *  sweep runs at ~1 Hz instead of every tick. */
    private static final long FORM_TARGET_SCAN_INTERVAL = 10;
    /** Stuck-in-water ticks while chasing a combat target before she gives up on
     *  it (~3 s at the 10 Hz behavior rate). */
    private static final int  COMBAT_WATER_GIVEUP_TICKS = 30;
    /** How long she ignores combat targets after a water give-up, so she actually
     *  gets back to dry land before re-engaging (~6 s). */
    private static final long COMBAT_WATER_GIVEUP_COOLDOWN = 60;
    /** Treat companion as "stuck" after this many movement ticks under {@link #STUCK_MIN_MOVE_SQ}. */
    private static final int STUCK_TICK_THRESHOLD = 6;
    /** Less than ~0.15 block of horizontal motion per tick = stuck. */
    private static final double STUCK_MIN_MOVE_SQ = 0.025;

    private boolean pathStepToward(Companion c, Location target, double referenceY, double stopDist) {
        if (c.entity == null) return false;
        Location standLoc = c.entity.getLocation();
        World w = standLoc.getWorld();
        long tick = behaviorTickCount;

        // Stuck-escape branch — runs BEFORE the path-following / straight-line
        // logic. While she's escaping, all normal movement is suspended; the
        // escape itself either frees her (resets the state) or times out and
        // emergency-teleports. Cheap when not escaping (one boolean check).
        if (c.escapeStartTick > 0) {
            tickStuckEscape(c, target, tick);
            return true;
        }

        // Cross-world: defer to the straight-line stepper (which teleports).
        if (target.getWorld() != w) {
            clearPath(c);
            return stepTowardLocation(c, target, referenceY, stopDist);
        }

        // Already close enough — clear any path and stop. Saves A* cost
        // on the very common "she's already next to you" case.
        if (standLoc.distance(target) <= stopDist) {
            clearPath(c);
            c.stuckTicks = 0;
            return true;
        }

        // ---- Path-following branch ----
        if (c.currentPath != null && !c.currentPath.isEmpty()) {
            // Stale? Goal drifted, or path is too old — drop and fall through to straight-line.
            if (c.pathGoal == null
                    || c.pathGoal.getWorld() != target.getWorld()
                    || c.pathGoal.distance(target) > PATH_GOAL_DRIFT
                    || tick - c.pathComputedTick > PATH_FRESHNESS_TICKS) {
                clearPath(c);
            } else {
                // Walk to the current waypoint. Advance when we've reached it.
                while (c.pathIndex < c.currentPath.size()) {
                    Location wp = c.currentPath.get(c.pathIndex);
                    double dxz = horizDistance(standLoc, wp);
                    if (dxz < PATH_WAYPOINT_REACHED) {
                        c.pathIndex++;
                        continue;
                    }
                    // Open doors / break plants / tunnel through soft terrain
                    // at this waypoint before stepping into it. Cheap when no
                    // obstacles are present (just two block-type lookups).
                    clearObstaclesAt(c, wp);
                    // Step toward the waypoint (not the final goal). The
                    // 0.4-block stop distance keeps her close enough to
                    // the waypoint that we advance on the next tick.
                    boolean result = stepTowardLocation(c, wp, wp.getY(), 0.4);
                    sampleStuck(c, standLoc, target, stopDist, tick);
                    return result;
                }
                // Exhausted the path — drop it and finish on straight-line.
                clearPath(c);
            }
        }

        // ---- Straight-line branch (default) ----
        boolean result = stepTowardLocation(c, target, referenceY, stopDist);
        sampleStuck(c, standLoc, target, stopDist, tick);
        return result;
    }

    /**
     * Track stuck-ness. If the companion's been barely moving for ~0.6 sec
     * while the goal is still distant, kick off an A* repath (subject to
     * the global cooldown).
     */
    private void sampleStuck(Companion c, Location preStep, Location target, double stopDist, long tick) {
        // Sample current position — note this is *before* the move took
        // effect this tick, so on the next call we'll see whether the
        // step actually advanced us.
        double dxs = preStep.getX() - c.lastStuckSampleX;
        double dzs = preStep.getZ() - c.lastStuckSampleZ;
        double dys = preStep.getY() - c.lastStuckSampleY;
        double movedSq = dxs * dxs + dzs * dzs;
        c.lastStuckSampleX = preStep.getX();
        c.lastStuckSampleY = preStep.getY();
        c.lastStuckSampleZ = preStep.getZ();

        // Vertical motion counts as progress when she's on a climbable
        // (ladder/vine/scaffolding) — without this, climbing reads as
        // "no horizontal motion" and triggers stuck → false escape mid-
        // climb. Same idea when she's swimming through a vertical water
        // column.
        boolean verticalContextOk = false;
        World w = preStep.getWorld();
        if (w != null) {
            verticalContextOk = isAtClimbable(w, preStep) || isCompanionSwimming(c);
        }

        if (movedSq < STUCK_MIN_MOVE_SQ
                && !(verticalContextOk && Math.abs(dys) > 0.05)) {
            c.stuckTicks++;
        } else {
            c.stuckTicks = 0;
        }

        if (c.stuckTicks >= STUCK_TICK_THRESHOLD
                && tick >= c.nextRepathAllowedTick
                && preStep.distance(target) > stopDist + 1.0) {
            tryRepath(c, preStep, target, tick);
        }

        // Combat give-up: if she's been stuck in water while chasing a combat
        // target (approachingEntity), she's almost certainly walled off and
        // just drowning at the barrier — destructive escape is disabled in
        // combat and tryCombatClimb can only lift her straight up, neither of
        // which clears a wall. Drop the target for a few seconds and let her
        // walk back to the owner (onto dry land) instead of bobbing forever.
        if (c.approachingEntity
                && c.stuckTicks >= COMBAT_WATER_GIVEUP_TICKS
                && w != null
                && w.getBlockAt(preStep.getBlockX(), preStep.getBlockY(), preStep.getBlockZ())
                        .getType() == Material.WATER) {
            c.combatGiveUpUntil = tick + COMBAT_WATER_GIVEUP_COOLDOWN;
            c.stuckTicks = 0;
        }

        // Escalation — if normal repathing hasn't freed her in a while,
        // kick into the dig-out-of-stuck system. Gated by escape-cooldown
        // so she doesn't immediately escape again right after one resolved.
        //
        // Fast-track: if her head OR feet are *currently inside a solid
        // block* she's suffocating right now — no point waiting the
        // full trigger ticks. We fire escape almost immediately (after
        // a short stuckTicks/2 sanity gate so a single tick of momentary
        // overlap during a step-up doesn't trigger). The normal trigger
        // ticks path still applies for the "stuck against a wall but not
        // suffocating" case.
        // Do NOT dig-out-escape during combat — that can break a player's
        // base wall to reach a mob outside. Combat un-sticking is handled
        // non-destructively by tryCombatClimb() in the stepper (she rises up
        // through the open air she fell through). The destructive escape is
        // for normal navigation only.
        if (stuckEscapeEnabled
                && !c.approachingEntity
                && tick >= c.escapeNextAllowedTick
                && c.escapeStartTick == 0
                && preStep.distance(target) > stopDist + 1.0) {
            boolean trapped = isHeadOrFootInSolid(c);
            int requiredStuck = trapped
                    ? Math.max(STUCK_TICK_THRESHOLD, stuckEscapeTriggerTicks / 4)
                    : stuckEscapeTriggerTicks;
            if (c.stuckTicks >= requiredStuck) {
                // Owner-below skip — if the goal is significantly below
                // her, she's not really "stuck", she's just slow falling
                // or hesitating. Don't dig UPWARD when the answer is
                // DOWNWARD. The normal stepper / pathfinder will handle
                // the descent on subsequent ticks.
                double goalYDelta = target.getY() - preStep.getY();
                if (!trapped && goalYDelta < -2.0) {
                    c.stuckTicks = 0;
                    return;
                }
                // Last-chance rescue pathfind — most "stuck in a dip"
                // situations are actually solvable by A* if we just give
                // it more node budget + radius. Try that before giving
                // up and digging. Skipping when she's actively
                // suffocating since the dig is genuinely needed there.
                if (!trapped && tryRescuePath(c, preStep, target, tick)) {
                    c.stuckTicks = 0;
                    return; // path found — follow it instead of digging
                }
                beginStuckEscape(c, preStep, tick);
            }
        }
    }

    /**
     * One-shot aggressive pathfind triggered RIGHT before escape mode
     * would fire. Uses a much bigger node budget + range than the
     * normal repath so 5-block side exits, multi-step climbs, and
     * lateral routes around dips are actually found.
     *
     * <p>Returns {@code true} if a path was successfully installed —
     * caller should skip escape mode and let the new path drive
     * movement instead. {@code false} means even this big search
     * failed; she's genuinely stuck and dig-out is appropriate.
     *
     * <p>Cheap when there IS a clear path (A* terminates as soon as
     * goal is reached). The full budget is only spent when no exit
     * exists. Bounded at ~3-5 ms even in the worst case.
     */
    private boolean tryRescuePath(Companion c, Location from, Location to, long tick) {
        // Bump the cooldown either way so we don't immediately try
        // again on the very next tick if this one fails.
        c.nextRepathAllowedTick = tick + PATH_REPATH_COOLDOWN_TICKS;
        try {
            CompanionPathfinder.PathOptions opt = new CompanionPathfinder.PathOptions();
            opt.allowOpenDoors        = pathOpenDoors;
            // Plant-breaking gets temporarily enabled for the rescue
            // search even if the player has it off — vines / leaves /
            // cobwebs are exactly the kind of thing that traps her.
            opt.allowBreakingPlants   = true;
            opt.allowTunneling        = pathTunneling;
            opt.swimCostMultiplier    = pathSwimCostMultiplier;
            opt.blockBreakCost        = pathBlockBreakCost;
            opt.smooth                = pathSmooth;

            List<Location> path = CompanionPathfinder.findPath(
                    from.getWorld(), from, to,
                    PATH_NODE_BUDGET_RESCUE, PATH_MAX_RANGE_RESCUE, opt);
            if (path == null || path.isEmpty()) return false;
            c.currentPath = path;
            c.pathIndex = 0;
            c.pathGoal = to.clone();
            c.pathComputedTick = tick;
            return true;
        } catch (Throwable t) {
            getLogger().warning("(✧) rescue pathfind failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Run an A* search from {@code from} → {@code to}. On success, wires
     * the resulting path into the companion + resets stuck counter. On
     * failure, just bumps the cooldown so we don't immediately retry.
     * Both branches set {@link Companion#nextRepathAllowedTick} so the
     * cost is bounded even for unreachable targets.
     */
    private void tryRepath(Companion c, Location from, Location to, long tick) {
        c.nextRepathAllowedTick = tick + PATH_REPATH_COOLDOWN_TICKS;
        // Visual feedback — brief crouch right when she has to switch
        // from straight-line walking to A* re-planning. Reads as
        // "hmm, let me find another way" without being a long pose.
        // Cheap (just a tick stamp); the actual pose change is handled
        // by computeDesiredPose.
        c.thinkingCrouchUntil = tick + 18; // ~0.9 s at 20 Hz
        try {
            CompanionPathfinder.PathOptions opt = new CompanionPathfinder.PathOptions();
            opt.allowOpenDoors        = pathOpenDoors;
            opt.allowBreakingPlants   = pathBreakPlants;
            opt.allowTunneling        = pathTunneling;
            opt.swimCostMultiplier    = pathSwimCostMultiplier;
            opt.blockBreakCost        = pathBlockBreakCost;
            opt.smooth                = pathSmooth;

            List<Location> path = CompanionPathfinder.findPath(
                    from.getWorld(), from, to, PATH_NODE_BUDGET, PATH_MAX_RANGE, opt);
            if (path == null || path.isEmpty()) {
                // Pathfinding gave up — keep her in straight-line mode
                // and reset stuck so we don't spam-retry. The cooldown
                // above ensures the next attempt is at least 2 sec out.
                c.currentPath = null;
                c.pathIndex = 0;
                c.stuckTicks = 0;
                return;
            }
            c.currentPath = path;
            c.pathIndex = 0;
            c.pathGoal = to.clone();
            c.pathComputedTick = tick;
            c.stuckTicks = 0;
        } catch (Throwable t) {
            // Pathfinder shouldn't throw, but if it does we never want
            // to crash the movement tick. Log once and disable for a bit.
            getLogger().warning("(✧) pathfinder failed: " + t.getMessage());
            c.currentPath = null;
            c.pathIndex = 0;
        }
    }

    /** Drop the current path. Called when goal changes drastically or pathing succeeds. */
    private void clearPath(Companion c) {
        c.currentPath = null;
        c.pathIndex = 0;
        c.pathGoal = null;
    }

    // ============== STUCK-ESCAPE ==============

    /** While < this offset of the start, she's still in the stuck cell.
     *  The escape system requires her to actually move out before
     *  declaring success. */
    private static final double ESCAPE_FREE_DIST_SQ = 1.5 * 1.5;

    /** Min ticks between successive break events during escape — keeps
     *  the dig from looking instant but is fast enough that she actually
     *  feels responsive. ~0.15s at 10 Hz movement tick. */
    private static final long ESCAPE_BREAK_INTERVAL_TICKS = 3L;

    /** Cooldown after a successful or aborted escape before she's
     *  allowed to enter another. Stops infinite re-escape loops in
     *  truly impossible spots. */
    private static final long ESCAPE_RETRY_COOLDOWN_TICKS = 200L; // 10 s

    /**
     * Enter escape mode at the given location. Records the origin so
     * we can detect "she's actually free" later, resets the break
     * counter, and clears any active path (escape replaces normal
     * pathing for its duration).
     */
    private void beginStuckEscape(Companion c, Location origin, long tick) {
        c.escapeStartTick = tick;
        c.escapeBlocksBroken = 0;
        c.escapeLastBreakTick = 0;
        c.escapeOriginX = origin.getX();
        c.escapeOriginY = origin.getY();
        c.escapeOriginZ = origin.getZ();
        clearPath(c);
        // Visual / chat feedback — single bubble so the player notices
        // she's struggling. The "..." and the soft tone match her
        // ambient phrases so the bubble doesn't feel out of voice.
        showChatBubble(c, "ehe~ stuck...");
        getLogger().fine("(✧) " + c.name + " entering escape mode at " + origin);
    }

    /** Reset escape state. Called on success, abort, and timeout. */
    private void endStuckEscape(Companion c, long tick) {
        c.escapeStartTick = 0;
        c.escapeBlocksBroken = 0;
        c.escapeLastBreakTick = 0;
        c.escapeNextAllowedTick = tick + ESCAPE_RETRY_COOLDOWN_TICKS;
        c.stuckTicks = 0;
    }

    /**
     * Per-tick driver for the escape state machine. Called from
     * {@link #pathStepToward} when {@code escapeStartTick > 0}.
     *
     * <p>Three exit conditions, in order:
     * <ol>
     *   <li><b>Free</b> — she moved more than {@link #ESCAPE_FREE_DIST_SQ}
     *       blocks from the origin AND her head/feet are no longer
     *       suffocating. Resets state, ends escape.</li>
     *   <li><b>Timeout</b> — escape has been running longer than the
     *       configured timeout, or she's broken her budgeted block
     *       count. Emergency-teleport to owner if enabled, else just
     *       end and let the next stuck cycle re-trigger.</li>
     *   <li><b>Continue</b> — pick a break-target and break it (or step
     *       up if the column above is already clear).</li>
     * </ol>
     */
    private void tickStuckEscape(Companion c, Location target, long tick) {
        if (c.entity == null) { endStuckEscape(c, tick); return; }
        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) { endStuckEscape(c, tick); return; }

        // ---- Exit 1: she's free ----
        double dx = loc.getX() - c.escapeOriginX;
        double dy = loc.getY() - c.escapeOriginY;
        double dz = loc.getZ() - c.escapeOriginZ;
        double movedSq = dx * dx + dz * dz;
        boolean stillSuffocating = isHeadOrFootInSolid(c);
        if (movedSq > ESCAPE_FREE_DIST_SQ && !stillSuffocating) {
            // Free! Clear state and bubble a short relief.
            endStuckEscape(c, tick);
            showChatBubble(c, "phew~ ✨");
            return;
        }
        // Special-case: she's been pulled UP a block (dy > 0.5) without
        // moving horizontally — that's still escape progress, especially
        // for vertical dig-up. Accept it.
        if (dy > 1.0 && !stillSuffocating) {
            endStuckEscape(c, tick);
            return;
        }

        // ---- Exit 2: timeout / over budget ----
        long elapsed = tick - c.escapeStartTick;
        if (elapsed > stuckEscapeTimeoutTicks
                || c.escapeBlocksBroken >= stuckEscapeMaxBlocks) {
            if (stuckEscapeEmergencyTeleport) {
                Player owner = Bukkit.getPlayer(c.owner);
                if (owner != null && owner.isOnline()) {
                    Location safeLoc = spawnLocFor(owner);
                    c.entity.teleport(safeLoc);
                    showChatBubble(c, "whoops, got stuck — i'm back!");
                }
            }
            endStuckEscape(c, tick);
            return;
        }

        // ---- Exit 3: continue digging ----
        // Throttle break events so it doesn't look instant. We still
        // tick every movement frame but only ACT on the throttled cadence.
        if (tick - c.escapeLastBreakTick < ESCAPE_BREAK_INTERVAL_TICKS) {
            // Small head-look toward target so she at least looks alive.
            faceTarget(c, target);
            return;
        }

        // Find the best block to break to make upward progress.
        Block toBreak = pickEscapeBreakTarget(c, w, loc, target);
        if (toBreak == null) {
            // Nothing in immediate reach is breakable — likely surrounded
            // by protected blocks (bedrock, builds, ores). The timeout
            // branch will catch this on the next pass; for this tick we
            // just face the target and wait.
            faceTarget(c, target);
            return;
        }
        Player owner = Bukkit.getPlayer(c.owner);
        if (owner == null) { endStuckEscape(c, tick); return; }

        // Break it via the existing helper (which fires BlockBreakEvent
        // attributed to the owner — protection plugins still apply).
        Material before = toBreak.getType();
        breakObstacleBlock(c, toBreak, owner);
        // Confirm the break actually happened (event might have been
        // cancelled by a protection plugin). If unchanged, we don't
        // count it against the budget — but we DO advance lastBreakTick
        // so we don't hammer the same cell on every tick.
        c.escapeLastBreakTick = tick;
        if (toBreak.getType() != before) {
            c.escapeBlocksBroken++;
        }
    }

    /**
     * Pick the best block for the escape system to break. Priority:
     * <ol>
     *   <li>Head block (if head-in-solid) — clear immediate suffocation.</li>
     *   <li>Block above head (y+2) — escape upward, the safest direction.</li>
     *   <li>Diagonal up toward target — biased toward where she WANTS to go.</li>
     *   <li>Side toward target — last resort if up isn't viable.</li>
     * </ol>
     *
     * <p>Each candidate is filtered through {@link WorldAwareness#isEmergencyDiggable}
     * so we never break beacons, chests, ores, or builds. Falling
     * blocks (sand/gravel) can still be broken — they'll just drop and
     * give her another block to break, but at least we're making forward
     * progress and the budget caps the loop.
     */
    private Block pickEscapeBreakTarget(Companion c, World w, Location loc, Location target) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        // Direction toward target (sign only).
        int dx = (int) Math.signum(target.getX() - loc.getX());
        int dz = (int) Math.signum(target.getZ() - loc.getZ());

        // Candidates in priority order. Pre-compute (x,y,z) → Block lookups
        // lazily through the helper to avoid 10 redundant world reads when
        // the first candidate succeeds.
        Block b;
        // 1. Head block.
        b = candidate(w, x, y + 1, z); if (b != null) return b;
        // 2. Block above head — vertical escape route.
        b = candidate(w, x, y + 2, z); if (b != null) return b;
        // 3. Diagonal up (in target direction).
        if (dx != 0 || dz != 0) {
            b = candidate(w, x + dx, y + 1, z + dz); if (b != null) return b;
            b = candidate(w, x + dx, y + 1, z);      if (b != null) return b;
            b = candidate(w, x,      y + 1, z + dz); if (b != null) return b;
        }
        // 4. Sides at foot level — last resort.
        if (dx != 0) { b = candidate(w, x + dx, y, z); if (b != null) return b; }
        if (dz != 0) { b = candidate(w, x, y, z + dz); if (b != null) return b; }
        // 5. Truly random sideways at head height.
        for (int[] off : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            b = candidate(w, x + off[0], y + 1, z + off[1]); if (b != null) return b;
            b = candidate(w, x + off[0], y,     z + off[1]); if (b != null) return b;
        }
        return null;
    }

    /**
     * Helper: return the block at the given coords IF it's diggable in
     * emergency mode AND not already air/passable. Refuses falling
     * blocks above the companion (sand/gravel ceilings) UNLESS the
     * config explicitly allows breaking them; even then the path will
     * just dump them on her head, so we deprioritize.
     *
     * <p>Returns {@code null} when the block is air, water, lava,
     * bedrock, or otherwise not a useful escape target.
     */
    private Block candidate(World w, int x, int y, int z) {
        if (y >= w.getMaxHeight() || y <= w.getMinHeight()) return null;
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m.isAir()) return null;
        // Never break lava — that would be... very bad.
        if (WorldAwareness.isLethalHazard(m)) return null;
        // Stone-like natural rock requires the explicit toggle.
        if (!stuckEscapeAllowStoneBreak
                && (m == Material.STONE || m == Material.COBBLESTONE
                || m == Material.DEEPSLATE || m == Material.COBBLED_DEEPSLATE
                || m == Material.NETHERRACK || m == Material.END_STONE
                || m == Material.BLACKSTONE || m == Material.BASALT)) {
            return null;
        }
        if (!WorldAwareness.isEmergencyDiggable(m)) return null;
        return b;
    }

    /**
     * Detect the suffocating-or-pinned state. True if either the foot
     * or head block is non-passable (we're inside terrain) or the head
     * block above is solid AND the foot block is also solid (truly
     * boxed in). Callers use this to decide when escape mode finishes.
     */
    private boolean isHeadOrFootInSolid(Companion c) {
        if (c.entity == null) return false;
        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        Block foot = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        return WorldAwareness.wouldSuffocate(foot)
            || WorldAwareness.wouldSuffocate(head);
    }

    /** 2D (XZ-plane) distance between two locations. */
    private static double horizDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * True if a straight ray from {@code from} to {@code to} hits no
     * solid block along the way — i.e. she has line-of-sight to fire an
     * arrow that won't bonk into terrain. Used by the bow path to
     * suppress wasted shots when she's in a dip / behind a wall / under
     * a ceiling that blocks the angle.
     *
     * <p>Cheap: a single {@code World#rayTraceBlocks} call which is
     * O(distance) and skips passable blocks (water, grass, doors). We
     * use {@link org.bukkit.FluidCollisionMode#NEVER} so a target
     * standing in shallow water still counts as visible. Returns
     * {@code true} on any reflection / API failure so a bug here can't
     * permanently silence her bow.
     */
    /**
     * True if the companion has line-of-sight to {@code target} — used to
     * stop her sensing / engaging mobs through solid walls. We check from her
     * eye to the target's eye AND to the target's feet, so a mob whose eye is
     * peeking but body is hidden (or vice-versa) is still handled sensibly.
     * Falls back to "visible" on any error so a glitch can't make her blind.
     */
    private boolean companionCanSee(Companion c, LivingEntity target) {
        if (c == null || c.entity == null || target == null) return false;
        Location eye = c.entity.getLocation().clone().add(0, 1.5, 0);
        if (hasClearShot(eye, target.getEyeLocation())) return true;
        return hasClearShot(eye, target.getLocation().clone().add(0, 0.2, 0));
    }

    private static boolean hasClearShot(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null) return true;
        if (from.getWorld() != to.getWorld()) return false;
        try {
            Vector dir = to.toVector().subtract(from.toVector());
            double dist = dir.length();
            if (dist < 0.1) return true; // basically the same spot
            dir.multiply(1.0 / dist); // normalize
            org.bukkit.util.RayTraceResult hit = from.getWorld().rayTraceBlocks(
                    from, dir, dist,
                    org.bukkit.FluidCollisionMode.NEVER,
                    true /* ignorePassableBlocks */);
            return hit == null || hit.getHitBlock() == null;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Find a nearby standable spot that has a clear shot to {@code threat}, so
     * she can step out of cover (a tree, a ceiling lip) to fire instead of
     * plinking arrows into it or standing idle. Checks a handful of ring
     * offsets around her; nearest valid spot wins. Returns null if none found
     * (the caller then just closes in toward the threat). Cheap + bounded: at
     * most ~20 short ray-traces, only while she has no line of sight.
     */
    private Location findFiringPosition(Companion c, LivingEntity threat) {
        if (c.entity == null) return null;
        Location base = c.entity.getLocation();
        World w = base.getWorld();
        if (w == null) return null;
        Location targetEye = threat.getEyeLocation();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int[][] offs = {
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4},
                {6, 0}, {-6, 0}, {0, 6}, {0, -6}
        };
        Location best = null;
        double bestSq = Double.MAX_VALUE;
        for (int[] o : offs) {
            Location spot = standableSpot(w, bx + o[0], by, bz + o[1]);
            if (spot == null) continue;
            Location eye = spot.clone().add(0, 1.5, 0);
            if (!hasClearShot(eye, targetEye)) continue;
            double dsq = spot.distanceSquared(base);
            if (dsq < bestSq) { bestSq = dsq; best = spot; }
        }
        return best;
    }

    /**
     * A standable block column near (x, ~y, z): solid floor with two passable
     * blocks above for her body. Searches a few Y levels around {@code yGuess}
     * (so a spot one step up/down still counts). Returns a block-centered
     * Location or null.
     */
    private Location standableSpot(World w, int x, int yGuess, int z) {
        for (int dy = 0; dy <= 3; dy++) {
            int[] candidates = (dy == 0) ? new int[]{0} : new int[]{-dy, dy};
            for (int s : candidates) {
                int y = yGuess + s;
                Block floor = w.getBlockAt(x, y - 1, z);
                Block feet = w.getBlockAt(x, y, z);
                Block head = w.getBlockAt(x, y + 1, z);
                if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    /**
     * Non-destructive "climb out of the hole" used during combat only. When
     * she's chasing a mob that's higher than her and a wall blocks the
     * horizontal step, she rises straight up THROUGH the open air above her
     * head (the same air she fell in through) toward the rim — instead of
     * digging, which could break a player's base wall. If the block above her
     * head is solid (she'd have to break something to get out) she does
     * nothing and waits: never breaks or places a block, so it can't grief.
     *
     * @return true if she climbed this tick.
     */
    private boolean tryCombatClimb(Companion c, Location standLoc, World w, Location target, double step) {
        if (c == null || !c.approachingEntity || c.entity == null) return false;
        if (target.getY() - standLoc.getY() <= 0.5) return false; // target isn't above her
        // Need open air above her head (feet at y, head at y+1, rise needs y+2 clear).
        Block above = w.getBlockAt(standLoc.getBlockX(), standLoc.getBlockY() + 2, standLoc.getBlockZ());
        if (!above.isPassable()) return false; // boxed in solid → don't break, just wait
        Location up = standLoc.clone().add(0, Math.min(step, 0.5), 0);
        Vector look = target.toVector().subtract(up.toVector());
        if (look.lengthSquared() > 1e-6) {
            up.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
        }
        c.entity.smoothMoveTo(up);
        return true;
    }

    /**
     * Clear any door / breakable obstacle in the column at {@code wp}
     * (foot + head blocks). Call this right before stepping into the
     * waypoint — that way the obstacle is gone by the time the move
     * packet places her in the cell. The pathfinder only chose the
     * waypoint in the first place if the relevant capability flag was
     * on, so we re-check the same flags here to stay consistent.
     *
     * <p>Doors get opened (non-destructive). Plants and terrain are
     * broken via {@link Block#breakNaturally()} which fires a
     * {@link BlockBreakEvent} so protection plugins can cancel — if
     * cancelled the block stays and the companion will get stuck for
     * a tick, then re-pathfind around it.
     *
     * <p>Cheap when nothing's in the way: two block-type lookups and
     * a couple of boolean checks.
     */
    private void clearObstaclesAt(Companion c, Location wp) {
        if (wp == null || wp.getWorld() == null) return;
        World w = wp.getWorld();
        int bx = wp.getBlockX();
        int by = wp.getBlockY();
        int bz = wp.getBlockZ();
        Player owner = Bukkit.getPlayer(c.owner);

        // Foot first, then head — so dropping leaves doesn't change
        // the head block underneath the foot block we just walked into.
        for (int dy = 0; dy < 2; dy++) {
            Block b = w.getBlockAt(bx, by + dy, bz);
            Material m = b.getType();

            // Already passable → nothing to do (covers air, water, grass).
            if (b.isPassable()) continue;

            // Door / fence gate → open in place.
            if (pathOpenDoors && CompanionPathfinder.isHandOpenable(m)) {
                openIfClosed(b);
                continue;
            }

            // Plant-class soft block → break (with player attribution).
            if (pathBreakPlants && CompanionPathfinder.isBreakablePlant(m)) {
                breakObstacleBlock(c, b, owner);
                continue;
            }

            // Tunneling → break terrain. Strongly opt-in.
            if (pathTunneling && CompanionPathfinder.isBreakableTerrain(m)) {
                breakObstacleBlock(c, b, owner);
                continue;
            }
        }

        // Also pre-open any door / gate in the immediately adjacent cells.
        // Doors in a 1-wide frame often sit just off the exact waypoint, so
        // opening only the waypoint cell left her bumping a closed door beside
        // her path and getting stuck — open the neighbours too.
        if (pathOpenDoors) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = 0; dy < 2; dy++) {
                    Block nb = w.getBlockAt(bx + d[0], by + dy, bz + d[1]);
                    if (CompanionPathfinder.isHandOpenable(nb.getType())) openIfClosed(nb);
                }
            }
        }
    }

    /**
     * Set a door / fence gate's {@code open} BlockData property to true
     * if it isn't already. Plays the appropriate vanilla sound at the
     * block. No-op for non-openable blocks.
     */
    private void openIfClosed(Block b) {
        try {
            BlockData bd = b.getBlockData();
            if (!(bd instanceof Openable openable)) return;
            if (openable.isOpen()) return;
            openable.setOpen(true);
            b.setBlockData(bd);
            // Play a sound so it feels like a real interaction. Fence
            // gates use a different sound than doors.
            Sound s = b.getType().name().endsWith("_FENCE_GATE")
                    ? Sound.BLOCK_FENCE_GATE_OPEN
                    : Sound.BLOCK_WOODEN_DOOR_OPEN;
            b.getWorld().playSound(b.getLocation(), s, 1.0f, 1.0f);
        } catch (Throwable t) {
            // Block might have been replaced between our check and now
            // (chunk unloaded, plugin protection, etc.) — non-fatal.
            getLogger().fine("openIfClosed failed at " + b.getLocation() + ": " + t.getMessage());
        }
    }

    /**
     * Break {@code b}, attributing the action to the companion's owner
     * via {@link BlockBreakEvent}. Protection plugins (WorldGuard,
     * GriefPrevention, etc.) hook that event and can cancel based on
     * the player passed in — if they do, we leave the block standing
     * and the companion will treat it as an obstacle again next tick.
     *
     * <p>If the owner isn't online we skip silently — we want
     * destructive actions to always be attributable to a real player
     * for moderation/log auditing.
     */
    private void breakObstacleBlock(Companion c, Block b, Player owner) {
        if (b == null || b.getType() == Material.AIR) return;
        if (owner == null) return; // never break "anonymously"
        try {
            BlockBreakEvent ev = new BlockBreakEvent(b, owner);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) return;

            // Visual swing on the companion before the block pops, so
            // the cause-and-effect feels right to anyone watching.
            if (c.entity != null && !c.entity.isDead()) c.entity.swingMainHand();

            // breakNaturally drops items + triggers the standard break
            // particles/sound. Bare-hands breaking — the speed doesn't
            // matter since the block is gone instantly anyway.
            b.breakNaturally();
        } catch (Throwable t) {
            getLogger().fine("breakObstacleBlock failed at " + b.getLocation() + ": " + t.getMessage());
        }
    }

    /**
     * Find the Y of the top surface of the highest non-passable block at
     * column {@code (x, z)} at or below {@code scanFromY}, returning that
     * surface (block Y + 1) so a standing entity placed at it would be
     * standing on the block.
     *
     * <p><b>Underwater honor.</b> If {@code desiredY} is already in a
     * water cell at this column, we return {@code desiredY} directly.
     * This lets the companion match an owner's diving depth instead of
     * always surfacing — without it, even a path that says "go to y=58
     * underwater" gets pulled back up to the lake top because the
     * normal water-tracking logic always returns the surface.
     *
     * <p><b>Water surface handling.</b> Otherwise, water blocks are
     * passable in vanilla terms, so a naive scan would skip every water
     * block and sink the companion to the lake bed. We track the
     * topmost water Y we passed through; if we then hit a solid floor
     * below it, we return the topmost water Y so the companion floats
     * at the surface (her foot lives in the topmost water block).
     *
     * <p>Scans at most 16 blocks down before giving up and returning
     * {@code fallback}, which keeps the search cheap for chatty
     * movement ticks.
     */
    private static double findGroundY(World w, int x, int z, double scanFromY,
                                       double desiredY, double fallback) {
        // Underwater honor — match the path's intended depth when it's
        // a real water cell. Saves a scan in the common case too.
        int desiredYInt = (int) Math.floor(desiredY);
        if (desiredYInt > w.getMinHeight() && desiredYInt + 1 < w.getMaxHeight()) {
            if (w.getBlockAt(x, desiredYInt, z).getType() == Material.WATER) {
                return desiredYInt;
            }
        }

        int startY = Math.min((int) Math.floor(scanFromY), w.getMaxHeight() - 1);
        int minY = Math.max(w.getMinHeight(), startY - 16);
        int highestWaterY = Integer.MIN_VALUE;
        for (int y = startY; y >= minY; y--) {
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m == Material.WATER) {
                if (highestWaterY == Integer.MIN_VALUE) highestWaterY = y;
                continue;
            }
            if (!b.isPassable()) {
                if (highestWaterY != Integer.MIN_VALUE) return highestWaterY;
                return y + 1.0;
            }
        }
        if (highestWaterY != Integer.MIN_VALUE) return highestWaterY;
        return fallback;
    }

    /**
     * True if a player-sized standing volume at this location overlaps a
     * solid block — checks the foot block and the head block above it,
     * which is the same 2-block volume vanilla Player collision uses.
     */
    private static boolean isBlocked(Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return !w.getBlockAt(x, y, z).isPassable()
                || !w.getBlockAt(x, y + 1, z).isPassable();
    }

    /**
     * Lerp between two angles in degrees, taking the short way around the
     * 360° wrap. {@code t = 0} returns {@code from}; {@code t = 1} returns
     * {@code to}; values in between blend.
     */
    private static double lerpAngleDeg(double from, double to, double t) {
        // Reduce the difference to the (-180, 180] range so a 359°→1° step
        // moves +2° instead of -358°.
        double delta = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0;
        return from + delta * t;
    }

    /**
     * Track {@code target} with the companion's head — pitch (up/down) plus
     * yaw clamped to ±90° of her body yaw, so she looks toward you while
     * walking without owl-twisting her neck. Used for FOLLOW.
     */
    private void faceToward(Companion c, Location target) {
        if (c.entity == null) return;
        Location loc = c.entity.getLocation();
        // Approximate eye height for a standing player.
        double dx = target.getX() - loc.getX();
        double dy = target.getY() - (loc.getY() + 1.62);
        double dz = target.getZ() - loc.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-4 && Math.abs(dy) < 1e-4) return;

        // Pitch — clamp so she doesn't crank her neck past human range.
        double pitchRad = -Math.atan2(dy, horiz);
        pitchRad = Math.max(-Math.PI / 3.0, Math.min(Math.PI / 3.0, pitchRad));
        c.entity.setHeadPitch((float) Math.toDegrees(pitchRad));

        // Head yaw — track the target, but clamp to ±90° of body yaw so she
        // can't spin her head all the way backwards. If owner is dead-behind
        // her, she'll look as far as she can and stop there.
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float bodyYaw = loc.getYaw();
        float diff = (float) (((targetYaw - bodyYaw) % 360.0 + 540.0) % 360.0 - 180.0);
        diff = Math.max(-90f, Math.min(90f, diff));
        c.entity.setHeadYaw(bodyYaw + diff);
    }

    // ============== CHAT ==============

    private void doSay(Player p, String message) {
        if (message == null || message.isBlank()) {
            p.sendMessage("§d(✧) usage: /kc say <message>");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastChatMillis.get(p.getUniqueId());
        if (last != null && (now - last) < chatCooldownMillis) {
            long remaining = chatCooldownMillis - (now - last);
            p.sendMessage("§d(✧) one sec~ try again in §f"
                    + Math.max(1, remaining / 100) / 10.0 + "§ds");
            return;
        }

        Companion c = companions.computeIfAbsent(p.getUniqueId(), u -> loadOrCreate(p));

        // #1: deterministic, AI-free answers for "where is the nearest
        // <structure>" and inventory questions — accurate (no hallucinated
        // coordinates), free, and they work even with no API key set.
        if (tryAnswerLocationQuery(p, c, message)) {
            lastChatMillis.put(p.getUniqueId(), now);
            return;
        }

        String key = resolvedApiKey();
        if (key.isEmpty()) {
            p.sendMessage("§c(✧) AI key not configured — set api-key in config.yml "
                    + "or DEEPSEEK_API_KEY env var");
            return;
        }
        lastChatMillis.put(p.getUniqueId(), now);
        // Compose request body off the main thread, then fire HTTP off-thread too.
        String trimmed = message.length() > 1024 ? message.substring(0, 1024) : message;
        synchronized (c.history) {
            c.history.add(new Message("user", trimmed));
            trimHistory(c);
        }

        showThinking(p, c);

        // Build the world-context snapshot on the MAIN thread (it reads
        // getNearbyEntities, which Paper rejects off-thread), then hand the
        // finished string to the async HTTP call.
        String ctx = null;
        if (deepSeekContextEnabled) {
            try { ctx = buildContextSnapshot(c); } catch (Throwable ignored) {}
        }
        final String contextSnapshot = ctx;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String reply;
            try {
                reply = callDeepSeek(key, c, contextSnapshot);
            } catch (Throwable t) {
                getLogger().warning("(✧) DeepSeek call failed: " + t);
                reply = null;
            }
            final String finalReply = reply;
            Bukkit.getScheduler().runTask(this, () -> deliverReply(p, c, finalReply));
        });
    }

    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.builder().character('§').build();

    private void showThinking(Player p, Companion c) {
        // Action bar (not chat) so she leaves a single chat line — the reply —
        // instead of a persistent "…thinking" message plus the answer.
        p.sendActionBar(SECTION.deserialize("§d<§f" + c.name + "§d> §7…thinking"));
    }

    private void deliverReply(Player p, Companion c, String reply) {
        if (reply == null || reply.isBlank()) {
            p.sendMessage("§c(✧) " + c.name + " couldn't think of anything (API error)");
            return;
        }
        synchronized (c.history) {
            c.history.add(new Message("assistant", reply));
            trimHistory(c);
        }
        saveMemory(c);

        // Bubble above her head so the chat feels grounded in the world,
        // not just chat-spam.
        showChatBubble(c, ChatColor.stripColor(reply));

        String formatted = "§d<§f" + c.name + "§d> §f" + ChatColor.stripColor(reply);
        if (privateReplies) {
            p.sendMessage(formatted);
            return;
        }
        // Broadcast to nearby players so others can listen in.
        Location origin = c.entity != null ? c.entity.getLocation() : p.getLocation();
        double radSq = broadcastRadius * broadcastRadius;
        for (Player other : origin.getWorld().getPlayers()) {
            if (other.getLocation().distanceSquared(origin) <= radSq) {
                other.sendMessage(formatted);
            }
        }
    }

    private void trimHistory(Companion c) {
        // Each "turn" = up to 2 messages (user + assistant). Cap on messages to
        // keep memory bounded even when many user messages stack up without replies.
        int cap = maxHistoryTurns * 2;
        while (c.history.size() > cap) c.history.remove(0);
    }

    /**
     * #3: when a player types a normal chat message that contains her current
     * name, treat it like {@code /kc say} so she replies without the command.
     * The chat event is async, so we bounce the actual handling onto the main
     * thread. doSay() applies the per-player cooldown, so this can't be spammed.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        if (!nameChatEnabled) return;
        Player p = e.getPlayer();
        Companion c = companions.get(p.getUniqueId());
        if (c == null || c.name == null || c.name.length() < 2) return;
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) return;
        if (!raw.toLowerCase(Locale.ROOT).contains(c.name.toLowerCase(Locale.ROOT))) return;
        Bukkit.getScheduler().runTask(this, () -> doSay(p, raw));
    }

    /**
     * #2: shield blocks near a spawned companion from explosions (TNT,
     * creeper, ghast fireball, wither, end crystal, bed/anchor). Protected
     * blocks are simply dropped from the explosion's block list, so they're
     * never destroyed — a plank wall stays a plank wall, with no item drops,
     * physics cascades, or rebuild lag. Only blocks within {@code
     * blockRestoreRadius} of a companion are kept; everything farther still
     * blows up.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent e) {
        protectBlocksNearCompanion(e.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        protectBlocksNearCompanion(e.blockList());
    }

    private void protectBlocksNearCompanion(java.util.List<Block> blocks) {
        if (!blockRestoreEnabled || blocks.isEmpty() || companions.isEmpty()) return;

        java.util.List<Location> guards = new java.util.ArrayList<>();
        for (Companion c : companions.values()) {
            if (c.entity == null) continue;
            Location l = c.entity.getLocation();
            if (l != null && l.getWorld() != null) guards.add(l);
        }
        if (guards.isEmpty()) return;

        double r2 = blockRestoreRadius * blockRestoreRadius;
        int saved = 0;
        for (java.util.Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Location bl = it.next().getLocation();
            for (Location g : guards) {
                if (g.getWorld() == bl.getWorld() && g.distanceSquared(bl) <= r2) {
                    it.remove(); // keep the block intact — as if never destroyed
                    saved++;
                    break;
                }
            }
        }

        if (saved > 0 && blockRestoreBubble) {
            for (Companion c : companions.values()) {
                if (c.entity != null) { showChatBubble(c, "not on my watch~ ✨"); break; }
            }
        }
    }

    /**
     * #1: answer "where is the nearest &lt;structure&gt;" and "what's in your
     * bag" deterministically — accurate (no hallucinated coordinates), free,
     * and works with no API key. Returns true if the message was handled here.
     */
    private boolean tryAnswerLocationQuery(Player p, Companion c, String message) {
        String m = message.toLowerCase(Locale.ROOT);

        // ---- Inventory questions ----
        if (m.contains("your bag") || m.contains("your inventory") || m.contains("holding")
                || m.contains("carrying")
                || ((m.contains("what") || m.contains("which"))
                    && (m.contains("you have") || m.contains("you got") || m.contains("items")))) {
            answerInventory(p, c);
            return true;
        }

        // ---- Structure questions ----
        boolean asksLocation = m.contains("where") || m.contains("how far")
                || m.contains("nearest") || m.contains("closest")
                || m.contains("locate") || m.contains("find");
        if (!asksLocation || !WorldAwareness.mentionsKnownStructure(m)) return false;

        Location origin = (c.entity != null) ? c.entity.getLocation() : p.getLocation();
        World w = origin.getWorld();
        if (w == null) return false;

        WorldAwareness.StructureHit hit =
                WorldAwareness.locateNamedStructure(w, origin, m, structureSearchChunks);
        if (hit == null) {
            speak(c, p, "hmm, i can't sense one nearby~ maybe it's really far away!");
            return true;
        }
        int dist = (int) Math.round(hit.distance);
        String dir = WorldAwareness.cardinalDirection(origin, hit.location);
        speak(c, p, "the nearest " + WorldAwareness.prettyStructure(hit.type)
                + " is about " + dist + " blocks to the " + dir + "~ (near "
                + hit.location.getBlockX() + ", " + hit.location.getBlockZ() + ")");
        return true;
    }

    /** #1: list what's in her bag, in her voice. */
    private void answerInventory(Player p, Companion c) {
        Inventory bag = ensureBag(c);
        java.util.Map<Material, Integer> counts = new java.util.LinkedHashMap<>();
        for (ItemStack it : bag.getContents()) {
            if (it == null || it.getType() == Material.AIR) continue;
            counts.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        if (counts.isEmpty()) {
            speak(c, p, "my bag's totally empty right now~ nothing to show!");
            return;
        }
        StringBuilder sb = new StringBuilder("in my bag i've got: ");
        int n = 0;
        for (java.util.Map.Entry<Material, Integer> en : counts.entrySet()) {
            if (n > 0) sb.append(", ");
            sb.append(prettyMaterial(en.getKey())).append(" x").append(en.getValue());
            if (++n >= 8) { sb.append(", and a bit more~"); break; }
        }
        speak(c, p, sb.toString());
    }

    /** Bubble + chat delivery shared by the deterministic answers (mirrors deliverReply). */
    private void speak(Companion c, Player audience, String text) {
        showChatBubble(c, text);
        String formatted = "§d<§f" + c.name + "§d> §f" + text;
        if (privateReplies) {
            if (audience != null) audience.sendMessage(formatted);
            return;
        }
        Location origin = (c.entity != null) ? c.entity.getLocation()
                : (audience != null ? audience.getLocation() : null);
        if (origin == null || origin.getWorld() == null) {
            if (audience != null) audience.sendMessage(formatted);
            return;
        }
        double radSq = broadcastRadius * broadcastRadius;
        for (Player other : origin.getWorld().getPlayers()) {
            if (other.getLocation().distanceSquared(origin) <= radSq) other.sendMessage(formatted);
        }
    }

    private static String prettyMaterial(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Blocking HTTP call. Caller must run this off the main thread. */
    /**
     * Sample the owner's vital stats and bubble a contextual warning if
     * any of them just crossed a critical threshold. Edge-triggered:
     * the warning fires on the transition (e.g., HP just dropped from
     * 7 → 5) rather than every tick while HP stays low. This avoids
     * spam while still being useful.
     *
     * <p>Each warning shares the global {@link Companion#lastStateWarnTick}
     * cooldown so two simultaneous events (low HP + on fire) don't
     * fire two bubbles in the same second.
     *
     * <p>Cheap — four field reads, four comparisons, one conditional
     * call to {@link #showChatBubble}.
     */
    // ============== ITEM & XP PICKUP ==============

    /**
     * Vacuum nearby dropped items and XP orbs. Throttled to once per
     * {@link #pickupScanInterval} movement ticks per companion — cheap
     * when there's nothing in range (one bounded {@code getNearbyEntities}
     * call), and the throttle keeps it at most every other tick by
     * default.
     *
     * <p>Behavior:
     * <ul>
     *   <li><b>Items:</b> tried via {@code Inventory#addItem}, which
     *       returns whatever didn't fit. If the bag's full and overflow-
     *       discard is on, the leftovers are silently dropped (the player
     *       requested this — "just clear the excess"). Pickup-delay flag
     *       is respected so freshly-dropped items still belong to the
     *       player who dropped them for the first 10 ticks.</li>
     *   <li><b>XP orbs:</b> her fake-player handle has no real player
     *       level, so we award the XP to the OWNER instead. Reads as
     *       "she's collecting xp for you", which is what most players
     *       expect anyway.</li>
     * </ul>
     *
     * <p>Visual feedback: arm-swing + the vanilla pickup sound when
     * something's actually grabbed, throttled to one swing per ~6
     * ticks so a pile of items doesn't trigger 20 swings in a row.
     */
    private void tickPickup(Companion c, Player owner, long tick) {
        if (c.entity == null || c.inventory == null) return;
        if (tick - c.lastPickupScanTick < pickupScanInterval) return;
        c.lastPickupScanTick = tick;

        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) return;

        double r = pickupRadius;
        // Slightly taller vertical box so items bouncing on her hitbox
        // (which is ~1.8 tall) get caught even if their Y lands at her
        // head. Horizontal stays tight to feel like a player.
        double yr = Math.max(r, 1.5);

        boolean swungThisTick = false;
        for (Entity e : w.getNearbyEntities(loc, r, yr, r)) {
            if (e instanceof Item drop) {
                if (drop.isDead()) continue;
                // Vanilla pickup-delay courtesy — give the dropper a
                // chance to grab their own stuff back.
                if (pickupRespectVanillaDelay && drop.getPickupDelay() > 0) continue;

                ItemStack stack = drop.getItemStack();
                if (stack == null || stack.getType() == Material.AIR) continue;

                java.util.HashMap<Integer, ItemStack> leftover = c.inventory.addItem(stack.clone());
                if (leftover.isEmpty()) {
                    // Whole stack fit — remove the dropped entity.
                    drop.remove();
                } else {
                    // Some of it fit, some didn't.
                    int leftoverAmount = leftover.values().stream()
                            .mapToInt(ItemStack::getAmount).sum();
                    if (pickupDiscardOverflow) {
                        // Per spec: pick it up anyway, excess vanishes.
                        drop.remove();
                    } else {
                        // Reduce the dropped stack to the leftover amount
                        // so the picked-up portion is gone but the rest
                        // still sits on the ground for someone else.
                        if (leftoverAmount >= stack.getAmount()) {
                            // Nothing actually fit — leave the drop alone.
                            continue;
                        }
                        ItemStack remaining = stack.clone();
                        remaining.setAmount(leftoverAmount);
                        drop.setItemStack(remaining);
                    }
                }

                // Visual + audio feedback. Swing + sound throttled so a
                // pile of items doesn't fire one per drop.
                if (!swungThisTick && tick - c.lastPickupTick > 4) {
                    c.entity.swingMainHand();
                    c.lastPickupTick = tick;
                    swungThisTick = true;
                    try {
                        w.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.6f);
                    } catch (Throwable ignored) { /* sound name might shift */ }
                }
            } else if (e instanceof ExperienceOrb orb && pickupXpOrbs) {
                if (orb.isDead()) continue;
                int xp = orb.getExperience();
                if (xp <= 0) { orb.remove(); continue; }
                // Award to the owner since she has no real player level.
                if (owner != null && owner.isOnline()) {
                    try {
                        owner.giveExp(xp);
                    } catch (Throwable ignored) { /* API safety */ }
                }
                orb.remove();
                if (!swungThisTick && tick - c.lastPickupTick > 4) {
                    c.entity.swingMainHand();
                    c.lastPickupTick = tick;
                    swungThisTick = true;
                    try {
                        w.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.4f);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void monitorPlayerState(Companion c, Player owner, long tick) {
        if (owner == null) return;
        // Sample.
        double hp   = owner.getHealth();
        int    food = owner.getFoodLevel();
        int    fire = owner.getFireTicks();
        int    air  = owner.getRemainingAir();

        // Decide whether we'd LIKE to fire a warning this tick. The
        // priority is: drowning > on fire > low HP > low hunger.
        // First match wins — kept narrow on purpose so she doesn't
        // try to shout 4 things at once.
        String warning = null;
        if (air < 100 && c.lastSampledOwnerAir >= 100) {
            warning = "you're drowning! swim up!!";
        } else if (fire > 20 && c.lastSampledOwnerFire <= 20) {
            warning = "you're on fire!! water!!";
        } else if (hp <= playerStateLowHpThreshold && c.lastSampledOwnerHp > playerStateLowHpThreshold) {
            warning = "you're hurt!! eat something! (>_<)";
        } else if (food <= playerStateLowFoodThreshold
                && c.lastSampledOwnerFood > playerStateLowFoodThreshold) {
            warning = "you should eat... your tummy!!";
        }

        // Cooldown gate AFTER edge detection so we don't lose the
        // edge sample if it's within cooldown.
        if (warning != null && tick - c.lastStateWarnTick > playerStateWarnCooldownTicks) {
            c.lastStateWarnTick = tick;
            showChatBubble(c, warning);
        }

        // Update last-sampled values regardless of whether we fired —
        // edge detection needs the previous value next tick.
        c.lastSampledOwnerHp   = hp;
        c.lastSampledOwnerFood = food;
        c.lastSampledOwnerFire = fire;
        c.lastSampledOwnerAir  = air;
    }

    /**
     * Build the world-snapshot system message for {@link #callDeepSeek}.
     * Plain English, ~150–300 tokens — enough that the model's replies
     * naturally reference the current scene ("ooh, a cow!") without
     * blowing the prompt budget.
     *
     * <p>Returns an empty string if the companion isn't currently
     * spawned, in which case the caller skips the extra system message.
     */
    private String buildContextSnapshot(Companion c) {
        if (c.entity == null || c.entity.isDead()) return "";
        Player owner = Bukkit.getPlayer(c.owner);
        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        if (w == null) return "";

        StringBuilder sb = new StringBuilder(256);
        sb.append("Current situation (you can reference this casually if relevant, ");
        sb.append("but don't list it back like a status report):\n");

        // Where + when + weather.
        sb.append("- Location: ").append(loc.getBlockX()).append(", ")
          .append(loc.getBlockY()).append(", ").append(loc.getBlockZ())
          .append(" (").append(WorldAwareness.biomeLabel(loc)).append(", ")
          .append(WorldAwareness.dimensionLabel(w)).append(")\n");
        sb.append("- Time: ").append(WorldAwareness.timeLabel(w));
        if (WorldAwareness.isRaining(w)) sb.append(", raining");
        sb.append("\n");

        // Owner state.
        if (owner != null && owner.isOnline()) {
            sb.append("- Player ").append(owner.getName()).append(": ")
              .append((int) Math.round(owner.getHealth())).append("/20 hp, ")
              .append(owner.getFoodLevel()).append("/20 food");
            if (owner.getFireTicks() > 0) sb.append(", on fire");
            if (owner.getRemainingAir() < 100) sb.append(", drowning");
            sb.append("\n");
        }

        // Her own state — equipment summary.
        if (deepSeekContextInventory) {
            String invSummary = summarizeInventoryForChat(c);
            if (!invSummary.isEmpty()) {
                sb.append("- Your bag has: ").append(invSummary).append("\n");
            }
        }

        // Nearby creatures — short list, capped.
        if (deepSeekContextNearbyMobs) {
            String nearby = summarizeNearbyForChat(c);
            if (!nearby.isEmpty()) {
                sb.append("- Nearby: ").append(nearby).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Compact human-readable summary of the companion's bag contents
     * — names categories rather than listing every stack. Used by
     * {@link #buildContextSnapshot}; intentionally short.
     */
    private String summarizeInventoryForChat(Companion c) {
        if (c.inventory == null) return "";
        StringBuilder sb = new StringBuilder();
        ItemStack melee = ItemKnowledge.bestMeleeWeapon(c.inventory);
        if (melee != null) {
            sb.append(ItemKnowledge.of(melee.getType()).shortDesc);
        }
        ItemStack ranged = ItemKnowledge.bestRangedWeapon(c.inventory);
        if (ranged != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ItemKnowledge.of(ranged.getType()).shortDesc);
        }
        ItemStack food = ItemKnowledge.bestFood(c.inventory);
        if (food != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ItemKnowledge.of(food.getType()).shortDesc);
        }
        if (ItemKnowledge.has(c.inventory, ItemKnowledge.Category.POTION)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("a potion");
        }
        if (sb.length() == 0) return "nothing useful";
        return sb.toString();
    }

    /**
     * Compact human-readable summary of nearby living entities. Caps
     * at a small total so a busy farm doesn't produce a 4 KB context.
     */
    private String summarizeNearbyForChat(Companion c) {
        if (c.entity == null) return "";
        Location origin = c.entity.getLocation();
        double r = 12.0;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        int total = 0;
        for (Entity e : origin.getWorld().getNearbyEntities(origin, r, r, r)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            // Skip the companion's own hitbox + her ServerPlayer (her UUID
            // happens to be different but world.getNearbyEntities won't
            // return her anyway — she's not registered as a Bukkit entity).
            if (hitboxToOwner.containsKey(le.getUniqueId())) continue;
            String key;
            if (le instanceof Player p) {
                key = p.getName();
            } else {
                key = le.getType().getKey().getKey();
            }
            counts.merge(key, 1, Integer::sum);
            total++;
            if (total >= 12) break;
        }
        if (counts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            int n = entry.getValue();
            if (n > 1) sb.append(n).append("× ");
            sb.append(entry.getKey());
        }
        return sb.toString();
    }

    private String callDeepSeek(String apiKey, Companion c, String contextSnapshot) throws Exception {
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                personalityTemplate.replace("{name}", c.name == null ? defaultName : c.name));
        messages.add(system);

        // Optional dynamic context — a second system message describing the
        // world around the companion. It's built on the MAIN thread by the
        // caller (it reads getNearbyEntities, which Paper forbids off-thread)
        // and passed in; null when context is disabled.
        if (contextSnapshot != null && !contextSnapshot.isEmpty()) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("role", "system");
            ctx.addProperty("content", contextSnapshot);
            messages.add(ctx);
        }

        synchronized (c.history) {
            for (Message m : c.history) {
                JsonObject obj = new JsonObject();
                obj.addProperty("role", m.role);
                obj.addProperty("content", m.content);
                messages.add(obj);
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("stream", false);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(httpTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            getLogger().warning("(✧) DeepSeek HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(400, resp.body().length())));
            return null;
        }
        JsonObject out = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray choices = out.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return null;
        JsonElement choice = choices.get(0);
        JsonObject msg = choice.getAsJsonObject().getAsJsonObject("message");
        if (msg == null) return null;
        JsonElement content = msg.get("content");
        if (content == null || content.isJsonNull()) return null;
        return content.getAsString().trim();
    }

    // ============== EVENTS ==============

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (buildManager != null) buildManager.onPlayerQuit(id);
        Companion c = companions.remove(id);
        if (c != null) {
            saveMemory(c);
            if (c.realEntity) despawnRealEntity(c); else despawnEntity(c);
        }
        // Despawn the player's helper drones (ephemeral — no persistence).
        List<Companion> ex = extras.remove(id);
        if (ex != null) for (Companion d : ex) despawnCompanion(d);
        // Drop the player's entries from the auxiliary per-player maps too —
        // these aren't tied to the Companion object and would otherwise
        // accumulate one stale entry per player forever (slow memory leak on
        // a long-uptime server with player churn).
        huntPicks.remove(id);
        lastChatMillis.remove(id);
        bedrockNoticeShown.remove(id);
    }

    /**
     * Despawn companions left in a world that's being unloaded at runtime
     * (e.g. a Multiverse / KawaiiWorlds world being torn down). A world can't
     * unload while players are inside it, so any companion still here is
     * already orphaned from its departed owner — if we don't clean it up the
     * tick loop keeps poking a now-invalid entity, and a world-integrated fake
     * player left in the level's player list pins the whole world in memory.
     * Memory is saved first; the owner re-summons when they return.
     */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        World unloading = e.getWorld();
        Iterator<Map.Entry<UUID, Companion>> it = companions.entrySet().iterator();
        while (it.hasNext()) {
            Companion c = it.next().getValue();
            if (companionWorld(c) != unloading) continue;
            try { saveMemoryNow(c); } catch (Throwable ignored) {}
            if (c.realEntity) despawnRealEntity(c); else despawnEntity(c);
            it.remove();
        }
        for (List<Companion> list : extras.values()) {
            list.removeIf(d -> {
                if (companionWorld(d) != unloading) return false;
                try { despawnCompanion(d); } catch (Throwable ignored) {}
                return true;
            });
        }
    }

    /** The world a companion's body currently occupies, or null if it has none. */
    private World companionWorld(Companion c) {
        try {
            if (c.realEntity) {
                Entity e = liveRealEntity(c);
                return e == null ? null : e.getWorld();
            }
            return c.entity == null ? null : c.entity.getWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pull the companion across with the owner when they change worlds
     * (e.g. via KawaiiWorlds / a portal). The per-tick FOLLOW logic already
     * teleports on a world mismatch, but the destination world + chunks
     * aren't necessarily ready the instant the event fires — so we also
     * force a teleport a few ticks later once things have settled. Without
     * this she sometimes only "half" follows: the fake entity respawns
     * before the destination is loaded and the move is dropped.
     */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Companion c = companions.get(p.getUniqueId());
        if (c == null || c.entity == null) return;
        // Drop any stale path/stuck state from the old world so she doesn't
        // try to walk old waypoints in the new one.
        clearPath(c);
        c.stuckTicks = 0;
        c.escapeStartTick = 0;
        // Re-teleport once the destination is settled. Re-fetch the companion
        // each time in case it was dismissed in between.
        for (int delay : new int[] { 2, 10, 20 }) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player owner = Bukkit.getPlayer(p.getUniqueId());
                Companion cc = companions.get(p.getUniqueId());
                if (owner == null || !owner.isOnline() || cc == null || cc.entity == null) return;
                if (cc.entity.getWorld() != owner.getWorld()) {
                    cc.entity.teleport(spawnLocFor(owner));
                    updateHitbox(cc);
                }
            }, delay);
        }
    }

    // ============== PERSISTENCE ==============

    private File memoryFile(UUID id) {
        File dir = new File(getDataFolder(), "memory");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, id + ".yml");
    }

    private Companion loadOrCreate(Player p) {
        File f = memoryFile(p.getUniqueId());
        // skin starts as null = "auto", resolved against the skins/ folder at
        // spawn time. Players opt into a fixed override via /kc skin <name>.
        Companion c = new Companion(p.getUniqueId(), defaultName, null);
        if (!f.exists()) return c;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            String name = cfg.getString("name");
            String skin = cfg.getString("skin");
            if (name != null && !name.isBlank()) c.name = name;
            if (skin != null && !skin.isBlank()) c.skin = skin;
            // FEATURE 2 (leveling) + FEATURE 1 (bedrock form) persisted state.
            c.level = Math.max(1, Math.min(maxLevel, cfg.getInt("level", 1)));
            c.xp    = Math.max(0, cfg.getInt("xp", 0));
            String bType = cfg.getString("bedrock-type");
            if (bType != null && !bType.isBlank()) c.bedrockType = bType;
            // FEATURE 5: persisted mob-form choice (Java owners can morph too).
            c.mobForm = cfg.getBoolean("mob-form", false);
            List<Map<?, ?>> raw = cfg.getMapList("history");
            for (Map<?, ?> m : raw) {
                Object role = m.get("role"), content = m.get("content");
                if (role == null || content == null) continue;
                c.history.add(new Message(role.toString(), content.toString()));
            }
            // Restore her bag. ItemStack is ConfigurationSerializable so
            // YamlConfiguration round-trips it natively.
            List<?> rawInv = cfg.getList("inventory");
            if (rawInv != null && !rawInv.isEmpty()) {
                ensureBag(c);
                int max = Math.min(rawInv.size(), c.inventory.getSize());
                for (int i = 0; i < max; i++) {
                    Object slot = rawInv.get(i);
                    if (slot instanceof ItemStack it) c.inventory.setItem(i, it);
                }
            }
            List<?> rawInv2 = cfg.getList("inventory2");
            if (rawInv2 != null && !rawInv2.isEmpty()) {
                ensureBag2(c);
                int max = Math.min(rawInv2.size(), c.inventory2.getSize());
                for (int i = 0; i < max; i++) {
                    Object slot = rawInv2.get(i);
                    if (slot instanceof ItemStack it) c.inventory2.setItem(i, it);
                }
            }
        } catch (Throwable t) {
            getLogger().warning("(✧) failed to load memory for " + p.getName() + ": " + t.getMessage());
        }
        return c;
    }

    /**
     * Schedule a memory snapshot to disk. Snapshots state on the calling
     * (main) thread under {@code c.history}'s lock, then writes YAML on an
     * async worker — keeps the main thread out of the I/O path even for
     * chatty players with long histories. Use {@link #saveMemoryNow}
     * instead during shutdown, when the async scheduler is gone.
     */
    private void saveMemory(Companion c) {
        if (c == null) return;
        MemorySnapshot snap = snapshotMemory(c);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeMemory(snap));
    }

    /** Synchronous variant for {@link #onDisable} (scheduler is shutting down). */
    private void saveMemoryNow(Companion c) {
        if (c == null) return;
        writeMemory(snapshotMemory(c));
    }

    private MemorySnapshot snapshotMemory(Companion c) {
        List<Map<String, String>> hist;
        synchronized (c.history) {
            hist = c.history.stream()
                    .map(m -> {
                        Map<String, String> entry = new HashMap<>();
                        entry.put("role", m.role);
                        entry.put("content", m.content);
                        return entry;
                    })
                    .collect(Collectors.toList());
        }
        // Inventory snapshot. We clone each non-null stack so async write
        // doesn't race with main-thread mutation.
        List<ItemStack> bag = null;
        if (c.inventory != null) {
            ItemStack[] contents = c.inventory.getContents();
            bag = new ArrayList<>(contents.length);
            for (ItemStack it : contents) {
                bag.add(it == null ? null : it.clone());
            }
        }
        List<ItemStack> bag2 = null;
        if (c.inventory2 != null) {
            ItemStack[] contents = c.inventory2.getContents();
            bag2 = new ArrayList<>(contents.length);
            for (ItemStack it : contents) {
                bag2.add(it == null ? null : it.clone());
            }
        }
        return new MemorySnapshot(c.owner, c.name, c.skin, hist, bag,
                c.level, c.xp, c.bedrockType, c.mobForm, bag2);
    }

    private void writeMemory(MemorySnapshot snap) {
        File f = memoryFile(snap.owner);
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("name", snap.name);
            cfg.set("skin", snap.skin);
            cfg.set("level", snap.level);
            cfg.set("xp", snap.xp);
            if (snap.bedrockType != null) cfg.set("bedrock-type", snap.bedrockType);
            cfg.set("mob-form", snap.mobForm);
            cfg.set("history", snap.history);
            if (snap.bagContents != null) {
                cfg.set("inventory", snap.bagContents);
            }
            if (snap.bag2Contents != null) {
                cfg.set("inventory2", snap.bag2Contents);
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            cfg.save(f);
        } catch (Throwable t) {
            getLogger().warning("(✧) failed to save memory for " + snap.owner + ": " + t.getMessage());
        }
    }

    /** Immutable snapshot of a companion's persistable state. */
    private static final class MemorySnapshot {
        final UUID owner;
        final String name;
        final String skin;
        final List<Map<String, String>> history;
        final List<ItemStack> bagContents;
        final int level;
        final int xp;
        final String bedrockType;
        final boolean mobForm;
        final List<ItemStack> bag2Contents;
        MemorySnapshot(UUID owner, String name, String skin,
                       List<Map<String, String>> history,
                       List<ItemStack> bagContents,
                       int level, int xp, String bedrockType, boolean mobForm,
                       List<ItemStack> bag2Contents) {
            this.owner = owner; this.name = name; this.skin = skin;
            this.history = history; this.bagContents = bagContents;
            this.level = level; this.xp = xp; this.bedrockType = bedrockType;
            this.mobForm = mobForm; this.bag2Contents = bag2Contents;
        }
    }

    // ============================================================
    // ============== BEHAVIOR MODES + IDLE LIFE ==================
    // ============================================================

    /**
     * STAY mode — anchored, doesn't move, but still glances around so
     * she doesn't look frozen. Just delegates to {@link #idleLook} and
     * keeps her at her anchor point.
     */
    private void idleAnimate(Companion c, long tick) {
        anchorIfMoved(c);
        idleLook(c, tick);
    }

    /**
     * GUARD mode — walks alongside the owner. If a hostile mob comes
     * within {@link #guardRadius} blocks, threat handling takes over:
     * she chases the mob, faces it, and only swings while she's
     * actually in melee range. The owner is set as the damage source
     * so XP / aggro attribute correctly to the player.
     */
    private void guardTick(Companion c, long tick) {
        Player owner = Bukkit.getPlayer(c.owner);
        if (owner == null) {
            anchorIfMoved(c);
            idleLook(c, tick);
            return;
        }

        // Threat priority — engage handles its own movement + facing.
        // Skipping stepToward(owner) when a threat is active is the fix
        // for "she body-yaws toward the owner instead of the mob".
        if (engageThreatsNear(c, c.entity.getLocation(), tick, owner)) {
            return;
        }

        // No threat — fall back to FOLLOW behavior.
        stepToward(c, owner);
    }

    /**
     * Detect and fight a live <b>Herobrine</b> (via the optional Herobrine
     * plugin's reflection API). Herobrine is a packet NPC with no real hitbox,
     * so the normal hostile scan can't see him — this special-cases him: if
     * he's active within {@link #guardRadius} of the companion (or of the
     * owner, so she rushes to defend), she walks up and swings on the usual
     * {@link #attackCooldownTicks} cadence, routing her weapon damage into his
     * logical HP until he's dead. Returns true if she engaged this tick (in
     * which case the caller skips her normal mode behaviour).
     */
    private boolean engageHerobrine(Companion c, long tick, Player owner) {
        if (!fightHerobrine) return false;
        if (c.entity == null || c.entity.isDead()) return false;
        if (!HerobrineBridge.isActive()) return false;
        Location hb = HerobrineBridge.location();
        if (hb == null || hb.getWorld() == null) return false;
        Location me = c.entity.getLocation();
        if (me.getWorld() != hb.getWorld()) return false;

        double dist = me.distance(hb);
        boolean ownerThreatened = owner != null && owner.getWorld() == hb.getWorld()
                && owner.getLocation().distance(hb) <= guardRadius;
        if (dist > guardRadius && !ownerThreatened) return false;

        // Spotted — announce on (re)engagement, throttled to ~5s.
        if (tick - c.herobrineSpotTick > 100) {
            showChatBubble(c, HerobrineBridge.isBoss() ? "Herobrine has awakened! Stay back!" : "Herobrine! I'll protect you!");
        }
        c.herobrineSpotTick = tick;
        c.lastActivityTick = tick;
        c.ambientCrouchUntil = 0;

        Location aim = hb.clone().add(0, 1.6, 0); // his head-ish
        double reach = 3.2;
        if (dist > reach) {
            // Close the gap (walk-only; never teleport mid-fight).
            c.approachingEntity = true;
            pathStepToward(c, hb, hb.getY(), reach - 0.5);
            c.approachingEntity = false;
            setEyePitch(c, aim);
            return true; // engaged but out of range — no air-swing
        }

        faceSnap(c, aim);
        if (tick % attackCooldownTicks == 0) {
            c.entity.swingMainHand();
            double dmg = computeWeaponDamage(c);
            boolean killed = HerobrineBridge.damage(dmg, owner != null ? owner.getName() : "companion");
            try {
                hb.getWorld().spawnParticle(org.bukkit.Particle.SMOKE,
                        hb.clone().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.01);
            } catch (Throwable ignored) {}
            if (killed) showChatBubble(c, "Got him! You're safe now.");
        }
        return true;
    }

    /**
     * STANDBY: hold position at the anchor, but fight anything she — or the
     * owner — can see near her. Like GUARD without the follow-the-owner
     * fallback; after chasing a threat off she drifts back to the anchor.
     */
    private void standbyTick(Companion c, long tick) {
        Player owner = Bukkit.getPlayer(c.owner);
        if (engageThreatsNear(c, c.entity.getLocation(), tick, owner)) return;
        Location here = c.entity.getLocation();
        Location anchor = c.anchor;
        if (anchor != null && anchor.getWorld() == here.getWorld()
                && here.distanceSquared(anchor) > 4.0) {
            pathStepToward(c, anchor, anchor.getY(), 1.0);
            return;
        }
        idleAnimate(c, tick);
    }

    /**
     * Hunt down entities on her explicit kill list (set by {@code /kc kill}).
     * Targets may be hostile mobs, passive mobs, or players — the owner
     * ordered it, so the normal hostile / line-of-sight filters don't apply.
     * Dead / despawned targets are pruned. Returns true if she engaged one.
     */
    private boolean engageKillTargets(Companion c, long tick, Player owner) {
        if (c.entity == null || c.entity.isDead()) return false;
        // Stand down briefly after giving up a target she was stuck in water
        // trying to reach, so she gets back to dry land before re-engaging.
        if (tick < c.combatGiveUpUntil) return false;
        // Persistent hunt: keep topping up the list with new matching mobs.
        if (!c.huntFilter.isEmpty()) refillHuntTargets(c, owner);
        if (c.killTargets.isEmpty()) return false;
        Location me = c.entity.getLocation();
        LivingEntity target = null;
        double bestSq = Double.MAX_VALUE;
        java.util.Iterator<UUID> it = c.killTargets.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity ent = Bukkit.getEntity(id);
            if (!(ent instanceof LivingEntity le) || le.isDead()) { it.remove(); continue; }
            if (le.getWorld() != me.getWorld()) continue;
            // Only chase ones she can actually SEE — never sense / path toward
            // targets through walls. Out-of-sight ones stay on the list and get
            // engaged if they come into view.
            if (!companionCanSee(c, le)) continue;
            double d = le.getLocation().distanceSquared(me);
            if (d < bestSq) { bestSq = d; target = le; }
        }
        if (target == null) {
            // Only "all done" for a one-shot order — a persistent type-hunt keeps waiting.
            if (c.killTargets.isEmpty() && c.killAnnounced && c.huntFilter.isEmpty()) {
                showChatBubble(c, "All done ~ ♥");
                c.killAnnounced = false;
            }
            return false;
        }
        c.lastActivityTick = tick;
        c.ambientCrouchUntil = 0;
        Location tl = target.getLocation();
        double reach = 3.0;

        // Track how long she's been chasing THIS target; give up on one she
        // can't reach (stuck escaping, or chased >8s) so she stops fixating /
        // staring at a sheep behind a wall and moves on to the next.
        UUID tid = target.getUniqueId();
        if (!tid.equals(c.killPursuingId)) { c.killPursuingId = tid; c.killPursueStartTick = tick; }
        if (me.distance(tl) > reach
                && (c.escapeStartTick > 0 || tick - c.killPursueStartTick > 80 /* ~8s @ 10Hz */)) {
            c.killTargets.remove(tid);
            c.escapeStartTick = 0;
            c.stuckTicks = 0;
            c.killPursuingId = null;
            return !c.killTargets.isEmpty(); // try the next one, or fall through to normal behaviour
        }

        if (me.distance(tl) > reach) {
            c.approachingEntity = true;
            pathStepToward(c, tl, tl.getY(), reach - 0.5);
            c.approachingEntity = false;
            setEyePitch(c, target.getEyeLocation());
            return true;
        }
        faceSnap(c, target.getEyeLocation());
        if (tick % attackCooldownTicks == 0) {
            c.entity.swingMainHand();
            applyAttackDamage(c, target, owner);
        }
        return true;
    }

    /**
     * Open any wooden door / fence gate within a block of her, so she stops
     * getting trapped against doors that sit just off her exact path cell.
     * Cheap (a small scan) and only runs while she's actively moving.
     */
    private void openNearbyDoors(Companion c) {
        if (!pathOpenDoors || c.entity == null || c.entity.isDead()) return;
        Location loc = c.entity.getLocation();
        if (loc == null || loc.getWorld() == null) return;
        World w = loc.getWorld();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (CompanionPathfinder.isHandOpenable(b.getType())) openIfClosed(b);
                }
            }
        }
    }

    /**
     * Reproduce the taught dig pattern, looping it (shifted by the last
     * break's offset) so she keeps mining onward — down a staircase, along a
     * tunnel — until she hits bedrock, lava, a full bag, the world floor, or
     * the owner stops her.
     */
    private void runDigJob(Companion c, Player owner, long tick) {
        if (c.entity == null || c.entity.isDead() || c.digPattern.isEmpty()) {
            stopDig(c, "I lost the pattern ~"); return;
        }
        if (tick < c.nextDigActTick) return;
        if (c.digBase == null) c.digBase = c.entity.getLocation();
        World w = c.digBase.getWorld();
        if (w == null) { stopDig(c, null); return; }

        // Map the facing-local offset to world using her "go" heading.
        int[] off = c.digPattern.get(c.digIndex); // plain world offset
        int tx = c.digBase.getBlockX() + off[0];
        int ty = c.digBase.getBlockY() + off[1];
        int tz = c.digBase.getBlockZ() + off[2];
        if (ty < w.getMinHeight() + 1) { stopDig(c, "reached the bottom of the world ~"); return; }
        Block b = w.getBlockAt(tx, ty, tz);
        Material m = b.getType();

        // Stop / safety conditions.
        if (m == Material.BEDROCK || m == Material.BARRIER) {
            stopDig(c, "I hit bedrock — can't dig further ~"); return;
        }
        if (isDigHazard(b) || isDigHazard(b.getRelative(0, -1, 0))
                || isDigHazard(b.getRelative(0, 1, 0))) {
            stopDig(c, "there's lava ahead — stopping for safety ~"); return;
        }
        if (c.inventory != null && c.inventory.firstEmpty() == -1) {
            stopDig(c, "my bag is full ~"); return;
        }

        Location target = new Location(w, tx + 0.5, ty, tz + 0.5);
        if (c.entity.getLocation().distance(target) > 3.0) {
            // Walk to within reach of the block first (never teleport mid-dig).
            // If she can't get there within ~6s, skip the block so a single
            // unreachable cell never freezes the whole job.
            if (++c.digStallTicks > 120) { advanceDig(c); c.nextDigActTick = tick + 1; return; }
            c.approachingEntity = true;
            pathStepToward(c, target, ty, 2.5);
            c.approachingEntity = false;
            setEyePitch(c, target);
            c.nextDigActTick = tick + 1;
            return;
        }
        faceSnap(c, target);
        breakObstacleBlock(c, b, owner); // no-ops on air
        c.nextDigActTick = tick + 3;     // gentle mining cadence
        advanceDig(c);
    }

    /** Step to the next pattern block, looping (shifted) when a full pass ends. */
    private void advanceDig(Companion c) {
        c.digStallTicks = 0;
        c.digIndex++;
        if (c.digIndex < c.digPattern.size()) return;
        c.digIndex = 0;
        c.digLoop++;
        if (c.digLoop > 256) { stopDig(c, "all done — that's a deep hole ~"); return; }
        int[] shift = c.digWorldShift; // per-loop step in WORLD coords (heading-rotated)
        if (shift == null || (shift[0] == 0 && shift[1] == 0 && shift[2] == 0)) {
            stopDig(c, "that pattern doesn't go anywhere — stopping ~"); return;
        }
        c.digBase = c.digBase.clone().add(shift[0], shift[1], shift[2]);
    }

    private void stopDig(Companion c, String bubble) {
        c.digging = false;
        c.digIndex = 0;
        c.digLoop = 0;
        c.digBase = null;
        if (bubble != null) showChatBubble(c, bubble);
    }

    /** Liquids / fire she must never dig into or next to. */
    private static boolean isDigHazard(Block b) {
        Material m = b.getType();
        return m == Material.LAVA || m == Material.FIRE
                || m == Material.SOUL_FIRE || m == Material.MAGMA_BLOCK;
    }

    /**
     * Find the nearest hostile within {@link #guardRadius} of {@code origin}.
     * If found:
     *   • out of melee range → walk toward it (this is the fix for the
     *     "scout sees mobs but doesn't approach" bug);
     *   • in melee range → snap-face it and swing on the
     *     {@link #attackCooldownTicks} cadence, applying damage from
     *     the configured weapon. Air-swinging while still walking is
     *     suppressed (fixes "attacks continuously while approaching").
     *
     * Returns true if any threat handling happened this tick.
     */
    /**
     * Find the nearest hostile within {@link #guardRadius} of {@code origin}
     * (or {@link #bowDetectionRange} if she's already in bow mode and
     * tracking a flying target). Three branches:
     *
     *   • flying threat → equip bow + shoot at the configured cadence
     *     ({@link #bowShootCooldownTicks}). Arrows are auto-resupplied
     *     so she has effectively infinite ammo (the bow is also given
     *     Infinity, so vanilla ammo cost is 0 anyway).
     *   • grounded threat in melee range → snap-face it and swing the
     *     sword on {@link #attackCooldownTicks}.
     *   • grounded threat out of melee range → walk toward it; only
     *     swing once we've closed the gap. Air-swinging while walking
     *     is suppressed.
     *
     * Returns true if any threat handling happened this tick.
     */
    /**
     * Find the highest-priority threat near {@code origin} and engage
     * it. Priority order:
     *
     *   1. **Assist target** — whatever just hit the owner is the
     *      priority for {@link #assistMemoryTicks} ticks (still has to
     *      be alive + nearby).
     *   2. **Focus-fire target** — any monster currently targeting the
     *      owner. Closer = higher tier-break.
     *   3. **Boss** — if a Wither / Ender Dragon is in range, that
     *      always wins over normal mobs.
     *   4. **Nearest hostile**.
     *
     * Three engagement branches:
     *
     *   • flying threat → equip bow + shoot at the configured cadence
     *     ({@link #bowShootCooldownTicks}, or {@link #bossBowCooldownTicks}
     *     for bosses). Ammo is virtual — bag stays empty.
     *   • grounded threat in melee range → snap-face it and swing the
     *     sword on {@link #attackCooldownTicks}.
     *   • grounded threat out of melee range → walk toward it; only
     *     swing once we've closed the gap.
     *
     * Returns true if any threat handling happened this tick.
     */
    private boolean engageThreatsNear(Companion c, Location origin, long tick, Player owner) {
        // Recently gave up a target she was drowning trying to reach — stand
        // down from combat briefly so she returns to the owner / dry land.
        if (tick < c.combatGiveUpUntil) return false;
        // Use the wider bow range if she's currently in bow mode so she
        // doesn't disengage the second the phantom drifts past 16 blocks.
        double scanRange = (autoEquipBowVsFlying && c.bowEquipped) ? bowDetectionRange : guardRadius;

        // Spotter pass — independent of combat decisions, runs every tick.
        if (spotterEnabled) runSpotterScan(c, origin, tick);

        LivingEntity threat = pickPriorityTarget(c, origin, scanRange, owner, tick);
        if (threat == null) {
            // No threat — if she's still holding the auto-bow, swap back
            // to sword for next time.
            if (c.bowEquipped) {
                c.bowEquipped = false;
                equipBestWeapon(c);
            }
            return false;
        }

        // Threat detected → she's actively combatting, not idling. Keeps
        // her from dozing off mid-fight (which would be both unsafe and
        // very silly looking).
        c.lastActivityTick = tick;
        c.ambientCrouchUntil = 0;

        boolean isBoss = bossModeEnabled && isBoss(threat);
        // Bosses use bow ONLY if they're actually flying (high above
        // ground). A wither hovering 1 block off the ground is
        // meleeable, so we don't force-bow here — isFlyingThreat()
        // alone decides.
        boolean flying = autoEquipBowVsFlying && isFlyingThreat(threat);

        // Underwater bow is useless — vanilla arrows have brutal water
        // drag and barely travel. Force melee when she's fully
        // submerged regardless of threat type. If the threat's flying
        // overhead and unreachable from underwater, the bow path
        // wouldn't have helped anyway; the melee branch will close
        // distance (or stall) and she'll path back to surface.
        if (flying && isCompanionSubmerged(c)) {
            flying = false;
        }

        // Wither in its low-HP charging state lands on the ground and
        // becomes shielded against arrows. We force melee-mode for a
        // 5-second window once charging is detected. The window
        // prevents bow↔sword flip-flop when the wither's isOnGround
        // bobs as it walks, AND prevents announce-spam.
        if (flying && threat.getType() == EntityType.WITHER) {
            if (isWitherCharging(threat)) {
                // Extend the melee window. Announce only on the leading edge.
                if (tick >= c.witherMeleeUntil && !c.witherSwitchAnnounced) {
                    showChatBubble(c, "switching to sword!");
                    c.witherSwitchAnnounced = true;
                }
                c.witherMeleeUntil = tick + 100; // 5 seconds
            }
            if (tick < c.witherMeleeUntil) {
                flying = false;
            } else {
                // Window expired — reset announce flag so we'd say it
                // again next time she lands.
                c.witherSwitchAnnounced = false;
            }
        }

        // ---- Bow path (flying threats + bosses always go ranged) ----
        if (flying) {
            if (!c.bowEquipped) {
                equipAutoBow(c);
                c.bowEquipped = true;
                c.nextBowShotTick = tick + 10; // brief draw delay
            }
            faceSnap(c, threat.getEyeLocation());
            // Boss = faster shots.
            long cooldown = isBoss ? bossBowCooldownTicks : bowShootCooldownTicks;

            // Line-of-sight gate — without this she'll cheerfully fire
            // arrows into the wall of a dip while a phantom hovers above
            // mocking her. We ray-trace from her eye to the target's eye;
            // if a block's in the way, refuse the shot AND step toward
            // the target so the path/stuck-escape logic can lift her
            // out of the obstruction.
            Location shooterEye = c.entity.getLocation().clone().add(0, 1.5, 0);
            boolean clearShot = hasClearShot(shooterEye, threat.getEyeLocation());

            if (clearShot) {
                if (tick >= c.nextBowShotTick) {
                    shootArrow(c, threat, owner);
                    c.entity.swingMainHand();
                    c.nextBowShotTick = tick + cooldown;
                }
            } else {
                // No line of sight. Don't freeze, and don't just walk straight
                // at the threat (that often walks her INTO the cover blocking
                // the shot). Never teleport — approachingEntity keeps the
                // stepper in walk-only mode. First try to find a nearby spot
                // that actually HAS a clear shot and move there; if none, close
                // in toward the threat so the (now silent) stuck-escape can
                // lift her out of any dip/hole she's in.
                Location threatLoc = threat.getLocation();
                c.approachingEntity = true;
                Location firingSpot = findFiringPosition(c, threat);
                if (firingSpot != null) {
                    pathStepToward(c, firingSpot, firingSpot.getY(), 0.6);
                } else {
                    pathStepToward(c, threatLoc, threatLoc.getY(), 4.0);
                }
                c.approachingEntity = false;
                if (tick >= c.nextBowShotTick) {
                    c.nextBowShotTick = tick + 4; // recheck in ~0.2 s
                }
            }
            return true;
        }

        // ---- Sword path ----
        // If she was just in bow mode, swap back to sword.
        if (c.bowEquipped) {
            c.bowEquipped = false;
            equipBestWeapon(c);
        }

        double reach = 3.0;
        Location threatLoc = threat.getLocation();
        double dist = c.entity.getLocation().distance(threatLoc);

        if (dist > reach) {
            // Walk toward it (never teleport — approachingEntity forces
            // walk-only). The silent stuck-escape will climb/dig her out if a
            // hole or wall blocks the approach, so she doesn't just stand in a
            // pit while a zombie waits at the rim.
            c.approachingEntity = true;
            pathStepToward(c, threatLoc, threatLoc.getY(), reach - 0.5);
            c.approachingEntity = false;
            setEyePitch(c, threat.getEyeLocation());
            return true; // engaged but not in range — don't air-swing
        }

        faceSnap(c, threat.getEyeLocation());

        if (tick % attackCooldownTicks == 0) {
            c.entity.swingMainHand();
            applyAttackDamage(c, threat, owner);
        }
        return true;
    }

    /**
     * Pick the highest-priority threat for this tick. Returns null if
     * nothing's nearby. Walks the priority chain: assist → focus →
     * boss → nearest.
     */
    private LivingEntity pickPriorityTarget(Companion c, Location origin, double scanRange,
                                             Player owner, long tick) {
        // 1. Assist target — was she told to chase this specific mob?
        if (assistOwnerEnabled
                && c.assistTargetId != null
                && tick < c.assistTargetUntil) {
            Entity ent = Bukkit.getEntity(c.assistTargetId);
            if (ent instanceof LivingEntity le && !le.isDead()
                    && le.getWorld() == origin.getWorld()
                    && le.getLocation().distance(origin) <= scanRange + 8) {
                return le;
            }
            // Expired / out of range / dead — clear it.
            c.assistTargetId = null;
            c.assistTargetUntil = 0;
        }

        // Throttle the expensive getNearbyEntities priority sweep: between
        // scans keep fighting the cached threat as long as it's still alive,
        // hostile, in range, visible and in the same world. Same ~10-tick
        // cadence as the mob-form target cache (pickFormTarget). Only re-scan
        // when the cache is empty/invalid or the throttle window elapsed.
        if (c.priorityTargetId != null && tick < c.nextPriorityScanTick) {
            Entity cached = Bukkit.getEntity(c.priorityTargetId);
            if (cached instanceof LivingEntity le && !le.isDead()
                    && le.getWorld() == origin.getWorld()
                    && le.getLocation().distanceSquared(origin) <= scanRange * scanRange
                    && isHostile(le)
                    && !isCompanionEntity(le.getUniqueId())
                    && (!combatRequireLineOfSight || companionCanSee(c, le))) {
                return le;
            }
            c.priorityTargetId = null;
        }
        c.nextPriorityScanTick = tick + FORM_TARGET_SCAN_INTERVAL;

        // Collect all hostiles in range. Note: EnderDragon does NOT
        // implement Monster (it implements ComplexLivingEntity + Boss),
        // so we use a broader isHostile() check that includes it.
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity e : origin.getWorld().getNearbyEntities(origin, scanRange, scanRange, scanRange)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            if (!isHostile(le)) continue;
            // A hostile-typed mob that's actually someone's mob-form
            // companion (wither form etc) is a friend, not a threat.
            if (isCompanionEntity(le.getUniqueId())) continue;
            // Don't sense / engage mobs through solid walls — without this she
            // turns toward and paths into a wall chasing a mob she can't reach,
            // which then trips the stuck-escape (and the warp-to-owner). The
            // ray-trace skips passable blocks so a mob behind glass/grass still
            // counts as visible.
            //
            // Require HER OWN line of sight — she no longer "borrows the owner's
            // eyes". Targeting mobs she can't actually reach (behind a wall) made
            // her stare at the wall and re-path endlessly, which also flickered
            // her crouch/stand pose. She now only engages what she can see.
            if (combatRequireLineOfSight && !companionCanSee(c, le)) continue;
            candidates.add(le);
        }
        if (candidates.isEmpty()) { c.priorityTargetId = null; return null; }

        LivingEntity chosen = null;

        // 2. Boss — always preferred.
        if (bossModeEnabled) {
            for (LivingEntity e : candidates) {
                if (isBoss(e)) { chosen = e; break; }
            }
        }

        // 3. Focus fire — pick mob currently targeting owner if any.
        if (chosen == null && focusFireEnabled && owner != null) {
            LivingEntity targetingOwner = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity e : candidates) {
                if (e instanceof Monster m && m.getTarget() != null
                        && m.getTarget().getUniqueId().equals(owner.getUniqueId())) {
                    double d = m.getLocation().distanceSquared(origin);
                    if (d < bestDist) { bestDist = d; targetingOwner = m; }
                }
            }
            if (targetingOwner != null) chosen = targetingOwner;
        }

        // 4. Nearest fallback.
        if (chosen == null) {
            double bestSq = Double.MAX_VALUE;
            for (LivingEntity e : candidates) {
                double d = e.getLocation().distanceSquared(origin);
                if (d < bestSq) { bestSq = d; chosen = e; }
            }
        }
        c.priorityTargetId = (chosen == null) ? null : chosen.getUniqueId();
        return chosen;
    }

    /** True for the two endgame bosses. */
    private static boolean isBoss(LivingEntity e) {
        EntityType t = e.getType();
        return t == EntityType.WITHER || t == EntityType.ENDER_DRAGON;
    }

    /**
     * True when a Wither is in its grounded "charging" state — low HP,
     * standing on the ground, and shielded against arrows. In this
     * state ranged attacks deal 0 damage so we want her to switch to
     * melee. Detected by:
     *   • HP below half (vanilla shield activates at half HP)
     *   • on ground (Bukkit's isOnGround is reliable here)
     *
     * Both conditions together give us a tight window — full-HP
     * wither flies and we keep using bow; phase-2 wither lands and we
     * switch to sword.
     */
    private static boolean isWitherCharging(LivingEntity e) {
        if (e.getType() != EntityType.WITHER) return false;
        try {
            double hp = e.getHealth();
            double max = e.getMaxHealth();
            if (max <= 0) return false;
            boolean lowHp = hp < (max * 0.5);
            boolean grounded = e.isOnGround();
            return lowHp && grounded;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Always-true placeholder — use real config flag if you have one. */
    private boolean smartAnnounceFinds() {
        return spotterEnabled; // reuse the spotter toggle for chat-bubble announcements
    }

    /**
     * True if {@code le} should be treated as a hostile threat. We
     * can't just test {@code instanceof Monster} because Bukkit's
     * class hierarchy quirks: Ghast extends Flying (not Monster),
     * EnderDragon extends ComplexLivingEntity, Slime/MagmaCube
     * extend Mob. So we union the Monster interface with explicit
     * always-hostile types.
     */
    private static boolean isHostile(LivingEntity le) {
        if (le instanceof Monster) return true;
        EntityType t = le.getType();
        return t == EntityType.GHAST
                || t == EntityType.ENDER_DRAGON
                || t == EntityType.WITHER
                || t == EntityType.SLIME
                || t == EntityType.MAGMA_CUBE
                || t == EntityType.PHANTOM
                || t == EntityType.SHULKER
                || t == EntityType.HOGLIN;
    }

    /**
     * Spotter scan — chat-bubble warnings about nearby dangerous mobs.
     * Per-EntityType cooldown so she doesn't spam the same warning.
     * Only flags the high-stress entries: phantom/ghast/creeper/
     * wither/dragon/blaze.
     */
    private void runSpotterScan(Companion c, Location origin, long tick) {
        // Throttle the per-tick getNearbyEntities sweep: spotter warnings are
        // purely cosmetic chat bubbles gated by a much longer per-type cooldown,
        // so scanning every behaviour tick is wasted work. Re-scan on the same
        // ~10-tick cadence used by the form-target cache.
        if (tick < c.nextSpotterScanTick) return;
        c.nextSpotterScanTick = tick + FORM_TARGET_SCAN_INTERVAL;
        EntityType found = null;
        for (Entity e : origin.getWorld().getNearbyEntities(
                origin, spotterRange, spotterRange, spotterRange)) {
            EntityType t = e.getType();
            if (e.isDead()) continue;
            // Only flag dangerous entity types directly — no Monster
            // check, since EnderDragon doesn't implement that interface.
            if (t != EntityType.PHANTOM
                    && t != EntityType.GHAST
                    && t != EntityType.CREEPER
                    && t != EntityType.WITHER
                    && t != EntityType.ENDER_DRAGON
                    && t != EntityType.BLAZE
                    && t != EntityType.WITHER_SKELETON
                    && t != EntityType.WARDEN) continue;
            // Require line-of-sight — don't warn about mobs through walls.
            if (!(e instanceof LivingEntity le)) continue;
            // Don't shout "WITHER!!!" about a friend's wither-form companion.
            if (isCompanionEntity(le.getUniqueId())) continue;
            if (!companionCanSee(c, le)) continue;
            found = t;
            break;
        }
        if (found == null) return;
        Long last = c.lastSpotterTick.get(found);
        if (last != null && tick - last < spotterCooldownTicks) return;
        c.lastSpotterTick.put(found, tick);
        showChatBubble(c, spotterMessage(found));
    }

    /** Cute warning string per dangerous mob. */
    private static String spotterMessage(EntityType t) {
        return switch (t) {
            case PHANTOM         -> "phantom!! look up! \u2728";
            case GHAST           -> "ghast incoming!";
            case CREEPER         -> "creeper nearby! ⚠";
            case WITHER          -> "WITHER!!!";
            case ENDER_DRAGON    -> "the dragon!!";
            case BLAZE           -> "blaze ahead!";
            case WITHER_SKELETON -> "wither skelly~";
            case WARDEN          -> "shhh... warden \u2026";
            default              -> "danger nearby!";
        };
    }

    /**
     * True if {@code threat} should be engaged with a bow. Covers the
     * obvious always-flying mobs (Phantom/Ghast/Vex/Blaze/Ender Dragon)
     * plus grounded mobs that are currently airborne and out of melee
     * reach. WITHER is intentionally NOT in the always-flying list —
     * it can hover only ~1 block off the ground and be perfectly
     * meleeable, especially in phase-2. Falls through to the height
     * check so a low-hovering wither = sword, high wither = bow.
     */
    private boolean isFlyingThreat(LivingEntity threat) {
        EntityType t = threat.getType();
        // Underwater mobs (drowned, guardians, anything swimming) are
        // NOT flying. The height-based check below would otherwise
        // misclassify them — water isn't a solid floor, so a guardian
        // hovering 12 blocks above the seafloor reads as "way up in
        // the air" to the ground-distance scan. Force melee path
        // instead; vanilla arrows have brutal water drag and would
        // plink uselessly anyway. She'll swim down + slash.
        Location threatLoc = threat.getLocation();
        org.bukkit.block.Block footBlock = threatLoc.getWorld().getBlockAt(
                threatLoc.getBlockX(), threatLoc.getBlockY(), threatLoc.getBlockZ());
        if (footBlock.getType() == Material.WATER) return false;

        double altitude = c_distanceFromGround(threat);

        // Hostile flyers — phantom / ghast / vex / blaze — are usually
        // best handled at range, BUT if one is hovering near the floor
        // (a freshly-spawned blaze, a vex coming out of a summon, a
        // ghast in a low cave) she should melee it instead. Bow vs a
        // mob she could literally punch is silly + arrows often miss
        // close targets due to bow draw delay. Threshold is the same
        // 2.5 blocks used for the generic case so the rule's
        // consistent.
        if (t == EntityType.PHANTOM
                || t == EntityType.GHAST
                || t == EntityType.VEX
                || t == EntityType.BLAZE) {
            return altitude > 2.5;
        }

        // Distance-based: anything more than 2.5 blocks above solid
        // ground is "flying" for combat purposes. This is what makes
        // grounded-but-hovering withers + creepers-on-cliffs work.
        if (altitude > 2.5) return true;
        return false;
    }

    /** Approximate height above solid ground for a LivingEntity. */
    private double c_distanceFromGround(LivingEntity e) {
        Location loc = e.getLocation();
        for (int dy = 0; dy < 16; dy++) {
            org.bukkit.Material m = loc.getWorld().getBlockAt(
                    loc.getBlockX(), loc.getBlockY() - 1 - dy, loc.getBlockZ()).getType();
            if (!m.isAir() && m.isSolid()) return dy + 0.5;
        }
        return 16.0; // way up there
    }

    /**
     * Equip her main hand with a freshly-built bow that has Power
     * {@link #bowPowerLevel} + Infinity I baked in. The bow doesn't
     * consume real arrows from her bag because Infinity is set; she
     * still needs *some* arrow item for the vanilla shoot animation,
     * but we render it via a real Arrow projectile so the bag stays
     * empty for the player's stuff.
     */
    private void equipAutoBow(Companion c) {
        if (c.entity == null) return;
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        if (meta != null) {
            try {
                if (bowPowerLevel > 0) {
                    meta.addEnchant(Enchantment.POWER, bowPowerLevel, true);
                }
                meta.addEnchant(Enchantment.INFINITY, 1, true);
            } catch (Throwable t) {
                // Older Bukkit constants — try legacy names.
                try {
                    Enchantment power = Enchantment.getByName("ARROW_DAMAGE");
                    Enchantment infinity = Enchantment.getByName("ARROW_INFINITE");
                    if (power != null && bowPowerLevel > 0) meta.addEnchant(power, bowPowerLevel, true);
                    if (infinity != null) meta.addEnchant(infinity, 1, true);
                } catch (Throwable ignored) {}
            }
            meta.setDisplayName("§d✦ " + c.name + "'s Bow");
            bow.setItemMeta(meta);
        }
        c.entity.setMainHand(bow);
    }

    /**
     * Spawn an Arrow at the companion's eye location heading toward the
     * target. Power-bonus damage is applied via {@link Arrow#setDamage};
     * the source is set to the owner so XP / aggro routes correctly.
     */
    private void shootArrow(Companion c, LivingEntity target, Player owner) {
        Location origin = c.entity.getLocation().clone().add(0, 1.5, 0);
        Vector toTarget = target.getEyeLocation().toVector().subtract(origin.toVector());
        // Lead the target slightly based on its current velocity so
        // moving phantoms/ghasts get hit instead of overshot.
        Vector lead = target.getVelocity().clone().multiply(8.0);
        toTarget.add(lead);
        if (toTarget.lengthSquared() < 1e-6) toTarget = new Vector(0, 0, 1);
        Vector dir = toTarget.normalize();

        try {
            Arrow arrow = origin.getWorld().spawnArrow(origin, dir, (float) bowArrowSpeed, 0f);
            // Vanilla bow base = 2.0; Power N adds 0.25*(N+1) + extra.
            // Approximate: damage = 2.0 + 0.5*level for parity feel.
            double dmg = 2.0 + 0.5 * bowPowerLevel;
            arrow.setDamage(dmg);
            arrow.setShooter(owner);
            arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED); // no looting her arrows
            arrow.setCritical(true); // visual — feels good
        } catch (Throwable t) {
            getLogger().warning("(\u2727) bow shot failed: " + t.getMessage());
        }
    }

    /** findNearestHostile but with caller-supplied range. */
    private LivingEntity findNearestHostileWithin(Location origin, double range) {
        World w = origin.getWorld();
        if (w == null) return null;
        double r2 = range * range;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : w.getNearbyEntities(origin, range, range, range)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            if (!isHostile(le)) continue;
            double d2 = le.getLocation().distanceSquared(origin);
            if (d2 > r2) continue;
            if (d2 < bestSq) { bestSq = d2; best = le; }
        }
        return best;
    }

    /**
     * Snap body yaw + head pitch to face {@code at}, no lerp. Used when
     * we want immediate facing (e.g. engaging a threat) instead of the
     * smooth turn from {@link #faceTarget}.
     */
    private void faceSnap(Companion c, Location at) {
        if (c.entity == null) return;
        Location loc = c.entity.getLocation();
        double dx = at.getX() - loc.getX();
        double dy = at.getY() - (loc.getY() + 1.62);
        double dz = at.getZ() - loc.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-4 && Math.abs(dy) < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        pitch = Math.max(-60f, Math.min(60f, pitch));
        c.entity.setBodyYaw(yaw);
        c.entity.setHeadPitch(pitch);
    }

    /** Set head pitch only — yaw left alone (movement controls it). */
    private void setEyePitch(Companion c, Location at) {
        if (c.entity == null) return;
        Location loc = c.entity.getLocation();
        double dx = at.getX() - loc.getX();
        double dy = at.getY() - (loc.getY() + 1.62);
        double dz = at.getZ() - loc.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-4 && Math.abs(dy) < 1e-4) return;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        pitch = Math.max(-60f, Math.min(60f, pitch));
        c.entity.setHeadPitch(pitch);
    }

    /**
     * SCOUT mode — wanders to random points around the anchor while
     * watching for hostiles (engages just like GUARD). Occasionally
     * pauses in place between scout targets for an immersive "she
     * stopped to look at something" beat.
     */
    private void scoutTick(Companion c, long tick) {
        anchorIfMoved(c);

        // Threats override wandering completely.
        Player owner = Bukkit.getPlayer(c.owner);
        if (owner != null && engageThreatsNear(c, c.entity.getLocation(), tick, owner)) {
            return;
        }

        // Honoring an active idle pause? Just look around.
        if (tick < c.scoutPauseUntil) {
            idleLook(c, tick);
            return;
        }

        boolean atTarget = c.scoutTarget != null
                && c.scoutTarget.getWorld() == c.entity.getWorld()
                && c.entity.getLocation().distance(c.scoutTarget) < 1.5;
        boolean noTarget = c.scoutTarget == null
                || c.scoutTarget.getWorld() != c.entity.getWorld();
        boolean timeout = tick >= c.nextScoutPickTick;

        if (atTarget || noTarget || timeout) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            // Roll for an immersive idle pause instead of immediately
            // picking the next scout target.
            if (rng.nextDouble() < scoutPauseChance) {
                long span = Math.max(1, scoutPauseMaxTicks - scoutPauseMinTicks);
                c.scoutPauseUntil = tick + scoutPauseMinTicks + rng.nextLong(span);
                c.scoutTarget = null;
                idleLook(c, tick);
                return;
            }
            pickScoutTarget(c);
        }

        if (c.scoutTarget != null) {
            pathStepToward(c, c.scoutTarget, c.anchor.getY(), 1.0);
        }
        idleLook(c, tick);
    }

    /** Lock in the current location as her anchor if it's not yet set. */
    private void anchorIfMoved(Companion c) {
        if (c.anchor == null || c.anchor.getWorld() != c.entity.getWorld()) {
            c.anchor = c.entity.getLocation();
        }
    }

    /**
     * Pick a fresh scout destination: 4-{@link #scoutRadius} blocks from
     * the anchor in a random direction. The 4-block minimum is important
     * — if we picked a target inside the {@code stopDist} radius she'd
     * never visibly walk anywhere.
     */
    private void pickScoutTarget(Companion c) {
        if (c.anchor == null) c.anchor = c.entity.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Try a few random directions before falling back to the last
        // candidate — this is the edge-guard. We sample the proposed
        // target's ground Y and reject it if it's a >edgeGuardMaxDrop
        // drop from the anchor, which keeps her from happily walking
        // off cliffs while wandering. Bounded retries (5) so we never
        // loop forever on a peninsula surrounded by ocean.
        Location anchor = c.anchor;
        World w = anchor.getWorld();
        Location best = null;
        int attempts = (edgeGuardScout && w != null) ? 5 : 1;
        for (int i = 0; i < attempts; i++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dist = 4.0 + rng.nextDouble() * Math.max(scoutRadius - 4.0, 1.0);
            Location candidate = anchor.clone()
                    .add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            best = candidate; // remember the last one as a fallback
            if (!edgeGuardScout || w == null) break;
            // Sample the ground Y under the candidate. If we can't find
            // ground at all in a reasonable window, treat that as a
            // cliff (probably an ocean edge) and reject.
            double scanFromY = anchor.getY() + 2.0;
            double minAcceptY = anchor.getY() - edgeGuardMaxDrop;
            double groundY = findGroundY(w, candidate.getBlockX(), candidate.getBlockZ(),
                    scanFromY, anchor.getY(), anchor.getY());
            if (groundY >= minAcceptY) {
                candidate.setY(groundY);
                best = candidate;
                break;
            }
            // else loop and try a different angle.
        }
        c.scoutTarget = best;
        // 3-6 seconds before we'll yank her toward a fresh target even
        // if she's still walking — keeps her unstuck against walls.
        c.nextScoutPickTick = behaviorTickCount + 60 + rng.nextInt(60);
    }

    /**
     * Periodic head turn so she looks alive instead of frozen. Picks a
     * random head yaw within ±70° of the body and a head pitch in
     * ±20°, refreshed every 2–6 seconds. Runs in STAY/GUARD/SCOUT,
     * not FOLLOW (FOLLOW already drives head/body via faceToward).
     */
    private void idleLook(Companion c, long tick) {
        if (tick < c.nextIdleTick) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        c.nextIdleTick = tick + 40 + rng.nextInt(80); // 2–6 s

        // Priority 1 — head-track the owner if she's in STAY and the
        // owner is hanging out nearby. Reads as "watching you cook" /
        // "making sure she keeps an eye on you" rather than the
        // default vacant random head wobble.
        if (headTrackOwnerInStay && c.mode == BehaviorMode.STAY) {
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null && owner.isOnline()
                    && owner.getWorld() == c.entity.getWorld()
                    && owner.getLocation().distance(c.entity.getLocation()) < 12.0
                    && rng.nextFloat() < 0.6f /* leave room for random looks */) {
                faceTarget(c, owner.getEyeLocation());
                return;
            }
        }

        // Priority 2 — look at a nearby cute mob/animal. Throttled by
        // its own cooldown so she doesn't lock onto one cow forever.
        if (lookAtNearbyEntitiesEnabled
                && (c.mode == BehaviorMode.STAY || c.mode == BehaviorMode.SCOUT)
                && tick - c.lastEntityLookTick > 100 /* ≥5 s between entity-locks */
                && rng.nextFloat() < 0.4f) {
            LivingEntity nearby = findNearestInteresting(c);
            if (nearby != null) {
                faceTarget(c, nearby.getEyeLocation());
                c.lastEntityLookTick = tick;
                return;
            }
        }

        // Priority 3 — random head turn (the original behavior).
        Location loc = c.entity.getLocation();
        float bodyYaw = loc.getYaw();
        float headYaw = bodyYaw + (rng.nextFloat() - 0.5f) * 140f; // ±70°
        float headPitch = (rng.nextFloat() - 0.5f) * 40f;          // ±20°
        c.entity.setHeadYaw(headYaw);
        c.entity.setHeadPitch(headPitch);

        // 15% chance to enter a brief crouch on this idle tick — adds a
        // "ooh, what's that?" beat without overdoing it. updatePoseState
        // will pick CROUCHING up on the next tick. Only triggers in
        // STAY/SCOUT modes (FOLLOW + GUARD have their own things going).
        if ((c.mode == BehaviorMode.STAY || c.mode == BehaviorMode.SCOUT)
                && rng.nextFloat() < 0.15f) {
            c.ambientCrouchUntil = tick + 60 + rng.nextInt(120); // 3–9 s
        }

        // 10% chance to fire a small idle fidget (arm swing). Layered
        // on top so a fidget can co-occur with a random head turn — it
        // really does look like a casual "shrug + glance" combo.
        if (randomFidgetsEnabled
                && (c.mode == BehaviorMode.STAY || c.mode == BehaviorMode.SCOUT)
                && tick - c.lastFidgetTick > 200 /* ≥10 s between fidgets */
                && rng.nextFloat() < 0.10f) {
            c.lastFidgetTick = tick;
            c.entity.swingMainHand();
        }
    }

    /**
     * Find the nearest "cute" non-hostile, non-owner living entity
     * within {@link #lookAtEntityRadius} of the companion. Used by
     * {@link #idleLook} for the "she's watching the chickens" beat.
     *
     * <p>We avoid hostile mobs (those have their own combat logic) and
     * skip the companion herself (she's also a "Player" entity to the
     * world). Returns {@code null} when nothing in range qualifies —
     * the caller falls back to random head movement.
     */
    private LivingEntity findNearestInteresting(Companion c) {
        if (c.entity == null) return null;
        Location origin = c.entity.getLocation();
        World w = origin.getWorld();
        if (w == null) return null;
        double r = lookAtEntityRadius;
        UUID ownerId = c.owner;
        LivingEntity best = null;
        double bestSq = r * r;
        for (Entity e : origin.getWorld().getNearbyEntities(origin, r, r, r)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            if (isHostile(le)) continue; // hostiles handled by combat
            if (le instanceof Player p && p.getUniqueId().equals(ownerId)) continue;
            // Skip the companion herself — she's a Player entity so the
            // hitbox + her own NMS handle would otherwise show up here.
            // We don't have a direct ref on the LivingEntity side, so
            // use the hitbox map's contents: any entity whose UUID is
            // known as a companion hitbox is also skipped.
            if (hitboxToOwner.containsKey(le.getUniqueId())) continue;
            double d = le.getLocation().distanceSquared(origin);
            if (d < bestSq) { bestSq = d; best = le; }
        }
        return best;
    }

    /**
     * Pivot body + head toward {@code at} via a yaw lerp, then tilt the
     * head to track its Y. Body rotation is the fix for the previous
     * owl-twist behavior — head used to spin past natural neck range
     * because the body never turned. Lerp at 0.3 so a 180° pivot
     * completes in ~5 ticks (~250ms).
     */
    private void faceTarget(Companion c, Location at) {
        Location loc = c.entity.getLocation();
        double dx = at.getX() - loc.getX();
        double dy = at.getY() - (loc.getY() + 1.62);
        double dz = at.getZ() - loc.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-4 && Math.abs(dy) < 1e-4) return;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        // Clamp pitch to a reasonable human neck range
        pitch = Math.max(-60f, Math.min(60f, pitch));
        float newBodyYaw = (float) lerpAngleDeg(loc.getYaw(), targetYaw, 0.3);
        c.entity.setBodyYaw(newBodyYaw);
        c.entity.setHeadPitch(pitch);
    }

    /**
     * Find the nearest hostile mob within {@link #guardRadius} of the
     * given origin. Skips friendly mobs (cows, villagers, etc.) using
     * {@link #isHostile(LivingEntity)} which handles the Bukkit class
     * hierarchy quirks (Ghast / EnderDragon don't implement Monster).
     */
    private LivingEntity findNearestHostile(Location origin) {
        LivingEntity best = null;
        double bestSq = guardRadius * guardRadius;
        for (Entity e : origin.getWorld().getNearbyEntities(
                origin, guardRadius, guardRadius, guardRadius)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead()) continue;
            if (!isHostile(le)) continue;
            double d = le.getLocation().distanceSquared(origin);
            if (d < bestSq) { bestSq = d; best = le; }
        }
        return best;
    }

    /**
     * Apply weapon damage to the target. Uses the owner as the damage
     * source so XP, aggro, and statistics attribute to them — the
     * companion is acting as the player's proxy. Adds a small upward
     * + outward knockback so the impact feels like a real hit.
     */
    private void applyAttackDamage(Companion c, LivingEntity target, Player owner) {
        double damage = computeWeaponDamage(c);
        // Clear the target's hurt-invulnerability so EVERY companion's hit
        // lands — otherwise the ~½s i-frames after the first hit swallow the
        // rest when a pack of helpers swings at the same mob in one window.
        try { target.setNoDamageTicks(0); } catch (Throwable ignored) {}
        try {
            target.damage(damage, owner);
        } catch (Throwable t) {
            // Some Paper builds reject the (double, Entity) overload
            // for fake-player sources; fall back to anonymous damage.
            target.damage(damage);
        }
        Vector kb = target.getLocation().toVector()
                .subtract(c.entity.getLocation().toVector());
        kb.setY(0);
        if (kb.lengthSquared() > 1e-6) {
            kb.normalize().multiply(0.4);
            kb.setY(0.4);
            target.setVelocity(target.getVelocity().add(kb));
        }
    }

    /** Compute melee damage from her best weapon (or 1.0 bare-handed). */
    /**
     * Pick a damage value for the strongest weapon currently in her bag.
     * Routes through {@link ItemKnowledge} so the same priorities apply
     * here as in {@link #equipBestWeapon} — keeping "what she swings"
     * and "how hard it hits" aligned. Falls back to 1.0 (bare hands).
     */
    private double computeWeaponDamage(Companion c) {
        double base = 1.0;
        if (c.inventory != null) {
            ItemStack best = ItemKnowledge.bestMeleeWeapon(c.inventory);
            if (best != null) {
                double dmg = ItemKnowledge.of(best.getType()).meleeDamage;
                if (dmg > 0) base = dmg;
            }
        }
        // Attack damage is fixed — it does NOT scale with level, so she never
        // hits harder as she levels up. Damage comes purely from her weapon.
        return base;
    }

    // ============================================================
    // ============== INTERACTION HITBOX ==========================
    // ============================================================

    /**
     * Spawn an {@link Interaction} entity at the companion's location.
     * Interaction is a non-rendering hitbox-only entity (added in 1.19.4)
     * — perfect for "here's where right-clicks should land". We size it
     * to a player and tag it via the {@link #hitboxToOwner} map so the
     * click handler can find the matching companion.
     */
    private void spawnHitbox(Companion c, Location loc) {
        try {
            Entity raw = loc.getWorld().spawnEntity(loc, EntityType.INTERACTION);
            if (!(raw instanceof Interaction hb)) {
                getLogger().warning("(✧) couldn't spawn Interaction entity (got " + raw + ")");
                raw.remove();
                return;
            }
            hb.setInteractionWidth(0.8f);
            hb.setInteractionHeight(1.9f);
            hb.setResponsive(true);
            hb.setPersistent(false); // don't write to world saves
            c.hitboxId = hb.getUniqueId();
            hitboxToOwner.put(hb.getUniqueId(), c.owner);
        } catch (Throwable t) {
            getLogger().warning("(✧) hitbox spawn failed: " + t.getMessage());
        }
    }

    /** Move the hitbox to the companion's current position. Recreates it if missing (chunk unload, etc). */
    private void updateHitbox(Companion c) {
        if (c.entity == null) return;
        Location loc = c.entity.getLocation();
        Entity hb = c.hitboxId == null ? null : Bukkit.getEntity(c.hitboxId);
        if (hb == null || !hb.isValid()) {
            spawnHitbox(c, loc);
        } else {
            // Bukkit's teleport on Interaction is cheap (no movement physics).
            hb.teleport(loc);
        }

        // Keep the chat bubble glued just above her head if one's active.
        if (c.bubbleId != null) {
            Entity bubble = Bukkit.getEntity(c.bubbleId);
            if (bubble != null && bubble.isValid()) {
                bubble.teleport(loc.clone().add(0, 2.4, 0));
            } else {
                c.bubbleId = null;
                c.bubbleClearTick = 0;
            }
        }
    }

    private void despawnHitbox(Companion c) {
        if (c.hitboxId == null) return;
        Entity hb = Bukkit.getEntity(c.hitboxId);
        if (hb != null) hb.remove();
        hitboxToOwner.remove(c.hitboxId);
        c.hitboxId = null;
    }

    // ============================================================
    // ============== CHAT BUBBLE + AMBIENT CHAT ==================
    // ============================================================

    /**
     * Make the companion "speak" {@code text}: spawn or update a
     * {@link TextDisplay} above her head, and broadcast to nearby
     * Bukkit players' chat. The bubble auto-clears after
     * {@link #chatBubbleDurationTicks}.
     */
    private void showChatBubble(Companion c, String text) {
        if (c == null || c.entity == null) return;
        if (text == null) text = "";

        Location loc = c.entity.getLocation();
        World w = loc.getWorld();
        Location bubbleLoc = loc.clone().add(0, 2.4, 0);

        // Reuse existing bubble or spawn a fresh one.
        TextDisplay td = null;
        if (chatBubbleEnabled) {
            if (c.bubbleId != null) {
                Entity existing = Bukkit.getEntity(c.bubbleId);
                if (existing instanceof TextDisplay && existing.isValid()) {
                    td = (TextDisplay) existing;
                }
            }
            if (td == null) {
                try {
                    Entity e = w.spawnEntity(bubbleLoc, EntityType.TEXT_DISPLAY);
                    if (e instanceof TextDisplay newTd) {
                        td = newTd;
                        td.setBillboard(Display.Billboard.CENTER);
                        td.setSeeThrough(false);
                        td.setShadowed(false);
                        td.setPersistent(false);
                        try {
                            td.setBackgroundColor(Color.fromARGB(180, 30, 0, 60));
                        } catch (Throwable ignored) {
                            // older Paper without ARGB Color — use defaults
                        }
                        td.setLineWidth(200);
                        c.bubbleId = td.getUniqueId();
                    } else {
                        e.remove();
                    }
                } catch (Throwable t) {
                    getLogger().warning("(\u2727) chat bubble spawn failed: " + t.getMessage());
                }
            }
            if (td != null) {
                try {
                    td.setText("§d" + text);
                } catch (Throwable t) {
                    // Fallback for builds where setText(String) was removed.
                    getLogger().fine("setText fallback: " + t.getMessage());
                }
                td.teleport(bubbleLoc);
                c.bubbleClearTick = behaviorTickCount + chatBubbleDurationTicks;
            }
        }

        // Also broadcast in chat to nearby players (excluding companions).
        String formatted = "§d<" + c.name + "> §f" + text;
        double radSq = chatBroadcastRadius * chatBroadcastRadius;
        for (Player p : w.getPlayers()) {
            // Don't try to send to companion-owned CraftPlayers — their
            // listener queue is already cleared periodically, but we
            // skip the cycle anyway.
            if (p.getWorld() != loc.getWorld()) continue; // never measure cross-world distance
            if (p.getLocation().distanceSquared(loc) <= radSq) {
                p.sendMessage(formatted);
            }
        }
    }

    /** Clear the bubble's text once its display window has elapsed. */
    private void clearChatBubbleIfDue(Companion c, long tick) {
        if (c.bubbleClearTick == 0 || tick < c.bubbleClearTick) return;
        if (c.bubbleId != null) {
            Entity bubble = Bukkit.getEntity(c.bubbleId);
            if (bubble instanceof TextDisplay td) {
                try { td.setText(""); } catch (Throwable ignored) {}
            }
        }
        c.bubbleClearTick = 0;
    }

    /** Roll for a random ambient phrase if it's been long enough since the last one. */
    private void tryAmbientChat(Companion c, long tick) {
        if (!ambientChatEnabled || ambientPhrases.isEmpty()) return;
        if (c.nextAmbientChatTick == 0) {
            // First-time scheduling — seed it so she doesn't speak the
            // instant she spawns.
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            long span = Math.max(1, ambientChatMaxTicks - ambientChatMinTicks);
            c.nextAmbientChatTick = tick + ambientChatMinTicks + rng.nextLong(span);
            return;
        }
        if (tick < c.nextAmbientChatTick) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String phrase = ambientPhrases.get(rng.nextInt(ambientPhrases.size()));
        showChatBubble(c, phrase);

        long span = Math.max(1, ambientChatMaxTicks - ambientChatMinTicks);
        c.nextAmbientChatTick = tick + ambientChatMinTicks + rng.nextLong(span);
    }

    /** Tear down the bubble entity if any (called from despawnEntity). */
    private void despawnBubble(Companion c) {
        if (c.bubbleId == null) return;
        Entity bubble = Bukkit.getEntity(c.bubbleId);
        if (bubble != null) bubble.remove();
        c.bubbleId = null;
        c.bubbleClearTick = 0;
    }


    // ============================================================
    // ============== ITEM CARRYING + WEAPON EQUIP ================
    // ============================================================

    /**
     * Find the best weapon in her inventory and put it in her main hand.
     * "Best" = first entry by typical melee preference (netherite >
     * diamond > iron > stone > wood for swords; falls through to axes
     * if no sword is found). Empties the hand if the inventory has no
     * weapon, so removing a sword visibly disarms her.
     */
    private void equipBestWeapon(Companion c) {
        if (c.entity == null) return;
        ItemStack chosen = null;
        if (c.inventory != null) {
            // Prefer a real melee weapon, then any tool with damage, then nothing.
            // ItemKnowledge.bestMeleeWeapon already encapsulates this priority.
            chosen = ItemKnowledge.bestMeleeWeapon(c.inventory);
        }
        c.entity.setMainHand(chosen);
    }

    private Inventory ensureBag(Companion c) {
        if (c.inventory == null) {
            c.inventory = Bukkit.createInventory(null, inventorySize, "✿ " + c.name + "'s bag");
        }
        return c.inventory;
    }

    /** Her second storage chest (extra bag). Title also contains "'s bag" so
     *  the bag-close persistence + control-GUI guards treat it like a bag. */
    private Inventory ensureBag2(Companion c) {
        if (c.inventory2 == null) {
            c.inventory2 = Bukkit.createInventory(null, inventorySize, "✿ " + c.name + "'s bag (2)");
        }
        return c.inventory2;
    }

    // ============================================================
    // ============== GUI: control + bag ==========================
    // ============================================================

    /** GUI title prefix used to identify our GUIs in click/close events. */
    private static final String GUI_TITLE_PREFIX = "✿ ";

    private void openControlGui(Player p, Companion c) {
        Inventory gui = Bukkit.createInventory(null, 18, GUI_TITLE_PREFIX + c.name);
        gui.setItem(0, modeButton(Material.LEAD,    "§dFollow Me",  "stay close to you", c.mode == BehaviorMode.FOLLOW));
        gui.setItem(1, modeButton(Material.BARRIER, "§dStay Here",  "don't move from this spot", c.mode == BehaviorMode.STAY));
        gui.setItem(2, modeButton(Material.SHIELD,  "§dGuard Me",   "watch for hostile mobs",    c.mode == BehaviorMode.GUARD));
        gui.setItem(3, modeButton(Material.COMPASS, "§dScout Area", "wander around here",        c.mode == BehaviorMode.SCOUT));
        if (p.hasPermission("kawaiicompanion.build")) {
            gui.setItem(4, plainButton(Material.STRUCTURE_BLOCK, "§dBuild", "build from a schematic"));
        }
        gui.setItem(5, plainButton(Material.NAME_TAG, "§dAppearances", "change her skin / form"));
        gui.setItem(6, plainButton(Material.CHEST,  "§dInventory",  "see what she's carrying"));
        gui.setItem(7, plainButton(Material.SADDLE, "§dRide",       "mount your companion"));
        gui.setItem(8, plainButton(Material.RED_BED,"§cDismiss",    "send her away"));
        // Second row — new controls.
        gui.setItem(9,  modeButton(Material.SPYGLASS, "§dStandby", "hold here & fight what's near", c.mode == BehaviorMode.STANDBY));
        gui.setItem(10, plainButton(Material.CAULDRON, "§cEmpty Bags", "clear everything she carries"));
        gui.setItem(11, plainButton(Material.ENDER_CHEST, "§dSecond Bag", "her extra storage chest"));
        gui.setItem(12, plainButton(Material.IRON_SWORD, "§cHunt Nearby",
                "pick which mobs/players she hunts"));
        boolean canMulti = p.isOp() || p.hasPermission("kawaiicompanion.multiple");
        if (canMulti) {
            gui.setItem(14, plainButton(Material.ARMOR_STAND, "§dSummon Helper", "an extra Kohaku that obeys your orders (op)"));
            gui.setItem(15, plainButton(Material.BONE, "§cDismiss Helpers", "send all helpers away (op)"));
        }
        p.openInventory(gui);
        activeControlGuis.put(p.getUniqueId(), c.owner);
    }

    private static ItemStack modeButton(Material m, String name, String desc, boolean active) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((active ? "§a✓ " : "") + name);
            meta.setLore(List.of("§7" + desc));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack plainButton(Material m, String name, String desc) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of("§7" + desc));
            it.setItemMeta(meta);
        }
        return it;
    }

    // ============================================================
    // ============== GUI: Hunt picker (choose mobs) ==============
    // ============================================================

    private static final String HUNT_GUI_PREFIX = "✿ Hunt ~ ";
    private static final String DIG_GUI_PREFIX  = "✿ Dig ~ ";

    /** {key, icon, label} for each pickable mob in the Hunt menu. */
    private static final Object[][] HUNT_OPTIONS = {
            // ---- hostiles ----
            {"ZOMBIE", Material.ZOMBIE_SPAWN_EGG, "§2Zombie"},
            {"ZOMBIE_VILLAGER", Material.ZOMBIE_VILLAGER_SPAWN_EGG, "§2Zombie Villager"},
            {"HUSK", Material.HUSK_SPAWN_EGG, "§eHusk"},
            {"DROWNED", Material.DROWNED_SPAWN_EGG, "§3Drowned"},
            {"SKELETON", Material.SKELETON_SPAWN_EGG, "§fSkeleton"},
            {"STRAY", Material.STRAY_SPAWN_EGG, "§bStray"},
            {"WITHER_SKELETON", Material.WITHER_SKELETON_SPAWN_EGG, "§8Wither Skeleton"},
            {"BOGGED", Material.BOGGED_SPAWN_EGG, "§2Bogged"},
            {"CREEPER", Material.CREEPER_SPAWN_EGG, "§aCreeper"},
            {"SPIDER", Material.SPIDER_SPAWN_EGG, "§8Spider"},
            {"CAVE_SPIDER", Material.CAVE_SPIDER_SPAWN_EGG, "§3Cave Spider"},
            {"ENDERMAN", Material.ENDERMAN_SPAWN_EGG, "§5Enderman"},
            {"ENDERMITE", Material.ENDERMITE_SPAWN_EGG, "§5Endermite"},
            {"SILVERFISH", Material.SILVERFISH_SPAWN_EGG, "§7Silverfish"},
            {"WITCH", Material.WITCH_SPAWN_EGG, "§5Witch"},
            {"SLIME", Material.SLIME_SPAWN_EGG, "§aSlime"},
            {"MAGMA_CUBE", Material.MAGMA_CUBE_SPAWN_EGG, "§6Magma Cube"},
            {"BLAZE", Material.BLAZE_SPAWN_EGG, "§6Blaze"},
            {"GHAST", Material.GHAST_SPAWN_EGG, "§fGhast"},
            {"PIGLIN", Material.PIGLIN_SPAWN_EGG, "§dPiglin"},
            {"PIGLIN_BRUTE", Material.PIGLIN_BRUTE_SPAWN_EGG, "§dPiglin Brute"},
            {"ZOMBIFIED_PIGLIN", Material.ZOMBIFIED_PIGLIN_SPAWN_EGG, "§dZombie Piglin"},
            {"HOGLIN", Material.HOGLIN_SPAWN_EGG, "§cHoglin"},
            {"ZOGLIN", Material.ZOGLIN_SPAWN_EGG, "§cZoglin"},
            {"PILLAGER", Material.PILLAGER_SPAWN_EGG, "§7Pillager"},
            {"VINDICATOR", Material.VINDICATOR_SPAWN_EGG, "§7Vindicator"},
            {"EVOKER", Material.EVOKER_SPAWN_EGG, "§7Evoker"},
            {"VEX", Material.VEX_SPAWN_EGG, "§bVex"},
            {"RAVAGER", Material.RAVAGER_SPAWN_EGG, "§4Ravager"},
            {"PHANTOM", Material.PHANTOM_SPAWN_EGG, "§bPhantom"},
            {"GUARDIAN", Material.GUARDIAN_SPAWN_EGG, "§3Guardian"},
            {"SHULKER", Material.SHULKER_SPAWN_EGG, "§5Shulker"},
            {"BREEZE", Material.BREEZE_SPAWN_EGG, "§fBreeze"},
            {"WARDEN", Material.WARDEN_SPAWN_EGG, "§3Warden"},
            // ---- passive / neutral ----
            {"COW", Material.COW_SPAWN_EGG, "§6Cow"},
            {"PIG", Material.PIG_SPAWN_EGG, "§dPig"},
            {"SHEEP", Material.SHEEP_SPAWN_EGG, "§fSheep"},
            {"CHICKEN", Material.CHICKEN_SPAWN_EGG, "§fChicken"},
            {"RABBIT", Material.RABBIT_SPAWN_EGG, "§eRabbit"},
            {"WOLF", Material.WOLF_SPAWN_EGG, "§7Wolf"},
            {"FOX", Material.FOX_SPAWN_EGG, "§6Fox"},
            {"GOAT", Material.GOAT_SPAWN_EGG, "§fGoat"},
    };

    /** Open the Hunt picker — check one or more mobs (and/or categories), then Go. */
    private void openHuntGui(Player p, Companion c) {
        java.util.Set<String> picks = huntPicks.computeIfAbsent(p.getUniqueId(), u -> new java.util.HashSet<>());
        Inventory gui = Bukkit.createInventory(null, 54, HUNT_GUI_PREFIX + c.name);
        for (int i = 0; i < HUNT_OPTIONS.length; i++) {
            Object[] o = HUNT_OPTIONS[i];
            gui.setItem(i, huntButton((Material) o[1], (String) o[2], picks.contains((String) o[0])));
        }
        gui.setItem(45, huntButton(Material.NETHERITE_SWORD, "§cAll Hostiles", picks.contains("HOSTILE")));
        gui.setItem(46, huntButton(Material.WHEAT, "§eAll Animals", picks.contains("ANIMAL")));
        if (allowKillPlayers && (p.isOp() || p.hasPermission("kawaiicompanion.kill.players"))) {
            gui.setItem(47, huntButton(Material.PLAYER_HEAD, "§4Players", picks.contains("PLAYERS")));
        }
        gui.setItem(49, plainButton(Material.LIME_WOOL, "§aGo Hunt!", "send her after everything checked"));
        gui.setItem(50, plainButton(Material.RED_WOOL, "§cStop Hunting", "call her off"));
        gui.setItem(53, plainButton(Material.ARROW, "§7Back", "back to her menu"));
        p.openInventory(gui);
        activeHuntGuis.put(p.getUniqueId(), c.owner);
    }

    /** A pick button with a ✓ when selected. */
    private static ItemStack huntButton(Material m, String name, boolean selected) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((selected ? "§a✓ " : "§7") + name);
            meta.setLore(List.of(selected ? "§aselected — click to remove" : "§7click to select"));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Map a Hunt-menu slot to its selection key, or null for non-pick slots. */
    private static String huntKeyForSlot(int slot) {
        if (slot >= 0 && slot < HUNT_OPTIONS.length) return (String) HUNT_OPTIONS[slot][0];
        return switch (slot) {
            case 45 -> "HOSTILE";
            case 46 -> "ANIMAL";
            case 47 -> "PLAYERS";
            default -> null;
        };
    }

    @EventHandler
    public void onHuntGuiClick(InventoryClickEvent event) {
        UUID ownerId = activeHuntGuis.get(event.getWhoClicked().getUniqueId());
        if (ownerId == null) return;
        String title = event.getView().getTitle();
        if (title == null || !title.startsWith(HUNT_GUI_PREFIX)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Companion c = companions.get(ownerId);
        if (c == null) { p.closeInventory(); return; }
        java.util.Set<String> picks = huntPicks.computeIfAbsent(p.getUniqueId(), u -> new java.util.HashSet<>());
        int slot = event.getRawSlot();
        switch (slot) {
            case 49 -> { p.closeInventory(); confirmHunt(p, picks); }
            case 50 -> { p.closeInventory(); doKill(p, "stop"); picks.clear(); }
            case 53 -> openControlGui(p, c);
            default -> {
                String key = huntKeyForSlot(slot);
                if (key == null) return;
                if (!picks.remove(key)) picks.add(key);
                openHuntGui(p, c); // refresh the checkmarks
            }
        }
    }

    /** Build + broadcast a hunt order from the picked mobs/categories. */
    private void confirmHunt(Player p, java.util.Set<String> picks) {
        if (picks.isEmpty()) { p.sendMessage("§d(✧) pick at least one thing to hunt ~"); return; }
        // The picked keys ARE the persistent hunt filter — she'll keep after
        // new matching mobs as they appear, not just the ones here right now.
        for (Companion cc : ownerCompanions(p.getUniqueId())) {
            cc.huntFilter.clear();
            cc.huntFilter.addAll(picks);
            cc.killAnnounced = true;
            refillHuntTargets(cc, p);
            showChatBubble(cc, "On it! ♥");
        }
        p.sendMessage("§d(✧) on the hunt ~ (§f/kc kill stop§d to end)");
    }

    // ============================================================
    // ============== GUI: Dig submenu ============================
    // ============================================================

    /** Open the Dig submenu — clear buttons instead of click shortcuts. */
    private void openDigGui(Player p, Companion c) {
        Inventory gui = Bukkit.createInventory(null, 9, DIG_GUI_PREFIX + c.name);
        boolean has = !c.digPattern.isEmpty();
        gui.setItem(0, plainButton(Material.WRITABLE_BOOK, "§eTeach (record)", "then mine your tunnel/staircase"));
        gui.setItem(2, plainButton(Material.IRON_PICKAXE, has ? "§aDig It!" : "§8Dig It!",
                has ? "she repeats your pattern, looping" : "teach her a pattern first"));
        gui.setItem(4, plainButton(Material.BARRIER, "§cStop / Finish", "finish recording or halt digging"));
        gui.setItem(6, plainButton(Material.LAVA_BUCKET, "§cForget", "delete the taught pattern"));
        gui.setItem(8, plainButton(Material.ARROW, "§7Back", "back to her menu"));
        p.openInventory(gui);
        activeDigGuis.put(p.getUniqueId(), c.owner);
    }

    @EventHandler
    public void onDigGuiClick(InventoryClickEvent event) {
        UUID ownerId = activeDigGuis.get(event.getWhoClicked().getUniqueId());
        if (ownerId == null) return;
        String title = event.getView().getTitle();
        if (title == null || !title.startsWith(DIG_GUI_PREFIX)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Companion c = companions.get(ownerId);
        if (c == null) { p.closeInventory(); return; }
        switch (event.getRawSlot()) {
            case 0 -> { p.closeInventory(); doDig(p, "record"); }
            case 2 -> { p.closeInventory(); doDig(p, "go"); }
            case 4 -> { p.closeInventory(); doDig(p, "stop"); }
            case 6 -> { p.closeInventory(); doDig(p, "cancel"); }
            case 8 -> openControlGui(p, c);
            default -> { /* ignore */ }
        }
    }

    // ============================================================
    // ============== EVENT HANDLERS ==============================
    // ============================================================

    /** Right-click on the click hitbox → open control GUI (or bag if sneaking). */
    @EventHandler
    public void onInteractCompanion(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        UUID ownerId = null;
        if (clicked instanceof Interaction hb) {
            ownerId = hitboxToOwner.get(hb.getUniqueId());
        } else {
            // FEATURE 5: the real-entity (mob form) companion is clickable
            // directly — and the click must never fall through to vanilla
            // mob interactions (feeding, saddling, trading...).
            for (Companion cc : companions.values()) {
                if (clicked.getUniqueId().equals(cc.realEntityId)) {
                    ownerId = cc.owner;
                    break;
                }
            }
            if (ownerId == null) {
                // Fallback: PDC marker, in case the tracked entity id is
                // stale (respawn race, plugin reload mid-session...).
                try {
                    String s = clicked.getPersistentDataContainer().get(companionMarkerKey(),
                            org.bukkit.persistence.PersistentDataType.STRING);
                    if (s != null) ownerId = UUID.fromString(s);
                } catch (Throwable ignored) {}
            }
        }
        if (ownerId == null) return;
        Companion c = companions.get(ownerId);
        if (c == null) return;

        Player clicker = event.getPlayer();
        event.setCancelled(true);

        // Only the owner can boss her around. A friendly nudge for
        // anyone else.
        if (!clicker.getUniqueId().equals(ownerId)) {
            clicker.sendMessage("§d(✧) that's not your companion ~");
            return;
        }
        if (clicker.isSneaking()) {
            clicker.openInventory(ensureBag(c));
        } else {
            openControlGui(clicker, c);
        }
    }

    /** Click handlers for the mode-select control GUI. */
    @EventHandler
    public void onControlGuiClick(InventoryClickEvent event) {
        UUID ownerId = activeControlGuis.get(event.getWhoClicked().getUniqueId());
        if (ownerId == null) return;
        // Match by title prefix so we only react to *our* GUIs (not the
        // bag GUI, which is a separate inventory).
        String title = event.getView().getTitle();
        if (title == null || !title.startsWith(GUI_TITLE_PREFIX)
                || title.contains("'s bag")) return;
        event.setCancelled(true);
        Companion c = companions.get(ownerId);
        if (c == null || !(event.getWhoClicked() instanceof Player p)) return;

        switch (event.getRawSlot()) {
            case 0 -> setMode(c, BehaviorMode.FOLLOW, p, "following you ~");
            case 1 -> setMode(c, BehaviorMode.STAY,   p, "staying right here ~");
            case 2 -> setMode(c, BehaviorMode.GUARD,  p, "on guard ~");
            case 3 -> setMode(c, BehaviorMode.SCOUT,  p, "scouting around ~");
            case 4 -> {
                p.closeInventory();
                if (buildManager != null && p.hasPermission("kawaiicompanion.build")) buildManager.openGui(p);
            }
            case 5 -> { p.closeInventory(); openSkinsGui(p, c); }
            case 6 -> { p.closeInventory(); p.openInventory(ensureBag(c)); }
            case 7 -> { p.closeInventory(); doMount(p); }
            case 8 -> { p.closeInventory(); doDismiss(p); }
            case 9 -> setMode(c, BehaviorMode.STANDBY, p, "on standby — holding here ♥");
            case 10 -> { p.closeInventory(); doClearInv(p); }
            case 11 -> { p.closeInventory(); p.openInventory(ensureBag2(c)); }
            case 12 -> openHuntGui(p, c);   // pick-your-mobs submenu
            case 14 -> { p.closeInventory(); spawnExtra(p); }
            case 15 -> { p.closeInventory(); dismissExtras(p); }
            default -> { /* ignore decorative slots */ }
        }
    }

    /** Apply a mode to ALL of the owner's companions (primary + helpers), then
     *  confirm once. Helpers obey the same orders as the primary. */
    private void setMode(Companion c, BehaviorMode mode, Player p, String confirmation) {
        for (Companion cc : ownerCompanions(p.getUniqueId())) applyMode(cc, mode);
        p.closeInventory();
        p.sendMessage("§d(✧) " + confirmation);
    }

    /** The per-companion mode switch (no messaging). */
    private void applyMode(Companion c, BehaviorMode mode) {
        c.mode = mode;
        // companionLoc covers both render paths, so STAY anchors the
        // mob-form companion at her current spot too.
        c.anchor = companionLoc(c);
        c.scoutTarget = null;
        // Drop bow mode + re-pick her main-hand tool for the new mode.
        c.bowEquipped = false;
        equipBestWeapon(c);
        // Mode switch invalidates any cached path (different goal logic
        // now applies). Reset stuck tracking too.
        clearPath(c);
        c.stuckTicks = 0;
        // Mode change counts as activity — wake her up if she was napping.
        c.lastActivityTick = behaviorTickCount;
        c.ambientCrouchUntil = 0;
    }

    /** Every live companion this owner controls — the chatting primary first,
     *  then any op-summoned helper drones. Orders broadcast across this list. */
    private List<Companion> ownerCompanions(UUID owner) {
        List<Companion> out = new ArrayList<>();
        Companion primary = companions.get(owner);
        if (primary != null) out.add(primary);
        List<Companion> ex = extras.get(owner);
        if (ex != null) {
            for (Companion c : ex) {
                boolean live = c.realEntity
                        ? liveRealEntity(c) != null            // mob-form helper
                        : (c.entity != null && !c.entity.isDead()); // packet-NPC helper
                if (live) out.add(c);
            }
        }
        return out;
    }

    /** Summon one op-only helper drone (extra companion). Returns true on success. */
    private boolean spawnExtra(Player p) {
        if (!(p.isOp() || p.hasPermission("kawaiicompanion.multiple"))) {
            p.sendMessage("§c(✧) only ops can summon helpers ~");
            return false;
        }
        List<Companion> list = extras.computeIfAbsent(p.getUniqueId(), u -> new ArrayList<>());
        // Prune only DEAD helpers — and check the right render path. Mob-form
        // helpers have a null packet entity, so the old "entity == null" prune
        // wrongly culled them (then the stray-cleanup deleted their mobs, which
        // looked like a new helper "replacing" the last one).
        list.removeIf(c -> c.realEntity
                ? liveRealEntity(c) == null
                : (c.entity == null || c.entity.isDead()));
        if (list.size() >= maxExtras) {
            p.sendMessage("§d(✧) that's all the helpers I can have (" + maxExtras + ") ~");
            return false;
        }
        Companion primary = companions.get(p.getUniqueId());
        String baseName = primary != null ? primary.name : "Kohaku";
        String skin = primary != null ? primary.skin : null;
        Companion ec = new Companion(p.getUniqueId(), baseName + " " + (list.size() + 2), skin);
        ec.profileSalt = UUID.randomUUID().toString(); // distinct client profile per helper
        // Helpers share the primary's bag, so a sword you put in it arms all of them.
        if (primary != null) ec.inventory = ensureBag(primary);
        // Spawn the helper in the primary's CURRENT form (human or a mob form).
        EntityType primaryForm = (primary != null && primary.realEntity && primary.bedrockType != null)
                ? parseLivingEntityType(primary.bedrockType) : null;
        boolean asMob = primaryForm != null;
        if (asMob) applyMobFormTo(p, ec, primaryForm);
        else       spawnEntity(p, ec);
        boolean live = asMob ? (liveRealEntity(ec) != null) : (ec.entity != null);
        if (!live) { p.sendMessage("§c(✧) couldn't summon a helper ~"); return false; }
        if (primary != null) ec.mode = primary.mode; // new helper matches current orders
        if (!asMob) equipBestWeapon(ec); // wield the best weapon from the shared bag right away
        list.add(ec);
        String cap = maxExtras == Integer.MAX_VALUE ? "" : "/" + maxExtras;
        p.sendMessage("§d(✧) summoned a helper ~ (" + list.size() + cap + " helpers)");
        return true;
    }

    /** Despawn all of an owner's helper drones (the primary is untouched). */
    private void dismissExtras(Player p) {
        List<Companion> list = extras.remove(p.getUniqueId());
        if (list == null || list.isEmpty()) { p.sendMessage("§d(✧) no helpers to dismiss ~"); return; }
        for (Companion c : list) despawnCompanion(c);
        p.sendMessage("§d(✧) helpers dismissed ~");
    }

    /** Lean per-tick logic for a helper drone — movement + combat + dig, no chat. */
    private void tickExtra(Companion c, Player owner, long tick) {
        // Mob-form helpers use the real-entity tick (native AI), like the primary.
        if (c.realEntity) { tickRealEntity(c, owner, tick); return; }
        if (c.entity == null || c.entity.isDead()) return;
        updateHitbox(c);
        if (c.isBuilding) { idleAnimate(c, tick); return; }
        if (c.mode != BehaviorMode.STAY) openNearbyDoors(c);
        if (c.digging) {
            runDigJob(c, owner, tick);
        } else if (engageKillTargets(c, tick, owner)) {
            // hunting an ordered target
        } else if (!engageHerobrine(c, tick, owner)) {
            switch (c.mode) {
                case FOLLOW  -> stepToward(c, owner);
                case STAY    -> idleAnimate(c, tick);
                case GUARD   -> guardTick(c, tick);
                case SCOUT   -> scoutTick(c, tick);
                case STANDBY -> standbyTick(c, tick);
            }
        }
        if (c.navMob != null && c.navDrivenTick != tick) parkNavigator(c);
        clearChatBubbleIfDue(c, tick); // clear "On it!" bubbles; no ambient chat for drones
        updatePoseState(c, tick);
        if (pickupEnabled) tickPickup(c, owner, tick);
    }

    /** When the bag closes, re-evaluate her main-hand weapon + persist contents. */
    @EventHandler
    public void onBagClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        if (title == null || !title.contains("'s bag")) return;
        Inventory closed = event.getInventory();
        for (Companion c : companions.values()) {
            if ((c.inventory != null && c.inventory.equals(closed))
                    || (c.inventory2 != null && c.inventory2.equals(closed))) {
                // Re-equip the primary AND her helpers (they share this bag),
                // so a sword you just dropped in arms all of them.
                for (Companion cc : ownerCompanions(c.owner)) equipBestWeapon(cc);
                // Persist immediately so a /kc dismiss right after
                // closing the bag doesn't lose items.
                saveMemory(c);
                break;
            }
        }
    }

    /** Drop the GUI tracking entry when the player closes the control GUI. */
    @EventHandler
    public void onControlGuiClose(InventoryCloseEvent event) {
        activeControlGuis.remove(event.getPlayer().getUniqueId());
        activeHuntGuis.remove(event.getPlayer().getUniqueId());
        activeDigGuis.remove(event.getPlayer().getUniqueId());
    }

    /** While the owner is teaching a dig pattern, record each block they break. */
    @EventHandler(ignoreCancelled = true)
    public void onDigRecordBreak(BlockBreakEvent event) {
        Companion c = companions.get(event.getPlayer().getUniqueId());
        if (c == null || !c.recordingDig || c.digAnchor == null) return;
        Block b = event.getBlock();
        if (b.getWorld() != c.digAnchor.getWorld()) return;
        if (c.digPattern.size() >= 512) return; // sanity cap
        // Store the break as a plain world offset from the anchor. She reproduces
        // the EXACT direction you dug (dig north → she digs north), independent of
        // which way you're facing when you press go.
        c.digPattern.add(new int[]{
                b.getX() - c.digAnchor.getBlockX(),
                b.getY() - c.digAnchor.getBlockY(),
                b.getZ() - c.digAnchor.getBlockZ()
        });
    }

    // ---- facing-relative dig coordinate transforms ----
    // Cardinal axes for a yaw: {forwardX, forwardZ, rightX, rightZ}. MC yaw:
    // 0=south(+Z), 90=west(-X), 180=north(-Z), 270=east(+X).
    private static int[] cardinalAxes(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (q) {
            case 0 -> new int[]{0, 1, -1, 0};   // south: fwd +Z, right -X
            case 1 -> new int[]{-1, 0, 0, -1};  // west:  fwd -X, right -Z
            case 2 -> new int[]{0, -1, 1, 0};   // north: fwd -Z, right +X
            default -> new int[]{1, 0, 0, 1};   // east:  fwd +X, right +Z
        };
    }

    /** World offset → facing-local {forward, dy, right}. */
    private static int[] worldToLocal(int wdx, int wdy, int wdz, float yaw) {
        int[] a = cardinalAxes(yaw);
        int forward = wdx * a[0] + wdz * a[1];
        int right   = wdx * a[2] + wdz * a[3];
        return new int[]{forward, wdy, right};
    }

    /** Facing-local {forward, dy, right} → world {dx, dy, dz}. */
    private static int[] localToWorld(int[] local, float yaw) {
        if (local == null) return new int[]{0, 0, 0};
        int[] a = cardinalAxes(yaw);
        int dx = local[0] * a[0] + local[2] * a[2];
        int dz = local[0] * a[1] + local[2] * a[3];
        return new int[]{dx, local[1], dz};
    }

    /** Forward clicks in the schematic Build GUI to the BuildManager. */
    @EventHandler
    public void onBuildGuiClick(InventoryClickEvent event) {
        if (buildManager == null) return;
        String title = event.getView().getTitle();
        if (title == null || !buildManager.isOurGui(title)) return;
        // Defense in depth: building places/removes blocks, so re-check the
        // op-only permission on every click — never act for a non-permitted
        // player even if they somehow have the menu open.
        if (!event.getWhoClicked().hasPermission("kawaiicompanion.build")) {
            event.setCancelled(true);
            event.getWhoClicked().closeInventory();
            return;
        }
        buildManager.handleClick(event);
    }

    /** Clear BuildManager GUI tracking when its menu closes. */
    @EventHandler
    public void onBuildGuiClose(InventoryCloseEvent event) {
        if (buildManager == null) return;
        String title = event.getView().getTitle();
        if (title != null && buildManager.isOurGui(title)) {
            buildManager.markGuiClosed(event.getPlayer().getUniqueId());
        }
    }

    /**
     * The companion mob-form is flagged invulnerable, but {@code
     * setInvulnerable(true)} does NOT stop VOID damage — so on a skyblock
     * island (a void world) she dies the moment she paths/falls off the edge,
     * then respawns and falls again, dying constantly. Cancel ALL damage to
     * any companion entity so she's truly immune everywhere.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCompanionDamaged(EntityDamageEvent event) {
        if (isCompanionEntity(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Assist target tracking — when the owner takes damage from an
     * entity, mark that entity as the priority threat for her
     * companion(s) for {@link #assistMemoryTicks} ticks. She'll chase
     * + attack that mob even if there are closer ones.
     */
    @EventHandler
    public void onPlayerDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity attacker = event.getDamager();
        // If the attacker was a projectile, blame the shooter.
        if (attacker instanceof org.bukkit.entity.Projectile proj) {
            if (proj.getShooter() instanceof Entity src) attacker = src;
        }
        if (!(attacker instanceof LivingEntity le)) return;
        if (le.getUniqueId().equals(victim.getUniqueId())) return; // self-damage
        if (isCompanionEntity(le.getUniqueId())) return; // never her own mob form

        // ---- Auto-defend: a PLAYER hit the owner → every companion she has
        //      hunts that player down. No permission / toggle. ----
        if (le instanceof Player) {
            List<Companion> all = ownerCompanions(victim.getUniqueId());
            if (all.isEmpty()) return;
            for (Companion cc : all) {
                cc.killTargets.add(le.getUniqueId());
                cc.killAnnounced = true;
            }
            showChatBubble(all.get(0), "don't touch them! ♥");
            return;
        }

        // ---- Assist vs hostile mobs (existing behaviour) ----
        if (!assistOwnerEnabled) return;
        if (!isHostile(le)) return;
        // A hostile-typed mob that's actually a mob-form companion never
        // becomes an assist target (its damage is cancelled anyway, but
        // this event also fires for cancelled hits).
        if (isCompanionEntity(le.getUniqueId())) return;
        // Find the victim's companion (if any) and tag the attacker. Both
        // render paths use the tag: fake-player combat AND mob-form combat.
        Companion c = companions.get(victim.getUniqueId());
        if (c == null || !isCompanionLive(c)) return;
        long now = behaviorTickCount;
        c.assistTargetId = attacker.getUniqueId();
        c.assistTargetUntil = now + assistMemoryTicks;
        // Quick verbal cue so the player knows she's on it.
        if (c.lastSpotterTick != null) {
            Long last = c.lastSpotterTick.get(le.getType());
            if (last == null || now - last > 100) {
                showChatBubble(c, "leave them alone! \u2728");
                c.lastSpotterTick.put(le.getType(), now);
            }
        }
    }

    // ============================================================
    // ============== FEATURE 1: REAL-ENTITY (BEDROCK) ============
    // ============================================================

    /** A companion is "live" if its active render path has a live entity. */
    private boolean isCompanionLive(Companion c) {
        if (c == null) return false;
        if (c.realEntity) return liveRealEntity(c) != null;
        return c.entity != null && !c.entity.isDead();
    }

    /** The live Bukkit entity for a real-entity companion, or null. */
    private Entity liveRealEntity(Companion c) {
        if (c == null || c.realEntityId == null) return null;
        Entity e = Bukkit.getEntity(c.realEntityId);
        if (e == null || e.isDead() || !e.isValid()) return null;
        return e;
    }

    /** Resolve the EntityType for a real-entity companion, with safe fallback. */
    private EntityType resolveBedrockType(Companion c) {
        String name = (c != null && c.bedrockType != null && !c.bedrockType.isBlank())
                ? c.bedrockType : bedrockCompanionType;
        EntityType t = parseLivingEntityType(name);
        if (t != null) return t;
        // Config value was bad \u2014 try the hard-coded default, then ALLAY.
        t = parseLivingEntityType("ALLAY");
        return t != null ? t : EntityType.ALLAY;
    }

    /** EntityType.valueOf in try/catch; only accept spawnable living mobs. */
    private EntityType parseLivingEntityType(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            EntityType t = EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
            // Dragon AI can't be tamed (circles 0,0, griefs blocks) — never a form.
            if (t == EntityType.ENDER_DRAGON) return null;
            Class<? extends Entity> cls = t.getEntityClass();
            if (cls == null || !Mob.class.isAssignableFrom(cls)) return null;
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * FEATURE 5: every form the companion can morph into — all spawnable
     * EntityTypes backed by a real {@link Mob} (so pathfinding + custom
     * names work), alphabetical. Computed once per (re)load so version
     * differences in the EntityType enum are picked up automatically.
     */
    private List<String> allMobForms() {
        List<String> out = new ArrayList<>();
        for (EntityType t : EntityType.values()) {
            try {
                if (!t.isSpawnable()) continue;
            } catch (Throwable ignored) {
                continue;
            }
            if (parseLivingEntityType(t.name()) == null) continue;
            out.add(t.name());
        }
        Collections.sort(out);
        return out;
    }

    /** Summon the REAL-ENTITY companion (Bedrock owners / forced via config). */
    private void doSummonReal(Player p) {
        // Reuse the tracked companion (never replace the object): a fresh
        // one would drop the old realEntityId and orphan a still-live mob.
        // Despawn both render paths up front so a re-summon can never
        // stack a second copy next to the first.
        Companion c = companions.computeIfAbsent(p.getUniqueId(), u -> loadOrCreate(p));
        despawnRealEntity(c);
        despawnEntity(c);
        c.realEntity = true;
        Entity spawned = spawnRealEntity(p, c);
        if (spawned == null) {
            p.sendMessage("\u00a7c(\u2727) couldn't spawn companion \u2014 invalid entity type");
            return;
        }
        companions.put(p.getUniqueId(), c);
        maybeNotifyBedrockOwner(p);
        p.sendMessage("\u00a7d(\u2727) \u00a7f" + companionDisplayName(c) + "\u00a7d appeared \u2728 \u00a78(form: "
                + resolveBedrockType(c).name().toLowerCase(Locale.ROOT) + ")");
    }

    /**
     * Spawn the configurable real mob and lock it down: persistent,
     * invulnerable, silent, custom-named, not targetable by mobs, and
     * not picking up items. Returns the spawned entity (null on failure).
     */
    private Entity spawnRealEntity(Player owner, Companion c) {
        Location loc = spawnLocFor(owner);
        World w = loc.getWorld();
        if (w == null) return null;
        EntityType type = resolveBedrockType(c);
        c.bedrockType = type.name();
        Entity e;
        try {
            e = w.spawnEntity(loc, type);
        } catch (Throwable t) {
            getLogger().warning("(\u2727) real-entity spawn failed for " + type + ": " + t.getMessage());
            return null;
        }
        applyRealEntityTraits(e, c);
        c.realEntityId = e.getUniqueId();
        // One companion per owner, ALWAYS: kill every other loaded mob
        // tagged with this owner. Transiently-invalid entities (relogs,
        // world changes, chunk races) can dodge the tracked despawn and
        // would otherwise pile up as duplicates on each re-summon/morph.
        removeStrayCompanionsOf(c.owner, e.getUniqueId());
        return e;
    }

    /**
     * Remove every loaded marker-tagged companion mob belonging to
     * {@code owner} except {@code keep}. Returns how many were removed.
     */
    private int removeStrayCompanionsOf(UUID owner, UUID keep) {
        int removed = 0;
        String want = owner.toString();
        org.bukkit.NamespacedKey markerKey = companionMarkerKey();
        // Never delete the owner's OTHER live companions (helper mob-forms,
        // navigators) — only truly orphaned, owner-tagged mobs.
        java.util.Set<UUID> protectedIds = protectedCompanionIds(owner);
        // Real-entity companions are non-persistent and respawned next to the
        // owner, so owner-tagged orphans live in the owner's current world.
        // Scan just that world (cheap) instead of every entity in every world;
        // fall back to all worlds only when the owner isn't online to locate.
        Iterable<World> worlds;
        Player owp = Bukkit.getPlayer(owner);
        if (owp != null && owp.isOnline() && owp.getWorld() != null) {
            worlds = java.util.List.of(owp.getWorld());
        } else {
            worlds = Bukkit.getWorlds();
        }
        for (World w : worlds) {
            for (Entity e : w.getEntities()) {
                if (keep != null && keep.equals(e.getUniqueId())) continue;
                if (protectedIds.contains(e.getUniqueId())) continue;
                String tag = null;
                try {
                    tag = e.getPersistentDataContainer().get(markerKey,
                            org.bukkit.persistence.PersistentDataType.STRING);
                } catch (Throwable ignored) {}
                if (!want.equals(tag)) continue;
                try { e.remove(); removed++; } catch (Throwable ignored) {}
            }
        }
        return removed;
    }

    /** PDC marker on real-entity companions: owner UUID as a string. */
    private org.bukkit.NamespacedKey companionMarkerKeyCache;
    private org.bukkit.NamespacedKey companionMarkerKey() {
        org.bukkit.NamespacedKey k = companionMarkerKeyCache;
        if (k == null) {
            k = new org.bukkit.NamespacedKey(this, "companion-owner");
            companionMarkerKeyCache = k;
        }
        return k;
    }

    /** Lock-down + cosmetic traits applied to the real-entity companion. */
    private void applyRealEntityTraits(Entity e, Companion c) {
        // NEVER saved with chunks: a crash or chunk unload would otherwise
        // leave an orphaned invulnerable mob (with vanilla hostile AI!)
        // roaming the world. The tick loop respawns her next to the owner
        // whenever she's lost, so nothing is lost by not persisting.
        try { e.setPersistent(false); } catch (Throwable ignored) {}
        try {
            e.getPersistentDataContainer().set(companionMarkerKey(),
                    org.bukkit.persistence.PersistentDataType.STRING, c.owner.toString());
        } catch (Throwable ignored) {}
        try { e.setCustomName("\u00a7d" + companionDisplayName(c)); } catch (Throwable ignored) {}
        try { e.setCustomNameVisible(showNameTag); } catch (Throwable ignored) {}
        try { e.setSilent(true); } catch (Throwable ignored) {}
        try { e.setInvulnerable(true); } catch (Throwable ignored) {}
        // Undead/nether forms ignite or look on fire — she never burns.
        try { e.setVisualFire(false); } catch (Throwable ignored) {}
        // A freshly-spawned wither does a 10-second spawn charge + boom;
        // skip straight past it. (Its boom is also block-stripped by the
        // companion-explosion guard either way.)
        try {
            if (e instanceof org.bukkit.entity.Wither w) w.setInvulnerableTicks(0);
        } catch (Throwable ignored) {}
        // Boss forms (wither) must not hijack everyone's boss bar — hide
        // it immediately at spawn (the real-entity tick re-asserts this).
        if (e instanceof org.bukkit.entity.Boss boss) {
            try {
                org.bukkit.boss.BossBar bar = boss.getBossBar();
                if (bar != null) { bar.setVisible(false); bar.removeAll(); }
            } catch (Throwable ignored) {}
        }
        try { e.setGlowing(!(glowColor == null || glowColor.equalsIgnoreCase("none"))); } catch (Throwable ignored) {}
        if (e instanceof LivingEntity le) {
            try { le.setRemoveWhenFarAway(false); } catch (Throwable ignored) {}
            try { le.setCanPickupItems(false); } catch (Throwable ignored) {}
            // version-safe health: deprecated setMaxHealth + setHealth, no Attribute enum.
            try {
                double hp = 20.0 + (c.level - 1) * 4.0;
                le.setMaxHealth(hp);
                le.setHealth(hp);
            } catch (Throwable ignored) {}
        }
        if (e instanceof Mob mob) {
            // Friendly: no AI targeting of anyone, and clear any target.
            try { mob.setTarget(null); } catch (Throwable ignored) {}
            try { mob.setAware(true); } catch (Throwable ignored) {}
        }
    }

    /** Despawn the real-entity companion + dismount + clear state. */
    private void despawnRealEntity(Companion c) {
        if (c == null) return;
        Entity e = liveRealEntity(c);
        if (e != null) {
            try { e.eject(); } catch (Throwable ignored) {}
            try { e.remove(); } catch (Throwable ignored) {}
        }
        c.realEntityId = null;
        c.mounted = false;
        despawnBubble(c);
    }

    /**
     * Per-tick logic for a real-entity companion: re-spawn if it somehow
     * died/despawned, follow the owner (teleport when too far / wrong
     * world), keep its name current, then run leveling pulses.
     */
    private void tickRealEntity(Companion c, Player owner, long tick) {
        Entity e = liveRealEntity(c);
        if (e == null) {
            // Lost the entity (chunk unload, killed somehow). Respawn it
            // next to the owner so the companion persists across hiccups.
            e = spawnRealEntity(owner, c);
            if (e == null) return;
        }

        // Belt-and-suspenders so she can NEVER die: setInvulnerable doesn't
        // stop void damage, and on a void skyblock world a physics mob can
        // wander off the island edge. Heal any damage that slipped through...
        if (e instanceof LivingEntity hle) {
            try {
                double mx = hle.getMaxHealth();
                if (hle.getHealth() < mx) hle.setHealth(mx);
            } catch (Throwable ignored) {}
        }
        // ...and yank her back to the owner the instant she falls past the
        // world floor OR starts dropping off the edge into open void (no
        // ground for 8 blocks below + falling), in any world.
        try {
            if (!c.mounted && e.getWorld() == owner.getWorld()) {
                Location el = e.getLocation();
                boolean belowWorld = el.getY() < e.getWorld().getMinHeight() + 3;
                boolean falling = e.getVelocity().getY() < -0.08;
                boolean fellOff = falling
                        && el.getY() < owner.getLocation().getY() - 4
                        && noGroundBelow(el, 8);
                if (belowWorld || fellOff) {
                    e.teleport(spawnLocFor(owner));
                    try { e.setFallDistance(0f); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // Hostile forms (zombie, skeleton...) ignite in daylight; she's
        // invulnerable so it's cosmetic \u2014 keep her from looking on fire.
        try { if (e.getFireTicks() > 0) e.setFireTicks(0); } catch (Throwable ignored) {}

        // FEATURE 5 watchdog: whatever the form's vanilla AI decided, it
        // may never hunt non-hostiles \u2014 the owner included. Brain-driven
        // mobs (warden!) acquire targets through anger without a
        // cancellable EntityTargetEvent on every build, so we clear both
        // the target AND the anger here every tick.
        // Kill-order routing: point the form's native AI at the nearest thing
        // she's been told to hunt (/kc kill) or a player who hit the owner, so
        // mob forms (creeper, etc.) obey the order + auto-defend too.
        LivingEntity killTarget = null;
        if (e instanceof Mob) {
            if (!c.huntFilter.isEmpty()) refillHuntTargets(c, owner);
            killTarget = nearestKillTarget(c, e.getLocation());
        }
        if (e instanceof Mob mob) {
            LivingEntity vt = null;
            try { vt = mob.getTarget(); } catch (Throwable ignored) {}
            // Clear stray non-hostile / companion targets — but NOT our kill target.
            if (vt != null && vt != killTarget
                    && (!isHostile(vt) || isCompanionEntity(vt.getUniqueId()))) {
                try { mob.setTarget(null); } catch (Throwable ignored) {}
                // Also drop the in-flight chase path — clearing the target
                // alone leaves the navigation running to its old goal.
                try { mob.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
                if (mob instanceof org.bukkit.entity.Warden wd) {
                    try { wd.clearAnger(vt); } catch (Throwable ignored) {}
                }
            }
            if (killTarget != null) {
                try { mob.setTarget(killTarget); } catch (Throwable ignored) {}
            }
            if (mob instanceof org.bukkit.entity.Warden wd) {
                // Vibrations (walking, jumping, opening doors) feed warden
                // anger continuously — wipe it for every nearby player so
                // she never locks onto anyone.
                try {
                    for (Player pl : e.getWorld().getPlayers()) {
                        if (pl.getLocation().distanceSquared(e.getLocation()) < 32 * 32) {
                            wd.clearAnger(pl);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        // Boss forms (wither) must not hijack everyone's screen-top boss
        // bar. setVisible is a no-op when already hidden, so per-tick is
        // free and survives whatever vanilla does to the bar.
        if (e instanceof org.bukkit.entity.Boss boss) {
            try {
                org.bukkit.boss.BossBar bar = boss.getBossBar();
                if (bar != null) { bar.setVisible(false); bar.removeAll(); }
            } catch (Throwable ignored) {}
        }

        // FEATURE 5: fight nearby hostile mobs in this form's own style.
        // While engaged she chases/shoots the target instead of heeling,
        // but the too-far / cross-world teleports below still leash her.
        // An ordered kill (/kc kill, hunt picker, or auto-defend) takes
        // priority and is fought via the SCRIPTED attack path — not just
        // native AI, which won't make a creeper/passive form actually attack
        // a sheep or another non-hostile. Otherwise fall back to fighting
        // nearby hostiles in her own style. Combat runs even while she's
        // being RIDDEN (her guard / kill orders still land hits) — only the
        // auto-movement is suppressed (the rider steers), handled inside the
        // combat helpers via the c.mounted guard.
        boolean engaged;
        if (killTarget != null) {
            tickKillCombat(c, e, owner, tick, killTarget);
            engaged = true;
        } else {
            engaged = tickFormCombat(c, e, owner, tick);
        }

        // STAY (set via the right-click panel) anchors her: no heeling,
        // no chasing \u2014 ranged forms still shoot from where she stands.
        boolean anchored = c.mode == BehaviorMode.STAY;

        // Mounted \u2192 she carries the owner; don't fight the ride physics.
        if (!c.mounted && !anchored) {
            World ow = owner.getWorld();
            Location eloc = e.getLocation();
            if (e.getWorld() != ow) {
                e.teleport(spawnLocFor(owner));
            } else {
                double dist = eloc.distance(owner.getLocation());
                if (dist > teleportThreshold) {
                    e.teleport(spawnLocFor(owner));
                } else if (!engaged && dist > followDistance + 1.0) {
                    // Glide toward the owner. For a Mob we set a path target
                    // first (smooth), but flyers + slow mobs benefit from a
                    // gentle nudge teleport when they fall behind.
                    if (e instanceof Mob mob) {
                        // FEATURE 2: movement speed up — pathfind faster at
                        // higher levels (capped) when the speed ability is on.
                        double speed = 1.0;
                        if (abilitySpeed && levelingEnabled) {
                            speed = Math.min(1.8, 1.0 + (c.level - 1) * 0.08);
                        }
                        try { mob.getPathfinder().moveTo(owner, speed); } catch (Throwable ignored) {}
                    }
                    if (dist > followDistance + 6.0
                            && tick - c.realFollowLastTick > 20) {
                        Location near = spawnLocFor(owner);
                        Location glide = eloc.clone().add(
                                near.toVector().subtract(eloc.toVector()).normalize().multiply(2.0));
                        glide.setDirection(near.toVector().subtract(eloc.toVector()));
                        e.teleport(glide);
                        c.realFollowLastTick = tick;
                    }
                }
            }
        }

        // Keep the name fresh (level may have changed).
        try { e.setCustomName("\u00a7d" + companionDisplayName(c)); } catch (Throwable ignored) {}

        tickLeveling(c, owner, tick);
    }

    // ============================================================
    // ============== FEATURE 5: MOB FORMS & FORM COMBAT ==========
    // ============================================================

    /** Attack styles a mob form can fight with. */
    private enum FormStyle {
        MELEE(true, 1.0),
        ARROW(false, 1.0),
        SMALL_FIREBALL(false, 1.0),
        FIREBALL(false, 1.5),
        WITHER_SKULL(false, 1.25),
        SHULKER_BULLET(false, 1.5),
        SPIT(false, 0.75),
        SNOWBALL(false, 0.5),
        TRIDENT(false, 1.25),
        MAGIC(false, 1.25),
        SONIC_BOOM(false, 2.5),
        BLINK(true, 1.0),
        BOOM(true, 2.0);

        /** Has to close to melee reach before it can land a hit. */
        final boolean melee;
        /** Cooldown multiplier on form-combat.attack-period-seconds. */
        final double cooldownMult;
        FormStyle(boolean melee, double cooldownMult) {
            this.melee = melee; this.cooldownMult = cooldownMult;
        }
    }

    /**
     * Which style a form fights with. Matched on the enum NAME so types
     * that don't exist on older servers (BOGGED, BREEZE, HAPPY_GHAST...)
     * stay compile- and runtime-safe everywhere.
     */
    private static FormStyle formStyle(EntityType t) {
        return switch (t.name()) {
            case "WITHER"                                       -> FormStyle.WITHER_SKULL;
            case "BLAZE"                                        -> FormStyle.SMALL_FIREBALL;
            case "GHAST", "HAPPY_GHAST"                         -> FormStyle.FIREBALL;
            case "SKELETON", "STRAY", "BOGGED", "PILLAGER",
                 "ILLUSIONER"                                   -> FormStyle.ARROW;
            case "SNOW_GOLEM", "SNOWMAN"                        -> FormStyle.SNOWBALL;
            case "LLAMA", "TRADER_LLAMA"                        -> FormStyle.SPIT;
            case "SHULKER"                                      -> FormStyle.SHULKER_BULLET;
            case "DROWNED"                                      -> FormStyle.TRIDENT;
            case "WITCH", "EVOKER", "ALLAY", "VEX", "BREEZE"    -> FormStyle.MAGIC;
            case "WARDEN"                                       -> FormStyle.SONIC_BOOM;
            case "ENDERMAN"                                     -> FormStyle.BLINK;
            case "CREEPER"                                      -> FormStyle.BOOM;
            default                                             -> FormStyle.MELEE;
        };
    }

    /** Human-readable style name for GUI lore + morph messages. */
    private static String styleLabel(FormStyle s) {
        return switch (s) {
            case MELEE          -> "melee";
            case ARROW          -> "arrows";
            case SMALL_FIREBALL -> "fire bolts";
            case FIREBALL       -> "fireballs";
            case WITHER_SKULL   -> "wither skulls";
            case SHULKER_BULLET -> "shulker bullets";
            case SPIT           -> "spit";
            case SNOWBALL       -> "snowballs";
            case TRIDENT        -> "tridents";
            case MAGIC          -> "magic";
            case SONIC_BOOM     -> "sonic boom";
            case BLINK          -> "blink strikes";
            case BOOM           -> "boom bursts";
        };
    }

    /** Damage per form-combat hit (HP). Fixed — does NOT scale with level, so
     *  her attacks never grow stronger as she levels up. */
    private double formDamage(Companion c) {
        return formDamageBase;
    }

    /** True if this entity UUID is some player's live mob-form companion. */
    private boolean isCompanionEntity(UUID id) {
        if (id == null) return false;
        if (navIds.contains(id)) return true; // her invisible navigator mob
        for (Companion c : companions.values()) {
            if (id.equals(c.realEntityId)) return true;
        }
        for (List<Companion> list : extras.values()) {     // helper mob-forms too
            for (Companion c : list) if (id.equals(c.realEntityId)) return true;
        }
        return false;
    }

    /** Every real entity (mob-form bodies + navigators) the owner currently owns —
     *  so the "one per owner" stray-cleanup never deletes a live helper. */
    private java.util.Set<UUID> protectedCompanionIds(UUID owner) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Companion c : ownerCompanions(owner)) {
            if (c.realEntityId != null) ids.add(c.realEntityId);
            if (c.navMob != null) {
                try { ids.add(c.navMob.getUniqueId()); } catch (Throwable ignored) {}
            }
        }
        return ids;
    }

    /** Despawn a companion via whichever render path it's using. */
    private void despawnCompanion(Companion c) {
        if (c.realEntity) despawnRealEntity(c); else despawnEntity(c);
    }

    /** Companion behind an attack (direct hit or its projectile), or null. */
    private Companion companionByAttacker(Entity attacker) {
        if (attacker == null) return null;
        Entity src = attacker;
        if (src instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Entity shooter) {
            src = shooter;
        }
        for (Companion c : companions.values()) {
            if (src.getUniqueId().equals(c.realEntityId)) return c;
        }
        return null;
    }

    /**
     * Per-tick combat for a mob-form companion: pick a hostile target and
     * fight it in this form's own style. Returns true while engaged so the
     * follow loop stops yanking her back to heel mid-fight.
     */
    private boolean tickFormCombat(Companion c, Entity e, Player owner, long tick) {
        if (!formCombatEnabled) return false;
        if (!(e instanceof LivingEntity self)) return false;
        LivingEntity target = pickFormTarget(c, self, owner);
        if (target == null) return false;

        FormStyle style = formStyle(e.getType());
        double dist = self.getLocation().distance(target.getLocation());
        // While ridden, the rider drives movement — skip native AI nav so it
        // doesn't fight the steering (the scripted attack still fires below).
        if (!c.mounted && e instanceof Mob mob) {
            // Point the form's own vanilla AI at the hostile too, so wither
            // heads / zombie melee / warden boom engage natively alongside
            // the scripted style. The per-tick watchdog keeps this from
            // ever being a non-hostile. Warden needs its anger primed —
            // its brain only attacks the entity it's angriest at.
            try {
                LivingEntity vt = mob.getTarget();
                if (vt == null || !vt.getUniqueId().equals(target.getUniqueId())) {
                    mob.setTarget(target);
                }
            } catch (Throwable ignored) {}
            if (mob instanceof org.bukkit.entity.Warden wd) {
                try { wd.setAnger(target, 120); } catch (Throwable ignored) {}
            }
            if (style.melee && dist > 2.4 && c.mode != BehaviorMode.STAY) {
                // Close in. Speed ability makes her lunge faster, like the
                // real-entity follow path.
                double speed = 1.1;
                if (abilitySpeed && levelingEnabled) {
                    speed = Math.min(1.9, 1.1 + (c.level - 1) * 0.08);
                }
                try { mob.getPathfinder().moveTo(target, speed); } catch (Throwable ignored) {}
            } else {
                try { mob.lookAt(target); } catch (Throwable ignored) {} // Paper-only nicety
            }
        }
        if (tick >= c.nextFormAttackTick && performFormAttack(c, self, target, style)) {
            c.nextFormAttackTick = tick
                    + Math.max(5L, (long) (formAttackPeriodTicks * style.cooldownMult));
        }
        return true;
    }

    /**
     * Fight an explicitly ORDERED target (a /kc kill / hunt-picker target, or
     * a player who hit the owner) in this form's own style. Unlike
     * {@link #tickFormCombat} this skips the hostile-only filter entirely, so
     * a creeper, sheep-form, etc. will actually chase and damage a passive
     * animal or player she's been told to kill.
     */
    private void tickKillCombat(Companion c, Entity e, Player owner, long tick, LivingEntity target) {
        if (!(e instanceof LivingEntity self)) return;
        if (target == null || target.isDead() || !target.isValid()) return;
        FormStyle style = formStyle(e.getType());
        double dist = self.getLocation().distance(target.getLocation());
        // While ridden, the rider drives movement — skip native AI nav so it
        // doesn't fight the steering. The scripted attack below still fires.
        if (!c.mounted && e instanceof Mob mob) {
            try {
                LivingEntity vt = mob.getTarget();
                if (vt == null || !vt.getUniqueId().equals(target.getUniqueId())) {
                    mob.setTarget(target);
                }
            } catch (Throwable ignored) {}
            if (mob instanceof org.bukkit.entity.Warden wd) {
                try { wd.setAnger(target, 120); } catch (Throwable ignored) {}
            }
            if (style.melee && dist > 2.4 && c.mode != BehaviorMode.STAY) {
                double speed = 1.1;
                if (abilitySpeed && levelingEnabled) {
                    speed = Math.min(1.9, 1.1 + (c.level - 1) * 0.08);
                }
                try { mob.getPathfinder().moveTo(target, speed); } catch (Throwable ignored) {}
            } else {
                try { mob.lookAt(target); } catch (Throwable ignored) {}
            }
        }
        if (tick >= c.nextFormAttackTick && performFormAttack(c, self, target, style)) {
            c.nextFormAttackTick = tick
                    + Math.max(5L, (long) (formAttackPeriodTicks * style.cooldownMult));
        }
    }

    /**
     * Target selection for form combat: HOSTILE mobs only \u2014 never players,
     * pets, villagers or passive animals, and never another owner's
     * companion. Prefers the mob that just attacked the owner (assist
     * memory), else the nearest hostile that she or the owner can see.
     */
    private LivingEntity pickFormTarget(Companion c, LivingEntity self, Player owner) {
        if (assistOwnerEnabled && c.assistTargetId != null
                && behaviorTickCount < c.assistTargetUntil) {
            Entity ent = Bukkit.getEntity(c.assistTargetId);
            if (ent instanceof LivingEntity le && !le.isDead() && le.isValid()
                    && le.getWorld() == self.getWorld()
                    && le.getLocation().distance(self.getLocation()) <= formCombatRange + 8
                    && isHostile(le) && !isCompanionEntity(le.getUniqueId())) {
                return le;
            }
            c.assistTargetId = null;
            c.assistTargetUntil = 0;
        }
        // Throttle the expensive getNearbyEntities sweep: a mob-form companion
        // ticks every behavior tick, but re-acquiring a target that often is
        // wasteful and scales as O(companions × nearby entities). Between scans
        // keep fighting the cached target as long as it's still alive, hostile,
        // in range and in the same world; only sweep again when the cache is
        // empty/invalid or the throttle window has elapsed.
        if (c.formTargetId != null && behaviorTickCount < c.nextFormTargetScanTick) {
            Entity cached = Bukkit.getEntity(c.formTargetId);
            if (cached instanceof LivingEntity le && !le.isDead() && le.isValid()
                    && le.getWorld() == self.getWorld()
                    && le.getLocation().distanceSquared(self.getLocation())
                            <= formCombatRange * formCombatRange
                    && isHostile(le) && !isCompanionEntity(le.getUniqueId())) {
                return le;
            }
            c.formTargetId = null;
        }
        c.nextFormTargetScanTick = behaviorTickCount + FORM_TARGET_SCAN_INTERVAL;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity ent : self.getNearbyEntities(formCombatRange, formCombatRange, formCombatRange)) {
            if (!(ent instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (!isHostile(le)) continue;
            if (isCompanionEntity(le.getUniqueId())) continue;
            // No sensing mobs through walls: engage only what SHE can see
            // or what the OWNER can see (she "borrows" your eyes) — the
            // same combat-require-line-of-sight rule as the fake-player.
            if (combatRequireLineOfSight) {
                boolean visible;
                try {
                    visible = self.hasLineOfSight(le)
                            || (owner != null && owner.hasLineOfSight(le));
                } catch (Throwable t) {
                    visible = true;
                }
                if (!visible) continue;
            }
            double d = le.getLocation().distanceSquared(self.getLocation());
            if (d < bestSq) { bestSq = d; best = le; }
        }
        c.formTargetId = (best == null) ? null : best.getUniqueId();
        return best;
    }

    /**
     * Land one attack in the form's style. Returns false when the attack
     * couldn't fire yet (melee still out of reach) so no cooldown is
     * consumed. Every style is wrapped so a missing class/method on some
     * server build degrades to a plain damage zap instead of breaking.
     */
    private boolean performFormAttack(Companion c, LivingEntity self,
                                      LivingEntity target, FormStyle style) {
        double dmg = formDamage(c);
        double dist = self.getLocation().distance(target.getLocation());
        World w = self.getWorld();
        try {
            switch (style) {
                case MELEE -> {
                    if (dist > 2.6) return false;
                    target.damage(dmg, self);
                    try { self.swingMainHand(); } catch (Throwable ignored) {}
                    spawnFormParticle(w, target.getLocation().add(0, 1.0, 0), "SWEEP_ATTACK", 1);
                    playFormSound(self, "entity.player.attack.sweep", 0.7f, 1.1f);
                }
                case BLINK -> {
                    // Enderman style: blink to the target, then slash.
                    if (dist > 2.6) {
                        spawnFormParticle(w, self.getLocation().add(0, 1.0, 0), "PORTAL", 20);
                        self.teleport(target.getLocation().add(
                                target.getLocation().getDirection().multiply(-1.2)));
                        playFormSound(self, "entity.enderman.teleport", 0.8f, 1.0f);
                    }
                    target.damage(dmg, self);
                    try { self.swingMainHand(); } catch (Throwable ignored) {}
                    spawnFormParticle(w, target.getLocation().add(0, 1.0, 0), "PORTAL", 10);
                }
                case BOOM -> {
                    if (dist > 3.2) return false;
                    // Creeper style: a harmless "boom" \u2014 zero block damage and
                    // she survives. Always hits the focused target (so an
                    // ordered sheep/player dies too), plus AoE to hostiles.
                    target.damage(dmg, self);
                    for (Entity ent : self.getNearbyEntities(3.5, 3.5, 3.5)) {
                        if (!(ent instanceof LivingEntity le) || le.isDead()) continue;
                        if (le.getUniqueId().equals(target.getUniqueId())) continue; // already hit
                        if (!isHostile(le) || isCompanionEntity(le.getUniqueId())) continue;
                        le.damage(dmg, self);
                    }
                    spawnFormParticle(w, self.getLocation().add(0, 0.8, 0), "EXPLOSION", 1);
                    spawnFormParticle(w, self.getLocation().add(0, 0.8, 0), "EXPLOSION_LARGE", 1);
                    playFormSound(self, "entity.generic.explode", 0.8f, 1.2f);
                }
                case SONIC_BOOM -> {
                    // Warden style: instant ray, heavier hit, slow cooldown.
                    Location from = self.getEyeLocation();
                    Vector step = target.getEyeLocation().toVector().subtract(from.toVector());
                    double len = Math.max(1.0, step.length());
                    step.normalize();
                    for (double d = 1.0; d < len; d += 1.0) {
                        spawnFormParticle(w, from.clone().add(step.clone().multiply(d)), "SONIC_BOOM", 1);
                    }
                    target.damage(dmg * 1.5, self);
                    playFormSound(self, "entity.warden.sonic_boom", 1.0f, 1.0f);
                }
                case MAGIC -> {
                    // Witch/evoker/vex style: spell zap. Both particle names
                    // tried \u2014 WITCH (1.20.5+) vs SPELL_WITCH (older).
                    spawnFormParticle(w, target.getLocation().add(0, 1.0, 0), "WITCH", 15);
                    spawnFormParticle(w, target.getLocation().add(0, 1.0, 0), "SPELL_WITCH", 15);
                    target.damage(dmg, self);
                    playFormSound(self, "entity.evoker.cast_spell", 0.8f, 1.2f);
                }
                case ARROW -> {
                    Arrow a = self.launchProjectile(Arrow.class, aimAt(self, target, 1.7, true));
                    try { a.setDamage(Math.max(2.0, dmg / 2.0)); } catch (Throwable ignored) {}
                    try {
                        a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                    } catch (Throwable ignored) {}
                    playFormSound(self, "entity.skeleton.shoot", 0.8f, 1.0f);
                }
                case SMALL_FIREBALL -> {
                    org.bukkit.entity.SmallFireball f = self.launchProjectile(
                            org.bukkit.entity.SmallFireball.class, aimAt(self, target, 1.4, false));
                    try { f.setIsIncendiary(false); f.setYield(0f); } catch (Throwable ignored) {}
                    playFormSound(self, "entity.blaze.shoot", 0.8f, 1.0f);
                }
                case FIREBALL -> {
                    org.bukkit.entity.Fireball f = self.launchProjectile(
                            org.bukkit.entity.Fireball.class, aimAt(self, target, 1.2, false));
                    try { f.setIsIncendiary(false); f.setYield(0f); } catch (Throwable ignored) {}
                    playFormSound(self, "entity.ghast.shoot", 0.9f, 1.0f);
                }
                case WITHER_SKULL -> {
                    org.bukkit.entity.WitherSkull s = self.launchProjectile(
                            org.bukkit.entity.WitherSkull.class, aimAt(self, target, 1.4, false));
                    try { s.setCharged(false); s.setYield(0f); } catch (Throwable ignored) {}
                    playFormSound(self, "entity.wither.shoot", 0.8f, 1.0f);
                }
                case SHULKER_BULLET -> {
                    org.bukkit.entity.ShulkerBullet b =
                            self.launchProjectile(org.bukkit.entity.ShulkerBullet.class);
                    try { b.setTarget(target); } catch (Throwable ignored) {}
                    playFormSound(self, "entity.shulker.shoot", 0.8f, 1.0f);
                }
                case SPIT -> {
                    self.launchProjectile(org.bukkit.entity.LlamaSpit.class,
                            aimAt(self, target, 1.6, true));
                    playFormSound(self, "entity.llama.spit", 0.9f, 1.0f);
                }
                case SNOWBALL -> {
                    self.launchProjectile(org.bukkit.entity.Snowball.class,
                            aimAt(self, target, 1.6, true));
                    playFormSound(self, "entity.snow_golem.shoot", 0.8f, 1.0f);
                }
                case TRIDENT -> {
                    org.bukkit.entity.Trident tr = self.launchProjectile(
                            org.bukkit.entity.Trident.class, aimAt(self, target, 1.6, true));
                    try {
                        tr.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                    } catch (Throwable ignored) {}
                    playFormSound(self, "entity.drowned.shoot", 0.8f, 1.0f);
                }
            }
            return true;
        } catch (Throwable t) {
            // Style unavailable on this build \u2014 plain zap so she still fights.
            try {
                target.damage(dmg, self);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** Aim vector eye-to-eye, with a small lob for gravity projectiles. */
    private static Vector aimAt(LivingEntity self, LivingEntity target,
                                double speed, boolean arc) {
        Vector v = target.getEyeLocation().toVector()
                .subtract(self.getEyeLocation().toVector());
        double dist = Math.max(0.1, v.length());
        v.normalize().multiply(speed);
        if (arc) v.setY(v.getY() + dist * 0.045);
        return v;
    }

    /** Version-safe particle spawn by enum NAME (names differ across versions). */
    private static void spawnFormParticle(World w, Location at, String name, int count) {
        try {
            w.spawnParticle(org.bukkit.Particle.valueOf(name), at, count, 0.25, 0.25, 0.25, 0.0);
        } catch (Throwable ignored) {}
    }

    /** Namespaced-string sound at the companion (no Sound enum \u2014 version-safe). */
    private static void playFormSound(LivingEntity self, String sound, float vol, float pitch) {
        try {
            self.getWorld().playSound(self.getLocation(), sound, vol, pitch);
        } catch (Throwable ignored) {}
    }

    /**
     * FEATURE 5 guard rail: whatever the form, the companion may ONLY ever
     * hurt hostile mobs. Cancels any companion-sourced damage \u2014 direct,
     * projectile, or splash (wither skulls, fireballs...) \u2014 against
     * players, pets, villagers, passive animals and other companions.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCompanionDealsDamage(EntityDamageByEntityEvent event) {
        Companion c = companionByAttacker(event.getDamager());
        if (c == null) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            event.setCancelled(true);
            return;
        }
        // An ORDERED kill (/kc kill, hunt picker, auto-defend) is always
        // allowed — even against a passive animal or a player — otherwise
        // mob forms could never damage a hunted sheep, while the human form
        // (whose hits aren't a real-entity source) could. Never a companion.
        if (c.killTargets.contains(victim.getUniqueId())
                && !isCompanionEntity(victim.getUniqueId())) {
            return;
        }
        if (!isHostile(victim) || isCompanionEntity(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Companion-sourced explosions (wither skulls, spawn boom) never break blocks. */
    @EventHandler(ignoreCancelled = true)
    public void onCompanionExplodes(EntityExplodeEvent event) {
        if (companionByAttacker(event.getEntity()) != null) event.blockList().clear();
    }

    /** Block-griefing form AI (wither break, enderman pickup...) is vetoed. */
    @EventHandler(ignoreCancelled = true)
    public void onCompanionChangesBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (isCompanionEntity(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    /**
     * Snowballs / llama spit do (next to) no vanilla damage \u2014 top them up
     * on impact so those styles actually work. Hostile victims only.
     */
    @EventHandler
    public void onCompanionProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Companion c = companionByAttacker(event.getEntity());
        if (c == null) return;
        if (!(event.getHitEntity() instanceof LivingEntity victim)) return;
        boolean ordered = c.killTargets.contains(victim.getUniqueId())
                && !isCompanionEntity(victim.getUniqueId());
        if (!ordered && (!isHostile(victim) || isCompanionEntity(victim.getUniqueId()))) return;
        String type = event.getEntityType().name();
        if (!type.equals("SNOWBALL") && !type.equals("LLAMA_SPIT")) return;
        Entity shooter = event.getEntity().getShooter() instanceof Entity s ? s : null;
        try { victim.damage(formDamage(c), shooter); } catch (Throwable ignored) {}
    }

    /**
     * The warden's passive darkness aura comes from vanilla brain code we
     * can't switch off — suppress the effect instead whenever it lands on
     * a player near a companion warden, so she never blinds her owner.
     */
    @EventHandler(ignoreCancelled = true)
    public void onWardenDarkness(org.bukkit.event.entity.EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!"WARDEN".equals(event.getCause().name())) return;
        for (Companion c : companions.values()) {
            Entity e = liveRealEntity(c);
            if (e == null || !"WARDEN".equals(e.getType().name())) continue;
            if (e.getWorld() == p.getWorld()
                    && e.getLocation().distanceSquared(p.getLocation()) < 32 * 32) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * True for a real-entity companion mob that no live companion record
     * owns — left behind by a crash, or saved into a chunk by builds where
     * the entity was still chunk-persistent. Matched by the PDC marker;
     * pre-marker orphans by their unmistakable trait combo (a silent,
     * invulnerable, custom-named, never-despawning Mob).
     */
    private boolean isOrphanCompanion(Entity e) {
        if (isCompanionEntity(e.getUniqueId())) return false;
        try {
            if (e.getPersistentDataContainer().has(companionMarkerKey(),
                    org.bukkit.persistence.PersistentDataType.STRING)) return true;
        } catch (Throwable ignored) {}
        if (!(e instanceof Mob m)) return false;
        try {
            String name = m.getCustomName();
            return name != null && name.startsWith("§d")
                    && m.isSilent() && m.isInvulnerable()
                    && !m.getRemoveWhenFarAway();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Remove orphaned companion mobs from {@code entities}; returns count. */
    private int sweepOrphanCompanions(Iterable<? extends Entity> entities) {
        int removed = 0;
        for (Entity e : entities) {
            if (!isOrphanCompanion(e)) continue;
            try { e.remove(); removed++; } catch (Throwable ignored) {}
        }
        return removed;
    }

    /** Orphan sweep for chunks whose entities load after startup. */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        sweepOrphanCompanions(event.getEntities());
    }

    // ============================================================
    // ============== FEATURE 2: LEVELING & ABILITIES ============
    // ============================================================

    /** Display name shown on the entity / in messages: name + level badge. */
    private String companionDisplayName(Companion c) {
        if (!levelingEnabled) return c.name;
        return c.name + " \u00a77[Lv " + c.level + "]";
    }

    /** XP required to advance FROM level n to n+1. */
    private int xpForLevel(int n) {
        return xpBase + xpPerLevel * Math.max(0, n - 1);
    }

    /**
     * Award XP and process any level-ups. Runs on the main thread. Safe to
     * call for both render paths. No-op when leveling is disabled or already
     * at max level.
     */
    private void awardXp(Companion c, Player owner, int amount) {
        if (!levelingEnabled || amount <= 0) return;
        if (c.level >= maxLevel) return;
        c.xp += amount;
        boolean leveled = false;
        while (c.level < maxLevel && c.xp >= xpForLevel(c.level)) {
            c.xp -= xpForLevel(c.level);
            c.level++;
            leveled = true;
            onLevelUp(c, owner);
        }
        if (c.level >= maxLevel) c.xp = 0;
        if (leveled) saveMemory(c);
    }

    /** Announce a level-up (title + sound) and refresh abilities/name. */
    private void onLevelUp(Companion c, Player owner) {
        // Title + subtitle. Stable Bukkit API.
        try {
            owner.sendTitle("\u00a7d\u2726 " + c.name + " \u00a7dreached Lv " + c.level + " \u2726",
                    "\u00a77she hits harder now ~ (+\u00bd\u2764 attack)", 10, 50, 20);
        } catch (Throwable ignored) {}
        // Sound by namespaced string \u2014 NO Sound enum (version-safe).
        try {
            owner.playSound(owner.getLocation(), "entity.player.levelup", 1.0f, 1.4f);
        } catch (Throwable ignored) {}
        owner.sendMessage("\u00a7d(\u2727) \u00a7f" + c.name + "\u00a7d leveled up to \u00a7fLv " + c.level + "\u00a7d \u2728");
        if (abilityHealAura && c.level == healAuraUnlockLevel) {
            owner.sendMessage("\u00a7d(\u2727) \u00a7f" + c.name + "\u00a7d unlocked the \u00a7dheal aura\u00a7d! \u2661");
        }
        // Refresh the name tag so the new level shows. Real-entity → update
        // traits in place; fake-player → respawn (the only way to rebroadcast
        // the NMS name tag, mirroring how /kc rename does it).
        if (c.realEntity) {
            Entity e = liveRealEntity(c);
            if (e != null) applyRealEntityTraits(e, c);
        } else if (showNameTag && c.entity != null && !c.entity.isDead() && owner != null) {
            despawnEntity(c);
            spawnEntity(owner, c);
        }
    }

    /**
     * Passive XP + heal-aura pulses. Called every movement tick for both
     * render paths; everything inside is throttled.
     */
    private void tickLeveling(Companion c, Player owner, long tick) {
        if (!levelingEnabled) return;

        // Slow passive XP while summoned.
        if (passiveXpAmount > 0 && c.level < maxLevel
                && tick - c.lastPassiveXpTick >= passiveXpPeriodTicks) {
            c.lastPassiveXpTick = tick;
            awardXp(c, owner, passiveXpAmount);
        }

        // Heal / regen aura to the owner at higher levels.
        if (abilityHealAura && c.level >= healAuraUnlockLevel
                && tick - c.lastHealAuraTick >= healAuraPeriodTicks) {
            c.lastHealAuraTick = tick;
            applyHealAura(c, owner);
        }
    }

    /** Gentle regen aura: top up the owner's HP if they're near + hurt. */
    private void applyHealAura(Companion c, Player owner) {
        Location at = companionLoc(c);
        if (at == null) return;
        if (owner.getWorld() != at.getWorld()) return;
        if (owner.getLocation().distance(at) > healAuraRange) return;
        // Never heal a dead / respawning owner. Calling setHealth(>0) while the
        // client is on the death screen revives them server-side only, leaving
        // them stuck there: the Respawn button does nothing and a force-quit is
        // the only recovery. This is exactly why a wither kill became
        // un-respawnable — the aura tops the owner up so steadily that only a
        // burst the pulse can't out-heal (the wither) actually lands a death,
        // and the very next pulse then revived the corpse server-side.
        if (owner.isDead() || owner.getHealth() <= 0.0) return;
        double max;
        try { max = owner.getMaxHealth(); } catch (Throwable t) { max = 20.0; }
        double hp = owner.getHealth();
        if (hp >= max) return;
        owner.setHealth(Math.min(max, hp + healAuraAmount));
        // Cute feedback \u2014 heart particles + a soft chime. Particle enum is
        // stable across 1.21.x; sound is a namespaced string (no Sound enum).
        try {
            owner.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                    owner.getLocation().add(0, 1.2, 0), 3, 0.3, 0.3, 0.3, 0.0);
        } catch (Throwable ignored) {}
        try {
            owner.playSound(owner.getLocation(), "entity.player.levelup", 0.2f, 2.0f);
        } catch (Throwable ignored) {}
    }

    /** Current world-location of either render path, or null if not live. */
    private Location companionLoc(Companion c) {
        if (c == null) return null;
        if (c.realEntity) {
            Entity e = liveRealEntity(c);
            return e == null ? null : e.getLocation();
        }
        if (c.entity == null || c.entity.isDead()) return null;
        return c.entity.getLocation();
    }

    /**
     * Award combat XP when an owner kills a mob near their companion.
     * Also nudges the companion's effective movement speed for the
     * real-entity path (FEATURE 2: movement speed up at higher levels).
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!levelingEnabled || xpPerKill <= 0) return;
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) {
            // FEATURE 5: mob-on-mob kills have no Player killer — when the
            // mob-form companion landed the killing blow herself, credit
            // the same combat XP.
            if (dead.getLastDamageCause() instanceof EntityDamageByEntityEvent ev) {
                Companion fc = companionByAttacker(ev.getDamager());
                if (fc != null) {
                    Player owner = Bukkit.getPlayer(fc.owner);
                    if (owner != null && owner.isOnline()) awardXp(fc, owner, xpPerKill);
                }
            }
            return;
        }
        Companion c = companions.get(killer.getUniqueId());
        if (c == null || !isCompanionLive(c)) return;
        // Only count kills near the companion so it feels like teamwork.
        Location cl = companionLoc(c);
        if (cl == null) return;
        if (killer.getWorld() != cl.getWorld()) return;
        if (killer.getLocation().distance(cl) > xpKillRadius) return;
        awardXp(c, killer, xpPerKill);
    }

    /**
     * FEATURE 2 (combat assist) + lockdown: stop mobs from ever targeting
     * the real-entity companion so it's a non-combatant ally and never
     * gets dragged into fights it can't win.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMobTargetCompanion(EntityTargetEvent event) {
        // FEATURE 5: the companion's own vanilla AI (wither, zombie...
        // forms) may only ever go after hostile mobs — never players,
        // pets or passive animals. (target == null is "forget target";
        // never cancel that.)
        if (isCompanionEntity(event.getEntity().getUniqueId())) {
            Entity tgt = event.getTarget();
            if (tgt != null && (!(tgt instanceof LivingEntity tle)
                    || !isHostile(tle)
                    || isCompanionEntity(tle.getUniqueId()))) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getTarget() == null) return;
        UUID tid = event.getTarget().getUniqueId();
        for (Companion c : companions.values()) {
            if (c.realEntity && tid.equals(c.realEntityId)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ============================================================
    // ============== FEATURE 3: COSMETIC SKINS GUI ===============
    // ============================================================

    private static final String SKINS_GUI_PREFIX = "\u00a7d\u273f Appearances \u273f";

    /** Inner 4\u00d77 grid of a 54-slot GUI = entries shown per page. */
    private static final int FORMS_PER_PAGE = 28;

    private void openSkinsGui(Player p, Companion c) {
        openSkinsGui(p, c, 0);
    }

    /**
     * Appearances menu (FEATURE 3 + 5), paginated: skin files first (Java
     * owners only \u2014 Geyser can't render the fake-player), then EVERY mob
     * form. Picking a mob morphs her into a real entity that fights
     * hostiles in that mob's style; picking a skin returns her human form.
     */
    private void openSkinsGui(Player p, Companion c, int page) {
        List<ItemStack> entries = new ArrayList<>();
        boolean humanAllowed = !isBedrockPlayer(p) && !bedrockRealEntity;
        if (humanAllowed) {
            String resolved = resolveSkinName(c);
            for (String s : listValidSkins()) {
                entries.add(skinHeadButton(s, !c.realEntity && s.equals(resolved)));
            }
        }
        String current = c.realEntity
                ? (c.bedrockType != null ? c.bedrockType : bedrockCompanionType).toUpperCase(Locale.ROOT)
                : null;
        for (String form : mobForms) {
            entries.add(formButton(form, form.equalsIgnoreCase(current)));
        }

        int pages = Math.max(1, (entries.size() + FORMS_PER_PAGE - 1) / FORMS_PER_PAGE);
        page = Math.max(0, Math.min(pages - 1, page));

        Inventory gui = Bukkit.createInventory(null, 54, SKINS_GUI_PREFIX);
        decorateShimmerBorder(gui, behaviorTickCount);
        int idx = page * FORMS_PER_PAGE;
        for (int row = 1; row <= 4 && idx < entries.size(); row++) {
            for (int col = 1; col <= 7 && idx < entries.size(); col++) {
                gui.setItem(row * 9 + col, entries.get(idx++));
            }
        }
        if (entries.isEmpty()) {
            gui.setItem(22, plainButton(Material.BARRIER, "\u00a7cnothing here",
                    "drop a .png/.json into skins/"));
        }
        if (page > 0) gui.setItem(48, plainButton(Material.ARROW,
                "\u00a7d\u2190 previous page", "page " + page + " of " + pages));
        gui.setItem(49, plainButton(Material.BOOK,
                "\u00a7dpage " + (page + 1) + "\u00a77/\u00a7d" + pages,
                "she can become any mob ~"));
        if (page < pages - 1) gui.setItem(50, plainButton(Material.ARROW,
                "\u00a7dnext page \u2192", "page " + (page + 2) + " of " + pages));

        p.openInventory(gui);
        skinsGuiViewers.put(p.getUniqueId(), c.owner);
        skinsGuiPage.put(p.getUniqueId(), page);

        // Animate the shimmer border while the GUI is open. The task
        // self-cancels once the player closes it (no longer a viewer).
        final UUID viewer = p.getUniqueId();
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!skinsGuiViewers.containsKey(viewer)) { task.cancel(); return; }
            Player pl = Bukkit.getPlayer(viewer);
            if (pl == null || !pl.isOnline()) { task.cancel(); return; }
            Inventory top = pl.getOpenInventory().getTopInventory();
            if (top == null || top.getSize() != 54) { task.cancel(); return; }
            decorateShimmerBorder(top, behaviorTickCount);
        }, 6L, 6L);
    }

    /** Players currently viewing the skins GUI \u2192 companion owner UUID. */
    private final Map<UUID, UUID> skinsGuiViewers = new HashMap<>();
    /** Current page per skins-GUI viewer. */
    private final Map<UUID, Integer> skinsGuiPage = new HashMap<>();

    private ItemStack formButton(String form, boolean active) {
        // Spawn egg icon when this version has one; generic egg otherwise
        // (golems, snow golem, the wither... have no egg).
        Material icon = Material.matchMaterial(form + "_SPAWN_EGG");
        ItemStack it = new ItemStack(icon != null ? icon : Material.EGG);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((active ? "\u00a7a\u2713 " : "\u00a7d") + prettyForm(form));
            List<String> lore = new ArrayList<>();
            lore.add(active ? "\u00a77current form" : "\u00a77click to become this");
            EntityType t = parseLivingEntityType(form);
            if (formCombatEnabled && t != null) {
                lore.add("\u00a78fights hostiles: " + styleLabel(formStyle(t)));
            }
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack skinHeadButton(String skinName, boolean active) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((active ? "\u00a7a\u2713 " : "\u00a7d") + skinName);
            meta.setLore(List.of(active ? "\u00a77in use" : "\u00a77click to wear this skin"));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String prettyForm(String form) {
        String s = form.toLowerCase(Locale.ROOT).replace('_', ' ');
        return s.isEmpty() ? form : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Animated shimmer border \u2014 phase shifts with the tick for a twinkle. */
    private void decorateShimmerBorder(Inventory gui, long tick) {
        Material[] palette = {
                Material.PINK_STAINED_GLASS_PANE,
                Material.MAGENTA_STAINED_GLASS_PANE,
                Material.PURPLE_STAINED_GLASS_PANE,
                Material.LIGHT_BLUE_STAINED_GLASS_PANE
        };
        int size = gui.getSize();
        int phase = (int) (tick / 4);
        for (int i = 0; i < size; i++) {
            int row = i / 9, col = i % 9;
            boolean border = row == 0 || row == (size / 9) - 1 || col == 0 || col == 8;
            if (!border) continue;
            // Never clobber a real button: only (re)paint empty border slots.
            ItemStack cur = gui.getItem(i);
            if (cur != null && cur.getType() != Material.AIR
                    && !isShimmerPane(cur.getType())) continue;
            Material m = palette[(i + phase) % palette.length];
            ItemStack pane = new ItemStack(m);
            ItemMeta meta = pane.getItemMeta();
            if (meta != null) { meta.setDisplayName(" "); pane.setItemMeta(meta); }
            gui.setItem(i, pane);
        }
    }

    private static boolean isShimmerPane(Material m) {
        return m == Material.PINK_STAINED_GLASS_PANE
                || m == Material.MAGENTA_STAINED_GLASS_PANE
                || m == Material.PURPLE_STAINED_GLASS_PANE
                || m == Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    }

    @EventHandler
    public void onSkinsGuiClick(InventoryClickEvent event) {
        UUID ownerId = skinsGuiViewers.get(event.getWhoClicked().getUniqueId());
        if (ownerId == null) return;
        String title = event.getView().getTitle();
        if (title == null || !title.equals(SKINS_GUI_PREFIX)) return;
        event.setCancelled(true);
        Companion c = companions.get(ownerId);
        if (c == null || !(event.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String label = ChatColor.stripColor(meta.getDisplayName()).replace("\u2713 ", "").trim();
        if (label.isEmpty() || label.equals("nothing here")) return;
        Material mat = clicked.getType();

        // Page navigation. Re-open on the next tick \u2014 mutating the open
        // inventory from inside its own click event is unreliable.
        if (mat == Material.ARROW) {
            int page = skinsGuiPage.getOrDefault(p.getUniqueId(), 0);
            int next = label.contains("previous") ? page - 1 : page + 1;
            Bukkit.getScheduler().runTask(this, () -> openSkinsGui(p, c, next));
            return;
        }
        if (mat == Material.BOOK) return;

        if (mat == Material.PLAYER_HEAD) {
            // Skin pick \u2014 also returns her to human form if she's a mob.
            List<String> skins = listValidSkins();
            String matched = null;
            for (String s : skins) if (s.equalsIgnoreCase(label)) { matched = s; break; }
            if (matched == null) return;
            c.skin = matched;
            if (c.realEntity) {
                p.closeInventory();
                revertToHumanForm(p, c);
                return;
            }
            if (c.entity != null && !c.entity.isDead()) {
                despawnEntity(c);
                spawnEntity(p, c);
            }
            saveMemory(c);
            p.sendMessage("\u00a7d(\u2727) skin set to \u00a7f" + matched);
            p.closeInventory();
            return;
        }

        // Mob form pick (spawn egg / egg button) \u2014 FEATURE 5.
        if (mat == Material.EGG || mat.name().endsWith("_SPAWN_EGG")) {
            EntityType t = parseLivingEntityType(label.replace(' ', '_'));
            if (t == null) return;
            p.closeInventory();
            applyMobForm(p, c, t);
        }
    }

    @EventHandler
    public void onSkinsGuiClose(InventoryCloseEvent event) {
        skinsGuiViewers.remove(event.getPlayer().getUniqueId());
        skinsGuiPage.remove(event.getPlayer().getUniqueId());
    }

    // ============================================================
    // ============== FEATURE 4: MOUNT MODE =======================
    // ============================================================

    private void doMount(Player p) {
        if (!allowMount) {
            p.sendMessage("\u00a7d(\u2727) mount mode is disabled on this server ~");
            return;
        }
        Companion c = companions.get(p.getUniqueId());
        if (c == null || !isCompanionLive(c)) {
            p.sendMessage("\u00a7d(\u2727) summon your companion first ~");
            return;
        }
        if (!c.realEntity) {
            p.sendMessage("\u00a7d(\u2727) riding only works with the \u00a7freal-entity\u00a7d companion "
                    + "(Bedrock / \u00a77bedrock-real-entity: true\u00a7d). The fake-player can't be ridden ~");
            return;
        }
        Entity e = liveRealEntity(c);
        if (e == null) {
            p.sendMessage("\u00a7d(\u2727) couldn't find your companion to mount ~");
            return;
        }
        if (c.mounted || (e.getPassengers() != null && e.getPassengers().contains(p))) {
            try { e.eject(); } catch (Throwable ignored) {}
            endRide(c, e);
            p.sendMessage("\u00a7d(\u2727) hopped off ~");
            return;
        }
        try {
            e.addPassenger(p);
            c.mounted = true;
            // Hand movement to the rider: freeze her wandering AI (the rider
            // steers via rideTick) \u2014 restored on dismount.
            if (e instanceof Mob mob) { try { mob.setAware(false); } catch (Throwable ignored) {} }
            RideMode mode = rideModeFor(e.getType());
            String how = switch (mode) {
                case FLYER   -> "\u00a77WASD to fly, \u00a7fSpace\u00a77 up / \u00a7fSneak\u00a77 down";
                case SWIMMER -> "\u00a77WASD to swim, \u00a7fSpace\u00a77 up / \u00a7fSneak\u00a77 down in water";
                default      -> "\u00a77WASD to walk, \u00a7fSpace\u00a77 to jump";
            };
            p.sendMessage("\u00a7d(\u2727) hop on! " + how
                    + " \u00a78(/companion mount again to get off)");
            p.sendMessage("\u00a78(\u2727) she still guards \u2014 her kill orders land while you ride ~");
        } catch (Throwable t) {
            p.sendMessage("\u00a7c(\u2727) couldn't ride this companion ~");
        }
    }

    /** Restore a companion's normal movement after a ride ends. */
    private void endRide(Companion c, Entity e) {
        c.mounted = false;
        if (e == null) return;
        try { e.setGravity(true); } catch (Throwable ignored) {}
        if (e instanceof Mob mob) { try { mob.setAware(true); } catch (Throwable ignored) {} }
    }

    // ---- ride steering -------------------------------------------------

    /** How a ridden form moves: on the ground, through the air, or in water. */
    private enum RideMode { LAND, FLYER, SWIMMER }

    private RideMode rideModeFor(EntityType t) {
        switch (t.name()) {
            case "ALLAY": case "BAT": case "BEE": case "BLAZE": case "ENDER_DRAGON":
            case "GHAST": case "HAPPY_GHAST": case "PARROT": case "PHANTOM":
            case "VEX": case "WITHER":
                return RideMode.FLYER;
            case "AXOLOTL": case "COD": case "DOLPHIN": case "DROWNED":
            case "ELDER_GUARDIAN": case "GLOW_SQUID": case "GUARDIAN":
            case "PUFFERFISH": case "SALMON": case "SQUID": case "TADPOLE":
            case "TROPICAL_FISH": case "TURTLE":
                return RideMode.SWIMMER;
            default:
                return RideMode.LAND;
        }
    }

    private boolean isInWater(Entity e) {
        try { return e.isInWater(); }
        catch (Throwable t) {
            try { return e.getLocation().getBlock().getType() == Material.WATER; }
            catch (Throwable t2) { return false; }
        }
    }

    // Read the rider's movement keys via the 1.21.3+ Input API by reflection,
    // so we don't hard-depend on it. Returns {fwd,back,left,right,jump,sneak}
    // or null when the API isn't there.
    private static boolean INPUT_INIT;
    private static java.lang.reflect.Method M_GET_INPUT, M_FWD, M_BACK, M_LEFT, M_RIGHT, M_JUMP, M_SNEAK;

    private boolean[] readInput(Player p) {
        try {
            if (!INPUT_INIT) {
                INPUT_INIT = true;
                try {
                    M_GET_INPUT = Player.class.getMethod("getCurrentInput");
                    Class<?> in = Class.forName("org.bukkit.Input");
                    M_FWD = in.getMethod("isForward");
                    M_BACK = in.getMethod("isBackward");
                    M_LEFT = in.getMethod("isLeft");
                    M_RIGHT = in.getMethod("isRight");
                    M_JUMP = in.getMethod("isJump");
                    M_SNEAK = in.getMethod("isSneak");
                } catch (Throwable t) { M_GET_INPUT = null; }
            }
            if (M_GET_INPUT == null) return null;
            Object input = M_GET_INPUT.invoke(p);
            if (input == null) return null;
            return new boolean[] {
                (Boolean) M_FWD.invoke(input), (Boolean) M_BACK.invoke(input),
                (Boolean) M_LEFT.invoke(input), (Boolean) M_RIGHT.invoke(input),
                (Boolean) M_JUMP.invoke(input), (Boolean) M_SNEAK.invoke(input)
            };
        } catch (Throwable t) { return null; }
    }

    /** 20 Hz: steer every ridden companion from its owner's movement keys. */
    private void rideTick() {
        // Fast early-return: nothing is being ridden when there are no companions.
        if (companions.isEmpty()) return;
        for (Companion c : companions.values()) {
            if (!c.mounted || !c.realEntity) continue;
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner == null || !owner.isOnline()) continue;
            Entity e = liveRealEntity(c);
            if (e == null) { c.mounted = false; continue; }
            boolean riding;
            try { riding = e.getPassengers().contains(owner); } catch (Throwable t) { riding = true; }
            if (!riding) { endRide(c, e); continue; }
            try { tickRideControl(c, e, owner); } catch (Throwable ignored) {}
        }
    }

    private void tickRideControl(Companion c, Entity e, Player owner) {
        RideMode mode = rideModeFor(e.getType());
        // Body faces where the rider looks.
        try { e.setRotation(owner.getLocation().getYaw(), 0f); } catch (Throwable ignored) {}

        boolean[] in = readInput(owner);
        if (in == null) return; // no Input API on this server \u2014 can't steer

        Vector look = owner.getEyeLocation().getDirection();
        Vector fwdH = new Vector(look.getX(), 0, look.getZ());
        if (fwdH.lengthSquared() > 1.0e-6) fwdH.normalize(); else fwdH = new Vector(0, 0, 0);
        Vector rightH = new Vector(-fwdH.getZ(), 0, fwdH.getX());
        double f = (in[0] ? 1 : 0) - (in[1] ? 1 : 0);
        double s = (in[3] ? 1 : 0) - (in[2] ? 1 : 0);
        boolean jump = in[4], sneak = in[5];

        boolean flying = (mode == RideMode.FLYER)
                || (mode == RideMode.SWIMMER && isInWater(e));
        if (flying) {
            try { e.setGravity(false); } catch (Throwable ignored) {}
            double sp = rideSpeedFly;
            Vector v = look.clone().multiply(f * sp);      // fly where you look
            v.add(rightH.clone().multiply(s * sp));         // strafe
            v.add(new Vector(0, ((jump ? 1 : 0) - (sneak ? 1 : 0)) * sp, 0));
            try { e.setVelocity(v); } catch (Throwable ignored) {}
        } else {
            try { e.setGravity(true); } catch (Throwable ignored) {}
            double sp = rideSpeedLand;
            Vector move = fwdH.clone().multiply(f).add(rightH.clone().multiply(s));
            if (move.lengthSquared() > 1.0e-6) move.normalize();
            Vector v = move.multiply(sp);
            double vy = e.getVelocity().getY();
            boolean onGround;
            try { onGround = e.isOnGround(); } catch (Throwable t) { onGround = true; }
            if (jump && onGround) vy = 0.52;
            v.setY(vy);
            try { e.setVelocity(v); } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    public void onChangedWorldReal(PlayerChangedWorldEvent e) {
        // Pull the real-entity companion across with the owner.
        Companion c = companions.get(e.getPlayer().getUniqueId());
        if (c == null || !c.realEntity) return;
        endRide(c, liveRealEntity(c)); // also restores gravity/AI if she was being ridden
        for (int delay : new int[] { 2, 10, 20 }) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player owner = Bukkit.getPlayer(e.getPlayer().getUniqueId());
                Companion cc = companions.get(e.getPlayer().getUniqueId());
                if (owner == null || !owner.isOnline() || cc == null || !cc.realEntity) return;
                Entity re = liveRealEntity(cc);
                if (re == null) { spawnRealEntity(owner, cc); return; }
                if (re.getWorld() != owner.getWorld()) re.teleport(spawnLocFor(owner));
            }, delay);
        }
    }
}
