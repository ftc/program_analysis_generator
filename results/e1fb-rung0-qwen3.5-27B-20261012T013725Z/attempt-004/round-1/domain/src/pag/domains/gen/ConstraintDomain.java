package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;

/**
 * Abstract domain that tracks exact value constraints on local variables.
 * Used for backward reachability analysis to prove target calls unreachable.
 * 
 * Soundness: Never excludes a state that can actually reach the target.
 * Precision: Can detect contradictions when variables are constrained to
 * conflicting values, proving certain paths unreachable.
 */
public class ConstraintDomain implements Domain<ConstraintDomain.State> {
    
    /**
     * State representing constraints on local variables.
     * Tracks exact values that variables must have to reach the target.
     */
    public static class State {
        private final Map<String, BigInteger> exactValues;
        private boolean isBottom;
        
        private State(Map<String, BigInteger> exactValues, boolean isBottom) {
            this.exactValues = exactValues != null ? new HashMap<>(exactValues) : new HashMap<>();
            this.isBottom = isBottom;
        }
        
        /** Creates an empty state (no constraints, not bottom). */
        public static State empty() {
            return new State(new HashMap<>(), false);
        }
        
        /** Creates a deep copy of a state. */
        public static State copy(State s) {
            return new State(s.exactValues, s.isBottom);
        }
        
        /** Gets the exact value constraint for a variable, if any. */
        public BigInteger getExactValue(String var) {
            return exactValues.get(var);
        }
        
        /** Sets an exact value constraint for a variable. */
        public void setExactValue(String var, BigInteger v) {
            exactValues.put(var, v);
        }
        
        /** Removes any constraint on a variable (makes it unconstrained). */
        public void setUnconstrained(String var) {
            exactValues.remove(var);
        }
        
        /** Marks this state as bottom (no states admitted). */
        public void setBottom() {
            isBottom = true;
        }
        
        // Package-private accessors for testing
        Map<String, BigInteger> exactValues() {
            return exactValues;
        }
        
        boolean isBottom() {
            return isBottom;
        }
    }
    
    @Override
    public String name() {
        return "ConstraintDomain";
    }
    
    @Override
    public State top() {
        return State.empty(); // No constraints = all states admitted
    }
    
    @Override
    public State bottom() {
        State s = State.empty();
        s.setBottom();
        return s;
    }
    
    @Override
    public boolean isBottom(State s) {
        return s.isBottom;
    }
    
    @Override
    public boolean entails(State a, State b) {
        if (a.isBottom) return true; // bottom entails everything
        if (b.isBottom) return false; // nothing entails bottom (except bottom)
        // a entails b if all of a's constraints are satisfied by b
        for (String var : a.exactValues.keySet()) {
            BigInteger aVal = a.exactValues.get(var);
            BigInteger bVal = b.exactValues.get(var);
            if (bVal == null || !aVal.equals(bVal)) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public State join(State a, State b) {
        if (a.isBottom) return State.copy(b);
        if (b.isBottom) return State.copy(a);
        State result = State.empty();
        // Keep only exact values that agree (disagreeing values mean unconstrained)
        for (String var : a.exactValues.keySet()) {
            BigInteger aVal = a.exactValues.get(var);
            BigInteger bVal = b.exactValues.get(var);
            if (bVal != null && aVal.equals(bVal)) {
                result.setExactValue(var, aVal);
            }
        }
        return result;
    }
    
    @Override
    public State widen(State a, State b) {
        return join(a, b); // Simple widening for convergence
    }
    
    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom) return post; // bottom propagates
        State pre = State.copy(post);
        
        if (step instanceof Step.Assign assign) {
            String x = assign.target().name();
            RVal e = assign.source();
            
            // If post constrains x to v, then e must equal v before the assignment
            BigInteger xVal = post.exactValues.get(x);
            if (xVal != null) {
                if (e instanceof LVal.Local local) {
                    // x = y, x must be v, so y must be v
                    pre.setExactValue(local.name(), xVal);
                } else if (e instanceof RVal.IntConst intConst) {
                    // x = c, x must be v, so c must equal v
                    if (!intConst.v().equals(xVal)) {
                        pre.setBottom(); // contradiction
                    }
                }
                // For Binop, we'd need more analysis - be conservative (don't add constraint)
            }
            
            // x is unconstrained before assignment (it gets overwritten)
            pre.setUnconstrained(x);
            
        } else if (step instanceof Step.Assume assume) {
            // Assume(c): only states where c holds continue
            // Intersect post with the condition c
            RVal cond = assume.cond();
            if (cond instanceof RVal.Binop binop) {
                if (binop.op() == BinOp.Eq) {
                    if (binop.l() instanceof LVal.Local l && binop.r() instanceof RVal.IntConst c) {
                        BigInteger cVal = c.v();
                        BigInteger lVal = pre.exactValues.get(l.name());
                        if (lVal != null && !lVal.equals(cVal)) {
                            pre.setBottom(); // contradiction
                        } else {
                            pre.setExactValue(l.name(), cVal);
                        }
                    } else if (binop.l() instanceof RVal.IntConst c && binop.r() instanceof LVal.Local l) {
                        BigInteger cVal = c.v();
                        BigInteger lVal = pre.exactValues.get(l.name());
                        if (lVal != null && !lVal.equals(cVal)) {
                            pre.setBottom(); // contradiction
                        } else {
                            pre.setExactValue(l.name(), cVal);
                        }
                    }
                }
            }
            
        } else if (step instanceof Step.Call call) {
            // Call(x, randInt()): x gets an arbitrary integer
            // x is unconstrained in pre (randInt can produce any value)
            if (call.target().isPresent()) {
                pre.setUnconstrained(call.target().get().name());
            }
        }
        
        return pre;
    }
}
