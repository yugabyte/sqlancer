package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;

/**
 * Exercises YugabyteDB's tablegroup DDL (a YB-only feature for co-locating a set of tables on the same tablet). Uses a
 * small fixed pool of names so CREATE/DROP statements interleave meaningfully without needing extra schema state.
 */
public final class YSQLTableGroupGenerator {

    private YSQLTableGroupGenerator() {
    }

    public static SQLQueryAdapter create() {
        ExpectedErrors errors = new ExpectedErrors();
        StringBuilder sb = new StringBuilder();
        String name = "grp" + Randomly.fromOptions(0, 1, 2, 3);
        if (Randomly.getBoolean()) {
            sb.append("CREATE TABLEGROUP IF NOT EXISTS ").append(name);
        } else {
            sb.append("DROP TABLEGROUP IF EXISTS ").append(name);
        }

        errors.add("Tablegroup");
        errors.add("tablegroup");
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("is not empty");
        errors.add("being used");
        errors.add("not supported");
        errors.add("This statement not supported yet");
        errors.add("syntax error");
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }
}
