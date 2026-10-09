package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;

/**
 * An interval abstract domain for backward reachability analysis.
 * Tracks intervals [min, max] for each local variable.
 * null bounds mean unbounded (all integers).
 */
public class IntervalDomain implements Domain<IntervalDomain.State> {
    
    /** State representing a set of possible program states via variable intervals */
    public static class State {
        private final Map<String, Interval> intervals;
        private final boolean bottom;
        
        private State(Map<String, Interval> intervals, boolean bottom) {
            this.intervals = new HashMap<>(intervals);
            this.bottom = bottom;
        }
        
        /** Top state: all variables unconstrained */
        public static State top() {
            return new State(new HashMap<>(), false);
        }
        
        /** Bottom state: no program states admitted */
        public static State bottom() {
            return new State(new HashMap<>(), true);
        }
        
        public boolean isBottom() {
            return bottom;
        }
        
        /** Get interval for variable, unbounded if not tracked */
        public Interval getInterval(String var) {
            return intervals.getOrDefault(var, Interval.unbounded());
        }
        
        /** Create new state with updated interval for a variable */
        public State withInterval(String var, Interval interval) {
            if (interval == null || interval.isEmpty()) {
                Map<String, Interval> newIntervals = new HashMap<>(intervals);
                newIntervals.remove(var);
                return new State(newIntervals, true);
            }
            Map<String, Interval> newIntervals = new HashMap<>(intervals);
            newIntervals.put(var, interval);
            return new State(newIntervals, false);
        }
        
        public Map<String, Interval> getMap() {
            return Collections.unmodifiableMap(intervals);
        }
    }
    
    /** Interval [min, max], null means unbounded in that direction */
    private static class Interval {
        private final BigInteger min;
        private final BigInteger max;
        
        private Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }
        
        /** Unbounded interval: all integers */
        public static Interval unbounded() {
            return new Interval(null, null);
        }
        
        /** Empty interval: no values */
        public static Interval empty() {
            return new Interval(BigInteger.ONE, BigInteger.ZERO);
        }
        
        public boolean isEmpty() {
            return min != null && max != null && min.compareTo(max) > 0;
        }
        
        public boolean isUnbounded() {
            return min == null && max == null;
        }
        
        /** Intersect this with another interval */
        public Interval intersect(Interval other) {
            if (this.isEmpty() || other.isEmpty()) return empty();
            
            BigInteger newMin = this.min == null ? other.min : 
                (other.min == null ? this.min : 
                 this.min.compareTo(other.min) > 0 ? this.min : other.min);
            BigInteger newMax = this.max == null ? other.max : 
                (other.max == null ? this.max : 
                 this.max.compareTo(other.max) < 0 ? this.max : other.max);
            
            if (newMin != null && newMax != null && newMin.compareTo(newMax) > 0) {
                return empty();
            }
            return new Interval(newMin, newMax);
        }
        
