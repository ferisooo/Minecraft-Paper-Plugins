package com.ferisooo.kawaiilogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Per-player persistent state for first-join dates, milestone tracking, etc.
 * Stored as flat properties keyed like "firstjoin.<uuid>".
 */
public final class PlayerState {

    private final File file;
    private final Logger log;
    private final Properties props = new Properties();
    private boolean dirty = false;

    public PlayerState(File file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    public synchronized void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException ex) {
            if (log != null) log.warning("(\u2727) couldn't load player state: " + ex.getMessage());
        }
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                props.store(out, "KawaiiLogger persistent player state");
            }
            dirty = false;
        } catch (IOException ex) {
            if (log != null) log.warning("(\u2727) couldn't save player state: " + ex.getMessage());
        }
    }

    public synchronized String get(String field, UUID id, String def) {
        return props.getProperty(field + "." + id, def);
    }

    public synchronized void set(String field, UUID id, String value) {
        String key = field + "." + id;
        Object old = props.setProperty(key, value);
        if (old == null || !value.equals(old)) {
            dirty = true;
        }
    }

    public synchronized long getLong(String field, UUID id, long def) {
        String s = props.getProperty(field + "." + id);
        if (s == null) return def;
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return def; }
    }

    public synchronized void setLong(String field, UUID id, long value) {
        set(field, id, String.valueOf(value));
    }
}
