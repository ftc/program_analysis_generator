package pag.domains.gen;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

import static org.junit.jupiter.api.Assertions.*;

class LinearReachabilityDomainTest {

    private static final LinearReachabilityDomain domain = new LinearReachabilityDomain();

    private static LVal.Local var(String name) {
        return new LVal.Local(name, "int");
    }

    private static RVal.IntConst intConst(int v) {
        return new RVal.IntConst(BigInteger.valueOf(v));
    }

    private static RVal.Binop binop(RVal l, BinOp op, RVal r) {
        return new RVal.Binop(l, op, r);
    }

    private static RVal eq(String v, int c) {
        return binop(var(v), BinOp.Eq, intConst(c));
    }

    private static Step.Assume assume(RVal cond) {
        return new Step.Assume(cond);
    }

    private static MethodId randMethod() {
        return new MethodId("pag.probe.Rand", "randInt", List.of(), "java.math.BigInteger");
    }

    @Test
    void topBottomBasics() {
        State top = domain.top();
        State bottom = domain.bottom();

        assertTrue(domain.isBottom(bottom));
        assertFalse(domain.isBottom(top));

        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(top, top));
        assertFalse(domain.entails(top, bottom));

        assertTrue(domain.entails(top, domain.join(top, bottom)));
    }

    @Test
    void assumeTrueFalse() {
        State x1 = domain.transfer(assume(eq("x", 1)), domain.top());
        assertFalse(domain.isBottom(x1));

        State x2 = domain.transfer(assume(eq("x", 2)), x1);
        assertTrue(domain.isBottom(x2));

        State xNe1 = domain.transfer(assume(binop(var("x"), BinOp.Ne, intConst(1))), x1);
        assertTrue(domain.isBottom(xNe1));
    }

    @Test
    void assignConstantRefutes() {
        State x1 = domain.transfer(assume(eq("x", 1)), domain.top());

        State preConst = domain.transfer(new Step.Assign(var("x"), intConst(2)), x1);
        assertTrue(domain.isBottom(preConst));

        State preVar = domain.transfer(new Step.Assign(var("x"), var("y")), x1);

        State y1 = domain.transfer(assume(eq("y", 1)), domain.top());
        State y2 = domain.transfer(assume(eq("y", 2)), domain.top());

        assertTrue(domain.entails(preVar, y1));
        assertFalse(domain.entails(preVar, y2));
    }

    @Test
    void callProjectsTarget() {
        State xy = domain.transfer(
                assume(eq("x", 1)),
                domain.transfer(assume(eq("y", 2)), domain.top())
        );

        State pre = domain.transfer(
                new Step.Call(Optional.of(var("x")), randMethod(), List.of()),
                xy
        );

        State y2 = domain.transfer(assume(eq("y", 2)), domain.top());
        State y3 = domain.transfer(assume(eq("y", 3)), domain.top());

        assertTrue(domain.entails(pre, y2));
        assertFalse(domain.entails(pre, y3));
    }

    @Test
    void joinWidenAdmitUnion() {
        State a = domain.transfer(assume(eq("x", 1)), domain.top());
        State b = domain.transfer(assume(eq("x", 2)), domain.top());

        State j = domain.join(a, b);
        assertTrue(domain.entails(a, j));
        assertTrue(domain.entails(b, j));

        State w = domain.widen(a, b);
        assertTrue(domain.entails(a, w));
        assertTrue(domain.entails(b, w));
    }

    @Test
    void randomSoundness() {
        Random rnd = new Random(12345);
        String[] vars = {"x", "y", "z"};

        for (int i = 0; i < 300; i++) {
            Map<String, BigInteger> before = new HashMap<>();
            for (String v : vars) {
                before.put(v, randomInt(rnd, 10));
            }

            State post = domain.top();
            int nAssumes = rnd.nextInt(4);
            for (int j = 0; j < nAssumes; j++) {
                RVal cond = genRVal(rnd, 2, true);
                post = domain.transfer(new Step.Assume(cond), post);
                if (domain.isBottom(post)) {
                    break;
                }
            }

            int kind = rnd.nextInt(3);
            if (kind == 0) {
                String target = vars[rnd.nextInt(vars.length)];
                RVal src = genRVal(rnd, 2, false);
                Map<String, BigInteger> after = new HashMap<>(before);
                after.put(target, eval(src, before));

                State pre = domain.transfer(new Step.Assign(var(target), src), post);
                if (domain.admits(post, after)) {
                    assertTrue(domain.admits(pre, before), "Assign soundness failed");
                }
            } else if (kind == 1) {
                RVal cond = genRVal(rnd, 2, true);
                State pre = domain.transfer(new Step.Assume(cond), post);
                if (eval(cond, before).signum() != 0 && domain.admits(post, before)) {
                    assertTrue(domain.admits(pre, before), "Assume soundness failed");
                }
            } else {
                String target = vars[rnd.nextInt(vars.length)];
                Map<String, BigInteger> after = new HashMap<>(before);
                after.put(target, randomInt(rnd, 10));

                State pre = domain.transfer(
                        new Step.Call(Optional.of(var(target)), randMethod(), List.of()),
                        post
                );
                if (domain.admits(post, after)) {
                    assertTrue(domain.admits(pre, before), "Call soundness failed");
                }
            }
        }
    }

    private static BigInteger randomInt(Random r, int bound) {
        return BigInteger.valueOf(r.nextInt(2 * bound + 1) - bound);
    }

    private static RVal genRVal(Random r, int depth, boolean allowComparison) {
        if (depth <= 0 || r.nextInt(10) < 3) {
            return intConst(r.nextInt(11) - 5);
        }
        if (r.nextInt(10) < 4) {
            String[] vars = {"x", "y", "z"};
            return var(vars[r.nextInt(vars.length)]);
        }

        BinOp op;
        if (allowComparison && r.nextInt(10) < 4) {
            BinOp[] comps = {BinOp.Lt, BinOp.Le, BinOp.Gt, BinOp.Ge, BinOp.Eq, BinOp.Ne};
            op = comps[r.nextInt(comps.length)];
        } else {
            BinOp[] arith = {BinOp.Add, BinOp.Sub, BinOp.Mult};
            op = arith[r.nextInt(arith.length)];
        }

        RVal l = genRVal(r, depth - 1, allowComparison);
        RVal rr = genRVal(r, depth - 1, allowComparison);
        return new RVal.Binop(l, op, rr);
    }

    private static BigInteger eval(RVal r, Map<String, BigInteger> val) {
        if (r instanceof RVal.IntConst ic) {
            return ic.v();
        }
        if (r instanceof LVal.Local l) {
            return val.getOrDefault(l.name(), BigInteger.ZERO);
        }
        if (r instanceof LVal.StaticField sf) {
            return switch (sf.name()) {
                case "ZERO" -> BigInteger.ZERO;
                case "ONE" -> BigInteger.ONE;
                case "TWO" -> BigInteger.valueOf(2);
                case "TEN" -> BigInteger.valueOf(10);
                default -> BigInteger.ZERO;
            };
        }
        if (r instanceof RVal.Binop b) {
            BigInteger l = eval(b.l(), val);
            BigInteger rr = eval(b.r(), val);
            return switch (b.op()) {
                case Mult -> l.multiply(rr);
                case Add -> l.add(rr);
                case Sub -> l.subtract(rr);
                case Lt -> l.compareTo(rr) < 0 ? BigInteger.ONE : BigInteger.ZERO;
                case Le -> l.compareTo(rr) <= 0 ? BigInteger.ONE : BigInteger.ZERO;
                case Gt -> l.compareTo(rr) > 0 ? BigInteger.ONE : BigInteger.ZERO;
                case Ge -> l.compareTo(rr) >= 0 ? BigInteger.ONE : BigInteger.ZERO;
                case Eq -> l.compareTo(rr) == 0 ? BigInteger.ONE : BigInteger.ZERO;
                case Ne -> l.compareTo(rr) != 0 ? BigInteger.ONE : BigInteger.ZERO;
            };
        }
        throw new IllegalStateException("unknown rvalue");
    }
}
