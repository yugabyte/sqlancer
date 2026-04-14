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

public final class YSQLPreparedStatementGenerator {

    private static final int MAX_PREPARED_STMTS = 5;

    private YSQLPreparedStatementGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        errors.add("prepared statement");
        errors.add("does not exist");
        errors.add("already exists");
        errors.add("wrong number of parameters");
        errors.add("could not determine data type of parameter");
        errors.add("there is no parameter");
        errors.add("is not a table");
        errors.add("invalid input syntax");
        errors.add("out of range");
        errors.add("division by zero");
        errors.add("cannot cast");
        errors.add("syntax error");
        errors.add("violates not-null constraint");
        errors.add("violates unique constraint");
        errors.add("violates check constraint");
        errors.add("violates foreign key constraint");
        errors.add("duplicate key");
        errors.add("cannot perform");
        errors.add("You need an unconditional ON");

        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            return generatePrepare(globalState, errors);
        case 1:
            return generateExecute(globalState, errors);
        case 2:
            return generateDeallocate(errors);
        default:
            throw new AssertionError();
        }
    }

    private static SQLQueryAdapter generatePrepare(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        if (table == null) {
            throw new IgnoreMeException();
        }
        StringBuilder sb = new StringBuilder();
        String stmtName = "ps" + globalState.getRandomly().getInteger(0, MAX_PREPARED_STMTS);
        sb.append("PREPARE ").append(stmtName).append(" AS ");
        if (Randomly.getBoolean()) {
            sb.append("SELECT ");
            YSQLColumn col = table.getRandomColumn();
            sb.append(col.getName());
            sb.append(" FROM ").append(table.getName());
            if (Randomly.getBoolean()) {
                sb.append(" WHERE ");
                sb.append(YSQLVisitor.asString(YSQLExpressionGenerator.generateExpression(globalState,
                        table.getColumns(), YSQLDataType.BOOLEAN)));
            }
        } else {
            sb.append("INSERT INTO ").append(table.getName());
            sb.append(" DEFAULT VALUES");
        }
        YSQLErrors.addCommonInsertUpdateErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateExecute(YSQLGlobalState globalState, ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String stmtName = "ps" + globalState.getRandomly().getInteger(0, MAX_PREPARED_STMTS);
        sb.append("EXECUTE ").append(stmtName);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateDeallocate(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("DEALLOCATE ");
        if (Randomly.getBoolean()) {
            sb.append("ALL");
        } else {
            sb.append("ps").append(Randomly.smallNumber() % MAX_PREPARED_STMTS);
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
