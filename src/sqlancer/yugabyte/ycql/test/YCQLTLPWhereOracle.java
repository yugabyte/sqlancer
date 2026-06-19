package sqlancer.yugabyte.ycql.test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.yugabyte.ycql.YCQLErrors;
import sqlancer.yugabyte.ycql.YCQLProvider.YCQLGlobalState;
import sqlancer.yugabyte.ycql.YCQLSchema.YCQLColumn;
import sqlancer.yugabyte.ycql.YCQLSchema.YCQLTable;
import sqlancer.yugabyte.ycql.YCQLSchema.YCQLTables;
import sqlancer.yugabyte.ycql.YCQLToStringVisitor;
import sqlancer.yugabyte.ycql.gen.YCQLExpressionGenerator;

/**
 * Ternary-logic partitioning (TLP) oracle for YCQL - the first logic-bug oracle for this dialect. For a single column c
 * and a type-matched constant k, the predicate {@code c <op> k} partitions a table into three disjoint, exhaustive
 * groups: {@code c <op> k} (true), {@code c <negated-op> k} (false, for non-null c), and {@code c IS NULL} (the only
 * case where the comparison is NULL, since k is non-null). Their combined rows must equal the unfiltered query. YCQL
 * lacks UNION, so partitions run separately and merge client-side; ALLOW FILTERING permits non-partition-key columns.
 *
 * <p>
 * The single-column form is deliberate: YCQL only supports {@code IS NULL} on a column (not an arbitrary expression),
 * so this is the decomposition that is both sound and expressible in YCQL. Any round where a partition raises an
 * expected error is skipped, so unsupported syntax never produces a false positive.
 */
public class YCQLTLPWhereOracle implements TestOracle<YCQLGlobalState> {

    private static final String[] OPS = { "=", "!=", "<", "<=", ">", ">=" };
    private static final String[] NEGATED = { "!=", "=", ">=", ">", "<=", "<" };

    private final YCQLGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public YCQLTLPWhereOracle(YCQLGlobalState globalState) {
        this.state = globalState;
        YCQLErrors.addExpressionErrors(errors);
        errors.add("Query timed out after PT2S");
        errors.add("Invalid Arguments");
        errors.add("Invalid CQL Statement");
        errors.add("Invalid SQL Statement");
        errors.add("Datatype Mismatch");
    }

    @Override
    public void check() throws SQLException {
        YCQLTables tables = state.getSchema().getRandomTableNonEmptyTables();
        YCQLTable table = tables.getTables().get(0);
        List<YCQLColumn> columns = table.getColumns();
        YCQLColumn column = Randomly.fromList(columns);
        YCQLExpressionGenerator gen = new YCQLExpressionGenerator(state).setColumns(columns);

        int opIndex = Randomly.fromOptions(0, 1, 2, 3, 4, 5);
        String constant = YCQLToStringVisitor
                .asString(gen.generateConstantForType(column.getType().getPrimitiveDataType()));
        String col = column.getName();
        String from = state.getDatabaseName() + "." + table.getName();

        String base = "SELECT " + col + " FROM " + from;
        String original = base + " ALLOW FILTERING";
        String whereTrue = base + " WHERE " + col + " " + OPS[opIndex] + " " + constant + " ALLOW FILTERING";
        String whereFalse = base + " WHERE " + col + " " + NEGATED[opIndex] + " " + constant + " ALLOW FILTERING";
        String whereNull = base + " WHERE " + col + " IS NULL ALLOW FILTERING";

        List<String> originalResult = ComparatorHelper.getResultSetFirstColumnAsString(original, errors, state);
        List<String> partitioned = ComparatorHelper.getCombinedResultSet(whereTrue, whereFalse, whereNull,
                new ArrayList<>(List.of(whereTrue, whereFalse, whereNull)), false, state, errors);
        state.getManager().incrementSelectQueryCount();

        ComparatorHelper.assumeResultSetsAreEqual(originalResult, partitioned, original,
                List.of(whereTrue, whereFalse, whereNull), state);
    }
}
