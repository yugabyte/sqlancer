package sqlancer.yugabyte.ysql;

import static sqlancer.yugabyte.ysql.YSQLOptions.YSQLOracleFactory.CATALOG;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.DBMSCommon;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.yugabyte.ysql.gen.YSQLAlterDatabaseGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLAlterTableGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLAnalyzeGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLCommentGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLCopyGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLCursorGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLDeleteGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLDiscardGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLDoBlockGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLDomainGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLDropIndexGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLExplainGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLFunctionGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLGrantRevokeGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLIndexGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLInsertGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLLockTableGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLMaterializedViewRefresh;
import sqlancer.yugabyte.ysql.gen.YSQLMergeGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLNotifyGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLParallelQueryGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLPolicyGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLPreparedStatementGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLRuleGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLSavepointGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLSequenceGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLSetGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLSimpleVectorGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTableGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTableGroupGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTransactionGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTriggerGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTruncateGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTypeGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLUpdateGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLVacuumGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLViewGenerator;

@AutoService(DatabaseProvider.class)
public class YSQLProvider extends SQLProviderAdapter<YSQLGlobalState, YSQLOptions> {

    /**
     * Global lock for database creation - YugabyteDB cannot create multiple databases simultaneously due to catalog
     * version conflicts across the distributed system.
     */
    private static final Object DATABASE_CREATION_LOCK = new Object();

    /**
     * Safety delay (in ms) before and after database creation to allow catalog changes to propagate.
     */
    private static final long SAFETY_DELAY_MS = 2000;

    /**
     * Generate only data types and expressions that are understood by PQS.
     */
    public static boolean generateOnlyKnown;
    protected String entryURL;
    protected String username;
    protected String password;
    protected String entryPath;
    protected String host;
    protected int port;
    protected String testURL;
    protected String databaseName;
    protected String createDatabaseCommand;

    public YSQLProvider() {
        super(YSQLGlobalState.class, YSQLOptions.class);
    }

    protected YSQLProvider(Class<YSQLGlobalState> globalClass, Class<YSQLOptions> optionClass) {
        super(globalClass, optionClass);
    }

    public static int mapActions(YSQLGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        boolean isCatalogTest = CATALOG.equals(globalState.getDbmsSpecificOptions().oracle.get(0));
        boolean isPgCompat = globalState.isPgCompatible();
        int nrPerformed;
        switch (a) {
        case CREATE_INDEX:
            nrPerformed = isCatalogTest ? r.getInteger(0, 30) : r.getInteger(0, 3);
            break;
        case DISCARD:
        case DROP_INDEX:
            nrPerformed = r.getInteger(0, 5);
            break;
        case COMMIT:
            nrPerformed = r.getInteger(0, 3);
            break;
        case SET_TRANSACTION:
            nrPerformed = r.getInteger(0, 2);
            break;
        case PARALLEL_QUERY_TEST:
            nrPerformed = isPgCompat ? 0 : r.getInteger(0, 1);
            break;
        case ALTER_DATABASE:
            nrPerformed = r.getInteger(0, 3);
            break;
        case ALTER_TABLE:
            nrPerformed = isCatalogTest ? r.getInteger(0, 20) : r.getInteger(0, 5);
            break;
        case RESET:
            nrPerformed = r.getInteger(0, 3);
            break;
        case ANALYZE:
            nrPerformed = r.getInteger(0, 3);
            break;
        case RESET_ROLE:
        case VACUUM:
        case SET_CONSTRAINTS:
        case SET:
        case COMMENT_ON:
        case NOTIFY:
        case LISTEN:
        case UNLISTEN:
            nrPerformed = 0; // LISTEN/NOTIFY disabled (requires ysql_yb_enable_listen_notify flag)
            break;
        case DELETE:
            nrPerformed = r.getInteger(0, 10);
            break;
        case MERGE:
            nrPerformed = r.getInteger(0, 5);
            break;
        case CREATE_TABLEGROUP:
            // Tablegroups are a YugabyteDB-only feature; skip in PostgreSQL-compatibility mode.
            nrPerformed = isPgCompat ? 0 : r.getInteger(0, 2);
            break;
        case VECTOR_TEST:
            nrPerformed = r.getInteger(0, 3);
            break;
        case TRUNCATE:
            nrPerformed = r.getInteger(0, 15);
            break;
        case CREATE_SEQUENCE:
            nrPerformed = isCatalogTest ? r.getInteger(0, 30) : r.getInteger(0, 15);
            break;
        case CREATE_VIEW:
            nrPerformed = isCatalogTest ? r.getInteger(0, 30) : r.getInteger(0, 5);
            break;
        case REFRESH_VIEW:
            nrPerformed = r.getInteger(0, 20);
            break;
        case UPDATE:
            nrPerformed = r.getInteger(0, 20);
            break;
        case INSERT:
            nrPerformed = r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
            break;
        case SAVEPOINT:
            nrPerformed = r.getInteger(0, 3);
            break;
        // New consistency-focused generators
        case PREPARED_STATEMENT:
        case CURSOR:
            nrPerformed = r.getInteger(0, 3);
            break;
        case CREATE_DOMAIN:
        case CREATE_TYPE:
            nrPerformed = r.getInteger(0, 2);
            break;
        case LOCK_TABLE:
            nrPerformed = r.getInteger(0, 2);
            break;
        case EXPLAIN:
            nrPerformed = r.getInteger(0, 3);
            break;
        case CREATE_FUNCTION:
        case CREATE_TRIGGER:
            nrPerformed = r.getInteger(0, 2);
            break;
        case COPY_TO:
            nrPerformed = r.getInteger(0, 2);
            break;
        case CREATE_POLICY:
            nrPerformed = r.getInteger(0, 2);
            break;
        case DO_BLOCK:
            nrPerformed = r.getInteger(0, 2);
            break;
        case CREATE_RULE:
            nrPerformed = r.getInteger(0, 2);
            break;
        case GRANT_REVOKE:
            nrPerformed = r.getInteger(0, 2);
            break;
        default:
            throw new AssertionError(a);
        }
        return nrPerformed;

    }

