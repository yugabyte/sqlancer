package sqlancer.yugabyte.ysql.oracle;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * Multi-session oracle for row-level lock conflicts with {@code SKIP LOCKED}, {@code NOWAIT} and a plain wait under
 * {@code lock_timeout}.
 *
 * <p>
 * Session A opens a transaction (any isolation level) and locks rows. The rows and the lock strengths it holds on each
 * are known exactly: they come back through the locking statement itself (the SELECT result or RETURNING). A locks
 * either a random user table (SELECT ... FOR, UPDATE ... SET c = c, DELETE) or one of the oracle's own tables in schema
 * {@code ysl} (a keyed table, a child with a foreign key to it, and a range-partitioned table), where it also locks
 * through a foreign-key check, INSERT ... ON CONFLICT DO UPDATE, MERGE, a CTE or a subquery, inside a savepoint that is
 * rolled back or released (including a lock upgrade in the savepoint), and optionally hands its locks to a prepared
 * transaction.
 * <ul>
 * <li>Session B reads the rows of a query Q ("all", ordered), then reads them again {@code FOR <strength> [OF t]} with
 * SKIP LOCKED, NOWAIT or a plain wait under lock_timeout, optionally as a queue ({@code ORDER BY ... LIMIT n}), through
 * a join, under forced scan types and YugabyteDB's row-locking batching knobs. SKIP LOCKED must return all minus the
 * rows on which A holds a conflicting strength (the first n of those, in order, under LIMIT). NOWAIT and the timed wait
 * must fail exactly when a row they must lock (all of Q, or the first n under LIMIT) is held with a conflicting
 * strength, and return that row list otherwise. None may block past the statement timeout or abort A.</li>
 * <li>For a SKIP LOCKED queue, session D then runs a queue read of its own while B still holds its rows: it must get
 * the first n rows that neither A nor B holds with a conflicting strength (two consumers never get the same row).</li>
 * <li>Session C runs {@code FOR UPDATE SKIP LOCKED} on every row A, B and D hold and must get none. After all sessions
 * roll back (A's prepared transaction included), C must get all of A's rows: the locks were released.</li>
 * </ul>
 * Rows are identified by the key in the oracle's own tables, otherwise by {@code tableoid} and {@code ybctid}
 * ({@code ctid} in PostgreSQL-compatible mode). YugabyteDB mode skips what YugabyteDB documents as unsupported or has
 * an open issue for (see YugabyteBugs): SKIP LOCKED / NOWAIT under SERIALIZABLE, NOWAIT outside READ COMMITTED, the
 * lock_timeout wait, MERGE and prepared transactions.
 */
public class YSQLSkipLockedOracle implements TestOracle<YSQLGlobalState> {

    private static final int STATEMENT_TIMEOUT_MS = 10000;
    private static final int LOCK_TIMEOUT_MS = 300;
    private static final int MAX_LOCKED_ROWS = 64;
    private static final int MAX_VERIFIED_ROWS = 256;
    private static final int SCRATCH_ROWS = 300;
    private static final String RU = "READ UNCOMMITTED";
    private static final String RC = "READ COMMITTED";
    private static final String RR = "REPEATABLE READ";
    private static final String SER = "SERIALIZABLE";
    private static final String LOCK_TIMEOUT_ERROR = "canceling statement due to lock timeout";

    private final YSQLGlobalState state;
    // Errors a generated predicate may raise; used for statements whose failure is not the property under test.
    private final ExpectedErrors predicateErrors = new ExpectedErrors();
    // Everything a lock-holder or baseline statement may legitimately raise.
    private final ExpectedErrors setupErrors = new ExpectedErrors();
    private boolean scratchReady;

    private enum Target {
        USER_TABLE, PARENT, PARTITIONED
    }

    private enum LockSource {
        SELECT_SUBSET, SELECT_PREDICATE, UPDATE_SAME_VALUE, DELETE, CTE, SUBQUERY, UPSERT, MERGE, FOREIGN_KEY, SAVEPOINT
    }

    private enum ReadShape {
        PLAIN, JOIN_OF, QUEUE_LIMIT
    }

    private enum Policy {
        SKIP_LOCKED(" SKIP LOCKED"), NOWAIT(" NOWAIT"), WAIT_TIMEOUT("");

        private final String sql;

        Policy(String sql) {
            this.sql = sql;
        }
    }

    // A relation under test: how B reads it, how rows are identified, and the strengths A holds on each row.
    private static final class Locks {
        String from;
        String alias;
        String id;
        String predicate;
        List<String> rows;
        final Map<String, Set<ForClause>> held = new HashMap<>();
        String preparedGid;

        void hold(Collection<String> lockedRows, ForClause strength) {
            for (String r : lockedRows) {
                held.computeIfAbsent(r, k -> EnumSet.noneOf(ForClause.class)).add(strength);
            }
        }
    }

    // How B reads: its isolation level, lock strength, wait policy, query shape and session settings.
    private static final class ReadPlan {
        String isolation;
        Policy policy;
        ForClause strength;
        ReadShape shape;
        Integer limit;
        List<String> settings;
    }

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
        setupErrors.add("prepared transactions are disabled");
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

    // Rows on which some held strength conflicts with the requested one.
    static Set<String> conflictingRows(Map<String, Set<ForClause>> held, ForClause requested) {
        return held.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(s -> conflicts(s, requested)))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    // Rows SKIP LOCKED must return: "all" (in Q order) minus the conflicting locked rows, cut to the limit.
    static List<String> expectedSkipLocked(List<String> allOrdered, Set<String> locked, boolean conflict,
            Integer limit) {
        List<String> available = allOrdered.stream().filter(r -> !conflict || !locked.contains(r))
                .collect(Collectors.toList());
        return limit == null ? available : available.subList(0, Math.min(limit, available.size()));
    }

    // Rows NOWAIT (or a timed wait) must lock: all of Q, or the first n under LIMIT (the lock node sits below it).
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
        boolean pg = state.isPgCompatible();
        String isolationA = !pg && YugabyteBugs.bugSerializableReadsLockCoarsely ? Randomly.fromOptions(RU, RC, RR)
                : Randomly.fromOptions(RU, RC, RR, SER);
        ReadPlan plan = new ReadPlan();
        plan.policy = pickPolicy(pg);
        plan.isolation = pickRequesterIsolation(pg, plan.policy, isolationA);
        plan.strength = ForClause.getRandom();
        plan.shape = Randomly.fromOptions(ReadShape.values());
        plan.limit = plan.shape == ReadShape.QUEUE_LIMIT ? (Integer) (int) Randomly.getNotCachedInteger(1, 6) : null;
        plan.settings = randomReadSettings();
        Target target = Randomly.fromOptions(Target.values());
        if (!pg && target == Target.PARTITIONED && plan.policy == Policy.NOWAIT
                && YugabyteBugs.bugNowaitOnPartitionedTableRetriesAsConflict) {
            plan.policy = Policy.SKIP_LOCKED;
        }

        List<String> log = new ArrayList<>();
        try (Connection a = openSession(); Connection b = openSession(); Connection c = openSession()) {
            execute(a, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "A");
            Locks locks = target == Target.USER_TABLE ? lockUserTable(a, isolationA, log)
                    : lockScratch(a, target, isolationA, log);
            try {
                runReaders(locks, plan, a, b, c, log);
            } finally {
                // A prepared transaction outlives its session; never leave one behind.
                if (locks.preparedGid != null) {
                    execute(a, "ROLLBACK PREPARED '" + locks.preparedGid + "'", log, "A");
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && setupErrors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw new AssertionError(e);
        }
    }

    // NOWAIT and the timed wait are left out where YugabyteDB does not support them (see the class comment).
    private static Policy pickPolicy(boolean pg) {
        Policy policy = Randomly.fromOptions(Policy.values());
        if (!pg && policy == Policy.WAIT_TIMEOUT && YugabyteBugs.bugRowLockIgnoresLockTimeout) {
            return Policy.SKIP_LOCKED;
        }
        return policy;
    }

    private static String pickRequesterIsolation(boolean pg, Policy policy, String isolationA) {
        if (pg) {
            return Randomly.fromOptions(RU, RC, RR, SER);
        }
        List<String> levels = new ArrayList<>(List.of(RU, RC, RR, SER));
        if (YugabyteBugs.bugSkipLockedUnsupportedInSerializable) {
            levels.remove(SER);
        }
        if (policy == Policy.NOWAIT) {
            // YugabyteDB documents NOWAIT for READ COMMITTED requesters (READ UNCOMMITTED maps to it).
            levels.retainAll(List.of(RU, RC));
            if (YugabyteBugs.bugNowaitAbortsRepeatableReadHolder && !RC.equals(isolationA) && !RU.equals(isolationA)) {
                throw new IgnoreMeException();
            }
        }
        return Randomly.fromList(levels);
    }

    // A random user table: SELECT ... FOR, a same-value UPDATE or a DELETE, identified by ybctid / ctid.
    private Locks lockUserTable(Connection a, String isolationA, List<String> log) throws SQLException {
        boolean pg = state.isPgCompatible();
        Locks locks = new Locks();
        YSQLTable table = null;
        for (YSQLTable candidate : candidateTables()) {
            List<String> rows = queryRaw(a,
                    "SELECT " + rowId(candidate.getName(), pg) + " FROM " + candidate.getName());
            if (!rows.isEmpty()) {
                table = candidate;
                locks.rows = rows;
                break;
            }
        }
        if (table == null) {
            throw new IgnoreMeException();
        }
        String t = table.getName();
        locks.from = t;
        locks.alias = t;
        locks.id = rowId(t, pg);
        log.add("-- A: SELECT " + locks.id + " FROM " + t + ";");
        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(table.getColumns());
        locks.predicate = Randomly.getBoolean() ? "TRUE"
                : YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        LockSource source = pickUserTableSource(a, table);
        List<String> subset = randomSubset(locks.rows);
        String inSubset = locks.id + " IN (" + quoted(subset) + ")";

        ForClause strengthA;
        String lockStatement;
        switch (source) {
        case SELECT_SUBSET:
            strengthA = ForClause.getRandom();
            lockStatement = "SELECT " + locks.id + " FROM " + t + " WHERE " + inSubset + " FOR "
                    + strengthA.getTextRepresentation();
            break;
        case SELECT_PREDICATE:
            strengthA = ForClause.getRandom();
            lockStatement = "SELECT " + locks.id + " FROM " + t + " WHERE "
                    + YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN)) + " FOR "
                    + strengthA.getTextRepresentation();
            break;
        case UPDATE_SAME_VALUE:
            Set<String> primaryKey = YugabyteBugs.bugSameValuePrimaryKeyUpdateTakesNoLock && !pg
                    ? indexColumns(a, t, "i.indisprimary") : Collections.emptySet();
            YSQLColumn col = Randomly.fromList(table.getColumns().stream()
                    .filter(x -> !x.isGenerated() && !primaryKey.contains(x.getName())).collect(Collectors.toList()));
            // PostgreSQL picks the row lock by whether key values change; a same-value UPDATE takes NO KEY UPDATE
            // even on a key column.
            strengthA = ForClause.NO_KEY_UPDATE;
            lockStatement = "UPDATE ONLY " + t + " SET " + col.getName() + " = " + col.getName() + " WHERE " + inSubset
                    + " RETURNING " + locks.id;
            break;
        case DELETE:
            strengthA = ForClause.UPDATE;
            lockStatement = "DELETE FROM ONLY " + t + " WHERE " + inSubset + " RETURNING " + locks.id;
            break;
        default:
            throw new AssertionError(source);
        }
        execute(a, "BEGIN ISOLATION LEVEL " + isolationA, log, "A");
        List<String> locked = query(a, lockStatement, log, "A");
        if (pg && source == LockSource.UPDATE_SAME_VALUE) {
            // PostgreSQL gives an updated row a new ctid, and other sessions still see the old one. With no
            // triggers or rules, the UPDATE locked exactly the targeted rows, so use those (by their old ctid).
            if (locked.size() != subset.size()) {
                throw new IgnoreMeException();
            }
            locked = subset;
        }
        locks.hold(locked, strengthA);
        return locks;
    }

    // The oracle's own tables in schema ysql: rows identified by key, every lock source available.
    private Locks lockScratch(Connection a, Target target, String isolationA, List<String> log) throws SQLException {
        boolean pg = state.isPgCompatible();
        ensureScratch(a);
        String table = target == Target.PARTITIONED ? "ysl.part" : "ysl.parent";
        Locks locks = new Locks();
        locks.from = table + " AS t";
        locks.alias = "t";
        locks.id = "t.k::text";
        locks.rows = queryRaw(a, "SELECT k::text FROM " + table);
        if (locks.rows.isEmpty()) {
            throw new IgnoreMeException();
        }
        locks.predicate = Randomly.fromOptions(
                "TRUE", "t.k % 3 = " + Randomly.getNotCachedInteger(0, 3), "t.k BETWEEN "
                        + Randomly.getNotCachedInteger(1, 150) + " AND " + Randomly.getNotCachedInteger(150, 300),
                "t.v > " + Randomly.getNotCachedInteger(0, 300));
        List<LockSource> sources = new ArrayList<>(
                List.of(LockSource.SELECT_SUBSET, LockSource.CTE, LockSource.SUBQUERY, LockSource.UPSERT,
                        LockSource.UPDATE_SAME_VALUE, LockSource.DELETE, LockSource.SAVEPOINT));
        if (target == Target.PARENT) {
            sources.add(LockSource.FOREIGN_KEY);
        }
        if (pg) {
            sources.add(LockSource.MERGE); // YugabyteDB does not support MERGE yet
        }
        LockSource source = Randomly.fromList(sources);
        execute(a, "BEGIN ISOLATION LEVEL " + isolationA, log, "A");
        if (source == LockSource.SAVEPOINT) {
            lockInSavepoint(a, table, locks, log);
        } else {
            List<String> subset = randomSubset(locks.rows);
            ForClause strength = ForClause.getRandom();
            locks.hold(query(a, scratchLockStatement(source, table, subset, strength), log, "A"),
                    scratchStrength(source, strength));
        }
        if (pg && Randomly.getBooleanWithRatherLowProbability()) {
            // The locks must survive PREPARE TRANSACTION and block others until ROLLBACK PREPARED.
            String gid = "ysl_" + state.getDatabaseName() + "_" + Randomly.getNotCachedInteger(0, 1000000000);
            execute(a, "PREPARE TRANSACTION '" + gid + "'", log, "A");
            locks.preparedGid = gid;
        }
        return locks;
    }

    private static String scratchLockStatement(LockSource source, String table, List<String> subset,
            ForClause strength) {
        String in = "k IN (" + String.join(", ", subset) + ")";
        String forClause = " FOR " + strength.getTextRepresentation();
        switch (source) {
        case SELECT_SUBSET:
            return "SELECT k::text FROM " + table + " WHERE " + in + forClause;
        case CTE:
            return "WITH x AS (SELECT k FROM " + table + " WHERE " + in + forClause + ") SELECT k::text FROM x";
        case SUBQUERY:
            return "SELECT k::text FROM (SELECT k FROM " + table + " WHERE " + in + forClause + ") AS sub";
        case UPSERT:
            return "INSERT INTO " + table + " SELECT k, v FROM " + table + " WHERE " + in
                    + " ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v RETURNING k::text";
        case MERGE:
            return "MERGE INTO " + table + " AS m USING (SELECT k FROM " + table + " WHERE " + in
                    + ") AS s ON m.k = s.k WHEN MATCHED THEN "
                    + (strength == ForClause.UPDATE ? "DELETE" : "UPDATE SET v = m.v") + " RETURNING m.k::text";
        case UPDATE_SAME_VALUE:
            return "UPDATE " + table + " SET v = v WHERE " + in + " RETURNING k::text";
        case DELETE:
            return "DELETE FROM " + table + " WHERE " + in + " RETURNING k::text";
        case FOREIGN_KEY:
            // The foreign-key check locks each referenced parent row FOR KEY SHARE.
            return "INSERT INTO ysl.child SELECT k, k FROM ysl.parent WHERE " + in + " RETURNING p::text";
        default:
            throw new AssertionError(source);
        }
    }

    // The strength a write takes: UPDATE / ON CONFLICT DO UPDATE / MERGE UPDATE of a non-key column takes NO KEY
    // UPDATE, DELETE takes UPDATE, a foreign-key check KEY SHARE.
    private static ForClause scratchStrength(LockSource source, ForClause strength) {
        switch (source) {
        case UPSERT:
        case UPDATE_SAME_VALUE:
            return ForClause.NO_KEY_UPDATE;
        case MERGE:
            return strength == ForClause.UPDATE ? ForClause.UPDATE : ForClause.NO_KEY_UPDATE;
        case DELETE:
            return ForClause.UPDATE;
        case FOREIGN_KEY:
            return ForClause.KEY_SHARE;
        default:
            return strength;
        }
    }

    // Locks taken in a subtransaction: a rolled-back savepoint releases them, a released one keeps them. Optionally
    // the rows are first locked outside it, so the savepoint upgrades (or repeats) a lock: after ROLLBACK TO only the
    // outer strength remains.
    private void lockInSavepoint(Connection a, String table, Locks locks, List<String> log) throws SQLException {
        List<String> subset = randomSubset(locks.rows);
        String in = "k IN (" + String.join(", ", subset) + ")";
        if (Randomly.getBoolean()) {
            ForClause outer = ForClause.getRandom();
            locks.hold(
                    query(a, "SELECT k::text FROM " + table + " WHERE " + in + " FOR " + outer.getTextRepresentation(),
                            log, "A"),
                    outer);
        }
        execute(a, "SAVEPOINT ysl_s", log, "A");
        ForClause inner = ForClause.getRandom();
        List<String> innerRows = query(a,
                "SELECT k::text FROM " + table + " WHERE " + in + " FOR " + inner.getTextRepresentation(), log, "A");
        if (Randomly.getBoolean()) {
            execute(a, "ROLLBACK TO SAVEPOINT ysl_s", log, "A");
        } else {
            execute(a, "RELEASE SAVEPOINT ysl_s", log, "A");
            locks.hold(innerRows, inner);
        }
    }

    // B, then D for a SKIP LOCKED queue, then C; finally everyone rolls back and C checks the release.
    private void runReaders(Locks locks, ReadPlan plan, Connection a, Connection b, Connection c, List<String> log)
            throws SQLException {
        Policy policy = plan.policy;
        ForClause strengthB = plan.strength;
        ReadShape shape = plan.shape;
        Integer limit = plan.limit;
        execute(b, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "B");
        for (String setting : plan.settings) {
            trySet(b, setting, log);
        }
        execute(b, "BEGIN ISOLATION LEVEL " + plan.isolation, log, "B");
        if (policy == Policy.WAIT_TIMEOUT) {
            execute(b, "SET LOCAL lock_timeout = '" + LOCK_TIMEOUT_MS + "ms'", log, "B");
        }
        String from = shape == ReadShape.JOIN_OF ? " FROM " + locks.from + " CROSS JOIN (VALUES (1)) AS yb_v(x)"
                : " FROM " + locks.from;
        String where = " WHERE " + locks.predicate;
        List<String> allOrdered = query(b, "SELECT " + locks.id + from + where + " ORDER BY " + locks.id, log, "B");
        String ofClause = shape == ReadShape.JOIN_OF || Randomly.getBooleanWithRatherLowProbability()
                ? " OF " + locks.alias : "";
        String lockedRead = "SELECT " + locks.id + from + where
                + (limit == null ? "" : " ORDER BY " + locks.id + " LIMIT " + limit) + " FOR "
                + strengthB.getTextRepresentation() + ofClause + policy.sql;
        Set<String> conflicting = conflictingRows(locks.held, strengthB);
        String context = "\nA holds " + locks.held + (locks.preparedGid == null ? "" : " (prepared)") + ", B="
                + strengthB + " " + policy + " " + shape + "\n" + String.join("\n", log) + "\n-- B: " + lockedRead
                + ";";

        List<String> bRows;
        try {
            bRows = queryRaw(b, lockedRead);
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage());
            boolean refused = policy == Policy.NOWAIT && msg.contains("could not obtain lock on row")
                    || policy == Policy.WAIT_TIMEOUT && msg.contains(LOCK_TIMEOUT_ERROR);
            if (refused) {
                if (!nowaitMustFail(allOrdered, conflicting, true, limit)) {
                    throw new AssertionError(policy + " failed although no row it must lock conflicts with A" + context,
                            e);
                }
                verifyStillLocked(c, locks, Collections.emptyList(), context);
                return;
            }
            if (predicateErrors.errorIsExpected(msg) && !isLockOrTransactionError(msg)) {
                throw new IgnoreMeException(); // the generated predicate failed under the locked plan
            }
            throw new AssertionError(policy + " read failed: " + msg + context, e);
        }
        String mismatch;
        if (policy == Policy.SKIP_LOCKED) {
            mismatch = compare(expectedSkipLocked(allOrdered, conflicting, true, limit), bRows, limit != null);
        } else {
            if (nowaitMustFail(allOrdered, conflicting, true, limit)) {
                throw new AssertionError(
                        policy + " returned " + bRows.size() + " rows instead of failing on a row A holds" + context);
            }
            mismatch = compare(nowaitTargets(allOrdered, limit), bRows, limit != null);
        }
        if (mismatch != null) {
            throw new AssertionError(policy + " " + mismatch + context);
        }
        try (Connection d = policy == Policy.SKIP_LOCKED && limit != null ? openSession() : null) {
            List<String> others = new ArrayList<>(bRows);
            if (d != null) {
                others.addAll(
                        secondConsumer(locks, d, bRows, strengthB, from + where, limit, allOrdered, log, context));
            }
            verifyStillLocked(c, locks, others, context);
            if (locks.preparedGid == null) {
                ensureAlive(a, context);
            }
            ensureAlive(b, context);
            execute(b, "ROLLBACK", log, "B");
            if (d != null) {
                execute(d, "ROLLBACK", log, "D");
            }
        }
        // Everyone rolls back: every row A held must be free again.
        if (locks.preparedGid != null) {
            execute(a, "ROLLBACK PREPARED '" + locks.preparedGid + "'", log, "A");
            locks.preparedGid = null;
        } else {
            execute(a, "ROLLBACK", log, "A");
        }
        verifyReleased(c, locks, context);
    }

    // After every session rolled back, C must lock all rows A held (they all exist again: A's writes rolled back).
    private static void verifyReleased(Connection c, Locks locks, String context) {
        if (locks.held.isEmpty()) {
            return;
        }
        List<String> probe = new ArrayList<>(locks.held.keySet());
        probe = probe.subList(0, Math.min(MAX_VERIFIED_ROWS, probe.size()));
        String mismatch = compare(probe, probeForUpdate(c, locks, probe, context), false);
        if (mismatch != null) {
            throw new AssertionError("locks were not released after rollback: " + mismatch + context);
        }
    }

    // Session D: a second queue consumer while B still holds its rows. It must skip everything A or B holds with a
    // conflicting strength, so the two consumers never get the same row.
    private List<String> secondConsumer(Locks locks, Connection d, List<String> bRows, ForClause strengthB,
            String fromWhere, int limit, List<String> allOrdered, List<String> log, String context)
            throws SQLException {
        Map<String, Set<ForClause>> held = new HashMap<>();
        locks.held.forEach((r, s) -> held.put(r, EnumSet.copyOf(s)));
        for (String r : bRows) {
            held.computeIfAbsent(r, k -> EnumSet.noneOf(ForClause.class)).add(strengthB);
        }
        ForClause strengthD = ForClause.getRandom();
        execute(d, "SET statement_timeout = " + STATEMENT_TIMEOUT_MS, log, "D");
        execute(d, "BEGIN ISOLATION LEVEL " + RC, log, "D");
        String read = "SELECT " + locks.id + fromWhere + " ORDER BY " + locks.id + " LIMIT " + limit + " FOR "
                + strengthD.getTextRepresentation() + " SKIP LOCKED";
        List<String> dRows;
        try {
            dRows = query(d, read, log, "D");
        } catch (SQLException e) {
            throw new AssertionError(
                    "second SKIP LOCKED consumer failed: " + e.getMessage() + context + "\n-- D: " + read + ";", e);
        }
        Set<String> skipped = new HashSet<>(conflictingRows(held, strengthD));
        if (!state.isPgCompatible() && YugabyteBugs.bugSkippedRowStaysLockedOnMultiTabletTable) {
            conflictingRows(locks.held, strengthB).stream().filter(r -> !dRows.contains(r)).forEach(skipped::add);
        }
        String mismatch = compare(expectedSkipLocked(allOrdered, skipped, true, limit), dRows, true);
        if (mismatch != null) {
            throw new AssertionError(
                    "second SKIP LOCKED consumer (" + strengthD + ") " + mismatch + context + "\n-- D: " + read + ";");
        }
        return dRows;
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

    // Tables only this oracle uses, outside the public schema the other generators work on.
    private void ensureScratch(Connection a) throws SQLException {
        if (scratchReady) {
            return;
        }
        try (Statement s = a.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS ysl");
            s.execute("CREATE TABLE IF NOT EXISTS ysl.parent (k int PRIMARY KEY, v int)");
            s.execute("CREATE TABLE IF NOT EXISTS ysl.child (id int, p int REFERENCES ysl.parent (k))");
            s.execute("CREATE TABLE IF NOT EXISTS ysl.part (k int PRIMARY KEY, v int) PARTITION BY RANGE (k)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS ysl.part_1 PARTITION OF ysl.part FOR VALUES FROM (MINVALUE) TO (100)");
            s.execute("CREATE TABLE IF NOT EXISTS ysl.part_2 PARTITION OF ysl.part FOR VALUES FROM (100) TO (200)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS ysl.part_3 PARTITION OF ysl.part FOR VALUES FROM (200) TO (MAXVALUE)");
            s.execute("INSERT INTO ysl.parent SELECT g, g FROM generate_series(1, " + SCRATCH_ROWS
                    + ") g ON CONFLICT DO NOTHING");
            s.execute("INSERT INTO ysl.part SELECT g, g FROM generate_series(1, " + SCRATCH_ROWS
                    + ") g ON CONFLICT DO NOTHING");
        }
        scratchReady = true;
    }

    // Row identity across inheritance children and partitions: ybctid in YugabyteDB, ctid in PostgreSQL.
    private static String rowId(String t, boolean pgCompatible) {
        return "(" + t + ".tableoid::regclass::text || ':' || " + t + (pgCompatible ? ".ctid" : ".ybctid") + "::text)";
    }

    private static List<String> randomSubset(List<String> rows) {
        List<String> subset = Randomly.nonEmptySubset(rows);
        return subset.subList(0, Math.min(MAX_LOCKED_ROWS, subset.size()));
    }

    private static String quoted(Collection<String> rows) {
        return rows.stream().map(r -> "'" + r.replace("'", "''") + "'").collect(Collectors.joining(", "));
    }

    // UPDATE and DELETE lock exactly their RETURNING rows only when nothing else writes on their behalf: no user
    // triggers, no rewrite rules, and no foreign keys that reference the table (cascades).
    private LockSource pickUserTableSource(Connection a, YSQLTable table) throws SQLException {
        LockSource source = Randomly.fromOptions(LockSource.SELECT_SUBSET, LockSource.SELECT_PREDICATE,
                LockSource.UPDATE_SAME_VALUE, LockSource.DELETE);
        if (source == LockSource.SELECT_SUBSET || source == LockSource.SELECT_PREDICATE) {
            return source;
        }
        Set<String> primaryKey = YugabyteBugs.bugSameValuePrimaryKeyUpdateTakesNoLock && !state.isPgCompatible()
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

    // Session C: every row A holds, or another reader reported, must still be locked against FOR UPDATE.
    private static void verifyStillLocked(Connection c, Locks locks, Collection<String> otherRows, String context) {
        Set<String> rows = new LinkedHashSet<>(locks.held.keySet());
        rows.addAll(otherRows);
        if (rows.isEmpty()) {
            return;
        }
        List<String> got = probeForUpdate(c, locks, rows, context);
        if (!got.isEmpty()) {
            throw new AssertionError(
                    "session C locked " + got.size() + " rows that A, B or D should still hold: " + got + context);
        }
    }

    private static List<String> probeForUpdate(Connection c, Locks locks, Collection<String> rows, String context) {
        List<String> probe = new ArrayList<>(rows).subList(0, Math.min(MAX_VERIFIED_ROWS, rows.size()));
        String sql = "SELECT " + locks.id + " FROM " + locks.from + " WHERE " + locks.id + " IN (" + quoted(probe)
                + ") FOR UPDATE SKIP LOCKED";
        try (Statement s = c.createStatement()) {
            s.execute("SET statement_timeout = " + STATEMENT_TIMEOUT_MS);
            return queryRaw(c, sql);
        } catch (SQLException e) {
            throw new AssertionError(
                    "session C lock probe failed: " + e.getMessage() + context + "\n-- C: " + sql + ";", e);
        }
    }

    // A session's transaction must survive another session's non-blocking lock attempt.
    private static void ensureAlive(Connection session, String context) {
        if (session == null) {
            return;
        }
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
