package sqlancer.yugabyte.ysql.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLSetGenerator {

    private YSQLSetGenerator() {
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder();
        ArrayList<ConfigurationOption> options = new ArrayList<>(Arrays.asList(ConfigurationOption.values()));
        options.remove(ConfigurationOption.DEFAULT_WITH_OIDS);
        ConfigurationOption option = Randomly.fromList(options);
        sb.append("SET ");
        if (Randomly.getBoolean()) {
            sb.append(Randomly.fromOptions("SESSION", "LOCAL"));
            sb.append(" ");
        }
        sb.append(option.getOptionName());
        sb.append("=");
        if (Randomly.getBoolean()) {
            sb.append("DEFAULT");
        } else {
            sb.append(option.op.apply(globalState.getRandomly()));
        }
        // todo avoiding props that are not represented in YSQL
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("unrecognized configuration parameter");
        errors.add("Failed DDL operation as requested");
        errors.add("value not set when in binary upgrade mode");
        errors.add("cannot be changed");
        errors.add("current transaction is aborted, commands ignored until end of transaction block");
        errors.add("time zone");
        errors.add("not recognized");
        errors.add("SET LOCAL can only be used in transaction blocks");
        errors.add("This statement not supported yet");
        errors.add("invalid input syntax for type");
        errors.add("invalid value for parameter");
        errors.add("invalid locale name");
        errors.add("is not a valid tablespace name");
        errors.add("tablespace");
        errors.add("is not accessible");
        errors.add("permission denied");
        errors.add("parameter cannot be changed without restarting the server");
        // JDBC driver errors when session parameters are changed to incompatible values
        errors.add("The server's client_encoding parameter was changed to");
        errors.add("The server's DateStyle parameter was changed to");
        // YugabyteDB follower read configuration error
        errors.add("cannot enable yb_read_from_followers with a staleness of less than");
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private enum ConfigurationOption {
        // ===== TIER 1: Critical GUCs =====
        // Session Identity
        APPLICATION_NAME("application_name", (r) -> Randomly.fromOptions("'app1'", "'app2'", "'test_client'", "''")),
        CLIENT_ENCODING("client_encoding", (r) -> Randomly.fromOptions("'UTF8'", "'LATIN1'", "'SQL_ASCII'")),
        // Security
        ROW_SECURITY("row_security", (r) -> Randomly.fromOptions("on", "off")),
        // Locale/Format
        DATE_STYLE("DateStyle",
                (r) -> Randomly.fromOptions("'ISO, MDY'", "'ISO, DMY'", "'Postgres, MDY'", "'SQL, MDY'")),
        TIME_ZONE("TimeZone",
                (r) -> Randomly.fromOptions("'UTC'", "'America/New_York'", "'Europe/London'", "'Asia/Tokyo'")),
        INTERVAL_STYLE("IntervalStyle",
                (r) -> Randomly.fromOptions("'postgres'", "'sql_standard'", "'postgres_verbose'", "'iso_8601'")),
        // Timeouts
        STATEMENT_TIMEOUT("statement_timeout", (r) -> Randomly.fromOptions(0, 1000, 5000, 30000)),
        LOCK_TIMEOUT("lock_timeout", (r) -> Randomly.fromOptions(0, 1000, 5000, 10000)),
        IDLE_IN_TRANSACTION_SESSION_TIMEOUT("idle_in_transaction_session_timeout",
                (r) -> Randomly.fromOptions(0, 10000, 60000)),
        IDLE_SESSION_TIMEOUT("idle_session_timeout", (r) -> Randomly.fromOptions(0, 60000, 300000)),
        // YugabyteDB Critical
        YB_READ_FROM_FOLLOWERS("yb_read_from_followers", (r) -> Randomly.fromOptions("on", "off")),
        YB_FOLLOWER_READ_STALENESS_MS("yb_follower_read_staleness_ms",
                (r) -> Randomly.fromOptions(0, 10000, 30000, 60000)),

        // ===== TIER 2: Widely Used =====
        // Query Planning Controls
        ENABLE_SEQSCAN("enable_seqscan", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_INDEXSCAN("enable_indexscan", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_INDEXONLYSCAN("enable_indexonlyscan", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_BITMAPSCAN("enable_bitmapscan", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_TIDSCAN("enable_tidscan", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_HASHJOIN("enable_hashjoin", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_MERGEJOIN("enable_mergejoin", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_NESTLOOP("enable_nestloop", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_HASHAGG("enable_hashagg", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_SORT("enable_sort", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_MATERIAL("enable_material", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_GATHERMERGE("enable_gathermerge", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_PARALLEL_APPEND("enable_parallel_append", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_INCREMENTALSORT("enable_incrementalsort", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_MEMOIZE("enable_memoize", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_PARALLEL_HASH("enable_parallel_hash", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_PARTITION_PRUNING("enable_partition_pruning", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_PARTITIONWISE_JOIN("enable_partitionwise_join", (r) -> Randomly.fromOptions(1, 0)),
        ENABLE_PARTITIONWISE_AGGREGATE("enable_partitionwise_aggregate", (r) -> Randomly.fromOptions(1, 0)),
        // Cost Parameters
        SEQ_PAGE_COST("seq_page_cost", (r) -> Randomly.fromOptions(1.0, 0.5, 2.0)),
        RANDOM_PAGE_COST("random_page_cost", (r) -> Randomly.fromOptions(1.0, 2.0, 4.0, 1.1)),
        CPU_TUPLE_COST("cpu_tuple_cost", (r) -> Randomly.fromOptions(0.01, 0.001, 0.1)),
        CPU_INDEX_TUPLE_COST("cpu_index_tuple_cost", (r) -> Randomly.fromOptions(0.005, 0.001, 0.01)),
        CPU_OPERATOR_COST("cpu_operator_cost", (r) -> Randomly.fromOptions(0.0025, 0.001, 0.01)),
        PARALLEL_SETUP_COST("parallel_setup_cost", (r) -> r.getLong(0, Long.MAX_VALUE)),
        PARALLEL_TUPLE_COST("parallel_tuple_cost", (r) -> r.getLong(0, Long.MAX_VALUE)),
        MIN_PARALLEL_TABLE_SCAN_SIZE("min_parallel_table_scan_size", (r) -> r.getInteger(0, 715827882)),
        MIN_PARALLEL_INDEX_SCAN_SIZE("min_parallel_index_scan_size", (r) -> r.getInteger(0, 715827882)),
        EFFECTIVE_CACHE_SIZE("effective_cache_size", (r) -> r.getInteger(1, 2147483647)),
        // Output Formatting
        BYTEA_OUTPUT("bytea_output", (r) -> Randomly.fromOptions("'hex'", "'escape'")),
        EXTRA_FLOAT_DIGITS("extra_float_digits", (r) -> Randomly.fromOptions(0, 1, 2, 3)),
        CLIENT_MIN_MESSAGES("client_min_messages",
                (r) -> Randomly.fromOptions("'debug5'", "'debug1'", "'log'", "'notice'", "'warning'", "'error'")),
        // Constraint and Validation
        CHECK_FUNCTION_BODIES("check_function_bodies", (r) -> Randomly.fromOptions(1, 0)),
        CONSTRAINT_EXCLUSION("constraint_exclusion", (r) -> Randomly.fromOptions("on", "off", "partition")),
        // Durability
        SYNCHRONOUS_COMMIT("synchronous_commit",
                (r) -> Randomly.fromOptions("on", "off", "local", "remote_write", "remote_apply")),
        // YugabyteDB Widely Used
        YB_ENABLE_EXPRESSION_PUSHDOWN("yb_enable_expression_pushdown", (r) -> Randomly.fromOptions("on", "off")),
        YB_ENABLE_DISTINCT_PUSHDOWN("yb_enable_distinct_pushdown", (r) -> Randomly.fromOptions("on", "off")),
        YB_ENABLE_HASH_BATCH_IN("yb_enable_hash_batch_in", (r) -> Randomly.fromOptions("on", "off")),
        YB_ENABLE_BATCHEDNL("yb_enable_batchednl", (r) -> Randomly.fromOptions("on", "off")),
        YB_BNL_BATCH_SIZE("yb_bnl_batch_size", (r) -> Randomly.fromOptions(1, 128, 1024, 4096)),
        YB_PREFER_BNL("yb_prefer_bnl", (r) -> Randomly.fromOptions("on", "off")),
        YB_ENABLE_BITMAPSCAN("yb_enable_bitmapscan", (r) -> Randomly.fromOptions("on", "off")),
        YB_ENABLE_PARALLEL_APPEND("yb_enable_parallel_append", (r) -> Randomly.fromOptions("on", "off")),

        // ===== TIER 3: Edge Cases =====
        // Prepared Statement Handling
        PLAN_CACHE_MODE("plan_cache_mode",
                (r) -> Randomly.fromOptions("'auto'", "'force_generic_plan'", "'force_custom_plan'")),
        CURSOR_TUPLE_FRACTION("cursor_tuple_fraction", (r) -> Randomly.fromOptions(0.0, 0.1, 0.5, 1.0)),
        GIN_FUZZY_SEARCH_LIMIT("gin_fuzzy_search_limit", (r) -> Randomly.fromOptions(0, 100, 1000)),
        XMLOPTION("xmloption", (r) -> Randomly.fromOptions("'content'", "'document'")),
        DEFAULT_TABLE_ACCESS_METHOD("default_table_access_method", (r) -> Randomly.fromOptions("'heap'", "'ybheap'")),
        DEFAULT_TABLESPACE("default_tablespace", (r) -> Randomly.fromOptions("''", "'pg_default'")),
        // Locale Variants
        LC_MONETARY("lc_monetary", (r) -> Randomly.fromOptions("'C'", "'en_US.UTF-8'")),
        LC_NUMERIC("lc_numeric", (r) -> Randomly.fromOptions("'C'", "'en_US.UTF-8'")),
        LC_TIME("lc_time", (r) -> Randomly.fromOptions("'C'", "'en_US.UTF-8'")),
        // Transaction Edge Cases
        DEFAULT_TRANSACTION_DEFERRABLE("default_transaction_deferrable", (r) -> Randomly.fromOptions("on", "off")),
        COMMIT_SIBLINGS("commit_siblings", (r) -> Randomly.fromOptions(0, 5, 10)),
        // Temp Objects
        TEMP_TABLESPACES("temp_tablespaces", (r) -> Randomly.fromOptions("''", "'pg_default'")),
        // YugabyteDB Edge Cases
        YB_ENABLE_OPTIMIZER_STATISTICS("yb_enable_optimizer_statistics", (r) -> Randomly.fromOptions("on", "off")),
        YB_ENABLE_BASE_SCANS_COST_MODEL("yb_enable_base_scans_cost_model", (r) -> Randomly.fromOptions("on", "off")),

        // ===== Additional existing YugabyteDB internals =====
        YB_DEBUG_REPORT_ERROR_STACKTRACE("yb_debug_report_error_stacktrace",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_DEBUG_LOG_CATCACHE_EVENTS("yb_debug_log_catcache_events", (r) -> Randomly.fromOptions("false", "true")),
        YB_DEBUG_LOG_INTERNAL_RESTARTS("yb_debug_log_internal_restarts", (r) -> Randomly.fromOptions("false", "true")),
        YB_DEBUG_LOG_DOCDB_REQUESTS("yb_debug_log_docdb_requests", (r) -> Randomly.fromOptions("false", "true")),
        YB_NON_DDL_TXN_FOR_SYS_TABLES_ALLOWED("yb_non_ddl_txn_for_sys_tables_allowed",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_TRANSACTION_PRIORITY("yb_transaction_priority",
                (r) -> Randomly.fromOptions(0, 0.1, 0.2, 0.3, 0.4, 1, 0.9, 0.8, 0.7, 0.6)),
        YB_TRANSACTION_PRIORITY_LOWER_BOUND("yb_transaction_priority_lower_bound",
                (r) -> Randomly.fromOptions(0, 0.1, 0.2, 0.3, 0.4)),
        YB_TRANSACTION_PRIORITY_UPPER_BOUND("yb_transaction_priority_upper_bound",
                (r) -> Randomly.fromOptions(1, 0.9, 0.8, 0.7, 0.6)),
        YB_FORMAT_FUNCS_INCLUDE_YB_METADATA("yb_format_funcs_include_yb_metadata",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_GEOLOCATION_COSTING("yb_enable_geolocation_costing", (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_UPSERT_MODE("yb_enable_upsert_mode", (r) -> Randomly.fromOptions("false", "true")),
        YB_PLANNER_CUSTOM_PLAN_FOR_PARTITION_PRUNING("yb_planner_custom_plan_for_partition_pruning",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_INDEX_STATE_FLAGS_UPDATE_DELAY("yb_index_state_flags_update_delay",
                (r) -> Randomly.getNotCachedInteger(200, 1000)),
        YB_PARALLEL_RANGE_ROWS("yb_parallel_range_rows", (r) -> Randomly.fromOptions(1, 512, 1024, 10000)),
        YB_FETCH_ROW_LIMIT("yb_fetch_row_limit", (r) -> Randomly.fromOptions(0, 1, 5, 100, 1024)),
        YB_USE_HASH_SPLITTING_BY_DEFAULT("yb_use_hash_splitting_by_default",
                (r) -> Randomly.fromOptions("false", "true")),
        YSQL_SESSION_MAX_BATCH_SIZE("ysql_session_max_batch_size", (r) -> Randomly.getNotCachedInteger(1, 10000)),
        YSQL_MAX_IN_FLIGHT_OPS("ysql_max_in_flight_ops", (r) -> Randomly.getNotCachedInteger(1, Integer.MAX_VALUE)),
        YB_ENABLE_READ_COMMITTED_ISOLATION("yb_enable_read_committed_isolation",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_MAX_QUERY_LAYER_RETRIES("yb_max_query_layer_retries", (r) -> Randomly.getNotCachedInteger(0, 60)),
        YB_ENABLE_WAIT_QUEUES("yb_enable_wait_queues", (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_OPTIMIZER("yb_enable_optimizer", (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_INDEX_AGGREGATE_PUSHDOWN("yb_enable_index_aggregate_pushdown",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_INDEX_FILTER_PUSHDOWN("yb_enable_index_filter_pushdown",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_PREFER_BNLOOKUP("yb_prefer_bnlookup", (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_HASH_BATCH_EXECUTION("yb_enable_hash_batch_execution", (r) -> Randomly.fromOptions("false", "true")),
        YB_ENABLE_LSM_INDEX_COST_FACTOR("yb_enable_lsm_index_cost_factor",
                (r) -> Randomly.fromOptions("false", "true")),
        YB_LSM_SEEK_COST_COEFFICIENT("yb_lsm_seek_cost_coefficient",
                (r) -> Randomly.fromOptions(0.0, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0)),
        // WAL/Durability
        WAL_COMPRESSION("wal_compression", (r) -> Randomly.fromOptions(1, 0)),
        COMMIT_DELAY("commit_delay", (r) -> r.getInteger(0, 10000)),
        // Statistics
        TRACK_ACTIVITIES("track_activities", (r) -> Randomly.fromOptions(1, 0)),
        TRACK_COUNTS("track_counts", (r) -> Randomly.fromOptions(1, 0)),
        TRACK_IO_TIMING("track_io_timing", (r) -> Randomly.fromOptions(1, 0)),
        TRACK_FUNCTIONS("track_functions", (r) -> Randomly.fromOptions("'none'", "'pl'", "'all'")),
        // Vacuum
        VACUUM_FREEZE_TABLE_AGE("vacuum_freeze_table_age", (r) -> Randomly.fromOptions(0, 5, 10, 100, 500, 2000000000)),
        VACUUM_FREEZE_MIN_AGE("vacuum_freeze_min_age", (r) -> Randomly.fromOptions(0, 5, 10, 100, 500, 1000000000)),
        VACUUM_MULTIXACT_FREEZE_TABLE_AGE("vacuum_multixact_freeze_table_age",
                (r) -> Randomly.fromOptions(0, 5, 10, 100, 500, 2000000000)),
        VACUUM_MULTIXACT_FREEZE_MIN_AGE("vacuum_multixact_freeze_min_age",
                (r) -> Randomly.fromOptions(0, 5, 10, 100, 500, 1000000000)),
        // Compatibility
        DEFAULT_WITH_OIDS("default_with_oids", (r) -> Randomly.fromOptions(0, 1)),
        SYNCHRONIZED_SEQSCANS("synchronize_seqscans", (r) -> Randomly.fromOptions(0, 1)),
        // Other Planner
        DEFAULT_STATISTICS_TARGET("default_statistics_target", (r) -> r.getInteger(1, 10000)),
        FROM_COLLAPSE_LIMIT("from_collapse_limit", (r) -> r.getInteger(1, Integer.MAX_VALUE)),
        JIT("jit", (r) -> Randomly.fromOptions(1, 0)),
        JIT_ABOVE_COST("jit_above_cost", (r) -> Randomly.fromOptions(0, r.getLong(-1, Long.MAX_VALUE - 1))),
        JIT_INLINE_ABOVE_COST("jit_inline_above_cost", (r) -> Randomly.fromOptions(0, r.getLong(-1, Long.MAX_VALUE))),
        JIT_OPTIMIZE_ABOVE_COST("jit_optimize_above_cost",
                (r) -> Randomly.fromOptions(0, r.getLong(-1, Long.MAX_VALUE))),
        JOIN_COLLAPSE_LIMIT("join_collapse_limit", (r) -> r.getInteger(1, Integer.MAX_VALUE)),
        PARALLEL_LEADER_PARTICIPATION("parallel_leader_participation", (r) -> Randomly.fromOptions(1, 0)),
        FORCE_PARALLEL_MODE("force_parallel_mode", (r) -> Randomly.fromOptions("off", "on", "regress")),
        // GEQO
        GEQO("geqo", (r) -> Randomly.fromOptions(1, 0)),
        GEQO_THRESHOLD("geqo_threshold", (r) -> r.getInteger(2, 2147483647)),
        GEQO_EFFORT("geqo_effort", (r) -> r.getInteger(1, 10)),
        GEQO_POOL_SIZE("geqo_pool_size", (r) -> r.getInteger(0, 2147483647)),
        GEQO_GENERATIONS("geqo_generations", (r) -> r.getInteger(0, 2147483647)),
        GEQO_SELECTION_BIAS("geqo_selection_bias", (r) -> Randomly.fromOptions(1.5, 1.8, 2.0)),
        GEQO_SEED("geqo_seed", (r) -> Randomly.fromOptions(0, 0.5, 1));

        private final String optionName;
        private final Function<Randomly, Object> op;

        ConfigurationOption(String optionName, Function<Randomly, Object> op) {
            this.optionName = optionName;
            this.op = op;
        }

        public String getOptionName() {
            return optionName;
        }
    }

}
