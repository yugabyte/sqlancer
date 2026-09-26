package sqlancer.yugabyte.ysql.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.yugabyte.YugabyteBugs;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLSelect.ForClause;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Three-session oracle for row-level lock conflicts with {@code SKIP LOCKED} and {@code NOWAIT}.
 *
 * <p>
 * Session A opens a transaction and locks rows of {@code t}, by one of: {@code SELECT ... FOR <strength>} on an
 * explicit subset of real rows, the same on a random predicate, {@code UPDATE ... SET c = c} (FOR UPDATE strength when
 * c is a key column, FOR NO KEY UPDATE otherwise) or {@code DELETE}. The rows A really locked come back through the
 * statement itself (the SELECT result or RETURNING). While A holds them, session B reads the rows that satisfy Q
 * ("all", ordered), then reads them again {@code FOR <strength> [OF t] SKIP LOCKED | NOWAIT}, optionally as a queue
 * ({@code ORDER BY ... LIMIT n}), through a join, under forced index or sequential scans, and under YugabyteDB's
 * explicit-row-locking batching knobs.
 * <ul>
 * <li>SKIP LOCKED must return all minus A's locked rows when the strengths conflict, and all otherwise. Under LIMIT n
 * it must return the first n of that set in order.</li>
 * <li>NOWAIT must fail with "could not obtain lock on row" exactly when a row it must lock (all of Q, or the first n
 * under LIMIT) is locked by A with a conflicting strength, and must return that row list otherwise.</li>
 * <li>Neither may block (statement timeout), fail with any lock or transaction error, or abort A.</li>
 * <li>Session C then runs {@code FOR UPDATE SKIP LOCKED} on the union of A's and B's rows. Both still hold locks that
 * conflict with FOR UPDATE, so C must get no rows.</li>
 * </ul>
 * Rows are identified by {@code tableoid} and {@code ybctid}. SERIALIZABLE is excluded, and NOWAIT is checked only for
 * READ COMMITTED requesters, because YugabyteDB supports neither policy otherwise.
 */
public class YSQLSkipLockedOracle implements TestOracle<YSQLGlobalState> {

    private static final int STATEMENT_TIMEOUT_MS = 10000;
    private static final int MAX_LOCKED_ROWS = 64;
    private static final int MAX_VERIFIED_ROWS = 256;
    private static final String RC = "READ COMMITTED";
    private static final String RR = "REPEATABLE READ";
    // Columns of non-partial, non-expression, immediate unique indexes: PostgreSQL's "key" columns for row locks.
    private static final String KEY_INDEX = "i.indisunique AND i.indimmediate AND i.indpred IS NULL"
            + " AND i.indexprs IS NULL";

    private enum LockSource {
        SELECT_SUBSET, SELECT_PREDICATE, UPDATE_SAME_VALUE, DELETE
    }

    private enum ReadShape {
        PLAIN, JOIN_OF, QUEUE_LIMIT
    }

    private final YSQLGlobalState state;
    // Errors a generated predicate may raise; used for statements whose failure is not the property under test.
    private final ExpectedErrors predicateErrors = new ExpectedErrors();
    // Everything a lock-holder or baseline statement may legitimately raise.
    private final ExpectedErrors setupErrors = new ExpectedErrors();

