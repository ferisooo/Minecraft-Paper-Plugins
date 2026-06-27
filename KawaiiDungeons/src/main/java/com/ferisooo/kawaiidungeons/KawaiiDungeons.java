package com.ferisooo.kawaiidungeons;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * KawaiiDungeons — instanced party dungeons via template-world cloning.
 *
 * <p>Parties ({@link PartyManager}) enter cloned dungeon worlds managed by
 * {@link InstanceManager}; mobs are tagged by {@link MobFactory}; loot rolls via
 * {@link LootManager}; persistent progression lives in {@link ProgressManager}.
 * Gameplay enforcement is in {@link DungeonListeners}.
 *
 * <p>All version-sensitive APIs are avoided (no Sound/Attribute/PotionEffectType
 * enums) — sounds use String keys, mob health uses the deprecated setters, and
 * mob damage is scaled by multiplying the damage event.
 */
public final class KawaiiDungeons extends JavaPlugin implements Listener {

    private final Map<String, DungeonDef> dungeons = new LinkedHashMap<>();

    private PartyManager parties;
    private MobFactory mobFactory;
    private LootManager lootManager;
    private ProgressManager progressManager;
    private InstanceManager instanceManager;

    // ---- animated GUI ----
    private static final Material[] SHIMMER = {
            Material.PINK_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE };
    private int guiFrame = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("dungeons.yml");
        saveResourceIfMissing("loot.yml");

        parties = new PartyManager(this);
        mobFactory = new MobFactory(this);
        lootManager = new LootManager(this, new File(getDataFolder(), "loot.yml"));
        lootManager.load();
        progressManager = new ProgressManager(this, new File(getDataFolder(), "playerdata.yml"));
        progressManager.load();
        progressManager.startAutoSave();
        instanceManager = new InstanceManager(this, mobFactory, lootManager, progressManager);

