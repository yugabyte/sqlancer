package sqlancer.yugabyte.ysql.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLMergeScanQueryGenerator {

    public enum Wrapper {
        SUBQUERY, CTE, MATERIALIZED_CTE, UNION_ALL
    }

    private YSQLMergeScanQueryGenerator() {
    }

    public static boolean isCandidate(YSQLTable table) {
        return !table.isView() && !table.isMaterializedView() && table.getColumns().size() >= 3
                && table.getColumns().stream().allMatch(c -> c.getType() == YSQLDataType.INT)
                && table.getColumns().stream().filter(YSQLColumn::isGenerated)
                        .anyMatch(c -> c.getGenerationExpression().contains("yb_hash_code("));
    }

    public static String generate(YSQLTable table) {
        return generate(table, Randomly.fromOptions(Wrapper.values()), Randomly.getBoolean(), Randomly.getBoolean(),
                Randomly.getNotCachedInteger(1, 20), Randomly.getNotCachedInteger(0, 5));
    }

    public static String generate(YSQLTable table, Wrapper wrapper, boolean wide, boolean descending, long limit,
            long offset) {
        if (!isCandidate(table)) {
            throw new IgnoreMeException();
        }
        List<YSQLColumn> columns = table.getColumns();
        List<YSQLColumn> order = new ArrayList<>(columns);
        // Start after the hash input, so ordering can merge streams of the range primary key.
        order.remove(0);
        order.add(columns.get(0));
        String direction = descending ? " DESC NULLS LAST" : " ASC NULLS FIRST";
        String ordering = order.stream().map(c -> c.getName() + direction).collect(Collectors.joining(", "));
        List<String> projection = new ArrayList<>();
        int count = wide ? columns.size() : 2;
        for (int i = 0; i < count; i++) {
            projection.add(columns.get(i % (columns.size() - 1)).getName() + " AS s" + i);
        }
        // Ordering every column makes LIMIT/OFFSET deterministic, including ties across buckets.
        String inner = "SELECT " + String.join(", ", projection) + " FROM " + table.getName() + " ORDER BY " + ordering
                + " LIMIT " + limit + " OFFSET " + offset;
        switch (wrapper) {
        case SUBQUERY:
            return "SELECT * FROM (" + inner + ") AS sq";
        case CTE:
            return "WITH sq AS (" + inner + ") SELECT * FROM sq";
        case MATERIALIZED_CTE:
            return "WITH sq AS MATERIALIZED (" + inner + ") SELECT * FROM sq";
        case UNION_ALL:
            return "SELECT * FROM ((" + inner + ") UNION ALL (" + inner + ")) AS sq";
        default:
            throw new AssertionError(wrapper);
        }
    }
}
