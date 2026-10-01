package pag.domains.interval;

import pag.api.Domain;
import pag.api.Step;

/**
 * The reference interval domain (implementation_strategy.md Phase 3).
 *
 * <p>A skeleton for now, to exercise the build template: a state is either "any
 * program state" (true) or "none" (false). That is sound and proves nothing;
 * the interval logic replaces it.
 */
public final class IntervalDomain implements Domain<Boolean> {

    @Override public String name() { return "interval-ref"; }
    @Override public Boolean top() { return true; }
    @Override public Boolean bottom() { return false; }
    @Override public boolean isBottom(Boolean s) { return !s; }
    @Override public boolean entails(Boolean a, Boolean b) { return !a || b; }
    @Override public Boolean join(Boolean a, Boolean b) { return a || b; }
    @Override public Boolean widen(Boolean a, Boolean b) { return a || b; }

    /** Backward: no state after the step means no state before it; otherwise anything. */
    @Override
    public Boolean transfer(Step step, Boolean post) {
        return post;
    }
}
