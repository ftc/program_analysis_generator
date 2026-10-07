package pag.domains.ref.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pag.domains.ref.sign.Sign.NEG;
import static pag.domains.ref.sign.Sign.POS;
import static pag.domains.ref.sign.Sign.ZERO;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pag.api.BinOp;
import pag.api.LVal;
import pag.api.MethodId;
import pag.api.RVal;
import pag.api.Step;

/** Backward transfer and the lattice, each answer worked out by hand. */
class SignDomainTest {

    private final SignDomain d = new SignDomain();
    private static final LVal.Local X = local("x"), Y = local("y");

    private static LVal.Local local(String n) { return new LVal.Local(n, "java.math.BigInteger"); }
    private static RVal.IntConst c(long n) { return new RVal.IntConst(BigInteger.valueOf(n)); }
    private static RVal op(RVal l, BinOp o, RVal r) { return new RVal.Binop(l, o, r); }
    private static Set<Sign> of(Sign... s) { return s.length == 0 ? EnumSet.noneOf(Sign.class) : EnumSet.of(s[0], s); }

    /** env("x", of(POS), "y", of(NEG, ZERO)): x is positive, y is not. */
    private static SignState env(Object... pairs) {
        var m = new HashMap<String, Set<Sign>>();
        for (int k = 0; k < pairs.length; k += 2) {
            @SuppressWarnings("unchecked") Set<Sign> signs = (Set<Sign>) pairs[k + 1];
            m.put((String) pairs[k], signs);
        }
        return new SignState.Env(m);
    }

    private SignState assign(LVal.Local x, RVal e, SignState post) { return d.transfer(new Step.Assign(x, e), post); }
    private SignState assume(RVal cond, SignState post) { return d.transfer(new Step.Assume(cond), post); }

    // --- Assignment

    @Test void assignAConstantWithAnAllowedSign() {
        // x := 5 with x > 0 afterwards: always true, so before anything goes
        assertEquals(d.top(), assign(X, c(5), env("x", of(POS))));
        assertEquals(d.top(), assign(X, c(0), env("x", of(NEG, ZERO))));
    }

    @Test void assignAConstantWithASignNotAllowed() {
        // x := 5 with x < 0 afterwards: never, so no state before reaches the target
        assertEquals(d.bottom(), assign(X, c(5), env("x", of(NEG))));
    }

    @Test void aCopyNarrowsItsSource() {
        // x := y with x > 0 afterwards, y in {0,+} before the step: y must have been positive
        assertEquals(env("y", of(POS)), assign(X, Y, env("x", of(POS), "y", of(ZERO, POS))));
    }

    @Test void arithmeticIsCheckedButItsOperandsAreNotNarrowed() {
        // x := y + 1 with x < 0 afterwards: y + 1 can be negative (y = -5), so nothing is ruled out,
        // and y is left free rather than narrowed to a guess
        assertEquals(d.top(), assign(X, op(Y, BinOp.Add, c(1)), env("x", of(NEG))));
        // x := y * y with y positive and x < 0 afterwards: a positive times a positive is never negative
        assertEquals(d.bottom(), assign(X, op(Y, BinOp.Mult, Y), env("x", of(NEG), "y", of(POS))));
        // x := y - y can be anything for y positive (as signs know it), so x < 0 is possible
        assertEquals(env("y", of(POS)), assign(X, op(Y, BinOp.Sub, Y), env("x", of(NEG), "y", of(POS))));
    }

    @Test void anUnconstrainedTargetConstrainsNothing() {
        assertEquals(env("y", of(POS)), assign(X, c(-3), env("y", of(POS))));
    }

    @Test void randIntFreesItsTarget() {
        Step call = new Step.Call(Optional.of(X),
                new MethodId("pag.probe.Rand", "randInt", List.of(), "java.math.BigInteger"), List.of());
        assertEquals(env("y", of(NEG)), d.transfer(call, env("x", of(POS), "y", of(NEG))));
    }

