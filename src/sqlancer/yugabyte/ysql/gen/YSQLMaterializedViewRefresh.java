package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLMaterializedViewRefresh {

    private YSQLMaterializedViewRefresh() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        try {
            ExpectedErrors errors = new ExpectedErrors();
            StringBuilder sb = new StringBuilder("REFRESH MATERIALIZED VIEW ");
            if (Randomly.getBoolean()) {
                sb.append("CONCURRENTLY ");
            }
            sb.append(Randomly.fromList(globalState.getSchema().getRandomMaterializedView()).getName());
            YSQLErrors.addGroupingErrors(errors);
            YSQLErrors.addViewErrors(errors);
            YSQLErrors.addCommonExpressionErrors(errors);
            YSQLErrors.addTransactionErrors(errors);
            errors.add("Create a unique index with no WHERE clause on one or more columns of the materialized view");
            errors.add("cannot refresh materialized view");
            errors.add("duplicate key value violates unique constraint");
            // CONCURRENTLY requires the new contents to have no fully non-null duplicate rows.
            errors.add("contains duplicate rows without any null columns");
            errors.add("contains rows with all null values");
            return new SQLQueryAdapter(sb.toString(), errors, true);
        } catch (Exception e) {
            throw new IgnoreMeException();
        }
    }

}
