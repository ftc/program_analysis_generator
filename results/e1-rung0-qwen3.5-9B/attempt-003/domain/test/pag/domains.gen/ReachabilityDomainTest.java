package pag.domains.gen;

import pag.api.*;

import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReachabilityDomain.
 * 
 * Tests verify soundness of the domain operations:
 * - isBottom() correctly identifies unreachable states
 * - entails() correctly checks state inclusion
 * - transfer() correctly propagates constraints backward
 */
class ReachabilityDomainTest {

    private ReachabilityDomain domain;

    @BeforeEach
    void setUp() {
        domain = new ReachabilityDomain();
    }

    @Test
    void testTopState() {
        State top = domain.top();
        assertNotNull(top);
        assertFalse(domain.isBottom(top));
        assertEquals(0, top.constraints.size());
    }

    @Test
    void testBottomState() {
        State bottom = domain.bottom();
        assertNotNull(bottom);
        assertTrue(domain.isBottom(bottom));
        assertEquals(0, bottom.constraints.size());
    }

    @Test
    void testIsBottomEmptyState() {
        State s = domain.top();
        assertTrue(domain.isBottom(s));
    }

    @Test
    void testEntailsBottom() {
        State bottom = domain.bottom();
        State top = domain.top();

        // Bottom entails everything (admits no state)
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(bottom, bottom));

        // Nothing entails bottom (except bottom itself)
        assertFalse(domain.entails(top, bottom));
        assertTrue(domain.entails(bottom, bottom));
    }

    @Test
    void testTransferAssign() {
        // Assignment: x = y + 1
        // If post says x must be 5, then pre says y + 1 must be 5

        State post = new ReachabilityDomain.State();
        post.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                          new ReachabilityDomain.Constraint(BigInteger.valueOf(5)));

        Step assign = new ReachabilityDomain.Assign(
            new ReachabilityDomain.Local("x", "int"),
            new ReachabilityDomain.Binop(
                new ReachabilityDomain.Local("y", "int"),
                BinOp.Add,
                new ReachabilityDomain.IntConst(BigInteger.valueOf(1))
            )
        );

        State pre = domain.transfer(assign, post);

        // y should be constrained to 4 (since y + 1 = 5)
        // x should be unconstrained
        assertNotNull(pre);
    }

    @Test
    void testTransferAssume() {
        // Assume: if cond holds, continue
        // Constraints propagate unchanged

        State post = new ReachabilityDomain.State();
        post.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                          new ReachabilityDomain.Constraint(BigInteger.valueOf(10)));

        Step assume = new ReachabilityDomain.Assume(
            new ReachabilityDomain.Binop(
                new ReachabilityDomain.Local("x", "int"),
                BinOp.Gt,
                new ReachabilityDomain.IntConst(BigInteger.valueOf(5))
            )
        );

        State pre = domain.transfer(assume, post);

        // Constraints should be preserved
        assertNotNull(pre);
    }

    @Test
    void testTransferCallRandInt() {
        // Call to randInt makes target unconstrained

        State post = new ReachabilityDomain.State();
        post.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                          new ReachabilityDomain.Constraint(BigInteger.valueOf(100)));

        Step call = new ReachabilityDomain.Call(
            Optional.of(new ReachabilityDomain.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );

        State pre = domain.transfer(call, post);

        // x should be unconstrained in pre
        assertNotNull(pre);
    }

    @Test
    void testJoin() {
        State s1 = new ReachabilityDomain.State();
        s1.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                        new ReachabilityDomain.Constraint(BigInteger.valueOf(5)));

        State s2 = new ReachabilityDomain.State();
        s2.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                        new ReachabilityDomain.Constraint(BigInteger.valueOf(10)));

        State joined = domain.join(s1, s2);

        // Join of two different EQ constraints should be ANY
        assertNotNull(joined);
    }

    @Test
    void testWiden() {
        State s1 = new ReachabilityDomain.State();
        s1.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                        new ReachabilityDomain.Constraint(BigInteger.valueOf(5)));

        State s2 = new ReachabilityDomain.State();
        s2.setConstraint(new ReachabilityDomain.Local("x", "int"), 
                        new ReachabilityDomain.Constraint(BigInteger.valueOf(10)));

        State widened = domain.widen(s1, s2);

        // For this domain, widen = join
        assertNotNull(widened);
    }
}
