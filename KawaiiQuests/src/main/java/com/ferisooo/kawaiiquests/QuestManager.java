package com.ferisooo.kawaiiquests;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds each player's current quest + progress. All access happens on the
 * main server thread (events and the synced AI callback), so a plain map is
 * fine — no locking needed.
 */
public final class QuestManager {

    /** A player's in-flight quest and how far along they are. */
    public static final class Active {
        public final Quest quest;
        public int progress;
        public boolean completed;

        Active(Quest quest) {
            this.quest = quest;
        }
    }

    private final Map<UUID, Active> active = new HashMap<>();

    public boolean has(UUID id)      { return active.containsKey(id); }
    public Active get(UUID id)       { return active.get(id); }
    public Active start(UUID id, Quest q) {
        Active a = new Active(q);
        active.put(id, a);
        return a;
    }
    public void clear(UUID id)       { active.remove(id); }
    public void clearAll()           { active.clear(); }

    /** Live view of all active quests (read-only use — e.g. saving to disk). */
    public Map<UUID, Active> all()   { return active; }

    /** Re-create a saved quest with its stored progress (used on load). */
    public void restore(UUID id, Quest q, int progress) {
        Active a = new Active(q);
        a.progress = Math.max(0, Math.min(q.getAmount(), progress));
        active.put(id, a);
    }
}
