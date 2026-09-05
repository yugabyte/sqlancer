package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders {@code TRIM([LEADING|TRAILING|BOTH] [chars] FROM <string>)}. Grammar form distinct from the function-call
 * {@code trim(string, chars)}. Both {@code side} and {@code chars} may be null - PG defaults side to BOTH and chars to
 * a single space.
 */
public class YSQLTrimGrammar implements YSQLExpression {

    public enum TrimSide {
        LEADING, TRAILING, BOTH
    }

    private final TrimSide side; // may be null
    private final YSQLExpression chars; // may be null
    private final YSQLExpression string;

    public YSQLTrimGrammar(TrimSide side, YSQLExpression chars, YSQLExpression string) {
        this.side = side;
        this.chars = chars;
        this.string = string;
    }

    public TrimSide getSide() {
        return side;
    }

    public YSQLExpression getChars() {
        return chars;
    }

    public YSQLExpression getString() {
        return string;
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
