package pag.api;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A value in the domain vocabulary (implementation_strategy.md §5.4): exactly
 * what lowering can emit, with the Scala IR's names and shapes. Every value is
 * a mathematical integer.
 */
public sealed interface RVal permits RVal.IntConst, RVal.Binop, LVal {

    record IntConst(BigInteger v) implements RVal {
        public IntConst {
            Objects.requireNonNull(v, "v");
        }
    }

    record Binop(RVal l, BinOp op, RVal r) implements RVal {
        public Binop {
            Objects.requireNonNull(l, "l");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(r, "r");
        }
    }
}
