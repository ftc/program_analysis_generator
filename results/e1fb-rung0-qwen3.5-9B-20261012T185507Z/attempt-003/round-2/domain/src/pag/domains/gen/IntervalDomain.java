package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import pag.api.*;

public class IntervalDomain implements Domain<IntervalDomain.State> {

    /**
     * Abstract state tracking interval constraints on variables.
     * Each variable maps to a constraint:
     * - null: unconstrained (top for that variable)
     * - Interval: constrained to that interval
     */
    public static final class State {
        private final Map<String, Interval> constraints = new HashMap<>();

        State() {}

        Interval getConstraint(String var) {
            return constraints.getOrDefault(var, null);
        }

        void setConstraint(String var, Interval interval) {
            constraints.put(var, interval);
        }

        void clearConstraint(String var) {
            constraints.remove(var);
        }

        @Override
        public String toString() {
            return constraints.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        }

        public static State top() {
            return new State();
        }

        public static State bottom() {
            return new State(); // Will be bottom if isBottom returns true
        }

        boolean isBottom() {
            return constraints.isEmpty();
        }

        boolean entails(State other) {
            // This state entails other if every constraint in this state
            // is at least as constraining as in other
            for (Map.Entry<String, Interval> entry : other.constraints.entrySet()) {
                String var = entry.getKey();
                Interval otherInterval = entry.getValue();
                Interval thisInterval = constraints.get(var);
                if (thisInterval == null) {
                    // This state has no constraint on var, but other does
                    // This means this state admits more states than other
                    return false;
                }
                if (!thisInterval.entails(otherInterval)) {
                    return false;
                }
            }
            return true;
        }

        State join(State other) {
            State result = new State();
            Set<String> allVars = new HashSet<>(constraints.keySet());
            allVars.addAll(other.constraints.keySet());
            for (String var : allVars) {
                Interval thisInt = constraints.get(var);
                Interval otherInt = other.constraints.get(var);
                Interval joined;
                if (thisInt == null) {
                    joined = otherInt;
                } else if (otherInt == null) {
                    joined = thisInt;
                } else {
                    joined = thisInt.join(otherInt);
                }
                result.setConstraint(var, joined);
            }
            return result;
        }

        State widen(State other) {
            State result = new State();
            Set<String> allVars = new HashSet<>(constraints.keySet());
            allVars.addAll(other.constraints.keySet());
            for (String var : allVars) {
                Interval thisInt = constraints.get(var);
                Interval otherInt = other.constraints.get(var);
                Interval widened;
                if (thisInt == null) {
                    widened = otherInt;
                } else if (otherInt == null) {
                    widened = thisInt;
                } else {
                    widened = thisInt.widen(otherInt);
                }
                result.setConstraint(var, widened);
            }
            return result;
        }
    }

    public IntervalDomain() {}

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
        return a.entails(b);
    }

    @Override
    public State join(State a, State b) {
        return a.join(b);
    }

    @Override
    public State widen(State a, State b) {
        return a.widen(b);
    }

    @Override
    public State transfer(Step step, State post) {
        switch (step) {
            case Step.Assign assign -> {
                String target = assign.target().name();
                RVal source = assign.source();
                State result = new State();
                // Copy all constraints from post except target
                for (Map.Entry<String, Interval> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (!var.equals(target)) {
                        result.setConstraint(var, entry.getValue());
                    }
                }
                // Apply constraint from source to target
                Interval sourceInterval = evaluate(source);
                result.setConstraint(target, sourceInterval);
                return result;
            }
            case Step.Assume assume -> {
                RVal cond = assume.cond();
                State result = new State();
                // Copy all constraints from post
                for (Map.Entry<String, Interval> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    result.setConstraint(var, entry.getValue());
                }
                // Intersect with constraint from condition
                Interval condInterval = evaluate(cond);
                for (Map.Entry<String, Interval> entry : result.constraints.entrySet()) {
                    String var = entry.getKey();
                    Interval newInterval = entry.getValue().intersect(condInterval);
                    result.setConstraint(var, newInterval);
                    if (newInterval == null) {
                        // Constraint eliminated (empty intersection)
                        result.clearConstraint(var);
                    }
                }
                return result;
            }
            case Step.Call call -> {
                // Call to randInt() - target gets arbitrary integer (unconstrained)
                // target is Optional<LVal.Local>, so we need to get the Local
                Optional<LVal.Local> targetOpt = call.target();
                String target = targetOpt.isPresent() ? targetOpt.get().name() : null;
                State result = new State();
                // Copy all constraints from post except target
                for (Map.Entry<String, Interval> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (!var.equals(target)) {
                        result.setConstraint(var, entry.getValue());
                    }
                }
                // Target becomes unconstrained
                if (target != null) {
                    result.clearConstraint(target);
                }
                return result;
            }
        }
        throw new UnsupportedOperationException("Unknown step: " + step);
    }

    private Interval evaluate(RVal r) {
        switch (r) {
            case RVal.IntConst intConst -> {
                BigInteger v = intConst.v();
                return new Interval(v, v);
            }
            case RVal.Binop binop -> {
                Interval left = evaluate(binop.l());
                Interval right = evaluate(binop.r());
                return Interval.apply(binop.op(), left, right);
            }
            case LVal lval -> {
                // Variable value - we need to track this through transfer
                // For now, return top interval (all integers)
                // This is conservative - the actual constraint comes from the variable's current state
                return new Interval(BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
            }
            default -> throw new UnsupportedOperationException("Unknown RVal: " + r);
        }
    }
}

