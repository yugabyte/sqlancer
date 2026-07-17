package sqlancer.yugabyte.ysql.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLSelect;

public class YSQLTLPDistinctOracle extends YSQLTLPBase {

    public YSQLTLPDistinctOracle(YSQLGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // TLP for DISTINCT: SELECT DISTINCT c FROM t must equal the deduplicated UNION of the same query partitioned
        // over p / NOT p / p IS NULL. UNION (not UNION ALL) removes the duplicates DISTINCT would have collapsed.
        select.setSelectType(YSQLSelect.SelectType.DISTINCT);
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
        // DISTINCT/UNION pick an arbitrary representative among SQL-equal values, and the two sides can pick different
        // renderings (e.g. -0.0 vs 0.0). Canonicalize both sides to avoid a spurious mismatch.
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state, ComparatorHelper::canonicalizeResultValue);
    }
}
