import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class WithBranch {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.signum() > 0) {                      // line 8: an if, not yet translated
            reach(1);
        }
    }
}
