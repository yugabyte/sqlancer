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

public final class YSQLGrantRevokeGenerator {

    private YSQLGrantRevokeGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        errors.add("does not exist");
        errors.add("dependent privileges exist");
        errors.add("cannot be dropped because some objects depend on it");
        errors.add("is not a table");
        errors.add("WARNING");
        errors.add("permission denied");
        errors.add("must be owner");
        errors.add("unrecognized privilege type");

        if (Randomly.getBoolean()) {
            return generateGrant(globalState, errors);
        } else {
            return generateRevoke(globalState, errors);
        }
    }

    private static SQLQueryAdapter generateGrant(YSQLGlobalState globalState, ExpectedErrors errors) {
        List<String> tableNames = globalState.getSchema().getDatabaseTables().stream().map(AbstractTable::getName)
                .collect(Collectors.toList());

        if (tableNames.isEmpty()) {
            throw new IgnoreMeException();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("GRANT ");
        appendPrivileges(sb);
        sb.append(" ON ");
        sb.append(Randomly.fromList(tableNames));
        sb.append(" TO PUBLIC");
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateRevoke(YSQLGlobalState globalState, ExpectedErrors errors) {
        List<String> tableNames = globalState.getSchema().getDatabaseTables().stream().map(AbstractTable::getName)
                .collect(Collectors.toList());

        if (tableNames.isEmpty()) {
            throw new IgnoreMeException();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("REVOKE ");
        appendPrivileges(sb);
        sb.append(" ON ");
        sb.append(Randomly.fromList(tableNames));
        sb.append(" FROM PUBLIC");
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static void appendPrivileges(StringBuilder sb) {
        if (Randomly.getBoolean()) {
            sb.append("ALL PRIVILEGES");
        } else {
            List<String> privs = Randomly.nonEmptySubset("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE",
                    "REFERENCES", "TRIGGER");
            sb.append(String.join(", ", privs));
        }
    }

}
