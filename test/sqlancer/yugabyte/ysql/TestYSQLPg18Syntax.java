package sqlancer.yugabyte.ysql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.ast.YSQLConstant;
import sqlancer.yugabyte.ysql.ast.YSQLFunctionWithUnknownResult;
import sqlancer.yugabyte.ysql.ast.YSQLIsJson;
import sqlancer.yugabyte.ysql.ast.YSQLJsonTableCount;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTableGenerator;

public class TestYSQLPg18Syntax {

    // Functions from PostgreSQL 17/18 do not exist in YugabyteDB's PostgreSQL 15, so only AMP may generate them.
    @Test
    public void pgOnlyFunctionsAreGatedByMode() {
        assertTrue(names(YSQLDataType.TEXT, true).contains("casefold"));
        assertFalse(names(YSQLDataType.TEXT, false).contains("casefold"));
        assertFalse(names(YSQLDataType.BOOLEAN, false).contains("json_exists"));
        assertTrue(names(YSQLDataType.INT_ARRAY, true).contains("array_sort"));
        assertTrue(names(YSQLDataType.BIGINT, true).contains("crc32"));
    }

    @Test
    public void temporalTableUsesWithoutOverlaps() {
        String sql = YSQLTableGenerator.generateTemporalTable("t0").getQueryString();
        assertTrue(sql.contains("(c0, c1 WITHOUT OVERLAPS)"), sql);
    }

    @Test
    public void jsonNodesRender() {
        String isJson = YSQLVisitor.asString(YSQLIsJson.create(YSQLConstant.createTextConstant("{}")));
        assertTrue(isJson.matches("\\(\\('\\{}'\\) IS (NOT )?JSON.*\\)"), isJson);
        String table = YSQLVisitor.asString(new YSQLJsonTableCount(YSQLConstant.createTextConstant("[1]"), "$[*]"));
        assertTrue(table.startsWith("(SELECT count(*) FROM JSON_TABLE('[1]', '$[*]' COLUMNS"), table);
    }

    // Only AMP generates the SQL/JSON nodes; YugabyteDB mode must never render them.
    @Test
    public void yugabyteModeNeverGeneratesJsonNodes() {
        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state(false));
        for (int i = 0; i < 2000; i++) {
            String sql;
            try {
                sql = YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN))
                        + YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.INT));
            } catch (IgnoreMeException e) {
                continue; // the generator skips some random choices
            }
            assertFalse(sql.contains("JSON_TABLE") || sql.contains(" IS JSON") || sql.contains(" IS NOT JSON"), sql);
        }
    }

    private static List<String> names(YSQLDataType type, boolean pgCompatible) {
        return YSQLFunctionWithUnknownResult.getSupportedFunctions(type, pgCompatible).stream()
                .map(YSQLFunctionWithUnknownResult::getName).collect(Collectors.toList());
    }

    private static YSQLGlobalState state(boolean pgCompatible) {
        YSQLColumn c0 = new YSQLColumn("c0", YSQLDataType.INT);
        YSQLTable table = new YSQLTable("t0", List.of(c0), List.of(), YSQLTable.TableType.STANDARD, List.of(), false,
                true);
        c0.setTable(table);
        YSQLGlobalState state = new YSQLGlobalState() {
            @Override
            public YSQLSchema getSchema() {
                return new YSQLSchema(List.of(table), "pg18");
            }
        };
        state.setRandomly(new Randomly(0));
        state.setMainOptions(new MainOptions());
        YSQLOptions options = new YSQLOptions();
        options.pgCompatibility = pgCompatible;
        state.setDbmsSpecificOptions(options);
        return state;
    }
}
