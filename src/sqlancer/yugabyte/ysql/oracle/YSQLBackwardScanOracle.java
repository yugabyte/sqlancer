package sqlancer.yugabyte.ysql.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

/**
 * Reads a range-sharded table in index order, forward and backward, while another session holds uncommitted writes and
 * row locks in it. Both directions must return exactly the committed rows (the writer always rolls back), in order,
 * without hanging. On YugabyteDB the tables are split, so most tablets have a lower key bound (the setting of the fast
 * backward scan bug #34839).
 */
public class YSQLBackwardScanOracle implements TestOracle<YSQLGlobalState> {

    static final int ROWS = 300;
    private static final int STATEMENT_TIMEOUT_MS = 10000;
    private static final String RC = "READ COMMITTED";
    private static final String RR = "REPEATABLE READ";
    private static final String SER = "SERIALIZABLE";

    private final YSQLGlobalState state;

    public YSQLBackwardScanOracle(YSQLGlobalState state) {
        this.state = state;
    }

    // The committed rows of k in [lo, hi] as "k:v" (v = k), ascending; first n of the requested direction.
    static List<String> expected(int lo, int hi, boolean descending, Integer limit) {
        List<String> rows = IntStream.rangeClosed(Math.max(lo, 1), Math.min(hi, ROWS)).mapToObj(k -> k + ":" + k)
                .collect(Collectors.toList());
        if (descending) {
            Collections.reverse(rows);
        }
        return limit == null ? rows : rows.subList(0, Math.min(limit, rows.size()));
    }

    @Override
    public void check() throws Exception {
        boolean pg = state.isPgCompatible();
        ensureScratch(pg);
        String table = Randomly.fromOptions("ybw.asc_k", "ybw.desc_k");
        List<String> log = new ArrayList<>();
        try (Connection a = openSession(); Connection b = openSession()) {
            String isolationA = Randomly.fromOptions(RC, RR, SER);
            execute(a, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "A");
            execute(a, "BEGIN ISOLATION LEVEL " + isolationA, log, "A");
            int writes = 1 + (int) Randomly.getNotCachedInteger(0, 3);
            for (int i = 0; i < writes; i++) {
                write(a, table, log);
            }
            execute(b, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "B");
            execute(b, "SET enable_seqscan = off", log, "B");
            execute(b, "SET enable_sort = off", log, "B");
            execute(b, "SET enable_bitmapscan = off", log, "B");
            if (!pg && Randomly.getBoolean()) {
                execute(b, "SET yb_fetch_row_limit = " + Randomly.fromOptions(1, 2, 3, 7, 1024), log, "B");
            }
            int lo = (int) Randomly.getNotCachedInteger(-10, ROWS);
            int hi = lo + (int) Randomly.getNotCachedInteger(0, ROWS + 20);
            Integer limit = Randomly.getBooleanWithRatherLowProbability() ? null
                    : (int) Randomly.getNotCachedInteger(1, 40);
            for (boolean descending : new boolean[] { true, false }) {
                String sql = "SELECT k || ':' || v FROM " + table + " WHERE k BETWEEN " + lo + " AND " + hi
                        + " ORDER BY k " + (descending ? "DESC" : "ASC") + (limit == null ? "" : " LIMIT " + limit);
                List<String> rows = read(b, sql, log);
                List<String> want = expected(lo, hi, descending, limit);
                if (!want.equals(rows)) {
                    throw new AssertionError("index scan " + (descending ? "DESC" : "ASC") + " under uncommitted "
                            + isolationA + " writes returned " + rows.size() + " rows, expected " + want.size()
                            + "; first differences: " + firstDifference(want, rows) + "\n" + String.join("\n", log));
                }
            }
            execute(a, "ROLLBACK", log, "A");
        }
    }

