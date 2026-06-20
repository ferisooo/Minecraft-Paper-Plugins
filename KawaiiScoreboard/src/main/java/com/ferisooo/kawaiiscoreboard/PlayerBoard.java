package com.ferisooo.kawaiiscoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;

/**
 * One sidebar's worth of state for a single player.
 *
 * <p>Sidebar lines are stored as team prefixes rather than as raw entry
 * strings: each row is a team whose only entry is a unique invisible
 * color-code stub ("§0", "§1", …). Updating a row is a single
 * {@link Team#prefix(Component)} call, which doesn't blink, doesn't trip
 * the entry-uniqueness rule, and doesn't churn the underlying scoreboard.
 *
 * <p>Score numbers on the right of each line are blanked via Paper's
 * per-score {@link NumberFormat#blankFormat()} so the sidebar reads as a
 * clean list of text rows.
 */
final class PlayerBoard {

    /** Unique invisible stubs per row. Order matters: index = row index. */
    private static final String[] ENTRY_STUBS = {
            "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9"
    };

    static final int MAX_ROWS = ENTRY_STUBS.length;

    private final UUID owner;
    private final Scoreboard board;
    private final Objective objective;
    private final Team[] rowTeams;
    private final int rowCount;

    PlayerBoard(Player p, Component title, int rowCount) {
        if (rowCount < 1 || rowCount > MAX_ROWS) {
            throw new IllegalArgumentException("rowCount out of range: " + rowCount);
        }
        this.owner = p.getUniqueId();
        this.rowCount = rowCount;
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = board.registerNewObjective("kawaii_sb", Criteria.DUMMY, title);
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.rowTeams = new Team[rowCount];
        for (int i = 0; i < rowCount; i++) {
            String entry = ENTRY_STUBS[i];
            Team t = board.registerNewTeam("ksb_row_" + i);
            t.addEntry(entry);
            rowTeams[i] = t;
            // Score N..1 (descending), so row 0 sits at the top.
            Score score = objective.getScore(entry);
            score.setScore(rowCount - i);
            try {
                score.numberFormat(NumberFormat.blank());
            } catch (Throwable ignored) {
                // Older Paper without per-score number formats — accept the
                // visible integers rather than failing to attach the board.
            }
        }
    }

    UUID owner() { return owner; }

    /** The per-player scoreboard this board lives on (for re-assert checks). */
    org.bukkit.scoreboard.Scoreboard scoreboard() { return board; }

    void show() {
        Player p = Bukkit.getPlayer(owner);
        if (p != null) p.setScoreboard(board);
    }

    void title(Component title) {
        objective.displayName(title);
    }

    void setRow(int i, Component text) {
        if (i < 0 || i >= rowCount) return;
        rowTeams[i].prefix(text);
    }

    /** Reset the sidebar (so the player goes back to the server's main scoreboard). */
    void detach() {
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            try {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            } catch (Throwable ignored) {}
        }
        for (Team t : rowTeams) {
            try { t.unregister(); } catch (Throwable ignored) {}
        }
        try { objective.unregister(); } catch (Throwable ignored) {}
    }

    int rowCount() { return rowCount; }
}