    @Override
    public void generateDatabase(YSQLGlobalState globalState) throws Exception {
        if (globalState.getDbmsSpecificOptions().createDatabases) {
            readFunctions(globalState);
            boolean isCatalogTest = CATALOG.equals(globalState.getDbmsSpecificOptions().oracle.get(0));
            int numTables = isCatalogTest ? Randomly.fromOptions(100, 110, 120) : Randomly.fromOptions(4, 5, 6);
            createTables(globalState, numTables);
            prepareTables(globalState);
        }
    }

    @Override
    public SQLConnection createDatabase(YSQLGlobalState globalState) throws SQLException {
        boolean isPgCompat = globalState.isPgCompatible();
        username = globalState.getOptions().getUserName();
        password = globalState.getOptions().getPassword();
        host = globalState.getOptions().getHost();
        port = globalState.getOptions().getPort();
        entryPath = isPgCompat ? "/postgres" : "/yugabyte";
        entryURL = globalState.getDbmsSpecificOptions().connectionURL;
        String entryDatabaseName = entryPath.substring(1);
        databaseName = globalState.getDatabaseName();

        if (host == null) {
            host = YSQLOptions.DEFAULT_HOST;
        }
        if (port == MainOptions.NO_SET_PORT) {
            port = isPgCompat ? YSQLOptions.DEFAULT_PG_PORT : YSQLOptions.DEFAULT_PORT;
        }

        String jdbcScheme = isPgCompat ? "jdbc:postgresql" : "jdbc:yugabytedb";

        try {
            URI uri = new URI(entryURL);
            String userInfoURI = uri.getUserInfo();
            String pathURI = uri.getPath();
            if (userInfoURI != null) {
                // username and password specified in URL take precedence
                if (userInfoURI.contains(":")) {
                    String[] userInfo = userInfoURI.split(":", 2);
                    username = userInfo[0];
                    password = userInfo[1];
                } else {
                    username = userInfoURI;
                    password = null;
                }
                int userInfoIndex = entryURL.indexOf(userInfoURI);
                String preUserInfo = entryURL.substring(0, userInfoIndex);
                String postUserInfo = entryURL.substring(userInfoIndex + userInfoURI.length() + 1);
                entryURL = preUserInfo + postUserInfo;
            }
            if (pathURI != null) {
                entryPath = pathURI;
            }
            if (host == null) {
                host = uri.getHost();
            }
            if (port == MainOptions.NO_SET_PORT) {
                port = uri.getPort();
            }
            entryURL = String.format("%s://%s:%d/%s", jdbcScheme, host, port, entryDatabaseName);
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }

        if (globalState.getDbmsSpecificOptions().createDatabases) {
            createDatabaseSync(globalState, entryDatabaseName);
        }

        int databaseIndex = entryURL.indexOf("/" + entryDatabaseName) + 1;
        String preDatabaseName = entryURL.substring(0, databaseIndex);
        String postDatabaseName = entryURL.substring(databaseIndex + entryDatabaseName.length());
        testURL = preDatabaseName + databaseName + postDatabaseName;
        globalState.getState().logStatement(String.format("\\c %s;", databaseName));

        return new SQLConnection(createConnectionSafely(testURL, username, password));
    }

