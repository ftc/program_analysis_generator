package pag.domains.gen;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pag.api.*;

import static org.junit.jupiter.api.Assertions.*;

class LinearCellDomainTest {

    private static final LinearCellDomain DOMAIN = new LinearCellDomain();

    private static LVal.Local var(String name) {
        return new LVal.Local(name, "int");
    }

    private static RVal.IntConst intConst(BigInteger v) {
        return new RVal.IntConst(v);
    }

    private static RVal.Binop binop(RVal l, BinOp op, RVal r) {
        return new RVal.Binop(l, op, r);
    }

    private static MethodId randInt() {
        return new MethodId("pag.probe.Rand", "randInt", List.<String>of(), "int");
    }

    @Test
    void topAndBottomBasics() {
        assertFalse(DOMAIN.isBottom(DOMAIN.top()));
        assertTrue(DOMAIN.isBottom(DOMAIN.bottom()));

        assertTrue(DOMAIN.entails(DOMAIN.bottom(), DOMAIN.top()));
        assertFalse(DOMAIN.entails(DOMAIN.top(), DOMAIN.bottom()));

        State join = DOMAIN.join(DOMAIN.bottom(), DOMAIN.top());
        assertFalse(DOMAIN.isBottom(join));
        assertTrue(DOMAIN.entails(DOMAIN.top(), join));
    }

    @Test
    void assumeFalseConstant() {
        State s = DOMAIN.transfer(new Step.Assume(intConst(BigInteger.ZERO)), DOMAIN.top());
        assertTrue(DOMAIN.isBottom(s));
    }

    @Test
    void assumeTrueConstant() {
        State s = DOMAIN.transfer(new Step.Assume(intConst(BigInteger.ONE)), DOMAIN.top());
        assertFalse(DOMAIN.isBottom(s));
    }

    @Test
    void constantAssignmentProof() {
        State s = DOMAIN.top();
        s = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Lt, intConst(BigInteger.ZERO))), s);
        s = DOMAIN.transfer(new Step.Assign(var("x"), intConst(BigInteger.valueOf(5))), s);
        assertTrue(DOMAIN.isBottom(s));
    }

    @Test
    void linearAssignmentProof() {
        State post = DOMAIN.top();
        post = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.valueOf(5)))), post);

        State pre = DOMAIN.transfer(
                new Step.Assign(var("x"), binop(var("y"), BinOp.Add, intConst(BigInteger.ONE))),
                post
        );

        State expected = DOMAIN.transfer(
                new Step.Assume(binop(var("y"), BinOp.Le, intConst(BigInteger.valueOf(4)))),
                DOMAIN.top()
        );
        assertTrue(DOMAIN.entails(pre, expected));

        State tooTight = DOMAIN.transfer(
                new Step.Assume(binop(var("y"), BinOp.Le, intConst(BigInteger.valueOf(3)))),
                DOMAIN.top()
        );
        assertFalse(DOMAIN.entails(pre, tooTight));
    }

    @Test
    void linearEqualityProof() {
        State s = DOMAIN.top();
        s = DOMAIN.transfer(new Step.Assume(
                binop(binop(var("x"), BinOp.Add, var("y")), BinOp.Eq, intConst(BigInteger.ZERO))
        ), s);
        s = DOMAIN.transfer(new Step.Assign(var("y"), intConst(BigInteger.ONE)), s);
        s = DOMAIN.transfer(new Step.Assign(var("x"), intConst(BigInteger.ONE)), s);
        assertTrue(DOMAIN.isBottom(s));
    }

    @Test
    void assumeInfeasibleLinear() {
        State s = DOMAIN.top();
        s = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.ZERO))), s);
        s = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Ge, intConst(BigInteger.ONE))), s);
        assertTrue(DOMAIN.isBottom(s));
    }

    @Test
    void callProjection() {
        State post = DOMAIN.top();
        post = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.valueOf(5)))), post);

        State pre = DOMAIN.transfer(
                new Step.Call(Optional.of(var("x")), randInt(), List.<RVal>of()),
                post
        );

        assertFalse(DOMAIN.isBottom(pre));
        assertTrue(DOMAIN.entails(pre, DOMAIN.top()));

        State preBottom = DOMAIN.transfer(
                new Step.Call(Optional.of(var("x")), randInt(), List.<RVal>of()),
                DOMAIN.bottom()
        );
        assertTrue(DOMAIN.isBottom(preBottom));
    }

    @Test
    void joinEntailsInputsButNotConverse() {
        State a = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.valueOf(5)))), DOMAIN.top());
        State b = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Ge, intConst(BigInteger.valueOf(5)))), DOMAIN.top());

        State j = DOMAIN.join(a, b);
        assertFalse(DOMAIN.isBottom(j));

        assertTrue(DOMAIN.entails(a, j));
        assertTrue(DOMAIN.entails(b, j));
        assertFalse(DOMAIN.entails(j, a));
    }

    @Test
    void widenEntailsInputs() {
        State a = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.valueOf(5)))), DOMAIN.top());
        State b = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Ge, intConst(BigInteger.valueOf(5)))), DOMAIN.top());

        State w = DOMAIN.widen(a, b);
        assertTrue(DOMAIN.entails(a, w));
        assertTrue(DOMAIN.entails(b, w));
    }

    @Test
    void nonlinearAssumeSound() {
        State s = DOMAIN.top();
        s = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Eq, intConst(BigInteger.ONE))), s);
        s = DOMAIN.transfer(new Step.Assume(binop(var("y"), BinOp.Eq, intConst(BigInteger.ZERO))), s);
        s = DOMAIN.transfer(new Step.Assume(
                binop(binop(var("x"), BinOp.Mult, var("y")), BinOp.Ne, intConst(BigInteger.ZERO))
        ), s);
        assertTrue(DOMAIN.isBottom(s));
    }

    @Test
    void staticFieldConst() {
        State s = DOMAIN.top();
        s = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Lt, intConst(BigInteger.valueOf(10)))), s);
        s = DOMAIN.transfer(
                new Step.Assign(var("x"), new LVal.StaticField("java.math.BigInteger", "TEN")),
                s
        );
        assertTrue(DOMAIN.isBottom(s));
    }

    @Test
    void neDisjunction() {
        State ne = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Ne, intConst(BigInteger.ZERO))), DOMAIN.top());

        State neg = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.valueOf(-1)))), DOMAIN.top());
        State pos = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Ge, intConst(BigInteger.ONE))), DOMAIN.top());

        State j = DOMAIN.join(neg, pos);
        assertTrue(DOMAIN.entails(ne, j));
        assertTrue(DOMAIN.entails(j, ne));
    }

    @Test
    void topDoesNotEntailNonTop() {
        State a = DOMAIN.transfer(new Step.Assume(binop(var("x"), BinOp.Le, intConst(BigInteger.ONE))), DOMAIN.top());
        assertFalse(DOMAIN.entails(DOMAIN.top(), a));
    }
}
