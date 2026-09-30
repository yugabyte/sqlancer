package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * Workloads aimed at a storage layer below the heap (Yugabyte AMP's page server), not at the planner: dropping the
 * buffer cache so the next read fetches pages remotely, GIN/GiST/SP-GiST builds that fail inside a subtransaction and
 * are followed by another build, row locks of changing strength from many subtransactions (multixacts), long chains of
 * value-preserving updates on a few rows, and switching tables between logged and unlogged. PostgreSQL-compatible mode
 * only.
 */
public final class YSQLStorageProbeGenerator {

    private enum Kind {
        CLEAR_BUFFER_CACHE, INDEX_BUILD_IN_SUBTRANSACTION, SUBTRANSACTION_LOCKS, HOT_ROW_CHAIN, SET_LOGGED
    }

    private YSQLStorageProbeGenerator() {
    }

    // AMP's yb_amp_test_utils (a trusted extension) drops every shared buffer; plain PostgreSQL lacks it.
    public static SQLQueryAdapter clearBufferCache() {
        ExpectedErrors errors = ExpectedErrors.from("does not exist", "permission denied");
        YSQLErrors.addTransactionErrors(errors); // includes the connection closing when a run ends
        return new SQLQueryAdapter("SELECT clear_buffer_cache()", errors);
    }

    public static SQLQueryAdapter createTestUtilsExtension() {
        ExpectedErrors errors = ExpectedErrors.from("is not available", "could not open extension control file",
                "permission denied", "does not exist", "not allowed");
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter("CREATE EXTENSION IF NOT EXISTS yb_amp_test_utils", errors);
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonInsertUpdateErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("could not obtain lock");
        errors.add("deadlock detected");
        errors.add("canceling statement due to statement timeout");
        errors.add("cannot perform"); // a rule on the table rewrites the UPDATE or the index build's expression
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView() && !t.isMaterializedView());
        String sql;
        switch (Randomly.fromOptions(Kind.values())) {
        case CLEAR_BUFFER_CACHE:
            return clearBufferCache();
        case INDEX_BUILD_IN_SUBTRANSACTION:
            sql = indexBuildInSubtransaction(table);
            errors.add("division by zero");
            errors.add("range lower bound must be less than or equal to range upper bound");
            errors.add("out of range");
            errors.add("could not create unique index");
            errors.add("functions in index expression must be marked IMMUTABLE");
            errors.add("has no default operator class"); // a text-like column of type name
            break;
        case SUBTRANSACTION_LOCKS:
            sql = "DO $$ BEGIN FOR i IN 1.." + Randomly.getNotCachedInteger(5, 60) + " LOOP BEGIN PERFORM 1 FROM "
                    + table.getName() + " LIMIT " + Randomly.getNotCachedInteger(1, 1000) + " FOR "
                    + Randomly.fromOptions("KEY SHARE", "SHARE", "NO KEY UPDATE", "UPDATE") + "; PERFORM 1 FROM "
                    + table.getName() + " LIMIT " + Randomly.getNotCachedInteger(1, 1000) + " FOR "
                    + Randomly.fromOptions("KEY SHARE", "SHARE")
                    + "; IF i % 3 = 0 THEN RAISE EXCEPTION 'roll back this subtransaction'; END IF;"
                    + " EXCEPTION WHEN raise_exception THEN NULL; END; END LOOP; END $$";
            errors.add("cannot lock rows in");
            errors.add("FOR KEY SHARE is not allowed");
            errors.add("FOR SHARE is not allowed");
            errors.add("FOR UPDATE is not allowed");
            break;
        case HOT_ROW_CHAIN:
            List<YSQLColumn> columns = table.getColumns().stream().filter(c -> !c.isGenerated())
                    .collect(Collectors.toList());
            if (columns.isEmpty()) {
                throw new IgnoreMeException();
            }
            String column = Randomly.fromList(columns).getName();
            // SET c = c leaves every value unchanged, so the oracles are unaffected, but each round adds a new row
            // version: long per-page change chains for the page server to replay.
            sql = "DO $$ BEGIN FOR i IN 1.." + Randomly.getNotCachedInteger(10, 300) + " LOOP UPDATE " + table.getName()
                    + " SET " + column + " = " + column + " WHERE ctid IN (SELECT ctid FROM " + table.getName()
                    + " LIMIT " + Randomly.getNotCachedInteger(1, 5) + "); END LOOP; END $$";
            errors.add("cannot update view");
            errors.add("can only be updated to DEFAULT");
            break;
        case SET_LOGGED:
            sql = "ALTER TABLE " + table.getName() + Randomly.fromOptions(" SET UNLOGGED", " SET LOGGED");
            errors.add("cannot change");
            errors.add("because it is temporary");
            errors.add("is not a table");
            errors.add("partitioned");
            errors.add("references");
            errors.add("referenced by");
            break;
        default:
            throw new AssertionError();
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

    // A build that fails in a subtransaction, then a successful build: AMP tracks GIN/GiST/SP-GiST builds (they are
    // WAL-logged only at the end) and resets that state at transaction end, not at subtransaction abort.
    private static String indexBuildInSubtransaction(YSQLTable table) {
        List<YSQLColumn> ints = table.getColumns().stream()
                .filter(c -> c.getType() == YSQLDataType.INT || c.getType() == YSQLDataType.SMALLINT)
                .collect(Collectors.toList());
        List<YSQLColumn> texts = table.getColumns().stream()
                .filter(c -> c.getType() == YSQLDataType.TEXT || c.getType() == YSQLDataType.VARCHAR)
                .collect(Collectors.toList());
        String failing;
        String succeeding;
        if (!ints.isEmpty() && (texts.isEmpty() || Randomly.getBoolean())) {
            String c = Randomly.fromList(ints).getName() + "::int"; // int4range needs int, not bigint
            failing = Randomly.fromOptions("gin ((ARRAY[1 / (" + c + " - " + c + ")]))",
                    "gist ((int4range(" + c + ", " + c + " - 1)))", "spgist ((int4range(" + c + ", " + c + " - 1)))");
            succeeding = Randomly.fromOptions("gin ((ARRAY[" + c + "]))",
                    "gist ((int4range(" + c + ", " + c + ", '[]')))",
                    "spgist ((int4range(" + c + ", " + c + ", '[]')))");
        } else if (!texts.isEmpty()) {
            String c = Randomly.fromList(texts).getName();
            String perRowError = "(1 / (length(" + c + ") - length(" + c + ")))::text";
            failing = Randomly.fromOptions("gin ((to_tsvector('simple', " + c + " || " + perRowError + ")))",
                    "spgist ((" + c + " || " + perRowError + "))");
            succeeding = Randomly.fromOptions("gin ((to_tsvector('simple', " + c + ")))", "spgist (" + c + ")");
        } else {
            throw new IgnoreMeException();
        }
        // PostgreSQL 18 builds GIN in parallel too.
        String parallel = Randomly.getBoolean() ? "EXECUTE 'SET LOCAL max_parallel_maintenance_workers = 2'; " : "";
        return "DO $$ BEGIN " + parallel + "BEGIN EXECUTE 'CREATE INDEX IF NOT EXISTS "
                + YSQLCatalogNames.newName("pbf") + " ON " + table.getName() + " USING " + failing.replace("'", "''")
                + "'; EXCEPTION WHEN OTHERS THEN NULL; END; EXECUTE 'CREATE INDEX IF NOT EXISTS "
                + YSQLCatalogNames.newName("pbs") + " ON " + table.getName() + " USING " + succeeding.replace("'", "''")
                + "'; END $$";
    }
}
