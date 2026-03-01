package sqlancer.yugabyte.ysql.ast;

import java.util.List;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;

public class YSQLCte implements YSQLExpression {

    private final String name;
    private final List<String> columnNames;
    private final YSQLSelect query;

    public YSQLCte(String name, List<String> columnNames, YSQLSelect query) {
        this.name = name;
        this.columnNames = columnNames;
        this.query = query;
    }

    public YSQLCte(String name, YSQLSelect query) {
        this(name, null, query);
    }

    public String getName() {
        return name;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public YSQLSelect getQuery() {
        return query;
    }

    @Override
    public YSQLDataType getExpressionType() {
        return null;
    }
}
