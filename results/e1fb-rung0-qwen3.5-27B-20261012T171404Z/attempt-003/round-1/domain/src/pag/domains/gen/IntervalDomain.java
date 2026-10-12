package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class IntervalDomain implements Domain<IntervalDomain.State> {

    /** Abstract state tracking intervals for each local variable. */
    public static final class State {
        private final Map<String, Interval> intervals;
        private final boolean isBottom;

        private static final Map<String, Interval> EMPTY = Collections.emptyMap();

        private State(Map<String, Interval> intervals, boolean isBottom) {
            this.intervals = isBottom ? EMPTY : Map.copyOf(intervals);
            this.isBottom = isBottom;
        }

        public static State bottom() {
            return new State(EMPTY, true);
        }

        public static State top() {
            return new State(new HashMap<>(), false);
        }

        public static State fromMap(Map<String, Interval> intervals) {
            return new State(intervals, false);
        }

        public boolean isBottom() {
            return isBottom;
        }

        public Interval getInterval(String var) {
            if (isBottom) return Interval.BOTTOM;
            return intervals.getOrDefault(var, Interval.TOP);
        }

        public Map<String, Interval> getIntervals() {
            return intervals;
        }
    }

    /** Interval [min, max] for a variable value. */
    public static final class Interval {
        private final BigInteger min;
        private final BigInteger max;
        private final boolean isBottom;

        private static final Interval BOTTOM = new Interval(null, null, true);
        private static final Interval TOP = new Interval(null, null, false);

        public Interval(BigInteger min, BigInteger max, boolean isBottom) {
            this.min = min;
            this.max = max;
            this.isBottom = isBottom;
        }

        public static Interval bottom() {
            return BOTTOM;
        }

        public static Interval top() {
            return TOP;
        }

        public static Interval of(BigInteger min, BigInteger max) {
            if (min != null && max != null && min.compareTo(max) > 0) {
                return BOTTOM;
            }
            return new Interval(min, max, false);
        }

        public boolean isBottom() {
            return isBottom;
        }

        public boolean isTop() {
            return !isBottom && min == null && max == null;
        }

        public BigInteger getMin() {
            return min;
        }

        public BigInteger getMax() {
            return max;
        }

        public Interval join(Interval other) {
            if (this.isBottom()) return other;
            if (other.isBottom()) return this;
            if (this.isTop() || other.isTop()) return TOP;

            BigInteger newMin = (this.min == null || other.min == null) ? null :
                this.min.compareTo(other.min) <= 0 ? this.min : other.min;
            BigInteger newMax = (this.max == null || other.max == null) ? null :
                this.max.compareTo(other.max) >= 0 ? this.max : other.max;

            return of(newMin, newMax);
        }

        public Interval widen(Interval other) {
            if (this.isBottom()) return other;
            if (other.isBottom()) return this;
            if (this.isTop() || other.isTop()) return TOP;

            BigInteger newMin, newMax;

            if (this.min != null && other.min != null) {
                if (other.min.compareTo(this.min) < 0) {
                    newMin = null; // Widening to -∞
                } else {
                    newMin = this.min;
                }
            } else {
                newMin = null;
            }

            if (this.max != null && other.max != null) {
                if (other.max.compareTo(this.max) > 0) {
                    newMax = null; // Widening to +∞
                } else {
                    newMax = this.max;
                }
            } else {
                newMax = null;
            }

            return of(newMin, newMax);
        }

        /** Returns true iff every value in this is also in other (this ⊆ other). */
        public boolean entails(Interval other) {
            if (this.isBottom()) return true;
            if (other.isBottom()) return false;
            if (other.isTop()) return true;
            if (this.isTop()) return false;

            if (other.min != null && this.min != null) {
                if (this.min.compareTo(other.min) < 0) return false;
            } else if (other.min != null && this.min == null) {
                return false; // this has -∞, other doesn't
            }

            if (other.max != null && this.max != null) {
                if (this.max.compareTo(other.max) > 0) return false;
            } else if (other.max != null && this.max == null) {
                return false; // this has +∞, other doesn't
            }

            return true;
        }

        public Interval assumeEq(BigInteger value) {
            if (isBottom()) return BOTTOM;
            if (isTop()) return of(value, value);

            if (min != null && max != null) {
                if (value.compareTo(min) >= 0 && value.compareTo(max) <= 0) {
                    return of(value, value);
                }
                return BOTTOM;
            } else if (min != null) {
                if (value.compareTo(min) >= 0) {
                    return of(value, value);
                }
                return BOTTOM;
            } else if (max != null) {
                if (value.compareTo(max) <= 0) {
                    return of(value, value);
                }
                return BOTTOM;
            }
            return of(value, value);
        }

        public Interval assumeNe(BigInteger value) {
            if (isBottom()) return BOTTOM;
            if (isTop()) return TOP;

            if (min != null && max != null && min.equals(max) && min.equals(value)) {
                return BOTTOM;
            }

            return this;
        }

        public Interval assumeLt(BigInteger value) {
            if (isBottom()) return BOTTOM;
            if (isTop()) return of(null, value.subtract(BigInteger.ONE));

            if (max != null && max.compareTo(value) >= 0) {
                BigInteger newMax = value.subtract(BigInteger.ONE);
                if (min != null && min.compareTo(newMax) > 0) {
                    return BOTTOM;
                }
                return of(min, newMax);
            }
            return this;
        }

        public Interval assumeLe(BigInteger value) {
            if (isBottom()) return BOTTOM;
            if (isTop()) return of(null, value);

            if (max != null && max.compareTo(value) > 0) {
                BigInteger newMax = value;
                if (min != null && min.compareTo(newMax) > 0) {
                    return BOTTOM;
                }
                return of(min, newMax);
            }
            return this;
        }

        public Interval assumeGt(BigInteger value) {
            if (isBottom()) return BOTTOM;
            if (isTop()) return of(value.add(BigInteger.ONE), null);

            if (min != null && min.compareTo(value) <= 0) {
                BigInteger newMin = value.add(BigInteger.ONE);
                if (max != null && newMin.compareTo(max) > 0) {
                    return BOTTOM;
                }
                return of(newMin, max);
            }
            return this;
        }

        public Interval assumeGe(BigInteger value) {
            if (isBottom()) return BOTTOM;
            if (isTop()) return of(value, null);

            if (min != null && min.compareTo(value) < 0) {
                BigInteger newMin = value;
                if (max != null && newMin.compareTo(max) > 0) {
                    return BOTTOM;
                }
                return of(newMin, max);
            }
            return this;
        }

        @Override
        public String toString() {
            if (isBottom()) return "⊥";
            if (isTop()) return "⊤";
            return "[" + (min == null ? "-∞" : min) + ", " + (max == null ? "+∞" : max) + "]";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Interval)) return false;
            Interval other = (Interval) obj;
            return isBottom == other.isBottom &&
                   Objects.equals(min, other.min) &&
                   Objects.equals(max, other.max);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isBottom, min, max);
        }
    }

    public IntervalDomain() {
    }

    @Override
    public String name() {
        return "IntervalDomain";
    }

    @Override
    public State top() {
        return State.top();
    }

    @Override
    public State bottom() {
        return State.bottom();
    }

    @Override
    public boolean isBottom(State s) {
        return s.isBottom();
    }

    @Override
    public boolean entails(State a, State b) {
        if (a.isBottom()) return true;
        if (b.isBottom()) return false;

        for (Map.Entry<String, Interval> entry : a.getIntervals().entrySet()) {
            Interval aInt = entry.getValue();
            Interval bInt = b.getInterval(entry.getKey());
            if (!aInt.entails(bInt)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public State join(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;

        Map<String, Interval> result = new HashMap<>(a.getIntervals());

        for (Map.Entry<String, Interval> entry : b.getIntervals().entrySet()) {
            String var = entry.getKey();
            Interval aInt = a.getInterval(var);
            Interval bInt = entry.getValue();
            result.put(var, aInt.join(bInt));
        }

        return State.fromMap(result);
    }

    @Override
    public State widen(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;

        Map<String, Interval> result = new HashMap<>(a.getIntervals());

        for (Map.Entry<String, Interval> entry : b.getIntervals().entrySet()) {
            String var = entry.getKey();
            Interval aInt = a.getInterval(var);
            Interval bInt = entry.getValue();
            result.put(var, aInt.widen(bInt));
        }

        return State.fromMap(result);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom()) {
            return State.bottom();
        }

        switch (step) {
            case Step.Assign assign -> {
                return transferAssign(assign, post);
            }
            case Step.Assume assume -> {
                return transferAssume(assume, post);
            }
            case Step.Call call -> {
                return transferCall(call, post);
            }
        }
        throw new UnsupportedOperationException("Unknown step type");
    }

    private State transferAssign(Step.Assign assign, State post) {
        String varName = assign.target().name();
        RVal source = assign.source();

        Interval targetPostInterval = post.getInterval(varName);

        // Check if source can satisfy the post constraint
        if (source instanceof RVal.IntConst constVal) {
            BigInteger constValue = constVal.v();
            if (targetPostInterval.getMin() != null && 
                constValue.compareTo(targetPostInterval.getMin()) < 0) {
                return State.bottom();
            }
            if (targetPostInterval.getMax() != null && 
                constValue.compareTo(targetPostInterval.getMax()) > 0) {
                return State.bottom();
            }
        }

        // The target variable is unconstrained before the assignment
        Map<String, Interval> result = new HashMap<>(post.getIntervals());
        result.remove(varName);

        return State.fromMap(result);
    }

    private State transferAssume(Step.Assume assume, State post) {
        RVal cond = assume.cond();

        if (cond instanceof RVal.Binop binop) {
            return transferAssumeBinop(binop, post);
        }

        return post;
    }

    private State transferAssumeBinop(RVal.Binop binop, State post) {
        BinOp op = binop.op();
        RVal l = binop.l();
        RVal r = binop.r();

        if (l instanceof RVal.IntConst && r instanceof LVal.Local) {
            BigInteger lVal = ((RVal.IntConst) l).v();
            LVal.Local rLocal = (LVal.Local) r;
            String varName = rLocal.name();
            Interval varInt = post.getInterval(varName);
            Interval newInt = applyComparisonToVar(varInt, op, lVal, false);
            Map<String, Interval> result = new HashMap<>(post.getIntervals());
            result.put(varName, newInt);
            return State.fromMap(result);
        } else if (l instanceof LVal.Local && r instanceof RVal.IntConst) {
            BigInteger rVal = ((RVal.IntConst) r).v();
            LVal.Local lLocal = (LVal.Local) l;
            String varName = lLocal.name();
            Interval varInt = post.getInterval(varName);
            Interval newInt = applyComparisonToVar(varInt, op, rVal, true);
            Map<String, Interval> result = new HashMap<>(post.getIntervals());
            result.put(varName, newInt);
            return State.fromMap(result);
        }

        return post;
    }

    private Interval applyComparisonToVar(Interval varInt, BinOp op, BigInteger constant, boolean swapped) {
        switch (op) {
            case Eq:
                return varInt.assumeEq(constant);
            case Ne:
                return varInt.assumeNe(constant);
            case Lt:
                return varInt.assumeLt(constant);
            case Le:
                return varInt.assumeLe(constant);
            case Gt:
                return varInt.assumeGt(constant);
            case Ge:
                return varInt.assumeGe(constant);
            default:
                return varInt;
        }
    }

    private State transferCall(Step.Call call, State post) {
        if (call.target().isPresent()) {
            String varName = call.target().get().name();
            Map<String, Interval> result = new HashMap<>(post.getIntervals());
            result.remove(varName);
            return State.fromMap(result);
        }
        return post;
    }
}
