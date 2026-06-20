package com.ferisooo.kawaiicam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the director's camera output to a JSON track file and reads it back
 * for playback. The file is NOT a video — it's a per-tick keyframe list
 * (position + rotation + which shot was active). Replay it in-game with
 * {@code /cam play} and screen-capture that clean run to get your MP4.
 */
final class Recorder {

    /** One captured camera keyframe. */
    static final class Frame {
        int t;
        double x, y, z, yaw, pitch;
        String shot;
    }

    /** A whole take: some metadata plus the keyframe list. */
    static final class Recording {
        String subject;
        String style;
        String world;
        long created;
        double tickRate = 20.0;
        List<Frame> frames = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Recording rec = new Recording();
    private int tick = 0;

    Recorder(String subject, String style, String world) {
        rec.subject = subject;
        rec.style = style;
        rec.world = world;
        rec.created = System.currentTimeMillis();
    }

    void add(Pose p, String shot) {
        Frame f = new Frame();
        f.t = tick++;
        f.x = round(p.x); f.y = round(p.y); f.z = round(p.z);
        f.yaw = round(p.yaw); f.pitch = round(p.pitch);
        f.shot = shot;
        rec.frames.add(f);
    }

    int frameCount() {
        return rec.frames.size();
    }

    void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer w = new FileWriter(file)) {
            GSON.toJson(rec, w);
        }
    }

    static Recording load(File file) throws IOException {
        try (Reader r = new FileReader(file)) {
            Recording rec = GSON.fromJson(r, Recording.class);
            if (rec == null || rec.frames == null) {
                throw new IOException("empty or malformed recording");
            }
            return rec;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
