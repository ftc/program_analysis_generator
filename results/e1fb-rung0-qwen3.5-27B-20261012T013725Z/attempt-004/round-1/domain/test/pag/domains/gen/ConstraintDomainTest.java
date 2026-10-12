package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pag.api.*;
import java.math.BigInteger;
import java.util.Optional;
import java.util.List;

public class ConstraintDomainTest {
    
    private ConstraintDomain domain = new ConstraintDomain();
    
    @Test
    public void testTopAndBottom() {
        ConstraintDomain.State top = domain.top();
        ConstraintDomain.State bottom = domain.bottom();
        
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
    }
    
    @Test
    public void testEntails() {
        ConstraintDomain.State s1 = domain.top();
        ConstraintDomain.State s2 = domain.bottom();
        
        // bottom entails everything
        assertTrue(domain.entails(s2, s1));
        
        // top entails top
        assertTrue(domain.entails(s1, s1));
        
        // top does not entail bottom
        assertFalse(domain.entails(s1, s2));
    }
    
    @Test
    public void testAssignConstantBackward() {
        // x = 5, post constrains x to 5
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(5));
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        ConstraintDomain.State pre = domain.transfer(assign, post);
        
        // x should be unconstrained before
        assertNull(pre.exactValues().get("x"));
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testAssignConstantContradiction() {
        // x = 5, post constrains x to 3 (contradiction)
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(3));
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        ConstraintDomain.State pre = domain.transfer(assign, post);
        
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    public void testAssignVariableBackward() {
        // x = y, post constrains x to 5
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(5));
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new LVal.Local("y", "int")
        );
        
        ConstraintDomain.State pre = domain.transfer(assign, post);
        
        assertEquals(BigInteger.valueOf(5), pre.exactValues().get("y"));
        assertNull(pre.exactValues().get("x"));
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testAssumeEquality() {
        // Assume x == 5, post has no constraints
        ConstraintDomain.State post = domain.top();
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(5))
            )
        );
        
        ConstraintDomain.State pre = domain.transfer(assume, post);
        
        assertEquals(BigInteger.valueOf(5), pre.exactValues().get("x"));
    }
    
    @Test
    public void testAssumeContradiction() {
        // Assume x == 5, post constrains x to 3
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(3));
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(5))
            )
        );
        
        ConstraintDomain.State pre = domain.transfer(assume, post);
        
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    public void testAssumeConsistent() {
        // Assume x == 5, post constrains x to 5
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(5));
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(5))
            )
        );
        
        ConstraintDomain.State pre = domain.transfer(assume, post);
        
        assertEquals(BigInteger.valueOf(5), pre.exactValues().get("x"));
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testJoinDifferentValues() {
        ConstraintDomain.State s1 = ConstraintDomain.State.empty();
        s1.setExactValue("x", BigInteger.valueOf(5));
        
        ConstraintDomain.State s2 = ConstraintDomain.State.empty();
        s2.setExactValue("x", BigInteger.valueOf(3));
        
        ConstraintDomain.State result = domain.join(s1, s2);
        
        // x should be unconstrained (different values)
        assertNull(result.exactValues().get("x"));
        assertFalse(domain.isBottom(result));
    }
    
    @Test
    public void testJoinAgreeingValues() {
        ConstraintDomain.State s1 = ConstraintDomain.State.empty();
        s1.setExactValue("x", BigInteger.valueOf(5));
        
        ConstraintDomain.State s2 = ConstraintDomain.State.empty();
        s2.setExactValue("x", BigInteger.valueOf(5));
        
        ConstraintDomain.State result = domain.join(s1, s2);
        
        // x should still be 5
        assertEquals(BigInteger.valueOf(5), result.exactValues().get("x"));
    }
    
    @Test
    public void testJoinWithBottom() {
        ConstraintDomain.State s1 = domain.bottom();
        ConstraintDomain.State s2 = ConstraintDomain.State.empty();
        s2.setExactValue("x", BigInteger.valueOf(5));
        
        ConstraintDomain.State result = domain.join(s1, s2);
        
        // bottom join s = s
        assertEquals(BigInteger.valueOf(5), result.exactValues().get("x"));
        assertFalse(domain.isBottom(result));
    }
    
    @Test
    public void testCallRandInt() {
        // x = randInt(), post constrains x to 5
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(5));
        
        Step.Call call = new Step.Call(
            Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );
        
        ConstraintDomain.State pre = domain.transfer(call, post);
        
        // x should be unconstrained before
        assertNull(pre.exactValues().get("x"));
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testMultipleVariables() {
        ConstraintDomain.State post = ConstraintDomain.State.empty();
        post.setExactValue("x", BigInteger.valueOf(5));
        post.setExactValue("y", BigInteger.valueOf(10));
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new LVal.Local("z", "int")
        );
        
        ConstraintDomain.State pre = domain.transfer(assign, post);
        
        assertEquals(BigInteger.valueOf(5), pre.exactValues().get("z"));
        assertEquals(BigInteger.valueOf(10), pre.exactValues().get("y"));
        assertNull(pre.exactValues().get("x"));
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testBottomPropagation() {
        ConstraintDomain.State post = domain.bottom();
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        ConstraintDomain.State pre = domain.transfer(assign, post);
        
        assertTrue(domain.isBottom(pre));
    }
    
    @Test
    public void testReachabilityProof() {
        // Simulate backward analysis for:
        // void foo() {
        //     int x = 5;
        //     if (x == 3) {
        //         reach(1);
        //     }
        // }
        
        // Start at reach(1) with top
        ConstraintDomain.State state = domain.top();
        
        // Go backward through if (x == 3)
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(3))
            )
        );
        state = domain.transfer(assume, state);
        
        // Go backward through x = 5
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        state = domain.transfer(assign, state);
        
        // Should be bottom (contradiction: x must be 3 and 5)
        assertTrue(domain.isBottom(state));
    }
}
