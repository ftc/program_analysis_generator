package pag.domains.ref.sign;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import pag.api.BinOp;
import pag.api.Domain;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.Step;
import pag.domains.ref.sign.SignState.Bottom;
import pag.domains.ref.sign.SignState.Env;

/**
 * A reference sign domain: for each local, the set of signs it may have, and no
 * relations between locals.
 *
 * <p>The analysis runs BACKWARD. A state at a location holds the program states
 * there that may still reach the target. transfer(step, post) takes the state
 * AFTER the step and returns the state BEFORE it.
 *
 * <p>The rule throughout: narrow only what you can justify, and when unsure,
 * keep the state as it is. Keeping too much costs precision; dropping a state
 * that can reach the target is unsound.
 */
public final class SignDomain implements Domain<SignState> {

    @Override public String name() { return "ref-sign"; }
    @Override public SignState top() { return SignState.TOP; }
    @Override public SignState bottom() { return SignState.BOTTOM; }
    @Override public boolean isBottom(SignState s) { return s instanceof Bottom; }

    /** Every local b constrains has at most b's signs in a. */
    @Override
    public boolean entails(SignState a, SignState b) {
        if (a instanceof Bottom) return true;
        if (!(b instanceof Env eb)) return false;
        Env ea = (Env) a;
        return eb.at().entrySet().stream().allMatch(e -> e.getValue().containsAll(ea.get(e.getKey())));
    }

    /** Local by local, the union of signs; a local free on either side stays free. */
    @Override
    public SignState join(SignState a, SignState b) {
        if (a instanceof Bottom) return b;
        if (b instanceof Bottom) return a;
        Env ea = (Env) a, eb = (Env) b;
        Map<String, Set<Sign>> m = new HashMap<>();
        ea.at().forEach((x, signs) -> {
            if (eb.at().containsKey(x)) {
                Set<Sign> union = EnumSet.copyOf(signs);
                union.addAll(eb.get(x));
                m.put(x, union);
            }
        });
        return new Env(m);
    }

    /** There are only eight sets of signs, so joining alone always terminates: no widening needed. */
    @Override
    public SignState widen(SignState prev, SignState next) { return join(prev, next); }

    @Override
    public SignState transfer(Step step, SignState post) {
        if (!(post instanceof Env env)) return post; // nothing after means nothing before
        return switch (step) {
            // x := e: afterwards x holds e's value from before, and nothing else changed.
            // So before, x is free and e must have a sign post allows for x.
            case Step.Assign a -> restrict(a.source(), env.get(a.target().name()), env.without(a.target().name()));
            // assume c: nothing changes; only states where c can hold get through.
            case Step.Assume a -> assume(a.cond(), env);
            // x := randInt() (or any call): x could be anything before; nothing else changes.
            case Step.Call c -> c.target().map(t -> (SignState) env.without(t.name())).orElse(env);
        };
    }

    /** The signs e may have in s. */
    static Set<Sign> signs(RVal e, Env s) {
        return switch (e) {
            case RVal.IntConst c -> EnumSet.of(Sign.of(c.v()));
            case LVal.Local l -> s.get(l.name());
            case RVal.Binop b when b.op() == BinOp.Add || b.op() == BinOp.Sub || b.op() == BinOp.Mult -> {
                Set<Sign> out = EnumSet.noneOf(Sign.class);
                for (Sign x : signs(b.l(), s)) for (Sign y : signs(b.r(), s)) out.addAll(Sign.arithmetic(b.op(), x, y));
                yield out;
            }
            default -> Sign.any();
        };
    }

    /** s narrowed so that e has one of the allowed signs; Bottom if it cannot. */
    static SignState restrict(RVal e, Set<Sign> allowed, Env s) {
        return switch (e) {
            case RVal.IntConst c -> allowed.contains(Sign.of(c.v())) ? s : SignState.BOTTOM;
            case LVal.Local l -> s.meet(l.name(), allowed);
            // Anything else, e.g. y + 1: check that some allowed sign is possible, but do not
            // narrow the operands. Narrowing y here would take reasoning this domain does not do,
            // and narrowing without it could drop states that reach the target.
            default -> {
                Set<Sign> possible = signs(e, s);
                possible.retainAll(allowed);
                yield possible.isEmpty() ? SignState.BOTTOM : s;
            }
        };
    }

    /** env restricted to the states where the comparison can hold. */
    static SignState assume(RVal cond, Env env) {
        if (!(cond instanceof RVal.Binop c) || !isComparison(c.op())) return env;
        Set<Sign> left = EnumSet.noneOf(Sign.class), right = EnumSet.noneOf(Sign.class);
        for (Sign a : signs(c.l(), env))
            for (Sign b : signs(c.r(), env))
                if (Sign.canCompare(c.op(), a, b)) { left.add(a); right.add(b); }
        if (left.isEmpty()) return SignState.BOTTOM; // no pair of signs satisfies it
        SignState s1 = restrict(c.l(), left, env);
        return s1 instanceof Env e1 ? restrict(c.r(), right, e1) : s1;
    }

    private static boolean isComparison(BinOp op) {
        return switch (op) {
            case Lt, Le, Gt, Ge, Eq, Ne -> true;
            case Add, Sub, Mult -> false;
        };
    }
}
