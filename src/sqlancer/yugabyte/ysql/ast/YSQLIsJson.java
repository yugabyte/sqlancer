package sqlancer.yugabyte.ysql.ast;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders the PostgreSQL 16+ SQL/JSON predicate {@code <expr> IS [NOT] JSON [VALUE | SCALAR | ARRAY | OBJECT]
 * [WITH | WITHOUT UNIQUE KEYS]}. PostgreSQL-compatible mode only (not in YugabyteDB's PostgreSQL 15).
 */
public class YSQLIsJson implements YSQLExpression {

    private final YSQLExpression expr;
    private final String predicate;

    public YSQLIsJson(YSQLExpression expr, String predicate) {
        this.expr = expr;
        this.predicate = predicate;
    }

    public static YSQLIsJson create(YSQLExpression expr) {
        String kind = Randomly.fromOptions("", " VALUE", " SCALAR", " ARRAY", " OBJECT");
        String uniqueKeys = Randomly.fromOptions("", " WITH UNIQUE KEYS", " WITHOUT UNIQUE KEYS");
        return new YSQLIsJson(expr, "IS " + (Randomly.getBoolean() ? "NOT " : "") + "JSON" + kind + uniqueKeys);
    }

    public YSQLExpression getExpr() {
        return expr;
    }

    public String getPredicate() {
        return predicate;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.BOOLEAN;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        return null;
    }
}
