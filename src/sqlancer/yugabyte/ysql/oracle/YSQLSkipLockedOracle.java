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

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.yugabyte.YugabyteBugs;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLSelect.ForClause;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Two-session oracle for row-level lock conflicts with {@code SKIP LOCKED} and {@code NOWAIT}.
 *
 * <p>
 * Session A opens a transaction and locks the rows of {@code t} that satisfy P with lock strength sA. While A holds the
 * locks, session B reads the rows that satisfy Q ("all") and then runs the same read with lock strength sB and a wait
 * policy:
 * <ul>
 * <li>{@code SKIP LOCKED} must return all minus the rows A locked when sA and sB conflict, and all otherwise.</li>
 * <li>{@code NOWAIT} must fail with "could not obtain lock" when sA and sB conflict and B's rows overlap A's locked
 * rows, and must return all otherwise. YugabyteDB supports NOWAIT only under READ COMMITTED, so B checks NOWAIT only
 * there.</li>
 * </ul>
 * Neither policy may block (B runs under a statement timeout), and neither may abort A's transaction. Rows are
 * identified by {@code ybctid}; SERIALIZABLE is excluded because YugabyteDB does not support either policy there.
 */
public class YSQLSkipLockedOracle implements TestOracle<YSQLGlobalState> {

    private static final int B_STATEMENT_TIMEOUT_MS = 10000;

    private final YSQLGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public YSQLSkipLockedOracle(YSQLGlobalState state) {
        this.state = state;
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addSubqueryErrors(errors);
        errors.add("canceling statement due to statement timeout");
    }

    // PostgreSQL row-level lock conflict table.
    static boolean conflicts(ForClause a, ForClause b) {
        if (a == ForClause.UPDATE || b == ForClause.UPDATE) {
            return true;
        }
        if (a == ForClause.KEY_SHARE || b == ForClause.KEY_SHARE) {
            return false;
        }
        // Remaining pairs are drawn from {NO KEY UPDATE, SHARE}; only SHARE/SHARE is compatible.
        return !(a == ForClause.SHARE && b == ForClause.SHARE);
    }

    @Override
    public void check() throws SQLException {
        List<YSQLTable> candidates = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView() && t.getTableType() == YSQLTable.TableType.STANDARD)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = Randomly.fromList(candidates);
        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(table.getColumns());
        String lockPredicate = YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        String readPredicate = Randomly.getBoolean() ? "TRUE"
                : YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        ForClause strengthA = ForClause.getRandom();
        ForClause strengthB = ForClause.getRandom();
        String isolationA = Randomly.fromOptions("READ COMMITTED", "REPEATABLE READ");
        String isolationB = Randomly.fromOptions("READ COMMITTED", "REPEATABLE READ");
        // YugabyteDB documents NOWAIT for READ COMMITTED requesters only.
        boolean nowaitSupported = "READ COMMITTED".equals(isolationB)
                && (!YugabyteBugs.bugNowaitAbortsRepeatableReadHolder || "READ COMMITTED".equals(isolationA));
        boolean nowait = nowaitSupported && Randomly.getBoolean();

        String from = " FROM ONLY " + table.getName();
        String lockQuery = "SELECT ybctid" + from + " WHERE " + lockPredicate + " FOR "
                + strengthA.getTextRepresentation();
        String allQuery = "SELECT ybctid" + from + " WHERE " + readPredicate;
        String lockedRead = allQuery + " FOR " + strengthB.getTextRepresentation()
                + (nowait ? " NOWAIT" : " SKIP LOCKED");

        List<String> log = new ArrayList<>();
        log.add("-- session A: BEGIN ISOLATION LEVEL " + isolationA + "; " + lockQuery + ";");
        log.add("-- session B: BEGIN ISOLATION LEVEL " + isolationB + "; " + allQuery + "; " + lockedRead + ";");
        state.getState().getLocalState().log(String.join("\n", log));

        try (Connection sessionA = openSession(); Connection sessionB = openSession()) {
            execute(sessionA, "BEGIN ISOLATION LEVEL " + isolationA);
            List<String> locked = query(sessionA, lockQuery);

            execute(sessionB, "SET statement_timeout = " + B_STATEMENT_TIMEOUT_MS);
            execute(sessionB, "BEGIN ISOLATION LEVEL " + isolationB);
            List<String> all = query(sessionB, allQuery);
            boolean conflict = conflicts(strengthA, strengthB);
            List<String> expected = new ArrayList<>(all);
            if (conflict) {
                expected.removeAll(locked);
            }
            boolean overlap = conflict && expected.size() != all.size();

            List<String> actual;
            try {
                actual = query(sessionB, lockedRead);
            } catch (SQLException e) {
                String msg = String.valueOf(e.getMessage());
                if (nowait && overlap && msg.contains("could not obtain lock on row")) {
                    ensureAlive(sessionA, locked, log);
                    return; // NOWAIT correctly refused a conflicting lock.
                }
                if (msg.contains("canceling statement due to statement timeout")) {
                    throw new AssertionError(mode(nowait) + " blocked for " + B_STATEMENT_TIMEOUT_MS
                            + " ms instead of returning immediately\n" + String.join("\n", log), e);
                }
                if (msg.contains("could not obtain lock on row")) {
                    throw new AssertionError(
                            "NOWAIT failed although no row of B conflicts with A's locks\n" + String.join("\n", log),
                            e);
                }
                handle(e);
                return;
            }
            if (nowait && overlap) {
                throw new AssertionError("NOWAIT returned " + actual.size() + " rows instead of failing; A holds "
                        + strengthA + " on " + locked.size() + " of them\n" + String.join("\n", log));
            }
            List<String> expectedSorted = new ArrayList<>(expected);
            List<String> actualSorted = new ArrayList<>(actual);
            Collections.sort(expectedSorted);
            Collections.sort(actualSorted);
            if (!expectedSorted.equals(actualSorted)) {
                throw new AssertionError(mode(nowait) + " returned " + actual.size() + " rows, expected "
                        + expected.size() + " (all=" + all.size() + ", locked by A=" + locked.size() + ", conflict="
                        + conflict + ")\n" + String.join("\n", log));
            }
            ensureAlive(sessionA, locked, log);
        } catch (SQLException e) {
            handle(e);
        }
    }

    private static String mode(boolean nowait) {
        return nowait ? "NOWAIT" : "SKIP LOCKED";
    }

    // A's transaction must survive B's non-blocking lock attempt.
    private void ensureAlive(Connection sessionA, List<String> locked, List<String> log) throws SQLException {
        try {
            execute(sessionA, "SELECT 1");
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("aborted by a conflict")) {
                throw new AssertionError("session B's non-blocking lock attempt aborted session A, which held "
                        + locked.size() + " row locks\n" + String.join("\n", log), e);
            }
            throw e;
        }
    }

    private void handle(SQLException e) {
        String msg = e.getMessage();
        if (msg != null && errors.errorIsExpected(msg)) {
            throw new IgnoreMeException();
        }
        throw new AssertionError(e);
    }

    private Connection openSession() throws SQLException {
        try (Statement s = state.getConnection().createStatement()) {
            String url = s.getConnection().getMetaData().getURL();
            return DriverManager.getConnection(url, state.getOptions().getUserName(), state.getOptions().getPassword());
        }
    }

    private static void execute(Connection con, String sql) throws SQLException {
        try (Statement s = con.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> query(Connection con, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
