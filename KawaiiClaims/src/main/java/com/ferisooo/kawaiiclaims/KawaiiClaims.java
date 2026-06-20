package com.ferisooo.kawaiiclaims;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class KawaiiClaims extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    public static final String PREFIX = ChatColor.LIGHT_PURPLE + "[KawaiiClaims] " + ChatColor.RESET;

    private ClaimManager manager;

    private final java.util.Set<UUID> bypassing = new java.util.HashSet<>();
    private final Map<UUID, Long> lastDenial = new HashMap<>();
    private static final long DENIAL_COOLDOWN_MS = 3000L;

    private int expirationTaskId = -1;

    // -----------------------------------------------------------------
    //  Animated GUI
    // -----------------------------------------------------------------
    private static final Material[] SHIMMER = {
            Material.PINK_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE };
    private int guiFrame = 0;

    // -----------------------------------------------------------------
    //  Claim tool tracking
    // -----------------------------------------------------------------
    private NamespacedKey toolKey;
    private final java.util.Set<UUID> givenTool = new java.util.HashSet<>();
    private File givenToolFile;

    // -----------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------
    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new ClaimManager(this);
        manager.loadFlagDefinitions();
        manager.load();

        toolKey = new NamespacedKey(this, "claim_tool");
        loadGivenTool();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new ProtectionListeners(this), this);
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);

        if (getCommand("claim") != null) {
            getCommand("claim").setExecutor(this);
            getCommand("claim").setTabCompleter(this);
        }

        // Shimmer the open claim menus.
        Bukkit.getScheduler().runTaskTimer(this, this::animateMenus, 4L, 4L);

        scheduleExpiration();
        getLogger().info("KawaiiClaims enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.save();
        getLogger().info("KawaiiClaims disabled.");
    }

    private void scheduleExpiration() {
        if (expirationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(expirationTaskId);
            expirationTaskId = -1;
        }
        int hours = getConfig().getInt("expiration-check-hours", 12);
        if (getConfig().getInt("expiration-days", 0) <= 0 || hours <= 0) return;
        long ticks = (long) hours * 60L * 60L * 20L;
        expirationTaskId = Bukkit.getScheduler().runTaskTimer(this,
                () -> manager.runExpirationSweep(), ticks, ticks).getTaskId();
    }

    public ClaimManager getClaimManager() { return manager; }

    public UUID getServerUuid() {
        String s = getConfig().getString("server-uuid", "");
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  Messaging helpers
    // -----------------------------------------------------------------
    public boolean isBypassing(Player player) {
        return player.hasPermission("kawaiiclaims.admin") && bypassing.contains(player.getUniqueId());
    }

    public void msg(CommandSender to, String s) {
        to.sendMessage(PREFIX + ChatColor.translateAlternateColorCodes('&', s));
    }

    /** Rate-limited denial message (one per player per ~3s). */
    public void denied(Player player, String action) {
        long now = System.currentTimeMillis();
        Long last = lastDenial.get(player.getUniqueId());
        if (last != null && now - last < DENIAL_COOLDOWN_MS) return;
        lastDenial.put(player.getUniqueId(), now);
        player.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to " + action + ".");
        player.playSound(player.getLocation(), "minecraft:block.note_block.bass", 0.7f, 0.8f);
    }

    /** Greeting/farewell crossing message (action bar or chat). */
    public void sendCrossingMessage(Player player, String raw) {
        String text = ChatColor.translateAlternateColorCodes('&', raw);
        if (getConfig().getBoolean("messages-on-actionbar", true)) {
            player.sendActionBar(Component.text(text));
        } else {
            player.sendMessage(text);
        }
    }

    private void success(Player p) {
        p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1f);
    }

    // -----------------------------------------------------------------
    //  Claim tool (golden shovel)
    // -----------------------------------------------------------------
    /** Build the named, PDC-tagged golden shovel claim tool. */
    private ItemStack makeClaimTool() {
        ItemStack tool = new ItemStack(Material.GOLDEN_SHOVEL);
        ItemMeta meta = tool.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "✦ Claim Tool");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Right-click a block: " + ChatColor.AQUA + "claim this chunk",
                    ChatColor.GRAY + "Left-click a block: " + ChatColor.AQUA + "inspect this chunk"));
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
            tool.setItemMeta(meta);
        }
        return tool;
    }

    /** Give the claim tool, dropping at feet if the inventory is full. */
    private void giveClaimTool(Player player) {
        ItemStack tool = makeClaimTool();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(tool);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.playSound(player.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1.2f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("give-tool-on-join", true)) return;
        Player player = e.getPlayer();
        if (!player.hasPermission("kawaiiclaims.use")) return;
        if (givenTool.contains(player.getUniqueId())) return;
        givenTool.add(player.getUniqueId());
        saveGivenTool();
        giveClaimTool(player);
        msg(player, "&dHere's your &a✦ Claim Tool&d! Right-click a block to claim a chunk.");
    }

    @SuppressWarnings("unchecked")
    private void loadGivenTool() {
        givenToolFile = new File(getDataFolder(), "given-tool.yml");
        if (!givenToolFile.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration y =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(givenToolFile);
        for (String s : y.getStringList("given")) {
            try { givenTool.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveGivenTool() {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID u : givenTool) list.add(u.toString());
        y.set("given", list);
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            y.save(givenToolFile);
        } catch (java.io.IOException ex) {
            getLogger().warning("Could not save given-tool.yml: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Golden shovel + GUI clicks
    // -----------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onShovel(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.GOLDEN_SHOVEL) return;
        if (e.getClickedBlock() == null) return;

        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            doClaim(player);
        } else if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            e.setCancelled(true);
            Claim claim = manager.getClaimAt(player.getLocation());
            if (claim == null) {
                msg(player, "&7This chunk is &funclaimed wilderness&7.");
            } else {
                msg(player, "&dThis chunk is claimed by &f" + ownerName(claim.getOwner()) + "&d.");
                manager.showBorder(player, claim);
            }
        }
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ClaimGuiHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;

        Claim claim = manager.getClaimById(holder.getClaimId());
        if (claim == null) {
            player.closeInventory();
            msg(player, "&cThat claim no longer exists.");
            return;
        }

        switch (holder.getType()) {
            case MENU -> handleMenuClick(player, claim, e.getSlot(), e.isShiftClick());
            case FLAGS -> handleFlagClick(player, claim, holder, e.getSlot());
            case TRUST -> handleTrustClick(player, claim, e.getSlot(), e.getCurrentItem());
            case ROLES -> handleRolesClick(player, claim, e.getSlot());
            case PERMS -> handlePermClick(player, claim, holder, e.getSlot());
            case PRESETS -> handlePresetClick(player, claim, e.getSlot());
        }
    }

    // -----------------------------------------------------------------
    //  Command dispatch
    // -----------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("KawaiiClaims commands are player-only.");
            return true;
        }
        if (!player.hasPermission("kawaiiclaims.use")) {
            msg(player, "&cYou don't have permission to use claims.");
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "claim" -> doClaim(player);
            case "radius" -> doRadius(player, args);
            case "unclaim", "abandon" -> doUnclaim(player, args);
            case "list" -> doList(player);
            case "info" -> doInfo(player);
            case "trust" -> doTrust(player, args);
            case "untrust" -> doUntrust(player, args);
            case "flag" -> doFlag(player, args);
            case "flags" -> openFlags(player);
            case "preset" -> doPreset(player, args);
            case "show", "border" -> doShow(player);
            case "perm" -> doPerm(player, args);
            case "perms", "roles", "permissions" -> openRoles(player);
            case "tool", "wand" -> {
                giveClaimTool(player);
                msg(player, "&aHere's a fresh &a✦ Claim Tool&a!");
            }
            case "sethome" -> doSetHome(player);
            case "home" -> doHome(player);
            case "transfer" -> doTransfer(player, args);
            case "greeting" -> doGreeting(player, args);
            case "farewell" -> doFarewell(player, args);
            case "menu" -> openMenu(player);
            case "bypass" -> doBypass(player);
            case "admin" -> doAdmin(player, args);
            case "help" -> sendHelp(player);
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player p) {
        msg(p, "&d&lKawaiiClaims commands:");
        p.sendMessage(ChatColor.GRAY + "/claim claim, unclaim [all], list, info");
        p.sendMessage(ChatColor.GRAY + "/claim radius <n> (claim a square of chunks around you)");
        p.sendMessage(ChatColor.GRAY + "/claim trust <player> <access|container|build|manage>");
        p.sendMessage(ChatColor.GRAY + "/claim untrust <player>");
        p.sendMessage(ChatColor.GRAY + "/claim flag <flag> <on|off>, flags");
        p.sendMessage(ChatColor.GRAY + "/claim preset <name> (apply a flag preset), show (toggle claim border)");
        p.sendMessage(ChatColor.GRAY + "/claim perm <role> <permission> <on|off>, perms (role editor)");
        p.sendMessage(ChatColor.GRAY + "/claim sethome, home, transfer <player>");
        p.sendMessage(ChatColor.GRAY + "/claim tool (get a Claim Tool), greeting <text>, farewell <text>, menu");
        if (p.hasPermission("kawaiiclaims.admin")) {
            p.sendMessage(ChatColor.GRAY + "/claim bypass, admin delete, admin list");
            p.sendMessage(ChatColor.GRAY + "/claim admin givechunks <player> <amount>, setchunks <player> <amount>");
        }
        p.sendMessage(ChatColor.GRAY + "Tip: a Golden Shovel claims (right-click) and inspects (left-click).");
    }

    private String ownerName(UUID uuid) {
        UUID server = getServerUuid();
        if (server != null && server.equals(uuid)) return "the server";
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() == null ? uuid.toString().substring(0, 8) : op.getName();
    }

    // -----------------------------------------------------------------
    //  Command implementations
    // -----------------------------------------------------------------
    private void doClaim(Player player) {
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        Chunk chunk = loc.getChunk();
        UUID id = player.getUniqueId();

        ClaimManager.ClaimResult result =
                manager.claimChunk(player, world, chunk.getX(), chunk.getZ());
        switch (result) {
            case WORLD_DISABLED -> msg(player, "&cClaiming is disabled in this world.");
            case ALREADY_CLAIMED -> {
                Claim existing = manager.getClaimInChunk(chunk);
                if (existing != null && existing.getOwner().equals(id)) {
                    msg(player, "&7You already own this chunk.");
                } else {
                    msg(player, "&cThis chunk is already claimed by &f"
                            + (existing == null ? "someone" : ownerName(existing.getOwner())) + "&c.");
                }
            }
            case LIMIT_REACHED -> msg(player, "&cYou've reached your chunk limit (&f"
                    + manager.getChunkLimit(id) + "&c).");
            default -> {
                Claim claim = manager.getClaimInChunk(chunk);
                String verb = switch (result) {
                    case MERGED -> "Claimed & merged this chunk!";
                    case ADDED -> "Added this chunk to your claim!";
                    default -> "Claimed this chunk!";
                };
                msg(player, "&a" + verb + " " + usageSuffix(player));
                success(player);
                if (claim != null) {
                    manager.showBorder(player, claim);
                    // Auto-set a home so /claim home can teleport you back from anywhere.
                    if (!claim.hasHome()) {
                        claim.setHome(player.getLocation());
                        manager.touchAndSave(claim);
                    }
                }
            }
        }
    }

    private void doRadius(Player player, String[] args) {
        if (!player.hasPermission("kawaiiclaims.radius")) {
            msg(player, "&cYou don't have permission to claim by radius.");
            return;
        }
        if (args.length < 2) {
            msg(player, "&cUsage: /claim radius <n>");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            msg(player, "&cThat's not a number.");
            return;
        }
        if (n < 0) {
            msg(player, "&cRadius must be 0 or more.");
            return;
        }
        int maxR = manager.getMaxRadius();
        if (n > maxR && !player.hasPermission("kawaiiclaims.admin")) {
            msg(player, "&cMax radius is &f" + maxR + "&c.");
            n = maxR;
        }
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        if (!manager.isWorldEnabled(world)) {
            msg(player, "&cClaiming is disabled in this world.");
            return;
        }
        Chunk chunk = loc.getChunk();
        int[] res = manager.claimRadius(player, world, chunk.getX(), chunk.getZ(), n);
        int claimed = res[0], skipped = res[1];
        if (claimed == 0) {
            msg(player, "&7No new chunks were claimed (&f" + skipped + "&7 skipped). " + usageSuffix(player));
            return;
        }
        msg(player, "&aClaimed &f" + claimed + "&a chunk(s)"
                + (skipped > 0 ? "&7, skipped &f" + skipped : "") + "&a. " + usageSuffix(player));
        success(player);
        Claim claim = manager.getClaimInChunk(chunk);
        if (claim != null) manager.showBorder(player, claim);
    }

    /** "(used/limit chunks)" suffix, omitting the limit for admins. */
    private String usageSuffix(Player player) {
        UUID id = player.getUniqueId();
        int used = manager.countChunks(id);
        if (player.hasPermission("kawaiiclaims.admin")) {
            return ChatColor.GRAY + "(" + used + " chunks)";
        }
        return ChatColor.GRAY + "(" + used + "/" + manager.getChunkLimit(id) + " chunks)";
    }

    private void doUnclaim(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            int count = manager.countChunks(player.getUniqueId());
            int deleted = manager.deleteAllOf(player.getUniqueId());
            if (deleted == 0) {
                msg(player, "&7You have no claims to abandon.");
            } else {
                msg(player, "&eAbandoned all your claims (&f" + deleted
                        + "&e claim(s), &f" + count + "&e chunk(s)).");
                success(player);
            }
            return;
        }
        Location loc = player.getLocation();
        Claim claim = manager.getClaimAt(loc);
        if (claim == null) {
            msg(player, "&7There's no claim here.");
            return;
        }
        if (!manager.isOwnerOrAdmin(player, claim)) {
            msg(player, "&cOnly the owner can abandon this chunk.");
            return;
        }
        Chunk chunk = loc.getChunk();
        manager.unclaimChunk(loc.getWorld().getName(), chunk.getX(), chunk.getZ());
        msg(player, "&eUnclaimed this chunk. " + usageSuffix(player));
        success(player);
    }

    private void doList(Player player) {
        UUID id = player.getUniqueId();
        List<Claim> mine = manager.getClaimsOf(id);
        if (mine.isEmpty()) {
            msg(player, "&7You have no claims yet. Use a Golden Shovel or &f/claim claim&7.");
            return;
        }
        msg(player, "&dYour claims (&f" + mine.size() + "&d claim(s), " + usageSuffix(player) + "&d):");
        for (Claim c : mine) {
            String anchor = describeAnchor(c);
            player.sendMessage(ChatColor.GRAY + " - " + c.getWorld() + " " + anchor
                    + ChatColor.GRAY + " (" + c.getChunkCount() + " chunk(s))"
                    + (c.hasHome() ? ChatColor.AQUA + " [home]" : ""));
        }
    }

    /** A human-friendly "@ chunk x,z" describing one of the claim's chunks. */
    private String describeAnchor(Claim claim) {
        String k = claim.anyChunkKey();
        if (k == null) return "@ (empty)";
        String[] parts = k.split(";");
        if (parts.length < 3) return "@ ?";
        return "@ chunk " + parts[1] + "," + parts[2];
    }

    private void doInfo(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7This chunk is unclaimed wilderness.");
            return;
        }
        msg(player, "&d&lClaim info");
        player.sendMessage(ChatColor.GRAY + "Owner: " + ChatColor.WHITE + ownerName(claim.getOwner()));
        player.sendMessage(ChatColor.GRAY + "World: " + ChatColor.WHITE + claim.getWorld()
                + " " + describeAnchor(claim));
        player.sendMessage(ChatColor.GRAY + "Chunks: " + ChatColor.WHITE + claim.getChunkCount());
        if (claim.getOwner().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.GRAY + "Your usage: " + ChatColor.WHITE + usageSuffix(player));
        }
        player.sendMessage(ChatColor.GRAY + "Trusted: " + ChatColor.WHITE + trustSummary(claim));
        StringBuilder fl = new StringBuilder();
        for (String f : manager.getFlagNames()) {
            boolean v = manager.flag(claim, f);
            fl.append(v ? ChatColor.GREEN : ChatColor.RED).append(f).append(ChatColor.GRAY).append(" ");
        }
        player.sendMessage(ChatColor.GRAY + "Flags: " + fl);
        manager.showBorder(player, claim);
    }

    private String trustSummary(Claim claim) {
        if (claim.getTrust().isEmpty()) return "(none)";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<UUID, TrustLevel> e : claim.getTrust().entrySet()) {
            parts.add(ownerName(e.getKey()) + "=" + e.getValue().display());
        }
        return String.join(", ", parts);
    }

    private void doTrust(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        if (args.length < 3) {
            msg(player, "&cUsage: /claim trust <player> <access|container|build|manage>");
            return;
        }
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null || target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "&cInvalid player.");
            return;
        }
        TrustLevel level = TrustLevel.fromString(args[2]);
        if (level == null) {
            msg(player, "&cUnknown level. Use access, container, build, or manage.");
            return;
        }
        claim.setTrust(target.getUniqueId(), level);
        manager.touchAndSave(claim);
        msg(player, "&aTrusted &f" + safeName(target) + "&a as &f" + level.display() + "&a.");
        success(player);
        if (target.isOnline() && target.getPlayer() != null) {
            msg(target.getPlayer(), "&aYou were trusted (&f" + level.display() + "&a) in a claim by &f"
                    + player.getName() + "&a.");
        }
    }

    private void doUntrust(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        if (args.length < 2) {
            msg(player, "&cUsage: /claim untrust <player>");
            return;
        }
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null) {
            msg(player, "&cInvalid player.");
            return;
        }
        if (claim.getTrustLevel(target.getUniqueId()) == null) {
            msg(player, "&7That player isn't trusted here.");
            return;
        }
        claim.removeTrust(target.getUniqueId());
        manager.touchAndSave(claim);
        msg(player, "&eRemoved &f" + safeName(target) + "&e's trust.");
        success(player);
        if (target.isOnline() && target.getPlayer() != null) {
            msg(target.getPlayer(), "&eYour trust was removed from a claim by &f" + player.getName() + "&e.");
        }
    }

    private void doFlag(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        if (args.length < 3) {
            msg(player, "&cUsage: /claim flag <flag> <on|off>. Flags: "
                    + String.join(", ", manager.getFlagNames()));
            return;
        }
        String flag = args[1].toLowerCase();
        if (!manager.isKnownFlag(flag)) {
            msg(player, "&cUnknown flag. Known: " + String.join(", ", manager.getFlagNames()));
            return;
        }
        Boolean val = parseOnOff(args[2]);
        if (val == null) {
            msg(player, "&cUse on or off.");
            return;
        }
        claim.setFlag(flag, val);
        manager.touchAndSave(claim);
        msg(player, "&aFlag &f" + flag + "&a set to " + (val ? "&aON" : "&cOFF") + "&a.");
        success(player);
    }

    private void doShow(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7Stand inside a claim to show its border.");
            return;
        }
        boolean on = manager.toggleBorder(player, claim);
        msg(player, on ? "&dShowing this claim's border for ~10s. Run again to hide."
                : "&7Hid the claim border.");
        success(player);
    }

    private void doPreset(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        List<String> names = manager.getPresetNames();
        if (args.length < 2) {
            msg(player, "&cUsage: /claim preset <name>. Presets: "
                    + (names.isEmpty() ? "(none configured)" : String.join(", ", names)));
            return;
        }
        String name = args[1].toLowerCase();
        if (!manager.isKnownPreset(name)) {
            msg(player, "&cUnknown preset. Presets: "
                    + (names.isEmpty() ? "(none configured)" : String.join(", ", names)));
            return;
        }
        manager.applyPreset(claim, name);
        msg(player, "&aApplied the &f" + name + "&a preset to this claim.");
        success(player);
    }

    private void doPerm(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        if (args.length < 4) {
            msg(player, "&cUsage: /claim perm <role> <permission> <on|off>");
            msg(player, "&7Roles: " + String.join(", ", TrustLevel.ROLE_NAMES));
            return;
        }
        String role = args[1].toLowerCase();
        boolean knownRole = false;
        for (String r : TrustLevel.ROLE_NAMES) if (r.equals(role)) knownRole = true;
        if (!knownRole) {
            msg(player, "&cUnknown role. Roles: " + String.join(", ", TrustLevel.ROLE_NAMES));
            return;
        }
        String perm = args[2].toLowerCase();
        if (!manager.isKnownPermission(perm)) {
            msg(player, "&cUnknown permission. See &f/claim perms&c for the list.");
            return;
        }
        Boolean val = parseOnOff(args[3]);
        if (val == null) {
            msg(player, "&cUse on or off.");
            return;
        }
        claim.setRolePerm(role, perm, val);
        manager.touchAndSave(claim);
        msg(player, "&aPermission &f" + perm + "&a for role &f" + role + "&a set to "
                + (val ? "&aON" : "&cOFF") + "&a.");
        success(player);
    }

    private void doSetHome(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&cStand inside your claim to set its home.");
            return;
        }
        if (!manager.isOwnerOrAdmin(player, claim)) {
            msg(player, "&cOnly the owner can set the claim home.");
            return;
        }
        claim.setHome(player.getLocation());
        manager.touchAndSave(claim);
        msg(player, "&aClaim home set.");
        success(player);
    }

    private void doHome(Player player) {
        List<Claim> mine = manager.getClaimsOf(player.getUniqueId());
        Claim target = null;
        // prefer the claim the player is standing in if it has a home
        Claim here = manager.getClaimAt(player.getLocation());
        if (here != null && here.getOwner().equals(player.getUniqueId()) && here.hasHome()) {
            target = here;
        } else {
            for (Claim c : mine) {
                if (c.hasHome()) { target = c; break; }
            }
        }
        if (mine.isEmpty()) {
            msg(player, "&7You don't own any claims yet. Stand on land and use &f/claim&7.");
            return;
        }
        Location dest;
        if (target != null) {
            // explicit home set
            Location home = target.getHome();
            if (home == null || home.getWorld() == null) {
                msg(player, "&cThat home's world isn't loaded.");
                return;
            }
            dest = home.clone().add(0.5, 0, 0.5);
        } else {
            // No home set anywhere — fall back to the centre of the player's first claim
            // so teleporting back to a claim works even without /claim sethome.
            dest = chunkCenter(mine.get(0));
            if (dest == null) {
                msg(player, "&cThat claim's world isn't loaded.");
                return;
            }
        }
        dest.getWorld().getChunkAt(dest); // ensure chunk loaded
        player.teleport(dest);
        msg(player, "&aWhoosh! Teleported to your claim.");
        success(player);
    }

    /** A safe-ish teleport location at the centre of one of a claim's chunks. */
    private Location chunkCenter(Claim claim) {
        String key = claim.anyChunkKey();
        if (key == null) return null;
        String[] parts = key.split(";");
        if (parts.length != 3) return null;
        org.bukkit.World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        int cx, cz;
        try { cx = Integer.parseInt(parts[1]); cz = Integer.parseInt(parts[2]); }
        catch (NumberFormatException e) { return null; }
        int x = (cx << 4) + 8;
        int z = (cz << 4) + 8;
        int y = w.getHighestBlockYAt(x, z) + 1;
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    private void doTransfer(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7There's no claim here.");
            return;
        }
        if (!claim.getOwner().equals(player.getUniqueId()) && !player.hasPermission("kawaiiclaims.admin")) {
            msg(player, "&cOnly the owner can transfer this claim.");
            return;
        }
        if (args.length < 2) {
            msg(player, "&cUsage: /claim transfer <player>");
            return;
        }
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null || target.getUniqueId().equals(claim.getOwner())) {
            msg(player, "&cInvalid player.");
            return;
        }
        manager.transferClaim(claim, target.getUniqueId());
        msg(player, "&aTransferred this claim to &f" + safeName(target) + "&a.");
        success(player);
        if (target.isOnline() && target.getPlayer() != null) {
            msg(target.getPlayer(), "&aYou received a claim from &f" + player.getName() + "&a.");
        }
    }

    private void doGreeting(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        String text = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        claim.setGreeting(text);
        manager.touchAndSave(claim);
        msg(player, text.isEmpty() ? "&eGreeting cleared." : "&aGreeting set.");
        success(player);
    }

    private void doFarewell(Player player, String[] args) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (notManager(player, claim)) return;
        String text = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        claim.setFarewell(text);
        manager.touchAndSave(claim);
        msg(player, text.isEmpty() ? "&eFarewell cleared." : "&aFarewell set.");
        success(player);
    }

    private void doBypass(Player player) {
        if (!player.hasPermission("kawaiiclaims.admin")) {
            msg(player, "&cYou don't have permission.");
            return;
        }
        UUID id = player.getUniqueId();
        if (bypassing.contains(id)) {
            bypassing.remove(id);
            msg(player, "&7Protection bypass &cOFF&7.");
        } else {
            bypassing.add(id);
            msg(player, "&7Protection bypass &aON&7. You now ignore all protection.");
        }
        success(player);
    }

    private void doAdmin(Player player, String[] args) {
        if (!player.hasPermission("kawaiiclaims.admin")) {
            msg(player, "&cYou don't have permission.");
            return;
        }
        if (args.length < 2) {
            msg(player, "&cUsage: /claim admin <delete|list|givechunks|setchunks>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "delete" -> {
                Claim claim = manager.getClaimAt(player.getLocation());
                if (claim == null) {
                    msg(player, "&7No claim here to delete.");
                    return;
                }
                manager.deleteClaim(claim);
                msg(player, "&cForce-deleted the claim here (was owned by &f"
                        + ownerName(claim.getOwner()) + "&c).");
                success(player);
            }
            case "list" -> {
                msg(player, "&d&lClaim statistics");
                player.sendMessage(ChatColor.GRAY + "Total claims: " + ChatColor.WHITE + manager.getClaimCount()
                        + ChatColor.GRAY + ", chunks: " + ChatColor.WHITE + manager.getChunkCount());
                Map<UUID, Integer> perOwner = manager.chunksPerOwner();
                List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(perOwner.entrySet());
                entries.sort((a, b) -> b.getValue() - a.getValue());
                int shown = 0;
                for (Map.Entry<UUID, Integer> en : entries) {
                    if (shown++ >= 15) {
                        player.sendMessage(ChatColor.DARK_GRAY + "  ...and more.");
                        break;
                    }
                    player.sendMessage(ChatColor.GRAY + "  " + ownerName(en.getKey())
                            + ": " + ChatColor.WHITE + en.getValue() + " chunk(s)");
                }
            }
            case "givechunks", "setchunks" -> doChunkGrant(player, args);
            default -> msg(player, "&cUsage: /claim admin <delete|list|givechunks|setchunks>");
        }
    }

    private void doChunkGrant(Player player, String[] args) {
        boolean set = args[1].equalsIgnoreCase("setchunks");
        if (args.length < 4) {
            msg(player, "&cUsage: /claim admin " + (set ? "setchunks" : "givechunks") + " <player> <amount>");
            return;
        }
        OfflinePlayer target = resolveOffline(args[2]);
        if (target == null) {
            msg(player, "&cInvalid player.");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            msg(player, "&cThat's not a number.");
            return;
        }
        UUID tid = target.getUniqueId();
        if (set) {
            manager.setBonusChunks(tid, amount);
            msg(player, "&aSet &f" + safeName(target) + "&a's bonus chunks to &f" + amount
                    + "&a (limit now &f" + manager.getChunkLimit(tid) + "&a).");
        } else {
            manager.addBonusChunks(tid, amount);
            msg(player, "&aGave &f" + safeName(target) + "&a &f" + amount
                    + "&a bonus chunk(s) (bonus now &f" + manager.getBonusChunks(tid)
                    + "&a, limit &f" + manager.getChunkLimit(tid) + "&a).");
        }
        success(player);
    }

    // shared guard: requires MANAGE (or owner/admin) on the claim here
    private boolean notManager(Player player, Claim claim) {
        if (claim == null) {
            msg(player, "&7There's no claim here.");
            return true;
        }
        if (!manager.canAct(player, claim, TrustLevel.MANAGE)) {
            msg(player, "&cYou need MANAGE trust (or ownership) here.");
            return true;
        }
        return false;
    }

    private Boolean parseOnOff(String s) {
        String l = s.toLowerCase();
        if (l.equals("on") || l.equals("true") || l.equals("yes")) return true;
        if (l.equals("off") || l.equals("false") || l.equals("no")) return false;
        return null;
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer resolveOffline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (op.hasPlayedBefore() || op.isOnline()) return op;
        return op; // best effort; UUID still resolvable for trust
    }

    private String safeName(OfflinePlayer op) {
        return op.getName() == null ? "player" : op.getName();
    }

    // -----------------------------------------------------------------
    //  GUIs
    // -----------------------------------------------------------------
    // Non-border (middle) content slots for a 54-slot (6-row) inventory.
    private static final int[] CONTENT_54 = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43 };
    // Fixed non-border content slots for navigation (bottom interior row).
    private static final int PREV_SLOT = 38;
    private static final int BACK_SLOT = 40;
    private static final int NEXT_SLOT = 42;
    private static final int TRUST_INFO_SLOT = 43;

    // Interior content slots usable for paged ITEMS (everything in CONTENT_54
    // except the bottom-row nav slots 38/40/42). 25 items per page.
    private static final int[] PAGE_ITEM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 39, 41, 43 };
    private static final int PAGE_SIZE = PAGE_ITEM_SLOTS.length;

    public void openMenu(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7Stand inside a claim to manage it. (Use a Golden Shovel to claim.)");
            return;
        }
        ClaimGuiHolder holder = new ClaimGuiHolder(ClaimGuiHolder.Type.MENU, claim.getId());
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.LIGHT_PURPLE + "✦ Claim Menu");
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);

        UUID id = player.getUniqueId();
        boolean admin = player.hasPermission("kawaiiclaims.admin");
        String usage = admin
                ? manager.countChunks(id) + " chunks (unlimited)"
                : manager.countChunks(id) + "/" + manager.getChunkLimit(id) + " chunks";

        inv.setItem(13, named(Material.PAPER, ChatColor.AQUA + "Claim Info",
                ChatColor.GRAY + "Owner: " + ChatColor.WHITE + ownerName(claim.getOwner()),
                ChatColor.GRAY + "World: " + ChatColor.WHITE + claim.getWorld(),
                ChatColor.GRAY + "Chunks in claim: " + ChatColor.WHITE + claim.getChunkCount(),
                ChatColor.GRAY + "Your usage: " + ChatColor.WHITE + usage,
                ChatColor.GRAY + "Home: " + (claim.hasHome() ? ChatColor.GREEN + "set" : ChatColor.RED + "none")));
        inv.setItem(20, named(Material.OAK_SIGN, ChatColor.YELLOW + "Edit Flags",
                ChatColor.GRAY + "Toggle protection flags."));
        inv.setItem(21, named(Material.PLAYER_HEAD, ChatColor.YELLOW + "Manage Trust",
                ChatColor.GRAY + "View/cycle trusted members."));
        inv.setItem(30, named(Material.WRITABLE_BOOK, ChatColor.YELLOW + "Role Permissions",
                ChatColor.GRAY + "Edit which permissions each",
                ChatColor.GRAY + "role (incl. visitors) is granted."));
        inv.setItem(22, named(Material.RED_BED, ChatColor.YELLOW + "Set Home",
                ChatColor.GRAY + "Set claim home to where you stand."));
        inv.setItem(23, named(Material.GOLDEN_SHOVEL, ChatColor.GREEN + "Get Claim Tool",
                ChatColor.GRAY + "Receive a golden Claim Tool shovel."));
        inv.setItem(24, named(Material.GRASS_BLOCK, ChatColor.GREEN + "Claim Radius",
                ChatColor.GRAY + "Use " + ChatColor.WHITE + "/claim radius <n>",
                ChatColor.GRAY + "to claim a square of chunks",
                ChatColor.GRAY + "around you (max " + manager.getMaxRadius() + ")."));
        inv.setItem(31, named(Material.BARRIER, ChatColor.RED + "Abandon Chunk",
                ChatColor.GRAY + "Owner only. Shift-click to confirm.",
                ChatColor.GRAY + "Removes the chunk you're standing in."));
        inv.setItem(32, named(Material.GLOWSTONE_DUST, ChatColor.GREEN + "Show Border",
                ChatColor.GRAY + "Flash this claim's outline for ~10s.",
                ChatColor.GRAY + "Click again to hide it."));
        inv.setItem(33, named(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "Flag Presets",
                ChatColor.GRAY + "Apply a bundle of flag settings",
                ChatColor.GRAY + "(private, public-farm, pvp-arena, open...)."));
        player.openInventory(inv);
    }

    private void handleMenuClick(Player player, Claim claim, int slot, boolean shift) {
        switch (slot) {
            case 20 -> openFlags(player);
            case 21 -> openTrust(player);
            case 30 -> openRoles(player);
            case 22 -> {
                player.closeInventory();
                doSetHome(player);
            }
            case 23 -> {
                player.closeInventory();
                giveClaimTool(player);
                msg(player, "&aHere's a fresh &a✦ Claim Tool&a!");
            }
            case 24 -> {
                player.closeInventory();
                msg(player, "&dUse &f/claim radius <n>&d to claim a square of chunks (max "
                        + manager.getMaxRadius() + ").");
            }
            case 31 -> {
                if (!manager.isOwnerOrAdmin(player, claim)) {
                    msg(player, "&cOnly the owner can abandon this chunk.");
                    return;
                }
                if (!shift) {
                    msg(player, "&eShift-click the barrier to confirm unclaiming this chunk.");
                    return;
                }
                player.closeInventory();
                Location loc = player.getLocation();
                Chunk ch = loc.getChunk();
                manager.unclaimChunk(loc.getWorld().getName(), ch.getX(), ch.getZ());
                msg(player, "&eUnclaimed this chunk. " + usageSuffix(player));
                success(player);
            }
            case 32 -> {
                player.closeInventory();
                doShow(player);
            }
            case 33 -> openPresets(player);
            default -> { /* info / filler: ignore */ }
        }
    }

    public void openFlags(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7Stand inside a claim to edit its flags.");
            return;
        }
        if (!manager.canAct(player, claim, TrustLevel.MANAGE)) {
            msg(player, "&cYou need MANAGE trust (or ownership) here.");
            return;
        }
        ClaimGuiHolder holder = new ClaimGuiHolder(ClaimGuiHolder.Type.FLAGS, claim.getId());
        int pages = pageCount(manager.getFlagNames().size());
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.LIGHT_PURPLE + "✦ Claim Flags " + (holder.getPage() + 1) + "/" + pages);
        holder.setInventory(inv);
        rebuildFlags(inv, claim, holder);
        player.openInventory(inv);
    }

    private int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void rebuildFlags(Inventory inv, Claim claim, ClaimGuiHolder holder) {
        for (int s : CONTENT_54) inv.setItem(s, null);
        paintBorder(inv, guiFrame);
        List<String> flags = manager.getFlagNames();
        int pages = pageCount(flags.size());
        int page = Math.max(0, Math.min(holder.getPage(), pages - 1));
        holder.setPage(page);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= flags.size()) break;
            String f = flags.get(idx);
            boolean v = manager.flag(claim, f);
            Material mat = v ? Material.LIME_DYE : Material.GRAY_DYE;
            inv.setItem(PAGE_ITEM_SLOTS[i], named(mat, (v ? ChatColor.GREEN : ChatColor.RED) + f,
                    ChatColor.GRAY + "Currently: " + (v ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"),
                    ChatColor.YELLOW + "Click to toggle."));
        }
        paintNav(inv, page, pages);
    }

    /** Paint Prev/Back/Next nav buttons for a paged GUI. */
    private void paintNav(Inventory inv, int page, int pages) {
        if (page > 0) {
            inv.setItem(PREV_SLOT, named(Material.ARROW, ChatColor.YELLOW + "« Previous page",
                    ChatColor.GRAY + "Page " + page + "/" + pages));
        } else {
            inv.setItem(PREV_SLOT, null);
        }
        inv.setItem(BACK_SLOT, named(Material.BARRIER, ChatColor.GRAY + "« Back",
                ChatColor.GRAY + "Return to the claim menu."));
        if (page < pages - 1) {
            inv.setItem(NEXT_SLOT, named(Material.ARROW, ChatColor.YELLOW + "Next page »",
                    ChatColor.GRAY + "Page " + (page + 2) + "/" + pages));
        } else {
            inv.setItem(NEXT_SLOT, null);
        }
    }

    private void handleFlagClick(Player player, Claim claim, ClaimGuiHolder holder, int slot) {
        if (slot == BACK_SLOT) { openMenu(player); return; }
        List<String> flags = manager.getFlagNames();
        int pages = pageCount(flags.size());
        if (slot == PREV_SLOT && holder.getPage() > 0) {
            holder.setPage(holder.getPage() - 1);
            reopenFlags(player, claim, holder);
            return;
        }
        if (slot == NEXT_SLOT && holder.getPage() < pages - 1) {
            holder.setPage(holder.getPage() + 1);
            reopenFlags(player, claim, holder);
            return;
        }
        int itemIndex = slotItemIndex(slot);
        if (itemIndex < 0) return;
        int idx = holder.getPage() * PAGE_SIZE + itemIndex;
        if (idx >= flags.size()) return;
        String f = flags.get(idx);
        boolean newVal = !manager.flag(claim, f);
        claim.setFlag(f, newVal);
        manager.touchAndSave(claim);
        rebuildFlags(player.getOpenInventory().getTopInventory(), claim, holder);
        success(player);
    }

    /** Reopen a fresh Flags inventory so the title's page number updates. */
    private void reopenFlags(Player player, Claim claim, ClaimGuiHolder holder) {
        int pages = pageCount(manager.getFlagNames().size());
        ClaimGuiHolder fresh = new ClaimGuiHolder(ClaimGuiHolder.Type.FLAGS, claim.getId());
        fresh.setPage(holder.getPage());
        Inventory inv = Bukkit.createInventory(fresh, 54,
                ChatColor.LIGHT_PURPLE + "✦ Claim Flags " + (fresh.getPage() + 1) + "/" + pages);
        fresh.setInventory(inv);
        rebuildFlags(inv, claim, fresh);
        player.openInventory(inv);
    }

    /** Index within PAGE_ITEM_SLOTS for a clicked slot, or -1 if not an item slot. */
    private int slotItemIndex(int slot) {
        for (int i = 0; i < PAGE_ITEM_SLOTS.length; i++) {
            if (PAGE_ITEM_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    public void openTrust(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7Stand inside a claim to manage trust.");
            return;
        }
        if (!manager.canAct(player, claim, TrustLevel.MANAGE)) {
            msg(player, "&cYou need MANAGE trust (or ownership) here.");
            return;
        }
        ClaimGuiHolder holder = new ClaimGuiHolder(ClaimGuiHolder.Type.TRUST, claim.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.LIGHT_PURPLE + "✦ Claim Trust");
        holder.setInventory(inv);
        rebuildTrust(inv, claim);
        player.openInventory(inv);
    }

    private void rebuildTrust(Inventory inv, Claim claim) {
        for (int s : CONTENT_54) inv.setItem(s, null);
        paintBorder(inv, guiFrame);
        int idx = 0;
        for (Map.Entry<UUID, TrustLevel> e : claim.getTrust().entrySet()) {
            int slot = trustSlot(idx);
            if (slot < 0) break;
            ItemStack head = named(Material.PLAYER_HEAD,
                    ChatColor.AQUA + ownerName(e.getKey()),
                    ChatColor.GRAY + "Level: " + ChatColor.WHITE + e.getValue().display(),
                    ChatColor.YELLOW + "Left-click: cycle level",
                    ChatColor.RED + "Shift-click: remove");
            inv.setItem(slot, head);
            idx++;
        }
        inv.setItem(BACK_SLOT, named(Material.ARROW, ChatColor.GRAY + "« Back",
                ChatColor.GRAY + "Return to the claim menu."));
        inv.setItem(TRUST_INFO_SLOT, named(Material.BOOK, ChatColor.YELLOW + "How to trust",
                ChatColor.GRAY + "Use /claim trust <player> <level>",
                ChatColor.GRAY + "Levels: access, container, build, manage"));
    }

    /** Map a trusted-member index to a content slot, skipping Back and the info book slot. */
    private int trustSlot(int index) {
        int seen = 0;
        for (int s : CONTENT_54) {
            if (s == BACK_SLOT || s == TRUST_INFO_SLOT) continue;
            if (seen == index) return s;
            seen++;
        }
        return -1;
    }

    private void handleTrustClick(Player player, Claim claim, int slot, ItemStack clicked) {
        if (slot == BACK_SLOT) {
            openMenu(player);
            return;
        }
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String shown = ChatColor.stripColor(meta.getDisplayName());
        // find the matching trusted uuid by display name
        UUID match = null;
        for (UUID u : new ArrayList<>(claim.getTrust().keySet())) {
            if (ownerName(u).equals(shown)) {
                match = u;
                break;
            }
        }
        if (match == null) return;
        if (player.isSneaking()) {
            claim.removeTrust(match);
            msg(player, "&eRemoved &f" + shown + "&e's trust.");
        } else {
            TrustLevel cur = claim.getTrustLevel(match);
            TrustLevel next = cur == null ? TrustLevel.ACCESS : cur.cycle();
            claim.setTrust(match, next);
            msg(player, "&a" + shown + " is now &f" + next.display() + "&a.");
        }
        manager.touchAndSave(claim);
        rebuildTrust(player.getOpenInventory().getTopInventory(), claim);
        success(player);
    }

    // -----------------------------------------------------------------
    //  Roles menu: pick a role to edit its permission grants
    // -----------------------------------------------------------------
    public void openRoles(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7Stand inside a claim to edit role permissions.");
            return;
        }
        if (!manager.canAct(player, claim, TrustLevel.MANAGE)) {
            msg(player, "&cYou need MANAGE trust (or ownership) here.");
            return;
        }
        ClaimGuiHolder holder = new ClaimGuiHolder(ClaimGuiHolder.Type.ROLES, claim.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.LIGHT_PURPLE + "✦ Role Permissions");
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        String[] roles = TrustLevel.ROLE_NAMES;
        Material[] icons = {
                Material.LEATHER_BOOTS, Material.IRON_DOOR, Material.CHEST,
                Material.IRON_PICKAXE, Material.GOLDEN_HELMET };
        for (int i = 0; i < roles.length; i++) {
            int slot = PAGE_ITEM_SLOTS[i];
            int granted = 0;
            for (String perm : manager.getPermissionNames()) {
                if (manager.roleGrants(claim, roles[i], perm)) granted++;
            }
            inv.setItem(slot, named(icons[i], ChatColor.AQUA + capitalize(roles[i]) + " role",
                    ChatColor.GRAY + "Permissions granted: " + ChatColor.WHITE + granted
                            + "/" + manager.getPermissionNames().size(),
                    ChatColor.YELLOW + "Click to edit this role's permissions."));
        }
        inv.setItem(BACK_SLOT, named(Material.BARRIER, ChatColor.GRAY + "« Back",
                ChatColor.GRAY + "Return to the claim menu."));
        player.openInventory(inv);
    }

    private void handleRolesClick(Player player, Claim claim, int slot) {
        if (slot == BACK_SLOT) { openMenu(player); return; }
        int idx = slotItemIndex(slot);
        if (idx < 0 || idx >= TrustLevel.ROLE_NAMES.length) return;
        openPerms(player, claim, TrustLevel.ROLE_NAMES[idx], 0);
    }

    // -----------------------------------------------------------------
    //  Presets menu: apply a config-defined bundle of flag values
    // -----------------------------------------------------------------
    public void openPresets(Player player) {
        Claim claim = manager.getClaimAt(player.getLocation());
        if (claim == null) {
            msg(player, "&7Stand inside a claim to apply a preset.");
            return;
        }
        if (!manager.canAct(player, claim, TrustLevel.MANAGE)) {
            msg(player, "&cYou need MANAGE trust (or ownership) here.");
            return;
        }
        ClaimGuiHolder holder = new ClaimGuiHolder(ClaimGuiHolder.Type.PRESETS, claim.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.LIGHT_PURPLE + "✦ Flag Presets");
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        List<String> names = manager.getPresetNames();
        for (int i = 0; i < names.size() && i < PAGE_ITEM_SLOTS.length; i++) {
            String name = names.get(i);
            Map<String, Boolean> flags = manager.getPreset(name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Click to apply this preset.");
            if (flags != null) {
                for (Map.Entry<String, Boolean> en : flags.entrySet()) {
                    lore.add((en.getValue() ? ChatColor.GREEN : ChatColor.RED) + en.getKey());
                }
            }
            inv.setItem(PAGE_ITEM_SLOTS[i], named(Material.CHEST,
                    ChatColor.AQUA + capitalize(name), lore.toArray(new String[0])));
        }
        inv.setItem(BACK_SLOT, named(Material.BARRIER, ChatColor.GRAY + "« Back",
                ChatColor.GRAY + "Return to the claim menu."));
        player.openInventory(inv);
    }

    private void handlePresetClick(Player player, Claim claim, int slot) {
        if (slot == BACK_SLOT) { openMenu(player); return; }
        int idx = slotItemIndex(slot);
        List<String> names = manager.getPresetNames();
        if (idx < 0 || idx >= names.size()) return;
        String name = names.get(idx);
        if (manager.applyPreset(claim, name)) {
            msg(player, "&aApplied the &f" + name + "&a preset.");
            success(player);
        }
        // reopen the flags view so the change is visible
        openFlags(player);
    }

    // -----------------------------------------------------------------
    //  Permission editor: paged toggles for one role
    // -----------------------------------------------------------------
    public void openPerms(Player player, Claim claim, String role, int page) {
        ClaimGuiHolder holder = new ClaimGuiHolder(ClaimGuiHolder.Type.PERMS, claim.getId());
        holder.setRole(role);
        holder.setPage(page);
        int pages = pageCount(manager.getPermissionNames().size());
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.LIGHT_PURPLE + "✦ " + capitalize(role) + " Perms " + (page + 1) + "/" + pages);
        holder.setInventory(inv);
        rebuildPerms(inv, claim, holder);
        player.openInventory(inv);
    }

    private void rebuildPerms(Inventory inv, Claim claim, ClaimGuiHolder holder) {
        for (int s : CONTENT_54) inv.setItem(s, null);
        paintBorder(inv, guiFrame);
        List<String> perms = manager.getPermissionNames();
        int pages = pageCount(perms.size());
        int page = Math.max(0, Math.min(holder.getPage(), pages - 1));
        holder.setPage(page);
        String role = holder.getRole();
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= perms.size()) break;
            String perm = perms.get(idx);
            boolean v = manager.roleGrants(claim, role, perm);
            Material mat = v ? Material.LIME_DYE : Material.GRAY_DYE;
            inv.setItem(PAGE_ITEM_SLOTS[i], named(mat, (v ? ChatColor.GREEN : ChatColor.RED) + perm,
                    ChatColor.GRAY + "Role: " + ChatColor.WHITE + capitalize(role),
                    ChatColor.GRAY + "Granted: " + (v ? ChatColor.GREEN + "YES" : ChatColor.RED + "NO"),
                    ChatColor.YELLOW + "Click to toggle."));
        }
        paintNav(inv, page, pages);
    }

    private void handlePermClick(Player player, Claim claim, ClaimGuiHolder holder, int slot) {
        if (slot == BACK_SLOT) { openRoles(player); return; }
        List<String> perms = manager.getPermissionNames();
        int pages = pageCount(perms.size());
        if (slot == PREV_SLOT && holder.getPage() > 0) {
            openPerms(player, claim, holder.getRole(), holder.getPage() - 1);
            return;
        }
        if (slot == NEXT_SLOT && holder.getPage() < pages - 1) {
            openPerms(player, claim, holder.getRole(), holder.getPage() + 1);
            return;
        }
        int itemIndex = slotItemIndex(slot);
        if (itemIndex < 0) return;
        int idx = holder.getPage() * PAGE_SIZE + itemIndex;
        if (idx >= perms.size()) return;
        String perm = perms.get(idx);
        String role = holder.getRole();
        boolean newVal = !manager.roleGrants(claim, role, perm);
        claim.setRolePerm(role, perm, newVal);
        manager.touchAndSave(claim);
        rebuildPerms(player.getOpenInventory().getTopInventory(), claim, holder);
        success(player);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private ItemStack named(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    // -----------------------------------------------------------------
    //  GUI animation
    // -----------------------------------------------------------------
    private void animateMenus() {
        guiFrame++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof ClaimGuiHolder) {
                paintBorder(top, guiFrame);
                p.updateInventory();
            }
        }
    }

    /** Shimmer the perimeter panes with a rotating colour offset. */
    private void paintBorder(Inventory inv, int frame) {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) {
                ItemStack pane = new ItemStack(SHIMMER[Math.floorMod(i + frame, SHIMMER.length)]);
                ItemMeta meta = pane.getItemMeta();
                if (meta != null) { meta.setDisplayName(" "); pane.setItemMeta(meta); }
                inv.setItem(i, pane);
            }
        }
    }

    // -----------------------------------------------------------------
    //  Tab completion
    // -----------------------------------------------------------------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("claim", "radius", "unclaim", "abandon", "list",
                    "info", "trust", "untrust", "flag", "flags", "preset", "show", "border", "perm", "perms",
                    "tool", "wand", "sethome", "home", "transfer", "greeting", "farewell", "menu", "help"));
            if (sender.hasPermission("kawaiiclaims.admin")) {
                subs.add("bypass");
                subs.add("admin");
            }
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2 && (sub.equals("trust") || sub.equals("untrust") || sub.equals("transfer"))) {
            return filter(onlineNames(), args[1]);
        }
        if (args.length == 2 && (sub.equals("unclaim") || sub.equals("abandon"))) {
            return filter(Arrays.asList("all"), args[1]);
        }
        if (args.length == 2 && sub.equals("flag")) {
            return filter(manager.getFlagNames(), args[1]);
        }
        if (args.length == 2 && sub.equals("preset")) {
            return filter(manager.getPresetNames(), args[1]);
        }
        if (args.length == 2 && sub.equals("perm")) {
            return filter(Arrays.asList(TrustLevel.ROLE_NAMES), args[1]);
        }
        if (args.length == 3 && sub.equals("perm")) {
            return filter(manager.getPermissionNames(), args[2]);
        }
        if (args.length == 4 && sub.equals("perm")) {
            return filter(Arrays.asList("on", "off"), args[3]);
        }
        if (args.length == 3 && sub.equals("trust")) {
            return filter(Arrays.asList("access", "container", "build", "manage"), args[2]);
        }
        if (args.length == 3 && sub.equals("flag")) {
            return filter(Arrays.asList("on", "off"), args[2]);
        }
        if (args.length == 2 && sub.equals("admin") && sender.hasPermission("kawaiiclaims.admin")) {
            return filter(Arrays.asList("delete", "list", "givechunks", "setchunks"), args[1]);
        }
        if (args.length == 3 && sub.equals("admin")
                && (args[1].equalsIgnoreCase("givechunks") || args[1].equalsIgnoreCase("setchunks"))) {
            return filter(onlineNames(), args[2]);
        }
        return new ArrayList<>();
    }

    private List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
