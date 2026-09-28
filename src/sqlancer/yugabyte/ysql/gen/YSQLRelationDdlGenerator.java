package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;

/**
 * PostgreSQL relation DDL the table/view generators do not issue: CREATE TABLE AS, SELECT INTO, ALTER and DROP of
 * views, materialized views, sequences and indexes, and CREATE/ALTER/DROP SCHEMA. PostgreSQL-compatible mode (AMP)
 * only.
 */
public final class YSQLRelationDdlGenerator {

    private static final String PUBLIC_VIEWS = "SELECT viewname FROM pg_views WHERE schemaname = 'public'";
    private static final String PUBLIC_MATVIEWS = "SELECT matviewname FROM pg_matviews WHERE schemaname = 'public'";
    private static final String PUBLIC_SEQUENCES = "SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'";
    private static final String PUBLIC_INDEXES = "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'";
    private static final String OWN_SCHEMAS = "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'ps%'";

    private enum Kind {
        CREATE_TABLE_AS, SELECT_INTO, ALTER_VIEW, DROP_VIEW, ALTER_MATERIALIZED_VIEW, DROP_MATERIALIZED_VIEW,
        ALTER_SEQUENCE, DROP_SEQUENCE, ALTER_INDEX, CREATE_SCHEMA, ALTER_SCHEMA, DROP_SCHEMA
    }

