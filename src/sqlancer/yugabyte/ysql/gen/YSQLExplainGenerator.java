package sqlancer.yugabyte.ysql.gen;

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
        if (Randomly.getBoolean()) {
            sb.append("(");
            boolean first = true;
            if (Randomly.getBoolean()) {
                sb.append("ANALYZE TRUE");
                first = false;
            }
            if (Randomly.getBoolean()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("VERBOSE TRUE");
                first = false;
            }
            if (Randomly.getBoolean()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("COSTS ").append(Randomly.fromOptions("TRUE", "FALSE"));
                first = false;
            }
            if (Randomly.getBoolean()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("BUFFERS TRUE");
                first = false;
            }
            if (Randomly.getBoolean()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("FORMAT ").append(Randomly.fromOptions("TEXT", "JSON", "XML", "YAML"));
            }
            sb.append(") ");
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
