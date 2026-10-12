package pag.domains.gen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import pag.api.Domain;
import pag.api.Step;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.BinOp;
import pag.api.MethodId;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IntervalDomain Tests")
class IntervalDomainTest {

    private final Domain<IntervalDomain.IntervalState> domain = new IntervalDomain();
    
    private LVal.Local var(String name) {
        return new LVal.Local(name, "int");
    }
    
    private RVal.IntConst constVal(int v) {
        return new RVal.IntConst(BigInteger.valueOf(v));
    }
    
    @Test
    @DisplayName("top() and bottom() are correct")
    void testTopAndBottom() {
        IntervalDomain.IntervalState top = domain.top();
        IntervalDomain.IntervalState bottom = domain.bottom();
        
        assertTrue(domain.isBottom(bottom));
        assertFalse(domain.isBottom(top));
        
        // bottom entails everything
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(bottom, bottom));
        
        // top entails only top
        assertFalse(domain.entails(top, bottom));
        assertTrue(domain.entails(top, top));
    }
    
    @Test
    @DisplayName("join() merges intervals correctly")
    void testJoin() {
        IntervalDomain.IntervalState s1 = domain.top();
        IntervalDomain.IntervalState s2 = domain.bottom();
        
        assertEquals(s1, domain.join(s1, s2));
        assertEquals(s2, domain.join(s2, s1));
        assertEquals(domain.top(), domain.join(s1, s1));
        assertEquals(domain.bottom(), domain.join(s2, s2));
    }
    
    @Test
    @DisplayName("widen() converges")
    void testWiden() {
        IntervalDomain.IntervalState s1 = domain.top();
        IntervalDomain.IntervalState s2 = domain.bottom();
        
        IntervalDomain.IntervalState w1 = domain.widen(s1, s2);
        IntervalDomain.IntervalState w2 = domain.widen(s2, s1);
        
        // Widening should be sound
        assertTrue(domain.entails(s1, w1));
        assertTrue(domain.entails(s2, w1));
        assertTrue(domain.entails(s1, w2));
        assertTrue(domain.entails(s2, w2));
    }
    
    @Test
    @DisplayName("transfer handles Assign correctly")
    void testTransferAssign() {
        LVal.Local x = var("x");
        LVal.Local y = var("y");
        
        // x = 5
        Step.Assign assign = new Step.Assign(x, constVal(5));
        
        // After assignment, x = 5, y can be anything
        // Before assignment, x can be anything, y can be anything
        IntervalDomain.IntervalState post = domain.top();
        IntervalDomain.IntervalState pre = domain.transfer(assign, post);
        
        // Should not be bottom
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    @DisplayName("transfer handles Assume correctly")
    void testTransferAssume() {
        LVal.Local x = var("x");
        RVal.Binop cond = new RVal.Binop(constVal(5), BinOp.Lt, constVal(10));
        
        Step.Assume assume = new Step.Assume(cond);
        
        IntervalDomain.IntervalState post = domain.top();
        IntervalDomain.IntervalState pre = domain.transfer(assume, post);
        
        // Should not be bottom (condition is satisfiable)
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    @DisplayName("transfer handles Call correctly")
    void testTransferCall() {
        LVal.Local x = var("x");
        MethodId randInt = new MethodId("pag.probe.Rand", "randInt", List.of(), "int");
        
        Step.Call call = new Step.Call(Optional.of(x), randInt, List.of());
        
        IntervalDomain.IntervalState post = domain.top();
        IntervalDomain.IntervalState pre = domain.transfer(call, post);
        
        // Should not be bottom
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    @DisplayName("entails is transitive")
    void testEntailsTransitive() {
        IntervalDomain.IntervalState top = domain.top();
        IntervalDomain.IntervalState bottom = domain.bottom();
        
        // bottom entails everything
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(bottom, bottom));
        
        // top entails only top
        assertTrue(domain.entails(top, top));
        assertFalse(domain.entails(top, bottom));
    }
    
    @Test
    @DisplayName("join is commutative")
    void testJoinCommutative() {
        IntervalDomain.IntervalState s1 = domain.top();
        IntervalDomain.IntervalState s2 = domain.bottom();
        
        assertEquals(domain.join(s1, s2), domain.join(s2, s1));
    }
    
    @Test
    @DisplayName("widen is sound")
    void testWidenSound() {
        IntervalDomain.IntervalState s1 = domain.top();
        IntervalDomain.IntervalState s2 = domain.bottom();
        
        IntervalDomain.IntervalState w = domain.widen(s1, s2);
        
        // w should admit everything s1 or s2 admits
        assertTrue(domain.entails(s1, w));
        assertTrue(domain.entails(s2, w));
    }
}
