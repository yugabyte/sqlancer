package sqlancer.yugabyte.ysql.oracle;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTables;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLColumnValue;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLSelect;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Differential oracle that runs the same query twice, the second time with a result-preserving YugabyteDB planner GUC
 * flipped to a non-default value (scan-method, join-method, and DocDB pushdown toggles). These GUCs only change the
 * physical plan, never the logical result, so both runs must return identical (unordered) result sets. A mismatch
 * indicates a planner / pushdown wrong-results bug - the class of bug that has historically shipped in YugabyteDB's
 * batched-nested-loop join and bitmap-scan paths.
 */
public class YSQLScanGUCOracle implements TestOracle<YSQLGlobalState> {

    /**
     * Each entry is a set of {@code param=value} assignments applied together (e.g. YB bitmap scan needs both the YB
     * and the stock PostgreSQL toggle). All defaults below are result-preserving plan-only knobs.
     */
    private static final String[][] GUC_FLIPS = { //
            { "yb_enable_bitmapscan=true", "enable_bitmapscan=true" }, //
            { "enable_seqscan=off" }, //
            { "enable_indexscan=off", "enable_indexonlyscan=off" }, //
            { "enable_hashjoin=off" }, //
            { "enable_mergejoin=off" }, //
            { "enable_nestloop=off" }, //
            { "enable_material=off" }, //
            { "yb_enable_batchednl=off" }, //
            { "yb_prefer_bnl=off" }, //
            { "yb_bnl_batch_size=1" }, //
            { "yb_enable_expression_pushdown=off" }, //
            { "yb_enable_distinct_pushdown=off" }, //
            { "yb_enable_index_aggregate_pushdown=off" }, //
            { "yb_enable_hash_batch_in=off" }, //
            { "yb_enable_saop_pushdown=off" }, //
            { "yb_enable_sequence_pushdown=off" }, //
            { "yb_pushdown_strict_inequality=off" }, //
            { "yb_pushdown_is_not_null=off" }, //
            { "yb_bypass_cond_recheck=off" }, //
            // Force parallel scans: zero the cost thresholds and grant workers so the planner picks a parallel plan.
            // Parallelism only changes the physical plan, so results must match the serial run. Targets the parallel
            // range / colocated / append scan paths.
            { "max_parallel_workers_per_gather=2", "parallel_setup_cost=0", "parallel_tuple_cost=0",
                    "min_parallel_table_scan_size=0", "min_parallel_index_scan_size=0", "yb_parallel_range_rows=1",
                    "yb_enable_parallel_scan_range_sharded=on", "yb_enable_parallel_scan_colocated=on",
                    "yb_enable_parallel_scan_hash_sharded=on" }, //
            { "max_parallel_workers_per_gather=2", "parallel_setup_cost=0", "min_parallel_table_scan_size=0",
                    "yb_parallel_range_rows=1", "yb_enable_parallel_append=on" }, //
            { "max_parallel_workers_per_gather=2", "parallel_setup_cost=0", "min_parallel_table_scan_size=0",
                    "yb_parallel_range_rows=1", "parallel_leader_participation=off" }, //
            // Shrink the DocDB fetch batch so multi-roundtrip paging is exercised; row count must be unaffected.
            { "yb_fetch_row_limit=1" }, //
            { "yb_fetch_row_limit=0", "yb_fetch_size_limit=1024" }, //
            // Cost-model / statistics toggles change plan choice only.
            { "yb_enable_base_scans_cost_model=on" }, //
            { "yb_enable_optimizer_statistics=on" }, //
            // Cost-based optimizer master switch - flip both ways; picks different plans, same results.
            { "yb_enable_cbo=on" }, //
            { "yb_enable_cbo=off" }, //
            // Batched-nested-loop internals (hashing / first-batch optimization) - plan detail only.
            { "yb_bnl_enable_hashing=off" }, //
            { "yb_bnl_optimize_first_batch=off" }, //
            // Planner derivations: advanced index-cond folding, equivalence-class equalities, derived scalar-array ops.
            // These add/remove logically-implied quals and index conditions, so the result set must be unchanged.
            { "yb_enable_advanced_index_cond_fold=off" }, //
            { "yb_enable_derived_equalities=off" }, //
            { "yb_enable_derived_saops=off" }, //
            { "yb_enable_geolocation_costing=off" }, //
            // Newer (2025-2026) planner/executor knobs. All are plan- or estimate-only: mixed-mode pushdown, index-only
            // PK decoding, column-statistics prefetch, legacy row-count estimation, and the LSM merge-scan / SAOP-merge
            // stream limits (the merge-scan path has a recent wrong-results history, e.g. YB #31200 / #30943). Each is
            // flipped away from its shipped default.
            { "yb_mixed_mode_expression_pushdown=off" }, //
            { "yb_mixed_mode_saop_pushdown=on" }, //
            { "yb_enable_primary_key_decode_from_index=on" }, //
            { "yb_prefetch_column_statistics=off" }, //
            { "yb_ignore_bool_cond_for_legacy_estimate=on" }, //
            { "yb_max_merge_scan_streams=1" }, //
            { "yb_max_saop_merge_streams=1" }, //
            // Cost / parallel-split knobs that only steer plan choice, never the row set: a high network-fetch cost,
            // a tiny parallel range size (more, smaller DocDB scan ranges), and the cluster-config geolocation
            // costing source.
            { "yb_network_fetch_cost=1000000" }, //
            { "yb_parallel_range_size=1024" }, //
            { "yb_use_cluster_config_for_geolocation_costing=on" }, //
            // New in yugabyte-db master (Aug-Sep 2026). Each is a QUERY_TUNING_* result-preserving knob (plan or
            // internal-RPC choice, never the row set), flipped to its non-default state.
            { "yb_enable_index_backfill_scan_optimization=off" }, // recently default-on (YB #33649)
            { "yb_disable_parallel_query_in_ddl=on" }, //
            { "yb_enable_parallel_scan_system=on" }, //
            { "yb_plpgsql_disable_prefetch_in_for_query=on" }, //
    };

