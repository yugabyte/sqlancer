package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

// Generator for YugabyteDB 2025.1 INSERT-related settings.
public final class YSQLInsertSettingsGenerator {

    private YSQLInsertSettingsGenerator() {
    }

    // Set read batch size for INSERT ON CONFLICT.
    public static SQLQueryAdapter setInsertOnConflictBatchSize(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder("SET ");

        if (Randomly.getBoolean()) {
            sb.append("LOCAL ");
        }

        sb.append("yb_insert_on_conflict_read_batch_size = ");

        // 0 disables batching, aligning with PostgreSQL behavior
        int batchSize = Randomly.fromOptions(0, 1, 10, 50, 100, 256, 512, 1024);
        sb.append(batchSize);

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("invalid value for parameter");
        errors.add("parameter \"yb_insert_on_conflict_read_batch_size\"");
        errors.add("out of range");
        YSQLErrors.addTransactionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors);
    }

    // Enable or disable in-place index updates.
    public static SQLQueryAdapter setInPlaceIndexUpdate(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder("SET ");

        if (Randomly.getBoolean()) {
            sb.append("LOCAL ");
        }

        sb.append("yb_enable_inplace_index_update = ");
        sb.append(Randomly.fromOptions("true", "false", "on", "off"));

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("invalid value for parameter");
        errors.add("parameter \"yb_enable_inplace_index_update\"");
        YSQLErrors.addTransactionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors);
    }

    // Configure DDL statement incrementing behavior.
    public static SQLQueryAdapter setDDLNonIncrementing(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder("SET ");

        if (Randomly.getBoolean()) {
            sb.append("LOCAL ");
        }

        sb.append("yb_make_next_ddl_statement_nonincrementing = ");
        sb.append(Randomly.fromOptions("true", "false"));

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("invalid value for parameter");
        errors.add("parameter \"yb_make_next_ddl_statement_nonincrementing\"");
        YSQLErrors.addTransactionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors);
    }
}
