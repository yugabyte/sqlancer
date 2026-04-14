package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLTypeGenerator {

    private static final int MAX_TYPES = 5;

    private YSQLTypeGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot be dropped because some objects depend on it");
        errors.add("is not a composite type");
        errors.add("type");
        errors.add("cannot drop");
        errors.add("cannot be made a member of itself");

        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            return generateCreateEnum(globalState, errors);
        case 1:
            return generateCreateComposite(globalState, errors);
        case 2:
            return generateDrop(errors);
        default:
            throw new AssertionError();
        }
    }

    private static SQLQueryAdapter generateCreateEnum(YSQLGlobalState globalState, ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String typeName = "tp" + globalState.getRandomly().getInteger(0, MAX_TYPES);
        sb.append("CREATE TYPE ").append(typeName).append(" AS ENUM (");
        int numLabels = 2 + Randomly.smallNumber() % 5;
        for (int i = 0; i < numLabels; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("'").append(typeName).append("_val").append(i).append("'");
        }
        sb.append(")");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateCreateComposite(YSQLGlobalState globalState, ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String typeName = "tp" + globalState.getRandomly().getInteger(0, MAX_TYPES);
        sb.append("CREATE TYPE ").append(typeName).append(" AS (");
        int numFields = 1 + Randomly.smallNumber() % 4;
        for (int i = 0; i < numFields; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("f").append(i).append(" ");
            sb.append(Randomly.fromOptions("INTEGER", "TEXT", "BOOLEAN", "NUMERIC", "BIGINT"));
        }
        sb.append(")");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateDrop(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("DROP TYPE IF EXISTS tp").append(Randomly.smallNumber() % MAX_TYPES);
        if (Randomly.getBoolean()) {
            sb.append(" CASCADE");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

}
