package sqlancer.yugabyte.ysql.ast;

import sqlancer.Randomly;
import sqlancer.common.ast.BinaryNode;
import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

/**
 * Array containment / overlap predicate: {@code arr @> arr}, {@code arr <@ arr}, {@code arr && arr}. Both operands are
 * generated with the same array element type, so the comparison is well-typed. Rendered generically as
 * {@code (left OP right)} by the base BinaryOperation visitor. No expected value is computed, so it is excluded from
 * the PQS "known result" mode - it exercises the array operator code paths for the differential / TLP oracles.
 */
public class YSQLBinaryArrayOperation extends BinaryNode<YSQLExpression> implements YSQLExpression {

    private final String op;

    public YSQLBinaryArrayOperation(YSQLArrayOperator op, YSQLExpression left, YSQLExpression right) {
        super(left, right);
        this.op = op.getTextRepresentation();
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.BOOLEAN;
    }

    @Override
    public String getOperatorRepresentation() {
        return op;
    }

    public enum YSQLArrayOperator implements Operator {
        CONTAINS("@>"), IS_CONTAINED_BY("<@"), OVERLAP("&&");

        private final String textRepresentation;

        YSQLArrayOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public static YSQLArrayOperator getRandom() {
            return Randomly.fromOptions(values());
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

}
