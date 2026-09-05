package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders {@code POSITION(<needle> IN <haystack>)}. Returns 1-based position, or 0 if the needle is not found.
 * SQL-grammar form distinct from the function-call {@code strpos(haystack, needle)}.
 */
public class YSQLPosition implements YSQLExpression {

    private final YSQLExpression needle;
    private final YSQLExpression haystack;

    public YSQLPosition(YSQLExpression needle, YSQLExpression haystack) {
        this.needle = needle;
        this.haystack = haystack;
    }

    public YSQLExpression getNeedle() {
        return needle;
    }

    public YSQLExpression getHaystack() {
        return haystack;
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
