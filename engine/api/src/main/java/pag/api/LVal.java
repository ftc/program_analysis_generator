package pag.api;

import java.util.Objects;

/** An assignable value in the domain vocabulary (implementation_strategy.md §5.4). */
public sealed interface LVal extends RVal {

    record Local(String name, String type) implements LVal {
        public Local {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /** Only with lift = false: a read of BigInteger.ZERO, ONE, TWO or TEN (§5.7). */
    record StaticField(String declaringClass, String name) implements LVal {
        public StaticField {
            Objects.requireNonNull(declaringClass, "declaringClass");
            Objects.requireNonNull(name, "name");
        }
    }
}
