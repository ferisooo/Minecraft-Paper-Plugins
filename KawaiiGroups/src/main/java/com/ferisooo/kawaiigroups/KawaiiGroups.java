package com.ferisooo.kawaiigroups;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KawaiiGroups — online-only player groups. Being grouped multiplies your max
 * hearts by the number of online members (base 5 hearts × members). Groups are
 * ephemeral: a member who logs off is removed, and if the owner logs off the
 * group disbands. FiveHearts is the single health authority and reads our
 * per-player "group-max-health" metadata so the two never fight.
 */
public final class KawaiiGroups extends JavaPlugin implements Listener {

    /** Metadata key FiveHearts reads to clamp a grouped player's max health. */
    static final String META_KEY = "group-max-health";

    private static final Material[] SHIMMER = {
        Material.PINK_STAINED_GLASS_PANE,
        Material.MAGENTA_STAINED_GLASS_PANE,
        Material.PURPLE_STAINED_GLASS_PANE,
    };
    private static final String[] PALETTE = { "&b", "&a", "&d", "&e", "&c", "&6", "&9", "&5", "&2", "&3" };
    private int guiFrame = 0;

    // ---- live state (in-memory only) ----
    // groups/playerGroup/chatChannel are read from the async chat event too, so
    // they're concurrent; invites/tpReqs are only touched on the main thread.
    private final Map<String, Group> groups = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> playerGroup = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();      // invitee -> invite
    private final Map<UUID, TpReq> tpReqs = new HashMap<>();        // target -> request
    private final Set<UUID> chatChannel = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ---- persisted per-player settings ----
    private File settingsFile;
    private YamlConfiguration settings;
    // Debounced async persistence: mutations mark this dirty; a periodic task
    // snapshots settings on the main thread and writes the bytes off-thread.
    private volatile boolean settingsDirty = false;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask flushTask;

    // ---- config ----
    private int baseHearts;
    private int heartsPerMember;
    private int maxHeartsCap;
    private long inviteExpiryMs;
    private long tpExpiryMs;
    private int maxNameLen;
    private boolean preventPartyPvp;
    private boolean announceEnabled;
    private boolean announceBosses;
    private boolean announceAdvancements;
    private final Set<EntityType> bossTypes = new HashSet<>();

    private NamespacedKey targetKey;   // a player UUID stashed on a GUI item
    private NamespacedKey groupKey;    // a group id stashed on a GUI item
    private NamespacedKey actionKey;   // a button action stashed on a GUI item

    private static final class Invite {
        final String groupId; final UUID inviter; final long expiry;
        Invite(String groupId, UUID inviter, long expiry) {
            this.groupId = groupId; this.inviter = inviter; this.expiry = expiry;
        }
    }
    private static final class TpReq {
        final UUID requester; final long expiry;
        TpReq(UUID requester, long expiry) { this.requester = requester; this.expiry = expiry; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        targetKey = new NamespacedKey(this, "target");
        groupKey  = new NamespacedKey(this, "group");
        actionKey = new NamespacedKey(this, "action");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        // Debounced async flush of players.yml every 30s (600 ticks).
        flushTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> flushSettingsAsync(), 600L, 600L);
        getLogger().info("(✧) KawaiiGroups ready ~ groups, roles, invites & shared hearts!");
    }