        /** Union of intervals (for join) */
        public Interval join(Interval other) {
            if (this.isEmpty()) return other;
            if (other.isEmpty()) return this;
            
            BigInteger newMin = this.min == null ? other.min : 
                (other.min == null ? this.min : 
                 this.min.compareTo(other.min) < 0 ? this.min : other.min);
            BigInteger newMax = this.max == null ? other.max : 
                (other.max == null ? this.max : 
                 this.max.compareTo(other.max) > 0 ? this.max : other.max);
            
            return new Interval(newMin, newMax);
        }
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
        for (Map.Entry<String, Interval> entry : a.getMap().entrySet()) {
            String var = entry.getKey();
            Interval aInt = entry.getValue();
            Interval bInt = b.getInterval(var);
            if (!intervalEntails(aInt, bInt)) return false;
        }
        return true;
    }
    
    private boolean intervalEntails(Interval a, Interval b) {
        if (a.isEmpty()) return true;
        if (b.isEmpty()) return false;
        if (b.isUnbounded()) return true;
        if (a.isUnbounded()) return false;
        return a.min.compareTo(b.min) >= 0 && a.max.compareTo(b.max) <= 0;
    }
    
    @Override
    public State join(State a, State b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        
        Map<String, Interval> newIntervals = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.getMap().keySet());
        allVars.addAll(b.getMap().keySet());
        
        for (String var : allVars) {
            Interval aInt = a.getInterval(var);
            Interval bInt = b.getInterval(var);
            Interval joined = aInt.join(bInt);
            if (!joined.isEmpty() && !joined.isUnbounded()) {
                newIntervals.put(var, joined);
            }
        }
        
        return new State(newIntervals, false);
    }
    
    @Override
    public State widen(State a, State b) {
        return join(a, b);
    }
    
    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom()) return post;
        
        if (step instanceof Step.Assign assign) {
            return backwardAssign(assign.target().name(), assign.source(), post);
        } else if (step instanceof Step.Assume assume) {
            return backwardAssume(assume.cond(), post);
        } else if (step instanceof Step.Call call) {
            if (call.callee().qualifiedName().equals("pag.probe.Rand.randInt")) {
                if (call.target().isPresent()) {
                    return post.withInterval(call.target().get().name(), Interval.unbounded());
                }
            }
            return post;
        }
        return post;
    }
    
    /**
     * Backward transfer for assignment x = e.
     * If post constrains x, compute constraint on e.
     * x becomes unconstrained before assignment.
     */
    private State backwardAssign(String target, RVal source, State post) {
        Interval targetInterval = post.getInterval(target);
        Interval sourceConstraint = computeSourceConstraint(source, targetInterval);
        
        Map<String, Interval> newIntervals = new HashMap<>(post.getMap());
        newIntervals.remove(target);
        
        if (source instanceof LVal.Local local) {
            Interval current = newIntervals.getOrDefault(local.name(), Interval.unbounded());
            Interval intersected = current.intersect(sourceConstraint);
            if (!intersected.isEmpty()) {
                newIntervals.put(local.name(), intersected);
            } else {
                return State.bottom();
            }
        }
        
        return new State(newIntervals, false);
    }
    
    /** Compute what constraint the source expression must satisfy */
    private Interval computeSourceConstraint(RVal source, Interval target) {
        if (source instanceof LVal.Local) {
            return target;
        } else if (source instanceof RVal.Binop binop) {
            return computeBinopConstraint(binop, target);
        }
        return Interval.unbounded();
    }
    
    /** Compute constraint on left operand of binary operation */
    private Interval computeBinopConstraint(RVal.Binop binop, Interval target) {
        BinOp op = binop.op();
        RVal right = binop.r();
        
        if (right instanceof RVal.IntConst const) {
            BigInteger c = const.v();
            return switch (op) {
                case Add -> subtractInterval(target, c);
                case Sub -> addInterval(target, c);
                case Mult -> multiplyInterval(target, c);
                default -> Interval.unbounded();
            };
        }
        return Interval.unbounded();
    }
    
    /** Backward transfer for assume(c): intersect with constraint c */
    private State backwardAssume(RVal cond, State post) {
        if (cond instanceof RVal.Binop binop) {
            BinOp op = binop.op();
            RVal left = binop.l();
            RVal right = binop.r();
            
            if (left instanceof LVal.Local local && right instanceof RVal.IntConst const) {
                String var = local.name();
                BigInteger c = const.v();
                Interval current = post.getInterval(var);
                Interval constraint = computeConstraint(op, c);
                Interval intersected = current.intersect(constraint);
                if (intersected.isEmpty()) {
                    return State.bottom();
                }
                return post.withInterval(var, intersected);
            }
        }
        return post;
    }
    
    /** Compute interval constraint from comparison operator */
    private Interval computeConstraint(BinOp op, BigInteger c) {
        return switch (op) {
            case Lt -> new Interval(null, c.subtract(BigInteger.ONE));
            case Le -> new Interval(null, c);
            case Gt -> new Interval(c.add(BigInteger.ONE), null);
            case Ge -> new Interval(c, null);
            case Eq -> new Interval(c, c);
            case Ne -> Interval.unbounded();
            default -> Interval.unbounded();
        };
    }
    
    /** Add constant to interval */
    private Interval addInterval(Interval interval, BigInteger c) {
        BigInteger newMin = interval.min == null ? null : interval.min.add(c);
        BigInteger newMax = interval.max == null ? null : interval.max.add(c);
        return new Interval(newMin, newMax);
    }
    
    /** Subtract constant from interval */
    private Interval subtractInterval(Interval interval, BigInteger c) {
        BigInteger newMin = interval.min == null ? null : interval.min.subtract(c);
        BigInteger newMax = interval.max == null ? null : interval.max.subtract(c);
        return new Interval(newMin, newMax);
    }
    
    /** Multiply interval by constant */
    private Interval multiplyInterval(Interval interval, BigInteger c) {
        if (c.compareTo(BigInteger.ZERO) > 0) {
            BigInteger newMin = interval.min == null ? null : interval.min.multiply(c);
            BigInteger newMax = interval.max == null ? null : interval.max.multiply(c);
            return new Interval(newMin, newMax);
        } else if (c.compareTo(BigInteger.ZERO) < 0) {
            BigInteger newMin = interval.max == null ? null : interval.max.multiply(c);
            BigInteger newMax = interval.min == null ? null : interval.min.multiply(c);
            return new Interval(newMin, newMax);
        }
        return new Interval(BigInteger.ZERO, BigInteger.ZERO);
    }
}
