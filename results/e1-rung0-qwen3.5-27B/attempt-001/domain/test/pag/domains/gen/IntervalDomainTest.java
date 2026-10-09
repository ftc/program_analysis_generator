package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntervalDomainTest {
    
    private final IntervalDomain domain = new IntervalDomain();
    
    @Test
    void testTopAndBottom() {
        var top = domain.top();
        var bottom = domain.bottom();
        
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
    }
    
    @Test
    void testJoin() {
        // Join of two states should contain all variables
        var state1 = domain.top();
        var state2 = domain.top();
        var joined = domain.join(state1, state2);
        
        assertFalse(domain.isBottom(joined));
    }
    
    @Test
    void testEntails() {
        var top = domain.top();
        var bottom = domain.bottom();
        
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(top, top));
        assertFalse(domain.entails(top, bottom));
    }
    
    @Test
    void testTransfer_Assign() {
        // Test simple assignment: x = 5
        var step = new pag.api.Step.Assign(
            new pag.api.LVal.Local("x", "int"),
            new pag.api.RVal.IntConst(java.math.BigInteger.valueOf(5))
        );
        
        var post = domain.top();
        var pre = domain.transfer(step, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testTransfer_Assume() {
        // Test assumption: x > 0
        var step = new pag.api.Step.Assume(
            new pag.api.RVal.Binop(
                new pag.api.LVal.Local("x", "int"),
                pag.api.BinOp.Gt,
                new pag.api.RVal.IntConst(java.math.BigInteger.ZERO)
            )
        );
        
        var post = domain.top();
        var pre = domain.transfer(step, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testTransfer_Call() {
        // Test call: x = randInt()
        var step = new pag.api.Step.Call(
            java.util.Optional.of(new pag.api.LVal.Local("x", "int")),
            new pag.api.MethodId("pag.probe.Rand", "randInt", java.util.List.of(), "int"),
            java.util.List.of()
        );
        
        var post = domain.top();
        var pre = domain.transfer(step, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testWiden() {
        var state1 = domain.top();
        var state2 = domain.top();
        var widened = domain.widen(state1, state2);
        
        assertFalse(domain.isBottom(widened));
    }
}
