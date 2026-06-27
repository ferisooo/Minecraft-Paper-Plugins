package com.ferisooo.kawaiiquests;

import net.kyori.adventure.text.Component;
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
import java.util.List;

/**
 * A CS:GO-style crate roll shown the moment a player picks a difficulty. A reel
 * of cute quest-themed icons scrolls while the AI writes the quest in the
 * background; once the quest is ready ({@link #reveal}) the reel eases to a stop
 * on the new quest scroll, then a callback opens the quest menu. Update rate is
 * kept modest so it stays smooth for Bedrock players viewing it through Geyser.
 */
public final class QuestCrateAnimation implements Runnable {

    // Middle row of a 27-slot chest is slots 9..17; the pointer sits on 13.
    private static final int ROW_START = 9;
    private static final int CENTER = 13;
    private static final int WINDOW = 9;
    private static final int CENTER_OFFSET = CENTER - ROW_START; // 4
    // Safety cap: if a result never arrives, don't spin forever.
    private static final int MAX_SPIN = 600;

    private final KawaiiQuests plugin;
    private final Player player;
    private final Difficulty diff;
    private final Inventory inv;
    private final List<ItemStack> pool = new ArrayList<>();
    private final int minSteps;

    private int pos = 0;          // window start index into the (cyclic) pool
    private int spun = 0;         // total steps spun so far
    private boolean stopping = false;
    private int easeStart = 0;
    private int stopAt = -1;

    private Quest result;         // set by reveal() once the quest exists
    private Runnable onDone;      // run after the reveal settles

    public QuestCrateAnimation(KawaiiQuests plugin, Player player, Difficulty diff) {
        this.plugin = plugin;
        this.player = player;
        this.diff = diff;
        this.minSteps = Math.max(8, plugin.getConfig().getInt("crate.spin-steps", 22));

        GuiHolder holder = new GuiHolder(GuiHolder.Kind.CRATE);
        this.inv = Bukkit.createInventory(holder, 27, plugin.text("&d✧ Rolling your Quest ✧"));
        holder.setInventory(inv);
        buildPool();
        decorate();
    }

    /** Open the crate and start the spin. Safe to call before {@link #reveal}. */
    public void start() {
        render(pos);
        player.openInventory(inv);
        // Folia-safe: the animation touches this player only — run it on the
        // player's entity scheduler.
        player.getScheduler().run(plugin, t -> run(), null);
    }

    /** Hand the finished quest to the reel; it eases to a stop and reveals it. */
    public void reveal(Quest quest, Runnable onDone) {
        this.result = quest;
        this.onDone = onDone;
    }

    @Override
    public void run() {
        if (!player.isOnline()) return; // nothing left to show
        // If they navigated away from the crate, quietly stop animating it.
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder gh)
                || gh.kind() != GuiHolder.Kind.CRATE) {
            return;
        }

        render(pos);
        float pitch = 0.8f + Math.min(1.2f, spun * 0.04f);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, pitch);

        if (stopping) {
            if (pos >= stopAt) { finish(); return; }
            // Ease-out: delay grows as we approach the stop so the reel slows down.
            long delay = 1 + Math.round(6.0 * (pos - easeStart) / Math.max(1, stopAt - easeStart));
            pos++;
            player.getScheduler().runDelayed(plugin, t -> run(), null, Math.max(1, delay));
            return;
        }

        pos++;
        spun++;

        // Keep spinning at a steady clip until the quest is ready (or we hit the
        // safety cap), then begin easing to a stop.
        if ((result != null && spun >= minSteps) || spun >= MAX_SPIN) {
            beginStop();
        }
        player.getScheduler().runDelayed(plugin, t -> run(), null, 2L);
    }

    private void beginStop() {
        stopping = true;
        int easeSteps = 14;
        easeStart = pos;
        stopAt = pos + easeSteps;
        // Land the reveal icon dead-center on the final frame.
        int winIndex = Math.floorMod(stopAt + CENTER_OFFSET, pool.size());
        pool.set(winIndex, revealIcon());
    }

    private void finish() {
        inv.setItem(CENTER, revealIcon()); // make sure the win is shown
        highlightWin();
        if (player.isOnline()) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
            if (plugin.getConfig().getBoolean("effects", true)) {
                var loc = player.getLocation().add(0, 1.0, 0);
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 22, 0.5, 0.6, 0.5, 0.25);
                player.spawnParticle(Particle.FIREWORK, loc, 25, 0.4, 0.5, 0.4, 0.06);
            }
        }
        // Let them admire the new quest scroll, then open the quest menu.
        player.getScheduler().runDelayed(plugin, t -> {
            if (onDone != null && player.isOnline()) onDone.run();
        }, null, 35L);
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
        int size = pool.size();
        for (int j = 0; j < WINDOW; j++) {
            inv.setItem(ROW_START + j, pool.get(Math.floorMod(start + j, size)));
        }
    }

    private void buildPool() {
        Material tier = switch (diff) {
            case EASY -> Material.LIME_WOOL;
            case MEDIUM -> Material.YELLOW_WOOL;
            case HARD -> Material.RED_WOOL;
            case BRUTAL -> Material.NETHERITE_BLOCK;
        };
        Material[] mats = {
                Material.PAPER, Material.BOOK, Material.IRON_PICKAXE, Material.IRON_SWORD,
                tier, Material.CHEST, Material.EXPERIENCE_BOTTLE, Material.MAP,
                Material.WRITABLE_BOOK, tier, Material.COMPASS, Material.NAME_TAG,
        };
        // A few cycles so the reel reads as "endless" while it scrolls.
        for (int c = 0; c < 3; c++) {
            for (Material m : mats) pool.add(icon(m));
        }
    }

    private ItemStack revealIcon() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null && result != null) {
            meta.displayName(plugin.text(result.getDifficulty().display()
                    + " &f" + result.getTitle()));
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.text("&7" + result.getDescription()));
            lore.add(Component.empty());
            lore.add(plugin.text("&dObjective: &f" + KawaiiQuests.objective(result)));
            lore.add(plugin.text("&8Your new quest~ ✿"));
            meta.lore(lore);
            book.setItemMeta(meta);
        }
        return book;
    }

    private void decorate() {
        ItemStack filler = pane(Material.MAGENTA_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        // Pointer arrows above and below the center reel slot.
        inv.setItem(CENTER - WINDOW, marker("&e▼"));   // slot 4
        inv.setItem(CENTER + WINDOW, marker("&e▲"));   // slot 22
    }

    private ItemStack icon(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.text("&d✧ " + KawaiiQuests.pretty(mat.name())));
            it.setItemMeta(meta);
        }
        return it;
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
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            it.setItemMeta(meta);
        }
        return it;
    }
}
