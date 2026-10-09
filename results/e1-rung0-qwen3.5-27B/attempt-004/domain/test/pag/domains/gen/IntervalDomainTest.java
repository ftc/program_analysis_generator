package pag.domains.gen;

import org.junit.jupiter.api.Test;
import pag.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;

public class IntervalDomainTest {
    
    private final IntervalDomain domain = new IntervalDomain();
    
    @Test
    void topIsNotBottom() {
        assertFalse(domain.isBottom(domain.top()));
    }
    
    @Test
    void bottomIsBottom() {
        assertTrue(domain.isBottom(domain.bottom()));
    }
    
    @Test
    void entailsBottomFromAnything() {
        assertTrue(domain.entails(domain.bottom(), domain.top()));
    }
    
    @Test
    void entailsTopOnlyFromTop() {
        assertTrue(domain.entails(domain.top(), domain.top()));
        assertFalse(domain.entails(domain.bottom(), domain.top()));
    }
    
    @Test
    void joinWithBottomReturnsOther() {
        IntervalDomain.State s = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(1), BigInteger.valueOf(10))), false);
        assertEquals(s, domain.join(domain.bottom(), s));
        assertEquals(s, domain.join(s, domain.bottom()));
    }
    
    @Test
    void joinUnionsIntervals() {
        IntervalDomain.State s1 = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(1), BigInteger.valueOf(5))), false);
        IntervalDomain.State s2 = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(3), BigInteger.valueOf(10))), false);
        
        IntervalDomain.State joined = domain.join(s1, s2);
        assertFalse(domain.isBottom(joined));
        
        // Should contain [1, 10]
        assertTrue(domain.entails(joined, 
            new IntervalDomain.State(java.util.Map.of("x", 
                new IntervalDomain.Interval(BigInteger.valueOf(1), BigInteger.valueOf(10))), false)));
    }
    
    @Test
    void backwardTransferAssignment() {
        // After: x in [5, 10]
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(5), BigInteger.valueOf(10))), false);
        
        // Step: x = y + 1
        Step step = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.Binop(new LVal.Local("y", "int"), BinOp.Add, new RVal.IntConst(BigInteger.ONE))
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
        
        // Before: y in [4, 9], x unconstrained
        IntervalDomain.State expected = new IntervalDomain.State(java.util.Map.of("y", 
            new IntervalDomain.Interval(BigInteger.valueOf(4), BigInteger.valueOf(9))), false);
        assertTrue(domain.entails(pre, expected));
    }
    
    @Test
    void backwardTransferAssume() {
        // After: x in [0, 10]
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(0), BigInteger.valueOf(10))), false);
        
        // Step: assume(x > 5)
        Step step = new Step.Assume(
            new RVal.Binop(new LVal.Local("x", "int"), BinOp.Gt, new RVal.IntConst(BigInteger.valueOf(5)))
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
        
        // Before: x in [6, 10]
        IntervalDomain.State expected = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(6), BigInteger.valueOf(10))), false);
        assertTrue(domain.entails(pre, expected));
    }
    
    @Test
    void backwardTransferAssumeContradiction() {
        // After: x in [0, 5]
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(0), BigInteger.valueOf(5))), false);
        
        // Step: assume(x > 10)
        Step step = new Step.Assume(
            new RVal.Binop(new LVal.Local("x", "int"), BinOp.Gt, new RVal.IntConst(BigInteger.valueOf(10)))
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    void backwardTransferRandInt() {
        // After: x in [5, 10]
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(5), BigInteger.valueOf(10))), false);
        
        // Step: x = randInt()
        Step step = new Step.Call(
            java.util.Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", java.util.List.of(), "int"),
            java.util.List.of()
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
        
        // x is unconstrained before randInt
        IntervalDomain.State expected = new IntervalDomain.State(java.util.Map.of(), false);
        assertTrue(domain.entails(pre, expected));
    }
    
    @Test
    void backwardTransferEquality() {
        // After: x in [5, 5] (exactly 5)
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(5), BigInteger.valueOf(5))), false);
        
        // Step: x = y + 2
        Step step = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.Binop(new LVal.Local("y", "int"), BinOp.Add, new RVal.IntConst(BigInteger.valueOf(2)))
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
        
        // Before: y in [3, 3]
        IntervalDomain.State expected = new IntervalDomain.State(java.util.Map.of("y", 
            new IntervalDomain.Interval(BigInteger.valueOf(3), BigInteger.valueOf(3))), false);
        assertTrue(domain.entails(pre, expected));
    }
    
    @Test
    void backwardTransferMultiplication() {
        // After: x in [6, 10]
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(6), BigInteger.valueOf(10))), false);
        
        // Step: x = y * 2
        Step step = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.Binop(new LVal.Local("y", "int"), BinOp.Mult, new RVal.IntConst(BigInteger.valueOf(2)))
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
        
        // Before: y in [3, 5]
        IntervalDomain.State expected = new IntervalDomain.State(java.util.Map.of("y", 
            new IntervalDomain.Interval(BigInteger.valueOf(3), BigInteger.valueOf(5))), false);
        assertTrue(domain.entails(pre, expected));
    }
    
    @Test
    void backwardTransferNegativeMultiplication() {
        // After: x in [-10, -6]
        IntervalDomain.State post = new IntervalDomain.State(java.util.Map.of("x", 
            new IntervalDomain.Interval(BigInteger.valueOf(-10), BigInteger.valueOf(-6))), false);
        
        // Step: x = y * -2
        Step step = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.Binop(new LVal.Local("y", "int"), BinOp.Mult, new RVal.IntConst(BigInteger.valueOf(-2)))
        );
        
        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
        
        // Before: y in [3, 5] (since -2 * 3 = -6, -2 * 5 = -10)
        IntervalDomain.State expected = new IntervalDomain.State(java.util.Map.of("y", 
            new IntervalDomain.Interval(BigInteger.valueOf(3), BigInteger.valueOf(5))), false);
        assertTrue(domain.entails(pre, expected));
    }
    
    @Test
    void name() {
        assertEquals("IntervalDomain", domain.name());
    }
}
