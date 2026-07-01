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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * In-memory cache of each online player's ender-chest contents, so the
     * open/close hot path never touches disk on the main thread. Loaded lazily
     * from disk on first open and evicted on quit. The stored array is the
     * authoritative snapshot; values may be {@code null} for empty slots.
     */
    private final Map<UUID, ItemStack[]> cache = new ConcurrentHashMap<>();

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
        // Persist any big ender chests still open (e.g. on /stop) into the cache,
        // then flush everything synchronously since the server is shutting down.
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof EnderHolder h) cache.put(h.owner, snapshot(top));
        }
        for (Map.Entry<UUID, ItemStack[]> e : cache.entrySet()) {
            writeToDisk(e.getKey(), e.getValue());
        }
        cache.clear();
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
        // PlayerInteractEvent fires once per hand — only open for the main hand
        // so we don't open (and play the sound) twice per click.
        if (e.getHand() == EquipmentSlot.HAND) open(p);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (inv.getHolder() instanceof EnderHolder h) {
            // Update the in-memory cache (main thread), then persist off-thread.
            ItemStack[] snap = snapshot(inv);
            cache.put(h.owner, snap);
            saveAsync(h.owner, snap);
            if (e.getPlayer() instanceof Player p) {
                p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.5f, 1.0f);
            }
        }
    }

    /** When a player leaves, flush their data off-thread and evict the cache. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        ItemStack[] snap = cache.get(id);
        if (snap == null) return;
        if (!isEnabled()) {
            writeToDisk(id, snap);
            cache.remove(id, snap);
            return;
        }
        // Evict only AFTER the write lands, and only if no newer snapshot was
        // cached meanwhile (instant rejoin) — otherwise a rejoin between quit
        // and write-completion would load stale data from disk.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            writeToDisk(id, snap);
            cache.remove(id, snap);
        });
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
        ItemStack[] contents = cache.computeIfAbsent(p.getUniqueId(), id -> loadFromDisk(p, id));
        for (int i = 0; i < contents.length && i < inv.getSize(); i++) {
            if (contents[i] != null) inv.setItem(i, contents[i].clone());
        }
    }

    /**
     * Reads a player's stored contents from disk (called at most once per
     * session, on first open). Returns an array sized to {@link #slots}; empty
     * slots are {@code null}.
     */
    private ItemStack[] loadFromDisk(Player p, UUID id) {
        ItemStack[] contents = new ItemStack[slots];
        File f = fileFor(id);
        if (!f.exists()) {
            // First open ever: bring across the vanilla 27-slot ender chest so the
            // player doesn't "lose" their old items when we take over.
            if (importVanilla) {
                ItemStack[] vanilla = p.getEnderChest().getContents();
                for (int i = 0; i < vanilla.length && i < contents.length; i++) {
                    if (vanilla[i] != null) contents[i] = vanilla[i].clone();
                }
            }
            return contents;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        List<?> list = cfg.getList("contents");
        if (list == null) return contents;
        for (int i = 0; i < list.size() && i < contents.length; i++) {
            Object o = list.get(i);
            if (o instanceof ItemStack it) contents[i] = it;
        }
        return contents;
    }

    /** Takes a detached, cloned snapshot of an inventory's contents. */
    private ItemStack[] snapshot(Inventory inv) {
        ItemStack[] src = inv.getContents();
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = (src[i] == null) ? null : src[i].clone(); // nulls preserved as empty slots
        }
        return out;
    }

    /** Persists a snapshot off the main thread; the snapshot must be detached. */
    private void saveAsync(UUID id, ItemStack[] snapshot) {
        if (!isEnabled()) {
            // During disable async tasks can't be scheduled; write inline.
            writeToDisk(id, snapshot);
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> writeToDisk(id, snapshot));
    }

    /** Serializes and writes a detached snapshot to disk. Thread-safe.
     *  Synchronized so overlapping async saves (close + quit, or quit +
     *  onDisable flush) never interleave writes to the same file. */
    private synchronized void writeToDisk(UUID id, ItemStack[] snapshot) {
        FileConfiguration cfg = new YamlConfiguration();
        List<ItemStack> contents = new ArrayList<>(snapshot.length);
        for (ItemStack it : snapshot) contents.add(it); // nulls preserved as empty slots
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
