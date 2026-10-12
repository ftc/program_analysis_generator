package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import pag.api.*;

public class EqualityDomainTest {
    
    private final EqualityDomain domain = new EqualityDomain();
    
    @Test
    public void testBottomIsBottom() {
        assertTrue(domain.isBottom(domain.bottom()));
        assertFalse(domain.isBottom(domain.top()));
    }
    
    @Test
    public void testTopEntailsEverything() {
        // top() entails bottom() is false (top admits states, bottom admits none)
        // But bottom() entails top() is true (bottom admits nothing)
        assertFalse(domain.entails(domain.top(), domain.bottom()));
        assertTrue(domain.entails(domain.bottom(), domain.top()));
    }
    
    @Test
    public void testJoin() {
        var s1 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        var s2 = domain.transfer(
            new Step.Assign(new LVal.Local("y", "int"), new RVal.IntConst(BigInteger.valueOf(10))),
            domain.top()
        );
        
        // Join of x=5 and y=10 should have both
        var joined = domain.join(s1, s2);
        assertFalse(domain.isBottom(joined));
        assertEquals(BigInteger.valueOf(5), joined.getEqualities().get("x"));
        assertEquals(BigInteger.valueOf(10), joined.getEqualities().get("y"));
    }
    
    @Test
    public void testJoinConflictingEqualities() {
        var s1 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        var s2 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(10))),
            domain.top()
        );
        
        // Join of x=5 and x=10 should forget x (they disagree)
        var joined = domain.join(s1, s2);
        assertFalse(domain.isBottom(joined));
        assertFalse(joined.getEqualities().containsKey("x"));
    }
    
    @Test
    public void testAssumeContradiction() {
        var s = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        
        // Assume x == 10 contradicts x == 5
        var afterAssume = domain.transfer(
            new Step.Assume(new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(10))
            )),
            s
        );
        
        assertTrue(domain.isBottom(afterAssume));
    }
    
    @Test
    public void testAssumeConsistent() {
        var s = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        
        // Assume x == 5 is consistent with x == 5
        var afterAssume = domain.transfer(
            new Step.Assume(new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(5))
            )),
            s
        );
        
        assertFalse(domain.isBottom(afterAssume));
        assertEquals(BigInteger.valueOf(5), afterAssume.getEqualities().get("x"));
    }
    
    @Test
    public void testRandIntForgetsConstraint() {
        var s = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        
        // randInt() call forgets x's constraint
        var methodId = new MethodId("pag.probe.Rand", "randInt", List.of(), "int");
        var afterCall = domain.transfer(
            new Step.Call(Optional.of(new LVal.Local("x", "int")), methodId, List.of()),
            s
        );
        
        assertFalse(domain.isBottom(afterCall));
        assertFalse(afterCall.getEqualities().containsKey("x"));
    }
    
    @Test
    public void testBackwardAssignment() {
        // Forward: x = 5; y = x;
        // Backward from y = 10: y must be 10, so x must be 10, so x = 5 is contradiction
        
        var afterY = domain.transfer(
            new Step.Assign(new LVal.Local("y", "int"), new RVal.IntConst(BigInteger.valueOf(10))),
            domain.top()
        );
        
        // Backward: y = x; y = 10
        // After y=x: y=10, so x must be 10
        var afterYAssign = domain.transfer(
            new Step.Assign(new LVal.Local("y", "int"), new LVal.Local("x", "int")),
            afterY
        );
        
        assertEquals(BigInteger.valueOf(10), afterYAssign.getEqualities().get("x"));
        assertEquals(BigInteger.valueOf(10), afterYAssign.getEqualities().get("y"));
    }
    
    @Test
    public void testProveUnreachable() {
        // Simulate proving unreachable code:
        // x = 5;
        // assume(x == 10);
        // reach();
        
        // Start at reach() with top
        var atReach = domain.top();
        
        // Backward through assume(x == 10)
        var atAssume = domain.transfer(
            new Step.Assume(new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(10))
            )),
            atReach
        );
        
        // Backward through x = 5
        var atEntry = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            atAssume
        );
        
        // At entry, we should have bottom (proven unreachable)
        assertTrue(domain.isBottom(atEntry));
    }
    
    @Test
    public void testEntails() {
        var s1 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        
        var s2 = domain.transfer(
            new Step.Assign(new LVal.Local("x", "int"), new RVal.IntConst(BigInteger.valueOf(5))),
            domain.top()
        );
        
        // Same equalities should entail each other
        assertTrue(domain.entails(s1, s2));
        assertTrue(domain.entails(s2, s1));
        
        // More specific entails less specific
        var s3 = domain.top();
        assertTrue(domain.entails(s1, s3));
        assertFalse(domain.entails(s3, s1));
    }
}