    private final YSQLGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();
    private final ExpectedErrors gucErrors = gucExpectedErrors();

    public YSQLScanGUCOracle(YSQLGlobalState globalState) {
        this.state = globalState;
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addSubqueryErrors(errors);
    }

    private static ExpectedErrors gucExpectedErrors() {
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("unrecognized configuration parameter");
        errors.add("invalid value for parameter");
        errors.add("cannot be changed");
        errors.add("permission denied");
        errors.add("must be superuser");
        return errors;
    }

    @Override
    public void check() throws SQLException {
        YSQLTables targetTables = state.getSchema().getRandomTableNonEmptyTables();
        List<YSQLColumn> columns = targetTables.getColumns();
        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(columns);

        YSQLSelect select = new YSQLSelect();
        // Plain comma-joined tables only - no LATERAL/join clauses, which are unnecessary here and can render to
        // invalid SQL. The GUC flip is what we are exercising, not the join shape.
        select.setFromList(targetTables.getTables().stream()
                .map(t -> new YSQLSelect.YSQLFromTable(t, Randomly.getBoolean())).collect(Collectors.toList()));
        List<YSQLExpression> fetchColumns = Randomly.nonEmptySubset(columns).stream()
                .map(c -> (YSQLExpression) new YSQLColumnValue(c, null)).collect(Collectors.toList());
        select.setFetchColumns(fetchColumns);
        select.setSelectType(Randomly.getBoolean() ? YSQLSelect.SelectType.ALL : YSQLSelect.SelectType.DISTINCT);
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression(0, YSQLDataType.BOOLEAN));
        }
        String queryString = YSQLVisitor.asString(select);

        List<String> defaultResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);

        String[] flip = Randomly.fromOptions(GUC_FLIPS);
        List<String> flippedResult;
        applyGuc(flip, true);
        try {
            flippedResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);
        } finally {
            applyGuc(flip, false);
        }

        List<String> combined = new ArrayList<>();
        for (String assignment : flip) {
            combined.add("SET " + assignment);
        }
        combined.add(queryString);
        ComparatorHelper.assumeResultSetsAreEqual(defaultResult, flippedResult, queryString, combined, state);
    }

    private void applyGuc(String[] flip, boolean set) throws SQLException {
        for (String assignment : flip) {
            String stmt;
            if (set) {
                stmt = "SET " + assignment;
            } else {
                String param = assignment.substring(0, assignment.indexOf('='));
                stmt = "RESET " + param;
            }
            boolean success = new SQLQueryAdapter(stmt, gucErrors).execute(state);
            if (set && !success) {
                // GUC unavailable on this build/version - the comparison would be meaningless, skip this round.
                throw new IgnoreMeException();
            }
        }
    }
}
