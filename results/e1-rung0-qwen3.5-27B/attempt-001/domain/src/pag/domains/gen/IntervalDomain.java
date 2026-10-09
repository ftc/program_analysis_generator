package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;

/**
 * An interval domain for backward reachability analysis.
 * Tracks [min, max] bounds for each local variable.
 * null bounds mean unbounded (no constraint).
 */
public class IntervalDomain implements Domain<State> {
    
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    
    /** Represents an interval [min, max] of integer values. */
    private static class Interval {
        BigInteger min;
        BigInteger max;
        
        private Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }
        
        /** Top interval: all integers (-∞, +∞) */
        public static Interval top() {
            return new Interval(null, null);
        }
        
        /** Bottom interval: no values */
        public static Interval bottom() {
            return new Interval(BigInteger.ONE, BigInteger.ZERO);
        }
        
        public boolean isBottom() {
            if (min == null && max == null) return false;
            if (min == null) return false;
            if (max == null) return false;
            return min.compareTo(max) > 0;
        }
        
        /** Intersection of two intervals. */
        public Interval intersect(Interval other) {
            if (this.isBottom() || other.isBottom()) return bottom();
            BigInteger newMin = (this.min == null) ? other.min : 
                               (other.min == null) ? this.min : 
                               this.min.max(other.min);
            BigInteger newMax = (this.max == null) ? other.max : 
                               (other.max == null) ? this.max : 
                               this.max.min(other.max);
            if (newMin != null && newMax != null && newMin.compareTo(newMax) > 0) {
                return bottom();
            }
            return new Interval(newMin, newMax);
        }
        
        /** Widening for loop convergence. */
        public Interval widen(Interval other) {
            if (this.isBottom()) return other;
            if (other.isBottom()) return this;
            BigInteger newMin = (this.min == null) ? null :
                               (other.min == null) ? null :
                               this.min.min(other.min);
            BigInteger newMax = (this.max == null) ? null :
                               (other.max == null) ? null :
                               this.max.max(other.max);
            if (newMin != null && newMax != null && newMin.compareTo(newMax) > 0) {
                return bottom();
            }
            return new Interval(newMin, newMax);
        }
        
        /** Check if this interval contains other. */
        public boolean entails(Interval other) {
            if (other.isBottom()) return true;
            if (this.isBottom()) return false;
            if (other.min != null && this.min != null && this.min.compareTo(other.min) > 0) return false;
            if (other.max != null && this.max != null && this.max.compareTo(other.max) < 0) return false;
            return true;
        }
        
