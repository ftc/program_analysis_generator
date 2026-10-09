package pag.domains.gen;

import org.junit.jupiter.api.Test;
import pag.api.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EqualityDomainTest {

    private final EqualityDomain domain = new EqualityDomain();
    
    @Test
    void testTopAndBottom() {
        assertEquals("EqualityDomain", domain.name());
        assertTrue(domain.isBottom(domain.bottom()));
        assertFalse(domain.isBottom(domain.top()));
    }
    
    @Test
    void testEntails() {
        EqualityDomain.State top = domain.top();
        EqualityDomain.State bottom = domain.bottom();
        
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(bottom, bottom));
        assertFalse(domain.entails(top, bottom));
        assertTrue(domain.entails(top, top));
    }
    
    @Test
    void testJoin() {
        EqualityDomain.State top = domain.top();
        EqualityDomain.State bottom = domain.bottom();
        
        assertEquals(domain.top(), domain.join(top, top));
        assertEquals(top, domain.join(top, bottom));
        assertEquals(bottom, domain.join(bottom, top));
        assertEquals(bottom, domain.join(bottom, bottom));
    }
    
    @Test
    void testAssignConstant() {
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst five = new RVal.IntConst(BigInteger.valueOf(5));
        Step.Assign assign = new Step.Assign(x, five);
        
        EqualityDomain.State post = domain.top();
        EqualityDomain.State pre = domain.transfer(assign, post);
        
        // After backward transfer, x should be constrained to 5
        assertEquals(BigInteger.valueOf(5), pre.getVariableValue("x"));
    }
    
    @Test
    void testAssignVariable() {
        LVal.Local x = new LVal.Local("x", "int");
        LVal.Local y = new LVal.Local("y", "int");
        Step.Assign assign = new Step.Assign(x, y);
        
        EqualityDomain.State post = domain.top();
        EqualityDomain.State pre = domain.transfer(assign, post);
        
        // x should be equal to y
        assertEquals("y", pre.varToVar.get("x"));
    }
    
    @Test
    void testAssumeEquality() {
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst five = new RVal.IntConst(BigInteger.valueOf(5));
        RVal.Binop eq = new RVal.Binop(x, BinOp.Eq, five);
        Step.Assume assume = new Step.Assume(eq);
        
        EqualityDomain.State post = domain.top();
        EqualityDomain.State pre = domain.transfer(assume, post);
        
        // x should be constrained to 5
        assertEquals(BigInteger.valueOf(5), pre.getVariableValue("x"));
    }
    
    @Test
    void testAssumeContradiction() {
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst five = new RVal.IntConst(BigInteger.valueOf(5));
        RVal.IntConst ten = new RVal.IntConst(BigInteger.valueOf(10));
        RVal.Binop eq = new RVal.Binop(five, BinOp.Eq, ten);
        Step.Assume assume = new Step.Assume(eq);
        
        EqualityDomain.State post = domain.top();
        EqualityDomain.State pre = domain.transfer(assume, post);
        
        // Contradiction leads to bottom
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    void testCallRandInt() {
        LVal.Local x = new LVal.Local("x", "int");
        MethodId randInt = new MethodId("pag.probe.Rand", "randInt", List.of(), "int");
        Step.Call call = new Step.Call(Optional.of(x), randInt, List.of());
        
        EqualityDomain.State post = new EqualityDomain.State(
            Map.of("x", BigInteger.valueOf(5)), 
            Map.of(), 
            false
        );
        EqualityDomain.State pre = domain.transfer(call, post);
        
        // After randInt, x should be unconstrained
        assertNull(pre.getVariableValue("x"));
    }
    
    @Test
    void testComplexBackward() {
        // Test a sequence of backward transfers
        LVal.Local x = new LVal.Local("x", "int");
        LVal.Local y = new LVal.Local("y", "int");
        
        // x = 5
        Step.Assign assign1 = new Step.Assign(x, new RVal.IntConst(BigInteger.valueOf(5)));
        
        // y = x
        Step.Assign assign2 = new Step.Assign(y, x);
        
        // Assume x == 5
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(x, BinOp.Eq, new RVal.IntConst(BigInteger.valueOf(5)))
        );
        
        // Start from top and go backward
        EqualityDomain.State state = domain.top();
        state = domain.transfer(assume, state);
        state = domain.transfer(assign2, state);
        state = domain.transfer(assign1, state);
        
        // After all transfers, x should be 5
        assertEquals(BigInteger.valueOf(5), state.getVariableValue("x"));
        // y should also be 5 (through the chain)
        assertEquals(BigInteger.valueOf(5), state.getVariableValue("y"));
    }
    
    @Test
    void testWiden() {
        EqualityDomain.State top = domain.top();
        EqualityDomain.State bottom = domain.bottom();
        
        assertEquals(domain.top(), domain.widen(top, top));
        assertEquals(top, domain.widen(top, bottom));
        assertEquals(bottom, domain.widen(bottom, top));
        assertEquals(bottom, domain.widen(bottom, bottom));
    }
}
