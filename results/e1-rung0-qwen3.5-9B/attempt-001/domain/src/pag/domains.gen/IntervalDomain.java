package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;

/**
 * An interval-based abstract domain for backward reachability analysis.
 * 
 * This domain tracks [min, max] intervals for each variable. It is sound:
 * it never excludes a program state that could reach the target.
 * 
 * State representation: Map<String, Interval>
 * - Missing key: variable is unconstrained (top for that variable)
 * - Present key with interval: variable is constrained to that interval
 * 
 * Sentinel "__bottom__" is used to distinguish top() from bottom() states.
 */
public class IntervalDomain implements Domain<Map<String, Interval>> {
    
    /** Interval representing a range of integer values. */
    public static class Interval {
        private final BigInteger min;
        private final BigInteger max;
        
        public Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }
        
        public static Interval top() {
            return new Interval(
                BigInteger.valueOf(Long.MIN_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE)
            );
        }
        
        public static Interval bottom() {
            // Empty interval: min > max
            return new Interval(
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MIN_VALUE)
            );
        }
        
        public static Interval empty() {
            return bottom();
        }
        
        public static Interval of(BigInteger min, BigInteger max) {
            return new Interval(min, max);
        }
        
        /**
         * Whether this interval is empty (admits no values).
         */
        public boolean isBottom() {
            return min.compareTo(max) > 0;
        }
        
        /**
         * Whether this interval contains the given value.
         */
        public boolean contains(BigInteger v) {
            return min.compareTo(v) <= 0 && v.compareTo(max) <= 0;
        }
        
        /**
         * Widens this interval by another, producing a conservative over-approximation.
         */
        public Interval widen(Interval other) {
            BigInteger newMin = min.min(other.min);
            BigInteger newMax = max.max(other.max);
            return new Interval(newMin, newMax);
        }
        
        @Override
        public String toString() {
            return "[" + min + ", " + max + "]";
        }
    }
    
    /** Sentinel key to distinguish bottom() from top() states. */
    private static final String BOTTOM_SENTINEL = "__bottom__";
    
    @Override
    public String name() {
        return "IntervalDomain";
    }
    
    @Override
    public Map<String, Interval> top() {
        // top() = no constraints (all variables unconstrained)
        return Collections.emptyMap();
    }
    
    @Override
    public Map<String, Interval> bottom() {
        // bottom() = sentinel indicating empty state
        Map<String, Interval> s = new HashMap<>();
        s.put(BOTTOM_SENTINEL, Interval.bottom());
        return s;
    }
    
    @Override
    public boolean isBottom(Map<String, Interval> s) {
        return s.containsKey(BOTTOM_SENTINEL);
    }
    
    @Override
    public boolean entails(Map<String, Interval> a, Map<String, Interval> b) {
        // a entails b iff a admits everything b admits
        // For each variable in b, a must contain b's interval
        for (Map.Entry<String, Interval> entry : b.entrySet()) {
            String key = entry.getKey();
            Interval bInterval = entry.getValue();
            
            // If key is not in a, it's unconstrained (top), so it contains b's interval
            if (!a.containsKey(key)) {
                continue;
            }
            
            Interval aInterval = a.get(key);
            // aInterval must contain all values in bInterval
            // Since both are intervals, we check if aInterval's range contains bInterval's range
            if (aInterval.min.compareTo(bInterval.min) > 0 || 
                aInterval.max.compareTo(bInterval.max) < 0) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public Map<String, Interval> join(Map<String, Interval> a, Map<String, Interval> b) {
        // join = union of constraints (for each variable, take union of intervals)
        Map<String, Interval> result = new HashMap<>();
        
        // Get all keys from both maps
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        
        for (String key : keys) {
            Interval aInterval = a.get(key);
            Interval bInterval = b.get(key);
            
            if (aInterval == null) {
                result.put(key, bInterval);
            } else if (bInterval == null) {
                result.put(key, aInterval);
            } else {
                // Union of intervals: widen
                result.put(key, aInterval.widen(bInterval));
            }
        }
        
        return result;
    }
    
    @Override
    public Map<String, Interval> widen(Map<String, Interval> a, Map<String, Interval> b) {
        // For this domain, widening is the same as join
        // This ensures convergence in loop analysis
        return join(a, b);
    }
    
    @Override
    public Map<String, Interval> transfer(Step step, Map<String, Interval> post) {
        // Backward transfer: given state after step, return state before step
        Map<String, Interval> pre = new HashMap<>();
        
        // Copy all constraints from post to pre (excluding sentinel)
        for (Map.Entry<String, Interval> entry : post.entrySet()) {
            String key = entry.getKey();
            if (!key.equals(BOTTOM_SENTINEL)) {
                pre.put(key, entry.getValue());
            }
        }
        
        // Handle the step based on its type
        if (step instanceof Assign) {
            Assign assign = (Assign) step;
            LVal.Local target = assign.target();
            
            // Before the assignment, the target variable is unconstrained
            // (it holds the value of the source, which we don't track in detail)
            pre.remove(target.name);
            
            // Note: We don't transfer constraints from source to pre
            // because the source value is evaluated before the assignment
            // and we're doing backward analysis. This is a simplification
            // that is sound (we lose some precision but don't exclude states)
        } else if (step instanceof Assume) {
            Assume assume = (Assume) step;
            // Before the assume, the condition must hold
            // We don't have a way to represent "condition must hold" in our domain
            // So we just keep the current state (conservative approximation)
            // This is sound: we don't exclude any states that could satisfy the condition
        } else if (step instanceof Call) {
            Call call = (Call) step;
            // Before the call, the target variable is unconstrained
            // (it gets an arbitrary integer from randInt())
            if (call.target().isPresent()) {
                pre.remove(call.target().get().name);
            }
        }
        
        return pre;
    }
}
