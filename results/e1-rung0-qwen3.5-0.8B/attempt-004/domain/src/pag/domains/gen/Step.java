package pag.domains.gen;

import pag.api.LVal;
import pag.api.RVal;
import pag.api.Step;

/**
 * What {@code Domain.transfer} receives: one CFG edge's command.
 */
public sealed interface Step {
    record Assign(LVal.Local target, RVal source) implements Step {
        public Assign {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
        }
    }

    record Assume(RVal cond) implements Step {
        public Assume {
            Objects.requireNonNull(cond, "cond");
        }
    }

    record Call(Optional<LVal.Local> target, MethodId callee, List<RVal> args) implements Step {
        public Call {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(callee, "callee");
            args = List.copyOf(args);
        }
    }
}
