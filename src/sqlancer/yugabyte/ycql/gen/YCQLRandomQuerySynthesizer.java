package sqlancer.yugabyte.ycql.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.yugabyte.ycql.YCQLProvider.YCQLGlobalState;
import sqlancer.yugabyte.ycql.YCQLSchema.YCQLTable;
import sqlancer.yugabyte.ycql.YCQLSchema.YCQLTables;
import sqlancer.yugabyte.ycql.ast.YCQLConstant;
import sqlancer.yugabyte.ycql.ast.YCQLExpression;
import sqlancer.yugabyte.ycql.ast.YCQLSelect;
import sqlancer.yugabyte.ycql.ast.YCQLTableReference;

public final class YCQLRandomQuerySynthesizer {

    private YCQLRandomQuerySynthesizer() {
    }

    public static YCQLSelect generateSelect(YCQLGlobalState globalState, int nrColumns) {
        YCQLTables targetTables = globalState.getSchema().getRandomTableNonEmptyTables();
        YCQLExpressionGenerator gen = new YCQLExpressionGenerator(globalState).setColumns(targetTables.getColumns());
        YCQLSelect select = new YCQLSelect();
        List<YCQLExpression> columns = new ArrayList<>();
        for (int i = 0; i < nrColumns; i++) {
            // Value context: no logical operators (they crash the tserver when evaluated as values).
            YCQLExpression expression = gen.generateValueExpression();
            columns.add(expression);
        }
        select.setFetchColumns(columns);
        List<YCQLTable> tables = targetTables.getTables();
        Optional<YCQLTableReference> table = tables.stream().map(t -> new YCQLTableReference(t)).findFirst();
        select.setFromList(table.stream().collect(Collectors.toList()));
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression());
        }
        if (Randomly.getBoolean()) {
            select.setOrderByClauses(gen.generateValueOrderBys());
        }
        if (Randomly.getBoolean()) {
            select.setGroupByExpressions(Randomly.nonEmptySubset(select.getFetchColumns()));
        }
        if (Randomly.getBoolean()) {
            select.setLimitClause(YCQLConstant.createIntConstant(Randomly.getNotCachedInteger(0, Integer.MAX_VALUE)));
        }
        if (Randomly.getBoolean()) {
            select.setOffsetClause(YCQLConstant.createIntConstant(Randomly.getNotCachedInteger(0, Integer.MAX_VALUE)));
        }
        return select;
    }

}
