package com.ferisooo.kawaiiclaims;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Iterator;
import java.util.List;

/**
 * All world protection. Protection is enforced ONLY inside claimed chunks;
 * unclaimed chunks are unprotected wilderness.
 *
 * Two systems are enforced here:
 *  - FLAGS: world/environment behaviour (explosions, fire, growth, spawns...).
 *  - PERMISSIONS: per-player member actions gated by role (build/interact/use...).
 */
public class ProtectionListeners implements Listener {

    private final KawaiiClaims plugin;
    private final ClaimManager manager;

    public ProtectionListeners(KawaiiClaims plugin) {
        this.plugin = plugin;
        this.manager = plugin.getClaimManager();
    }

    private boolean flag(Claim c, String name) { return manager.flag(c, name); }

    private boolean allowed(Player p, Claim c, String perm) { return manager.permissionAllowed(p, c, perm); }

    // =================================================================
    //  Block break / place -> destroy_block / place_block + ignite/trample
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        if (!allowed(e.getPlayer(), claim, "destroy_block")) {
            e.setCancelled(true);
            plugin.denied(e.getPlayer(), "build here");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        if (!allowed(e.getPlayer(), claim, "place_block")) {
            e.setCancelled(true);
            plugin.denied(e.getPlayer(), "build here");
        }
    }

    // =================================================================
    //  Player interactions: blocks (right-click) + physical (trample) +
    //  item-in-hand use (use_*).
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        Block block = e.getClickedBlock();

        // Crop trample (PHYSICAL on farmland) -> trample_crops
        if (e.getAction() == Action.PHYSICAL) {
            if (block == null) return;
            Claim claim = manager.getClaimAt(block.getLocation());
            if (claim == null) return;
            Material t = block.getType();
            if (t == Material.FARMLAND) {
                if (!allowed(player, claim, "trample_crops")) e.setCancelled(true);
            } else if (t == Material.TURTLE_EGG || t == Material.SNIFFER_EGG) {
                if (!allowed(player, claim, "destroy_block")) e.setCancelled(true);
            } else if (isTripwire(t)) {
                if (!allowed(player, claim, "interact_tripwire")) e.setCancelled(true);
            }
            return;
        }

