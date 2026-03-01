package sqlancer.yugabyte.ysql.ast;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLSetOperation implements YSQLExpression {

    private final YSQLExpression left;
    private final YSQLExpression right;
    private final SetOperationType type;

    public enum SetOperationType {
        UNION("UNION"), UNION_ALL("UNION ALL"), INTERSECT("INTERSECT"), INTERSECT_ALL("INTERSECT ALL"),
        EXCEPT("EXCEPT"), EXCEPT_ALL("EXCEPT ALL");

        private final String textRepresentation;

        SetOperationType(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }

        public static SetOperationType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public YSQLSetOperation(YSQLExpression left, YSQLExpression right, SetOperationType type) {
        this.left = left;
        this.right = right;
        this.type = type;
    }

    public YSQLExpression getLeft() {
        return left;
    }

    public YSQLExpression getRight() {
        return right;
    }

    public SetOperationType getType() {
        return type;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return null;
    }
}
