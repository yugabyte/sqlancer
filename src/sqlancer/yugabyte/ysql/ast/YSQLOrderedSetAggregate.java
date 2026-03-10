package sqlancer.yugabyte.ysql.ast;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLOrderedSetAggregate implements YSQLExpression {

    private final OrderedSetFunction function;
    private final List<YSQLExpression> directArgs;
    private final List<YSQLExpression> orderByArgs;

    public YSQLOrderedSetAggregate(OrderedSetFunction function, List<YSQLExpression> directArgs,
            List<YSQLExpression> orderByArgs) {
        this.function = function;
        this.directArgs = directArgs;
        this.orderByArgs = orderByArgs;
    }

    public OrderedSetFunction getFunction() {
        return function;
    }

    public List<YSQLExpression> getDirectArgs() {
        return directArgs;
    }

    public List<YSQLExpression> getOrderByArgs() {
        return orderByArgs;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return function.getReturnType();
    }

    public enum OrderedSetFunction {
        MODE {
            @Override
            public YSQLDataType getReturnType() {
                return null;
            }
        },
        PERCENTILE_CONT {
            @Override
            public YSQLDataType getReturnType() {
                return YSQLDataType.FLOAT;
            }
        },
        PERCENTILE_DISC {
            @Override
            public YSQLDataType getReturnType() {
                return null;
            }
        };

        public abstract YSQLDataType getReturnType();

        public static OrderedSetFunction getRandom() {
            return Randomly.fromOptions(values());
        }
    }

}