        loadDungeons();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new DungeonListeners(this, instanceManager, mobFactory), this);

        long tickTicks = Math.max(1L, getConfig().getLong("instance-tick-ticks", 20L));
        // Folia-safe: a global-region repeating driver reads the live instance
        // collection, then hops each instance's per-tick work onto the region
        // thread owning that instance's world (entities/blocks there must only be
        // touched on their own region thread). See InstanceManager.tickAll.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> instanceManager.tickAll(), tickTicks, tickTicks);

        // Shimmer the open dungeon menus. Global-region driver reads the online
        // players, then hops each player's inventory update onto that player's
        // own entity scheduler (inventory is per-player region state).
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> animateGuis(), 4L, 4L);

        getLogger().info("(✿) KawaiiDungeons ready ~ " + dungeons.size() + " dungeon(s) loaded! /dungeon to begin~");
    }

    @Override
    public void onDisable() {
        if (instanceManager != null) instanceManager.shutdownAll();
        if (progressManager != null) progressManager.saveSync();
        // Folia: cancel our regionized scheduler tasks (tick driver, GUI driver,
        // autosave, async writes) — the legacy Bukkit.getScheduler() doesn't exist.
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
    }

    private void saveResourceIfMissing(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists()) saveResource(name, false);
    }

    private void loadDungeons() {
        dungeons.clear();
        File f = new File(getDataFolder(), "dungeons.yml");
        if (!f.exists()) saveResource("dungeons.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("dungeons");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s != null) dungeons.put(key.toLowerCase(Locale.ROOT), DungeonDef.parse(key.toLowerCase(Locale.ROOT), s));
        }
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("party")) return handleParty(sender, args);
        return handleDungeon(sender, args);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        // /party (alias p) invite|kick <player> — suggest online player names at the name arg (args[1]).
        if (command.getName().equalsIgnoreCase("party")
                && args.length == 2
                && (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("kick"))) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            }
            return out;
        }
        return Collections.emptyList();
    }

    // ----- /dungeon -----

    private boolean handleDungeon(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kawaiidungeons.admin")) { sender.sendMessage("§c(✿) no permission~"); return true; }
            reloadConfig();
            lootManager.load();
            loadDungeons();
            sender.sendMessage("§d(✿) KawaiiDungeons reloaded ✨");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("§c(✿) players only~"); return true; }
        if (!p.hasPermission("kawaiidungeons.use")) { p.sendMessage("§c(✿) no permission~"); return true; }

        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> openDungeonMenu(p);
            case "list" -> cmdList(p);
            case "info" -> cmdInfo(p, args);
            case "start" -> cmdStart(p, args);
            case "leave" -> instanceManager.leave(p);
            case "tokens" -> p.sendMessage("§d(✿) you have §f" + progressManager.get(p.getUniqueId()).tokens()
                    + "§d dungeon tokens. (dungeon level §f" + progressManager.get(p.getUniqueId()).dungeonLevel() + "§d)");
            case "weekly" -> cmdWeekly(p);
            case "rep" -> cmdRep(p);
            case "leaderboard", "lb" -> cmdLeaderboard(p, args);
            default -> dungeonHelp(p);
        }
        return true;
    }

    private void dungeonHelp(Player p) {
        p.sendMessage("§d(✿) §lKawaiiDungeons");
        p.sendMessage("  §f/dg list §7• §f/dg info <id> §7• §f/dg menu");
        p.sendMessage("  §f/dg start <id> <normal|hard|nightmare|mythic> [--speedrun] [--deathless] [--hardcore]");
        p.sendMessage("  §f/dg leave §7• §f/dg tokens §7• §f/dg weekly §7• §f/dg rep");
        p.sendMessage("  §f/dg leaderboard <id> <difficulty>");
        p.sendMessage("  §f/party §7for party commands");
    }

    private void cmdList(Player p) {
        p.sendMessage("§d(✿) §lAvailable Dungeons:");
        if (dungeons.isEmpty()) { p.sendMessage("§7  (none configured~)"); return; }
        for (DungeonDef d : dungeons.values()) {
            p.sendMessage("  §f" + d.id + " §7— " + ChatColor.translateAlternateColorCodes('&', d.displayName)
                    + " §7(lvl " + d.levelRequirement + ", gear " + d.gearScoreRequirement + ")");
        }
    }

    private void cmdInfo(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§c(✿) usage: §f/dg info <id>"); return; }
        DungeonDef d = dungeons.get(args[1].toLowerCase(Locale.ROOT));
        if (d == null) { p.sendMessage("§c(✿) no dungeon named §f" + args[1]); return; }
        p.sendMessage("§d(✿) §l" + ChatColor.translateAlternateColorCodes('&', d.displayName));
        p.sendMessage("  §7id: §f" + d.id + " §7• template: §f" + d.templateWorld);
        p.sendMessage("  §7objective: §f" + d.objectiveType + " §7• time limit: §f" + d.timeLimitSeconds + "s");
        p.sendMessage("  §7level req: §f" + d.levelRequirement + " §7• gear req: §f" + d.gearScoreRequirement);
        p.sendMessage("  §7tokens: §f" + d.tokenReward + " §7• rep: §f" + d.reputationReward
                + " §7• boss: §f" + (d.hasBoss() ? d.bossType : "none"));
    }

    private void cmdStart(Player p, String[] args) {
        if (args.length < 3) {
            p.sendMessage("§c(✿) usage: §f/dg start <id> <normal|hard|nightmare|mythic> [--speedrun] [--deathless] [--hardcore]");
            return;
        }
        DungeonDef d = dungeons.get(args[1].toLowerCase(Locale.ROOT));
        if (d == null) { p.sendMessage("§c(✿) no dungeon named §f" + args[1]); return; }
        DungeonInstance.Difficulty diff = DungeonInstance.Difficulty.from(args[2]);

        boolean speedrun = false, deathless = false, hardcore = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "--speedrun" -> speedrun = true;
                case "--deathless" -> deathless = true;
                case "--hardcore" -> hardcore = true;
                default -> { /* ignore unknown flag */ }
            }
        }
        startRun(p, d, diff, speedrun, deathless, hardcore);
    }

    /** Validates the party + requirements, then asks {@link InstanceManager} to start. */
    private void startRun(Player p, DungeonDef d, DungeonInstance.Difficulty diff,
                          boolean speedrun, boolean deathless, boolean hardcore) {
        Party party = parties.partyOf(p.getUniqueId());
        List<Player> members = new ArrayList<>();
        UUID leader;
        if (party == null) {
            members.add(p);
            leader = p.getUniqueId();
        } else {
            if (!party.isLeader(p.getUniqueId())) {
                p.sendMessage("§c(✿) only the party leader can start a dungeon~");
                return;
            }
            leader = party.leader();
            for (UUID id : party.memberList()) {
                Player m = Bukkit.getPlayer(id);
                if (m != null && m.isOnline()) members.add(m);
            }
        }

        if (instanceManager.instanceOfPlayer(p) != null) {
            p.sendMessage("§c(✿) you're already in a dungeon — §f/dg leave§c first~");
            return;
        }

        // Requirement checks for ALL members.
        for (Player m : members) {
            PlayerProgress pr = progressManager.get(m.getUniqueId());
            if (pr.dungeonLevel() < d.levelRequirement) {
                p.sendMessage("§c(✿) §f" + m.getName() + "§c needs dungeon level §f" + d.levelRequirement
                        + "§c (has §f" + pr.dungeonLevel() + "§c)~");
                return;
            }
            int gs = gearScore(m);
            if (gs < d.gearScoreRequirement) {
                p.sendMessage("§c(✿) §f" + m.getName() + "§c needs gear score §f" + d.gearScoreRequirement
                        + "§c (has §f" + gs + "§c)~");
                return;
            }
            if (instanceManager.instanceOfPlayer(m) != null) {
                p.sendMessage("§c(✿) §f" + m.getName() + "§c is already in a dungeon~");
                return;
            }
        }

        instanceManager.start(d, diff, members, leader, speedrun, deathless, hardcore);
    }

    private void cmdWeekly(Player p) {
        if (!getConfig().getBoolean("weekly.enabled", true)) { p.sendMessage("§c(✿) weekly rewards are disabled~"); return; }
        PlayerProgress pr = progressManager.get(p.getUniqueId());
        long cooldownMs = getConfig().getLong("weekly.cooldown-days", 7) * 24L * 60L * 60L * 1000L;
        long now = System.currentTimeMillis();
        if (now - pr.lastWeeklyClaim() < cooldownMs) {
            long remaining = (cooldownMs - (now - pr.lastWeeklyClaim())) / (60L * 60L * 1000L);
            p.sendMessage("§c(✿) you already claimed this week~ (§f" + remaining + "h§c left)");
            return;
        }
        int tokens = getConfig().getInt("weekly.tokens", 100);
        pr.addTokens(tokens);
        pr.setLastWeeklyClaim(now);
        progressManager.save();
        p.sendMessage("§d(✿) ✨ weekly reward claimed: +§f" + tokens + "§d tokens!");
        p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 1f, 1.3f);
    }

    private void cmdRep(Player p) {
        PlayerProgress pr = progressManager.get(p.getUniqueId());
        p.sendMessage("§d(✿) §lYour Reputation:");
        if (pr.reputationMap().isEmpty()) { p.sendMessage("§7  (none yet — clear some dungeons!)"); return; }
        for (Map.Entry<String, Integer> e : pr.reputationMap().entrySet()) {
            p.sendMessage("  §f" + e.getKey() + " §7— §f" + e.getValue());
        }
    }

    private void cmdLeaderboard(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage("§c(✿) usage: §f/dg leaderboard <id> <difficulty>"); return; }
        String key = args[1].toLowerCase(Locale.ROOT) + ":" + args[2].toLowerCase(Locale.ROOT);
        List<Map.Entry<UUID, Long>> rows = progressManager.speedrunLeaderboard(key, 10);
        p.sendMessage("§d(✿) §l✿ Speedrun Leaderboard §7(" + key + ")");
        if (rows.isEmpty()) { p.sendMessage("§7  (no times yet~)"); return; }
        int rank = 1;
        for (Map.Entry<UUID, Long> r : rows) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(r.getKey());
            String name = op.getName() == null ? r.getKey().toString().substring(0, 8) : op.getName();
            long secs = r.getValue() / 1000L;
            p.sendMessage("  §f#" + rank + " §d" + name + " §7— §f" + (secs / 60) + "m " + (secs % 60) + "s");
            rank++;
        }
    }

    /** Simple gear score: sum of base material value + enchantment levels over armor + main hand. */
    private int gearScore(Player p) {
        int score = 0;
        List<ItemStack> gear = new ArrayList<>();
        for (ItemStack it : p.getInventory().getArmorContents()) gear.add(it);
        gear.add(p.getInventory().getItemInMainHand());
        for (ItemStack it : gear) {
            if (it == null || it.getType() == Material.AIR) continue;
            score += materialValue(it.getType());
            ItemMeta meta = it.getItemMeta();
            if (meta != null && meta.hasEnchants()) {
                for (int lvl : meta.getEnchants().values()) score += lvl;
            }
        }
        return score;
    }

    private static int materialValue(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE")) return 40;
        if (n.startsWith("DIAMOND")) return 30;
        if (n.startsWith("IRON")) return 15;
        if (n.startsWith("CHAINMAIL")) return 12;
        if (n.startsWith("GOLDEN") || n.startsWith("GOLD")) return 10;
        if (n.startsWith("STONE")) return 6;
        if (n.startsWith("LEATHER")) return 5;
        if (n.startsWith("WOODEN") || n.startsWith("WOOD")) return 3;
        if (n.contains("SWORD") || n.contains("AXE") || n.contains("BOW") || n.contains("TRIDENT")) return 8;
        return 1;
    }

    // ----- /party -----

    private boolean handleParty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("§c(✿) players only~"); return true; }
        if (!p.hasPermission("kawaiidungeons.use")) { p.sendMessage("§c(✿) no permission~"); return true; }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> partyCreate(p);
            case "invite" -> partyInvite(p, args);
            case "accept" -> partyAccept(p);
            case "leave" -> partyLeave(p);
            case "kick" -> partyKick(p, args);
            case "disband" -> partyDisband(p);
            case "list" -> partyList(p);
            case "tp" -> partyTp(p);
            default -> partyHelp(p);
        }
        return true;
    }

    private void partyHelp(Player p) {
        p.sendMessage("§d(✿) §lParty");
        p.sendMessage("  §f/p create §7• §f/p invite <player> §7• §f/p accept");
        p.sendMessage("  §f/p leave §7• §f/p kick <player> §7• §f/p disband");
        p.sendMessage("  §f/p list §7• §f/p tp");
    }

    private void partyCreate(Player p) {
        if (parties.inParty(p.getUniqueId())) { p.sendMessage("§c(✿) you're already in a party~"); return; }
        parties.create(p);
        p.sendMessage("§d(✿) ✨ party created! invite friends with §f/p invite <player>");
    }

    private void partyInvite(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§c(✿) usage: §f/p invite <player>"); return; }
        Party party = parties.partyOf(p.getUniqueId());
        if (party == null) { party = parties.create(p); p.sendMessage("§d(✿) (created a party for you)"); }
        if (!party.isLeader(p.getUniqueId())) { p.sendMessage("§c(✿) only the leader can invite~"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { p.sendMessage("§c(✿) §f" + args[1] + "§c isn't online~"); return; }
        if (target.equals(p)) { p.sendMessage("§c(✿) you can't invite yourself~"); return; }
        if (parties.inParty(target.getUniqueId())) { p.sendMessage("§c(✿) they're already in a party~"); return; }
        parties.invite(p.getUniqueId(), target.getUniqueId());
        int secs = getConfig().getInt("party.invite-expire-seconds", 120);
        p.sendMessage("§d(✿) invited §f" + target.getName() + "§d (they have " + secs + "s to §f/p accept§d)");
        target.sendMessage("§d(✿) §f" + p.getName() + "§d invited you to their party! type §f/p accept§d~");
    }

    private void partyAccept(Player p) {
        if (parties.inParty(p.getUniqueId())) { p.sendMessage("§c(✿) leave your current party first~"); return; }
        Party party = parties.accept(p.getUniqueId());
        if (party == null) { p.sendMessage("§c(✿) no valid invite (expired or party full)~"); return; }
        p.sendMessage("§d(✿) ✨ you joined the party!");
        Player leader = Bukkit.getPlayer(party.leader());
        if (leader != null) leader.sendMessage("§d(✿) §f" + p.getName() + "§d joined your party~");
    }

    private void partyLeave(Player p) {
        if (!parties.inParty(p.getUniqueId())) { p.sendMessage("§c(✿) you're not in a party~"); return; }
        parties.leave(p.getUniqueId());
        p.sendMessage("§d(✿) you left the party~");
    }

    private void partyKick(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage("§c(✿) usage: §f/p kick <player>"); return; }
        Party party = parties.partyOf(p.getUniqueId());
        if (party == null || !party.isLeader(p.getUniqueId())) { p.sendMessage("§c(✿) only the leader can kick~"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID targetId = target != null ? target.getUniqueId() : null;
        if (targetId == null || !party.isMember(targetId)) { p.sendMessage("§c(✿) that player isn't in your party~"); return; }
        if (targetId.equals(p.getUniqueId())) { p.sendMessage("§c(✿) use §f/p disband§c instead~"); return; }
        parties.kick(party, targetId);
        p.sendMessage("§d(✿) kicked §f" + target.getName() + "§d~");
        target.sendMessage("§c(✿) you were kicked from the party~");
    }

    private void partyDisband(Player p) {
        Party party = parties.partyOf(p.getUniqueId());
        if (party == null || !party.isLeader(p.getUniqueId())) { p.sendMessage("§c(✿) only the leader can disband~"); return; }
        for (UUID id : party.memberList()) {
            Player m = Bukkit.getPlayer(id);
            if (m != null) m.sendMessage("§d(✿) the party was disbanded~");
        }
        parties.disband(party);
    }

    private void partyList(Player p) {
        Party party = parties.partyOf(p.getUniqueId());
        if (party == null) { p.sendMessage("§c(✿) you're not in a party~"); return; }
        p.sendMessage("§d(✿) §lParty §7(" + party.size() + "):");
        for (UUID id : party.memberList()) {
            Player m = Bukkit.getPlayer(id);
            String name = m != null ? m.getName() : Bukkit.getOfflinePlayer(id).getName();
            p.sendMessage("  " + (party.isLeader(id) ? "§6★ " : "§7• ") + "§f" + name);
        }
    }

    private void partyTp(Player p) {
        Party party = parties.partyOf(p.getUniqueId());
        if (party == null || !party.isLeader(p.getUniqueId())) { p.sendMessage("§c(✿) only the leader can summon the party~"); return; }
        int moved = 0;
        for (UUID id : party.memberList()) {
            if (id.equals(p.getUniqueId())) continue;
            Player m = Bukkit.getPlayer(id);
            if (m != null) {
                Player member = m;
                member.teleportAsync(p.getLocation()).thenRun(() ->
                        member.sendMessage("§d(✿) summoned to the party leader~"));
                moved++;
            }
        }
        p.sendMessage("§d(✿) summoned §f" + moved + "§d member(s) to you~");
    }

    // --------------------------------------------------------------- GUI

    private static final class DgGuiHolder implements InventoryHolder {
        private Inventory inv;
        private final Map<Integer, String> slotDungeon = new HashMap<>();        // pick screen
        private final Map<Integer, DungeonInstance.Difficulty> slotDiff = new HashMap<>(); // difficulty screen
        private String selected; // dungeon id once chosen, for difficulty screen
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    // Inner content slots for a 45-slot menu (everything that isn't the perimeter).
    private static final int[] CONTENT_45 = {
            10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34 };

    private void openDungeonMenu(Player p) {
        DgGuiHolder holder = new DgGuiHolder();
        Inventory inv = Bukkit.createInventory(holder, 45,
                ChatColor.translateAlternateColorCodes('&', "&d✿ Dungeons ✿"));
        holder.inv = inv;
        paintBorder(inv, guiFrame);
        int i = 0;
        for (DungeonDef d : dungeons.values()) {
            if (i >= CONTENT_45.length) break;
            int slot = CONTENT_45[i++];
            inv.setItem(slot, button(Material.SKELETON_SKULL,
                    "&d" + ChatColor.translateAlternateColorCodes('&', d.displayName),
                    "&7id: &f" + d.id, "&7objective: &f" + d.objectiveType,
                    "&7lvl req: &f" + d.levelRequirement + " &7gear: &f" + d.gearScoreRequirement,
                    "&8click to pick difficulty"));
            holder.slotDungeon.put(slot, d.id);
        }
        if (dungeons.isEmpty()) {
            inv.setItem(22, button(Material.BARRIER, "&7No dungeons configured", "&8edit dungeons.yml"));
        }
        p.openInventory(inv);
    }

    private void openDifficultyMenu(Player p, String dungeonId) {
        DgGuiHolder holder = new DgGuiHolder();
        holder.selected = dungeonId;
        Inventory inv = Bukkit.createInventory(holder, 27,
                ChatColor.translateAlternateColorCodes('&', "&d✿ Difficulty: " + dungeonId));
        holder.inv = inv;
        paintBorder(inv, guiFrame);
        addDiff(holder, inv, 11, Material.LIME_DYE,   "&aNormal",    "&7baseline",        DungeonInstance.Difficulty.NORMAL);
        addDiff(holder, inv, 12, Material.ORANGE_DYE, "&6Hard",      "&7tougher mobs",    DungeonInstance.Difficulty.HARD);
        addDiff(holder, inv, 14, Material.RED_DYE,    "&cNightmare", "&7brutal",          DungeonInstance.Difficulty.NIGHTMARE);
        addDiff(holder, inv, 15, Material.PURPLE_DYE, "&5Mythic",    "&7maximum pain",    DungeonInstance.Difficulty.MYTHIC);
        p.openInventory(inv);
    }

    private void addDiff(DgGuiHolder holder, Inventory inv, int slot, Material mat,
                         String name, String lore, DungeonInstance.Difficulty diff) {
        inv.setItem(slot, button(mat, name, lore, "&8click to start"));
        holder.slotDiff.put(slot, diff);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof DgGuiHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof DgGuiHolder)) return;

        if (holder.selected == null) {
            // Dungeon-pick screen.
            String id = holder.slotDungeon.get(e.getRawSlot());
            if (id == null) return; // clicked the shimmer border or empty space
            openDifficultyMenu(p, id);
        } else {
            DungeonDef d = dungeons.get(holder.selected);
            if (d == null) { p.closeInventory(); return; }
            DungeonInstance.Difficulty diff = holder.slotDiff.get(e.getRawSlot());
            if (diff == null) return;
            p.closeInventory();
            startRun(p, d, diff, false, false, false);
        }
    }

    private void animateGuis() {
        guiFrame++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Touch the player's open inventory only on that player's region thread.
            p.getScheduler().run(this, t -> {
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof DgGuiHolder) {
                    paintBorder(top, guiFrame);
                    p.updateInventory();
                }
            }, null);
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

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(ChatColor.translateAlternateColorCodes('&', s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }
}
