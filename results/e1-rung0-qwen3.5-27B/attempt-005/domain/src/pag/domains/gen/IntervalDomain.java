package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;

public class IntervalDomain implements Domain<IntervalState> {
    
    public IntervalDomain() {}
    
    @Override
    public String name() {
        return "IntervalDomain";
    }
    
    @Override
    public IntervalState top() {
        return new IntervalState();
    }
    
    @Override
    public IntervalState bottom() {
        return IntervalState.BOTTOM;
    }
    
    @Override
    public boolean isBottom(IntervalState s) {
        return s.isBottom();
    }
    
    @Override
    public boolean entails(IntervalState a, IntervalState b) {
        if (a.isBottom()) return true;
        if (b.isBottom()) return false;
        
        for (Map.Entry<String, Interval> entry : a.intervals.entrySet()) {
            String var = entry.getKey();
            Interval aInt = entry.getValue();
            Interval bInt = b.intervals.get(var);
            
            if (bInt == null) continue;
            if (!aInt.isContainedIn(bInt)) return false;
        }
        return true;
    }
    
    @Override
    public IntervalState join(IntervalState a, IntervalState b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        
        Map<String, Interval> joined = new HashMap<>();
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.intervals.keySet());
        allVars.addAll(b.intervals.keySet());
        
        for (String var : allVars) {
            Interval aInt = a.intervals.get(var);
            Interval bInt = b.intervals.get(var);
            joined.put(var, Interval.join(aInt, bInt));
        }
        
