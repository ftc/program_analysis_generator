package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

/**
 * An equality domain that tracks variable-to-constant mappings.
 * 
 * Soundness: This domain over-approximates by:
 * - Forgetting constraints when variables are not bound to constants
 * - Treating unbound variables as "unknown" (not false)
 * - Never claiming a state is impossible unless we have direct evidence
 * 
 * Precision: Proves unreachable code when we derive contradictory equalities
 * or when assumptions contradict known equalities.
 */
public class EqualityDomain implements Domain<EqualityDomain.State> {

    /**
     * Abstract state tracking variable equalities to constants.
     * 
     * isBottom = true means this state admits no program states (contradiction).
     * equalities maps variable names to their known constant values.
     * If a variable is not in equalities, it may have any value.
     */
    public static final class State {
        private final Map<String, BigInteger> equalities;
        private final boolean isBottom;
        
        private State(Map<String, BigInteger> equalities, boolean isBottom) {
            this.equalities = equalities != null ? new HashMap<>(equalities) : new HashMap<>();
            this.isBottom = isBottom;
        }
        
        public Map<String, BigInteger> getEqualities() {
            return Collections.unmodifiableMap(equalities);
        }
        
        public boolean isBottom() {
            return isBottom;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State state = (State) o;
            return isBottom == state.isBottom && Objects.equals(equalities, state.equalities);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(isBottom, equalities);
        }
    }

    public EqualityDomain() {
    }

    @Override
    public String name() {
        return "EqualityDomain";
    }

    @Override
    public State top() {
        // Admits all states: no constraints, not bottom
        return new State(new HashMap<>(), false);
    }

    @Override
    public State bottom() {
        // Admits no states: bottom
        return new State(new HashMap<>(), true);
    }

    @Override
    public boolean isBottom(State s) {
        return s.isBottom;
    }

    @Override
    public boolean entails(State a, State b) {
        // a entails b if every state admitted by a is also admitted by b
        if (a.isBottom) return true;  // bottom entails everything
        if (b.isBottom) return false; // nothing entails bottom except bottom
        
        // For equality domain: a entails b if all equalities in a are in b
        return b.equalities.containsAll(a.equalities);
    }

    @Override
    public State join(State a, State b) {
        if (a.isBottom) return b;
        if (b.isBottom) return a;
        
        // Join: keep only equalities that agree in both states
        Map<String, BigInteger> result = new HashMap<>();
        for (String var : a.equalities.keySet()) {
            if (b.equalities.containsKey(var)) {
                BigInteger valA = a.equalities.get(var);
                BigInteger valB = b.equalities.get(var);
                if (valA.equals(valB)) {
                    result.put(var, valA);
                }
            }
        }
        return new State(result, false);
    }

    @Override
    public State widen(State a, State b) {
        // Similar to join but designed for convergence
        // Keep equalities that are consistent
        if (a.isBottom) return b;
        if (b.isBottom) return a;
        
        Map<String, BigInteger> result = new HashMap<>();
        for (String var : a.equalities.keySet()) {
            if (b.equalities.containsKey(var)) {
                BigInteger valA = a.equalities.get(var);
                BigInteger valB = b.equalities.get(var);
                if (valA.equals(valB)) {
                    result.put(var, valA);
                }
            }
        }
        return new State(result, false);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom) return post;
        
