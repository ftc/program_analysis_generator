import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class CrossArith {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        BigInteger y = Rand.randInt();
        reach(1);
        BigInteger s = x.add(y);
        if (s.compareTo(BigInteger.ZERO) > 0) reach(2);
        BigInteger d = x.subtract(y.multiply(BigInteger.TWO));
        if (d.compareTo(BigInteger.valueOf(-7)) < 0) reach(3);
        BigInteger p = x.multiply(y).add(BigInteger.ONE);
        if (p.compareTo(BigInteger.TEN) >= 0) reach(4);
        BigInteger n = s.negate();
        if (n.equals(d)) reach(5);
        if (!n.equals(BigInteger.ZERO)) reach(6);
        reach(7);
    }
}
