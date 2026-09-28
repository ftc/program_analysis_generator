package pag.probe;

import java.math.BigInteger;

/**
 * A probe's only source of input (implementation_strategy.md §5.6).
 *
 * <p>Despite the name nothing is random: the adversary chooses every value and
 * passes them as {@code -Dpag.inputs=10,-3}. The name says what the analysis may
 * assume about the value, which is nothing.
 */
public final class Rand {

    private static Inputs inputs;

    private Rand() {}

    /**
     * The next input.
     *
     * @throws IllegalArgumentException if {@code pag.inputs} is malformed, on the first call
     * @throws IllegalStateException when the inputs are used up
     */
    public static synchronized BigInteger randInt() {
        if (inputs == null) {
            inputs = Inputs.parse(System.getProperty(Inputs.PROPERTY));
        }
        return inputs.next();
    }
}
