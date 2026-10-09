import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class Boundary {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            if (x.compareTo(BigInteger.TWO) < 0) {
                reach(1);
            }
        }
    }
}
