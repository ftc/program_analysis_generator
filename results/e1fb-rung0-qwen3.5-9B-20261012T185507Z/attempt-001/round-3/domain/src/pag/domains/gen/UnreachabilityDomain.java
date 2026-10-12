package pag.domains.gen;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pag.api.*;

/**
 * Abstract domain for proving reach(id) calls are unreachable.
 * 
 * Tracks constraints on local variables:
 * - Each variable can be "unconstrained" (any integer value)
 * - Each variable can be "constrained" to a specific constant value
 * 
 * A state is "bottom" (admits no states) if it contains a special marker.
 * 
 * Soundness: if post admits no states (is bottom), pre must also be bottom.
 */
public class UnreachabilityDomain implements Domain<UnreachabilityDomain.State> {

    /**
     * Abstract state tracking variable constraints.
     * Each variable maps to either null (unconstrained) or a BigInteger (constrained).
     * A special "__bottom" key marks the state as bottom (admits no states).
     */
    public static final class State {
        private final Map<String, Object> constraints = new HashMap<>();
        
        public State() {
            // All variables are UNCONSTRAINED by default
        }
        
        public State(Map<String, Object> initial) {
            this.constraints.putAll(initial);
        }
        
        public Object get(String varName) {
            return constraints.getOrDefault(varName, null);
        }
        
        public void set(String varName, Object c) {
            constraints.put(varName, c);
        }
        
        public boolean isBottom() {
            return constraints.containsKey("__bottom");
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
     * Bottom state: admits no program state.
     * Marked with a special "__bottom" key.
     */
    @Override
    public State bottom() {
        State s = new State();
        s.constraints.put("__bottom", BigInteger.valueOf(-1));
        return s;
    }
    
    /**
     * Check if state is bottom (admits no program state).
     */
    @Override
    public boolean isBottom(State s) {
        return s.isBottom();
    }
    
    /**
     * Check if every state admitted by a is also admitted by b.
     * a entails b if for all variables, a's constraint is at least as restrictive as b's.
     * 
     * - bottom entails everything
     * - nothing entails bottom (unless a is also bottom)
     * - for other cases, check each variable's constraint relationship
     */
    @Override
    public boolean entails(State a, State b) {
        // Bottom entails everything
        if (isBottom(a)) {
            return true;
        }
        // Nothing entails bottom
        if (isBottom(b)) {
            return false;
        }
        
        // Check all variables in a
        for (Map.Entry<String, Object> entry : a.constraints.entrySet()) {
            String var = entry.getKey();
            
            // Skip the bottom marker
            if (var.equals("__bottom")) {
                continue;
            }
            
            Object aConstraint = entry.getValue();
            Object bConstraint = b.get(var);
            
            // If a is constrained to a value and b is unconstrained, a entails b
            if (aConstraint != null && bConstraint == null) {
                continue; // OK
            }
            
            // If a is unconstrained and b is constrained, a does NOT entail b
            if (aConstraint == null && bConstraint != null) {
                return false;
            }
            
            // If both constrained, check if values match
            if (aConstraint != null && bConstraint != null) {
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
        // Bottom join anything = bottom
        if (isBottom(a)) {
            return a;
        }
        if (isBottom(b)) {
            return b;
        }
        
        State result = new State();
        
        // For each variable, take the union of constraints
        for (String var : new HashSet<>(a.constraints.keySet())) {
            if (var.equals("__bottom")) continue;
            
            Object aConstraint = a.get(var);
            Object bConstraint = b.get(var);
            
            if (aConstraint == null || bConstraint == null) {
                result.set(var, null);
            } else if (aConstraint.equals(bConstraint)) {
                result.set(var, aConstraint);
            } else {
                // Different constrained values -> unconstrained
                result.set(var, null);
            }
        }
        
        return result;
    }
    
    /**
     * Widen two states: result admits everything either admits, but converges.
     * For this domain, widening is the same as join to ensure convergence.
     */
    @Override
    public State widen(State a, State b) {
        return join(a, b);
    }
    
    /**
     * Backward transfer across one step.
     * 
     * For Assign(x, e):
     *   - After: x = value(e)
     *   - Before: x is unconstrained (e's value before assignment could be anything)
     *   - Other variables: same as after
     * 
     * For Assume(c):
     *   - Before: same as after, but restricted by c
     *   - Actually, since we're going backward, the constraint from post carries through
     * 
     * For Call(x, Rand.randInt(), []):
     *   - After: x = random value (unconstrained)
     *   - Before: x is unconstrained
     *   - Other variables: same as after
     */
    @Override
    public State transfer(Step step, State post) {
        State pre = new State();
        
        switch (step) {
            case Assign assign -> {
                // Copy all constraints from post (except x)
                for (Map.Entry<String, Object> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (var.equals("__bottom")) continue;
                    
                    // Don't copy the target variable (it gets a new value)
                    if (!var.equals(assign.target().name())) {
                        pre.set(var, entry.getValue());
                    }
                }
                
                // Target variable becomes unconstrained before the assignment
                pre.set(assign.target().name(), null);
                
                // If post is bottom, pre must be bottom (soundness)
                if (isBottom(post)) {
                    pre.constraints.put("__bottom", BigInteger.valueOf(-1));
                }
            }
            
            case Assume assume -> {
                // Copy all constraints from post
                for (Map.Entry<String, Object> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (var.equals("__bottom")) continue;
                    pre.set(var, entry.getValue());
                }
                
                // If post is bottom, pre must be bottom (soundness)
                if (isBottom(post)) {
                    pre.constraints.put("__bottom", BigInteger.valueOf(-1));
                }
            }
            
            case Call call -> {
                // Target variable becomes unconstrained (random value)
                // Other variables remain as in post
                
                for (Map.Entry<String, Object> entry : post.constraints.entrySet()) {
                    String var = entry.getKey();
                    if (var.equals("__bottom")) continue;
                    
                    if (call.target().isPresent() && 
                        var.equals(call.target().get().name())) {
                        // Target variable becomes unconstrained
                        pre.set(var, null);
                    } else {
                        pre.set(var, entry.getValue());
                    }
                }
                
                // If post is bottom, pre must be bottom (soundness)
                if (isBottom(post)) {
                    pre.constraints.put("__bottom", BigInteger.valueOf(-1));
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
