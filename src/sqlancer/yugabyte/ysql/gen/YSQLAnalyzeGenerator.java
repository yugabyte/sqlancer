package sqlancer.yugabyte.ysql.gen;

import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.yugabyte.YugabyteBugs;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLAnalyzeGenerator {

    private YSQLAnalyzeGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable();
        StringBuilder sb = new StringBuilder("ANALYZE");
        if (Randomly.getBoolean()) {
            sb.append("(");
            sb.append(" VERBOSE");
            sb.append(")");
        }
        sb.append(" ");
        sb.append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append("(");
            sb.append(table.getRandomNonEmptyColumnSubset().stream().map(AbstractTableColumn::getName)
                    .collect(Collectors.joining(", ")));
            sb.append(")");
        }

        // PostgreSQL allows ANALYZE in a read-only transaction; YugabyteDB failed it at COMMIT until #34575.
        boolean readOnlyBlock = Randomly.getBooleanWithRatherLowProbability();
        ExpectedErrors errors = readOnlyBlock && !YugabyteBugs.bugAnalyzeInReadOnlyTransactionFails
                ? new YSQLReadOnlyAnalyzeErrors() : new ExpectedErrors();
        errors.add("deadlock");
        errors.add("is an identity column");
        errors.add("is in a primary key");
        YSQLErrors.addGroupingErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        if (readOnlyBlock) {
            return new SQLQueryAdapter("BEGIN READ ONLY;\n" + sb + ";\nCOMMIT;", errors);
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static final class YSQLReadOnlyAnalyzeErrors extends ExpectedErrors {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean errorIsExpected(String error) {
            return !error.contains("in a read-only transaction") && super.errorIsExpected(error);
        }
    }

}
