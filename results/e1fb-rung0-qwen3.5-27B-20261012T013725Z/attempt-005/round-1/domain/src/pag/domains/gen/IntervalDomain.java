package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * An interval domain for backward reachability analysis.
 * Tracks intervals [min, max] for each local variable.
 * Sound: never excludes states that can reach the target.
 */
public class IntervalDomain implements Domain<IntervalDomain.State> {

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
        for (String var : a.intervals.keySet()) {
            Interval aInt = a.intervals.get(var);
            Interval bInt = b.intervals.getOrDefault(var, Interval.top());
            if (!aInt.isContainedIn(bInt)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public State join(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.intervals.keySet());
        allVars.addAll(b.intervals.keySet());
        
        Map<String, Interval> newIntervals = new HashMap<>();
        for (String var : allVars) {
            Interval aInt = a.intervals.getOrDefault(var, Interval.top());
            Interval bInt = b.intervals.getOrDefault(var, Interval.top());
            newIntervals.put(var, aInt.join(bInt));
        }
        
        return new State(newIntervals, false);
    }

    @Override
    public State widen(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.intervals.keySet());
        allVars.addAll(b.intervals.keySet());
        
        Map<String, Interval> newIntervals = new HashMap<>();
        for (String var : allVars) {
            Interval aInt = a.intervals.getOrDefault(var, Interval.top());
            Interval bInt = b.intervals.getOrDefault(var, Interval.top());
            newIntervals.put(var, aInt.widen(bInt));
        }
        
        return new State(newIntervals, false);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom()) return post;
        
        if (step instanceof Step.Assign assign) {
            return transferAssign(assign, post);
        } else if (step instanceof Step.Assume assume) {
            return transferAssume(assume, post);
        } else if (step instanceof Step.Call call) {
            return transferCall(call, post);
        }
        
