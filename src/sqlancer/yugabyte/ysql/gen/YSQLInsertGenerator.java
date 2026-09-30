package sqlancer.yugabyte.ysql.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
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
        YSQLErrors.addSubqueryErrors(errors);
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
        errors.add("data type unknown");
        errors.add("INSERT with ON CONFLICT clause cannot be used with table that has INSERT or UPDATE rules");
        if (globalState.isPgCompatible() && Randomly.getBooleanWithRatherLowProbability()) {
            return insertSeries(globalState, table, errors);
        }
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
                if (columns.get(i).isGenerated()) {
                    sbRowValue.append("DEFAULT");
                    continue;
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
        if (Randomly.getBooleanWithRatherLowProbability()) {
            sb.append(" ON CONFLICT");
            if (Randomly.getBoolean()) {
                sb.append(" (");
                List<YSQLColumn> conflictColumns = table.getRandomNonEmptyColumnSubset();
                sb.append(conflictColumns.stream().map(AbstractTableColumn::getName).collect(Collectors.joining(", ")));
                sb.append(")");
            }
            if (Randomly.getBoolean()) {
                sb.append(" DO NOTHING");
            } else {
                sb.append(" DO UPDATE SET ");
                List<YSQLColumn> updateColumns = table.getRandomNonEmptyColumnSubset();
                for (int i = 0; i < updateColumns.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    YSQLColumn col = updateColumns.get(i);
                    sb.append(col.getName()).append(" = ");
                    if (col.isGenerated()) {
                        sb.append("DEFAULT");
                    } else if (Randomly.getBoolean()) {
                        sb.append("EXCLUDED.").append(col.getName());
                    } else {
                        YSQLExpression expr = YSQLExpressionGenerator.generateConstant(globalState.getRandomly(),
                                col.getType());
                        sb.append(YSQLVisitor.asString(expr));
                    }
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    sb.append(" WHERE ");
                    sb.append(YSQLVisitor.asString(YSQLExpressionGenerator.generateExpression(globalState,
                            table.getColumns(), YSQLDataType.BOOLEAN)));
                }
                errors.add("ON CONFLICT DO UPDATE command cannot affect row a second time");
                errors.add("ON CONFLICT DO UPDATE requires inference specification or constraint name");
                errors.add("column reference is ambiguous");
            }
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            sb.append(" RETURNING ");
            if (Randomly.getBoolean()) {
                sb.append("*");
            } else {
                List<YSQLColumn> returningColumns = table.getRandomNonEmptyColumnSubset();
                sb.append(
                        returningColumns.stream().map(AbstractTableColumn::getName).collect(Collectors.joining(", ")));
            }
            if (globalState.isPgCompatible() && Randomly.getBoolean()) {
                // PostgreSQL 18 old/new row aliases; old is NULL unless ON CONFLICT DO UPDATE fires.
                sb.append(Randomly.fromOptions(", old.*", ", new.*", ", old.*, new.*"));
            }
        }
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    // Many distinct rows in one statement: multi-page heaps and indexes, and TOAST-sized text and bytea values. The
    // values depend only on g, so a reproducer inserts the same rows.
    private static SQLQueryAdapter insertSeries(YSQLGlobalState globalState, YSQLTable table, ExpectedErrors errors) {
        List<YSQLColumn> columns = table.getRandomNonEmptyColumnSubset().stream().filter(c -> !c.isGenerated())
                .collect(Collectors.toList());
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        // 100+ md5 blocks (3.2 kB+) exceed the ~2 kB TOAST threshold even after compression.
        long repeat = Randomly.getBoolean() ? 1 : Randomly.getNotCachedInteger(100, 400);
        long rows = repeat == 1 ? Randomly.getNotCachedInteger(500, 5000) : Randomly.getNotCachedInteger(50, 500);
        String values = columns.stream().map(c -> seriesValue(globalState, c.getType(), repeat))
                .collect(Collectors.joining(", "));
        return new SQLQueryAdapter("INSERT INTO " + table.getName() + "("
                + columns.stream().map(AbstractTableColumn::getName).collect(Collectors.joining(", ")) + ") SELECT "
                + values + " FROM generate_series(1, " + rows + ") g"
                + (Randomly.getBoolean() ? " ON CONFLICT DO NOTHING" : ""), errors);
    }

    private static String seriesValue(YSQLGlobalState globalState, YSQLDataType type, long repeat) {
        switch (type) {
        case SMALLINT:
            return "(g % 30000)::smallint";
        case INT:
            return "g";
        case BIGINT:
            return "g::bigint * 1000003";
        case NUMERIC:
        case DECIMAL:
            return "g / 7.0";
        case REAL:
            return "(g / 3.0)::real";
        case DOUBLE_PRECISION:
        case FLOAT:
            return "g / 3.0::float8";
        case VARCHAR:
        case CHAR:
            return "left(md5(g::text), 1)"; // the declared length is not known here
        case TEXT:
            return "repeat(md5(g::text), " + repeat + ")";
        case BYTEA:
            return "decode(repeat(md5(g::text), " + repeat + "), 'hex')";
        case DATE:
            return "DATE '2000-01-01' + g";
        case TIME:
            return "TIME '00:00' + g * INTERVAL '1 second'";
        case TIMESTAMP:
            return "TIMESTAMP '2000-01-01' + g * INTERVAL '1 minute'";
        case TIMESTAMPTZ:
            return "TIMESTAMPTZ '2000-01-01 00:00:00+00' + g * INTERVAL '1 minute'";
        case INTERVAL:
            return "g * INTERVAL '1 second'";
        case BOOLEAN:
            return "g % 2 = 0";
        case INET:
            return "'10.0.0.0'::inet + g";
        case CIDR:
            return "('10.0.0.0'::inet + g)::cidr";
        case UUID:
            return "md5(g::text)::uuid";
        case JSON:
            return "json_build_object('g', g)";
        case JSONB:
            return "jsonb_build_object('g', g, 'v', md5(g::text))";
        case INT4RANGE:
        case RANGE:
            return "int4range(g, g + 10)";
        case INT8RANGE:
            return "int8range(g, g + 10)";
        case NUMRANGE:
            return "numrange(g, g + 1)";
        case DATERANGE:
            return "daterange(DATE '2000-01-01' + g, DATE '2000-01-01' + g + 5)";
        case TSRANGE:
            return "tsrange(TIMESTAMP '2000-01-01' + g * INTERVAL '1 minute', TIMESTAMP '2000-01-01' + g * INTERVAL"
                    + " '1 minute' + INTERVAL '1 hour')";
        case TSTZRANGE:
            return "tstzrange(TIMESTAMPTZ '2000-01-01 00:00:00+00' + g * INTERVAL '1 minute', TIMESTAMPTZ"
                    + " '2000-01-01 00:00:00+00' + g * INTERVAL '1 minute' + INTERVAL '1 hour')";
        case INT_ARRAY:
            return "ARRAY[g, g * 2]";
        case TEXT_ARRAY:
            return "ARRAY[md5(g::text)]";
        case BOOLEAN_ARRAY:
            return "ARRAY[g % 2 = 0]";
        case MONEY:
            return "g::numeric::money";
        case POINT:
            return "point(g, g)";
        case BOX:
            return "box(point(g, g), point(g + 1, g + 1))";
        case CIRCLE:
            return "circle(point(g, g), 1)";
        case LSEG:
            return "lseg(point(g, g), point(g + 1, g + 1))";
        default:
            return YSQLVisitor.asString(YSQLExpressionGenerator.generateConstant(globalState.getRandomly(), type));
        }
    }

    private static void insertRow(YSQLGlobalState globalState, StringBuilder sb, List<YSQLColumn> columns,
            boolean canBeDefault) {
        sb.append("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            if (columns.get(i).isGenerated()) {
                sb.append("DEFAULT");
            } else if (!Randomly.getBooleanWithSmallProbability() || !canBeDefault) {
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