        if (step instanceof Step.Assign assign) {
            // Backward: if post constrains target, that constraint transfers to source
            // target is unconstrained before the assignment
            return transferAssign(assign, post);
        } else if (step instanceof Step.Assume assume) {
            // Backward: only states where cond holds pass through
            return transferAssume(assume.cond(), post);
        } else if (step instanceof Step.Call call) {
            // Special case: randInt() produces arbitrary value
            return transferCall(call, post);
        }
        throw new AssertionError("unhandled step: " + step);
    }
    
    private State transferAssign(Step.Assign assign, State post) {
        // Evaluate source expression in post state
        BigInteger sourceValue = evaluateRVal(assign.source(), post.equalities);
        
        if (sourceValue == null) {
            // Can't evaluate source to constant, forget target constraint
            Map<String, BigInteger> newEqualities = new HashMap<>(post.equalities);
            newEqualities.remove(assign.target().name());
            return new State(newEqualities, false);
        }
        
        // Source evaluates to constant, target must have this value before assignment
        Map<String, BigInteger> newEqualities = new HashMap<>(post.equalities);
        newEqualities.put(assign.target().name(), sourceValue);
        return new State(newEqualities, false);
    }
    
    private State transferAssume(RVal cond, State post) {
        // Check if assumption is consistent with current equalities
        if (!checkAssume(cond, post.equalities)) {
            // Assumption contradicts known equalities -> bottom
            return bottom();
        }
        // Assumption is consistent (or we can't prove it's not)
        return post;
    }
    
    private boolean checkAssume(RVal cond, Map<String, BigInteger> equalities) {
        // Returns true if assumption is consistent with equalities
        // Returns false only if we can prove the assumption is violated
        
        if (cond instanceof RVal.IntConst ic) {
            // Constants don't constrain anything
            return true;
        } else if (cond instanceof RVal.Binop binop) {
            BigInteger left = evaluateRVal(binop.l(), equalities);
            BigInteger right = evaluateRVal(binop.r(), equalities);
            
            if (left == null || right == null) {
                // Can't evaluate, assume consistent (sound over-approximation)
                return true;
            }
            
            switch (binop.op()) {
                case Eq: return left.equals(right);
                case Ne: return !left.equals(right);
                case Lt: return left.compareTo(right) < 0;
                case Le: return left.compareTo(right) <= 0;
                case Gt: return left.compareTo(right) > 0;
                case Ge: return left.compareTo(right) >= 0;
                case Add:
                case Sub:
                case Mult: return true; // Arithmetic is always consistent
                default: return true;
            }
        } else if (cond instanceof LVal.Local local) {
            // Variable as condition: unknown value, assume consistent
            return true;
        }
        return true;
    }
    
    private State transferCall(Step.Call call, State post) {
        // Check for randInt() call
        if ("pag.probe.Rand.randInt".equals(call.callee().qualifiedName())) {
            if (call.target().isPresent()) {
                // randInt() produces arbitrary value, forget target constraint
                Map<String, BigInteger> newEqualities = new HashMap<>(post.equalities);
                newEqualities.remove(call.target().get().name());
                return new State(newEqualities, false);
            }
        }
        // Unknown call: conservative, return post unchanged
        return post;
    }
    
    private BigInteger evaluateRVal(RVal rval, Map<String, BigInteger> equalities) {
        if (rval instanceof RVal.IntConst ic) {
            return ic.v();
        } else if (rval instanceof RVal.Binop binop) {
            BigInteger left = evaluateRVal(binop.l(), equalities);
            BigInteger right = evaluateRVal(binop.r(), equalities);
            
            if (left == null || right == null) {
                return null;
            }
            
            switch (binop.op()) {
                case Add: return left.add(right);
                case Sub: return left.subtract(right);
                case Mult: return left.multiply(right);
                case Lt:
                case Le:
                case Gt:
                case Ge:
                case Eq:
                case Ne: return null; // Comparisons return boolean
                default: return null;
            }
        } else if (rval instanceof LVal.Local local) {
            return equalities.get(local.name());
        } else if (rval instanceof LVal.StaticField sf) {
            // Handle BigInteger constants: ZERO, ONE, TWO, TEN
            if ("java.math.BigInteger".equals(sf.declaringClass())) {
                switch (sf.name()) {
                    case "ZERO": return BigInteger.ZERO;
                    case "ONE": return BigInteger.ONE;
                    case "TWO": return BigInteger.valueOf(2);
                    case "TEN": return BigInteger.TEN;
                    default: return null;
                }
            }
            return null;
        }
        return null;
    }
}
