package sqlancer.yugabyte.ysql;

import java.util.List;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.ast.YSQLAggregate;
import sqlancer.yugabyte.ysql.ast.YSQLAtTimeZone;
import sqlancer.yugabyte.ysql.ast.YSQLBetweenOperation;
import sqlancer.yugabyte.ysql.ast.YSQLBinaryLogicalOperation;
import sqlancer.yugabyte.ysql.ast.YSQLCaseExpression;
import sqlancer.yugabyte.ysql.ast.YSQLCastOperation;
import sqlancer.yugabyte.ysql.ast.YSQLColumnValue;
import sqlancer.yugabyte.ysql.ast.YSQLConstant;
import sqlancer.yugabyte.ysql.ast.YSQLCte;
import sqlancer.yugabyte.ysql.ast.YSQLDateTrunc;
import sqlancer.yugabyte.ysql.ast.YSQLExistsSubquery;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;
import sqlancer.yugabyte.ysql.ast.YSQLExtract;
import sqlancer.yugabyte.ysql.ast.YSQLFunction;
import sqlancer.yugabyte.ysql.ast.YSQLGroupingFunction;
import sqlancer.yugabyte.ysql.ast.YSQLGroupingSets;
import sqlancer.yugabyte.ysql.ast.YSQLInOperation;
import sqlancer.yugabyte.ysql.ast.YSQLInSubquery;
import sqlancer.yugabyte.ysql.ast.YSQLJSONBFunction;
import sqlancer.yugabyte.ysql.ast.YSQLJSONBOperation;
import sqlancer.yugabyte.ysql.ast.YSQLLikeOperation;
import sqlancer.yugabyte.ysql.ast.YSQLOrderByTerm;
import sqlancer.yugabyte.ysql.ast.YSQLOrderedSetAggregate;
import sqlancer.yugabyte.ysql.ast.YSQLOverlay;
import sqlancer.yugabyte.ysql.ast.YSQLPOSIXRegularExpression;
import sqlancer.yugabyte.ysql.ast.YSQLPosition;
import sqlancer.yugabyte.ysql.ast.YSQLPostfixOperation;
import sqlancer.yugabyte.ysql.ast.YSQLPostfixText;
import sqlancer.yugabyte.ysql.ast.YSQLPrefixOperation;
import sqlancer.yugabyte.ysql.ast.YSQLQuantifiedComparison;
import sqlancer.yugabyte.ysql.ast.YSQLScalarSubquery;
import sqlancer.yugabyte.ysql.ast.YSQLSelect;
import sqlancer.yugabyte.ysql.ast.YSQLSelect.YSQLFromTable;
import sqlancer.yugabyte.ysql.ast.YSQLSelect.YSQLSubquery;
import sqlancer.yugabyte.ysql.ast.YSQLSetOperation;
import sqlancer.yugabyte.ysql.ast.YSQLSimilarTo;
import sqlancer.yugabyte.ysql.ast.YSQLSubstringGrammar;
import sqlancer.yugabyte.ysql.ast.YSQLTrimGrammar;
import sqlancer.yugabyte.ysql.ast.YSQLWindowFunctionExpression;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

public interface YSQLVisitor {

    static String asString(YSQLExpression expr) {
        YSQLToStringVisitor visitor = new YSQLToStringVisitor();
        visitor.visit(expr);
        return visitor.get();
    }

    static String asExpectedValues(YSQLExpression expr) {
        YSQLExpectedValueVisitor v = new YSQLExpectedValueVisitor();
        v.visit(expr);
        return v.get();
    }

    static String getExpressionAsString(YSQLGlobalState globalState, YSQLDataType type, List<YSQLColumn> columns) {
        YSQLExpression expression = YSQLExpressionGenerator.generateExpression(globalState, columns, type);
        YSQLToStringVisitor visitor = new YSQLToStringVisitor();
        visitor.visit(expression);
        return visitor.get();
    }

    void visit(YSQLConstant constant);

    void visit(YSQLPostfixOperation op);

    void visit(YSQLColumnValue c);

    void visit(YSQLPrefixOperation op);

    void visit(YSQLSelect op);

    void visit(YSQLOrderByTerm op);

    void visit(YSQLFunction f);

    void visit(YSQLCastOperation cast);

    void visit(YSQLBetweenOperation op);

    void visit(YSQLInOperation op);

    void visit(YSQLPostfixText op);

    void visit(YSQLAggregate op);

    void visit(YSQLSimilarTo op);

    void visit(YSQLLikeOperation op);

    void visit(YSQLExtract op);

    void visit(YSQLAtTimeZone op);

    void visit(YSQLDateTrunc op);

    void visit(YSQLPOSIXRegularExpression op);

    void visit(YSQLFromTable from);

    void visit(YSQLSubquery subquery);

    void visit(YSQLBinaryLogicalOperation op);

