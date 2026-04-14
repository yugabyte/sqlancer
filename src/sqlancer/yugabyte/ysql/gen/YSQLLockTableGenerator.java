package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractTable;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLLockTableGenerator {

    private YSQLLockTableGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        List<String> tableNames = globalState.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .map(AbstractTable::getName).collect(Collectors.toList());

        if (tableNames.isEmpty()) {
            throw new IgnoreMeException();
        }

        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("LOCK TABLE can only be used in transaction blocks");
        errors.add("does not exist");
        errors.add("is not a table");
        errors.add("cannot be locked");
        errors.add("deadlock detected");
        errors.add("canceling statement due to lock timeout");
        errors.add("not supported yet");
        errors.add("not yet supported");

        StringBuilder sb = new StringBuilder();
        sb.append("LOCK TABLE ");
        List<String> selected = Randomly.nonEmptySubset(tableNames);
        sb.append(String.join(", ", selected));
        sb.append(" IN ");
        sb.append(Randomly.fromOptions("ACCESS SHARE", "ROW SHARE", "ROW EXCLUSIVE", "SHARE UPDATE EXCLUSIVE", "SHARE",
                "SHARE ROW EXCLUSIVE", "EXCLUSIVE", "ACCESS EXCLUSIVE"));
        sb.append(" MODE");
        if (Randomly.getBoolean()) {
            sb.append(" NOWAIT");
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
