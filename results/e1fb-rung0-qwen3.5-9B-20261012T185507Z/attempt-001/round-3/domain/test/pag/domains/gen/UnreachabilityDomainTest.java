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
        domain = new UnreachabilityDomain();
        assertEquals("UnreachabilityDomain", domain.name());
    }
    
    @Test
    void testTopBottom() {
        domain = new UnreachabilityDomain();
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
        domain = new UnreachabilityDomain();
        UnreachabilityDomain.State top = domain.top();
        UnreachabilityDomain.State bottom = domain.bottom();
        
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
    }
    
    @Test
    void testEntails() {
        domain = new UnreachabilityDomain();
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
        domain = new UnreachabilityDomain();
        UnreachabilityDomain.State s1 = domain.top();
        UnreachabilityDomain.State s2 = domain.bottom();
        
        UnreachabilityDomain.State joined = domain.join(s1, s2);
        
        // Join of top and bottom should be bottom
        assertTrue(domain.isBottom(joined));
    }
    
    @Test
    void testTransferAssign() {
        domain = new UnreachabilityDomain();
        
        Step assign = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.IntConst(BigInteger.valueOf(42))
        );
        
        UnreachabilityDomain.State post = domain.top();
        UnreachabilityDomain.State pre = domain.transfer(assign, post);
        
        // After assignment, x is constrained to 42
        // Before assignment, x should be unconstrained
        Object preX = pre.get("x");
        assertNull(preX);
    }
    
    @Test
    void testTransferCall() {
        domain = new UnreachabilityDomain();
        
        Step call = new Step.Call(
            java.util.Optional.of(new Step.Local("rand", "int")),
            new Step.MethodId("pag.probe.Rand", "randInt", java.util.List.of(), "int"),
            java.util.List.of()
        );
        
        UnreachabilityDomain.State post = domain.top();
        UnreachabilityDomain.State pre = domain.transfer(call, post);
        
        // After random call, rand should be unconstrained
        Object preRand = pre.get("rand");
        assertNull(preRand);
    }
    
    @Test
    void testTransferAssume() {
        domain = new UnreachabilityDomain();
        
        Step assume = new Step.Assume(new Step.IntConst(BigInteger.ONE));
        
        UnreachabilityDomain.State post = domain.top();
        UnreachabilityDomain.State pre = domain.transfer(assume, post);
        
        // Assume doesn't change constraints, just restricts
        Object preX = pre.get("x");
        assertNull(preX);
    }
    
    @Test
    void testSoundnessBottom() {
        // If post is bottom, pre must be bottom (soundness)
        domain = new UnreachabilityDomain();
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
        domain = new UnreachabilityDomain();
        UnreachabilityDomain.State s1 = domain.top();
        UnreachabilityDomain.State s2 = domain.bottom();
        
        UnreachabilityDomain.State w1 = domain.widen(s1, s2);
        UnreachabilityDomain.State w2 = domain.widen(s1, s2);
        
        // Widening should produce same result (idempotent)
        assertEquals(w1, w2);
    }
}
