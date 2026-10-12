package pag.domains.gen;

import pag.api.Domain;
import pag.api.Step;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.BinOp;
import pag.api.MethodId;

import java.math.BigInteger;
import java.util.*;

/**
 * An interval-based abstract domain for backward reachability analysis.
 * Tracks [min, max] intervals for each local variable.
 * 
 * In backward analysis:
 * - top() admits all states (variables can be anything)
 * - bottom() admits no states (contradiction)
 * - transfer computes what constraints on pre-state variables would allow
 *   reaching the post-state constraints
 */
public class IntervalDomain implements Domain<IntervalDomain.IntervalState> {
    
    private static final BigInteger MIN_INF = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_INF = BigInteger.valueOf(Long.MAX_VALUE);
    
    @Override
    public String name() {
        return "IntervalDomain";
    }
    
    @Override
    public IntervalState top() {
        return IntervalState.TOP;
    }
    
    @Override
    public IntervalState bottom() {
        return IntervalState.BOTTOM;
    }
    
    @Override
    public boolean isBottom(IntervalState s) {
        return s == IntervalState.BOTTOM;
    }
    
    @Override
    public boolean entails(IntervalState a, IntervalState b) {
        if (a == IntervalState.BOTTOM) return true;
        if (b == IntervalState.BOTTOM) return false;
        
        // a entails b if every interval in a is contained in b's interval
        for (Map.Entry<String, Interval> entry : a.intervals.entrySet()) {
            String var = entry.getKey();
            Interval intervalA = entry.getValue();
            Interval intervalB = b.intervals.getOrDefault(var, Interval.TOP);
            
            if (!intervalA.isContainedIn(intervalB)) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public IntervalState join(IntervalState a, IntervalState b) {
        if (a == IntervalState.BOTTOM) return b;
        if (b == IntervalState.BOTTOM) return a;
        if (a == IntervalState.TOP) return IntervalState.TOP;
        if (b == IntervalState.TOP) return IntervalState.TOP;
        
        Map<String, Interval> merged = new HashMap<>(a.intervals);
        
        for (Map.Entry<String, Interval> entry : b.intervals.entrySet()) {
            String var = entry.getKey();
            Interval intervalB = entry.getValue();
            Interval intervalA = merged.getOrDefault(var, Interval.TOP);
            merged.put(var, intervalA.join(intervalB));
        }
        
        return new IntervalState(merged);
    }
    
    @Override
    public IntervalState widen(IntervalState a, IntervalState b) {
        if (a == IntervalState.BOTTOM) return b;
        if (b == IntervalState.BOTTOM) return a;
        if (a == IntervalState.TOP) return IntervalState.TOP;
        if (b == IntervalState.TOP) return IntervalState.TOP;
        
        Map<String, Interval> widened = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.intervals.keySet());
        allVars.addAll(b.intervals.keySet());
        
        for (String var : allVars) {
            Interval intervalA = a.intervals.getOrDefault(var, Interval.TOP);
            Interval intervalB = b.intervals.getOrDefault(var, Interval.TOP);
            widened.put(var, intervalA.widen(intervalB));
        }
        
        return new IntervalState(widened);
    }
    
    @Override
    public IntervalState transfer(Step step, IntervalState post) {
        if (post == IntervalState.BOTTOM) return IntervalState.BOTTOM;
        if (post == IntervalState.TOP) return IntervalState.TOP;
        
        return switch (step) {
            case Step.Assign assign -> transferAssign(assign, post);
            case Step.Assume assume -> transferAssume(assume, post);
            case Step.Call call -> transferCall(call, post);
        };
    }
    
    private IntervalState transferAssign(Step.Assign assign, IntervalState post) {
        String targetName = assign.target().name();
        RVal source = assign.source();
        
        // In backward analysis: the constraint on target after assignment
        // becomes a constraint on the source expression before assignment
        // The target itself is unconstrained before (can be anything)
        
        Map<String, Interval> newIntervals = new HashMap<>(post.intervals);
        
        // Remove the old constraint on target (it's unconstrained before assignment)
        newIntervals.remove(targetName);
        
        // For each variable in the source expression, compute its constraint
        // This is simplified - we just propagate the interval through the expression
        for (String var : variablesInRVal(source)) {
            Interval current = newIntervals.getOrDefault(var, Interval.TOP);
            newIntervals.put(var, current);
        }
        
        return new IntervalState(newIntervals);
    }
    
    private IntervalState transferAssume(Step.Assume assume, IntervalState post) {
        RVal cond = assume.cond();
        
        // In backward analysis: we need to restrict post to states where cond holds
        // For soundness, we keep the current constraints (may lose precision)
        
        Map<String, Interval> newIntervals = new HashMap<>(post.intervals);
        
        // For each variable in the condition, keep its current constraint
        for (String var : variablesInRVal(cond)) {
            Interval current = newIntervals.getOrDefault(var, Interval.TOP);
            newIntervals.put(var, current);
        }
        
        return new IntervalState(newIntervals);
    }
    
    private IntervalState transferCall(Step.Call call, IntervalState post) {
        // randInt() returns arbitrary integer, so target is unconstrained before
        if (call.target().isPresent()) {
            String targetName = call.target().get().name();
            Map<String, Interval> newIntervals = new HashMap<>(post.intervals);
            newIntervals.remove(targetName);
            return new IntervalState(newIntervals);
        }
        return post;
    }
    
    private Set<String> variablesInRVal(RVal rval) {
        Set<String> vars = new HashSet<>();
        collectVariables(rval, vars);
        return vars;
    }
    
    private void collectVariables(RVal rval, Set<String> vars) {
        switch (rval) {
            case RVal.IntConst ic -> {}
            case RVal.Binop binop -> {
                collectVariables(binop.l(), vars);
                collectVariables(binop.r(), vars);
            }
            case LVal.Local local -> vars.add(local.name());
            case LVal.StaticField sf -> {}
        }
    }
    
    /**
     * State representation: maps variable names to their intervals
     */
    static class IntervalState {
        static final IntervalState TOP = new IntervalState(new HashMap<>());
        static final IntervalState BOTTOM = new IntervalState(Collections.emptyMap());
        
        final Map<String, Interval> intervals;
        
        IntervalState(Map<String, Interval> intervals) {
            this.intervals = intervals;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IntervalState that = (IntervalState) o;
            return this.intervals.equals(that.intervals);
        }
        
        @Override
        public int hashCode() {
            return intervals.hashCode();
        }
        
        @Override
        public String toString() {
            if (intervals.isEmpty()) return "TOP";
            return intervals.toString();
        }
    }
    
    /**
     * Interval [min, max] with special TOP and BOTTOM values
     */
    static class Interval {
        static final Interval TOP = new Interval(MIN_INF, MAX_INF, true);
        static final Interval BOTTOM = new Interval(BigInteger.ZERO, BigInteger.ONE, false);
        
        final BigInteger min;
        final BigInteger max;
        final boolean isTop;
        
        Interval(BigInteger min, BigInteger max, boolean isTop) {
            this.min = min;
            this.max = max;
            this.isTop = isTop;
        }
        
        Interval(BigInteger min, BigInteger max) {
            this(min, max, false);
        }
        
        boolean isContainedIn(Interval other) {
            if (this.isTop) return other.isTop;
            if (other.isTop) return true;
            return this.min.compareTo(other.min) >= 0 && this.max.compareTo(other.max) <= 0;
        }
        
        Interval join(Interval other) {
            if (this.isTop || other.isTop) return TOP;
            BigInteger newMin = min.compareTo(other.min) < 0 ? min : other.min;
            BigInteger newMax = max.compareTo(other.max) > 0 ? max : other.max;
            return new Interval(newMin, newMax);
        }
        
        Interval widen(Interval other) {
            if (this.isTop || other.isTop) return TOP;
            BigInteger newMin = min.compareTo(other.min) < 0 ? min : MIN_INF;
            BigInteger newMax = max.compareTo(other.max) > 0 ? max : MAX_INF;
            return new Interval(newMin, newMax);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Interval interval = (Interval) o;
            return isTop == interval.isTop && 
                   min.equals(interval.min) && 
                   max.equals(interval.max);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(min, max, isTop);
        }
        
        @Override
        public String toString() {
            if (isTop) return "[TOP]";
            return "[" + min + ", " + max + "]";
        }
    }
}
