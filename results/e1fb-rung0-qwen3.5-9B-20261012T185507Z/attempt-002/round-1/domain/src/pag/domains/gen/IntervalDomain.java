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
        private final Map<String, Interval> constraints = new HashMap<>();
        private final Set<String> variables = new HashSet<>();

        State() {}

        State(Map<String, Interval> constraints) {
            this.constraints = constraints;
            this.variables = new HashSet<>(constraints.keySet());
        }

        Interval getConstraint(String var) {
            return constraints.computeIfAbsent(var, k -> Interval.TOP);
        }

        void removeConstraint(String var) {
            constraints.remove(var);
            variables.remove(var);
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
        // If a has no constraint on x, it entails b's constraint on x
        for (String var : a.variables) {
            if (b.variables.contains(var)) {
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
        Set<String> vars = new HashSet<>(a.variables);
        vars.addAll(b.variables);

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
        Set<String> vars = new HashSet<>(a.variables);
        vars.addAll(b.variables);

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
            case Assign assign -> {
                String target = assign.target().name();
                RVal source = assign.source();
                State pre = new State();
                pre.constraints.putAll(post.constraints);

                // After assignment: x has constraint C
                // Before assignment: e must evaluate to something in C
                // x itself becomes unconstrained before the assignment
                pre.removeConstraint(target);

                // Compute constraint on source expression
                // post's constraint on target becomes constraint on source
                // We need to invert the operation
                BigInteger sourceMin = null;
                BigInteger sourceMax = null;

                switch (source) {
                    case RVal.IntConst ic -> {
                        // x = const has no effect on pre (const is always valid)
                        // pre is just post with x unconstrained
                    }
                    case RVal.Binop binop -> {
                        // x = l op r, need to find pre constraints on l and r
                        // For soundness, we compute the inverse
                        BigInteger[] bounds = computeInverseBinop(source, post.constraints.get(target));
                        if (bounds != null) {
                            sourceMin = bounds[0];
                            sourceMax = bounds[1];
                        }
                    }
                    case LVal.Local lv -> {
                        // x = y, pre constraint on y = post constraint on x
                        Interval postConstraint = post.constraints.get(target);
                        if (postConstraint.isUnconstrained()) {
                            pre.constraints.remove(lv.name());
                        } else {
                            pre.constraints.put(lv.name(), postConstraint);
                        }
                    }
                    case LVal.StaticField sf -> {
                        // x = static_field, static fields are constants
                        // pre is just post with x unconstrained
                    }
                }

                if (sourceMin != null && sourceMax != null) {
                    pre.constraints.put(source.name(), new Interval(sourceMin, sourceMax));
                }

                return pre;
            }
            case Assume assume -> {
                // Restrict post to where cond holds
                // If cond is false, result is bottom
                // If cond is true, result is post
                RVal cond = assume.cond();
                return restrictToConstraint(post, cond);
            }
            case Call call -> {
                // randInt() assigns arbitrary integer to target
                // Before call, target is unconstrained
                State pre = new State(post.constraints);
                pre.constraints.remove(call.target().name());
                return pre;
            }
        }
        throw new UnsupportedOperationException("Unhandled step: " + step);
    }

    /**
     * Restrict a state to where the condition holds.
     * For soundness, we compute the intersection of post with the condition.
     */
    private State restrictToConstraint(State post, RVal cond) {
        State restricted = new State(post.constraints);
        BigInteger min = null;
        BigInteger max = null;

        switch (cond) {
            case RVal.IntConst ic -> {
                // Integer constants are always true or always false
                // But we can't determine this without knowing if it's compared
                // Conservative: assume it might be true
            }
            case RVal.Binop binop -> {
                // For soundness, we need to compute the constraint on post's variables
                // that satisfies the condition
                BigInteger[] bounds = computeInverseBinop(cond, null);
                if (bounds != null) {
                    min = bounds[0];
                    max = bounds[1];
                }
            }
            case LVal.Local lv -> {
                // Restrict post's constraint on lv
                Interval interval = restricted.constraints.get(lv.name());
                if (interval != null && !interval.isUnconstrained()) {
                    // We need to intersect with the condition
                    // For now, be conservative and keep the constraint
                }
            }
            case LVal.StaticField sf -> {
                // Static fields are constants, always evaluate to a fixed value
            }
        }

        if (min != null && max != null) {
            restricted.constraints.put("cond_var", new Interval(min, max));
        }

        return restricted;
    }

    /**
     * Compute inverse constraint for a binary operation.
     * Returns [min, max] for the result that would satisfy the operation.
     * Returns null if we can't compute a meaningful constraint.
     */
    private BigInteger[] computeInverseBinop(RVal binop, BigInteger postValue) {
        switch (binop) {
            case RVal.Binop inner -> {
                return computeInverseBinop(inner, postValue);
            }
            default -> {
                // Can't handle static fields or locals in inverse
                return null;
            }
        }
    }

    /**
     * Simplified version for the actual transfer.
     * For Assign(x = y), we put post's constraint on x into y's constraint.
     * For Assign(x = const), x is unconstrained before, const is always valid.
     */
    private State transferAssign(State post, Assign assign) {
        String targetName = assign.target().name();
        String sourceName = null;

        switch (assign.source()) {
            case RVal.IntConst ic -> {
                // x = const: before assignment, x is unconstrained
                // const always evaluates to that value
                State pre = new State(post.constraints);
                pre.constraints.remove(targetName);
                return pre;
            }
            case RVal.Binop binop -> {
                // x = l op r: we need to find pre constraints on l and r
                // For soundness, we make them unconstrained (conservative)
                State pre = new State(post.constraints);
                pre.constraints.remove(targetName);
                return pre;
            }
            case LVal.Local lv -> {
                // x = y: constraint on y before = constraint on x after
                State pre = new State(post.constraints);
                pre.constraints.remove(targetName);
                if (post.constraints.containsKey(lv.name())) {
                    pre.constraints.put(lv.name(), post.constraints.get(lv.name()));
                }
                return pre;
            }
            case LVal.StaticField sf -> {
                // x = static_field: similar to const
                State pre = new State(post.constraints);
                pre.constraints.remove(targetName);
                return pre;
            }
        }
        throw new UnsupportedOperationException();
    }

    private BigInteger[] computeBinopBounds(RVal binop, BigInteger value) {
        switch (binop) {
            case RVal.Binop inner -> {
                return computeBinopBounds(inner, value);
            }
            default -> {
                return null;
            }
        }
    }
}
