package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLAlterDatabaseGenerator {

    private YSQLAlterDatabaseGenerator() {
    }

    private enum GUCParameter {
        // Session Identity
        APPLICATION_NAME("application_name", "'app1'", "'app2'", "'test_client'", "''"),
        CLIENT_ENCODING("client_encoding", "'UTF8'", "'LATIN1'", "'SQL_ASCII'"),
        // Security
        ROW_SECURITY("row_security", "'on'", "'off'"),
        // Locale/Format
        DATE_STYLE("DateStyle", "'ISO, MDY'", "'ISO, DMY'", "'ISO, Postgres, MDY'", "'ISO, SQL, MDY'"),
        TIME_ZONE("TimeZone", "'UTC'", "'America/New_York'", "'Europe/London'", "'Asia/Tokyo'"),
        INTERVAL_STYLE("IntervalStyle", "'postgres'", "'sql_standard'", "'postgres_verbose'", "'iso_8601'"),
        // Timeouts
        STATEMENT_TIMEOUT("statement_timeout", "'0'", "'1000'", "'5000'", "'30000'"),
        LOCK_TIMEOUT("lock_timeout", "'0'", "'1000'", "'5000'", "'10000'"),
        IDLE_IN_TRANSACTION_SESSION_TIMEOUT("idle_in_transaction_session_timeout", "'0'", "'10000'", "'60000'"),
        IDLE_SESSION_TIMEOUT("idle_session_timeout", "'0'", "'60000'", "'300000'"),
        // YugabyteDB Critical
        YB_READ_FROM_FOLLOWERS("yb_read_from_followers", "'on'", "'off'"),
        YB_FOLLOWER_READ_STALENESS_MS("yb_follower_read_staleness_ms", "'0'", "'10000'", "'30000'", "'60000'"),
        // Query Planning Controls
        ENABLE_SEQSCAN("enable_seqscan", "'on'", "'off'"),
        ENABLE_INDEXSCAN("enable_indexscan", "'on'", "'off'"),
        ENABLE_INDEXONLYSCAN("enable_indexonlyscan", "'on'", "'off'"),
        ENABLE_BITMAPSCAN("enable_bitmapscan", "'on'", "'off'"),
        ENABLE_TIDSCAN("enable_tidscan", "'on'", "'off'"),
        ENABLE_HASHJOIN("enable_hashjoin", "'on'", "'off'"),
        ENABLE_MERGEJOIN("enable_mergejoin", "'on'", "'off'"),
        ENABLE_NESTLOOP("enable_nestloop", "'on'", "'off'"),
        ENABLE_HASHAGG("enable_hashagg", "'on'", "'off'"),
        ENABLE_SORT("enable_sort", "'on'", "'off'"),
        ENABLE_MATERIAL("enable_material", "'on'", "'off'"),
        // Cost Parameters
        RANDOM_PAGE_COST("random_page_cost", "'1.0'", "'2.0'", "'4.0'", "'1.1'"),
        SEQ_PAGE_COST("seq_page_cost", "'1.0'", "'0.5'", "'2.0'"),
        CPU_TUPLE_COST("cpu_tuple_cost", "'0.01'", "'0.001'", "'0.1'"),
        CPU_INDEX_TUPLE_COST("cpu_index_tuple_cost", "'0.005'", "'0.001'", "'0.01'"),
        CPU_OPERATOR_COST("cpu_operator_cost", "'0.0025'", "'0.001'", "'0.01'"),
        // Output Formatting
        BYTEA_OUTPUT("bytea_output", "'hex'", "'escape'"),
        EXTRA_FLOAT_DIGITS("extra_float_digits", "'0'", "'1'", "'2'", "'3'"),
        CLIENT_MIN_MESSAGES("client_min_messages", "'debug5'", "'debug1'", "'log'", "'notice'", "'warning'", "'error'"),
        // Constraint and Validation
        CHECK_FUNCTION_BODIES("check_function_bodies", "'on'", "'off'"),
        CONSTRAINT_EXCLUSION("constraint_exclusion", "'on'", "'off'", "'partition'"),
        // Durability
        SYNCHRONOUS_COMMIT("synchronous_commit", "'on'", "'off'", "'local'", "'remote_write'", "'remote_apply'"),
        // YugabyteDB Widely Used
        YB_ENABLE_EXPRESSION_PUSHDOWN("yb_enable_expression_pushdown", "'on'", "'off'"),
        YB_ENABLE_DISTINCT_PUSHDOWN("yb_enable_distinct_pushdown", "'on'", "'off'"),
        YB_ENABLE_HASH_BATCH_IN("yb_enable_hash_batch_in", "'on'", "'off'"),
        YB_ENABLE_BATCHEDNL("yb_enable_batchednl", "'on'", "'off'"),
        YB_BNL_BATCH_SIZE("yb_bnl_batch_size", "'1'", "'128'", "'1024'", "'4096'"),
        YB_PREFER_BNL("yb_prefer_bnl", "'on'", "'off'"),
        YB_ENABLE_BITMAPSCAN("yb_enable_bitmapscan", "'on'", "'off'"),
        YB_ENABLE_PARALLEL_APPEND("yb_enable_parallel_append", "'on'", "'off'"),
        // Prepared Statement Handling
        PLAN_CACHE_MODE("plan_cache_mode", "'auto'", "'force_generic_plan'", "'force_custom_plan'"),
        CURSOR_TUPLE_FRACTION("cursor_tuple_fraction", "'0.0'", "'0.1'", "'0.5'", "'1.0'"),
        // Session State Edge Cases
        GIN_FUZZY_SEARCH_LIMIT("gin_fuzzy_search_limit", "'0'", "'100'", "'1000'"),
        XMLOPTION("xmloption", "'content'", "'document'"),
        DEFAULT_TABLE_ACCESS_METHOD("default_table_access_method", "'heap'", "'ybheap'"),
        DEFAULT_TABLESPACE("default_tablespace", "''", "'pg_default'"),
        // Locale Variants
        LC_MONETARY("lc_monetary", "'C'", "'en_US.UTF-8'"),
        LC_NUMERIC("lc_numeric", "'C'", "'en_US.UTF-8'"),
        LC_TIME("lc_time", "'C'", "'en_US.UTF-8'"),
        // Transaction Edge Cases
        DEFAULT_TRANSACTION_DEFERRABLE("default_transaction_deferrable", "'on'", "'off'"),
        COMMIT_SIBLINGS("commit_siblings", "'0'", "'5'", "'10'"),
        // Temp Objects
        TEMP_TABLESPACES("temp_tablespaces", "''", "'pg_default'"),
        // YugabyteDB Edge Cases
        YB_ENABLE_OPTIMIZER_STATISTICS("yb_enable_optimizer_statistics", "'on'", "'off'"),
        YB_ENABLE_BASE_SCANS_COST_MODEL("yb_enable_base_scans_cost_model", "'on'", "'off'");

        private final String name;
        private final String[] values;

        GUCParameter(String name, String... values) {
            this.name = name;
            this.values = values;
        }
    }

    public static SQLQueryAdapter create(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder("ALTER DATABASE ");
        sb.append(globalState.getDatabaseName());

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("unrecognized configuration parameter");
        errors.add("invalid value for parameter");
        errors.add("cannot be changed");
        errors.add("cannot enable");
        errors.add("permission denied");
        errors.add("must be superuser");
        errors.add("must be less than");
        errors.add("must be owner");
        errors.add("current transaction is aborted");
        errors.add("parameter cannot be set after connection start");
        errors.add("not supported yet");
        errors.add("This statement not supported yet");
        errors.add("invalid locale name");
        errors.add("is not a valid tablespace name");
        errors.add("tablespace");
        errors.add("is not accessible");
        YSQLErrors.addTransactionErrors(errors);

        GUCParameter param = Randomly.fromOptions(GUCParameter.values());

        if (Randomly.getBoolean()) {
            sb.append(" SET ");
            sb.append(param.name);
            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(" TO DEFAULT");
            } else {
                sb.append(" TO ");
                sb.append(Randomly.fromOptions(param.values));
            }
        } else {
            if (Randomly.getBoolean()) {
                sb.append(" RESET ");
                sb.append(param.name);
            } else {
                sb.append(" RESET ALL");
            }
        }

        return new SQLQueryAdapter(sb.toString(), errors);
    }
}
