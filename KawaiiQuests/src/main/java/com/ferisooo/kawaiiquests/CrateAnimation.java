package com.ferisooo.kawaiiquests;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A CS:GO-style loot reveal in a chest GUI: a horizontal reel of reward icons
 * scrolls through the middle row and eases to a stop on the winning item,
 * which is then handed to the player. Update rate is kept modest so it stays
 * smooth for Bedrock players viewing it through Geyser.
 */
public final class CrateAnimation implements Runnable {

    // Middle row of a 27-slot chest is slots 9..17; the pointer sits on 13.
    private static final int ROW_START = 9;
    private static final int CENTER = 13;
    private static final int WINDOW = 9;

    private final KawaiiQuests plugin;
    private final Player player;
    private final Difficulty diff;
    private final Inventory inv;
    private final List<ItemStack> reel = new ArrayList<>();
    private final int steps;
    private final LootTable.Entry winner;

    private int step = 0;

    public CrateAnimation(KawaiiQuests plugin, Player player, Difficulty diff) {
        this(plugin, player, diff, null);
    }

    /** {@code forced} (the AI-chosen reward) wins if non-null; otherwise the loot table is rolled. */
    public CrateAnimation(KawaiiQuests plugin, Player player, Difficulty diff, LootTable.Entry forced) {
        this.plugin = plugin;
        this.player = player;
        this.diff = diff;
        this.steps = Math.max(8, plugin.getConfig().getInt("crate.spin-steps", 22));
        this.winner = forced != null ? forced : plugin.lootTable().roll(diff);

        GuiHolder holder = new GuiHolder(GuiHolder.Kind.CRATE);
        this.inv = Bukkit.createInventory(holder, 27, plugin.text("&d✧ Loot Crate ✧"));
        holder.setInventory(inv);
        decorate();
        buildReel();
    }

    /** Open the crate and kick off the spin. */
    public void start() {
        // Grant the reward immediately (and synchronously) so it can never be
        // lost if the player quits or the server stops mid-spin. The spin is
        // purely a reveal; the announcement still fires at the end.
        giveReward();
        player.openInventory(inv);
        // Folia-safe: the animation touches this player only — run it on the
        // player's entity scheduler.
        player.getScheduler().run(plugin, t -> run(), null);
    }

    @Override
    public void run() {
        render(step);
        float pitch = 0.8f + Math.min(1.2f, step * 0.05f);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, pitch);

        if (step >= steps) {
            finish();
            return;
        }
        // Ease-out: delay grows as we approach the end so the reel slows down.
        long delay = 1 + Math.round(5.0 * step / steps);
        step++;
        player.getScheduler().runDelayed(plugin, t -> run(), null, Math.max(1, delay));
    }

    private void finish() {
        inv.setItem(CENTER, plugin.lootTable().icon(winner)); // make sure the win shows
        highlightWin();
        if (player.isOnline()) {
            // Reward was already granted in start(); this is just the reveal.
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
            if (plugin.getConfig().getBoolean("effects", true)) {
                var loc = player.getLocation().add(0, 1.0, 0);
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 22, 0.5, 0.6, 0.5, 0.25);
                player.spawnParticle(Particle.FIREWORK, loc, 25, 0.4, 0.5, 0.4, 0.06);
            }
            player.sendMessage(plugin.msg("reward",
                    "%amount%", String.valueOf(winner.amount()),
                    "%item%", KawaiiQuests.pretty(winner.material().name())));
        }
        // Let them admire it, then close if they're still looking at the crate.
        player.getScheduler().runDelayed(plugin, t -> {
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder gh
                    && gh.kind() == GuiHolder.Kind.CRATE) {
                player.closeInventory();
            }
        }, null, 60L);
    }

    private void giveReward() {
        ItemStack reward = plugin.lootTable().reward(winner);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(reward);
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    /** Frame the winning slot in green so the reveal reads as "locked in". */
    private void highlightWin() {
        ItemStack win = pane(Material.LIME_STAINED_GLASS_PANE);
        for (int j = 0; j < WINDOW; j++) {
            int slot = ROW_START + j;
            if (slot != CENTER) inv.setItem(slot, win);
        }
        inv.setItem(CENTER - WINDOW, marker("&a❯"));
        inv.setItem(CENTER + WINDOW, marker("&a❮"));
    }

    // ------------------------------------------------------------------ visuals

    private void render(int start) {
        for (int j = 0; j < WINDOW; j++) {
            inv.setItem(ROW_START + j, reel.get(start + j));
        }
    }

    private void buildReel() {
        // Need enough icons so the final window (start == steps) is in range.
        int len = steps + WINDOW + 1;
        for (int i = 0; i < len; i++) {
            reel.add(plugin.lootTable().icon(plugin.lootTable().roll(diff)));
        }
        // At the final step the window starts at `steps`, so its center slot
        // (13) reads reel[steps + (CENTER - ROW_START)]. Land the winner there.
        int winIndex = steps + (CENTER - ROW_START);
        reel.set(winIndex, plugin.lootTable().icon(winner));
    }

    private void decorate() {
        ItemStack filler = pane(Material.MAGENTA_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        // Pointer arrows above and below the center reel slot.
        inv.setItem(CENTER - WINDOW, marker("&e▼"));   // slot 4
        inv.setItem(CENTER + WINDOW, marker("&e▲"));   // slot 22
    }

    private ItemStack marker(String name) {
        ItemStack it = new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.text(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(" ")
                    .decoration(TextDecoration.ITALIC, false));
            it.setItemMeta(meta);
        }
        return it;
    }
}
