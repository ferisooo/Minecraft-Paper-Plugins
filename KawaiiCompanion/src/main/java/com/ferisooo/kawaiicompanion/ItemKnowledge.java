package com.ferisooo.kawaiicompanion;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Static knowledge base for what items <i>are</i> and what they're <i>for</i>.
 *
 * <p>The companion's combat / equipment / chat code used to hard-code
 * "is this a sword?" "is this a bow?" in two or three different places —
 * with no concept of food, potions, tools, utility items, or anything
 * else a player normally carries. This class consolidates all of that
 * into one table so {@link KawaiiCompanion} can ask high-level questions
 * like:
 *
 * <ul>
 *   <li>"What's the best weapon in this inventory for fighting a zombie?"</li>
 *   <li>"Is there any food in this inventory I could eat?"</li>
 *   <li>"What kind of item is this — weapon, tool, food, …?"</li>
 *   <li>"How much damage would this sword do?"</li>
 * </ul>
 *
 * <p>Everything here is data. No Bukkit events, no NMS reflection, no
 * world side effects — just lookups against {@link Material}. Cheap,
 * thread-safe, and easy to test in isolation.
 *
 * <h2>Tier scale</h2>
 *
 * <p>Tools and weapons have a {@link #materialTier(Material)} value
 * roughly matching vanilla durability tiers: 0 = wood/none, 1 = stone,
 * 2 = iron, 3 = gold (fast but fragile), 4 = diamond, 5 = netherite.
 * Used for "best of category" comparisons without baking specific item
 * preferences into the caller.
 */
public final class ItemKnowledge {

    private ItemKnowledge() {}

    /** What broad role does an item play. */
    public enum Category {
        /** Sword, axe, trident — primary melee weapons. */
        WEAPON_MELEE,
        /** Bow, crossbow — ranged weapons (we treat them as a bow class). */
        WEAPON_RANGED,
        /** Pickaxe, axe-as-tool, shovel, hoe, shears — block-mining tools. */
        TOOL_MINING,
        /** Helmet, chestplate, leggings, boots, elytra. */
        ARMOR,
        /** Shield — passive defense. */
        SHIELD,
        /** Edible items that restore hunger. */
        FOOD,
        /** Drinkable / throwable potions (regular + splash + lingering). */
        POTION,
        /** Healing utility — totem, golden apple-class items handled as FOOD. */
        SAVE_LIFE,
        /** Fire utilities — flint+steel, fire charge. */
        FIRE_TOOL,
        /** Mobility / utility — ender pearl, snowball, water bucket, etc. */
        UTILITY,
        /** Light source — torches, lanterns, glowstone. */
        LIGHT_BLOCK,
        /** Generic placeable building block (stone, wood, dirt, …). */
        BLOCK_BUILDING,
        /** Crafting materials — ingots, gems, sticks, string, leather, … */
        MATERIAL,
        /** Container — chest, barrel, shulker box. */
        CONTAINER,
        /** Workstation — crafting table, furnace, anvil, brewing stand, etc. */
        WORKSTATION,
        /** Anything else not classified. */
        OTHER
    }

    /** Compact per-Material info record. */
    public static final class ItemInfo {
        public final Category category;
        /** Tool/weapon tier (0–5). 0 for non-tiered items. */
        public final int tier;
        /** Approximate vanilla base damage for weapons, 0 for non-weapons. */
        public final double meleeDamage;
        /** Approximate hunger restored for food, 0 for non-food. */
        public final int hungerRestored;
        /** True if this food can also save you (golden apple, totem, …). */
        public final boolean emergencySave;
        /** Free-form description we can include in DeepSeek context — short. */
        public final String shortDesc;

        ItemInfo(Category c, int tier, double dmg, int hunger, boolean save, String desc) {
            this.category = c;
            this.tier = tier;
            this.meleeDamage = dmg;
            this.hungerRestored = hunger;
            this.emergencySave = save;
            this.shortDesc = desc;
        }
    }

    /** Default for materials we haven't classified explicitly. */
    private static final ItemInfo OTHER = new ItemInfo(Category.OTHER, 0, 0.0, 0, false, "");

    private static final Map<Material, ItemInfo> TABLE = new EnumMap<>(Material.class);

    static {
        // ---- Swords (tiered) ----
        // Vanilla base damage = 4/5/6/6/7/8 for wooden/stone/iron/gold/diamond/netherite.
        // We use those numbers so combat code's tier-aware decisions match player expectations.
        put(Material.WOODEN_SWORD,    new ItemInfo(Category.WEAPON_MELEE, 0, 4.0, 0, false, "wooden sword"));
        put(Material.STONE_SWORD,     new ItemInfo(Category.WEAPON_MELEE, 1, 5.0, 0, false, "stone sword"));
        put(Material.IRON_SWORD,      new ItemInfo(Category.WEAPON_MELEE, 2, 6.0, 0, false, "iron sword"));
        put(Material.GOLDEN_SWORD,    new ItemInfo(Category.WEAPON_MELEE, 3, 4.0, 0, false, "golden sword"));
        put(Material.DIAMOND_SWORD,   new ItemInfo(Category.WEAPON_MELEE, 4, 7.0, 0, false, "diamond sword"));
        put(Material.NETHERITE_SWORD, new ItemInfo(Category.WEAPON_MELEE, 5, 8.0, 0, false, "netherite sword"));

        // ---- Axes (function as melee weapons too — slower but bigger hits) ----
        put(Material.WOODEN_AXE,    new ItemInfo(Category.WEAPON_MELEE, 0, 7.0, 0, false, "wooden axe"));
        put(Material.STONE_AXE,     new ItemInfo(Category.WEAPON_MELEE, 1, 9.0, 0, false, "stone axe"));
        put(Material.IRON_AXE,      new ItemInfo(Category.WEAPON_MELEE, 2, 9.0, 0, false, "iron axe"));
        put(Material.GOLDEN_AXE,    new ItemInfo(Category.WEAPON_MELEE, 3, 7.0, 0, false, "golden axe"));
        put(Material.DIAMOND_AXE,   new ItemInfo(Category.WEAPON_MELEE, 4, 9.0, 0, false, "diamond axe"));
        put(Material.NETHERITE_AXE, new ItemInfo(Category.WEAPON_MELEE, 5, 10.0, 0, false, "netherite axe"));

        // ---- Trident — versatile but rare ----
        put(Material.TRIDENT, new ItemInfo(Category.WEAPON_MELEE, 4, 9.0, 0, false, "trident"));
        put(Material.MACE,    new ItemInfo(Category.WEAPON_MELEE, 4, 6.0, 0, false, "mace"));

        // ---- Ranged ----
        put(Material.BOW,      new ItemInfo(Category.WEAPON_RANGED, 2, 0.0, 0, false, "bow"));
        put(Material.CROSSBOW, new ItemInfo(Category.WEAPON_RANGED, 2, 0.0, 0, false, "crossbow"));

        // ---- Mining tools ----
        // Tier-aware so "best pickaxe in inventory" is a one-liner for the
        // caller. Damage is set low because using a pickaxe to fight a
        // zombie is sub-optimal and combat code should prefer swords/axes.
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) {
                put(m, new ItemInfo(Category.TOOL_MINING, tierFromName(n), 1.0, 0, false, n.toLowerCase()));
            }
        }
        // Shears — useful for sheep / leaves / cobwebs.
        put(Material.SHEARS, new ItemInfo(Category.TOOL_MINING, 2, 0.0, 0, false, "shears"));

        // ---- Armor ----
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                    || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) {
                put(m, new ItemInfo(Category.ARMOR, tierFromName(n), 0.0, 0, false, n.toLowerCase()));
            }
        }
        put(Material.ELYTRA,                 new ItemInfo(Category.ARMOR, 4, 0.0, 0, false, "elytra"));
        put(Material.TURTLE_HELMET,          new ItemInfo(Category.ARMOR, 2, 0.0, 0, false, "turtle helmet"));

        // ---- Shield ----
        put(Material.SHIELD, new ItemInfo(Category.SHIELD, 1, 0.0, 0, false, "shield"));

        // ---- Food (basic — covers the common cases; not exhaustive) ----
        // Hunger value is the "food points" restored. Saturation isn't tracked;
        // we just need a rough "is this any good?" sort.
        put(Material.BREAD,                new ItemInfo(Category.FOOD, 0, 0.0, 5, false, "bread"));
        put(Material.APPLE,                new ItemInfo(Category.FOOD, 0, 0.0, 4, false, "apple"));
        put(Material.GOLDEN_APPLE,         new ItemInfo(Category.FOOD, 0, 0.0, 4, true,  "golden apple"));
        put(Material.ENCHANTED_GOLDEN_APPLE, new ItemInfo(Category.FOOD, 0, 0.0, 4, true, "enchanted golden apple"));
        put(Material.GOLDEN_CARROT,        new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "golden carrot"));
        put(Material.CARROT,               new ItemInfo(Category.FOOD, 0, 0.0, 3, false, "carrot"));
        put(Material.POTATO,               new ItemInfo(Category.FOOD, 0, 0.0, 1, false, "potato"));
        put(Material.BAKED_POTATO,         new ItemInfo(Category.FOOD, 0, 0.0, 5, false, "baked potato"));
        put(Material.BEETROOT,             new ItemInfo(Category.FOOD, 0, 0.0, 1, false, "beetroot"));
        put(Material.BEETROOT_SOUP,        new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "beetroot soup"));
        put(Material.MUSHROOM_STEW,        new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "mushroom stew"));
        put(Material.RABBIT_STEW,          new ItemInfo(Category.FOOD, 0, 0.0, 10, false, "rabbit stew"));
        put(Material.SUSPICIOUS_STEW,      new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "suspicious stew"));
        put(Material.COOKED_BEEF,          new ItemInfo(Category.FOOD, 0, 0.0, 8, false, "steak"));
        put(Material.BEEF,                 new ItemInfo(Category.FOOD, 0, 0.0, 3, false, "raw beef"));
        put(Material.COOKED_PORKCHOP,      new ItemInfo(Category.FOOD, 0, 0.0, 8, false, "cooked pork"));
        put(Material.PORKCHOP,             new ItemInfo(Category.FOOD, 0, 0.0, 3, false, "raw pork"));
        put(Material.COOKED_CHICKEN,       new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "cooked chicken"));
        put(Material.CHICKEN,              new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "raw chicken"));
        put(Material.COOKED_MUTTON,        new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "cooked mutton"));
        put(Material.MUTTON,               new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "raw mutton"));
        put(Material.COOKED_RABBIT,        new ItemInfo(Category.FOOD, 0, 0.0, 5, false, "cooked rabbit"));
        put(Material.RABBIT,               new ItemInfo(Category.FOOD, 0, 0.0, 3, false, "raw rabbit"));
        put(Material.COOKED_SALMON,        new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "cooked salmon"));
        put(Material.SALMON,               new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "raw salmon"));
        put(Material.COOKED_COD,           new ItemInfo(Category.FOOD, 0, 0.0, 5, false, "cooked cod"));
        put(Material.COD,                  new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "raw cod"));
        put(Material.MELON_SLICE,          new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "melon slice"));
        put(Material.SWEET_BERRIES,        new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "sweet berries"));
        put(Material.GLOW_BERRIES,         new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "glow berries"));
        put(Material.HONEY_BOTTLE,         new ItemInfo(Category.FOOD, 0, 0.0, 6, false, "honey bottle"));
        put(Material.PUMPKIN_PIE,          new ItemInfo(Category.FOOD, 0, 0.0, 8, false, "pumpkin pie"));
        put(Material.CAKE,                 new ItemInfo(Category.FOOD, 0, 0.0, 14, false, "cake"));
        put(Material.COOKIE,               new ItemInfo(Category.FOOD, 0, 0.0, 2, false, "cookie"));
        put(Material.DRIED_KELP,           new ItemInfo(Category.FOOD, 0, 0.0, 1, false, "dried kelp"));
        put(Material.CHORUS_FRUIT,         new ItemInfo(Category.FOOD, 0, 0.0, 4, false, "chorus fruit"));

        // ---- Save-life items ----
        put(Material.TOTEM_OF_UNDYING, new ItemInfo(Category.SAVE_LIFE, 5, 0.0, 0, true, "totem of undying"));

        // ---- Potions (we don't track type, but we know they exist) ----
        put(Material.POTION,           new ItemInfo(Category.POTION, 0, 0.0, 0, false, "potion"));
        put(Material.SPLASH_POTION,    new ItemInfo(Category.POTION, 0, 0.0, 0, false, "splash potion"));
        put(Material.LINGERING_POTION, new ItemInfo(Category.POTION, 0, 0.0, 0, false, "lingering potion"));

        // ---- Fire tools ----
        put(Material.FLINT_AND_STEEL, new ItemInfo(Category.FIRE_TOOL, 1, 0.0, 0, false, "flint and steel"));
        put(Material.FIRE_CHARGE,     new ItemInfo(Category.FIRE_TOOL, 1, 0.0, 0, false, "fire charge"));

        // ---- Utility ----
        put(Material.ENDER_PEARL,     new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "ender pearl"));
        put(Material.ENDER_EYE,       new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "eye of ender"));
        put(Material.SNOWBALL,        new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "snowball"));
        put(Material.EGG,             new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "egg"));
        put(Material.FISHING_ROD,     new ItemInfo(Category.UTILITY, 1, 0.0, 0, false, "fishing rod"));
        put(Material.WATER_BUCKET,    new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "water bucket"));
        put(Material.LAVA_BUCKET,     new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "lava bucket"));
        put(Material.MILK_BUCKET,     new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "milk bucket"));
        put(Material.BUCKET,          new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "empty bucket"));
        put(Material.COMPASS,         new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "compass"));
        put(Material.RECOVERY_COMPASS,new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "recovery compass"));
        put(Material.CLOCK,           new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "clock"));
        put(Material.SPYGLASS,        new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "spyglass"));
        put(Material.MAP,             new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "empty map"));
        put(Material.FILLED_MAP,      new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "map"));
        put(Material.LEAD,            new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "lead"));
        put(Material.NAME_TAG,        new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "name tag"));
        put(Material.WIND_CHARGE,     new ItemInfo(Category.UTILITY, 0, 0.0, 0, false, "wind charge"));

        // ---- Light blocks ----
        put(Material.TORCH,           new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "torch"));
        put(Material.SOUL_TORCH,      new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "soul torch"));
        put(Material.REDSTONE_TORCH,  new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "redstone torch"));
        put(Material.LANTERN,         new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "lantern"));
        put(Material.SOUL_LANTERN,    new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "soul lantern"));
        put(Material.GLOWSTONE,       new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "glowstone"));
        put(Material.SHROOMLIGHT,     new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "shroomlight"));
        put(Material.SEA_LANTERN,     new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "sea lantern"));
        put(Material.JACK_O_LANTERN,  new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "jack o' lantern"));
        put(Material.CAMPFIRE,        new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "campfire"));
        put(Material.SOUL_CAMPFIRE,   new ItemInfo(Category.LIGHT_BLOCK, 0, 0.0, 0, false, "soul campfire"));

        // ---- Containers ----
        put(Material.CHEST,         new ItemInfo(Category.CONTAINER, 0, 0.0, 0, false, "chest"));
        put(Material.TRAPPED_CHEST, new ItemInfo(Category.CONTAINER, 0, 0.0, 0, false, "trapped chest"));
        put(Material.ENDER_CHEST,   new ItemInfo(Category.CONTAINER, 0, 0.0, 0, false, "ender chest"));
        put(Material.BARREL,        new ItemInfo(Category.CONTAINER, 0, 0.0, 0, false, "barrel"));
        for (Material m : Material.values()) {
            if (m.name().endsWith("SHULKER_BOX")) {
                put(m, new ItemInfo(Category.CONTAINER, 0, 0.0, 0, false, "shulker box"));
            }
        }

        // ---- Workstations ----
        put(Material.CRAFTING_TABLE,    new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "crafting table"));
        put(Material.FURNACE,           new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "furnace"));
        put(Material.BLAST_FURNACE,     new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "blast furnace"));
        put(Material.SMOKER,            new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "smoker"));
        put(Material.ANVIL,             new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "anvil"));
        put(Material.CHIPPED_ANVIL,     new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "chipped anvil"));
        put(Material.DAMAGED_ANVIL,     new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "damaged anvil"));
        put(Material.ENCHANTING_TABLE,  new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "enchanting table"));
        put(Material.BREWING_STAND,     new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "brewing stand"));
        put(Material.GRINDSTONE,        new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "grindstone"));
        put(Material.STONECUTTER,       new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "stonecutter"));
        put(Material.LOOM,              new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "loom"));
        put(Material.SMITHING_TABLE,    new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "smithing table"));
        put(Material.CARTOGRAPHY_TABLE, new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "cartography table"));
        put(Material.FLETCHING_TABLE,   new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "fletching table"));
        put(Material.BEACON,            new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "beacon"));
        put(Material.RESPAWN_ANCHOR,    new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "respawn anchor"));
        put(Material.LODESTONE,         new ItemInfo(Category.WORKSTATION, 0, 0.0, 0, false, "lodestone"));

        // Materials and building blocks aren't enumerated here — anything
        // not in the table falls through to OTHER, and the inventory
        // helpers below treat that as "non-priority". Adding more entries
        // refines the chat-context output but isn't required for combat.
    }

    /**
     * Tier inferred from a tool/armor name prefix. Order matters because
     * "DIAMOND" appears before "GOLDEN" alphabetically but tier-wise we
     * want diamond (4) above gold (3). We check from highest to lowest.
     */
    private static int tierFromName(String n) {
        if (n.startsWith("NETHERITE_")) return 5;
        if (n.startsWith("DIAMOND_"))   return 4;
        if (n.startsWith("GOLDEN_"))    return 3;
        if (n.startsWith("IRON_"))      return 2;
        if (n.startsWith("STONE_"))     return 1;
        if (n.startsWith("WOODEN_"))    return 0;
        if (n.startsWith("CHAINMAIL_")) return 1; // armor-only, no tools
        if (n.startsWith("LEATHER_"))   return 0;
        return 0;
    }

    private static void put(Material m, ItemInfo info) { TABLE.put(m, info); }

    /** Look up info for a material; never null (returns OTHER for unknowns). */
    public static ItemInfo of(Material m) {
        if (m == null) return OTHER;
        return TABLE.getOrDefault(m, OTHER);
    }

    // =================== Inventory helpers ===================

    /**
     * Find the "best" item in {@code inv} matching {@code category}
     * according to {@link ItemInfo#tier} (higher wins) and ties broken
     * by {@link ItemInfo#meleeDamage} (higher wins). Returns the
     * matching {@link ItemStack} or {@code null} if no item of that
     * category exists.
     */
    public static ItemStack bestOf(Inventory inv, Category category) {
        if (inv == null) return null;
        ItemStack best = null;
        ItemInfo bestInfo = null;
        for (ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            ItemInfo info = of(stack.getType());
            if (info.category != category) continue;
            if (best == null
                    || info.tier > bestInfo.tier
                    || (info.tier == bestInfo.tier && info.meleeDamage > bestInfo.meleeDamage)) {
                best = stack;
                bestInfo = info;
            }
        }
        return best;
    }

    /**
     * Pick a "best fight" item from {@code inv}. Prefers a real
     * melee weapon; falls back to any tool with non-zero damage; then
     * to {@code null} if she's truly bare-handed.
     */
    public static ItemStack bestMeleeWeapon(Inventory inv) {
        ItemStack melee = bestOf(inv, Category.WEAPON_MELEE);
        if (melee != null) return melee;
        // Mining tools have damage 1.0 — better than fists if that's all we have.
        return bestOf(inv, Category.TOOL_MINING);
    }

    /** Best ranged weapon (bow first then crossbow) — picked by tier. */
    public static ItemStack bestRangedWeapon(Inventory inv) {
        return bestOf(inv, Category.WEAPON_RANGED);
    }

    /** Best food — preference: highest hunger restored, save-life ones bumped. */
    public static ItemStack bestFood(Inventory inv) {
        if (inv == null) return null;
        ItemStack best = null;
        ItemInfo bestInfo = null;
        for (ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            ItemInfo info = of(stack.getType());
            if (info.category != Category.FOOD && info.category != Category.SAVE_LIFE) continue;
            int score = info.hungerRestored + (info.emergencySave ? 8 : 0);
            int bestScore = bestInfo == null ? -1
                    : bestInfo.hungerRestored + (bestInfo.emergencySave ? 8 : 0);
            if (best == null || score > bestScore) {
                best = stack;
                bestInfo = info;
            }
        }
        return best;
    }

    /** Does the inventory contain at least one item of the given category? */
    public static boolean has(Inventory inv, Category category) {
        if (inv == null) return false;
        for (ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (of(stack.getType()).category == category) return true;
        }
        return false;
    }

    /** Total count of items in {@code inv} matching any of the given categories. */
    public static int countOf(Inventory inv, Set<Category> cats) {
        if (inv == null) return 0;
        int total = 0;
        for (ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (cats.contains(of(stack.getType()).category)) total += stack.getAmount();
        }
        return total;
    }

    /** Convenience set for "stuff worth mentioning in chat context." */
    public static final Set<Category> NOTABLE_CATEGORIES = EnumSet.of(
            Category.WEAPON_MELEE, Category.WEAPON_RANGED,
            Category.ARMOR, Category.SHIELD,
            Category.FOOD, Category.SAVE_LIFE,
            Category.POTION, Category.UTILITY);

    /**
     * Compute the rough material tier of a piece of equipment by looking
     * at the table directly. Centralized so combat / ambient tips can
     * rank "what's she actually carrying" without rebuilding the logic.
     */
    public static int materialTier(Material m) {
        return of(m).tier;
    }
}
