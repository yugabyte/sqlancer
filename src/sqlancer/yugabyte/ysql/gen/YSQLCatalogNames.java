package sqlancer.yugabyte.ysql.gen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

/**
 * Catalog lookups for generators that act on existing objects (views, functions, operators, roles, ...). The catalog is
 * queried at generation time because these objects are not part of {@code YSQLSchema}.
 */
public final class YSQLCatalogNames {

    private YSQLCatalogNames() {
    }

    // A random first-column value of the query; skips the action when there is no candidate.
    public static String random(YSQLGlobalState globalState, String query) {
        List<String> names = all(globalState, query);
        if (names.isEmpty()) {
            throw new IgnoreMeException();
        }
        return Randomly.fromList(names);
    }

    // Every first-column value of the query; IgnoreMeException when the lookup itself fails.
    private static List<String> all(YSQLGlobalState globalState, String query) {
        List<String> names = new ArrayList<>();
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors); // e.g. the lookup runs inside an aborted transaction
        try {
            SQLancerResultSet rs = new SQLQueryAdapter(query, errors, false).executeAndGet(globalState);
            if (rs != null) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                rs.close();
            }
        } catch (Exception e) {
            throw new IgnoreMeException();
        }
        return names;
    }

    // The first free tN. Dropped tables leave gaps, and a name can be taken by a relation the schema snapshot misses
    // (a temporary table, one created since the last refresh), so the catalog decides.
    public static String newTableName(YSQLGlobalState globalState) {
        Set<String> taken = globalState.getSchema().getDatabaseTables().stream().map(YSQLTable::getName)
                .collect(Collectors.toCollection(HashSet::new));
        try {
            taken.addAll(all(globalState, "SELECT relname FROM pg_class WHERE relname ~ '^t[0-9]+$'"));
        } catch (IgnoreMeException e) {
            // the schema's names are the best available
        }
        int i = globalState.getSchema().getDatabaseTables().size();
        while (taken.contains(DBMSCommon.createTableName(i))) {
            i++;
        }
        return DBMSCommon.createTableName(i);
    }

    // Roles are cluster-wide and shared by every SQLancer thread, so their names carry the database name.
    public static String rolePrefix(YSQLGlobalState globalState) {
        return "r_" + globalState.getDatabaseName() + "_";
    }

    public static String newRole(YSQLGlobalState globalState) {
        return rolePrefix(globalState) + Randomly.getNotCachedInteger(0, 50);
    }

    // Database-local objects created by the PostgreSQL command generators: prefix plus a small number, so later
    // statements find and reuse them.
    public static String newName(String prefix) {
        return prefix + Randomly.getNotCachedInteger(0, 5);
    }
}