    @Override
    public void onDisable() {
        // Drop every group's heart bonus so nobody is left over-buffed if the
        // plugin (or FiveHearts) reloads without us.
        for (Group g : new ArrayList<>(groups.values())) {
            for (UUID u : new ArrayList<>(g.members.keySet())) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) resetHearts(p);
            }
        }
        if (flushTask != null) { flushTask.cancel(); flushTask = null; }
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        saveSettings(); // synchronous flush on shutdown so nothing is lost
    }

    private void loadConfigValues() {
        reloadConfig();
        baseHearts      = Math.max(1, getConfig().getInt("base-hearts", 5));
        heartsPerMember = Math.max(1, getConfig().getInt("hearts-per-member", 5));
        maxHeartsCap    = Math.max(baseHearts, getConfig().getInt("max-hearts", 50));
        inviteExpiryMs  = Math.max(10, getConfig().getLong("invite-expiry-seconds", 120)) * 1000L;
        tpExpiryMs      = Math.max(10, getConfig().getLong("teleport-expiry-seconds", 60)) * 1000L;
        maxNameLen      = Math.max(3, getConfig().getInt("max-name-length", 24));
        preventPartyPvp = getConfig().getBoolean("prevent-party-pvp", true);

        announceEnabled      = getConfig().getBoolean("announcements.enabled", true);
        announceBosses       = getConfig().getBoolean("announcements.bosses", true);
        announceAdvancements = getConfig().getBoolean("announcements.advancements", false);
        bossTypes.clear();
        List<String> names = getConfig().getStringList("announcements.boss-types");
        if (names.isEmpty()) names = java.util.Arrays.asList("ENDER_DRAGON", "WITHER", "WARDEN", "ELDER_GUARDIAN");
        for (String n : names) {
            try { bossTypes.add(EntityType.valueOf(n.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { /* unknown on this server version */ }
        }
    }

    // ============================================================ settings

    private void loadSettings() {
        getDataFolder().mkdirs();
        settingsFile = new File(getDataFolder(), "players.yml");
        if (!settingsFile.exists()) {
            try { settingsFile.createNewFile(); }
            catch (IOException e) { getLogger().warning("Could not create players.yml: " + e.getMessage()); }
        }
        settings = YamlConfiguration.loadConfiguration(settingsFile);
    }

    /** Synchronous flush (used on disable). Writes the current settings to disk. */
    private void saveSettings() {
        settingsDirty = false;
        try { settings.save(settingsFile); }
        catch (IOException e) { getLogger().warning("Could not save players.yml: " + e.getMessage()); }
    }

    /**
     * Periodic debounced flush. Runs on the main thread: if dirty, snapshot the
     * config to a String here (YamlConfiguration is not thread-safe), then write
     * the bytes off-thread so the main thread never blocks on disk I/O.
     */
    private void flushSettingsAsync() {
        if (!settingsDirty) return;
        settingsDirty = false;
        final String data = settings.saveToString();
        final File file = settingsFile;
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                Files.write(file.toPath(), data.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                settingsDirty = true; // retry on the next tick of the flush timer
                getLogger().warning("Could not save players.yml: " + e.getMessage());
            }
        });
    }

    private boolean setting(UUID u, String key, boolean def) {
        return settings.getBoolean(u + "." + key, def);
    }
    private void toggleSetting(UUID u, String key, boolean def) {
        settings.set(u + "." + key, !setting(u, key, def));
        settingsDirty = true;
    }
    private List<String> blocked(UUID u) { return settings.getStringList(u + ".blocked"); }
    private void setBlocked(UUID u, List<String> v) { settings.set(u + ".blocked", v); settingsDirty = true; }

    // ============================================================ hearts

    private double baseHp() { return baseHearts * 2.0; }
    private double hpFor(int members) {
        int hearts = Math.min(maxHeartsCap, heartsPerMember * members);
        return hearts * 2.0;
    }

    @SuppressWarnings("deprecation")
    private void applyHearts(Player p, double hp) {
        p.setMetadata(META_KEY, new FixedMetadataValue(this, hp));
        if (p.isDead() || p.getHealth() <= 0.0) return; // never touch a dead/respawning player
        try {
            if (p.getMaxHealth() != hp) p.setMaxHealth(hp);
            if (p.getHealth() > hp) p.setHealth(hp);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void resetHearts(Player p) {
        p.removeMetadata(META_KEY, this);
        if (p.isDead() || p.getHealth() <= 0.0) return;
        double base = baseHp();
        try {
            if (p.getMaxHealth() != base) p.setMaxHealth(base);
            if (p.getHealth() > base) p.setHealth(base);
        } catch (Throwable ignored) {}
    }

    /** Recompute and apply the heart bonus for every online member of {@code g}. */
    private void recalc(Group g) {
        double hp = hpFor(g.size());
        for (UUID u : g.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) applyHearts(p, hp);
        }
    }

    // ============================================================ helpers

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void msg(CommandSender to, String s) {
        String c = color(s);
        if (to instanceof Player p && isBedrock(p.getUniqueId())) c = bedrockText(c);
        to.sendMessage(c);
    }

    private void pickupSound(Player p) {
        try { p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1f); } catch (Throwable ignored) {}
    }

    private Group groupOf(UUID u) {
        String id = playerGroup.get(u);
        return id == null ? null : groups.get(id);
    }

    private String pickColor(String id) {
        return PALETTE[Math.floorMod(id.hashCode(), PALETTE.length)];
    }

    private boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9 _]{3," + maxNameLen + "}") && !name.contains("%");
    }

    private Invite activeInvite(UUID invitee) {
        Invite inv = invites.get(invitee);
        if (inv == null) return null;
        if (System.currentTimeMillis() > inv.expiry || !groups.containsKey(inv.groupId)) {
            invites.remove(invitee);
            return null;
        }
        return inv;
    }

    private String pretty(OfflinePlayer op) {
        String n = op.getName();
        return n != null ? n : "someone";
    }

    // ============================================================ bedrock

    private static Boolean FG_PRESENT;
    private static java.lang.reflect.Method FG_IS;
    private static Object FG_API;

    static boolean isBedrock(UUID id) {
        try {
            if (FG_PRESENT == null) {
                try {
                    Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    FG_API = api.getMethod("getInstance").invoke(null);
                    FG_IS = api.getMethod("isFloodgatePlayer", UUID.class);
                    FG_PRESENT = (FG_API != null && FG_IS != null);
                } catch (Throwable t) { FG_PRESENT = false; }
            }
            if (!FG_PRESENT) return false;
            Object r = FG_IS.invoke(FG_API, id);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) { return false; }
    }

    static String bedrockText(String s) {
        if (s == null) return null;
        String t = s
                .replace("✿", "*").replace("✦", "*").replace("✧", "*").replace("✈", ">")
                .replace("✚", "+").replace("▶", ">").replace("◀", "<").replace("↩", "<")
                .replace("→", "->").replace("←", "<-").replace("━", "-").replace("─", "-")
                .replace("×", "x").replace("♥", "<3").replace("●", "*").replace("☆", "*")
                .replace("👑", "").replace("🛡", "").replace("⚔", "").replace("🗑", "")
                .replace("🏠", "").replace("✨", "");
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.trim();
    }

    private String titleFor(Player p, String rawAmp) {
        String c = color(rawAmp);
        return isBedrock(p.getUniqueId()) ? bedrockText(c) : c;
    }

    private void applyBedrock(Player p, Inventory inv) {
        if (!isBedrock(p.getUniqueId())) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            ItemMeta meta = it.getItemMeta();
            if (meta == null) continue;
            boolean changed = false;
            if (meta.hasDisplayName()) { meta.setDisplayName(bedrockText(meta.getDisplayName())); changed = true; }
            if (meta.hasLore()) {
                List<String> nl = new ArrayList<>();
                for (String l : meta.getLore()) nl.add(bedrockText(l));
                meta.setLore(nl);
                changed = true;
            }
            if (changed) it.setItemMeta(meta);
        }
    }

    // ============================================================ items

    private ItemStack menuItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(color(s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private void tagGroup(ItemStack it, String groupId) {
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.getPersistentDataContainer().set(groupKey, PersistentDataType.STRING, groupId); it.setItemMeta(m); }
    }
    private void tagTarget(ItemStack it, UUID target) {
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.toString()); it.setItemMeta(m); }
    }
    private void tagAction(ItemStack it, String action) {
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); it.setItemMeta(m); }
    }
    private String readGroup(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
    }
    private UUID readTarget(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        try { return s == null ? null : UUID.fromString(s); } catch (Throwable t) { return null; }
    }
    private String readAction(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private ItemStack head(OfflinePlayer op, String name, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(op);
            skull.setDisplayName(color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(color(s));
                skull.setLore(l);
            }
            it.setItemMeta(skull);
        }
        return it;
    }

    private static final int[] LIST_SLOTS = buildListSlots();
    private static final int BACK = 49;
    private static int[] buildListSlots() {
        List<Integer> s = new ArrayList<>();
        for (int row = 1; row <= 4; row++) for (int col = 1; col <= 7; col++) s.add(row * 9 + col);
        int[] out = new int[s.size()];
        for (int i = 0; i < out.length; i++) out[i] = s.get(i);
        return out;
    }

    private void paintBorder(Inventory inv, int frame) {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8;
            if (edge) inv.setItem(i, menuItem(SHIMMER[Math.floorMod(i + frame, SHIMMER.length)], " "));
        }
    }

    // ============================================================ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { msg(sender, "&dThis command is for players only ~"); return true; }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("gc")) { return cmdGroupChat(p, args); }
        // /group ...
        if (args.length == 0) { openMain(p); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "create":   return cmdCreate(p, rest);
            case "invite":   return cmdInvite(p, rest);
            case "accept":   return cmdAccept(p, rest);
            case "deny":     return cmdDeny(p, rest);
            case "leave":    return cmdLeave(p);
            case "disband":  return cmdDisband(p);
            case "kick":     return cmdKick(p, rest);
            case "promote":  return cmdPromote(p, rest, true);
            case "demote":   return cmdPromote(p, rest, false);
            case "owner":    return cmdTransfer(p, rest);
            case "members":  return cmdMembers(p);
            case "announce": return cmdAnnounce(p, rest);
            case "chat":     return cmdToggleChat(p);
            case "color":    return cmdColor(p, rest);
            case "tp":       return cmdTp(p, rest);
            case "tpaccept": return cmdTpAccept(p);
            case "tpdeny":   return cmdTpDeny(p);
            case "menu":     openMain(p); return true;
            case "help":     return cmdHelp(p);
            default:         return cmdHelp(p);
        }
    }

    private boolean cmdHelp(Player p) {
        msg(p, "&d✦ Groups &7— /group <create|invite|accept|deny|leave|disband|kick|promote|demote|owner|members|announce|chat|color|tp> &8or just &f/group&8 for the menu");
        return true;
    }

    private boolean cmdCreate(Player p, String[] a) {
        if (groupOf(p.getUniqueId()) != null) { msg(p, "&cYou're already in a group. &f/group leave&c first."); return true; }
        String name = String.join(" ", a).trim();
        if (!validName(name)) { msg(p, "&cName needs 3-" + maxNameLen + " letters/numbers/spaces."); return true; }
        String id = name.toLowerCase(Locale.ROOT);
        if (groups.containsKey(id)) { msg(p, "&cA group called &f" + name + " &calready exists."); return true; }
        Group g = new Group(id, name, pickColor(id), p.getUniqueId());
        g.privateMode = setting(p.getUniqueId(), "privateMode", false);
        groups.put(id, g);
        playerGroup.put(p.getUniqueId(), id);
        recalc(g);
        msg(p, "&dCreated group " + g.colored() + "&d! Invite people with &f/group invite <player>&d. (✧)");
        pickupSound(p);
        return true;
    }

    private boolean cmdInvite(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canInvite()) { msg(p, "&cYour role can't invite."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group invite <player>"); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || !t.isOnline()) { msg(p, "&cThat player isn't online."); return true; }
        if (t.getUniqueId().equals(p.getUniqueId())) { msg(p, "&cYou can't invite yourself."); return true; }
        if (groupOf(t.getUniqueId()) != null) { msg(p, "&cThey're already in a group."); return true; }
        if (!setting(t.getUniqueId(), "groupInvites", true)) { msg(p, "&c" + t.getName() + " isn't accepting group invites."); return true; }
        if (blocked(t.getUniqueId()).contains(p.getUniqueId().toString())) { msg(p, "&cThey've blocked invites from you."); return true; }
        invites.put(t.getUniqueId(), new Invite(g.id, p.getUniqueId(), System.currentTimeMillis() + inviteExpiryMs));
        msg(p, "&dInvited &f" + t.getName() + "&d to " + g.colored() + "&d.");
        msg(t, "&f" + p.getName() + " &dinvited you to " + g.colored() + "&d! &f/group accept " + g.name + "&d or &f/group deny&d.");
        pickupSound(t);
        return true;
    }

    private boolean cmdAccept(Player p, String[] a) {
        if (groupOf(p.getUniqueId()) != null) { msg(p, "&cLeave your current group first."); return true; }
        Invite inv = activeInvite(p.getUniqueId());
        if (inv == null) { msg(p, "&cYou have no pending group invite."); return true; }
        Group g = groups.get(inv.groupId);
        if (g == null) { invites.remove(p.getUniqueId()); msg(p, "&cThat group no longer exists."); return true; }
        invites.remove(p.getUniqueId());
        g.members.put(p.getUniqueId(), Role.MEMBER);
        playerGroup.put(p.getUniqueId(), g.id);
        recalc(g);
        msg(p, "&dYou joined " + g.colored() + "&d! You now have " + (Math.min(maxHeartsCap, heartsPerMember * g.size())) + " hearts. ✨");
        notifyMembers(g, p.getUniqueId(), "joinNotifications", "&f" + p.getName() + " &djoined the group~");
        pickupSound(p);
        return true;
    }

    private boolean cmdDeny(Player p, String[] a) {
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null) { msg(p, "&cYou have no pending group invite."); return true; }
        msg(p, "&dInvite denied.");
        Player inviter = Bukkit.getPlayer(inv.inviter);
        if (inviter != null) msg(inviter, "&c" + p.getName() + " denied your group invite.");
        return true;
    }

    private boolean cmdLeave(Player p) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (g.owner.equals(p.getUniqueId())) {
            msg(p, "&dYou owned " + g.colored() + "&d, so it disbanded.");
            disbandInternal(g, "&7(the owner left)");
            return true;
        }
        removeMemberInternal(g, p.getUniqueId(), false);
        msg(p, "&dYou left the group.");
        return true;
    }

    private boolean cmdDisband(Player p) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canDisband()) { msg(p, "&cOnly the owner can disband the group."); return true; }
        msg(p, "&dDisbanded " + g.colored() + "&d.");
        disbandInternal(g, "&7(disbanded by the owner)");
        return true;
    }

    private boolean cmdKick(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canKick()) { msg(p, "&cYour role can't kick."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group kick <player>"); return true; }
        UUID target = findMemberByName(g, a[0]);
        if (target == null) { msg(p, "&cNo group member called &f" + a[0] + "&c."); return true; }
        if (target.equals(g.owner)) { msg(p, "&cYou can't kick the owner."); return true; }
        if (g.roleOf(target).ordinal() >= g.roleOf(p.getUniqueId()).ordinal()) { msg(p, "&cYou can't kick someone at your rank or above."); return true; }
        Player tp = Bukkit.getPlayer(target);
        removeMemberInternal(g, target, false);
        if (tp != null) msg(tp, "&cYou were kicked from " + g.colored() + "&c.");
        msg(p, "&dKicked &f" + (tp != null ? tp.getName() : "them") + "&d.");
        return true;
    }

    private boolean cmdPromote(Player p, String[] a, boolean up) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canPromote()) { msg(p, "&cOnly the owner can change ranks."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group " + (up ? "promote" : "demote") + " <player>"); return true; }
        UUID target = findMemberByName(g, a[0]);
        if (target == null || target.equals(g.owner)) { msg(p, "&cNo member called &f" + a[0] + "&c."); return true; }
        Role cur = g.roleOf(target);
        Role next = up ? cur.promoted() : cur.demoted();
        g.members.put(target, next);
        Player tp = Bukkit.getPlayer(target);
        String nm = tp != null ? tp.getName() : a[0];
        msg(p, "&d" + nm + " is now " + next.tag() + "&d.");
        if (tp != null) msg(tp, "&dYou are now " + next.tag() + " &din " + g.colored() + "&d.");
        return true;
    }

    private boolean cmdTransfer(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canTransfer()) { msg(p, "&cOnly the owner can transfer ownership."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group owner <player>"); return true; }
        UUID target = findMemberByName(g, a[0]);
        if (target == null || target.equals(g.owner)) { msg(p, "&cNo other member called &f" + a[0] + "&c."); return true; }
        g.members.put(g.owner, Role.ADMIN);
        g.members.put(target, Role.OWNER);
        g.owner = target;
        Player tp = Bukkit.getPlayer(target);
        msg(p, "&dYou handed " + g.colored() + " &dto &f" + (tp != null ? tp.getName() : a[0]) + "&d.");
        if (tp != null) msg(tp, "&d✦ You are now the owner of " + g.colored() + "&d!");
        notifyMembers(g, null, "joinNotifications", "&dOwnership transferred to &f" + (tp != null ? tp.getName() : a[0]) + "&d.");
        return true;
    }

    private boolean cmdMembers(Player p) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        openMembers(p, g.id);
        return true;
    }

    private boolean cmdAnnounce(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canModerateChat()) { msg(p, "&cOnly admins+ can make announcements."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group announce <message>"); return true; }
        broadcastGroup(g, String.join(" ", a));
        return true;
    }

    /** Server-wide broadcast tagged with the group's coloured name. */
    private void broadcastGroup(Group g, String text) {
        Bukkit.broadcastMessage(color(g.colorCode + "&l[" + g.name + "]&r&f " + text));
    }

    private boolean cmdToggleChat(Player p) {
        if (groupOf(p.getUniqueId()) == null) { msg(p, "&cYou're not in a group."); return true; }
        if (chatChannel.remove(p.getUniqueId())) {
            msg(p, "&dGroup chat &coff&d — your messages go to everyone again.");
        } else {
            chatChannel.add(p.getUniqueId());
            msg(p, "&dGroup chat &aon&d — your messages now go only to your group. &8(/group chat to turn off)");
        }
        return true;
    }

    private boolean cmdGroupChat(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/gc <message>"); return true; }
        sendGroupChat(g, p.getName(), String.join(" ", a));
        return true;
    }

    private boolean cmdColor(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (!g.roleOf(p.getUniqueId()).canTransfer()) { msg(p, "&cOnly the owner can set the group colour."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group color <aqua|green|pink|yellow|red|gold|blue|purple>"); return true; }
        String code = colorCodeOf(a[0]);
        if (code == null) { msg(p, "&cUnknown colour."); return true; }
        g.colorCode = code;
        msg(p, "&dGroup colour set: " + g.colored());
        return true;
    }

    private String colorCodeOf(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "aqua": case "cyan":   return "&b";
            case "green":               return "&a";
            case "pink": case "magenta":return "&d";
            case "yellow":              return "&e";
            case "red":                 return "&c";
            case "gold": case "orange": return "&6";
            case "blue":                return "&9";
            case "purple":              return "&5";
            case "white":               return "&f";
            default:                    return null;
        }
    }

    // ---- group teleport requests ----

    private boolean cmdTp(Player p, String[] a) {
        Group g = groupOf(p.getUniqueId());
        if (g == null) { msg(p, "&cYou're not in a group."); return true; }
        if (a.length < 1) { msg(p, "&cUsage: &f/group tp <member>"); return true; }
        UUID target = findMemberByName(g, a[0]);
        if (target == null || target.equals(p.getUniqueId())) { msg(p, "&cNo other member called &f" + a[0] + "&c."); return true; }
        Player tp = Bukkit.getPlayer(target);
        if (tp == null) { msg(p, "&cThey're not online."); return true; }
        if (!setting(target, "teleportRequests", true)) { msg(p, "&c" + tp.getName() + " isn't accepting teleport requests."); return true; }
        tpReqs.put(target, new TpReq(p.getUniqueId(), System.currentTimeMillis() + tpExpiryMs));
        msg(p, "&dAsked &f" + tp.getName() + " &dto let you teleport. Expires soon.");
        msg(tp, "&f" + p.getName() + " &dwants to teleport to you. &f/group tpaccept &dor &f/group tpdeny&d.");
        pickupSound(tp);
        return true;
    }

    private boolean cmdTpAccept(Player p) {
        TpReq req = tpReqs.remove(p.getUniqueId());
        if (req == null) { msg(p, "&cYou have no pending teleport requests."); return true; }
        if (System.currentTimeMillis() > req.expiry) { msg(p, "&cThat request expired."); return true; }
        Player requester = Bukkit.getPlayer(req.requester);
        if (requester == null) { msg(p, "&cThey're no longer online."); return true; }
        Location dest = p.getLocation();
        try { requester.teleportAsync(dest); } catch (Throwable ignored) {}
        msg(requester, "&dTeleporting you to &f" + p.getName() + "&d~");
        msg(p, "&dAccepted &f" + requester.getName() + "&d's teleport.");
        return true;
    }

    private boolean cmdTpDeny(Player p) {
        TpReq req = tpReqs.remove(p.getUniqueId());
        if (req == null) { msg(p, "&cYou have no pending teleport requests."); return true; }
        Player requester = Bukkit.getPlayer(req.requester);
        if (requester != null) msg(requester, "&c" + p.getName() + " denied your teleport request.");
        msg(p, "&dDenied.");
        return true;
    }

    // ============================================================ internals

    private UUID findMemberByName(Group g, String name) {
        for (UUID u : g.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.getName().equalsIgnoreCase(name)) return u;
        }
        return null;
    }

    private void notifyMembers(Group g, UUID except, String setting, String text) {
        for (UUID u : g.members.keySet()) {
            if (except != null && u.equals(except)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null && setting(u, setting, true)) msg(p, "&8[" + g.colored() + "&8] " + text);
        }
    }

    /** Remove a member, reset their hearts, recalc the rest. {@code silent} skips leave notices. */
    private void removeMemberInternal(Group g, UUID u, boolean silent) {
        g.members.remove(u);
        g.joinRequests.remove(u);
        playerGroup.remove(u);
        chatChannel.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (p != null) resetHearts(p);
        if (g.members.isEmpty()) { groups.remove(g.id); return; }
        recalc(g);
        if (!silent) {
            String who = p != null ? p.getName() : "A member";
            notifyMembers(g, u, "leaveNotifications", "&f" + who + " &dleft the group~");
        }
    }

    private void disbandInternal(Group g, String reason) {
        for (UUID u : new ArrayList<>(g.members.keySet())) {
            playerGroup.remove(u);
            chatChannel.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                resetHearts(p);
                if (!u.equals(g.owner)) msg(p, "&d" + g.colored() + " &ddisbanded " + reason + "&d.");
            }
        }
        g.members.clear();
        groups.remove(g.id);
    }

    private void sendGroupChat(Group g, String speaker, String text) {
        String line = color("&a[" + g.name + " chat] &f" + speaker + "&7: &f" + text);
        // Snapshot the members: this runs from the async chat event too, while
        // the main thread may be adding/removing members.
        for (UUID u : new ArrayList<>(g.members.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(isBedrock(u) ? bedrockText(line) : line);
        }
    }

    // ============================================================ friendly fire

    /** Stop members of the same group from hurting each other (incl. projectiles). */
    @EventHandler(ignoreCancelled = true)
    public void onPartyDamage(EntityDamageByEntityEvent e) {
        if (!preventPartyPvp) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity damager = e.getDamager();
        // Blame the shooter for arrows / thrown potions / etc.
        if (damager instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Entity src) {
            damager = src;
        }
        if (!(damager instanceof Player attacker)) return;
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return; // self
        Group g = groupOf(attacker.getUniqueId());
        if (g != null && g.has(victim.getUniqueId())) {
            e.setCancelled(true); // same group → no friendly fire
        }
    }

    // ============================================================ auto announcements

    /** A group member slays a boss → server-wide brag. */
    @EventHandler
    public void onBossKill(EntityDeathEvent e) {
        if (!announceEnabled || !announceBosses) return;
        if (!bossTypes.contains(e.getEntityType())) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        Group g = groupOf(killer.getUniqueId());
        if (g == null) return;
        broadcastGroup(g, killer.getName() + " " + bossVerb(e.getEntityType()) + "!");
    }

    /** A group member earns a notable advancement → optional server-wide brag. */
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!announceEnabled || !announceAdvancements) return;
        String key = e.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/")) return; // recipe unlocks are noise
        Player p = e.getPlayer();
        Group g = groupOf(p.getUniqueId());
        if (g == null) return;
        broadcastGroup(g, p.getName() + " earned " + advancementTitle(e.getAdvancement(), key));
    }

    /** Nice past-tense verb for a slain boss; falls back to the prettified type. */
    private String bossVerb(EntityType t) {
        switch (t.name()) {
            case "ENDER_DRAGON":   return "slew the Ender Dragon";
            case "WITHER":         return "defeated the Wither";
            case "WARDEN":         return "conquered the Warden";
            case "ELDER_GUARDIAN": return "vanquished an Elder Guardian";
            default:               return "defeated a " + prettify(t.name());
        }
    }

    /** Best-effort display title of an advancement, else the prettified key. */
    private String advancementTitle(org.bukkit.advancement.Advancement adv, String key) {
        try {
            if (adv.getDisplay() != null) {
                String title = adv.getDisplay().getTitle();
                if (title != null && !title.isEmpty()) return ChatColor.stripColor(title);
            }
        } catch (Throwable ignored) { /* getDisplay() not on older API */ }
        int slash = key.lastIndexOf('/');
        return "the " + prettify(slash >= 0 ? key.substring(slash + 1) : key) + " advancement";
    }

    /** ENDER_DRAGON / nether_travel → "Ender Dragon" / "Nether Travel". */
    private String prettify(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("[_/]");
        StringBuilder sb = new StringBuilder();
        for (String w : parts) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ============================================================ chat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Group g = groupOf(p.getUniqueId());
        if (g == null) return;
        if (chatChannel.contains(p.getUniqueId())) {
            // Channel mode: route only to the group.
            e.setCancelled(true);
            sendGroupChat(g, p.getName(), e.getMessage());
            return;
        }
        // Otherwise prepend the coloured [group] tag to public chat.
        String tag = color(g.colorCode + "[" + g.name + "]&r ").replace("%", "%%");
        e.setFormat(tag + e.getFormat());
    }

    // ============================================================ join/quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // They can't be in a group (removed on their last quit), so make sure
        // their hearts are at base and no stale bonus metadata lingers.
        resetHearts(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        invites.remove(id);
        tpReqs.remove(id);
        chatChannel.remove(id);
        Group g = groupOf(id);
        if (g != null) {
            if (g.owner.equals(id)) {
                disbandInternal(g, "&7(the owner went offline)");
            } else {
                removeMemberInternal(g, id, false);
            }
        }
        // Drop the bonus metadata so a relog starts clean.
        p.removeMetadata(META_KEY, this);
    }

    // ============================================================ GUIs

    private Inventory newGui(GroupHolder.Kind kind, Player p, String title) {
        GroupHolder holder = new GroupHolder(kind);
        Inventory inv = Bukkit.createInventory(holder, 54, titleFor(p, title));
        holder.setInventory(inv);
        paintBorder(inv, guiFrame);
        return inv;
    }

    private void openMain(Player p) {
        Inventory inv = newGui(GroupHolder.Kind.MAIN, p, "&b✦ Groups ✦");
        Group g = groupOf(p.getUniqueId());
        ItemStack mine = g == null
                ? menuItem(Material.BOOK, "&7You're not in a group", "&7Make one: &f/group create <name>")
                : menuItem(Material.BEACON, g.colored(), "&7" + g.size() + " online member(s)", "&8Left-click to open");
        if (g != null) tagGroup(mine, g.id);
        inv.setItem(13, mine);
        inv.setItem(20, action(menuItem(Material.CHEST, "&a✦ Groups", "&7Browse active groups."), "groups"));
        inv.setItem(22, action(menuItem(Material.PAPER, "&e✦ Pending Invites", "&7See your group invites."), "invites"));
        inv.setItem(24, action(menuItem(Material.COMPARATOR, "&d✦ Settings", "&7Toggle invites, sounds, etc."), "settings"));
        inv.setItem(30, action(menuItem(Material.PLAYER_HEAD, "&b✦ Invite a Player", "&7Pick an online player to invite."), "search"));
        inv.setItem(32, action(menuItem(Material.BARRIER, "&c✦ Close"), "close"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    private ItemStack action(ItemStack it, String act) { tagAction(it, act); return it; }

    private void openGroups(Player p) {
        Inventory inv = newGui(GroupHolder.Kind.GROUPS, p, "&b✦ Groups ✦");
        List<Group> visible = new ArrayList<>();
        for (Group g : groups.values()) {
            if (!g.privateMode || g.has(p.getUniqueId())) visible.add(g);
        }
        int idx = 0;
        for (Group g : visible) {
            if (idx >= LIST_SLOTS.length) break;
            OfflinePlayer ow = Bukkit.getOfflinePlayer(g.owner);
            ItemStack it = menuItem(Material.WHITE_BANNER, g.colored(),
                    "&7Owner: &f" + pretty(ow),
                    "&7Members online: &f" + g.size(),
                    g.has(p.getUniqueId()) ? "&8Left-click: open  &8Right-click: leave" : "&8Left-click: open / request to join");
            tagGroup(it, g.id);
            inv.setItem(LIST_SLOTS[idx++], it);
        }
        if (visible.isEmpty()) inv.setItem(LIST_SLOTS[0], menuItem(Material.BARRIER, "&cNo groups yet", "&7Make one: &f/group create <name>"));
        inv.setItem(BACK, action(menuItem(Material.ARROW, "&7↩ Back"), "main"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    private void openProfile(Player p, String groupId) {
        Group g = groups.get(groupId);
        if (g == null) { openGroups(p); return; }
        Inventory inv = newGui(GroupHolder.Kind.PROFILE, p, "&b✦ " + g.name + " ✦");
        ((GroupHolder) inv.getHolder()).setContext(groupId);
        OfflinePlayer ow = Bukkit.getOfflinePlayer(g.owner);
        boolean member = g.has(p.getUniqueId());
        inv.setItem(13, menuItem(Material.WHITE_BANNER, g.colored(),
                "&7Owner: &f" + pretty(ow),
                "&7Members online: &f" + g.size(),
                "&7Hearts here: &f" + Math.min(maxHeartsCap, heartsPerMember * g.size()),
                "&7" + (g.description.isEmpty() ? "&8(no description)" : g.description)));
        inv.setItem(20, action(tagGroupCopy(menuItem(Material.PLAYER_HEAD, "&b✦ Invite Player", "&7Invite an online player."), g.id), "invite"));
        inv.setItem(21, action(tagGroupCopy(menuItem(Material.SKELETON_SKULL, "&a✦ Members", "&7View / manage members."), g.id), "members"));
        inv.setItem(22, action(tagGroupCopy(menuItem(Material.OAK_SIGN, "&e✦ Group Chat", "&7Toggle group-only chat.", chatChannel.contains(p.getUniqueId()) ? "&aCurrently ON" : "&8Currently off"), g.id), "togglechat"));
        inv.setItem(23, action(menuItem(Material.COMPARATOR, "&d✦ Settings", "&7Your personal toggles."), "settings"));
        if (member) {
            inv.setItem(24, action(tagGroupCopy(menuItem(Material.IRON_DOOR, "&c✦ Leave Group"), g.id), "leave"));
        } else {
            inv.setItem(24, action(tagGroupCopy(menuItem(Material.LIME_DYE, "&a✦ Request to Join"), g.id), "join"));
        }
        inv.setItem(BACK, action(menuItem(Material.ARROW, "&7↩ Back"), "groups"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    private ItemStack tagGroupCopy(ItemStack it, String id) { tagGroup(it, id); return it; }

    private void openMembers(Player p, String groupId) {
        Group g = groups.get(groupId);
        if (g == null) { openGroups(p); return; }
        Inventory inv = newGui(GroupHolder.Kind.MEMBERS, p, "&b✦ " + g.name + " — Members ✦");
        ((GroupHolder) inv.getHolder()).setContext(groupId);
        int idx = 0;
        for (Map.Entry<UUID, Role> en : g.members.entrySet()) {
            if (idx >= LIST_SLOTS.length) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(en.getKey());
            ItemStack it = head(op, en.getValue().tag() + " &f" + pretty(op),
                    "&7Role: " + en.getValue().tag(),
                    "&8Left: promote  &8Right: demote",
                    "&8Shift-left: transfer  &8Shift-right: kick");
            tagTarget(it, en.getKey());
            inv.setItem(LIST_SLOTS[idx++], it);
        }
        inv.setItem(BACK, action(tagGroupCopy(menuItem(Material.ARROW, "&7↩ Back"), g.id), "profile"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    private void openInvites(Player p) {
        Inventory inv = newGui(GroupHolder.Kind.INVITES, p, "&b✦ Your Invites ✦");
        Invite inv0 = activeInvite(p.getUniqueId());
        if (inv0 == null) {
            inv.setItem(LIST_SLOTS[0], menuItem(Material.BARRIER, "&cNo pending invites"));
        } else {
            Group g = groups.get(inv0.groupId);
            OfflinePlayer from = Bukkit.getOfflinePlayer(inv0.inviter);
            inv.setItem(LIST_SLOTS[2], action(menuItem(Material.LIME_DYE, "&a✦ Accept",
                    "&7Join " + (g != null ? g.colored() : "&fthe group"), "&7Invited by &f" + pretty(from)), "acceptinvite"));
            inv.setItem(LIST_SLOTS[4], action(menuItem(Material.RED_DYE, "&c✦ Deny"), "denyinvite"));
            inv.setItem(LIST_SLOTS[6], action(head(from, "&7Block &f" + pretty(from), "&7Stop future invites from them."), "blockinviter"));
        }
        inv.setItem(BACK, action(menuItem(Material.ARROW, "&7↩ Back"), "main"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    private void openSettings(Player p) {
        Inventory inv = newGui(GroupHolder.Kind.SETTINGS, p, "&b✦ Group Settings ✦");
        inv.setItem(10, toggle(p, "groupInvites", true, Material.PAPER, "Group Invites"));
        inv.setItem(11, toggle(p, "friendRequests", true, Material.WRITABLE_BOOK, "Friend Requests"));
        inv.setItem(12, toggle(p, "teleportRequests", true, Material.ENDER_PEARL, "Teleport Requests"));
        inv.setItem(13, toggle(p, "joinNotifications", true, Material.LIME_DYE, "Join Notifications"));
        inv.setItem(14, toggle(p, "leaveNotifications", true, Material.RED_DYE, "Leave Notifications"));
        inv.setItem(15, toggle(p, "sounds", true, Material.NOTE_BLOCK, "Sounds"));
        inv.setItem(16, toggle(p, "privateMode", false, Material.IRON_DOOR, "Private Mode"));
        inv.setItem(BACK, action(menuItem(Material.ARROW, "&7↩ Back"), "main"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    private ItemStack toggle(Player p, String key, boolean def, Material mat, String label) {
        boolean on = setting(p.getUniqueId(), key, def);
        ItemStack it = menuItem(mat, (on ? "&a" : "&7") + label, on ? "&aEnabled" : "&7Disabled", "&8Click to toggle");
        tagAction(it, "toggle:" + key + ":" + def);
        return it;
    }

    private void openSearch(Player p) {
        Group g = groupOf(p.getUniqueId());
        Inventory inv = newGui(GroupHolder.Kind.SEARCH, p, "&b✦ Invite a Player ✦");
        if (g == null || !g.roleOf(p.getUniqueId()).canInvite()) {
            inv.setItem(LIST_SLOTS[0], menuItem(Material.BARRIER, "&cYou can't invite right now",
                    "&7Be in a group with invite rights."));
        } else {
            int idx = 0;
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (idx >= LIST_SLOTS.length) break;
                if (t.getUniqueId().equals(p.getUniqueId())) continue;
                if (groupOf(t.getUniqueId()) != null) continue;
                ItemStack it = head(t, "&b" + t.getName(), "&7Click to invite to " + g.colored());
                tagTarget(it, t.getUniqueId());
                inv.setItem(LIST_SLOTS[idx++], it);
            }
            if (idx == 0) inv.setItem(LIST_SLOTS[0], menuItem(Material.BARRIER, "&cNobody to invite", "&7No groupless players online."));
        }
        inv.setItem(BACK, action(menuItem(Material.ARROW, "&7↩ Back"), "main"));
        applyBedrock(p, inv);
        p.openInventory(inv);
        pickupSound(p);
    }

    // ============================================================ GUI clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GroupHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof GroupHolder)) return;
        ItemStack it = e.getCurrentItem();
        if (it == null) return;
        String act = readAction(it);
        String grp = readGroup(it);
        UUID target = readTarget(it);

        // Navigation / generic actions.
        if (act != null) {
            switch (act) {
                case "main":     openMain(p); return;
                case "groups":   openGroups(p); return;
                case "invites":  openInvites(p); return;
                case "settings": openSettings(p); return;
                case "search":   openSearch(p); return;
                case "close":    p.closeInventory(); return;
                case "profile":  if (grp != null) openProfile(p, grp); return;
                case "members":  if (grp != null) openMembers(p, grp); return;
                case "invite":   openSearch(p); return;
                case "togglechat": cmdToggleChat(p); if (grp != null) openProfile(p, grp); return;
                case "leave":    cmdLeave(p); openMain(p); return;
                case "join":     requestJoin(p, grp); return;
                case "acceptinvite": cmdAccept(p, new String[0]); p.closeInventory(); return;
                case "denyinvite":   cmdDeny(p, new String[0]); openMain(p); return;
                case "blockinviter": blockInviter(p); openMain(p); return;
                default:
                    if (act.startsWith("toggle:")) {
                        String[] parts = act.split(":");
                        boolean def = parts.length > 2 && Boolean.parseBoolean(parts[2]);
                        toggleSetting(p.getUniqueId(), parts[1], def);
                        openSettings(p);
                        return;
                    }
                    return;
            }
        }

        // Group icon (Main/Groups menus): left = open, right = leave.
        if (grp != null && target == null) {
            if (holder.getKind() == GroupHolder.Kind.GROUPS || holder.getKind() == GroupHolder.Kind.MAIN) {
                if (e.isRightClick() && groups.containsKey(grp) && groups.get(grp).has(p.getUniqueId())) {
                    cmdLeave(p); openGroups(p);
                } else {
                    openProfile(p, grp);
                }
                return;
            }
        }

        // Member head (Members menu) or invite-target head (Search menu).
        if (target != null) {
            if (holder.getKind() == GroupHolder.Kind.SEARCH) {
                Player t = Bukkit.getPlayer(target);
                if (t != null) cmdInvite(p, new String[]{ t.getName() });
                openSearch(p);
                return;
            }
            if (holder.getKind() == GroupHolder.Kind.MEMBERS) {
                handleMemberClick(p, holder.getContext(), target, e);
                return;
            }
        }
    }

    private void handleMemberClick(Player p, String groupId, UUID target, InventoryClickEvent e) {
        Group g = groups.get(groupId);
        if (g == null || !g.has(target)) { openGroups(p); return; }
        Player tp = Bukkit.getPlayer(target);
        String name = tp != null ? tp.getName() : pretty(Bukkit.getOfflinePlayer(target));
        if (e.isShiftClick() && e.isLeftClick())  { cmdTransfer(p, new String[]{ name }); }
        else if (e.isShiftClick() && e.isRightClick()) { cmdKick(p, new String[]{ name }); }
        else if (e.isLeftClick())  { cmdPromote(p, new String[]{ name }, true); }
        else if (e.isRightClick()) { cmdPromote(p, new String[]{ name }, false); }
        if (groups.containsKey(groupId)) openMembers(p, groupId); else openGroups(p);
    }

    private void requestJoin(Player p, String groupId) {
        Group g = groupId == null ? null : groups.get(groupId);
        if (g == null) { msg(p, "&cThat group is gone."); return; }
        if (groupOf(p.getUniqueId()) != null) { msg(p, "&cLeave your current group first."); return; }
        g.joinRequests.add(p.getUniqueId());
        msg(p, "&dRequested to join " + g.colored() + "&d. A moderator can approve you.");
        for (UUID u : g.members.keySet()) {
            if (g.roleOf(u).canManageRequests()) {
                Player mod = Bukkit.getPlayer(u);
                if (mod != null) msg(mod, "&f" + p.getName() + " &dasked to join — invite them with &f/group invite " + p.getName());
            }
        }
    }

    private void blockInviter(Player p) {
        Invite inv = invites.get(p.getUniqueId());
        if (inv == null) { msg(p, "&cNo invite to block."); return; }
        List<String> b = new ArrayList<>(blocked(p.getUniqueId()));
        if (!b.contains(inv.inviter.toString())) b.add(inv.inviter.toString());
        setBlocked(p.getUniqueId(), b);
        invites.remove(p.getUniqueId());
        OfflinePlayer op = Bukkit.getOfflinePlayer(inv.inviter);
        msg(p, "&dBlocked future invites from &f" + pretty(op) + "&d.");
    }

    // ============================================================ tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("gc")) return Collections.emptyList();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> subs = java.util.Arrays.asList("create", "invite", "accept", "deny", "leave",
                    "disband", "kick", "promote", "demote", "owner", "members", "announce", "chat",
                    "color", "tp", "tpaccept", "tpdeny", "menu", "help");
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(pre)) out.add(s);
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String pre = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (sub.equals("invite")) {
                for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            } else if (sub.equals("kick") || sub.equals("promote") || sub.equals("demote") || sub.equals("owner") || sub.equals("tp")) {
                if (sender instanceof Player p) {
                    Group g = groupOf(p.getUniqueId());
                    if (g != null) for (UUID u : g.members.keySet()) {
                        Player m = Bukkit.getPlayer(u);
                        if (m != null && m.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(m.getName());
                    }
                }
            }
            return out;
        }
        return Collections.emptyList();
    }
}
