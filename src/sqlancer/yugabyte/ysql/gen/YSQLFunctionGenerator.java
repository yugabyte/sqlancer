package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLFunctionGenerator {

    private static final int MAX_FUNCTIONS = 5;

    private YSQLFunctionGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot drop");
        errors.add("cannot change return type");
        errors.add("cannot change name of input parameter");
        errors.add("is not a function");
        errors.add("could not find a function");
        errors.add("function result type must be");
        errors.add("return type mismatch");
        errors.add("cannot determine result data type");
        errors.add("CALLED ON NULL INPUT");
        errors.add("set-returning functions");

        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            return generateCreateFunction(globalState, errors);
        case 1:
            return generateCreateTriggerFunction(globalState, errors);
        case 2:
            return generateDrop(errors);
        default:
            throw new AssertionError();
        }
    }

    private static SQLQueryAdapter generateCreateFunction(YSQLGlobalState globalState, ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String funcName = "fn" + globalState.getRandomly().getInteger(0, MAX_FUNCTIONS);
        sb.append("CREATE OR REPLACE FUNCTION ").append(funcName).append("(");

        String returnType;
        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            sb.append("p_val INTEGER");
            returnType = "INTEGER";
            sb.append(") RETURNS ").append(returnType).append(" AS $$ ");
            sb.append("BEGIN RETURN p_val * ").append(Randomly.smallNumber() + 1).append("; END;");
            break;
        case 1:
            sb.append("p_val TEXT");
            returnType = "TEXT";
            sb.append(") RETURNS ").append(returnType).append(" AS $$ ");
            sb.append("BEGIN RETURN p_val || '_suffix'; END;");
            break;
        case 2:
            sb.append("p_val INTEGER");
            returnType = "BOOLEAN";
            sb.append(") RETURNS ").append(returnType).append(" AS $$ ");
            sb.append("BEGIN RETURN p_val > ").append(Randomly.smallNumber()).append("; END;");
            break;
        default:
            returnType = "VOID";
            sb.append(") RETURNS ").append(returnType).append(" AS $$ ");
            sb.append("BEGIN PERFORM 1; END;");
            break;
        }
        sb.append(" $$ LANGUAGE plpgsql");
        if (Randomly.getBoolean()) {
            sb.append(" ").append(Randomly.fromOptions("IMMUTABLE", "STABLE", "VOLATILE"));
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateCreateTriggerFunction(YSQLGlobalState globalState, ExpectedErrors errors) {
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        StringBuilder sb = new StringBuilder();
        String funcName = "trgfn" + globalState.getRandomly().getInteger(0, MAX_FUNCTIONS);
        sb.append("CREATE OR REPLACE FUNCTION ").append(funcName);
        sb.append("() RETURNS TRIGGER AS $$ BEGIN ");

        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            sb.append("RETURN NEW;");
            break;
        case 1:
            sb.append("RETURN OLD;");
            break;
        case 2:
            sb.append("RETURN NULL;");
            break;
        default:
            throw new AssertionError();
        }

        sb.append(" END; $$ LANGUAGE plpgsql");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateDrop(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("DROP FUNCTION IF EXISTS ");
        if (Randomly.getBoolean()) {
            sb.append("fn").append(Randomly.smallNumber() % MAX_FUNCTIONS);
        } else {
            sb.append("trgfn").append(Randomly.smallNumber() % MAX_FUNCTIONS);
        }
        if (Randomly.getBoolean()) {
            sb.append(" CASCADE");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