    @Override
    public String getDBMSName() {
        return "ysql";
    }

    private void createDatabaseSync(YSQLGlobalState globalState, String entryDatabaseName) throws SQLException {
        if (globalState.isPgCompatible()) {
            createDatabaseSimple(globalState, entryDatabaseName);
        } else {
            createDatabaseWithLock(globalState, entryDatabaseName);
        }
    }

    private void createDatabaseSimple(YSQLGlobalState globalState, String entryDatabaseName) throws SQLException {
        try (Connection con = createConnectionSafely(entryURL, username, password)) {
            globalState.getState().logStatement(String.format("\\c %s;", entryDatabaseName));
            globalState.getState().logStatement("DROP DATABASE IF EXISTS " + databaseName);
            createDatabaseCommand = getCreateDatabaseCommand(globalState);
            globalState.getState().logStatement(createDatabaseCommand);
            try (Statement s = con.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + databaseName);
            }
            try (Statement s = con.createStatement()) {
                s.execute(createDatabaseCommand);
            }
        }
    }

    private void createDatabaseWithLock(YSQLGlobalState globalState, String entryDatabaseName) throws SQLException {
        synchronized (DATABASE_CREATION_LOCK) {
            // Safety delay before - allow any previous DDL to propagate across the cluster
            exceptionLessSleep(SAFETY_DELAY_MS);

            int counter = 0;
            while (true) {
                try (Connection con = createConnectionSafely(entryURL, username, password)) {
                    globalState.getState().logStatement(String.format("\\c %s;", entryDatabaseName));
                    globalState.getState().logStatement("DROP DATABASE IF EXISTS " + databaseName);
                    createDatabaseCommand = getCreateDatabaseCommand(globalState);
                    globalState.getState().logStatement(createDatabaseCommand);
                    try (Statement s = con.createStatement()) {
                        s.execute("DROP DATABASE IF EXISTS " + databaseName);
                    }
                    exceptionLessSleep(SAFETY_DELAY_MS);
                    try (Statement s = con.createStatement()) {
                        s.execute(createDatabaseCommand);
                    }
                    break;
                } catch (Exception e) {
                    if (isRetryableDatabaseCreationError(e) && counter < 20) {
                        counter++;
                        exceptionLessSleep(500);
                    } else {
                        throw e;
                    }
                }
            }

            // Safety delay after - allow DDL to propagate before releasing lock
            exceptionLessSleep(SAFETY_DELAY_MS);
        }
    }

    private boolean isRetryableDatabaseCreationError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("Catalog Version Mismatch") || msg.contains("Restart read required")
                || msg.contains("could not serialize access due to concurrent update") || msg.contains("not onlined")
                || msg.contains("is being accessed by other users") || msg.contains("connection has been closed")
                || msg.contains("does not exist") || msg.contains("already exists") || msg.contains("Timed out waiting")
                || msg.contains("Restarting a DDL transaction not supported")
                || msg.contains("insufficient disk space");
    }

    private Connection createConnectionSafely(String entryURL, String user, String password) {
        Connection con = null;
        IllegalStateException lastException = new IllegalStateException("Empty exception");
        long endTime = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < endTime) {
            try {
                con = DriverManager.getConnection(entryURL, user, password);
                con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                break;
            } catch (SQLException throwables) {
                lastException = new IllegalStateException(throwables);
            }
        }

        if (con == null) {
            throw lastException;
        }

        return con;
    }

    protected void readFunctions(YSQLGlobalState globalState) throws SQLException {
        // Commented out to avoid set-returning functions causing errors
        /*
         * SQLQueryAdapter query = new SQLQueryAdapter("SELECT proname, provolatile FROM pg_proc;"); SQLancerResultSet
         * rs = query.executeAndGet(globalState); while (rs.next()) { String functionName = rs.getString(1); Character
         * functionType = rs.getString(2).charAt(0); globalState.addFunctionAndType(functionName, functionType); }
         */
    }

    protected void createTables(YSQLGlobalState globalState, int numTables) throws Exception {
        while (globalState.getSchema().getDatabaseTables().size() < numTables) {
            try {
                String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
                SQLQueryAdapter createTable = YSQLTableGenerator.generate(tableName, generateOnlyKnown, globalState);
                globalState.executeStatement(createTable);
            } catch (IgnoreMeException e) {
                // do nothing
            }
        }
    }

    private void exceptionLessSleep(long timeout) {
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            throw new AssertionError();
        }
    }

    protected void prepareTables(YSQLGlobalState globalState) throws Exception {
        StatementExecutor<YSQLGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                YSQLProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        globalState.executeStatement(new SQLQueryAdapter("COMMIT", errors, true));
        ExpectedErrors setErrors = new ExpectedErrors();
        YSQLErrors.addKnownIssues(setErrors);
        globalState.executeStatement(new SQLQueryAdapter("SET SESSION statement_timeout = 15000;\n", setErrors));
    }

    private String getCreateDatabaseCommand(YSQLGlobalState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE DATABASE ").append(databaseName).append(" ");
        boolean isPgCompat = state.getDbmsSpecificOptions().pgCompatibility;
        if (!isPgCompat && CATALOG.equals(state.getDbmsSpecificOptions().oracle.get(0))) {
            // Always colocate for CATALOG tests - many tables benefit from colocation
            sb.append("WITH COLOCATION = true ");

            if (Randomly.getBoolean() && state.getDbmsSpecificOptions().testCollations) {
                if (Randomly.getBoolean()) {
                    sb.append("ENCODING '");
                    sb.append(Randomly.fromOptions("utf8"));
                    sb.append("' ");
                }
                for (String lc : Arrays.asList("LC_COLLATE", "LC_CTYPE")) {
                    if (!state.getCollates().isEmpty() && Randomly.getBoolean()) {
                        sb.append(String.format(" %s = '%s'", lc, Randomly.fromList(state.getCollates())));
                    }
                }
                sb.append(" TEMPLATE template0");

            }
        } else {
            if (Randomly.getBoolean() && state.getDbmsSpecificOptions().testCollations) {
                sb.append("WITH ");
                if (Randomly.getBoolean()) {
                    sb.append("ENCODING '");
                    sb.append(Randomly.fromOptions("utf8"));
                    sb.append("' ");
                }

                // create colocated database with low priority, skip entirely in PG compatibility mode
                if (!isPgCompat && Randomly.getPercentage() > 0.05) {
                    sb.append("COLOCATION = true ");
                }

                for (String lc : Arrays.asList("LC_COLLATE", "LC_CTYPE")) {
                    if (!state.getCollates().isEmpty() && Randomly.getBoolean()) {
                        sb.append(String.format(" %s = '%s'", lc, Randomly.fromList(state.getCollates())));
                    }
                }
                sb.append(" TEMPLATE template0");

            }
        }
        return sb.toString();
    }

    public enum Action implements AbstractAction<YSQLGlobalState> {
        ANALYZE(YSQLAnalyzeGenerator::create), //
        ALTER_TABLE(g -> YSQLAlterTableGenerator.create(g.getSchema().getRandomTable(t -> !t.isView()), g)), //
        COMMIT(g -> {
            SQLQueryAdapter query;
            ExpectedErrors errors = new ExpectedErrors();
            YSQLErrors.addTransactionErrors(errors);
            if (Randomly.getBoolean()) {
                query = new SQLQueryAdapter("COMMIT", errors, true);
            } else if (Randomly.getBoolean()) {
                query = YSQLTransactionGenerator.executeBegin();
            } else {
                query = new SQLQueryAdapter("ROLLBACK", errors, true);
            }
            return query;
        }), //
        DELETE(YSQLDeleteGenerator::create), //
        DISCARD(YSQLDiscardGenerator::create), //
        DROP_INDEX(YSQLDropIndexGenerator::create), //
        CREATE_INDEX(YSQLIndexGenerator::generate), //
        INSERT(YSQLInsertGenerator::insert), //
        UPDATE(YSQLUpdateGenerator::create), //
        TRUNCATE(YSQLTruncateGenerator::create), //
        VACUUM(YSQLVacuumGenerator::create), //
        SET(YSQLSetGenerator::create), //
        SET_CONSTRAINTS((g) -> {
            String sb = "SET CONSTRAINTS ALL " + Randomly.fromOptions("DEFERRED", "IMMEDIATE");
            ExpectedErrors errors = new ExpectedErrors();
            errors.add("SET CONSTRAINTS is not supported yet");
            errors.add("result of range union would not be contiguous");
            errors.add("current transaction is aborted");
            errors.add("there is no unique or exclusion constraint");
            YSQLErrors.addTransactionErrors(errors);
            return new SQLQueryAdapter(sb, errors);
        }), //
        SET_TRANSACTION(YSQLTransactionGenerator::setTransactionMode), //
        RESET_ROLE((g) -> {
            ExpectedErrors errors = new ExpectedErrors();
            errors.add("This statement not supported yet");
            errors.add("current transaction is aborted");
            YSQLErrors.addTransactionErrors(errors);
            return new SQLQueryAdapter("RESET ROLE", errors);
        }), //
        COMMENT_ON(YSQLCommentGenerator::generate), //
        RESET((g) -> {
            ExpectedErrors errors = new ExpectedErrors();
            errors.add("current transaction is aborted, commands ignored until end of transaction block");
            errors.add("RESET ALL cannot run inside a transaction block");
            YSQLErrors.addTransactionErrors(errors);
            return new SQLQueryAdapter("RESET ALL", errors);
        }), //
        NOTIFY(YSQLNotifyGenerator::createNotify), //
        LISTEN((g) -> YSQLNotifyGenerator.createListen()), //
        UNLISTEN((g) -> YSQLNotifyGenerator.createUnlisten()), //
        CREATE_SEQUENCE(YSQLSequenceGenerator::createSequence), //
        CREATE_VIEW(YSQLViewGenerator::create), //
        REFRESH_VIEW(YSQLMaterializedViewRefresh::create), //
        PARALLEL_QUERY_TEST(YSQLParallelQueryGenerator::generateParallelQueryTest), //
        ALTER_DATABASE(YSQLAlterDatabaseGenerator::create), //
        SAVEPOINT(YSQLSavepointGenerator::generate), //
        // New consistency-focused generators
        PREPARED_STATEMENT(YSQLPreparedStatementGenerator::generate), //
        CURSOR(YSQLCursorGenerator::generate), //
        CREATE_DOMAIN(YSQLDomainGenerator::generate), //
        CREATE_TYPE(YSQLTypeGenerator::generate), //
        LOCK_TABLE(YSQLLockTableGenerator::generate), //
        EXPLAIN(YSQLExplainGenerator::generate), //
        CREATE_FUNCTION(YSQLFunctionGenerator::generate), //
        CREATE_TRIGGER(YSQLTriggerGenerator::generate), //
        COPY_TO(YSQLCopyGenerator::generate), //
        CREATE_POLICY(YSQLPolicyGenerator::generate), //
        DO_BLOCK(YSQLDoBlockGenerator::generate), //
        CREATE_RULE(YSQLRuleGenerator::generate), //
        GRANT_REVOKE(YSQLGrantRevokeGenerator::generate), //
        MERGE(YSQLMergeGenerator::create), //
        CREATE_TABLEGROUP(g -> YSQLTableGroupGenerator.create()), //
        VECTOR_TEST(g -> {
            switch (Randomly.fromOptions(0, 1, 2)) {
            case 0:
                return YSQLSimpleVectorGenerator.testVectorSyntax(g);
            case 1:
                return YSQLSimpleVectorGenerator.testVectorIndexSyntax(g);
            default:
                return YSQLSimpleVectorGenerator.testVectorSettings(g);
            }
        });

        private final SQLQueryProvider<YSQLGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<YSQLGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(YSQLGlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

}
