package pag.domains.gen;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import pag.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class IntervalDomainTest {

    @Test
    void testTopAndBottom() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State top = domain.top();
        IntervalDomain.State bottom = domain.bottom();
        
        assertTrue(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
    }

    @Test
    void testEntails() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State s1 = new IntervalDomain.State();
        s1.setConstraint("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        IntervalDomain.State s2 = new IntervalDomain.State();
        s2.setConstraint("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(20)));
        
        // s2 entails s1 (s2's constraint is looser)
        assertFalse(domain.entails(s1, s2)); // s1 does not entail s2
        assertTrue(domain.entails(s2, s1)); // s2 entails s1
    }

    @Test
    void testAssignTransfer() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State post = new IntervalDomain.State();
        post.setConstraint("x", new Interval(BigInteger.valueOf(5), BigInteger.valueOf(5)));
        
        IntervalDomain.State pre = domain.transfer(new Step.Assign(new Step.Local("y", "int"), 
            new Step.IntConst(BigInteger.valueOf(10))), post);
        
        assertEquals(BigInteger.valueOf(10), pre.getConstraint("y").getMin());
        assertEquals(BigInteger.valueOf(10), pre.getConstraint("y").getMax());
        assertNull(pre.getConstraint("x"));
    }

    @Test
    void testAssumeTransfer() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State post = new IntervalDomain.State();
        post.setConstraint("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        IntervalDomain.State pre = domain.transfer(new Step.Assume(new Step.IntConst(BigInteger.valueOf(5))), post);
        
        assertEquals(BigInteger.valueOf(5), pre.getConstraint("x").getMin());
        assertEquals(BigInteger.valueOf(5), pre.getConstraint("x").getMax());
    }

    @Test
    void testCallTransfer() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State post = new IntervalDomain.State();
        post.setConstraint("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        IntervalDomain.State pre = domain.transfer(new Step.Call(Optional.of(new Step.Local("y", "int")), 
            new Step.MethodId("java.lang.Math", "randInt", List.of(), "int"), List.of()), post);
        
        assertNull(pre.getConstraint("y"));
        assertEquals(BigInteger.valueOf(0), pre.getConstraint("x").getMin());
        assertEquals(BigInteger.valueOf(10), pre.getConstraint("x").getMax());
    }

    @Test
    void testJoin() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State s1 = new IntervalDomain.State();
        s1.setConstraint("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        IntervalDomain.State s2 = new IntervalDomain.State();
        s2.setConstraint("x", new Interval(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        
        IntervalDomain.State joined = domain.join(s1, s2);
        
        assertEquals(BigInteger.valueOf(0), joined.getConstraint("x").getMin());
        assertEquals(BigInteger.valueOf(15), joined.getConstraint("x").getMax());
    }

    @Test
    void testWiden() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State s1 = new IntervalDomain.State();
        s1.setConstraint("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        IntervalDomain.State s2 = new IntervalDomain.State();
        s2.setConstraint("x", new Interval(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        
        IntervalDomain.State widened = domain.widen(s1, s2);
        
        assertEquals(BigInteger.valueOf(0), widened.getConstraint("x").getMin());
        assertEquals(BigInteger.valueOf(15), widened.getConstraint("x").getMax());
    }

    @Test
    void testReachProof() {
        // Test that a domain can prove reach() unreachable
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State target = new IntervalDomain.State();
        target.setConstraint("id", new Interval(BigInteger.valueOf(42), BigInteger.valueOf(42)));
        
        // Work backward through an assume that makes id != 42
        IntervalDomain.State pre = domain.transfer(new Step.Assume(new Step.Binop(
            new Step.IntConst(BigInteger.valueOf(42)), Step.Ne, new Step.Local("id", "int"))), target);
        
        // After assume, id != 42, so the interval should be empty or at least not include 42
        IntervalDomain.State entry = domain.transfer(new Step.Assign(new Step.Local("id", "int"), 
            new Step.IntConst(BigInteger.valueOf(0))), pre);
        
        // If the constraint is empty, isBottom should be true
        assertTrue(domain.isBottom(entry));
    }
}