        return new IntervalState(joined);
    }
    
    @Override
    public IntervalState widen(IntervalState a, IntervalState b) {
        return join(a, b);
    }
    
    @Override
    public IntervalState transfer(Step step, IntervalState post) {
        if (post.isBottom()) return post;
        
        if (step instanceof Step.Assign assign) {
            return transferAssign(assign, post);
        } else if (step instanceof Step.Assume assume) {
            return transferAssume(assume, post);
        } else if (step instanceof Step.Call call) {
            return transferCall(call, post);
        }
        
        return post;
    }
    
    private IntervalState transferAssign(Step.Assign assign, IntervalState post) {
        String target = assign.target().name();
        RVal source = assign.source();
        
        Map<String, Interval> pre = new HashMap<>(post.intervals);
        pre.remove(target);
        
        constrainRVal(source, pre, post.intervals.get(target));
        
        return new IntervalState(pre);
    }
    
    private void constrainRVal(RVal rval, Map<String, Interval> intervals, Interval targetInterval) {
        if (rval instanceof RVal.IntConst constVal) {
            if (targetInterval != null && !targetInterval.contains(constVal.v())) {
                intervals.put("CONTRADICTION", Interval.BOTTOM);
            }
        } else if (rval instanceof LVal local) {
            String varName = local.name();
            Interval current = intervals.get(varName);
            if (targetInterval != null) {
                intervals.put(varName, Interval.meet(current, targetInterval));
            }
        } else if (rval instanceof RVal.Binop binop) {
            constrainRVal(binop.l(), intervals, targetInterval);
            constrainRVal(binop.r(), intervals, targetInterval);
        }
    }
    
    private IntervalState transferAssume(Step.Assume assume, IntervalState post) {
        RVal cond = assume.cond();
        Map<String, Interval> pre = new HashMap<>(post.intervals);
        
        if (cond instanceof RVal.Binop binop) {
            if (binop.op() == BinOp.Eq) {
                constrainEquality(binop, pre, true);
            } else if (binop.op() == BinOp.Ne) {
                constrainEquality(binop, pre, false);
            } else if (binop.op() == BinOp.Lt || binop.op() == BinOp.Le ||
                       binop.op() == BinOp.Gt || binop.op() == BinOp.Ge) {
                constrainComparison(binop, pre);
            }
        }
        
        return new IntervalState(pre);
    }
    
    private void constrainEquality(RVal.Binop binop, Map<String, Interval> intervals, boolean eq) {
        if (binop.l() instanceof LVal leftVar && binop.r() instanceof LVal rightVar) {
            Interval left = intervals.getOrDefault(leftVar.name(), Interval.TOP);
            Interval right = intervals.getOrDefault(rightVar.name(), Interval.TOP);
            Interval intersection = Interval.meet(left, right);
            
            if (intersection.isBottom()) {
                intervals.put("CONTRADICTION", Interval.BOTTOM);
            } else if (eq) {
                intervals.put(leftVar.name(), intersection);
                intervals.put(rightVar.name(), intersection);
            }
        } else if (binop.l() instanceof LVal var && binop.r() instanceof RVal.IntConst constVal) {
            String varName = var.name();
            Interval current = intervals.getOrDefault(varName, Interval.TOP);
            Interval constInterval = eq ? new Interval(constVal.v(), constVal.v()) : Interval.TOP;
            intervals.put(varName, Interval.meet(current, constInterval));
        } else if (binop.r() instanceof LVal var && binop.l() instanceof RVal.IntConst constVal) {
            String varName = var.name();
            Interval current = intervals.getOrDefault(varName, Interval.TOP);
            Interval constInterval = eq ? new Interval(constVal.v(), constVal.v()) : Interval.TOP;
            intervals.put(varName, Interval.meet(current, constInterval));
        }
    }
    
    private void constrainComparison(RVal.Binop binop, Map<String, Interval> intervals) {
        if (binop.l() instanceof LVal var) {
            String varName = var.name();
            Interval current = intervals.getOrDefault(varName, Interval.TOP);
            BigInteger limit = null;
            
            if (binop.r() instanceof RVal.IntConst constVal) {
                limit = constVal.v();
            }
            
            switch (binop.op()) {
                case Lt:
                    if (limit != null) {
                        intervals.put(varName, Interval.meet(current, new Interval(null, limit.subtract(BigInteger.ONE))));
                    }
                    break;
                case Le:
                    if (limit != null) {
                        intervals.put(varName, Interval.meet(current, new Interval(null, limit)));
                    }
                    break;
                case Gt:
                    if (limit != null) {
                        intervals.put(varName, Interval.meet(current, new Interval(limit.add(BigInteger.ONE), null)));
                    }
                    break;
                case Ge:
                    if (limit != null) {
                        intervals.put(varName, Interval.meet(current, new Interval(limit, null)));
                    }
                    break;
            }
        }
    }
    
    private IntervalState transferCall(Step.Call call, IntervalState post) {
        Map<String, Interval> pre = new HashMap<>(post.intervals);
        
        if (call.target().isPresent()) {
            String target = call.target().get().name();
            pre.remove(target);
        }
        
        return new IntervalState(pre);
    }
    
    private static class Interval {
        private static final Interval TOP = new Interval(null, null);
        private static final Interval BOTTOM = new Interval(BigInteger.ONE, BigInteger.ZERO);
        
        private final BigInteger min;
        private final BigInteger max;
        
        private Interval(BigInteger min, BigInteger max) {
            this.min = min;
            this.max = max;
        }
        
        public boolean isBottom() {
            return min != null && max != null && min.compareTo(max) > 0;
        }
        
        public boolean contains(BigInteger val) {
            if (min != null && val.compareTo(min) < 0) return false;
            if (max != null && val.compareTo(max) > 0) return false;
            return true;
        }
        
        public boolean isContainedIn(Interval other) {
            if (isBottom()) return true;
            if (other.isBottom()) return false;
            
            if (other.min != null && min != null && min.compareTo(other.min) < 0) return false;
            if (other.max != null && max != null && max.compareTo(other.max) > 0) return false;
            
            return true;
        }
        
        public static Interval meet(Interval a, Interval b) {
            if (a == null) return b;
            if (b == null) return a;
            if (a.isBottom() || b.isBottom()) return BOTTOM;
            
            BigInteger min = a.min;
            BigInteger max = b.max;
            
            if (b.min != null) {
                if (min == null || b.min.compareTo(min) > 0) min = b.min;
            }
            if (a.max != null) {
                if (max == null || a.max.compareTo(max) < 0) max = a.max;
            }
            
            if (min != null && max != null && min.compareTo(max) > 0) return BOTTOM;
            
            return new Interval(min, max);
        }
        
        public static Interval join(Interval a, Interval b) {
            if (a == null) return b;
            if (b == null) return a;
            if (a.isBottom()) return b;
            if (b.isBottom()) return a;
            
            BigInteger min = a.min;
            BigInteger max = a.max;
            
            if (b.min != null) {
                if (min == null || b.min.compareTo(min) < 0) min = b.min;
            }
            if (b.max != null) {
                if (max == null || b.max.compareTo(max) > 0) max = b.max;
            }
            
            return new Interval(min, max);
        }
    }
    
    private static class IntervalState {
        private static final IntervalState BOTTOM = new IntervalState(null);
        
        private final Map<String, Interval> intervals;
        
        private IntervalState() {
            this.intervals = new HashMap<>();
        }
        
        private IntervalState(Map<String, Interval> intervals) {
            this.intervals = intervals != null ? new HashMap<>(intervals) : new HashMap<>();
        }
        
        public boolean isBottom() {
            return intervals == null;
        }
    }
}
