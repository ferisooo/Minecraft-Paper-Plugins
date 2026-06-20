package com.ferisooo.kawaiidungeons;

/**
 * Runtime objective state for a single dungeon instance. The {@link Type}
 * enumerates all eight supported objective kinds; {@code progress}/{@code target}
 * give a generic counter that each objective type interprets in its own way:
 *
 * <ul>
 *   <li>KILL_BOSS        — target 1, progress 1 when the boss dies.</li>
 *   <li>COLLECT_RELICS   — target N relics, progress = relics collected.</li>
 *   <li>ACTIVATE_SHRINES — target N shrines, progress = shrines activated.</li>
 *   <li>DEFEND_NPC       — target = seconds to survive, progress = seconds elapsed.</li>
 *   <li>ESCORT_NPC       — target 1, progress 1 when the NPC reaches the goal.</li>
 *   <li>DESTROY_CRYSTALS — target N crystals, progress = crystals destroyed.</li>
 *   <li>SURVIVE_WAVES    — target N waves, progress = waves cleared.</li>
 *   <li>TIMED_CHALLENGE  — target = seconds, progress = sub-goal kills, completes
 *                          when progress reaches a configured amount in time.</li>
 * </ul>
 */
public final class Objective {

    public enum Type {
        KILL_BOSS,
        COLLECT_RELICS,
        ACTIVATE_SHRINES,
        DEFEND_NPC,
        ESCORT_NPC,
        DESTROY_CRYSTALS,
        SURVIVE_WAVES,
        TIMED_CHALLENGE
    }

    private final Type type;
    private int progress;
    private final int target;
    private boolean complete;
    private boolean failed;

    public Objective(Type type, int target) {
        this.type = type;
        this.target = Math.max(1, target);
    }

    public Type type() { return type; }

    public int progress() { return progress; }

    public int target() { return target; }

    public boolean isComplete() { return complete; }

    public boolean isFailed() { return failed; }

    public void increment() {
        if (complete || failed) return;
        progress++;
        if (progress >= target) complete = true;
    }

    public void setProgress(int value) {
        if (complete || failed) return;
        progress = value;
        if (progress >= target) complete = true;
    }

    public void forceComplete() { complete = true; }

    public void fail() { failed = true; }

    /** A short human-readable progress label for boss bars / action bars. */
    public String label() {
        return switch (type) {
            case KILL_BOSS -> "Slay the boss";
            case COLLECT_RELICS -> "Relics " + progress + "/" + target;
            case ACTIVATE_SHRINES -> "Shrines " + progress + "/" + target;
            case DEFEND_NPC -> "Defend " + progress + "/" + target + "s";
            case ESCORT_NPC -> "Escort the NPC to the goal";
            case DESTROY_CRYSTALS -> "Crystals " + progress + "/" + target;
            case SURVIVE_WAVES -> "Waves " + progress + "/" + target;
            case TIMED_CHALLENGE -> "Challenge " + progress + "/" + target;
        };
    }

    public double fraction() {
        return Math.max(0.0, Math.min(1.0, (double) progress / target));
    }
}
