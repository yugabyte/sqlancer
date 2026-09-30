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
        if (!globalState.isPgCompatible()) {
            errors.add("COPY"); // the YugabyteDB JDBC driver refuses COPY outside its CopyManager API
        }
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

        if (globalState.isPgCompatible() && Randomly.getBoolean()) {
            return generateCopyRoundTrip(globalState, errors);
        }
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
        if (globalState.isPgCompatible()) {
            return new YSQLCopyQuery(null, sb.toString(), null, errors);
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    // COPY a table out and back in, into itself or into a new table LIKE it: COPY FROM loads rows in batches
    // (heap multi-insert), a different write path and WAL record than INSERT.
    private static SQLQueryAdapter generateCopyRoundTrip(YSQLGlobalState globalState, ExpectedErrors errors) {
        YSQLTable source = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<String> columns = source.getColumns().stream().filter(c -> !c.isGenerated()).map(YSQLColumn::getName)
                .collect(Collectors.toList());
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        String columnList = " (" + String.join(", ", columns) + ")";
        String format = Randomly.fromOptions("csv", "text");
        String setup = null;
        String target = source.getName();
        if (Randomly.getBoolean()) {
            target = YSQLCatalogNames.newTableName(globalState);
            setup = "CREATE TABLE " + target + " (LIKE " + source.getName()
                    + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING GENERATED INCLUDING INDEXES)";
        }
        String options = Randomly.fromOptions("", ", ON_ERROR ignore", ", ON_ERROR ignore, LOG_VERBOSITY verbose",
                ", ON_ERROR ignore, REJECT_LIMIT 10");
        errors.add("violates");
        errors.add("value too long");
        errors.add("out of range");
        errors.add("already exists");
        errors.add("skipped due to data type incompatibility"); // ON_ERROR ignore notices past REJECT_LIMIT
        errors.add("exceeded the limit");
        errors.add("no partition of relation");
        errors.add("cannot insert");
        return new YSQLCopyQuery(setup, "COPY " + source.getName() + columnList + " TO STDOUT (FORMAT " + format + ")",
                "COPY " + target + columnList + " FROM STDIN (FORMAT " + format + options + ")", errors);
    }

}
