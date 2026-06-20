package com.ferisooo.kawaiiquests;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as one of ours so the click listener can recognize it
 * (and know which screen it is) instead of guessing from the title.
 */
public final class GuiHolder implements InventoryHolder {

    public enum Kind { SELECT, ACTIVE, CRATE }

    private final Kind kind;
    private Inventory inventory;

    public GuiHolder(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
