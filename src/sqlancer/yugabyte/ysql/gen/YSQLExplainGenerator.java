package sqlancer.yugabyte.ysql.gen;

import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;

public final class YSQLExplainGenerator {

    private YSQLExplainGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }

        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        errors.add("does not exist");
        errors.add("out of range");
        errors.add("division by zero");
        errors.add("cannot cast");
        errors.add("invalid input syntax");
        errors.add("canceling statement due to statement timeout");

        StringBuilder sb = new StringBuilder();
        sb.append("EXPLAIN ");
        List<String> options = Randomly.subset("ANALYZE TRUE", "VERBOSE TRUE",
                "COSTS " + Randomly.fromOptions("TRUE", "FALSE"), "BUFFERS TRUE",
                "FORMAT " + Randomly.fromOptions("TEXT", "JSON", "XML", "YAML"));
        if (globalState.isPgCompatible()) {
            // PostgreSQL 16-18; SERIALIZE, WAL and TIMING need ANALYZE, GENERIC_PLAN excludes it.
            options.addAll(Randomly.subset("MEMORY TRUE", "SETTINGS TRUE", "SUMMARY TRUE"));
            options.addAll(options.contains("ANALYZE TRUE") ? Randomly
                    .subset("SERIALIZE " + Randomly.fromOptions("TEXT", "BINARY", "NONE"), "WAL TRUE", "TIMING FALSE")
                    : Randomly.subset("GENERIC_PLAN TRUE"));
            errors.add("requires ANALYZE");
            errors.add("cannot be used together");
        }
        if (!options.isEmpty()) {
            sb.append("(").append(String.join(", ", options)).append(") ");
        }

        YSQLTable table = globalState.getSchema().getRandomTable();
        YSQLColumn col = table.getRandomColumn();
        sb.append("SELECT ").append(col.getName());
        sb.append(" FROM ").append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append(" WHERE ");
            sb.append(YSQLVisitor.asString(
                    YSQLExpressionGenerator.generateExpression(globalState, table.getColumns(), YSQLDataType.BOOLEAN)));
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
