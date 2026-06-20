package com.ferisooo.kawaiiquests;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Builds the two menu screens: difficulty SELECT and the ACTIVE quest view. */
public final class QuestGui {

    // Slot layout (27-slot chest). SELECT screen spreads four tiers across the row.
    public static final int SLOT_EASY   = 10;
    public static final int SLOT_MEDIUM = 12;
    public static final int SLOT_HARD   = 14;
    public static final int SLOT_BRUTAL = 16;
    // ACTIVE screen.
    public static final int SLOT_QUEST  = 13;
    public static final int SLOT_ABANDON = 16;

    private final KawaiiQuests plugin;

    public QuestGui(KawaiiQuests plugin) {
        this.plugin = plugin;
    }

    /** Open whichever screen fits the player's state. */
    public void open(Player p) {
        if (plugin.questManager().has(p.getUniqueId())) {
            openActive(p);
        } else {
            openSelect(p);
        }
    }

    // ------------------------------------------------------------ select screen

    private void openSelect(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.SELECT);
        Inventory inv = Bukkit.createInventory(holder, 27,
                plugin.text("&d✧ Choose a Quest ✧"));
        holder.setInventory(inv);

        frame(inv, Material.PINK_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE);

        inv.setItem(SLOT_EASY, button(Material.LIME_WOOL, Difficulty.EASY,
                "Gentle objectives, cozy rewards."));
        inv.setItem(SLOT_MEDIUM, button(Material.YELLOW_WOOL, Difficulty.MEDIUM,
                "A real challenge with shiny loot."));
        inv.setItem(SLOT_HARD, button(Material.RED_WOOL, Difficulty.HARD,
                "Tough quests, great rewards~"));
        inv.setItem(SLOT_BRUTAL, button(Material.NETHERITE_BLOCK, Difficulty.BRUTAL,
                "Only for the fearless. The finest loot."));

        p.openInventory(inv);
    }

    private ItemStack button(Material mat, Difficulty diff, String blurb) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.text(diff.display() + " Quest"));
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.text("&7" + blurb));
            lore.add(Component.empty());
            lore.add(plugin.text("&7Objective amount: &f"
                    + plugin.minAmount(diff) + "&7-&f" + plugin.maxAmount(diff)));
            lore.add(Component.empty());
            lore.add(plugin.text("&8▶ Click to roll your quest~"));
            meta.lore(lore);
            glint(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ------------------------------------------------------------ active screen

    private void openActive(Player p) {
        QuestManager.Active a = plugin.questManager().get(p.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.ACTIVE);
        Inventory inv = Bukkit.createInventory(holder, 27,
                plugin.text("&d✧ Your Quest ✧"));
        holder.setInventory(inv);

        frame(inv, Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE);

        Quest q = a.quest;
        int pct = q.getAmount() <= 0 ? 100
                : (int) Math.round((double) a.progress / q.getAmount() * 100);
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.text(q.getDifficulty().display() + " &f" + q.getTitle()));
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.text("&7" + q.getDescription()));
            lore.add(Component.empty());
            lore.add(plugin.text("&dObjective: &f" + KawaiiQuests.objective(q)));
            lore.add(plugin.text("&dProgress: &f" + a.progress + "&7/&f" + q.getAmount()
                    + " &8(" + pct + "%)"));
            lore.add(progressBar(a.progress, q.getAmount()));
            if (q.hasReward()) {
                lore.add(Component.empty());
                lore.add(plugin.text("&6Reward: &f" + q.getRewardAmount() + "x "
                        + KawaiiQuests.pretty(q.getRewardItem())));
            }
            meta.lore(lore);
            glint(meta);
            book.setItemMeta(meta);
        }
        inv.setItem(SLOT_QUEST, book);

        ItemStack abandon = new ItemStack(Material.BARRIER);
        ItemMeta am = abandon.getItemMeta();
        if (am != null) {
            am.displayName(plugin.text("&c✖ Abandon Quest"));
            am.lore(List.of(plugin.text("&7Give up and pick a new one.")));
            glint(am);
            abandon.setItemMeta(am);
        }
        inv.setItem(SLOT_ABANDON, abandon);

        p.openInventory(inv);
    }

    /** A smooth dark-green → lime gradient bar; unfilled segments stay dark gray. */
    private Component progressBar(int progress, int amount) {
        int slots = 20;
        int filled = amount <= 0 ? slots : (int) Math.round((double) progress / amount * slots);
        filled = Math.max(0, Math.min(slots, filled));
        var bar = Component.text();
        for (int i = 0; i < slots; i++) {
            if (i < filled) {
                double t = slots <= 1 ? 0 : (double) i / (slots - 1);
                bar.append(Component.text("■").color(lerp(0x1E7F3C, 0x7CFF6B, t)));
            } else {
                bar.append(Component.text("■", NamedTextColor.DARK_GRAY));
            }
        }
        return bar.build().decoration(TextDecoration.ITALIC, false);
    }

    /** Linear RGB interpolation between two packed 0xRRGGBB colors. */
    static TextColor lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
        int g = (int) Math.round(((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
        int bl = (int) Math.round((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
        return TextColor.color(r, g, bl);
    }

    /** Add an enchant-glint sheen to an item (no real enchantment). */
    private static void glint(ItemMeta meta) {
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
            // Older API without the glint override — skip the sheen silently.
        }
    }

    /** Fill the outer ring with {@code border} and the interior with {@code inner}. */
    private void frame(Inventory inv, Material border, Material inner) {
        ItemStack edge = pane(border);
        ItemStack mid = pane(inner);
        for (int i = 0; i < 27; i++) {
            boolean isEdge = i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8;
            inv.setItem(i, isEdge ? edge : mid);
        }
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
