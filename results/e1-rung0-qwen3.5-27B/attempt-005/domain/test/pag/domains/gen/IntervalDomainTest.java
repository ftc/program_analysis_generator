package pag.domains.gen;

import org.junit.jupiter.api.Test;
import pag.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.*;

class IntervalDomainTest {
    
    @Test
    void testTopIsNotBottom() {
        IntervalDomain domain = new IntervalDomain();
        IntervalState top = domain.top();
        assertFalse(domain.isBottom(top));
    }
    
    @Test
    void testBottomIsBottom() {
        IntervalDomain domain = new IntervalDomain();
        IntervalState bottom = domain.bottom();
        assertTrue(domain.isBottom(bottom));
    }
    
    @Test
    void testEntailsBottomFromBottom() {
        IntervalDomain domain = new IntervalDomain();
        assertTrue(domain.entails(domain.bottom(), domain.bottom()));
        assertTrue(domain.entails(domain.bottom(), domain.top()));
    }
    
    @Test
    void testEntailsTopFromBottom() {
        IntervalDomain domain = new IntervalDomain();
        assertFalse(domain.entails(domain.top(), domain.bottom()));
    }
    
    @Test
    void testJoinWithBottom() {
        IntervalDomain domain = new IntervalDomain();
        IntervalState top = domain.top();
        IntervalState bottom = domain.bottom();
        
        assertEquals(top, domain.join(top, bottom));
        assertEquals(top, domain.join(bottom, top));
    }
    
    @Test
    void testTransferAssign() {
        IntervalDomain domain = new IntervalDomain();
        
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst five = new RVal.IntConst(BigInteger.valueOf(5));
        Step.Assign assign = new Step.Assign(x, five);
        
        IntervalState post = domain.top();
        IntervalState pre = domain.transfer(assign, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testTransferAssume() {
        IntervalDomain domain = new IntervalDomain();
        
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst zero = new RVal.IntConst(BigInteger.ZERO);
        RVal.Binop eq = new RVal.Binop(x, BinOp.Eq, zero);
        Step.Assume assume = new Step.Assume(eq);
        
        IntervalState post = domain.top();
        IntervalState pre = domain.transfer(assume, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testTransferCall() {
        IntervalDomain domain = new IntervalDomain();
        
        LVal.Local x = new LVal.Local("x", "int");
        MethodId randInt = new MethodId("pag.probe.Rand", "randInt", List.of(), "int");
        Step.Call call = new Step.Call(Optional.of(x), randInt, List.of());
        
        IntervalState post = domain.top();
        IntervalState pre = domain.transfer(call, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testContradictionInAssign() {
        IntervalDomain domain = new IntervalDomain();
        
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst five = new RVal.IntConst(BigInteger.valueOf(5));
        Step.Assign assign = new Step.Assign(x, five);
        
        IntervalState post = domain.bottom();
        IntervalState pre = domain.transfer(assign, post);
        
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    void testEqualityConstraint() {
        IntervalDomain domain = new IntervalDomain();
        
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst five = new RVal.IntConst(BigInteger.valueOf(5));
        RVal.Binop eq = new RVal.Binop(x, BinOp.Eq, five);
        Step.Assume assume = new Step.Assume(eq);
        
        IntervalState post = domain.top();
        IntervalState pre = domain.transfer(assume, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testComparisonConstraint() {
        IntervalDomain domain = new IntervalDomain();
        
        LVal.Local x = new LVal.Local("x", "int");
        RVal.IntConst ten = new RVal.IntConst(BigInteger.valueOf(10));
        RVal.Binop lt = new RVal.Binop(x, BinOp.Lt, ten);
        Step.Assume assume = new Step.Assume(lt);
        
        IntervalState post = domain.top();
        IntervalState pre = domain.transfer(assume, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    void testJoinPreservesConstraints() {
        IntervalDomain domain = new IntervalDomain();
        
        IntervalState s1 = domain.top();
        IntervalState s2 = domain.top();
        
        IntervalState joined = domain.join(s1, s2);
        assertFalse(domain.isBottom(joined));
    }
    
    @Test
    void testWidenSameAsJoin() {
        IntervalDomain domain = new IntervalDomain();
        
        IntervalState s1 = domain.top();
        IntervalState s2 = domain.top();
        
        IntervalState joined = domain.join(s1, s2);
        IntervalState widened = domain.widen(s1, s2);
        
        assertEquals(joined, widened);
    }
}
