package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pag.api.*;

/**
 * Tests for UnreachabilityDomain.
 * 
 * Tests soundness and correctness of the domain operations.
 */
class UnreachabilityDomainTest {
    
    private UnreachabilityDomain domain;
    
    @Test
    void testName() {
        assertEquals("UnreachabilityDomain", domain.name());
    }
    
    @Test
    void testTopBottom() {
        UnreachabilityDomain.State top = domain.top();
        UnreachabilityDomain.State bottom = domain.bottom();
        
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
        
        // Top should admit everything
        assertTrue(domain.entails(top, bottom));
        assertTrue(domain.entails(bottom, top));
    }
    
    @Test
    void testIsBottom() {
        UnreachabilityDomain.State top = domain.top();
        UnreachabilityDomain.State bottom = domain.bottom();
        
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
    }
    
    @Test
    void testEntails() {
        UnreachabilityDomain.State top = domain.top();
        UnreachabilityDomain.State bottom = domain.bottom();
        
        // Bottom entails everything
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(bottom, bottom));
        
        // Top is entailed by everything
        assertTrue(domain.entails(top, top));
        assertTrue(domain.entails(top, bottom));
    }
    
    @Test
    void testJoin() {
        UnreachabilityDomain.State s1 = domain.top();
        UnreachabilityDomain.State s2 = domain.bottom();
        
        UnreachabilityDomain.State joined = domain.join(s1, s2);
        
        // Join of top and bottom should be bottom
        assertTrue(domain.isBottom(joined));
    }
    
    @Test
    void testTransferAssign() {
        Step assign = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.IntConst(BigInteger.valueOf(42))
        );
        
        UnreachabilityDomain.State post = domain.top();
        UnreachabilityDomain.State pre = domain.transfer(assign, post);
        
        // After assignment, x is constrained to 42
        // Before assignment, x should be unconstrained
        UnreachabilityDomain.State.Constraint preX = pre.get("x");
        assertEquals(UnreachabilityDomain.State.Constraint.UNCONSTRAINED, preX);
    }
    
    @Test
    void testTransferCall() {
        Step call = new Step.Call(
            java.util.Optional.of(new Step.Local("rand", "int")),
            new Step.MethodId("pag.probe.Rand", "randInt", java.util.List.of(), "int"),
            java.util.List.of()
        );
        
        UnreachabilityDomain.State post = domain.top();
        UnreachabilityDomain.State pre = domain.transfer(call, post);
        
        // After random call, rand should be unconstrained
        UnreachabilityDomain.State.Constraint preRand = pre.get("rand");
        assertEquals(UnreachabilityDomain.State.Constraint.UNCONSTRAINED, preRand);
    }
    
    @Test
    void testTransferAssume() {
        Step assume = new Step.Assume(new Step.IntConst(BigInteger.ONE));
        
        UnreachabilityDomain.State post = domain.top();
        UnreachabilityDomain.State pre = domain.transfer(assume, post);
        
        // Assume doesn't change constraints, just restricts
        UnreachabilityDomain.State.Constraint preX = pre.get("x");
        assertEquals(UnreachabilityDomain.State.Constraint.UNCONSTRAINED, preX);
    }
    
    @Test
    void testSoundnessBottom() {
        // If post is bottom, pre must be bottom (soundness)
        UnreachabilityDomain.State bottom = domain.bottom();
        Step assign = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.IntConst(BigInteger.ZERO)
        );
        
        UnreachabilityDomain.State pre = domain.transfer(assign, bottom);
        
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    void testWidenConvergence() {
        UnreachabilityDomain.State s1 = domain.top();
        UnreachabilityDomain.State s2 = domain.bottom();
        
        UnreachabilityDomain.State w1 = domain.widen(s1, s2);
        UnreachabilityDomain.State w2 = domain.widen(s1, s2);
        
        // Widening should produce same result (idempotent)
        assertEquals(w1, w2);
    }
}