    private YSQLRelationDdlGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addSubqueryErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot drop");
        errors.add("depends on");
        errors.add("cannot alter");
        errors.add("is not a");
        errors.add("must be owner");
        errors.add("cannot include schema elements");
        String sql;
        switch (Randomly.fromOptions(Kind.values())) {
        case CREATE_TABLE_AS:
            sql = createTableAs(globalState);
            break;
        case SELECT_INTO:
            sql = selectInto(globalState);
            break;
        case ALTER_VIEW:
            sql = alterView(globalState);
            errors.add("WITH CHECK OPTION is supported only on automatically updatable views");
            break;
        case DROP_VIEW:
            sql = "DROP VIEW " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, PUBLIC_VIEWS) + Randomly.fromOptions("", " CASCADE");
            break;
        case ALTER_MATERIALIZED_VIEW:
            sql = alterMaterializedView(globalState);
            errors.add("does not support compression");
            break;
        case DROP_MATERIALIZED_VIEW:
            sql = "DROP MATERIALIZED VIEW " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, PUBLIC_MATVIEWS) + Randomly.fromOptions("", " CASCADE");
            break;
        case ALTER_SEQUENCE:
            sql = alterSequence(globalState);
            errors.add("is out of range for sequence data type");
            errors.add("cannot be greater than MAXVALUE");
            errors.add("cannot be less than MINVALUE");
            errors.add("cannot change ownership of identity sequence");
            break;
        case DROP_SEQUENCE:
            sql = "DROP SEQUENCE " + YSQLCatalogNames.random(globalState, PUBLIC_SEQUENCES)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case ALTER_INDEX:
            sql = alterIndex(globalState);
            errors.add("cannot alter statistics on");
            errors.add("column number");
            break;
        case CREATE_SCHEMA:
            String schema = YSQLCatalogNames.newName("ps");
            sql = "CREATE SCHEMA " + Randomly.fromOptions("", "IF NOT EXISTS ") + schema
                    + Randomly.fromOptions("", " AUTHORIZATION CURRENT_USER")
                    + Randomly.fromOptions("", " CREATE TABLE " + schema + "_t (a int, b text)",
                            " CREATE VIEW " + schema + "_v AS SELECT 1 AS a");
            break;
        case ALTER_SCHEMA:
            sql = "ALTER SCHEMA " + YSQLCatalogNames.random(globalState, OWN_SCHEMAS)
                    + Randomly.fromOptions(" OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("ps"));
            break;
        case DROP_SCHEMA:
            sql = "DROP SCHEMA " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, OWN_SCHEMAS) + Randomly.fromOptions("", " CASCADE");
            break;
        default:
            throw new AssertionError();
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

    // New tables keep the source's column names, so the oracles use them like any other table.
    private static String createTableAs(YSQLGlobalState globalState) {
        YSQLTable source = globalState.getSchema().getRandomTable();
        String target = newTableName(globalState);
        return "CREATE " + Randomly.fromOptions("", "UNLOGGED ", "TEMP ") + "TABLE " + target + " AS SELECT * FROM "
                + source.getName() + where(globalState, source) + Randomly.fromOptions("", " WITH NO DATA");
    }

    private static String selectInto(YSQLGlobalState globalState) {
        YSQLTable source = globalState.getSchema().getRandomTable();
        String target = newTableName(globalState);
        return "SELECT * INTO " + Randomly.fromOptions("", "UNLOGGED ", "TEMP ") + target + " FROM " + source.getName()
                + where(globalState, source);
    }

    // The first free tN: dropped tables leave gaps, so the table count is often a name already in use.
    private static String newTableName(YSQLGlobalState globalState) {
        List<String> names = globalState.getSchema().getDatabaseTables().stream().map(YSQLTable::getName)
                .collect(Collectors.toList());
        int i = names.size();
        while (names.contains(DBMSCommon.createTableName(i))) {
            i++;
        }
        return DBMSCommon.createTableName(i);
    }

    private static String where(YSQLGlobalState globalState, YSQLTable table) {
        if (Randomly.getBoolean()) {
            return "";
        }
        return " WHERE " + YSQLVisitor.asString(
                YSQLExpressionGenerator.generateExpression(globalState, table.getColumns(), YSQLDataType.BOOLEAN));
    }

    private static String alterView(YSQLGlobalState globalState) {
        String column = YSQLCatalogNames.random(globalState,
                "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name IN (" + PUBLIC_VIEWS + ")");
        String view = column.substring(0, column.indexOf('.'));
        String col = column.substring(column.indexOf('.') + 1);
        return "ALTER VIEW " + view
                + Randomly.fromOptions(" ALTER COLUMN " + col + " SET DEFAULT NULL",
                        " ALTER COLUMN " + col + " DROP DEFAULT", " SET (check_option = local)",
                        " SET (check_option = cascaded)", " SET (security_barrier = true)",
                        " SET (security_invoker = true)", " RESET (check_option, security_barrier)",
                        " OWNER TO CURRENT_USER", " RENAME COLUMN " + col + " TO " + col + "_r");
    }

    private static String alterMaterializedView(YSQLGlobalState globalState) {
        String column = YSQLCatalogNames.random(globalState,
                "SELECT c.relname || '.' || a.attname FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid"
                        + " WHERE c.relkind = 'm' AND c.relnamespace = 'public'::regnamespace AND a.attnum > 0"
                        + " AND NOT a.attisdropped");
        String view = column.substring(0, column.indexOf('.'));
        String col = column.substring(column.indexOf('.') + 1);
        return "ALTER MATERIALIZED VIEW " + view
                + Randomly.fromOptions(" SET ACCESS METHOD heap",
                        " ALTER COLUMN " + col + " SET STATISTICS " + Randomly.getNotCachedInteger(-1, 1000),
                        " ALTER COLUMN " + col + " SET (n_distinct = " + Randomly.getNotCachedInteger(-1, 100) + ")",
                        " ALTER COLUMN " + col + " SET COMPRESSION pglz", " SET (fillfactor = 50)",
                        " RESET (fillfactor)", " SET WITHOUT CLUSTER", " OWNER TO CURRENT_USER");
    }

    // One variant per option group: PostgreSQL rejects an option given twice.
    private static String alterSequence(YSQLGlobalState globalState) {
        String sequence = YSQLCatalogNames.random(globalState, PUBLIC_SEQUENCES);
        List<String> options = Randomly.nonEmptySubset(
                Randomly.fromOptions(" RESTART", " RESTART WITH " + Randomly.getNotCachedInteger(1, 100)),
                " INCREMENT BY " + Randomly.fromOptions(-5, -1, 1, 2, 10), Randomly.fromOptions(" CYCLE", " NO CYCLE"),
                " CACHE " + Randomly.getNotCachedInteger(1, 20), " NO MAXVALUE", " NO MINVALUE",
                " AS " + Randomly.fromOptions("smallint", "integer", "bigint"), " OWNED BY NONE");
        return "ALTER SEQUENCE " + sequence + String.join("", options);
    }

    private static String alterIndex(YSQLGlobalState globalState) {
        String index = YSQLCatalogNames.random(globalState, PUBLIC_INDEXES);
        return "ALTER INDEX " + Randomly.fromOptions("", "IF EXISTS ") + index
                + Randomly.fromOptions(" SET (fillfactor = " + Randomly.getNotCachedInteger(10, 100) + ")",
                        " RESET (fillfactor)",
                        " ALTER COLUMN 1 SET STATISTICS " + Randomly.getNotCachedInteger(-1, 1000),
                        " SET TABLESPACE pg_default", " RENAME TO " + index + "_r");
    }
}
