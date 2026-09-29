package pag.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code Domain.transfer} receives: one CFG edge's command
 * (implementation_strategy.md §5.3, §5.4). The engine handles control flow
 * between locations itself, so a domain sees only these three forms.
 */
public sealed interface Step {

    record Assign(LVal.Local target, RVal source) implements Step {
        public Assign {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
        }
    }

    /** Blocks unless cond holds. */
    record Assume(RVal cond) implements Step {
        public Assume {
            Objects.requireNonNull(cond, "cond");
        }
    }

    /**
     * A call with no dispatch kind; a receiver, if any, is the first argument.
     * In v1 the only callee is pag.probe.Rand.randInt(): any integer (§5.6).
     */
    record Call(Optional<LVal.Local> target, MethodId callee, List<RVal> args) implements Step {
        public Call {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(callee, "callee");
            args = List.copyOf(args);
        }
    }
}
