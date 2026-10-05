package pag.domains.ref.interval;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import pag.api.BinOp;
import pag.api.Domain;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.Step;
import pag.domains.ref.interval.IntervalState.Bottom;
import pag.domains.ref.interval.IntervalState.Env;

/**
 * The reference interval domain (implementation_strategy.md Phase 3): one
 * interval per local, no relations between locals.
 *
 * <p>The analysis runs BACKWARD. A state at a location holds the program states
 * there that may still reach the target. transfer(step, post) takes the state
 * AFTER the step and returns the state BEFORE it.
 */
public final class IntervalDomain implements Domain<IntervalState> {

    @Override public String name() { return "ref-interval"; }
    @Override public IntervalState top() { return IntervalState.TOP; }
    @Override public IntervalState bottom() { return IntervalState.BOTTOM; }
    @Override public boolean isBottom(IntervalState s) { return s instanceof Bottom; }

    /** Every local b constrains is constrained at least as tightly in a. */
    @Override
    public boolean entails(IntervalState a, IntervalState b) {
        if (a instanceof Bottom) return true;
        if (!(b instanceof Env eb)) return false;
        Env ea = (Env) a;
        return eb.at().entrySet().stream().allMatch(e -> ea.get(e.getKey()).within(e.getValue()));
    }

    /** Local by local, the smallest interval covering both; a local free on either side stays free. */
    @Override
    public IntervalState join(IntervalState a, IntervalState b) {
        if (a instanceof Bottom) return b;
        if (b instanceof Bottom) return a;
        Env ea = (Env) a, eb = (Env) b;
        Map<String, Interval> m = new HashMap<>();
        ea.at().forEach((x, i) -> { if (eb.at().containsKey(x)) m.put(x, i.hull(eb.get(x))); });
        return new Env(m);
    }

    /** Any bound of next that moved outward past prev's becomes infinite. Contains join(prev, next). */
    @Override
    public IntervalState widen(IntervalState prev, IntervalState next) {
        if (prev instanceof Bottom) return next;
        if (next instanceof Bottom) return prev;
        Env ep = (Env) prev, en = (Env) next;
        Map<String, Interval> m = new HashMap<>();
        ep.at().forEach((x, p) -> {
            if (!en.at().containsKey(x)) return;
            Interval n = en.get(x);
            Bound lo = Bound.compare(n.lo(), p.lo()) < 0 ? Bound.NEG_INF : p.lo();
            Bound hi = Bound.compare(n.hi(), p.hi()) > 0 ? Bound.POS_INF : p.hi();
            m.put(x, new Interval(lo, hi));
        });
        return new Env(m);
    }

    @Override
    public IntervalState transfer(Step step, IntervalState post) {
        if (!(post instanceof Env env)) return post; // nothing after means nothing before
        return switch (step) {
            // x := e: afterwards x holds e's value from before, and nothing else changed.
            // So before, x is free and e must evaluate within what post requires of x.
            case Step.Assign a -> restrict(a.source(), env.get(a.target().name()), env.without(a.target().name()));
            // assume c: nothing changes; only states where c holds get through.
            case Step.Assume a -> assume(a.cond(), env);
            // x := randInt() (or any call): x could be anything before; nothing else changes.
            case Step.Call c -> c.target().map(t -> (IntervalState) env.without(t.name())).orElse(env);
        };
    }

    /** The values e can take in s. */
    static Interval range(RVal e, Env s) {
        return switch (e) {
            case RVal.IntConst c -> Interval.of(c.v());
            case LVal.Local l -> s.get(l.name());
            case RVal.Binop b when b.op() == BinOp.Add -> range(b.l(), s).plus(range(b.r(), s));
            case RVal.Binop b when b.op() == BinOp.Sub -> range(b.l(), s).minus(range(b.r(), s));
            case RVal.Binop b when b.op() == BinOp.Mult -> range(b.l(), s).times(range(b.r(), s));
            default -> Interval.TOP;
        };
    }

