package sqlancer.yugabyte.ysql.oracle;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLGlobalState;
import sqlancer.common.DBMSCommon;
import sqlancer.common.oracle.CERTOracleBase;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryLogicalOperation;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryLogicalOperation.BinaryLogicalOperator;
import sqlancer.yugabyte.ysql.ast.YSQLColumnValue;
import sqlancer.yugabyte.ysql.ast.YSQLConstant;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLSelect;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Cardinality Estimation Restriction Testing (CERT) for YSQL. Generates a query, reads the optimizer's estimated row
 * count from EXPLAIN, then applies a single result-monotone mutation (e.g. dropping a WHERE clause should not decrease
 * the estimate, adding one should not increase it). If the plan shape is essentially unchanged but the estimate moves
 * the wrong direction, the cardinality estimator is inconsistent - the class of bug CERT targets.
 *
 * <p>
 * JOIN mutation is intentionally excluded: {@code YSQLJoin} does not expose ON-clause mutation, so we build plain
 * comma-joined FROM lists and mutate the other clauses (DISTINCT/WHERE/GROUP BY/HAVING/AND/OR/LIMIT).
 */
public class YSQLCERTOracle extends CERTOracleBase<YSQLGlobalState> implements TestOracle<YSQLGlobalState> {

    // YB clamps optimizer row estimates at 1e9 and uses coarse defaults; at/above this the monotonicity heuristic
    // does not hold, so such rounds are skipped.
    private static final int SATURATED_ESTIMATE = 100_000_000;

    private YSQLExpressionGenerator gen;
    private YSQLSelect select;

    public YSQLCERTOracle(YSQLGlobalState globalState) {
        super(globalState);
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addSubqueryErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        queryPlan1Sequences = new ArrayList<>();
        queryPlan2Sequences = new ArrayList<>();

        // Single table only: multi-table comma cross-joins make YB's estimate saturate at 1e9, which breaks the
        // monotonicity assumption and yields false positives. One relation keeps the estimate comparison meaningful.
        YSQLTable table = Randomly.fromList(state.getSchema().getRandomTableNonEmptyTables().getTables());
        List<YSQLColumn> columns = table.getColumns();
        gen = new YSQLExpressionGenerator(state).setColumns(columns);

        List<YSQLExpression> fromList = new ArrayList<>();
        fromList.add(new YSQLSelect.YSQLFromTable(table, Randomly.getBoolean()));
        List<YSQLExpression> fetchColumns = Randomly.nonEmptySubset(columns).stream()
                .map(c -> (YSQLExpression) new YSQLColumnValue(c, null)).collect(Collectors.toList());

        select = new YSQLSelect();
        select.setFetchColumns(fetchColumns);
        select.setFromList(fromList);
        select.setSelectType(YSQLSelect.SelectType.getRandom());
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        }
        if (Randomly.getBoolean()) {
            select.setGroupByExpressions(fetchColumns);
        }

        String queryString1 = YSQLVisitor.asString(select);
        int rowCount1 = getRow(state, queryString1, queryPlan1Sequences);

        // Exclude JOIN (YSQLJoin cannot safely swap ON clauses here) and the predicate-selectivity mutations
        // (WHERE/AND/OR): YB estimates the selectivity of arbitrary boolean expressions coarsely and
        // non-monotonically, so those mutations produce false positives rather than real cardinality bugs. Keep the
        // structural mutations (DISTINCT/GROUP BY/HAVING/LIMIT).
        boolean increase = mutate(Mutator.JOIN, Mutator.WHERE, Mutator.AND, Mutator.OR);

        String queryString2 = YSQLVisitor.asString(select);
        int rowCount2 = getRow(state, queryString2, queryPlan2Sequences);

        // Skip saturated/clamped estimates: YB emits a 1e9 ceiling and coarse defaults where monotonicity does not
        // hold, which would only produce false positives.
        if (rowCount1 >= SATURATED_ESTIMATE || rowCount2 >= SATURATED_ESTIMATE) {
            throw new IgnoreMeException();
        }

