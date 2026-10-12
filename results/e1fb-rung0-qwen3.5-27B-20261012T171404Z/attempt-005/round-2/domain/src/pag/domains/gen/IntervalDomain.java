package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class IntervalDomain implements Domain<IntervalDomain.State> {

    public static final class State {
        private final Map<String, Interval> intervals;
        private final boolean isBottom;
        
        private State(Map<String, Interval> intervals, boolean isBottom) {
            this.intervals = intervals;
            this.isBottom = isBottom;
        }
        
        private Interval getInterval(String varName) {
            if (isBottom) return null;
            return intervals.get(varName);
        }
        
        private State withInterval(String varName, Interval interval) {
            if (isBottom) return this;
            Map<String, Interval> newIntervals = new HashMap<>(intervals);
            if (interval == null) {
                newIntervals.remove(varName);
            } else {
                newIntervals.put(varName, interval);
            }
            return new State(newIntervals, false);
        }
    }
    
    private static final class Interval {
        final BigInteger min;
        final BigInteger max;
        
        Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }
    }
    
    public IntervalDomain() {
    }
    
    @Override
    public String name() {
        return "IntervalDomain";
    }
    
    @Override
    public State top() {
        return new State(new HashMap<>(), false);
    }
    
    @Override
    public State bottom() {
        return new State(new HashMap<>(), true);
    }
    
    @Override
    public boolean isBottom(State s) {
        return s.isBottom;
    }
    
    @Override
    public boolean entails(State a, State b) {
        if (a.isBottom) return true;
        if (b.isBottom) return false;
        for (Map.Entry<String, Interval> entry : a.intervals.entrySet()) {
            String var = entry.getKey();
            Interval aInt = entry.getValue();
            Interval bInt = b.intervals.get(var);
            if (bInt == null) return false;
            if (aInt.min.compareTo(bInt.min) < 0) return false;
            if (aInt.max.compareTo(bInt.max) > 0) return false;
        }
        return true;
    }
    
    @Override
    public State join(State a, State b) {
        if (a.isBottom) return b;
        if (b.isBottom) return a;
        Map<String, Interval> newIntervals = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.intervals.keySet());
        allVars.addAll(b.intervals.keySet());
        for (String var : allVars) {
            Interval aInt = a.intervals.get(var);
            Interval bInt = b.intervals.get(var);
            if (aInt == null) {
                newIntervals.put(var, bInt);
            } else if (bInt == null) {
                newIntervals.put(var, aInt);
            } else {
                BigInteger newMin = min(aInt.min, bInt.min);
                BigInteger newMax = max(aInt.max, bInt.max);
                newIntervals.put(var, new Interval(newMin, newMax));
            }
        }
        return new State(newIntervals, false);
    }
    
    @Override
    public State widen(State a, State b) {
        if (a.isBottom) return b;
        if (b.isBottom) return a;
        Map<String, Interval> newIntervals = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.intervals.keySet());
        allVars.addAll(b.intervals.keySet());
        for (String var : allVars) {
            Interval aInt = a.intervals.get(var);
            Interval bInt = b.intervals.get(var);
            if (aInt == null) {
                newIntervals.put(var, bInt);
            } else if (bInt == null) {
                newIntervals.put(var, aInt);
            } else {
                BigInteger newMin = min(aInt.min, bInt.min);
                BigInteger newMax = max(aInt.max, bInt.max);
                if (newMin.compareTo(aInt.min) < 0) {
                    newMin = BigInteger.valueOf(Long.MIN_VALUE);
                }
                if (newMax.compareTo(aInt.max) > 0) {
                    newMax = BigInteger.valueOf(Long.MAX_VALUE);
                }
                newIntervals.put(var, new Interval(newMin, newMax));
            }
        }
        return new State(newIntervals, false);
    }
    
    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom) return bottom();
        
        switch (step) {
            case Step.Assign assign -> {
                return transferAssign(assign, post);
            }
            case Step.Assume assume -> {
                return transferAssume(assume, post);
            }
            case Step.Call call -> {
                return transferCall(call, post);
            }
        }
    }
    
    private State transferAssign(Step.Assign assign, State post) {
        String varName = assign.target().name();
        RVal source = assign.source();
        Interval xInterval = post.getInterval(varName);
        State pre = post.withInterval(varName, null);
        
        if (xInterval != null) {
            if (source instanceof RVal.IntConst) {
                RVal.IntConst constVal = (RVal.IntConst) source;
                BigInteger val = constVal.v();
                if (val.compareTo(xInterval.min) < 0 || val.compareTo(xInterval.max) > 0) {
                    return bottom();
                }
            } else if (source instanceof LVal.Local) {
                LVal.Local sourceLocal = (LVal.Local) source;
                String sourceVar = sourceLocal.name();
                pre = pre.withInterval(sourceVar, xInterval);
            }
        }
        
        return pre;
    }
    
    private State transferAssume(Step.Assume assume, State post) {
        RVal cond = assume.cond();
        
        if (cond instanceof RVal.Binop) {
            RVal.Binop binop = (RVal.Binop) cond;
            BinOp op = binop.op();
            RVal left = binop.l();
            RVal right = binop.r();
            
            if (left instanceof LVal.Local && right instanceof RVal.IntConst) {
                LVal.Local local = (LVal.Local) left;
                RVal.IntConst intConst = (RVal.IntConst) right;
                String varName = local.name();
                BigInteger constVal = intConst.v();
                
                Interval currentInterval = post.getInterval(varName);
                Interval newInterval = null;
                
                if (currentInterval != null) {
                    switch (op) {
                        case Lt -> newInterval = restrictLt(currentInterval, constVal);
                        case Le -> newInterval = restrictLe(currentInterval, constVal);
                        case Gt -> newInterval = restrictGt(currentInterval, constVal);
                        case Ge -> newInterval = restrictGe(currentInterval, constVal);
                        case Eq -> newInterval = restrictEq(currentInterval, constVal);
                        case Ne -> newInterval = restrictNe(currentInterval, constVal);
                        default -> newInterval = currentInterval;
                    }
                } else {
                    switch (op) {
                        case Lt -> newInterval = new Interval(BigInteger.valueOf(Long.MIN_VALUE), constVal.subtract(BigInteger.ONE));
                        case Le -> newInterval = new Interval(BigInteger.valueOf(Long.MIN_VALUE), constVal);
                        case Gt -> newInterval = new Interval(constVal.add(BigInteger.ONE), BigInteger.valueOf(Long.MAX_VALUE));
                        case Ge -> newInterval = new Interval(constVal, BigInteger.valueOf(Long.MAX_VALUE));
                        case Eq -> newInterval = new Interval(constVal, constVal);
                        case Ne -> newInterval = null;
                        default -> newInterval = null;
                    }
                }
                
                if (newInterval == null && currentInterval != null) {
                    return bottom();
                }
                
                return post.withInterval(varName, newInterval);
            }
        }
        
        return post;
    }
    
    private Interval restrictLt(Interval interval, BigInteger constVal) {
        BigInteger newMax = constVal.subtract(BigInteger.ONE);
        if (interval.min.compareTo(newMax) > 0) return null;
        return new Interval(interval.min, min(interval.max, newMax));
    }
    
    private Interval restrictLe(Interval interval, BigInteger constVal) {
        if (interval.min.compareTo(constVal) > 0) return null;
        return new Interval(interval.min, min(interval.max, constVal));
    }
    
    private Interval restrictGt(Interval interval, BigInteger constVal) {
        BigInteger newMin = constVal.add(BigInteger.ONE);
        if (interval.max.compareTo(newMin) < 0) return null;
        return new Interval(max(interval.min, newMin), interval.max);
    }
    
    private Interval restrictGe(Interval interval, BigInteger constVal) {
        if (interval.max.compareTo(constVal) < 0) return null;
        return new Interval(max(interval.min, constVal), interval.max);
    }
    
    private Interval restrictEq(Interval interval, BigInteger constVal) {
        if (interval.min.compareTo(constVal) > 0 || interval.max.compareTo(constVal) < 0) return null;
        return new Interval(constVal, constVal);
    }
    
    private Interval restrictNe(Interval interval, BigInteger constVal) {
        return interval;
    }
    
    private State transferCall(Step.Call call, State post) {
        if (call.target().isPresent()) {
            LVal.Local target = call.target().get();
            String varName = target.name();
            return post.withInterval(varName, null);
        }
        return post;
    }
    
    private static BigInteger min(BigInteger a, BigInteger b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
    
    private static BigInteger max(BigInteger a, BigInteger b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
