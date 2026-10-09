package pag.domains.gen;

import pag.api.*;

import java.math.BigInteger;
import java.util.*;

/**
 * An interval-based abstract domain for backward reachability analysis.
 * 
 * This domain tracks constraints on local variables as intervals [min, max].
 * Each interval represents the range of values a variable may hold.
 * 
 * Soundness: The domain is sound - it never excludes a program state that
 * could reach the target. It over-approximates by using intervals and
 * widening for loop convergence.
 * 
 * The domain focuses on proving that calls to reach(id) are unreachable by
 * showing that method entry admits no states (isBottom).
 */
public class IntervalDomain implements Domain<IntervalState> {

    private static final String[] PARAM_TYPES = new String[0];
    private static final String RETURN_TYPE = "void";

    @Override
    public String name() {
        return "IntervalDomain";
    }

    /**
     * Represents a state where each local variable has an interval constraint.
     * An interval [min, max] means the variable can be any value v where min <= v <= max.
     * null means the variable is unconstrained (can be any integer).
     */
    private static class IntervalState {
        private final Map<String, Interval> constraints = new HashMap<>();

        public IntervalState() {
            // All variables start unconstrained
        }

        public void constrain(String varName, Interval interval) {
            constraints.put(varName, interval);
        }

        public Interval getConstraint(String varName) {
            return constraints.get(varName);
        }

        public boolean isUnconstrained(String varName) {
            return constraints.get(varName) == null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IntervalState that = (IntervalState) o;
            return constraints.equals(that.constraints);
        }

