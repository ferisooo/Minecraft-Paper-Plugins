package com.ferisooo.kawaiiseasons;

/** The four seasons, in cycle order, with a display label (legacy § codes). */
public enum Season {
    SPRING("§a❀ Spring"),
    SUMMER("§e☀ Summer"),
    AUTUMN("§6🍂 Autumn"),
    WINTER("§b❄ Winter");

    private final String display;

    Season(String display) { this.display = display; }

    public String display() { return display; }

    public static Season byIndex(int i) {
        int n = ((i % 4) + 4) % 4;
        return values()[n];
    }
}
