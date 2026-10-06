package pag.domains.mut.addoffbyone;

import java.math.BigInteger;
import java.util.Optional;

/**
 * [lo, hi] with lo ≤ hi: the integers between, inclusive. lo is never +∞ and hi
 * never -∞. An empty interval is never built; operations that would produce one
 * return Optional.empty() instead, and the domain turns that into Bottom.
 */
public record Interval(Bound lo, Bound hi) {

    public static final Interval TOP = new Interval(Bound.NEG_INF, Bound.POS_INF);

    public Interval {
        if (lo instanceof Bound.PosInf || hi instanceof Bound.NegInf || Bound.compare(lo, hi) > 0) {
            throw new IllegalArgumentException("empty interval [" + lo + ", " + hi + "]");
        }
    }

    public static Interval of(BigInteger n) { return new Interval(Bound.of(n), Bound.of(n)); }

    /** [lo, hi], or empty when lo > hi. */
    public static Optional<Interval> make(Bound lo, Bound hi) {
        if (lo instanceof Bound.PosInf || hi instanceof Bound.NegInf || Bound.compare(lo, hi) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Interval(lo, hi));
    }

    public boolean contains(BigInteger n) {
        Bound b = Bound.of(n);
        return Bound.compare(lo, b) <= 0 && Bound.compare(b, hi) <= 0;
    }

    public boolean within(Interval o) {
        return Bound.compare(o.lo, lo) <= 0 && Bound.compare(hi, o.hi) <= 0;
    }

    public Interval hull(Interval o) {
        return new Interval(Bound.min(lo, o.lo), Bound.max(hi, o.hi));
    }

    public Optional<Interval> meet(Interval o) {
        return make(Bound.max(lo, o.lo), Bound.min(hi, o.hi));
    }

    public Interval plus(Interval o) { return new Interval(Bound.add(lo, o.lo), Bound.add(hi, o.hi)); }

    public Interval negate() { return new Interval(Bound.negate(hi), Bound.negate(lo)); }

    public Interval minus(Interval o) { return plus(o.negate()); }

    /** Every product of a value in this and a value in o. Unbounded unless both are finite or one is [0,0]. */
    public Interval times(Interval o) {
        if (isZero() || o.isZero()) return of(BigInteger.ZERO);
        if (!(lo instanceof Bound.Fin a && hi instanceof Bound.Fin b
                && o.lo instanceof Bound.Fin c && o.hi instanceof Bound.Fin d)) {
            return TOP;
        }
        BigInteger[] ps = {a.n().multiply(c.n()), a.n().multiply(d.n()), b.n().multiply(c.n()), b.n().multiply(d.n())};
        BigInteger min = ps[0], max = ps[0];
        for (BigInteger p : ps) { min = min.min(p); max = max.max(p); }
        return new Interval(Bound.of(min), Bound.of(max));
    }

    private boolean isZero() { return equals(of(BigInteger.ZERO)); }

    /** [lo,hi], with a round bracket at an infinite end: (-∞,-2], [0,5], (-∞,+∞). */
    @Override
    public String toString() {
        return (lo instanceof Bound.Fin ? "[" : "(") + lo + "," + hi + (hi instanceof Bound.Fin ? "]" : ")");
    }

    /** The single value, if this interval holds exactly one. */
    public Optional<BigInteger> single() {
        return lo instanceof Bound.Fin a && lo.equals(hi) ? Optional.of(a.n()) : Optional.empty();
    }
}
