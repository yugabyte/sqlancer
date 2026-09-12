package sqlancer.yugabyte.ysql.ast;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLRowComparison implements YSQLExpression {

    private final List<YSQLExpression> left;
    private final List<YSQLExpression> right;
    private final RowComparisonOperator op;

    public YSQLRowComparison(List<YSQLExpression> left, List<YSQLExpression> right, RowComparisonOperator op) {
        if (left.size() < 2 || left.size() != right.size()) {
            throw new IllegalArgumentException("row comparison sides must have the same arity of at least two");
        }
        this.left = List.copyOf(left);
        this.right = List.copyOf(right);
        this.op = op;
    }

    public List<YSQLExpression> getLeft() {
        return left;
    }

    public List<YSQLExpression> getRight() {
        return right;
    }

    public RowComparisonOperator getOp() {
        return op;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.BOOLEAN;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        return null;
    }

    public enum RowComparisonOperator {
        EQUALS("="), NOT_EQUALS("<>"), LESS("<"), LESS_EQUALS("<="), GREATER(">"), GREATER_EQUALS(">=");

        private final String textRepresentation;

        RowComparisonOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public static RowComparisonOperator getRandom() {
            return Randomly.fromOptions(values());
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }
    }
}
