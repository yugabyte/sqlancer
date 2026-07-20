package sqlancer.yugabyte.ysql.oracle;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Constant-folding differential oracle (a simplified CODDTest). Generates a column-independent boolean expression E,
 * evaluates it once via "SELECT (E)" to its constant value V, then requires "SELECT c FROM t WHERE (E)" to select the
 * same rows as "SELECT c FROM t WHERE V". A mismatch means the constant folder and the row-wise expression evaluator
 * disagree about E.
 *
 * <p>
 * This covers the constant-expression core of CODDTest; it does not implement the full subquery / correlated-subquery
 * folding of the SQLite3 implementation (which needs in-place AST substitution the YSQL provider does not have).
 */
public class YSQLCODDTestOracle implements TestOracle<YSQLGlobalState> {

    private final YSQLGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public YSQLCODDTestOracle(YSQLGlobalState globalState) {
        this.state = globalState;
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        // The generated expression may embed a scalar subquery that returns >1 row ("more than one row returned by a
        // subquery used as an expression") - a legitimate evaluation error, not a folding mismatch.
        YSQLErrors.addSubqueryErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        // No setColumns -> the generator produces a column-independent (constant) boolean expression.
        YSQLExpression expr = new YSQLExpressionGenerator(state).generateExpression(0, YSQLDataType.BOOLEAN);
        String exprString = YSQLVisitor.asString(expr);

        String folded;
        SQLQueryAdapter foldQuery = new SQLQueryAdapter("SELECT (" + exprString + ")", errors);
        try (SQLancerResultSet rs = foldQuery.executeAndGet(state)) {
            if (rs == null || !rs.next()) {
                throw new IgnoreMeException();
            }
            String v = rs.getString(1);
            if (v == null) {
                folded = "NULL";
            } else if ("t".equals(v)) {
                folded = "TRUE";
            } else if ("f".equals(v)) {
                folded = "FALSE";
            } else {
                throw new IgnoreMeException();
            }
        }

        YSQLTable table = state.getSchema().getRandomTableNonEmptyTables().getTables().get(0);
        if (table.getColumns().isEmpty()) {
            throw new IgnoreMeException();
        }
        String col = table.getColumns().get(0).getName();
        String from = " FROM " + table.getName();
        String originalQuery = "SELECT " + col + from + " WHERE (" + exprString + ")";
        String foldedQuery = "SELECT " + col + from + " WHERE " + folded;

        List<String> originalResult = ComparatorHelper.getResultSetFirstColumnAsString(originalQuery, errors, state);
        List<String> foldedResult = ComparatorHelper.getResultSetFirstColumnAsString(foldedQuery, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(originalResult, foldedResult, originalQuery, List.of(foldedQuery),
                state);
    }
}
