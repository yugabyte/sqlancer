package sqlancer.yugabyte.ycql;

import sqlancer.common.query.ExpectedErrors;

public final class YCQLErrors {

    private YCQLErrors() {
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        errors.add("Signature mismatch in call to builtin function");
        errors.add("Qualified name not allowed for column reference");
        errors.add("Datatype Mismatch");
        errors.add("Invalid CQL Statement");
        errors.add("Invalid SQL Statement");

    }

    public static void addInsertErrors(ExpectedErrors errors) {
        errors.add("Datatype Mismatch");
        errors.add("Invalid CQL Statement");
        errors.add("Invalid SQL Statement");
    }

    public static void addGroupByErrors(ExpectedErrors errors) {
        errors.add("Datatype Mismatch");
        errors.add("Invalid CQL Statement");
        errors.add("Invalid SQL Statement");
    }

}
