import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/**
 * Unreachable: x > 0 makes y = x + 1 at least 2, so y < 0 never holds. The test on
 * x comes before the assignment, so working backward the analysis meets y = x + 1
 * knowing only y < 0: it must narrow back through the addition (x <= -2) to see the
 * contradiction. Intervals do; signs cannot narrow, so they cannot prove it.
 */
public class Arith1 {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            BigInteger y = x.add(BigInteger.ONE);
            if (y.compareTo(BigInteger.ZERO) < 0) {
                reach(1);
            }
        }
    }
}
