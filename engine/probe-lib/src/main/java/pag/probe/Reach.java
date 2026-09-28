package pag.probe;

/**
 * Marks a location a probe asks about (implementation_strategy.md §5.8).
 *
 * <p>{@code reach(7)} prints {@code REACHED-7} and has no other effect. The
 * analysis finds the call and targets its location; the run reports it on
 * stdout. The line is on its way to the OS before {@code reach} returns, because
 * the JDK creates {@code System.out} with {@code autoFlush} on and
 * {@code println} flushes when it is on. So the marker survives an exception or
 * a kill immediately after. Keep this a single {@code System.out.println}.
 */
public final class Reach {

    private Reach() {}

    public static void reach(int id) {
        System.out.println("REACHED-" + id);
    }
}
