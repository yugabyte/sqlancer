package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLExistsSubquery implements YSQLExpression {

    private final YSQLSelect subquery;
    private final boolean negated;

    public YSQLExistsSubquery(YSQLSelect subquery, boolean negated) {
        this.subquery = subquery;
        this.negated = negated;
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