    public YSQLSkipLockedOracle(YSQLGlobalState state) {
        this.state = state;
        YSQLErrors.addCommonExpressionErrors(predicateErrors);
        YSQLErrors.addSubqueryErrors(predicateErrors);
        YSQLErrors.addCommonExpressionErrors(setupErrors);
        YSQLErrors.addSubqueryErrors(setupErrors);
        YSQLErrors.addCommonFetchErrors(setupErrors);
        YSQLErrors.addTransactionErrors(setupErrors);
        YSQLErrors.addCommonInsertUpdateErrors(setupErrors);
        setupErrors.add("canceling statement due to statement timeout");
        setupErrors.add("can only be updated to DEFAULT");
        setupErrors.add("cannot update column");
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

    // Rows SKIP LOCKED must return: "all" (in Q order) minus the conflicting locked rows, cut to the limit.
    static List<String> expectedSkipLocked(List<String> allOrdered, Set<String> locked, boolean conflict,
            Integer limit) {
        List<String> available = allOrdered.stream().filter(r -> !conflict || !locked.contains(r))
                .collect(Collectors.toList());
        return limit == null ? available : available.subList(0, Math.min(limit, available.size()));
    }

    // Rows NOWAIT must lock: all of Q, or the first n under LIMIT (the lock node sits below the limit).
    static List<String> nowaitTargets(List<String> allOrdered, Integer limit) {
        return limit == null ? allOrdered : allOrdered.subList(0, Math.min(limit, allOrdered.size()));
    }

    static boolean nowaitMustFail(List<String> allOrdered, Set<String> locked, boolean conflict, Integer limit) {
        return conflict && nowaitTargets(allOrdered, limit).stream().anyMatch(locked::contains);
    }

    // Unordered comparison unless the read is ordered (LIMIT); returns null when equal, else a description.
    static String compare(List<String> expected, List<String> actual, boolean ordered) {
        List<String> e = new ArrayList<>(expected);
        List<String> a = new ArrayList<>(actual);
        if (!ordered) {
            Collections.sort(e);
            Collections.sort(a);
        }
        if (e.equals(a)) {
            return null;
        }
        Set<String> missing = new LinkedHashSet<>(e);
        missing.removeAll(a);
        Set<String> extra = new LinkedHashSet<>(a);
        extra.removeAll(e);
        return "expected " + e.size() + " rows, got " + a.size() + (ordered ? " (ordered)" : "") + "; missing "
                + missing + "; unexpected " + extra;
    }

    @Override
    public void check() throws SQLException {
        List<YSQLTable> candidates = candidateTables();
        String isolationA = Randomly.fromOptions(RC, RR);
        String isolationB = Randomly.fromOptions(RC, RR);
        boolean nowaitSupported = RC.equals(isolationB)
                && (!YugabyteBugs.bugNowaitAbortsRepeatableReadHolder || RC.equals(isolationA));
        boolean nowait = nowaitSupported && Randomly.getBoolean();
        ForClause strengthB = ForClause.getRandom();
        ReadShape shape = Randomly.fromOptions(ReadShape.values());
        Integer limit = shape == ReadShape.QUEUE_LIMIT ? (Integer) (int) Randomly.getNotCachedInteger(1, 6) : null;
        List<String> bSettings = randomReadSettings();

        List<String> log = new ArrayList<>();
        try (Connection a = openSession(); Connection b = openSession(); Connection c = openSession()) {
            execute(a, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "A");
            // Only a table with rows can show a lock conflict; take the first non-empty one in random order.
            YSQLTable table = null;
            List<String> allRows = Collections.emptyList();
            for (YSQLTable candidate : candidates) {
                allRows = queryRaw(a, "SELECT " + rowId(candidate.getName()) + " FROM " + candidate.getName());
                if (!allRows.isEmpty()) {
                    table = candidate;
                    break;
                }
            }
            if (table == null) {
                throw new IgnoreMeException();
            }
            String t = table.getName();
            String id = rowId(t);
            log.add("-- A: SELECT " + id + " FROM " + t + ";");
            YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(table.getColumns());
            String readPredicate = Randomly.getBoolean() ? "TRUE"
                    : YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN));
            LockSource source = pickLockSource(a, table);
            List<String> subset = Randomly.nonEmptySubset(allRows);
            subset = subset.subList(0, Math.min(MAX_LOCKED_ROWS, subset.size()));
            String inSubset = id + " IN ("
                    + subset.stream().map(r -> "'" + r.replace("'", "''") + "'").collect(Collectors.joining(", "))
                    + ")";

            ForClause strengthA;
            String lockStatement;
            switch (source) {
            case SELECT_SUBSET:
                strengthA = ForClause.getRandom();
                lockStatement = "SELECT " + id + " FROM " + t + " WHERE " + inSubset + " FOR "
                        + strengthA.getTextRepresentation();
                break;
            case SELECT_PREDICATE:
                strengthA = ForClause.getRandom();
                lockStatement = "SELECT " + id + " FROM " + t + " WHERE "
                        + YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN)) + " FOR "
                        + strengthA.getTextRepresentation();
                break;
            case UPDATE_SAME_VALUE:
                Set<String> primaryKey = YugabyteBugs.bugSameValuePrimaryKeyUpdateTakesNoLock
                        ? indexColumns(a, t, "i.indisprimary") : Collections.emptySet();
                YSQLColumn col = Randomly.fromList(
                        table.getColumns().stream().filter(x -> !x.isGenerated() && !primaryKey.contains(x.getName()))
                                .collect(Collectors.toList()));
                strengthA = indexColumns(a, t, KEY_INDEX).contains(col.getName()) ? ForClause.UPDATE
                        : ForClause.NO_KEY_UPDATE;
                lockStatement = "UPDATE ONLY " + t + " SET " + col.getName() + " = " + col.getName() + " WHERE "
                        + inSubset + " RETURNING " + id;
                break;
            case DELETE:
                strengthA = ForClause.UPDATE;
                lockStatement = "DELETE FROM ONLY " + t + " WHERE " + inSubset + " RETURNING " + id;
                break;
            default:
                throw new AssertionError(source);
            }
            execute(a, "BEGIN ISOLATION LEVEL " + isolationA, log, "A");
            Set<String> locked = new HashSet<>(query(a, lockStatement, log, "A"));

            execute(b, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "B");
            for (String setting : bSettings) {
                trySet(b, setting, log);
            }
            execute(b, "BEGIN ISOLATION LEVEL " + isolationB, log, "B");
            String where = " WHERE " + readPredicate;
            String from = shape == ReadShape.JOIN_OF ? " FROM " + t + " CROSS JOIN (VALUES (1)) AS yb_v(x)"
                    : " FROM " + t;
            List<String> allOrdered = query(b, "SELECT " + id + from + where + " ORDER BY " + id, log, "B");
            String lockedRead = "SELECT " + id + from + where
                    + (limit == null ? "" : " ORDER BY " + id + " LIMIT " + limit) + " FOR "
                    + strengthB.getTextRepresentation()
                    + (shape == ReadShape.JOIN_OF || Randomly.getBooleanWithRatherLowProbability() ? " OF " + t : "")
                    + (nowait ? " NOWAIT" : " SKIP LOCKED");
            boolean conflict = conflicts(strengthA, strengthB);
            String context = "\nA=" + source + " " + strengthA + " (" + locked.size() + " rows), B=" + strengthB + " "
                    + (nowait ? "NOWAIT" : "SKIP LOCKED") + " " + shape + ", conflict=" + conflict + "\n"
                    + String.join("\n", log) + "\n-- B: " + lockedRead + ";";

            List<String> bRows;
            try {
                bRows = queryRaw(b, lockedRead);
            } catch (SQLException e) {
                String msg = String.valueOf(e.getMessage());
                if (nowait && msg.contains("could not obtain lock on row")) {
                    if (!nowaitMustFail(allOrdered, locked, conflict, limit)) {
                        throw new AssertionError(
                                "NOWAIT failed although no row it must lock conflicts with A" + context, e);
                    }
                    verifyStillLocked(c, id, t, locked, Collections.emptyList(), context);
                    ensureAlive(a, context);
                    return;
                }
                if (predicateErrors.errorIsExpected(msg) && !isLockOrTransactionError(msg)) {
                    throw new IgnoreMeException(); // the generated predicate failed under the locked plan
                }
                throw new AssertionError((nowait ? "NOWAIT" : "SKIP LOCKED") + " read failed: " + msg + context, e);
            }
            String mismatch;
            if (nowait) {
                if (nowaitMustFail(allOrdered, locked, conflict, limit)) {
                    throw new AssertionError(
                            "NOWAIT returned " + bRows.size() + " rows instead of failing on a row A holds" + context);
                }
                mismatch = compare(nowaitTargets(allOrdered, limit), bRows, limit != null);
            } else {
                mismatch = compare(expectedSkipLocked(allOrdered, locked, conflict, limit), bRows, limit != null);
            }
            if (mismatch != null) {
                throw new AssertionError((nowait ? "NOWAIT" : "SKIP LOCKED") + " " + mismatch + context);
            }
            verifyStillLocked(c, id, t, locked, bRows, context);
            ensureAlive(a, context);
            ensureAlive(b, context);
        } catch (SQLException e) {
            if (e.getMessage() != null && setupErrors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw new AssertionError(e);
        }
    }

    private List<YSQLTable> candidateTables() {
        List<YSQLTable> candidates = state.getSchema().getDatabaseTables().stream()
                .filter(x -> !x.isView() && x.getTableType() == YSQLTable.TableType.STANDARD)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw new IgnoreMeException();
        }
        Collections.shuffle(candidates, new java.util.Random(state.getRandomly().getInteger()));
        return candidates;
    }

    private static String rowId(String t) {
        return "(" + t + ".tableoid::regclass::text || ':' || " + t + ".ybctid::text)";
    }

    // UPDATE and DELETE lock exactly their RETURNING rows only when nothing else writes on their behalf: no user
    // triggers, no rewrite rules, and no foreign keys that reference the table (cascades).
    private LockSource pickLockSource(Connection a, YSQLTable table) throws SQLException {
        LockSource source = Randomly.fromOptions(LockSource.values());
        if (source == LockSource.SELECT_SUBSET || source == LockSource.SELECT_PREDICATE) {
            return source;
        }
        Set<String> primaryKey = YugabyteBugs.bugSameValuePrimaryKeyUpdateTakesNoLock
                ? indexColumns(a, table.getName(), "i.indisprimary") : Collections.emptySet();
        boolean writable = source == LockSource.DELETE
                || table.getColumns().stream().anyMatch(x -> !x.isGenerated() && !primaryKey.contains(x.getName()));
        String reg = "'" + table.getName() + "'::regclass";
        List<String> side = queryRaw(a,
                "SELECT (SELECT count(*) FROM pg_trigger WHERE tgrelid = " + reg + " AND NOT tgisinternal)"
                        + " + (SELECT count(*) FROM pg_rewrite WHERE ev_class = " + reg + " AND rulename <> '_RETURN')"
                        + " + (SELECT count(*) FROM pg_constraint WHERE confrelid = " + reg + ")");
        return writable && "0".equals(side.get(0)) ? source : LockSource.SELECT_SUBSET;
    }

    private static Set<String> indexColumns(Connection a, String table, String indexFilter) throws SQLException {
        return new HashSet<>(queryRaw(a,
                "SELECT DISTINCT att.attname FROM pg_index i JOIN pg_attribute att"
                        + " ON att.attrelid = i.indrelid AND att.attnum = ANY (i.indkey) WHERE i.indrelid = '" + table
                        + "'::regclass AND " + indexFilter));
    }

    // Plan and batching settings for the locked read; unknown parameters (older builds) are skipped.
    private static List<String> randomReadSettings() {
        List<String> settings = new ArrayList<>();
        if (Randomly.getBoolean()) {
            settings.add(Randomly.fromOptions("enable_seqscan = off", "enable_indexscan = off",
                    "enable_indexonlyscan = off", "enable_bitmapscan = off"));
        }
        if (Randomly.getBoolean()) {
            settings.add("yb_explicit_row_locking_batch_size = " + Randomly.fromOptions(1, 2, 3, 16, 1024));
        }
        if (Randomly.getBoolean()) {
            settings.add("yb_explicit_row_lock_skip_locked_max_read_ahead = " + Randomly.fromOptions(1, 2, 5, 64));
        }
        return settings;
    }

    private static boolean isLockOrTransactionError(String msg) {
        String m = msg.toLowerCase();
        return m.contains("obtain lock") || m.contains("deadlock") || m.contains("lock timeout")
                || m.contains("conflict") || m.contains("serializ") || m.contains("restart read")
                || m.contains("aborted") || m.contains("expired") || m.contains("try again")
                || m.contains("statement timeout");
    }

    // Session C: every row A or B reported as locked must still be locked against FOR UPDATE.
    private void verifyStillLocked(Connection c, String id, String t, Collection<String> aRows,
            Collection<String> bRows, String context) {
        Set<String> rows = new LinkedHashSet<>(aRows);
        rows.addAll(bRows);
        if (rows.isEmpty()) {
            return;
        }
        List<String> probe = new ArrayList<>(rows).subList(0, Math.min(MAX_VERIFIED_ROWS, rows.size()));
        String sql = "SELECT " + id + " FROM " + t + " WHERE " + id + " IN ("
                + probe.stream().map(r -> "'" + r.replace("'", "''") + "'").collect(Collectors.joining(", "))
                + ") FOR UPDATE SKIP LOCKED";
        List<String> got;
        try (Statement s = c.createStatement()) {
            s.execute("SET statement_timeout = " + STATEMENT_TIMEOUT_MS);
            got = queryRaw(c, sql);
        } catch (SQLException e) {
            throw new AssertionError(
                    "session C lock probe failed: " + e.getMessage() + context + "\n-- C: " + sql + ";", e);
        }
        if (!got.isEmpty()) {
            throw new AssertionError("session C locked " + got.size() + " rows that A or B should still hold: " + got
                    + context + "\n-- C: " + sql + ";");
        }
    }

    // A session's transaction must survive another session's non-blocking lock attempt.
    private static void ensureAlive(Connection session, String context) {
        try (Statement s = session.createStatement()) {
            s.execute("SELECT 1");
        } catch (SQLException e) {
            throw new AssertionError("a lock-holding transaction was aborted: " + e.getMessage() + context, e);
        }
    }

    private static void trySet(Connection b, String setting, List<String> log) {
        try (Statement s = b.createStatement()) {
            s.execute("SET " + setting);
            log.add("-- B: SET " + setting + ";");
        } catch (SQLException e) {
            // Parameter absent on this build; the read runs without it.
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
        return queryRaw(con, sql);
    }

    private static List<String> queryRaw(Connection con, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
