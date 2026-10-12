package sqlancer.yugabyte.ysql.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

public class TestYSQLBackwardScanOracle {

    @Test
    public void expectedClampsToTheCommittedKeys() {
        assertEquals(Arrays.asList("1:1", "2:2"), YSQLBackwardScanOracle.expected(-5, 2, false, null));
        assertEquals(Arrays.asList("300:300", "299:299"), YSQLBackwardScanOracle.expected(299, 400, true, null));
        assertEquals(Collections.emptyList(), YSQLBackwardScanOracle.expected(301, 400, false, null));
    }

    @Test
    public void expectedTakesTheFirstRowsOfTheRequestedDirection() {
        assertEquals(Arrays.asList("10:10", "9:9", "8:8"), YSQLBackwardScanOracle.expected(1, 10, true, 3));
        assertEquals(Arrays.asList("1:1", "2:2"), YSQLBackwardScanOracle.expected(1, 10, false, 2));
    }
}
