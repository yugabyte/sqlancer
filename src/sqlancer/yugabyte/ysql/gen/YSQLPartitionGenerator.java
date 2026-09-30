package sqlancer.yugabyte.ysql.gen;

import java.util.Collections;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

/**
 * Partitions for the partitioned tables the table generator creates: without them every row routed to such a table
 * fails, and partition pruning and partitionwise joins have nothing to work on. Creates partitions (PARTITION OF),
 * attaches standalone tables and detaches partitions.
 */
public final class YSQLPartitionGenerator {

    // name, strategy (h/l/r), number of key columns, type of the first key column ('' for an expression key)
    private static final String PARTITIONED = "SELECT c.relname || chr(1) || p.partstrat::text || chr(1) || p.partnatts"
            + " || chr(1) || coalesce(format_type(a.atttypid, NULL), '') FROM pg_partitioned_table p"
            + " JOIN pg_class c ON c.oid = p.partrelid"
            + " LEFT JOIN pg_attribute a ON a.attrelid = p.partrelid AND a.attnum = p.partattrs[0]"
            + " WHERE c.relnamespace = 'public'::regnamespace";
    private static final String PARTITIONS = "SELECT p.relname || chr(1) || c.relname FROM pg_inherits i"
            + " JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent"
            + " WHERE p.relkind = 'p' AND p.relnamespace = 'public'::regnamespace";

    private YSQLPartitionGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        // Bounds are random, so overlaps, a second DEFAULT, incompatible hash moduli and rows the new bound rejects
        // are all legitimate refusals.
        errors.add("partition");
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("invalid input syntax");
        errors.add("out of range");
        errors.add("cannot be cast");
        errors.add("is not partitioned");
        errors.add("cannot run inside a transaction block"); // DETACH CONCURRENTLY
        errors.add("modulus");
        errors.add("remainder");
        String sql;
        int kind = (int) Randomly.getNotCachedInteger(0, 10);
        if (kind < 7) {
            String[] parent = YSQLCatalogNames.random(globalState, PARTITIONED).split("\\u0001", -1);
            sql = "CREATE TABLE " + YSQLCatalogNames.newTableName(globalState) + " PARTITION OF " + parent[0] + " "
                    + bound(parent);
        } else if (kind < 9) {
            String[] parent = YSQLCatalogNames.random(globalState, PARTITIONED).split("\\u0001", -1);
            String table = YSQLCatalogNames.newTableName(globalState);
            sql = "CREATE TABLE " + table + " (LIKE " + parent[0] + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS); "
                    + "ALTER TABLE " + parent[0] + " ATTACH PARTITION " + table + " " + bound(parent);
        } else {
            String[] partition = YSQLCatalogNames.random(globalState, PARTITIONS).split("\\u0001", -1);
            sql = "ALTER TABLE " + partition[0] + " DETACH PARTITION " + partition[1]
                    + (Randomly.getBooleanWithRatherLowProbability() ? " CONCURRENTLY" : "");
        }
        return new SQLQueryAdapter(sql, errors, true);
    }

    private static String bound(String... parent) {
        String strategy = parent[1];
        int keys = Integer.parseInt(parent[2]);
        String type = parent[3];
        if ("h".equals(strategy)) {
            int modulus = Randomly.fromOptions(2, 4, 8);
            return "FOR VALUES WITH (MODULUS " + modulus + ", REMAINDER " + Randomly.getNotCachedInteger(0, modulus)
                    + ")";
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            return "DEFAULT";
        }
        boolean numeric = type.matches("smallint|integer|bigint|numeric");
        boolean text = type.matches("text|character varying|character");
        if ("l".equals(strategy) && (numeric || text)) {
            StringBuilder sb = new StringBuilder("FOR VALUES IN (");
            for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
                sb.append(i == 0 ? "" : ", ").append(numeric ? String.valueOf(Randomly.getNotCachedInteger(-10, 100))
                        : "'" + (char) ('a' + Randomly.getNotCachedInteger(0, 26)) + "'");
            }
            return sb.append(")").toString();
        }
        if ("r".equals(strategy) && keys == 1 && numeric) {
            long from = Randomly.getNotCachedInteger(-100, 100);
            return "FOR VALUES FROM (" + from + ") TO (" + (from + Randomly.getNotCachedInteger(1, 100)) + ")";
        }
        if ("r".equals(strategy)) {
            String min = String.join(", ", Collections.nCopies(keys, "MINVALUE"));
            String max = String.join(", ", Collections.nCopies(keys, "MAXVALUE"));
            return "FOR VALUES FROM (" + min + ") TO (" + max + ")";
        }
        return "DEFAULT";
    }
}
