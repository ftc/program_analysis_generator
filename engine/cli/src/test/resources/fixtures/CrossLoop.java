import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class CrossLoop {
    public static void main(String[] args) {
        BigInteger n = Rand.randInt();
        BigInteger i = BigInteger.ZERO;
        BigInteger acc = BigInteger.ZERO;
        reach(1);
        while (i.compareTo(n) < 0) {
            reach(2);
            BigInteger j = BigInteger.ZERO;
            while (j.compareTo(i) < 0) {
                acc = acc.add(j);
                j = j.add(BigInteger.ONE);
                reach(3);
            }
            i = i.add(BigInteger.ONE);
        }
        if (acc.compareTo(BigInteger.valueOf(10)) > 0) reach(4);
        reach(5);
    }
}
