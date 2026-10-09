package pag.domains.gen;

import pag.api.*;

import java.math.BigInteger;
import java.util.*;

/**
 * Abstract domain for backward reachability analysis.
 * 
 * Tracks equality constraints on Local variables to prove reach(id) unreachable.
 * 
 * State representation: map from Local to Constraint
 * - Constraint.EQ(v): variable must equal v
 * - Constraint.ANY: variable is unconstrained
 * - Constraint.BOTTOM: no state admits this variable (used for isBottom)
 * 
 * Soundness: We never claim a variable cannot take a value unless it truly cannot.
 */
public class ReachabilityDomain implements Domain<State> {

    public static class State {
        private final Map<Local, Constraint> constraints = new HashMap<>();

        /**
         * Returns true if this state admits no program state.
         * Used at method entry to prove reach(id) unreachable.
         */
        public boolean isBottom() {
            // Bottom state: all variables have BOTTOM constraint
            return constraints.size() == 0 || 
                   constraints.values().stream().allMatch(c -> c == Constraint.BOTTOM);
        }

        public void setConstraint(Local l, Constraint c) {
            constraints.put(l, c);
        }

        public Constraint getConstraint(Local l) {
            return constraints.get(l);
        }

        public boolean contains(Local l) {
            return constraints.containsKey(l);
        }
    }

    public static enum Constraint {
        ANY,      // unconstrained - any value possible
        EQ(BigInteger),  // must equal this value
        BOTTOM;   // no value possible (bottom state marker)

        private final BigInteger value;

        Constraint(BigInteger value) {
            this.value = value;
        }

        public boolean equals(Constraint other) {
            return this == other;
        }

        public boolean isBottom() {
            return this == BOTTOM;
        }
    }

    public ReachabilityDomain() {
    }

    @Override
    public String name() {
        return "ReachabilityDomain";
    }

    @Override
    public State top() {
        return new State();
    }

    @Override
    public State bottom() {
        State s = new State();
        // Bottom: empty map means no constraints are tracked
        // isBottom() returns true for empty state
        return s;
    }

    @Override
    public boolean isBottom(State s) {
        // A state is bottom if it admits no program state
        // In our domain: empty constraint map = bottom
        return s == null || s.constraints.isEmpty();
    }

    @Override
    public boolean entails(State a, State b) {
        // a entails b means: every state admitted by a is also admitted by b
        // For soundness: if a is more constrained than b, a entails b
        // We use interval containment: a's range ⊆ b's range
        
        if (a == null || b == null) {
            return false;
        }

        if (a.constraints.isEmpty()) {
            // a is bottom, so it admits no state, thus entails everything
            return true;
        }

        if (b.constraints.isEmpty()) {
            // b is bottom, a cannot entail bottom unless a is also bottom
            return a.constraints.isEmpty();
        }

        // Check each variable: a's constraint must be more restrictive than b's
        for (Map.Entry<Local, Constraint> entry : a.constraints.entrySet()) {
            Local l = entry.getKey();
            Constraint aC = entry.getValue();

            if (!b.constraints.containsKey(l)) {
                // b doesn't constrain l, a does - a might be more restrictive
                // This is okay if aC is BOTTOM or EQ
                if (aC == Constraint.BOTTOM) {
                    continue;
                }
                // a constrains l to a specific value, b doesn't
                // This is sound: if a says x=v, b saying "unconstrained" admits x=v
                continue;
            }

            Constraint bC = b.constraints.get(l);

            // Check if aC is contained in bC
            if (!containsConstraint(aC, bC)) {
                return false;
            }
        }

        return true;
    }

    private boolean containsConstraint(Constraint aC, Constraint bC) {
        if (aC == Constraint.BOTTOM) {
            return true; // BOTTOM is contained in everything
        }
        if (bC == Constraint.BOTTOM) {
            return false; // nothing is contained in BOTTOM
        }
        if (aC == Constraint.ANY) {
            return bC == Constraint.ANY;
        }
        if (bC == Constraint.ANY) {
            return true; // ANY contains everything
        }

        // Both are EQ constraints
        if (aC.value.equals(bC.value)) {
            return true;
        }
        return false;
    }

    @Override
    public State join(State a, State b) {
        if (a == null) return b;
        if (b == null) return a;

        State result = new State();

        // Collect all variables from both states
        Set<Local> allVars = new HashSet<>();
        allVars.addAll(a.constraints.keySet());
        allVars.addAll(b.constraints.keySet());

        for (Local l : allVars) {
            Constraint ca = a.constraints.get(l);
            Constraint cb = b.constraints.get(l);

            // Join: take the least restrictive constraint that contains both
            Constraint joined = joinConstraint(ca, cb);
            result.setConstraint(l, joined);
        }

        return result;
    }

    private Constraint joinConstraint(Constraint a, Constraint b) {
        if (a == null) return b;
        if (b == null) return a;

        if (a == Constraint.BOTTOM || b == Constraint.BOTTOM) {
            return Constraint.BOTTOM;
        }

        if (a == Constraint.ANY) {
            return b;
        }
        if (b == Constraint.ANY) {
            return a;
        }

        // Both are EQ - join is ANY (union of two different values)
        if (a.value.equals(b.value)) {
            return a; // same value
        }

        return Constraint.ANY;
    }

    @Override
    public State widen(State a, State b) {
        // For this domain, widen = join (fixed point will converge)
        return join(a, b);
    }

    @Override
    public State transfer(Step step, State post) {
        if (post == null) {
            return new State();
        }

        State pre = new State();

        switch (step) {
            case Assign assign -> {
                Local target = assign.target;
                RVal source = assign.source;
                Constraint postC = post.constraints.get(target);

                // Backward transfer for assignment:
                // If post says target must be in constraint C,
                // then before assignment, source must evaluate to something in C
                // And target itself becomes unconstrained (it gets overwritten)

                // Propagate constraint from target to source
                Constraint sourceC = propagateConstraint(postC, source);
                pre.setConstraint(source, sourceC);

                // Target becomes unconstrained
                pre.setConstraint(target, Constraint.ANY);
            }

            case Assume assume -> {
                RVal cond = assume.cond;

                // Assume doesn't change variable values, but filters states
                // The constraint on variables remains the same
                pre.constraints.putAll(post.constraints);
            }

            case Call call -> {
                if (call.callee.qualifiedName().equals("pag.probe.Rand.randInt")) {
                    // randInt returns arbitrary integer
                    // Target variable becomes unconstrained
                    Local target = call.target.get();
                    pre.setConstraint(target, Constraint.ANY);
                } else {
                    // Other calls: target gets some value, but we can't determine it
                    // Conservative: treat target as unconstrained
                    Local target = call.target.get();
                    pre.setConstraint(target, Constraint.ANY);
                }

                // Other arguments/variables unchanged
                pre.constraints.putAll(post.constraints);
            }
        }

        return pre;
    }

    private Constraint propagateConstraint(Constraint postC, RVal source) {
        if (postC == Constraint.BOTTOM) {
            // If target must be impossible, source must also be impossible
            return Constraint.BOTTOM;
        }

        if (postC == Constraint.ANY) {
            return Constraint.ANY;
        }

        if (postC == Constraint.EQ(postC.value)) {
            // If target must equal v, then source must evaluate to v
            // We track this as an EQ constraint on source
            return Constraint.EQ(postC.value);
        }

        return Constraint.ANY;
    }
}
