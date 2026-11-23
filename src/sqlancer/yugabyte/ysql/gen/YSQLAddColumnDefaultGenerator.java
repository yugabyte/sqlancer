package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.YugabyteBugs;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * Adds a column whose default calls a {@code LANGUAGE sql} function that planning inlines to a constant, then checks in
 * the same implicit transaction that every existing row reads the default. PostgreSQL stores such a default as the
 * column's missing value instead of rewriting the table, so DocDB has to serve the same value for the existing rows. A
 * row that reads anything else raises an unexpected error.
 */
public final class YSQLAddColumnDefaultGenerator {

    static final String MISSING_DEFAULT = "existing rows do not read the ADD COLUMN default";

    private YSQLAddColumnDefaultGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView() && !t.isMaterializedView());
        String[] typeAndValue = Randomly.fromOptions(new String[] { "int", "42" }, new String[] { "bigint", "-7" },
                new String[] { "numeric", "1.5" }, new String[] { "text", "'abc'" },
                new String[] { "boolean", "true" });
        String type = typeAndValue[0];
        String function = "adddef_" + type;
        String volatility = Randomly.fromOptions("", " VOLATILE", " STABLE", " IMMUTABLE");
        String call = function + "()";
        String defaultExpression;
        if (Randomly.getBoolean() || "boolean".equals(type)) {
            defaultExpression = call;
        } else if ("text".equals(type)) {
            defaultExpression = call + " || 'x'";
        } else {
            defaultExpression = call + " + 1";
        }
        String column = DBMSCommon
                .createColumnName(table.getColumns().size() + (int) Randomly.getNotCachedInteger(0, 3));
        String sql = "CREATE OR REPLACE FUNCTION " + function + "() RETURNS " + type + " LANGUAGE sql" + volatility
                + " AS $f$SELECT " + typeAndValue[1] + "$f$;\n" + "ALTER TABLE " + table.getName() + " ADD COLUMN "
                + column + " " + type + (Randomly.getBoolean() ? " NOT NULL" : "") + " DEFAULT " + defaultExpression
                + ";\n" + "DO $$ BEGIN IF EXISTS (SELECT 1 FROM " + table.getName() + " WHERE " + column
                + " IS DISTINCT FROM (" + defaultExpression + ")) THEN RAISE EXCEPTION '" + MISSING_DEFAULT
                + "'; END IF; END $$";

        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot add column to typed table");
        errors.add("cannot add column to a partition");
        errors.add("is not a table");
        errors.add("could not obtain lock");
        errors.add("deadlock detected");
        errors.add("canceling statement due to statement timeout");
        errors.add("tuple concurrently updated");
        errors.add("permission denied");
        errors.add("must be owner of");
        errors.add("tables can have at most");
        errors.add("violates check constraint"); // an existing CHECK rejects the new value during a rewrite
        errors.add("cannot alter type"); // the table is the row type of another table's column
        if (YugabyteBugs.bugAddColumnInlinedSqlDefaultReadsNull) {
            errors.add(MISSING_DEFAULT);
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

}
