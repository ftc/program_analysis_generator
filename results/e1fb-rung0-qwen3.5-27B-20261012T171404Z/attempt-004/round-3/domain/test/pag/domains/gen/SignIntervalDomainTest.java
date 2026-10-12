package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import pag.api.*;

public class SignIntervalDomainTest {

    private final SignIntervalDomain domain = new SignIntervalDomain();

    @Test
    void testBottom() {
        assertTrue(domain.isBottom(domain.bottom()));
        assertFalse(domain.isBottom(domain.top()));
    }

    @Test
    void testEntails() {
        SignIntervalDomain.State top = domain.top();
        SignIntervalDomain.State bottom = domain.bottom();
        assertTrue(domain.entails(bottom, bottom));
        assertTrue(domain.entails(bottom, top));
        assertFalse(domain.entails(top, bottom));
        assertTrue(domain.entails(top, top));
    }

    @Test
    void testAssign() {
        Step step = new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5)));
        SignIntervalDomain.State post = domain.top();
        SignIntervalDomain.State pre = domain.transfer(step, post);
        SignIntervalDomain.AbstractValue val = pre.get("x");
        assertNotNull(val);
        assertEquals(BigInteger.valueOf(5), val.min);
        assertEquals(BigInteger.valueOf(5), val.max);
    }

    @Test
    void testAssume() {
        Step step = new Step.Assume(new RVal.Binop(
            new LVal.Local("x", "int"),
            BinOp.Lt,
            new RVal.IntConst(BigInteger.ZERO)
        ));
        SignIntervalDomain.State post = domain.top();
        SignIntervalDomain.State pre = domain.transfer(step, post);
        SignIntervalDomain.AbstractValue val = pre.get("x");
        assertNotNull(val);
        assertTrue(val.max != null && val.max.compareTo(BigInteger.ZERO) < 0);
    }

    @Test
    void testUnreachable() {
        Step assign = new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5)));
        Step assume = new Step.Assume(new RVal.Binop(
            new LVal.Local("x", "int"),
            BinOp.Lt,
            new RVal.IntConst(BigInteger.ZERO)
        ));

        SignIntervalDomain.State stateAfterReach = domain.top();
        SignIntervalDomain.State stateAfterAssume = domain.transfer(assume, stateAfterReach);
        SignIntervalDomain.State stateBeforeAssign = domain.transfer(assign, stateAfterAssume);

        assertTrue(domain.isBottom(stateBeforeAssign));
    }
}
