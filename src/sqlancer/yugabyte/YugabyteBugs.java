package sqlancer.yugabyte;

public final class YugabyteBugs {

    // https://github.com/yugabyte/yugabyte-db/issues/14330
    public static boolean bug14330 = true;

    // YCQL: a logical AND/OR whose operands are values (not conditions) aborts the tserver via a CHECK in
    // ql_expr.cc (EvalQLCondition) when evaluated in a value context such as a SELECT projection list or ORDER BY.
    // While unfixed, do not generate logical operators in value contexts so the fuzzer can explore past this crash.
    public static boolean bugYcqlLogicalInValueContext = true;

    // YSQL: the optimizer estimates the selectivity of arbitrary boolean expressions coarsely and non-monotonically
    // (e.g. "A OR B" can be estimated below "A"), so the CERT oracle's predicate-selectivity mutations (WHERE/AND/OR)
    // produce false positives rather than real cardinality bugs. While this holds, YSQLCERTOracle keeps only the
    // structural mutations (DISTINCT/GROUP BY/HAVING/LIMIT); flip to false to restore predicate coverage once YB's
    // estimator is monotone.
    public static boolean cardinalityEstimatePredicateSelectivityUnstable = true;

    // YSQL: the planner raises the internal error "variable not found in subplan target list" for some correlated
    // subqueries over inheritance parents (e.g. DELETE FROM ONLY t WHERE NOT EXISTS (SELECT ... FROM p*, ONLY t, ...)).
    // Known YB bug; tolerate the error while unfixed and flip to false to start catching it once resolved.
    public static boolean bugPlannerVariableNotFoundInSubplan = true;

    // YSQL: EXTRACT(field FROM ts AT TIME ZONE '<named zone>') raises "time zone \"...\" not recognized" when
    // yb_enable_expression_pushdown=on because the DocDB tserver's postgres backend cannot locate share/timezone
    // (reproduces on production release tarballs; offset zones like '+00' are unaffected). Phorge D51850 /
    // yugabyte-db#30815 (closed); no longer reproduces on master, so the error is reported again.
    public static boolean bugTimezoneNotRecognizedUnderPushdown;

    // YSQL: yb_hash_code() allocates its key buffer with alloca(size) where size is the encoded size of its arguments,
    // so an argument larger than the ~8 MB backend stack (e.g. yb_hash_code(repeat('x', 10000000))) crashes the backend
    // with SIGSEGV. While unfixed, yb_hash_code only receives leaf arguments (constants or columns) so the fuzzer does
    // not re-hit this crash; flip to false once fixed. https://github.com/yugabyte/yugabyte-db/issues/34210
    public static boolean bugYbHashCodeUnboundedAlloca = true;

    // YSQL: a batched nested loop pushes "yb_hash_code(k) = ANY(ARRAY[...])" into the inner index scan, and the
    // executor rejects it with "indexqual doesn't have key on left side" (e.g. t1 LEFT JOIN t4 ON
    // yb_hash_code(t4.c0) = t1.c4). Tolerate the error while unfixed.
    // https://github.com/yugabyte/yugabyte-db/issues/34212
    public static boolean bugYbHashCodeSaopIndexQual = true;

    // YSQL: while a REPEATABLE READ transaction holds a row lock, a READ COMMITTED "SELECT ... FOR <strength> NOWAIT"
    // on a conflicting strength does not fail with "could not obtain lock on row". It aborts the holder ("expired or
    // aborted by a conflict") and returns the locked rows. Two READ COMMITTED sessions behave correctly. While this
    // holds, YSQLSkipLockedOracle checks NOWAIT only when both sessions are READ COMMITTED.
    public static boolean bugNowaitAbortsRepeatableReadHolder = true;

    // YSQL: "UPDATE t SET pk = pk" (a primary-key column set to its own value) takes no row lock. Another session's
    // FOR SHARE SKIP LOCKED returns the row, FOR SHARE NOWAIT does not fail, and a concurrent UPDATE of the row does
    // not
    // wait. A same-value update of a non-key column locks correctly; yb_skip_redundant_update_ops and
    // yb_update_optimization_infra do not change it. While this holds, YSQLSkipLockedOracle does not lock rows through
    // same-value updates of primary-key columns.
    public static boolean bugSameValuePrimaryKeyUpdateTakesNoLock = true;

