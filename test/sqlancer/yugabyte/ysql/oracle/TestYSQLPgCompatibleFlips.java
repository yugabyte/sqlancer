package sqlancer.yugabyte.ysql.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.yugabyte.amp.YugabyteAmpProvider;

public class TestYSQLPgCompatibleFlips {

    private static final String[][] FLIPS = { { "enable_seqscan=off" }, { "yb_enable_cbo=off" },
            { "max_parallel_workers_per_gather=2", "yb_parallel_range_rows=1" } };
    private static final String[][] PG_ONLY = { { "enable_memoize=off" } };

    @Test
    public void yugabyteModeKeepsEveryFlip() {
        List<String[]> flips = YSQLScanGUCOracle.flipsFor(FLIPS, PG_ONLY, false);
        assertEquals(3, flips.size());
    }

    // A flip that sets any yb_ parameter would only fail with "unrecognized configuration parameter" on PostgreSQL.
    @Test
    public void pgModeDropsYugabyteFlipsAndAddsPgOnes() {
        List<String[]> flips = YSQLScanGUCOracle.flipsFor(FLIPS, PG_ONLY, true);
        assertEquals(2, flips.size());
        assertEquals("enable_seqscan=off", flips.get(0)[0]);
        assertEquals("enable_memoize=off", flips.get(1)[0]);
        assertTrue(flips.stream().flatMap(Arrays::stream).noneMatch(a -> a.startsWith("yb_")));
    }

    @Test
    public void ampProviderIsNamedAmp() {
        assertEquals("amp", new YugabyteAmpProvider().getDBMSName());
    }
}
