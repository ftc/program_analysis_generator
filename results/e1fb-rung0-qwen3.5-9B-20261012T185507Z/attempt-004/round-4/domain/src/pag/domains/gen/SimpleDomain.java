package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class SimpleDomain implements Domain<SimpleDomain.State> {

    /**
     * Abstract state: for each variable, track whether it's constrained to a specific value
     * or unconstrained (any value). IMPOSSIBLE means this state admits no program states.
     */
    public static final class State {
        private final Map<String, Constraint> constraints;

        private State(Map<String, Constraint> constraints) {
            this.constraints = constraints;
        }

        /** Top state: all variables are unconstrained (any value is possible). */
        public static final State TOP = new State(new HashMap<>());

        /** Bottom state: all variables are IMPOSSIBLE (no state admitted). */
        public static final State BOTTOM = new State(
            new HashMap<>(Collections.singletonMap("bottom_marker", Constraint.IMPOSSIBLE))
        );

        /**
         * Whether this state admits no program state.
         * Soundness: must return true only if truly impossible.
         */
        public boolean isBottom() {
            // Check if there's any non-IMPOSSIBLE constraint
            for (Constraint c : constraints.values()) {
                if (c != Constraint.IMPOSSIBLE) {
                    return false;
                }
            }
            return true;
        }

        /** Get constraint for a variable name, or null if unconstrained. */
        public Constraint get(String name) {
            return constraints.get(name);
        }

        /** Get constraints map. */
        public Map<String, Constraint> constraints() {
            return constraints;
        }

        /** Create a new state with updated constraints. */
        private static State of(Map<String, Constraint> newConstraints) {
            return new State(new HashMap<>(newConstraints));
        }
    }

    /**
     * Constraint on a variable: UNCONSTRAINED means any value is possible,
     * CONSTRAINED means only this value is possible, IMPOSSIBLE means no state.
     */
    public static final class Constraint {
        public static final Constraint UNCONSTRAINED = new Constraint(null, true);
        public static final Constraint IMPOSSIBLE = new Constraint(null, false);

        private final BigInteger value;
        private final boolean isUnconstrained;

        private Constraint(BigInteger value, boolean isUnconstrained) {
            this.value = value;
            this.isUnconstrained = isUnconstrained;
        }

        public BigInteger value() {
            return value;
        }

        public boolean isUnconstrained() {
            return isUnconstrained;
        }

        public static Constraint CONSTRAINED(BigInteger v) {
            return new Constraint(v, false);
        }
    }

    /**
     * Constructor with no arguments as required by the engine.
     */
    public SimpleDomain() {
    }

    @Override
    public String name() {
        return "SimpleDomain";
    }

    @Override
    public State top() {
        return State.TOP;
    }

    @Override
    public State bottom() {
        return State.BOTTOM;
    }

    @Override
    public boolean isBottom(State s) {
        return s.isBottom();
    }

    @Override
    public boolean entails(State a, State b) {
        // If a is bottom, it entails everything (vacuously true)
        if (a.isBottom()) {
            return true;
        }

        // Check that for each variable, a's constraint implies b's constraint
        for (String var : a.constraints().keySet()) {
            Constraint aConstraint = a.get(var);
            Constraint bConstraint = b.get(var);

            if (aConstraint == Constraint.UNCONSTRAINED) {
                // UNCONSTRAINED implies anything
                continue;
            }

            if (bConstraint == Constraint.UNCONSTRAINED) {
                // UNCONSTRAINED does not imply CONSTRAINED
                return false;
            }

            if (aConstraint == Constraint.CONSTRAINED && bConstraint == Constraint.CONSTRAINED) {
                // CONSTRAINED implies CONSTRAINED only if values match
                if (!aConstraint.value().equals(bConstraint.value())) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public State join(State a, State b) {
        // Join: admit everything a or b admits
        Map<String, Constraint> joined = new HashMap<>();

        // Get all variable names from both states
        Set<String> allVars = new HashSet<>(a.constraints().keySet());
        allVars.addAll(b.constraints().keySet());

        for (String var : allVars) {
            Constraint aConstraint = a.get(var);
            Constraint bConstraint = b.get(var);

            if (aConstraint == Constraint.IMPOSSIBLE || bConstraint == Constraint.IMPOSSIBLE) {
                // If either is impossible, join is impossible
                joined.put(var, Constraint.IMPOSSIBLE);
            } else if (aConstraint == Constraint.UNCONSTRAINED) {
                joined.put(var, bConstraint);
            } else if (bConstraint == Constraint.UNCONSTRAINED) {
                joined.put(var, aConstraint);
            } else if (aConstraint == Constraint.CONSTRAINED && bConstraint == Constraint.CONSTRAINED) {
                // Both constrained to specific values
                if (aConstraint.value().equals(bConstraint.value())) {
                    joined.put(var, aConstraint);
                } else {
                    // Conflict: different values - join is impossible
                    joined.put(var, Constraint.IMPOSSIBLE);
                }
            } else {
                joined.put(var, Constraint.UNCONSTRAINED);
            }
        }

        return State.of(joined);
    }

    @Override
    public State widen(State a, State b) {
        // Widen: same as join for this domain (convergence will be handled by iteration limit)
        return join(a, b);
    }

    @Override
    public State transfer(Step step, State post) {
        switch (step) {
            case Step.Assign assign -> {
                // Assign(x, e): x holds value of e evaluated before step
                // If post constrains x to v, then pre constrains e to v, and x becomes unconstrained
                Map<String, Constraint> preConstraints = new HashMap<>();

                // First, evaluate e in pre-state to get value v
                BigInteger eValue = evaluateRVal(assign.source(), post);
                if (eValue != null) {
                    preConstraints.put(assign.target().name(), Constraint.CONSTRAINED(eValue));
                } else {
                    // If e cannot be evaluated (contains variables), x becomes unconstrained
                    preConstraints.put(assign.target().name(), Constraint.UNCONSTRAINED);
                }

                // All other variables from post are carried forward (but x is reset)
                for (String var : post.constraints().keySet()) {
                    if (!var.equals(assign.target().name())) {
                        Constraint c = post.get(var);
                        if (c != Constraint.IMPOSSIBLE) {
                            preConstraints.put(var, c);
                        }
                    }
                }

                return State.of(preConstraints);
            }

            case Step.Assume assume -> {
                // Assume(c): nothing changes, but only states where c holds pass
                // Restrict post to states where c holds
                Map<String, Constraint> preConstraints = new HashMap<>();

                // Add all constraints from post
                for (String var : post.constraints().keySet()) {
                    Constraint c = post.get(var);
                    if (c != Constraint.IMPOSSIBLE) {
                        preConstraints.put(var, c);
                    }
                }

                // Evaluate cond in pre-state to see if it's satisfiable
                // If cond constrains some variable to a value, add that constraint
                Set<String> constrainedVars = new HashSet<>();
                evaluateRVal(assume.cond(), post, constrainedVars);

                // If cond is satisfiable, keep the state; otherwise, make it impossible
                if (constrainedVars.isEmpty()) {
                    // No constraints from cond - state is valid
                    return State.of(preConstraints);
                } else {
                    // Cond constrains variables - add those constraints
                    for (String var : constrainedVars) {
                        Constraint c = post.get(var);
                        if (c != Constraint.IMPOSSIBLE && c != Constraint.UNCONSTRAINED) {
                            preConstraints.put(var, c);
                        }
                    }
                    return State.of(preConstraints);
                }
            }

            case Step.Call call -> {
                // Call(x, randInt()): x becomes unconstrained in pre
                Map<String, Constraint> preConstraints = new HashMap<>();

                // Add all constraints from post except the target variable
                String targetName = call.target().get().name();
                for (String var : post.constraints().keySet()) {
                    if (!var.equals(targetName)) {
                        Constraint c = post.get(var);
                        if (c != Constraint.IMPOSSIBLE) {
                            preConstraints.put(var, c);
                        }
                    }
                }

                // Target variable becomes unconstrained (randInt gives arbitrary value)
                preConstraints.put(targetName, Constraint.UNCONSTRAINED);

                return State.of(preConstraints);
            }
        }

        throw new UnsupportedOperationException("Unknown step: " + step);
    }

    /**
     * Evaluate an RVal in the given state.
     * Returns the value if it can be fully evaluated, or null if it depends on variables.
     */
    private BigInteger evaluateRVal(RVal rval, State state) {
        if (rval instanceof LVal.Local local) {
            // LVal read - depends on variable value
            return null;
        } else if (rval instanceof RVal.IntConst intConst) {
            return intConst.v();
        } else if (rval instanceof RVal.Binop binop) {
            BigInteger left = evaluateRVal(binop.l(), state);
            BigInteger right = evaluateRVal(binop.r(), state);
            if (left != null && right != null) {
                switch (binop.op()) {
                    case Mult:
                        return left.multiply(right);
                    case Add:
                        return left.add(right);
                    case Sub:
                        return left.subtract(right);
                    case Lt:
                    case Le:
                    case Gt:
                    case Ge:
                    case Eq:
                    case Ne:
                        throw new IllegalArgumentException("Comparison not supported in evaluation");
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Evaluate an RVal and collect constrained variable names.
     */
    private void evaluateRVal(RVal rval, State state, Set<String> constrainedVars) {
        if (rval instanceof LVal.Local local) {
            // LVal read - variable is constrained
            constrainedVars.add(local.name());
        } else if (rval instanceof RVal.Binop binop) {
            evaluateRVal(binop.l(), state, constrainedVars);
            evaluateRVal(binop.r(), state, constrainedVars);
        }
    }
}
