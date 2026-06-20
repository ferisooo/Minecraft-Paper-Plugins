package com.ferisooo.kawaiilogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Writes log lines to per-day files in plugins/KawaiiLogger/logs/.
 * Writes are queued on a single background daemon thread.
 */
public final class LogWriter {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File logsDir;
    private final Logger pluginLog;
    private final ExecutorService exec;

    private LocalDate currentDate;
    private BufferedWriter currentWriter;

    public LogWriter(File logsDir, Logger pluginLog) {
        this.logsDir = logsDir;
        this.pluginLog = pluginLog;
        if (!logsDir.exists()) logsDir.mkdirs();
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "KawaiiLogger-FileWriter");
            t.setDaemon(true);
            return t;
        });
    }

    public void log(String category, String message) {
        final LocalDateTime now = LocalDateTime.now();
        final String line = "[" + TIME_FMT.format(now) + "] [" + category + "] " + sanitize(message);
        exec.submit(() -> writeLine(now.toLocalDate(), line));
    }

    private void writeLine(LocalDate date, String line) {
        try {
            if (currentWriter == null || currentDate == null || !currentDate.equals(date)) {
                rollover(date);
            }
            if (currentWriter != null) {
                currentWriter.write(line);
                currentWriter.newLine();
                currentWriter.flush();
            }
        } catch (IOException ex) {
            if (pluginLog != null) pluginLog.warning("(\u2727) log write failed: " + ex.getMessage());
        }
    }

    private void rollover(LocalDate date) throws IOException {
        if (currentWriter != null) {
            try { currentWriter.close(); } catch (IOException ignored) {}
            currentWriter = null;
        }
        File f = new File(logsDir, date.toString() + ".log");
        currentWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
        currentDate = date;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        // strip newlines so each event is one line
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    public void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (currentWriter != null) {
            try { currentWriter.close(); } catch (IOException ignored) {}
            currentWriter = null;
        }
    }
}
