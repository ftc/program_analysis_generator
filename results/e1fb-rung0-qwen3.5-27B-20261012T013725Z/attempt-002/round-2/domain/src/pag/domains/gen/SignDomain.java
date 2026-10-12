package pag.domains.gen;

import pag.api.*;
import java.math.BigInteger;
import java.util.*;

/**
 * A sign-based abstract domain for backward reachability analysis.
 * Tracks whether each local variable is: unknown, zero, positive, negative,
 * non-negative, or non-positive.
 */
public class SignDomain implements Domain<Map<String, SignDomain.Sign>> {

    /** Abstract sign values for integer variables. */
    public enum Sign {
        UNKNOWN,        // Any integer value
        ZERO,           // Exactly 0
        POSITIVE,       // > 0
        NEGATIVE,       // < 0
        NONNEGATIVE,    // >= 0
        NONPOSITIVE;    // <= 0

        /**
         * Whether this sign definitely excludes zero.
         */
        public boolean excludesZero() {
            return this == POSITIVE || this == NEGATIVE;
        }

        /**
         * Whether this sign definitely excludes positive values.
         */
        public boolean excludesPositive() {
            return this == NEGATIVE || this == ZERO;
        }

        /**
         * Whether this sign definitely excludes negative values.
         */
        public boolean excludesNegative() {
            return this == POSITIVE || this == ZERO;
        }
    }

    @Override
    public String name() {
        return "SignDomain";
    }

    @Override
    public Map<String, Sign> top() {
        return new HashMap<>();
    }

    @Override
    public Map<String, Sign> bottom() {
        // Bottom state: represented by a special marker - empty map with a flag
        return Collections.singletonMap("__BOTTOM__", Sign.UNKNOWN);
    }

    @Override
    public boolean isBottom(Map<String, Sign> s) {
        return s != null && s.containsKey("__BOTTOM__");
    }