        // Only compare when the two plans are structurally near-identical, else the estimate change is expected.
        if (DBMSCommon.editDistance(queryPlan1Sequences, queryPlan2Sequences) > 1) {
            return;
        }

        if (increase && rowCount1 > rowCount2 + 1 || !increase && rowCount1 + 1 < rowCount2) {
            throw new AssertionError("Inconsistent cardinality estimate:\nEXPLAIN " + queryString1 + "; -- rows="
                    + rowCount1 + "\nEXPLAIN " + queryString2 + "; -- rows=" + rowCount2);
        }
    }

    @Override
    protected boolean mutateDistinct() {
        if (select.getSelectOption() != YSQLSelect.SelectType.ALL) {
            select.setSelectType(YSQLSelect.SelectType.ALL);
            return true;
        }
        select.setSelectType(YSQLSelect.SelectType.DISTINCT);
        return false;
    }

    @Override
    protected boolean mutateWhere() {
        boolean increase = select.getWhereClause() != null;
        if (increase) {
            select.setWhereClause(null);
        } else {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        }
        return increase;
    }

    @Override
    protected boolean mutateGroupBy() {
        boolean increase = !select.getGroupByExpressions().isEmpty();
        if (increase) {
            select.clearGroupByExpressions();
        } else {
            select.setGroupByExpressions(select.getFetchColumns());
        }
        return increase;
    }

    @Override
    protected boolean mutateHaving() {
        if (select.getGroupByExpressions().isEmpty()) {
            select.setGroupByExpressions(select.getFetchColumns());
            select.setHavingClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
            return false;
        }
        if (select.getHavingClause() == null) {
            select.setHavingClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
            return false;
        }
        select.setHavingClause(null);
        return true;
    }

    @Override
    protected boolean mutateAnd() {
        if (select.getWhereClause() == null) {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        } else {
            select.setWhereClause(new YSQLBinaryLogicalOperation(select.getWhereClause(),
                    gen.generateExpression(0, YSQLDataType.BOOLEAN), BinaryLogicalOperator.AND));
        }
        return false;
    }

    @Override
    protected boolean mutateOr() {
        if (select.getWhereClause() == null) {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
            return false;
        }
        select.setWhereClause(new YSQLBinaryLogicalOperation(select.getWhereClause(),
                gen.generateExpression(0, YSQLDataType.BOOLEAN), BinaryLogicalOperator.OR));
        return true;
    }

    @Override
    protected boolean mutateLimit() {
        boolean increase = select.getLimitClause() != null;
        if (increase) {
            select.setLimitClause(null);
        } else {
            select.setLimitClause(YSQLConstant.createIntConstant(Math.abs(state.getRandomly().getInteger())));
        }
        return increase;
    }

    private int getRow(SQLGlobalState<?, ?> globalState, String selectStr, List<String> queryPlanSequences)
            throws SQLException {
        int row = -1;
        String explainQuery = "EXPLAIN " + selectStr;
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(explainQuery);
            try {
                globalState.getLogger().getCurrentFileWriter().flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        SQLQueryAdapter q = new SQLQueryAdapter(explainQuery, errors);
        try (SQLancerResultSet rs = q.executeAndGet(globalState)) {
            if (rs == null) {
                throw new IgnoreMeException();
            }
            while (rs.next()) {
                String content = rs.getString(1).trim();
                if (row == -1 && content.contains("rows=")) {
                    try {
                        int ind = content.indexOf("rows=");
                        row = Integer.parseInt(content.substring(ind + 5).split(" ")[0]);
                    } catch (NumberFormatException e) {
                        // ignore an unparsable estimate and keep scanning
                    }
                }
                String[] planPart = content.split("-> ");
                queryPlanSequences.add(planPart[planPart.length - 1].split("  ")[0].trim());
            }
        } catch (IgnoreMeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(q.getQueryString(), e);
        }
        if (row == -1) {
            throw new IgnoreMeException();
        }
        return row;
    }
}
