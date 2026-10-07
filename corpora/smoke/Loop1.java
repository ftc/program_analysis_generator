import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Unreachable: y starts at 0 and only grows, so it is never negative. Working backward, each trip round the loop raises the bound on x by one, without end: proving it needs widening, and a domain that does not converge stops at the iteration limit. */
public class Loop1 {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        BigInteger y = BigInteger.ZERO;
        while (x.compareTo(BigInteger.ZERO) > 0) {
            x = x.subtract(BigInteger.ONE);
            y = y.add(BigInteger.TWO);
        }
        if (y.compareTo(BigInteger.ZERO) < 0) {
            reach(1);
        }
    }
}