        @Override
        public String toString() {
            return "[" + min + ", " + max + "]";
        }
    }
    
    /** State: mapping from variable names to intervals. */
    private static class State {
        Map<String, Interval> vars = new HashMap<>();
        
        public Interval getVar(String name) {
            return vars.getOrDefault(name, Interval.top());
        }
        
        public void setVar(String name, Interval interval) {
            if (interval == null || interval.isBottom()) {
                vars.remove(name);
            } else {
                vars.put(name, interval);
            }
        }
        
        public State join(State other) {
            State result = new State();
            Set<String> allVars = new HashSet<>(this.vars.keySet());
            allVars.addAll(other.vars.keySet());
            for (String var : allVars) {
                Interval i1 = this.getVar(var);
                Interval i2 = other.getVar(var);
                result.setVar(var, i1.widen(i2));
            }
            return result;
        }
        
        public State widen(State other) {
            State result = new State();
            Set<String> allVars = new HashSet<>(this.vars.keySet());
            allVars.addAll(other.vars.keySet());
            for (String var : allVars) {
                Interval i1 = this.getVar(var);
                Interval i2 = other.getVar(var);
                result.setVar(var, i1.widen(i2));
            }
            return result;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State other = (State) o;
            if (this.vars.size() != other.vars.size()) return false;
            for (String key : this.vars.keySet()) {
                if (!Objects.equals(this.vars.get(key), other.vars.get(key))) return false;
            }
            return true;
        }
        
        @Override
        public int hashCode() {
            return vars.hashCode();
        }
        
        @Override
        public String toString() {
            return "State{" + vars + "}";
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
        return new State();
    }
    
    @Override
    public State bottom() {
        State s = new State();
        s.vars.put("BOTTOM", Interval.bottom());
        return s;
    }
    
    @Override
    public boolean isBottom(State s) {
        return s.vars.containsKey("BOTTOM");
    }
    
    @Override
    public boolean entails(State a, State b) {
        if (isBottom(a)) return true;
        if (isBottom(b)) return false;
        for (String var : a.vars.keySet()) {
            Interval ia = a.getVar(var);
            Interval ib = b.getVar(var);
            if (!ia.entails(ib)) return false;
        }
        return true;
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
        if (isBottom(post)) return post;
        
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
        State result = new State();
        result.vars.putAll(post.vars);
        
        // Get the constraint on the target variable from post
        Interval targetInterval = post.getVar(assign.target().name());
        
        // Evaluate the source expression in the post state
        // and constrain the variables in the source accordingly
        State sourceConstraint = evalRVal(assign.source(), post, targetInterval);
        
        // Merge source constraints into result
        for (String var : sourceConstraint.vars.keySet()) {
            result.setVar(var, sourceConstraint.getVar(var));
        }
        
        // Target is unconstrained before assignment
        result.vars.remove(assign.target().name());
        
        return result;
    }
    
    private State evalRVal(RVal expr, State state, Interval constraint) {
        State result = new State();
        
        if (expr instanceof RVal.IntConst intConst) {
            // Constant: check if it satisfies constraint
            Interval constInterval = new Interval(intConst.v(), intConst.v());
            if (!constInterval.intersect(constraint).isBottom()) {
                return result; // Constraint satisfied
            } else {
                result.vars.put("BOTTOM", Interval.bottom());
                return result;
            }
        }
        
        if (expr instanceof RVal.Binop binop) {
            // For binary operations, evaluate both operands
            // and apply interval arithmetic constraints
            RVal l = binop.l();
            RVal r = binop.r();
            BinOp op = binop.op();
            
            // Get intervals for operands
            Interval lInterval = getInterval(l, state);
            Interval rInterval = getInterval(r, state);
            
            // Apply operation and intersect with constraint
            Interval resultInterval = applyOp(lInterval, op, rInterval);
            Interval constrained = resultInterval.intersect(constraint);
            
            if (constrained.isBottom()) {
                result.vars.put("BOTTOM", Interval.bottom());
                return result;
            }
            
            // Propagate constraints back to operands (simplified)
            // This is a sound over-approximation
            if (l instanceof LVal.Local localL) {
                result.setVar(localL.name(), lInterval);
            }
            if (r instanceof LVal.Local localR) {
                result.setVar(localR.name(), rInterval);
            }
            
            return result;
        }
        
        if (expr instanceof LVal.Local local) {
            Interval current = state.getVar(local.name());
            Interval constrained = current.intersect(constraint);
            if (constrained.isBottom()) {
                result.vars.put("BOTTOM", Interval.bottom());
            } else {
                result.setVar(local.name(), constrained);
            }
            return result;
        }
        
        return result;
    }
    
    private Interval getInterval(RVal expr, State state) {
        if (expr instanceof RVal.IntConst intConst) {
            return new Interval(intConst.v(), intConst.v());
        }
        if (expr instanceof LVal.Local local) {
            return state.getVar(local.name());
        }
        if (expr instanceof RVal.Binop binop) {
            Interval l = getInterval(binop.l(), state);
            Interval r = getInterval(binop.r(), state);
            return applyOp(l, binop.op(), r);
        }
        return Interval.top();
    }
    
    private Interval applyOp(Interval l, BinOp op, Interval r) {
        if (l.isBottom() || r.isBottom()) return Interval.bottom();
        
        BigInteger lMin = l.min;
        BigInteger lMax = l.max;
        BigInteger rMin = r.min;
        BigInteger rMax = r.max;
        
        switch (op) {
            case Add:
                BigInteger min = (lMin == null || rMin == null) ? null : lMin.add(rMin);
                BigInteger max = (lMax == null || rMax == null) ? null : lMax.add(rMax);
                return new Interval(min, max);
                
            case Sub:
                min = (lMin == null || rMax == null) ? null : lMin.subtract(rMax);
                max = (lMax == null || rMin == null) ? null : lMax.subtract(rMin);
                return new Interval(min, max);
                
            case Mult:
                // Simplified multiplication
                List<BigInteger> bounds = new ArrayList<>();
                if (lMin != null && rMin != null) bounds.add(lMin.multiply(rMin));
                if (lMin != null && rMax != null) bounds.add(lMin.multiply(rMax));
                if (lMax != null && rMin != null) bounds.add(lMax.multiply(rMin));
                if (lMax != null && rMax != null) bounds.add(lMax.multiply(rMax));
                if (bounds.isEmpty()) return new Interval(null, null);
                return new Interval(Collections.min(bounds), Collections.max(bounds));
                
            case Lt:
            case Le:
            case Gt:
            case Ge:
            case Eq:
            case Ne:
                // For comparisons, return a boolean-like interval
                return new Interval(ZERO, ONE);
                
            default:
                return Interval.top();
        }
    }
    
    private State transferAssume(Step.Assume assume, State post) {
        State result = new State();
        result.vars.putAll(post.vars);
        
        RVal cond = assume.cond();
        
        // Handle simple comparisons: x OP constant
        if (cond instanceof RVal.Binop binop) {
            RVal l = binop.l();
            RVal r = binop.r();
            BinOp op = binop.op();
            
            if (l instanceof LVal.Local local && r instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                Interval current = result.getVar(local.name());
                Interval newInterval;
                
                switch (op) {
                    case Gt: // x > val means x >= val + 1
                        newInterval = current.intersect(new Interval(val.add(ONE), null));
                        break;
                    case Ge: // x >= val
                        newInterval = current.intersect(new Interval(val, null));
                        break;
                    case Lt: // x < val means x <= val - 1
                        newInterval = current.intersect(new Interval(null, val.subtract(ONE)));
                        break;
                    case Le: // x <= val
                        newInterval = current.intersect(new Interval(null, val));
                        break;
                    case Eq: // x == val
                        newInterval = current.intersect(new Interval(val, val));
                        break;
                    case Ne: // x != val - can't represent in intervals, be conservative
                        newInterval = current;
                        break;
                    default:
                        newInterval = current;
                        break;
                }
                
                if (newInterval.isBottom()) {
                    result.vars.put("BOTTOM", Interval.bottom());
                    return result;
                }
                
                result.setVar(local.name(), newInterval);
                return result;
            }
        }
        
        return result;
    }
    
    private State transferCall(Step.Call call, State post) {
        State result = new State();
        result.vars.putAll(post.vars);
        
        // For randInt(), the target variable gets an arbitrary value
        // Before the call, the target is unconstrained
        if (call.target().isPresent()) {
            result.vars.remove(call.target().get().name());
        }
        
        return result;
    }
}
