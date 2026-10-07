import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Reachable with x = 5, which is above 3 and below 10. */
public class Range2 {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.valueOf(3)) > 0) {
            if (x.compareTo(BigInteger.TEN) < 0) {
                reach(1);
            }
        }
    }
}
