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
    // yugabyte-db#30815; flip to false once shipped to start catching regressions and similar-class bugs.
    public static boolean bugTimezoneNotRecognizedUnderPushdown = true;

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

    private YugabyteBugs() {
    }

}
