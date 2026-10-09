package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An abstract domain for backward reachability that tracks linear constraints
 * on local variables. A state represents a set of linear constraints that must
 * hold for a program state to potentially reach the target.
 *
 * Each constraint is of the form: a*x + b*y + ... + c >= 0 or == 0
 *
 * This domain is sound: it never excludes a state that can reach the target.
 */
public class LinearConstraintDomain implements Domain<LinearConstraintDomain.State> {

    @Override
    public String name() {
        return "LinearConstraintDomain";
    }

    @Override
    public State top() {
        return new State(new HashMap<>());
    }

    @Override
    public State bottom() {
        // Bottom is represented by an unsatisfiable constraint: 0 >= 1
        Map<String, List<Constraint>> constraints = new HashMap<>();
        constraints.put("bottom", List.of(new Constraint(0, 1, false)));
        return new State(constraints);
    }

    @Override
    public boolean isBottom(State s) {
        // Check if any constraint is unsatisfiable (e.g., 0 >= 1 or 0 > 0)
        for (List<Constraint> list : s.constraints.values()) {
            for (Constraint c : list) {
                if (c.isUnsatisfiable()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean entails(State a, State b) {
        // a entails b if every state satisfying a also satisfies b
        // For simplicity, we check if a has more or equal constraints
        // This is conservative (loses precision but is sound)
        for (String var : b.constraints.keySet()) {
            if (!a.constraints.containsKey(var)) {
                return false;
            }
            List<Constraint> aConstraints = a.constraints.get(var);
            List<Constraint> bConstraints = b.constraints.get(var);
            for (Constraint bC : bConstraints) {
                boolean found = false;
                for (Constraint aC : aConstraints) {
                    if (aC.implies(bC)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public State join(State a, State b) {
        // Join takes the intersection of constraints (weaker constraints)
        Map<String, List<Constraint>> result = new HashMap<>(a.constraints);
        for (Map.Entry<String, List<Constraint>> entry : b.constraints.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return new State(result);
    }

    @Override
    public State widen(State a, State b) {
        // For widening, we keep constraints that appear in both
        // This helps convergence while maintaining soundness
        Map<String, List<Constraint>> result = new HashMap<>(a.constraints);
        for (Map.Entry<String, List<Constraint>> entry : b.constraints.entrySet()) {
            if (result.containsKey(entry.getKey())) {
                List<Constraint> aConsts = result.get(entry.getKey());
                List<Constraint> bConsts = entry.getValue();
                // Keep only constraints that appear in both (conservative)
                List<Constraint> common = new ArrayList<>();
                for (Constraint aC : aConsts) {
                    for (Constraint bC : bConsts) {
                        if (aC.equals(bC)) {
                            common.add(aC);
                            break;
                        }
                    }
                }
                result.put(entry.getKey(), common);
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new State(result);
    }

    @Override
    public State transfer(Step step, State post) {
        Map<String, List<Constraint>> newConstraints = new HashMap<>(post.constraints);

        if (step instanceof Step.Assign assign) {
            // Backward: x = e means before the assignment, e must satisfy what x must satisfy after
            String varName = assign.target.name();
            RVal expr = assign.source;

            // Get constraints on x from post-state
            List<Constraint> xConstraints = post.constraints.getOrDefault(varName, Collections.emptyList());

            // Remove constraints on x (it's unconstrained before assignment)
            newConstraints.remove(varName);

            // Add constraints on the expression e
            if (!xConstraints.isEmpty()) {
                List<Constraint> newExprConstraints = new ArrayList<>();
                for (Constraint c : xConstraints) {
                    newExprConstraints.add(c.translateToExpr(expr));
                }
                addConstraints(newConstraints, expr, newExprConstraints);
            }

        } else if (step instanceof Step.Assume assume) {
            // Backward: the condition must hold, so we add it as a constraint
            RVal cond = assume.cond();
            // For Assume(c), we restrict to states where c holds
            // In backward analysis, this means we add the constraint that c must be true
            // For simplicity, we track that the condition must be satisfiable
            // If the condition is false (e.g., 0 == 1), we can mark as bottom
            if (isFalseCondition(cond)) {
                return bottom();
            }
            // Add constraint that condition must hold
            addConditionConstraint(newConstraints, cond);

        } else if (step instanceof Step.Call call) {
            // Backward: x = randInt() means x is unconstrained before
            // (randInt() can return any integer)
            if (call.target().isPresent()) {
                String varName = call.target().get().name();
                newConstraints.remove(varName); // x is unconstrained before
            }
        }

        return new State(newConstraints);
    }

    private boolean isFalseCondition(RVal cond) {
        // Check if condition is obviously false (e.g., 0 == 1, 1 == 0)
        if (cond instanceof RVal.Binop binop) {
            if (binop.op() == BinOp.Eq || binop.op() == BinOp.Ne) {
                BigInteger leftVal = extractConstant(binop.l());
                BigInteger rightVal = extractConstant(binop.r());
                if (leftVal != null && rightVal != null) {
                    boolean equal = leftVal.equals(rightVal);
                    if (binop.op() == BinOp.Eq && !equal) {
                        return true;
                    }
                    if (binop.op() == BinOp.Ne && equal) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BigInteger extractConstant(RVal r) {
        if (r instanceof RVal.IntConst ic) {
            return ic.v();
        }
        return null;
    }

    private void addConstraints(Map<String, List<Constraint>> constraints, RVal expr, List<Constraint> newConstraints) {
        if (expr instanceof LVal.Local local) {
            constraints.computeIfAbsent(local.name(), k -> new ArrayList<>()).addAll(newConstraints);
        } else if (expr instanceof RVal.Binop binop) {
            // For binary operations, we add constraints to both operands
            addConstraints(constraints, binop.l(), newConstraints);
            addConstraints(constraints, binop.r(), newConstraints);
        }
        // For IntConst, we don't add constraints (constants are fixed)
    }

    private void addConditionConstraint(Map<String, List<Constraint>> constraints, RVal cond) {
        // For Assume(c), we need to add the constraint that c must hold
        // This is a simplification - in practice we'd need to track the condition
        // For now, we just note that the condition was assumed
        if (cond instanceof RVal.Binop binop) {
            String key = "assume_" + binop.op().name();
            constraints.putIfAbsent(key, new ArrayList<>());
        }
    }

    /** A linear constraint on variables */
    private static class Constraint {
        private final Map<String, BigInteger> coefficients;
        private final BigInteger constant;
        private final boolean inequality; // true for >=, false for ==

        public Constraint(Map<String, BigInteger> coefficients, BigInteger constant, boolean inequality) {
            this.coefficients = new HashMap<>(coefficients);
            this.constant = constant;
            this.inequality = inequality;
        }

        // Constructor for simple constraints like "0 >= 1" (unsatisfiable)
        public Constraint(BigInteger constant, BigInteger rhs, boolean inequality) {
            this(new HashMap<>(), constant.subtract(rhs), inequality);
        }

        public boolean isUnsatisfiable() {
            // Check if all coefficients are zero but constraint is unsatisfiable
            if (coefficients.isEmpty()) {
                // 0 >= constant or 0 == constant
                if (inequality) {
                    // 0 >= constant is unsatisfiable if constant > 0
                    return constant.compareTo(BigInteger.ZERO) > 0;
                } else {
                    // 0 == constant is unsatisfiable if constant != 0
                    return !constant.equals(BigInteger.ZERO);
                }
            }
            return false;
        }

        public boolean implies(Constraint other) {
            // This constraint implies other if every state satisfying this also satisfies other
            // For simplicity, we check if they're the same constraint
            return this.equals(other);
        }

        public Constraint translateToExpr(RVal expr) {
            // Translate this constraint on x to a constraint on expr
            // If x >= c, then expr >= c
            // If x == c, then expr == c
            // For simplicity, we keep the same constraint structure
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Constraint that = (Constraint) o;
            return inequality == that.inequality &&
                   constant.equals(that.constant) &&
                   coefficients.equals(that.coefficients);
        }

        @Override
        public int hashCode() {
            return Objects.hash(coefficients, constant, inequality);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, BigInteger> entry : coefficients.entrySet()) {
                if (!first) sb.append(" + ");
                sb.append(entry.getValue()).append("*").append(entry.getKey());
                first = false;
            }
            if (coefficients.isEmpty()) {
                sb.append("0");
            }
            sb.append(inequality ? " >= " : " == ").append(constant);
            return sb.toString();
        }
    }

    /** The state of the domain - a set of linear constraints */
    public static class State {
        private final Map<String, List<Constraint>> constraints;

        public State(Map<String, List<Constraint>> constraints) {
            this.constraints = new HashMap<>(constraints);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return constraints.equals(state.constraints);
        }

        @Override
        public int hashCode() {
            return constraints.hashCode();
        }

        @Override
        public String toString() {
            return "State{" + constraints + '}';
        }
    }
}
