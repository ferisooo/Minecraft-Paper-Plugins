package com.ferisooo.kawaiiessentials;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Tiny marker so we can recognise OUR trash-bin inventories
 * in InventoryCloseEvent (instead of guessing by title).
 */
public class TrashHolder implements InventoryHolder {

    private Inventory inventory;

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
