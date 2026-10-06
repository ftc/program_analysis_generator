import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** reach(1) is reachable: any input x > 0 gets there (x = 5, say). */
public class Reachable {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            BigInteger y = x.add(BigInteger.ONE);
            if (y.compareTo(BigInteger.ONE) > 0) {
                reach(1);
            }
        }
    }
}
