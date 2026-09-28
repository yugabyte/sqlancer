package sqlancer.yugabyte.ysql.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * PostgreSQL objects that normally need external setup, generated in forms that work in one database: foreign-data
 * wrappers without a handler, servers, user mappings and foreign tables (kept in schema pext, so the oracles never
 * query them), publications, subscriptions created with connect = false, event triggers with a no-op function, access
 * methods reusing the built-in handlers, languages and transforms reusing plpgsql and built-in support functions,
 * conversions, large objects, SECURITY LABEL and text search parsers/templates. PostgreSQL-compatible mode (AMP) only.
 */
public final class YSQLExternalObjectGenerator {

    private static final String OWN = "oid >= 16384";
    private static final String OWN_WRAPPERS = "SELECT fdwname FROM pg_foreign_data_wrapper WHERE " + OWN;
    private static final String OWN_SERVERS = "SELECT srvname FROM pg_foreign_server WHERE " + OWN;
    private static final String OWN_FOREIGN_TABLES = "SELECT c.oid::regclass::text FROM pg_foreign_table f"
            + " JOIN pg_class c ON c.oid = f.ftrelid";
    private static final String OWN_PUBLICATIONS = "SELECT pubname FROM pg_publication";
    private static final String OWN_SUBSCRIPTIONS = "SELECT subname FROM pg_subscription WHERE subdbid ="
            + " (SELECT oid FROM pg_database WHERE datname = current_database())";
    private static final String OWN_EVENT_TRIGGERS = "SELECT evtname FROM pg_event_trigger";
    private static final String OWN_ACCESS_METHODS = "SELECT amname FROM pg_am WHERE " + OWN;
    private static final String OWN_LANGUAGES = "SELECT lanname FROM pg_language WHERE " + OWN;
    private static final String OWN_CONVERSIONS = "SELECT conname FROM pg_conversion WHERE " + OWN;
    private static final String OWN_TS_PARSERS = "SELECT prsname FROM pg_ts_parser WHERE " + OWN;
    private static final String OWN_TS_TEMPLATES = "SELECT tmplname FROM pg_ts_template WHERE " + OWN;

    // Foreign tables and user mappings need a server, and the random DDL often drops them all.
    private static final String BASE_SERVER = "DO $$BEGIN CREATE FOREIGN DATA WRAPPER pwb;"
            + " EXCEPTION WHEN duplicate_object THEN NULL; END$$; CREATE SERVER IF NOT EXISTS psvb FOREIGN DATA WRAPPER"
            + " pwb; ";

    private enum Kind {
        CREATE_WRAPPER, ALTER_WRAPPER, DROP_WRAPPER, CREATE_SERVER, ALTER_SERVER, DROP_SERVER, USER_MAPPING,
        CREATE_FOREIGN_TABLE, ALTER_FOREIGN_TABLE, DROP_FOREIGN_TABLE, IMPORT_FOREIGN_SCHEMA, CREATE_PUBLICATION,
        ALTER_PUBLICATION, DROP_PUBLICATION, CREATE_SUBSCRIPTION, ALTER_SUBSCRIPTION, DROP_SUBSCRIPTION,
        CREATE_EVENT_TRIGGER, ALTER_EVENT_TRIGGER, DROP_EVENT_TRIGGER, CREATE_ACCESS_METHOD, USE_ACCESS_METHOD,
        DROP_ACCESS_METHOD, CREATE_LANGUAGE, ALTER_LANGUAGE, DROP_LANGUAGE, CREATE_TRANSFORM, DROP_TRANSFORM,
        CREATE_CONVERSION, ALTER_CONVERSION, DROP_CONVERSION, LARGE_OBJECT, SECURITY_LABEL, CREATE_TS_PARSER_TEMPLATE,
        ALTER_TS_PARSER_TEMPLATE, DROP_TS_PARSER_TEMPLATE
    }

