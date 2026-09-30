package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

/**
 * PostgreSQL session and administration commands: START TRANSACTION, SHOW, CHECKPOINT, two-phase commit, roles, users
 * and groups, SET SESSION AUTHORIZATION, ALTER DEFAULT PRIVILEGES, REASSIGN/DROP OWNED and ALTER SYSTEM. Everything
 * that changes state beyond the test database only touches objects this generator created (roles carry the database
 * name), and ALTER SYSTEM only resets parameters. PostgreSQL-compatible mode (AMP) only.
 */
public final class YSQLSessionCommandGenerator {

    private enum Kind {
        START_TRANSACTION, SHOW, CHECKPOINT, TWO_PHASE_COMMIT, CREATE_ROLE, ALTER_ROLE, GRANT_ROLE, DROP_ROLE,
        SESSION_AUTHORIZATION, DEFAULT_PRIVILEGES, REASSIGN_OWNED, DROP_OWNED, ALTER_SYSTEM
    }

    private YSQLSessionCommandGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonInsertUpdateErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot be dropped because some objects depend on it");
        errors.add("is a member of role");
        errors.add("already a member");
        errors.add("there is already a transaction in progress");
        errors.add("cannot be executed within a pipeline");
        errors.add("prepared transactions are disabled");
        errors.add("transaction identifier");
        errors.add("current transaction is aborted");
        errors.add("must be called before any query"); // START TRANSACTION modes inside an open transaction
        errors.add("cannot be run inside a transaction block");
        errors.add("cannot run inside a transaction block");
        errors.add("violates");
        errors.add("unrecognized configuration parameter");
        String role = YSQLCatalogNames.newRole(globalState);
        String ownRoles = "SELECT rolname FROM pg_roles WHERE rolname LIKE '"
                + YSQLCatalogNames.rolePrefix(globalState).replace("_", "\\_") + "%'";
        String sql;
        switch (Randomly.fromOptions(Kind.values())) {
        case START_TRANSACTION:
            sql = "START TRANSACTION "
                    + String.join(", ",
                            Randomly.subset(
                                    "ISOLATION LEVEL "
                                            + Randomly.fromOptions("READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"),
                                    Randomly.fromOptions("READ WRITE", "READ ONLY"),
                                    Randomly.fromOptions("DEFERRABLE", "NOT DEFERRABLE")))
                    + "; SHOW transaction_isolation; COMMIT";
            break;
        case SHOW:
            sql = "SHOW " + Randomly.fromOptions("ALL", "work_mem", "search_path", "transaction_isolation",
                    "server_version", "TimeZone", "enable_seqscan", "default_transaction_read_only");
            break;
        case CHECKPOINT:
            sql = "CHECKPOINT";
            break;
        case TWO_PHASE_COMMIT:
            sql = twoPhaseCommit(globalState);
            errors.add("cannot lock rows in"); // materialized views count as tables in the schema
            errors.add("cannot PREPARE a transaction that has operated on temporary objects");
            break;
        case CREATE_ROLE:
            sql = "CREATE " + Randomly.fromOptions("ROLE ", "USER ", "GROUP ") + role
                    + Randomly.fromOptions("", " NOLOGIN", " LOGIN PASSWORD NULL", " CONNECTION LIMIT 5",
                            " VALID UNTIL 'infinity'", " NOINHERIT", " CREATEDB");
            break;
        case ALTER_ROLE:
            sql = "ALTER " + Randomly.fromOptions("ROLE ", "USER ") + YSQLCatalogNames.random(globalState, ownRoles)
                    + Randomly.fromOptions(" SET work_mem = '4MB'", " RESET ALL", " CONNECTION LIMIT 3", " NOLOGIN",
                            " IN DATABASE " + globalState.getDatabaseName() + " SET search_path = public",
                            " RENAME TO " + role);
            break;
        case GRANT_ROLE:
            String member = YSQLCatalogNames.random(globalState, ownRoles);
            sql = Randomly.getBoolean() ? "ALTER GROUP " + member + " ADD USER " + role
                    : "GRANT " + member + " TO " + YSQLCatalogNames.random(globalState, ownRoles)
                            + Randomly.fromOptions("", " WITH ADMIN OPTION", " WITH INHERIT FALSE");
            errors.add("would create a cycle");
            errors.add("cannot be granted back to your own grantor");
            errors.add("is a member of role");
            break;
        case DROP_ROLE:
            sql = "DROP " + Randomly.fromOptions("ROLE ", "USER ", "GROUP ") + Randomly.fromOptions("", "IF EXISTS ")
                    + YSQLCatalogNames.random(globalState, ownRoles);
            break;
        case SESSION_AUTHORIZATION:
            // One implicit transaction: SET LOCAL ends with it, and a failure leaves no transaction block open.
            sql = "SET LOCAL SESSION AUTHORIZATION " + YSQLCatalogNames.random(globalState, ownRoles)
                    + "; SELECT current_user, session_user";
            errors.add("permission denied");
            break;
        case DEFAULT_PRIVILEGES:
            sql = "ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER" + Randomly.fromOptions("", " IN SCHEMA public")
                    + Randomly.fromOptions(" GRANT SELECT ON TABLES TO ", " REVOKE SELECT ON TABLES FROM ",
                            " GRANT USAGE ON SEQUENCES TO ", " GRANT EXECUTE ON FUNCTIONS TO ",
                            " REVOKE ALL ON TYPES FROM ")
                    + YSQLCatalogNames.random(globalState, ownRoles);
            break;
        case REASSIGN_OWNED:
            sql = "REASSIGN OWNED BY " + YSQLCatalogNames.random(globalState, ownRoles) + " TO CURRENT_USER";
            break;
        case DROP_OWNED:
            sql = "DROP OWNED BY " + YSQLCatalogNames.random(globalState, ownRoles)
                    + Randomly.fromOptions("", " CASCADE");
            break;
        case ALTER_SYSTEM:
            String parameter = Randomly.fromOptions("work_mem", "log_min_duration_statement", "random_page_cost",
                    "jit_above_cost");
            sql = Randomly.getBoolean() ? "ALTER SYSTEM SET " + parameter + " = DEFAULT"
                    : "ALTER SYSTEM RESET " + parameter;
            errors.add("permission denied");
            break;
        default:
            throw new AssertionError();
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

    // A row lock cannot fail midway and leave BEGIN open; a failed PREPARE rolls the transaction back itself.
    private static String twoPhaseCommit(YSQLGlobalState globalState) {
        String table = globalState.getSchema().getRandomTable(t -> !t.isView() && !t.isMaterializedView()).getName();
        String gid = "g_" + globalState.getDatabaseName() + "_" + Randomly.getNotCachedInteger(0, 1000000);
        return "BEGIN; SELECT * FROM " + table + " LIMIT 1 FOR UPDATE; PREPARE TRANSACTION '" + gid + "'; "
                + Randomly.fromOptions("COMMIT", "ROLLBACK") + " PREPARED '" + gid + "'";
    }
}
