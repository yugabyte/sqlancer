package sqlancer.yugabyte.ysql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLGlobalState;

public class YSQLGlobalState extends SQLGlobalState<YSQLOptions, YSQLSchema> {

    public static final char IMMUTABLE = 'i';
    public static final char STABLE = 's';
    public static final char VOLATILE = 'v';
    // store and allow filtering by function volatility classifications
    private final Map<String, Character> functionsAndTypes = new HashMap<>();
    private List<String> operators = Collections.emptyList();
    private List<String> collates = Collections.emptyList();
    private List<String> opClasses = Collections.emptyList();
    // Whether the pgvector extension can be created on this cluster. Detected once at connection setup so the
    // VECTOR_TEST action is skipped entirely on builds without pgvector instead of emitting guaranteed-failing SQL.
    private boolean vectorAvailable;
    private List<Character> allowedFunctionTypes = Arrays.asList(IMMUTABLE, STABLE, VOLATILE);

    @Override
    public void setConnection(SQLConnection con) {
        super.setConnection(con);
        try {
            this.opClasses = getOpclasses(getConnection());
            this.operators = getOperators(getConnection());
            this.collates = getCollnames(getConnection());
            this.vectorAvailable = checkVectorAvailable(getConnection());
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    // Read-only probe of pg_available_extensions; swallows its own errors so connection setup is never broken by it.
    private boolean checkVectorAvailable(SQLConnection con) {
        try (Statement s = con.createStatement();
                ResultSet rs = s.executeQuery("SELECT 1 FROM pg_available_extensions WHERE name = 'vector'")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean isVectorAvailable() {
        return vectorAvailable;
    }

    @Override
    public YSQLSchema readSchema() throws SQLException {
        return YSQLSchema.fromConnection(getConnection(), getDatabaseName());
    }

    private List<String> getCollnames(SQLConnection con) throws SQLException {
        List<String> opClasses = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s
                    .executeQuery("SELECT collname FROM pg_collation WHERE collname LIKE '%utf8' or collname = 'C';")) {
                while (rs.next()) {
                    opClasses.add(rs.getString(1));
                }
            }
        }
        return opClasses;
    }

    private List<String> getOpclasses(SQLConnection con) throws SQLException {
        List<String> opClasses = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("select opcname FROM pg_opclass;")) {
                while (rs.next()) {
                    opClasses.add(rs.getString(1));
                }
            }
        }
        return opClasses;
    }

    private List<String> getOperators(SQLConnection con) throws SQLException {
        List<String> opClasses = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT oprname FROM pg_operator;")) {
                while (rs.next()) {
                    opClasses.add(rs.getString(1));
                }
            }
        }
        return opClasses;
    }

    public List<String> getOperators() {
        return operators;
    }

    public String getRandomOperator() {
        return Randomly.fromList(operators);
    }

    public List<String> getCollates() {
        return collates;
    }

    public String getRandomCollate() {
        return Randomly.fromList(collates);
    }

    public List<String> getOpClasses() {
        return opClasses;
    }

    public String getRandomOpclass() {
        return Randomly.fromList(opClasses);
    }

    public void addFunctionAndType(String functionName, Character functionType) {
        this.functionsAndTypes.put(functionName, functionType);
    }

    public Map<String, Character> getFunctionsAndTypes() {
        return this.functionsAndTypes;
    }

    public void setDefaultAllowedFunctionTypes() {
        this.allowedFunctionTypes = Arrays.asList(IMMUTABLE, STABLE, VOLATILE);
    }

    public List<Character> getAllowedFunctionTypes() {
        return this.allowedFunctionTypes;
    }

    public void setAllowedFunctionTypes(List<Character> types) {
        this.allowedFunctionTypes = types;
    }

    public boolean isPgCompatible() {
        return getDbmsSpecificOptions().pgCompatibility;
    }

}
