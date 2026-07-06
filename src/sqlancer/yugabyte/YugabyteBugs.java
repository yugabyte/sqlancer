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

    private YugabyteBugs() {
    }

}
