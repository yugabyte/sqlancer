package sqlancer.yugabyte.ysql.ast;

import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLWindowFunctionExpression implements YSQLExpression {

    private final YSQLExpression baseWindowFunction;
    private List<YSQLExpression> partitionBy = new ArrayList<>();
    private List<YSQLExpression> orderBy = new ArrayList<>();
    private YSQLExpression frameSpec;
    private YSQLFrameSpecExclude exclude;
    private YSQLFrameSpecKind frameSpecKind;

    public static class YSQLWindowFunctionFrameSpecTerm implements YSQLExpression {

        public enum YSQLWindowFunctionFrameSpecTermKind {
            UNBOUNDED_PRECEDING("UNBOUNDED PRECEDING"), EXPR_PRECEDING("PRECEDING"), CURRENT_ROW("CURRENT ROW"),
            EXPR_FOLLOWING("FOLLOWING"), UNBOUNDED_FOLLOWING("UNBOUNDED FOLLOWING");

            private final String s;

            YSQLWindowFunctionFrameSpecTermKind(String s) {
                this.s = s;
            }

            public String getString() {
                return s;
            }
        }

        private final YSQLExpression expression;
        private final YSQLWindowFunctionFrameSpecTermKind kind;

        public YSQLWindowFunctionFrameSpecTerm(YSQLExpression expression, YSQLWindowFunctionFrameSpecTermKind kind) {
            this.expression = expression;
            this.kind = kind;
        }

        public YSQLWindowFunctionFrameSpecTerm(YSQLWindowFunctionFrameSpecTermKind kind) {
            this.kind = kind;
            this.expression = null;
        }

        public YSQLExpression getExpression() {
            return expression;
        }

        public YSQLWindowFunctionFrameSpecTermKind getKind() {
            return kind;
        }

        @Override
        public YSQLDataType getExpressionType() {
            return null;
        }
    }

    public static class YSQLWindowFunctionFrameSpecBetween implements YSQLExpression {

        private final YSQLWindowFunctionFrameSpecTerm left;
        private final YSQLWindowFunctionFrameSpecTerm right;

        public YSQLWindowFunctionFrameSpecBetween(YSQLWindowFunctionFrameSpecTerm left,
                YSQLWindowFunctionFrameSpecTerm right) {
            this.left = left;
            this.right = right;
        }

        public YSQLWindowFunctionFrameSpecTerm getLeft() {
            return left;
        }

        public YSQLWindowFunctionFrameSpecTerm getRight() {
            return right;
        }

        @Override
        public YSQLDataType getExpressionType() {
            return null;
        }
    }

    public enum YSQLFrameSpecExclude {
        EXCLUDE_NO_OTHERS("EXCLUDE NO OTHERS"), EXCLUDE_CURRENT_ROW("EXCLUDE CURRENT ROW"),
        EXCLUDE_GROUP("EXCLUDE GROUP"), EXCLUDE_TIES("EXCLUDE TIES");

        private final String s;

        YSQLFrameSpecExclude(String s) {
            this.s = s;
        }

        public static YSQLFrameSpecExclude getRandom() {
            return Randomly.fromOptions(values());
        }

        public String getString() {
            return s;
        }
    }

    public enum YSQLFrameSpecKind {
        RANGE, ROWS, GROUPS;

        public static YSQLFrameSpecKind getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public YSQLWindowFunctionExpression(YSQLExpression baseWindowFunction) {
        this.baseWindowFunction = baseWindowFunction;
    }

    public YSQLExpression getBaseWindowFunction() {
        return baseWindowFunction;
    }

    public List<YSQLExpression> getPartitionBy() {
        return partitionBy;
    }

    public void setPartitionBy(List<YSQLExpression> partitionBy) {
        this.partitionBy = partitionBy;
    }

    public List<YSQLExpression> getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(List<YSQLExpression> orderBy) {
        this.orderBy = orderBy;
    }

    public YSQLExpression getFrameSpec() {
        return frameSpec;
    }

    public void setFrameSpec(YSQLExpression frameSpec) {
        this.frameSpec = frameSpec;
    }

    public YSQLFrameSpecExclude getExclude() {
        return exclude;
    }

    public void setExclude(YSQLFrameSpecExclude exclude) {
        this.exclude = exclude;
    }

    public YSQLFrameSpecKind getFrameSpecKind() {
        return frameSpecKind;
    }

    public void setFrameSpecKind(YSQLFrameSpecKind frameSpecKind) {
        this.frameSpecKind = frameSpecKind;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return baseWindowFunction.getExpressionType();
    }
}
