package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;

public final class YSQLPolicyGenerator {

    private static final int MAX_POLICIES = 5;

    private YSQLPolicyGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("is not a table");
        errors.add("cannot be applied");
        errors.add("policy");
        errors.add("row-level security");
        errors.add("row level security");
        errors.add("new row violates row-level security");
        errors.add("only WITH CHECK expression allowed for INSERT");
        errors.add("This statement not supported yet");

        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            return generateCreate(globalState, errors);
        case 1:
            return generateDrop(globalState, errors);
        case 2:
            return generateEnableRLS(globalState, errors);
        case 3:
            return generateDisableRLS(globalState, errors);
        default:
            throw new AssertionError();
        }
    }

    private static SQLQueryAdapter generateCreate(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        if (table == null) {
            throw new IgnoreMeException();
        }
        StringBuilder sb = new StringBuilder();
        String policyName = "pol" + globalState.getRandomly().getInteger(0, MAX_POLICIES);
        sb.append("CREATE POLICY ").append(policyName).append(" ON ").append(table.getName());

        if (Randomly.getBoolean()) {
            sb.append(" AS ").append(Randomly.fromOptions("PERMISSIVE", "RESTRICTIVE"));
        }
        if (Randomly.getBoolean()) {
            sb.append(" FOR ").append(Randomly.fromOptions("ALL", "SELECT", "INSERT", "UPDATE", "DELETE"));
        }
        sb.append(" TO PUBLIC");

        if (Randomly.getBoolean()) {
            sb.append(" USING (");
            sb.append(YSQLVisitor.asString(
                    YSQLExpressionGenerator.generateExpression(globalState, table.getColumns(), YSQLDataType.BOOLEAN)));
            sb.append(")");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateDrop(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        if (table == null) {
            throw new IgnoreMeException();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("DROP POLICY IF EXISTS pol").append(Randomly.smallNumber() % MAX_POLICIES);
        sb.append(" ON ").append(table.getName());
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateEnableRLS(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        if (table == null) {
            throw new IgnoreMeException();
        }
        return new SQLQueryAdapter("ALTER TABLE " + table.getName() + " ENABLE ROW LEVEL SECURITY", errors, true);
    }

    private static SQLQueryAdapter generateDisableRLS(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        if (table == null) {
            throw new IgnoreMeException();
        }
        return new SQLQueryAdapter("ALTER TABLE " + table.getName() + " DISABLE ROW LEVEL SECURITY", errors, true);
    }

}
