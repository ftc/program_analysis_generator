package pag.domains.mut.addoffbyone;

import java.math.BigInteger;

/**
 * One end of an interval: minus infinity, a finite integer, or plus infinity,
 * ordered NegInf < Fin(n) < PosInf. Every value is a mathematical integer, so
 * nothing overflows.
 */
public sealed interface Bound {

    record NegInf() implements Bound {
        @Override public String toString() { return "-∞"; }
    }
    record PosInf() implements Bound {
        @Override public String toString() { return "+∞"; }
    }
    record Fin(BigInteger n) implements Bound {
        @Override public String toString() { return n.toString(); }
    }

    Bound NEG_INF = new NegInf();
    Bound POS_INF = new PosInf();

    static Bound of(BigInteger n) { return new Fin(n); }

    static int compare(Bound a, Bound b) {
        if (a instanceof Fin x && b instanceof Fin y) return x.n().compareTo(y.n());
        return Integer.compare(rank(a), rank(b)); // equal infinities compare equal
    }

    private static int rank(Bound b) {
        return switch (b) {
            case NegInf x -> 0;
            case Fin x -> 1;
            case PosInf x -> 2;
        };
    }

    static Bound min(Bound a, Bound b) { return compare(a, b) <= 0 ? a : b; }
    static Bound max(Bound a, Bound b) { return compare(a, b) >= 0 ? a : b; }

    /** a + b. Never called with opposite infinities: interval code adds lo to lo and hi to hi. */
    static Bound add(Bound a, Bound b) {
        if (a instanceof Fin x && b instanceof Fin y) return of(x.n().add(y.n()));
        if (a instanceof NegInf || b instanceof NegInf) return NEG_INF;
        return POS_INF;
    }

    static Bound negate(Bound b) {
        return switch (b) {
            case NegInf x -> POS_INF;
            case PosInf x -> NEG_INF;
            case Fin x -> of(x.n().negate());
        };
    }
}
