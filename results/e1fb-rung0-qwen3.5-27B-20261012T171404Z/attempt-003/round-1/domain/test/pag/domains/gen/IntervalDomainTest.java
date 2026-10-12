package pag.domains.gen;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pag.api.*;

class IntervalDomainTest {

    private final IntervalDomain domain = new IntervalDomain();

    private IntervalDomain.State createState(String var, BigInteger min, BigInteger max) {
        Map<String, IntervalDomain.Interval> intervals = Map.of(var, IntervalDomain.Interval.of(min, max));
        return IntervalDomain.State.fromMap(intervals);
    }

    @Test
    void testTop() {
        IntervalDomain.State top = domain.top();
        assertFalse(domain.isBottom(top));
    }

    @Test
    void testBottom() {
        IntervalDomain.State bottom = domain.bottom();
        assertTrue(domain.isBottom(bottom));
    }

    @Test
    void testEntails() {
        // [1, 5] entails [0, 10]
        IntervalDomain.State a = createState("x", BigInteger.ONE, BigInteger.valueOf(5));
        IntervalDomain.State b = createState("x", BigInteger.ZERO, BigInteger.TEN);
        assertTrue(domain.entails(a, b));

        // [1, 5] does not entail [6, 10]
        IntervalDomain.State c = createState("x", BigInteger.valueOf(6), BigInteger.TEN);
        assertFalse(domain.entails(a, c));
    }

    @Test
    void testJoin() {
        // Join [1, 3] and [5, 7] should give [1, 7]
        IntervalDomain.State a = createState("x", BigInteger.ONE, BigInteger.valueOf(3));
        IntervalDomain.State b = createState("x", BigInteger.valueOf(5), BigInteger.valueOf(7));
        IntervalDomain.State result = domain.join(a, b);

        IntervalDomain.Interval resultInt = result.getInterval("x");
        assertEquals(BigInteger.ONE, resultInt.getMin());
        assertEquals(BigInteger.valueOf(7), resultInt.getMax());
    }

    @Test
    void testWiden() {
        // Widen [1, 3] and [1, 5] should give [1, 5] (no widening needed)
        IntervalDomain.State a = createState("x", BigInteger.ONE, BigInteger.valueOf(3));
        IntervalDomain.State b = createState("x", BigInteger.ONE, BigInteger.valueOf(5));
        IntervalDomain.State result = domain.widen(a, b);

        IntervalDomain.Interval resultInt = result.getInterval("x");
        assertEquals(BigInteger.ONE, resultInt.getMin());
        assertEquals(BigInteger.valueOf(5), resultInt.getMax());
    }

    @Test
    void testTransferAssign() {
        // Backward transfer: x = 5, post has x in [5, 5]
        // Before assignment, x can be anything (TOP)
        LVal.Local target = new LVal.Local("x", "int");
        RVal source = new RVal.IntConst(BigInteger.valueOf(5));
        Step.Assign assign = new Step.Assign(target, source);

        IntervalDomain.State post = createState("x", BigInteger.valueOf(5), BigInteger.valueOf(5));
        IntervalDomain.State pre = domain.transfer(assign, post);

        // Before assignment, x should be unconstrained (TOP)
        IntervalDomain.Interval preInt = pre.getInterval("x");
        assertTrue(preInt.isTop());
    }

    @Test
    void testTransferAssignBottom() {
        // x = 5, post has x in [10, 20] -> impossible, should be bottom
        LVal.Local target = new LVal.Local("x", "int");
        RVal source = new RVal.IntConst(BigInteger.valueOf(5));
        Step.Assign assign = new Step.Assign(target, source);

        IntervalDomain.State post = createState("x", BigInteger.TEN, BigInteger.valueOf(20));
        IntervalDomain.State pre = domain.transfer(assign, post);

        assertTrue(domain.isBottom(pre));
    }

    @Test
    void testTransferAssumeEq() {
        // Assume x == 5, post has x in [0, 10]
        // After assume, x should be in [5, 5]
        RVal cond = new RVal.Binop(
            new LVal.Local("x", "int"),
            BinOp.Eq,
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        Step.Assume assume = new Step.Assume(cond);

        IntervalDomain.State post = createState("x", BigInteger.ZERO, BigInteger.TEN);
        IntervalDomain.State pre = domain.transfer(assume, post);

        IntervalDomain.Interval preInt = pre.getInterval("x");
        assertEquals(BigInteger.valueOf(5), preInt.getMin());
        assertEquals(BigInteger.valueOf(5), preInt.getMax());
    }

    @Test
    void testTransferAssumeLt() {
        // Assume x < 5, post has x in [0, 10]
        // After assume, x should be in [0, 4]
        RVal cond = new RVal.Binop(
            new LVal.Local("x", "int"),
            BinOp.Lt,
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        Step.Assume assume = new Step.Assume(cond);

        IntervalDomain.State post = createState("x", BigInteger.ZERO, BigInteger.TEN);
        IntervalDomain.State pre = domain.transfer(assume, post);

        IntervalDomain.Interval preInt = pre.getInterval("x");
        assertEquals(BigInteger.ZERO, preInt.getMin());
        assertEquals(BigInteger.valueOf(4), preInt.getMax());
    }

    @Test
    void testTransferCall() {
        // Call x = randInt(), post has x in [0, 100]
        // Before call, x can be anything (TOP)
        LVal.Local target = new LVal.Local("x", "int");
        MethodId callee = new MethodId("pag.probe.Rand", "randInt", List.of(), "int");
        Step.Call call = new Step.Call(Optional.of(target), callee, List.of());

        IntervalDomain.State post = createState("x", BigInteger.ZERO, BigInteger.valueOf(100));
        IntervalDomain.State pre = domain.transfer(call, post);

        IntervalDomain.Interval preInt = pre.getInterval("x");
        assertTrue(preInt.isTop());
    }

    @Test
    void testBottomPropagation() {
        // If post is bottom, transfer should return bottom
        IntervalDomain.State post = domain.bottom();
        LVal.Local target = new LVal.Local("x", "int");
        RVal source = new RVal.IntConst(BigInteger.valueOf(5));
        Step.Assign assign = new Step.Assign(target, source);

        IntervalDomain.State pre = domain.transfer(assign, post);
        assertTrue(domain.isBottom(pre));
    }

    @Test
    void testAssumeBottom() {
        // Assume x == 5, post has x in [10, 20] -> impossible
        RVal cond = new RVal.Binop(
            new LVal.Local("x", "int"),
            BinOp.Eq,
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        Step.Assume assume = new Step.Assume(cond);

        IntervalDomain.State post = createState("x", BigInteger.TEN, BigInteger.valueOf(20));
        IntervalDomain.State pre = domain.transfer(assume, post);

        assertTrue(domain.isBottom(pre));
    }
}
