package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

/**
 * A disjunctive linear-inequality domain for backward reachability.
 *
 * <p>A state is a finite set of cells. Each cell is a conjunction of linear
 * integer inequalities of the form {@code expr <= bound}. The state admits the
 * union of the integer valuations admitted by its cells. {@code top()} is one
 * empty conjunction, and {@code bottom()} is the empty set of cells.
 *
 * <p>Transfer is sound by substitution for linear assignments, by adding
 * linear guard constraints for linear assumptions, and by projection (dropping
 * constraints involving the assigned variable) for {@code randInt()}.
 * Nonlinear expressions are handled conservatively: constraints that cannot be
 * represented are dropped, and assumptions are kept unless interval reasoning
 * proves them impossible.
 */
public final class LinearCellDomain implements Domain<LinearCellDomain.State> {

    private static final int MAX_CELLS = 128;
    private static final int FM_VAR_LIMIT = 8;
    private static final int FM_INEQ_LIMIT = 256;
    private static final int FM_PAIR_LIMIT = 512;

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger TEN = BigInteger.valueOf(10);
    private static final BigInteger MINUS_ONE = BigInteger.ONE.negate();

    public LinearCellDomain() {
    }

    public static final class State {
        final Set<Cell> cells;

        State(Set<Cell> cells) {
            this.cells = Set.copyOf(cells);
        }

        boolean hasTop() {
            for (Cell c : cells) {
                if (c.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof State s)) {
                return false;
            }
            return cells.equals(s.cells);
        }

        @Override
        public int hashCode() {
            return cells.hashCode();
        }

        @Override
        public String toString() {
            return cells.toString();
        }
    }

    static final class Cell {
        final List<Ineq> constraints;

        Cell(List<Ineq> constraints) {
            this.constraints = List.copyOf(constraints);
        }

