package pag.domains.gen;

import org.junit.jupiter.api.Test;
import pag.api.*;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleDomainTest {

    @Test
    void testTopAndBottom() {
        SimpleDomain domain = new SimpleDomain();
        SimpleDomain.State top = domain.top();
        SimpleDomain.State bottom = domain.bottom();

        assertFalse(domain.isBottom(top), "Top should not be bottom");
        assertTrue(domain.isBottom(bottom), "Bottom should be bottom");
    }

    @Test
    void testEntails() {
        SimpleDomain domain = new SimpleDomain();

        // Bottom entails everything
        assertTrue(domain.entails(SimpleDomain.State.BOTTOM, SimpleDomain.State.TOP));
        assertTrue(domain.entails(SimpleDomain.State.BOTTOM, SimpleDomain.State.BOTTOM));

        // Top entails top
        assertTrue(domain.entails(SimpleDomain.State.TOP, SimpleDomain.State.TOP));

        // Constraining more implies constraining less
        SimpleDomain.State top = SimpleDomain.State.TOP;
        SimpleDomain.State constrained = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );

        assertTrue(domain.entails(constrained, top));
        assertFalse(domain.entails(top, constrained));
    }

    @Test
    void testTransferAssign() {
        SimpleDomain domain = new SimpleDomain();

        // Assign(x, 5): if post constrains x to 5, pre has x unconstrained (since e=5 is constant)
        SimpleDomain.State post = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );

        Step assign = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.IntConst(BigInteger.valueOf(5))
        );

        SimpleDomain.State pre = domain.transfer(assign, post);
        assertEquals(SimpleDomain.Constraint.UNCONSTRAINED, pre.get("x"),
            "After assign(x, 5), x should be unconstrained in pre");
    }

    @Test
    void testTransferAssignWithVariable() {
        SimpleDomain domain = new SimpleDomain();

        // Assign(x, y): if post constrains x to 5, pre constrains y to 5
        SimpleDomain.State post = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );

        Step assign = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.Local("y", "int")
        );

        SimpleDomain.State pre = domain.transfer(assign, post);
        assertEquals(SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)), pre.get("y"),
            "After assign(x, y), pre should constrain y to 5");
        assertEquals(SimpleDomain.Constraint.UNCONSTRAINED, pre.get("x"),
            "After assign(x, y), x should be unconstrained in pre");
    }

    @Test
    void testTransferCall() {
        SimpleDomain domain = new SimpleDomain();

        // Call(x, randInt()): x becomes unconstrained in pre
        SimpleDomain.State post = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );

        List<RVal> args = new ArrayList<>();
        Step call = new Step.Call(
            Optional.of(new Step.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", Collections.emptyList(), "int"),
            args
        );

        SimpleDomain.State pre = domain.transfer(call, post);
        assertEquals(SimpleDomain.Constraint.UNCONSTRAINED, pre.get("x"),
            "After call to randInt, x should be unconstrained in pre");
    }

    @Test
    void testTransferAssume() {
        SimpleDomain domain = new SimpleDomain();

        // Assume(x == 5): restricts post to states where x == 5
        SimpleDomain.State post = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );

        Step assume = new Step.Assume(new Step.Binop(
            new Step.Local("x", "int"),
            BinOp.Eq,
            new Step.IntConst(BigInteger.valueOf(5))
        ));

        SimpleDomain.State pre = domain.transfer(assume, post);
        assertEquals(SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)), pre.get("x"),
            "After assume(x == 5), pre should constrain x to 5");
    }

    @Test
    void testJoin() {
        SimpleDomain domain = new SimpleDomain();

        SimpleDomain.State a = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );
        SimpleDomain.State b = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );

        SimpleDomain.State join = domain.join(a, b);
        assertEquals(SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)), join.get("x"));

        // Join with different values should be impossible
        SimpleDomain.State c = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(10)))
        );
        SimpleDomain.State join2 = domain.join(a, c);
        assertTrue(domain.isBottom(join2), "Join of conflicting constraints should be bottom");
    }

    @Test
    void testReachableProof() {
        SimpleDomain domain = new SimpleDomain();

        // Simulate a path that proves unreachable
        // Start at target (top), work backward to entry (should be bottom)

        // Step 1: Assign(x, 5)
        SimpleDomain.State s1 = SimpleDomain.State.TOP;
        Step assign1 = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.IntConst(BigInteger.valueOf(5))
        );
        s1 = domain.transfer(assign1, s1);

        // Step 2: Assume(x == 5)
        Step assume = new Step.Assume(new Step.Binop(
            new Step.Local("x", "int"),
            BinOp.Eq,
            new Step.IntConst(BigInteger.valueOf(5))
        ));
        s1 = domain.transfer(assume, s1);

        // Step 3: Assign(y, x)
        Step assign2 = new Step.Assign(
            new Step.Local("y", "int"),
            new Step.Local("x", "int")
        );
        s1 = domain.transfer(assign2, s1);

        // Check if this state is bottom (proves unreachable)
        // In this case, it should NOT be bottom since we have valid constraints
        assertFalse(domain.isBottom(s1), "This path should be reachable");
    }

    @Test
    void testUnreachableProof() {
        SimpleDomain domain = new SimpleDomain();

        // Simulate a path that proves unreachable
        // Start at target (top), work backward to entry (should be bottom)

        // Step 1: Assign(x, 5)
        SimpleDomain.State s1 = SimpleDomain.State.TOP;
        Step assign1 = new Step.Assign(
            new Step.Local("x", "int"),
            new Step.IntConst(BigInteger.valueOf(5))
        );
        s1 = domain.transfer(assign1, s1);

        // Step 2: Assume(x == 10) - this conflicts with x == 5
        Step assume = new Step.Assume(new Step.Binop(
            new Step.Local("x", "int"),
            BinOp.Eq,
            new Step.IntConst(BigInteger.valueOf(10))
        ));
        s1 = domain.transfer(assume, s1);

        // This should be bottom because x cannot be both 5 and 10
        assertTrue(domain.isBottom(s1), "This path should be unreachable due to contradiction");
    }

    @Test
    void testWiden() {
        SimpleDomain domain = new SimpleDomain();

        SimpleDomain.State a = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(5)))
        );
        SimpleDomain.State b = SimpleDomain.State.of(
            Map.of("x", SimpleDomain.Constraint.CONSTRAINED(BigInteger.valueOf(10)))
        );

        SimpleDomain.State widened = domain.widen(a, b);
        assertTrue(domain.isBottom(widened), "Widen of conflicting constraints should be bottom");
    }
}
