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

public final class YSQLMaterializedViewIndexGenerator {

    private YSQLMaterializedViewIndexGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        List<YSQLTable> materializedViews = globalState.getSchema().getRandomMaterializedView();
        if (materializedViews.isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable view = Randomly.fromList(materializedViews);
        List<YSQLColumn> indexableColumns = view.getColumns().stream().filter(c -> isIndexable(c.getType()))
                .collect(Collectors.toList());
        if (indexableColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        StringBuilder sb = new StringBuilder("CREATE UNIQUE INDEX ");
        sb.append("i").append(Math.abs(globalState.getRandomly().getInteger() % 1000));
        sb.append(" ON ");
        sb.append(view.getName());
        sb.append("(");
        sb.append(indexableColumns.stream().map(YSQLColumn::getName).collect(Collectors.joining(", ")));
        sb.append(")");

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("could not create unique index");
        errors.add("duplicate key value violates unique constraint");
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("no default operator class");
        errors.add("cannot create index on");
        errors.add("INDEX on column of type");
        errors.add("not supported yet");
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static boolean isIndexable(YSQLDataType type) {
        switch (type) {
        case JSON:
        case POINT:
        case LINE:
        case LSEG:
        case BOX:
        case PATH:
        case POLYGON:
        case CIRCLE:
        case INT_ARRAY:
        case TEXT_ARRAY:
        case BOOLEAN_ARRAY:
            return false;
        default:
            return true;
        }
    }
}
