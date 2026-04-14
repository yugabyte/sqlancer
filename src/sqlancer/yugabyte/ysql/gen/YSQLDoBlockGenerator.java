package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

public final class YSQLDoBlockGenerator {

    private YSQLDoBlockGenerator() {
    }

    public static SQLQueryAdapter generate(YSQLGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addCommonExpressionErrors(errors);
        errors.add("does not exist");
        errors.add("division by zero");
        errors.add("out of range");
        errors.add("syntax error");
        errors.add("query has no destination for result data");
        errors.add("query returned no rows");
        errors.add("duplicate key");
        errors.add("violates");
        errors.add("cannot cast");
        errors.add("invalid input syntax");

        StringBuilder sb = new StringBuilder();
        sb.append("DO $$ ");

        switch (Randomly.fromOptions(0, 1, 2, 3)) {
        case 0:
            appendRaiseBlock(sb);
            break;
        case 1:
            appendPerformBlock(sb, globalState);
            break;
        case 2:
            appendDeclareBlock(sb);
            break;
        case 3:
            appendIfBlock(sb, globalState);
            break;
        default:
            throw new AssertionError();
        }

        sb.append(" $$");
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static void appendRaiseBlock(StringBuilder sb) {
        sb.append("BEGIN RAISE NOTICE 'test notice %', ");
        sb.append(Randomly.smallNumber());
        sb.append("; END;");
    }

    private static void appendPerformBlock(StringBuilder sb, YSQLGlobalState globalState) {
        sb.append("BEGIN PERFORM ");
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            sb.append("1");
        } else {
            YSQLTable table = globalState.getSchema().getRandomTable();
            sb.append("count(*) FROM ").append(table.getName());
        }
        sb.append("; END;");
    }

    private static void appendDeclareBlock(StringBuilder sb) {
        sb.append("DECLARE v_val INTEGER; ");
        sb.append("BEGIN v_val := ").append(Randomly.smallNumber()).append("; ");
        sb.append("IF v_val > ").append(Randomly.smallNumber()).append(" THEN ");
        sb.append("RAISE NOTICE 'high: %', v_val; ");
        sb.append("END IF; END;");
    }

    private static void appendIfBlock(StringBuilder sb, YSQLGlobalState globalState) {
        sb.append("DECLARE v_cnt BIGINT; ");
        sb.append("BEGIN ");
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            sb.append("v_cnt := 0; ");
        } else {
            YSQLTable table = globalState.getSchema().getRandomTable();
            sb.append("SELECT count(*) INTO v_cnt FROM ").append(table.getName()).append("; ");
        }
        sb.append("IF v_cnt > ").append(Randomly.smallNumber()).append(" THEN ");
        sb.append("RAISE NOTICE 'count: %', v_cnt; ");
        sb.append("END IF; END;");
    }

}
