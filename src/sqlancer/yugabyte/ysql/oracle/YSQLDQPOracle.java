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
 * Differential Query Plan (DQP) oracle: runs one generated query under a series of plan-forcing configurations - each
 * scan method, each join method, and DocDB pushdown / cost-model toggles. Every forced plan must return the same rows;
 * a mismatch is a plan-dependent wrong-result bug. Complements SCAN_GUC (one GUC flip per round) by asserting result
 * stability across many plans for the same query.
 */
public class YSQLDQPOracle implements TestOracle<YSQLGlobalState> {

    // Each entry forces a different physical plan; all are result-invariant, so every one must match the default run.
    private static final String[][] PLAN_CONFIGS = { //
            { "enable_seqscan=off" }, //
            { "enable_indexscan=off", "enable_indexonlyscan=off" }, //
            { "enable_bitmapscan=off" }, //
            { "enable_nestloop=off", "enable_mergejoin=off" }, // force hash join
            { "enable_hashjoin=off", "enable_mergejoin=off" }, // force nested loop
            { "enable_hashjoin=off", "enable_nestloop=off" }, // force merge join
            { "enable_material=off" }, //
            { "yb_enable_expression_pushdown=off" }, //
            { "yb_enable_batchednl=off" }, //
            { "yb_bnl_batch_size=1" }, //
            { "yb_enable_cbo=off" }, //
            { "yb_enable_cbo=on" }, //
    };

    private final YSQLGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private final ExpectedErrors gucErrors = new ExpectedErrors();

    public YSQLDQPOracle(YSQLGlobalState globalState) {
        this.state = globalState;
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        // Generated predicates may embed scalar subqueries that return >1 row ("more than one row returned by a
        // subquery used as an expression") - a legitimate evaluation error, not a plan-dependent result mismatch.
        YSQLErrors.addSubqueryErrors(errors);
        gucErrors.add("unrecognized configuration parameter");
        gucErrors.add("invalid value for parameter");
        gucErrors.add("cannot be changed");
        gucErrors.add("permission denied");
        gucErrors.add("must be superuser");
    }

    @Override
    public void check() throws SQLException {
        YSQLTables targetTables = state.getSchema().getRandomTableNonEmptyTables();
        List<YSQLColumn> columns = targetTables.getColumns();
        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(columns);

        YSQLSelect select = new YSQLSelect();
        // Plain comma-joined tables - the GUC-forced plan is what we exercise, not the join shape.
        select.setFromList(targetTables.getTables().stream()
                .map(t -> new YSQLSelect.YSQLFromTable(t, Randomly.getBoolean())).collect(Collectors.toList()));
        List<YSQLExpression> fetchColumns = Randomly.nonEmptySubset(columns).stream()
                .map(c -> (YSQLExpression) new YSQLColumnValue(c, null)).collect(Collectors.toList());
        select.setFetchColumns(fetchColumns);
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        }
        String queryString = YSQLVisitor.asString(select);

        List<String> defaultResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);

        for (String[] config : PLAN_CONFIGS) {
            applyGuc(config, true);
            List<String> planResult;
            try {
                planResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);
            } finally {
                applyGuc(config, false);
            }
            List<String> combined = new ArrayList<>();
            for (String assignment : config) {
                combined.add("SET " + assignment);
            }
            combined.add(queryString);
            ComparatorHelper.assumeResultSetsAreEqual(defaultResult, planResult, queryString, combined, state,
                    ComparatorHelper::canonicalizeResultValue);
        }
    }

    private void applyGuc(String[] config, boolean set) throws SQLException {
        for (String assignment : config) {
            String stmt;
            if (set) {
                stmt = "SET " + assignment;
            } else {
                stmt = "RESET " + assignment.substring(0, assignment.indexOf('='));
            }
            boolean success = new SQLQueryAdapter(stmt, gucErrors).execute(state);
            if (set && !success) {
                // GUC unavailable on this build/version - the comparison would be meaningless, skip this round.
                throw new IgnoreMeException();
            }
        }
    }
}
