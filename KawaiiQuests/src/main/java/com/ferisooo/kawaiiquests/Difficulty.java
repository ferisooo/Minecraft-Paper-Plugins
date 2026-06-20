package com.ferisooo.kawaiiquests;

import java.util.Locale;

/** The three quest tiers a player can pick from the menu. */
public enum Difficulty {
    EASY("&aEasy"),
    MEDIUM("&eMedium"),
    HARD("&cHard"),
    BRUTAL("&4&lBrutal");

    private final String display;

    Difficulty(String display) {
        this.display = display;
    }

    /** Colored label for menus/messages (legacy '&' codes). */
    public String display() {
        return display;
    }

    /** Lowercase config-key form, e.g. "easy". */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Difficulty from(String s, Difficulty def) {
        if (s == null) return def;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
