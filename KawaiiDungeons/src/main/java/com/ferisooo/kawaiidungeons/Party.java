package com.ferisooo.kawaiidungeons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory party. A party has a leader and a set of members (the leader is
 * always also a member). Pending invites are tracked elsewhere by
 * {@link PartyManager} with expiry timestamps.
 */
public final class Party {

    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();
    /** Non-null while this party is inside a dungeon instance. */
    private String instanceWorld;

    public Party(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
    }

    public UUID leader() { return leader; }

    public void setLeader(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
    }

    public Set<UUID> members() { return members; }

    public List<UUID> memberList() { return new ArrayList<>(members); }

    public boolean isMember(UUID id) { return members.contains(id); }

    public boolean isLeader(UUID id) { return leader.equals(id); }

    public void add(UUID id) { members.add(id); }

    public void remove(UUID id) { members.remove(id); }

    public int size() { return members.size(); }

    public String instanceWorld() { return instanceWorld; }

    public void setInstanceWorld(String world) { this.instanceWorld = world; }
}
