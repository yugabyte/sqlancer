package sqlancer.yugabyte.ysql.ast;

import sqlancer.LikeImplementationHelper;
import sqlancer.common.ast.BinaryNode;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLLikeOperation extends BinaryNode<YSQLExpression> implements YSQLExpression {

    private final boolean caseSensitive;

    public YSQLLikeOperation(YSQLExpression left, YSQLExpression right, boolean caseSensitive) {
        super(left, right);
        this.caseSensitive = caseSensitive;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.BOOLEAN;
    }

    @Override
    public YSQLConstant getExpectedValue() {
        YSQLConstant leftVal = getLeft().getExpectedValue();
        YSQLConstant rightVal = getRight().getExpectedValue();
        if (leftVal == null || rightVal == null) {
            return null;
        }
        if (leftVal.isNull() || rightVal.isNull()) {
            return YSQLConstant.createNullConstant();
        }
        boolean val = LikeImplementationHelper.match(leftVal.asString(), rightVal.asString(), 0, 0, caseSensitive);
        return YSQLConstant.createBooleanConstant(val);
    }

    @Override
    public String getOperatorRepresentation() {
        return caseSensitive ? "LIKE" : "ILIKE";
    }

}
