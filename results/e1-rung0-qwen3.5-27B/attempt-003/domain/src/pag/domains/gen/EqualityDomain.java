package pag.domains.gen;

import pag.api.*;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An abstract domain that tracks equality constraints between variables and constants.
 * 
 * Each variable can be:
 * - Unconstrained (may be any value)
 * - Equal to a specific constant
 * - Equal to another variable (transitively)
 * 
 * This domain is sound for backward reachability analysis. It proves reach(id) calls
 * unreachable when the constraints required to reach them cannot be satisfied at method entry.
 */
public class EqualityDomain implements Domain<EqualityDomain.State> {

    @Override
    public String name() {
        return "EqualityDomain";
    }

    @Override
    public State top() {
        return new State(Map.of(), Map.of());
    }

    @Override
    public State bottom() {
        return new State(Map.of(), Map.of(), true);
    }

    @Override
    public boolean isBottom(State s) {
        return s.isBottom;
    }

    @Override
    public boolean entails(State a, State b) {
        if (a.isBottom) return true;
        if (b.isBottom) return false;
        
        // For entailment, every constraint in b must be implied by a
        // In our domain, this means a's equivalence classes must be subsets of b's
        return b.varToVal.entrySet().stream()
            .allMatch(entry -> {
                String var = entry.getKey();
                BigInteger expectedVal = entry.getValue();
                BigInteger actualVal = a.getVariableValue(var);
                return actualVal == null || actualVal.equals(expectedVal);
            })
            && b.varToVar.entrySet().stream()
                .allMatch(entry -> {
                    String var = entry.getKey();
                    String otherVar = entry.getValue();
                    // Check if var and otherVar are in the same equivalence class in a
                    BigInteger varVal = a.getVariableValue(var);
                    BigInteger otherVal = a.getVariableValue(otherVar);
                    return (varVal == null && otherVal == null) || 
                           (varVal != null && varVal.equals(otherVal));
                });
    }

    @Override
    public State join(State a, State b) {
        if (a.isBottom) return b;
        if (b.isBottom) return a;
        
        Map<String, BigInteger> joinedVarToVal = new HashMap<>();
        Map<String, String> joinedVarToVar = new HashMap<>();
        
        // For varToVal: only keep if both agree
        Set<String> commonVars = new HashSet<>(a.varToVal.keySet());
        commonVars.retainAll(b.varToVal.keySet());
        for (String var : commonVars) {
            BigInteger valA = a.varToVal.get(var);
            BigInteger valB = b.varToVal.get(var);
            if (valA.equals(valB)) {
                joinedVarToVal.put(var, valA);
            }
        }
        
        // For varToVar: only keep if both agree
        Set<String> commonVarsVar = new HashSet<>(a.varToVar.keySet());
        commonVarsVar.retainAll(b.varToVar.keySet());
        for (String var : commonVarsVar) {
            String otherA = a.varToVar.get(var);
            String otherB = b.varToVar.get(var);
            if (otherA.equals(otherB)) {
                joinedVarToVar.put(var, otherA);
            }
        }
        
        return new State(joinedVarToVal, joinedVarToVar, false);
    }

    @Override
    public State widen(State a, State b) {
        // For convergence, we can be more aggressive than join
        // In practice, this is similar to join for our domain
        return join(a, b);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom) return post;
        
        if (step instanceof Step.Assign assign) {
            return handleAssign(assign, post);
        } else if (step instanceof Step.Assume assume) {
            return handleAssume(assume, post);
        } else if (step instanceof Step.Call call) {
            return handleCall(call, post);
        }
        
