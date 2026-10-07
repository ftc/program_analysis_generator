import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Unreachable: x is 5, never above 10. Constants or intervals prove it; signs cannot. */
public class Const1 {
    public static void main(String[] args) {
        BigInteger x = BigInteger.valueOf(5);
        if (x.compareTo(BigInteger.TEN) > 0) {
            reach(1);
        }
    }
}
