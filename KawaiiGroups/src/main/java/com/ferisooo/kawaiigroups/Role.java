package com.ferisooo.kawaiigroups;

/**
 * Group roles, ordered low → high. Permissions follow the spec:
 *   OWNER     — everything (invite, kick, promote, demote, transfer, disband)
 *   ADMIN     — invite, kick, moderate chat
 *   MODERATOR — invite, manage join requests
 *   MEMBER    — chat, view members
 */
public enum Role {
    MEMBER, MODERATOR, ADMIN, OWNER;

    public boolean canInvite()         { return ordinal() >= MODERATOR.ordinal(); }
    public boolean canManageRequests() { return ordinal() >= MODERATOR.ordinal(); }
    public boolean canKick()           { return ordinal() >= ADMIN.ordinal(); }
    public boolean canModerateChat()   { return ordinal() >= ADMIN.ordinal(); }
    public boolean canPromote()        { return this == OWNER; }
    public boolean canDisband()        { return this == OWNER; }
    public boolean canTransfer()       { return this == OWNER; }

    /** A short coloured tag for member lists / GUIs. */
    public String tag() {
        switch (this) {
            case OWNER:     return "&6Owner";
            case ADMIN:     return "&cAdmin";
            case MODERATOR: return "&bModerator";
            default:        return "&7Member";
        }
    }

    /** Next role up (capped just below OWNER — ownership transfers explicitly). */
    public Role promoted() {
        switch (this) {
            case MEMBER:    return MODERATOR;
            case MODERATOR: return ADMIN;
            default:        return this; // ADMIN stays (only transfer makes an OWNER)
        }
    }

    /** Next role down (floored at MEMBER). */
    public Role demoted() {
        switch (this) {
            case ADMIN:     return MODERATOR;
            case MODERATOR: return MEMBER;
            default:        return MEMBER;
        }
    }
}
