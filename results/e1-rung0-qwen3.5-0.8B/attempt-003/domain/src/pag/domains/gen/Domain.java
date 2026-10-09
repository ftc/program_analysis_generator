package pag.domains.gen;

import pag.api.BinOp;
import pag.api.LVal;
import pag.api.MethodId;
import pag.api.RVal;
import pag.api.Step;

/**
 * Abstract domain for backward reachability analysis.
 * Tracks program variables as mathematical integers.
 *
 * <p>Top state is the target; bottom state is the entry point.
 * Transfer is backward: post admits states before step, result is post.
 */
public class Domain extends RVal {

    public Domain() {
        super(RVal.class);
    }

    public String name() {
        return "Domain";
    }

    public S top() {
        return new RVal.IntConst(1L);
    }

    public S bottom() {
        return new RVal.IntConst(0L);
    }

    @Override
    public boolean isBottom(S s) {
        return s == top();
    }

    @Override
    public boolean entails(S a, S b) {
        return s == top() && s == bottom();
    }

    @Override
    public S join(S a, S b) {
        return new RVal.IntConst(1L);
    }

    @Override
    public S widen(S a, S b) {
        return new RVal.IntConst(2L);
    }

    @Override
    public S transfer(Step step, S post) {
        return new RVal.IntConst(1L);
    }
}
