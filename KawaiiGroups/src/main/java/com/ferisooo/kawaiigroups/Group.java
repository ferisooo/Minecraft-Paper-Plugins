package com.ferisooo.kawaiigroups;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory group. Groups are ephemeral and online-only: a member who logs
 * off is removed, and if the owner logs off the group disbands. So there is no
 * persistence for groups themselves — only the live registry in GroupManager.
 */
public final class Group {

    final String id;                 // unique key (lowercased name)
    String name;                     // display name (original case)
    String colorCode;                // legacy colour code, e.g. "&b"
    UUID owner;
    String description = "";
    boolean privateMode = false;

    /**
     * Members mapped to their role. The owner is always present. Concurrent
     * because the async chat event reads/iterates this while the main thread
     * adds and removes members.
     */
    final Map<UUID, Role> members = new java.util.concurrent.ConcurrentHashMap<>();
    /** Players who have asked to join (handled by moderators+). */
    final Set<UUID> joinRequests = new LinkedHashSet<>();

    Group(String id, String name, String colorCode, UUID owner) {
        this.id = id;
        this.name = name;
        this.colorCode = colorCode;
        this.owner = owner;
        this.members.put(owner, Role.OWNER);
    }

    int size() { return members.size(); }

    Role roleOf(UUID u) { return members.get(u); }

    boolean has(UUID u) { return members.containsKey(u); }

    /** The coloured display name (no reset appended). */
    String colored() { return colorCode + name; }

    /** Online members. */
    Map<UUID, Role> all() { return members; }
}
