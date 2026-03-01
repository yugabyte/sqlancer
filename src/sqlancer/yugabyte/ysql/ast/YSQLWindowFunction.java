package sqlancer.yugabyte.ysql.ast;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLWindowFunction implements YSQLExpression {

    private final WindowFunction func;
    private final YSQLExpression[] args;

    public YSQLWindowFunction(WindowFunction func, YSQLExpression... args) {
        this.func = func;
        this.args = args.clone();
    }

    public enum WindowFunction {
        ROW_NUMBER(0), RANK(0), DENSE_RANK(0), NTILE(1), LAG(1), LEAD(1), FIRST_VALUE(1), LAST_VALUE(1), NTH_VALUE(2),
        PERCENT_RANK(0), CUME_DIST(0);

        private final int nrArgs;

        WindowFunction(int nrArgs) {
            this.nrArgs = nrArgs;
        }

        public int getNrArgs() {
            return nrArgs;
        }

        public static WindowFunction getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public WindowFunction getFunc() {
        return func;
    }

    public YSQLExpression[] getArgs() {
        return args.clone();
    }

    @Override
    public YSQLDataType getExpressionType() {
        switch (func) {
        case ROW_NUMBER:
        case RANK:
        case DENSE_RANK:
        case NTILE:
            return YSQLDataType.BIGINT;
        case PERCENT_RANK:
        case CUME_DIST:
            return YSQLDataType.DOUBLE_PRECISION;
        case LAG:
        case LEAD:
        case FIRST_VALUE:
        case LAST_VALUE:
        case NTH_VALUE:
            if (args.length > 0) {
                return args[0].getExpressionType();
            }
            return null;
        default:
            return null;
        }
    }
}
