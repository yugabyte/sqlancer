package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLInSubquery implements YSQLExpression {

    private final YSQLExpression expression;
    private final YSQLSelect subquery;
    private final boolean negated;

    public YSQLInSubquery(YSQLExpression expression, YSQLSelect subquery, boolean negated) {
        this.expression = expression;
        this.subquery = subquery;
        this.negated = negated;
    }

    public YSQLExpression getExpression() {
        return expression;
    }

    public YSQLSelect getSubquery() {
        return subquery;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.BOOLEAN;
    }

}
