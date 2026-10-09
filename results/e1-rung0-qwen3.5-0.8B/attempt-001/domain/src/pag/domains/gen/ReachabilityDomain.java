package pag.domains.gen;

import pag.api.Domain;
import pag.api.RVal;
import pag.api.Step;

public class ReachabilityDomain extends Domain<Integer> {

    private final int top = 0;
    private final int bottom = Integer.MIN_VALUE;

    @Override
    public String name() {
        return "ReachabilityDomain";
    }

    @Override
    public S top() {
        return top;
    }

    @Override
    public S bottom() {
        return bottom;
    }

    @Override
    public boolean isBottom(S s) {
        return s == Integer.MIN_VALUE;
    }

    @Override
    public boolean entails(S a, S b) {
        return a == b;
    }

    @Override
    public S join(S a, S b) {
        return a;
    }

    @Override
    public S widen(S a, S b) {
        return a;
    }

    @Override
    public S transfer(Step step, S post) {
        return post;
    }
}