    // YSQL: under SERIALIZABLE, "FOR <strength> SKIP LOCKED" and "NOWAIT" only raise a WARNING ("not supported yet for
    // SERIALIZABLE isolation") and then wait like a plain lock. While this holds, YSQLSkipLockedOracle does not run the
    // SKIP LOCKED / NOWAIT reader under SERIALIZABLE in YugabyteDB mode.
    // https://github.com/yugabyte/yugabyte-db/issues/11761
    public static boolean bugSkipLockedUnsupportedInSerializable = true;

    // YSQL: a row lock wait does not honor lock_timeout; "SET lock_timeout = '500ms'" then a conflicting
    // "SELECT ... FOR UPDATE" waits for the holder instead of failing with "canceling statement due to lock timeout",
    // at every isolation level. While this holds, YSQLSkipLockedOracle does not run its lock_timeout reader in
    // YugabyteDB mode.
    // https://github.com/yugabyte/yugabyte-db/issues/29549
    public static boolean bugRowLockIgnoresLockTimeout = true;

    // YSQL: a SERIALIZABLE transaction's reads lock coarsely (not just the rows read). After "SELECT ... WHERE k IN
    // (1, 2)" in a SERIALIZABLE transaction, another session's "FOR UPDATE SKIP LOCKED" skips every row of the table.
    // While this holds, YSQLSkipLockedOracle does not use SERIALIZABLE for the lock holder in YugabyteDB mode.
    // https://github.com/yugabyte/yugabyte-db/issues/9517
    public static boolean bugSerializableReadsLockCoarsely = true;

    // YSQL: on a partitioned table, "SELECT ... FOR UPDATE NOWAIT" on a row another transaction holds fails with
    // "could not serialize access ... (yb_max_query_layer_retries ... exhausted)" (40001) instead of "could not obtain
    // lock on row" (55P03) when that row is not in the last partition scanned. While this holds,
    // YSQLSkipLockedOracle does not use NOWAIT on its partitioned table in YugabyteDB mode.
    // https://github.com/yugabyte/yugabyte-db/issues/34844
    public static boolean bugNowaitOnPartitionedTableRetriesAsConflict = true;

    // YSQL: on a table with 2+ tablets, a row that "SELECT ... FOR NO KEY UPDATE SKIP LOCKED" skipped (another
    // transaction holds it FOR SHARE) stays invisible to a third session's "FOR SHARE SKIP LOCKED" until the skipping
    // transaction ends: the skipped request stays registered as a granted row lock. One tablet is correct.
    // While this holds, YSQLSkipLockedOracle lets its second consumer also skip the rows the first consumer skipped.
    // https://github.com/yugabyte/yugabyte-db/issues/34752
    public static boolean bugSkippedRowStaysLockedOnMultiTabletTable = true;

    // YSQL debug builds: "ALTER TABLE ONLY t DROP CONSTRAINT c" fails Assert(cmd->subtype != AT_DropConstraint) in
    // yb_cmds.c and crashes the backend. While this holds, ALTER TABLE does not combine ONLY with DROP CONSTRAINT on
    // servers with debug_assertions = on.
    // https://github.com/yugabyte/yugabyte-db/issues/26227
    public static boolean bugAlterTableOnlyDropConstraintAssert = true;

    // YSQL debug builds: an UPDATE that moves a primary key built on a stored generated column fails
    // Assert(skip_entities_initially_empty) in ybOptimizeModifyTable.c and crashes the backend. While this holds, the
    // hash-bucket table (generated column first in the primary key) is not created on servers with debug_assertions.
    // https://github.com/yugabyte/yugabyte-db/issues/33617
    public static boolean bugGeneratedPrimaryKeyUpdateAssert = true;

    // YSQL: yb_index_check() is marked PARALLEL SAFE, but in a parallel worker (for example with
    // force_parallel_mode = on, which the generators can set) it fails with "cannot update SecondarySnapshot during a
    // parallel operation". While this holds, YSQLIndexCheckGenerator expects that error.
    public static boolean bugIndexCheckFailsInParallelWorker = true;

    private YugabyteBugs() {
    }

}
