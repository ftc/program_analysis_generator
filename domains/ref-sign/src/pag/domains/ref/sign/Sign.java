package pag.domains.ref.sign;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;
import pag.api.BinOp;

/** The sign of an integer. A local's abstract value is the set of signs it may have. */
public enum Sign {
    NEG, ZERO, POS;

    /** Every sign: a local nothing is known about. */
    public static Set<Sign> any() { return EnumSet.allOf(Sign.class); }

    public static Sign of(BigInteger n) {
        return switch (n.signum()) {
            case -1 -> NEG;
            case 0 -> ZERO;
            default -> POS;
        };
    }

    /** The signs a OP b may have, where OP is +, - or *. */
    public static Set<Sign> arithmetic(BinOp op, Sign a, Sign b) {
        return switch (op) {
            case Add -> add(a, b);
            case Sub -> add(a, b.negated());
            case Mult -> a == ZERO || b == ZERO ? EnumSet.of(ZERO) : EnumSet.of(a == b ? POS : NEG);
            default -> any();
        };
    }

    private static Set<Sign> add(Sign a, Sign b) {
        if (a == ZERO) return EnumSet.of(b);
        if (b == ZERO || a == b) return EnumSet.of(a);
        return any(); // a negative plus a positive can be anything
    }

    private Sign negated() {
        return switch (this) {
            case NEG -> POS;
            case ZERO -> ZERO;
            case POS -> NEG;
        };
    }

    /** Can a OP b hold, for some values with signs a and b? OP is a comparison. */
    public static boolean canCompare(BinOp op, Sign a, Sign b) {
        if (a != b) {
            boolean less = a.ordinal() < b.ordinal(); // NEG < ZERO < POS
            return switch (op) {
                case Lt, Le -> less;
                case Gt, Ge -> !less;
                case Ne -> true;
                default -> false; // Eq: different signs are never equal
            };
        }
        if (a == ZERO) return op == BinOp.Eq || op == BinOp.Le || op == BinOp.Ge; // 0 against 0
        return true; // two negatives, or two positives: any comparison can hold
    }

    @Override
    public String toString() {
        return switch (this) {
            case NEG -> "−";
            case ZERO -> "0";
            case POS -> "+";
        };
    }
}
