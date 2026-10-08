import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** reach(1) is unreachable: x > 0 makes y = x + 1 at least 2, so y < 0 never holds. */
public class Unreachable {
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
