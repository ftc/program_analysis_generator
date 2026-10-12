package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class SignIntervalDomain implements Domain<SignIntervalDomain.State> {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    public static final class State {
        private static final State BOTTOM = new State(Collections.emptyMap(), true);
        private static final State TOP = new State(Collections.emptyMap(), false);

        private final Map<String, AbstractValue> values;
        private final boolean isBottom;

        private State(Map<String, AbstractValue> values, boolean isBottom) {
            this.values = Collections.unmodifiableMap(values);
            this.isBottom = isBottom;
        }

        public AbstractValue get(String name) {
            if (isBottom) return AbstractValue.BOTTOM;
            return values.getOrDefault(name, AbstractValue.TOP);
        }

        public State put(String name, AbstractValue val) {
            if (isBottom) return BOTTOM;
            if (val.isBottom()) return BOTTOM;
            Map<String, AbstractValue> newValues = new HashMap<>(values);
            newValues.put(name, val);
            return new State(newValues, false);
        }

        public State remove(String name) {
            if (isBottom) return BOTTOM;
            if (!values.containsKey(name)) return this;
            Map<String, AbstractValue> newValues = new HashMap<>(values);
            newValues.remove(name);
            return new State(newValues, false);
        }

        public boolean isBottom() {
            return isBottom;
        }

        public boolean entails(State other) {
            if (isBottom) return true;
            if (other.isBottom) return false;
            for (Map.Entry<String, AbstractValue> entry : values.entrySet()) {
                if (!entry.getValue().entails(other.get(entry.getKey()))) {
                    return false;
                }
            }
            for (String key : other.values.keySet()) {
                if (!values.containsKey(key)) {
                    if (!AbstractValue.TOP.entails(other.get(key))) {
                        return false;
                    }
                }
            }
            return true;
        }

        public State join(State other) {
            if (isBottom) return other;
            if (other.isBottom) return this;
            Map<String, AbstractValue> newValues = new HashMap<>(values);
            for (Map.Entry<String, AbstractValue> entry : other.values.entrySet()) {
                String key = entry.getKey();
                AbstractValue thisVal = values.getOrDefault(key, AbstractValue.TOP);
                newValues.put(key, thisVal.join(entry.getValue()));
            }
            return new State(newValues, false);
        }

        public State widen(State other) {
            return join(other);
        }
    }

    public static final class AbstractValue {
        public static final AbstractValue TOP = new AbstractValue(Sign.TOP, null, null, false);
        public static final AbstractValue BOTTOM = new AbstractValue(Sign.TOP, BigInteger.ONE, BigInteger.ZERO, true);

        public enum Sign {
            TOP, ZERO, POS, NEG, NONZERO, NONPOS, NONNEG;

            public boolean entails(Sign other) {
                if (this == Sign.TOP) return other == Sign.TOP;
                if (other == Sign.TOP) return true;
                if (this == Sign.ZERO) return other == Sign.ZERO || other == Sign.NONPOS || other == Sign.NONNEG;
                if (this == Sign.POS) return other == Sign.POS || other == Sign.NONZERO || other == Sign.NONNEG;
                if (this == Sign.NEG) return other == Sign.NEG || other == Sign.NONZERO || other == Sign.NONPOS;
                if (this == Sign.NONZERO) return other == Sign.NONZERO;
                if (this == Sign.NONPOS) return other == Sign.NONPOS;
                if (this == Sign.NONNEG) return other == Sign.NONNEG;
                return false;
            }

            public Sign join(Sign other) {
                if (this == Sign.TOP || other == Sign.TOP) return Sign.TOP;
                if (this == Sign.ZERO && other == Sign.ZERO) return Sign.ZERO;
                if (this == Sign.POS && other == Sign.POS) return Sign.POS;
                if (this == Sign.NEG && other == Sign.NEG) return Sign.NEG;
                if ((this == Sign.POS && other == Sign.NEG) || (this == Sign.NEG && other == Sign.POS)) return Sign.NONZERO;
                if ((this == Sign.POS && other == Sign.ZERO) || (this == Sign.ZERO && other == Sign.POS)) return Sign.NONNEG;
                if ((this == Sign.NEG && other == Sign.ZERO) || (this == Sign.ZERO && other == Sign.NEG)) return Sign.NONPOS;
                if ((this == Sign.NONZERO && other == Sign.ZERO) || (this == Sign.ZERO && other == Sign.NONZERO)) return Sign.TOP;
                if ((this == Sign.NONPOS && other == Sign.NONNEG) || (this == Sign.NONNEG && other == Sign.NONPOS)) return Sign.TOP;
                if (this == Sign.NONPOS && other == Sign.NONPOS) return Sign.NONPOS;
                if (this == Sign.NONNEG && other == Sign.NONNEG) return Sign.NONNEG;
                return Sign.TOP;
            }
        }

        private final Sign sign;
        private final BigInteger min;
        private final BigInteger max;
        private final boolean isBottom;

        private AbstractValue(Sign sign, BigInteger min, BigInteger max, boolean isBottom) {
            this.sign = sign;
            this.min = min;
            this.max = max;
            this.isBottom = isBottom;
        }

        public boolean isBottom() {
            return isBottom;
        }

        public boolean entails(AbstractValue other) {
            if (isBottom) return true;
            if (other.isBottom) return false;
            if (!sign.entails(other.sign)) return false;
            if (min != null && other.min != null && min.compareTo(other.min) < 0) return false;
            if (max != null && other.max != null && max.compareTo(other.max) > 0) return false;
            return true;
        }

        public AbstractValue join(AbstractValue other) {
            if (isBottom) return other;
            if (other.isBottom) return this;
            Sign joinedSign = sign.join(other.sign);
            BigInteger joinedMin = (min == null || other.min == null) ? null : min.min(other.min);
            BigInteger joinedMax = (max == null || other.max == null) ? null : max.max(other.max);
            return new AbstractValue(joinedSign, joinedMin, joinedMax, false);
        }

        public AbstractValue withSign(Sign newSign) {
            return new AbstractValue(newSign, min, max, false);
        }
    }

    private static AbstractValue.Sign fromValue(BigInteger v) {
        int cmp = v.compareTo(ZERO);
        if (cmp == 0) return AbstractValue.Sign.ZERO;
        if (cmp > 0) return AbstractValue.Sign.POS;
        return AbstractValue.Sign.NEG;
    }

    private static AbstractValue.Sign addSign(AbstractValue.Sign l, AbstractValue.Sign r) {
        if (l == AbstractValue.Sign.ZERO) return r;
        if (r == AbstractValue.Sign.ZERO) return l;
        if (l == AbstractValue.Sign.POS && r == AbstractValue.Sign.POS) return AbstractValue.Sign.POS;
        if (l == AbstractValue.Sign.NEG && r == AbstractValue.Sign.NEG) return AbstractValue.Sign.NEG;
        return AbstractValue.Sign.TOP;
    }

    private static AbstractValue.Sign subSign(AbstractValue.Sign l, AbstractValue.Sign r) {
        if (r == AbstractValue.Sign.ZERO) return l;
        if (l == AbstractValue.Sign.ZERO) return negateSign(r);
        if (l == AbstractValue.Sign.POS && r == AbstractValue.Sign.POS) return AbstractValue.Sign.TOP;
        if (l == AbstractValue.Sign.NEG && r == AbstractValue.Sign.NEG) return AbstractValue.Sign.TOP;
        if (l == AbstractValue.Sign.POS && r == AbstractValue.Sign.NEG) return AbstractValue.Sign.POS;
        if (l == AbstractValue.Sign.NEG && r == AbstractValue.Sign.POS) return AbstractValue.Sign.NEG;
        return AbstractValue.Sign.TOP;
    }

    private static AbstractValue.Sign multSign(AbstractValue.Sign l, AbstractValue.Sign r) {
        if (l == AbstractValue.Sign.ZERO || r == AbstractValue.Sign.ZERO) return AbstractValue.Sign.ZERO;
        if (l == AbstractValue.Sign.POS) return r;
        if (l == AbstractValue.Sign.NEG) return negateSign(r);
        if (r == AbstractValue.Sign.POS) return l;
        if (r == AbstractValue.Sign.NEG) return negateSign(l);
        return AbstractValue.Sign.TOP;
    }

    private static AbstractValue.Sign negateSign(AbstractValue.Sign s) {
        if (s == AbstractValue.Sign.ZERO) return AbstractValue.Sign.ZERO;
        if (s == AbstractValue.Sign.POS) return AbstractValue.Sign.NEG;
        if (s == AbstractValue.Sign.NEG) return AbstractValue.Sign.POS;
        if (s == AbstractValue.Sign.NONZERO) return AbstractValue.Sign.NONZERO;
        if (s == AbstractValue.Sign.NONPOS) return AbstractValue.Sign.NONNEG;
        if (s == AbstractValue.Sign.NONNEG) return AbstractValue.Sign.NONPOS;
        return AbstractValue.Sign.TOP;
    }

    public SignIntervalDomain() {
    }

    @Override public String name() { return "SignIntervalDomain"; }
    @Override public State top() { return State.TOP; }
    @Override public State bottom() { return State.BOTTOM; }
    @Override public boolean isBottom(State s) { return s.isBottom(); }
    @Override public boolean entails(State a, State b) { return a.entails(b); }
    @Override public State join(State a, State b) { return a.join(b); }
    @Override public State widen(State a, State b) { return a.widen(b); }

    @Override
    public State transfer(Step step, State post) {
        if (post.isBottom()) return post;
        if (step instanceof Step.Assign assign) {
            String target = assign.target().name();
            State pre = post.remove(target);
            RVal source = assign.source();
            AbstractValue val = eval(source, post);
            if (val.isBottom()) return State.BOTTOM;
            if (source instanceof LVal.Local local) {
                pre = pre.put(local.name(), val);
            } else if (source instanceof RVal.Binop binop) {
                pre = constrainBinopInverse(binop, val, pre);
            }
            return pre;
        }
        if (step instanceof Step.Assume assume) {
            return constrainAssume(assume.cond(), post);
        }
        if (step instanceof Step.Call call) {
            if (call.target().isPresent()) {
                String target = call.target().get().name();
                return post.remove(target);
            }
            return post;
        }
        throw new AssertionError("unreachable");
    }

    private AbstractValue eval(RVal rval, State state) {
        if (rval instanceof RVal.IntConst ic) {
            return new AbstractValue(fromValue(ic.v()), ic.v(), ic.v(), false);
        }
        if (rval instanceof LVal.Local local) {
            return state.get(local.name());
        }
        if (rval instanceof RVal.Binop binop) {
            AbstractValue l = eval(binop.l(), state);
            AbstractValue r = eval(binop.r(), state);
            if (l.isBottom() || r.isBottom()) return AbstractValue.BOTTOM;
            return evalBinop(binop.op(), l, r);
        }
        return AbstractValue.TOP;
    }

    private AbstractValue evalBinop(BinOp op, AbstractValue l, AbstractValue r) {
        if (op == BinOp.Add) {
            BigInteger min = (l.min == null || r.min == null) ? null : l.min.add(r.min);
            BigInteger max = (l.max == null || r.max == null) ? null : l.max.add(r.max);
            AbstractValue.Sign s = addSign(l.sign, r.sign);
            return new AbstractValue(s, min, max, false);
        }
        if (op == BinOp.Sub) {
            BigInteger min = (l.min == null || r.max == null) ? null : l.min.subtract(r.max);
            BigInteger max = (l.max == null || r.min == null) ? null : l.max.subtract(r.min);
            AbstractValue.Sign s = subSign(l.sign, r.sign);
            return new AbstractValue(s, min, max, false);
        }
        if (op == BinOp.Mult) {
            AbstractValue.Sign s = multSign(l.sign, r.sign);
            return new AbstractValue(s, null, null, false);
        }
        return AbstractValue.TOP;
    }

    private State constrainBinopInverse(RVal.Binop binop, AbstractValue target, State state) {
        BinOp op = binop.op();
        if (op == BinOp.Add) {
            AbstractValue lVal = eval(binop.l(), state);
            AbstractValue rVal = eval(binop.r(), state);
            AbstractValue lConstr = new AbstractValue(target.sign, 
                (target.min == null || rVal.max == null) ? null : target.min.subtract(rVal.max),
                (target.max == null || rVal.min == null) ? null : target.max.subtract(rVal.min),
                false);
            AbstractValue rConstr = new AbstractValue(target.sign,
                (target.min == null || lVal.max == null) ? null : target.min.subtract(lVal.max),
                (target.max == null || lVal.min == null) ? null : target.max.subtract(lVal.min),
                false);
            State pre = state;
            if (binop.l() instanceof LVal.Local l) {
                pre = pre.put(l.name(), lConstr);
            }
            if (binop.r() instanceof LVal.Local r) {
                pre = pre.put(r.name(), rConstr);
            }
            return pre;
        }
        if (op == BinOp.Sub) {
            AbstractValue lVal = eval(binop.l(), state);
            AbstractValue rVal = eval(binop.r(), state);
            AbstractValue lConstr = new AbstractValue(target.sign,
                (target.min == null || rVal.min == null) ? null : target.min.add(rVal.min),
                (target.max == null || rVal.max == null) ? null : target.max.add(rVal.max),
                false);
            AbstractValue rConstr = new AbstractValue(target.sign,
                (lVal.min == null || target.max == null) ? null : lVal.min.subtract(target.max),
                (lVal.max == null || target.min == null) ? null : lVal.max.subtract(target.min),
                false);
            State pre = state;
            if (binop.l() instanceof LVal.Local l) {
                pre = pre.put(l.name(), lConstr);
            }
            if (binop.r() instanceof LVal.Local r) {
                pre = pre.put(r.name(), rConstr);
            }
            return pre;
        }
        if (op == BinOp.Mult) {
            AbstractValue lVal = eval(binop.l(), state);
            AbstractValue rVal = eval(binop.r(), state);
            AbstractValue.Sign lSign = target.sign;
            AbstractValue.Sign rSign = target.sign;
            if (target.sign != AbstractValue.Sign.ZERO) {
                lSign = AbstractValue.Sign.NONZERO;
                rSign = AbstractValue.Sign.NONZERO;
            }
            State pre = state;
            if (binop.l() instanceof LVal.Local l) {
                pre = pre.put(l.name(), lVal.withSign(lSign));
            }
            if (binop.r() instanceof LVal.Local r) {
                pre = pre.put(r.name(), rVal.withSign(rSign));
            }
            return pre;
        }
        return state;
    }

    private State constrainAssume(RVal cond, State state) {
        if (cond instanceof RVal.Binop binop) {
            if (binop.op() == BinOp.Eq) {
                AbstractValue l = eval(binop.l(), state);
                AbstractValue r = eval(binop.r(), state);
                if (l.isBottom() || r.isBottom()) return State.BOTTOM;
                BigInteger min = (l.min == null || r.min == null) ? null : l.min.max(r.min);
                BigInteger max = (l.max == null || r.max == null) ? null : l.max.min(r.max);
                if (min != null && max != null && min.compareTo(max) > 0) return State.BOTTOM;
                AbstractValue.Sign s = l.sign.join(r.sign);
                AbstractValue common = new AbstractValue(s, min, max, false);
                State pre = state;
                if (binop.l() instanceof LVal.Local lLocal) {
                    pre = pre.put(lLocal.name(), common);
                }
                if (binop.r() instanceof LVal.Local rLocal) {
                    pre = pre.put(rLocal.name(), common);
                }
                return pre;
            }
            if (binop.op() == BinOp.Ne) {
                return state;
            }
            if (binop.op() == BinOp.Lt) {
                AbstractValue l = eval(binop.l(), state);
                AbstractValue r = eval(binop.r(), state);
                if (l.isBottom() || r.isBottom()) return State.BOTTOM;
                BigInteger rMin = r.min;
                BigInteger xMax = (rMin == null) ? null : rMin.subtract(ONE);
                BigInteger xMin = l.min;
                if (xMax != null && xMin != null && xMin.compareTo(xMax) > 0) return State.BOTTOM;
                AbstractValue lNew = new AbstractValue(l.sign, xMin, xMax, false);
                State pre = state;
                if (binop.l() instanceof LVal.Local lLocal) {
                    pre = pre.put(lLocal.name(), lNew);
                }
                return pre;
            }
            if (binop.op() == BinOp.Le) {
                AbstractValue l = eval(binop.l(), state);
                AbstractValue r = eval(binop.r(), state);
                if (l.isBottom() || r.isBottom()) return State.BOTTOM;
                BigInteger rMax = r.max;
                BigInteger xMax = rMax;
                BigInteger xMin = l.min;
                if (xMax != null && xMin != null && xMin.compareTo(xMax) > 0) return State.BOTTOM;
                AbstractValue lNew = new AbstractValue(l.sign, xMin, xMax, false);
                State pre = state;
                if (binop.l() instanceof LVal.Local lLocal) {
                    pre = pre.put(lLocal.name(), lNew);
                }
                return pre;
            }
            if (binop.op() == BinOp.Gt) {
                AbstractValue l = eval(binop.l(), state);
                AbstractValue r = eval(binop.r(), state);
                if (l.isBottom() || r.isBottom()) return State.BOTTOM;
                BigInteger rMax = r.max;
                BigInteger xMin = (rMax == null) ? null : rMax.add(ONE);
                BigInteger xMax = l.max;
                if (xMax != null && xMin != null && xMin.compareTo(xMax) > 0) return State.BOTTOM;
                AbstractValue lNew = new AbstractValue(l.sign, xMin, xMax, false);
                State pre = state;
                if (binop.l() instanceof LVal.Local lLocal) {
                    pre = pre.put(lLocal.name(), lNew);
                }
                return pre;
            }
            if (binop.op() == BinOp.Ge) {
                AbstractValue l = eval(binop.l(), state);
                AbstractValue r = eval(binop.r(), state);
                if (l.isBottom() || r.isBottom()) return State.BOTTOM;
                BigInteger rMax = r.max;
                BigInteger xMin = rMax;
                BigInteger xMax = l.max;
                if (xMax != null && xMin != null && xMin.compareTo(xMax) > 0) return State.BOTTOM;
                AbstractValue lNew = new AbstractValue(l.sign, xMin, xMax, false);
                State pre = state;
                if (binop.l() instanceof LVal.Local lLocal) {
                    pre = pre.put(lLocal.name(), lNew);
                }
                return pre;
            }
        }
        return state;
    }
}
