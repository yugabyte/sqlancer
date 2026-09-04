package sqlancer.yugabyte.ysql.oracle;

import static sqlancer.ComparatorHelper.getResultSetFirstColumnAsString;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.DQEBase;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractTables;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.YSQLVisitor;
import sqlancer.yugabyte.ysql.gen.YSQLExpressionGenerator;

/**
 * Differential Query Execution (DQE) oracle: a SELECT, an UPDATE and a DELETE that share the same WHERE predicate must
 * access the same set of rows. Two auxiliary columns are added to a table - a unique {@code rowId} and an
 * {@code updated} marker; the oracle then compares the rows a SELECT returns, the rows an UPDATE marks, and the rows a
 * DELETE removes, all under one generated predicate. A divergence is a predicate-evaluation inconsistency between the
 * read and write paths - a class of bug the SELECT-only oracles (NoREC, PQS, TLP, SCAN_GUC, DQP) cannot reach. See the
 * DQE paper (https://ieeexplore.ieee.org/document/10172736).
 *
 * <p>
 * To stay noise-free the oracle only compares rounds in which all three statements execute cleanly: if the shared
 * predicate raises any tolerated error (e.g. a type/expression error), the whole round is skipped rather than
 * attempting the fragile cross-statement error comparison the upstream MySQL implementation performs. All three
 * statements run inside one {@code BEGIN}/{@code ROLLBACK} so they observe a single consistent read snapshot (avoiding
 * spurious mismatches from YugabyteDB's per-transaction read times), and the table content is never actually modified.
 */
public class YSQLDQEOracle extends DQEBase<YSQLGlobalState> implements TestOracle<YSQLGlobalState> {

    private final ExpectedErrors errors = new ExpectedErrors();
    private final ExpectedErrors ddlErrors = new ExpectedErrors();

    public YSQLDQEOracle(YSQLGlobalState globalState) {
        super(globalState);
        YSQLErrors.addCommonExpressionErrors(errors);
        YSQLErrors.addCommonFetchErrors(errors);
        YSQLErrors.addTransactionErrors(errors);
        YSQLErrors.addSubqueryErrors(errors);
        YSQLErrors.addCommonInsertUpdateErrors(errors);

        YSQLErrors.addCommonTableErrors(ddlErrors);
        YSQLErrors.addTransactionErrors(ddlErrors);
        ddlErrors.add("does not exist");
        ddlErrors.add("already exists");
        // Other DDL generators may have already grown the table to the column limit before the aux columns are added.
        ddlErrors.add("tables can have at most 1600 columns");
    }

    @Override
    public String generateSelectStatement(AbstractTables<?, ?> tables, String tableName, String whereClauseStr) {
        return "SELECT " + COLUMN_ROWID + " FROM " + tableName + " WHERE " + whereClauseStr;
    }

    @Override
    public String generateUpdateStatement(AbstractTables<?, ?> tables, String tableName, String whereClauseStr) {
        return "UPDATE " + tableName + " SET " + COLUMN_UPDATED + " = 1 WHERE " + whereClauseStr;
    }

    @Override
    public String generateDeleteStatement(String tableName, String whereClauseStr) {
        return "DELETE FROM " + tableName + " WHERE " + whereClauseStr;
    }

