package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class IntegerConstraintDomain implements Domain<IntegerConstraintDomain.State> {

    /**
     * Abstract state tracking constraints on integer variables.
     * Each variable is constrained to: UNCONSTRAINED (all integer values), EQUALS(v) (exactly v), or BOTTOM (no values).
     * A state is BOTTOM if any variable is constrained to BOTTOM, ensuring soundness.
     */
    public static final class State {
        private final Map<String, Constraint> constraints = new HashMap<>();

        public static State top() {
            return new State();
        }

        public static State bottom() {
            return new State();
        }

        public boolean isBottom() {
            // Soundness: return true if any variable has BOTTOM constraint
            for (Constraint c : constraints.values()) {
                if (c == Constraint.BOTTOM) {
                    return true;
                }
            }
            return false;
        }

        public State copy() {
            State s = new State();
            s.constraints.putAll(this.constraints);
            return s;
        }
    }

    /**
     * Constraint on a single variable: either unconstrained, equals a specific value, or bottom.
     */
    public static final class Constraint {
        private final BigInteger value;
        private final boolean equals;

        private Constraint() {
            this.value = null;
            this.equals = false;
        }

        private Constraint(BigInteger value, boolean equals) {
            this.value = value;
            this.equals = equals;
        }

        public static final Constraint UNCONSTRAINED = new Constraint(null, false);
        public static final Constraint BOTTOM = new Constraint(null, true);

        public static Constraint equals(BigInteger v) {
            return new Constraint(v, true);
        }
    }

    public IntegerConstraintDomain() {
    }

    @Override
    public String name() {
        return "IntegerConstraintDomain";
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
        // a entails b if every state admitted by a is also admitted by b
        // For each variable, if a constrains it to EQUALS(v), b must also constrain it to EQUALS(v) or BOTTOM
        for (String var : a.constraints.keySet()) {
            Constraint ca = a.constraints.get(var);
            if (ca == Constraint.BOTTOM) {
                return true; // BOTTOM entails everything
            }
            if (ca.equals) {
                Constraint cb = b.constraints.getOrDefault(var, Constraint.UNCONSTRAINED);
                if (cb.equals && cb.value.equals(ca.value)) {
                    continue;
                }
                // a constrains var to exactly v, but b doesn't constrain it to v or BOTTOM
                return false;
            }
            // a is UNCONSTRAINED on this variable, b's constraint is fine
        }
        return true;
    }

    @Override
    public State join(State a, State b) {
        State result = State.top();
        for (String var : a.constraints.keySet()) {
            Constraint ca = a.constraints.get(var);
            Constraint cb = b.constraints.getOrDefault(var, Constraint.UNCONSTRAINED);
            if (ca == Constraint.BOTTOM || cb == Constraint.BOTTOM) {
                result.constraints.put(var, Constraint.BOTTOM);
            } else if (ca.equals && cb.equals) {
                if (!ca.value.equals(cb.value)) {
                    result.constraints.put(var, Constraint.BOTTOM);
                } else {
                    result.constraints.put(var, Constraint.equals(ca.value));
                }
            } else if (ca.equals) {
                result.constraints.put(var, Constraint.equals(ca.value));
            } else if (cb.equals) {
                result.constraints.put(var, Constraint.equals(cb.value));
            } else {
                result.constraints.put(var, Constraint.UNCONSTRAINED);
            }
        }
        return result;
    }

    @Override
    public State widen(State a, State b) {
        State result = State.top();
        for (String var : a.constraints.keySet()) {
            Constraint ca = a.constraints.get(var);
            Constraint cb = b.constraints.getOrDefault(var, Constraint.UNCONSTRAINED);
            if (ca == Constraint.BOTTOM || cb == Constraint.BOTTOM) {
                result.constraints.put(var, Constraint.BOTTOM);
            } else if (ca.equals || cb.equals) {
                // Widen EQUALS to UNCONSTRAINED for convergence
                result.constraints.put(var, Constraint.UNCONSTRAINED);
            } else {
                result.constraints.put(var, Constraint.UNCONSTRAINED);
            }
        }
        return result;
    }

    @Override
    public State transfer(Step step, State post) {
        State pre = State.top();
        
        switch (step) {
            case Step.Assign assign -> {
                // post constrains assign.target(), so pre constrains the source expression
                // The target variable is unconstrained before the assignment
                String targetVar = assign.target().name();
                
                // Target is unconstrained before assignment
                pre.constraints.remove(targetVar);
                
                RVal source = assign.source();
                if (source instanceof LVal.Local local) {
                    // If source is a variable, pre constrains it to the same value as post constrains target
                    Constraint postTargetConstraint = post.constraints.get(targetVar);
                    if (postTargetConstraint == Constraint.UNCONSTRAINED) {
                        pre.constraints.remove(local.name());
                    } else if (postTargetConstraint.equals) {
                        pre.constraints.put(local.name(), Constraint.equals(postTargetConstraint.value));
                    } else {
                        pre.constraints.remove(local.name());
                    }
                } else if (source instanceof RVal.IntConst intConst) {
                    // If source is a constant, no constraint on source before
                    pre.constraints.remove(intConst.v().toString());
                } else {
                    // For Binop, we conservatively remove any constraint
                    pre.constraints.remove(source.toString());
                }
            }
            case Step.Assume assume -> {
                // Intersect post with assume condition
                RVal cond = assume.cond();
                if (cond instanceof LVal.Local local) {
                    String condVar = local.name();
                    Constraint condConstraint = post.constraints.get(condVar);
                    if (condConstraint == Constraint.BOTTOM) {
                        pre.constraints.put(condVar, Constraint.BOTTOM);
                    }
                } else if (cond instanceof RVal.IntConst intConst) {
                    // Constant condition: if it's 0, this is contradiction
                    if (intConst.v().equals(BigInteger.ZERO)) {
                        pre.constraints.put(condVar, Constraint.BOTTOM);
                    }
                } else if (cond instanceof RVal.Binop binop) {
                    // For Binop conditions, we conservatively keep post's constraints
                    // This is sound but loses precision
                }
            }
            case Step.Call call -> {
                // Call to Rand.randInt(): target gets arbitrary integer, so unconstrained
                if (call.target().isPresent()) {
                    String targetVar = call.target().get().name();
                    pre.constraints.remove(targetVar);
                }
            }
        }

        // For Assume with zero constant, mark as BOTTOM
        if (step instanceof Step.Assume assume) {
            RVal cond = assume.cond();
            if (cond instanceof RVal.IntConst intConst) {
                if (intConst.v().equals(BigInteger.ZERO)) {
                    pre.constraints.putAll(post.constraints);
                }
            }
        }

        return pre;
    }
}
