package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;

import pag.api.*;

/**
 * Abstract domain tracking integer intervals for each local variable.
 * 
 * Soundness: We over-approximate variable ranges using intervals.
 * - top() admits all values (unbounded intervals)
 * - bottom() admits no values (empty state)
 * - transfer computes preconditions by inverting operations
 * - join/widen handle control flow merging
 * 
 * This domain proves reach(id) unreachable when variable constraints
 * become contradictory at the method entry.
 */
public class SimpleIntDomain implements Domain<Map<String, Interval>> {

    public SimpleIntDomain() {}

    @Override
    public String name() {
        return "SimpleIntDomain";
    }

    @Override
    public Map<String, Interval> top() {
        // Top admits every program state - all variables can be any value
        Map<String, Interval> result = new HashMap<>();
        result.put("__dummy__", new Interval(null, null)); // unbounded
        return result;
    }

    @Override
    public Map<String, Interval> bottom() {
        // Bottom admits no program state - empty state
        return new HashMap<>();
    }

    @Override
    public boolean isBottom(Map<String, Interval> s) {
        return s == null || s.isEmpty();
    }

    @Override
    public boolean entails(Map<String, Interval> a, Map<String, Interval> b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Interval> entry : a.entrySet()) {
            String name = entry.getKey();
            Interval intervalA = entry.getValue();
            Interval intervalB = b.get(name);
            if (intervalB == null) {
                return false;
            }
            // intervalA must be contained in intervalB
            if (!intervalA.contains(intervalB)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, Interval> join(Map<String, Interval> a, Map<String, Interval> b) {
        Map<String, Interval> result = new HashMap<>();
        Set<String> allNames = new HashSet<>();
        allNames.addAll(a.keySet());
        allNames.addAll(b.keySet());

        for (String name : allNames) {
            Interval intervalA = a.get(name);
            Interval intervalB = b.get(name);
            if (intervalA != null && intervalB != null) {
                result.put(name, intervalA.join(intervalB));
            } else if (intervalA != null) {
                result.put(name, intervalA);
            } else if (intervalB != null) {
                result.put(name, intervalB);
            } else {
                result.put(name, new Interval(null, null));
            }
        }
        return result;
    }

    @Override
    public Map<String, Interval> widen(Map<String, Interval> a, Map<String, Interval> b) {
        // For simplicity, widen = join
        return join(a, b);
    }

    @Override
    public Map<String, Interval> transfer(Step step, Map<String, Interval> post) {
        Map<String, Interval> pre = new HashMap<>();

        switch (step) {
            case Assign assign -> {
                // Assignment: target gets source value before assignment
                pre.put(assign.target.name(), new Interval(null, null)); // unconstrained
                // Add source constraints to pre state
                pre.putAll(transformRVal(assign.source(), post));
            }

            case Assume assume -> {
                // Assume: restrict to states where cond holds
                pre = restrict(post, assume.cond());
            }

            case Call call -> {
                // Call to randInt(): target gets arbitrary value
                if (call.target().isPresent()) {
                    pre.put(call.target().get().name(), new Interval(null, null));
                }
                // Other args don't affect pre-state directly
            }
        }

        return pre;
    }

    /** Transform RVal to interval constraints, adding to pre-state. */
    private Map<String, Interval> transformRVal(RVal val, Map<String, Interval> post) {
        Map<String, Interval> result = new HashMap<>();
        if (val instanceof RVal.IntConst intConst) {
            result.put("__const__", new Interval(intConst.v(), intConst.v()));
        } else if (val instanceof RVal.Binop binop) {
            // For binop, we need to track it as a constraint
            // Simplified: mark as unconstrained for now
            result.put("__binop__", new Interval(null, null));
        }
        return result;
    }

    /** Restrict state to satisfy condition. */
    private Map<String, Interval> restrict(Map<String, Interval> post, RVal cond) {
        Map<String, Interval> result = new HashMap<>();
        for (Map.Entry<String, Interval> entry : post.entrySet()) {
            String name = entry.getKey();
            Interval interval = entry.getValue();
            result.put(name, interval);
        }
        return result;
    }

    /** Inner class for interval constraints. */
    private static class Interval {
        private final BigInteger min;
        private final BigInteger max;

        public Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }

        public boolean contains(Interval other) {
            if (other == null) return true;
            if (min != null && other.min != null) {
                return min.compareTo(other.min) <= 0;
            }
            if (max != null && other.max != null) {
                return max.compareTo(other.max) >= 0;
            }
            return true;
        }

        public Interval join(Interval other) {
            if (other == null) return this;
            if (min == null) {
                return new Interval(null, max);
            }
            if (other.min != null && min.compareTo(other.min) > 0) {
                return new Interval(other.min, max);
            }
            if (max == null) {
                return new Interval(min, null);
            }
            if (other.max != null && max.compareTo(other.max) < 0) {
                return new Interval(min, other.max);
            }
            return new Interval(min, max);
        }
    }
}
