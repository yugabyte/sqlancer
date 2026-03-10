package sqlancer.yugabyte.ysql.ast;

import java.util.List;

import sqlancer.Randomly;

public class YSQLGroupingSets implements YSQLExpression {

    private final GroupingSetType type;
    private final List<List<YSQLExpression>> sets;

    public YSQLGroupingSets(GroupingSetType type, List<List<YSQLExpression>> sets) {
        this.type = type;
        this.sets = sets;
    }

    public GroupingSetType getGroupingSetType() {
        return type;
    }

    public List<List<YSQLExpression>> getSets() {
        return sets;
    }

    public enum GroupingSetType {
        GROUPING_SETS, ROLLUP, CUBE;

        public static GroupingSetType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

}
