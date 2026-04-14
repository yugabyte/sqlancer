package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;

public final class YSQLDomainGenerator {

    private static final int MAX_DOMAINS = 5;

    private YSQLDomainGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        errors.add("already exists");
        errors.add("does not exist");
        errors.add("cannot be dropped because some objects depend on it");
        errors.add("cannot be implemented without a definition");
        errors.add("is not a domain");
        errors.add("column \"value\" not found");
        errors.add("cannot be cast");
        errors.add("constraint");
        errors.add("violates check constraint");

        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            return generateCreate(globalState, errors);
        case 1:
            return generateAlterDefault(errors);
        case 2:
            return generateAlterConstraint(globalState, errors);
        case 3:
            return generateDrop(errors);
        default:
            throw new AssertionError();
        }
    }

    private static SQLQueryAdapter generateCreate(YSQLGlobalState globalState, ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String domainName = "dom" + globalState.getRandomly().getInteger(0, MAX_DOMAINS);
        sb.append("CREATE DOMAIN ").append(domainName).append(" AS ");
        String baseType = Randomly.fromOptions("INTEGER", "TEXT", "BOOLEAN", "NUMERIC", "BIGINT", "VARCHAR(100)");
        sb.append(baseType);
        if (Randomly.getBoolean()) {
            sb.append(" DEFAULT ");
            appendDefaultForType(sb, baseType);
        }
        if (Randomly.getBoolean()) {
            sb.append(" NOT NULL");
        }
        if (Randomly.getBoolean()) {
            sb.append(" CHECK (VALUE IS NOT NULL)");
        } else if (Randomly.getBoolean() && isNumericType(baseType)) {
            sb.append(" CHECK (VALUE >= 0)");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static SQLQueryAdapter generateAlterDefault(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String domainName = "dom" + Randomly.smallNumber() % MAX_DOMAINS;
        sb.append("ALTER DOMAIN ").append(domainName);
        if (Randomly.getBoolean()) {
            sb.append(" SET DEFAULT ");
            sb.append(Randomly.fromOptions("0", "'default_val'", "TRUE", "42"));
        } else {
            sb.append(" DROP DEFAULT");
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateAlterConstraint(YSQLGlobalState globalState, ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        String domainName = "dom" + globalState.getRandomly().getInteger(0, MAX_DOMAINS);
        sb.append("ALTER DOMAIN ").append(domainName);
        if (Randomly.getBoolean()) {
            sb.append(" SET NOT NULL");
        } else if (Randomly.getBoolean()) {
            sb.append(" DROP NOT NULL");
        } else {
            sb.append(" DROP CONSTRAINT IF EXISTS dom_check");
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static SQLQueryAdapter generateDrop(ExpectedErrors errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("DROP DOMAIN IF EXISTS dom").append(Randomly.smallNumber() % MAX_DOMAINS);
        if (Randomly.getBoolean()) {
            sb.append(" CASCADE");
        }
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static void appendDefaultForType(StringBuilder sb, String baseType) {
        if (isNumericType(baseType)) {
            sb.append(Randomly.smallNumber());
        } else if (baseType.equals("BOOLEAN")) {
            sb.append(Randomly.fromOptions("TRUE", "FALSE"));
        } else {
            sb.append("'default'");
        }
    }

    private static boolean isNumericType(String type) {
        return type.equals("INTEGER") || type.equals("NUMERIC") || type.equals("BIGINT");
    }

}
