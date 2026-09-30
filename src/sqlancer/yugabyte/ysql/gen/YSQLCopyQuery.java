package sqlancer.yugabyte.ysql.gen;

import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import sqlancer.GlobalState;
import sqlancer.Main;
import sqlancer.SQLConnection;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * COPY through pgjdbc's CopyManager. A plain JDBC Statement cannot run COPY ... TO STDOUT or FROM STDIN (pgjdbc refuses
 * with "COPY commands are only supported using the CopyManager API"), so without this COPY never reached the server.
 * Runs an optional setup statement, then COPY ... TO STDOUT, then optionally COPY ... FROM STDIN fed with the rows just
 * copied out. PostgreSQL JDBC driver only (PostgreSQL-compatible mode).
 */
public class YSQLCopyQuery extends SQLQueryAdapter {

    private static final long serialVersionUID = 1L;

    private final String setup;
    private final String copyOut;
    private final String copyIn;

    public YSQLCopyQuery(String setup, String copyOut, String copyIn, ExpectedErrors errors) {
        super(Arrays.asList(setup, copyOut, copyIn).stream().filter(Objects::nonNull)
                .collect(Collectors.joining(";\n")), errors, setup != null || copyIn != null);
        this.setup = setup;
        this.copyOut = copyOut;
        this.copyIn = copyIn;
    }

    @Override
    protected <G extends GlobalState<?, ?, SQLConnection>> boolean internalExecute(SQLConnection connection,
            boolean reportException, String... fills) throws SQLException {
        try {
            if (setup != null) {
                try (Statement s = connection.createStatement()) {
                    s.execute(setup);
                }
            }
            CopyManager copy = new CopyManager(connection.getConnection().unwrap(BaseConnection.class));
            StringWriter rows = new StringWriter();
            copy.copyOut(copyOut, rows);
            if (copyIn != null) {
                copy.copyIn(copyIn, new StringReader(rows.toString()));
            }
            Main.nrSuccessfulActions.addAndGet(1);
            return true;
        } catch (Exception e) {
            Main.nrUnsuccessfulActions.addAndGet(1);
            if (reportException) {
                checkException(e);
            }
            return false;
        }
    }
}
