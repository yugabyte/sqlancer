package sqlancer.yugabyte.ysql.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLColumnValue;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;

public class YSQLTLPGroupByOracle extends YSQLTLPBase {

    private static boolean isStar(YSQLExpression e) {
        return e instanceof YSQLColumnValue && "*".equals(((YSQLColumnValue) e).getColumn().getName());
    }

    public YSQLTLPGroupByOracle(YSQLGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // TLP for GROUP BY: SELECT c FROM t GROUP BY c returns the distinct group keys. Partitioning the input over
        // p / NOT p / p IS NULL and grouping each partition yields the same keys, but a key straddling two partitions
        // appears in both - so the partitions must be combined with a deduplicating UNION (not UNION ALL).
        if (select.getFetchColumns().stream().anyMatch(YSQLTLPGroupByOracle::isStar)) {
            // "GROUP BY *" is not valid SQL; group by the real columns instead.
            select.setFetchColumns(targetTables.getColumns().stream()
                    .map(c -> (YSQLExpression) new YSQLColumnValue(c, null)).collect(Collectors.toList()));
        }
        select.setGroupByExpressions(select.getFetchColumns());
        String originalQueryString = YSQLVisitor.asString(select);
        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        select.setWhereClause(predicate);
        String firstQueryString = YSQLVisitor.asString(select);
        select.setWhereClause(negatedPredicate);
        String secondQueryString = YSQLVisitor.asString(select);
        select.setWhereClause(isNullPredicate);
        String thirdQueryString = YSQLVisitor.asString(select);
        List<String> combinedString = new ArrayList<>();
        List<String> secondResultSet = ComparatorHelper.getCombinedResultSetNoDuplicates(firstQueryString,
                secondQueryString, thirdQueryString, combinedString, true, state, errors);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state, ComparatorHelper::canonicalizeResultValue);
    }
}
