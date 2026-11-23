package sqlancer.yugabyte.ysql.ast;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLCompoundDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLQuantifiedComparison implements YSQLExpression {

    private final YSQLExpression expression;
    private final YSQLSelect subquery;
    private final ComparisonOperator operator;
    private final QuantifierType quantifier;
    private final List<YSQLExpression> arrayElements;
    private final YSQLCompoundDataType arrayElementType;

    public YSQLQuantifiedComparison(YSQLExpression expression, YSQLSelect subquery, ComparisonOperator operator,
            QuantifierType quantifier) {
        this.expression = expression;
        this.subquery = subquery;
        this.operator = operator;
        this.quantifier = quantifier;
        this.arrayElements = null;
        this.arrayElementType = null;
    }

    public YSQLQuantifiedComparison(YSQLExpression expression, List<YSQLExpression> arrayElements,
            YSQLCompoundDataType arrayElementType, ComparisonOperator operator, QuantifierType quantifier) {
        this.expression = expression;
        this.subquery = null;
        this.operator = operator;
        this.quantifier = quantifier;
        this.arrayElements = arrayElements;
        this.arrayElementType = arrayElementType;
    }

    public boolean isArray() {
        return arrayElements != null;
    }

    public List<YSQLExpression> getArrayElements() {
        return arrayElements;
    }

    public YSQLCompoundDataType getArrayElementType() {
        return arrayElementType;
    }

    public YSQLExpression getExpression() {
        return expression;
    }

    public YSQLSelect getSubquery() {
        return subquery;
    }

    public ComparisonOperator getOperator() {
        return operator;
    }

    public QuantifierType getQuantifier() {
        return quantifier;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return YSQLDataType.BOOLEAN;
    }

    public enum ComparisonOperator {
        EQUALS("="), NOT_EQUALS("<>"), LESS_THAN("<"), GREATER_THAN(">"), LESS_THAN_OR_EQUAL("<="),
        GREATER_THAN_OR_EQUAL(">=");

        private final String textRepresentation;

        ComparisonOperator(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }

        public static ComparisonOperator getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum QuantifierType {
        ANY, ALL;

        public static QuantifierType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

}
