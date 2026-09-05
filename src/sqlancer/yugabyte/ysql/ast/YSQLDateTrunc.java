package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders {@code date_trunc('<field>', <source>[, '<zone>'])}. Both the 2-arg and 3-arg forms are covered; the 3-arg
 * form takes a timezone name and exercises the same DocDB expression-pushdown timezone-resolution path as
 * {@link YSQLAtTimeZone} (Phorge D51850 / yugabyte-db#30815) - a distinct grammar entry point for the same bug class.
 * {@code date_trunc} is a real function but the field arg is a restricted vocabulary so it lives here rather than in
 * the generic unknown-result function table (where a random text arg would produce constant errors).
 */
public class YSQLDateTrunc implements YSQLExpression {

    private final String field;
    private final YSQLExpression source;
    private final String zone; // null for the 2-arg form
    private final YSQLDataType returnType;

    public YSQLDateTrunc(String field, YSQLExpression source, String zone, YSQLDataType returnType) {
        this.field = field;
        this.source = source;
        this.zone = zone;
        this.returnType = returnType;
    }

    public String getField() {
        return field;
    }

    public YSQLExpression getSource() {
        return source;
    }

    public String getZone() {
        return zone;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return returnType;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        return null;
    }
}
