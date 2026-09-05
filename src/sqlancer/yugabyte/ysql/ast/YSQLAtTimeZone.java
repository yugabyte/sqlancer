package sqlancer.yugabyte.ysql.ast;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Renders {@code ((<time>) AT TIME ZONE '<zone>')}. Independent from {@link YSQLExtract} so the fuzzer can compose both
 * freely: {@code AT TIME ZONE} alone, {@code EXTRACT(f FROM x AT TIME ZONE 'z')}, arithmetic on the shifted timestamp,
 * etc. Return type is provided by the caller because it flips with the operand (TIMESTAMPTZ &lt;-&gt; TIMESTAMP, TIME
 * &lt;-&gt; TIMETZ).
 */
public class YSQLAtTimeZone implements YSQLExpression {

    private final YSQLExpression time;
    private final String zone;
    private final YSQLDataType returnType;

    public YSQLAtTimeZone(YSQLExpression time, String zone, YSQLDataType returnType) {
        this.time = time;
        this.zone = zone;
        this.returnType = returnType;
    }

    public YSQLExpression getTime() {
        return time;
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