        // Right-click block -> interact_* permission
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && block != null) {
            Claim claim = manager.getClaimAt(block.getLocation());
            if (claim != null) {
                String perm = blockInteractPerm(block.getType());
                if (perm != null && !allowed(player, claim, perm)) {
                    e.setCancelled(true);
                    plugin.denied(player, "use this here");
                    return;
                }
            }
        }

        // Item-in-hand use (right-click air or block) -> use_* permission
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack inHand = e.getItem();
            if (inHand == null) return;
            Location loc = block != null ? block.getLocation() : player.getLocation();
            Claim claim = manager.getClaimAt(loc);
            if (claim == null) return;
            String perm = itemUsePerm(inHand.getType());
            if (perm != null && !allowed(player, claim, perm)) {
                e.setCancelled(true);
                plugin.denied(player, "use that here");
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        Location loc = e.getInventory().getLocation();
        if (loc == null) return;
        Claim claim = manager.getClaimAt(loc);
        if (claim == null) return;
        // generic container access requires interact permission on a chest-like block
        if (!allowed(player, claim, "interact_chest")) {
            e.setCancelled(true);
            plugin.denied(player, "open this here");
        }
    }

    // =================================================================
    //  Entity interaction: item frames / armor stands / vehicles / mobs
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        Claim claim = manager.getClaimAt(ent.getLocation());
        if (claim == null) return;
        Player player = e.getPlayer();
        String perm = entityInteractPerm(ent);
        if (perm != null && !allowed(player, claim, perm)) {
            e.setCancelled(true);
            plugin.denied(player, "touch this here");
        }
    }

    // =================================================================
    //  Vehicles: entering & damaging
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!(e.getEntered() instanceof Player player)) return;
        Claim claim = manager.getClaimAt(e.getVehicle().getLocation());
        if (claim == null) return;
        if (!allowed(player, claim, "interact_vehicle")) {
            e.setCancelled(true);
            plugin.denied(player, "use vehicles here");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onVehicleDamage(VehicleDamageEvent e) {
        Claim claim = manager.getClaimAt(e.getVehicle().getLocation());
        if (claim == null) return;
        Player attacker = resolvePlayer(e.getAttacker());
        if (attacker != null) {
            if (!allowed(attacker, claim, "destroy_vehicle")) {
                e.setCancelled(true);
                plugin.denied(attacker, "break vehicles here");
            }
        }
        // non-player vehicle damage is vanilla (no flag enforced)
    }

    // =================================================================
    //  Item pickup / drop
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        Item item = e.getItem();
        Claim claim = manager.getClaimAt(item.getLocation());
        if (claim == null) return;
        if (!allowed(player, claim, "pickup_item")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Player player = e.getPlayer();
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) return;
        if (!allowed(player, claim, "drop_item")) {
            e.setCancelled(true);
            plugin.denied(player, "drop items here");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBedEnter(PlayerBedEnterEvent e) {
        Claim claim = manager.getClaimAt(e.getBed().getLocation());
        if (claim == null) return;
        if (!allowed(e.getPlayer(), claim, "sleep")) {
            e.setCancelled(true);
            plugin.denied(e.getPlayer(), "sleep here");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPortal(PlayerPortalEvent e) {
        Claim claim = manager.getClaimAt(e.getFrom());
        if (claim == null) return;
        if (!allowed(e.getPlayer(), claim, "use_portal")) {
            e.setCancelled(true);
            plugin.denied(e.getPlayer(), "use portals here");
        }
    }

    // =================================================================
    //  Entity damage by entity: PvP / damage_entity / destroy_* / breed etc.
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Claim claim = manager.getClaimAt(victim.getLocation());
        if (claim == null) return;

        Player attacker = resolvePlayer(e.getDamager());

        // ----- PvP -----
        if (victim instanceof Player) {
            if (attacker == null) return; // not player-sourced
            if (flag(claim, "pvp")) return;
            if (plugin.isBypassing(attacker)) return;
            e.setCancelled(true);
            plugin.denied(attacker, "fight here (safe zone)");
            return;
        }

        if (attacker != null) {
            String perm = damageEntityPerm(victim);
            if (perm != null && !allowed(attacker, claim, perm)) {
                e.setCancelled(true);
                plugin.denied(attacker, "harm this here");
            }
            return;
        }

        // non-player damager: entity_grief protects item frames/paintings/stands.
        if ((victim instanceof ItemFrame || victim instanceof Painting || victim instanceof ArmorStand)
                && !flag(claim, "entity_grief")) {
            e.setCancelled(true);
        }
    }

    // generic entity damage by cause -> fall_damage flag (others are vanilla)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityDamageGeneric(EntityDamageEvent e) {
        Claim claim = manager.getClaimAt(e.getEntity().getLocation());
        if (claim == null) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && !flag(claim, "fall_damage")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Claim claim = manager.getClaimAt(e.getEntity().getLocation());
        if (claim == null) return;
        Player attacker = resolvePlayer(e.getRemover());
        if (attacker != null) {
            String perm = e.getEntity() instanceof Painting ? "destroy_painting"
                    : e.getEntity() instanceof ItemFrame ? "destroy_item_frame" : "destroy_entity";
            if (!allowed(attacker, claim, perm)) {
                e.setCancelled(true);
                plugin.denied(attacker, "break this here");
            }
        } else {
            if (!flag(claim, "entity_grief")) e.setCancelled(true);
        }
    }

    // =================================================================
    //  Pistons crossing a claim boundary
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonViolation(e.getBlock(), e.getBlocks())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonViolation(e.getBlock(), e.getBlocks())) e.setCancelled(true);
    }

    private boolean pistonViolation(Block piston, List<Block> affected) {
        Claim pistonClaim = manager.getClaimAt(piston.getLocation());
        String pistonId = pistonClaim == null ? null : pistonClaim.getId();
        for (Block b : affected) {
            Claim c = manager.getClaimAt(b.getLocation());
            if (c == null) continue;
            if (!c.getId().equals(pistonId)) return true;
        }
        return false;
    }

    // =================================================================
    //  Liquids flowing -> liquids_flow flag; cross-boundary always blocked
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onLiquidFlow(BlockFromToEvent e) {
        Block from = e.getBlock();
        Block to = e.getToBlock();
        Claim toClaim = manager.getClaimAt(to.getLocation());
        if (toClaim == null) return;
        Claim fromClaim = manager.getClaimAt(from.getLocation());
        if (fromClaim == null || !fromClaim.getId().equals(toClaim.getId())) {
            e.setCancelled(true);
            return;
        }
        if (!flag(toClaim, "liquids_flow")) e.setCancelled(true);
    }

    // =================================================================
    //  Explosions -> per-source *_explosions flags + block damage filtering
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent e) {
        Entity src = e.getEntity();
        // Source-specific gate: only the kept creeper/tnt flags are honoured; any
        // other source falls back to the generic explosion_block_damage gate.
        filterExplosion(e.blockList(), explosionFlagFor(src));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        // block explosions (beds/anchors/etc.) are gated only by explosion_block_damage
        filterExplosion(e.blockList(), null);
    }

    /** Returns the kept explosion flag for the exploding entity, or null for the generic gate. */
    private String explosionFlagFor(Entity src) {
        if (src == null) return null;
        if (src instanceof Creeper) return "creeper_explosions";
        if (src instanceof org.bukkit.entity.TNTPrimed
                || src instanceof org.bukkit.entity.minecart.ExplosiveMinecart) {
            return "tnt_explosions";
        }
        return null;
    }

    /**
     * Filter the explosion block list per claim: a block is spared if the
     * source-specific flag (when given) is off, or explosion_block_damage is off.
     */
    private void filterExplosion(List<Block> blocks, String sourceFlag) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            Claim claim = manager.getClaimAt(b.getLocation());
            if (claim == null) continue;
            boolean allow = flag(claim, "explosion_block_damage");
            if (sourceFlag != null) allow = allow && flag(claim, sourceFlag);
            if (!allow) it.remove();
        }
    }

    // =================================================================
    //  Fire
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBurn(BlockBurnEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim != null && !flag(claim, "fire_burn")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onIgnite(BlockIgniteEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        // a player with ignite permission may light fire manually
        if (e.getPlayer() != null) {
            if (!allowed(e.getPlayer(), claim, "ignite_block")) {
                e.setCancelled(true);
                plugin.denied(e.getPlayer(), "light fires here");
            }
            return;
        }
        BlockIgniteEvent.IgniteCause cause = e.getCause();
        if (cause == BlockIgniteEvent.IgniteCause.SPREAD) {
            if (!flag(claim, "fire_spread")) e.setCancelled(true);
        } else {
            if (!flag(claim, "fire_ignite")) e.setCancelled(true);
        }
    }

    // =================================================================
    //  Block spread (fire/sculk/mushroom/mycelium/vine/flower) -> flags
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockSpread(BlockSpreadEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        Material src = e.getSource().getType();
        // Only fire spread is enforced; all other spreads (sculk/mushroom/vine/grass) are vanilla.
        if (src == Material.FIRE || src == Material.SOUL_FIRE) {
            if (!flag(claim, "fire_spread")) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockGrow(BlockGrowEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        Material to = e.getNewState().getType();
        boolean crop = to == Material.WHEAT || to == Material.CARROTS || to == Material.POTATOES
                || to == Material.BEETROOTS || to == Material.NETHER_WART
                || to == Material.MELON || to == Material.PUMPKIN
                || to == Material.MELON_STEM || to == Material.PUMPKIN_STEM
                || to == Material.TORCHFLOWER || to == Material.TORCHFLOWER_CROP
                || to == Material.PITCHER_CROP;
        // Only crop growth is enforced; other block growth is vanilla.
        if (crop && !flag(claim, "crop_growth")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onLeafDecay(LeavesDecayEvent e) {
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        if (!flag(claim, "leaf_decay")) e.setCancelled(true);
    }

    // =================================================================
    //  Mob spawning / griefing / transforms / teleports / projectiles
    // =================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        Claim claim = manager.getClaimAt(e.getLocation());
        if (claim == null) return;
        // Only natural breeding is enforced; all other spawns are vanilla.
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
            if (!flag(claim, "natural_breeding")) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMobChangeBlock(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        Claim claim = manager.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        boolean griefer = ent instanceof Creeper
                || ent instanceof Enderman
                || ent instanceof Wither
                || ent.getType() == EntityType.WITHER_SKULL
                || ent.getType() == EntityType.RAVAGER
                || ent.getType() == EntityType.SILVERFISH
                || ent.getType() == EntityType.ZOMBIE
                || ent.getType() == EntityType.ZOMBIE_VILLAGER
                || ent.getType() == EntityType.SHEEP
                || ent.getType() == EntityType.RABBIT
                || ent.getType() == EntityType.FOX;
        if (griefer && !flag(claim, "entity_grief")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPortalCreate(PortalCreateEvent e) {
        for (BlockState bs : e.getBlocks()) {
            Claim claim = manager.getClaimAt(bs.getLocation());
            if (claim != null && !flag(claim, "portal_create")) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onLightning(LightningStrikeEvent e) {
        Claim claim = manager.getClaimAt(e.getLightning().getLocation());
        if (claim == null) return;
        if (!flag(claim, "lightning_strike")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onWeatherChange(WeatherChangeEvent e) {
        // global weather isn't claim-scoped; only honour if ANY claim in the
        // world disables weather change. Lightweight: skip enforcement when
        // turning weather OFF (rain stopping is harmless).
        if (!e.toWeatherState()) return;
        for (Claim c : manager.getAllClaims()) {
            String w = c.getWorld();
            if (w != null && w.equals(e.getWorld().getName()) && !flag(c, "weather_change")) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // =================================================================
    //  Helpers
    // =================================================================
    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        if (damager instanceof AbstractArrow arrow) {
            if (arrow.getShooter() instanceof Player p) return p;
        }
        if (damager instanceof AreaEffectCloud cloud) {
            if (cloud.getSource() instanceof Player p) return p;
        }
        return null;
    }

    private boolean isTripwire(Material m) {
        return m == Material.TRIPWIRE || m == Material.TRIPWIRE_HOOK;
    }

    // -----------------------------------------------------------------
    //  Lookup tables: clicked block -> interact_* permission
    // -----------------------------------------------------------------
    private String blockInteractPerm(Material t) {
        String n = t.name();
        // doors / trapdoors / fence gates
        if (Tag.DOORS.isTagged(t)) return "interact_door";
        if (Tag.TRAPDOORS.isTagged(t)) return "interact_trapdoor";
        if (Tag.FENCE_GATES.isTagged(t)) return "interact_fence_gate";
        if (Tag.BUTTONS.isTagged(t)) return "interact_button";
        if (t == Material.LEVER) return "interact_lever";
        if (Tag.PRESSURE_PLATES.isTagged(t)) return "interact_pressure_plate";
        if (Tag.BEDS.isTagged(t)) return "interact_bed";
        if (Tag.CANDLES.isTagged(t)) return "interact_candle";
        if (Tag.SIGNS.isTagged(t) || n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN")) return "interact_sign";
        if (n.endsWith("_SHULKER_BOX") || t == Material.SHULKER_BOX) return "interact_shulker_box";

        switch (t) {
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
            case ANVIL:
            case CHIPPED_ANVIL:
            case DAMAGED_ANVIL: return "interact_anvil";
            case GRINDSTONE: return "interact_grindstone";
            case LOOM: return "interact_loom";
            case CARTOGRAPHY_TABLE: return "interact_cartography_table";
            case SMITHING_TABLE: return "interact_smithing_table";
            case STONECUTTER: return "interact_stonecutter";
            case CRAFTING_TABLE: return "interact_crafting_table";
            case ENCHANTING_TABLE: return "interact_enchanting_table";
            case LECTERN: return "interact_lectern_read";
            case BOOKSHELF:
            case CHISELED_BOOKSHELF: return "interact_bookshelf";
            case COMPOSTER: return "interact_composter";
            case CAULDRON:
            case WATER_CAULDRON:
            case LAVA_CAULDRON:
            case POWDER_SNOW_CAULDRON: return "interact_cauldron";
            case CAMPFIRE:
            case SOUL_CAMPFIRE: return "interact_campfire";
            case BELL: return "interact_bell";
            case JUKEBOX: return "interact_jukebox";
            case NOTE_BLOCK: return "interact_note_block";
            case REPEATER: return "interact_repeater";
            case COMPARATOR: return "interact_comparator";
            case DAYLIGHT_DETECTOR: return "interact_daylight_detector";
            case FLOWER_POT: return "interact_flower_pot";
            case DECORATED_POT: return "interact_decorated_pot";
            case SPAWNER:
            case TRIAL_SPAWNER: return "interact_spawner";
            case DRAGON_EGG: return "interact_dragon_egg";
            case VAULT: return "interact_vault";
            case TRIPWIRE:
            case TRIPWIRE_HOOK: return "interact_tripwire";
            default: break;
        }
        // potted plants / coloured flower pots
        if (n.startsWith("POTTED_")) return "interact_flower_pot";
        return null;
    }

    // -----------------------------------------------------------------
    //  Lookup tables: in-hand item -> use_* permission
    // -----------------------------------------------------------------
    private String itemUsePerm(Material m) {
        String n = m.name();
        if (n.endsWith("_BUCKET") || m == Material.BUCKET) return "use_bucket";
        if (m == Material.BOW || m == Material.CROSSBOW) return "use_bow_crossbow";
        if (m == Material.FISHING_ROD) return "use_fishing_rod";
        if (m == Material.ENDER_PEARL) return "use_ender_pearl";
        if (m == Material.CHORUS_FRUIT) return "use_chorus_fruit";
        if (m == Material.ELYTRA) return "use_elytra";
        if (m == Material.FIREWORK_ROCKET) return "use_firework";
        if (n.endsWith("POTION") || m == Material.POTION || m == Material.SPLASH_POTION
                || m == Material.LINGERING_POTION) return "use_potion";
        if (m == Material.EGG) return "use_egg";
        if (m == Material.SNOWBALL) return "use_snowball";
        if (m == Material.TRIDENT) return "use_trident";
        if (m == Material.SHIELD) return "use_shield";
        if (m == Material.FIRE_CHARGE) return "use_fire_charge";
        if (m == Material.WIND_CHARGE) return "use_wind_charge";
        if (m == Material.BUNDLE || n.endsWith("_BUNDLE")) return "use_bundle";
        if (m == Material.SPYGLASS) return "use_spyglass";
        if (m == Material.MAP || m == Material.FILLED_MAP) return "use_map";
        if (m == Material.BRUSH) return "use_brush";
        if (m == Material.FLINT_AND_STEEL) return "ignite_block";
        if (m == Material.LEAD) return "lead_entity";
        if (m == Material.NAME_TAG) return "name_tag_entity";
        if (m == Material.SHEARS) return "shear_entity";
        // spawn eggs -> placing a mob counts as build-ish; treat as place_block
        if (n.endsWith("_SPAWN_EGG")) return "place_block";
        return null;
    }

    // -----------------------------------------------------------------
    //  Lookup: right-clicked entity -> interact_* permission
    // -----------------------------------------------------------------
    private String entityInteractPerm(Entity ent) {
        if (ent instanceof ItemFrame) return "interact_item_frame";
        if (ent instanceof Painting) return "interact_painting";
        if (ent instanceof ArmorStand) return "interact_armor_stand";
        if (ent instanceof Boat || ent instanceof Minecart || ent instanceof Vehicle) return "interact_vehicle";
        if (ent instanceof Villager) return "interact_villager";
        EntityType t = ent.getType();
        if (t == EntityType.WANDERING_TRADER) return "trade_wandering_trader";
        if (ent instanceof Animals || ent instanceof Tameable || ent instanceof LivingEntity) return "interact_entity";
        return null;
    }

    // -----------------------------------------------------------------
    //  Lookup: damaged entity -> permission key
    // -----------------------------------------------------------------
    private String damageEntityPerm(Entity victim) {
        if (victim instanceof ItemFrame) return "destroy_item_frame";
        if (victim instanceof Painting) return "destroy_painting";
        if (victim instanceof ArmorStand) return "destroy_armor_stand";
        if (victim instanceof Boat || victim instanceof Minecart || victim instanceof Vehicle) {
            return "destroy_vehicle";
        }
        if (victim instanceof Villager
                || victim instanceof Animals
                || victim instanceof Tameable) {
            return "damage_entity";
        }
        if (victim instanceof LivingEntity) return "damage_entity";
        return null;
    }
}
