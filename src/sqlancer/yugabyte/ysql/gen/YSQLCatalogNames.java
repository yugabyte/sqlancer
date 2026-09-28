package sqlancer.yugabyte.ysql.gen;

import java.util.ArrayList;
import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

/**
 * Catalog lookups for generators that act on existing objects (views, functions, operators, roles, ...). The catalog is
 * queried at generation time because these objects are not part of {@code YSQLSchema}.
 */
public final class YSQLCatalogNames {

    private YSQLCatalogNames() {
    }

    // A random first-column value of the query; skips the action when there is no candidate.
    public static String random(YSQLGlobalState globalState, String query) {
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
        if (names.isEmpty()) {
            throw new IgnoreMeException();
        }
        return Randomly.fromList(names);
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