    private YSQLExternalObjectGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot drop");
        errors.add("depends on");
        errors.add("has no handler");
        errors.add("no security label providers have been loaded");
        errors.add("is insufficient to publish logical changes");
        errors.add("is already member of publication");
        errors.add("is not part of the publication");
        errors.add("cannot add relation");
        errors.add("publication \"");
        errors.add("cannot be executed inside a transaction block");
        errors.add("cannot run inside a transaction block");
        errors.add("must be superuser");
        errors.add("must be owner");
        errors.add("permission denied");
        errors.add("option \""); // OPTIONS (ADD x) when x is set, (DROP x) or (SET x) when it is not
        String sql;
        switch (Randomly.fromOptions(Kind.values())) {
        case CREATE_WRAPPER:
            sql = "CREATE FOREIGN DATA WRAPPER " + YSQLCatalogNames.newName("pw")
                    + Randomly.fromOptions("", " OPTIONS (debug 'true')", " NO HANDLER NO VALIDATOR");
            break;
        case ALTER_WRAPPER:
            sql = "ALTER FOREIGN DATA WRAPPER " + YSQLCatalogNames.random(globalState, OWN_WRAPPERS)
                    + Randomly.fromOptions(" OPTIONS (ADD level '1')", " OPTIONS (DROP debug)", " NO VALIDATOR",
                            " OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("pw"));
            break;
        case DROP_WRAPPER:
            sql = "DROP FOREIGN DATA WRAPPER " + YSQLCatalogNames.random(globalState, OWN_WRAPPERS)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case CREATE_SERVER:
            sql = "CREATE SERVER " + Randomly.fromOptions("", "IF NOT EXISTS ") + YSQLCatalogNames.newName("psv")
                    + Randomly.fromOptions("", " TYPE 'test'") + Randomly.fromOptions("", " VERSION '1'")
                    + " FOREIGN DATA WRAPPER " + YSQLCatalogNames.random(globalState, OWN_WRAPPERS);
            break;
        case ALTER_SERVER:
            sql = "ALTER SERVER " + YSQLCatalogNames.random(globalState, OWN_SERVERS)
                    + Randomly.fromOptions(" VERSION '2'", " OPTIONS (ADD host 'localhost')", " OWNER TO CURRENT_USER",
                            " RENAME TO " + YSQLCatalogNames.newName("psv"));
            break;
        case DROP_SERVER:
            sql = "DROP SERVER " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, OWN_SERVERS) + Randomly.fromOptions("", " CASCADE");
            break;
        case USER_MAPPING:
            String server = server(globalState);
            String user = Randomly.fromOptions("CURRENT_USER", "PUBLIC");
            sql = BASE_SERVER + Randomly.fromOptions(
                    "CREATE USER MAPPING " + Randomly.fromOptions("", "IF NOT EXISTS ") + "FOR " + user + " SERVER "
                            + server + " OPTIONS (user 'u')",
                    "ALTER USER MAPPING FOR " + user + " SERVER " + server + " OPTIONS (SET user 'v')",
                    "DROP USER MAPPING " + Randomly.fromOptions("", "IF EXISTS ") + "FOR " + user + " SERVER "
                            + server);
            break;
        case CREATE_FOREIGN_TABLE:
            sql = BASE_SERVER + "CREATE SCHEMA IF NOT EXISTS pext; CREATE FOREIGN TABLE "
                    + Randomly.fromOptions("", "IF NOT EXISTS ") + "pext." + YSQLCatalogNames.newName("pft")
                    + " (a int NOT NULL, b text, c int4range) SERVER " + server(globalState)
                    + Randomly.fromOptions("", " OPTIONS (table_name 'x')");
            break;
        case ALTER_FOREIGN_TABLE:
            sql = "ALTER FOREIGN TABLE " + YSQLCatalogNames.random(globalState, OWN_FOREIGN_TABLES)
                    + Randomly.fromOptions(" ADD COLUMN d numeric", " ALTER COLUMN b SET DEFAULT 'x'",
                            " ALTER COLUMN a DROP NOT NULL", " OPTIONS (ADD schema_name 's')",
                            " ALTER COLUMN b OPTIONS (ADD column_name 'bb')", " OWNER TO CURRENT_USER");
            break;
        case DROP_FOREIGN_TABLE:
            sql = "DROP FOREIGN TABLE " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, OWN_FOREIGN_TABLES);
            break;
        case IMPORT_FOREIGN_SCHEMA:
            sql = BASE_SERVER + "CREATE SCHEMA IF NOT EXISTS pext; IMPORT FOREIGN SCHEMA public"
                    + Randomly.fromOptions("", " LIMIT TO (t0)", " EXCEPT (t0)") + " FROM SERVER " + server(globalState)
                    + " INTO pext";
            break;
        case CREATE_PUBLICATION:
            sql = createPublication(globalState);
            break;
        case ALTER_PUBLICATION:
            YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
            sql = "ALTER PUBLICATION " + YSQLCatalogNames.random(globalState, OWN_PUBLICATIONS)
                    + Randomly.fromOptions(" ADD TABLE " + table.getName(), " DROP TABLE " + table.getName(),
                            " SET TABLE " + table.getName(), " SET (publish = 'insert, truncate')",
                            " SET (publish_generated_columns = stored)", " OWNER TO CURRENT_USER");
            break;
        case DROP_PUBLICATION:
            sql = "DROP PUBLICATION " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, OWN_PUBLICATIONS);
            break;
        case CREATE_SUBSCRIPTION:
            // connect = false: the subscription is only recorded; nothing connects, creates a slot or copies data.
            sql = "CREATE SUBSCRIPTION " + YSQLCatalogNames.newName("psub") + " CONNECTION 'dbname="
                    + globalState.getDatabaseName() + "' PUBLICATION "
                    + YSQLCatalogNames.random(globalState, OWN_PUBLICATIONS)
                    + " WITH (connect = false, enabled = false, create_slot = false, slot_name = NONE)";
            break;
        case ALTER_SUBSCRIPTION:
            sql = "ALTER SUBSCRIPTION " + YSQLCatalogNames.random(globalState, OWN_SUBSCRIPTIONS)
                    + Randomly.fromOptions(" SET (binary = true)", " SET (streaming = parallel)", " DISABLE",
                            " OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("psub"));
            break;
        case DROP_SUBSCRIPTION:
            sql = "DROP SUBSCRIPTION " + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, OWN_SUBSCRIPTIONS);
            break;
        case CREATE_EVENT_TRIGGER:
            String event = Randomly.fromOptions("ddl_command_start", "ddl_command_end", "sql_drop", "table_rewrite",
                    "login");
            // table_rewrite fires only for ALTER TABLE/TYPE; login takes no tag filter.
            String tags = "table_rewrite".equals(event) ? " WHEN TAG IN ('ALTER TABLE', 'ALTER TYPE')"
                    : " WHEN TAG IN ('CREATE TABLE', 'ALTER TABLE')";
            sql = "CREATE OR REPLACE FUNCTION pevt_noop() RETURNS event_trigger LANGUAGE plpgsql AS $$BEGIN END$$;"
                    + " CREATE EVENT TRIGGER " + YSQLCatalogNames.newName("pet") + " ON " + event
                    + ("login".equals(event) || Randomly.getBoolean() ? "" : tags) + " EXECUTE FUNCTION pevt_noop()";
            errors.add("filter value");
            break;
        case ALTER_EVENT_TRIGGER:
            sql = "ALTER EVENT TRIGGER " + YSQLCatalogNames.random(globalState, OWN_EVENT_TRIGGERS)
                    + Randomly.fromOptions(" DISABLE", " ENABLE", " ENABLE REPLICA", " ENABLE ALWAYS",
                            " OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("pet"));
            break;
        case DROP_EVENT_TRIGGER:
            sql = "DROP EVENT TRIGGER " + YSQLCatalogNames.random(globalState, OWN_EVENT_TRIGGERS);
            break;
        case CREATE_ACCESS_METHOD:
            sql = "CREATE ACCESS METHOD " + YSQLCatalogNames.newName("pam")
                    + Randomly.fromOptions(" TYPE TABLE HANDLER heap_tableam_handler", " TYPE INDEX HANDLER bthandler",
                            " TYPE INDEX HANDLER hashhandler");
            break;
        case USE_ACCESS_METHOD:
            // Rewrites a real table through another name for the heap handler; the oracles keep querying it.
            sql = "ALTER TABLE " + globalState.getSchema().getRandomTable(t -> !t.isView()).getName()
                    + " SET ACCESS METHOD "
                    + YSQLCatalogNames.random(globalState, "SELECT amname FROM pg_am WHERE amtype = 't' AND " + OWN);
            errors.add("cannot change access method");
            break;
        case DROP_ACCESS_METHOD:
            sql = "DROP ACCESS METHOD " + YSQLCatalogNames.random(globalState, OWN_ACCESS_METHODS);
            break;
        case CREATE_LANGUAGE:
            sql = "CREATE " + Randomly.fromOptions("", "OR REPLACE ") + Randomly.fromOptions("", "TRUSTED ")
                    + "LANGUAGE " + YSQLCatalogNames.newName("plg") + " HANDLER plpgsql_call_handler"
                    + Randomly.fromOptions("", " VALIDATOR plpgsql_validator", " INLINE plpgsql_inline_handler");
            break;
        case ALTER_LANGUAGE:
            sql = "ALTER LANGUAGE " + YSQLCatalogNames.random(globalState, OWN_LANGUAGES)
                    + Randomly.fromOptions(" OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("plg"));
            break;
        case DROP_LANGUAGE:
            sql = "DROP LANGUAGE " + YSQLCatalogNames.random(globalState, OWN_LANGUAGES)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case CREATE_TRANSFORM:
            String type = Randomly.fromOptions("int", "text");
            sql = "CREATE " + Randomly.fromOptions("", "OR REPLACE ") + "TRANSFORM FOR " + type + " LANGUAGE "
                    + YSQLCatalogNames.random(globalState, OWN_LANGUAGES)
                    + " (FROM SQL WITH FUNCTION array_unnest_support(internal), TO SQL WITH FUNCTION "
                    + ("int".equals(type) ? "int4recv" : "textrecv") + "(internal))";
            errors.add("return data type of TO SQL function must be the transform data type");
            break;
        case DROP_TRANSFORM:
            sql = "DROP TRANSFORM IF EXISTS FOR " + Randomly.fromOptions("int", "text") + " LANGUAGE "
                    + YSQLCatalogNames.random(globalState, OWN_LANGUAGES);
            break;
        case CREATE_CONVERSION:
            sql = "CREATE " + Randomly.fromOptions("", "DEFAULT ") + "CONVERSION " + YSQLCatalogNames.newName("pcv")
                    + Randomly.fromOptions(" FOR 'LATIN1' TO 'UTF8' FROM iso8859_1_to_utf8",
                            " FOR 'UTF8' TO 'LATIN1' FROM utf8_to_iso8859_1",
                            " FOR 'WIN1251' TO 'UTF8' FROM win1251_to_utf8");
            break;
        case ALTER_CONVERSION:
            sql = "ALTER CONVERSION " + YSQLCatalogNames.random(globalState, OWN_CONVERSIONS)
                    + Randomly.fromOptions(" OWNER TO CURRENT_USER", " RENAME TO " + YSQLCatalogNames.newName("pcv"));
            break;
        case DROP_CONVERSION:
            sql = "DROP CONVERSION " + YSQLCatalogNames.random(globalState, OWN_CONVERSIONS);
            break;
        case LARGE_OBJECT:
            sql = "DO $$ DECLARE o oid := lo_create(0); BEGIN EXECUTE format('ALTER LARGE OBJECT %s OWNER TO"
                    + " CURRENT_USER', o); PERFORM lo_put(o, 0, '\\x0102'::bytea); PERFORM lo_unlink(o); END $$";
            break;
        case SECURITY_LABEL:
            sql = "SECURITY LABEL " + Randomly.fromOptions("", "FOR selinux ") + "ON TABLE "
                    + globalState.getSchema().getRandomTable(t -> !t.isView()).getName() + " IS 'label'";
            errors.add("security label provider");
            break;
        case CREATE_TS_PARSER_TEMPLATE:
            sql = Randomly.getBoolean()
                    ? "CREATE TEXT SEARCH PARSER " + YSQLCatalogNames.newName("pprs")
                            + " (START = prsd_start, GETTOKEN = prsd_nexttoken, END = prsd_end, LEXTYPES = prsd_lextype"
                            + Randomly.fromOptions("", ", HEADLINE = prsd_headline") + ")"
                    : "CREATE TEXT SEARCH TEMPLATE " + YSQLCatalogNames.newName("ptpl")
                            + " (INIT = dsimple_init, LEXIZE = dsimple_lexize)";
            break;
        case ALTER_TS_PARSER_TEMPLATE:
            sql = Randomly.getBoolean()
                    ? "ALTER TEXT SEARCH PARSER " + YSQLCatalogNames.random(globalState, OWN_TS_PARSERS) + " RENAME TO "
                            + YSQLCatalogNames.newName("pprs")
                    : "ALTER TEXT SEARCH TEMPLATE " + YSQLCatalogNames.random(globalState, OWN_TS_TEMPLATES)
                            + " RENAME TO " + YSQLCatalogNames.newName("ptpl");
            break;
        case DROP_TS_PARSER_TEMPLATE:
            sql = Randomly.getBoolean()
                    ? "DROP TEXT SEARCH PARSER " + YSQLCatalogNames.random(globalState, OWN_TS_PARSERS)
                            + Randomly.fromOptions("", " CASCADE")
                    : "DROP TEXT SEARCH TEMPLATE " + YSQLCatalogNames.random(globalState, OWN_TS_TEMPLATES)
                            + Randomly.fromOptions("", " CASCADE");
            break;
        default:
            throw new AssertionError();
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

    // An existing server, or the base server that BASE_SERVER creates.
    private static String server(YSQLGlobalState globalState) {
        if (Randomly.getBoolean()) {
            return "psvb";
        }
        try {
            return YSQLCatalogNames.random(globalState, OWN_SERVERS);
        } catch (IgnoreMeException e) {
            return "psvb";
        }
    }

    private static String createPublication(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        return "CREATE PUBLICATION " + YSQLCatalogNames.newName("pub")
                + Randomly.fromOptions(" FOR TABLE " + table.getName(), " FOR TABLE ONLY " + table.getName(),
                        " FOR ALL TABLES", " FOR TABLES IN SCHEMA public",
                        " FOR TABLE " + table.getName() + " (" + table.getRandomColumn().getName() + ")", "")
                // Publishing UPDATE/DELETE makes PostgreSQL reject them on tables without a replica identity, which
                // would
                // stop most DML; INSERT and TRUNCATE have no such requirement.
                + Randomly.fromOptions(" WITH (publish = 'insert')", " WITH (publish = 'insert, truncate')",
                        " WITH (publish = 'insert', publish_via_partition_root = true)");
    }
}
