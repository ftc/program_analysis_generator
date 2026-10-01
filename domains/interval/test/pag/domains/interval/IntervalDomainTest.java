package pag.domains.interval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pag.api.BinOp;
import pag.api.LVal;
import pag.api.MethodId;
import pag.api.RVal;
import pag.api.Step;

/** Backward transfer and the lattice, each answer worked out by hand. */
class IntervalDomainTest {

    private final IntervalDomain d = new IntervalDomain();
    private static final LVal.Local X = local("x"), Y = local("y");
    private static final Bound NEG = Bound.NEG_INF, POS = Bound.POS_INF;

    private static LVal.Local local(String n) { return new LVal.Local(n, "java.math.BigInteger"); }
    private static RVal.IntConst c(long n) { return new RVal.IntConst(BigInteger.valueOf(n)); }
    private static Bound b(long n) { return Bound.of(BigInteger.valueOf(n)); }
    private static Interval i(long lo, long hi) { return new Interval(b(lo), b(hi)); }
    private static RVal op(RVal l, BinOp o, RVal r) { return new RVal.Binop(l, o, r); }
    private static IntervalState env(Object... pairs) {
        var m = new java.util.HashMap<String, Interval>();
        for (int k = 0; k < pairs.length; k += 2) m.put((String) pairs[k], (Interval) pairs[k + 1]);
        return new IntervalState.Env(m);
    }
    private IntervalState assign(LVal.Local x, RVal e, IntervalState post) { return d.transfer(new Step.Assign(x, e), post); }
    private IntervalState assume(RVal cond, IntervalState post) { return d.transfer(new Step.Assume(cond), post); }

    // --- The six cases in README.md, "The transfer function"

    @Test void readme1_assignAConstantInsideTheRange() {
        assertEquals(d.top(), assign(X, c(5), env("x", i(0, 10))));
    }

    @Test void readme2_assignAConstantOutsideTheRange() {
        assertEquals(d.bottom(), assign(X, c(5), env("x", i(6, 10))));
    }

    @Test void readme3_increment() {
        assertEquals(env("x", i(-1, 9)), assign(X, op(X, BinOp.Add, c(1)), env("x", i(0, 10))));
    }

    @Test void readme4_copyIntersectsIntoTheSource() {
        assertEquals(env("y", i(5, 10)), assign(X, Y, env("x", i(0, 10), "y", i(5, 20))));
    }

    @Test void readme5_randIntFreesItsTarget() {
        Step call = new Step.Call(Optional.of(X),
                new MethodId("pag.probe.Rand", "randInt", List.of(), "java.math.BigInteger"), List.of());
        assertEquals(env("y", i(1, 2)), d.transfer(call, env("x", i(0, 10), "y", i(1, 2))));
    }

    @Test void readme6_assumeLessThanAConstant() {
        assertEquals(env("x", i(0, 9)), assume(op(X, BinOp.Lt, c(10)), env("x", i(0, 10))));
    }

    // --- Assignment

    @Test void subtraction() {
        // x := y - 3, x in [0,10] after: y - 3 in [0,10], so y in [3,13]
        assertEquals(env("y", i(3, 13)), assign(X, op(Y, BinOp.Sub, c(3)), env("x", i(0, 10))));
        // x := 3 - y, x in [0,10] after: y in [3 - 10, 3 - 0] = [-7,3]
        assertEquals(env("y", i(-7, 3)), assign(X, op(c(3), BinOp.Sub, Y), env("x", i(0, 10))));
    }

    @Test void multiplicationByAPositiveConstantRoundsInward() {
        // x := y * 3, x in [1,10]: y * 3 in [1,10] means y in [⌈1/3⌉, ⌊10/3⌋] = [1,3]
        assertEquals(env("y", i(1, 3)), assign(X, op(Y, BinOp.Mult, c(3)), env("x", i(1, 10))));
    }

    @Test void multiplicationByANegativeConstantSwapsTheEnds() {
        // x := y * -2, x in [-10,4]: y in [⌈4/-2⌉, ⌊-10/-2⌋] = [-2,5]
        assertEquals(env("y", i(-2, 5)), assign(X, op(Y, BinOp.Mult, c(-2)), env("x", i(-10, 4))));
    }

    @Test void multiplicationByZero() {
        assertEquals(d.top(), assign(X, op(Y, BinOp.Mult, c(0)), env("x", i(-1, 1))));
        assertEquals(d.bottom(), assign(X, op(Y, BinOp.Mult, c(0)), env("x", i(1, 5))));
    }

    @Test void aProductOfTwoLocalsIsNotRefined() {
        assertEquals(env("y", i(1, 2)), assign(X, op(Y, BinOp.Mult, local("z")), env("x", i(0, 10), "y", i(1, 2))));
    }

