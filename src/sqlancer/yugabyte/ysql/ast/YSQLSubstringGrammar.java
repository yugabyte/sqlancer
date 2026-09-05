package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders the SQL grammar form {@code SUBSTRING(<string> FROM <from> [FOR <len>])}. Distinct parser path from the
 * function-call form {@code substring(a, b, c)} covered by {@link YSQLFunction}; both should behave identically but
 * different code paths in PG's parser/analyzer catch different bugs.
 */
public class YSQLSubstringGrammar implements YSQLExpression {

    private final YSQLExpression string;
    private final YSQLExpression from;
    private final YSQLExpression len; // may be null (FOR clause is optional)

    public YSQLSubstringGrammar(YSQLExpression string, YSQLExpression from, YSQLExpression len) {
        this.string = string;
        this.from = from;
        this.len = len;
    }

    public YSQLExpression getString() {
        return string;
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