        return post;
    }

    private State handleAssign(Step.Assign assign, State post) {
        LVal.Local target = assign.target();
        RVal source = assign.source();
        String targetName = target.name();
        
        // In backward analysis: target gets whatever constraints source has
        // and target itself becomes unconstrained (it could have been anything before)
        
        Map<String, BigInteger> newVarToVal = new HashMap<>(post.varToVal);
        Map<String, String> newVarToVar = new HashMap<>(post.varToVar);
        
        // Remove target from constraints (it's unconstrained before assignment)
        newVarToVal.remove(targetName);
        newVarToVar.remove(targetName);
        
        // Also remove any constraints where target is the "other" variable
        newVarToVar.entrySet().removeIf(entry -> entry.getValue().equals(targetName));
        
        // If source is a constant, add that constraint
        if (source instanceof RVal.IntConst intConst) {
            newVarToVal.put(targetName, intConst.v());
        } else if (source instanceof LVal.Local sourceLocal) {
            // target equals sourceLocal
            String sourceName = sourceLocal.name();
            newVarToVar.put(targetName, sourceName);
            
            // If sourceLocal has a constant value, target gets that too
            BigInteger sourceVal = post.getVariableValue(sourceName);
            if (sourceVal != null) {
                newVarToVal.put(targetName, sourceVal);
            }
        }
        // For Binop, we can't track the exact result, so target remains unconstrained
        
        return new State(newVarToVal, newVarToVar, false);
    }

    private State handleAssume(Step.Assume assume, State post) {
        RVal cond = assume.cond();
        
        // Only handle simple equality comparisons for now
        if (cond instanceof RVal.Binop binop) {
            BinOp op = binop.op();
            if (op == BinOp.Eq) {
                return handleEquality(binop.l(), binop.r(), post);
            } else if (op == BinOp.Ne) {
                // For inequality, we can't add positive constraints in a sound way
                // that would help prove unreachability, so we just return post
                return post;
            }
        }
        
        return post;
    }

    private State handleEquality(RVal left, RVal right, State post) {
        Map<String, BigInteger> newVarToVal = new HashMap<>(post.varToVal);
        Map<String, String> newVarToVar = new HashMap<>(post.varToVar);
        
        BigInteger leftVal = getValue(left, post);
        BigInteger rightVal = getValue(right, post);
        
        // If both are known constants
        if (leftVal != null && rightVal != null) {
            if (!leftVal.equals(rightVal)) {
                // Contradiction! This path is unreachable
                return bottom();
            }
            // Otherwise, the equality holds, no new constraints needed
            return post;
        }
        
        // If one is a constant and the other is a variable
        if (leftVal != null) {
            if (right instanceof LVal.Local local) {
                String varName = local.name();
                if (newVarToVal.containsKey(varName)) {
                    // Variable already has a value, check consistency
                    if (!newVarToVal.get(varName).equals(leftVal)) {
                        return bottom();
                    }
                } else {
                    newVarToVal.put(varName, leftVal);
                }
            }
        } else if (rightVal != null) {
            if (left instanceof LVal.Local local) {
                String varName = local.name();
                if (newVarToVal.containsKey(varName)) {
                    if (!newVarToVal.get(varName).equals(rightVal)) {
                        return bottom();
                    }
                } else {
                    newVarToVal.put(varName, rightVal);
                }
            }
        }
        
        // If both are variables, they should be equal
        if (left instanceof LVal.Local leftLocal && right instanceof LVal.Local rightLocal) {
            String leftName = leftLocal.name();
            String rightName = rightLocal.name();
            
            if (leftName.equals(rightName)) {
                return post;
            }
            
            // Check if they already have conflicting constant values
            BigInteger leftVal2 = post.getVariableValue(leftName);
            BigInteger rightVal2 = post.getVariableValue(rightName);
            
            if (leftVal2 != null && rightVal2 != null && !leftVal2.equals(rightVal2)) {
                return bottom();
            }
            
            // Add equality constraint
            newVarToVar.put(leftName, rightName);
            
            // Propagate constant values
            if (leftVal2 != null) {
                newVarToVal.put(rightName, leftVal2);
            } else if (rightVal2 != null) {
                newVarToVal.put(leftName, rightVal2);
            }
        }
        
        return new State(newVarToVal, newVarToVar, false);
    }

    private BigInteger getValue(RVal rval, State state) {
        if (rval instanceof RVal.IntConst intConst) {
            return intConst.v();
        } else if (rval instanceof LVal.Local local) {
            return state.getVariableValue(local.name());
        }
        return null;
    }

    private State handleCall(Step.Call call, State post) {
        // For randInt(), the target variable gets an arbitrary value
        // In backward analysis, this means the target becomes unconstrained
        
        if (call.target().isPresent()) {
            LVal.Local target = call.target().get();
            Map<String, BigInteger> newVarToVal = new HashMap<>(post.varToVal);
            Map<String, String> newVarToVar = new HashMap<>(post.varToVar);
            
            newVarToVal.remove(target.name());
            newVarToVar.remove(target.name());
            newVarToVar.entrySet().removeIf(entry -> entry.getValue().equals(target.name()));
            
            return new State(newVarToVal, newVarToVar, post.isBottom);
        }
        
        return post;
    }

    /**
     * State representation for the equality domain.
     */
    public static class State {
        final Map<String, BigInteger> varToVal;
        final Map<String, String> varToVar;
        final boolean isBottom;
        
        State(Map<String, BigInteger> varToVal, Map<String, String> varToVar) {
            this(varToVal, varToVar, false);
        }
        
        State(Map<String, BigInteger> varToVal, Map<String, String> varToVar, boolean isBottom) {
            this.varToVal = Collections.unmodifiableMap(varToVal);
            this.varToVar = Collections.unmodifiableMap(varToVar);
            this.isBottom = isBottom;
        }
        
        /**
         * Get the constant value of a variable, if known.
         * Returns null if the variable is unconstrained.
         */
        public BigInteger getVariableValue(String varName) {
            // First check direct constant assignment
            if (varToVal.containsKey(varName)) {
                return varToVal.get(varName);
            }
            
            // Then check if it's equal to another variable that has a constant
            if (varToVar.containsKey(varName)) {
                String otherVar = varToVar.get(varName);
                if (varToVal.containsKey(otherVar)) {
                    return varToVal.get(otherVar);
                }
            }
            
            return null;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return isBottom == state.isBottom &&
                   Objects.equals(varToVal, state.varToVal) &&
                   Objects.equals(varToVar, state.varToVar);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(varToVal, varToVar, isBottom);
        }
        
        @Override
        public String toString() {
            if (isBottom) return "⊥";
            return "State{varToVal=" + varToVal + ", varToVar=" + varToVar + "}";
        }
    }
}
