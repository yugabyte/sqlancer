package sqlancer.yugabyte.ysql.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import sqlancer.yugabyte.ysql.ast.YSQLSelect.ForClause;

public class TestYSQLSkipLockedOracle {

    private static final List<String> ALL = Arrays.asList("a", "b", "c", "d", "e");
    private static final Set<String> LOCKED = new HashSet<>(Arrays.asList("b", "d"));

    // PostgreSQL docs, "Conflicting Row-Level Locks": X marks a conflict. Rows and columns: KEY SHARE, SHARE,
    // NO KEY UPDATE, UPDATE.
    @Test
    public void conflictTableMatchesPostgres() {
        ForClause[] order = { ForClause.KEY_SHARE, ForClause.SHARE, ForClause.NO_KEY_UPDATE, ForClause.UPDATE };
        boolean[][] expected = { //
                { false, false, false, true }, //
                { false, false, true, true }, //
                { false, true, true, true }, //
                { true, true, true, true } };
        for (int i = 0; i < order.length; i++) {
            for (int j = 0; j < order.length; j++) {
                assertEquals(expected[i][j], YSQLSkipLockedOracle.conflicts(order[i], order[j]),
                        order[i] + " vs " + order[j]);
            }
        }
    }

    // A row can carry several strengths (an outer KEY SHARE plus a released savepoint's UPDATE); it conflicts when any
    // of them does.
    @Test
    public void conflictingRowsUsesEveryHeldStrength() {
        Map<String, Set<ForClause>> held = new HashMap<>();
        held.put("a", EnumSet.of(ForClause.KEY_SHARE));
        held.put("b", EnumSet.of(ForClause.KEY_SHARE, ForClause.UPDATE));
        held.put("c", EnumSet.of(ForClause.SHARE));
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")),
                YSQLSkipLockedOracle.conflictingRows(held, ForClause.UPDATE));
        assertEquals(new HashSet<>(Arrays.asList("b")),
                YSQLSkipLockedOracle.conflictingRows(held, ForClause.KEY_SHARE));
        assertEquals(new HashSet<>(Arrays.asList("b", "c")),
                YSQLSkipLockedOracle.conflictingRows(held, ForClause.NO_KEY_UPDATE));
    }

    @Test
    public void skipLockedExpectation() {
        assertEquals(Arrays.asList("a", "c", "e"), YSQLSkipLockedOracle.expectedSkipLocked(ALL, LOCKED, true, null));
        assertEquals(ALL, YSQLSkipLockedOracle.expectedSkipLocked(ALL, LOCKED, false, null));
        // Queue pattern: the first n available rows in order, skipping locked ones.
        assertEquals(Arrays.asList("a", "c"), YSQLSkipLockedOracle.expectedSkipLocked(ALL, LOCKED, true, 2));
        assertEquals(Arrays.asList("a", "c", "e"), YSQLSkipLockedOracle.expectedSkipLocked(ALL, LOCKED, true, 9));
    }

    @Test
    public void nowaitExpectation() {
        assertTrue(YSQLSkipLockedOracle.nowaitMustFail(ALL, LOCKED, true, null));
        assertFalse(YSQLSkipLockedOracle.nowaitMustFail(ALL, LOCKED, false, null));
        // Under LIMIT 1 NOWAIT only locks "a", which A does not hold.
        assertFalse(YSQLSkipLockedOracle.nowaitMustFail(ALL, LOCKED, true, 1));
        assertTrue(YSQLSkipLockedOracle.nowaitMustFail(ALL, LOCKED, true, 2));
        assertEquals(Arrays.asList("a"), YSQLSkipLockedOracle.nowaitTargets(ALL, 1));
    }

    // Negative control for the comparison: a SKIP LOCKED that ignores locks, or that returns the right rows in the
    // wrong order under LIMIT, must be reported.
    @Test
    public void comparisonRejectsBrokenSkipLocked() {
        List<String> expected = YSQLSkipLockedOracle.expectedSkipLocked(ALL, LOCKED, true, null);
        assertNull(YSQLSkipLockedOracle.compare(expected, Arrays.asList("e", "a", "c"), false));
        assertNotNull(YSQLSkipLockedOracle.compare(expected, ALL, false));
        assertNotNull(YSQLSkipLockedOracle.compare(expected, Arrays.asList("a", "c"), false));
        List<String> queue = YSQLSkipLockedOracle.expectedSkipLocked(ALL, LOCKED, true, 2);
        assertNotNull(YSQLSkipLockedOracle.compare(queue, Arrays.asList("c", "a"), true));
        assertNotNull(YSQLSkipLockedOracle.compare(queue, Arrays.asList("a", "b"), true));
    }

    // Live negative control, opt-in like TestYSQLRecentCoverageDatabase: against a real YSQL server the oracle's
    // expectation must match SKIP LOCKED and NOWAIT, and the lock-ignoring expectation must not.
    @Test
    public void liveSkipLockedMatchesExpectationAndRejectsMutation() throws Exception {
        String url = System.getenv("YSQL_COVERAGE_JDBC_URL");
        assumeTrue(url != null);
        String user = System.getenv().getOrDefault("YSQL_COVERAGE_USER", "yugabyte");
        String password = System.getenv().getOrDefault("YSQL_COVERAGE_PASSWORD", "yugabyte");
        String database = "sqlancer_skiplocked_" + UUID.randomUUID().toString().replace("-", "");
        String dbUrl = url.substring(0, url.lastIndexOf('/') + 1) + database;
        try (Connection admin = DriverManager.getConnection(url, user, password)) {
            exec(admin, "CREATE DATABASE " + database);
            try {
                try (Connection setup = DriverManager.getConnection(dbUrl, user, password)) {
                    exec(setup, "CREATE TABLE q (k int PRIMARY KEY, v int)");
                    exec(setup, "INSERT INTO q SELECT i, i FROM generate_series(1, 6) i");
                }
                try (Connection a = DriverManager.getConnection(dbUrl, user, password);
                        Connection b = DriverManager.getConnection(dbUrl, user, password)) {
                    exec(a, "BEGIN ISOLATION LEVEL READ COMMITTED");
                    Set<String> locked = new HashSet<>(rows(a, "SELECT k::text FROM q WHERE k <= 3 FOR UPDATE"));
                    exec(b, "SET statement_timeout = 10000");
                    exec(b, "BEGIN ISOLATION LEVEL READ COMMITTED");
                    List<String> all = rows(b, "SELECT k::text FROM q ORDER BY k");
                    List<String> skip = rows(b, "SELECT k::text FROM q FOR SHARE SKIP LOCKED");
                    assertNull(YSQLSkipLockedOracle
                            .compare(YSQLSkipLockedOracle.expectedSkipLocked(all, locked, true, null), skip, false));
                    assertNotNull(YSQLSkipLockedOracle
                            .compare(YSQLSkipLockedOracle.expectedSkipLocked(all, locked, false, null), skip, false));
                    List<String> queue = rows(b, "SELECT k::text FROM q ORDER BY k LIMIT 2 FOR UPDATE SKIP LOCKED");
                    List<String> sorted = new ArrayList<>(all);
                    Collections.sort(sorted);
                    assertNull(YSQLSkipLockedOracle
                            .compare(YSQLSkipLockedOracle.expectedSkipLocked(sorted, locked, true, 2), queue, true));
                    exec(b, "ROLLBACK");
                    exec(b, "BEGIN ISOLATION LEVEL READ COMMITTED");
                    assertThrows(SQLException.class, () -> rows(b, "SELECT k FROM q FOR SHARE NOWAIT"));
                }
            } finally {
                exec(admin, "DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