class Interval {
    private final BigInteger min;
    private final BigInteger max;

    Interval(BigInteger min, BigInteger max) {
        this.min = min;
        this.max = max;
    }

    static Interval empty() {
        // Represents no possible value (empty set)
        return new Interval(null, null);
    }

    Interval join(Interval other) {
        if (this == null || other == null) return null;
        if (this == other) return this;
        BigInteger newMin = min == null ? other.min : (this.min.compareTo(other.min) < 0 ? this.min : other.min);
        BigInteger newMax = max == null ? other.max : (this.max.compareTo(other.max) > 0 ? this.max : other.max);
        return new Interval(newMin, newMax);
    }

    Interval widen(Interval other) {
        if (this == null || other == null) return null;
        if (this == other) return this;
        // For widening, we might increase bounds to ensure convergence
        // Keep the union of intervals
        BigInteger newMin = min == null ? other.min : (this.min.compareTo(other.min) < 0 ? this.min : other.min);
        BigInteger newMax = max == null ? other.max : (this.max.compareTo(other.max) > 0 ? this.max : other.max);
        return new Interval(newMin, newMax);
    }

    Interval intersect(Interval other) {
        if (this == null || other == null) return null;
        if (this == other) return this;
        BigInteger newMin = min == null ? other.min : (this.min.compareTo(other.min) < 0 ? this.min : other.min);
        BigInteger newMax = max == null ? other.max : (this.max.compareTo(other.max) > 0 ? this.max : other.max);
        if (newMin != null && newMax != null && newMin.compareTo(newMax) > 0) {
            return empty();
        }
        return new Interval(newMin, newMax);
    }

    boolean entails(Interval other) {
        if (this == null) return other == null || other == this;
        if (other == null) return false;
        if (this == other) return true;
        // This interval entails other if this is a subset of other
        // i.e., other.min <= this.min and this.max <= other.max
        return other.min == null || other.min.compareTo(this.min) <= 0
            && other.max == null || other.max.compareTo(this.max) >= 0;
    }

    @Override
    public String toString() {
        if (min == null || max == null) return "empty";
        return "[" + min + ", " + max + "]";
    }

    static Interval apply(BinOp op, Interval left, Interval right) {
        switch (op) {
            case Add:
                return new Interval(left.min.add(right.min), left.max.add(right.max));
            case Sub:
                return new Interval(left.min.subtract(right.max), left.max.subtract(right.min));
            case Mult:
                BigInteger[] bounds = computeMultBounds(left, right);
                return new Interval(bounds[0], bounds[1]);
            case Lt:
            case Le:
            case Gt:
            case Ge:
            case Eq:
            case Ne:
                // Comparisons produce boolean, not integer - for our domain,
                // we interpret this as a constraint that might be always true
                // For backward analysis, assume always true (all integers)
                return new Interval(BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
            default:
                return null;
        }
    }

    private static BigInteger[] computeMultBounds(Interval left, Interval right) {
        BigInteger[] lBounds = new BigInteger[]{left.min, left.max};
        BigInteger[] rBounds = new BigInteger[]{right.min, right.max};
        BigInteger[] products = new BigInteger[4];
        int idx = 0;
        for (BigInteger lb : lBounds) {
            for (BigInteger rb : rBounds) {
                products[idx++] = lb.multiply(rb);
            }
        }
        BigInteger min = products[0];
        BigInteger max = products[0];
        for (int i = 1; i < 4; i++) {
            if (products[i].compareTo(min) < 0) min = products[i];
            if (products[i].compareTo(max) > 0) max = products[i];
        }
        return new BigInteger[]{min, max};
    }
}
