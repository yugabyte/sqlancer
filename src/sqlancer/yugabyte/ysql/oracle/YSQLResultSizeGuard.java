package sqlancer.yugabyte.ysql.oracle;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

// Differential oracles that compare full result sets in memory skip queries whose result is too large: an unbounded
// comma join of several tables can return millions of rows and exhaust the JVM heap (seen as OutOfMemoryError in DQP).
// The row count is computed in the database, so the guard itself stays cheap on memory.
public final class YSQLResultSizeGuard {

    public static final long MAX_ROWS = 100_000;

    private YSQLResultSizeGuard() {
    }

    public static void skipIfTooLarge(String query, ExpectedErrors errors, YSQLGlobalState state) throws SQLException {
        List<String> count = ComparatorHelper.getResultSetFirstColumnAsString(
                "SELECT count(*) FROM (" + query + ") AS yb_result_size", errors, state);
        if (count.isEmpty() || Long.parseLong(count.get(0)) > MAX_ROWS) {
            throw new IgnoreMeException();
        }
    }
}