    @Test void bottomStaysBottom() {
        assertEquals(d.bottom(), assign(X, c(1), d.bottom()));
        assertEquals(d.bottom(), assume(op(X, BinOp.Lt, c(0)), d.bottom()));
    }

    // --- Assume

    @Test void eachComparisonWithZero() {
        assertEquals(env("x", of(NEG)), assume(op(X, BinOp.Lt, c(0)), d.top()));
        assertEquals(env("x", of(NEG, ZERO)), assume(op(X, BinOp.Le, c(0)), d.top()));
        assertEquals(env("x", of(POS)), assume(op(X, BinOp.Gt, c(0)), d.top()));
        assertEquals(env("x", of(ZERO, POS)), assume(op(X, BinOp.Ge, c(0)), d.top()));
        assertEquals(env("x", of(ZERO)), assume(op(X, BinOp.Eq, c(0)), d.top()));
        assertEquals(env("x", of(NEG, POS)), assume(op(X, BinOp.Ne, c(0)), d.top()));
    }

    @Test void aComparisonWithANonZeroConstant() {
        // x > 3: x is positive. x < 3: x could still be anything with a sign (−5, 0, 2).
        assertEquals(env("x", of(POS)), assume(op(X, BinOp.Gt, c(3)), d.top()));
        assertEquals(d.top(), assume(op(X, BinOp.Lt, c(3)), d.top()));
    }

    @Test void anImpossibleComparisonIsBottom() {
        assertEquals(d.bottom(), assume(op(X, BinOp.Lt, c(0)), env("x", of(POS))));
        assertEquals(d.bottom(), assume(op(X, BinOp.Eq, c(0)), env("x", of(NEG, POS))));
    }

    @Test void comparingTwoLocalsNarrowsBoth() {
        // x < y with x in {0,+}: y must be bigger than something non-negative, so positive
        assertEquals(env("x", of(ZERO, POS), "y", of(POS)), assume(op(X, BinOp.Lt, Y), env("x", of(ZERO, POS))));
        // x < y with x positive and y not: impossible
        assertEquals(d.bottom(), assume(op(X, BinOp.Lt, Y), env("x", of(POS), "y", of(NEG, ZERO))));
    }

    // --- The lattice

    @Test void bottomAndTop() {
        assertTrue(d.isBottom(d.bottom()));
        assertFalse(d.isBottom(d.top()));
        assertEquals(d.top(), env("x", Sign.any())); // a local with every sign is the same as an absent one
    }

    @Test void entailment() {
        assertTrue(d.entails(d.bottom(), d.top()));
        assertFalse(d.entails(d.top(), d.bottom()));
        assertTrue(d.entails(env("x", of(POS)), env("x", of(ZERO, POS))));
        assertFalse(d.entails(env("x", of(ZERO, POS)), env("x", of(POS))));
        assertTrue(d.entails(env("x", of(POS), "y", of(NEG)), env("x", of(POS)))); // extra constraints are fine
        assertFalse(d.entails(env("y", of(NEG)), env("x", of(POS))));              // x free on the left
    }

    @Test void joinIsUnion() {
        assertEquals(env("x", of(NEG, POS)), d.join(env("x", of(NEG)), env("x", of(POS))));
        assertEquals(d.top(), d.join(env("x", of(NEG)), env("y", of(NEG)))); // free on one side: free
        assertEquals(env("x", of(ZERO)), d.join(d.bottom(), env("x", of(ZERO))));
    }

    @Test void widenIsJoin() {
        SignState a = env("x", of(NEG)), b = env("x", of(ZERO), "y", of(POS));
        assertEquals(d.join(a, b), d.widen(a, b));
    }

    @Test void statesPrintReadably() {
        assertEquals("⊥", d.bottom().toString());
        assertEquals("⊤", d.top().toString());
        assertEquals("x ∈ {−,+}, y ∈ {0}", env("y", of(ZERO), "x", of(POS, NEG)).toString());
    }
}
