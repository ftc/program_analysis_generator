import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** reach(1) is reached exactly when x = 9. mut-add-off-by-one wrongly refutes it. */
public class OffByOne {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        BigInteger y = x.add(BigInteger.ONE);
        if (y.compareTo(BigInteger.TEN) == 0) {
            reach(1);
        }
    }
}
