package sqlancer.yugabyte.ysql.oracle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Main;
import sqlancer.MainOptions;
import sqlancer.SQLConnection;
import sqlancer.common.DBMSCommon;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLProvider;
import sqlancer.yugabyte.ysql.gen.YSQLTableGenerator;

public class YSQLCatalog implements TestOracle<YSQLGlobalState> {
    protected final YSQLGlobalState state;

    protected final ExpectedErrors errors = new ExpectedErrors();
    protected final Main.StateLogger logger;
    protected final MainOptions options;
    protected final SQLConnection con;

    private final List<YSQLProvider.Action> dmlActions = Arrays.asList(YSQLProvider.Action.INSERT,
            YSQLProvider.Action.UPDATE, YSQLProvider.Action.DELETE);
    private final List<YSQLProvider.Action> catalogActions = new ArrayList<>(Arrays.asList(
            YSQLProvider.Action.CREATE_INDEX, YSQLProvider.Action.CREATE_VIEW, YSQLProvider.Action.REFRESH_VIEW,
            YSQLProvider.Action.CREATE_SEQUENCE, YSQLProvider.Action.ALTER_TABLE, YSQLProvider.Action.SET_CONSTRAINTS,
            YSQLProvider.Action.DISCARD, YSQLProvider.Action.DROP_INDEX, YSQLProvider.Action.COMMENT_ON,
            YSQLProvider.Action.ALTER_DATABASE, YSQLProvider.Action.RESET_ROLE, YSQLProvider.Action.RESET,
            YSQLProvider.Action.ANALYZE, YSQLProvider.Action.SET));
    private final List<YSQLProvider.Action> diskActions = new ArrayList<>(
            Arrays.asList(YSQLProvider.Action.TRUNCATE, YSQLProvider.Action.VACUUM));

    public YSQLCatalog(YSQLGlobalState globalState) {
        this.state = globalState;
        this.con = state.getConnection();
        this.logger = state.getLogger();
        this.options = state.getOptions();
        if (globalState.isPgCompatible()) {
            // AMP: the PostgreSQL command generators run here continuously; three copies each weight them to
            // about half of the catalog actions, since each generator covers 12 to 30 commands.
            for (int i = 0; i < 3; i++) {
                catalogActions.addAll(
                        Arrays.asList(YSQLProvider.Action.PG_RELATION_DDL, YSQLProvider.Action.PG_USER_DEFINED_OBJECT,
                                YSQLProvider.Action.PG_SESSION_COMMAND, YSQLProvider.Action.PG_EXTERNAL_OBJECT));
            }
            // Targets for ALTER POLICY and ALTER RULE, which otherwise exist only after database generation.
            catalogActions.addAll(Arrays.asList(YSQLProvider.Action.CREATE_POLICY, YSQLProvider.Action.CREATE_RULE));
            // Storage maintenance and partitions, which rewrite, truncate and re-route heap pages.
            catalogActions.addAll(Arrays.asList(YSQLProvider.Action.VACUUM, YSQLProvider.Action.CLUSTER,
                    YSQLProvider.Action.REINDEX, YSQLProvider.Action.PARTITION, YSQLProvider.Action.PG_STORAGE_PROBE,
                    YSQLProvider.Action.COPY_TO));
        } else {
            // Checks index/table consistency after the DDL and DML churn above.
            diskActions.add(YSQLProvider.Action.INDEX_CHECK);
        }
    }

    private YSQLProvider.Action getRandomAction(List<YSQLProvider.Action> actions) {
        return actions.get(state.getRandomly().getInteger(0, actions.size()));
    }

    protected void createTables(YSQLGlobalState globalState, int numTables) throws Exception {
        while (globalState.getSchema().getDatabaseTables().size() < numTables) {
            try {
                String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
                SQLQueryAdapter createTable = YSQLTableGenerator.generate(tableName, true, globalState);
                globalState.executeStatement(createTable);
                globalState.getManager().incrementSelectQueryCount();
            } catch (IgnoreMeException e) {
                // do nothing
            }
            // Also ends an aborted transaction, which would otherwise fail every retry of this loop.
            globalState.executeStatement(new SQLQueryAdapter("COMMIT", true));
        }
    }

    @Override
    public void check() throws Exception {
        int seed = state.getRandomly().getInteger(1, 100);
        if (seed > 95) {
            // 5%: create a new table (may be temp)
            createTables(state, 1);
        } else {
            YSQLProvider.Action randomAction;

            if (seed > 55) {
                // 40%: catalog DDL actions (indexes, views, sequences, alter table, etc.)
                randomAction = getRandomAction(catalogActions);
            } else if (seed > 15) {
                // 40%: DML actions
                randomAction = getRandomAction(dmlActions);
            } else {
                // 15%: disk actions (truncate, vacuum, index check)
                randomAction = getRandomAction(diskActions);
            }
            randomAction.getQuery(state).execute(state);
        }
        state.getManager().incrementSelectQueryCount();
    }
}
