package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLClusterGenerator {

    private YSQLClusterGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("there is no previously clustered index for table");
        errors.add("cannot cluster a partitioned table");
        errors.add("access method does not support clustering");
        errors.add("does not exist"); // a view, materialized view or index dropped since the schema was read
        errors.add("is not a table or materialized view");
        errors.add("is not an index for table");
        errors.add("does not belong to table");
        errors.add("cannot cluster on invalid index");
        errors.add("cannot run inside a transaction block"); // the bare, database-wide form
        StringBuilder sb = new StringBuilder(
                globalState.isPgCompatible() && Randomly.getBoolean() ? "CLUSTER (VERBOSE) " : "CLUSTER ");
        if (!Randomly.getBooleanWithRatherLowProbability()) { // a bare CLUSTER needs previously clustered tables
            // A table with an index when there is one: CLUSTER without USING needs a previously clustered index.
            YSQLTable table = globalState.getSchema().getDatabaseTables().stream()
                    .anyMatch(t -> !t.isView() && !t.getIndexes().isEmpty())
                            ? globalState.getSchema().getRandomTable(t -> !t.isView() && !t.getIndexes().isEmpty())
                            : globalState.getSchema().getRandomTable(t -> !t.isView());
            sb.append(table.getName());
            // Without USING, CLUSTER needs an index marked clustered earlier, which is rarely there.
            if (!table.getIndexes().isEmpty() && !Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(" USING ");
                sb.append(table.getRandomIndex().getIndexName());
                errors.add("cannot cluster on partial index");
            }
        }
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
