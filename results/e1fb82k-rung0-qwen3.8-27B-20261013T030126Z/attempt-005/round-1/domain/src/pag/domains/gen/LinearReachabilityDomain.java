package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

/**
 * A disjunctive linear-integer abstract domain.
 *
 * <p>Each component is a conjunction of integer linear inequalities.  The domain
 * is deliberately sound-first: whenever a constraint or a projection cannot be
 * represented or decided, the analysis widens rather than excludes states.
 */
public class LinearReachabilityDomain implements Domain<LinearReachabilityDomain.State> {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger TEN = BigInteger.valueOf(10);

    private static final int MAX_STATE_COMPONENTS = 128;
    private static final int MAX_COMPONENT_INEQS = 80;
    private static final int MAX_FE_INEQS = 60;
    private static final int MAX_FE_VARS = 8;
    private static final int MAX_COMBINED_INEQS = 200;
    private static final int MAX_RATIONAL_BITS = 256;
    private static final int MAX_ENTAIL_THIS = 30;

    private static final class Abort extends RuntimeException {
        Abort(String message) {
            super(message);
        }
    }

    public LinearReachabilityDomain() {
    }

    /** Disjunction of linear components. */
    public static final class State {
        private final List<Component> comps;

        State(List<Component> comps) {
            this.comps = List.copyOf(comps);
        }

        List<Component> components() {
            return comps;
        }

