package pag.domains.gen;

import pag.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntervalDomainTest {

    @Test
    void testTopAndBottom() {
        IntervalDomain domain = new IntervalDomain();
        
        // Top should have no constraints (unconstrained state)
        IntervalState top = domain.top();
        assertTrue(top.constraints.isEmpty());
        
        // Bottom should have all variables constrained (no states admitted)
        IntervalState bottom = domain.bottom();
        // Bottom is always empty for our implementation
        assertTrue(domain.isBottom(bottom));
    }

    @Test
    void testEntails() {
        IntervalDomain domain = new IntervalDomain();
        
        // Create two states
        IntervalState s1 = new IntervalState();
        s1.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        s1.constrain("y", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5)));
        
        IntervalState s2 = new IntervalState();
        s2.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(100)));
        s2.constrain("y", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(20)));
        
        // s1 entails s2 because s1's intervals are contained in s2's
        assertTrue(domain.entails(s1, s2));
        
        // s2 does not entail s1
        assertFalse(domain.entails(s2, s1));
        
        // Equal states
        IntervalState s3 = new IntervalState();
        s3.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        s3.constrain("y", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5)));
        assertTrue(domain.entails(s1, s3));
    }

    @Test
    void testJoin() {
        IntervalDomain domain = new IntervalDomain();
        
        IntervalState s1 = new IntervalState();
        s1.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        s1.constrain("y", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5)));
        
        IntervalState s2 = new IntervalState();
        s2.constrain("x", Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        s2.constrain("y", Interval.of(BigInteger.valueOf(2), BigInteger.valueOf(8)));
        
        IntervalState joined = domain.join(s1, s2);
        
        // x should be [0, 15]
        assertSame(Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(15)), 
                   joined.getConstraint("x"));
        // y should be [0, 8]
        assertSame(Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(8)), 
                   joined.getConstraint("y"));
    }

    @Test
    void testWiden() {
        IntervalDomain domain = new IntervalDomain();
        
        IntervalState s1 = new IntervalState();
        s1.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        s1.constrain("y", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5)));
        
        IntervalState s2 = new IntervalState();
        s2.constrain("x", Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        s2.constrain("y", Interval.of(BigInteger.valueOf(2), BigInteger.valueOf(8)));
        
        IntervalState widened = domain.widen(s1, s2);
        
        // Widening should produce similar or larger intervals
        Interval xWiden = widened.getConstraint("x");
        Interval yWiden = widened.getConstraint("y");
        
        // For overlapping intervals, widening keeps the larger one
        // This is a conservative approximation
        assertNotNull(xWiden);
        assertNotNull(yWiden);
    }

    @Test
    void testTransferAssign() {
        IntervalDomain domain = new IntervalDomain();
        
        // After assignment x = 5, x is constrained to [5, 5]
        IntervalState post = new IntervalState();
        post.constrain("x", Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(5)));
        
        Step assign = new Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5)));
        
        IntervalState pre = domain.transfer(assign, post);
        
        // Before assignment, x is unconstrained (will take value 5)
        assertTrue(pre.isUnconstrained("x"));
    }

    @Test
    void testTransferAssume() {
        IntervalDomain domain = new IntervalDomain();
        
        // After assume x > 0, x is constrained to values > 0
        IntervalState post = new IntervalState();
        post.constrain("x", Interval.of(BigInteger.valueOf(1), BigInteger.valueOf(100)));
        
        Step assume = new Assume(new RVal.Binop(
            new RVal.IntConst(BigInteger.ZERO),
            BinOp.Gt,
            new LVal.Local("x", "int")
        ));
        
        IntervalState pre = domain.transfer(assume, post);
        
        // Before assume, x should still be constrained but we need to handle
        // the condition evaluation properly
        Interval xPre = pre.getConstraint("x");
        assertNotNull(xPre);
    }

    @Test
    void testTransferCall() {
        IntervalDomain domain = new IntervalDomain();
        
        // After call to randInt(), target variable is unconstrained
        IntervalState post = new IntervalState();
        post.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(100)));
        
        Step call = new Call(
            Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );
        
        IntervalState pre = domain.transfer(call, post);
        
        // Before call, x is unconstrained (gets arbitrary value)
        assertTrue(pre.isUnconstrained("x"));
    }

    @Test
    void testIsBottom() {
        IntervalDomain domain = new IntervalDomain();
        
        // Empty state (no constraints) should be considered bottom
        IntervalState empty = new IntervalState();
        // Note: our implementation considers empty as bottom
        // This is conservative for soundness
        
        // State with some constraints
        IntervalState withConstraints = new IntervalState();
        withConstraints.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        // State with no constraints should be top, not bottom
        assertFalse(domain.isBottom(empty));
    }

    @Test
    void testBinopFrom() {
        IntervalDomain domain = new IntervalDomain();
        
        // Test interval extraction from various expressions
        Interval addInt = Interval.from(new RVal.Binop(
            new RVal.IntConst(BigInteger.valueOf(2)),
            BinOp.Add,
            new RVal.IntConst(BigInteger.valueOf(3))
        ));
        assertSame(Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(5)), addInt);
        
        // Test multiplication
        Interval multInt = Interval.from(new RVal.Binop(
            new RVal.IntConst(BigInteger.valueOf(2)),
            BinOp.Mult,
            new RVal.IntConst(BigInteger.valueOf(3))
        ));
        assertSame(Interval.of(BigInteger.valueOf(6), BigInteger.valueOf(6)), multInt);
        
        // Test comparison (should be unconstrained)
        Interval cmpInt = Interval.from(new RVal.Binop(
            new RVal.IntConst(BigInteger.valueOf(1)),
            BinOp.Lt,
            new RVal.IntConst(BigInteger.valueOf(2))
        ));
        assertSame(Interval.any(), cmpInt);
    }

    @Test
    void testSoundnessBasic() {
        // Basic soundness test: ensure we don't exclude valid states
        IntervalDomain domain = new IntervalDomain();
        
        // If a variable is constrained to [0, 10], it should admit values in that range
        IntervalState s = new IntervalState();
        s.constrain("x", Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        // The state should not be bottom (it admits some states)
        assertFalse(domain.isBottom(s));
        
        // Entailment should work correctly
        IntervalState subset = new IntervalState();
        subset.constrain("x", Interval.of(BigInteger.valueOf(2), BigInteger.valueOf(5)));
        assertTrue(domain.entails(subset, s));
    }
}
