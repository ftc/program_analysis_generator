package pag.domains.interval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.Step;

class IntervalDomainTest {

    private final IntervalDomain d = new IntervalDomain();
    private final Step assignX = new Step.Assign(
            new LVal.Local("x", "java.math.BigInteger"), new RVal.IntConst(BigInteger.ONE));

    @Test
    void bottomIsBottomAndTopIsNot() {
        assertTrue(d.isBottom(d.bottom()));
        assertFalse(d.isBottom(d.top()));
    }

    @Test
    void entailmentOrdersBottomBelowTop() {
        assertTrue(d.entails(d.bottom(), d.top()));
        assertFalse(d.entails(d.top(), d.bottom()));
    }

    @Test
    void transferKeepsBottomBottom() {
        assertEquals(d.bottom(), d.transfer(assignX, d.bottom()));
    }
}
