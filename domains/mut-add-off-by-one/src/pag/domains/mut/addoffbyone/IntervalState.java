package pag.domains.mut.addoffbyone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An abstract state: Bottom (no program state), or an interval for each
 * constrained local. A local absent from the map is unconstrained, so an empty
 * Env is top.
 */
public sealed interface IntervalState {

    record Bottom() implements IntervalState {
        @Override public String toString() { return "⊥"; }
    }

    record Env(Map<String, Interval> at) implements IntervalState {
        /** Unconstrained locals are dropped, so equal meanings are equal values. */
        public Env {
            Map<String, Interval> kept = new HashMap<>(at);
            kept.values().removeIf(Interval.TOP::equals);
            at = Map.copyOf(kept);
        }

        public Interval get(String local) { return at.getOrDefault(local, Interval.TOP); }

        /** x ↦ (-∞,-2], y ↦ [0,5], locals sorted by name; ⊤ when nothing is constrained. */
        @Override
        public String toString() {
            if (at.isEmpty()) return "⊤";
            return at.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + " ↦ " + e.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        public Env without(String local) {
            Map<String, Interval> m = new HashMap<>(at);
            m.remove(local);
            return new Env(m);
        }

        /** This state with local's interval narrowed to i; Bottom if they do not overlap. */
        public IntervalState meet(String local, Interval i) {
            Optional<Interval> met = get(local).meet(i);
            if (met.isEmpty()) return BOTTOM;
            Map<String, Interval> m = new HashMap<>(at);
            m.put(local, met.get());
            return new Env(m);
        }
    }

    IntervalState BOTTOM = new Bottom();
    IntervalState TOP = new Env(Map.of());
}
