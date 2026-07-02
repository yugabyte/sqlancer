package sqlancer.yugabyte.ysql.gen;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.yugabyte.ysql.YSQLErrors;
import sqlancer.yugabyte.ysql.YSQLGlobalState;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;

// Simplified vector (pgvector) operations generator that works with the existing schema. Exercises vector literal,
// distance-operator, cast, index, and GUC syntax without requiring a dedicated vector column type.
public final class YSQLSimpleVectorGenerator {

    private YSQLSimpleVectorGenerator() {
    }

    // Creates the pgvector extension so the other branches exercise a real vector type/index rather than failing with
    // "type vector does not exist". Only emitted when the cluster reports pgvector as available.
    public static SQLQueryAdapter createVectorExtension() {
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("permission denied to create extension");
        errors.add("must be superuser");
        errors.add("could not open extension control file");
        errors.add("is not available");
        errors.add("does not exist");
        errors.add("already exists");
        errors.add("not supported");
        errors.add("This statement not supported yet");
        YSQLErrors.addTransactionErrors(errors);
        return new SQLQueryAdapter("CREATE EXTENSION IF NOT EXISTS vector", errors, true);
    }

    // Exercises vector literal / distance / cast syntax with mock vector data.
    public static SQLQueryAdapter testVectorSyntax(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder();

        // Test various vector operations
        String operation = Randomly.fromOptions("vector_literal", "vector_distance", "vector_cast", "vector_aggregate");

        switch (operation) {
        case "vector_literal":
            // Test vector literal syntax
            sb.append("SELECT ");
            sb.append(generateVectorLiteral());
            sb.append(" AS test_vector");
            break;

        case "vector_distance":
            // Test distance operators
            sb.append("SELECT ");
            String v1 = generateVectorLiteral();
            String v2 = generateVectorLiteral();
            String op = Randomly.fromOptions("<->", "<#>", "<=>");
            // Cast both operands to vector so the distance operator resolves; two untyped literals make it ambiguous
            // ("operator is not unique: unknown <-> unknown") because pgvector defines several vector types.
            sb.append(v1).append("::vector ").append(op).append(" ").append(v2).append("::vector");
            sb.append(" AS distance");
            break;

        case "vector_cast":
            // Test casting to vector type
            sb.append("SELECT CAST(");
            sb.append(generateArrayLiteral());
            sb.append(" AS vector)");
            break;

        case "vector_aggregate":
            // Test with actual table but using mock vector data
            YSQLTable table = globalState.getSchema().getRandomTable();
            sb.append("SELECT COUNT(*) FROM ");
            sb.append(table.getName());
            sb.append(" WHERE ");
            sb.append(generateVectorLiteral());
            sb.append(" IS NOT NULL");
            break;
        default:
            break;
        }

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("type \"vector\" does not exist");
        errors.add("operator does not exist");
        errors.add("operator is not unique");
        errors.add("Could not choose a best candidate operator");
        errors.add("cannot cast");
        errors.add("invalid input syntax for type vector");
        errors.add("malformed vector literal");
        errors.add("vector dimension mismatch");
        errors.add("different vector dimensions");
        errors.add("vector must have at least");
        errors.add("access method \"hnsw\" does not exist");
        errors.add("access method \"ybhnsw\" does not exist");
        YSQLErrors.addCommonFetchErrors(errors);
        // A preceding failed statement aborts the surrounding transaction; this probe then cascades with
        // "current transaction is aborted, commands ignored until end of transaction block".
        YSQLErrors.addTransactionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    // Exercises CREATE INDEX with vector index methods (hnsw/ybhnsw/ivfflat), operator classes, and HNSW parameters.
    public static SQLQueryAdapter testVectorIndexSyntax(YSQLGlobalState globalState) {
        YSQLTable table = globalState.getSchema().getRandomTable(t -> !t.isView());

        StringBuilder sb = new StringBuilder("CREATE INDEX ");
        sb.append("test_vec_idx_").append(Randomly.smallNumber());
        sb.append(" ON ").append(table.getName());
        sb.append(" USING ");

        // Try to use vector index methods (will fail without extension)
        String method = Randomly.fromOptions("hnsw", "ybhnsw", "ivfflat");
        sb.append(method);

        sb.append(" (");
        sb.append(table.getRandomColumn().getName());

        // Try to add vector operator class
        if (Randomly.getBoolean()) {
            sb.append(" ");
            String opClass = Randomly.fromOptions("vector_l2_ops", "vector_ip_ops", "vector_cosine_ops");
            sb.append(opClass);
        }
        sb.append(")");

        // Try to add HNSW parameters. Only emit WITH (...) when at least one parameter is present, and place the
        // comma only between two present parameters - otherwise we generate invalid syntax ("WITH ()", a trailing
        // comma, or two params with no separator).
        if (method.contains("hnsw") && Randomly.getBoolean()) {
            boolean addM = Randomly.getBoolean();
            boolean addEfConstruction = Randomly.getBoolean();
            if (addM || addEfConstruction) {
                sb.append(" WITH (");
                if (addM) {
                    // YB's ybhnsw rejects small m values ("value N out of bounds for option m"), so stay >= 16.
                    sb.append("m = ").append(Randomly.fromOptions(16, 32, 48, 64));
                }
                if (addEfConstruction) {
                    if (addM) {
                        sb.append(", ");
                    }
                    sb.append("ef_construction = ").append(Randomly.fromOptions(64, 200, 500));
                }
                sb.append(")");
            }
        }

        ExpectedErrors errors = new ExpectedErrors();
        // ExpectedErrors matches by substring, not regex - use literal fragments. On builds without pgvector the
        // access method / operator class simply does not exist, which must be treated as expected.
        errors.add("access method");
        errors.add("operator class");
        errors.add("does not exist");
        errors.add("has no default operator class");
        errors.add("cannot create index on dimensionless vector column");
        errors.add("vector indexes do not support");
        errors.add("out of bounds for option");
        errors.add("extension");
        errors.add("must be installed");
        YSQLErrors.addCommonTableErrors(errors);
        YSQLErrors.addTransactionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors);
    }

