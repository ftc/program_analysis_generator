import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** implementation_strategy.md §11's example probe, with the inner test flipped so reach(1) is reachable. */
public class AnalyzeAlarm {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            BigInteger y = x.add(BigInteger.ONE);
            if (y.compareTo(BigInteger.ZERO) > 0) {
                reach(1);
            }
        }
    }
}