    @Test void anUnconstrainedTargetConstrainsNothing() {
        assertEquals(env("y", i(1, 2)), assign(X, op(Y, BinOp.Add, c(1)), env("y", i(1, 2))));
    }

    @Test void bottomStaysBottom() {
        assertEquals(d.bottom(), assign(X, c(1), d.bottom()));
        assertEquals(d.bottom(), assume(op(X, BinOp.Lt, c(1)), d.bottom()));
    }

    // --- Assume

    @Test void eachComparisonWithAConstant() {
        IntervalState post = env("x", i(0, 10));
        assertEquals(env("x", i(0, 4)), assume(op(X, BinOp.Lt, c(5)), post));
        assertEquals(env("x", i(0, 5)), assume(op(X, BinOp.Le, c(5)), post));
        assertEquals(env("x", i(6, 10)), assume(op(X, BinOp.Gt, c(5)), post));
        assertEquals(env("x", i(5, 10)), assume(op(X, BinOp.Ge, c(5)), post));
        assertEquals(env("x", i(5, 5)), assume(op(X, BinOp.Eq, c(5)), post));
        assertEquals(post, assume(op(X, BinOp.Ne, c(5)), post)); // a hole in the middle is not expressible
    }

    @Test void anImpossibleComparisonIsBottom() {
        assertEquals(d.bottom(), assume(op(X, BinOp.Gt, c(10)), env("x", i(0, 10))));
        assertEquals(d.bottom(), assume(op(X, BinOp.Ne, c(3)), env("x", i(3, 3))));
    }

    @Test void notEqualTrimsAnEnd() {
        assertEquals(env("x", i(6, 9)), assume(op(X, BinOp.Ne, c(5)), env("x", i(5, 9))));
        assertEquals(env("x", i(5, 8)), assume(op(X, BinOp.Ne, c(9)), env("x", i(5, 9))));
    }

    @Test void comparingTwoLocalsRefinesBoth() {
        // x < y with x in [0,10], y in [0,5]: x ≤ 5 - 1 = 4, then y ≥ 0 + 1 = 1
        assertEquals(env("x", i(0, 4), "y", i(1, 5)), assume(op(X, BinOp.Lt, Y), env("x", i(0, 10), "y", i(0, 5))));
    }

    @Test void aCompoundSideIsRefinedToo() {
        // x + 1 < 5 with x free: x + 1 ≤ 4, so x ≤ 3
        assertEquals(env("x", new Interval(NEG, b(3))), assume(op(op(X, BinOp.Add, c(1)), BinOp.Lt, c(5)), d.top()));
    }

    // --- The lattice

    @Test void bottomAndTop() {
        assertTrue(d.isBottom(d.bottom()));
        assertFalse(d.isBottom(d.top()));
        assertEquals(d.top(), env("x", Interval.TOP)); // an unconstrained local is the same as an absent one
    }

    @Test void entailment() {
        assertTrue(d.entails(d.bottom(), d.top()));
        assertFalse(d.entails(d.top(), d.bottom()));
        assertTrue(d.entails(env("x", i(2, 3)), env("x", i(0, 5))));
        assertFalse(d.entails(env("x", i(0, 5)), env("x", i(2, 3))));
        assertTrue(d.entails(env("x", i(0, 1), "y", i(0, 1)), env("x", i(0, 5)))); // extra constraints are fine
        assertFalse(d.entails(env("y", i(0, 1)), env("x", i(0, 5))));              // x free on the left
    }

    @Test void joinCoversBoth() {
        assertEquals(env("x", i(0, 9)), d.join(env("x", i(0, 3)), env("x", i(7, 9))));
        assertEquals(d.top(), d.join(env("x", i(0, 3)), env("y", i(0, 3))));      // free on one side: free
        assertEquals(env("x", i(0, 3)), d.join(d.bottom(), env("x", i(0, 3))));
    }

    @Test void widenSendsAGrowingBoundToInfinity() {
        assertEquals(env("x", new Interval(b(0), POS)), d.widen(env("x", i(0, 1)), env("x", i(0, 2))));
        assertEquals(env("x", new Interval(NEG, b(5))), d.widen(env("x", i(0, 5)), env("x", i(-1, 5))));
        assertEquals(env("x", i(0, 5)), d.widen(env("x", i(0, 5)), env("x", i(1, 4)))); // shrinking: stable
    }

    @Test void widenContainsTheJoin() {
        IntervalState a = env("x", i(0, 1), "y", i(3, 3)), n = env("x", i(-2, 4), "y", i(3, 3));
        assertTrue(d.entails(d.join(a, n), d.widen(a, n)));
    }
}
