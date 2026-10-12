package pag.domains.gen;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.List;
import pag.api.*;

/**
 * Abstract domain for proving reach(id) calls are unreachable.
 * 
 * Tracks constraints on local variables:
 * - Each variable can be "unconstrained" (any integer value)
 * - Each variable can be "constrained" to a specific constant value
 * 
 * This domain proves unreachability by showing that at method entry,
 * no state exists that can reach the target.
 */
public class UnreachabilityDomain implements Domain<UnreachabilityDomain.State> {

    /**
     * Abstract state tracking variable constraints.
     * Each variable maps to either UNCONSTRAINED or CONSTRAINED(value).
     */
    public static final class State {
        private final Map<String, Constraint> constraints = new HashMap<>();
        
        public enum Constraint {
            UNCONSTRAINED,
            CONSTRAINED(BigInteger value)
        }
        
        public State() {
            // Initialize with all variables as UNCONSTRAINED
        }
        
        public State(Map<String, Constraint> initial) {
            this.constraints.putAll(initial);
        }
        
        public Constraint get(String varName) {
            return constraints.getOrDefault(varName, Constraint.UNCONSTRAINED);
        }
        
        public void set(String varName, Constraint c) {
            constraints.put(varName, c);
        }
    }
    
    /**
     * Top state: all variables are unconstrained (admits all program states).
     */
    @Override
    public State top() {
        State s = new State();
        // All variables are unconstrained by default in top
        return s;
    }
    
    /**
     * Bottom state: all variables are constrained to impossible values.
     * A state is bottom if any variable is constrained to a value that
     * makes reaching the target impossible.
     */
    @Override
    public State bottom() {
        State s = new State();
        // Constrain all variables to an impossible state
        // We use a marker value that can't satisfy any condition
        s.constraints.putAll(Map.of(
            "__bottom", State.Constraint.CONSTRAINED(BigInteger.valueOf(-1))
        ));
        return s;
    }
    
    /**
     * Check if state is bottom (admits no program state).
     */
    @Override
    public boolean isBottom(State s) {
        // State is bottom if it contains the bottom marker
        return s.constraints.containsKey("__bottom");
    }
    
    /**
     * Check if every state admitted by a is also admitted by b.
     * For our domain: a entails b if for all variables, a's constraint
     * is at least as restrictive as b's.
     */
    @Override
    public boolean entails(State a, State b) {
        // Bottom entails everything
        if (isBottom(a)) {
            return true;
        }
        // Top is entailed by everything
        if (isBottom(b)) {
            return false;
        }
        
        // Check all variables in a
        for (Map.Entry<String, State.Constraint> entry : a.constraints.entrySet()) {
            String var = entry.getKey();
            if (var.equals("__bottom")) {
                continue;
            }
            
            State.Constraint aConstraint = entry.getValue();
            State.Constraint bConstraint = b.get(var);
            
            // If a is constrained to a value and b is unconstrained, a entails b
            if (aConstraint == State.Constraint.CONSTRAINED && 
                bConstraint == State.Constraint.UNCONSTRAINED) {
                continue; // OK
            }
            
            // If a is unconstrained and b is constrained, a does NOT entail b
            if (aConstraint == State.Constraint.UNCONSTRAINED && 
                bConstraint == State.Constraint.CONSTRAINED) {
                return false;
            }
            
            // If both constrained, check if values match
            if (aConstraint == State.Constraint.CONSTRAINED && 
                bConstraint == State.Constraint.CONSTRAINED) {
                if (!aConstraint.equals(bConstraint)) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Join two states: result admits everything either admits.
     */
    @Override
    public State join(State a, State b) {
        State result = new State();
        
        // Bottom join anything = bottom
        if (isBottom(a)) {
            return a;
        }
        if (isBottom(b)) {
            return b;
        }
        
        // Top join anything = top
        if (isBottom(a.constraints.isEmpty())) {
            return a;
        }
        if (isBottom(b.constraints.isEmpty())) {
            return b;
        }
        
        // For each variable, take the union of constraints
        for (String var : new HashSet<>(a.constraints.keySet()) {
            if (var.equals("__bottom")) continue;
            
            State.Constraint aConstraint = a.get(var);
            State.Constraint bConstraint = b.get(var);
            
            if (aConstraint == State.Constraint.UNCONSTRAINED || 
                bConstraint == State.Constraint.UNCONSTRAINED) {
                result.set(var, State.Constraint.UNCONSTRAINED);
            } else if (aConstraint.equals(bConstraint)) {
                result.set(var, aConstraint);
            } else {
                // Different constrained values -> unconstrained
                result.set(var, State.Constraint.UNCONSTRAINED);
            }
        }
        
        return result;
    }
    
    /**
     * Widen two states: result admits everything either admits, but converges.
     * For our domain, widening is similar to join but can be more aggressive.
     */
    @Override
    public State widen(State a, State b) {
        // For this domain, widening can be the same as join
        // This ensures convergence
        return join(a, b);
    }
    
    /**
     * Backward transfer across one step.
     * 
     * For Assign(x, e):
     * - After: x = value(e)
     * - Before: x is unconstrained (e's value before assignment)
     * 
     * For Assume(c):
     * - Before: same as after, but restricted by c
     * 
     * For Call(x, Rand.randInt(), []):
     * - After: x = random value (unconstrained)
     * - Before: x is unconstrained
     */
    @Override
    public State transfer(Step step, State post) {
        State pre = new State();
        
        switch (step) {
            case Assign assign -> {
                // x gets value of e before the step
                // After step: x is constrained to e's value
                // Before step: x is unconstrained (since e could be any value)
                
                // First, copy all constraints from post (except x)
                for (Map.Entry<String, State.Constraint> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (var.equals("__bottom")) continue;
                    
                    if (!var.equals(assign.target().name())) {
                        pre.set(var, entry.getValue());
                    }
                }
                
                // x becomes unconstrained before the assignment
                pre.set(assign.target().name(), State.Constraint.UNCONSTRAINED);
                
                // If post is bottom, pre should also be bottom (unsound to continue)
                if (isBottom(post)) {
                    pre.constraints.put("__bottom", State.Constraint.CONSTRAINED(BigInteger.valueOf(-1)));
                }
            }
            
            case Assume assume -> {
                // Copy all constraints from post
                for (Map.Entry<String, State.Constraint> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (var.equals("__bottom")) continue;
                    pre.set(var, entry.getValue());
                }
                
                // If post is bottom, pre should be bottom
                if (isBottom(post)) {
                    pre.constraints.put("__bottom", State.Constraint.CONSTRAINED(BigInteger.valueOf(-1)));
                }
            }
            
            case Call call -> {
                // x gets arbitrary integer (unconstrained)
                // All other variables remain as in post
                
                for (Map.Entry<String, State.Constraint> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (var.equals("__bottom")) continue;
                    
                    if (call.target().isPresent() && 
                        var.equals(call.target().get().name())) {
                        // Target variable becomes unconstrained
                        pre.set(var, State.Constraint.UNCONSTRAINED);
                    } else {
                        pre.set(var, entry.getValue());
                    }
                }
                
                // If post is bottom, pre should be bottom
                if (isBottom(post)) {
                    pre.constraints.put("__bottom", State.Constraint.CONSTRAINED(BigInteger.valueOf(-1)));
                }
            }
        }
        
        return pre;
    }
    
    /**
     * Get a name for this domain.
     */
    @Override
    public String name() {
        return "UnreachabilityDomain";
    }
}