    void visit(YSQLJSONBOperation op);

    void visit(YSQLJSONBFunction op);

    void visit(YSQLCaseExpression op);

    void visit(YSQLWindowFunctionExpression op);

    void visit(YSQLCte cte);

    void visit(YSQLSetOperation op);

    void visit(YSQLExistsSubquery op);

    void visit(YSQLInSubquery op);

    void visit(YSQLQuantifiedComparison op);

    void visit(YSQLScalarSubquery op);

    void visit(YSQLGroupingSets op);

    void visit(YSQLGroupingFunction op);

    void visit(YSQLOrderedSetAggregate op);

    void visit(YSQLSubstringGrammar op);

    void visit(YSQLPosition op);

    void visit(YSQLTrimGrammar op);

    void visit(YSQLOverlay op);

    default void visit(YSQLExpression expression) {
        if (expression instanceof YSQLConstant) {
            visit((YSQLConstant) expression);
        } else if (expression instanceof YSQLPostfixOperation) {
            visit((YSQLPostfixOperation) expression);
        } else if (expression instanceof YSQLColumnValue) {
            visit((YSQLColumnValue) expression);
        } else if (expression instanceof YSQLPrefixOperation) {
            visit((YSQLPrefixOperation) expression);
        } else if (expression instanceof YSQLSelect) {
            visit((YSQLSelect) expression);
        } else if (expression instanceof YSQLOrderByTerm) {
            visit((YSQLOrderByTerm) expression);
        } else if (expression instanceof YSQLFunction) {
            visit((YSQLFunction) expression);
        } else if (expression instanceof YSQLCastOperation) {
            visit((YSQLCastOperation) expression);
        } else if (expression instanceof YSQLBetweenOperation) {
            visit((YSQLBetweenOperation) expression);
        } else if (expression instanceof YSQLInOperation) {
            visit((YSQLInOperation) expression);
        } else if (expression instanceof YSQLAggregate) {
            visit((YSQLAggregate) expression);
        } else if (expression instanceof YSQLPostfixText) {
            visit((YSQLPostfixText) expression);
        } else if (expression instanceof YSQLSimilarTo) {
            visit((YSQLSimilarTo) expression);
        } else if (expression instanceof YSQLLikeOperation) {
            visit((YSQLLikeOperation) expression);
        } else if (expression instanceof YSQLExtract) {
            visit((YSQLExtract) expression);
        } else if (expression instanceof YSQLAtTimeZone) {
            visit((YSQLAtTimeZone) expression);
        } else if (expression instanceof YSQLDateTrunc) {
            visit((YSQLDateTrunc) expression);
        } else if (expression instanceof YSQLPOSIXRegularExpression) {
            visit((YSQLPOSIXRegularExpression) expression);
        } else if (expression instanceof YSQLFromTable) {
            visit((YSQLFromTable) expression);
        } else if (expression instanceof YSQLSubquery) {
            visit((YSQLSubquery) expression);
        } else if (expression instanceof YSQLJSONBOperation) {
            visit((YSQLJSONBOperation) expression);
        } else if (expression instanceof YSQLJSONBFunction) {
            visit((YSQLJSONBFunction) expression);
        } else if (expression instanceof YSQLCaseExpression) {
            visit((YSQLCaseExpression) expression);
        } else if (expression instanceof YSQLWindowFunctionExpression) {
            visit((YSQLWindowFunctionExpression) expression);
        } else if (expression instanceof YSQLCte) {
            visit((YSQLCte) expression);
        } else if (expression instanceof YSQLSetOperation) {
            visit((YSQLSetOperation) expression);
        } else if (expression instanceof YSQLExistsSubquery) {
            visit((YSQLExistsSubquery) expression);
        } else if (expression instanceof YSQLInSubquery) {
            visit((YSQLInSubquery) expression);
        } else if (expression instanceof YSQLQuantifiedComparison) {
            visit((YSQLQuantifiedComparison) expression);
        } else if (expression instanceof YSQLScalarSubquery) {
            visit((YSQLScalarSubquery) expression);
        } else if (expression instanceof YSQLGroupingSets) {
            visit((YSQLGroupingSets) expression);
        } else if (expression instanceof YSQLGroupingFunction) {
            visit((YSQLGroupingFunction) expression);
        } else if (expression instanceof YSQLOrderedSetAggregate) {
            visit((YSQLOrderedSetAggregate) expression);
        } else if (expression instanceof YSQLSubstringGrammar) {
            visit((YSQLSubstringGrammar) expression);
        } else if (expression instanceof YSQLPosition) {
            visit((YSQLPosition) expression);
        } else if (expression instanceof YSQLTrimGrammar) {
            visit((YSQLTrimGrammar) expression);
        } else if (expression instanceof YSQLOverlay) {
            visit((YSQLOverlay) expression);
        } else {
            throw new AssertionError(expression);
        }
    }

}
