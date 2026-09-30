import java.math.BigInteger;
import static pag.probe.Reach.reach;

public class Loop {
    public static void main(String[] args) {
        BigInteger i = BigInteger.ZERO;
        while (i.compareTo(BigInteger.TEN) < 0) {
            i = i.add(BigInteger.ONE);
        }
        reach(1);
    }
}
