package sqlancer.yugabyte.ysql.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    // name, strategy (h/l/r), number of key columns, chr(2)-separated types of the key columns ('' for an expression)
    private static final String PARTITIONED = "SELECT c.relname || chr(1) || p.partstrat::text || chr(1) || p.partnatts"
            + " || chr(1) || coalesce((SELECT string_agg(coalesce(format_type(a.atttypid, NULL), ''), chr(2)"
            + " ORDER BY k.n) FROM unnest(p.partattrs::int2[]) WITH ORDINALITY k(attnum, n)"
            + " LEFT JOIN pg_attribute a ON a.attrelid = p.partrelid AND a.attnum = k.attnum), '')"
            + " FROM pg_partitioned_table p JOIN pg_class c ON c.oid = p.partrelid"
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
        // ATTACH checks every row against the constraints copied by LIKE, which can raise any expression error.
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonRangeExpressionErrors(errors);
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
        String[] types = parent[3].split("\\u0002", -1);
        String type = types[0];
        if ("h".equals(strategy)) {
            int modulus = Randomly.fromOptions(2, 4, 8);
            return "FOR VALUES WITH (MODULUS " + modulus + ", REMAINDER " + Randomly.getNotCachedInteger(0, modulus)
                    + ")";
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            return "DEFAULT";
        }
        boolean numeric = isNumeric(type);
        boolean text = type.matches("text|character varying|character");
        if ("l".equals(strategy) && (numeric || text)) {
            StringBuilder sb = new StringBuilder("FOR VALUES IN (");
            for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
                sb.append(i == 0 ? "" : ", ").append(numeric ? String.valueOf(Randomly.getNotCachedInteger(-10, 100))
                        : "'" + (char) ('a' + Randomly.getNotCachedInteger(0, 26)) + "'");
            }
            return sb.append(")").toString();
        }
        if ("r".equals(strategy)) {
            return rangeBound(types);
        }
        return "DEFAULT";
    }

    private static boolean isNumeric(String type) {
        return type.matches("smallint|integer|bigint|numeric");
    }

    // Values for a prefix of the numeric leading key columns, then MINVALUE or MAXVALUE for the rest, e.g.
    // FROM (13, 0) TO (19, MAXVALUE). Multi-column bounds like these are where pruning has skipped partitions and the
    // DEFAULT partition (YB #36208, #36209, #36140). A later column with an equal first value can make the range
    // empty, which CREATE rejects.
    private static String rangeBound(String... types) {
        int numericPrefix = 0;
        while (numericPrefix < types.length && isNumeric(types[numericPrefix])) {
            numericPrefix++;
        }
        int values = numericPrefix == 0 ? 0 : (int) Randomly.getNotCachedInteger(1, numericPrefix + 1);
        boolean tail = values < types.length;
        List<String> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        for (int i = 0; i < values; i++) {
            long lower = Randomly.getNotCachedInteger(-100, 100);
            from.add(String.valueOf(lower));
            if (i == 0) {
                to.add(String.valueOf(lower + Randomly.getNotCachedInteger(tail || values > 1 ? 0 : 1, 100)));
            } else {
                to.add(String.valueOf(Randomly.getNotCachedInteger(-100, 100)));
            }
        }
        // Every bound after MINVALUE or MAXVALUE must repeat it.
        from.addAll(Collections.nCopies(types.length - values,
                values == 0 ? "MINVALUE" : Randomly.fromOptions("MINVALUE", "MAXVALUE")));
        to.addAll(Collections.nCopies(types.length - values,
                values == 0 ? "MAXVALUE" : Randomly.fromOptions("MINVALUE", "MAXVALUE")));
        return "FOR VALUES FROM (" + String.join(", ", from) + ") TO (" + String.join(", ", to) + ")";
    }
}
