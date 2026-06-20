package com.ferisooo.kawaiigroups;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Marker so KawaiiGroups can recognise its own menus in click events and carry
 * a little per-GUI state (which group it's about, the current page, and a
 * slot → target-player mapping for member/invite pickers).
 */
public final class GroupHolder implements InventoryHolder {

    public enum Kind { MAIN, GROUPS, PROFILE, MEMBERS, INVITES, SETTINGS, SEARCH }

    private Inventory inventory;
    private final Kind kind;
    private String context;                 // group id this menu relates to
    private int page = 0;
    private final List<UUID> rowTargets = new ArrayList<>(); // slot index → player

    public GroupHolder(Kind kind) { this.kind = kind; }

    public Kind getKind() { return kind; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public List<UUID> targets() { return rowTargets; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
