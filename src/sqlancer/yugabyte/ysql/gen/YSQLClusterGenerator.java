package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLClusterGenerator {

    // table, then a valid non-partial btree index on it
    private static final String CLUSTERABLE = "SELECT c.relname || chr(1) || i.relname FROM pg_index x"
            + " JOIN pg_class c ON c.oid = x.indrelid JOIN pg_class i ON i.oid = x.indexrelid"
            + " JOIN pg_am a ON a.oid = i.relam WHERE a.amname = 'btree' AND x.indpred IS NULL AND x.indisvalid"
            + " AND c.relkind IN ('r', 'm') AND c.relnamespace = 'public'::regnamespace";

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
            // The table and one of its btree indexes from the catalog: the schema does not always list a table's
            // indexes, and CLUSTER without USING needs an index clustered earlier, so it nearly always failed.
            String[] pair;
            try {
                pair = YSQLCatalogNames.random(globalState, CLUSTERABLE).split("\\u0001", 2);
            } catch (IgnoreMeException e) {
                pair = new String[] { globalState.getSchema().getRandomTable(t -> !t.isView()).getName() };
            }
            sb.append(pair[0]);
            if (pair.length == 2 && !Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(" USING ").append(pair[1]);
            }
        }
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