    /** s narrowed so that e evaluates within target; Bottom if it cannot. */
    static IntervalState restrict(RVal e, Interval target, IntervalState state) {
        if (!(state instanceof Env s)) return state;
        return switch (e) {
            case RVal.IntConst c -> target.contains(c.v()) ? s : IntervalState.BOTTOM;
            case LVal.Local l -> s.meet(l.name(), target);
            case RVal.Binop b when b.op() == BinOp.Add -> {
                IntervalState s1 = restrict(b.l(), target.minus(range(b.r(), s)), s);
                yield s1 instanceof Env e1 ? restrict(b.r(), target.minus(range(b.l(), e1)), e1) : s1;
            }
            case RVal.Binop b when b.op() == BinOp.Sub -> {
                IntervalState s1 = restrict(b.l(), target.plus(range(b.r(), s)), s);
                yield s1 instanceof Env e1 ? restrict(b.r(), range(b.l(), e1).minus(target), e1) : s1;
            }
            case RVal.Binop b when b.op() == BinOp.Mult && b.r() instanceof RVal.IntConst c ->
                    restrictFactor(b.l(), c.v(), target, s);
            case RVal.Binop b when b.op() == BinOp.Mult && b.l() instanceof RVal.IntConst c ->
                    restrictFactor(b.r(), c.v(), target, s);
            default -> s; // e.g. a product of two locals: no refinement, which is sound
        };
    }

    /** s narrowed so that x * c lies within target. */
    private static IntervalState restrictFactor(RVal x, BigInteger c, Interval target, Env s) {
        if (c.signum() == 0) return target.contains(BigInteger.ZERO) ? s : IntervalState.BOTTOM;
        // x * c in [lo, hi]  ⇔  x in [⌈lo/c⌉, ⌊hi/c⌋] for c > 0, with the ends swapped for c < 0
        Bound lo = c.signum() > 0 ? divide(target.lo(), c, RoundingMode.CEILING) : divide(target.hi(), c, RoundingMode.CEILING);
        Bound hi = c.signum() > 0 ? divide(target.hi(), c, RoundingMode.FLOOR) : divide(target.lo(), c, RoundingMode.FLOOR);
        Optional<Interval> i = Interval.make(lo, hi);
        return i.isEmpty() ? IntervalState.BOTTOM : restrict(x, i.get(), s);
    }

    private static Bound divide(Bound b, BigInteger c, RoundingMode mode) {
        if (b instanceof Bound.Fin f) {
            return Bound.of(new java.math.BigDecimal(f.n()).divide(new java.math.BigDecimal(c), 0, mode).toBigIntegerExact());
        }
        boolean flips = c.signum() < 0;
        return (b instanceof Bound.NegInf) != flips ? Bound.NEG_INF : Bound.POS_INF;
    }

    /** env restricted to the states where the comparison holds. */
    static IntervalState assume(RVal cond, Env env) {
        if (!(cond instanceof RVal.Binop c)) return env;
        RVal l = c.l(), r = c.r();
        BigInteger one = BigInteger.ONE;
        return switch (c.op()) {
            case Lt -> below(l, r, one, env);       // l ≤ r - 1
            case Le -> below(l, r, BigInteger.ZERO, env);
            case Gt -> below(r, l, one, env);
            case Ge -> below(r, l, BigInteger.ZERO, env);
            case Eq -> {
                IntervalState s1 = restrict(l, range(r, env), env);
                yield s1 instanceof Env e1 ? restrict(r, range(l, e1), e1) : s1;
            }
            case Ne -> notEqual(l, r, env);
            case Add, Sub, Mult -> env;
        };
    }

    /** l ≤ r - gap: l at most r's maximum minus gap, r at least l's minimum plus gap. */
    private static IntervalState below(RVal l, RVal r, BigInteger gap, Env env) {
        Bound gapB = Bound.of(gap);
        Interval rr = range(r, env);
        IntervalState s1 = restrict(l, new Interval(Bound.NEG_INF, Bound.add(rr.hi(), Bound.negate(gapB))), env);
        if (!(s1 instanceof Env e1)) return s1;
        Interval lr = range(l, e1);
        return restrict(r, new Interval(Bound.add(lr.lo(), gapB), Bound.POS_INF), e1);
    }

    /** l ≠ r: only a constant side can be excluded, and only from an end of the other's interval. */
    private static IntervalState notEqual(RVal l, RVal r, Env env) {
        Optional<BigInteger> rc = range(r, env).single();
        if (rc.isPresent()) return trim(l, rc.get(), env);
        Optional<BigInteger> lc = range(l, env).single();
        return lc.isPresent() ? trim(r, lc.get(), env) : env;
    }

    private static IntervalState trim(RVal e, BigInteger c, Env env) {
        Interval i = range(e, env);
        Bound cb = Bound.of(c);
        if (i.lo().equals(cb) && i.hi().equals(cb)) return IntervalState.BOTTOM;
        if (i.lo().equals(cb)) return restrict(e, new Interval(Bound.of(c.add(BigInteger.ONE)), i.hi()), env);
        if (i.hi().equals(cb)) return restrict(e, new Interval(i.lo(), Bound.of(c.subtract(BigInteger.ONE))), env);
        return env;
    }
}
