package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLTriggerGenerator {

    private static final int MAX_TRIGGERS = 5;
    private static final int MAX_TRIGGER_FUNCTIONS = 5;

    private YSQLTriggerGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("is not a table");
        errors.add("is a view");
        errors.add("cannot create trigger");
        errors.add("trigger function");
        errors.add("must be declared as a function");
        errors.add("function does not return");
        errors.add("Triggers are not yet supported");
        errors.add("Triggers are not supported yet");
        errors.add("This statement not supported yet");
        errors.add("is not supported");
        errors.add("cannot drop");

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
        String triggerName = "trg" + globalState.getRandomly().getInteger(0, MAX_TRIGGERS);
        sb.append("CREATE OR REPLACE TRIGGER ").append(triggerName);
        sb.append(" ");

        sb.append(Randomly.fromOptions("BEFORE", "AFTER"));
        sb.append(" ");

        // Event
        boolean needOr = false;
        if (Randomly.getBoolean()) {
            sb.append("INSERT");
            needOr = true;
        }
        if (Randomly.getBoolean()) {
            if (needOr) {
                sb.append(" OR ");
            }
            sb.append("UPDATE");
            needOr = true;
        }
        if (Randomly.getBoolean()) {
            if (needOr) {
                sb.append(" OR ");
            }
            sb.append("DELETE");
            needOr = true;
        }
        if (!needOr) {
            sb.append("INSERT");
        }

        sb.append(" ON ").append(table.getName());
        sb.append(" FOR EACH ");
        sb.append(Randomly.fromOptions("ROW", "STATEMENT"));
        sb.append(" EXECUTE FUNCTION trgfn").append(Randomly.smallNumber() % MAX_TRIGGER_FUNCTIONS).append("()");

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
        sb.append("DROP TRIGGER IF EXISTS trg").append(Randomly.smallNumber() % MAX_TRIGGERS);
        sb.append(" ON ").append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append(" CASCADE");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
