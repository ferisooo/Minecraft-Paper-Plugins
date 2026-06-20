package com.ferisooo.kawaiiessentials;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker so we can recognise the /kess essentials menu in click events
 * (and tell it apart from the trash bin).
 */
public class MenuHolder implements InventoryHolder {

    /** Distinguishes the different MenuHolder-backed GUIs so click handling doesn't collide. */
    public enum Kind { MAIN, TRASH_FILTER, TPA_PICKER, TPAHERE_PICKER, HOMES, WARPS_PLAYERS, WARPS_LIST }

    private Inventory inventory;
    private Kind kind = Kind.MAIN;
    // Optional free-form context for a GUI (e.g. the warp owner's UUID in WARPS_LIST).
    private String context;

    public MenuHolder() {
    }

    public MenuHolder(Kind kind) {
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
