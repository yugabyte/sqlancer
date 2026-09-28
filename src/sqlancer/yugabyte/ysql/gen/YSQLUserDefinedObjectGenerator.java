package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLOptions.YSQLOracleFactory;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * PostgreSQL user-defined objects: collations (a nondeterministic ICU collation can be applied to a text column, which
 * changes comparison semantics under every oracle), aggregates, operators, operator classes and families, casts,
 * procedures, ALTER FUNCTION/ROUTINE/TRIGGER/POLICY/RULE/STATISTICS and text search dictionaries/configurations.
 * PostgreSQL-compatible mode (AMP) only.
 */
public final class YSQLUserDefinedObjectGenerator {

    private static final String PUBLIC = "'public'::regnamespace";
    private static final String OWN_COLLATIONS = "SELECT collname FROM pg_collation WHERE collnamespace = " + PUBLIC;
    private static final String OWN_AGGREGATES = "SELECT oid::regprocedure::text FROM pg_proc WHERE prokind = 'a'"
            + " AND pronamespace = " + PUBLIC;
    private static final String OWN_OPERATORS = "SELECT oid::regoperator::text FROM pg_operator WHERE oprnamespace = "
            + PUBLIC;
    private static final String OWN_OPCLASSES = "SELECT opcname FROM pg_opclass WHERE opcnamespace = " + PUBLIC;
    private static final String OWN_OPFAMILIES = "SELECT opfname FROM pg_opfamily WHERE opfnamespace = " + PUBLIC;
    private static final String OWN_FUNCTIONS = "SELECT oid::regprocedure::text FROM pg_proc WHERE prokind = 'f'"
            + " AND pronamespace = " + PUBLIC;
    private static final String OWN_PROCEDURES = "SELECT oid::regprocedure::text FROM pg_proc WHERE prokind = 'p'"
            + " AND pronamespace = " + PUBLIC;
    private static final String OWN_CASTS = "SELECT format('%s AS %s', castsource::regtype, casttarget::regtype)"
            + " FROM pg_cast WHERE oid >= 16384";
    private static final String OWN_TS_DICTIONARIES = "SELECT dictname FROM pg_ts_dict WHERE dictnamespace = " + PUBLIC;
    private static final String OWN_TS_CONFIGS = "SELECT cfgname FROM pg_ts_config WHERE cfgnamespace = " + PUBLIC;

    private enum Kind {
        CREATE_COLLATION, APPLY_COLLATION, ALTER_COLLATION, DROP_COLLATION, CREATE_AGGREGATE, ALTER_AGGREGATE,
        DROP_AGGREGATE, CREATE_OPERATOR, ALTER_OPERATOR, DROP_OPERATOR, CREATE_OPERATOR_CLASS, INDEX_WITH_OPCLASS,
        ALTER_OPERATOR_CLASS, DROP_OPERATOR_CLASS, CREATE_CAST, DROP_CAST, CREATE_PROCEDURE, CALL_PROCEDURE,
        ALTER_PROCEDURE, DROP_PROCEDURE, ALTER_FUNCTION, ALTER_ROUTINE, DROP_ROUTINE, ALTER_TRIGGER, ALTER_POLICY,
        ALTER_RULE, ALTER_STATISTICS, CREATE_TEXT_SEARCH, ALTER_TEXT_SEARCH, DROP_TEXT_SEARCH
    }

    private YSQLUserDefinedObjectGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addCommonInsertUpdateErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot drop");
        errors.add("depends on");
        errors.add("must be owner");
        errors.add("is not unique");
        errors.add("violates");
        errors.add("could not create unique index");
        errors.add("nondeterministic collations are not supported");
        errors.add("cannot be cast");
        errors.add("cannot alter type of a column used");
        errors.add("function is not a procedure");
        String sql;
        switch (Randomly.fromOptions(Kind.values())) {
        case CREATE_COLLATION:
            sql = "CREATE COLLATION " + Randomly.fromOptions("", "IF NOT EXISTS ") + YSQLCatalogNames.newName("pc")
                    + Randomly.fromOptions(" (provider = icu, locale = 'und-u-ks-level2', deterministic = false)",
                            " (provider = icu, locale = 'und-u-ks-level1', deterministic = false)",
                            " (provider = icu, locale = 'und')", " (provider = libc, locale = 'C')",
                            " (provider = builtin, locale = 'C.UTF-8')", " FROM \"C\"");
            break;
        case APPLY_COLLATION:
            sql = applyCollation(globalState);
            break;
        case ALTER_COLLATION:
            sql = "ALTER COLLATION " + YSQLCatalogNames.random(globalState, OWN_COLLATIONS) + Randomly.fromOptions(
                    " REFRESH VERSION", " OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("pc"));
            break;
        case DROP_COLLATION:
            sql = "DROP COLLATION " + YSQLCatalogNames.random(globalState, OWN_COLLATIONS)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case CREATE_AGGREGATE:
            sql = "CREATE " + Randomly.fromOptions("", "OR REPLACE ") + "AGGREGATE " + YSQLCatalogNames.newName("pa")
                    + Randomly.fromOptions("(int) (SFUNC = int4pl, STYPE = int, INITCOND = '0')",
                            "(bigint) (SFUNC = int8pl, STYPE = bigint)",
                            "(int) (SFUNC = int4larger, STYPE = int, COMBINEFUNC = int4larger, PARALLEL = SAFE)",
                            "(float8) (SFUNC = float8pl, STYPE = float8, INITCOND = '0')",
                            "(text) (SFUNC = textcat, STYPE = text, INITCOND = '')");
            break;
        case ALTER_AGGREGATE:
            sql = "ALTER AGGREGATE " + YSQLCatalogNames.random(globalState, OWN_AGGREGATES) + Randomly.fromOptions(
                    " OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("pa"), " SET SCHEMA public");
            break;
        case DROP_AGGREGATE:
            sql = "DROP AGGREGATE " + YSQLCatalogNames.random(globalState, OWN_AGGREGATES)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case CREATE_OPERATOR:
            sql = "CREATE OPERATOR " + Randomly.fromOptions("===", "<<<", "@@@", "!=~")
                    + Randomly.fromOptions(
                            " (LEFTARG = int, RIGHTARG = int, FUNCTION = int4eq, COMMUTATOR = ===, RESTRICT = eqsel)",
                            " (LEFTARG = int, RIGHTARG = int, FUNCTION = int4lt, RESTRICT = scalarltsel)",
                            " (LEFTARG = text, RIGHTARG = text, FUNCTION = texteq, HASHES, MERGES)",
                            " (RIGHTARG = int, FUNCTION = int4um)");
            errors.add("is already the commutator of operator");
            break;
        case ALTER_OPERATOR:
            sql = "ALTER OPERATOR " + YSQLCatalogNames.random(globalState, OWN_OPERATORS) + Randomly.fromOptions(
                    " SET (RESTRICT = eqsel, JOIN = eqjoinsel)", " SET (RESTRICT = NONE)", " OWNER TO CURRENT_USER");
            errors.add("only binary operators can have join selectivity");
            errors.add("only boolean operators can have");
            break;
        case DROP_OPERATOR:
            sql = "DROP OPERATOR " + YSQLCatalogNames.random(globalState, OWN_OPERATORS)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case CREATE_OPERATOR_CLASS:
            String family = YSQLCatalogNames.newName("pf");
            sql = "CREATE OPERATOR FAMILY " + family + " USING btree; CREATE OPERATOR CLASS "
                    + YSQLCatalogNames.newName("po") + " FOR TYPE int USING btree FAMILY " + family
                    + " AS OPERATOR 1 <, OPERATOR 2 <=, OPERATOR 3 =, OPERATOR 4 >=, OPERATOR 5 >,"
                    + " FUNCTION 1 btint4cmp(int, int)";
            break;
        case INDEX_WITH_OPCLASS:
            sql = indexWithOperatorClass(globalState);
            errors.add("does not accept data type");
            break;
        case ALTER_OPERATOR_CLASS:
            sql = Randomly.getBoolean()
                    ? "ALTER OPERATOR CLASS " + YSQLCatalogNames.random(globalState, OWN_OPCLASSES) + " USING btree"
                            + Randomly.fromOptions(" OWNER TO CURRENT_USER",
                                    " RENAME TO " + YSQLCatalogNames.newName("po"))
                    : "ALTER OPERATOR FAMILY " + YSQLCatalogNames.random(globalState, OWN_OPFAMILIES) + " USING btree"
                            + Randomly.fromOptions(" OWNER TO CURRENT_USER",
                                    " RENAME TO " + YSQLCatalogNames.newName("pf"));
            break;
        case DROP_OPERATOR_CLASS:
            sql = Randomly.getBoolean()
                    ? "DROP OPERATOR CLASS " + YSQLCatalogNames.random(globalState, OWN_OPCLASSES) + " USING btree"
                            + Randomly.fromOptions("", " CASCADE")
                    : "DROP OPERATOR FAMILY " + YSQLCatalogNames.random(globalState, OWN_OPFAMILIES) + " USING btree"
                            + Randomly.fromOptions("", " CASCADE");
            break;
        case CREATE_CAST:
            sql = "CREATE CAST (" + Randomly.fromOptions("int4range AS text", "text AS int4range", "money AS text",
                    "inet AS int8range") + ") WITH INOUT" + Randomly.fromOptions("", " AS ASSIGNMENT");
            break;
        case DROP_CAST:
            sql = "DROP CAST " + Randomly.fromOptions("", "IF EXISTS ") + "("
                    + YSQLCatalogNames.random(globalState, OWN_CASTS) + ")";
            break;
        case CREATE_PROCEDURE:
            sql = createProcedure(globalState);
            break;
        case CALL_PROCEDURE:
            String procedure = YSQLCatalogNames.random(globalState, OWN_PROCEDURES);
            sql = "CALL " + procedure.substring(0, procedure.indexOf('(')) + "(" + Randomly.getNotCachedInteger(0, 20)
                    + ")";
            break;
        case ALTER_PROCEDURE:
            sql = "ALTER PROCEDURE " + YSQLCatalogNames.random(globalState, OWN_PROCEDURES)
                    + Randomly.fromOptions(" SET work_mem = '8MB'", " RESET ALL", " SECURITY DEFINER",
                            " SECURITY INVOKER", " OWNER TO CURRENT_USER");
            break;
        case DROP_PROCEDURE:
            sql = "DROP PROCEDURE " + YSQLCatalogNames.random(globalState, OWN_PROCEDURES);
            break;
        case ALTER_FUNCTION:
            // Only volatility downgrades: marking a function IMMUTABLE that is not would make constant folding
            // legitimately change results, which the oracles would report as bugs.
            sql = "ALTER FUNCTION " + YSQLCatalogNames.random(globalState, OWN_FUNCTIONS) + functionAction();
            break;
        case ALTER_ROUTINE:
            sql = "ALTER ROUTINE " + YSQLCatalogNames.random(globalState, OWN_FUNCTIONS) + functionAction();
            break;
        case DROP_ROUTINE:
            sql = "DROP ROUTINE " + YSQLCatalogNames.random(globalState, OWN_FUNCTIONS)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case ALTER_TRIGGER:
            String trigger = YSQLCatalogNames.random(globalState,
                    "SELECT tgname || ' ON ' || tgrelid::regclass" + " FROM pg_trigger WHERE NOT tgisinternal");
            sql = "ALTER TRIGGER " + trigger + " RENAME TO " + YSQLCatalogNames.newName("ptr");
            break;
        case ALTER_POLICY:
            sql = alterPolicy(globalState);
            errors.add("only USING expression allowed for");
            errors.add("only WITH CHECK expression allowed for");
            break;
        case ALTER_RULE:
            sql = "ALTER RULE "
                    + YSQLCatalogNames.random(globalState,
                            "SELECT rulename || ' ON ' || tablename FROM pg_rules WHERE schemaname = 'public'")
                    + " RENAME TO " + YSQLCatalogNames.newName("pr");
            break;
        case ALTER_STATISTICS:
            sql = "ALTER STATISTICS " + YSQLCatalogNames.random(globalState, "SELECT stxname FROM pg_statistic_ext")
                    + Randomly.fromOptions(" SET STATISTICS " + Randomly.getNotCachedInteger(-1, 1000),
                            " OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("pst"));
            break;
        case CREATE_TEXT_SEARCH:
            sql = Randomly.getBoolean() ? "CREATE TEXT SEARCH DICTIONARY " + YSQLCatalogNames.newName("pd")
                    + Randomly.fromOptions(" (TEMPLATE = simple, STOPWORDS = english)",
                            " (TEMPLATE = simple, ACCEPT = false)", " (TEMPLATE = snowball, LANGUAGE = english)")
                    : "CREATE TEXT SEARCH CONFIGURATION " + YSQLCatalogNames.newName("pcfg")
                            + Randomly.fromOptions(" (COPY = english)", " (COPY = simple)", " (PARSER = default)");
            break;
        case ALTER_TEXT_SEARCH:
            sql = Randomly.getBoolean()
                    ? "ALTER TEXT SEARCH CONFIGURATION " + YSQLCatalogNames.random(globalState, OWN_TS_CONFIGS)
                            + Randomly.fromOptions(" ALTER MAPPING FOR word, asciiword WITH simple",
                                    " ADD MAPPING FOR int WITH simple", " DROP MAPPING IF EXISTS FOR email",
                                    " OWNER TO CURRENT_USER")
                    : "ALTER TEXT SEARCH DICTIONARY " + YSQLCatalogNames.random(globalState, OWN_TS_DICTIONARIES)
                            + Randomly.fromOptions(" (STOPWORDS = english)", " (ACCEPT = true)",
                                    " OWNER TO CURRENT_USER");
            errors.add("unrecognized Snowball parameter");
            errors.add("unrecognized simple dictionary parameter");
            break;
        case DROP_TEXT_SEARCH:
            sql = Randomly.getBoolean()
                    ? "DROP TEXT SEARCH CONFIGURATION " + YSQLCatalogNames.random(globalState, OWN_TS_CONFIGS)
                    : "DROP TEXT SEARCH DICTIONARY " + YSQLCatalogNames.random(globalState, OWN_TS_DICTIONARIES)
                            + Randomly.fromOptions("", " CASCADE");
            break;
        default:
            throw new AssertionError();
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

    private static String functionAction() {
        return Randomly.fromOptions(" STABLE", " VOLATILE", " COST " + Randomly.getNotCachedInteger(1, 1000),
                " PARALLEL UNSAFE", " PARALLEL RESTRICTED", " CALLED ON NULL INPUT", " SECURITY INVOKER",
                " SET work_mem = '8MB'", " RESET ALL", " OWNER TO CURRENT_USER");
    }

    // Moves a real text column to one of our collations, so every later query compares it under that collation.
    private static String applyCollation(YSQLGlobalState globalState) {
        if (globalState.getDbmsSpecificOptions().oracle.contains(YSQLOracleFactory.PQS)) {
            throw new IgnoreMeException(); // PQS evaluates text comparisons in Java, without collation semantics
        }
        String collation = YSQLCatalogNames.random(globalState, OWN_COLLATIONS);
        List<YSQLTable> tables = globalState.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .collect(Collectors.toList());
        List<YSQLColumn> textColumns = tables.stream().flatMap(t -> t.getColumns().stream())
                .filter(c -> c.getType() == YSQLDataType.TEXT && !c.isGenerated()).collect(Collectors.toList());
        if (textColumns.isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLColumn column = Randomly.fromList(textColumns);
        return "ALTER TABLE " + column.getTable().getName() + " ALTER COLUMN " + column.getName()
                + " TYPE text COLLATE " + collation;
    }

    private static String indexWithOperatorClass(YSQLGlobalState globalState) {
        String opclass = YSQLCatalogNames.random(globalState, OWN_OPCLASSES);
        List<YSQLColumn> intColumns = globalState.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .flatMap(t -> t.getColumns().stream()).filter(c -> c.getType() == YSQLDataType.INT)
                .collect(Collectors.toList());
        if (intColumns.isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLColumn column = Randomly.fromList(intColumns);
        return "CREATE INDEX ON " + column.getTable().getName() + " (" + column.getName() + " " + opclass + ")";
    }

    private static String createProcedure(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        YSQLColumn column = Randomly.fromList(table.getColumns());
        String body = Randomly.fromOptions(
                "UPDATE " + table.getName() + " SET " + column.getName() + " = " + column.getName(),
                "DELETE FROM " + table.getName() + " WHERE false", "SELECT count(*) FROM " + table.getName());
        return "CREATE " + Randomly.fromOptions("", "OR REPLACE ") + "PROCEDURE " + YSQLCatalogNames.newName("pp")
                + "(n int) LANGUAGE " + Randomly.fromOptions("sql AS $$ " + body + " $$",
                        "plpgsql AS $$ BEGIN " + body.replaceFirst("^SELECT", "PERFORM") + "; END $$");
    }

    private static String alterPolicy(YSQLGlobalState globalState) {
        String policy = YSQLCatalogNames.random(globalState,
                "SELECT policyname || '|' || tablename FROM pg_policies WHERE schemaname = 'public'");
        String name = policy.substring(0, policy.indexOf('|'));
        String table = policy.substring(policy.indexOf('|') + 1);
        return "ALTER POLICY " + name + " ON " + table
                + Randomly.fromOptions(" USING (true)", " USING (false)", " WITH CHECK (true)", " TO PUBLIC",
                        " TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("ppl"));
    }
}