    // Exercises SET of vector search parameters (hnsw.ef_search / ivfflat.probes).
    public static SQLQueryAdapter testVectorSettings(YSQLGlobalState globalState) {
        StringBuilder sb = new StringBuilder("SET ");

        if (Randomly.getBoolean()) {
            sb.append("LOCAL ");
        }

        // Try to set vector-related parameters. "ybhnsw" is a reserved GUC prefix on YugabyteDB (SET always fails
        // with "invalid configuration parameter name"), so it is not a useful probe target - omit it.
        String param = Randomly.fromOptions("hnsw.ef_search", "ivfflat.probes");

        sb.append(param).append(" = ");
        sb.append(Randomly.fromOptions(10, 40, 100, 200));

        ExpectedErrors errors = new ExpectedErrors();
        errors.add("unrecognized configuration parameter");
        errors.add("cannot be set");
        errors.add("invalid value for parameter");
        errors.add("invalid configuration parameter name");
        errors.add("reserved prefix");
        YSQLErrors.addTransactionErrors(errors);

        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static String generateVectorLiteral() {
        int dimensions = Randomly.fromOptions(2, 3, 4, 8);
        StringBuilder sb = new StringBuilder("'[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(Randomly.getUncachedDouble());
        }
        sb.append("]'");

        // Optionally add cast
        if (Randomly.getBoolean()) {
            sb.append("::vector");
            if (Randomly.getBoolean()) {
                sb.append("(").append(dimensions).append(")");
            }
        }

        return sb.toString();
    }

    private static String generateArrayLiteral() {
        int dimensions = Randomly.fromOptions(2, 3, 4);
        StringBuilder sb = new StringBuilder("ARRAY[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(Randomly.getUncachedDouble());
        }
        sb.append("]");
        return sb.toString();
    }
}
