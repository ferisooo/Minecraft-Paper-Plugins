package com.ferisooo.kawaiiskyblock;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * All gameplay event enforcement scoped to {@code kawaii_isle_*} worlds.
 *
 * <p>Two data-driven systems are enforced here, both SimpleClaimSystem-style:
 * <ul>
 *   <li><b>Environment flags</b> (~70 booleans, {@link IslandManager#FLAG_KEYS}) —
 *       generic listeners keyed by event + cause/entity-type decide whether a
 *       natural/environmental action is allowed (explosions split by source,
 *       fire, spreads, growth, spawns, weather, damage causes…). Many obscure
 *       keys are stored & shown in the GUI even where there is no stable 1.21
 *       event to enforce them.</li>
 *   <li><b>Member permissions</b> (~120 booleans, {@link IslandManager#PERM_KEYS}) —
 *       generic lookup-table listeners map a clicked block / interacted entity /
 *       in-hand item to a permission key and cancel + message when the acting
 *       player's role doesn't grant it.</li>
 * </ul>
 * Plus the upgradable cobblestone generator, the ender-chest block, and warp
 * signs (unchanged behaviour).
 */
public final class IslandListeners implements Listener {

    private final KawaiiSkyblock plugin;
    private final IslandManager islands;
    private final Random rng = new Random();

    public IslandListeners(KawaiiSkyblock plugin, IslandManager islands) {
        this.plugin = plugin;
        this.islands = islands;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    private boolean inIsland(Location loc) {
        return loc != null && loc.getWorld() != null && islands.isIslandWorld(loc.getWorld().getName());
    }

    /** True if a flag is enabled (allowed) on the island owning this world. */
    private boolean flag(String world, String key) {
        return islands.flagInWorld(world, key);
    }

    /** True if the player has the given member-permission on this world's island. */
    private boolean perm(String world, Player p, String key) {
        if (p.hasPermission("kawaiiskyblock.admin")) return true;
        return islands.hasPermissionInWorld(world, p.getUniqueId(), key);
    }

    /** Cancels {@code e}, messaging the player (only on the main hand) when denied a permission. */
    private void deny(org.bukkit.event.Cancellable e, Player p, EquipmentSlot hand, String what) {
        e.setCancelled(true);
        if (hand == null || hand == EquipmentSlot.HAND) {
            p.sendMessage("§c(✧) you can't " + what + " on this island~");
        }
    }

    // ============================================================ member permissions

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        Player p = e.getPlayer();
        if (p.hasPermission("kawaiiskyblock.admin")) return;
        String world = l.getWorld().getName();
        if (!perm(world, p, "destroy_block")) {
            e.setCancelled(true);
            p.sendMessage("§c(✧) you don't have build access on this island~");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        Player p = e.getPlayer();
        if (p.hasPermission("kawaiiskyblock.admin")) return;
        String world = l.getWorld().getName();
        if (!perm(world, p, "place_block")) {
            e.setCancelled(true);
            p.sendMessage("§c(✧) you don't have build access on this island~");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Location l = e.getClickedBlock().getLocation();
        if (!inIsland(l)) return;
        Player p = e.getPlayer();
        Material type = e.getClickedBlock().getType();
        String world = l.getWorld().getName();
        boolean admin = p.hasPermission("kawaiiskyblock.admin");
        EquipmentSlot hand = e.getHand();

        // Ender chest disabled in skyblock (configurable) — handled before perms.
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && type == Material.ENDER_CHEST
                && cfg().getBoolean("disable-enderchest", true)) {
            e.setCancelled(true);
            if (hand == EquipmentSlot.HAND) {
                p.sendMessage("§d(✧) ender chests are disabled on skyblock islands~");
            }
            return;
        }

        // Warp signs: right-click teleports.
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && hand == EquipmentSlot.HAND
                && e.getClickedBlock().getState() instanceof Sign sign) {
            String first = plainLine(sign, 0);
            if (first.equalsIgnoreCase("[warp]")) {
                e.setCancelled(true);
                String warpName = plainLine(sign, 1).trim();
                plugin.useWarpSign(p, world, warpName);
                return;
            }
        }

        if (admin) return;

        // Physical farmland trample → trample_crops permission (and flag check is
        // separate; handled in onTrample for non-player too).
        if (e.getAction() == Action.PHYSICAL) {
            if (type == Material.FARMLAND && !perm(world, p, "trample_crops")) {
                e.setCancelled(true);
            }
            return;
        }

        // Right-click block → permission key via lookup table.
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            String key = blockInteractPermission(type);
            if (key != null && !perm(world, p, key)) {
                deny(e, p, hand, "use that here");
                return;
            }
        }

        // In-hand item Material → use_* permission.
        ItemStack item = e.getItem();
        if (item != null) {
            // Spawn eggs keep their dedicated container/spawn handling.
            String useKey = itemUsePermission(item.getType());
            if (useKey != null && !perm(world, p, useKey)) {
                deny(e, p, hand, "use that here");
            }
        }
    }

    /** Lookup: a clicked block's right-click permission key (or null = unrestricted). */
    private static String blockInteractPermission(Material m) {
        if (Tag.DOORS.isTagged(m)) return "interact_door";
        if (Tag.TRAPDOORS.isTagged(m)) return "interact_trapdoor";
        if (Tag.FENCE_GATES.isTagged(m)) return "interact_fence_gate";
        if (Tag.BUTTONS.isTagged(m)) return "interact_button";
        if (Tag.PRESSURE_PLATES.isTagged(m)) return "interact_pressure_plate";
        if (Tag.CANDLES.isTagged(m)) return "interact_candle";
        if (Tag.STANDING_SIGNS.isTagged(m) || Tag.WALL_SIGNS.isTagged(m)) return "interact_sign";
        if (Tag.FLOWER_POTS.isTagged(m)) return "interact_flower_pot";
        if (m.name().endsWith("SHULKER_BOX")) return "interact_shulker_box";
        if (m.name().endsWith("_BED")) return "interact_bed";
        switch (m) {
            case LEVER: return "interact_lever";
            case CHEST: return "interact_chest";
            case TRAPPED_CHEST: return "interact_trap_chest";
            case ENDER_CHEST: return "interact_ender_chest";
            case BARREL: return "interact_barrel";
            case FURNACE: return "interact_furnace";
            case BLAST_FURNACE: return "interact_blast_furnace";
            case SMOKER: return "interact_smoker";
            case HOPPER: return "interact_hopper";
            case DROPPER: return "interact_dropper";
            case DISPENSER: return "interact_dispenser";
            case BREWING_STAND: return "interact_brewing_stand";
            case BEACON: return "interact_beacon";
            case ANVIL: case CHIPPED_ANVIL: case DAMAGED_ANVIL: return "interact_anvil";
            case GRINDSTONE: return "interact_grindstone";
            case LOOM: return "interact_loom";
            case CARTOGRAPHY_TABLE: return "interact_cartography_table";
            case SMITHING_TABLE: return "interact_smithing_table";
            case STONECUTTER: return "interact_stonecutter";
            case CRAFTING_TABLE: return "interact_crafting_table";
            case ENCHANTING_TABLE: return "interact_enchanting_table";
            case LECTERN: return "interact_lectern_read";
            case BOOKSHELF: case CHISELED_BOOKSHELF: return "interact_bookshelf";
            case COMPOSTER: return "interact_composter";
            case CAULDRON: case WATER_CAULDRON: case LAVA_CAULDRON: case POWDER_SNOW_CAULDRON:
                return "interact_cauldron";
            case CAMPFIRE: case SOUL_CAMPFIRE: return "interact_campfire";
            case BELL: return "interact_bell";
            case JUKEBOX: return "interact_jukebox";
            case NOTE_BLOCK: return "interact_note_block";
            case REPEATER: return "interact_repeater";
            case COMPARATOR: return "interact_comparator";
            case DECORATED_POT: return "interact_decorated_pot";
            case DRAGON_EGG: return "interact_dragon_egg";
            default:
                if (m.name().equals("VAULT")) return "interact_vault";
                if (m.name().equals("TRIAL_SPAWNER") || m == Material.SPAWNER) return "interact_spawner";
                return null;
        }
    }

    /** Lookup: a held item's "use" permission key (or null = unrestricted). */
    private static String itemUsePermission(Material m) {
        String n = m.name();
        if (n.endsWith("_BUCKET") || m == Material.BUCKET) return "use_bucket";
        if (m == Material.BOW || m == Material.CROSSBOW) return "use_bow_crossbow";
        if (m == Material.FISHING_ROD) return "use_fishing_rod";
        if (m == Material.ENDER_PEARL) return "use_ender_pearl";
        if (m == Material.CHORUS_FRUIT) return "use_chorus_fruit";
        if (m == Material.ELYTRA) return "use_elytra";
        if (m == Material.FIREWORK_ROCKET) return "use_firework";
        if (m == Material.SNOWBALL) return "use_snowball";
        if (m == Material.TRIDENT) return "use_trident";
        if (m == Material.SHIELD) return "use_shield";
        if (m == Material.FIRE_CHARGE) return "use_fire_charge";
        if (n.equals("WIND_CHARGE")) return "use_wind_charge";
        if (m == Material.BUNDLE || n.endsWith("_BUNDLE")) return "use_bundle";
        if (m == Material.SPYGLASS) return "use_spyglass";
        if (m == Material.MAP || m == Material.FILLED_MAP) return "use_map";
        if (n.equals("BRUSH")) return "use_brush";
        if (m == Material.EGG) return "use_egg";
        if (m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION
                || m == Material.EXPERIENCE_BOTTLE) return "use_potion";
        return null;
    }

    /** Right-click / use on an entity → permission key per entity type. */
    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (!inIsland(ent.getLocation())) return;
        Player p = e.getPlayer();
        if (p.hasPermission("kawaiiskyblock.admin")) return;
        String world = ent.getLocation().getWorld().getName();
        String key = entityInteractPermission(ent, p);
        if (key != null && !perm(world, p, key)) {
            deny(e, p, e.getHand(), "interact with that here");
        }
    }

    /** Lookup: interact (right-click) permission for an entity, given the player's held item. */
    private String entityInteractPermission(Entity ent, Player p) {
        Material hand = p.getInventory().getItemInMainHand().getType();
        if (ent.getType().name().equals("WANDERING_TRADER")) return "trade_wandering_trader";
        if (ent instanceof Villager) return "interact_villager";
        switch (ent.getType().name()) {
            case "ITEM_FRAME": case "GLOW_ITEM_FRAME": return "interact_item_frame";
            case "PAINTING": return "interact_painting";
            case "ARMOR_STAND": return "interact_armor_stand";
            case "MINECART": case "CHEST_MINECART": case "FURNACE_MINECART": case "HOPPER_MINECART":
            case "TNT_MINECART": case "SPAWNER_MINECART": case "COMMAND_BLOCK_MINECART":
            case "BOAT": case "CHEST_BOAT": return "interact_vehicle";
            default: break;
        }
        // Item-driven entity actions.
        if (hand == Material.LEAD) return "lead_entity";
        if (hand == Material.NAME_TAG) return "name_tag_entity";
        if (hand == Material.SHEARS) return "shear_entity";
        if (hand == Material.BUCKET || hand == Material.WATER_BUCKET) {
            if (ent instanceof org.bukkit.entity.Cow) return "milk_entity";
            return "capture_entity"; // fish/axolotl in a bucket
        }
        if (ent instanceof Animals) {
            // Feeding/breeding both gated behind feed_entity here for simplicity.
            return "feed_entity";
        }
        return "interact_entity";
    }

    @EventHandler(ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        Location l = e.getBed().getLocation();
        if (!inIsland(l)) return;
        Player p = e.getPlayer();
        if (p.hasPermission("kawaiiskyblock.admin")) return;
        if (!perm(l.getWorld().getName(), p, "sleep")) {
            e.setCancelled(true);
            p.sendMessage("§c(✧) you can't sleep here~");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Location l = e.getItem().getLocation();
        if (!inIsland(l)) return;
        if (p.hasPermission("kawaiiskyblock.admin")) return;
        if (!perm(l.getWorld().getName(), p, "pickup_item")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Location l = e.getItemDrop().getLocation();
        if (!inIsland(l)) return;
        Player p = e.getPlayer();
        if (p.hasPermission("kawaiiskyblock.admin")) return;
        if (!perm(l.getWorld().getName(), p, "drop_item")) {
            e.setCancelled(true);
            p.sendMessage("§c(✧) you can't drop items here~");
        }
    }

    /** When trample_crops is denied (perm) or flag off, players/mobs can't trample farmland. */
    @EventHandler(ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent e) {
        if (e.getBlock().getType() != Material.FARMLAND) return;
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        String world = l.getWorld().getName();
        Entity ent = e.getEntity();
        if (ent instanceof Player p) {
            if (p.hasPermission("kawaiiskyblock.admin")) return;
            if (!perm(world, p, "trample_crops")) e.setCancelled(true);
        }
        // Non-player trampling is governed by entity_grief (handled below as well).
    }

    // ====================================================== entity damage (perms + pvp + causes)

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (!inIsland(victim.getLocation())) return;
        String world = victim.getLocation().getWorld().getName();
        Entity damager = resolveDamager(e.getDamager());

        // PvP toggle: player → player.
        if (victim instanceof Player && damager instanceof Player) {
            if (!flag(world, "pvp")) { e.setCancelled(true); return; }
        }

        // Player attacking a non-player entity → damage/destroy permission.
        if (damager instanceof Player pl && !(victim instanceof Player)) {
            if (pl.hasPermission("kawaiiskyblock.admin")) return;
            String key = entityDamagePermission(victim);
            if (key != null && !perm(world, pl, key)) {
                e.setCancelled(true);
                pl.sendMessage("§c(✧) you can't hurt that here~");
            }
        }
    }

    /** Lookup: damage/destroy permission for attacking an entity. */
    private static String entityDamagePermission(Entity victim) {
        switch (victim.getType().name()) {
            case "ITEM_FRAME": case "GLOW_ITEM_FRAME": return "destroy_item_frame";
            case "PAINTING": return "destroy_painting";
            case "ARMOR_STAND": return "destroy_armor_stand";
            case "MINECART": case "CHEST_MINECART": case "FURNACE_MINECART": case "HOPPER_MINECART":
            case "TNT_MINECART": case "SPAWNER_MINECART": case "COMMAND_BLOCK_MINECART":
            case "BOAT": case "CHEST_BOAT": return "destroy_vehicle";
            default:
                if (victim instanceof Animals || victim instanceof Villager || victim instanceof Tameable) {
                    return "damage_entity";
                }
                return "destroy_entity";
        }
    }

    /** Unwraps projectiles to their shooting entity when applicable. */
    private Entity resolveDamager(Entity damager) {
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Entity ent) return ent;
        }
        return damager;
    }

    /** Damage-cause flag: only the fall_damage toggle is enforced (rest is vanilla). */
    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        if (!inIsland(ent.getLocation())) return;
        String world = ent.getLocation().getWorld().getName();

        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && !flag(world, "fall_damage")) {
            e.setCancelled(true);
        }
    }

    // ====================================================== explosions (flags, split by source)

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!inIsland(e.getLocation())) return;
        String world = e.getLocation().getWorld().getName();
        String sourceFlag = explosionSourceFlag(e.getEntity());
        boolean blocked = !flag(world, "explosion_block_damage")
                || (sourceFlag != null && !flag(world, sourceFlag));
        if (blocked) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!inIsland(e.getBlock().getLocation())) return;
        String world = e.getBlock().getWorld().getName();
        if (!flag(world, "explosion_block_damage")) e.blockList().clear();
    }

    /** Maps an exploding entity to its source-specific explosion flag (kept set only). */
    private static String explosionSourceFlag(Entity ent) {
        if (ent instanceof Creeper) {
            return "creeper_explosions";
        }
        String n = ent.getType().name();
        switch (n) {
            case "PRIMED_TNT": case "TNT": return "tnt_explosions";
            default: return null;
        }
    }

    // ====================================================== fire

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        if (!flag(l.getWorld().getName(), "fire_burn")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        String world = l.getWorld().getName();
        switch (e.getCause()) {
            case SPREAD:
                if (!flag(world, "fire_spread")) e.setCancelled(true);
                break;
            case LIGHTNING:
            case LAVA:
                if (!flag(world, "fire_ignite")) e.setCancelled(true);
                break;
            case FLINT_AND_STEEL:
            case FIREBALL:
                // Player ignition gated by the ignite_block permission.
                if (e.getPlayer() != null) {
                    if (!e.getPlayer().hasPermission("kawaiiskyblock.admin")
                            && !islands.hasPermissionInWorld(world, e.getPlayer().getUniqueId(), "ignite_block")) {
                        e.setCancelled(true);
                    }
                } else if (!flag(world, "fire_ignite")) {
                    e.setCancelled(true);
                }
                break;
            default:
                if (!flag(world, "fire_ignite")) e.setCancelled(true);
        }
    }

    // ====================================================== spreads / growth / forms

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        String world = l.getWorld().getName();
        // Only fire spread is enforced; all other spreads are vanilla.
        if (e.getNewState().getType() == Material.FIRE && !flag(world, "fire_spread")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent e) {
        Block block = e.getBlock();
        if (!inIsland(block.getLocation())) return;
        String world = block.getWorld().getName();
        Material result = e.getNewState().getType();

        // Cobblestone generator takes priority for cobble/stone forms.
        if ((result == Material.COBBLESTONE || result == Material.STONE)
                && cfg().getBoolean("generator.enabled", true)) {
            UUID owner = islands.ownerOfWorld(world);
            int genLevel = owner == null ? 1 : islands.generatorLevel(owner);
            Material rolled = rollGenerator(genLevel);
            if (rolled != null) e.getNewState().setType(rolled);
        }
        // No snow/ice form flags are enforced anymore — vanilla behaviour.
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        String world = l.getWorld().getName();
        // Only crop growth is enforced; other block growth is vanilla.
        if (Tag.CROPS.isTagged(e.getNewState().getType()) && !flag(world, "crop_growth")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        if (!flag(l.getWorld().getName(), "leaf_decay")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        if (!flag(l.getWorld().getName(), "liquids_flow")) e.setCancelled(true);
    }

    // ====================================================== spawns / weather / grief

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        Location l = e.getLocation();
        if (!inIsland(l)) return;
        String world = l.getWorld().getName();
        // Only natural breeding is enforced; all other spawns are vanilla.
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING
                && !flag(world, "natural_breeding")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLightning(LightningStrikeEvent e) {
        Location l = e.getLightning().getLocation();
        if (!inIsland(l)) return;
        if (!flag(l.getWorld().getName(), "lightning_strike")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent e) {
        if (e.getWorld() == null) return;
        if (!islands.isIslandWorld(e.getWorld().getName())) return;
        // Only block the transition INTO storming when weather_change is off.
        if (e.toWeatherState() && !flag(e.getWorld().getName(), "weather_change")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        String world = l.getWorld().getName();
        Entity ent = e.getEntity();

        if (ent instanceof Player) return; // players handled by build perms / trample
        if (ent instanceof FallingBlock) return; // falling blocks always allowed (vanilla)

        // Any non-player mob altering blocks (enderman pickup, etc.) → entity_grief.
        if (!flag(world, "entity_grief")) e.setCancelled(true);
    }

    // ====================================================== ender chest (open)

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!cfg().getBoolean("disable-enderchest", true)) return;
        Location l = e.getInventory().getLocation();
        if (!inIsland(l)) return;
        if (e.getInventory().getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) {
            e.setCancelled(true);
        }
    }

    // ====================================================== cobble generator roll

    /**
     * Rolls a possible upgraded drop for the given generator level. Reads
     * {@code generator.levels.<level>} as a list of {@code "MATERIAL:chance"}
     * (chance 0..1). Returns null to keep the default cobblestone/stone.
     */
    private Material rollGenerator(int level) {
        var sec = cfg().getConfigurationSection("generator.levels");
        if (sec == null) return null;
        int best = -1;
        for (String k : sec.getKeys(false)) {
            try {
                int lv = Integer.parseInt(k);
                if (lv <= level && lv > best) best = lv;
            } catch (NumberFormatException ignored) {}
        }
        if (best < 0) return null;
        var entries = cfg().getStringList("generator.levels." + best);
        if (entries.isEmpty()) return null;
        double roll = rng.nextDouble();
        double acc = 0.0;
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length != 2) continue;
            Material mat = Material.matchMaterial(parts[0].trim().toUpperCase(Locale.ROOT));
            double chance;
            try { chance = Double.parseDouble(parts[1].trim()); }
            catch (NumberFormatException ex) { continue; }
            if (mat == null) continue;
            acc += chance;
            if (roll < acc) return mat;
        }
        return null;
    }

    // ====================================================== warp signs (create)

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        Location l = e.getBlock().getLocation();
        if (!inIsland(l)) return;
        String first = plainEventLine(e, 0);
        if (!first.equalsIgnoreCase("[warp]")) return;

        Player p = e.getPlayer();
        String world = l.getWorld().getName();
        if (!p.hasPermission("kawaiiskyblock.admin")
                && !islands.canIn(world, p.getUniqueId(), IslandManager.Perm.MANAGE)) {
            e.setCancelled(true);
            p.sendMessage("§c(✧) only members with MANAGE can make warp signs~");
            return;
        }
        String warpName = plainEventLine(e, 1).trim();
        if (warpName.isEmpty()) {
            p.sendMessage("§c(✧) put a warp name on the 2nd line!");
            return;
        }
        UUID owner = islands.ownerOfWorld(world);
        if (owner == null || islands.warpLocation(owner, warpName) == null) {
            p.sendMessage("§e(✧) note: no warp named §f" + warpName + "§e yet — set one with §f/is setwarp " + warpName);
        }
        e.setLine(0, "§d[warp]");
        e.setLine(1, "§f" + warpName);
        p.sendMessage("§d(✧) ✨ warp sign created → §f" + warpName);
    }

    // ====================================================== helpers

    private static String plainLine(Sign sign, int i) {
        try {
            return org.bukkit.ChatColor.stripColor(sign.getLine(i));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String plainEventLine(SignChangeEvent e, int i) {
        String s = e.getLine(i);
        return s == null ? "" : org.bukkit.ChatColor.stripColor(s);
    }
}