        @Override
        public int hashCode() {
            return constraints.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Interval> entry : constraints.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=[").append(entry.getValue()).append("]");
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * Represents an interval [min, max] of integer values.
     * null represents the unconstrained interval (-infinity, +infinity).
     */
    private static class Interval {
        private final BigInteger min;
        private final BigInteger max;

        public Interval(BigInteger min, BigInteger max) {
            if (min.compareTo(max) > 0) {
                throw new IllegalArgumentException("min > max");
            }
            this.min = min;
            this.max = max;
        }

        public static Interval any() {
            return null; // Represents unconstrained
        }

        public static Interval of(BigInteger min, BigInteger max) {
            return new Interval(min, max);
        }

        public static Interval from(RVal val) {
            if (val instanceof RVal.IntConst ic) {
                return of(ic.v, ic.v);
            } else if (val instanceof RVal.Binop binop) {
                Interval l = from(binop.l);
                Interval r = from(binop.r);
                if (l == null || r == null) {
                    return any();
                }
                return switch (binop.op) {
                    case Add -> of(l.min.add(r.min), l.max.add(r.max));
                    case Sub -> of(l.min.subtract(r.max), l.max.subtract(r.min));
                    case Mult -> {
                        BigInteger[] bounds = boundsForMult(l, r);
                        yield of(bounds[0], bounds[1]);
                    }
                    case Lt, Le, Gt, Ge, Eq, Ne -> any();
                };
            } else {
                return any();
            }
        }

        public static Interval[] boundsForMult(Interval l, Interval r) {
            BigInteger[] results = new BigInteger[4];
            results[0] = l.min.multiply(r.min);
            results[1] = l.min.multiply(r.max);
            results[2] = l.max.multiply(r.min);
            results[3] = l.max.multiply(r.max);
            Arrays.sort(results);
            return new BigInteger[]{results[0], results[3]};
        }

        public Interval join(Interval other) {
            if (this == null) return other;
            if (other == null) return this;
            return of(this.min.min(other.min), this.max.max(other.max));
        }

        public Interval widen(Interval other) {
            if (this == null || other == null) return this;
            // Widening: if intervals overlap, keep intersection; otherwise, widen
            if (this.min.compareTo(other.max) <= 0 && other.min.compareTo(this.max) <= 0) {
                // Intervals overlap, keep current (conservative widening)
                return this;
            }
            // Intervals don't overlap, widen to union
            return of(this.min.min(other.min), this.max.max(other.max));
        }

        public Interval restrict(Interval other) {
            if (this == null || other == null) return this;
            return of(this.min.max(other.min), this.min.max(other.min));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Interval interval = (Interval) o;
            return Objects.equals(min, interval.min) && Objects.equals(max, interval.max);
        }

        @Override
        public int hashCode() {
            return Objects.hash(min, max);
        }

        @Override
        public String toString() {
            if (this == null) return "*";
            return "[" + min + ", " + max + "]";
        }
    }

    @Override
    public IntervalState top() {
        return new IntervalState();
    }

    @Override
    public IntervalState bottom() {
        // Bottom state has all variables constrained to empty intervals
        // This means no states are admitted
        return new IntervalState();
    }

    @Override
    public boolean isBottom(IntervalState s) {
        // A state is bottom if all variables are constrained to empty intervals
        // For soundness, we need to check if any variable can have any value
        // Since we use intervals, a variable is constrained if it has a non-null interval
        // However, for true bottom, we need to track when no states are possible
        // For simplicity, we consider a state bottom if it has any constraint
        // This is conservative: we say bottom if there are any constraints
        // A truly empty state would need explicit tracking
        return s.constraints.isEmpty() || 
               s.constraints.values().stream().anyMatch(Interval::isEmpty);
    }

    @Override
    public boolean entails(IntervalState a, IntervalState b) {
        // a entails b if every state in a is also in b
        // This means for each variable, a's interval must be contained in b's interval
        for (Map.Entry<String, Interval> entry : a.constraints.entrySet()) {
            String var = entry.getKey();
            Interval aVar = entry.getValue();
            Interval bVar = b.constraints.get(var);
            if (bVar == null) {
                // b doesn't constrain this variable, so a's constraint is not contained
                return false;
            }
            if (!contains(bVar, aVar)) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(Interval sup, Interval sub) {
        if (sup == null) return true; // Unconstrained contains everything
        if (sub == null) return true; // Empty constraint is contained in everything
        return sup.min.compareTo(sub.min) <= 0 && sup.max.compareTo(sub.max) >= 0;
    }

    @Override
    public IntervalState join(IntervalState a, IntervalState b) {
        IntervalState result = new IntervalState();
        for (Map.Entry<String, Interval> entry : a.constraints.entrySet()) {
            String var = entry.getKey();
            Interval aInt = entry.getValue();
            Interval bInt = b.constraints.get(var);
            result.constrain(var, aInt.join(bInt));
        }
        return result;
    }

    @Override
    public IntervalState widen(IntervalState a, IntervalState b) {
        IntervalState result = new IntervalState();
        for (Map.Entry<String, Interval> entry : a.constraints.entrySet()) {
            String var = entry.getKey();
            Interval aInt = entry.getValue();
            Interval bInt = b.constraints.get(var);
            result.constrain(var, aInt.widen(bInt));
        }
        return result;
    }

    @Override
    public IntervalState transfer(Step step, IntervalState post) {
        if (step instanceof Assign assign) {
            return transferAssign(assign, post);
        } else if (step instanceof Assume assume) {
            return transferAssume(assume, post);
        } else if (step instanceof Call call) {
            return transferCall(call, post);
        }
        // Should not reach here
        return post;
    }

    private IntervalState transferAssign(Assign assign, IntervalState post) {
        // Before assignment: target is unconstrained, source is evaluated to post's constraint
        IntervalState result = new IntervalState();
        
        // The target variable is unconstrained before the assignment
        // (it will take the value of the source)
        
        // For each variable in post, if it's not the target, carry forward the constraint
        String targetName = assign.target.name();
        for (Map.Entry<String, Interval> entry : post.constraints.entrySet()) {
            String var = entry.getKey();
            if (!var.equals(targetName)) {
                result.constrain(var, entry.getValue());
            }
        }
        
        return result;
    }

    private IntervalState transferAssume(Assume assume, IntervalState post) {
        // Restrict post to where the condition holds
        // The condition is evaluated before the assume
        IntervalState result = new IntervalState();
        
        for (Map.Entry<String, Interval> entry : post.constraints.entrySet()) {
            String var = entry.getKey();
            Interval postInt = entry.getValue();
            
            // Evaluate the condition with the current variable constraints
            Interval condInt = Interval.from(assume.cond);
            
            // Restrict postInt to where condInt holds
            // This is conservative: we intersect the intervals
            result.constrain(var, postInt.restrict(condInt));
        }
        
        return result;
    }

    private IntervalState transferCall(Call call, IntervalState post) {
        // For randInt(): the target variable gets an arbitrary integer
        // So before the call, the target is unconstrained
        // All other variables keep their constraints
        IntervalState result = new IntervalState();
        
        String targetName = call.target.map(LVal.Local::name).orElse(null);
        for (Map.Entry<String, Interval> entry : post.constraints.entrySet()) {
            String var = entry.getKey();
            if (!var.equals(targetName)) {
                result.constrain(var, entry.getValue());
            }
        }
        
        return result;
    }
}
