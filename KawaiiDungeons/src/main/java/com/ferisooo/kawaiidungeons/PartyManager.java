package com.ferisooo.kawaiidungeons;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks parties entirely in memory. Each player belongs to at most one party.
 * A solo player effectively acts as a one-person party when they start a
 * dungeon; for command purposes a party must be explicitly created first.
 */
public final class PartyManager {

    private final KawaiiDungeons plugin;

    /** playerId -> party they belong to. */
    private final Map<UUID, Party> byMember = new HashMap<>();
    /** invitee -> (leaderId, expiry epoch millis). */
    private final Map<UUID, long[]> inviteExpiry = new HashMap<>();
    private final Map<UUID, UUID> inviteFrom = new HashMap<>();

    public PartyManager(KawaiiDungeons plugin) {
        this.plugin = plugin;
    }

    public Party partyOf(UUID id) { return byMember.get(id); }

    public boolean inParty(UUID id) { return byMember.containsKey(id); }

    public Party create(Player leader) {
        Party party = new Party(leader.getUniqueId());
        byMember.put(leader.getUniqueId(), party);
        return party;
    }

    public void invite(UUID leader, UUID invitee) {
        long expireMs = plugin.getConfig().getLong("party.invite-expire-seconds", 120) * 1000L;
        inviteExpiry.put(invitee, new long[]{System.currentTimeMillis() + expireMs});
        inviteFrom.put(invitee, leader);
    }

    /** Returns the leader who invited {@code invitee}, or null if none / expired. */
    public UUID activeInvite(UUID invitee) {
        long[] exp = inviteExpiry.get(invitee);
        if (exp == null) return null;
        if (System.currentTimeMillis() > exp[0]) {
            inviteExpiry.remove(invitee);
            inviteFrom.remove(invitee);
            return null;
        }
        return inviteFrom.get(invitee);
    }

    public void clearInvite(UUID invitee) {
        inviteExpiry.remove(invitee);
        inviteFrom.remove(invitee);
    }

    /** Adds invitee to the inviter's party. Returns the party, or null on failure. */
    public Party accept(UUID invitee) {
        UUID leader = activeInvite(invitee);
        if (leader == null) return null;
        Party party = byMember.get(leader);
        if (party == null) return null;
        int max = plugin.getConfig().getInt("party.max-size", 5);
        if (party.size() >= max) return null;
        party.add(invitee);
        byMember.put(invitee, party);
        clearInvite(invitee);
        return party;
    }

    /** Removes a player from their party, dissolving it if the leader leaves. */
    public void leave(UUID id) {
        Party party = byMember.remove(id);
        if (party == null) return;
        party.remove(id);
        if (party.isLeader(id)) {
            // Promote the next member, or disband if empty.
            if (party.members().isEmpty()) {
                disbandInternal(party);
            } else {
                UUID next = party.members().iterator().next();
                party.setLeader(next);
                Player np = Bukkit.getPlayer(next);
                if (np != null) np.sendMessage("§d(✿) you are now the party leader~");
            }
        }
    }

    public void kick(Party party, UUID target) {
        party.remove(target);
        byMember.remove(target);
    }

    public void disband(Party party) {
        disbandInternal(party);
    }

    private void disbandInternal(Party party) {
        for (UUID m : party.memberList()) {
            byMember.remove(m);
        }
        party.members().clear();
    }
}
