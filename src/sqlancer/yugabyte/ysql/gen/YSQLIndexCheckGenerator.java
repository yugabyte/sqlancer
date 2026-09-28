package sqlancer.yugabyte.ysql.gen;

import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLIndex;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * Runs YugabyteDB's {@code yb_index_check()} on a generated index, or on every valid LSM index of a table (including
 * the primary key and unique constraints). The function compares the index with its base table and raises an error such
 * as "index contains spurious row" or "index '...' is missing row corresponding to ybctid" on any mismatch; those
 * errors are deliberately not expected, so an inconsistent index is reported as a bug.
 */
public final class YSQLIndexCheckGenerator {

    private YSQLIndexCheckGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<YSQLIndex> indexes = table.getIndexes();
        String query;
        if (indexes.isEmpty() || Randomly.getBoolean()) {
            query = String.format("SELECT yb_index_check(i.indexrelid) FROM pg_index i JOIN pg_class c"
                    + " ON c.oid = i.indexrelid JOIN pg_am a ON a.oid = c.relam WHERE i.indrelid = '%s'::regclass"
                    + " AND i.indisvalid AND a.amname = 'lsm' ORDER BY i.indexrelid", table.getName());
        } else {
            YSQLIndex index = Randomly.fromList(indexes);
            if (index.getIndexName().isEmpty()) {
                throw new IgnoreMeException();
            }
            query = String.format("SELECT yb_index_check('%s'::regclass)", index.getIndexName());
        }
        ExpectedErrors errors = new ExpectedErrors();
        // Legitimate rejections: a non-LSM index (temp-table btree, ybgin, vector), an index left invalid by a failed
        // CREATE INDEX, and an index or table dropped since the schema was read.
        errors.add("This operation is not supported for index with");
        errors.add("is marked invalid");
        errors.add("Object is not an index");
        errors.add("does not exist");
        errors.add("was concurrently dropped");
        errors.add("permission denied for function yb_index_check");
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter(query, errors);
    }

}
