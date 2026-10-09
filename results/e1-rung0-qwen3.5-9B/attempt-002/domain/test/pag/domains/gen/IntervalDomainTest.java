package pag.domains.gen;

import pag.api.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IntervalDomain.
 * 
 * Key soundness tests:
 * 1. top() should never be bottom()
 * 2. isBottom() must be conservative (only return true for truly empty states)
 * 3. entailment must be sound
 * 4. transfer must be sound (backward analysis)
 */
class IntervalDomainTest {

    private final IntervalDomain domain = new IntervalDomain();

    @Test
    void name() {
        assertEquals("IntervalDomain", domain.name());
    }

    @Test
    void topIsNotBottom() {
        IntervalState top = domain.top();
        IntervalState bottom = domain.bottom();
        
        // top should not be bottom
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
        
        // They should be different
        assertFalse(top.equals(bottom));
    }

    @Test
    void topEntailsTop() {
        IntervalState top = domain.top();
        assertTrue(domain.entails(top, top));
    }

    @Test
    void bottomEntailsBottom() {
        IntervalState bottom = domain.bottom();
        assertTrue(domain.entails(bottom, bottom));
    }

    @Test
    void topEntailsBottom() {
        // top should entail bottom (conservative)
        IntervalState top = domain.top();
        IntervalState bottom = domain.bottom();
        assertTrue(domain.entails(top, bottom));
    }

    @Test
    void joinIsTop() {
        IntervalState top = domain.top();
        IntervalState bottom = domain.bottom();
        IntervalState joined = domain.join(top, bottom);
        
        // join(top, bottom) should be top (conservative)
        assertEquals(top, joined);
    }

    @Test
    void widenConverges() {
        // Create two states with different constraints
        IntervalState a = new IntervalState();
        a.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(0), BigInteger.valueOf(100)
        ));
        
        IntervalState b = new IntervalState();
        b.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(50), BigInteger.valueOf(200)
        ));
        
        IntervalState widened = domain.widen(a, b);
        
        // Widening should make it unconstrained (convergence)
        assertNull(widened.getConstraint(new LVal.Local("x", "int")));
    }

    @Test
    void transferAssignUnconstrains() {
        // After: x = 5
        // Before: x is unconstrained (assignment doesn't constrain pre-state)
        IntervalState post = new IntervalState();
        post.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(5), BigInteger.valueOf(5)
        ));
        
        Step assign = new Assign(new LVal.Local("x", "int"), 
            new RVal.IntConst(BigInteger.valueOf(5)));
        
        IntervalState pre = domain.transfer(assign, post);
        
        // x should be unconstrained before assignment
        assertNull(pre.getConstraint(new LVal.Local("x", "int")));
    }

    @Test
    void transferCallUnconstrains() {
        // After: x = Rand.randInt()
        // Before: x is unconstrained (random value)
        IntervalState post = new IntervalState();
        post.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(0), BigInteger.valueOf(100)
        ));
        
        Step call = new Call(
            Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );
        
        IntervalState pre = domain.transfer(call, post);
        
        // x should be unconstrained before call
        assertNull(pre.getConstraint(new LVal.Local("x", "int")));
    }

    @Test
    void transferNonTargetVarsPreserved() {
        // After: y = 10
        // Before: y = 10, x unconstrained
        IntervalState post = new IntervalState();
        post.setConstraint(new LVal.Local("y", "int"), new Interval(
            BigInteger.valueOf(10), BigInteger.valueOf(10)
        ));
        
        Step assign = new Assign(new LVal.Local("x", "int"), 
            new RVal.IntConst(BigInteger.valueOf(5)));
        
        IntervalState pre = domain.transfer(assign, post);
        
        // y should still be constrained
        assertSame(post.getConstraint(new LVal.Local("y", "int")),
                   pre.getConstraint(new LVal.Local("y", "int")));
    }

    @Test
    void isBottomSound() {
        // isBottom must be conservative: only return true for truly empty states
        IntervalState top = domain.top();
        assertFalse(domain.isBottom(top));
        
        IntervalState bottom = domain.bottom();
        assertTrue(domain.isBottom(bottom));
    }

    @Test
    void entailmentSound() {
        // If a entails b, and b is empty, a must be empty
        IntervalState empty = new IntervalState();
        empty.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(0), BigInteger.valueOf(-1)
        ));
        
        IntervalState unconstrained = new IntervalState();
        
        // unconstrained does NOT entail empty
        assertFalse(domain.entails(unconstrained, empty));
        
        // empty entails empty
        assertTrue(domain.entails(empty, empty));
    }

    @Test
    void joinPreservesConstraints() {
        IntervalState a = new IntervalState();
        a.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(0), BigInteger.valueOf(10)
        ));
        
        IntervalState b = new IntervalState();
        b.setConstraint(new LVal.Local("x", "int"), new Interval(
            BigInteger.valueOf(5), BigInteger.valueOf(15)
        ));
        
        IntervalState joined = domain.join(a, b);
        
        // Join should preserve and widen constraints
        assertNotNull(joined.getConstraint(new LVal.Local("x", "int")));
    }
}
