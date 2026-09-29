import pag.api.Domain;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.Step;

/**
 * A domain written the way a generated one is, compiled by SeparateCompilationTest
 * against pag.api alone. States are booleans: true admits everything, false
 * nothing. Trivially sound, and it touches every type in the domain vocabulary.
 */
public final class StubDomain implements Domain<Boolean> {

    public String name() { return "stub"; }
    public Boolean top() { return true; }
    public Boolean bottom() { return false; }
    public boolean isBottom(Boolean s) { return !s; }
    public boolean entails(Boolean a, Boolean b) { return !a || b; }
    public Boolean join(Boolean a, Boolean b) { return a || b; }
    public Boolean widen(Boolean a, Boolean b) { return a || b; }

    public Boolean transfer(Step step, Boolean post) {
        return switch (step) {
            case Step.Assign a -> post && describe(a.source()) != null;
            case Step.Assume a -> post && describe(a.cond()) != null;
            case Step.Call c -> post && c.callee().qualifiedName() != null;
        };
    }

    private static String describe(RVal v) {
        return switch (v) {
            case RVal.IntConst c -> c.v().toString();
            case RVal.Binop b -> describe(b.l()) + " " + b.op() + " " + describe(b.r());
            case LVal.Local l -> l.name();
            case LVal.StaticField f -> f.declaringClass() + "." + f.name();
        };
    }
}
