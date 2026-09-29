package pag.api;

import java.util.List;
import java.util.Objects;

/**
 * A method's fully qualified identity (implementation_strategy.md §5.1, §5.4).
 * Independent of any front end's naming format; printed as
 * {@code Probe.main(java.lang.String[])}.
 */
public record MethodId(String declaringClass, String name, List<String> paramTypes, String returnType) {

    public MethodId {
        Objects.requireNonNull(declaringClass, "declaringClass");
        Objects.requireNonNull(name, "name");
        paramTypes = List.copyOf(paramTypes);
        Objects.requireNonNull(returnType, "returnType");
    }

    /** {@code java.math.BigInteger.add}: class and name, ignoring overloads (§5.2 callees). */
    public String qualifiedName() {
        return declaringClass + "." + name;
    }

    @Override
    public String toString() {
        return qualifiedName() + "(" + String.join(",", paramTypes) + ")";
    }
}
