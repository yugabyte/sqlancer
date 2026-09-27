package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders a scalar subquery over the PostgreSQL 17+ {@code JSON_TABLE} table function:
 * {@code (SELECT count(*) FROM JSON_TABLE(<jsonb>, '<path>' COLUMNS (v text PATH '$')))}. Counting rows keeps the
 * result deterministic, so the node composes with every oracle. PostgreSQL-compatible mode only.
 */
public class YSQLJsonTableCount implements YSQLExpression {

    private final YSQLExpression json;
    private final String path;

    public YSQLJsonTableCount(YSQLExpression json, String path) {
        this.json = json;
        this.path = path;
    }

    public YSQLExpression getJson() {
        return json;
    }

    public String getPath() {
        return path;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.INT;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        return null;
    }
}