        return post;
    }

    private State transferAssign(Step.Assign assign, State post) {
        String targetVar = assign.target().name();
        RVal source = assign.source();
        
        // In backward analysis: the constraint on targetVar in post
        // becomes a constraint on the source expression in pre.
        // targetVar itself becomes unconstrained in pre.
        
        Map<String, Interval> newIntervals = new HashMap<>(post.intervals);
        newIntervals.remove(targetVar);
        
        // Get the constraint from post[targetVar]
        Interval targetInterval = post.getInterval(targetVar);
        
        // Apply this constraint to the source expression
        if (!targetInterval.isEmpty() && !targetInterval.isTop()) {
            Map<String, Interval> refined = applyConstraintToExpression(source, targetInterval, newIntervals);
            if (refined == null) {
                return bottom();
            }
            newIntervals = refined;
        }
        
        return new State(newIntervals, false);
    }

    private Map<String, Interval> applyConstraintToExpression(RVal expr, Interval constraint, Map<String, Interval> intervals) {
        if (expr instanceof LVal.Local local) {
            // Simple case: source is a variable
            String varName = local.name();
            Interval currentInterval = intervals.getOrDefault(varName, Interval.top());
            Interval newInterval = currentInterval.meet(constraint);
            if (newInterval.isEmpty()) {
                return null; // Inconsistent -> bottom
            }
            Map<String, Interval> result = new HashMap<>(intervals);
            result.put(varName, newInterval);
            return result;
        } else if (expr instanceof RVal.IntConst intConst) {
            // Constant: check if constraint is satisfied
            if (!constraint.contains(intConst.v())) {
                return null; // Inconsistent -> bottom
            }
            return intervals; // No change needed
        } else if (expr instanceof RVal.Binop binop) {
            // Complex expression: for soundness, we can't precisely track
            // Just return intervals unchanged (lose precision but stay sound)
            return intervals;
        }
        return intervals;
    }

    private State transferAssume(Step.Assume assume, State post) {
        RVal cond = assume.cond();
        
        // In backward analysis: Assume(c) means we only consider states where c holds
        // So we restrict post to states where cond evaluates to true
        
        Map<String, Interval> newIntervals = new HashMap<>(post.intervals);
        Map<String, Interval> restricted = restrictByCondition(cond, newIntervals);
        
        if (restricted == null) {
            return bottom();
        }
        
        return new State(restricted, false);
    }

    private Map<String, Interval> restrictByCondition(RVal cond, Map<String, Interval> intervals) {
        if (cond instanceof RVal.Binop binop) {
            BinOp op = binop.op();
            RVal left = binop.l();
            RVal right = binop.r();
            
            // Handle simple comparisons between variables and constants
            if (left instanceof LVal.Local && right instanceof RVal.IntConst) {
                return restrictVarConst((LVal.Local) left, op, (RVal.IntConst) right, intervals);
            } else if (right instanceof LVal.Local && left instanceof RVal.IntConst) {
                return restrictConstVar((RVal.IntConst) left, op, (LVal.Local) right, intervals);
            }
        }
        // For complex conditions, return unchanged (lose precision but stay sound)
        return intervals;
    }

    private Map<String, Interval> restrictVarConst(LVal.Local var, BinOp op, RVal.IntConst constVal, Map<String, Interval> intervals) {
        String varName = var.name();
        Interval currentInterval = intervals.getOrDefault(varName, Interval.top());
        Interval newInterval = currentInterval;
        
        BigInteger val = constVal.v();
        
        switch (op) {
            case Lt:
                newInterval = newInterval.intersectLessThan(val);
                break;
            case Le:
                newInterval = newInterval.intersectLessEqual(val);
                break;
            case Gt:
                newInterval = newInterval.intersectGreaterThan(val);
                break;
            case Ge:
                newInterval = newInterval.intersectGreaterEqual(val);
                break;
            case Eq:
                newInterval = newInterval.intersectEqual(val);
                break;
            case Ne:
                // For inequality, we can't easily represent in intervals
                // For soundness, return unchanged
                return intervals;
            default:
                return intervals;
        }
        
        if (newInterval.isEmpty()) {
            return null;
        }
        
        Map<String, Interval> result = new HashMap<>(intervals);
        result.put(varName, newInterval);
        return result;
    }

    private Map<String, Interval> restrictConstVar(RVal.IntConst constVal, BinOp op, LVal.Local var, Map<String, Interval> intervals) {
        // Reverse the comparison
        BinOp reversedOp;
        switch (op) {
            case Lt: reversedOp = BinOp.Gt; break;
            case Le: reversedOp = BinOp.Ge; break;
            case Gt: reversedOp = BinOp.Lt; break;
            case Ge: reversedOp = BinOp.Le; break;
            case Eq: reversedOp = BinOp.Eq; break;
            case Ne: reversedOp = BinOp.Ne; break;
            default: return intervals;
        }
        
        return restrictVarConst(var, reversedOp, constVal, intervals);
    }

    private State transferCall(Step.Call call, State post) {
        // For randInt(), the target variable gets an arbitrary integer
        // So in pre, the target is unconstrained (any integer is possible)
        
        if (call.callee().qualifiedName().equals("pag.probe.Rand.randInt")) {
            Map<String, Interval> newIntervals = new HashMap<>(post.intervals);
            if (call.target().isPresent()) {
                newIntervals.remove(call.target().get().name());
            }
            return new State(newIntervals, false);
        }
        
        // For other calls, we don't know the effect, so return unchanged
        return post;
    }

    /** State representing abstract intervals for each variable */
    public static class State {
        private final Map<String, Interval> intervals;
        private final boolean bottom;
        
        private State(Map<String, Interval> intervals, boolean bottom) {
            this.intervals = new HashMap<>(intervals);
            this.bottom = bottom;
        }
        
        public static State top() {
            return new State(new HashMap<>(), false);
        }
        
        public static State bottom() {
            return new State(new HashMap<>(), true);
        }
        
        public boolean isBottom() {
            return bottom;
        }
        
        public Interval getInterval(String var) {
            if (bottom) return Interval.bottom();
            return intervals.getOrDefault(var, Interval.top());
        }
        
        @Override
        public String toString() {
            if (bottom) return "bottom";
            return intervals.toString();
        }
    }

    /** Interval representing [min, max] for a variable */
    public static class Interval {
        private final BigInteger min;
        private final BigInteger max;
        private final boolean empty;
        
        private Interval(BigInteger min, BigInteger max, boolean empty) {
            this.min = min;
            this.max = max;
            this.empty = empty;
        }
        
        public static Interval top() {
            return new Interval(null, null, false);
        }
        
        public static Interval bottom() {
            return new Interval(null, null, true);
        }
        
        public static Interval of(BigInteger min, BigInteger max) {
            if (min != null && max != null && min.compareTo(max) > 0) {
                return bottom();
            }
            return new Interval(min, max, false);
        }
        
        public boolean isEmpty() {
            return empty;
        }
        
        public boolean isTop() {
            return min == null && max == null && !empty;
        }
        
        public boolean contains(BigInteger value) {
            if (empty) return false;
            if (min != null && value.compareTo(min) < 0) return false;
            if (max != null && value.compareTo(max) > 0) return false;
            return true;
        }
        
        public boolean isContainedIn(Interval other) {
            if (empty) return true;
            if (other.isEmpty()) return false;
            if (other.min != null && min != null && min.compareTo(other.min) < 0) return false;
            if (other.max != null && max != null && max.compareTo(other.max) > 0) return false;
            return true;
        }
        
        public Interval meet(Interval other) {
            if (empty || other.empty) return bottom();
            
            BigInteger newMin = min == null ? other.min : (other.min == null ? min : maxOf(min, other.min));
            BigInteger newMax = max == null ? other.max : (other.max == null ? max : minOf(max, other.max));
            
            if (newMin != null && newMax != null && newMin.compareTo(newMax) > 0) {
                return bottom();
            }
            
            return new Interval(newMin, newMax, false);
        }
        
        public Interval join(Interval other) {
            if (empty) return other;
            if (other.empty) return this;
            
            BigInteger newMin = min == null ? null : (other.min == null ? min : minOf(min, other.min));
            BigInteger newMax = max == null ? null : (other.max == null ? max : maxOf(max, other.max));
            
            return new Interval(newMin, newMax, false);
        }
        
        public Interval widen(Interval other) {
            if (empty) return other;
            if (other.empty) return this;
            
            BigInteger newMin = (min == null || other.min == null) ? null :
                (min.compareTo(other.min) <= 0) ? min : null;
            
            BigInteger newMax = (max == null || other.max == null) ? null :
                (max.compareTo(other.max) >= 0) ? max : null;
            
            return new Interval(newMin, newMax, false);
        }
        
        public Interval intersectLessThan(BigInteger val) {
            if (empty) return bottom();
            BigInteger newMax = max == null ? val : minOf(max, val.subtract(BigInteger.ONE));
            if (min != null && min.compareTo(newMax) > 0) return bottom();
            return new Interval(min, newMax, false);
        }
        
        public Interval intersectLessEqual(BigInteger val) {
            if (empty) return bottom();
            BigInteger newMax = max == null ? val : minOf(max, val);
            if (min != null && min.compareTo(newMax) > 0) return bottom();
            return new Interval(min, newMax, false);
        }
        
        public Interval intersectGreaterThan(BigInteger val) {
            if (empty) return bottom();
            BigInteger newMin = min == null ? val : maxOf(min, val.add(BigInteger.ONE));
            if (max != null && newMin.compareTo(max) > 0) return bottom();
            return new Interval(newMin, max, false);
        }
        
        public Interval intersectGreaterEqual(BigInteger val) {
            if (empty) return bottom();
            BigInteger newMin = min == null ? val : maxOf(min, val);
            if (max != null && newMin.compareTo(max) > 0) return bottom();
            return new Interval(newMin, max, false);
        }
        
        public Interval intersectEqual(BigInteger val) {
            if (empty) return bottom();
            if (min != null && min.compareTo(val) > 0) return bottom();
            if (max != null && max.compareTo(val) < 0) return bottom();
            return new Interval(val, val, false);
        }
        
        private BigInteger minOf(BigInteger a, BigInteger b) {
            return a.compareTo(b) <= 0 ? a : b;
        }
        
        private BigInteger maxOf(BigInteger a, BigInteger b) {
            return a.compareTo(b) >= 0 ? a : b;
        }
        
        @Override
        public String toString() {
            if (empty) return "empty";
            return "[" + min + ", " + max + "]";
        }
    }
}
