package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLCopyGenerator {

    private YSQLCopyGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("does not exist");
        errors.add("is not a table");
        errors.add("COPY");
        errors.add("cannot copy");
        errors.add("must be superuser");
        errors.add("relative path not allowed");
        errors.add("could not open file");
        errors.add("invalid input syntax");
        errors.add("violates");
        errors.add("duplicate key");
        errors.add("No such file or directory");
        errors.add("Permission denied");
        errors.add("extra data after");
        errors.add("missing data for column");
        errors.add("cannot specify HEADER in BINARY mode");
        errors.add("not supported yet");
        errors.add("BINARY is not supported yet");

        // Only use COPY TO STDOUT (safe - no file system access needed)
        return generateCopyToStdout(globalState, errors);
    }

    private static SQLQueryAdapter generateCopyToStdout(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        if (table == null) {
            throw new IgnoreMeException();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("COPY ");

        if (Randomly.getBoolean()) {
            // COPY table TO
            sb.append(table.getName());
            if (Randomly.getBoolean()) {
                List<String> cols = Randomly.nonEmptySubset(table.getColumns()).stream().map(YSQLColumn::getName)
                        .collect(Collectors.toList());
                sb.append(" (").append(String.join(", ", cols)).append(")");
            }
        } else {
            // COPY (query) TO
            YSQLColumn col = table.getRandomColumn();
            sb.append("(SELECT ").append(col.getName()).append(" FROM ").append(table.getName());
            if (Randomly.getBoolean()) {
                sb.append(" LIMIT ").append(Randomly.smallNumber());
            }
            sb.append(")");
        }
        sb.append(" TO STDOUT");

        if (Randomly.getBoolean()) {
            sb.append(" WITH (");
            sb.append("FORMAT ").append(Randomly.fromOptions("text", "csv", "binary"));
            if (Randomly.getBoolean()) {
                sb.append(", HEADER TRUE");
            }
            sb.append(")");
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
