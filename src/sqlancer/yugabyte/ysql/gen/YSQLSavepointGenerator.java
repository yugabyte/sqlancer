package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLSavepointGenerator {

    private YSQLSavepointGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addSavepointErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        String savepointName = "sp" + globalState.getRandomly().getInteger(0, 5);
        String sql;
        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            sql = "SAVEPOINT " + savepointName;
            break;
        case 1:
            sql = "ROLLBACK TO SAVEPOINT " + savepointName;
            break;
        case 2:
            sql = "RELEASE SAVEPOINT " + savepointName;
            break;
        default:
            throw new AssertionError();
        }
        return new SQLQueryAdapter(sql, errors);
    }

}