    @Override
    public boolean entails(Map<String, Sign> a, Map<String, Sign> b) {
        if (isBottom(a)) return true;
        if (isBottom(b)) return false;
        
        for (Map.Entry<String, Sign> entry : a.entrySet()) {
            String var = entry.getKey();
            if (var.equals("__BOTTOM__")) continue;
            Sign signA = entry.getValue();
            Sign signB = b.getOrDefault(var, Sign.UNKNOWN);
            
            if (!signEntails(signA, signB)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if sign a entails sign b.
     */
    private boolean signEntails(Sign a, Sign b) {
        if (b == Sign.UNKNOWN) return true;
        if (a == Sign.UNKNOWN) return false;
        if (a == b) return true;
        
        // ZERO entails NONNEGATIVE and NONPOSITIVE
        if (a == Sign.ZERO && (b == Sign.NONNEGATIVE || b == Sign.NONPOSITIVE)) {
            return true;
        }
        
        // POSITIVE entails NONNEGATIVE
        if (a == Sign.POSITIVE && b == Sign.NONNEGATIVE) {
            return true;
        }
        
        // NEGATIVE entails NONPOSITIVE
        if (a == Sign.NEGATIVE && b == Sign.NONPOSITIVE) {
            return true;
        }
        
        return false;
    }

    @Override
    public Map<String, Sign> join(Map<String, Sign> a, Map<String, Sign> b) {
        if (isBottom(a)) return b;
        if (isBottom(b)) return a;
        
        Map<String, Sign> result = new HashMap<>();
        
        Set<String> allVars = new HashSet<>();
        allVars.addAll(a.keySet());
        allVars.addAll(b.keySet());
        
        for (String var : allVars) {
            if (var.equals("__BOTTOM__")) continue;
            Sign signA = a.getOrDefault(var, Sign.UNKNOWN);
            Sign signB = b.getOrDefault(var, Sign.UNKNOWN);
            result.put(var, joinSigns(signA, signB));
        }
        
        return result;
    }

    /**
     * Joins two signs - returns the least upper bound.
     */
    private Sign joinSigns(Sign a, Sign b) {
        if (a == Sign.UNKNOWN || b == Sign.UNKNOWN) return Sign.UNKNOWN;
        if (a == b) return a;
        
        // ZERO + anything non-zero = UNKNOWN
        if (a == Sign.ZERO || b == Sign.ZERO) {
            if (a == Sign.ZERO && b == Sign.ZERO) return Sign.ZERO;
            if (b == Sign.ZERO) return joinSigns(a, Sign.ZERO);
            return Sign.UNKNOWN;
        }
        
        // POSITIVE + NONNEGATIVE = NONNEGATIVE
        if ((a == Sign.POSITIVE && b == Sign.NONNEGATIVE) ||
            (a == Sign.NONNEGATIVE && b == Sign.POSITIVE)) {
            return Sign.NONNEGATIVE;
        }
        
        // NEGATIVE + NONPOSITIVE = NONPOSITIVE
        if ((a == Sign.NEGATIVE && b == Sign.NONPOSITIVE) ||
            (a == Sign.NONPOSITIVE && b == Sign.NEGATIVE)) {
            return Sign.NONPOSITIVE;
        }
        
        // POSITIVE + NEGATIVE = UNKNOWN
        // NONNEGATIVE + NONPOSITIVE = UNKNOWN (could be anything)
        // etc.
        return Sign.UNKNOWN;
    }

    @Override
    public Map<String, Sign> widen(Map<String, Sign> a, Map<String, Sign> b) {
        // For simplicity, widen = join for this domain
        return join(a, b);
    }

    @Override
    public Map<String, Sign> transfer(Step step, Map<String, Sign> post) {
        if (isBottom(post)) {
            return post;
        }

        if (step instanceof Step.Assign assign) {
            return transferAssign(assign, post);
        } else if (step instanceof Step.Assume assume) {
            return transferAssume(assume, post);
        } else if (step instanceof Step.Call call) {
            return transferCall(call, post);
        }
        
        return post;
    }

    /**
     * Backward transfer for assignment: x = e
     * After: x has some sign constraint
     * Before: x is unconstrained, but e must satisfy x's constraint
     */
    private Map<String, Sign> transferAssign(Step.Assign assign, Map<String, Sign> post) {
        Map<String, Sign> result = new HashMap<>(post);
        String var = assign.target().name();
        
        // Remove constraint on x (it's unconstrained before assignment)
        result.remove(var);
        
        // The expression e must evaluate to a value satisfying post's constraint on x
        Sign xConstraint = post.getOrDefault(var, Sign.UNKNOWN);
        if (xConstraint != Sign.UNKNOWN) {
            result = constrainExpression(result, assign.source(), xConstraint);
        }
        
        return result;
    }

    /**
     * Backward transfer for assume: assume(c)
     * Restricts post to states where c holds.
     */
    private Map<String, Sign> transferAssume(Step.Assume assume, Map<String, Sign> post) {
        return applyConstraint(post, assume.cond());
    }

    /**
     * Backward transfer for call: x = randInt()
     * x becomes unconstrained before the call.
     */
    private Map<String, Sign> transferCall(Step.Call call, Map<String, Sign> post) {
        Map<String, Sign> result = new HashMap<>(post);
        
        if (call.target().isPresent()) {
            result.remove(call.target().get().name());
        }
        
        return result;
    }

    /**
     * Applies a constraint from an expression to the state.
     */
    private Map<String, Sign> applyConstraint(Map<String, Sign> state, RVal expr) {
        if (expr instanceof RVal.Binop binop) {
            return applyBinopConstraint(state, binop);
        }
        
        return state;
    }

    /**
     * Applies a binary operation constraint to the state.
     */
    private Map<String, Sign> applyBinopConstraint(Map<String, Sign> state, RVal.Binop binop) {
        BinOp op = binop.op();
        RVal left = binop.l();
        RVal right = binop.r();
        
        // Handle comparison operations
        if (op == BinOp.Eq) {
            // left == right: both must have same value
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.ZERO);
                } else if (val.signum() > 0) {
                    return constrainTo(state, left, Sign.POSITIVE);
                } else {
                    return constrainTo(state, left, Sign.NEGATIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.ZERO);
                } else if (val.signum() > 0) {
                    return constrainTo(state, right, Sign.POSITIVE);
                } else {
                    return constrainTo(state, right, Sign.NEGATIVE);
                }
            }
        } else if (op == BinOp.Lt) {
            // left < right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.NEGATIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.POSITIVE);
                }
            }
        } else if (op == BinOp.Le) {
            // left <= right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.NONPOSITIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.NONNEGATIVE);
                }
            }
        } else if (op == BinOp.Gt) {
            // left > right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.POSITIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.NEGATIVE);
                }
            }
        } else if (op == BinOp.Ge) {
            // left >= right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.NONNEGATIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.NONPOSITIVE);
                }
            }
        }
        
        return state;
    }

    /**
     * Constrains the state so that the expression evaluates to the given sign.
     */
    private Map<String, Sign> constrainExpression(Map<String, Sign> state, RVal expr, Sign requiredSign) {
        if (expr instanceof LVal.Local local) {
            Map<String, Sign> result = new HashMap<>(state);
            result.put(local.name(), requiredSign);
            return result;
        }
        
        if (expr instanceof RVal.IntConst) {
            // Constants don't impose constraints on variables
            return state;
        }
        
        if (expr instanceof RVal.Binop binop) {
            return constrainBinop(state, binop, requiredSign);
        }
        
        return state;
    }

    /**
     * Constrains a binary operation to satisfy the required sign.
     */
    private Map<String, Sign> constrainBinop(Map<String, Sign> state, RVal.Binop binop, Sign requiredSign) {
        // For simplicity, we handle common cases
        // This is where we lose precision, but remain sound
        
        BinOp op = binop.op();
        RVal left = binop.l();
        RVal right = binop.r();
        
        // Handle comparison operations
        if (op == BinOp.Eq) {
            // left == right: both must have same value
            // For simplicity, if right is a constant, constrain left
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.ZERO);
                } else if (val.signum() > 0) {
                    return constrainTo(state, left, Sign.POSITIVE);
                } else {
                    return constrainTo(state, left, Sign.NEGATIVE);
                }
            }
        } else if (op == BinOp.Ne) {
            // left != right: they must have different values
            // Harder to constrain precisely, skip for soundness
        } else if (op == BinOp.Lt) {
            // left < right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.NEGATIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.POSITIVE);
                }
            }
        } else if (op == BinOp.Le) {
            // left <= right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.NONPOSITIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.NONNEGATIVE);
                }
            }
        } else if (op == BinOp.Gt) {
            // left > right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.POSITIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.NEGATIVE);
                }
            }
        } else if (op == BinOp.Ge) {
            // left >= right
            if (right instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, left, Sign.NONNEGATIVE);
                }
            } else if (left instanceof RVal.IntConst intConst) {
                BigInteger val = intConst.v();
                if (val.equals(BigInteger.ZERO)) {
                    return constrainTo(state, right, Sign.NONPOSITIVE);
                }
            }
        }
        
        // For arithmetic operations with requiredSign constraint
        // This is complex, so we return state unchanged (sound but imprecise)
        return state;
    }

    /**
     * Constrains a variable to have the given sign.
     */
    private Map<String, Sign> constrainTo(Map<String, Sign> state, RVal expr, Sign sign) {
        if (expr instanceof LVal.Local local) {
            Map<String, Sign> result = new HashMap<>(state);
            result.put(local.name(), sign);
            return result;
        }
        return state;
    }

    /**
     * Evaluates the sign of an expression given the current state.
     */
    Sign evaluateSign(Map<String, Sign> state, RVal expr) {
        if (expr instanceof LVal.Local local) {
            return state.getOrDefault(local.name(), Sign.UNKNOWN);
        }
        
        if (expr instanceof RVal.IntConst intConst) {
            BigInteger val = intConst.v();
            if (val.equals(BigInteger.ZERO)) return Sign.ZERO;
            return val.signum() > 0 ? Sign.POSITIVE : Sign.NEGATIVE;
        }
        
        if (expr instanceof RVal.Binop binop) {
            return evaluateBinopSign(state, binop);
        }
        
        return Sign.UNKNOWN;
    }

    /**
     * Evaluates the sign of a binary operation.
     */
    Sign evaluateBinopSign(Map<String, Sign> state, RVal.Binop binop) {
        BinOp op = binop.op();
        Sign leftSign = evaluateSign(state, binop.l());
        Sign rightSign = evaluateSign(state, binop.r());
        
        return switch (op) {
            case Add -> addSigns(leftSign, rightSign);
            case Sub -> subSigns(leftSign, rightSign);
            case Mult -> multSigns(leftSign, rightSign);
            case Eq, Ne, Lt, Le, Gt, Ge -> {
                // Comparisons return boolean, treated as unknown for sign purposes
                yield Sign.UNKNOWN;
            }
        };
    }

    /**
     * Adds two signs.
     */
    Sign addSigns(Sign a, Sign b) {
        if (a == Sign.UNKNOWN || b == Sign.UNKNOWN) return Sign.UNKNOWN;
        
        // ZERO + x = x
        if (a == Sign.ZERO) return b;
        if (b == Sign.ZERO) return a;
        
        // POSITIVE + POSITIVE = POSITIVE
        // NEGATIVE + NEGATIVE = NEGATIVE
        if (a == Sign.POSITIVE && b == Sign.POSITIVE) return Sign.POSITIVE;
        if (a == Sign.NEGATIVE && b == Sign.NEGATIVE) return Sign.NEGATIVE;
        
        // NONNEGATIVE + NONNEGATIVE = NONNEGATIVE
        // NONPOSITIVE + NONPOSITIVE = NONPOSITIVE
        if (a == Sign.NONNEGATIVE && b == Sign.NONNEGATIVE) return Sign.NONNEGATIVE;
        if (a == Sign.NONPOSITIVE && b == Sign.NONPOSITIVE) return Sign.NONPOSITIVE;
        
        // Everything else is UNKNOWN
        return Sign.UNKNOWN;
    }

    /**
     * Subtracts two signs.
     */
    Sign subSigns(Sign a, Sign b) {
        if (a == Sign.UNKNOWN || b == Sign.UNKNOWN) return Sign.UNKNOWN;
        
        // ZERO - x = -x
        if (a == Sign.ZERO) {
            return negateSign(b);
        }
        
        // x - ZERO = x
        if (b == Sign.ZERO) return a;
        
        // POSITIVE - NEGATIVE = POSITIVE
        if (a == Sign.POSITIVE && b == Sign.NEGATIVE) return Sign.POSITIVE;
        
        // NEGATIVE - POSITIVE = NEGATIVE
        if (a == Sign.NEGATIVE && b == Sign.POSITIVE) return Sign.NEGATIVE;
        
        // Everything else is UNKNOWN
        return Sign.UNKNOWN;
    }

    /**
     * Negates a sign.
     */
    Sign negateSign(Sign s) {
        return switch (s) {
            case ZERO -> Sign.ZERO;
            case POSITIVE -> Sign.NEGATIVE;
            case NEGATIVE -> Sign.POSITIVE;
            case NONNEGATIVE -> Sign.NONPOSITIVE;
            case NONPOSITIVE -> Sign.NONNEGATIVE;
            case UNKNOWN -> Sign.UNKNOWN;
        };
    }

    /**
     * Multiplies two signs.
     */
    Sign multSigns(Sign a, Sign b) {
        if (a == Sign.UNKNOWN || b == Sign.UNKNOWN) return Sign.UNKNOWN;
        
        // ZERO * x = ZERO
        if (a == Sign.ZERO || b == Sign.ZERO) return Sign.ZERO;
        
        // POSITIVE * POSITIVE = POSITIVE
        // NEGATIVE * NEGATIVE = POSITIVE
        if ((a == Sign.POSITIVE && b == Sign.POSITIVE) ||
            (a == Sign.NEGATIVE && b == Sign.NEGATIVE)) {
            return Sign.POSITIVE;
        }
        
        // POSITIVE * NEGATIVE = NEGATIVE
        // NEGATIVE * POSITIVE = NEGATIVE
        if ((a == Sign.POSITIVE && b == Sign.NEGATIVE) ||
            (a == Sign.NEGATIVE && b == Sign.POSITIVE)) {
            return Sign.NEGATIVE;
        }
        
        // NONNEGATIVE * NONNEGATIVE = NONNEGATIVE
        if (a == Sign.NONNEGATIVE && b == Sign.NONNEGATIVE) return Sign.NONNEGATIVE;
        
        // NONPOSITIVE * NONPOSITIVE = NONNEGATIVE
        if (a == Sign.NONPOSITIVE && b == Sign.NONPOSITIVE) return Sign.NONNEGATIVE;
        
        // Everything else is UNKNOWN
        return Sign.UNKNOWN;
    }
}
