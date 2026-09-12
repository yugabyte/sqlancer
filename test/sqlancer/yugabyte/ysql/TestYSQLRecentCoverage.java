package sqlancer.yugabyte.ysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.ast.YSQLCastOperation;
import sqlancer.yugabyte.ysql.ast.YSQLConstant;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLInOperation;
import sqlancer.yugabyte.ysql.ast.YSQLRowComparison;
import sqlancer.yugabyte.ysql.ast.YSQLRowComparison.RowComparisonOperator;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLMaterializedViewIndexGenerator;

public class TestYSQLRecentCoverage {

    @Test
    public void rowComparisonsPreserveNullsAndOperators() {
        for (RowComparisonOperator op : RowComparisonOperator.values()) {
            YSQLRowComparison row = new YSQLRowComparison(
                    List.of(YSQLConstant.createIntConstant(0), YSQLConstant.createTextConstant("a")),
                    List.of(YSQLConstant.createIntConstant(0), YSQLConstant.createNullConstant()), op);
            assertEquals("((0, 'a') " + op.getTextRepresentation() + " (0, NULL))", YSQLVisitor.asString(row));
            assertEquals(YSQLDataType.BOOLEAN, row.getExpressionType());
            assertNull(row.getExpectedValue());
            assertTrue(YSQLVisitor.asExpectedValues(row).contains("NULL"));
        }
    }

    @Test
    public void rowComparisonsRejectInvalidArityAndCopyInputs() {
        List<YSQLExpression> one = List.of(YSQLConstant.createIntConstant(1));
        assertThrows(IllegalArgumentException.class, () -> new YSQLRowComparison(one, one, RowComparisonOperator.LESS));
        List<YSQLExpression> two = new ArrayList<>(List.of(one.get(0), one.get(0)));
        assertThrows(IllegalArgumentException.class, () -> new YSQLRowComparison(one, two, RowComparisonOperator.LESS));
        YSQLRowComparison row = new YSQLRowComparison(two, two, RowComparisonOperator.LESS);
        two.clear();
        assertEquals("((1, 1) < (1, 1))", YSQLVisitor.asString(row));
    }

    @Test
    public void materializedViewIndexLeavesNonEquatablePayloadOutOfKey() {
        YSQLTable view = new YSQLTable("mv0",
                List.of(new YSQLColumn("id", YSQLDataType.INT), new YSQLColumn("payload", YSQLDataType.JSON)),
                List.of(), YSQLTable.TableType.MATERIALIZED_VIEW, List.of(), false, false);
        YSQLGlobalState state = stateWithTables(List.of(view));
        String sql = YSQLMaterializedViewIndexGenerator.create(state).getQueryString();
        assertTrue(sql.startsWith("CREATE UNIQUE INDEX i"));
        assertTrue(sql.contains(" ON mv0(id)"));
        assertFalse(sql.contains("payload"));
        assertTrue(YSQLMaterializedViewIndexGenerator.create(state).couldAffectSchema());
        assertThrows(IgnoreMeException.class,
                () -> YSQLMaterializedViewIndexGenerator.create(stateWithTables(List.of())));
    }

    @Test
    public void seededGeneratorReachesNewPredicateShapes() {
        YSQLGlobalState state = stateWithTables(List.of());
        state.setMainOptions(new MainOptions());
        state.setDbmsSpecificOptions(new YSQLOptions());
        YSQLExpressionGenerator generator = new YSQLExpressionGenerator(state)
                .setColumns(List.of(new YSQLColumn("a", YSQLDataType.INT), new YSQLColumn("b", YSQLDataType.TEXT)));
        boolean rowWithNull = false;
        boolean crossTypeIn = false;
        boolean hashRange = false;
        for (int i = 0; i < 5000 && !(rowWithNull && crossTypeIn && hashRange); i++) {
            try {
                YSQLExpression expression = generator.generateExpression(YSQLDataType.BOOLEAN);
                if (expression instanceof YSQLRowComparison) {
                    rowWithNull |= ((YSQLRowComparison) expression).getRight().stream()
                            .anyMatch(e -> e instanceof YSQLConstant && ((YSQLConstant) e).isNull());
                }
                if (expression instanceof YSQLInOperation) {
                    YSQLInOperation in = (YSQLInOperation) expression;
                    crossTypeIn |= in.getListElements().stream().anyMatch(e -> e instanceof YSQLCastOperation
                            && e.getExpressionType() != in.getExpr().getExpressionType());
                }
                hashRange |= YSQLVisitor.asString(expression).contains("yb_hash_code(");
            } catch (IgnoreMeException ignored) {
                // Skip unsupported expressions.
            }
        }
        assertTrue(rowWithNull, "row comparison with a NULL subkey was not generated");
        assertTrue(crossTypeIn, "cross-type IN list was not generated");
        assertTrue(hashRange, "hash-code range was not generated");
    }

    private static YSQLGlobalState stateWithTables(List<YSQLTable> tables) {
        YSQLGlobalState state = new YSQLGlobalState() {
            @Override
            public YSQLSchema getSchema() {
                return new YSQLSchema(tables, "coverage");
            }
        };
        state.setRandomly(new Randomly(0));
        return state;
    }
}