    @Override
    public void check() throws SQLException {
        List<YSQLTable> candidates = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView() && !t.isMaterializedView() && t.isInsertable()).collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw new IgnoreMeException();
        }
        YSQLTable table = Randomly.fromList(candidates);
        if (table.getNrRows(state) == 0) {
            throw new IgnoreMeException();
        }
        String tableName = table.getName();
        // A DO INSTEAD rule on SELECT/UPDATE/DELETE (pg_rewrite.ev_type 1/2/4) rewrites or silences that statement
        // (e.g. DO INSTEAD NOTHING on DELETE makes DELETE a no-op while SELECT/UPDATE still return the row) - correct
        // PostgreSQL semantics, but produces exactly the row-set mismatch pattern DQE looks for. Skip the round.
        if (hasInsteadRule(tableName)) {
            throw new IgnoreMeException();
        }

        YSQLExpressionGenerator gen = new YSQLExpressionGenerator(state).setColumns(table.getColumns());
        String whereClauseStr = YSQLVisitor.asString(gen.generateExpression(0, YSQLDataType.BOOLEAN));

        boolean added = false;
        try {
            addAuxiliaryColumns(table);
            added = true;

            // Run every read and write inside a single transaction so all three row sets are observed at one
            // consistent snapshot. Comparing an autocommit SELECT against separate per-statement transactions let
            // YugabyteDB's per-transaction read time differ (notably on colocated databases), which surfaced as
            // spurious mismatches (SELECT/UPDATE find a row but DELETE reports none). The UPDATE and DELETE are rolled
            // back, so the table is never actually modified.
            new SQLQueryAdapter("BEGIN").execute(state);
            try {
                Set<String> selectRows = new HashSet<>(getResultSetFirstColumnAsString(
                        generateSelectStatement(null, tableName, whereClauseStr), errors, state));

                if (!new SQLQueryAdapter(generateUpdateStatement(null, tableName, whereClauseStr), errors)
                        .execute(state)) {
                    throw new IgnoreMeException();
                }
                Set<String> updateRows = new HashSet<>(getResultSetFirstColumnAsString(
                        "SELECT " + COLUMN_ROWID + " FROM " + tableName + " WHERE " + COLUMN_UPDATED + " = 1", errors,
                        state));

                Set<String> deleteRows = new HashSet<>(getResultSetFirstColumnAsString(
                        "SELECT " + COLUMN_ROWID + " FROM " + tableName, errors, state));
                if (!new SQLQueryAdapter(generateDeleteStatement(tableName, whereClauseStr), errors).execute(state)) {
                    throw new IgnoreMeException();
                }
                deleteRows.removeAll(new HashSet<>(getResultSetFirstColumnAsString(
                        "SELECT " + COLUMN_ROWID + " FROM " + tableName, errors, state)));

                if (!selectRows.equals(updateRows) || !selectRows.equals(deleteRows)) {
                    throw new AssertionError(
                            String.format("DQE row-set mismatch on %s WHERE %s%n  SELECT=%s%n  UPDATE=%s%n  DELETE=%s",
                                    tableName, whereClauseStr, selectRows, updateRows, deleteRows));
                }
            } finally {
                new SQLQueryAdapter("ROLLBACK").execute(state);
            }
        } finally {
            if (added) {
                dropAuxiliaryColumns(table);
            }
        }
    }

    private boolean hasInsteadRule(String tableName) throws SQLException {
        // pg_rewrite.ev_type: '1'=SELECT, '2'=UPDATE, '4'=DELETE. INSERT ('3') is not on DQE's path.
        String q = "SELECT 1 FROM pg_rewrite WHERE ev_class = '" + tableName
                + "'::regclass AND is_instead AND ev_type IN ('1','2','4') LIMIT 1";
        return !getResultSetFirstColumnAsString(q, errors, state).isEmpty();
    }

    @Override
    public void addAuxiliaryColumns(AbstractRelationalTable<?, ?, ?> table) throws SQLException {
        String tableName = table.getName();
        // Nullable columns only: no DEFAULT avoids YugabyteDB's ADD-COLUMN-with-default table rewrite. The updated
        // marker is left NULL and set to 1 for matched rows, so "WHERE updated = 1" selects exactly the touched rows.
        if (!new SQLQueryAdapter("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + COLUMN_ROWID + " text",
                ddlErrors).execute(state)) {
            throw new IgnoreMeException();
        }
        if (!new SQLQueryAdapter("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + COLUMN_UPDATED + " int",
                ddlErrors).execute(state)) {
            throw new IgnoreMeException();
        }
        if (!new SQLQueryAdapter("UPDATE " + tableName + " SET " + COLUMN_ROWID + " = gen_random_uuid()::text", errors)
                .execute(state)) {
            throw new IgnoreMeException();
        }
    }

    @Override
    public void dropAuxiliaryColumns(AbstractRelationalTable<?, ?, ?> table) throws SQLException {
        String tableName = table.getName();
        new SQLQueryAdapter("ALTER TABLE " + tableName + " DROP COLUMN IF EXISTS " + COLUMN_ROWID, ddlErrors)
                .execute(state);
        new SQLQueryAdapter("ALTER TABLE " + tableName + " DROP COLUMN IF EXISTS " + COLUMN_UPDATED, ddlErrors)
                .execute(state);
    }
}
