package com.ferisooo.kawaiiquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Talks to DeepSeek's OpenAI-compatible chat-completions endpoint to generate
 * a quest. The network call runs off the main thread; the callback is always
 * delivered back on the main thread so it's safe to touch the Bukkit API.
 *
 * <p>Quests are AI-only: if no API key is set, or the call fails / returns an
 * invalid quest, this delivers {@code null} and the caller shows an error.
 * There is no built-in fallback.
 */
public final class DeepSeekClient {

    private final KawaiiQuests plugin;
    private final HttpClient http;

    public DeepSeekClient(KawaiiQuests plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Generate a quest asynchronously; {@code onResult} runs on the main thread.
     * Quests are AI-only — delivers {@code null} if the key is missing or the
     * request fails / returns an invalid quest, and the caller shows an error.
     */
    public void generate(Difficulty diff, List<String> avoidTargets, List<String> recentTypes,
                         Consumer<Quest> onResult) {
        String key = plugin.getConfig().getString("deepseek.api-key", "");
        boolean noKey = key == null || key.isBlank() || key.equalsIgnoreCase("PUT-YOUR-KEY-HERE");
        if (noKey) {
            plugin.getLogger().warning("(✧) DeepSeek API key is not set — set deepseek.api-key. "
                    + "Quests are AI-only, so none can be generated until then.");
            onResult.accept(null);
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Quest result;
            try {
                result = request(diff, key, avoidTargets, recentTypes); // null if the AI gave an invalid/blocked quest
            } catch (Exception ex) {
                plugin.getLogger().warning("(✧) DeepSeek request failed: " + ex.getMessage());
                result = null;
            }
            final Quest delivered = result;
            // Hop back onto a server thread to deliver the callback. The caller's
            // consumer is responsible for routing any per-entity work to that
            // entity's scheduler (it does — see KawaiiQuests#generate callback).
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> onResult.accept(delivered));
        });
    }

    // ----------------------------------------------------------------- request

    private Quest request(Difficulty diff, String key, List<String> avoidTargets,
                          List<String> recentTypes) throws Exception {
        var cfg = plugin.getConfig();
        String url   = cfg.getString("deepseek.api-url", "https://api.deepseek.com/chat/completions");
        String model = cfg.getString("deepseek.model", "deepseek-chat");
        double temp  = cfg.getDouble("deepseek.temperature", 1.1);
        int timeout  = cfg.getInt("deepseek.timeout-seconds", 20);
        int min = plugin.minAmount(diff);
        int max = plugin.maxAmount(diff);

        StringBuilder userMsg = new StringBuilder("Generate a " + diff.name() + " quest now.");
        if (avoidTargets != null && !avoidTargets.isEmpty()) {
            userMsg.append(" Do NOT reuse any of these recently-used targets: ")
                   .append(String.join(", ", avoidTargets))
                   .append(". Pick a DIFFERENT target and a fresh theme.");
        }
        if (recentTypes != null && !recentTypes.isEmpty()) {
            userMsg.append(" Recent quest types (newest last): ").append(String.join(", ", recentTypes))
                   .append(". Balance variety — do NOT default to KILL; favour MINE or COLLECT when KILL "
                           + "is over-represented above.");
        }
        JsonObject system = message("system", systemPrompt(diff, min, max));
        JsonObject user   = message("user", userMsg.toString());
        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 800); // room to "think" (esp. for the reasoner model)
        // deepseek-reasoner does its own chain-of-thought but rejects temperature
        // and response_format; the chat model supports both. Branch accordingly.
        if (!model.toLowerCase(Locale.ROOT).contains("reason")) {
            body.addProperty("temperature", temp);
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();

        return parseQuest(content, diff, min, max);
    }

    /** Turn the model's JSON content into a validated Quest, or fall back. */
    private Quest parseQuest(String content, Difficulty diff, int min, int max) {
        JsonObject o = JsonParser.parseString(extractJson(content)).getAsJsonObject();

        // Sanitize AI text: strip color/format codes and newlines so it can't
        // inject formatting or impersonate server messages, and cap length.
        String title = sanitize(str(o, "title", "Mystery Quest"), 40, "Mystery Quest");
        String desc  = sanitize(str(o, "description", "Complete the objective for a reward!"),
                120, "Complete the objective for a reward!");
        Quest.Type type = parseType(str(o, "type", "MINE"));
        String rawTarget = str(o, "target", "STONE").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        int amount = o.has("amount") && o.get("amount").isJsonPrimitive()
                ? o.get("amount").getAsInt() : min;
        amount = Math.max(min, Math.min(max, amount));

        String canonical = validateTarget(type, rawTarget);
        if (canonical == null) {
            plugin.getLogger().warning("(✧) AI gave an invalid/blocked target '" + rawTarget
                    + "' for " + type + "; rejecting this quest.");
            return null; // generate() decides whether to error out or fall back
        }

        // The AI also picks the reward. Validate it; if it's junk we leave it
        // null and the loot crate falls back to the config table for that tier.
        String rewardItem = null;
        int rewardAmount = 0;
        if (o.has("reward") && o.get("reward").isJsonObject()) {
            JsonObject r = o.getAsJsonObject("reward");
            String rmat = str(r, "material", "").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            int ramt = r.has("amount") && r.get("amount").isJsonPrimitive() ? r.get("amount").getAsInt() : 1;
            Material rm = Material.matchMaterial(rmat);
            if (rm != null && rm.isItem() && !plugin.isBlockedTarget(rm.name())) {
                rewardItem = rm.name();
                int cap = Math.min(rm.getMaxStackSize(), rewardCap(diff));
                rewardAmount = Math.max(1, Math.min(cap, ramt));
            } else if (!rmat.isEmpty()) {
                plugin.getLogger().warning("(✧) AI gave an invalid reward '" + rmat
                        + "'; falling back to the loot table.");
            }
        }

        return new Quest(title, desc, type, canonical, amount, diff, rewardItem, rewardAmount);
    }

    /** Pull the JSON object out of the reply, tolerating code fences / stray prose. */
    private static String extractJson(String s) {
        if (s == null) return "{}";
        s = s.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    /** Upper bound on how many of a reward item a tier may hand out. */
    private static int rewardCap(Difficulty diff) {
        switch (diff) {
            case EASY:   return 16;
            case MEDIUM: return 12;
            case HARD:   return 10;
            case BRUTAL:
            default:     return 12;
        }
    }

    /** @return the canonical target (or "ANY") if valid + not blocked for this type, else null. */
    private String validateTarget(Quest.Type type, String target) {
        if (target.equalsIgnoreCase("ANY")) return "ANY"; // count-any objectives (fish/enchant/trade)
        String canonical;
        switch (type) {
            case TRADE:
                return "ANY"; // trades are count-based regardless of target
            case KILL:
            case BREED:
            case TAME: {
                try {
                    EntityType t = EntityType.valueOf(target);
                    Class<?> cls = t.getEntityClass();
                    boolean living = cls != null && LivingEntity.class.isAssignableFrom(cls);
                    if (!living || t == EntityType.ARMOR_STAND || t == EntityType.PLAYER) return null;
                    canonical = t.name();
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
                break;
            }
            case MINE: {
                Material m = Material.matchMaterial(target);
                if (m == null || !m.isBlock()) return null;
                canonical = m.name();
                break;
            }
            case COLLECT:
            case FISH:
            case SMELT:
            case CRAFT:
            case ENCHANT:
            default: {
                Material m = Material.matchMaterial(target);
                if (m == null || !m.isItem()) return null;
                canonical = m.name();
                break;
            }
        }
        // Reject impossible / creative-only / boss targets configured by the admin.
        if (plugin.isBlockedTarget(canonical)) return null;
        return canonical;
    }

    /** Strip '&'/'§' codes + newlines, collapse blanks, cap length, fall back if empty. */
    private String sanitize(String s, int max, String fallback) {
        if (s == null) return fallback;
        s = s.replace('§', ' ').replace('&', ' ')
             .replace('\n', ' ').replace('\r', ' ')
             .replaceAll("\\s+", " ").trim();
        if (s.length() > max) s = s.substring(0, max).trim();
        return s.isEmpty() ? fallback : s;
    }

    private Quest.Type parseType(String s) {
        try {
            return Quest.Type.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Quest.Type.MINE;
        }
    }

    // ----------------------------------------------------------------- helpers

    private String systemPrompt(Difficulty diff, int min, int max) {
        return "You design quests for a cute (\"kawaii\") Minecraft 1.21 survival server "
                + "that supports both Java and Bedrock players. "
                + "Return ONLY one minified JSON object, no code fences, no extra text, with EXACTLY these keys: "
                + "\"title\" (short playful name, max 40 chars), "
                + "\"description\" (one friendly sentence, max 120 chars), "
                + "\"type\" (one of \"MINE\", \"KILL\", \"COLLECT\", \"FISH\", \"BREED\", \"TAME\", \"SMELT\", "
                + "\"CRAFT\", \"ENCHANT\", \"TRADE\" — use the FULL variety, do not keep picking the same few; "
                + "never default to KILL), "
                + "\"target\" (UPPER_SNAKE_CASE; its meaning depends on type: "
                + "MINE=a block Material to break; COLLECT=an item Material to pick up; FISH=a fish item Material "
                + "like COD/SALMON or \"ANY\"; SMELT=the smelted result item Material; CRAFT=the crafted result "
                + "item Material; ENCHANT=an item Material to enchant or \"ANY\"; KILL=a living EntityType; "
                + "BREED=a farm animal EntityType (COW, SHEEP, PIG, CHICKEN, RABBIT, etc.); TAME=a tameable "
                + "EntityType (WOLF, CAT, PARROT, HORSE, LLAMA, etc.); TRADE=always \"ANY\"), "
                + "\"amount\" (integer between " + min + " and " + max + "), "
                + "\"reward\" (an object: \"material\" = a valid Bukkit ITEM Material UPPER_SNAKE_CASE the "
                + "player wins, \"amount\" = a sensible integer count). "
                + difficultyRubric(diff)
                + rewardGuidance(diff)
                + "Use only vanilla Minecraft 1.21 materials/entities. Keep it survival-achievable and friendly. "
                + "THINK carefully, step by step, about the most fitting and NON-repetitive quest and a fair "
                + "reward for THIS exact difficulty before deciding. Then output ONLY the final minified JSON "
                + "object — no reasoning text, no markdown, no code fences.";
    }

    /** Tells the model what reward fits each tier. */
    private String rewardGuidance(Difficulty diff) {
        String base = "The reward MUST scale with difficulty and feel worth the effort. ";
        switch (diff) {
            case EASY:
                return base + "EASY reward: basic survival items — bread, cooked food, coal, a few iron "
                        + "ingots, a couple xp bottles (amounts ~4-12). ";
            case MEDIUM:
                return base + "MEDIUM reward: valuable mid items — a few diamonds or emeralds, gold ingots, "
                        + "ender pearls, a golden apple, xp bottles (amounts ~2-8). ";
            case HARD:
                return base + "HARD reward: rare strong items — several diamonds, netherite scrap, golden "
                        + "apples, a diamond block, maybe a totem of undying (rare items use small counts, 1-6). ";
            case BRUTAL:
            default:
                return base + "BRUTAL reward: the BEST loot on the server — netherite ingots, a netherite "
                        + "block, enchanted golden apples, totems of undying, or a beacon (small counts, 1-4). ";
        }
    }

    /** Tells the model what each tier should actually feel like, with example targets. */
    private String difficultyRubric(Difficulty diff) {
        switch (diff) {
            case EASY:
                return "DIFFICULTY = EASY: a relaxed early-game task a brand-new player can finish "
                        + "near spawn with no danger. Use COMMON, SAFE targets only. "
                        + "Good examples: MINE OAK_LOG/BIRCH_LOG/STONE/COAL_ORE/SAND/DIRT; "
                        + "COLLECT WHEAT/APPLE/EGG; KILL CHICKEN/PIG/COW/SHEEP/RABBIT (passive animals only). "
                        + "Never use ores rarer than coal/copper, the Nether/End, or hostile mobs. ";
            case MEDIUM:
                return "DIFFICULTY = MEDIUM: a mid-game task needing some combat or proper mining, "
                        + "but nothing end-game. Good examples: MINE IRON_ORE/GOLD_ORE/REDSTONE_ORE/LAPIS_ORE; "
                        + "KILL ZOMBIE/SKELETON/SPIDER/CREEPER/DROWNED/WITCH/PILLAGER; COLLECT STRING/GUNPOWDER/LEATHER. "
                        + "Avoid trivial blocks (dirt, cobblestone, netherrack) and avoid the very hardest mobs/ores. ";
            case HARD:
                return "DIFFICULTY = HARD: a genuinely demanding late-game challenge that requires the "
                        + "Nether or End, rare resources, or DANGEROUS mobs. It must NOT be doable near spawn. "
                        + "Good examples: MINE DIAMOND_ORE/ANCIENT_DEBRIS/EMERALD_ORE; "
                        + "KILL BLAZE/GHAST/WITHER_SKELETON/PIGLIN_BRUTE/HOGLIN/ENDERMAN/EVOKER/RAVAGER/"
                        + "ELDER_GUARDIAN/SHULKER. "
                        + "STRICTLY FORBIDDEN for HARD: common/cheap blocks (NETHERRACK, COBBLESTONE, STONE, DIRT, "
                        + "SAND, GRAVEL, basic logs, any '*_ORE' easier than diamond) and weak/common mobs "
                        + "(SKELETON, ZOMBIE, SPIDER, CREEPER, slimes, passive animals). If unsure, pick diamonds, "
                        + "ancient debris, or a dangerous Nether/raid mob. ";
            case BRUTAL:
            default:
                return "DIFFICULTY = BRUTAL: the most punishing end-game content on the server — even harder "
                        + "than HARD, for fully-geared veterans. Demand the rarest resources in bulk or the "
                        + "DEADLIEST mobs. Good examples: MINE ANCIENT_DEBRIS in quantity; "
                        + "KILL WARDEN/RAVAGER/EVOKER/ELDER_GUARDIAN (use SMALL counts for these — they're "
                        + "lethal) or WITHER_SKELETON/GHAST/BLAZE/PIGLIN_BRUTE/SHULKER in larger numbers. "
                        + "Must require top-tier gear and the Nether/End/Ancient City. "
                        + "STRICTLY FORBIDDEN for BRUTAL: anything an EASY/MEDIUM/HARD player could shrug off, "
                        + "common blocks, and weak mobs (skeletons, zombies, spiders, creepers, passive animals). ";
        }
    }

    private JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
