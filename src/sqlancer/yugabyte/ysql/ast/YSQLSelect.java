package sqlancer.yugabyte.ysql.ast;

import java.util.Collections;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.ast.SelectBase;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public class YSQLSelect extends SelectBase<YSQLExpression> implements YSQLExpression {

    private SelectType selectOption = SelectType.ALL;
    private List<YSQLJoin> joinClauses = Collections.emptyList();
    private YSQLExpression distinctOnClause;
    private ForClause forClause;
    private List<YSQLCte> cteList = Collections.emptyList();

    public List<YSQLCte> getCteList() {
        return cteList;
    }

    public void setCteList(List<YSQLCte> cteList) {
        this.cteList = cteList;
    }

    public void setSelectType(SelectType fromOptions) {
        this.setSelectOption(fromOptions);
    }

    public SelectType getSelectOption() {
        return selectOption;
    }

    public void setSelectOption(SelectType fromOptions) {
        this.selectOption = fromOptions;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return null;
    }

    public List<YSQLJoin> getJoinClauses() {
        return joinClauses;
    }

    public void setJoinClauses(List<YSQLJoin> joinStatements) {
        this.joinClauses = joinStatements;

    }

    public YSQLExpression getDistinctOnClause() {
        return distinctOnClause;
    }

    public void setDistinctOnClause(YSQLExpression distinctOnClause) {
        if (selectOption != SelectType.DISTINCT) {
            throw new IllegalArgumentException();
        }
        this.distinctOnClause = distinctOnClause;
    }

    public ForClause getForClause() {
        return forClause;
    }

    public void setForClause(ForClause forClause) {
        this.forClause = forClause;
    }

    public enum ForClause {
        UPDATE("UPDATE"), NO_KEY_UPDATE("NO KEY UPDATE"), SHARE("SHARE"), KEY_SHARE("KEY SHARE");

        private final String textRepresentation;

        ForClause(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public static ForClause getRandom() {
            return Randomly.fromOptions(values());
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

    public enum SelectType {
        DISTINCT, ALL;

        public static SelectType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public static class YSQLFromTable implements YSQLExpression {
        private final YSQLTable t;
        private final boolean only;
        private final boolean includeDescendants;

        public YSQLFromTable(YSQLTable t, boolean only) {
            this.t = t;
            this.only = only;
            // Decide the inheritance-descendants "*" suffix once, here, instead of at render time. Rendering the same
            // node twice (e.g. the CERT oracle EXPLAINs the query before and after a mutation) must produce identical
            // SQL; a render-time Randomly.getBoolean() made "FROM t" flip to "FROM t*" between renders, so the oracle
            // compared two different queries and could report a spurious "Inconsistent cardinality estimate".
            this.includeDescendants = !only && Randomly.getBoolean();
        }

        public YSQLTable getTable() {
            return t;
        }

        public boolean isOnly() {
            return only;
        }

        public boolean isIncludeDescendants() {
            return includeDescendants;
        }

        @Override
        public YSQLDataType getExpressionType() {
            return null;
        }
    }

    public static class YSQLSubquery implements YSQLExpression {
        private final YSQLSelect s;
        private final String name;

        public YSQLSubquery(YSQLSelect s, String name) {
            this.s = s;
            this.name = name;
        }

        public YSQLSelect getSelect() {
            return s;
        }

        public String getName() {
            return name;
        }

        @Override
        public YSQLDataType getExpressionType() {
            return null;
        }
    }

}
