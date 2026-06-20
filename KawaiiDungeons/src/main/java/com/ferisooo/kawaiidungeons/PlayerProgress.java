package com.ferisooo.kawaiidungeons;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent per-player progression: tokens, per-dungeon reputation,
 * completions, dungeon level, weekly-claim timestamp, achievements, and
 * deathless / speedrun best times. Loaded and saved by {@link ProgressManager}.
 */
public final class PlayerProgress {

    private int tokens;
    private int completions;
    private long lastWeeklyClaim; // epoch millis
    private final Map<String, Integer> reputation = new HashMap<>();   // dungeonId -> rep
    private final Set<String> achievements = new HashSet<>();
    // key "dungeonId:difficulty" -> best run time in millis (lower is better).
    private final Map<String, Long> speedrunBest = new HashMap<>();
    private boolean everDeathless;

    public int tokens() { return tokens; }

    public void addTokens(int amount) { tokens = Math.max(0, tokens + amount); }

    public void setTokens(int amount) { tokens = Math.max(0, amount); }

    public boolean spendTokens(int amount) {
        if (tokens < amount) return false;
        tokens -= amount;
        return true;
    }

    public int completions() { return completions; }

    public void addCompletion() { completions++; }

    public void setCompletions(int c) { completions = c; }

    /** Simple "dungeon level" derived from total completions. */
    public int dungeonLevel() {
        // every 3 completions = 1 level, level >= 0.
        return completions / 3;
    }

    public long lastWeeklyClaim() { return lastWeeklyClaim; }

    public void setLastWeeklyClaim(long ts) { lastWeeklyClaim = ts; }

    public int reputation(String dungeonId) { return reputation.getOrDefault(dungeonId, 0); }

    public void addReputation(String dungeonId, int amount) {
        reputation.merge(dungeonId, amount, Integer::sum);
    }

    public void setReputation(String dungeonId, int amount) { reputation.put(dungeonId, amount); }

    public Map<String, Integer> reputationMap() { return reputation; }

    public Set<String> achievements() { return achievements; }

    /** Returns true if the achievement was newly added. */
    public boolean grantAchievement(String id) { return achievements.add(id); }

    public boolean hasAchievement(String id) { return achievements.contains(id); }

    public Map<String, Long> speedrunBestMap() { return speedrunBest; }

    public Long speedrunBest(String key) { return speedrunBest.get(key); }

    /** Records a run time if it beats the previous best. Returns true if it's a new best. */
    public boolean recordSpeedrun(String key, long millis) {
        Long prev = speedrunBest.get(key);
        if (prev == null || millis < prev) {
            speedrunBest.put(key, millis);
            return true;
        }
        return false;
    }

    public boolean everDeathless() { return everDeathless; }

    public void setEverDeathless(boolean v) { everDeathless = v; }
}
