import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Unreachable: x cannot be above 3 and below 2. Intervals prove it; signs cannot, both are positive. */
public class Range1 {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.valueOf(3)) > 0) {
            if (x.compareTo(BigInteger.TWO) < 0) {
                reach(1);
            }
        }
    }
}
