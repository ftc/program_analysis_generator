package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class IntervalDomain implements Domain<IntervalDomain.State> {

    public static final class State {
        private final Map<String, Interval> constraints;
        private final boolean isBottom;

        private static final State BOTTOM = new State(new HashMap<>(), true);
        private static final State TOP = new State(new HashMap<>(), false);

        private State(Map<String, Interval> constraints, boolean isBottom) {
            this.constraints = constraints;
            this.isBottom = isBottom;
        }

        public boolean isBottom() {
            return isBottom;
        }

        public Interval get(String var) {
            if (isBottom) return Interval.EMPTY;
            return constraints.getOrDefault(var, Interval.TOP);
        }

        public State update(String var, Interval interval) {
            if (isBottom) return this;
            if (interval.isBottom()) return bottom();
            Map<String, Interval> newConstraints = new HashMap<>(constraints);
            newConstraints.put(var, interval);
            return new State(newConstraints, false);
        }

        public State merge(State other) {
            if (isBottom) return other;
            if (other.isBottom) return this;
            Map<String, Interval> newConstraints = new HashMap<>(constraints);
            for (Map.Entry<String, Interval> entry : other.constraints.entrySet()) {
                String v = entry.getKey();
                Interval i = entry.getValue();
                Interval existing = constraints.getOrDefault(v, Interval.TOP);
                newConstraints.put(v, existing.join(i));
            }
            return new State(newConstraints, false);
        }

        public static State bottom() { return BOTTOM; }
        public static State top() { return TOP; }
    }

    public static final class Interval {
        private final BigInteger min;
        private final BigInteger max;

        private static final Interval EMPTY = new Interval(BigInteger.ONE, BigInteger.ZERO);
        private static final Interval TOP = new Interval(null, null);

        private Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }

        public boolean isBottom() {
            return min != null && max != null && min.compareTo(max) > 0;
        }

        public Interval join(Interval other) {
            BigInteger newMin = min == null ? other.min : (other.min == null ? min : min.min(other.min));
            BigInteger newMax = max == null ? other.max : (other.max == null ? max : max.max(other.max));
            return new Interval(newMin, newMax);
        }

        public Interval widen(Interval other) {
            BigInteger newMin = min;
            BigInteger newMax = max;
            if (other.min != null && (min == null || other.min.compareTo(min) < 0)) {
                newMin = null;
            }
            if (other.max != null && (max == null || other.max.compareTo(max) > 0)) {
                newMax = null;
            }
            return new Interval(newMin, newMax);
        }

        public Interval intersect(Interval other) {
            BigInteger newMin = min == null ? other.min : (other.min == null ? min : min.max(other.min));
            BigInteger newMax = max == null ? other.max : (other.max == null ? max : max.min(other.max));
            return new Interval(newMin, newMax);
        }

        public boolean entails(Interval other) {
            if (isBottom()) return true;
            if (other.isBottom()) return false;
            if (min != null && other.min != null && min.compareTo(other.min) < 0) return false;
            if (max != null && other.max != null && max.compareTo(other.max) > 0) return false;
            if (min == null && other.min != null) return false;
            if (max == null && other.max != null) return false;
            return true;
        }
    }

    public IntervalDomain() {}

    @Override public String name() { return "IntervalDomain"; }
    @Override public State top() { return State.top(); }
    @Override public State bottom() { return State.bottom(); }
    @Override public boolean isBottom(State s) { return s.isBottom(); }

    @Override public boolean entails(State a, State b) {
        if (a.isBottom()) return true;
        if (b.isBottom()) return false;
        for (Map.Entry<String, Interval> entry : a.constraints.entrySet()) {
            String v = entry.getKey();
            Interval ia = entry.getValue();
            Interval ib = b.constraints.getOrDefault(v, Interval.TOP);
            if (!ia.entails(ib)) return false;
        }
        for (Map.Entry<String, Interval> entry : b.constraints.entrySet()) {
             String v = entry.getKey();
             Interval ia = a.constraints.getOrDefault(v, Interval.TOP);
             Interval ib = entry.getValue();
             if (!ia.entails(ib)) return false;
        }
        return true;
    }

    @Override public State join(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        Map<String, Interval> newConstraints = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.constraints.keySet());
        allVars.addAll(b.constraints.keySet());
        for (String v : allVars) {
            Interval ia = a.constraints.getOrDefault(v, Interval.TOP);
            Interval ib = b.constraints.getOrDefault(v, Interval.TOP);
            newConstraints.put(v, ia.join(ib));
        }
        return new State(newConstraints, false);
    }

    @Override public State widen(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        Map<String, Interval> newConstraints = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.constraints.keySet());
        allVars.addAll(b.constraints.keySet());
        for (String v : allVars) {
            Interval ia = a.constraints.getOrDefault(v, Interval.TOP);
            Interval ib = b.constraints.getOrDefault(v, Interval.TOP);
            newConstraints.put(v, ia.widen(ib));
        }
        return new State(newConstraints, false);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom()) return post;
        switch (step) {
            case Step.Assign assign -> {
                String targetName = lvalToString(assign.target());
                Interval postTarget = post.get(targetName);
                Interval rangeE = evalRVal(assign.source(), post);
                Interval intersection = postTarget.intersect(rangeE);
                if (intersection.isBottom()) return bottom();
                return post.update(targetName, Interval.TOP);
            }
            case Step.Assume assume -> {
                return refineAssume(assume.cond(), post);
            }
            case Step.Call call -> {
                if (call.target().isPresent()) {
                    String targetName = lvalToString(call.target().get());
                    return post.update(targetName, Interval.TOP);
                }
                return post;
            }
        }
        throw new UnsupportedOperationException();
    }

    private String lvalToString(LVal lval) {
        if (lval instanceof LVal.Local local) {
            return local.name();
        } else if (lval instanceof LVal.StaticField sf) {
            return sf.declaringClass() + "." + sf.name();
        }
        return "unknown";
    }

    private Interval evalRVal(RVal rval, State state) {
        switch (rval) {
            case RVal.IntConst constVal -> new Interval(constVal.v(), constVal.v());
            case RVal.Binop binop -> {
                Interval l = evalRVal(binop.l(), state);
                Interval r = evalRVal(binop.r(), state);
                return evalBinop(l, binop.op(), r);
            }
            case LVal local -> return state.get(lvalToString(local));
        }
        return Interval.TOP;
    }

    private Interval evalBinop(Interval l, BinOp op, Interval r) {
        if (l.isBottom() || r.isBottom()) return Interval.EMPTY;
        if (l.min == null || l.max == null || r.min == null || r.max == null) return Interval.TOP;

        switch (op) {
            case Add -> return new Interval(l.min.add(r.min), l.max.add(r.max));
            case Sub -> return new Interval(l.min.subtract(r.max), l.max.subtract(r.min));
            case Mult -> {
                List<BigInteger> candidates = Arrays.asList(
                    l.min.multiply(r.min), l.min.multiply(r.max),
                    l.max.multiply(r.min), l.max.multiply(r.max)
                );
                BigInteger min = candidates.stream().min(BigInteger::compareTo).get();
                BigInteger max = candidates.stream().max(BigInteger::compareTo).get();
                return new Interval(min, max);
            }
            default -> return Interval.TOP;
        }
    }

    private State refineAssume(RVal cond, State post) {
        if (cond instanceof RVal.Binop binop) {
            RVal l = binop.l();
            RVal r = binop.r();
            BinOp op = binop.op();

            if (l instanceof LVal.Local && r instanceof RVal.IntConst) {
                String var = ((LVal.Local) l).name();
                BigInteger val = ((RVal.IntConst) r).v();
                Interval oldInt = post.get(var);
                Interval newInt = refineInterval(oldInt, op, val);
                if (newInt.isBottom()) return bottom();
                return post.update(var, newInt);
            }
            if (r instanceof LVal.Local && l instanceof RVal.IntConst) {
                String var = ((LVal.Local) r).name();
                BigInteger val = ((RVal.IntConst) l).v();
                Interval oldInt = post.get(var);
                Interval newInt = refineIntervalConstLeft(oldInt, op, val);
                if (newInt.isBottom()) return bottom();
                return post.update(var, newInt);
            }
            Interval lInt = evalRVal(l, post);
            Interval rInt = evalRVal(r, post);
            if (!isSatisfiable(lInt, op, rInt)) return bottom();
            return post;
        }
        return post;
    }

    private Interval refineInterval(Interval interval, BinOp op, BigInteger val) {
        BigInteger min = interval.min;
        BigInteger max = interval.max;
        BigInteger newMin = min;
        BigInteger newMax = max;

        switch (op) {
            case Lt: newMax = val.subtract(BigInteger.ONE); break;
            case Le: newMax = val; break;
            case Gt: newMin = val.add(BigInteger.ONE); break;
            case Ge: newMin = val; break;
            case Eq: newMin = val; newMax = val; break;
            case Ne:
                if (min != null && max != null && min.equals(val) && max.equals(val)) {
                    return Interval.EMPTY;
                }
                return interval;
        }
        return new Interval(newMin, newMax);
    }

    private Interval refineIntervalConstLeft(Interval interval, BinOp op, BigInteger val) {
        BigInteger min = interval.min;
        BigInteger max = interval.max;
        BigInteger newMin = min;
        BigInteger newMax = max;

        switch (op) {
            case Lt: newMin = val.add(BigInteger.ONE); break;
            case Le: newMin = val; break;
            case Gt: newMax = val.subtract(BigInteger.ONE); break;
            case Ge: newMax = val; break;
            case Eq: newMin = val; newMax = val; break;
            case Ne:
                if (min != null && max != null && min.equals(val) && max.equals(val)) {
                    return Interval.EMPTY;
                }
                return interval;
        }
        return new Interval(newMin, newMax);
    }

    private boolean isSatisfiable(Interval l, BinOp op, Interval r) {
        if (l.isBottom() || r.isBottom()) return false;
        if (l.min == null || l.max == null || r.min == null || r.max == null) return true;

        switch (op) {
            case Lt: return l.min.compareTo(r.max) < 0;
            case Le: return l.min.compareTo(r.max) <= 0;
            case Gt: return l.max.compareTo(r.min) > 0;
            case Ge: return l.max.compareTo(r.min) >= 0;
            case Eq: return l.min.compareTo(r.max) <= 0 && l.max.compareTo(r.min) >= 0;
            case Ne: return !(l.min.equals(l.max) && l.min.equals(r.min) && r.min.equals(r.max));
            default: return true;
        }
    }
}
