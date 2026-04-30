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

public final class YSQLCursorGenerator {

    private static final int MAX_CURSORS = 5;

    private YSQLCursorGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        errors.add("does not exist");
        errors.add("already in use");
        errors.add("already exists");
        errors.add("is not open");
        errors.add("no such cursor");
        errors.add("cursor can only scan forward");
        errors.add("DECLARE CURSOR can only be used in transaction blocks");
        errors.add("is not a simply updatable relation");
        errors.add("no result set");
        errors.add("portal");

        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            return generateDeclare(globalState, errors);
        case 1:
            return generateFetch(errors);
        case 2:
            return generateMove(errors);
        case 3:
            return generateClose(errors);
        default:
            throw new AssertionError();
        }
    }

    private static SQLQueryAdapter generateDeclare(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable();
        StringBuilder sb = new StringBuilder();
        String cursorName = "cur" + globalState.getRandomly().getInteger(0, MAX_CURSORS);
        sb.append("DECLARE ").append(cursorName);
        if (Randomly.getBoolean()) {
            sb.append(" BINARY");
        }
        if (Randomly.getBoolean()) {
            sb.append(Randomly.fromOptions(" SCROLL", " NO SCROLL"));
        }
        sb.append(" CURSOR");
        if (Randomly.getBoolean()) {
            sb.append(Randomly.fromOptions(" WITH HOLD", " WITHOUT HOLD"));
        }
        sb.append(" FOR SELECT ");
        YSQLColumn col = table.getRandomColumn();
        sb.append(col.getName());
        sb.append(" FROM ").append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append(" WHERE ");
            sb.append(YSQLVisitor.asString(
                    YSQLExpressionGenerator.generateExpression(globalState, table.getColumns(), YSQLDataType.BOOLEAN)));
        }
        if (Randomly.getBoolean()) {
            sb.append(" ORDER BY ").append(col.getName());
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateFetch(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("FETCH ");
        appendDirection(sb);
        sb.append(" FROM cur").append(Randomly.smallNumber() % MAX_CURSORS);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateMove(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("MOVE ");
        appendDirection(sb);
        sb.append(" FROM cur").append(Randomly.smallNumber() % MAX_CURSORS);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateClose(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLOSE ");
        if (Randomly.getBoolean()) {
            sb.append("ALL");
        } else {
            sb.append("cur").append(Randomly.smallNumber() % MAX_CURSORS);
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static void appendDirection(StringBuilder sb) {
        switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5, 6, 7)) {
        case 0:
            sb.append("NEXT");
            break;
        case 1:
            sb.append("PRIOR");
            break;
        case 2:
            sb.append("FIRST");
            break;
        case 3:
            sb.append("LAST");
            break;
        case 4:
            sb.append("FORWARD");
            break;
        case 5:
            sb.append("BACKWARD");
            break;
        case 6:
            sb.append("FORWARD ").append(Randomly.smallNumber());
            break;
        case 7:
            sb.append("ABSOLUTE ").append(Randomly.smallNumber());
            break;
        default:
            throw new AssertionError();
        }
    }

}
