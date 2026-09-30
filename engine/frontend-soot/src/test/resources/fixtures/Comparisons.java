import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class Comparisons {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) < 0) reach(1);
        if (x.compareTo(BigInteger.ZERO) <= 0) reach(2);
        if (x.compareTo(BigInteger.ZERO) > 0) reach(3);
        if (x.compareTo(BigInteger.ZERO) >= 0) reach(4);
        if (x.compareTo(BigInteger.ZERO) == 0) reach(5);
        if (x.compareTo(BigInteger.ZERO) != 0) reach(6);
    }
}
