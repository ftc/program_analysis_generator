package pag.domains.gen;

import java.util.List;
import java.util.Objects;

/**
 * A method's fully qualified identity.
 */
public record MethodId(String declaringClass, String name, List<String> paramTypes, String returnType) {
    public String qualifiedName() {
        return declaringClass + "." + name;
    }

    @Override
    public String toString() {
        return qualifiedName() + "(" + String.join(",", paramTypes) + ")";
    }
}
