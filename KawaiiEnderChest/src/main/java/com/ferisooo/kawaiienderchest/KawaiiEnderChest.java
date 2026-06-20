package com.ferisooo.kawaiienderchest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bigger ender chest: 54 slots (two vanilla chests combined) per player.
 *
 * <p>The vanilla ender chest is only 27 slots and can't be resized, so we
 * intercept the open and show a custom 54-slot inventory instead, backed by a
 * per-player YAML file under {@code plugins/KawaiiEnderChest/enderchests/}.
 * 54 slots is Minecraft's largest single container (a double chest), so it
 * renders correctly for Bedrock players through Geyser too.
 *
 * <p>On a player's first open we copy whatever was in their original 27-slot
 * vanilla ender chest into the new one, so nothing is lost in the upgrade.
 */
public final class KawaiiEnderChest extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('§').build();

    private int slots;
    private Component title;
    private boolean importVanilla;
    private File dataDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        dataDir = new File(getDataFolder(), "enderchests");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().warning("(✧) couldn't create enderchests data folder!");
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("(✧) KawaiiEnderChest ready ~ " + slots + " slots");
    }

    @Override
    public void onDisable() {
        // Persist any big ender chests still open (e.g. on /stop).
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof EnderHolder h) save(h.owner, top);
        }
    }

    private void readConfig() {
        reloadConfig();
        int s = getConfig().getInt("slots", 54);
        // Clamp to a legal container size: multiple of 9, 9..54.
        s = Math.max(9, Math.min(54, s));
        s -= (s % 9);
        slots = s;
        title = LEGACY.deserialize(getConfig().getString("title", "§d✿ Ender Chest ✿"));
        importVanilla = getConfig().getBoolean("import-vanilla-on-first-open", true);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!"kawaiienderchest".equalsIgnoreCase(command.getName())) return false;

        if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("kawaiienderchest.admin") && !sender.isOp()) {
                sender.sendMessage("§d(✧) you don't have permission~");
                return true;
            }
            readConfig();
            sender.sendMessage("§d(✧) KawaiiEnderChest reloaded ✨  slots=" + slots);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c(✧) /kec must be run by a player");
            return true;
        }
        if (!p.hasPermission("kawaiienderchest.use")) {
            p.sendMessage("§d(✧) you don't have permission~");
            return true;
        }
        open(p);
        return true;
    }

    /** Right-clicking an ender chest block opens our big version instead. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENDER_CHEST) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("kawaiienderchest.use")) return; // fall through to vanilla

        // If the player is sneaking with a placeable block in hand, they're
        // trying to place it against the chest — don't hijack that.
        if (p.isSneaking()) {
            ItemStack inHand = e.getItem();
            if (inHand != null && inHand.getType().isBlock() && !inHand.getType().isAir()) return;
        }

        e.setCancelled(true); // stop the vanilla 27-slot ender chest from opening
        open(p);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (inv.getHolder() instanceof EnderHolder h) {
            save(h.owner, inv);
            if (e.getPlayer() instanceof Player p) {
                p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.5f, 1.0f);
            }
        }
    }

    // ============== open / load / save ==============

    private void open(Player p) {
        Inventory inv = Bukkit.createInventory(new EnderHolder(p.getUniqueId()), slots, title);
        load(p, inv);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.0f);
    }

    private File fileFor(UUID id) {
        return new File(dataDir, id.toString() + ".yml");
    }

    private void load(Player p, Inventory inv) {
        File f = fileFor(p.getUniqueId());
        if (!f.exists()) {
            // First open: bring across the vanilla 27-slot ender chest so the
            // player doesn't "lose" their old items when we take over.
            if (importVanilla) {
                ItemStack[] vanilla = p.getEnderChest().getContents();
                for (int i = 0; i < vanilla.length && i < inv.getSize(); i++) {
                    if (vanilla[i] != null) inv.setItem(i, vanilla[i].clone());
                }
            }
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        List<?> list = cfg.getList("contents");
        if (list == null) return;
        for (int i = 0; i < list.size() && i < inv.getSize(); i++) {
            Object o = list.get(i);
            if (o instanceof ItemStack it) inv.setItem(i, it);
        }
    }

    private void save(UUID id, Inventory inv) {
        FileConfiguration cfg = new YamlConfiguration();
        List<ItemStack> contents = new ArrayList<>(inv.getSize());
        for (ItemStack it : inv.getContents()) contents.add(it); // nulls preserved as empty slots
        cfg.set("contents", contents);
        try {
            cfg.save(fileFor(id));
        } catch (IOException ex) {
            getLogger().warning("(✧) couldn't save ender chest for " + id + ": " + ex.getMessage());
        }
    }

    /** Marks an inventory as ours and remembers whose it is. */
    private static final class EnderHolder implements InventoryHolder {
        private final UUID owner;
        private EnderHolder(UUID owner) { this.owner = owner; }
        @Override public @NotNull Inventory getInventory() {
            // Not used — Bukkit only needs this for inventories it constructs
            // around a holder; ours is created via Bukkit.createInventory.
            throw new UnsupportedOperationException();
        }
    }
}
