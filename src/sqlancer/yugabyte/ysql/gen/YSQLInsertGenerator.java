package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;

import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.ast.YSQLExpression;

public final class YSQLInsertGenerator {

    private YSQLInsertGenerator() {
    }

    public static SQLQueryAdapter insert(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable(YSQLTable::isInsertable);
        ExpectedErrors errors = new ExpectedErrors();
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonInsertUpdateErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        errors.add("cannot insert into column");
        errors.add("violates not-null constraint");
        errors.add("does not support Infinity yet");
        errors.add("cannot insert a non-DEFAULT value into column");
        errors.add("multiple assignments to same column");
        errors.add("violates foreign key constraint");
        errors.add("value too long for type character varying");
        errors.add("conflicting key value violates exclusion constraint");
        errors.add("current transaction is aborted");
        errors.add("bit string too long");
        errors.add("new row violates check option for view");
        errors.add("reached maximum value of sequence");
        errors.add("but expression is of type");
        errors.add("there is no unique or exclusion constraint matching the ON CONFLICT specification");
        errors.add("duplicate key value violates unique constraint");
        errors.add("identity column defined as GENERATED ALWAYS");
        errors.add("out of range");
        errors.add("violates check constraint");
        errors.add("no partition of relation");
        errors.add("invalid input syntax");
        errors.add("division by zero");
        errors.add("violates foreign key constraint");
        errors.add("data type unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ");
        sb.append(table.getName());
        List<YSQLColumn> columns = table.getRandomNonEmptyColumnSubset();
        sb.append("(");
        sb.append(columns.stream().map(AbstractTableColumn::getName).collect(Collectors.joining(", ")));
        sb.append(")");
        if (Randomly.getBooleanWithRatherLowProbability()) {
            sb.append(" OVERRIDING");
            sb.append(" ");
            sb.append(Randomly.fromOptions("SYSTEM", "USER"));
            sb.append(" VALUE");
        }
        sb.append(" VALUES");

        if (globalState.getDbmsSpecificOptions().allowBulkInsert && Randomly.getBooleanWithSmallProbability()) {
            StringBuilder sbRowValue = new StringBuilder();
            sbRowValue.append("(");
            for (int i = 0; i < columns.size(); i++) {
                if (i != 0) {
                    sbRowValue.append(", ");
                }
                sbRowValue.append(YSQLVisitor.asString(
                        YSQLExpressionGenerator.generateConstant(globalState.getRandomly(), columns.get(i).getType())));
            }
            sbRowValue.append(")");

            int n = (int) Randomly.getNotCachedInteger(100, 1000);
            for (int i = 0; i < n; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(sbRowValue);
            }
        } else {
            int n = Randomly.smallNumber() + 1;
            for (int i = 0; i < n; i++) {
                if (i != 0) {
                    sb.append(", ");
                }
                insertRow(globalState, sb, columns, n == 1);
            }
        }
        // Enhanced ON CONFLICT support for YugabyteDB 2025.1
//        if (Randomly.getBooleanWithRatherLowProbability()) {
//            sb.append(" ON CONFLICT ");
//
//            // Conflict target specification
//            if (Randomly.getBoolean()) {
//                sb.append("(");
//                // Support multiple columns for composite constraints
//                List<YSQLColumn> conflictColumns = table.getRandomNonEmptyColumnSubset(
//                        Randomly.fromOptions(1, 2, 3));
//                sb.append(conflictColumns.stream()
//                        .map(AbstractTableColumn::getName)
//                        .collect(Collectors.joining(", ")));
//
//                // Optional WHERE clause for partial unique index (2025.1 enhancement)
//                if (Randomly.getBooleanWithRatherLowProbability()) {
//                    sb.append(") WHERE ");
//                    YSQLExpression whereExpr = YSQLExpressionGenerator.generateExpression(globalState,
//                            table.getColumns());
//                    sb.append(YSQLVisitor.asString(whereExpr));
//                } else {
//                    sb.append(")");
//                }
//                errors.add("there is no unique or exclusion constraint matching the ON CONFLICT specification");
//            }
//
//            // Conflict action: DO NOTHING or DO UPDATE
//            if (Randomly.getBoolean()) {
//                sb.append(" DO NOTHING");
//            } else {
//                // DO UPDATE with enhanced support
//                sb.append(" DO UPDATE SET ");
//                List<YSQLColumn> updateColumns = table.getRandomNonEmptyColumnSubset();
//                for (int i = 0; i < updateColumns.size(); i++) {
//                    if (i > 0) sb.append(", ");
//                    YSQLColumn col = updateColumns.get(i);
//                    sb.append(col.getName()).append(" = ");
//
//                    if (Randomly.getBoolean()) {
//                        // Use EXCLUDED values
//                        sb.append("EXCLUDED.").append(col.getName());
//                    } else if (Randomly.getBoolean()) {
//                        // Use expression
//                        YSQLExpression expr = YSQLExpressionGenerator.generateConstant(
//                                globalState.getRandomly(), col.getType());
//                        sb.append(YSQLVisitor.asString(expr));
//                    } else {
//                        // Use current value with modification
//                        sb.append(table.getName()).append(".").append(col.getName());
//                        if (col.getType() == YSQLDataType.TEXT) {
//                            sb.append(" || '_updated'");
//                        } else if (col.getType() == YSQLDataType.INT) {
//                            sb.append(" + 1");
//                        }
//                    }
//                }
//
//                // Optional WHERE clause for conditional update
//                if (Randomly.getBooleanWithRatherLowProbability()) {
//                    sb.append(" WHERE ");
//                    YSQLExpression whereExpr = new YSQLExpressionGenerator(globalState)
//                            .setColumns(table.getColumns())
//                            .generateExpression(YSQLDataType.BOOLEAN);
//                    sb.append(YSQLVisitor.asString(whereExpr));
//                }
//
//                errors.add("ON CONFLICT DO UPDATE command cannot affect row a second time");
//                errors.add("column reference is ambiguous");
//            }
//        }
        
        // RETURNING clause support (enhanced in 2025.1 for ON CONFLICT)
//        if (Randomly.getBooleanWithRatherLowProbability()) {
//            sb.append(" RETURNING ");
//            if (Randomly.getBoolean()) {
//                sb.append("*");
//            } else {
//                List<YSQLColumn> returningColumns = table.getRandomNonEmptyColumnSubset();
//                sb.append(returningColumns.stream()
//                        .map(AbstractTableColumn::getName)
//                        .collect(Collectors.joining(", ")));
//            }
//            errors.add("corrupt data for INSERT ON CONFLICT with RETURNING");
//        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static void insertRow(YSQLGlobalState globalState, StringBuilder sb, List<YSQLColumn> columns,
            boolean canBeDefault) {
        sb.append("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            if (!Randomly.getBooleanWithSmallProbability() || !canBeDefault) {
                YSQLExpression generateConstant;
                if (Randomly.getBoolean()) {
                    generateConstant = YSQLExpressionGenerator.generateConstant(globalState.getRandomly(),
                            columns.get(i).getType());
                } else {
                    generateConstant = new YSQLExpressionGenerator(globalState)
                            .generateExpression(columns.get(i).getType());
                }
                sb.append(YSQLVisitor.asString(generateConstant));
            } else {
                sb.append("DEFAULT");
            }
        }
        sb.append(")");
    }

}
