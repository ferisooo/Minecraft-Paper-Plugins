package com.ferisooo.kawaiiclaims;

/**
 * Ordered trust levels. ACCESS &lt; CONTAINER &lt; BUILD &lt; MANAGE.
 * Higher ordinal = more rights. Owner always has implicit full rights.
 */
public enum TrustLevel {
    ACCESS,
    CONTAINER,
    BUILD,
    MANAGE;

    /** True if this level satisfies (is at least) the required level. */
    public boolean atLeast(TrustLevel required) {
        return this.ordinal() >= required.ordinal();
    }

    /** Parse from a user-supplied string, or null if unrecognised. */
    public static TrustLevel fromString(String s) {
        if (s == null) return null;
        switch (s.toLowerCase()) {
            case "access": return ACCESS;
            case "container": return CONTAINER;
            case "build": return BUILD;
            case "manage": return MANAGE;
            default: return null;
        }
    }

    /** Next level in the cycle (used by the Trust GUI). Wraps MANAGE -> ACCESS. */
    public TrustLevel cycle() {
        switch (this) {
            case ACCESS: return CONTAINER;
            case CONTAINER: return BUILD;
            case BUILD: return MANAGE;
            default: return ACCESS;
        }
    }

    public String display() {
        switch (this) {
            case ACCESS: return "Access";
            case CONTAINER: return "Container";
            case BUILD: return "Build";
            default: return "Manage";
        }
    }

    /** Lower-case role name used by the permission registry. */
    public String roleName() {
        return name().toLowerCase();
    }

    /** Role name for a (possibly null) trust level. Null -> "visitor". */
    public static String roleNameOf(TrustLevel level) {
        return level == null ? "visitor" : level.roleName();
    }

    /**
     * Ordered role names recognised by the permission registry, from least to
     * most privileged. "visitor" is the implicit role for untrusted players.
     */
    public static final String[] ROLE_NAMES = { "visitor", "access", "container", "build", "manage" };
}
