package sqlancer.yugabyte.ycql.ast;

public class YCQLCastOperation implements YCQLExpression {
    // TODO Implement this
    private final YCQLExpression expr;
    private final CastType type;

    public enum CastType {
        SIGNED, UNSIGNED;

        public static CastType getRandom() {
            return SIGNED;
            // return Randomly.fromOptions(CastType.values());
        }

    }

    public YCQLCastOperation(YCQLExpression expr, CastType type) {
        this.expr = expr;
        this.type = type;
    }

    public YCQLExpression getExpr() {
        return expr;
    }

    public CastType getType() {
        return type;
    }

    @Override
    public YCQLConstant getExpectedValue() {
        return expr.getExpectedValue();
    }

}
