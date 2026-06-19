package sqlancer.yugabyte;

public final class YugabyteBugs {

    // https://github.com/yugabyte/yugabyte-db/issues/14330
    public static boolean bug14330 = true;

    // YCQL: a logical AND/OR whose operands are values (not conditions) aborts the tserver via a CHECK in
    // ql_expr.cc (EvalQLCondition) when evaluated in a value context such as a SELECT projection list or ORDER BY.
    // While unfixed, do not generate logical operators in value contexts so the fuzzer can explore past this crash.
    public static boolean bugYcqlLogicalInValueContext = true;

    private YugabyteBugs() {
    }

}
