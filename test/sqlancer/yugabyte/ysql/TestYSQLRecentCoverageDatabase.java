package sqlancer.yugabyte.ysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLRowValue;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTables;
import sqlancer.yugabyte.ysql.ast.YSQLColumnValue;
import sqlancer.yugabyte.ysql.ast.YSQLConstant;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLRowComparison;
import sqlancer.yugabyte.ysql.ast.YSQLRowComparison.RowComparisonOperator;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLMaterializedViewIndexGenerator;

public class TestYSQLRecentCoverageDatabase {

    private Connection admin;
    private Connection connection;
    private String database;

    // Explicit opt-in: a JDBC URL ending in the database name, with credentials supplied separately.
    @BeforeEach
    public void createDatabase() throws Exception {
        String url = System.getenv("YSQL_COVERAGE_JDBC_URL");
        assumeTrue(url != null);
        String user = System.getenv().getOrDefault("YSQL_COVERAGE_USER", "yugabyte");
        String password = System.getenv().getOrDefault("YSQL_COVERAGE_PASSWORD", "yugabyte");
        database = "sqlancer_coverage_" + UUID.randomUUID().toString().replace("-", "");
        admin = DriverManager.getConnection(url, user, password);
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        connection = DriverManager.getConnection(url.substring(0, url.lastIndexOf('/') + 1) + database, user, password);
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE t0 (a int, b text, PRIMARY KEY (a ASC, b ASC))");
            s.execute("INSERT INTO t0 VALUES (-1, 'p'), (0, 'a'), (3, 'z')");
        }
    }

    @AfterEach
    public void dropDatabase() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
            if (admin != null) {
                try (Statement s = admin.createStatement()) {
                    s.execute("DROP DATABASE IF EXISTS " + database);
                }
            }
        } finally {
            if (admin != null) {
                admin.close();
            }
        }
    }

    @Test
    public void testNullRowBounds() throws Exception {
        try (Statement s = connection.createStatement()) {
            checkRowBounds(s);
        }
    }

    @Test
    public void testCrossTypeAndHashBounds() throws Exception {
        try (Statement s = connection.createStatement()) {
            s.execute("SET enable_seqscan = off");
            assertEquals(2, scalar(s, "SELECT count(*) FROM t0 WHERE a IN (-1::bigint, 3::bigint)"));
            assertEquals(3,
                    scalar(s, "SELECT count(*) FROM t0 WHERE yb_hash_code(a) >= 0 AND yb_hash_code(a) < 65536"));
        }
    }

    @Test
    public void testConcurrentRefresh() throws Exception {
        try (Statement s = connection.createStatement()) {
            checkMaterializedView(connection, s, database);
        }
    }

    @Test
    public void testOrmPredicateTruthValues() throws Exception {
        YSQLColumn a = new YSQLColumn("a", YSQLDataType.SMALLINT);
        YSQLColumn b = new YSQLColumn("b", YSQLDataType.SMALLINT);
        YSQLTable table = new YSQLTable("orm_values", List.of(a, b), List.of(), YSQLTable.TableType.STANDARD, List.of(),
                false, true);
        table.getColumns().forEach(c -> c.setTable(table));
        YSQLGlobalState state = new YSQLGlobalState();
        state.setRandomly(new Randomly(0));
        state.setMainOptions(new MainOptions());
        state.setDbmsSpecificOptions(new YSQLOptions());
        YSQLExpressionGenerator generator = new YSQLExpressionGenerator(state).setColumns(table.getColumns());
        List<YSQLConstant> values = List.of(YSQLConstant.createNullConstant(), YSQLConstant.createIntConstant(0),
                YSQLConstant.createIntConstant(7));
        try (Statement statement = connection.createStatement()) {
            for (YSQLConstant first : values) {
                for (YSQLConstant second : values) {
                    generator
                            .setRowValue(new YSQLRowValue(new YSQLTables(List.of(table)), Map.of(a, first, b, second)));
                    for (int i = 0; i < 30; i++) {
                        YSQLExpression predicate = generator.generateOrmPredicate();
                        YSQLConstant expected = predicate.getExpectedValue();
                        String query = "SELECT " + YSQLVisitor.asString(predicate) + " FROM (VALUES ("
                                + YSQLVisitor.asString(first) + "::smallint, " + YSQLVisitor.asString(second)
                                + "::smallint)) AS orm_values(a, b)";
                        try (ResultSet result = statement.executeQuery(query)) {
                            assertTrue(result.next());
                            assertEquals(expected.isNull() ? null : expected.asBoolean(), result.getObject(1), query);
                            assertFalse(result.next());
                        }
                    }
                }
            }
        }
    }

    private static void checkRowBounds(Statement s) throws Exception {
        s.execute("SET enable_seqscan = off");
        for (RowComparisonOperator op : List.of(RowComparisonOperator.GREATER, RowComparisonOperator.LESS_EQUALS)) {
            YSQLRowComparison predicate = new YSQLRowComparison(
                    List.of(YSQLColumnValue.create(new YSQLColumn("a", YSQLDataType.INT), null),
                            YSQLColumnValue.create(new YSQLColumn("b", YSQLDataType.TEXT), null)),
                    List.of(YSQLConstant.createIntConstant(0), YSQLConstant.createNullConstant()), op);
            assertEquals(1, scalar(s, "SELECT count(*) FROM t0 WHERE " + YSQLVisitor.asString(predicate)));
        }
    }

    private static void checkMaterializedView(Connection con, Statement s, String database) throws Exception {
        s.execute("CREATE MATERIALIZED VIEW mv0 AS SELECT a AS id, json_build_object('b', b) AS payload, "
                + "ARRAY[b] AS labels FROM t0");
        YSQLSchema schema = YSQLSchema.fromConnection(new SQLConnection(con), database);
        assertEquals(List.of(YSQLDataType.INT, YSQLDataType.TEXT_ARRAY, YSQLDataType.JSON),
                schema.getRandomMaterializedView().get(0).getColumns().stream().map(c -> c.getType())
                        .collect(Collectors.toList()));
        YSQLGlobalState state = new YSQLGlobalState() {
            @Override
            public YSQLSchema getSchema() {
                return schema;
            }
        };
        state.setRandomly(new Randomly(0));
        s.execute(YSQLMaterializedViewIndexGenerator.create(state).getQueryString());
        assertFalse(YSQLSchema.fromConnection(new SQLConnection(con), database).getRandomMaterializedView().get(0)
                .getIndexes().isEmpty());
        s.execute("UPDATE t0 SET b = 'changed' WHERE a = 3");
        s.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv0");
        assertEquals(1, scalar(s, "SELECT count(*) FROM mv0 WHERE payload->>'b' = 'changed'"));
    }

    private static int scalar(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
