import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Reachable with x = 2: the loop runs twice, so y = 4 > 3. Catches loop handling that drops states. */
public class Loop2 {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        BigInteger y = BigInteger.ZERO;
        while (x.compareTo(BigInteger.ZERO) > 0) {
            x = x.subtract(BigInteger.ONE);
            y = y.add(BigInteger.TWO);
        }
        if (y.compareTo(BigInteger.valueOf(3)) > 0) {
            reach(1);
        }
    }
}