        boolean isEmpty() {
            return constraints.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Cell c)) {
                return false;
            }
            return constraints.equals(c.constraints);
        }

        @Override
        public int hashCode() {
            return constraints.hashCode();
        }

        @Override
        public String toString() {
            return constraints.toString();
        }
    }

    record Ineq(LinearExpr expr, BigInteger bound) {
    }

    static final class LinearExpr {
        final BigInteger constant;
        final TreeMap<String, BigInteger> coeffs;

        private LinearExpr(BigInteger constant, TreeMap<String, BigInteger> coeffs) {
            this.constant = constant;
            this.coeffs = coeffs;
        }

        static LinearExpr ofConst(BigInteger v) {
            return new LinearExpr(v, new TreeMap<>());
        }

        static LinearExpr ofVar(String name) {
            TreeMap<String, BigInteger> m = new TreeMap<>();
            m.put(name, ONE);
            return new LinearExpr(ZERO, m);
        }

        LinearExpr add(LinearExpr o) {
            TreeMap<String, BigInteger> m = new TreeMap<>(coeffs);
            for (Map.Entry<String, BigInteger> e : o.coeffs.entrySet()) {
                BigInteger v = m.getOrDefault(e.getKey(), ZERO).add(e.getValue());
                if (v.signum() == 0) {
                    m.remove(e.getKey());
                } else {
                    m.put(e.getKey(), v);
                }
            }
            return new LinearExpr(constant.add(o.constant), m);
        }

        LinearExpr sub(LinearExpr o) {
            return add(o.mul(MINUS_ONE));
        }

        LinearExpr mul(BigInteger factor) {
            if (factor.signum() == 0) {
                return ofConst(ZERO);
            }
            if (factor.equals(ONE)) {
                return this;
            }
            TreeMap<String, BigInteger> m = new TreeMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                BigInteger v = e.getValue().multiply(factor);
                if (v.signum() != 0) {
                    m.put(e.getKey(), v);
                }
            }
            return new LinearExpr(constant.multiply(factor), m);
        }

        BigInteger coeff(String name) {
            return coeffs.getOrDefault(name, ZERO);
        }

        TreeMap<String, BigInteger> coeffs() {
            return coeffs;
        }

        boolean isConst() {
            return coeffs.isEmpty();
        }

        BigInteger constant() {
            return constant;
        }

        String key() {
            StringBuilder sb = new StringBuilder(constant.toString());
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                sb.append('|').append(e.getKey()).append(':').append(e.getValue());
            }
            return sb.toString();
        }

        Interval toInterval(Map<String, Interval> box) {
            Interval res = Interval.of(constant, constant);
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                Interval vi = box.getOrDefault(e.getKey(), Interval.TOP);
                Interval term = Interval.mul(Interval.of(e.getValue(), e.getValue()), vi);
                res = Interval.add(res, term);
                if (res.empty) {
                    return res;
                }
            }
            return res;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LinearExpr e)) {
                return false;
            }
            return constant.equals(e.constant) && coeffs.equals(e.coeffs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(constant, coeffs);
        }

        @Override
        public String toString() {
            return key();
        }
    }

    static final class Interval {
        static final Interval TOP = new Interval(null, null, false);
        static final Interval EMPTY = new Interval(null, null, true);
        static final Interval ZERO_INTERVAL = new Interval(BigInteger.ZERO, BigInteger.ZERO, false);

        final BigInteger lo;
        final BigInteger hi;
        final boolean empty;

        private Interval(BigInteger lo, BigInteger hi, boolean empty) {
            this.lo = lo;
            this.hi = hi;
            this.empty = empty;
        }

        static Interval of(BigInteger lo, BigInteger hi) {
            if (lo == null && hi == null) {
                return TOP;
            }
            if (lo != null && hi != null && lo.compareTo(hi) > 0) {
                return EMPTY;
            }
            return new Interval(lo, hi, false);
        }

        boolean isZero() {
            return ZERO_INTERVAL.equals(this);
        }

        Interval intersect(Interval o) {
            if (empty || o.empty) {
                return EMPTY;
            }
            BigInteger lo = this.lo == null ? o.lo : (o.lo == null ? null : this.lo.max(o.lo));
            BigInteger hi = this.hi == null ? o.hi : (o.hi == null ? null : this.hi.min(o.hi));
            return of(lo, hi);
        }

        static Interval add(Interval a, Interval b) {
            if (a.empty || b.empty) {
                return EMPTY;
            }
            BigInteger lo = a.lo == null || b.lo == null ? null : a.lo.add(b.lo);
            BigInteger hi = a.hi == null || b.hi == null ? null : a.hi.add(b.hi);
            return of(lo, hi);
        }

        static Interval sub(Interval a, Interval b) {
            if (a.empty || b.empty) {
                return EMPTY;
            }
            BigInteger lo = a.lo == null || b.hi == null ? null : a.lo.subtract(b.hi);
            BigInteger hi = a.hi == null || b.lo == null ? null : a.hi.subtract(b.lo);
            return of(lo, hi);
        }

        static Interval mul(Interval a, Interval b) {
            if (a.empty || b.empty) {
                return EMPTY;
            }
            if (a.isZero() || b.isZero()) {
                return ZERO_INTERVAL;
            }

            List<BigInteger> finite = new ArrayList<>();
            if (a.lo != null) {
                if (b.lo != null) {
                    finite.add(a.lo.multiply(b.lo));
                }
                if (b.hi != null) {
                    finite.add(a.lo.multiply(b.hi));
                }
            }
            if (a.hi != null) {
                if (b.lo != null) {
                    finite.add(a.hi.multiply(b.lo));
                }
                if (b.hi != null) {
                    finite.add(a.hi.multiply(b.hi));
                }
            }

            boolean aUnboundedBelow = a.lo == null;
            boolean aUnboundedAbove = a.hi == null;
            boolean bUnboundedBelow = b.lo == null;
            boolean bUnboundedAbove = b.hi == null;

            boolean aNeg = containsNegative(a);
            boolean aPos = containsPositive(a);
            boolean bNeg = containsNegative(b);
            boolean bPos = containsPositive(b);

            boolean unboundedBelow =
                    (aUnboundedBelow && bPos)
                            || (aUnboundedAbove && bNeg)
                            || (bUnboundedBelow && aPos)
                            || (bUnboundedAbove && aNeg);
            boolean unboundedAbove =
                    (aUnboundedBelow && bNeg)
                            || (aUnboundedAbove && bPos)
                            || (bUnboundedBelow && aNeg)
                            || (bUnboundedAbove && aPos);

            BigInteger lo = unboundedBelow ? null : (finite.isEmpty() ? null : Collections.min(finite));
            BigInteger hi = unboundedAbove ? null : (finite.isEmpty() ? null : Collections.max(finite));
            return of(lo, hi);
        }

        static boolean containsNegative(Interval i) {
            return !i.empty && (i.lo == null || i.lo.signum() < 0);
        }

        static boolean containsPositive(Interval i) {
            return !i.empty && (i.hi == null || i.hi.signum() > 0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Interval i)) {
                return false;
            }
            if (empty != i.empty) {
                return false;
            }
            if (empty) {
                return true;
            }
            return Objects.equals(lo, i.lo) && Objects.equals(hi, i.hi);
        }

        @Override
        public int hashCode() {
            return Objects.hash(empty, lo, hi);
        }

        @Override
        public String toString() {
            if (empty) {
                return "EMPTY";
            }
            String l = lo == null ? "-INF" : lo.toString();
            String h = hi == null ? "+INF" : hi.toString();
            return "[" + l + "," + h + "]";
        }
    }

    static final class FmIneq {
        final BigInteger[] coeffs;
        final BigInteger bound;

        FmIneq(BigInteger[] coeffs, BigInteger bound) {
            this.coeffs = coeffs;
            this.bound = bound;
        }
    }

    static final FmIneq INFEASIBLE = new FmIneq(new BigInteger[0], ONE);

    @Override
    public String name() {
        return "LinearCellDomain";
    }

    @Override
    public State top() {
        return topState();
    }

    @Override
    public State bottom() {
        return bottomState();
    }

    @Override
    public boolean isBottom(State s) {
        return s.cells.isEmpty();
    }

    @Override
    public boolean entails(State a, State b) {
        if (a.cells.isEmpty()) {
            return true;
        }
        if (b.cells.isEmpty()) {
            return false;
        }
        if (b.hasTop()) {
            return true;
        }

        for (Cell ca : a.cells) {
            if (!isFeasible(ca.constraints)) {
                continue;
            }
            boolean ok = false;
            for (Cell cb : b.cells) {
                if (cellEntails(ca, cb)) {
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
        return union(a.cells, b.cells);
    }

    @Override
    public State widen(State a, State b) {
        return union(a.cells, b.cells);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post.cells.isEmpty()) {
            return bottomState();
        }

        Set<Cell> results = new HashSet<>();
        for (Cell cell : post.cells) {
            State r = transferCell(step, cell);
            results.addAll(r.cells);
            if (results.size() > MAX_CELLS) {
                return topState();
            }
        }
        return stateFromCells(results);
    }

    static State topState() {
        return new State(Set.of(new Cell(List.of())));
    }

    static State bottomState() {
        return new State(Set.of());
    }

    static State stateFromCells(Set<Cell> cells) {
        if (cells.isEmpty()) {
            return bottomState();
        }
        for (Cell c : cells) {
            if (c.isEmpty()) {
                return topState();
            }
        }
        if (cells.size() > MAX_CELLS) {
            return topState();
        }
        return new State(cells);
    }

    static State union(Collection<Cell> a, Collection<Cell> b) {
        Set<Cell> set = new HashSet<>(a);
        set.addAll(b);
        return stateFromCells(set);
    }

    static State transferCell(Step step, Cell cell) {
        if (step instanceof Step.Assign assign) {
            return assignBack(assign, cell);
        }
        if (step instanceof Step.Assume assume) {
            return assumeBack(assume, cell);
        }
        if (step instanceof Step.Call call) {
            return callBack(call, cell);
        }
        throw new UnsupportedOperationException("unknown step");
    }

    static State assignBack(Step.Assign assign, Cell cell) {
        String x = assign.target().name();
        LinearExpr lin = linear(assign.source());

        List<Ineq> next = new ArrayList<>();
        for (Ineq ineq : cell.constraints) {
            LinearExpr expr = ineq.expr();
            if (!expr.coeffs().containsKey(x)) {
                next.add(ineq);
                continue;
            }

            if (lin != null) {
                LinearExpr sub = substitute(expr, x, lin);
                if (sub.isConst()) {
                    if (sub.constant().compareTo(ineq.bound()) > 0) {
                        return bottomState();
                    }
                } else {
                    next.add(new Ineq(sub, ineq.bound()));
                }
            }
        }

        Cell c = canonicalize(next);
        return c == null ? bottomState() : new State(Set.of(c));
    }

    static State assumeBack(Step.Assume assume, Cell cell) {
        RVal cond = assume.cond();

        if (cond instanceof RVal.Binop b && isComparison(b.op())) {
            LinearExpr l = linear(b.l());
            LinearExpr r = linear(b.r());
            if (l != null && r != null) {
                return addComparison(cell, l.sub(r), b.op());
            }
        }

        Cell c = canonicalize(cell.constraints);
        if (c == null) {
            return bottomState();
        }

        Map<String, Interval> box = propagateBox(c.constraints);
        if (box == null) {
            return bottomState();
        }

        Interval condInt = rvalInterval(cond, box);
        if (condInt.empty || condInt.isZero()) {
            return bottomState();
        }

        return new State(Set.of(c));
    }

    static State callBack(Step.Call call, Cell cell) {
        if (!"pag.probe.Rand.randInt".equals(call.callee().qualifiedName())) {
            return topState();
        }

        if (call.target().isEmpty()) {
            Cell c = canonicalize(cell.constraints);
            return c == null ? bottomState() : new State(Set.of(c));
        }

        String x = call.target().get().name();
        List<Ineq> next = new ArrayList<>();
        for (Ineq ineq : cell.constraints) {
            if (!ineq.expr().coeffs().containsKey(x)) {
                next.add(ineq);
            }
        }

        Cell c = canonicalize(next);
        return c == null ? bottomState() : new State(Set.of(c));
    }

    static State addComparison(Cell cell, LinearExpr diff, BinOp op) {
        switch (op) {
            case Lt:
                return oneCell(cell, List.of(new Ineq(diff, MINUS_ONE)));
            case Le:
                return oneCell(cell, List.of(new Ineq(diff, ZERO)));
            case Gt:
                return oneCell(cell, List.of(new Ineq(diff.mul(MINUS_ONE), MINUS_ONE)));
            case Ge:
                return oneCell(cell, List.of(new Ineq(diff.mul(MINUS_ONE), ZERO)));
            case Eq:
                return oneCell(cell, List.of(
                        new Ineq(diff, ZERO),
                        new Ineq(diff.mul(MINUS_ONE), ZERO)
                ));
            case Ne: {
                Cell c1 = canonicalize(withIneq(cell, new Ineq(diff, MINUS_ONE)));
                Cell c2 = canonicalize(withIneq(cell, new Ineq(diff.mul(MINUS_ONE), MINUS_ONE)));
                Set<Cell> set = new HashSet<>();
                if (c1 != null) {
                    set.add(c1);
                }
                if (c2 != null) {
                    set.add(c2);
                }
                return stateFromCells(set);
            }
            default:
                return new State(Set.of(cell));
        }
    }

    static State oneCell(Cell cell, List<Ineq> extra) {
        List<Ineq> all = new ArrayList<>(cell.constraints);
        all.addAll(extra);
        Cell c = canonicalize(all);
        return c == null ? bottomState() : new State(Set.of(c));
    }

    static List<Ineq> withIneq(Cell cell, Ineq... extra) {
        List<Ineq> all = new ArrayList<>(cell.constraints);
        Collections.addAll(all, extra);
        return all;
    }

    static LinearExpr substitute(LinearExpr expr, String var, LinearExpr replacement) {
        BigInteger c = expr.coeff(var);
        if (c.signum() == 0) {
            return expr;
        }
        LinearExpr rest = expr.sub(LinearExpr.ofVar(var).mul(c));
        return rest.add(replacement.mul(c));
    }

    static Cell canonicalize(List<Ineq> raw) {
        Map<String, Ineq> map = new TreeMap<>();

        for (Ineq ineq : raw) {
            LinearExpr expr = ineq.expr();
            if (expr.isConst()) {
                if (expr.constant().compareTo(ineq.bound()) > 0) {
                    return null;
                }
                continue;
            }

            String key = expr.key();
            Ineq old = map.get(key);
            if (old == null || ineq.bound().compareTo(old.bound()) < 0) {
                map.put(key, new Ineq(expr, ineq.bound()));
            }
        }

        List<Ineq> list = new ArrayList<>(map.values());
        if (!isFeasible(list)) {
            return null;
        }
        return new Cell(list);
    }

    static boolean isFeasible(List<Ineq> constraints) {
        if (propagateBox(constraints) == null) {
            return false;
        }
        return fmFeasible(constraints);
    }

    static boolean cellEntails(Cell a, Cell b) {
        if (b.constraints.isEmpty()) {
            return true;
        }
        if (a.constraints.equals(b.constraints)) {
            return true;
        }
        if (!isFeasible(a.constraints)) {
            return true;
        }

        for (Ineq ib : b.constraints) {
            if (!impliesIneq(a.constraints, ib)) {
                return false;
            }
        }
        return true;
    }

    static boolean impliesIneq(List<Ineq> cell, Ineq target) {
        Map<String, Interval> box = propagateBox(cell);
        if (box == null) {
            return true;
        }

        Interval exprInt = target.expr().toInterval(box);
        if (exprInt.hi != null && exprInt.hi.compareTo(target.bound()) <= 0) {
            return true;
        }

        List<Ineq> counter = new ArrayList<>(cell);
        counter.add(new Ineq(
                target.expr().mul(MINUS_ONE),
                target.bound().negate().subtract(ONE)
        ));
        return !isFeasible(counter);
    }

    static Map<String, Interval> propagateBox(List<Ineq> constraints) {
        Map<String, Interval> box = new HashMap<>();

        for (int iter = 0; iter < 100; iter++) {
            boolean changed = false;

            for (Ineq ineq : constraints) {
                LinearExpr expr = ineq.expr();
                Interval exprInt = expr.toInterval(box);
                if (exprInt.empty) {
                    return null;
                }
                if (exprInt.lo != null && exprInt.lo.compareTo(ineq.bound()) > 0) {
                    return null;
                }

                for (Map.Entry<String, BigInteger> e : expr.coeffs().entrySet()) {
                    String var = e.getKey();
                    BigInteger a = e.getValue();
                    if (a.signum() == 0) {
                        continue;
                    }

                    LinearExpr rest = expr.sub(LinearExpr.ofVar(var).mul(a));
                    Interval restInt = rest.toInterval(box);
                    if (restInt.empty) {
                        return null;
                    }

                    Interval varInt = box.getOrDefault(var, Interval.TOP);
                    BigInteger newLo = varInt.lo;
                    BigInteger newHi = varInt.hi;

                    if (restInt.lo != null) {
                        BigInteger k = ineq.bound().subtract(restInt.lo);
                        if (a.signum() > 0) {
                            BigInteger b = floorDiv(k, a);
                            if (newHi == null || b.compareTo(newHi) < 0) {
                                newHi = b;
                            }
                        } else {
                            BigInteger b = ceilDiv(k, a);
                            if (newLo == null || b.compareTo(newLo) > 0) {
                                newLo = b;
                            }
                        }
                    }

                    Interval candidate = Interval.of(newLo, newHi);
                    Interval updated = varInt.intersect(candidate);
                    if (updated.empty) {
                        return null;
                    }
                    if (!updated.equals(varInt)) {
                        box.put(var, updated);
                        changed = true;
                    }
                }
            }

            if (!changed) {
                break;
            }
        }

        return box;
    }

    static boolean fmFeasible(List<Ineq> constraints) {
        TreeSet<String> vars = new TreeSet<>();
        for (Ineq ineq : constraints) {
            vars.addAll(ineq.expr().coeffs().keySet());
        }

        int n = vars.size();
        if (n > FM_VAR_LIMIT) {
            return true;
        }

        List<String> varList = new ArrayList<>(vars);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(varList.get(i), i);
        }

        List<FmIneq> list = new ArrayList<>();
        for (Ineq ineq : constraints) {
            BigInteger[] coeffs = new BigInteger[n];
            Arrays.fill(coeffs, ZERO);
            for (Map.Entry<String, BigInteger> e : ineq.expr().coeffs().entrySet()) {
                Integer idx = index.get(e.getKey());
                if (idx != null) {
                    coeffs[idx] = e.getValue();
                }
            }

            FmIneq norm = normalizeFm(coeffs, ineq.bound());
            if (norm == INFEASIBLE) {
                return false;
            }
            if (norm != null) {
                list.add(norm);
            }
        }

        if (list.isEmpty()) {
            return true;
        }

        for (int i = 0; i < n; i++) {
            List<FmIneq> zero = new ArrayList<>();
            List<FmIneq> pos = new ArrayList<>();
            List<FmIneq> neg = new ArrayList<>();

            for (FmIneq ineq : list) {
                BigInteger c = ineq.coeffs[i];
                if (c.signum() > 0) {
                    pos.add(ineq);
                } else if (c.signum() < 0) {
                    neg.add(ineq);
                } else {
                    zero.add(ineq);
                }
            }

            List<FmIneq> next = new ArrayList<>(zero);

            if (!pos.isEmpty() && !neg.isEmpty()) {
                long pairs = (long) pos.size() * (long) neg.size();
                if (pairs > FM_PAIR_LIMIT) {
                    return true;
                }

                for (FmIneq p : pos) {
                    for (FmIneq q : neg) {
                        BigInteger multP = q.coeffs[i].negate();
                        BigInteger multQ = p.coeffs[i];

                        BigInteger[] newCoeffs = new BigInteger[n];
                        Arrays.fill(newCoeffs, ZERO);
                        for (int j = 0; j < n; j++) {
                            if (j == i) {
                                continue;
                            }
                            newCoeffs[j] = p.coeffs[j].multiply(multP).add(q.coeffs[j].multiply(multQ));
                        }
                        BigInteger newBound = p.bound.multiply(multP).add(q.bound.multiply(multQ));

                        FmIneq norm = normalizeFm(newCoeffs, newBound);
                        if (norm == INFEASIBLE) {
                            return false;
                        }
                        if (norm != null) {
                            next.add(norm);
                            if (next.size() > FM_INEQ_LIMIT) {
                                return true;
                            }
                        }
                    }
                }
            }

            list = next;
            if (list.size() > FM_INEQ_LIMIT) {
                return true;
            }
        }

        for (FmIneq ineq : list) {
            for (BigInteger c : ineq.coeffs) {
                if (c.signum() != 0) {
                    return true;
                }
            }
            if (ineq.bound.signum() < 0) {
                return false;
            }
        }
        return true;
    }

    static FmIneq normalizeFm(BigInteger[] coeffs, BigInteger bound) {
        int first = -1;
        for (int i = 0; i < coeffs.length; i++) {
            if (coeffs[i].signum() != 0) {
                first = i;
                break;
            }
        }

        if (first == -1) {
            if (bound.signum() < 0) {
                return INFEASIBLE;
            }
            return null;
        }

        BigInteger g = ZERO;
        for (BigInteger c : coeffs) {
            if (c.signum() != 0) {
                g = g.gcd(c.abs());
            }
        }
        g = g.gcd(bound.abs());

        if (g.signum() > 1 && bound.mod(g).equals(ZERO)) {
            for (int i = 0; i < coeffs.length; i++) {
                if (coeffs[i].signum() != 0) {
                    coeffs[i] = coeffs[i].divide(g);
                }
            }
            bound = bound.divide(g);
        }

        return new FmIneq(coeffs, bound);
    }

    static BigInteger floorDiv(BigInteger a, BigInteger b) {
        if (b.signum() == 0) {
            throw new ArithmeticException("divide by zero");
        }
        BigInteger[] qr = a.divideAndRemainder(b);
        BigInteger r = qr[1];
        if (r.signum() != 0 && r.signum() != b.signum()) {
            qr[0] = qr[0].subtract(ONE);
        }
        return qr[0];
    }

    static BigInteger ceilDiv(BigInteger a, BigInteger b) {
        if (b.signum() == 0) {
            throw new ArithmeticException("divide by zero");
        }
        BigInteger[] qr = a.divideAndRemainder(b);
        BigInteger r = qr[1];
        if (r.signum() != 0 && r.signum() == b.signum()) {
            qr[0] = qr[0].add(ONE);
        }
        return qr[0];
    }

    static LinearExpr linear(RVal r) {
        if (r instanceof RVal.IntConst c) {
            return LinearExpr.ofConst(c.v());
        }
        if (r instanceof LVal.Local l) {
            return LinearExpr.ofVar(l.name());
        }
        if (r instanceof LVal.StaticField f) {
            BigInteger v = staticConst(f);
            return v == null ? null : LinearExpr.ofConst(v);
        }
        if (r instanceof RVal.Binop b) {
            return linearBinop(b);
        }
        return null;
    }

    static LinearExpr linearBinop(RVal.Binop b) {
        switch (b.op()) {
            case Mult: {
                BigInteger lc = constant(b.l());
                if (lc != null && lc.signum() == 0) {
                    return LinearExpr.ofConst(ZERO);
                }
                BigInteger rc = constant(b.r());
                if (rc != null && rc.signum() == 0) {
                    return LinearExpr.ofConst(ZERO);
                }

                LinearExpr l = linear(b.l());
                LinearExpr rr = linear(b.r());
                if (l == null || rr == null) {
                    return null;
                }
                if (l.isConst()) {
                    return rr.mul(l.constant());
                }
                if (rr.isConst()) {
                    return l.mul(rr.constant());
                }
                return null;
            }
            case Add: {
                LinearExpr l = linear(b.l());
                LinearExpr rr = linear(b.r());
                if (l == null || rr == null) {
                    return null;
                }
                return l.add(rr);
            }
            case Sub: {
                LinearExpr l = linear(b.l());
                LinearExpr rr = linear(b.r());
                if (l == null || rr == null) {
                    return null;
                }
                return l.sub(rr);
            }
            default:
                return null;
        }
    }

    static BigInteger constant(RVal r) {
        if (r instanceof RVal.IntConst c) {
            return c.v();
        }
        if (r instanceof LVal.StaticField f) {
            return staticConst(f);
        }
        if (r instanceof RVal.Binop b) {
            return constantBinop(b);
        }
        return null;
    }

    static BigInteger constantBinop(RVal.Binop b) {
        switch (b.op()) {
            case Mult: {
                BigInteger l = constant(b.l());
                BigInteger rr = constant(b.r());
                if (l == null || rr == null) {
                    return null;
                }
                return l.multiply(rr);
            }
            case Add: {
                BigInteger l = constant(b.l());
                BigInteger rr = constant(b.r());
                if (l == null || rr == null) {
                    return null;
                }
                return l.add(rr);
            }
            case Sub: {
                BigInteger l = constant(b.l());
                BigInteger rr = constant(b.r());
                if (l == null || rr == null) {
                    return null;
                }
                return l.subtract(rr);
            }
            default:
                return null;
        }
    }

    static BigInteger staticConst(LVal.StaticField f) {
        if (!"java.math.BigInteger".equals(f.declaringClass())) {
            return null;
        }
        switch (f.name()) {
            case "ZERO":
                return ZERO;
            case "ONE":
                return ONE;
            case "TWO":
                return TWO;
            case "TEN":
                return TEN;
            default:
                return null;
        }
    }

    static Interval rvalInterval(RVal r, Map<String, Interval> box) {
        if (r instanceof RVal.IntConst c) {
            return Interval.of(c.v(), c.v());
        }
        if (r instanceof LVal.Local l) {
            return box.getOrDefault(l.name(), Interval.TOP);
        }
        if (r instanceof LVal.StaticField f) {
            BigInteger v = staticConst(f);
            return v == null ? Interval.TOP : Interval.of(v, v);
        }
        if (r instanceof RVal.Binop b) {
            return intervalBinop(b, box);
        }
        return Interval.TOP;
    }

    static Interval intervalBinop(RVal.Binop b, Map<String, Interval> box) {
        Interval l = rvalInterval(b.l(), box);
        Interval rr = rvalInterval(b.r(), box);
        if (l.empty || rr.empty) {
            return Interval.EMPTY;
        }

        switch (b.op()) {
            case Mult:
                return Interval.mul(l, rr);
            case Add:
                return Interval.add(l, rr);
            case Sub:
                return Interval.sub(l, rr);
            default:
                if (!opPossible(b.op(), l, rr)) {
                    return Interval.EMPTY;
                }
                return Interval.of(ZERO, ONE);
        }
    }

    static boolean isComparison(BinOp op) {
        switch (op) {
            case Lt:
            case Le:
            case Gt:
            case Ge:
            case Eq:
            case Ne:
                return true;
            default:
                return false;
        }
    }

    static boolean opPossible(BinOp op, Interval l, Interval r) {
        if (l.empty || r.empty) {
            return false;
        }

        switch (op) {
            case Lt:
                return !(l.lo != null && r.hi != null && l.lo.compareTo(r.hi) >= 0);
            case Le:
                return !(l.lo != null && r.hi != null && l.lo.compareTo(r.hi) > 0);
            case Gt:
                return !(r.lo != null && l.hi != null && r.lo.compareTo(l.hi) >= 0);
            case Ge:
                return !(r.lo != null && l.hi != null && r.lo.compareTo(l.hi) > 0);
            case Eq:
                return intervalsIntersect(l, r);
            case Ne:
                return !(isSingleton(l) && isSingleton(r) && l.lo.equals(r.lo));
            default:
                return true;
        }
    }

    static boolean intervalsIntersect(Interval a, Interval b) {
        if (a.empty || b.empty) {
            return false;
        }
        BigInteger lo = a.lo == null ? b.lo : (b.lo == null ? null : a.lo.max(b.lo));
        BigInteger hi = a.hi == null ? b.hi : (b.hi == null ? null : a.hi.min(b.hi));
        return !(lo != null && hi != null && lo.compareTo(hi) > 0);
    }

    static boolean isSingleton(Interval i) {
        return !i.empty && i.lo != null && i.hi != null && i.lo.equals(i.hi);
    }
}
