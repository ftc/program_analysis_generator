package pag.api;

/**
 * An abstract domain for goal-directed backward reachability
 * (implementation_strategy.md §5.4, §6).
 *
 * <p><b>The analysis runs backward.</b> It starts at a target location and works
 * toward the method entry. A state {@code S} at a location over-approximates the
 * program states there that <i>may still reach the target</i> — not the states
 * reachable at that location. The target starts at {@link #top()}, every other
 * location at {@link #bottom()}. The target is proved unreachable when the state
 * at the method entry {@link #isBottom is bottom}.
 *
 * <p>Every value is a mathematical integer: nothing wraps or overflows.
 *
 * <p>Soundness is the only requirement. Precision — how many targets a domain
 * proves unreachable — is scored, not required. Any method may lose precision;
 * none may exclude a program state it cannot rule out.
 */
public interface Domain<S> {

    /** A name for reports. */
    String name();

    /** The state admitting every program state. Seeded at the target. */
    S top();

    /** The state admitting no program state. Every non-target location starts here. */
    S bottom();

    /**
     * Whether {@code s} admits no program state. This is the refutation test at the
     * method entry, so returning true for a state that admits some program state is
     * unsound.
     */
    boolean isBottom(S s);

    /**
     * Whether every program state {@code a} admits, {@code b} also admits. Returning
     * true when some state admitted by {@code a} is outside {@code b} is unsound.
     */
    boolean entails(S a, S b);

    /** A state admitting everything {@code a} or {@code b} admits. Used where control flow merges. */
    S join(S a, S b);

    /**
     * Like {@link #join}, at loop heads, so the fixed point can converge: the result
     * must admit everything {@code a} or {@code b} admits. Convergence is not
     * required; an iteration limit stops the search.
     */
    S widen(S a, S b);

    /**
     * Backward transfer across one step. {@code post} admits states <i>after</i> the
     * step; return a state admitting every state <i>before</i> the step from which
     * executing it can end in a state {@code post} admits.
     *
     * <p>Soundness: if executing {@code step} from state σ′ ends in σ, and
     * {@code post} admits σ, the result must admit σ′.
     *
     * <ul>
     *   <li>{@code Assign(x, e)}: {@code x} holds the value of {@code e} evaluated
     *       <i>before</i> the step, and nothing else changes. So {@code post}'s
     *       constraint on {@code x} becomes a constraint on {@code e}, and {@code x}
     *       itself is unconstrained before.
     *   <li>{@code Assume(c)}: nothing changes, but only states where {@code c} holds
     *       pass. The result is {@code post} restricted to {@code c}.
     *   <li>{@code Call(x, pag.probe.Rand.randInt(), [])}: {@code x} gets an arbitrary
     *       integer. The result is {@code post} with {@code x} unconstrained.
     * </ul>
     *
     * <p>This is not the forward transfer function: the argument is the state after
     * the step, and the result is the state before it.
     */
    S transfer(Step step, S post);
}
