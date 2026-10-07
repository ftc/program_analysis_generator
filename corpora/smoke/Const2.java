import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Reachable with no input: x is 5, below 10. */
public class Const2 {
    public static void main(String[] args) {
        BigInteger x = BigInteger.valueOf(5);
        if (x.compareTo(BigInteger.TEN) < 0) {
            reach(1);
        }
    }
}
