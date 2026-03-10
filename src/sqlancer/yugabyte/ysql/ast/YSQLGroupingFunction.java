package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLGroupingFunction implements YSQLExpression {

    private final YSQLExpression expression;

    public YSQLGroupingFunction(YSQLExpression expression) {
        this.expression = expression;
    }

    public YSQLExpression getGroupingExpression() {
        return expression;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.INT;
    }

}