        public boolean isTop() {
            for (Component c : comps) {
                if (c.isTop()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A conjunction of integer linear inequalities. */
    static final class Component {
        final List<Ineq> ineqs;
        final boolean bottom;
        private Integer bottomCache;

        Component(List<Ineq> ineqs) {
            this.ineqs = List.copyOf(ineqs);
            this.bottom = ineqs.stream().anyMatch(Ineq::isFalse);
        }

        private Component(List<Ineq> ineqs, boolean bottom) {
            this.ineqs = List.copyOf(ineqs);
            this.bottom = bottom;
        }

        static Component top() {
            return new Component(List.of());
        }

        static Component bottom() {
            return new Component(List.of(), true);
        }

        boolean isTop() {
            return !bottom && ineqs.isEmpty();
        }

        boolean isBottom() {
            if (bottom) {
                return true;
            }
            if (ineqs.isEmpty()) {
                return false;
            }
            if (bottomCache != null) {
                return bottomCache == 1;
            }
            if (ineqs.size() > MAX_FE_INEQS) {
                bottomCache = 0;
                return false;
            }
            Set<String> vars = new HashSet<>();
            for (Ineq i : ineqs) {
                vars.addAll(i.coeffs().keySet());
            }
            if (vars.size() > MAX_FE_VARS) {
                bottomCache = 0;
                return false;
            }
            Solver.Status st = Solver.unsat(ineqs);
            bottomCache = st == Solver.Status.UNSAT ? 1 : 0;
            return st == Solver.Status.UNSAT;
        }

        Component addIneq(Ineq ineq) {
            if (bottom) {
                return this;
            }
            if (ineq.isFalse()) {
                return bottom();
            }
            if (ineq.isTrue()) {
                return this;
            }
            if (ineqs.contains(ineq)) {
                return this;
            }
            if (ineqs.size() >= MAX_COMPONENT_INEQS) {
                return this;
            }
            List<Ineq> next = new ArrayList<>(ineqs);
            next.add(ineq);
            return new Component(next);
        }

        Component addIneq(Map<String, BigInteger> coeffs, BigInteger rhs) {
            return addIneq(Ineq.normalize(coeffs, rhs));
        }

        boolean entails(Component other) {
            if (this.isBottom()) {
                return true;
            }
            if (other.isBottom()) {
                return false;
            }
            if (other.isTop()) {
                return true;
            }
            if (this.isTop()) {
                return false;
            }
            if (ineqs.equals(other.ineqs)) {
                return true;
            }
            // This has at least all of other's explicit constraints.
            if (this.ineqs.containsAll(other.ineqs)) {
                return true;
            }
            if (ineqs.size() > MAX_ENTAIL_THIS || other.ineqs.size() > MAX_ENTAIL_THIS) {
                return false;
            }
            for (Ineq i : other.ineqs) {
                if (!entailsIneq(i)) {
                    return false;
                }
            }
            return true;
        }

        boolean entailsIneq(Ineq ineq) {
            if (this.isBottom()) {
                return true;
            }
            if (ineq.isFalse()) {
                return false;
            }
            if (ineq.isTrue()) {
                return true;
            }
            if (this.isTop()) {
                return false;
            }
            if (ineqs.contains(ineq)) {
                return true;
            }
            if (ineqs.size() > MAX_ENTAIL_THIS) {
                return false;
            }
            List<Ineq> all = new ArrayList<>(ineqs);
            all.add(ineq.negate());
            return Solver.unsat(all) == Solver.Status.UNSAT;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Component c)) {
                return false;
            }
            return bottom == c.bottom && ineqs.equals(c.ineqs);
        }

        @Override
        public int hashCode() {
            return bottom ? 1231 : ineqs.hashCode();
        }
    }

    /**
     * Integer linear inequality: sum(coeffs * var) <= rhs.
     *
     * <p>Normalization divides by the gcd of all coefficients and floors the
     * right-hand side.  This is exact over integer valuations.
     */
    static final class Ineq {
        final Map<String, BigInteger> coeffs;
        final BigInteger rhs;
        final boolean falseConst;
        final boolean trueConst;

        private Ineq(Map<String, BigInteger> coeffs, BigInteger rhs, boolean falseConst, boolean trueConst) {
            this.coeffs = Map.copyOf(coeffs);
            this.rhs = rhs;
            this.falseConst = falseConst;
            this.trueConst = trueConst;
        }

        static Ineq normalize(Map<String, BigInteger> coeffs, BigInteger rhs) {
            Map<String, BigInteger> c = new HashMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                if (e.getValue().signum() != 0) {
                    c.put(e.getKey(), e.getValue());
                }
            }
            if (c.isEmpty()) {
                if (rhs.signum() < 0) {
                    return new Ineq(Map.of(), BigInteger.valueOf(-1), true, false);
                }
                return new Ineq(Map.of(), ZERO, false, true);
            }

            BigInteger g = ONE;
            for (BigInteger v : c.values()) {
                g = g.gcd(v.abs());
            }
            if (g.compareTo(ONE) > 0) {
                Map<String, BigInteger> reduced = new HashMap<>();
                for (Map.Entry<String, BigInteger> e : c.entrySet()) {
                    reduced.put(e.getKey(), e.getValue().divide(g));
                }
                rhs = rhs.floorDiv(g);
                c = reduced;
            }
            return new Ineq(Map.copyOf(c), rhs, false, false);
        }

        static Ineq fromForm(LinearForm form, BigInteger bound) {
            return normalize(form.coeffs(), bound.subtract(form.constValue()));
        }

        boolean isFalse() {
            return falseConst;
        }

        boolean isTrue() {
            return trueConst;
        }

        Map<String, BigInteger> coeffs() {
            return coeffs;
        }

        BigInteger rhs() {
            return rhs;
        }

        boolean mentions(String var) {
            return coeffs.containsKey(var);
        }

        Ineq negate() {
            if (falseConst) {
                return new Ineq(Map.of(), ZERO, false, true);
            }
            if (trueConst) {
                return new Ineq(Map.of(), BigInteger.valueOf(-1), true, false);
            }
            Map<String, BigInteger> neg = new HashMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                neg.put(e.getKey(), e.getValue().negate());
            }
            // Integer negation of (sum <= rhs) is (sum >= rhs + 1),
            // i.e. (-sum <= -rhs - 1).
            return normalize(neg, rhs.negate().subtract(ONE));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Ineq i)) {
                return false;
            }
            return coeffs.equals(i.coeffs) && rhs.equals(i.rhs) && falseConst == i.falseConst && trueConst == i.trueConst;
        }

        @Override
        public int hashCode() {
            return Objects.hash(coeffs, rhs, falseConst, trueConst);
        }
    }

    /** Affine integer expression: sum(coeffs * var) + const. */
    static final class LinearForm {
        final Map<String, BigInteger> coeffs;
        final BigInteger constValue;

        private LinearForm(Map<String, BigInteger> coeffs, BigInteger constValue) {
            Map<String, BigInteger> clean = new HashMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                if (e.getValue().signum() != 0) {
                    clean.put(e.getKey(), e.getValue());
                }
            }
            this.coeffs = Map.copyOf(clean);
            this.constValue = constValue;
        }

        static LinearForm ofConst(BigInteger v) {
            return new LinearForm(Map.of(), v);
        }

        static LinearForm ofVar(String name) {
            Map<String, BigInteger> c = new HashMap<>();
            c.put(name, ONE);
            return new LinearForm(c, ZERO);
        }

        LinearForm add(LinearForm other) {
            Map<String, BigInteger> c = new HashMap<>(coeffs);
            for (Map.Entry<String, BigInteger> e : other.coeffs.entrySet()) {
                c.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
            return new LinearForm(c, constValue.add(other.constValue));
        }

        LinearForm subtract(LinearForm other) {
            Map<String, BigInteger> c = new HashMap<>(coeffs);
            for (Map.Entry<String, BigInteger> e : other.coeffs.entrySet()) {
                c.merge(e.getKey(), e.getValue().negate(), BigInteger::add);
            }
            return new LinearForm(c, constValue.subtract(other.constValue));
        }

        LinearForm negate() {
            Map<String, BigInteger> c = new HashMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                c.put(e.getKey(), e.getValue().negate());
            }
            return new LinearForm(c, constValue.negate());
        }

        LinearForm multiply(BigInteger k) {
            if (k.signum() == 0) {
                return ofConst(ZERO);
            }
            Map<String, BigInteger> c = new HashMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                c.put(e.getKey(), e.getValue().multiply(k));
            }
            return new LinearForm(c, constValue.multiply(k));
        }

        boolean isConstant() {
            return coeffs.isEmpty();
        }

        Map<String, BigInteger> coeffs() {
            return coeffs;
        }

        BigInteger constValue() {
            return constValue;
        }
    }

    static final class Rational {
        static final Rational ZERO = new Rational(ZERO, ONE);
        static final Rational ONE = new Rational(ONE, ONE);

        final BigInteger num;
        final BigInteger den;

        private Rational(BigInteger n, BigInteger d) {
            if (d.signum() == 0) {
                throw new Abort("division by zero");
            }
            if (n.signum() == 0) {
                this.num = ZERO;
                this.den = ONE;
                return;
            }
            if (d.signum() < 0) {
                n = n.negate();
                d = d.negate();
            }
            BigInteger g = n.abs().gcd(d);
            n = n.divide(g);
            d = d.divide(g);
            if (n.bitLength() > MAX_RATIONAL_BITS || d.bitLength() > MAX_RATIONAL_BITS) {
                throw new Abort("rational too large");
            }
            this.num = n;
            this.den = d;
        }

        static Rational of(BigInteger n) {
            return new Rational(n, ONE);
        }

        Rational add(Rational o) {
            return new Rational(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
        }

        Rational sub(Rational o) {
            return new Rational(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
        }

        Rational mul(Rational o) {
            return new Rational(num.multiply(o.num), den.multiply(o.den));
        }

        Rational divide(Rational o) {
            return new Rational(num.multiply(o.den), den.multiply(o.num));
        }

        Rational neg() {
            return new Rational(num.negate(), den);
        }

        Rational abs() {
            return new Rational(num.abs(), den);
        }

        int compareTo(Rational o) {
            return num.multiply(o.den).compareTo(o.num.multiply(den));
        }

        boolean isZero() {
            return num.signum() == 0;
        }

        boolean isPositive() {
            return num.signum() > 0;
        }

        boolean isNegative() {
            return num.signum() < 0;
        }

        String key() {
            return num.toString() + "/" + den.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Rational r)) {
                return false;
            }
            return num.equals(r.num) && den.equals(r.den);
        }

        @Override
        public int hashCode() {
            return Objects.hash(num, den);
        }
    }

    /**
     * Fourier-Motzkin elimination over rationals, used only as a sound
     * real-relaxation check for integer constraints.
     */
    static final class Solver {
        enum Status {
            SAT, UNSAT, UNKNOWN
        }

        record ElimResult(Status status, List<IneqR> ineqs) {
            static ElimResult SAT(List<IneqR> ineqs) {
                return new ElimResult(Status.SAT, List.copyOf(ineqs));
            }
            static ElimResult UNSAT() {
                return new ElimResult(Status.UNSAT, List.of());
            }
            static ElimResult UNKNOWN() {
                return new ElimResult(Status.UNKNOWN, List.of());
            }
        }

        record ProjectionResult(Status status, List<Ineq> ineqs) {
            static ProjectionResult SAT(List<Ineq> ineqs) {
                return new ProjectionResult(Status.SAT, List.copyOf(ineqs));
            }
            static ProjectionResult UNSAT() {
                return new ProjectionResult(Status.UNSAT, List.of());
            }
            static ProjectionResult UNKNOWN() {
                return new ProjectionResult(Status.UNKNOWN, List.of());
            }
        }

        static final class IneqR {
            final Map<String, Rational> coeffs;
            final Rational rhs;

            IneqR(Map<String, Rational> coeffs, Rational rhs) {
                this.coeffs = Map.copyOf(coeffs);
                this.rhs = rhs;
            }

            boolean isFalse() {
                return coeffs.isEmpty() && rhs.isNegative();
            }

            String key() {
                StringBuilder sb = new StringBuilder();
                List<String> vars = new ArrayList<>(coeffs.keySet());
                Collections.sort(vars);
                for (String v : vars) {
                    sb.append(v).append(':').append(coeffs.get(v).key()).append(';');
                }
                sb.append("rhs:").append(rhs.key());
                return sb.toString();
            }
        }

        static Status unsat(List<Ineq> ineqs) {
            try {
                List<IneqR> cur = toRationals(ineqs);
                for (IneqR i : cur) {
                    if (i.isFalse()) {
                        return Status.UNSAT;
                    }
                }
                Set<String> vars = new TreeSet<>();
                for (IneqR i : cur) {
                    vars.addAll(i.coeffs.keySet());
                }
                int iter = 0;
                while (!vars.isEmpty()) {
                    if (cur.size() > MAX_FE_INEQS) {
                        return Status.UNKNOWN;
                    }
                    String var = vars.iterator().next();
                    vars.remove(var);
                    ElimResult er = eliminate(cur, var);
                    if (er.status() != Status.SAT) {
                        return er.status();
                    }
                    cur = er.ineqs();
                    if (++iter > 20) {
                        return Status.UNKNOWN;
                    }
                }
                for (IneqR i : cur) {
                    if (i.isFalse()) {
                        return Status.UNSAT;
                    }
                }
                return Status.SAT;
            } catch (Abort e) {
                return Status.UNKNOWN;
            }
        }

        static ProjectionResult projectOut(List<Ineq> ineqs, String var) {
            try {
                List<IneqR> cur = toRationals(ineqs);
                for (IneqR i : cur) {
                    if (i.isFalse()) {
                        return ProjectionResult.UNSAT();
                    }
                }
                boolean present = false;
                for (IneqR i : cur) {
                    if (i.coeffs.containsKey(var)) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    return ProjectionResult.SAT(ineqs);
                }
                ElimResult er = eliminate(cur, var);
                if (er.status() != Status.SAT) {
                    return new ProjectionResult(er.status(), List.of());
                }
                Optional<List<Ineq>> converted = convertToInteger(er.ineqs());
                if (converted.isEmpty()) {
                    return ProjectionResult.UNKNOWN();
                }
                return ProjectionResult.SAT(converted.get());
            } catch (Abort e) {
                return ProjectionResult.UNKNOWN();
            }
        }

        private static List<IneqR> toRationals(List<Ineq> ineqs) {
            List<IneqR> out = new ArrayList<>();
            for (Ineq i : ineqs) {
                if (i.isFalse()) {
                    out.add(new IneqR(Map.of(), Rational.of(BigInteger.valueOf(-1))));
                    continue;
                }
                if (i.isTrue()) {
                    continue;
                }
                Map<String, Rational> c = new HashMap<>();
                for (Map.Entry<String, BigInteger> e : i.coeffs().entrySet()) {
                    c.put(e.getKey(), Rational.of(e.getValue()));
                }
                IneqR r = simplify(c, Rational.of(i.rhs()));
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        }

        private static IneqR simplify(Map<String, Rational> coeffs, Rational rhs) {
            IneqR r = canonicalize(coeffs, rhs);
            if (r.coeffs.isEmpty()) {
                if (r.rhs.isNegative()) {
                    return r;
                }
                return null;
            }
            return r;
        }

        private static IneqR canonicalize(Map<String, Rational> coeffs, Rational rhs) {
            Map<String, Rational> c = new HashMap<>();
            for (Map.Entry<String, Rational> e : coeffs.entrySet()) {
                if (!e.getValue().isZero()) {
                    c.put(e.getKey(), e.getValue());
                }
            }
            if (c.isEmpty()) {
                return new IneqR(Map.of(), rhs);
            }
            List<String> vars = new ArrayList<>(c.keySet());
            Collections.sort(vars);
            Rational first = c.get(vars.get(0));
            Rational div = first.abs();
            if (!div.isZero()) {
                Map<String, Rational> c2 = new HashMap<>();
                for (Map.Entry<String, Rational> e : c.entrySet()) {
                    c2.put(e.getKey(), e.getValue().divide(div));
                }
                rhs = rhs.divide(div);
                c = c2;
            }
            return new IneqR(Map.copyOf(c), rhs);
        }

        private static ElimResult eliminate(List<IneqR> ineqs, String var) {
            if (ineqs.size() > MAX_FE_INEQS) {
                return ElimResult.UNKNOWN();
            }
            List<IneqR> pos = new ArrayList<>();
            List<IneqR> neg = new ArrayList<>();
            List<IneqR> zero = new ArrayList<>();
            for (IneqR i : ineqs) {
                Rational c = i.coeffs.getOrDefault(var, Rational.ZERO);
                if (c.isPositive()) {
                    pos.add(i);
                } else if (c.isNegative()) {
                    neg.add(i);
                } else {
                    zero.add(i);
                }
            }

            if (pos.isEmpty() || neg.isEmpty()) {
                List<IneqR> out = new ArrayList<>();
                for (IneqR i : ineqs) {
                    Map<String, Rational> c = new HashMap<>(i.coeffs);
                    c.remove(var);
                    IneqR j = simplify(c, i.rhs);
                    if (j == null) {
                        continue;
                    }
                    if (j.isFalse()) {
                        return ElimResult.UNSAT();
                    }
                    out.add(j);
                }
                return ElimResult.SAT(dedup(out));
            }

            List<IneqR> out = new ArrayList<>();
            for (IneqR z : zero) {
                Map<String, Rational> c = new HashMap<>(z.coeffs);
                c.remove(var);
                IneqR j = simplify(c, z.rhs);
                if (j == null) {
                    continue;
                }
                if (j.isFalse()) {
                    return ElimResult.UNSAT();
                }
                out.add(j);
            }

            for (IneqR p : pos) {
                Rational a = p.coeffs.get(var);
                Map<String, Rational> R = new HashMap<>(p.coeffs);
                R.remove(var);
                Rational C = p.rhs;

                for (IneqR n : neg) {
                    Rational b = n.coeffs.get(var);
                    Map<String, Rational> S = new HashMap<>(n.coeffs);
                    S.remove(var);
                    Rational D = n.rhs;

                    Map<String, Rational> nc = new HashMap<>();
                    Set<String> vars = new HashSet<>();
                    vars.addAll(R.keySet());
                    vars.addAll(S.keySet());
                    for (String v : vars) {
                        Rational pv = R.getOrDefault(v, Rational.ZERO);
                        Rational nv = S.getOrDefault(v, Rational.ZERO);
                        // a*S - b*R <= a*D - b*C
                        Rational val = a.mul(nv).sub(b.mul(pv));
                        if (!val.isZero()) {
                            nc.put(v, val);
                        }
                    }
                    Rational nr = a.mul(D).sub(b.mul(C));
                    IneqR j = simplify(nc, nr);
                    if (j == null) {
                        continue;
                    }
                    if (j.isFalse()) {
                        return ElimResult.UNSAT();
                    }
                    out.add(j);
                    if (out.size() > MAX_COMBINED_INEQS) {
                        return ElimResult.UNKNOWN();
                    }
                }
            }
            return ElimResult.SAT(dedup(out));
        }

        private static List<IneqR> dedup(List<IneqR> in) {
            Set<String> seen = new HashSet<>();
            List<IneqR> out = new ArrayList<>();
            for (IneqR i : in) {
                if (seen.add(i.key())) {
                    out.add(i);
                }
                if (out.size() > MAX_COMBINED_INEQS) {
                    return out;
                }
            }
            return out;
        }

        private static Optional<List<Ineq>> convertToInteger(List<IneqR> ineqs) {
            List<Ineq> out = new ArrayList<>();
            for (IneqR r : ineqs) {
                if (r.coeffs.isEmpty()) {
                    if (r.rhs.isNegative()) {
                        return Optional.of(List.of(Ineq.normalize(Map.of(), BigInteger.valueOf(-1))));
                    }
                    continue;
                }
                BigInteger l = ONE;
                for (Rational c : r.coeffs.values()) {
                    l = lcm(l, c.den);
                    if (l.bitLength() > MAX_RATIONAL_BITS) {
                        return Optional.empty();
                    }
                }
                l = lcm(l, r.rhs.den);
                if (l.bitLength() > MAX_RATIONAL_BITS) {
                    return Optional.empty();
                }
                Map<String, BigInteger> c = new HashMap<>();
                for (Map.Entry<String, Rational> e : r.coeffs.entrySet()) {
                    BigInteger num = e.getValue().num.multiply(l).divide(e.getValue().den);
                    if (num.signum() != 0) {
                        c.put(e.getKey(), num);
                    }
                }
                BigInteger rhs = r.rhs.num.multiply(l).divide(r.rhs.den);
                Ineq i = Ineq.normalize(c, rhs);
                if (i.isFalse()) {
                    return Optional.of(List.of(i));
                }
                if (!i.isTrue()) {
                    out.add(i);
                }
            }
            Set<Ineq> seen = new HashSet<>();
            List<Ineq> dedup = new ArrayList<>();
            for (Ineq i : out) {
                if (seen.add(i)) {
                    dedup.add(i);
                }
            }
            return Optional.of(dedup);
        }

        private static BigInteger lcm(BigInteger a, BigInteger b) {
            if (a.signum() == 0) {
                return b;
            }
            if (b.signum() == 0) {
                return a;
            }
            BigInteger g = a.gcd(b);
            BigInteger res = a.divide(g).multiply(b).abs();
            if (res.bitLength() > MAX_RATIONAL_BITS) {
                throw new Abort("lcm too large");
            }
            return res;
        }
    }

    @Override
    public String name() {
        return "linear";
    }

    @Override
    public State top() {
        return new State(List.of(Component.top()));
    }

    @Override
    public State bottom() {
        return new State(List.of());
    }

    @Override
    public boolean isBottom(State s) {
        if (s == null) {
            return true;
        }
        if (s.components().isEmpty()) {
            return true;
        }
        for (Component c : s.components()) {
            if (!c.isBottom()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean entails(State a, State b) {
        if (isBottom(a)) {
            return true;
        }
        if (isBottom(b)) {
            return false;
        }
        if (b.isTop()) {
            return true;
        }
        if (a.isTop()) {
            return false;
        }
        for (Component ca : a.components()) {
            if (ca.isBottom()) {
                continue;
            }
            boolean ok = false;
            for (Component cb : b.components()) {
                if (cb.isTop()) {
                    ok = true;
                    break;
                }
                if (cb.isBottom()) {
                    continue;
                }
                if (ca.entails(cb)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public State join(State a, State b) {
        if (isBottom(a)) {
            return b;
        }
        if (isBottom(b)) {
            return a;
        }
        if (a.isTop() || b.isTop()) {
            return top();
        }

        List<Component> res = new ArrayList<>();
        for (Component c : b.components()) {
            if (!c.isBottom()) {
                res.add(c);
            }
        }

        for (Component ca : a.components()) {
            if (ca.isBottom()) {
                continue;
            }
            if (ca.isTop()) {
                return top();
            }
            boolean handled = false;
            for (int i = 0; i < res.size(); i++) {
                Component cr = res.get(i);
                if (cr.isTop()) {
                    return top();
                }
                if (canSubsume(ca, cr)) {
                    if (cr.entails(ca)) {
                        handled = true;
                        break;
                    }
                    if (ca.entails(cr)) {
                        res.set(i, ca);
                        handled = true;
                        break;
                    }
                }
            }
            if (!handled) {
                res.add(ca);
            }
        }
        return stateFrom(res);
    }

    @Override
    public State widen(State a, State b) {
        return join(a, b);
    }

    @Override
    public State transfer(Step step, State post) {
        if (isBottom(post)) {
            return bottom();
        }
        if (step instanceof Step.Assign assign) {
            return transferAssign(assign, post);
        }
        if (step instanceof Step.Assume assume) {
            return transferAssume(assume, post);
        }
        if (step instanceof Step.Call call) {
            return transferCall(call, post);
        }
        throw new UnsupportedOperationException("unknown step");
    }

    private State transferAssign(Step.Assign assign, State post) {
        String x = assign.target().name();
        Optional<LinearForm> ef = linearForm(assign.source());
        List<Component> res = new ArrayList<>();

        for (Component c : post.components()) {
            if (c.isBottom()) {
                continue;
            }
            Component pre = Component.top();
            for (Ineq ineq : c.ineqs) {
                if (!ineq.mentions(x)) {
                    pre = pre.addIneq(ineq);
                } else if (ef.isPresent()) {
                    Map<String, BigInteger> coeffs = new HashMap<>(ineq.coeffs());
                    BigInteger rhs = ineq.rhs();
                    BigInteger cx = coeffs.remove(x);
                    if (cx != null) {
                        for (Map.Entry<String, BigInteger> e : ef.get().coeffs().entrySet()) {
                            coeffs.merge(e.getKey(), cx.multiply(e.getValue()), BigInteger::add);
                        }
                        rhs = rhs.subtract(cx.multiply(ef.get().constValue()));
                    }
                    pre = pre.addIneq(coeffs, rhs);
                } else {
                    // Non-linear source: drop this constraint.  Sound over-approximation.
                }
                if (pre.isBottom()) {
                    break;
                }
            }
            if (!pre.isBottom()) {
                res.add(pre);
            }
        }
        return stateFrom(res);
    }

    private State transferAssume(Step.Assume assume, State post) {
        List<Component> res = new ArrayList<>();
        for (Component c : post.components()) {
            if (c.isBottom()) {
                continue;
            }
            res.addAll(assumeComponent(c, assume.cond()));
        }
        return stateFrom(res);
    }

    private State transferCall(Step.Call call, State post) {
        if (call.target().isEmpty()) {
            return post;
        }
        String x = call.target().get().name();
        List<Component> res = new ArrayList<>();

        for (Component c : post.components()) {
            if (c.isBottom()) {
                continue;
            }
            Solver.ProjectionResult pr = Solver.projectOut(c.ineqs, x);
            if (pr.status() == Solver.Status.UNSAT) {
                continue;
            }

            Component pre = Component.top();
            if (pr.status() == Solver.Status.SAT) {
                for (Ineq ineq : pr.ineqs()) {
                    if (!ineq.mentions(x)) {
                        pre = pre.addIneq(ineq);
                    }
                }
            } else {
                // Fallback projection: drop every constraint mentioning the overwritten variable.
                for (Ineq ineq : c.ineqs) {
                    if (!ineq.mentions(x)) {
                        pre = pre.addIneq(ineq);
                    }
                }
            }
            if (!pre.isBottom()) {
                res.add(pre);
            }
        }
        return stateFrom(res);
    }

    private State stateFrom(List<Component> comps) {
        List<Component> out = new ArrayList<>();
        for (Component c : comps) {
            if (c.isBottom()) {
                continue;
            }
            if (c.isTop()) {
                return top();
            }
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            return bottom();
        }
        if (out.size() > MAX_STATE_COMPONENTS) {
            return top();
        }
        return new State(out);
    }

    private boolean canSubsume(Component a, Component b) {
        return a.ineqs.size() <= MAX_ENTAIL_THIS && b.ineqs.size() <= MAX_ENTAIL_THIS;
    }

    /** For tests: exact admission of a concrete integer valuation by this abstract state. */
    public boolean admits(State s, Map<String, BigInteger> val) {
        if (s == null) {
            return false;
        }
        for (Component c : s.components()) {
            if (c.isBottom()) {
                continue;
            }
            boolean ok = true;
            for (Ineq i : c.ineqs) {
                BigInteger lhs = ZERO;
                for (Map.Entry<String, BigInteger> e : i.coeffs().entrySet()) {
                    BigInteger v = val.getOrDefault(e.getKey(), ZERO);
                    lhs = lhs.add(e.getValue().multiply(v));
                }
                if (lhs.compareTo(i.rhs()) > 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private List<Component> assumeComponent(Component c, RVal cond) {
        Optional<BigInteger> cv = constantFold(cond);
        if (cv.isPresent()) {
            // "Holds" is treated as non-zero.  This is sound if the front end uses
            // >0 or ==1, because it only admits more states.
            return cv.get().signum() == 0 ? List.of() : List.of(c);
        }

        if (cond instanceof RVal.Binop b) {
            BinOp op = b.op();
            if (isComparison(op)) {
                if (op == BinOp.Eq || op == BinOp.Ne) {
                    Optional<BigInteger> rc = constantFold(b.r());
                    Optional<BigInteger> lc = constantFold(b.l());

                    if (b.l() instanceof RVal.Binop lb && isComparison(lb.op()) && rc.isPresent()) {
                        boolean wantTrue = rc.get().signum() != 0;
                        if (op == BinOp.Eq) {
                            return wantTrue ? assumeComponent(c, lb) : assumeFalse(c, lb);
                        }
                        return wantTrue ? assumeFalse(c, lb) : assumeComponent(c, lb);
                    }
                    if (b.r() instanceof RVal.Binop rb && isComparison(rb.op()) && lc.isPresent()) {
                        boolean wantTrue = lc.get().signum() != 0;
                        if (op == BinOp.Eq) {
                            return wantTrue ? assumeComponent(c, rb) : assumeFalse(c, rb);
                        }
                        return wantTrue ? assumeFalse(c, rb) : assumeComponent(c, rb);
                    }
                }
                return addRelation(c, b.l(), op, b.r());
            }
        }

        Optional<LinearForm> lf = linearForm(cond);
        if (lf.isPresent()) {
            return addNe(c, lf.get());
        }
        return List.of(c);
    }

    private List<Component> assumeFalse(Component c, RVal comp) {
        if (comp instanceof RVal.Binop b && isComparison(b.op())) {
            return addRelation(c, b.l(), negateOp(b.op()), b.r());
        }
        return List.of(c);
    }

    private List<Component> addRelation(Component c, RVal l, BinOp op, RVal r) {
        Optional<BigInteger> lc = constantFold(l);
        Optional<BigInteger> rc = constantFold(r);
        if (lc.isPresent() && rc.isPresent()) {
            return evalComparison(lc.get(), rc.get(), op).signum() == 0 ? List.of() : List.of(c);
        }
        Optional<LinearForm> fl = linearForm(l);
        Optional<LinearForm> fr = linearForm(r);
        if (fl.isPresent() && fr.isPresent()) {
            LinearForm diff = fl.get().subtract(fr.get());
            return addOpFromDiff(c, diff, op);
        }
        return List.of(c);
    }

    private List<Component> addOpFromDiff(Component c, LinearForm diff, BinOp op) {
        if (diff.isConstant()) {
            boolean holds = switch (op) {
                case Lt -> diff.constValue().signum() < 0;
                case Le -> diff.constValue().signum() <= 0;
                case Gt -> diff.constValue().signum() > 0;
                case Ge -> diff.constValue().signum() >= 0;
                case Eq -> diff.constValue().signum() == 0;
                case Ne -> diff.constValue().signum() != 0;
                default -> false;
            };
            return holds ? List.of(c) : List.of();
        }

        switch (op) {
            case Lt:
                return addIneqComponent(c, diff, BigInteger.valueOf(-1));
            case Le:
                return addIneqComponent(c, diff, ZERO);
            case Gt:
                return addIneqComponent(c, diff.negate(), BigInteger.valueOf(-1));
            case Ge:
                return addIneqComponent(c, diff.negate(), ZERO);
            case Eq: {
                Component n1 = c.addIneq(Ineq.fromForm(diff, ZERO));
                if (n1.isBottom()) {
                    return List.of();
                }
                Component n2 = n1.addIneq(Ineq.fromForm(diff.negate(), ZERO));
                return n2.isBottom() ? List.of() : List.of(n2);
            }
            case Ne: {
                List<Component> res = new ArrayList<>();
                Component n1 = c.addIneq(Ineq.fromForm(diff, BigInteger.valueOf(-1)));
                if (!n1.isBottom()) {
                    res.add(n1);
                }
                Component n2 = c.addIneq(Ineq.fromForm(diff.negate(), BigInteger.valueOf(-1)));
                if (!n2.isBottom()) {
                    res.add(n2);
                }
                return res;
            }
            default:
                return List.of(c);
        }
    }

    private List<Component> addIneqComponent(Component c, LinearForm form, BigInteger bound) {
        Component nc = c.addIneq(Ineq.fromForm(form, bound));
        return nc.isBottom() ? List.of() : List.of(nc);
    }

    private List<Component> addNe(Component c, LinearForm form) {
        if (form.isConstant()) {
            return form.constValue().signum() == 0 ? List.of() : List.of(c);
        }
        List<Component> res = new ArrayList<>();
        Component n1 = c.addIneq(Ineq.fromForm(form, BigInteger.valueOf(-1)));
        if (!n1.isBottom()) {
            res.add(n1);
        }
        Component n2 = c.addIneq(Ineq.fromForm(form.negate(), BigInteger.valueOf(-1)));
        if (!n2.isBottom()) {
            res.add(n2);
        }
        return res;
    }

    private static boolean isComparison(BinOp op) {
        return op == BinOp.Lt || op == BinOp.Le || op == BinOp.Gt || op == BinOp.Ge
                || op == BinOp.Eq || op == BinOp.Ne;
    }

    private static BinOp negateOp(BinOp op) {
        return switch (op) {
            case Lt -> BinOp.Ge;
            case Le -> BinOp.Gt;
            case Gt -> BinOp.Le;
            case Ge -> BinOp.Lt;
            case Eq -> BinOp.Ne;
            case Ne -> BinOp.Eq;
            default -> BinOp.Eq;
        };
    }

    private static Optional<LinearForm> linearForm(RVal r) {
        if (r instanceof RVal.IntConst ic) {
            return Optional.of(LinearForm.ofConst(ic.v()));
        }
        if (r instanceof LVal.Local l) {
            return Optional.of(LinearForm.ofVar(l.name()));
        }
        if (r instanceof LVal.StaticField sf) {
            BigInteger v = staticValue(sf);
            if (v != null) {
                return Optional.of(LinearForm.ofConst(v));
            }
            return Optional.of(LinearForm.ofVar("static:" + sf.declaringClass() + "." + sf.name()));
        }
        if (r instanceof RVal.Binop b) {
            BinOp op = b.op();
            if (op == BinOp.Mult) {
                Optional<LinearForm> fl = linearForm(b.l());
                Optional<LinearForm> fr = linearForm(b.r());
                if (fl.isEmpty() || fr.isEmpty()) {
                    return Optional.empty();
                }
                LinearForm lf = fl.get();
                LinearForm rf = fr.get();
                if (lf.isConstant() && rf.isConstant()) {
                    return Optional.of(LinearForm.ofConst(lf.constValue().multiply(rf.constValue())));
                }
                if (lf.isConstant()) {
                    return Optional.of(rf.multiply(lf.constValue()));
                }
                if (rf.isConstant()) {
                    return Optional.of(lf.multiply(rf.constValue()));
                }
                return Optional.empty();
            }
            if (op == BinOp.Add || op == BinOp.Sub) {
                Optional<LinearForm> fl = linearForm(b.l());
                Optional<LinearForm> fr = linearForm(b.r());
                if (fl.isEmpty() || fr.isEmpty()) {
                    return Optional.empty();
                }
                if (op == BinOp.Add) {
                    return Optional.of(fl.get().add(fr.get()));
                }
                return Optional.of(fl.get().subtract(fr.get()));
            }
            // Comparisons are not affine unless they are constant.
            Optional<BigInteger> l = constantFold(b.l());
            Optional<BigInteger> rr = constantFold(b.r());
            if (l.isPresent() && rr.isPresent()) {
                return Optional.of(LinearForm.ofConst(evalComparison(l.get(), rr.get(), op)));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<BigInteger> constantFold(RVal r) {
        if (r instanceof RVal.IntConst ic) {
            return Optional.of(ic.v());
        }
        if (r instanceof LVal.StaticField sf) {
            BigInteger v = staticValue(sf);
            return v == null ? Optional.empty() : Optional.of(v);
        }
        if (r instanceof RVal.Binop b) {
            BinOp op = b.op();
            if (isComparison(op)) {
                Optional<BigInteger> l = constantFold(b.l());
                Optional<BigInteger> rr = constantFold(b.r());
                if (l.isPresent() && rr.isPresent()) {
                    return Optional.of(evalComparison(l.get(), rr.get(), op));
                }
                return Optional.empty();
            }
            Optional<LinearForm> fl = linearForm(b.l());
            Optional<LinearForm> fr = linearForm(b.r());
            if (fl.isPresent() && fr.isPresent() && fl.get().isConstant() && fr.get().isConstant()) {
                BigInteger lv = fl.get().constValue();
                BigInteger rv = fr.get().constValue();
                return switch (op) {
                    case Add -> Optional.of(lv.add(rv));
                    case Sub -> Optional.of(lv.subtract(rv));
                    case Mult -> Optional.of(lv.multiply(rv));
                    default -> Optional.empty();
                };
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static BigInteger staticValue(LVal.StaticField f) {
        return switch (f.name()) {
            case "ZERO" -> ZERO;
            case "ONE" -> ONE;
            case "TWO" -> TWO;
            case "TEN" -> TEN;
            default -> null;
        };
    }

    private static BigInteger evalComparison(BigInteger l, BigInteger r, BinOp op) {
        boolean b = switch (op) {
            case Lt -> l.compareTo(r) < 0;
            case Le -> l.compareTo(r) <= 0;
            case Gt -> l.compareTo(r) > 0;
            case Ge -> l.compareTo(r) >= 0;
            case Eq -> l.compareTo(r) == 0;
            case Ne -> l.compareTo(r) != 0;
            default -> false;
        };
        return b ? ONE : ZERO;
    }
}
