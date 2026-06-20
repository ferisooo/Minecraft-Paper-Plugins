package com.ferisooo.kawaiilogger;

import java.util.HashMap;
import java.util.Map;

/**
 * Inline emojis for materials, entity types, and damage causes.
 * Lookup is by uppercase Bukkit name (e.g., "DIAMOND_ORE", "ZOMBIE", "FALL").
 * Falls back to category-specific defaults via suffix matching, then a generic
 * sparkle if nothing matches.
 */
public final class Emojis {

    private Emojis() {}

    private static final Map<String, String> M = new HashMap<>();
    private static final Map<String, String> SUFFIX = new HashMap<>();
    static {
        // ─────── ORES ───────
        put("\uD83D\uDC8E", "DIAMOND", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "DIAMOND_BLOCK");
        put("\uD83D\uDC9A", "EMERALD", "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "EMERALD_BLOCK");
        put("\u26AB",       "COAL", "COAL_ORE", "DEEPSLATE_COAL_ORE", "COAL_BLOCK", "CHARCOAL");
        put("\uD83E\uDE99", "GOLD_ORE", "DEEPSLATE_GOLD_ORE", "NETHER_GOLD_ORE", "RAW_GOLD",
                            "GOLD_INGOT", "GOLD_NUGGET", "GOLD_BLOCK", "RAW_GOLD_BLOCK");
        put("\uD83D\uDD29", "IRON_ORE", "DEEPSLATE_IRON_ORE", "RAW_IRON", "IRON_INGOT", "IRON_NUGGET",
                            "IRON_BLOCK", "RAW_IRON_BLOCK");
        put("\uD83D\uDFE0", "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "RAW_COPPER", "COPPER_INGOT",
                            "COPPER_BLOCK", "RAW_COPPER_BLOCK");
        put("\uD83D\uDD34", "REDSTONE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "REDSTONE_BLOCK");
        put("\uD83D\uDD35", "LAPIS_LAZULI", "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE", "LAPIS_BLOCK");
        put("\uD83C\uDF11", "ANCIENT_DEBRIS", "NETHERITE_INGOT", "NETHERITE_SCRAP", "NETHERITE_BLOCK");
        put("\uD83D\uDC9C", "AMETHYST_CLUSTER", "AMETHYST_SHARD", "AMETHYST_BLOCK", "BUDDING_AMETHYST",
                            "ECHO_SHARD");

        // ─────── LOGS / WOOD ───────
        put("\uD83C\uDF32", "SPRUCE_LOG", "SPRUCE_WOOD", "STRIPPED_SPRUCE_LOG", "STRIPPED_SPRUCE_WOOD");
        put("\uD83C\uDF34", "JUNGLE_LOG", "JUNGLE_WOOD", "STRIPPED_JUNGLE_LOG", "STRIPPED_JUNGLE_WOOD");
        put("\uD83C\uDF38", "CHERRY_LOG", "CHERRY_WOOD", "STRIPPED_CHERRY_LOG", "STRIPPED_CHERRY_WOOD");
        put("\uD83C\uDF33", "OAK_LOG", "OAK_WOOD", "STRIPPED_OAK_LOG", "STRIPPED_OAK_WOOD",
                            "BIRCH_LOG", "BIRCH_WOOD", "STRIPPED_BIRCH_LOG", "STRIPPED_BIRCH_WOOD",
                            "DARK_OAK_LOG", "DARK_OAK_WOOD", "STRIPPED_DARK_OAK_LOG", "STRIPPED_DARK_OAK_WOOD",
                            "ACACIA_LOG", "ACACIA_WOOD", "STRIPPED_ACACIA_LOG", "STRIPPED_ACACIA_WOOD",
                            "MANGROVE_LOG", "MANGROVE_WOOD", "STRIPPED_MANGROVE_LOG", "STRIPPED_MANGROVE_WOOD",
                            "PALE_OAK_LOG", "PALE_OAK_WOOD", "STRIPPED_PALE_OAK_LOG", "STRIPPED_PALE_OAK_WOOD");
        put("\uD83D\uDFE5", "CRIMSON_STEM", "CRIMSON_HYPHAE", "STRIPPED_CRIMSON_STEM", "STRIPPED_CRIMSON_HYPHAE");
        put("\uD83D\uDFE6", "WARPED_STEM", "WARPED_HYPHAE", "STRIPPED_WARPED_STEM", "STRIPPED_WARPED_HYPHAE");
        put("\uD83C\uDF8B", "BAMBOO", "BAMBOO_BLOCK", "STRIPPED_BAMBOO_BLOCK");

        // ─────── HOSTILE MOBS ───────
        put("\uD83E\uDDDF", "ZOMBIE", "HUSK", "ZOMBIE_VILLAGER", "DROWNED");
        put("\uD83D\uDC80", "SKELETON", "STRAY", "WITHER_SKELETON", "BOGGED",
                            "SKELETON_HORSE", "WITHER_SKELETON_SKULL");
        put("\uD83D\uDCA5", "CREEPER");
        put("\uD83D\uDD77", "SPIDER", "CAVE_SPIDER");
        put("\uD83D\uDC41", "ENDERMAN");
        put("\uD83E\uDDDD", "ENDERMITE");
        put("\uD83E\uDDD9", "WITCH", "EVOKER", "ILLUSIONER");
        put("\uD83D\uDD25", "BLAZE", "MAGMA_CUBE");
        put("\uD83D\uDC7B", "GHAST", "VEX", "PHANTOM");
        put("\uD83D\uDFE2", "SLIME");
        put("\uD83D\uDC17", "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN", "HOGLIN", "ZOGLIN");
        put("\uD83C\uDFF9", "PILLAGER", "VINDICATOR");
        put("\uD83D\uDC02", "RAVAGER");
        put("\uD83E\uDD87", "WARDEN");
        put("\uD83D\uDCA8", "BREEZE", "WIND_CHARGE", "BREEZE_ROD");
        put("\uD83D\uDC1F", "GUARDIAN", "ELDER_GUARDIAN", "SILVERFISH");
        put("\uD83D\uDCE6", "SHULKER", "SHULKER_BOX");

        // ─────── FRIENDLY / NEUTRAL MOBS ───────
        put("\uD83E\uDDD1", "VILLAGER");
        put("\uD83C\uDF92", "WANDERING_TRADER");
        put("\uD83D\uDC3A", "WOLF");
        put("\uD83D\uDC08", "CAT");
        put("\uD83E\uDD8A", "FOX");
        put("\uD83E\uDD9C", "PARROT");
        put("\uD83E\uDEBC", "AXOLOTL");
        put("\uD83D\uDC99", "ALLAY");
        put("\uD83D\uDC38", "FROG", "TADPOLE");
        put("\uD83D\uDC18", "SNIFFER");
        put("\uD83D\uDC34", "HORSE");
        put("\uD83E\uDECF", "DONKEY", "MULE");
        put("\uD83E\uDD99", "LLAMA", "TRADER_LLAMA");
        put("\uD83D\uDC3C", "PANDA");
        put("\uD83D\uDC3B\u200D\u2744\uFE0F", "POLAR_BEAR");
        put("\uD83D\uDC30", "RABBIT");
        put("\uD83D\uDC05", "OCELOT");
        put("\uD83D\uDC14", "CHICKEN", "EGG");
        put("\uD83D\uDC16", "PIG");
        put("\uD83D\uDC04", "COW");
        put("\uD83C\uDF44", "MOOSHROOM", "RED_MUSHROOM", "BROWN_MUSHROOM");
        put("\uD83D\uDC11", "SHEEP");
        put("\uD83D\uDC10", "GOAT");
        put("\uD83D\uDC1D", "BEE", "BEEHIVE", "BEE_NEST", "HONEYCOMB");
        put("\uD83D\uDC2C", "DOLPHIN");
        put("\uD83D\uDC22", "TURTLE", "TURTLE_EGG");
        put("\uD83E\uDD91", "SQUID", "GLOW_SQUID", "INK_SAC", "GLOW_INK_SAC");
        put("\uD83E\uDD9C",  "STRIDER");
        put("\uD83D\uDC2A", "CAMEL");
        put("\uD83D\uDC1F", "COD", "SALMON");
        put("\uD83D\uDC20", "TROPICAL_FISH");
        put("\uD83D\uDC21", "PUFFERFISH");
        put("\uD83E\uDD8C", "ARMADILLO");
        put("\uD83D\uDC0D", "SNOW_GOLEM");
        put("\uD83E\uDDF1", "IRON_GOLEM");

        // ─────── BOSSES ───────
        put("\uD83D\uDC09", "ENDER_DRAGON", "DRAGON_EGG", "DRAGON_HEAD", "DRAGON_BREATH");
        put("\u2620\uFE0F", "WITHER");

        // ─────── FOODS ───────
        put("\uD83C\uDF4E", "APPLE", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE");
        put("\uD83E\uDD55", "CARROT", "GOLDEN_CARROT");
        put("\uD83E\uDD54", "POTATO", "BAKED_POTATO", "POISONOUS_POTATO");
        put("\uD83C\uDF6F", "HONEY_BOTTLE", "HONEY_BLOCK", "HONEYCOMB_BLOCK");
        put("\uD83E\uDD5B", "MILK_BUCKET");
        put("\uD83C\uDF5E", "BREAD", "WHEAT");
        put("\uD83C\uDF6A", "COOKIE");
        put("\uD83C\uDF70", "CAKE");
        put("\uD83E\uDD69", "BEEF", "COOKED_BEEF", "PORKCHOP", "COOKED_PORKCHOP",
                            "MUTTON", "COOKED_MUTTON", "RABBIT", "COOKED_RABBIT");
        put("\uD83C\uDF57", "CHICKEN_FOOD", "COOKED_CHICKEN");
        put("\uD83E\uDD5A", "ROTTEN_FLESH", "SPIDER_EYE", "FERMENTED_SPIDER_EYE");
        put("\uD83C\uDF53", "SWEET_BERRIES", "GLOW_BERRIES");
        put("\uD83C\uDF49", "MELON", "MELON_SLICE", "MELON_SEEDS", "GLISTERING_MELON_SLICE");
        put("\uD83C\uDF8B", "BAMBOO_FOOD"); // dummy
        put("\uD83E\uDDC4", "BEETROOT", "BEETROOT_SOUP", "BEETROOT_SEEDS");
        put("\uD83C\uDF6B", "CHORUS_FRUIT", "POPPED_CHORUS_FRUIT");
        put("\uD83C\uDF63", "TROPICAL_FISH_FOOD"); // unused
        put("\uD83E\uDD58", "MUSHROOM_STEW", "RABBIT_STEW", "SUSPICIOUS_STEW");
        put("\uD83E\uDD63", "PUMPKIN_PIE");
        put("\uD83C\uDF66", "GLOW_BERRIES_TREAT"); // unused

        // ─────── POTIONS / BREWING ───────
        put("\uD83E\uDDEA", "POTION", "SPLASH_POTION", "LINGERING_POTION", "BREWING_STAND",
                            "GLASS_BOTTLE", "EXPERIENCE_BOTTLE");

        // ─────── TOOLS ───────
        putSuffix("\u26CF\uFE0F", "_PICKAXE");
        putSuffix("\uD83E\uDE93", "_AXE");
        putSuffix("\uD83C\uDF7D\uFE0F", "_SHOVEL"); // close enough~
        putSuffix("\uD83C\uDF3E", "_HOE");
        put("\u2702\uFE0F", "SHEARS");
        put("\uD83E\uDE9D", "FISHING_ROD");
        put("\uD83E\uDDF2", "FLINT_AND_STEEL", "FLINT");
        put("\uD83E\uDDED", "COMPASS", "RECOVERY_COMPASS", "LODESTONE");
        put("\u23F1\uFE0F", "CLOCK");
        put("\uD83D\uDD2D", "SPYGLASS");
        put("\uD83E\uDEA3", "BUCKET", "WATER_BUCKET", "LAVA_BUCKET", "POWDER_SNOW_BUCKET",
                            "PUFFERFISH_BUCKET", "TROPICAL_FISH_BUCKET", "AXOLOTL_BUCKET",
                            "COD_BUCKET", "SALMON_BUCKET", "TADPOLE_BUCKET");

        // ─────── WEAPONS ───────
        putSuffix("\u2694\uFE0F", "_SWORD");
        put("\uD83C\uDFF9", "BOW", "ARROW", "TIPPED_ARROW", "SPECTRAL_ARROW", "CROSSBOW");
        put("\uD83D\uDD31", "TRIDENT");
        put("\uD83E\uDDE8", "SNOWBALL", "SNOW_BLOCK", "SNOW", "POWDER_SNOW");
        put("\uD83C\uDF7E", "FIREWORK_ROCKET", "FIREWORK_STAR");

        // ─────── ARMOR ───────
        putSuffix("\uD83E\uDE96", "_HELMET");
        putSuffix("\uD83E\uDDA5", "_CHESTPLATE");
        putSuffix("\uD83D\uDC56", "_LEGGINGS");
        putSuffix("\uD83E\uDD7E", "_BOOTS");
        put("\uD83D\uDEE1\uFE0F", "SHIELD", "TURTLE_HELMET");
        put("\uD83E\uDDB4", "BONE", "BONE_MEAL", "BONE_BLOCK");
        put("\uD83D\uDD78\uFE0F", "STRING", "COBWEB");
        put("\uD83E\uDE7A", "GUNPOWDER", "TNT");

        // ─────── BUILDING ───────
        putSuffix("\uD83D\uDFEB", "_PLANKS");
        putSuffix("\uD83C\uDF31", "_SAPLING");
        put("\uD83E\uDEA8", "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE",
                            "TUFF", "CALCITE", "GRANITE", "DIORITE", "ANDESITE",
                            "BLACKSTONE", "BASALT", "SMOOTH_BASALT", "POLISHED_BLACKSTONE",
                            "POLISHED_DEEPSLATE", "POLISHED_GRANITE", "POLISHED_DIORITE",
                            "POLISHED_ANDESITE", "GRAVEL");
        put("\uD83C\uDFD6\uFE0F", "SAND", "RED_SAND", "SOUL_SAND", "SOUL_SOIL",
                                   "SANDSTONE", "RED_SANDSTONE");
        put("\uD83E\uDDF1", "BRICKS", "NETHER_BRICKS", "RED_NETHER_BRICKS", "NETHER_BRICK",
                            "MUD_BRICKS", "PACKED_MUD", "MUD", "STONE_BRICKS");
        put("\uD83C\uDF31", "DIRT", "GRASS_BLOCK", "ROOTED_DIRT", "COARSE_DIRT", "PODZOL",
                            "MYCELIUM", "FARMLAND");
        put("\uD83C\uDF7D", "GLASS", "TINTED_GLASS"); // serving plate ish

        // ─────── REDSTONE ───────
        put("\uD83D\uDD27", "REDSTONE_DUST", "REPEATER", "COMPARATOR", "OBSERVER",
                            "DISPENSER", "DROPPER", "HOPPER", "PISTON", "STICKY_PISTON",
                            "REDSTONE_TORCH", "REDSTONE_LAMP", "DAYLIGHT_DETECTOR",
                            "TARGET", "TRIPWIRE_HOOK", "LEVER", "TRAPPED_CHEST");

        // ─────── UTILITY BLOCKS ───────
        put("\uD83E\uDE9A", "CRAFTING_TABLE", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE",
                            "SMITHING_TABLE", "STONECUTTER", "LOOM");
        put("\uD83D\uDD25", "FURNACE", "BLAST_FURNACE", "SMOKER", "CAMPFIRE", "SOUL_CAMPFIRE",
                            "TORCH", "SOUL_TORCH", "LANTERN", "SOUL_LANTERN", "LAVA",
                            "FIRE", "SOUL_FIRE", "MAGMA_BLOCK");
        put("\uD83D\uDCDA", "BOOK", "BOOKSHELF", "CHISELED_BOOKSHELF", "WRITABLE_BOOK",
                            "WRITTEN_BOOK", "ENCHANTED_BOOK", "KNOWLEDGE_BOOK");
        put("\u2728",       "ENCHANTING_TABLE", "BEACON", "END_CRYSTAL", "NETHER_STAR",
                            "TOTEM_OF_UNDYING", "EXPERIENCE_BOTTLE_POP");
        put("\uD83D\uDCA0", "GLOWSTONE", "GLOWSTONE_DUST", "SHROOMLIGHT", "SEA_LANTERN");
        put("\uD83D\uDD32", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "GRINDSTONE");
        put("\uD83D\uDCE6", "CHEST", "BARREL", "ENDER_CHEST", "BUNDLE");
        put("\uD83D\uDECF", "WHITE_BED", "RED_BED", "BLACK_BED", "BLUE_BED", "GREEN_BED",
                            "YELLOW_BED", "ORANGE_BED", "PINK_BED", "PURPLE_BED",
                            "MAGENTA_BED", "CYAN_BED", "LIGHT_BLUE_BED", "LIME_BED",
                            "LIGHT_GRAY_BED", "GRAY_BED", "BROWN_BED");

        // ─────── PLANTS / NATURE ───────
        put("\uD83C\uDF3F", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "VINE",
                            "GLOW_LICHEN", "MOSS_BLOCK", "MOSS_CARPET", "AZALEA",
                            "FLOWERING_AZALEA", "LILY_PAD", "SEAGRASS", "TALL_SEAGRASS",
                            "KELP", "DRIED_KELP", "DEAD_BUSH", "SPORE_BLOSSOM",
                            "CACTUS", "SUGAR_CANE");
        put("\uD83C\uDF38", "PINK_TULIP", "PINK_PETALS", "PEONY", "AZURE_BLUET", "ALLIUM",
                            "OXEYE_DAISY", "POPPY", "CORNFLOWER", "LILY_OF_THE_VALLEY",
                            "DANDELION", "BLUE_ORCHID", "RED_TULIP", "WHITE_TULIP",
                            "ORANGE_TULIP", "WITHER_ROSE", "TORCHFLOWER", "PITCHER_PLANT",
                            "PINK_DAISY", "WILDFLOWERS");
        put("\uD83C\uDF42", "AZALEA_LEAVES", "OAK_LEAVES", "BIRCH_LEAVES", "SPRUCE_LEAVES",
                            "DARK_OAK_LEAVES", "JUNGLE_LEAVES", "ACACIA_LEAVES",
                            "MANGROVE_LEAVES", "CHERRY_LEAVES", "PALE_OAK_LEAVES",
                            "FLOWERING_AZALEA_LEAVES");
        put("\uD83C\uDF80", "PUMPKIN", "CARVED_PUMPKIN", "JACK_O_LANTERN", "PUMPKIN_SEEDS",
                            "PUMPKIN_STEM");
        put("\uD83E\uDED0", "COCOA_BEANS", "COCOA_PODS");

        // ─────── MISC ───────
        put("\uD83E\uDE9D", "FISHING_ROD"); // already; safe overwrite
        put("\uD83D\uDD52", "CLOCK");
        put("\uD83D\uDC1A", "NAUTILUS_SHELL", "CONDUIT", "TURTLE_SCUTE", "ARMADILLO_SCUTE");
        put("\uD83D\uDC99", "HEART_OF_THE_SEA", "PRISMARINE", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
                            "DARK_PRISMARINE", "PRISMARINE_BRICKS");
        put("\uD83C\uDFF7\uFE0F", "NAME_TAG");
        put("\uD83E\uDE91", "SADDLE", "LEATHER_HORSE_ARMOR", "IRON_HORSE_ARMOR",
                            "GOLDEN_HORSE_ARMOR", "DIAMOND_HORSE_ARMOR");
        put("\uD83E\uDEB6", "FEATHER", "ELYTRA");
        put("\u26F5",       "OAK_BOAT", "BIRCH_BOAT", "SPRUCE_BOAT", "JUNGLE_BOAT",
                            "ACACIA_BOAT", "DARK_OAK_BOAT", "MANGROVE_BOAT", "CHERRY_BOAT",
                            "BAMBOO_RAFT", "PALE_OAK_BOAT");
        put("\uD83D\uDED2", "MINECART", "CHEST_MINECART", "FURNACE_MINECART",
                            "TNT_MINECART", "HOPPER_MINECART", "COMMAND_BLOCK_MINECART");
        put("\uD83E\uDDF5", "CARPET", "WHITE_CARPET", "RED_CARPET", "BLUE_CARPET",
                            "GREEN_CARPET", "YELLOW_CARPET", "BLACK_CARPET");
        put("\uD83C\uDFA8", "PAINTING", "ITEM_FRAME", "GLOW_ITEM_FRAME");
        put("\uD83D\uDDA4", "ARMOR_STAND");
        put("\uD83C\uDFB5", "MUSIC_DISC_13", "MUSIC_DISC_CAT", "MUSIC_DISC_BLOCKS",
                            "MUSIC_DISC_CHIRP", "MUSIC_DISC_FAR", "MUSIC_DISC_MALL",
                            "MUSIC_DISC_MELLOHI", "MUSIC_DISC_STAL", "MUSIC_DISC_STRAD",
                            "MUSIC_DISC_WARD", "MUSIC_DISC_11", "MUSIC_DISC_WAIT",
                            "MUSIC_DISC_PIGSTEP", "MUSIC_DISC_OTHERSIDE",
                            "MUSIC_DISC_RELIC", "MUSIC_DISC_CREATOR",
                            "MUSIC_DISC_CREATOR_MUSIC_BOX", "MUSIC_DISC_PRECIPICE",
                            "MUSIC_DISC_5", "MUSIC_DISC_TEARS",
                            "JUKEBOX", "NOTE_BLOCK");
        put("\uD83E\uDDF6", "WOOL", "WHITE_WOOL", "RED_WOOL", "BLUE_WOOL", "GREEN_WOOL",
                            "YELLOW_WOOL", "BLACK_WOOL", "PINK_WOOL", "PURPLE_WOOL",
                            "ORANGE_WOOL", "MAGENTA_WOOL", "CYAN_WOOL", "LIGHT_BLUE_WOOL",
                            "LIME_WOOL", "LIGHT_GRAY_WOOL", "GRAY_WOOL", "BROWN_WOOL");
        put("\uD83E\uDDF6", "STRING_FISHING"); // unused
        put("\uD83D\uDD17", "CHAIN", "TRIPWIRE", "LEAD");
        put("\u26F2",       "WATER", "WATER_BUCKET_FOUNTAIN"); // unused alt
        put("\uD83C\uDF77", "GLASS_BOTTLE_DUMMY"); // no usage
        put("\uD83D\uDD11", "TRIAL_KEY", "OMINOUS_TRIAL_KEY");
        put("\uD83C\uDFAF", "TARGET_DUMMY"); // unused

        // ─────── DAMAGE CAUSES ───────
        put("\uD83C\uDF21\uFE0F", "FALL"); // thermometer-ish; tiny falling impact
        put("\uD83D\uDD25", "LAVA", "FIRE", "FIRE_TICK", "HOT_FLOOR", "CAMPFIRE_DAMAGE");
        put("\uD83C\uDF0A", "DROWNING");
        put("\uD83D\uDC9A", "POISON"); // green heart for poison
        put("\u2620\uFE0F", "WITHER_DMG"); // skull
        put("\uD83C\uDF7D\uFE0F", "STARVATION"); // hungry plate
        put("\uD83C\uDF35", "CONTACT", "CACTUS"); // cactus
        put("\u2B1B",      "VOID");
        put("\u26A1",      "LIGHTNING");
        put("\uD83E\uDDCA", "FREEZE"); // ice cube
        put("\uD83D\uDCA8", "SUFFOCATION", "FLY_INTO_WALL"); // dash
        put("\uD83D\uDCA5", "EXPLOSION", "BLOCK_EXPLOSION", "ENTITY_EXPLOSION"); // collision
        put("\uD83C\uDFAF", "PROJECTILE"); // bullseye
        put("\uD83E\uDDD9", "MAGIC", "DRAGON_BREATH_DMG", "SONIC_BOOM"); // wizard
        put("\uD83D\uDCA2", "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "MOB_ATTACK"); // anger symbol
        put("\u2728",      "GENERIC", "FALLING_BLOCK", "STALAGMITE", "FALLING_STALACTITE", "CRAMMING");

        // ─────── JUNK FISHING ITEMS (catchall) ───────
        put("\uD83C\uDF3F", "LILY_PAD_FISH"); // unused alt
        put("\uD83E\uDD58", "BOWL");
        put("\uD83E\uDDF6", "STRING");
        put("\uD83D\uDD78\uFE0F", "TRIPWIRE_HOOK");
        put("\uD83D\uDCA7", "WATER_BOTTLE");
    }

    private static void put(String emoji, String... keys) {
        for (String k : keys) M.put(k, emoji);
    }

    /** suffix-pattern fallbacks (resolved AFTER specific lookups). */
    private static void putSuffix(String emoji, String suffix) {
        SUFFIX.put(suffix, emoji);
    }

    public static String forItem(String name) {
        if (name == null) return "\u2728";
        String key = name.toUpperCase();
        String hit = M.get(key);
        if (hit != null) return hit;

        // suffix fallback
        for (Map.Entry<String, String> e : SUFFIX.entrySet()) {
            if (key.endsWith(e.getKey())) return e.getValue();
        }

        // generic suffix patterns
        if (key.endsWith("_LOG") || key.endsWith("_WOOD")) return "\uD83C\uDF33";
        if (key.endsWith("_STEM") || key.endsWith("_HYPHAE")) return "\uD83C\uDF33";
        if (key.endsWith("_PLANKS")) return "\uD83D\uDFEB";
        if (key.endsWith("_SAPLING")) return "\uD83C\uDF31";
        if (key.endsWith("_LEAVES")) return "\uD83C\uDF42";
        if (key.endsWith("_ORE")) return "\uD83E\uDEA8";
        if (key.endsWith("_INGOT")) return "\uD83E\uDE99";
        if (key.endsWith("_NUGGET")) return "\u2728";
        if (key.endsWith("_BOAT") || key.endsWith("_RAFT")) return "\u26F5";
        if (key.endsWith("_BED")) return "\uD83D\uDECF";
        if (key.endsWith("_WOOL")) return "\uD83E\uDDF6";
        if (key.endsWith("_CARPET")) return "\uD83E\uDDF5";
        if (key.endsWith("_GLAZED_TERRACOTTA") || key.endsWith("_TERRACOTTA")
                || key.endsWith("_CONCRETE") || key.endsWith("_CONCRETE_POWDER")) return "\uD83E\uDDF1";
        if (key.endsWith("_BANNER")) return "\uD83C\uDFF3\uFE0F";
        if (key.endsWith("_DYE")) return "\uD83C\uDFA8";
        if (key.endsWith("_SHULKER_BOX")) return "\uD83D\uDCE6";
        if (key.endsWith("_SLAB") || key.endsWith("_STAIRS") || key.endsWith("_WALL")
                || key.endsWith("_FENCE") || key.endsWith("_FENCE_GATE")
                || key.endsWith("_DOOR") || key.endsWith("_TRAPDOOR")
                || key.endsWith("_PRESSURE_PLATE") || key.endsWith("_BUTTON")
                || key.endsWith("_SIGN") || key.endsWith("_HANGING_SIGN")) return "\uD83E\uDE9A";

        return "\u2728";
    }
}
