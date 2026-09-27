package sqlancer.yugabyte.amp;

import java.sql.SQLException;

import com.google.auto.service.AutoService;

import sqlancer.DatabaseProvider;
import sqlancer.SQLConnection;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLProvider;

/**
 * Yugabyte AMP (PostgreSQL 18 compute): the YSQL provider, generators and oracles run in PostgreSQL-compatible mode, so
 * AMP gets the same oracles as YugabyteDB without YugabyteDB-only syntax. Invoked as {@code amp}; defaults to
 * {@code jdbc:postgresql://localhost:5432/postgres}.
 */
@AutoService(DatabaseProvider.class)
public class YugabyteAmpProvider extends YSQLProvider {

    @Override
    public SQLConnection createDatabase(YSQLGlobalState globalState) throws SQLException {
        globalState.getDbmsSpecificOptions().pgCompatibility = true;
        return super.createDatabase(globalState);
    }

    @Override
    public String getDBMSName() {
        return "amp";
    }
}
