package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLVacuumGenerator {

    private YSQLVacuumGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = ExpectedErrors.from("VACUUM cannot run inside a transaction block");
        YSQLErrors.addTransactionErrors(errors);
        if (!globalState.isPgCompatible()) {
            return new SQLQueryAdapter("VACUUM", errors);
        }
        // PostgreSQL: VACUUM rewrites (FULL), freezes, truncates the tail of the heap and cleans indexes, which is
        // where a storage layer under the heap sees page removal and relation truncation.
        StringBuilder sb = new StringBuilder("VACUUM");
        // FULL rewrites the table and rejects the options that only make sense for a lazy vacuum.
        List<String> options = Randomly.getBooleanWithRatherLowProbability()
                ? Randomly.subset("FULL", "FREEZE", "ANALYZE", "SKIP_LOCKED", "PROCESS_TOAST TRUE")
                : Randomly.subset("FREEZE", "ANALYZE", "DISABLE_PAGE_SKIPPING", "SKIP_LOCKED",
                        "INDEX_CLEANUP " + Randomly.fromOptions("AUTO", "ON", "OFF"),
                        "PROCESS_MAIN " + Randomly.fromOptions("TRUE", "FALSE"),
                        "PROCESS_TOAST " + Randomly.fromOptions("TRUE", "FALSE"),
                        "TRUNCATE " + Randomly.fromOptions("TRUE", "FALSE"),
                        "PARALLEL " + Randomly.getNotCachedInteger(0, 4),
                        "BUFFER_USAGE_LIMIT '" + Randomly.fromOptions("256kB", "1MB", "0") + "'");
        if (!options.isEmpty()) {
            sb.append(" (").append(String.join(", ", options)).append(")");
        }
        if (Randomly.getBoolean() && !globalState.getSchema().getDatabaseTablesWithoutViews().isEmpty()) {
            List<YSQLTable> tables = Randomly.nonEmptySubset(globalState.getSchema().getDatabaseTablesWithoutViews());
            sb.append(" ").append(tables.stream().map(YSQLTable::getName).collect(Collectors.joining(", ")));
        }
        errors.add("cannot be used with FULL"); // DISABLE_PAGE_SKIPPING, BUFFER_USAGE_LIMIT
        errors.add("cannot be performed in parallel"); // FULL with PARALLEL
        errors.add("PROCESS_TOAST required with VACUUM FULL");
        errors.add("BUFFER_USAGE_LIMIT cannot be specified for VACUUM FULL");
        errors.add("could not obtain lock"); // SKIP_LOCKED is best effort; FULL may still need the lock
        errors.add("deadlock detected");
        errors.add("does not exist");
        return new SQLQueryAdapter(sb.toString(), errors);
    }

}
