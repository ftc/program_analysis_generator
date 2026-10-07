import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** reach(1) needs x > 0 and x < 0 at once: unreachable, and provable from signs alone. */
public class SignRefute {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            if (x.compareTo(BigInteger.ZERO) < 0) {
                reach(1);
            }
        }
    }
}
