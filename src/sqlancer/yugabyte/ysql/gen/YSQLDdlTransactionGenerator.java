package sqlancer.yugabyte.ysql.gen;

import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * Emits a DDL transaction block - {@code BEGIN; <one or more DDL statements>; COMMIT|ROLLBACK} - to exercise
 * YugabyteDB's "DDL transaction block" feature (the {@code yb_ddl_transaction_block_enabled} preview gflag, set at
 * server start).
 *
 * <p>
 * Without that flag YugabyteDB rejects a DDL inside a transaction with "autonomous DDL not exepcted inside a
 * transaction block", which is tolerated here so the generator stays noise-free on builds that lack the feature. With
 * the flag enabled the whole block should commit atomically, or leave no trace when it ends in ROLLBACK.
 */
public final class YSQLDdlTransactionGenerator {

    private YSQLDdlTransactionGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        List<YSQLTable> tables = globalState.getSchema().getDatabaseTablesWithoutViews();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }

        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        // DDL inside a transaction block is rejected unless yb_ddl_transaction_block_enabled is set at server start.
        errors.add("not exepcted inside a transaction block"); // note: YugabyteDB message contains this typo
        errors.add("cannot run inside a transaction block");
        errors.add("DDL statement is not allowed within a transaction block");

        StringBuilder sb = new StringBuilder("BEGIN;\n");
        int count = Randomly.smallNumber() + 1;
        for (int i = 0; i < count; i++) {
            SQLQueryAdapter ddl = randomDdl(globalState, tables);
            sb.append(ddl.getUnterminatedQueryString()).append(";\n");
            // Inherit each composed generator's tolerated errors - otherwise DDL-specific rejections (e.g. an index
            // expression with no default operator class) would leak out of the block as false positives.
            errors.add(ddl.getExpectedErrors());
        }
        // Occasionally roll back to exercise DDL-transaction atomicity (the block must leave no schema change behind).
        sb.append(Randomly.getBooleanWithRatherLowProbability() ? "ROLLBACK;" : "COMMIT;");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter randomDdl(YSQLGlobalState globalState, List<YSQLTable> tables) {
        // ALTER TABLE and CREATE INDEX both operate on the existing schema, avoiding CREATE/DROP naming churn.
        if (Randomly.getBoolean()) {
            return YSQLIndexGenerator.generate(globalState);
        }
        return YSQLAlterTableGenerator.create(Randomly.fromList(tables), globalState);
    }
}
