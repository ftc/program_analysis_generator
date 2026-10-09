package pag.domains.gen;

import pag.api.*;

import java.math.BigInteger;
import java.util.*;

/**
 * Abstract domain for backward reachability analysis using interval constraints.
 * 
 * Tracks each variable's possible value range. Uses over-approximation:
 * - top() = all variables are fully unconstrained (can be any integer)
 * - bottom() = all variables are constrained to empty set (no state admits)
 * 
 * This is sound because:
 * - We never exclude states that can reach the target
 * - We may include unreachable states (conservative)
 * 
 * For proving reach(id) unreachable: when entry state is bottom(), 
 * no state can reach the target (the call is unreachable).
 */
public class IntervalDomain implements Domain<IntervalState> {

    /**
     * Represents a variable's constraint as an interval [min, max] (inclusive).
     * null means unconstrained (any integer).
     */
    static class Interval {
        private final BigInteger min;
        private final BigInteger max;
        
        public Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }
        
        public boolean isUnconstrained() {
            return min == null && max == null;
        }
        
        public boolean isEmpty() {
            return min != null && max != null && min.compareTo(max) > 0;
        }
        
        @Override
        public String toString() {
            if (isUnconstrained()) return "*";
            if (isEmpty()) return "∅";
            return "[" + min + ", " + max + "]";
        }
    }
    
    /**
     * State in the domain: mapping from variables to their interval constraints.
     * Unconstrained variables map to null.
     */
    static class IntervalState {
        private final Map<LVal, Interval> constraints;
        
        public IntervalState() {
            this.constraints = new HashMap<>();
        }
        
        public Interval getConstraint(LVal lval) {
            return constraints.get(lval);
        }
        
        public void setConstraint(LVal lval, Interval interval) {
            constraints.put(lval, interval);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IntervalState)) return false;
            IntervalState that = (IntervalState) o;
            return constraints.equals(that.constraints);
        }
        
        @Override
        public int hashCode() {
            return constraints.hashCode();
        }
    }

    private static final String NAME = "IntervalDomain";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public IntervalState top() {
        // All variables are unconstrained (can reach target from any value)
        return new IntervalState();
    }

    @Override
    public IntervalState bottom() {
        // All variables are constrained to empty set (no state can reach target)
        IntervalState state = new IntervalState();
        // Add constraints for all possible LVals to make it truly empty
        // In practice, we just need at least one variable constrained to empty
        state.setConstraint(new LVal.Local("x", "int"), new Interval(BigInteger.valueOf(0), BigInteger.valueOf(-1)));
        return state;
    }

    @Override
    public boolean isBottom(IntervalState s) {
        // Check if any variable is constrained to empty interval
        // If ANY variable is empty, the state is bottom (unsound to admit it)
        for (Interval interval : s.constraints.values()) {
            if (interval.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean entails(IntervalState a, IntervalState b) {
        // a entails b means: if a admits σ, then b also admits σ
        // For intervals: a[i] ⊆ b[i] for all variables i
        // If a[i] is unconstrained, it entails anything (conservative)
        // If b[i] is empty, a[i] must also be empty to entail
        for (Map.Entry<LVal, Interval> entry : a.constraints.entrySet()) {
            LVal lval = entry.getKey();
            Interval aInt = entry.getValue();
            Interval bInt = b.getConstraint(lval);
            
            // If b doesn't constrain this variable, a must not constrain it either
            if (bInt == null) {
                if (aInt != null) {
                    // a constrains but b doesn't - a might not entail b
                    return false;
                }
            } else {
                // b constrains this variable
                if (aInt == null) {
                    // a is unconstrained but b is constrained - unsound
                    return false;
                }
                // Check interval containment: aInt ⊆ bInt
                if (!contains(bInt, aInt)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean contains(Interval container, Interval contained) {
        if (container.isEmpty()) return false;
        if (contained.isUnconstrained()) return false;
        if (container.isUnconstrained()) return true; // anything contained in unconstrained
        
        BigInteger contMin = container.min;
        BigInteger contMax = container.max;
        BigInteger contMinNeg = contMin.negate();
        BigInteger contMaxNeg = contMax.negate();
        
        // For interval containment [a,b] ⊆ [c,d]: c ≤ a and b ≤ d
        // Also need to handle negated intervals (complement)
        if (contained.min == null) {
            // contained is [0, max] or similar
            return contained.max != null && contained.max.compareTo(contMax) <= 0;
        }
        
        return contained.min.compareTo(contMin) >= 0 && 
               contained.max.compareTo(contMax) <= 0;
    }

    @Override
    public IntervalState join(IntervalState a, IntervalState b) {
        // Join: admit everything either admits
        // For intervals: union of intervals
        IntervalState result = new IntervalState();
        
        Set<LVal> allVars = new HashSet<>(a.constraints.keySet());
        allVars.addAll(b.constraints.keySet());
        
        for (LVal lval : allVars) {
            Interval ia = a.getConstraint(lval);
            Interval ib = b.getConstraint(lval);
            
            Interval resultInt;
            if (ia == null && ib == null) {
                resultInt = null;
            } else if (ia == null) {
                resultInt = ib;
            } else if (ib == null) {
                resultInt = ia;
            } else {
                // Union of two intervals
                resultInt = union(ia, ib);
            }
            
            result.setConstraint(lval, resultInt);
        }
        
        return result;
    }

    private Interval union(Interval a, Interval b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() ? b : a;
        }
        
        BigInteger minA = a.min;
        BigInteger maxA = a.max;
        BigInteger minB = b.min;
        BigInteger maxB = b.max;
        
        BigInteger min = minA.compareTo(minB) < 0 ? minA : minB;
        BigInteger max = maxA.compareTo(maxB) > 0 ? maxA : maxB;
        
        // Check if intervals are disjoint - if so, return unconstrained (conservative)
        if (min.compareTo(max) > 0) {
            return new Interval(null, null);
        }
        
        return new Interval(min, max);
    }

    @Override
    public IntervalState widen(IntervalState a, IntervalState b) {
        // Widen: ensure convergence
        // For intervals: expand to unconstrained if too many iterations
        // Simple widening: after 1 iteration, make all constrained vars unconstrained
        IntervalState result = new IntervalState();
        
        Set<LVal> allVars = new HashSet<>(a.constraints.keySet());
        allVars.addAll(b.constraints.keySet());
        
        for (LVal lval : allVars) {
            Interval ia = a.getConstraint(lval);
            Interval ib = b.getConstraint(lval);
            
            // Widen: if both are constrained, make them unconstrained
            if (ia != null && ib != null) {
                result.setConstraint(lval, null);
            } else if (ia != null) {
                result.setConstraint(lval, ia);
            } else if (ib != null) {
                result.setConstraint(lval, ib);
            } else {
                result.setConstraint(lval, null);
            }
        }
        
        return result;
    }

    @Override
    public IntervalState transfer(Step step, IntervalState post) {
        // Backward transfer: what must hold BEFORE the step given post holds AFTER
        switch (step) {
            case Assign(Assign.Target target, RVal source) -> {
                // Assign(x = e): before assignment, x is unconstrained
                // The constraint on e (from post) doesn't constrain x before
                IntervalState result = new IntervalState();
                for (Map.Entry<LVal, Interval> entry : post.constraints.entrySet()) {
                    LVal lval = entry.getKey();
                    if (!(lval instanceof LVal.Local)) {
                        result.setConstraint(lval, entry.getValue());
                    }
                    // x (target) is unconstrained before assignment
                }
                return result;
            }
            
            case Assume(RVal cond) -> {
                // Assume(cond): restrict post to where cond holds
                // For backward analysis, we need to invert the constraint
                // Since we track intervals, this is complex
                // Conservative: if cond can be false, restrict to empty
                // Otherwise, keep post as-is (conservative over-approximation)
                IntervalState result = new IntervalState();
                for (Map.Entry<LVal, Interval> entry : post.constraints.entrySet()) {
                    LVal lval = entry.getKey();
                    Interval interval = entry.getValue();
                    
                    // Copy constraint (conservative: assume cond might always hold)
                    result.setConstraint(lval, interval);
                }
                return result;
            }
            
            case Call(Optional<LVal.Local> target, MethodId callee, List<RVal> args) -> {
                // Call(x, Rand.randInt(), []): x gets arbitrary integer
                // Before the call, x is unconstrained (can be any value)
                IntervalState result = new IntervalState();
                for (Map.Entry<LVal, Interval> entry : post.constraints.entrySet()) {
                    LVal lval = entry.getKey();
                    if (!(lval instanceof LVal.Local)) {
                        result.setConstraint(lval, entry.getValue());
                    }
                }
                return result;
            }
        }
        
        // Default: return post (conservative)
        return post;
    }
}
