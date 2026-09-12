package sqlancer.yugabyte.ysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLRowValue;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTables;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryComparisonOperation;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryComparisonOperation.YSQLBinaryComparisonOperator;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryLogicalOperation;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryLogicalOperation.BinaryLogicalOperator;
import sqlancer.yugabyte.ysql.ast.YSQLConstant;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLInOperation;
import sqlancer.yugabyte.ysql.ast.YSQLPostfixOperation;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLUpdateGenerator;

public class TestYSQLOrmPatterns {

    @Test
    public void predicatesRetainNullSemanticsAndPivotValues() {
        YSQLColumn a = new YSQLColumn("a", YSQLDataType.SMALLINT);
        YSQLColumn b = new YSQLColumn("b", YSQLDataType.SMALLINT);
        YSQLTable table = table(List.of(a, b));
        YSQLGlobalState state = state(table);
        YSQLExpressionGenerator generator = new YSQLExpressionGenerator(state).setColumns(table.getColumns())
                .setRowValue(new YSQLRowValue(new YSQLTables(List.of(table)),
                        Map.of(a, YSQLConstant.createNullConstant(), b, YSQLConstant.createNullConstant())));
        Set<String> shapes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            YSQLExpression expression = generator.generateOrmPredicate();
            YSQLConstant expected = expression.getExpectedValue();
            assertNotNull(expected, "ORM expressions must preserve PQS expected values");
            if (expression instanceof YSQLBinaryComparisonOperation) {
                YSQLBinaryComparisonOperation comparison = (YSQLBinaryComparisonOperation) expression;
                assertTrue(comparison.getLeft() == comparison.getRight());
                if (comparison.getOp() == YSQLBinaryComparisonOperator.IS_NOT_DISTINCT) {
                    assertTrue(expected.asBoolean());
                } else {
                    assertTrue(expected.isNull(), "NULL = NULL and NULL <> NULL are UNKNOWN");
                }
                shapes.add("self");
            } else if (expression instanceof YSQLInOperation) {
                YSQLInOperation in = (YSQLInOperation) expression;
                assertTrue(in.getListElements().get(0) == in.getListElements().get(2));
                assertTrue(expected.isNull());
                shapes.add("in");
            } else {
                YSQLBinaryLogicalOperation logical = (YSQLBinaryLogicalOperation) expression;
                if (logical.getLeft() instanceof YSQLPostfixOperation) {
                    assertTrue(expected.isNull() || expected.asBoolean());
                    shapes.add("optional");
                } else if (logical.getOp() == BinaryLogicalOperator.AND) {
                    assertTrue(expected.isNull());
                    shapes.add("version");
                } else {
                    YSQLBinaryLogicalOperation right = (YSQLBinaryLogicalOperation) logical.getRight();
                    if (right.getLeft() instanceof YSQLPostfixOperation) {
                        assertTrue(expected.isNull() || expected.asBoolean());
                        shapes.add("nullable");
                    } else {
                        assertTrue(expected.isNull());
                        shapes.add("seek");
                    }
                }
            }
            assertEquals(YSQLDataType.BOOLEAN, expression.getExpressionType());
            assertTrue(YSQLVisitor.asString(expression).contains("t0."));
        }
        assertEquals(Set.of("self", "in", "optional", "version", "nullable", "seek"), shapes);
        assertThrows(IgnoreMeException.class,
                () -> new YSQLExpressionGenerator(state).setColumns(List.of()).generateOrmPredicate());
    }

    @Test
    public void sharedExpressionGeneratorAndUpdatesReachOrmPatterns() {
        YSQLColumn a = new YSQLColumn("a", YSQLDataType.SMALLINT);
        YSQLTable table = table(List.of(a));
        YSQLGlobalState state = state(table);
        YSQLExpressionGenerator generator = new YSQLExpressionGenerator(state).setColumns(table.getColumns());
        Set<String> assignments = new HashSet<>();
        boolean selfPredicate = false;
        for (int i = 0; i < 3000 && (assignments.size() < 4 || !selfPredicate); i++) {
            try {
                YSQLExpression expression = generator.generateExpression(YSQLDataType.BOOLEAN);
                if (expression instanceof YSQLBinaryComparisonOperation) {
                    YSQLBinaryComparisonOperation comparison = (YSQLBinaryComparisonOperation) expression;
                    selfPredicate |= comparison.getLeft() == comparison.getRight();
                }
                String update = YSQLUpdateGenerator.create(state).getQueryString();
                if (update.matches("UPDATE t0 SET a=a(?: WHERE .*|;?)")) {
                    assignments.add("self");
                }
                if (update.startsWith("UPDATE t0 SET a=COALESCE(")) {
                    assignments.add("coalesce");
                }
                if (update.startsWith("UPDATE t0 SET a=CASE WHEN ")) {
                    assignments.add("case");
                }
                if (update.startsWith("UPDATE t0 SET a=a + 1") || update.startsWith("UPDATE t0 SET a=a - 1")) {
                    assignments.add("counter");
                }
            } catch (IgnoreMeException ignored) {
                // Skip unsupported expressions.
            }
        }
        assertTrue(selfPredicate, "shared expression generator never selected an ORM self-comparison");
        assertEquals(Set.of("self", "coalesce", "case", "counter"), assignments);
    }

    private static YSQLTable table(List<YSQLColumn> columns) {
        YSQLTable table = new YSQLTable("t0", columns, List.of(), YSQLTable.TableType.STANDARD, List.of(), false, true);
        columns.forEach(c -> c.setTable(table));
        return table;
    }

    private static YSQLGlobalState state(YSQLTable table) {
        YSQLGlobalState state = new YSQLGlobalState() {
            @Override
            public YSQLSchema getSchema() {
                return new YSQLSchema(List.of(table), "orm");
            }
        };
        state.setRandomly(new Randomly(0));
        state.setMainOptions(new MainOptions());
        state.setDbmsSpecificOptions(new YSQLOptions());
        return state;
    }
}
