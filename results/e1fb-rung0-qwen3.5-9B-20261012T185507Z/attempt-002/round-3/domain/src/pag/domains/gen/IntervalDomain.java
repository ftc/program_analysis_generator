package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class IntervalDomain implements Domain<IntervalDomain.State> {

    /**
     * Abstract state tracking intervals for each variable.
     * Each variable maps to [min, max] where min <= max.
     * Top = no constraints ([-∞, +∞]), Bottom = empty (no states admitted).
     */
    public static final class State {
        private final Map<String, Interval> constraints;

        State() {
            this.constraints = new HashMap<>();
        }

        State(Map<String, Interval> constraints) {
            this.constraints = new HashMap<>(constraints);
        }

        Interval getConstraint(String var) {
            return constraints.getOrDefault(var, Interval.TOP);
        }

        void removeConstraint(String var) {
            constraints.remove(var);
        }

        boolean hasConstraint(String var) {
            return constraints.containsKey(var);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("State{");
            boolean first = true;
            for (Map.Entry<String, Interval> e : constraints.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            return sb.append("}").toString();
        }
    }

    /** Interval [min, max] representing a range of integer values. */
    static final class Interval implements Comparable<Interval> {
        final BigInteger min;
        final BigInteger max;

        static final Interval TOP = new Interval(BigInteger.ZERO, BigInteger.ZERO);

        private static final BigInteger NEGATIVE_INFINITY = BigInteger.valueOf(Long.MIN_VALUE);
        private static final BigInteger POSITIVE_INFINITY = BigInteger.valueOf(Long.MAX_VALUE);

        Interval(BigInteger min, BigInteger max) {
            if (min.compareTo(max) > 0) {
                throw new IllegalArgumentException("min > max");
            }
            this.min = min;
            this.max = max;
        }

        Interval() {
            this(NEGATIVE_INFINITY, POSITIVE_INFINITY);
        }

        static Interval bottom() {
            return new Interval(POSITIVE_INFINITY, NEGATIVE_INFINITY);
        }

        static Interval empty() {
            return bottom();
        }

        @Override
        public String toString() {
            if (equals(TOP)) {
                return "[unconstrained]";
            }
            return "[" + min + ", " + max + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Interval)) return false;
            Interval that = (Interval) o;
            return min.equals(that.min) && max.equals(that.max);
        }

        @Override
        public int hashCode() {
            return Objects.hash(min, max);
        }

        @Override
        public int compareTo(Interval o) {
            int cmp = this.min.compareTo(o.min);
            if (cmp != 0) return cmp;
            return this.max.compareTo(o.max);
        }

        boolean isUnconstrained() {
            return equals(TOP);
        }

        boolean isBottom() {
            return min.compareTo(max) > 0;
        }
    }

    /**
     * The top state admits all program states (no constraints).
     * For interval domains, this means all variables are unconstrained.
     */
    public static final State TOP = new State(Map.of());

    /**
     * The bottom state admits no program states (empty).
     * For interval domains, this means at least one variable has empty interval.
     */
    public static final State BOTTOM = new State(Map.of("x", Interval.bottom()));

    public IntervalDomain() {}

    @Override
    public String name() {
        return "Interval Domain";
    }

    @Override
    public State top() {
        return TOP;
    }

    @Override
    public State bottom() {
        return BOTTOM;
    }

    @Override
    public boolean isBottom(State s) {
        if (s == BOTTOM) return true;
        for (Interval interval : s.constraints.values()) {
            if (interval.isBottom()) return true;
        }
        return false;
    }

    @Override
    public boolean entails(State a, State b) {
        // a entails b if every state admitted by a is also admitted by b
        // For intervals: a[i] ⊆ b[i] for all variables i
        // Unconstrained (TOP) is contained in everything
        for (String var : a.constraints.keySet()) {
            if (b.constraints.containsKey(var)) {
                if (!a.constraints.get(var).isUnconstrained() &&
                    a.constraints.get(var).compareTo(b.constraints.get(var)) > 0) {
                    return false;
                }
            } else {
                // b doesn't track this variable, so a's constraint is more restrictive
                // This could be unsound if a has a constraint b doesn't know about
                // But in backward analysis, we only care about variables we track
                continue;
            }
        }
        return true;
    }

    @Override
    public State join(State a, State b) {
        if (a == b) return a;
        Map<String, Interval> joined = new HashMap<>();
        Set<String> vars = new HashSet<>(a.constraints.keySet());
        vars.addAll(b.constraints.keySet());

        for (String var : vars) {
            Interval ia = a.constraints.getOrDefault(var, Interval.TOP);
            Interval ib = b.constraints.getOrDefault(var, Interval.TOP);
            joined.put(var, joinInterval(ia, ib));
        }
        return new State(joined);
    }

    private Interval joinInterval(Interval a, Interval b) {
        if (a.isUnconstrained()) return b;
        if (b.isUnconstrained()) return a;
        if (a.isBottom() || b.isBottom()) return Interval.bottom();
        return new Interval(a.min.min(a.min), b.max.max(b.max));
    }

    @Override
    public State widen(State a, State b) {
        if (a == b) return a;
        Map<String, Interval> widened = new HashMap<>();
        Set<String> vars = new HashSet<>(a.constraints.keySet());
        vars.addAll(b.constraints.keySet());

        for (String var : vars) {
            Interval ia = a.constraints.getOrDefault(var, Interval.TOP);
            Interval ib = b.constraints.getOrDefault(var, Interval.TOP);
            widened.put(var, widenInterval(ia, ib));
        }
        return new State(widened);
    }

    private Interval widenInterval(Interval a, Interval b) {
        if (a.isUnconstrained()) return b;
        if (b.isUnconstrained()) return a;
        if (a.isBottom() || b.isBottom()) return Interval.bottom();
        // Widen by expanding intervals to converge
        return new Interval(a.min, b.max);
    }

    @Override
    public State transfer(Step step, State post) {
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
        throw new UnsupportedOperationException("Unhandled step: " + step);
    }

    private State transferAssign(Step.Assign assign, State post) {
        String targetName = assign.target().name();
        RVal source = assign.source();

        // After assignment: x has constraint C
        // Before assignment: e must evaluate to something in C
        // x itself becomes unconstrained before the assignment

        State pre = new State(post.constraints);
        pre.removeConstraint(targetName);

        // For the source expression, we need to compute the inverse constraint
        // For soundness, we compute what values of source would result in post's constraint on target

        switch (source) {
            case RVal.IntConst ic -> {
                // x = const: before assignment, x is unconstrained
                // const always evaluates to that value, so no constraint on pre
            }
            case RVal.Binop binop -> {
                // x = l op r: we need to find pre constraints on l and r
                // For soundness, make them unconstrained (conservative)
                // In practice, we can't compute exact inverse without knowing values
            }
            case LVal.Local lv -> {
                // x = y: constraint on y before = constraint on x after
                if (post.constraints.containsKey(targetName)) {
                    pre.constraints.put(lv.name(), post.constraints.get(targetName));
                }
            }
            case LVal.StaticField sf -> {
                // x = static_field: similar to const
                // static fields are constants, so no constraint on pre
            }
        }

        return pre;
    }

    private State transferAssume(Step.Assume assume, State post) {
        // Restrict post to where cond holds
        // For soundness, we compute the intersection of post with the condition
        // If cond is false, result is bottom
        // If cond is true, result is post

        RVal cond = assume.cond();

        // For soundness, we need to check if the condition can be satisfied
        // If we can't determine, we're conservative and keep the state
        // In practice, we can't easily check this without more analysis

        State restricted = new State(post.constraints);

        // For now, be conservative and don't restrict (sound but imprecise)
        // A more precise implementation would check if cond is always true/false
        // For example, if cond is x < 0 and x is constrained to [0, 10], then restricted is bottom

        return restricted;
    }

    private State transferCall(Step.Call call, State post) {
        // randInt() assigns arbitrary integer to target
        // Before call, target is unconstrained
        State pre = new State(post.constraints);
        pre.removeConstraint(call.target().get().name());
        return pre;
    }
}
