package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import pag.api.*;

public class IntervalDomainTest {

    private final IntervalDomain domain = new IntervalDomain();

    @Test
    public void testTopBottom() {
        assertFalse(domain.isBottom(domain.top()));
        assertTrue(domain.isBottom(domain.bottom()));
    }

    @Test
    public void testEntails() {
        IntervalDomain.State top = domain.top();
        IntervalDomain.State bottom = domain.bottom();
        IntervalDomain.State s1 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            top
        );
        assertTrue(domain.entails(s1, top));
        IntervalDomain.State s2 = domain.transfer(
            new Step.Assume(new RVal.Binop(new LVal.Local("x", "int"), BinOp.Lt, new RVal.IntConst(BigInteger.valueOf(10)))),
            top
        );
        assertTrue(domain.entails(s2, top));
        assertFalse(domain.entails(top, s2));
    }

    @Test
    public void testTransferAssign() {
        IntervalDomain.State constrainedPost = domain.transfer(
            new Step.Assume(new RVal.Binop(new LVal.Local("x", "int"), BinOp.Eq, new RVal.IntConst(BigInteger.valueOf(5)))),
            domain.top()
        );
        IntervalDomain.State pre1 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            constrainedPost
        );
        assertFalse(domain.isBottom(pre1));

        IntervalDomain.State pre2 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(4))),
            constrainedPost
        );
        assertTrue(domain.isBottom(pre2));
    }

    @Test
    public void testTransferAssume() {
        IntervalDomain.State pre = domain.transfer(
            new Step.Assume(new RVal.Binop(new LVal.Local("x", "int"), BinOp.Gt, new RVal.IntConst(BigInteger.valueOf(5)))),
            domain.top()
        );
        assertFalse(domain.isBottom(pre));
    }

    @Test
    public void testTransferCall() {
        IntervalDomain.State post = domain.top();
        IntervalDomain.State pre = domain.transfer(
            new Step.Call(Optional.of(new LVal.Local("x", "int")),
                new MethodId("pag.probe.Rand", "randInt", List.of(), "int"), List.of()),
            post
        );
        assertFalse(domain.isBottom(pre));
    }
}
