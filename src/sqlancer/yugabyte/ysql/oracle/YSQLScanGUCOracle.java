package sqlancer.yugabyte.ysql.oracle;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTables;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLColumnValue;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLSelect;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Differential oracle that runs the same query twice, the second time with a result-preserving YugabyteDB planner GUC
 * flipped to a non-default value (scan-method, join-method, and DocDB pushdown toggles). These GUCs only change the
 * physical plan, never the logical result, so both runs must return identical (unordered) result sets. A mismatch
 * indicates a planner / pushdown wrong-results bug - the class of bug that has historically shipped in YugabyteDB's
 * batched-nested-loop join and bitmap-scan paths.
 */
public class YSQLScanGUCOracle implements TestOracle<YSQLGlobalState> {

    /**
     * Each entry is a set of {@code param=value} assignments applied together (e.g. YB bitmap scan needs both the YB
     * and the stock PostgreSQL toggle). All defaults below are result-preserving plan-only knobs.
     */
    private static final String[][] GUC_FLIPS = { //
            { "yb_enable_bitmapscan=true", "enable_bitmapscan=true" }, //
            { "enable_seqscan=off" }, //
            { "enable_indexscan=off", "enable_indexonlyscan=off" }, //
            { "enable_hashjoin=off" }, //
            { "enable_mergejoin=off" }, //
            { "enable_nestloop=off" }, //
            { "enable_material=off" }, //
            { "yb_enable_batchednl=off" }, //
            { "yb_prefer_bnl=off" }, //
            { "yb_bnl_batch_size=1" }, //
            { "yb_enable_expression_pushdown=off" }, //
            { "yb_enable_distinct_pushdown=off" }, //
            { "yb_enable_index_aggregate_pushdown=off" }, //
            { "yb_enable_hash_batch_in=off" }, //
    };

    private final YSQLGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private final ExpectedErrors gucErrors = gucExpectedErrors();

    public YSQLScanGUCOracle(YSQLGlobalState globalState) {
        this.state = globalState;
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addSubqueryErrors(errors);
    }

    private static ExpectedErrors gucExpectedErrors() {
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("unrecognized configuration parameter");
        errors.add("invalid value for parameter");
        errors.add("cannot be changed");
        errors.add("permission denied");
        errors.add("must be superuser");
        return errors;
    }

    @Override
    public void check() throws SQLException {
        YSQLTables targetTables = state.getSchema().getRandomTableNonEmptyTables();
        List<YSQLColumn> columns = targetTables.getColumns();
        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(columns);

        YSQLSelect select = new YSQLSelect();
        // Plain comma-joined tables only - no LATERAL/join clauses, which are unnecessary here and can render to
        // invalid SQL. The GUC flip is what we are exercising, not the join shape.
        select.setFromList(targetTables.getTables().stream()
                .map(t -> new YSQLSelect.YSQLFromTable(t, Randomly.getBoolean())).collect(Collectors.toList()));
        List<YSQLExpression> fetchColumns = Randomly.nonEmptySubset(columns).stream()
                .map(c -> (YSQLExpression) new YSQLColumnValue(c, null)).collect(Collectors.toList());
        select.setFetchColumns(fetchColumns);
        select.setSelectType(Randomly.getBoolean() ? YSQLSelect.SelectType.ALL : YSQLSelect.SelectType.DISTINCT);
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        }
        String queryString = YSQLVisitor.asString(select);

        List<String> defaultResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);

        String[] flip = Randomly.fromOptions(GUC_FLIPS);
        List<String> flippedResult;
        applyGuc(flip, true);
        try {
            flippedResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);
        } finally {
            applyGuc(flip, false);
        }

        List<String> combined = new ArrayList<>();
        for (String assignment : flip) {
            combined.add("SET " + assignment);
        }
        combined.add(queryString);
        ComparatorHelper.assumeResultSetsAreEqual(defaultResult, flippedResult, queryString, combined, state);
    }

    private void applyGuc(String[] flip, boolean set) throws SQLException {
        for (String assignment : flip) {
            String stmt;
            if (set) {
                stmt = "SET " + assignment;
            } else {
                String param = assignment.substring(0, assignment.indexOf('='));
                stmt = "RESET " + param;
            }
            boolean success = new SQLQueryAdapter(stmt, gucErrors).execute(state);
            if (set && !success) {
                // GUC unavailable on this build/version - the comparison would be meaningless, skip this round.
                throw new IgnoreMeException();
            }
        }
    }
}
