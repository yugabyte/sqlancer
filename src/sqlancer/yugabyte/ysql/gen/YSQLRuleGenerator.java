package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLRuleGenerator {

    private static final int MAX_RULES = 5;

    private YSQLRuleGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot have RETURNING");
        errors.add("is not a table");
        errors.add("is not a view");
        errors.add("cannot be used as a relation");
        errors.add("rule");
        errors.add("rules on SELECT");
        errors.add("cannot drop");
        errors.add("This statement not supported yet");
        errors.add("is not supported");

        if (Randomly.getBoolean()) {
            return generateCreate(globalState, errors);
        } else {
            return generateDrop(globalState, errors);
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
        String ruleName = "rule" + globalState.getRandomly().getInteger(0, MAX_RULES);
        sb.append("CREATE OR REPLACE RULE ").append(ruleName);
        sb.append(" AS ON ");
        sb.append(Randomly.fromOptions("INSERT", "UPDATE", "DELETE"));
        sb.append(" TO ").append(table.getName());
        sb.append(" DO ");
        if (Randomly.getBoolean()) {
            sb.append("INSTEAD NOTHING");
        } else {
            sb.append(Randomly.fromOptions("ALSO", "INSTEAD"));
            sb.append(" NOTHING");
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
        sb.append("DROP RULE IF EXISTS rule").append(Randomly.smallNumber() % MAX_RULES);
        sb.append(" ON ").append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append(" CASCADE");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
