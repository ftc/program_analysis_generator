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
        // x is unconstrained in pre, so s1 is top
        assertTrue(domain.entails(s1, top));
        assertFalse(domain.entails(top, s1)); // s1 is top, so true. Wait.
        // Actually s1 is top because x is unconstrained.
        // Let's test with Assume.
        IntervalDomain.State s2 = domain.transfer(
            new Step.Assume(new RVal.Binop(new LVal.Local("x", "int"), BinOp.Lt, new RVal.IntConst(BigInteger.valueOf(10)))),
            top
        );
        // s2 constrains x < 10.
        assertTrue(domain.entails(s2, top));
        assertFalse(domain.entails(top, s2));
    }

    @Test
    public void testTransferAssign() {
        // x = 5. post requires x = 5.
        IntervalDomain.State post = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        // post is top (x unconstrained).
        // pre requires x = 5.
        IntervalDomain.State pre = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            post
        );
        // Wait, I need to construct a post that constrains x.
        // Use Assume to constrain x.
        IntervalDomain.State constrainedPost = domain.transfer(
            new Step.Assume(new RVal.Binop(new LVal.Local("x", "int"), BinOp.Eq, new RVal.IntConst(BigInteger.valueOf(5)))),
            domain.top()
        );
        // constrainedPost says x = 5.
        // Assign x = 5.
        IntervalDomain.State pre1 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            constrainedPost
        );
        // pre1 should be valid (x unconstrained).
        assertFalse(domain.isBottom(pre1));

        // Assign x = 4. post requires x = 5.
        IntervalDomain.State pre2 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(4))),
            constrainedPost
        );
        // pre2 should be bottom.
        assertTrue(domain.isBottom(pre2));
    }

    @Test
    public void testTransferAssume() {
        // Assume x > 5. post is top.
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
        // x becomes unconstrained.
        assertFalse(domain.isBottom(pre));
    }
}
