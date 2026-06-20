package com.ferisooo.kawaiiclaims;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker InventoryHolder used to identify which KawaiiClaims GUI an
 * inventory belongs to, and which claim (by id) it is editing.
 *
 * Extended for paging (FLAGS / PERMS span multiple pages) and for the
 * per-role permission editor (which role is currently being edited).
 */
public class ClaimGuiHolder implements InventoryHolder {

    public enum Type { MENU, FLAGS, TRUST, ROLES, PERMS, PRESETS }

    private final Type type;
    private final String claimId;
    private Inventory inventory;

    // paging + role-editor state
    private int page = 0;
    private String role = null; // for PERMS: which role is being edited

    public ClaimGuiHolder(Type type, String claimId) {
        this.type = type;
        this.claimId = claimId;
    }

    public Type getType() { return type; }
    public String getClaimId() { return claimId; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
