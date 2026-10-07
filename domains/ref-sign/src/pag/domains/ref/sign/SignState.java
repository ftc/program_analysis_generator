package pag.domains.ref.sign;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An abstract state: Bottom (no program state), or for each constrained local
 * the set of signs it may have. A local absent from the map may have any sign,
 * so an empty Env is top.
 */
public sealed interface SignState {

    record Bottom() implements SignState {
        @Override public String toString() { return "⊥"; }
    }

    record Env(Map<String, Set<Sign>> at) implements SignState {
        /** Unconstrained locals are dropped, so equal meanings are equal values. */
        public Env {
            Map<String, Set<Sign>> kept = new HashMap<>();
            at.forEach((x, signs) -> {
                if (signs.isEmpty()) throw new IllegalArgumentException(x + " has no possible sign: use Bottom");
                if (!signs.equals(Sign.any())) kept.put(x, Set.copyOf(signs));
            });
            at = Map.copyOf(kept);
        }

        public Set<Sign> get(String local) { return at.getOrDefault(local, Sign.any()); }

        public Env without(String local) {
            Map<String, Set<Sign>> m = new HashMap<>(at);
            m.remove(local);
            return new Env(m);
        }

        /** This state with local's signs narrowed to those in allowed; Bottom if none remain. */
        public SignState meet(String local, Set<Sign> allowed) {
            Set<Sign> met = EnumSet.noneOf(Sign.class);
            met.addAll(get(local));
            met.retainAll(allowed);
            if (met.isEmpty()) return BOTTOM;
            Map<String, Set<Sign>> m = new HashMap<>(at);
            m.put(local, met);
            return new Env(m);
        }

        /** x ∈ {0,+}, y ∈ {−}, locals sorted by name; ⊤ when nothing is constrained. */
        @Override
        public String toString() {
            if (at.isEmpty()) return "⊤";
            return at.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + " ∈ {" + EnumSet.copyOf(e.getValue()).stream()
                            .map(Sign::toString).collect(Collectors.joining(",")) + "}")
                    .collect(Collectors.joining(", "));
        }
    }

    SignState BOTTOM = new Bottom();
    SignState TOP = new Env(Map.of());
}
