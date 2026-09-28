package pag.probe;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The input list behind {@link Rand#randInt()} (implementation_strategy.md §5.6).
 *
 * <p>Public so the Stage 1 interpreter reads inputs with this same parser: the
 * two stages must answer every {@code randInt} call identically. Probes cannot
 * call it; the language profile admits only {@code randInt} and {@code reach}.
 */
public final class Inputs {

    /** The system property the JVM reads inputs from: {@code -Dpag.inputs=10,-3}. */
    public static final String PROPERTY = "pag.inputs";

    private final List<BigInteger> values;
    private int next = 0;

    private Inputs(List<BigInteger> values) {
        this.values = values;
    }

    /**
     * Parses a comma-separated list of integers of any size. Whitespace around
     * each value is ignored; {@code null} or a blank string means no inputs.
     *
     * @throws IllegalArgumentException naming the first token that is not an integer
     */
    public static Inputs parse(String spec) {
        List<BigInteger> values = new ArrayList<>();
        if (spec != null && !spec.isBlank()) {
            for (String token : spec.split(",", -1)) {
                String value = token.strip();
                try {
                    values.add(new BigInteger(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            PROPERTY + ": not an integer: \"" + value + "\"", e);
                }
            }
        }
        return new Inputs(List.copyOf(values));
    }

    /** Every value, in order, including any already returned. */
    public List<BigInteger> values() {
        return values;
    }

    /**
     * The next value.
     *
     * @throws IllegalStateException when every value has been returned, so a run
     *     never continues on a value nobody chose
     */
    public BigInteger next() {
        if (next >= values.size()) {
            throw new IllegalStateException(
                    PROPERTY + ": inputs exhausted after " + values.size() + " value(s)");
        }
        return values.get(next++);
    }
}
