package com.ferisooo.kawaiiquests;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Tracks quest progress from gameplay (with anti-farming guards) and handles menu clicks. */
public final class QuestListener implements Listener {

    private final KawaiiQuests plugin;

    public QuestListener(KawaiiQuests plugin) {
        this.plugin = plugin;
    }

    // --------------------------------------------------------- progress tracking

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        plugin.antiExploit().markPlaced(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        // Breaking a block the player placed earns nothing (anti place-and-break).
        if (plugin.antiExploit().isPlayerPlaced(e.getBlock())) return;
        plugin.handleProgress(e.getPlayer(), Quest.Type.MINE,
                e.getBlock().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent e) {
        // Forget the position and, if it was player-placed, mark its drops as no-credit.
        plugin.antiExploit().onBlockDrops(e.getBlock(), e.getItems());
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        if (!plugin.antiExploit().isCountableKill(e.getEntity())) return;
        plugin.handleProgress(killer, Quest.Type.KILL,
                e.getEntity().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item it) {
            plugin.handleProgress(e.getPlayer(), Quest.Type.FISH, it.getItemStack().getType().name(), 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p) {
            plugin.handleProgress(p, Quest.Type.BREED, e.getEntityType().name(), 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (e.getOwner() instanceof Player p) {
            plugin.handleProgress(p, Quest.Type.TAME, e.getEntityType().name(), 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent e) {
        plugin.handleProgress(e.getPlayer(), Quest.Type.SMELT,
                e.getItemType().name(), Math.max(1, e.getItemAmount()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || e.getRecipe() == null) return;
        ItemStack result = e.getRecipe().getResult();
        plugin.handleProgress(p, Quest.Type.CRAFT, result.getType().name(), Math.max(1, result.getAmount()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        plugin.handleProgress(e.getEnchanter(), Quest.Type.ENCHANT, e.getItem().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        plugin.handleProgress(e.getPlayer(), Quest.Type.TRADE, "ANY", 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        plugin.antiExploit().tagDropped(e.getItemDrop());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (plugin.antiExploit().isNoCredit(e.getItem())) return; // dropped/place-broken items
        var stack = e.getItem().getItemStack();
        plugin.handleProgress(p, Quest.Type.COLLECT, stack.getType().name(), stack.getAmount());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.clearTransient(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Re-sync the sidebar bridge in case a quest was restored on startup.
        plugin.updateQuestDisplay(e.getPlayer());
    }

    // --------------------------------------------------------- menu interaction

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof GuiHolder gui)) return;

        // Never let players take/move items in any of our screens.
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        // Ignore clicks in the player's own inventory area.
        if (e.getClickedInventory() == null
                || !(e.getClickedInventory().getHolder() instanceof GuiHolder)) {
            return;
        }

        switch (gui.kind()) {
            case SELECT -> {
                Difficulty diff = switch (e.getRawSlot()) {
                    case QuestGui.SLOT_EASY -> Difficulty.EASY;
                    case QuestGui.SLOT_MEDIUM -> Difficulty.MEDIUM;
                    case QuestGui.SLOT_HARD -> Difficulty.HARD;
                    case QuestGui.SLOT_BRUTAL -> Difficulty.BRUTAL;
                    default -> null;
                };
                if (diff != null) {
                    // closeInventory()/openInventory() must not run inside an
                    // InventoryClickEvent handler — defer to the next tick.
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        plugin.requestQuest(p, diff);
                    });
                }
            }
            case ACTIVE -> {
                if (e.getRawSlot() == QuestGui.SLOT_ABANDON) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.abandonQuest(p);
                        p.closeInventory();
                    });
                }
            }
            case CRATE -> { /* nothing clickable while it spins */ }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            e.setCancelled(true);
        }
    }
}