    // One uncommitted write or row lock by A on a few keys (the table itself is never changed: A rolls back).
    private void write(Connection a, String table, List<String> log) throws SQLException {
        int k = (int) Randomly.getNotCachedInteger(1, ROWS + 1);
        int span = (int) Randomly.getNotCachedInteger(0, 5);
        String keys = "k BETWEEN " + k + " AND " + (k + span);
        String sql;
        switch (Randomly.fromOptions(0, 1, 2, 3, 4)) {
        case 0:
            sql = "UPDATE " + table + " SET v = v + 1000 WHERE " + keys;
            break;
        case 1:
            sql = "DELETE FROM " + table + " WHERE " + keys;
            break;
        case 2:
            sql = "INSERT INTO " + table + " VALUES (" + (ROWS + 1 + k) + ", 0) ON CONFLICT DO NOTHING";
            break;
        case 3:
            sql = "SELECT k FROM " + table + " WHERE " + keys + " FOR "
                    + Randomly.fromOptions("UPDATE", "NO KEY UPDATE", "SHARE", "KEY SHARE");
            break;
        default:
            sql = "UPDATE " + table + " SET k = k WHERE " + keys;
            break;
        }
        try {
            execute(a, sql, log, "A");
        } catch (SQLException e) {
            throw new IgnoreMeException(); // the writer's own failure says nothing about the reader
        }
    }

    private List<String> read(Connection b, String sql, List<String> log) throws SQLException {
        try {
            return query(b, sql, log, "B");
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("canceling statement due to statement timeout")) {
                throw new AssertionError("index scan did not finish in " + STATEMENT_TIMEOUT_MS + " ms (hang?)\n"
                        + String.join("\n", log), e);
            }
            ExpectedErrors errors = new ExpectedErrors();
            YSQLErrors.addTransactionErrors(errors);
            if (errors.errorIsExpected(msg)) {
                throw new IgnoreMeException();
            }
            throw new AssertionError("index scan failed: " + msg + "\n" + String.join("\n", log), e);
        }
    }

    private static String firstDifference(List<String> want, List<String> got) {
        for (int i = 0; i < Math.max(want.size(), got.size()); i++) {
            String w = i < want.size() ? want.get(i) : "<none>";
            String g = i < got.size() ? got.get(i) : "<none>";
            if (!w.equals(g)) {
                return "at " + i + " expected " + w + ", got " + g;
            }
        }
        return "none";
    }

    // Two 300-row tables only this oracle uses: k ASC and k DESC primary keys, split in three on YugabyteDB.
    private void ensureScratch(boolean pg) throws SQLException {
        try (Statement s = state.getConnection().createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid ="
                    + " c.relnamespace WHERE n.nspname = 'ybw' AND c.relname IN ('asc_k', 'desc_k')")) {
                if (rs.next() && rs.getInt(1) == 2) {
                    return;
                }
            }
            s.execute("CREATE SCHEMA IF NOT EXISTS ybw");
            for (String order : new String[] { "ASC", "DESC" }) {
                String name = "ybw." + order.toLowerCase() + "_k";
                String split = pg ? ""
                        : "ASC".equals(order) ? " SPLIT AT VALUES ((100), (200))" : " SPLIT AT VALUES ((200), (100))";
                s.execute("CREATE TABLE IF NOT EXISTS " + name + " (k int, v int, PRIMARY KEY (k " + order + "))"
                        + split);
                s.execute("INSERT INTO " + name + " SELECT g, g FROM generate_series(1, " + ROWS
                        + ") g ON CONFLICT DO NOTHING");
            }
        } catch (SQLException e) {
            throw new IgnoreMeException(); // a concurrent DDL or a missing feature; try again next check
        }
    }

    private Connection openSession() throws SQLException {
        try (Statement s = state.getConnection().createStatement()) {
            String url = s.getConnection().getMetaData().getURL();
            return DriverManager.getConnection(url, state.getOptions().getUserName(), state.getOptions().getPassword());
        }
    }

    private static void execute(Connection con, String sql, List<String> log, String session) throws SQLException {
        log.add("-- " + session + ": " + sql + ";");
        try (Statement s = con.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> query(Connection con, String sql, List<String> log, String session)
            throws SQLException {
        log.add("-- " + session + ": " + sql + ";");
        List<String> rows = new ArrayList<>();
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
