package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLTriggerGenerator {

    public static final String NEW_TABLE_ALIAS = "yb_new_table";
    public static final String OLD_TABLE_ALIAS = "yb_old_table";
    public static final String TRANSITION_FUNCTION_NEW = "ttfnnew";
    public static final String TRANSITION_FUNCTION_OLD = "ttfnold";
    public static final String TRANSITION_FUNCTION_BOTH = "ttfnboth";
    public static final String[] TRANSITION_FUNCTIONS = { TRANSITION_FUNCTION_NEW, TRANSITION_FUNCTION_OLD,
            TRANSITION_FUNCTION_BOTH };

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
        errors.add("REFERENCING clause (transition tables) not supported yet");
        errors.add("transition table name can only be specified for an AFTER trigger");
        errors.add("Transition tables cannot be specified for triggers with more than one event");
        errors.add("transition tables cannot be specified for triggers with column lists");
        errors.add("ROW variable naming in the REFERENCING clause is not supported");

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
        if (Randomly.getBoolean()) {
            return generateTransitionTableTrigger(globalState, table, errors);
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

    // Transition tables require a single-event AFTER ... FOR EACH STATEMENT trigger, and OLD/NEW are only
    // available for the events that produce them.
    private static SQLQueryAdapter generateTransitionTableTrigger(YSQLGlobalState globalState, YSQLTable table,
            ExpectedErrors errors) {
        String event = Randomly.fromOptions("INSERT", "UPDATE", "DELETE");
        boolean hasNew = !"DELETE".equals(event);
        boolean hasOld = !"INSERT".equals(event);
        if (hasNew && hasOld && Randomly.getBoolean()) {
            if (Randomly.getBoolean()) {
                hasOld = false;
            } else {
                hasNew = false;
            }
        }
        String function;
        if (hasNew && hasOld) {
            function = TRANSITION_FUNCTION_BOTH;
        } else if (hasNew) {
            function = TRANSITION_FUNCTION_NEW;
        } else {
            function = TRANSITION_FUNCTION_OLD;
        }
        StringBuilder sb = new StringBuilder();
        // Pair the function with the trigger so the declared aliases always resolve.
        sb.append(YSQLFunctionGenerator.transitionFunctionSql(function)).append("; ");
        sb.append("CREATE OR REPLACE TRIGGER ttrg").append(globalState.getRandomly().getInteger(0, MAX_TRIGGERS));
        sb.append(" AFTER ").append(event).append(" ON ").append(table.getName());
        sb.append(" REFERENCING");
        if (hasOld) {
            sb.append(" OLD TABLE AS ").append(OLD_TABLE_ALIAS);
        }
        if (hasNew) {
            sb.append(" NEW TABLE AS ").append(NEW_TABLE_ALIAS);
        }
        sb.append(" FOR EACH STATEMENT EXECUTE FUNCTION ").append(function).append("()");
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
        sb.append("DROP TRIGGER IF EXISTS ").append(Randomly.getBoolean() ? "trg" : "ttrg")
                .append(Randomly.smallNumber() % MAX_TRIGGERS);
        sb.append(" ON ").append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append(" CASCADE");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
