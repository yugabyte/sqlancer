package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders {@code OVERLAY(<string> PLACING <replacement> FROM <from> [FOR <len>])}. SQL-standard grammar form for
 * replacing a substring; no equivalent common function-call form, so this is fresh coverage regardless of the function
 * catalog.
 */
public class YSQLOverlay implements YSQLExpression {

    private final YSQLExpression string;
    private final YSQLExpression replacement;
    private final YSQLExpression from;
    private final YSQLExpression len; // may be null (FOR clause is optional)

    public YSQLOverlay(YSQLExpression string, YSQLExpression replacement, YSQLExpression from, YSQLExpression len) {
        this.string = string;
        this.replacement = replacement;
        this.from = from;
        this.len = len;
    }

    public YSQLExpression getString() {
        return string;
    }

    public YSQLExpression getReplacement() {
        return replacement;
    }

    public YSQLExpression getFrom() {
        return from;
    }

    public YSQLExpression getLen() {
        return len;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.TEXT;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        return null;
    }
}
