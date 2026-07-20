package sqlancer.yugabyte.ysql.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLVisitor;

public class YSQLTLPGroupByOracle extends YSQLTLPBase {

    public YSQLTLPGroupByOracle(YSQLGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // TLP for GROUP BY: SELECT c FROM t GROUP BY c returns the distinct group keys. Partitioning the input over
        // p / NOT p / p IS NULL and grouping each partition yields the same keys, but a key straddling two partitions
        // appears in both - so the partitions must be combined with a deduplicating UNION (not UNION ALL).
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
