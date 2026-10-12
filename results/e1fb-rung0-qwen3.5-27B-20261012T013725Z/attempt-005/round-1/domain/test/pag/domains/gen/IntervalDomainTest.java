package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import pag.api.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

class IntervalDomainTest {

    private static final IntervalDomain domain = new IntervalDomain();
    private static final IntervalDomain.State TOP = domain.top();
    private static final IntervalDomain.State BOTTOM = domain.bottom();

    @Test
    void testTopAndBottom() {
        assertTrue(domain.isBottom(BOTTOM));
        assertFalse(domain.isBottom(TOP));
    }

    @Test
    void testEntails() {
        // Bottom entails everything
        assertTrue(domain.entails(BOTTOM, TOP));
        
        // Top entails top
        assertTrue(domain.entails(TOP, TOP));
        
        // Top doesn't entail bottom
        assertFalse(domain.entails(TOP, BOTTOM));
    }

    @Test
    void testJoin() {
        // Join with bottom returns the other
        assertEquals(TOP, domain.join(BOTTOM, TOP));
        assertEquals(TOP, domain.join(TOP, BOTTOM));
        
        // Join is idempotent
        assertEquals(TOP, domain.join(TOP, TOP));
    }

    @Test
    void testWiden() {
        // Widen with bottom returns the other
        assertEquals(TOP, domain.widen(BOTTOM, TOP));
        assertEquals(TOP, domain.widen(TOP, BOTTOM));
    }

    @Test
    void testTransferAssign() {
        // Assign: x = 5
        // If post has constraint x in [5,5], pre has x unconstrained (any value)
        // and the source expression (constant 5) must equal 5 (which it does)
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        // Start with post where x must be 5
        IntervalDomain.State post = domain.transfer(
            new Step.Assign(
                new LVal.Local("y", "int"),
                new RVal.IntConst(BigInteger.valueOf(5))
            ),
            TOP
        );
        
        // After y=5, y is unconstrained in pre
        assertFalse(domain.isBottom(post));
    }

    @Test
    void testTransferAssignWithVariable() {
        // Assign: x = y
        // If post has x in [5,5], pre has x unconstrained and y in [5,5]
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new LVal.Local("y", "int")
        );
        
        // Create a state where x must be 5
        IntervalDomain.State post = new IntervalDomain.State(
            java.util.Map.of("x", IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(5))),
            false
        );
        
        IntervalDomain.State pre = domain.transfer(assign, post);
        
        assertFalse(domain.isBottom(pre));
        // x should be unconstrained in pre
        assertTrue(pre.getInterval("x").isTop());
        // y should be constrained to [5,5]
        assertEquals(IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(5)),
            pre.getInterval("y"));
    }

    @Test
    void testTransferAssume() {
        // Assume: x > 0
        // If post has x in [0,10], pre has x in [1,10]
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Gt,
                new RVal.IntConst(BigInteger.valueOf(0))
            )
        );
        
        IntervalDomain.State post = new IntervalDomain.State(
            java.util.Map.of("x", IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10))),
            false
        );
        
        IntervalDomain.State pre = domain.transfer(assume, post);
        
        assertFalse(domain.isBottom(pre));
        // x should be in [1,10]
        assertEquals(IntervalDomain.Interval.of(BigInteger.valueOf(1), BigInteger.valueOf(10)),
            pre.getInterval("x"));
    }

    @Test
    void testTransferAssumeBottom() {
        // Assume: x = 5, but x is constrained to [0,0]
        // This should result in bottom
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(5))
            )
        );
        
        IntervalDomain.State post = new IntervalDomain.State(
            java.util.Map.of("x", IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(0))),
            false
        );
        
        IntervalDomain.State pre = domain.transfer(assume, post);
        
        assertTrue(domain.isBottom(pre));
    }

    @Test
    void testTransferCallRandInt() {
        // Call: x = randInt()
        // x becomes unconstrained in pre
        
        Step.Call call = new Step.Call(
            Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );
        
        // Post has x constrained to [5,5]
        IntervalDomain.State post = new IntervalDomain.State(
            java.util.Map.of("x", IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(5))),
            false
        );
        
        IntervalDomain.State pre = domain.transfer(call, post);
        
        assertFalse(domain.isBottom(pre));
        // x should be unconstrained in pre
        assertTrue(pre.getInterval("x").isTop());
    }

    @Test
    void testIntervalMeet() {
        IntervalDomain.Interval a = IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(10));
        IntervalDomain.Interval b = IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(15));
        
        IntervalDomain.Interval result = a.meet(b);
        
        assertEquals(IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(10)), result);
    }

    @Test
    void testIntervalMeetEmpty() {
        IntervalDomain.Interval a = IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5));
        IntervalDomain.Interval b = IntervalDomain.Interval.of(BigInteger.valueOf(10), BigInteger.valueOf(15));
        
        IntervalDomain.Interval result = a.meet(b);
        
        assertTrue(result.isEmpty());
    }

    @Test
    void testIntervalJoin() {
        IntervalDomain.Interval a = IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5));
        IntervalDomain.Interval b = IntervalDomain.Interval.of(BigInteger.valueOf(10), BigInteger.valueOf(15));
        
        IntervalDomain.Interval result = a.join(b);
        
        assertEquals(IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(15)), result);
    }

    @Test
    void testIntervalWiden() {
        IntervalDomain.Interval a = IntervalDomain.Interval.of(BigInteger.valueOf(0), BigInteger.valueOf(5));
        IntervalDomain.Interval b = IntervalDomain.Interval.of(BigInteger.valueOf(10), BigInteger.valueOf(15));
        
        IntervalDomain.Interval result = a.widen(b);
        
        // Widen should expand to cover both
        assertTrue(result.contains(BigInteger.valueOf(0)));
        assertTrue(result.contains(BigInteger.valueOf(15)));
    }

    @Test
    void testSoundnessAssign() {
        // Test that we don't exclude reachable states
        
        // x = y, post has x in [5,5]
        // pre should have y in [5,5] and x unconstrained
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new LVal.Local("y", "int")
        );
        
        IntervalDomain.State post = new IntervalDomain.State(
            java.util.Map.of("x", IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(5))),
            false
        );
        
        IntervalDomain.State pre = domain.transfer(assign, post);
        
        // Should not be bottom (state is reachable)
        assertFalse(domain.isBottom(pre));
        
        // y should be constrained
        assertFalse(pre.getInterval("y").isTop());
    }

    @Test
    void testSoundnessAssume() {
        // Test that we don't exclude reachable states with assumptions
        
        // Assume x > 0, post has x in [5,10]
        // pre should have x in [5,10] (intersection with x > 0)
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Gt,
                new RVal.IntConst(BigInteger.valueOf(0))
            )
        );
        
        IntervalDomain.State post = new IntervalDomain.State(
            java.util.Map.of("x", IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(10))),
            false
        );
        
        IntervalDomain.State pre = domain.transfer(assume, post);
        
        // Should not be bottom
        assertFalse(domain.isBottom(pre));
        
        // x should still be in [5,10]
        assertEquals(IntervalDomain.Interval.of(BigInteger.valueOf(5), BigInteger.valueOf(10)),
            pre.getInterval("x"));
    }
}
