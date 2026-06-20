package com.ferisooo.kawaiiquests;

/**
 * A single quest with a machine-verifiable objective. The AI picks the
 * {@link Type}, target, amount AND the reward (item + count); the flavor lives
 * in title/desc. {@code rewardItem == null} means "no AI reward — roll the
 * built-in loot table instead".
 */
public final class Quest {

    /** Objective kinds the plugin knows how to track via events. */
    public enum Type { MINE, KILL, COLLECT, FISH, BREED, TAME, SMELT, CRAFT, ENCHANT, TRADE }

    private final String title;
    private final String description;
    private final Type type;
    private final String target;       // canonical Material or EntityType name
    private final int amount;
    private final Difficulty difficulty;
    private final String rewardItem;   // canonical Material name the AI chose, or null
    private final int rewardAmount;

    public Quest(String title, String description, Type type, String target,
                 int amount, Difficulty difficulty) {
        this(title, description, type, target, amount, difficulty, null, 0);
    }

    public Quest(String title, String description, Type type, String target,
                 int amount, Difficulty difficulty, String rewardItem, int rewardAmount) {
        this.title = title;
        this.description = description;
        this.type = type;
        this.target = target;
        this.amount = amount;
        this.difficulty = difficulty;
        this.rewardItem = rewardItem;
        this.rewardAmount = rewardAmount;
    }

    public String getTitle()         { return title; }
    public String getDescription()   { return description; }
    public Type getType()            { return type; }
    public String getTarget()        { return target; }
    public int getAmount()           { return amount; }
    public Difficulty getDifficulty(){ return difficulty; }
    public String getRewardItem()    { return rewardItem; }
    public int getRewardAmount()     { return rewardAmount; }
    public boolean hasReward()       { return rewardItem != null && rewardAmount > 0; }
}
