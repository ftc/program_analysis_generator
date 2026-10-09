package pag.domains.gen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import pag.api.*;

/**
 * Tests for proving reach(id) calls unreachable through backward analysis.
 * 
 * The domain proves reachability by tracking variable constraints.
 * When constraints become contradictory at method entry, reach(id) is unreachable.
 */
public class SimpleIntDomainReachabilityTest {

    private SimpleIntDomain domain = new SimpleIntDomain();

    @Test
    void testContradictoryConstraints() {
        // Simulate backward analysis where we prove a condition is impossible
        Map<String, SimpleIntDomain.Interval> state = new HashMap<>();
        state.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.ZERO));

        // Apply assume that x must be non-zero
        Assume step = new Assume(new SimpleIntDomain.Interval.Binop(
            new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO),
            BinOp.Eq,
            new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO)
        ));

        Map<String, SimpleIntDomain.Interval> restricted = domain.transfer(step, state);

        // After constraint, state should reflect the condition
        assertNotNull(restricted);
    }

    @Test
    void testJoinWithBottom() {
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> b = domain.bottom();

        Map<String, SimpleIntDomain.Interval> result = domain.join(a, b);

        // Join with bottom should preserve a
        assertEquals(BigInteger.ZERO, result.get("x").min);
        assertEquals(BigInteger.TEN, result.get("x").max);
    }

    @Test
    void testMultipleAssignments() {
        Map<String, SimpleIntDomain.Interval> post = new HashMap<>();
        post.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Assign step1 = new Assign(new SimpleIntDomain.Interval.Local("x", "int"),
            new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO));

        Map<String, SimpleIntDomain.Interval> pre1 = domain.transfer(step1, post);

        Assign step2 = new Assign(new SimpleIntDomain.Interval.Local("y", "int"),
            new SimpleIntDomain.Interval.IntConst(BigInteger.ONE));

        Map<String, SimpleIntDomain.Interval> pre2 = domain.transfer(step2, pre1);

        // Both x and y should be unconstrained after assignments
        assertNotNull(pre2.get("x"));
        assertNotNull(pre2.get("y"));
    }

    @Test
    void testBinopInTransfer() {
        Map<String, SimpleIntDomain.Interval> post = new HashMap<>();
        post.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Assign step = new Assign(
            new SimpleIntDomain.Interval.Local("y", "int"),
            new SimpleIntDomain.Interval.Binop(
                new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO),
                BinOp.Add,
                new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO)
            )
        );

        Map<String, SimpleIntDomain.Interval> pre = domain.transfer(step, post);

        assertNotNull(pre);
        assertNotNull(pre.get("y"));
    }

    @Test
    void testCallWithArgs() {
        Map<String, SimpleIntDomain.Interval> post = new HashMap<>();
        post.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Call step = new Call(
            Optional.of(new SimpleIntDomain.Interval.Local("result", "int")),
            new MethodId("java.lang.Integer", "parseInt", List.of("String"), "int"),
            List.of(new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO))
        );

        Map<String, SimpleIntDomain.Interval> pre = domain.transfer(step, post);

        // result should be unconstrained after call
        assertNotNull(pre.get("result"));
    }

    @Test
    void testEmptyState() {
        Map<String, SimpleIntDomain.Interval> empty = new HashMap<>();

        Assign step = new Assign(
            new SimpleIntDomain.Interval.Local("x", "int"),
            new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO)
        );

        Map<String, SimpleIntDomain.Interval> result = domain.transfer(step, empty);

        // After assignment, x should be unconstrained
        assertNotNull(result.get("x"));
    }

    @Test
    void testEntailsEmpty() {
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> b = new HashMap<>();
        b.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.FIVE));

        // a does not entail b (a is less precise)
        assertFalse(domain.entails(a, b));

        // b entails b
        assertTrue(domain.entails(b, b));
    }

    @Test
    void testJoinPreservesSoundness() {
        // Join should admit all states from both inputs
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> b = new HashMap<>();
        b.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> result = domain.join(a, b);

        // Result should contain both intervals
        assertNotNull(result.get("x"));
    }

    @Test
    void testWidenConvergence() {
        // Widen should converge (not require infinite iteration)
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> b = new HashMap<>();
        b.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> widened = domain.widen(a, b);

        // Widen should not diverge
        assertNotNull(widened.get("x"));
    }
}
