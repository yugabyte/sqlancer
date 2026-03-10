package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLScalarSubquery implements YSQLExpression {

    private final YSQLSelect subquery;
    private final YSQLDataType type;

    public YSQLScalarSubquery(YSQLSelect subquery, YSQLDataType type) {
        this.subquery = subquery;
        this.type = type;
    }

    public YSQLSelect getSubquery() {
        return subquery;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return type;
    }

}
