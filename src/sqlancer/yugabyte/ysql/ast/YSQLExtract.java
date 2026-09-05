package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders the SQL grammar construct {@code EXTRACT(<field> FROM (<source>))}. {@code EXTRACT} is not a function -
 * PostgreSQL parses it as a special production - so it needs its own AST node. Composes with any datetime-ish source
 * (timestamp / timestamptz / date / time / interval), and the outer expression sees a NUMERIC, so it plugs into any
 * arithmetic or comparison generator branch without further wiring.
 */
public class YSQLExtract implements YSQLExpression {

    private final String field;
    private final YSQLExpression source;

    public YSQLExtract(String field, YSQLExpression source) {
        this.field = field;
        this.source = source;
    }

    public String getField() {
        return field;
    }

    public YSQLExpression getSource() {
        return source;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.NUMERIC;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        return null;
    }
}
