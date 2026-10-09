package pag.domains.gen;

import org.junit.jupiter.api.Test;
import pag.api.*;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class LinearConstraintDomainTest {

    private final LinearConstraintDomain domain = new LinearConstraintDomain();

    @Test
    public void testTopIsNotBottom() {
        LinearConstraintDomain.State top = domain.top();
        assertFalse(domain.isBottom(top));
    }

    @Test
    public void testBottomIsBottom() {
        LinearConstraintDomain.State bottom = domain.bottom();
        assertTrue(domain.isBottom(bottom));
    }

    @Test
    public void testEntailsReflexive() {
        LinearConstraintDomain.State state = domain.top();
        assertTrue(domain.entails(state, state));
    }

    @Test
    public void testEntailsBottom() {
        LinearConstraintDomain.State bottom = domain.bottom();
        LinearConstraintDomain.State top = domain.top();
        assertTrue(domain.entails(bottom, top));
    }

    @Test
    public void testJoinWithBottom() {
        LinearConstraintDomain.State bottom = domain.bottom();
        LinearConstraintDomain.State top = domain.top();
        LinearConstraintDomain.State joined = domain.join(bottom, top);
        // Join should not be bottom (it's the union of admitted states)
        // Actually, for backward analysis, join at merge points combines constraints
        // The result should admit states admitted by either
        assertFalse(domain.isBottom(joined));
    }

    @Test
    public void testTransferAssign() {
        // Test backward transfer for assignment: x = 5
        // If after: x >= 10, then before: 5 >= 10 (unsatisfiable)
        LinearConstraintDomain.State post = new LinearConstraintDomain.State(
            Map.of("x", List.of(
                new LinearConstraintDomain.Constraint(
                    Map.of("x", BigInteger.ONE),
                    BigInteger.TEN,
                    true
                )
            ))
        );

        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );

        LinearConstraintDomain.State pre = domain.transfer(assign, post);
        // Should be bottom because 5 >= 10 is false
        assertTrue(domain.isBottom(pre));
    }

    @Test
    public void testTransferAssignValid() {
        // Test backward transfer for assignment: x = 15
        // If after: x >= 10, then before: 15 >= 10 (satisfiable)
        LinearConstraintDomain.State post = new LinearConstraintDomain.State(
            Map.of("x", List.of(
                new LinearConstraintDomain.Constraint(
                    Map.of("x", BigInteger.ONE),
                    BigInteger.TEN,
                    true
                )
            ))
        );

        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(15))
        );

        LinearConstraintDomain.State pre = domain.transfer(assign, post);
        // Should not be bottom because 15 >= 10 is true
        assertFalse(domain.isBottom(pre));
    }

    @Test
    public void testTransferAssumeFalse() {
        // Test backward transfer for Assume(false)
        // This should result in bottom
        RVal cond = new RVal.Binop(
            new RVal.IntConst(BigInteger.ZERO),
            BinOp.Eq,
            new RVal.IntConst(BigInteger.ONE)
        );
        Step.Assume assume = new Step.Assume(cond);

        LinearConstraintDomain.State post = domain.top();
        LinearConstraintDomain.State pre = domain.transfer(assume, post);

        assertTrue(domain.isBottom(pre));
    }

    @Test
    public void testTransferAssumeTrue() {
        // Test backward transfer for Assume(true)
        // This should not result in bottom
        RVal cond = new RVal.Binop(
            new RVal.IntConst(BigInteger.ONE),
            BinOp.Eq,
            new RVal.IntConst(BigInteger.ONE)
        );
        Step.Assume assume = new Step.Assume(cond);

        LinearConstraintDomain.State post = domain.top();
        LinearConstraintDomain.State pre = domain.transfer(assume, post);

        assertFalse(domain.isBottom(pre));
    }

    @Test
    public void testTransferCall() {
        // Test backward transfer for x = randInt()
        // x should be unconstrained before the call
        LinearConstraintDomain.State post = new LinearConstraintDomain.State(
            Map.of("x", List.of(
                new LinearConstraintDomain.Constraint(
                    Map.of("x", BigInteger.ONE),
                    BigInteger.ZERO,
                    true
                )
            ))
        );

        Step.Call call = new Step.Call(
            Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );

        LinearConstraintDomain.State pre = domain.transfer(call, post);
        // x should be unconstrained (removed from constraints)
        assertFalse(pre.constraints.containsKey("x"));
        assertFalse(domain.isBottom(pre));
    }

    @Test
    public void testName() {
        assertEquals("LinearConstraintDomain", domain.name());
    }

    @Test
    public void testWiden() {
        LinearConstraintDomain.State a = domain.top();
        LinearConstraintDomain.State b = domain.top();
        LinearConstraintDomain.State widened = domain.widen(a, b);
        // Widen should not be bottom
        assertFalse(domain.isBottom(widened));
    }

    @Test
    public void testConstraintUnsatisfiable() {
        LinearConstraintDomain.Constraint c = new LinearConstraintDomain.Constraint(
            BigInteger.ZERO,
            BigInteger.ONE,
            true
        );
        assertTrue(c.isUnsatisfiable());
    }

    @Test
    public void testConstraintSatisfiable() {
        LinearConstraintDomain.Constraint c = new LinearConstraintDomain.Constraint(
            Map.of("x", BigInteger.ONE),
            BigInteger.ZERO,
            true
        );
        assertFalse(c.isUnsatisfiable());
    }
}
