import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Unreachable: x cannot be both positive and negative. Signs or intervals prove it. */
public class Sign1 {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            if (x.compareTo(BigInteger.ZERO) < 0) {
                reach(1);
            }
        }
    }
}
