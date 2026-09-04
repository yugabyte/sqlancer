package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders {@code EXTRACT(<field> FROM <timeExpr> AT TIME ZONE '<zone>')}. Exercises the datetime EXTRACT + AT TIME ZONE
 * surface, which triggers YugabyteDB expression-pushdown timezone-resolution bugs (e.g. Phorge D51850 /
 * yugabyte-db#30815, where a named zone like {@code 'UTC'} raises "time zone not recognized" only when
 * {@code yb_enable_expression_pushdown=on} because the tserver's postgres backend cannot locate
 * {@code share/timezone}). SCAN_GUC flips that GUC, so any pushdown/no-pushdown result divergence surfaces the bug.
 */
public class YSQLTimezoneExtract implements YSQLExpression {

    private final String field;
    private final YSQLExpression timeExpr;
    private final String zone;

    public YSQLTimezoneExtract(String field, YSQLExpression timeExpr, String zone) {
        this.field = field;
        this.timeExpr = timeExpr;
        this.zone = zone;
    }

    public String getField() {
        return field;
    }

    public YSQLExpression getTimeExpr() {
        return timeExpr;
    }

    public String getZone() {
        return zone;
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
