package sqlancer.yugabyte.ycql.ast;

import sqlancer.common.ast.SelectBase;
import sqlancer.common.ast.newast.Node;

public class YCQLSelect extends SelectBase<Node<YCQLExpression>> implements Node<YCQLExpression> {

    private boolean isDistinct;
    private boolean allowFiltering;

    public void setDistinct(boolean isDistinct) {
        this.isDistinct = isDistinct;
    }

    public boolean isDistinct() {
        return isDistinct;
    }

    public void setAllowFiltering(boolean allowFiltering) {
        this.allowFiltering = allowFiltering;
    }

    public boolean isAllowFiltering() {
        return allowFiltering;
    }

}
