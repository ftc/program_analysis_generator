package pag.domains.gen;

import java.util.Objects;

/**
 * An assignable value in the domain vocabulary.
 */
public sealed interface LVal extends RVal {
    record Local(String name, String type) implements LVal {
        public Local {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    record StaticField(String declaringClass, String name) implements LVal {
        public StaticField {
            Objects.requireNonNull(declaringClass, "declaringClass");
            Objects.requireNonNull(name, "name");
        }
    }
}
