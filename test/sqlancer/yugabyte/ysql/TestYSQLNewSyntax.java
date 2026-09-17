package sqlancer.yugabyte.ysql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLColumn;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLDataType;
import sqlancer.yugabyte.ysql.YSQLSchema.YSQLTable;
import sqlancer.yugabyte.ysql.gen.YSQLTriggerGenerator;
import sqlancer.yugabyte.ysql.gen.YSQLTypeGenerator;

public class TestYSQLNewSyntax {

    @Test
    public void triggersGenerateTransitionTables() {
        YSQLGlobalState state = state();
        List<String> transition = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String query = YSQLTriggerGenerator.generate(state).getQueryString();
            if (query.contains("REFERENCING")) {
                transition.add(query);
            }
        }
        assertFalse(transition.isEmpty(), "transition table triggers must be reachable");
        for (String query : transition) {
            assertTrue(query.contains("AFTER "), query);
            assertTrue(query.contains("FOR EACH STATEMENT"), query);
            // A single event only: transition tables are rejected for multi-event triggers.
            String trigger = query.substring(query.indexOf(" AFTER ", query.indexOf("REPLACE TRIGGER")));
            assertFalse(trigger.contains(" OR "), trigger);
            boolean hasNew = query.contains("NEW TABLE AS " + YSQLTriggerGenerator.NEW_TABLE_ALIAS);
            boolean hasOld = query.contains("OLD TABLE AS " + YSQLTriggerGenerator.OLD_TABLE_ALIAS);
            assertTrue(hasNew || hasOld, query);
            assertFalse(query.contains("AFTER INSERT") && hasOld, query);
            assertFalse(query.contains("AFTER DELETE") && hasNew, query);
            // The declaring function is created alongside the trigger so the aliases always resolve.
            String function = hasNew && hasOld ? YSQLTriggerGenerator.TRANSITION_FUNCTION_BOTH : hasNew
                    ? YSQLTriggerGenerator.TRANSITION_FUNCTION_NEW : YSQLTriggerGenerator.TRANSITION_FUNCTION_OLD;
            assertTrue(query.contains("CREATE OR REPLACE FUNCTION " + function + "()"), query);
            assertTrue(query.contains("EXECUTE FUNCTION " + function + "()"), query);
        }
    }

    @Test
    public void typesGenerateDropAttribute() {
        YSQLGlobalState state = state();
        boolean seen = false;
        for (int i = 0; i < 400; i++) {
            String query = YSQLTypeGenerator.generate(state).getQueryString();
            if (query.startsWith("ALTER TYPE")) {
                assertTrue(query.matches("ALTER TYPE tp\\d DROP ATTRIBUTE (IF EXISTS )?f\\d( (RESTRICT|CASCADE))?;"),
                        query);
                seen = true;
            }
        }
        assertTrue(seen, "ALTER TYPE ... DROP ATTRIBUTE must be reachable");
    }

    private static YSQLGlobalState state() {
        YSQLColumn c0 = new YSQLColumn("c0", YSQLDataType.INT);
        YSQLTable table = new YSQLTable("t0", List.of(c0), List.of(), YSQLTable.TableType.STANDARD, List.of(), false,
                true);
        c0.setTable(table);
        YSQLGlobalState state = new YSQLGlobalState() {
            @Override
            public YSQLSchema getSchema() {
                return new YSQLSchema(List.of(table), "syntax");
            }
        };
        state.setRandomly(new Randomly(0));
        state.setMainOptions(new MainOptions());
        state.setDbmsSpecificOptions(new YSQLOptions());
        return state;
    }
}
